package org.quarkpress.admin.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.admin.runtime.AssetRegistry.Asset;
import org.quarkpress.admin.runtime.AssetRegistry.Inline;
import org.quarkpress.admin.runtime.AssetRegistry.Position;

/**
 * Writes the admin screens' two HTML pages: the shell page (the lists, sign-in and every screen but the editor) and the
 * editor page, which adds WordPress's stylesheets in the head and, after the markup, WordPress's scripts in the order
 * WordPress prints them: the head group, the import map, the editor's script module, the footer group (each script
 * with its inline scripts around it, an alias printing only those), then the shell's own editor module. Both pages
 * load the vendored sign-in client with a classic script right before the shell's module, which reads its global.
 * Neither page depends on a post or a user; WordPress's files are named relative to the page, through the prefix that
 * climbs from the admin path to the site's root, and the shell's own files relative to the admin path.
 */
public final class PageRenderer {

    /** The stylesheet WordPress prints inline on admin pages, as the text of a style element. */
    static final String VIEW_TRANSITIONS_FILE = "wp-admin/css/view-transitions.min.css";

    /**
     * The token an inline script uses for the absolute address of TinyMCE's folder, which WordPress prints from the
     * site's URL; a static page computes it in the browser instead.
     */
    static final String TINYMCE_BASE = "%TINYMCE_BASE%";

    private static final String SPDX = "<!-- SPDX-License-Identifier: GPL-2.0-or-later -->";

    /** The vendored sign-in client, whose classic script defines the global {@code oidc} the shell's modules use. */
    private static final String SIGN_IN_CLIENT =
            "<script src=\"quarkpress-admin/vendor/oidc-client-ts.js\"></script>\n";

    /**
     * The styles WordPress hands the editor's canvas iframe: the block editor's canvas sheet with what it depends on,
     * and the classic theme's sheet.
     */
    static final List<String> IFRAME_STYLES = List.of("wp-edit-blocks", "classic-theme-styles");

    /** The one script WordPress hands the editor's canvas iframe, which the editor page itself does not print. */
    static final List<String> IFRAME_SCRIPTS = List.of("wp-polyfill");

    private static final String EDITOR_BODY_CLASS =
            "wp-admin wp-core-ui js is-fullscreen-mode block-editor-page admin-color-modern locale-en-us";

    private final AssetRegistry registry;

    private final String adminPath;

    private final Optional<String> viewTransitionsCss;

    /**
     * @param registry WordPress's handles
     * @param adminPath the configured admin path, such as {@code /wp-admin/}, which the shell reads from the page
     * @param viewTransitionsCss the text of {@value #VIEW_TRANSITIONS_FILE} when the jar ships it
     */
    public PageRenderer(AssetRegistry registry, String adminPath, Optional<String> viewTransitionsCss) {
        this.registry = registry;
        this.adminPath = adminPath;
        this.viewTransitionsCss = viewTransitionsCss;
    }

