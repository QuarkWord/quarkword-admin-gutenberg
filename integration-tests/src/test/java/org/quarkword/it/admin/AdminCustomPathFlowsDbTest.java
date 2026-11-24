package org.quarkword.it.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The editor under an admin path of two segments ({@code quarkword.admin.path=/a/b/}), on the main site and on a site
 * of the network: the editor's links into the admin screens (the dashboard, the newer autosave's revision screen) lead
 * to that site's admin folder, not to {@code wp-admin/}. As {@link AdminFlowsDbTest}, one user per browser context,
 * waiting on the page's state, never on time.
 */
@QuarkusTest
@TestProfile(AdminCustomPathFlowsDbTest.CustomAdminPath.class)
class AdminCustomPathFlowsDbTest {

    /** The admin screens under {@code /a/b/}. */
    public static class CustomAdminPath implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkword.admin.path", "/a/b/");
        }
    }

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
        setup.role("alice", "administrator");
    }

    @AfterEach
    void noPageLoggedAnError() {
        assertThat(browsers.takeErrors()).isEmpty();
    }

    private static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** The admin folder of the site at {@code sitePath} under the custom admin path. */
    private static String adminUrl(String sitePath) {
        return browsers.root() + sitePath.substring(1) + "a/b/";
    }

    /** Opens the editor of the post and waits until it is mounted; answers the editor's settings. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> editorSettings(Page page, String sitePath, long id) {
        page.navigate(adminUrl(sitePath) + "post.php?post=" + id + "&action=edit");
        page.waitForFunction("() => window.quarkwordAdmin && window.quarkwordAdmin.outcome");
        assertThat(page.evaluate("() => window.quarkwordAdmin.outcome")).isEqualTo("mounted");
        Browsers.awaitEditor(page);
        return (Map<String, Object>)
                page.evaluate("() => { const s = wp.data.select('core/editor').getEditorSettings();"
                        + " return { dashboard: s.__experimentalDashboardLink, autosave: s.autosave || null }; }");
    }

    @Test
    void theEditorsAdminLinksLeadToTheSitesAdminFolder() {
        long post = setup.post("bob", "post", "publish", "Custom path " + token());
        setup.backdate(post, "2026-01-01T10:00:00");
        long autosave = setup.autosave(post, "alice", "Alice's newer title", "Alice's newer words.");
        String site = "/cp" + token() + "/";
        setup.createSite(site, "Custom path site");
        setup.membership(site, "bob", "editor");
        long onSite = setup.postOn(site, "bob", "post", "draft", "On the site " + token());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = browsers.signedIn(context, "bob", adminUrl("/"));

            Map<String, Object> main = editorSettings(page, "/", post);
            assertThat(main.get("dashboard")).isEqualTo(adminUrl("/"));
            @SuppressWarnings("unchecked")
            Map<String, Object> link = (Map<String, Object>) main.get("autosave");
            assertThat(link).isNotNull();
            assertThat(link.get("editLink")).isEqualTo(adminUrl("/") + "revision.php?revision=" + autosave);

            Map<String, Object> subsite = editorSettings(page, site, onSite);
            assertThat(subsite.get("dashboard")).isEqualTo(adminUrl(site));

            // both links are pages of the admin folder: the revision screen's notice and the posts list
            page.navigate((String) link.get("editLink"));
            page.waitForFunction("() => window.quarkwordAdmin && window.quarkwordAdmin.outcome");
            assertThat(page.evaluate("() => window.quarkwordAdmin.outcome")).isEqualTo("revision");
            page.navigate((String) subsite.get("dashboard"));
            page.locator("#qw-main[aria-busy=false]").waitFor();
            assertThat(page.url()).isEqualTo(adminUrl(site));
        }
        // the 404s of the routes the editor route leaves out are no errors of the flow
        browsers.takeOmitted();
    }
}
