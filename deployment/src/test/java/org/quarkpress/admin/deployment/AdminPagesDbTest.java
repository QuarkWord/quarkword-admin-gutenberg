package org.quarkpress.admin.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.admin.runtime.AssetOrder;
import org.quarkpress.admin.runtime.AssetRegistry;

/** The shell page and the editor page, rendered from WordPress's handle registry in WordPress's order. */
class AdminPagesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = AdminApp.app(AdminPagesDbTest.class);

    @Test
    void theEditorPagePrintsTagsInResolutionOrder() {
        Response response = given().get("/wp-admin/post.php?post=1&action=edit");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("text/html; charset=utf-8");
        assertThat(response.header("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.header("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(response.header("Content-Security-Policy")).isEqualTo("frame-ancestors 'self'");
        assertThat(response.header("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        String page = response.asString();

        assertThat(page).startsWith("<!DOCTYPE html>");
        assertThat(page)
                .contains("<body class=\"wp-admin wp-core-ui js is-fullscreen-mode block-editor-page"
                        + " admin-color-modern locale-en-us\">");
        assertThat(page).contains("<div id=\"quarkpress-admin\" data-admin-path=\"/wp-admin/\">");
        assertThat(page).contains("<div id=\"editor\" class=\"block-editor__container hide-if-no-js\"></div>");

        List<String> scripts = ids(page, "-js\"");
        AssetRegistry registry = AssetRegistry.load();
        AssetOrder.Grouped grouped =
                AssetOrder.groups(AssetOrder.resolve(registry.scriptsByHandle(), registry.scriptQueue()));
        List<String> expected = new ArrayList<>(tags(grouped.head(), "-js"));
        expected.addAll(tags(grouped.footer(), "-js"));
        assertThat(scripts).isEqualTo(expected);
        assertThat(scripts.subList(0, 5))
                .containsExactly("jquery-core-js", "jquery-migrate-js", "utils-js", "moxiejs-js", "plupload-js");
        assertThat(scripts.subList(scripts.size() - 3, scripts.size()))
                .containsExactly("wp-core-commands-js", "wp-tinymce-root-js", "wp-tinymce-js");

        // the module after the import map, before the first footer script; the shell's own module last
        int importMap = page.indexOf("<script id=\"wp-importmap\" type=\"importmap\">");
        int module = page.indexOf("id=\"@wordpress/latex-to-mathml/loader-js-module\"");
        int firstFooter = page.indexOf("id=\"wp-dom-ready-js\"");
        assertThat(importMap).isPositive().isGreaterThan(page.indexOf("id=\"plupload-js\""));
        assertThat(module).isGreaterThan(importMap).isLessThan(firstFooter);
        assertThat(page)
                .contains("\"@wordpress/latex-to-mathml\":\"../wp-includes/js/dist/script-modules/latex-to-mathml/"
                        + "index.min.js?ver=662cb0e7f68d2a21cd68\"");
        int tinymce = page.indexOf("id=\"wp-tinymce-js\"");
        int shell = page.indexOf("<script type=\"module\" src=\"quarkpress-admin/editor.js\"></script>");
        assertThat(shell).isGreaterThan(tinymce);
        assertThat(page.indexOf("<script", shell + 1)).isEqualTo(-1);
        // the sign-in client's classic script defines its global before the shell's module runs
        assertThat(page.indexOf("<script src=\"quarkpress-admin/vendor/oidc-client-ts.js\"></script>"))
                .isGreaterThan(tinymce)
                .isLessThan(shell);

        assertThat(page)
                .contains("<script src=\"../wp-includes/js/dist/hooks.min.js?ver=")
                .contains("<script src=\"../wp-admin/js/editor.min.js?ver=7.1.3\" id=\"editor-js\"></script>");
        for (String src : attributes(page, " src=\"")) {
            if (!src.startsWith("quarkpress-admin/")) {
                assertThat(src).startsWith("../wp-");
                assertThat(src).contains("?ver=");
            }
        }

        List<String> styles = ids(page, "-css'");
        assertThat(styles)
                .isEqualTo(tags(AssetOrder.resolve(registry.stylesByHandle(), registry.styleQueue()), "-css"));
        assertThat(styles.get(0)).isEqualTo("dashicons-css");
        assertThat(page)
                .contains("<link rel='stylesheet' id='wp-edit-post-css'"
                        + " href='../wp-includes/css/dist/edit-post/style.min.css?ver=7.1.3' media='all' />")
                .contains("<link rel=\"stylesheet\" href=\"quarkpress-admin/shell.css\">")
                .contains("<style id=\"wp-view-transitions-admin-inline-css\">");
        assertThat(page.indexOf("wp-view-transitions-admin-inline-css"))
                .isGreaterThan(page.indexOf("id='classic-theme-styles-css'"))
                .isLessThan(page.indexOf("</head>"));
    }

    @Test
    void theEditorPageDefinesTheStubsBeforeTheirScripts() {
        String page = given().get("/wp-admin/post.php?post=1&action=edit").asString();

        int wpEditor = page.indexOf("id=\"wp-editor-js\"");
        assertThat(page.indexOf("var _wpMetaBoxUrl = \"\";")).isPositive().isLessThan(wpEditor);
        assertThat(page.indexOf("window.__experimentalDataViewsMediaModal = true;"))
                .isPositive()
                .isLessThan(wpEditor);
        assertThat(page.indexOf("_wpMediaViewsL10n")).isPositive().isLessThan(page.indexOf("id=\"media-views-js\""));
        assertThat(page.indexOf("_wpPluploadSettings")).isPositive().isLessThan(page.indexOf("id=\"wp-plupload-js\""));
        assertThat(page.indexOf("_wpMediaModelsL10n")).isPositive().isLessThan(page.indexOf("id=\"media-models-js\""));
        assertThat(page.indexOf("var mejsL10n")).isPositive().isLessThan(page.indexOf("id=\"mediaelement-core-js\""));
        // the alias prints its inline script where it resolves, before what depends on it
        assertThat(page.indexOf("<script id=\"mediaelement-js-after\">"))
                .isGreaterThan(page.indexOf("id=\"mediaelement-migrate-js\""))
                .isLessThan(page.indexOf("id=\"wp-mediaelement-js\""));
        assertThat(page.indexOf("jQuery.uiBackCompat = true;"))
                .isPositive()
                .isLessThan(page.indexOf("id=\"jquery-ui-core-js\""));

        int editorAfter = page.indexOf("<script id=\"editor-js-after\">\nwindow.wp.oldEditor = window.wp.editor;\n");
        assertThat(editorAfter).isGreaterThan(page.indexOf("id=\"editor-js\""));
        assertThat(page.indexOf("<script id=\"wp-editor-js-after\">\nObject.assign( window.wp.editor,"))
                .isGreaterThan(wpEditor);
        assertThat(page.indexOf("window.wpEditorL10n = {tinymce:{baseURL:new URL(\"../wp-includes/js/tinymce\","
                        + " document.baseURI).href,"))
                .isPositive()
                .isLessThan(page.indexOf("id=\"wp-block-library-js\""));
        int preInit = page.indexOf("var tinyMCEPreInit = {");
        assertThat(preInit)
                .isGreaterThan(page.indexOf("id=\"wp-core-commands-js\""))
                .isLessThan(page.indexOf("id=\"wp-tinymce-root-js\""));
        assertThat(page.indexOf("window.wp.editor.getDefaultSettings = function()"))
                .isPositive()
                .isLessThan(preInit);
        assertThat(page.indexOf("tinymce.addI18n( 'en',")).isGreaterThan(page.indexOf("id=\"wp-tinymce-js\""));
        assertThat(page).contains("//# sourceURL=wp-editor-js-before\n</script>");
        assertThat(page).contains("toolbar3:\"\",toolbar4:\"\",external_plugins:[],classic_block_editor:true}");

        assertThat(page)
                .doesNotContain("%TINYMCE_BASE%")
                .doesNotContain("localhost")
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("heartbeat")
                .doesNotContain("__clientSideMediaProcessing")
                .doesNotContain("admin-ajax");
    }

    @Test
    void theShellPageHasNoWordPressScripts() {
        Response response = given().get("/wp-admin/edit.php");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("text/html; charset=utf-8");
        assertThat(response.header("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.header("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(response.header("Content-Security-Policy")).isEqualTo("frame-ancestors 'self'");
        assertThat(response.header("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        String page = response.asString();
        assertThat(page)
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("wp-includes")
                .doesNotContain("wp-admin/css")
                .doesNotContain("id=\"editor\"")
                .contains("<body class=\"qp-admin\">")
                .contains("<div id=\"quarkpress-admin\" data-admin-path=\"/wp-admin/\">")
                .contains("<main id=\"qp-main\" tabindex=\"-1\">")
                .contains("<div id=\"qp-notices\" role=\"status\" aria-live=\"polite\">")
                .contains("<link rel=\"stylesheet\" href=\"quarkpress-admin/shell.css\">")
                .endsWith("<script type=\"module\" src=\"quarkpress-admin/app.js\"></script>\n</body>\n</html>\n");
        // the sign-in client's classic script first, then the shell's module, and nothing else
        int client = page.indexOf("<script src=\"quarkpress-admin/vendor/oidc-client-ts.js\"></script>");
        int module = page.indexOf("<script type=\"module\" src=\"quarkpress-admin/app.js\"");
        assertThat(page.indexOf("<script")).isEqualTo(client).isLessThan(module);
        assertThat(page.indexOf("<script", client + 1)).isEqualTo(module);
    }

    @Test
    void everyAdminRouteShapeResolves() {
        String shell = given().get("/wp-admin/edit.php").asString();
        String editor = given().get("/wp-admin/post.php").asString();
        assertThat(shell).isNotEqualTo(editor);

        for (String path : List.of(
                "/wp-admin/", "/wp-admin/index.php", "/wp-admin/edit.php?post_type=page", "/wp-admin/upload.php")) {
            Response response = given().get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.asString()).as(path).isEqualTo(shell);
        }
        for (String path : List.of(
                "/wp-admin/post.php?post=7&action=edit",
                "/wp-admin/post-new.php?post_type=page",
                "/wp-admin/revision.php?revision=3")) {
            Response response = given().get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.asString()).as(path).isEqualTo(editor);
        }
        assertThat(given().get("/wp-admin/nope.php").statusCode()).isEqualTo(404);
        // a site below a path: the same page, whose relative URLs resolve under that site
        assertThat(given().get("/fv01/wp-admin/post.php").asString()).isEqualTo(editor);
        assertThat(given().get("/fv01/wp-admin/edit.php").asString()).isEqualTo(shell);

        Response head = given().head("/wp-admin/post.php");
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.header("Content-Length"))
                .isEqualTo(Integer.toString(editor.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        assertThat(head.asByteArray()).isEmpty();
    }

    /** The ids of the assets that print a tag of their own (aliases have no file), with a suffix. */
    private static List<String> tags(List<AssetRegistry.Asset> assets, String suffix) {
        List<String> tags = new ArrayList<>();
        for (AssetRegistry.Asset asset : assets) {
            if (asset.file().isPresent()) {
                tags.add(asset.handle() + suffix);
            }
        }
        return tags;
    }

    /** The values of an attribute whose text starts with {@code prefix}, such as {@code  src="}, in page order. */
    static List<String> attributes(String page, String prefix) {
        List<String> values = new ArrayList<>();
        int at = page.indexOf(prefix);
        while (at >= 0) {
            int start = at + prefix.length();
            char quote = prefix.charAt(prefix.length() - 1);
            int end = page.indexOf(quote, start);
            values.add(page.substring(start, end));
            at = page.indexOf(prefix, end);
        }
        return values;
    }

    /** The {@code id} values ending with {@code suffix} (the closing quote included), such as {@code -js"}. */
    static List<String> ids(String page, String suffix) {
        char quote = suffix.charAt(suffix.length() - 1);
        List<String> ids = new ArrayList<>();
        for (String id : attributes(page, " id=" + quote)) {
            if (id.endsWith(suffix.substring(0, suffix.length() - 1))) {
                ids.add(id);
            }
        }
        return ids;
    }
}
