package org.quarkpress.it.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * in a subdirectory, the pages and media lists; and the editor screen: WordPress's block editor by role, a new post
 * with an upload, autosave, schedule and publish, pages, synced patterns, preloads, the media modal, the token's
 * retry and its failed renewal, omitted routes, the Classic block, missing, trashed and revision addresses, the site's
 * date settings, the post lock, draft and published previews through the preview session and its renewal, and trash
 * from the editor. Each flow runs as one user in a browser context of its own and waits on requests, answers, the DOM,
 * the shell's or the editor's state, never on time; no page may log an error but those a flow expects and takes, and
 * the 404s of the routes the editor route leaves out.
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
        // the network's administrator is also the main site's, as on a WordPress network's first site
        setup.role("alice", "administrator");
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
            // nothing it names is in the trash: WordPress's untrash error
            notices.getByText("Error in restoring from Trash.").waitFor();
            page.waitForFunction("() => [...document.querySelectorAll('#qp-notices button')]"
                    + ".some(b => b.textContent === 'Undo' && !b.disabled)");
            assertThat(browsers.requests(page)).noneMatch(request -> request.startsWith("PUT "));
            assertThat(setup.statusOf("bob", "posts", id)).contains("publish");
        }
    }

    @Test
    void undoWithoutATrashedIdShowsWordPresssError() {
        try (BrowserContext context = browsers.context("bob")) {
            // a crafted address with no usable id
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/") + "edit.php?trashed=1&ids=x,-3");
            Locator notices = page.locator("#qp-notices");
            notices.getByText("1 post moved to the Trash.").waitFor();
            int before = browsers.requests(page).size();

            notices.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Undo"))
                    .click();

            notices.getByText("Error in restoring from Trash.").waitFor();
            assertThat(notices.locator(".qp-notice-error").count()).isEqualTo(1);
            // nothing was asked: there was no post to look up
            List<String> sent = browsers.requests(page);
            assertThat(sent.subList(before, sent.size())).noneMatch(request -> request.contains("/wp-json/"));
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
                    // a request whose path leaves the REST root is refused, with nothing sent
                    + " const auth = async options => { sent.length = 0;"
                    + "   try { await sign(options, next); } catch (error) {"
                    + "     if (sent.length === 0 && error.message.startsWith('Not a REST API address')) return 'refused';"
                    + "     throw error; }"
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
                    // the shell's own calls: an address that leaves the REST root is refused before anything else;
                    // one that stays in it is only stopped by the missing token
                    + " const shell = async path => { try { await api.createApi(site, expired).fetchJson(path);"
                    + "   return 'fetched'; } catch (error) { return error.message.startsWith('Not a REST API address')"
                    + "   ? 'refused' : error.status + ' ' + error.code; } };"
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
                    + "   config.sameFolder('//evil.test/x', site.adminUrl),"
                    + "   config.sameFolder('../edit.php', site.adminUrl),"
                    + "   config.sameFolder('%2e%2e/edit.php', site.adminUrl),"
                    + "   config.sameFolder('http://user@localhost:8080/fv01/wp-admin/edit.php', site.adminUrl),"
                    + "   config.sameFolder('http://localhost:8080@evil.test/fv01/wp-admin/edit.php', site.adminUrl),"
                    + "   await auth({path: '../x'}),"
                    + "   await auth({path: '%2e%2e/%2e%2e/x'}),"
                    + "   await auth({path: '/wp/v2/../../../x'}),"
                    + "   await auth({url: site.restRoot + '../x'}),"
                    + "   await auth({url: site.restRoot + '%2e%2e/x'}),"
                    + "   await auth({url: 'http://user@localhost:8080/fv01/wp-json/wp/v2/posts'}),"
                    + "   await auth({url: '//evil.test/fv01/wp-json/wp/v2/posts'}),"
                    + "   await auth({path: '/wp/v2/posts?context=edit'}),"
                    // the path wins over the url, as api-fetch's root-URL middleware builds the address from it
                    + "   await auth({url: site.restRoot + 'wp/v2/posts', path: '../../x'}),"
                    + "   await auth({namespace: 'wp/v2', endpoint: 'posts'}),"
                    + "   await auth({namespace: '/wp/v2/', endpoint: '../../../x'}),"
                    + "   await shell('../x'), await shell('%2e%2e/x'), await shell('//evil.test/x'),"
                    + "   await shell('http://user@localhost:8080/fv01/wp-json/x'), await shell('user@evil.test/x')];"
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
                            false,
                            false,
                            false,
                            false,
                            false,
                            "refused",
                            "refused",
                            "refused",
                            null,
                            null,
                            null,
                            null,
                            "Bearer T",
                            "refused",
                            "Bearer T",
                            "refused",
                            "refused",
                            "refused",
                            "refused",
                            "refused",
                            // a relative path, however it looks, below the REST root
                            "401 rest_not_logged_in");
            assertThat(browsers.requests(page)).noneMatch(request -> request.contains("example.org"));

            // WordPress's offset text: the number as PHP prints it, quarter hours as minutes
            Object offsets = page.evaluate("async () => {"
                    + " const settings = await import(new URL('quarkpress-admin/editor/settings.js', document.baseURI));"
                    + " return [0, 1, -5, 5.5, -3.5, 5.75, 8.25, -9.75, 5.33].map(settings.offsetFormatted); }");
            assertThat(offsets)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .containsExactly("0", "1", "-5", "5:30", "-3:30", "5:45", "8:15", "-9:45", "5.33");

            // the stored preferences as WordPress prints them: REST's empty array for none becomes an empty string
            Object stored = page.evaluate("async () => {"
                    + " const boot = await import(new URL('quarkpress-admin/editor/bootstrap.js', document.baseURI));"
                    + " return [{meta: {persisted_preferences: []}}, {meta: {}}, null,"
                    + "   {meta: {persisted_preferences: {_modified: 'x', core: {a: 1}}}}]"
                    + "   .map(user => JSON.stringify(boot.storedPreferences(user))); }");
            assertThat(stored)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .containsExactly("\"\"", "\"\"", "\"\"", "{\"_modified\":\"x\",\"core\":{\"a\":1}}");
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
            // the type the shell created the draft with is remembered: the editor asks no type route
            assertThat(outcome(page)).isEqualTo("mounted");
            assertThat(browsers.requests(page))
                    .noneMatch(request -> request.contains("/wp-json/wp/v2/types?context=edit")
                            || request.contains("_fields=id,type,status"));
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

    // ---- the editor screen ------------------------------------------------------------------------------------------

    private static final String SAVE_FAILED_UNAUTHENTICATED = "Updating failed. You are not currently logged in.";

    private static final String NOT_LOGGED_IN =
            "{\"code\":\"rest_not_logged_in\",\"message\":\"You are not currently logged in.\",\"data\":{\"status\":401}}";

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static String editUrl(String sitePath, long id) {
        return browsers.adminUrl(sitePath) + "post.php?post=" + id + "&action=edit";
    }

    /** Waits for the editor page to settle: {@code mounted}, {@code notFound}, {@code forbidden}, {@code trash}, … */
    private static String outcome(Page page) {
        page.waitForFunction("() => window.quarkpressAdmin && window.quarkpressAdmin.outcome");
        return (String) page.evaluate("() => window.quarkpressAdmin.outcome");
    }

    /** Waits for a mounted editor, then closes its welcome guide. */
    private static void awaitMounted(Page page) {
        assertThat(outcome(page)).isEqualTo("mounted");
        Browsers.awaitEditor(page);
        Browsers.closeWelcomeGuide(page);
    }

    /** Signs {@code user} in on the main site's posts list and waits for the list. */
    private Page listAs(BrowserContext context, String user) {
        Page page = browsers.signedIn(context, user, browsers.adminUrl("/"));
        page.locator("#qp-main[aria-busy=false]").waitFor();
        return page;
    }

    /** Signs {@code user} in, then opens the editor of post {@code id} of the main site and waits until it is mounted. */
    private Page editorAs(BrowserContext context, String user, long id) {
        Page page = listAs(context, user);
        page.navigate(editUrl("/", id));
        awaitMounted(page);
        return page;
    }

    /** Whether the request is a save of the post: api-fetch sends a PUT as a POST with the method override. */
    private static boolean isSave(Request request, String base, long id) {
        return request.method().equals("POST")
                && request.url().contains("/wp-json/wp/v2/" + base + "/" + id + "?")
                && "PUT".equals(request.headers().get("x-http-method-override"));
    }

    /** Runs the editor's savePost and waits for the save request's answer. */
    private static Response save(Page page, String base, long id) {
        return page.waitForResponse(
                response -> isSave(response.request(), base, id),
                () -> page.evaluate("() => wp.data.dispatch('core/editor').savePost()"));
    }

    /** The texts of the editor's notices (banners and snackbars) in the notices store. */
    private static List<String> editorNotices(Page page) {
        @SuppressWarnings("unchecked")
        List<String> notices =
                (List<String>) page.evaluate("() => wp.data.select('core/notices').getNotices().map(n => n.content)");
        return notices;
    }

    /** Takes the collected errors, which must all be the browser's reports of answers with one of the statuses. */
    private static void takeExpectedFailures(int... statuses) {
        List<String> errors = browsers.takeErrors();
        assertThat(errors)
                .isNotEmpty()
                .allSatisfy(error -> assertThat(java.util.Arrays.stream(statuses)
                                .anyMatch(status -> error.contains("the server responded with a status of " + status)))
                        .as(error)
                        .isTrue());
    }

    private static boolean visible(Page page, String role, String name) {
        Locator button = page.getByRole(
                AriaRole.valueOf(role),
                new Page.GetByRoleOptions().setName(name).setExact(true));
        return button.count() > 0 && button.first().isVisible();
    }

    /** The post's {@code _links} action names as the editor holds them. */
    @SuppressWarnings("unchecked")
    private static List<String> actionLinks(Page page) {
        return (List<String>) page.evaluate("() => Object.keys(wp.data.select('core/editor').getCurrentPost()._links)"
                + ".filter(name => name.startsWith('wp:action-'))");
    }

    /** The document sidebar's text, the sidebar opened first. */
    private static String documentSidebar(Page page) {
        page.evaluate("() => wp.data.dispatch('core/edit-post').openGeneralSidebar('edit-post/document')");
        page.locator(".editor-sidebar").first().waitFor();
        page.locator(".editor-post-summary, .editor-post-panel__section")
                .first()
                .waitFor();
        return page.locator(".editor-sidebar").first().innerText();
    }

    @Test
    void eachRoleSeesItsButtons() {
        long bobs = setup.post("bob", "post", "draft", "Role check " + token());
        long carols = setup.post("carol", "post", "draft", "Carol's draft " + token());
        long daves = setup.post("dave", "post", "draft", "Dave's draft " + token());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", bobs);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Publish").setExact(true))
                    .first()
                    .waitFor();
            assertThat(actionLinks(page)).contains("wp:action-publish", "wp:action-assign-author", "wp:action-sticky");
            assertThat(documentSidebar(page)).contains("Author");
            assertThat(visible(page, "BUTTON", "Submit for Review")).isFalse();
            assertThat(page.title())
                    .isEqualTo(page.evaluate("() => wp.data.select('core/editor')" + ".getEditedPostAttribute('title')")
                            + " ‹ QuarkPress");
            assertThat(page.locator("#qp-editor-title").textContent()).isEqualTo("Edit Post");
        }
        try (BrowserContext context = browsers.context("carol")) {
            Page page = editorAs(context, "carol", carols);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Publish").setExact(true))
                    .first()
                    .waitFor();
            assertThat(actionLinks(page)).contains("wp:action-publish").doesNotContain("wp:action-assign-author");
            assertThat(documentSidebar(page)).doesNotContain("Author");
        }
        try (BrowserContext context = browsers.context("dave")) {
            Page page = editorAs(context, "dave", daves);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit for Review"))
                    .first()
                    .waitFor();
            assertThat(visible(page, "BUTTON", "Publish")).isFalse();
            assertThat(actionLinks(page))
                    .doesNotContain("wp:action-publish", "wp:action-assign-author", "wp:action-sticky");
            String sidebar = documentSidebar(page);
            assertThat(sidebar).doesNotContain("Author").doesNotContain("Stick to the top of the blog");

            // another user's draft: refused before the editor
            page.navigate(editUrl("/", bobs));
            assertThat(outcome(page)).isEqualTo("forbidden");
            assertThat(page.locator("#qp-main").innerText()).contains("Sorry, you are not allowed to edit this item.");
            assertThat(page.locator("#editor > *").count()).isZero();
            assertThat(page.evaluate("() => window._wpLoadBlockEditor === undefined"))
                    .isEqualTo(true);
            takeExpectedFailures(403);
        }
        try (BrowserContext context = browsers.context("erin")) {
            Page page = browsers.signedIn(context, "erin", browsers.adminUrl("/"));
            page.getByText("Sorry, you are not allowed to access this page.").waitFor();
            browsers.takeErrors();
            page.navigate(editUrl("/", bobs));
            assertThat(outcome(page)).isEqualTo("forbidden");
            assertThat(page.locator("#qp-main").innerText()).contains("Sorry, you are not allowed to edit this item.");
            assertThat(browsers.requests(page)).noneMatch(request -> request.contains("/quarkpress/v1/editor/"));
            takeExpectedFailures(403);
        }
        // dave's released lock still holds the post for a few seconds, as a released lock does in WordPress: make it an
        // old one, as if alice came later
        setup.lock(daves, "dave", 300);
        try (BrowserContext context = browsers.context("alice")) {
            Page page = editorAs(context, "alice", daves);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Publish").setExact(true))
                    .first()
                    .waitFor();
            assertThat(actionLinks(page)).contains("wp:action-publish", "wp:action-assign-author", "wp:action-sticky");
            assertThat(documentSidebar(page)).contains("Author");
        }
    }

    @Test
    void aNewPostWithAnUploadedImageAutosavesPreviewsSchedulesAndPublishes() throws IOException {
        String file = "qp-new-" + token();
        Path png = Files.createDirectories(Path.of("target", "flow-files")).resolve(file + ".png");
        ImageIO.write(new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), "png", png.toFile());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            Response draft = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/quarkpress/v1/drafts"),
                    () -> page.navigate(browsers.adminUrl("/") + "post-new.php"));
            assertThat(draft.status()).isEqualTo(201);
            awaitMounted(page);
            long id = ((Number) page.evaluate("() => wp.data.select('core/editor').getCurrentPostId()")).longValue();
            assertThat(page.evaluate("() => wp.data.select('core/editor').getCurrentPost().status"))
                    .isEqualTo("auto-draft");
            // the type's own label for a new item, as WordPress titles post-new.php
            assertThat(page.locator("#qp-editor-title").textContent()).isEqualTo("Add Post");

            page.evaluate("() => { wp.data.dispatch('core/editor').editPost({ title: 'Flow post' });"
                    + " wp.data.dispatch('core/block-editor').insertBlocks("
                    + "wp.blocks.createBlock('core/paragraph', { content: 'First words.' })); }");
            assertThat(save(page, "posts", id).status()).isEqualTo(200);
            // the address names the saved post
            page.waitForURL(editUrl("/", id));
            assertThat(setup.statusOf("bob", "posts", id)).contains("draft");

            page.evaluate("() => wp.data.dispatch('core/block-editor')"
                    + ".insertBlocks(wp.blocks.createBlock('core/image'))");
            Locator input = Browsers.canvas(page).locator("[data-type='core/image'] input[type=file]");
            Response uploaded = page.waitForResponse(
                    response -> response.url().contains("/wp-json/wp/v2/media")
                            && response.request().method().equals("POST"),
                    () -> input.setInputFiles(png));
            assertThat(uploaded.status()).isEqualTo(201);
            page.waitForFunction(
                    "() => { const b = wp.data.select('core/block-editor').getBlocks()"
                            + ".find(b => b.name === 'core/image');"
                            + " return !!b && !!b.attributes.id && !!b.attributes.url && !b.attributes.url.startsWith('blob:'); }");

            Response autosaved = page.waitForResponse(
                    response -> response.url().contains("/wp-json/wp/v2/posts/" + id + "/autosaves")
                            && response.request().method().equals("POST"),
                    () -> page.evaluate("() => wp.data.dispatch('core/editor').autosave()"));
            assertThat(autosaved.status()).isEqualTo(200);
            // the author's own draft: the autosave is the post itself
            assertThat(autosaved.text()).contains("\"id\":" + id + ",");

            String future = LocalDateTime.now(ZoneOffset.UTC).plusDays(7).format(LOCAL);
            page.evaluate("date => wp.data.dispatch('core/editor').editPost({ status: 'future', date })", future);
            assertThat(save(page, "posts", id).status()).isEqualTo(200);
            assertThat(setup.statusOf("bob", "posts", id)).contains("future");
            assertThat(editorNotices(page)).contains("Post scheduled.");

            String past = LocalDateTime.now(ZoneOffset.UTC).minusHours(1).format(LOCAL);
            page.evaluate("date => wp.data.dispatch('core/editor').editPost({ status: 'publish', date })", past);
            assertThat(save(page, "posts", id).status()).isEqualTo(200);
            assertThat(setup.statusOf("bob", "posts", id)).contains("publish");
            // a scheduled post already counts as published for the editor: publishing it now is an update
            assertThat(editorNotices(page)).as("after publishing").contains("Post updated.");
            assertThat(setup.rest("bob", "/", "wp/v2/posts/" + id + "?context=edit")
                            .getString("content.raw"))
                    .contains("wp:image")
                    .contains("First words.")
                    .doesNotContain("blob:");
        }
    }

    @Test
    void editAPageWithAParent() {
        String parentTitle = "Parent " + token();
        String childTitle = "Child " + token();
        long parent = setup.post("bob", "page", "publish", parentTitle);
        long child = setup.post("bob", "page", "publish", childTitle);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = browsers.signedIn(context, "bob", browsers.adminUrl("/") + "edit.php?post_type=page");
            // opened from the list, which knows the post is a page: no trial of the posts route
            row(page, childTitle)
                    .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(childTitle))
                    .click();
            awaitMounted(page);
            assertThat(page.url()).isEqualTo(editUrl("/", child));
            assertThat(page.locator("#qp-editor-title").textContent()).isEqualTo("Edit Page");

            page.evaluate("parent => wp.data.dispatch('core/editor').editPost({ parent })", Math.toIntExact(parent));
            assertThat(save(page, "pages", child).status()).isEqualTo(200);
            assertThat(setup.rest("bob", "/", "wp/v2/pages/" + child + "?context=edit")
                            .getLong("parent"))
                    .isEqualTo(parent);
            assertThat(browsers.requests(page)).noneMatch(request -> request.contains("/wp-json/wp/v2/posts/" + child));
        }
    }

    @Test
    void insertASyncedPattern() {
        long post = setup.post("bob", "post", "draft", "Pattern host " + token());
        long ref;
        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            ref = ((Number)
                            page.evaluate(
                                    "async () => (await wp.data.dispatch('core').saveEntityRecord('postType',"
                                            + " 'wp_block', { title: 'Synced " + token() + "', status: 'publish',"
                                            + " content: '<!-- wp:paragraph -->\\n<p>Synced text.</p>\\n<!-- /wp:paragraph -->' })).id"))
                    .longValue();
            page.evaluate(
                    "ref => wp.data.dispatch('core/block-editor').insertBlocks(wp.blocks.createBlock('core/block', { ref }))",
                    Math.toIntExact(ref));
            assertThat(save(page, "posts", post).status()).isEqualTo(200);
            assertThat(setup.rest("bob", "/", "wp/v2/posts/" + post + "?context=edit")
                            .getString("content.raw"))
                    .contains("<!-- wp:block {\"ref\":" + ref + "} /-->");
        }

        String content = "<!-- wp:paragraph -->\n<p>Own text.</p>\n<!-- /wp:paragraph -->\n\n<!-- wp:block {\"ref\":"
                + ref + "} /-->";
        for (String user : List.of("carol", "dave")) {
            long own = setup.post(user, "post", "draft", "Own " + user + " " + token(), content, null);
            try (BrowserContext context = browsers.context(user)) {
                Page page = listAs(context, user);
                Response block = page.waitForResponse(
                        response -> response.url().contains("/wp-json/wp/v2/blocks/" + ref + "?"),
                        () -> page.navigate(editUrl("/", own)));
                // another user's synced pattern is not the author's or the contributor's to read in edit context
                assertThat(block.status()).as(user).isEqualTo(403);
                awaitMounted(page);

                page.evaluate("() => wp.data.dispatch('core/editor').setIsInserterOpened({ tab: 'patterns' })");
                page.locator(".block-editor-inserter__panel-content, .block-editor-inserter__menu")
                        .first()
                        .waitFor();
                page.locator("[role=tab], [role=option], .block-editor-inserter__category-panel button, "
                                + ".block-editor-block-patterns-explorer__sidebar__categories-list button, "
                                + ".block-editor-inserter__block-patterns-tabs button")
                        .first()
                        .waitFor();
                Locator mine = page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("My patterns"))
                        .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("My patterns")))
                        .or(page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName("My patterns")));
                assertThat(mine.count()).as(user).isZero();
                page.evaluate("() => wp.data.dispatch('core/editor').setIsInserterOpened(false)");

                page.evaluate("() => { const b = wp.data.select('core/block-editor').getBlocks()"
                        + ".find(x => x.name === 'core/paragraph');"
                        + " wp.data.dispatch('core/block-editor').selectBlock(b.clientId); }");
                page.locator(".block-editor-block-toolbar button[aria-label='Options']")
                        .first()
                        .click();
                page.getByRole(AriaRole.MENUITEM).first().waitFor();
                boolean create =
                        page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Create pattern"))
                                        .count()
                                > 0;
                assertThat(create).as(user).isEqualTo(user.equals("carol"));
                page.keyboard().press("Escape");
                takeExpectedFailures(403);
            }
        }
    }

    @Test
    void preloadsAreConsumedAndThenCleared() {
        String title = "Preloaded " + token();
        setup.post("bob", "post", "publish", title);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            int listed = browsers.requests(page).size();
            List<String> refused = new CopyOnWriteArrayList<>();
            page.onResponse(response -> {
                if (response.status() >= 400) {
                    refused.add(response.status() + " " + response.request().method() + " " + response.url());
                }
            });
            row(page, title)
                    .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(title))
                    .click();
            awaitMounted(page);
            page.waitForCondition(() ->
                    browsers.console(page).stream().anyMatch(message -> message.contains("[api-fetch][preload]")));
            // The preloads are cleared once the editor's first wave of reads settles. That wave reads the theme's base
            // global styles, their variations and the front page's template lookup, routes QuarkPress does not serve
            // (they come with the theme engine): their 404s end the wave, so the second wave, whose read of the post's
            // author would consume its preload, never runs, and api-fetch names that entry when it clears the rest.
            // This
            // warning is QuarkPress's expected behaviour, a documented difference; WordPress, which answers every read
            // of the first wave, logs "All preloads consumed." at log level instead.
            assertThat(refused)
                    .anyMatch(answer ->
                            answer.startsWith("404 GET ") && answer.contains("/wp-json/wp/v2/global-styles/themes/"))
                    .anyMatch(answer -> answer.startsWith("404 GET ")
                            && answer.contains("/wp-json/wp/v2/templates/lookup?slug=front-page"));
            assertThat(browsers.console(page))
                    .as("refused at boot: %s", refused)
                    .filteredOn(message -> message.contains("[api-fetch][preload]"))
                    .containsExactly(
                            "warning: [api-fetch][preload] Some preloads were never consumed: [GET /wp/v2/users/"
                                    + setup.userId("bob") + "?_fields=id%2Cname&context=view]");

            List<String> boot = browsers.requests(page);
            List<String> editorRequests = boot.subList(listed, boot.size());
            assertThat(editorRequests)
                    .noneMatch(request -> request.startsWith("GET ")
                            && request.contains("/wp-json/wp/v2/types?")
                            && request.contains("context=view"))
                    // the editor's read of the current user is the preloaded one
                    .noneMatch(request -> request.startsWith("GET ")
                            && (request.contains("/wp-json/wp/v2/users/me?") && !request.contains("context=edit")
                                    || request.endsWith("/wp-json/wp/v2/users/me")))
                    .noneMatch(request -> request.startsWith("GET ") && request.contains("/wp-json/wp/v2/settings"))
                    .noneMatch(request -> request.startsWith("OPTIONS ") && request.contains("/wp-json/wp/v2/media"));
            // the shell reads the user's stored preferences once, in the edit context, which the preloaded user record
            // (view context) does not carry, and hands them to the preferences store, as WordPress prints them into its
            // page: the store asks for nothing more
            assertThat(editorRequests)
                    .filteredOn(request -> request.contains("/wp-json/wp/v2/users/me?"))
                    .containsExactly("GET " + browsers.root() + "wp-json/wp/v2/users/me?context=edit");

            Request media = page.waitForRequest(
                    request -> request.method().equals("GET")
                            && request.url().contains("/wp-json/wp/v2/media?")
                            && request.url().contains("per_page=1"),
                    () -> page.evaluate("() => wp.data.dispatch('core/editor').setIsInserterOpened({ tab: 'media' })"));
            assertThat(media.url()).contains("context=edit").contains("media_type=");
            assertThat(browsers.requests(page))
                    .noneMatch(request -> request.startsWith("OPTIONS ") && request.contains("/wp-json/wp/v2/media"));
        }
    }

    @Test
    void theDataViewsMediaModalNeedsNoAdminAjax() throws IOException {
        String file = "qp-modal-" + token();
        Path png = Files.createDirectories(Path.of("target", "flow-files")).resolve(file + ".png");
        ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        long attachment = setup.upload("bob", png);
        long post = setup.post("bob", "post", "draft", "Modal " + token());

        try (BrowserContext context = browsers.context("bob")) {
            AtomicInteger adminAjax = new AtomicInteger();
            context.route("**/admin-ajax.php**", route -> {
                adminAjax.incrementAndGet();
                route.abort();
            });
            Page page = editorAs(context, "bob", post);

            page.evaluate("() => wp.data.dispatch('core/block-editor')"
                    + ".insertBlocks(wp.blocks.createBlock('core/image'))");
            Response listed = page.waitForResponse(
                    response -> response.url().contains("/wp-json/wp/v2/media?")
                            && response.request().method().equals("GET"),
                    () -> Browsers.canvas(page)
                            .locator("[data-type='core/image']")
                            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Media Library"))
                            .click());
            assertThat(listed.status()).isEqualTo(200);
            assertThat(listed.text()).contains("\"id\":" + attachment);
            Locator modal = page.getByRole(AriaRole.DIALOG);
            modal.first().waitFor();
            modal.first().locator("img[src*='" + file + "']").first().waitFor();
            page.keyboard().press("Escape");
            modal.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));

            page.evaluate("() => wp.data.dispatch('core/edit-post').openGeneralSidebar('edit-post/document')");
            page.getByText("Set featured image").first().click();
            page.getByRole(AriaRole.DIALOG).first().waitFor();

            assertThat(adminAjax.get()).isZero();
            assertThat(browsers.console(page)).noneMatch(message -> message.contains("wp.media"));
        }
    }

    @Test
    void a401IsRetriedOnceWithAFreshToken() {
        long post = setup.post("bob", "post", "publish", "Retried " + token());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            List<String> authorizations = new CopyOnWriteArrayList<>();
            page.route(url -> url.contains("/wp-json/wp/v2/posts/" + post + "?"), route -> {
                if (isSave(route.request(), "posts", post)) {
                    authorizations.add(route.request().headers().get("authorization"));
                    if (authorizations.size() == 1) {
                        route.fulfill(new Route.FulfillOptions()
                                .setStatus(401)
                                .setContentType("application/json")
                                .setBody(NOT_LOGGED_IN));
                        return;
                    }
                }
                route.resume();
            });
            int before = browsers.requests(page).size();

            page.evaluate("() => wp.data.dispatch('core/editor').editPost({ title: 'Retried and saved' })");
            page.evaluate("() => wp.data.dispatch('core/editor').savePost()");

            assertThat(authorizations).hasSize(2);
            assertThat(authorizations.get(0)).startsWith("Bearer ");
            assertThat(authorizations.get(1)).startsWith("Bearer ").isNotEqualTo(authorizations.get(0));
            List<String> sent = browsers.requests(page);
            List<String> during = sent.subList(before, sent.size());
            int first =
                    indexOf(during, request -> request.startsWith("POST ") && request.contains("/posts/" + post + "?"));
            int token = indexOf(during, request -> request.contains("/protocol/openid-connect/token"));
            int second = lastIndexOf(
                    during, request -> request.startsWith("POST ") && request.contains("/posts/" + post + "?"));
            assertThat(first).isNotNegative().isLessThan(token);
            assertThat(token).isLessThan(second);
            assertThat(page.evaluate("() => wp.data.select('core/editor').didPostSaveRequestSucceed()"))
                    .isEqualTo(true);
            assertThat(editorNotices(page)).noneMatch(text -> text.startsWith("Updating failed."));
            assertThat(setup.rest("bob", "/", "wp/v2/posts/" + post + "?context=edit")
                            .getString("title.raw"))
                    .isEqualTo("Retried and saved");
            takeExpectedFailures(401);
        }
    }

    @Test
    void aFailedRenewalShowsTheSessionOverlayAndLocksSaving() {
        long post = setup.post("bob", "post", "publish", "Expired " + token());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            AtomicInteger saves = new AtomicInteger();
            AtomicInteger renewals = new AtomicInteger();
            page.route(url -> url.contains("/wp-json/wp/v2/posts/" + post + "?"), route -> {
                if (isSave(route.request(), "posts", post)) {
                    saves.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions()
                            .setStatus(401)
                            .setContentType("application/json")
                            .setBody(NOT_LOGGED_IN));
                    return;
                }
                route.resume();
            });
            context.route("**/protocol/openid-connect/token", route -> {
                renewals.incrementAndGet();
                route.abort();
            });

            page.evaluate("() => wp.data.dispatch('core/editor').editPost({ title: 'Never saved' })");
            page.evaluate("() => wp.data.dispatch('core/editor').savePost()");

            assertThat(saves.get()).isEqualTo(1);
            assertThat(renewals.get()).isEqualTo(1);
            page.getByText(SAVE_FAILED_UNAUTHENTICATED).first().waitFor();
            Locator overlay = page.getByText(
                    "Your session has expired. You can log in again from this page or go to the login page.");
            overlay.waitFor();
            assertThat(page.getByRole(AriaRole.ALERTDIALOG)
                            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Log in again"))
                            .count())
                    .isEqualTo(1);
            assertThat(page.evaluate("() => wp.data.select('core/editor').isPostSavingLocked()"))
                    .isEqualTo(true);
            assertThat(page.evaluate("() => wp.data.select('core/editor').isPostAutosavingLocked()"))
                    .isEqualTo(true);
            assertThat(editorNotices(page)).containsOnlyOnce(SAVE_FAILED_UNAUTHENTICATED);
            assertThat(setup.rest("bob", "/", "wp/v2/posts/" + post + "?context=edit")
                            .getString("title.raw"))
                    .startsWith("Expired ");
            List<String> errors = browsers.takeErrors();
            assertThat(errors)
                    .isNotEmpty()
                    .allSatisfy(
                            error -> assertThat(error.contains("status of 401") || error.contains("net::ERR_FAILED"))
                                    .as(error)
                                    .isTrue());
        }
    }

    @Test
    void omittedPreloadsDegradeWithoutBreaking() {
        long post = setup.post("bob", "post", "publish", "Degraded " + token());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            List<String> omitted = new CopyOnWriteArrayList<>();
            page.onResponse(response -> {
                if (Browsers.OMITTED_ROUTES.stream()
                        .anyMatch(route -> response.url().contains(route))) {
                    omitted.add(response.status() + " " + response.url());
                }
            });
            page.navigate(editUrl("/", post));
            awaitMounted(page);
            page.waitForCondition(() -> omitted.stream().anyMatch(answer -> answer.startsWith("404 ")));

            assertThat(omitted)
                    .anyMatch(answer -> answer.startsWith("404 ")
                            && (answer.contains("/wp/v2/templates/lookup?slug=")
                                    || answer.contains("/wp/v2/global-styles")));
            assertThat(page.locator(".editor-error-boundary").count()).isZero();
            assertThat(page.getByText("Attempt recovery").count()).isZero();
            assertThat(page.getByText("The editor has encountered an unexpected error.")
                            .count())
                    .isZero();
            assertThat(browsers.takeOmitted()).isNotEmpty();
        }
    }

    @Test
    void aClassicPostOpensAsAClassicBlockAndEditsWithTinyMCE() {
        long post = setup.post("bob", "post", "publish", "Classic " + token(), "<p>Classic text</p>", null);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            List<String> tinymce = new CopyOnWriteArrayList<>();
            page.onResponse(response -> {
                if (response.url().contains("/wp-includes/js/tinymce/")) {
                    tinymce.add(response.status() + " " + response.url());
                }
            });
            page.navigate(editUrl("/", post));
            awaitMounted(page);

            assertThat(page.evaluate("() => wp.blocks.getFreeformContentHandlerName()"))
                    .isEqualTo("core/freeform");
            assertThat(page.evaluate("() => wp.data.select('core/block-editor').getBlocks().map(b => b.name)"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .containsExactly("core/freeform");
            page.evaluate("() => wp.data.dispatch('core/block-editor')"
                    + ".selectBlock(wp.data.select('core/block-editor').getBlocks()[0].clientId)");
            page.locator(".block-editor-block-toolbar button")
                    .filter(new Locator.FilterOptions().setHasText("Edit"))
                    .first()
                    .click();
            page.locator(".block-editor-freeform-modal__content").waitFor();
            page.waitForFunction("() => window.tinymce && tinymce.editors.length > 0");
            page.waitForFunction("() => tinymce.editors[0].initialized");

            assertThat(page.evaluate("() => tinymce.editors[0].getContent()"))
                    .asString()
                    .contains("Classic text");
            assertThat(tinymce)
                    .isNotEmpty()
                    .allSatisfy(answer -> assertThat(answer).startsWith("200 "));
            assertThat(tinymce).anyMatch(answer -> answer.contains("/tinymce/themes/modern/theme.min.js"));
            assertThat(tinymce).noneMatch(answer -> answer.contains("/tinymce/langs/"));
            assertThat(browsers.requests(page)).noneMatch(request -> request.contains("admin-ajax.php"));
        }
    }

    @Test
    void aMissingPostShowsTheNotFoundMessage() {
        String title = "Vanishing " + token();
        long post = setup.post("bob", "post", "draft", title);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            row(page, title).waitFor();
            setup.deletePost(post);

            row(page, title)
                    .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(title))
                    .click();

            assertThat(outcome(page)).isEqualTo("notFound");
            assertThat(page.locator("#qp-main").innerText())
                    .contains("You attempted to edit an item that does not exist. Perhaps it was deleted?");
            assertThat(page.locator("#editor > *").count()).isZero();
            assertThat(page.evaluate("() => window._wpLoadBlockEditor === undefined"))
                    .isEqualTo(true);
            takeExpectedFailures(404);
        }
    }

    @Test
    void aTrashedPostIsNotOpened() {
        long post = setup.post("bob", "post", "publish", "Trashed " + token());
        setup.trash("bob", "posts", post);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            page.navigate(editUrl("/", post));

            assertThat(outcome(page)).isEqualTo("trash");
            assertThat(page.locator("#qp-main").innerText())
                    .contains("You cannot edit this item because it is in the Trash. Please restore it and try"
                            + " again.");
            assertThat(page.evaluate("() => window._wpLoadBlockEditor === undefined"))
                    .isEqualTo(true);
        }
    }

    @Test
    void revisionAddressesPointBackToTheEditor() {
        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            page.navigate(browsers.adminUrl("/") + "revision.php?revision=3");

            assertThat(outcome(page)).isEqualTo("revision");
            assertThat(page.locator("#qp-main").innerText())
                    .contains("Revisions are shown inside the editor: open the post and choose the revision.");
            assertThat(page.locator("#qp-main")
                            .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("All Posts"))
                            .getAttribute("href"))
                    .isEqualTo("edit.php");
        }
    }

    @Test
    void aNewerAutosaveByAnotherUserIsOffered() {
        long post = setup.post("bob", "post", "publish", "Autosaved " + token());
        setup.backdate(post, "2026-01-01T10:00:00");
        // alice is a member of the site (see roles()): bob may read her user record, but not in the edit context
        long autosave = setup.autosave(post, "alice", "Alice's newer title", "Alice's newer words.");
        long alice = setup.userId("alice");

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            Locator notice =
                    page.getByText("There is an autosave of this post that is more recent than the version below.");
            notice.first().waitFor();

            Request[] revisions = new Request[1];
            Request user = page.waitForRequest(
                    request -> request.url().contains("/wp-json/wp/v2/users/" + alice + "?")
                            && request.url().contains("context=edit"),
                    () -> revisions[0] = page.waitForRequest(
                            request -> request.url().contains("/wp-json/wp/v2/posts/" + post + "/revisions?"),
                            () -> page.getByRole(
                                            AriaRole.BUTTON, new Page.GetByRoleOptions().setName("View the autosave"))
                                    .click()));
            assertThat(revisions[0].url())
                    .contains("per_page=100")
                    .contains("page=1")
                    .contains("context=edit")
                    .contains("orderby=date")
                    .contains("order=desc")
                    .contains("_fields=");
            assertThat(user.response().status()).isEqualTo(403);
            page.waitForURL(editUrl("/", post) + "&revision=" + autosave);
            takeExpectedFailures(403);
        }
    }

    @Test
    void dateSettingsFollowTheSite() {
        setup.createSite("/fv03/", "Site three");
        setup.membership("/fv03/", "carol", "author");
        setup.optionOn("/fv03/", "timezone_string", "\"Europe/Paris\"");
        setup.optionOn("/fv03/", "date_format", "\"d/m/Y\"");
        setup.optionOn("/fv03/", "time_format", "\"H:i\"");
        setup.optionOn("/fv03/", "start_of_week", "0");
        String title = "Dated " + token();
        long post = setup.postOn("/fv03/", "carol", "post", "draft", title);
        String abbr = setup.rest("carol", "/fv03/", "quarkpress/v1/editor/post/" + post)
                .getString("dateSettings.timezone.abbr");
        assertThat(abbr).isIn("CET", "CEST");

        try (BrowserContext context = browsers.context("carol")) {
            Page page = browsers.signedIn(context, "carol", browsers.adminUrl("/fv03/"));
            row(page, title)
                    .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(title))
                    .click();
            awaitMounted(page);

            @SuppressWarnings("unchecked")
            Map<String, Object> settings =
                    (Map<String, Object>) page.evaluate("() => { const s = wp.date.getSettings();"
                            + " return { string: s.timezone.string, abbr: s.timezone.abbr, offset: String(s.timezone.offset),"
                            + " offsetFormatted: s.timezone.offsetFormatted, date: s.formats.date, time: s.formats.time,"
                            + " datetime: s.formats.datetime, abbreviated: s.formats.datetimeAbbreviated,"
                            + " startOfWeek: s.l10n.startOfWeek, locale: s.l10n.locale, dow: moment.localeData('en_US')"
                            + ".firstDayOfWeek() , lt: moment.localeData('en_US').longDateFormat('LT'),"
                            + " ll: moment.localeData('en_US').longDateFormat('LL')}; }");
            assertThat(settings)
                    .containsEntry("string", "Europe/Paris")
                    .containsEntry("abbr", abbr)
                    .containsEntry("date", "d/m/Y")
                    .containsEntry("time", "H:i")
                    .containsEntry("datetime", "F j, Y g:i a")
                    .containsEntry("abbreviated", "M j, Y g:i a")
                    .containsEntry("startOfWeek", 0)
                    .containsEntry("locale", "en_US")
                    .containsEntry("dow", 0)
                    // moment's own formats follow the site's, as WordPress sets them
                    .containsEntry("lt", "H:i")
                    .containsEntry("ll", "d/m/Y");
            assertThat(settings.get("offset")).isIn("1", "2");
            assertThat(settings.get("offsetFormatted")).isEqualTo(settings.get("offset"));
            assertThat(browsers.requests(page)).noneMatch(request -> request.contains("/wp-json/wp/v2/settings"));
        }
    }

    @Test
    void theShellRetriesARefusedRequestOnceWithAFreshToken() {
        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            List<String> authorizations = new CopyOnWriteArrayList<>();
            page.route(url -> url.contains("/wp-json/wp/v2/posts?"), route -> {
                if (route.request().url().contains("per_page=20")) {
                    authorizations.add(route.request().headers().get("authorization"));
                    if (authorizations.size() == 1) {
                        route.fulfill(new Route.FulfillOptions()
                                .setStatus(401)
                                .setContentType("application/json")
                                .setBody(NOT_LOGGED_IN));
                        return;
                    }
                }
                route.resume();
            });
            int before = browsers.requests(page).size();

            Response listed = page.waitForResponse(
                    response -> response.url().contains("/wp-json/wp/v2/posts?")
                            && response.url().contains("per_page=20")
                            && response.status() == 200,
                    () -> page.reload());
            page.locator("#qp-main[aria-busy=false]").waitFor();

            assertThat(listed.status()).isEqualTo(200);
            assertThat(authorizations).hasSize(2);
            assertThat(authorizations.get(1)).startsWith("Bearer ").isNotEqualTo(authorizations.get(0));
            List<String> sent = browsers.requests(page);
            List<String> during = sent.subList(before, sent.size());
            int first = indexOf(
                    during, request -> request.contains("/wp-json/wp/v2/posts?") && request.contains("per_page=20"));
            int token = indexOf(during, request -> request.contains("/protocol/openid-connect/token"));
            int second = lastIndexOf(
                    during, request -> request.contains("/wp-json/wp/v2/posts?") && request.contains("per_page=20"));
            assertThat(first).isNotNegative().isLessThan(token);
            assertThat(token).isLessThan(second);
            assertThat(heading(page).textContent()).isEqualTo("Posts");
            assertThat(page.getByRole(AriaRole.TABLE).count()).isEqualTo(1);
            takeExpectedFailures(401);
        }
    }

    /** The canvas iframe's style sheets, in the order WordPress resolves them for a theme without theme.json. */
    private static final List<String> CANVAS_STYLES = List.of(
            "wp-theme",
            "wp-base-styles",
            "wp-components",
            "common",
            "forms",
            "wp-reset-editor-styles",
            "wp-block-library",
            "wp-block-editor-content",
            "wp-editor-classic-layout-styles",
            "wp-edit-blocks",
            "classic-theme-styles");

    @Test
    void theCanvasGetsWordPresssStylesThePresetsAndThePolyfill() {
        long post = setup.post("bob", "post", "draft", "Canvas " + token());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            page.evaluate("() => wp.data.dispatch('core/block-editor').insertBlocks(wp.blocks.createBlock("
                    + "'core/buttons', {}, [wp.blocks.createBlock('core/button', { text: 'Go' })]))");
            FrameLocator canvas = Browsers.canvas(page);
            Locator button = canvas.locator(".wp-block-button__link").first();
            button.waitFor();

            // the block library's own rule: without the canvas styles a Button's link is a plain block
            assertThat(button.evaluate("a => getComputedStyle(a).display")).isEqualTo("inline-block");
            for (String handle : CANVAS_STYLES) {
                assertThat(canvas.locator("link#" + handle + "-css").getAttribute("href"))
                        .as(handle)
                        .startsWith(browsers.root() + "wp-")
                        .contains("?ver=");
            }
            assertThat(canvas.locator("style#global-styles-css-custom-properties-inline-css")
                            .textContent())
                    .contains(":root{")
                    .contains("--wp--preset--color--")
                    .contains("--wp--preset--font-size--");
            // the editor loads the iframe's one script anew, by its address
            canvas.locator("script[src='" + browsers.root()
                            + "wp-includes/js/dist/vendor/wp-polyfill.min.js?ver=3.15.0']")
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
            assertThat(page.evaluate("() => wp.data.select('core/editor').getEditorSettings().defaultEditorStyles"
                            + ".map(style => style.css.length > 0)"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .containsExactly(true);
        }
    }

    @Test
    void theServersNewerPreferencesWinOverTheBrowsersCopy() {
        long post = setup.post("bob", "post", "draft", "Preferences " + token());
        long bob = setup.userId("bob");
        setup.as("bob")
                .contentType("application/json")
                .body("{\"meta\":{\"persisted_preferences\":{\"_modified\":\"2026-11-16T10:00:00.000Z\","
                        + "\"core/edit-post\":{\"welcomeGuide\":false},\"qp-test\":{\"marker\":\"server\"}}}}")
                .post("/wp-json/wp/v2/users/me")
                .then()
                .statusCode(200);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            // an older copy in this browser, as another session left it
            page.evaluate(
                    "id => localStorage.setItem('WP_PREFERENCES_USER_' + id, JSON.stringify({"
                            + " _modified: '2020-01-01T00:00:00.000Z', 'core/edit-post': { welcomeGuide: false },"
                            + " 'qp-test': { marker: 'local' } }))",
                    Math.toIntExact(bob));
            page.navigate(editUrl("/", post));
            assertThat(outcome(page)).isEqualTo("mounted");
            Browsers.awaitEditor(page);

            assertThat(page.evaluate("() => wp.data.select('core/preferences').get('qp-test', 'marker')"))
                    .isEqualTo("server");
        }
    }

    @Test
    void aSuperAdminWhoIsNotAMemberEditsAndItsPreferenceWritesAreRefused() {
        setup.createSite("/fv04/", "Site four");
        setup.membership("/fv04/", "bob", "editor");
        long post = setup.postOn("/fv04/", "bob", "post", "draft", "Network admin " + token());

        try (BrowserContext context = browsers.context("alice")) {
            Page page = browsers.signedIn(context, "alice", browsers.adminUrl("/fv04/"));
            page.locator("#qp-main[aria-busy=false]").waitFor();
            List<String> me = new CopyOnWriteArrayList<>();
            page.onResponse(response -> {
                if (response.url().contains("/fv04/wp-json/wp/v2/users/me")) {
                    me.add(response.status() + " " + response.request().method());
                }
            });
            page.navigate(editUrl("/fv04/", post));
            awaitMounted(page);
            // the preview session's cookie belongs to the site's path
            assertThat(previewCookie(context).path).isEqualTo("/fv04/");

            // WordPress answers any signed-in user's own record, but refuses its writes on a site it is no member of
            Response write = page.waitForResponse(
                    response -> response.url().contains("/fv04/wp-json/wp/v2/users/me")
                            && "PUT".equals(response.request().headers().get("x-http-method-override")),
                    () -> page.evaluate("() => wp.data.dispatch('core/preferences').set('qp-test', 'marker', 'x')"));
            assertThat(write.status()).isEqualTo(404);
            assertThat(me).contains("200 GET");
            assertThat(page.locator(".editor-error-boundary").count()).isZero();
            assertThat(page.evaluate("() => wp.data.select('core/editor').getCurrentPostId()"))
                    .isEqualTo(Math.toIntExact(post));
            takeExpectedFailures(404);
        }
    }

    /** Makes the page's tab remember the type of a post of its site, as the lists do for their rows. */
    private static void remember(Page page, long id, String type) {
        page.evaluate(
                "([id, type]) => sessionStorage.setItem('qp-type:' + id,"
                        + " JSON.stringify({ site: new URL('.', location.href).href, type }))",
                java.util.Arrays.asList((Object) Math.toIntExact(id), type));
    }

    @Test
    void theEditorRoutesOwnRefusalsAreShownWhenTheTypeIsKnown() {
        long bobs = setup.post("bob", "post", "draft", "Not dave's " + token());
        long trashed = setup.post("bob", "post", "publish", "In the trash " + token());
        setup.trash("bob", "posts", trashed);

        try (BrowserContext context = browsers.context("dave")) {
            Page page = listAs(context, "dave");
            remember(page, bobs, "post");
            Response route = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/quarkpress/v1/editor/post/" + bobs),
                    () -> page.navigate(editUrl("/", bobs)));

            assertThat(route.status()).isEqualTo(403);
            assertThat(outcome(page)).isEqualTo("forbidden");
            assertThat(page.locator("#qp-main").innerText()).contains("Sorry, you are not allowed to edit this item.");
            assertThat(page.evaluate("() => window._wpLoadBlockEditor === undefined"))
                    .isEqualTo(true);
            assertThat(browsers.requests(page)).noneMatch(request -> request.contains("_fields=id,type,status"));
            takeExpectedFailures(403);
        }
        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            remember(page, trashed, "post");
            Response route = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/quarkpress/v1/editor/post/" + trashed),
                    () -> page.navigate(editUrl("/", trashed)));

            assertThat(route.status()).isEqualTo(200);
            assertThat(outcome(page)).isEqualTo("trash");
            assertThat(page.locator("#qp-main").innerText())
                    .contains("You cannot edit this item because it is in the Trash. Please restore it and try"
                            + " again.");
            assertThat(page.evaluate("() => window._wpLoadBlockEditor === undefined"))
                    .isEqualTo(true);
        }
    }

    // ---- post locks

    private static final String LOCKED_TEXT = "Bob Baker is currently working on this post, which means you cannot make"
            + " changes, unless you take over.";

    private static final String TAKE_OVER_WARNING = "If you take over, the other user will lose editing control to the"
            + " post, but their changes will be saved.";

    private static final String TAKEN_OVER_TEXT = "Alice Anders now has editing control of this post. Don't worry, your"
            + " changes up to this moment have been saved.";

    /** Whether the request is a {@code method} request of post {@code id}'s lock route (the shell sends real methods). */
    private static boolean isLock(Request request, String method, long id) {
        return request.method().equals(method) && request.url().endsWith("/wp-json/quarkpress/v1/locks/" + id);
    }

    /** The requests the page sent since the {@code from}th, as {@link Browsers#requests} lists them. */
    private static List<String> requestsSince(Page page, int from) {
        List<String> all = browsers.requests(page);
        return all.subList(Math.min(from, all.size()), all.size());
    }

    /** Answers Gravatar's avatar requests with a blank image, so that the dialogs' avatars load without the network. */
    private static void blankAvatars(BrowserContext context) throws IOException {
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
        byte[] image = png.toByteArray();
        context.route(
                "https://secure.gravatar.com/**",
                route -> route.fulfill(new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("image/png")
                        .setBodyBytes(image)));
    }

    private static Locator lockDialog(Page page) {
        return page.getByRole(AriaRole.ALERTDIALOG);
    }

    /** The lock's time and user, {@code "<time>:<user>"}. */
    private static long[] lockParts(String value) {
        int colon = value.indexOf(':');
        return new long[] {Long.parseLong(value.substring(0, colon)), Long.parseLong(value.substring(colon + 1))};
    }

    @Test
    void openingTakesTheLockAndLeavingReleasesIt() throws InterruptedException {
        long post = setup.post("bob", "post", "publish", "Locked " + token());
        long bob = setup.userId("bob");

        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            Response taken = page.waitForResponse(
                    response -> isLock(response.request(), "POST", post), () -> page.navigate(editUrl("/", post)));
            assertThat(taken.status()).isEqualTo(200);
            awaitMounted(page);
            String held = (String) page.evaluate("() => quarkpressAdmin.locks.state.activePostLock");
            assertThat(held).matches("^\\d+:\\d+$");
            assertThat(lockParts(held)[1]).isEqualTo(bob);
            assertThat(setup.lockOf(post)).contains(held);
            // the editor's own lock dialog never has anything to show
            assertThat(page.evaluate("() => wp.data.select('core/editor').isPostLocked()"))
                    .isEqualTo(false);

            AtomicReference<Object> result = new AtomicReference<>();
            Response refreshed = page.waitForResponse(
                    response -> isLock(response.request(), "PUT", post),
                    () -> result.set(page.evaluate("() => quarkpressAdmin.locks.refresh()")));
            assertThat(result.get()).isEqualTo("refreshed");
            assertThat(refreshed.status()).isEqualTo(200);
            assertThat(refreshed.text()).contains("\"new_lock\":").contains("\"release_token\":");
            String renewed = (String) page.evaluate("() => quarkpressAdmin.locks.state.activePostLock");
            assertThat(setup.lockOf(post)).contains(renewed);

            // leaving the page (not closing it, which need not run the page's leaving handlers) releases the lock;
            // Chromium sends the release after the page is gone, so only the application sees it arrive
            ItRequestLog.clear();
            page.navigate(browsers.adminUrl("/") + "edit.php");
            assertThat(ItRequestLog.await("/wp-json/quarkpress/v1/locks/" + post + "?", Duration.ofSeconds(60)))
                    .isEqualTo("POST /wp-json/quarkpress/v1/locks/" + post + "?_method=DELETE 200");
            page.locator("#qp-main[aria-busy=false]").waitFor();

            // released as WordPress releases: the lock stays bob's, aged past its window less a few seconds
            String value = setup.lockOf(post).orElseThrow();
            assertThat(lockParts(value)[1]).isEqualTo(bob);
            assertThat(java.time.Instant.now().getEpochSecond() - lockParts(value)[0])
                    .isGreaterThanOrEqualTo(140);
        }
    }

    @Test
    void aLockedPostShowsTheNoticeAndTakeOverMounts() throws IOException {
        long post = setup.post("bob", "post", "publish", "Held " + token());
        setup.lock(post, "bob", 10);

        try (BrowserContext context = browsers.context("alice")) {
            blankAvatars(context);
            Page page = listAs(context, "alice");
            Response taken = page.waitForResponse(
                    response -> isLock(response.request(), "POST", post), () -> page.navigate(editUrl("/", post)));
            assertThat(taken.text()).contains("\"isLocked\":true");
            Locator dialog = lockDialog(page);
            dialog.getByRole(
                            AriaRole.HEADING,
                            new Locator.GetByRoleOptions().setName("This post is already being edited"))
                    .waitFor();
            assertThat(dialog.locator("p").allTextContents()).containsExactly(LOCKED_TEXT, TAKE_OVER_WARNING);
            assertThat(dialog.locator("strong").textContent()).isEqualTo("Bob Baker");
            assertThat(dialog.locator("img").getAttribute("src")).startsWith("https://secure.gravatar.com/avatar/");
            assertThat(dialog.getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Exit editor"))
                            .getAttribute("href"))
                    .isEqualTo(browsers.adminUrl("/") + "edit.php?post_type=post");
            // the editor is not mounted under the dialog, and nothing was taken
            assertThat(page.locator("#editor > *").count()).isZero();
            assertThat(page.evaluate("() => window._wpLoadBlockEditor === undefined"))
                    .isEqualTo(true);
            assertThat(lockParts(setup.lockOf(post).orElseThrow())[1]).isEqualTo(setup.userId("bob"));

            Response takeover = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/quarkpress/v1/locks/" + post + "/takeover"),
                    () -> dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Take over"))
                            .click());
            assertThat(takeover.status()).isEqualTo(200);
            awaitMounted(page);
            assertThat(page.locator("#qp-lock").count()).isZero();
            assertThat(lockParts(setup.lockOf(post).orElseThrow())[1]).isEqualTo(setup.userId("alice"));
            // the route still reported bob's lock: the editor's own dialog must not show it
            assertThat(page.evaluate("() => wp.data.select('core/editor').isPostLocked()"))
                    .isEqualTo(false);
        }

        setup.lock(post, "bob", 10);
        try (BrowserContext context = browsers.context("alice")) {
            blankAvatars(context);
            Page page = listAs(context, "alice");
            page.navigate(editUrl("/", post));
            Locator exit =
                    lockDialog(page).getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Exit editor"));
            page.waitForResponse(response -> response.url().contains("/wp-json/wp/v2/posts?"), exit::click);
            page.waitForURL(browsers.adminUrl("/") + "edit.php?post_type=post");
            page.locator("#qp-main[aria-busy=false]").waitFor();
            assertThat(heading(page).textContent()).isEqualTo("Posts");
            assertThat(lockParts(setup.lockOf(post).orElseThrow())[1]).isEqualTo(setup.userId("bob"));
        }
    }

    @Test
    void aTakeOverAddressOnlyOpensThePostAndAsks() throws IOException {
        // WordPress refuses its Take over address without the link's nonce; QuarkPress has no nonce, so a crafted
        // address opens the post as any opening does, and only the dialog's button takes over
        long post = setup.post("bob", "post", "publish", "Taken by address " + token());
        setup.lock(post, "bob", 10);

        try (BrowserContext context = browsers.context("alice")) {
            blankAvatars(context);
            Page page = listAs(context, "alice");
            Response taken = page.waitForResponse(
                    response -> isLock(response.request(), "POST", post),
                    () -> page.navigate(browsers.adminUrl("/") + "post.php?get-post-lock=1&lockKey=true&post=" + post
                            + "&action=edit"));
            assertThat(taken.text()).contains("\"isLocked\":true");
            Locator dialog = lockDialog(page);
            dialog.getByRole(
                            AriaRole.HEADING,
                            new Locator.GetByRoleOptions().setName("This post is already being edited"))
                    .waitFor();
            assertThat(page.url()).isEqualTo(editUrl("/", post));
            assertThat(browsers.requests(page)).noneMatch(request -> request.endsWith("/locks/" + post + "/takeover"));
            assertThat(page.locator("#editor > *").count()).isZero();
            assertThat(lockParts(setup.lockOf(post).orElseThrow())[1]).isEqualTo(setup.userId("bob"));

            Response takeover = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/quarkpress/v1/locks/" + post + "/takeover"),
                    () -> dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Take over"))
                            .click());
            assertThat(takeover.status()).isEqualTo(200);
            awaitMounted(page);
            assertThat(lockParts(setup.lockOf(post).orElseThrow())[1]).isEqualTo(setup.userId("alice"));
            assertThat(browsers.requests(page).stream().filter(request -> isLockRequest(request, "POST", post)))
                    .hasSize(1);
        }
    }

    private static boolean isLockRequest(String request, String method, long id) {
        return request.equals(method + " " + browsers.root() + "wp-json/quarkpress/v1/locks/" + id);
    }

    @Test
    void aTakeoverFreezesTheLoser() throws IOException {
        long post = setup.post("bob", "post", "publish", "Taken over " + token());

        try (BrowserContext bobs = browsers.context("bob");
                BrowserContext alices = browsers.context("alice")) {
            blankAvatars(bobs);
            Page bob = editorAs(bobs, "bob", post);
            bob.evaluate("() => wp.data.dispatch('core/block-editor').insertBlocks("
                    + "wp.blocks.createBlock('core/paragraph', { content: 'Typed before the takeover.' }))");

            Page alice = listAs(alices, "alice");
            alice.navigate(editUrl("/", post));
            alice.waitForResponse(
                    response -> response.url().endsWith("/wp-json/quarkpress/v1/locks/" + post + "/takeover"),
                    () -> lockDialog(alice)
                            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Take over"))
                            .click());
            awaitMounted(alice);

            int from = browsers.requests(bob).size();
            AtomicReference<Object> result = new AtomicReference<>();
            Response autosaved = bob.waitForResponse(
                    response -> response.url().contains("/wp-json/wp/v2/posts/" + post + "/autosaves")
                            && response.request().method().equals("POST"),
                    () -> result.set(bob.evaluate("() => quarkpressAdmin.locks.refresh()")));
            assertThat(result.get()).isEqualTo("lost");
            assertThat(autosaved.status()).isEqualTo(200);
            assertThat(autosaved.request().postData()).contains("Typed before the takeover.");

            Locator dialog = lockDialog(bob);
            dialog.getByRole(
                            AriaRole.HEADING,
                            new Locator.GetByRoleOptions().setName("Someone else has taken over this post"))
                    .waitFor();
            assertThat(dialog.locator("p").allTextContents()).containsExactly(TAKEN_OVER_TEXT);
            assertThat(dialog.getByRole(AriaRole.BUTTON).count()).isZero();
            assertThat(dialog.getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Exit editor"))
                            .getAttribute("href"))
                    .isEqualTo(browsers.adminUrl("/") + "edit.php?post_type=post");
            assertThat(bob.evaluate("() => wp.data.select('core/editor').isPostSavingLocked()"))
                    .isEqualTo(true);
            assertThat(bob.evaluate("() => wp.data.select('core/editor').isPostAutosavingLocked()"))
                    .isEqualTo(true);

            // frozen: no further refresh, no save and no second autosave
            assertThat(bob.evaluate("() => quarkpressAdmin.locks.refresh()")).isEqualTo("stopped");
            bob.evaluate("async () => { wp.data.dispatch('core/block-editor').insertBlocks("
                    + "wp.blocks.createBlock('core/paragraph', { content: 'Typed after the takeover.' }));"
                    + " await wp.data.dispatch('core/editor').savePost();"
                    + " await wp.data.dispatch('core/editor').autosave(); }");
            // writes to other routes still go: the user's own preferences, saved as the editor saves them
            assertThat(bob.evaluate(
                            "async () => { const me = await wp.apiFetch({ path: '/wp/v2/users/me?context=edit' });"
                                    + " const saved = await wp.apiFetch({ path: '/wp/v2/users/me', method: 'PUT',"
                                    + " data: { meta: { persisted_preferences: me.meta.persisted_preferences } } });"
                                    + " return saved.id; }"))
                    .isEqualTo(Math.toIntExact(setup.userId("bob")));
            assertThat(bob.evaluate("() => wp.apiFetch({ path: '/wp/v2/posts/" + post + "/autosaves', method: 'POST',"
                            + " data: { content: 'x' } }).then(() => 'sent', error => error.code)"))
                    .isEqualTo("quarkpress_post_lock_lost");
            List<String> sent = requestsSince(bob, from);
            assertThat(sent.stream().filter(request -> request.contains("/wp/v2/posts/" + post + "/autosaves")))
                    .hasSize(1);
            // the refresh that found the takeover, and no other
            assertThat(sent.stream().filter(request -> request.contains("/quarkpress/v1/locks/")))
                    .containsExactly("PUT " + browsers.root() + "wp-json/quarkpress/v1/locks/" + post);
            assertThat(sent)
                    .noneMatch(
                            request -> request.startsWith("POST ") && request.contains("/wp/v2/posts/" + post + "?"));
            assertThat(setup.rest("bob", "/", "wp/v2/posts/" + post + "?context=edit")
                            .getString("content.raw"))
                    .doesNotContain("Typed after the takeover.");
            assertThat(lockParts(setup.lockOf(post).orElseThrow())[1]).isEqualTo(setup.userId("alice"));
        }
    }

    @Test
    void aHiddenTabDoesNotRefresh() {
        long post = setup.post("bob", "post", "publish", "Hidden " + token());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            page.evaluate("() => { Object.defineProperty(document, 'visibilityState',"
                    + " { configurable: true, get: () => 'hidden' });"
                    + " Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });"
                    + " document.dispatchEvent(new Event('visibilitychange')); }");
            int from = browsers.requests(page).size();

            assertThat(page.evaluate("() => quarkpressAdmin.locks.refresh()")).isEqualTo("skipped");
            assertThat(requestsSince(page, from)).noneMatch(request -> request.contains("/quarkpress/v1/locks/"));

            Request refresh = page.waitForRequest(
                    request -> isLock(request, "PUT", post),
                    () -> page.evaluate("() => { delete document.visibilityState; delete document.hidden;"
                            + " document.dispatchEvent(new Event('visibilitychange')); }"));
            assertThat(refresh.response().status()).isEqualTo(200);
            assertThat(requestsSince(page, from).stream().filter(request -> request.contains("/quarkpress/v1/locks/")))
                    .containsExactly("PUT " + refresh.url());
        }
    }

    @Test
    void theLockIsRefreshedAtTheConfiguredInterval() {
        long post = setup.post("bob", "post", "publish", "Refreshed " + token());

        try (BrowserContext context = browsers.context("bob")) {
            // this flow's shell refreshes every second (the application's setting is a minute)
            String config = setup.as(null)
                    .get("/wp-admin/quarkpress-admin/config.json")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();
            assertThat(config).contains("\"lockRefreshInterval\":60,");
            String everySecond = config.replace("\"lockRefreshInterval\":60,", "\"lockRefreshInterval\":1,");
            context.route(
                    "**/wp-admin/quarkpress-admin/config.json",
                    route -> route.fulfill(new Route.FulfillOptions()
                            .setStatus(200)
                            .setContentType("application/json")
                            .setBody(everySecond)));
            Page page = editorAs(context, "bob", post);
            String token = (String) page.evaluate("() => quarkpressAdmin.locks.state.releaseToken");

            // no call: the shell's own timer sends the refresh, whose new release token the shell keeps
            Request refresh = page.waitForRequest(request -> isLock(request, "PUT", post), () -> {});
            assertThat(refresh.response().text()).contains("\"new_lock\":");
            page.waitForFunction("token => quarkpressAdmin.locks.state.releaseToken !== token", token);
        }
    }

    /** Whether the request is a POST of the preview session route of the main site. */
    private static boolean isPreviewSession(Request request) {
        return request.method().equals("POST") && request.url().endsWith("/wp-json/quarkpress/v1/preview/session");
    }

    /** The context's preview session cookie, or null. */
    private static com.microsoft.playwright.options.Cookie previewCookie(BrowserContext context) {
        return context.cookies().stream()
                .filter(cookie -> cookie.name.equals("quarkpress_preview"))
                .findFirst()
                .orElse(null);
    }

    /** Opens the preview from the header's preview menu, as a user does, and answers the window once it has loaded. */
    private static Page openPreview(Page page) {
        Page popup = page.waitForPopup(() -> {
            page.locator(".editor-preview-dropdown__toggle").first().click();
            page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Preview in new tab"))
                    .first()
                    .click();
        });
        popup.waitForURL(url -> !url.startsWith("about:"));
        popup.waitForLoadState();
        return popup;
    }

    @Test
    void aDraftPreviewRendersForItsAuthorThroughTheSessionCookie() {
        long post = setup.post("bob", "post", "draft", "Preview draft " + token());
        String url;

        try (BrowserContext context = browsers.context("bob")) {
            Page page = listAs(context, "bob");
            Response opened = page.waitForResponse(
                    response -> isPreviewSession(response.request()), () -> page.navigate(editUrl("/", post)));
            assertThat(opened.status()).isEqualTo(200);
            assertThat(opened.text()).matches("^\\{\"expires_in\":\\d+}$");
            awaitMounted(page);
            // the session is opened before the editor mounts, and its cookie is the site's, out of the page's reach
            com.microsoft.playwright.options.Cookie cookie = previewCookie(context);
            assertThat(cookie).isNotNull();
            assertThat(cookie.path).isEqualTo("/");
            assertThat(cookie.httpOnly).isTrue();
            assertThat(cookie.sameSite).isEqualTo(com.microsoft.playwright.options.SameSiteAttribute.LAX);
            assertThat(page.evaluate("() => document.cookie.includes('quarkpress_preview')"))
                    .isEqualTo(false);

            page.evaluate("() => wp.data.dispatch('core/block-editor').insertBlocks("
                    + "wp.blocks.createBlock('core/paragraph', { content: 'Previewed text' }))");
            Page popup = openPreview(page);
            url = popup.url();
            assertThat(url)
                    .startsWith(browsers.root())
                    .contains("p=" + post)
                    .contains("preview=true")
                    .doesNotContain("preview_nonce")
                    .doesNotContain("preview_id")
                    .doesNotContain("access_token")
                    .doesNotContain("code=");
            assertThat(popup.content()).contains("Previewed text");
            // a draft is saved, not autosaved, before its preview
            assertThat(setup.rest("bob", "/", "wp/v2/posts/" + post + "?context=edit")
                            .getString("content.raw"))
                    .contains("Previewed text");
        }

        try (BrowserContext context = browsers.context("anonymous")) {
            Page page = context.newPage();
            Response answer = page.navigate(url);
            assertThat(answer.status()).isEqualTo(404);
            assertThat(page.content()).doesNotContain("Previewed text");
        }
        // the anonymous visitor's 404 is the only error
        assertThat(browsers.takeErrors())
                .allSatisfy(error -> assertThat(error)
                        .startsWith("anonymous console:")
                        .contains("the server responded with a status of 404"));
    }

    @Test
    void aPublishedPostPreviewCarriesTheTokenAndShowsTheEdit() throws IOException {
        Path png = Files.createDirectories(Path.of("target", "flow-files")).resolve("qp-thumb-" + token() + ".png");
        ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        long thumbnail = setup.upload("bob", png);
        long post = setup.post(
                "bob",
                "post",
                "publish",
                "Preview published " + token(),
                "<!-- wp:paragraph -->\n" + "<p>Saved for preview</p>\n<!-- /wp:paragraph -->",
                null);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            page.evaluate("() => { const block = wp.data.select('core/block-editor').getBlocks()"
                    + ".find(b => b.name === 'core/paragraph');"
                    + " wp.data.dispatch('core/block-editor').updateBlockAttributes(block.clientId,"
                    + " { content: 'Edited for preview' }); }");
            page.evaluate(
                    "id => wp.data.dispatch('core/editor').editPost({ featured_media: id })",
                    Math.toIntExact(thumbnail));
            AtomicReference<Page> popup = new AtomicReference<>();
            Response autosaved = page.waitForResponse(
                    response -> response.url().contains("/wp-json/wp/v2/posts/" + post + "/autosaves")
                            && response.request().method().equals("POST"),
                    () -> popup.set(openPreview(page)));
            assertThat(autosaved.status()).isEqualTo(200);
            String url = popup.get().url();
            assertThat(url)
                    .startsWith(browsers.root())
                    .contains("preview_id=" + post)
                    .containsPattern("preview_nonce=[^&]+")
                    .contains("preview=true")
                    .contains("_thumbnail_id=" + thumbnail)
                    .doesNotContain("access_token")
                    .doesNotContain("code=");
            assertThat(popup.get().content()).contains("Edited for preview").doesNotContain("Saved for preview");
            // the published post itself is unchanged
            assertThat(setup.rest("bob", "/", "wp/v2/posts/" + post + "?context=edit")
                            .getString("content.raw"))
                    .contains("Saved for preview");
        }
    }

    /** Whether the request is the editor's trashing of the post: api-fetch sends a DELETE as a POST with the override. */
    private static boolean isTrash(Request request, long id) {
        return request.method().equals("POST")
                && request.url().contains("/wp-json/wp/v2/posts/" + id + "?")
                && "DELETE".equals(request.headers().get("x-http-method-override"));
    }

    @Test
    void trashFromTheEditorReturnsToTheListWithUndo() {
        String title = "Trash from the editor " + token();
        long post = setup.post("bob", "post", "draft", title);

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            page.evaluate("() => wp.data.dispatch('core/edit-post').openGeneralSidebar('edit-post/document')");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Move to trash"))
                    .first()
                    .click();
            Locator confirm = page.getByRole(AriaRole.DIALOG)
                    .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Move to trash"));
            AtomicReference<Response> deleted = new AtomicReference<>();
            // the editor's own action: dispatching core/editor's trashPost alone trashes but leaves the page where it
            // is; the action confirmed in the editor's dialog trashes and then leaves for the list
            Request exit = page.waitForRequest(
                    request -> request.isNavigationRequest()
                            && request.url()
                                    .equals(browsers.adminUrl("/") + "edit.php?trashed=1&post_type=post&ids=" + post),
                    () -> deleted.set(
                            page.waitForResponse(response -> isTrash(response.request(), post), confirm::click)));
            assertThat(deleted.get().status()).isEqualTo(200);
            assertThat(exit.frame()).isEqualTo(page.mainFrame());
            assertThat(setup.statusOf("bob", "posts", post)).contains("trash");

            Locator notices = page.locator("#qp-notices");
            notices.getByText("1 post moved to the Trash.").waitFor();
            page.locator("#qp-main[aria-busy=false]").waitFor();
            assertThat(heading(page).textContent()).isEqualTo("Posts");
            Response undone = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/wp/v2/posts/" + post)
                            && response.request().method().equals("PUT"),
                    () -> notices.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Undo"))
                            .click());
            assertThat(undone.status()).isEqualTo(200);
            notices.getByText("1 post restored from the Trash.").waitFor();
            assertThat(setup.statusOf("bob", "posts", post)).contains("draft");
            row(page, title).waitFor();
        }
    }

    @Test
    void theSessionIsRenewedBeforeExpiry() {
        long post = setup.post("bob", "post", "draft", "Renewed " + token());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            String first = previewCookie(context).value;

            Response renewed = page.waitForResponse(
                    response -> isPreviewSession(response.request()),
                    () -> page.evaluate("() => quarkpressAdmin.preview.renew()"));
            assertThat(renewed.status()).isEqualTo(200);
            // Playwright leaves Set-Cookie out of headers()
            assertThat(renewed.headerValue("set-cookie")).startsWith("quarkpress_preview=");
            String second = previewCookie(context).value;
            assertThat(second).isNotEmpty().isNotEqualTo(first);

            // the cookie names the viewer of the front end only: the REST API never takes it as a credential
            AtomicReference<Object> status = new AtomicReference<>();
            Request plain = page.waitForRequest(
                    request -> request.url().endsWith("/wp-json/wp/v2/users/me?context=edit"),
                    () -> status.set(page.evaluate("() => fetch('/wp-json/wp/v2/users/me?context=edit',"
                            + " { credentials: 'same-origin' }).then(r => r.status)")));
            assertThat(plain.headerValue("cookie")).contains("quarkpress_preview=" + second);
            assertThat(plain.headerValue("authorization")).isNull();
            assertThat(status.get()).isEqualTo(401);
            takeExpectedFailures(401);
        }
    }

    @Test
    void theRenewalIsScheduledFromTheSessionsLifetime() {
        long post = setup.post("bob", "post", "draft", "Renewed by the timer " + token());

        try (BrowserContext context = browsers.context("bob")) {
            // this flow's first session is answered as living two seconds (and sets no cookie): the shell renews it
            // when 80% of that has passed; the renewal reaches the application
            // both are observed from handlers set before the editor opens, so the renewal is seen whenever it goes
            AtomicInteger answered = new AtomicInteger();
            java.util.concurrent.CompletableFuture<Response> renewal = new java.util.concurrent.CompletableFuture<>();
            context.route("**/wp-json/quarkpress/v1/preview/session", route -> {
                if (answered.getAndIncrement() > 0) {
                    route.resume();
                    return;
                }
                route.fulfill(new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("application/json")
                        .setBody("{\"expires_in\":2}"));
            });
            context.onResponse(response -> {
                if (isPreviewSession(response.request()) && response.headerValue("set-cookie") != null) {
                    renewal.complete(response);
                }
            });
            Page page = editorAs(context, "bob", post);

            // no call: the shell's own timer sends the renewal
            page.waitForCondition(renewal::isDone);
            Response renewed = renewal.join();
            assertThat(renewed.status()).isEqualTo(200);
            assertThat(renewed.headerValue("set-cookie")).startsWith("quarkpress_preview=");
            assertThat(answered.get()).isGreaterThanOrEqualTo(2);
            assertThat(previewCookie(context)).isNotNull();
        }
    }

    @Test
    void aRefusedRenewalShowsTheSessionOverlay() {
        long post = setup.post("bob", "post", "draft", "Refused renewal " + token());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            AtomicInteger refused = new AtomicInteger();
            page.route("**/wp-json/quarkpress/v1/preview/session", route -> {
                refused.incrementAndGet();
                route.fulfill(new Route.FulfillOptions()
                        .setStatus(401)
                        .setContentType("application/json")
                        .setBody(NOT_LOGGED_IN));
            });

            page.evaluate("() => quarkpressAdmin.preview.renew().catch(() => {})");

            page.getByText("Your session has expired. You can log in again from this page or go to the login page.")
                    .waitFor();
            // refused once, renewed the token, refused again: then no more
            assertThat(refused.get()).isEqualTo(2);
            assertThat(page.evaluate("() => wp.data.select('core/editor').isPostSavingLocked()"))
                    .isEqualTo(true);
            takeExpectedFailures(401);
        }
    }

    @Test
    void loggingOutEndsThePreviews() {
        long post = setup.post(
                "bob",
                "post",
                "draft",
                "Logged out " + token(),
                "<!-- wp:paragraph -->\n" + "<p>Seen before logging out</p>\n<!-- /wp:paragraph -->",
                null);
        String url = browsers.root() + "?p=" + post + "&preview=true";

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            Page before = context.newPage();
            assertThat(before.navigate(url).status()).isEqualTo(200);
            assertThat(before.content()).contains("Seen before logging out");
            before.close();

            page.navigate(browsers.adminUrl("/"));
            page.locator("#qp-main[aria-busy=false]").waitFor();
            Response closed = page.waitForResponse(
                    response -> response.url().endsWith("/wp-json/quarkpress/v1/preview/session")
                            && response.request().method().equals("DELETE"),
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log Out"))
                            .click());
            assertThat(closed.status()).isEqualTo(200);
            assertThat(closed.request().headerValue("authorization")).startsWith("Bearer ");

            Page after = context.newPage();
            assertThat(after.navigate(url).status()).isEqualTo(404);
            assertThat(after.content()).doesNotContain("Seen before logging out");
        }
        // the logged-out preview's 404 is the only error
        assertThat(browsers.takeErrors())
                .allSatisfy(error -> assertThat(error).contains("the server responded with a status of 404"));
    }

    @Test
    void aForbiddenRenewalStopsAndAServerErrorIsRetried() {
        long post = setup.post("bob", "post", "draft", "Renewal refusals " + token());

        try (BrowserContext context = browsers.context("bob")) {
            Page page = editorAs(context, "bob", post);
            assertThat(page.evaluate("() => quarkpressAdmin.preview.scheduled()"))
                    .isEqualTo(true);
            AtomicInteger status = new AtomicInteger(500);
            page.route(
                    "**/wp-json/quarkpress/v1/preview/session",
                    route -> route.fulfill(new Route.FulfillOptions()
                            .setStatus(status.get())
                            .setContentType("application/json")
                            .setBody(
                                    "{\"code\":\"rest_forbidden\",\"message\":\"Sorry, you are not allowed to do that.\","
                                            + "\"data\":{\"status\":" + status.get() + "}}")));

            // a server error is passing: tried again later
            page.evaluate("() => quarkpressAdmin.preview.renew().catch(() => {})");
            assertThat(page.evaluate("() => quarkpressAdmin.preview.scheduled()"))
                    .isEqualTo(true);
            // a refusal is not: no more renewals, and no session overlay (the user is still signed in)
            status.set(403);
            page.evaluate("() => quarkpressAdmin.preview.renew().catch(() => {})");
            assertThat(page.evaluate("() => quarkpressAdmin.preview.scheduled()"))
                    .isEqualTo(false);
            assertThat(page.locator("#qp-overlay").count()).isZero();
            takeExpectedFailures(500, 403);
        }
    }

    private static int indexOf(List<String> list, java.util.function.Predicate<String> test) {
        for (int i = 0; i < list.size(); i++) {
            if (test.test(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(List<String> list, java.util.function.Predicate<String> test) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (test.test(list.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
