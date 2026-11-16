package org.quarkpress.admin.runtime;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.quarkpress.admin.runtime.AdminPaths.Target;

/**
 * Answers the admin screens' requests under every site: the shell page and the editor page, WordPress's files, the
 * vendored sign-in client and the shell's own files, each served on its own ({@code GET} and {@code HEAD}). A file
 * request whose query has a {@code ver} parameter may be cached for a year and never revalidated, since a new release
 * changes the parameter; any other answer, the pages included, must be revalidated. No entity tag is sent. A path the
 * admin screens do not answer goes on to the application's other routes.
 */
@Singleton
public class AdminRoute {

    static final String NOSNIFF = "nosniff";

    static final String CACHE_VERSIONED = "public, max-age=31536000, immutable";

    static final String CACHE_UNVERSIONED = "no-cache";

    static final String HTML = "text/html; charset=utf-8";

    static final String FRAME_OPTIONS = "SAMEORIGIN";

    static final String FRAME_ANCESTORS = "frame-ancestors 'self'";

    static final String REFERRER_POLICY = "strict-origin-when-cross-origin";

    @Inject
    AdminConfig config;

    @Inject
    AssetFiles files;

    @Inject
    AdminPages pages;

    /** The bytes of each file served so far, by its kind and path; the shipped set is about ten megabytes. */
    private final ConcurrentMap<String, byte[]> cache = new ConcurrentHashMap<>();

    void register(@Observes Router router) {
        AdminPaths paths = AdminPaths.of(config.path());
        router.route().method(HttpMethod.GET).method(HttpMethod.HEAD).handler(context -> handle(context, paths));
    }

    private void handle(RoutingContext context, AdminPaths paths) {
        Optional<Target> target = paths.classify(context.request().path());
        if (target.isEmpty()) {
            context.next();
            return;
        }
        String path = target.get().path();
        // read from the raw query, which is never decoded: a malformed escape elsewhere in it changes nothing
        String cacheControl = hasVer(context.request().query()) ? CACHE_VERSIONED : CACHE_UNVERSIONED;
        switch (target.get().kind()) {
            case SHELL_PAGE -> page(context, false);
            case EDITOR_PAGE -> page(context, true);
            case WP_FILE -> file(context, "wp:" + path, path, cacheControl, files.wordpress(path));
            case VENDOR_FILE -> file(context, "vendor:" + path, path, cacheControl, files.vendor(path));
            case SHELL_FILE -> file(context, "shell:" + path, path, cacheControl, files.shell(path));
            default -> context.response().setStatusCode(501).end();
        }
    }

    /**
     * One of the two pages. They are rendered when the application starts; a request that comes earlier renders them
     * off the event loop, since rendering reads the registry and a stylesheet.
     */
    private void page(RoutingContext context, boolean editor) {
        if (pages.rendered()) {
            sendPage(context, editor ? pages.editor() : pages.shell());
            return;
        }
        context.vertx()
                .executeBlocking(() -> editor ? pages.editor() : pages.shell(), false)
                .onComplete(rendered -> {
                    try {
                        if (rendered.failed()) {
                            context.fail(rendered.cause());
                        } else {
                            sendPage(context, rendered.result());
                        }
                    } catch (RuntimeException failure) {
                        context.fail(failure);
                    }
                });
    }

    /** A page may be framed only by its own site, and sends only its origin to other sites, as WordPress's admin. */
    private static void sendPage(RoutingContext context, String page) {
        HttpServerResponse response = context.response();
        response.putHeader("X-Frame-Options", FRAME_OPTIONS);
        response.putHeader("Content-Security-Policy", FRAME_ANCESTORS);
        response.putHeader("Referrer-Policy", REFERRER_POLICY);
        respond(context, HTML, CACHE_UNVERSIONED, page.getBytes(StandardCharsets.UTF_8));
    }

    private void file(RoutingContext context, String key, String path, String cacheControl, Optional<URL> resource) {
        if (resource.isEmpty()) {
            context.response().setStatusCode(404).end();
            return;
        }
        byte[] cached = cache.get(key);
        if (cached != null) {
            send(context, path, cacheControl, cached);
            return;
        }
        context.vertx()
                .executeBlocking(() -> cache.computeIfAbsent(key, ignored -> read(resource.get())), false)
                .onComplete(read -> {
                    // whatever happens here, the response is ended or the request failed: never left open
                    try {
                        if (read.failed()) {
                            context.fail(read.cause());
                        } else {
                            send(context, path, cacheControl, read.result());
                        }
                    } catch (RuntimeException failure) {
                        context.fail(failure);
                    }
                });
    }

    private static void send(RoutingContext context, String path, String cacheControl, byte[] bytes) {
        respond(context, AssetFiles.contentType(path), cacheControl, bytes);
    }

    private static void respond(RoutingContext context, String contentType, String cacheControl, byte[] bytes) {
        HttpServerResponse response = context.response();
        response.putHeader(HttpHeaders.CONTENT_TYPE, contentType);
        response.putHeader("X-Content-Type-Options", NOSNIFF);
        response.putHeader(HttpHeaders.CACHE_CONTROL, cacheControl);
        response.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes.length));
        if (context.request().method() == HttpMethod.HEAD) {
            response.end();
        } else {
            response.end(Buffer.buffer(bytes));
        }
    }

    /**
     * Whether a raw query string has a parameter whose name is exactly {@code ver} (case-sensitive, not decoded): at
     * the start or after an {@code &}, followed by {@code =}, {@code &} or the end.
     */
    static boolean hasVer(String query) {
        if (query == null) {
            return false;
        }
        int start = 0;
        while (start <= query.length()) {
            int end = query.indexOf('&', start);
            if (end < 0) {
                end = query.length();
            }
            int equals = query.indexOf('=', start);
            int nameEnd = equals >= 0 && equals < end ? equals : end;
            if (nameEnd - start == 3 && query.startsWith("ver", start)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    private static byte[] read(URL resource) {
        try (InputStream in = resource.openStream()) {
            return in.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
