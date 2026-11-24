package org.quarkword.it.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.path.json.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The smoke flow against the packaged application (the JVM jar locally, the native executable in CI): the shell page and
 * the editor page with its TinyMCE tag and import map, one WordPress file and the sign-in client served from the jar,
 * the editor route's zone abbreviation for a Europe/Paris site, and in Chromium: bob signs in, "Add New Post" mounts the
 * editor, one save, and the list shows the draft.
 */
@QuarkusIntegrationTest
class AdminSmokeIT {

    static Browsers browsers;

    @TestHTTPResource("/")
    URL root;

    final ItSetup setup = new ItSetup();

    @BeforeAll
    static void launch() {
        browsers = Browsers.launch();
    }

    @AfterAll
    static void close() {
        if (browsers != null) {
            browsers.close();
        }
    }

    @BeforeEach
    void roles() {
        browsers.base(root);
        setup.role("bob", "editor");
    }

    @AfterEach
    void noPageLoggedAnError() {
        assertThat(browsers.takeErrors()).isEmpty();
    }

    private static byte[] jarResource(String path) throws IOException {
        try (InputStream in = AdminSmokeIT.class.getClassLoader().getResourceAsStream("org/quarkword/admin/" + path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Europe/Paris's offset in hours at {@code at}, from the test JVM's own time zone data. */
    private static long parisHours(Instant at) {
        return ZoneId.of("Europe/Paris").getRules().getOffset(at).getTotalSeconds() / 3600;
    }

    @Test
    void theAdminScreensServeSignInEditAndSave() throws IOException {
        // the pages and the files they load come out of the executable
        io.restassured.response.Response shell = setup.as(null).get("/wp-admin/");
        assertThat(shell.statusCode()).isEqualTo(200);
        assertThat(shell.asString()).contains("<div id=\"quarkword-admin\" data-admin-path=\"/wp-admin/\">");

        io.restassured.response.Response editor = setup.as(null).get("/wp-admin/post.php");
        assertThat(editor.statusCode()).isEqualTo(200);
        assertThat(editor.asString())
                .contains("<script id=\"wp-importmap\" type=\"importmap\">")
                .contains("id=\"wp-tinymce-js\"")
                .contains("<script src=\"quarkword-admin/vendor/oidc-client-ts.js\"></script>");

        io.restassured.response.Response hooks = setup.as(null).get("/wp-includes/js/dist/hooks.min.js");
        assertThat(hooks.statusCode()).isEqualTo(200);
        assertThat(hooks.header("Content-Type")).isEqualTo("text/javascript; charset=utf-8");
        assertThat(hooks.asByteArray()).isEqualTo(jarResource("wp/wp-includes/js/dist/hooks.min.js"));

        io.restassured.response.Response client =
                setup.as(null).get("/wp-admin/quarkword-admin/vendor/oidc-client-ts.js");
        assertThat(client.statusCode()).isEqualTo(200);
        assertThat(client.asByteArray()).isEqualTo(jarResource("vendor/oidc-client-ts.js"));

        // the executable's zone names: Paris is CET in winter and CEST in summer, matching the offset it reports
        setup.option("timezone_string", "\"Europe/Paris\"");
        long fixture = setup.post("bob", "post", "draft", "Zone check");
        long before = parisHours(Instant.now());
        JsonPath dates = setup.rest("bob", "/", "quarkword/v1/editor/post/" + fixture);
        long after = parisHours(Instant.now());
        String abbr = dates.getString("dateSettings.timezone.abbr");
        long offset = dates.getLong("dateSettings.timezone.offset");
        assertThat(dates.getString("dateSettings.timezone.string")).isEqualTo("Europe/Paris");
        assertThat(offset).isIn(before, after);
        assertThat(abbr).isEqualTo(offset == 2 ? "CEST" : "CET");

        try (BrowserContext context = browsers.context("bob")) {
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/"));
            page.locator("#qw-main[aria-busy=false]").waitFor();

            Pattern edit =
                    Pattern.compile(Pattern.quote(browsers.adminUrl("/")) + "post\\.php\\?post=(\\d+)&action=edit");
            Response draft = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/quarkword/v1/drafts")
                            && response.request().method().equals("POST"),
                    () -> page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Add New Post"))
                            .click());
            assertThat(draft.status()).isEqualTo(201);
            page.waitForURL(edit.asMatchPredicate());
            Browsers.awaitEditor(page);
            assertThat(page.evaluate("() => window.quarkwordAdmin.outcome")).isEqualTo("mounted");
            Browsers.closeWelcomeGuide(page);
            long id = ((Number) page.evaluate("() => wp.data.select('core/editor').getCurrentPostId()")).longValue();

            // the editor got the same zone settings
            @SuppressWarnings("unchecked")
            Map<String, Object> zone = (Map<String, Object>) page.evaluate("() => { const t = wp.date.getSettings()"
                    + ".timezone; return { abbr: t.abbr, string: t.string }; }");
            assertThat(zone).containsEntry("string", "Europe/Paris").containsEntry("abbr", abbr);

            page.evaluate("() => wp.data.dispatch('core/editor').editPost({ title: 'Native smoke' })");
            Response saved = page.waitForResponse(
                    response -> response.request().method().equals("POST")
                            && response.url().contains("/wp-json/wp/v2/posts/" + id + "?")
                            && "PUT".equals(response.request().headers().get("x-http-method-override")),
                    () -> page.evaluate("() => wp.data.dispatch('core/editor').savePost()"));
            assertThat(saved.status()).isEqualTo(200);
            assertThat(setup.statusOf("bob", "posts", id)).contains("draft");

            page.navigate(browsers.adminUrl("/"));
            page.locator("#qw-main[aria-busy=false]").waitFor();
            Locator row = page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText("Native smoke"));
            assertThat(row.count()).isEqualTo(1);
            assertThat(List.of(row.first().locator("td.column-title strong").innerText()))
                    .containsExactly("Native smoke \u2014 Draft");
        }
    }
}
