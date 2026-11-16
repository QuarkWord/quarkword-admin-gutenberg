package org.quarkpress.it.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The admin screens in a real browser (Chromium headless) against the test application: sign-in through Keycloak with
 * the public client, the lists and their status views, trash, undo, restore and permanent deletion, a new draft, a site
 * in a subdirectory, the pages and media lists. Each flow runs as one user in a browser context of its own and waits on
 * requests, answers, the DOM or the shell's state, never on time; no page may log an error.
 */
@QuarkusTest
class AdminFlowsDbTest {

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
        setup.role("carol", "author");
        setup.role("dave", "contributor");
        setup.role("erin", "subscriber");
    }

    @AfterEach
    void noPageLoggedAnError() {
        assertThat(browsers.takeErrors()).isEmpty();
    }

    private static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static Locator heading(Page page) {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setLevel(1));
    }

    private static Locator row(Page page, String title) {
        return page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText(title));
    }

    private static Locator view(Page page, String label) {
        return page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Filter posts list"))
                .getByRole(
                        AriaRole.LINK,
                        new Locator.GetByRoleOptions().setName(Pattern.compile("^" + label + " \\(\\d+\\)$")));
    }

    /** Waits for the list request of a click's navigation (the status named), then for the list to be shown. */
    private static void openView(Page page, String label, String status) {
        page.waitForResponse(
                response -> response.url().contains("/wp-json/wp/v2/posts?")
                        && response.url().contains("status=" + status)
                        && response.url().contains("per_page=20"),
                () -> view(page, label).click());
        page.locator("#qp-main[aria-busy=false]").waitFor();
    }

    @Test
    void signInShowsThePostsListAndKeepsTokensOutOfLocalStorage() {
        try (BrowserContext context = browsers.context("bob")) {
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/"));

            assertThat(heading(page).textContent()).isEqualTo("Posts");
            assertThat(page.url()).isEqualTo(browsers.adminUrl("/"));
            assertThat(page.evaluate("() => localStorage.length")).isEqualTo(0);
            assertThat(page.evaluate("() => Object.keys(sessionStorage).some(k => k.startsWith('oidc.user:'))"))
                    .isEqualTo(true);
            // the focus is on the screen's heading
            assertThat(page.evaluate("() => document.activeElement.tagName")).isEqualTo("H1");
        }
    }

    @Test
    void aSubscriberIsRefused() {
        try (BrowserContext context = browsers.context("erin")) {
            Page page = browsers.signedIn(context, "erin", browsers.adminUrl("/"));

            Locator refusal = page.getByText("Sorry, you are not allowed to access this page.");
            refusal.waitFor();
            assertThat(refusal.isVisible()).isTrue();
            assertThat(heading(page).textContent()).isEqualTo("Posts");
            assertThat(page.getByRole(AriaRole.TABLE).count()).isZero();
            // the one refused request (the status filter is not the subscriber's), which the browser logs; no other
            assertThat(browsers.takeErrors())
                    .singleElement()
                    .asString()
                    .contains("status of 400")
                    .contains(browsers.root() + "wp-json/wp/v2/posts?context=edit&status=");
        }
    }

    @Test
    void statusViewsFilterTheList() {
        String draft = "Draft " + token();
        String published = "Published " + token();
        setup.post("bob", "post", "draft", draft);
        setup.post("bob", "post", "publish", published);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/"));

            row(page, draft).waitFor();
            assertThat(row(page, published).count()).isEqualTo(1);
            assertThat(view(page, "All").textContent())
                    .isEqualTo("All (" + setup.total("bob", "posts", "publish,future,draft,pending,private") + ")");
            assertThat(view(page, "Drafts").textContent())
                    .isEqualTo("Drafts (" + setup.total("bob", "posts", "draft") + ")");
            assertThat(view(page, "Published").textContent())
                    .isEqualTo("Published (" + setup.total("bob", "posts", "publish") + ")");

            openView(page, "Drafts", "draft");
            assertThat(page.url()).isEqualTo(browsers.adminUrl("/") + "edit.php?post_status=draft");
            assertThat(row(page, draft).count()).isEqualTo(1);
            assertThat(row(page, published).count()).isZero();
            assertThat(view(page, "Drafts").getAttribute("aria-current")).isEqualTo("page");

            openView(page, "Published", "publish");
            assertThat(row(page, published).count()).isEqualTo(1);
            assertThat(row(page, draft).count()).isZero();

            openView(page, "All", "publish%2Cfuture%2Cdraft%2Cpending%2Cprivate");
            assertThat(row(page, draft).count()).isEqualTo(1);
            assertThat(row(page, published).count()).isEqualTo(1);
        }
    }

    @Test
    void trashRestoreAndDeletePermanently() {
        String title = "Trash me " + token();
        long id = setup.post("bob", "post", "publish", title);
        String restored = "Restore me " + token();
        long restoredId = setup.post("bob", "post", "publish", restored);
        setup.trash("bob", "posts", restoredId);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/"));
            Locator notices = page.locator("#qp-notices");

            Response trashed = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/wp/v2/posts/" + id)
                            && response.request().method().equals("DELETE"),
                    () -> row(page, title)
                            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Move to Trash"))
                            .click());
            assertThat(trashed.status()).isEqualTo(200);
            notices.getByText("1 post moved to the Trash.").waitFor();
            assertThat(row(page, title).count()).isZero();
            assertThat(setup.statusOf("bob", "posts", id)).contains("trash");
            // the notice's parameters are read once and dropped from the address
            assertThat(page.url()).isEqualTo(browsers.adminUrl("/"));

            Response undone = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/wp/v2/posts/" + id)
                            && response.request().method().equals("PUT"),
                    () -> notices.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Undo"))
                            .click());
            assertThat(undone.status()).isEqualTo(200);
            assertThat(undone.request().postData()).isEqualTo("{\"status\":\"draft\"}");
            notices.getByText("1 post restored from the Trash.").waitFor();
            assertThat(setup.statusOf("bob", "posts", id)).contains("draft");
            row(page, title).waitFor();

            page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/wp/v2/posts/" + id)
                            && response.request().method().equals("DELETE"),
                    () -> row(page, title)
                            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Move to Trash"))
                            .click());
            notices.getByText("1 post moved to the Trash.").waitFor();

            openView(page, "Trash", "trash");
            assertThat(row(page, title).getByRole(AriaRole.LINK).count()).isZero();
            Response restore = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/wp/v2/posts/" + restoredId)
                            && response.request().method().equals("PUT"),
                    () -> row(page, restored)
                            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Restore"))
                            .click());
            assertThat(restore.status()).isEqualTo(200);
            assertThat(restore.request().postData()).isEqualTo("{\"status\":\"draft\"}");
            notices.getByText("1 post restored from the Trash.").waitFor();
            assertThat(setup.statusOf("bob", "posts", restoredId)).contains("draft");
            assertThat(row(page, restored).count()).isZero();
            assertThat(row(page, title).count()).isEqualTo(1);
            Response deleted = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/wp/v2/posts/" + id + "?force=true")
                            && response.request().method().equals("DELETE"),
                    () -> row(page, title)
                            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Delete Permanently"))
                            .click());
            assertThat(deleted.status()).isEqualTo(200);
            notices.getByText("1 post permanently deleted.").waitFor();
            assertThat(setup.statusOf("bob", "posts", id)).isEmpty();
            assertThat(row(page, title).count()).isZero();
        }
    }

    @Test
    void undoRestoresOnlyPostsThatAreInTheTrash() {
        String title = "Live " + token();
        long id = setup.post("bob", "post", "publish", title);

        try (BrowserContext context = browsers.context("bob")) {
            // a crafted address naming a published post as trashed
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/") + "edit.php?trashed=1&ids=" + id);
            Locator notices = page.locator("#qp-notices");
            notices.getByText("1 post moved to the Trash.").waitFor();
            Locator undo = notices.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Undo"));

            Response checked = page.waitForResponse(
                    response -> response.url().contains("/wp-json/wp/v2/posts?")
                            && response.url().contains("status=trash")
                            && response.url().contains("include=" + id),
                    undo::click);
            assertThat(checked.status()).isEqualTo(200);
            // the action has finished once its button is enabled again
            page.waitForFunction("() => [...document.querySelectorAll('#qp-notices button')]"
                    + ".some(b => b.textContent === 'Undo' && !b.disabled)");
            assertThat(browsers.requests(page)).noneMatch(request -> request.startsWith("PUT "));
            assertThat(setup.statusOf("bob", "posts", id)).contains("publish");
        }
    }

    @Test
    void theShellsModulesKeepTheTokenAndTheAddressesInTheirSite() {
        try (BrowserContext context = browsers.context("bob")) {
            Page page = context.newPage();
            page.navigate(browsers.adminUrl("/"));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log in with QuarkPress"))
                    .waitFor();

            Object checks = page.evaluate("async () => {"
                    + " const api = await import(new URL('quarkpress-admin/api.js', document.baseURI));"
                    + " const config = await import(new URL('quarkpress-admin/config.js', document.baseURI));"
                    + " const answer = {adminPath: '/wp-admin/', restPath: '/wp-json', oidc: {}, lockRefreshInterval: 60,"
                    + "   wordpress: '7.1.3'};"
                    + " const site = config.siteUrls(answer, new URL('http://localhost:8080/fv01/wp-admin/edit.php'));"
                    + " const two = config.siteUrls({...answer, adminPath: '/a/b/'},"
                    + "   new URL('http://localhost:8080/fv01/a/b/post.php?post=1'));"
                    + " const signedIn = {getToken: async () => 'T', refresh: async () => {}};"
                    + " const sent = [];"
                    + " const next = async options => { sent.push(options); return {}; };"
                    + " const sign = api.bearerMiddleware(site, signedIn);"
                    + " const auth = async options => { sent.length = 0; await sign(options, next);"
                    + "   const headers = sent[0].headers || {}; return headers.Authorization || null; };"
                    + " let unsent = 'sent';"
                    + " const expired = {getToken: async () => null, refresh: async () => { throw new Error('x'); }};"
                    + " sent.length = 0;"
                    + " try { await api.bearerMiddleware(site, expired)({path: 'wp/v2/posts/1', method: 'POST'}, next); }"
                    + " catch (error) { unsent = sent.length + ' ' + error.status + ' ' + error.code; }"
                    + " let outside = 'fetched';"
                    + " try { await api.createApi(site, signedIn).fetchJson('https://example.org/x'); }"
                    + " catch (error) { outside = error.message; }"
                    + " let shellUnsent = 'fetched';"
                    + " try { await api.createApi(site, expired).fetchJson('wp/v2/posts'); }"
                    + " catch (error) { shellUnsent = error.status + ' ' + error.code; }"
                    + " return [site.restRoot, site.adminUrl, two.restRoot, two.adminUrl,"
                    + "   await auth({path: 'wp/v2/posts'}),"
                    + "   await auth({url: site.restRoot + 'wp/v2/posts'}),"
                    + "   await auth({url: 'https://example.org/x'}),"
                    + "   await auth({url: site.siteRoot + '/?p=1'}),"
                    + "   await auth({url: 'http://localhost:8080/fv01/wp-json-other/x'}),"
                    + "   unsent, outside.startsWith('Not a REST API address') ? 'refused' : outside, shellUnsent,"
                    + "   config.sameFolder('http://localhost:8080/fv01/wp-admin/edit.php?x=1', site.adminUrl),"
                    + "   config.sameFolder('http://localhost:8080/fv01/wp-admin/sub/edit.php', site.adminUrl),"
                    + "   config.sameFolder('http://evil.test/fv01/wp-admin/edit.php', site.adminUrl),"
                    + "   config.sameFolder('http://localhost:8080/fv02/wp-admin/edit.php', site.adminUrl),"
                    + "   config.sameFolder('//evil.test/x', site.adminUrl)];"
                    + "}");

            assertThat(checks)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .containsExactly(
                            "http://localhost:8080/fv01/wp-json/",
                            "http://localhost:8080/fv01/wp-admin/",
                            "http://localhost:8080/fv01/wp-json/",
                            "http://localhost:8080/fv01/a/b/",
                            "Bearer T",
                            "Bearer T",
                            null,
                            null,
                            null,
                            "0 401 rest_not_logged_in",
                            "refused",
                            "401 rest_not_logged_in",
                            true,
                            false,
                            false,
                            false,
                            false);
            assertThat(browsers.requests(page)).noneMatch(request -> request.contains("example.org"));
        }
    }

    @Test
    void theListPagesAndHidesEmptyViews() {
        setup.createSite("/fv02/", "Site two");
        setup.membership("/fv02/", "bob", "editor");
        for (int i = 1; i <= 21; i++) {
            setup.postOn("/fv02/", "bob", "post", "publish", String.format("Paged %02d", i));
        }

        try (BrowserContext context = browsers.context("bob")) {
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/fv02/"));

            Locator paged = page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText("Paged "));
            paged.first().waitFor();
            List<String> first = paged.locator(".row-title").allTextContents();
            assertThat(first).hasSize(20).doesNotHaveDuplicates();
            assertThat(view(page, "All").textContent()).isEqualTo("All (21)");
            assertThat(view(page, "Published").textContent()).isEqualTo("Published (21)");
            for (String empty : List.of("Scheduled", "Drafts", "Pending", "Private", "Trash")) {
                assertThat(view(page, empty).count()).as(empty).isZero();
            }
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Previous"))
                            .count())
                    .isZero();

            page.waitForResponse(
                    response -> response.url().contains("/fv02/wp-json/wp/v2/posts?")
                            && response.url().contains("page=2")
                            && response.url().contains("per_page=20"),
                    () -> page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Next"))
                            .click());
            page.locator("#qp-main[aria-busy=false]").waitFor();
            assertThat(page.url()).isEqualTo(browsers.adminUrl("/fv02/") + "edit.php?paged=2");
            List<String> second = paged.locator(".row-title").allTextContents();
            assertThat(second).hasSize(1);
            assertThat(first).doesNotContainAnyElementsOf(second);
            assertThat(page.getByText("2 of 2").count()).isEqualTo(1);
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Next"))
                            .count())
                    .isZero();
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Previous"))
                            .getAttribute("href"))
                    .isEqualTo("edit.php");
        }
    }

    @Test
    void addNewCreatesADraftAndOpensTheEditorPage() {
        try (BrowserContext context = browsers.context("bob")) {
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/"));
            heading(page).waitFor();
            // Until the runtime ships quarkpress-admin/editor.js (the editor screen's task), the editor page's own
            // module is answered empty here. Remove this route then: left in place it would replace the real module.
            context.route(
                    "**/quarkpress-admin/editor.js",
                    route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                            .setContentType("text/javascript")
                            .setBody("")));

            Pattern editor =
                    Pattern.compile(Pattern.quote(browsers.adminUrl("/")) + "post\\.php\\?post=(\\d+)&action=edit");
            Response[] drafts = new Response[1];
            Response opened = page.waitForResponse(
                    response -> editor.matcher(response.url()).matches(),
                    () -> drafts[0] = page.waitForResponse(
                            response -> response.url().endsWith("/wp-json/quarkpress/v1/drafts")
                                    && response.request().method().equals("POST"),
                            () -> page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Add New Post"))
                                    .click()));

            assertThat(drafts[0].status()).isEqualTo(201);
            assertThat(drafts[0].request().postData()).isEqualTo("{\"type\":\"post\"}");
            assertThat(opened.status()).isEqualTo(200);
            page.waitForURL(editor.asMatchPredicate());
            page.waitForLoadState();
            var matcher = editor.matcher(page.url());
            assertThat(matcher.matches()).isTrue();
            assertThat(setup.statusOf("bob", "posts", Long.parseLong(matcher.group(1))))
                    .contains("auto-draft");
        }
    }

    @Test
    void aSubdirectorySiteUsesItsOwnRestRootAndAssets() {
        setup.createSite("/fv01/", "Site one");
        setup.membership("/fv01/", "bob", "editor");
        String mine = "On fv01 " + token();
        String main = "On the main site " + token();
        setup.postOn("/fv01/", "bob", "post", "publish", mine);
        setup.post("bob", "post", "publish", main);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/fv01/"));

            row(page, mine).waitFor();
            assertThat(row(page, main).count()).isZero();
            assertThat(page.url()).isEqualTo(browsers.adminUrl("/fv01/"));
            List<String> rest = browsers.requests(page).stream()
                    .filter(request -> request.contains("/wp-json/"))
                    .toList();
            assertThat(rest)
                    .isNotEmpty()
                    .allSatisfy(request -> assertThat(request).contains(" " + browsers.root() + "fv01/wp-json/"));
            assertThat(browsers.requests(page))
                    .contains("GET " + browsers.adminUrl("/fv01/") + "quarkpress-admin/config.json");
            // the editor's relative links resolve under the subsite's admin folder
            assertThat(row(page, mine).getByRole(AriaRole.LINK).first().evaluate("a => a.href"))
                    .asString()
                    .startsWith(browsers.adminUrl("/fv01/") + "post.php?post=");

            // fetched by the page, through the browser's own resolver
            Object dashicons = page.evaluate(
                    "async url => { const r = await fetch(url); return r.status + ' ' + r.headers.get('content-type'); }",
                    browsers.root() + "fv01/wp-includes/css/dashicons.min.css?ver=7.1.3");
            assertThat(dashicons).isEqualTo("200 text/css; charset=utf-8");
        }
    }

    @Test
    void pagesAndMediaLists() throws IOException {
        String pageTitle = "A page " + token();
        setup.post("bob", "page", "publish", pageTitle);
        String file = "qp-flow-" + token();
        Path png = Files.createDirectories(Path.of("target", "flow-files")).resolve(file + ".png");
        ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        setup.upload("bob", png);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/") + "edit.php?post_type=page");

            row(page, pageTitle).waitFor();
            assertThat(heading(page).textContent()).isEqualTo("Pages");
            assertThat(page.url()).isEqualTo(browsers.adminUrl("/") + "edit.php?post_type=page");
            Locator addNew = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Add New Page"));
            assertThat(addNew.getAttribute("href")).isEqualTo("post-new.php?post_type=page");

            page.waitForResponse(
                    response -> response.url().contains("/wp-json/wp/v2/media?"),
                    () -> page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Media Library"))
                            .click());
            row(page, file).waitFor();
            assertThat(heading(page).textContent()).isEqualTo("Media Library");
            assertThat(row(page, file).getByRole(AriaRole.CELL).nth(1).textContent())
                    .isEqualTo("image/png");
            // the upload's own URL, on the site's address, loads
            assertThat(row(page, file).locator("img").getAttribute("src"))
                    .startsWith(browsers.root() + "wp-content/uploads/");
            row(page, file).locator("img").evaluate("img => img.decode()");
        }
    }
}
