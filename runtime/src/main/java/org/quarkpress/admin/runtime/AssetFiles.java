package org.quarkpress.admin.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The files the jar carries under {@code org/quarkpress/admin/}: WordPress's files under {@code wp/} (those
 * {@code wordpress-files.txt} lists), the vendored sign-in client under {@code vendor/} (those
 * {@code oidc-client-ts-files.txt} lists), and the shell's own files under {@code shell/} (those
 * {@code shell/shell-files.txt} lists). A file is found only by a path its list names, so no request reaches another
 * resource, nor a folder, which the class loader would also find.
 */
@ApplicationScoped
public class AssetFiles {

    static final String ROOT = "org/quarkpress/admin/";

    private static final String OCTET_STREAM = "application/octet-stream";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("eot", "application/vnd.ms-fontobject"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            // the vendored client's licence, oidc-client-ts.LICENSE: shown by a browser, not downloaded
            Map.entry("license", "text/plain; charset=utf-8"));

    private volatile Lists lists;

    /** The two lists' targets, read once. */
    private record Lists(Set<String> wordpress, Set<String> vendor, Set<String> shell) {}

    /**
     * The targets of {@code wordpress-files.txt}: paths below {@code wp/}, such as
     * {@code wp-includes/css/buttons.min.css}.
     */
    public Set<String> wordpressPaths() {
        return lists().wordpress();
    }

    /** The targets of {@code oidc-client-ts-files.txt}, such as {@code vendor/oidc-client-ts.js}. */
    public Set<String> vendorPaths() {
        return lists().vendor();
    }

    /** The paths of {@code shell/shell-files.txt}: the shell's own files below {@code shell/}, such as {@code app.js}. */
    public Set<String> shellPaths() {
        return lists().shell();
    }

    /** A WordPress file the list names. */
    public Optional<URL> wordpress(String path) {
        return wordpressPaths().contains(path) ? resource("wp/" + path) : Optional.empty();
    }

    /** A vendored file the list names, by its target path. */
    public Optional<URL> vendor(String path) {
        return vendorPaths().contains(path) ? resource(path) : Optional.empty();
    }

    /** One of the shell's own files the list names, such as {@code screens/lists.js}. */
    public Optional<URL> shell(String path) {
        return shellPaths().contains(path) ? resource("shell/" + path) : Optional.empty();
    }

    /** The content type of a file, by its extension. */
    public static String contentType(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash + 1 || dot == path.length() - 1) {
            return OCTET_STREAM;
        }
        String type = TYPES.get(lowerAscii(path.substring(dot + 1)));
        return type != null ? type : OCTET_STREAM;
    }

    private Lists lists() {
        Lists loaded = lists;
        if (loaded == null) {
            loaded = new Lists(
                    targets("wordpress-files.txt"),
                    targets("oidc-client-ts-files.txt"),
                    shellTargets(targets("shell/shell-files.txt")));
            lists = loaded;
        }
        return loaded;
    }

    /** The target of each line of a list: the line, or what follows its {@code ->}. */
    private static Set<String> targets(String list) {
        String text;
        try (InputStream in = loader().getResourceAsStream(ROOT + list)) {
            if (in == null) {
                throw new IllegalStateException("QuarkPress admin: the jar has no " + ROOT + list);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        Set<String> targets = new LinkedHashSet<>();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            String line = text.substring(start, end).strip();
            start = end + 1;
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int arrow = line.indexOf("->");
            targets.add(arrow < 0 ? line : line.substring(arrow + 2).strip());
        }
        return Collections.unmodifiableSet(targets);
    }

    /**
     * The shell's list, checked: each path is relative, of plain segments, with the extension of a served type; a list
     * that names anything else stops the start.
     */
    static Set<String> shellTargets(Set<String> paths) {
        for (String path : paths) {
            if (!plainPath(path) || contentType(path).equals(OCTET_STREAM)) {
                throw new IllegalStateException("QuarkPress admin: shell/shell-files.txt names " + path
                        + ", which is no file the shell serves");
            }
        }
        return paths;
    }

    /** A relative path of non-empty segments, none starting with a dot, no backslash. */
    private static boolean plainPath(String path) {
        if (path.isEmpty() || path.indexOf('\\') >= 0) {
            return false;
        }
        int start = 0;
        while (start <= path.length()) {
            int end = path.indexOf('/', start);
            if (end < 0) {
                end = path.length();
            }
            if (end == start || path.charAt(start) == '.') {
                return false;
            }
            start = end + 1;
        }
        return true;
    }

    private static Optional<URL> resource(String path) {
        return Optional.ofNullable(loader().getResource(ROOT + path));
    }

    private static ClassLoader loader() {
        return AssetFiles.class.getClassLoader();
    }

    private static String lowerAscii(String text) {
        StringBuilder lower = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return lower.toString();
    }
}