    /**
     * The shell page, with WordPress's view-transitions opt-in as the editor page has it. It names no WordPress file,
     * so the prefix is not used; it is taken for symmetry with
     * {@link #editorPage(String)}.
     */
    public String shellPage(String relativePrefix) {
        StringBuilder html = new StringBuilder(1024);
        head(html);
        viewTransitions(html);
        html.append("</head>\n");
        html.append("<body class=\"qp-admin\">\n");
        container(html, false);
        html.append(SIGN_IN_CLIENT);
        html.append("<script type=\"module\" src=\"quarkpress-admin/app.js\"></script>\n");
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    /** The editor page, whose WordPress files are named through {@code relativePrefix}, such as {@code ../}. */
    public String editorPage(String relativePrefix) {
        StringBuilder html = new StringBuilder(64 * 1024);
        head(html);
        for (Asset style : AssetOrder.resolve(registry.stylesByHandle(), registry.styleQueue())) {
            if (style.file().isPresent()) {
                html.append("<link rel='stylesheet' id='")
                        .append(attribute(style.handle()))
                        .append("-css' href='")
                        .append(attribute(url(relativePrefix, style)))
                        .append("' media='all' />\n");
            }
        }
        viewTransitions(html);
        html.append("</head>\n");
        html.append("<body class=\"").append(EDITOR_BODY_CLASS).append("\">\n");
        container(html, true);

        AssetOrder.Grouped scripts =
                AssetOrder.groups(AssetOrder.resolve(registry.scriptsByHandle(), registry.scriptQueue()));
        for (Asset script : scripts.head()) {
            script(html, relativePrefix, script);
        }
        html.append("<script id=\"wp-importmap\" type=\"importmap\">\n")
                .append(importMap(relativePrefix))
                .append("\n</script>\n");
        Asset module = registry.module();
        html.append("<script type=\"module\" id=\"")
                .append(attribute(module.handle()))
                .append("-js-module\" src=\"")
                .append(attribute(url(relativePrefix, module)))
                .append("\"></script>\n");
        for (Asset script : scripts.footer()) {
            script(html, relativePrefix, script);
        }
        html.append(SIGN_IN_CLIENT);
        html.append("<script type=\"module\" src=\"quarkpress-admin/editor.js\"></script>\n");
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    /**
     * WordPress's view-transitions opt-in, which it prints on every admin screen: both pages carry it, so a navigation
     * from one to the other keeps its cross-document transition.
     */
    private void viewTransitions(StringBuilder html) {
        viewTransitionsCss.ifPresent(css -> {
            String text = css.strip();
            if (text.contains("</")) {
                throw new IllegalStateException("QuarkPress admin: " + VIEW_TRANSITIONS_FILE
                        + " would close the style element it is printed in");
            }
            html.append("<style id=\"wp-view-transitions-admin-inline-css\">\n")
                    .append(text)
                    .append("\n/*# sourceURL=wp-view-transitions-admin-inline-css */\n</style>\n");
        });
    }

    private static void head(StringBuilder html) {
        html.append("<!DOCTYPE html>\n")
                .append(SPDX)
                .append('\n')
                .append("<html lang=\"en-US\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width\">\n")
                .append("<title>QuarkPress</title>\n")
                .append("<link rel=\"stylesheet\" href=\"quarkpress-admin/shell.css\">\n");
    }

    private void container(StringBuilder html, boolean editor) {
        html.append("<div id=\"quarkpress-admin\" data-admin-path=\"")
                .append(attribute(adminPath))
                .append("\">\n")
                .append("<header></header>\n")
                .append("<main id=\"qp-main\" tabindex=\"-1\"></main>\n")
                .append("<div id=\"qp-notices\" role=\"status\" aria-live=\"polite\"></div>\n");
        if (editor) {
            html.append("<div class=\"block-editor\">")
                    .append("<h1 class=\"screen-reader-text hide-if-no-js\" id=\"qp-editor-title\"></h1>")
                    .append("<div id=\"editor\" class=\"block-editor__container hide-if-no-js\" data-iframe-styles=\"")
                    .append(attribute(iframeAssets(registry.stylesByHandle(), IFRAME_STYLES)))
                    .append("\" data-iframe-scripts=\"")
                    .append(attribute(iframeAssets(registry.scriptsByHandle(), IFRAME_SCRIPTS)))
                    .append("\"></div>")
                    .append("<noscript><div class=\"wrap block-editor-no-js\"><p>The block editor requires"
                            + " JavaScript. Please enable JavaScript in your browser settings.</p></div></noscript>")
                    .append("</div>\n");
        }
        html.append("</div>\n");
    }

    /**
     * The canvas iframe's styles or scripts in resolution order, as JSON: each handle with its file below the site's
     * root and its version, from which the shell writes the iframe's tags with the site's absolute address.
     */
    private static String iframeAssets(Map<String, Asset> handles, List<String> queue) {
        ObjectMapper json = new ObjectMapper();
        ArrayNode assets = json.createArrayNode();
        for (Asset asset : AssetOrder.resolve(handles, queue)) {
            if (asset.file().isPresent()) {
                assets.addObject()
                        .put("handle", asset.handle())
                        .put("file", asset.file().get())
                        .put("ver", asset.ver());
            }
        }
        try {
            return json.writeValueAsString(assets);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void script(StringBuilder html, String relativePrefix, Asset script) {
        inline(html, relativePrefix, script.handle(), Position.BEFORE);
        script.file()
                .ifPresent(file -> html.append("<script src=\"")
                        .append(attribute(url(relativePrefix, script)))
                        .append("\" id=\"")
                        .append(attribute(script.handle()))
                        .append("-js\"></script>\n"));
        inline(html, relativePrefix, script.handle(), Position.AFTER);
    }

    /** A handle's inline scripts at one position, joined in one tag as WordPress joins them. */
    private void inline(StringBuilder html, String relativePrefix, String handle, Position position) {
        StringBuilder text = new StringBuilder();
        for (Inline inline : registry.inlines()) {
            if (inline.handle().equals(handle) && inline.position() == position) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(inline.script());
            }
        }
        if (text.isEmpty()) {
            return;
        }
        String id = handle + (position == Position.BEFORE ? "-js-before" : "-js-after");
        String tinymceBase = "new URL(\"" + relativePrefix + "wp-includes/js/tinymce\", document.baseURI).href";
        html.append("<script id=\"")
                .append(attribute(id))
                .append("\">\n")
                .append(text.toString().replace(TINYMCE_BASE, tinymceBase))
                .append("\n//# sourceURL=")
                .append(id)
                .append("\n</script>\n");
    }

    private String importMap(String relativePrefix) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode imports = json.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> entries =
                        registry.importMap().get("imports").fields();
                entries.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = entries.next();
            imports.put(entry.getKey(), relativePrefix + entry.getValue().asText());
        }
        ObjectNode map = json.createObjectNode();
        map.set("imports", imports);
        try {
            // a script element ends at the first "</", so none may appear in its text
            return json.writeValueAsString(map).replace("</", "<\\/");
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String url(String relativePrefix, Asset asset) {
        return relativePrefix + asset.file().orElseThrow() + "?ver=" + asset.ver();
    }

    /** Text for a quoted attribute value. */
    private static String attribute(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
