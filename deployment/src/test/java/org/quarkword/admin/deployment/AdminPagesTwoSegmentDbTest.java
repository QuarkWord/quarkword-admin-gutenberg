package org.quarkword.admin.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** An admin path of two segments: every site-relative URL of the pages climbs both. */
class AdminPagesTwoSegmentDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            AdminApp.app(AdminPagesTwoSegmentDbTest.class).overrideConfigKey("quarkword.admin.path", "/a/b/");

    @Test
    void aTwoSegmentAdminPathPrefixesAssetsTwice() {
        Response response = given().get("/a/b/post.php?post=1&action=edit");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(response.header("Content-Security-Policy")).isEqualTo("frame-ancestors 'self'");
        assertThat(response.header("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        String page = response.asString();
        assertThat(page)
                .doesNotContain("http://")
                .doesNotContain("https://")
                .contains("<script src=\"../../wp-includes/js/dist/hooks.min.js?ver=")
                .contains("href='../../wp-admin/css/common.min.css?ver=7.1.3'")
                .contains("\"../../wp-includes/js/dist/script-modules/route/index.min.js?ver=ef851c0f4dda0d1dd359\"")
                .contains("src=\"../../wp-includes/js/dist/script-modules/latex-to-mathml/loader.min.js?ver=")
                .contains("new URL(\"../../wp-includes/js/tinymce\", document.baseURI).href")
                .contains("<div id=\"quarkword-admin\" data-admin-path=\"/a/b/\">")
                .contains("<script type=\"module\" src=\"quarkword-admin/editor.js\"></script>")
                .doesNotContain("\"../wp-includes")
                .doesNotContain("'../wp-");
        // the files the page names are served where they resolve: below the site, not below the admin path
        assertThat(given().get("/wp-includes/js/dist/hooks.min.js").statusCode())
                .isEqualTo(200);
        Response shell = given().get("/a/b/edit.php");
        assertThat(shell.header("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(shell.header("Content-Security-Policy")).isEqualTo("frame-ancestors 'self'");
        assertThat(shell.header("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(given().get("/a/b/edit.php").asString()).contains("data-admin-path=\"/a/b/\"");

        Response folder = given().redirects().follow(false).get("/a/b");
        assertThat(folder.statusCode()).isEqualTo(301);
        assertThat(folder.header("Location")).isEqualTo("/a/b/");
        assertThat(given().redirects().follow(false).get("/wp-admin").statusCode())
                .isEqualTo(404);
        assertThat(given().get("/wp-admin/post.php").statusCode()).isEqualTo(404);
        assertThat(given().get("/wp-admin/edit.php").statusCode()).isEqualTo(404);
    }
}
