package org.quarkword.admin.runtime;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The admin screens' two pages, each rendered once, when the application starts, for the configured admin path: they
 * depend on no site, post or user, so every site and request gets the same text.
 */
@ApplicationScoped
public class AdminPages {

    @Inject
    AdminConfig config;

    @Inject
    AssetRegistry registry;

    @Inject
    AssetFiles files;

    private volatile Pages pages;

    private record Pages(String shell, String editor) {}

    /** Renders both pages when the application starts, so that a broken registry stops the start. */
    void start(@Observes StartupEvent event) {
        pages();
    }

    /** The shell page: sign-in, the lists and every screen but the editor. */
    public String shell() {
        return pages().shell();
    }

    /** The editor page. */
    public String editor() {
        return pages().editor();
    }

    /** Whether both pages are rendered, so that asking for one reads nothing. */
    boolean rendered() {
        return pages != null;
    }

    private Pages pages() {
        Pages rendered = pages;
        if (rendered == null) {
            String prefix = AdminPaths.of(config.path()).relativePrefix();
            PageRenderer renderer = new PageRenderer(registry, config.path(), viewTransitions());
            rendered = new Pages(renderer.shellPage(prefix), renderer.editorPage(prefix));
            pages = rendered;
        }
        return rendered;
    }

    private Optional<String> viewTransitions() {
        Optional<URL> resource = files.wordpress(PageRenderer.VIEW_TRANSITIONS_FILE);
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = resource.get().openStream()) {
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
