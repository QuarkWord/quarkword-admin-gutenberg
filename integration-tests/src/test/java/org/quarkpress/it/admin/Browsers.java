package org.quarkpress.it.admin;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chromium (headless) for the flows: Playwright starts once per test class ({@link #launch()} in {@code @BeforeAll},
 * {@link #close()} in {@code @AfterAll}) and Chromium once the application's port is known ({@link #base(URL)}); one
 * context per user and flow, every console error and page error of every page collected (a flow ends with
 * {@link #takeErrors()} empty), every request a page sends logged in order. Nothing here waits on time: the helpers
 * wait on the network, the DOM or the page's own state.
 *
 * <p>The browser reaches the application as {@code http://localhost/}, the main site's own address: Chromium's resolver
 * maps {@code localhost:80} to the application's test port, so the absolute URLs the application writes from its
 * sites' addresses (uploads, front-end links) load in the browser as they would on a real host; the Host header is
 * {@code localhost}, as the REST tests send it. Keycloak keeps its own port.
 */
final class Browsers implements AutoCloseable {

    static Playwright playwright;

    static Browser browser;

    private int port = -1;

    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

    private final Map<Page, List<String>> requests = new ConcurrentHashMap<>();

    private Browsers() {}

    /** Starts Playwright; Chromium starts with the first {@link #base(URL)}. */
    static Browsers launch() {
        playwright = Playwright.create();
        return new Browsers();
    }

    @Override
    public void close() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }

    /**
     * The application's root URL from the test's {@code @TestHTTPResource}, such as {@code http://localhost:43123/}:
     * (re)starts Chromium with {@code localhost:80} mapped to that port.
     */
    void base(URL root) {
        int actual = root.getPort();
        if (browser != null && actual == port) {
            return;
        }
        if (browser != null) {
            browser.close();
        }
        port = actual;
        browser = playwright
                .chromium()
                .launch(new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(List.of("--host-resolver-rules=MAP localhost:80 127.0.0.1:" + port)));
    }

    /** The admin folder of the site at {@code sitePath} ({@code /} for the main site, {@code /fv01/} for a subsite). */
    String adminUrl(String sitePath) {
        return root() + sitePath.substring(1) + "wp-admin/";
    }

    /** The main site's address as the browser reaches it. */
    String root() {
        return "http://localhost/";
    }

    /** A new browser context for {@code user}'s flow; its pages report their errors and requests here. */
    BrowserContext context(String user) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 900));
        context.setDefaultTimeout(60_000);
        context.onPage(page -> watch(user, page));
        return context;
    }

    private void watch(String user, Page page) {
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        requests.put(page, sent);
        page.onRequest(request -> sent.add(request.method() + " " + request.url()));
        page.onConsoleMessage(message -> {
            if ("error".equals(message.type())) {
                errors.add(user + " console: " + message.text() + " at " + message.location() + " on " + page.url());
            }
        });
        page.onPageError(error -> errors.add(user + " page error: " + error + " on " + page.url()));
    }

    /** The requests the page sent so far, as {@code "<method> <url>"}, in order. */
    List<String> requests(Page page) {
        return List.copyOf(requests.getOrDefault(page, List.of()));
    }

    /** The errors collected since the last call, which forgets them. */
    List<String> takeErrors() {
        synchronized (errors) {
            List<String> taken = List.copyOf(errors);
            errors.clear();
            return taken;
        }
    }

    /**
     * Opens {@code url} in a new page of the context, signs in as {@code user} at Keycloak (the user name is the
     * password) and waits until the shell is back in the same admin folder with the user signed in.
     */
    Page signedIn(BrowserContext context, String user, String url) {
        Page page = context.newPage();
        page.navigate(url);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log in with QuarkPress"))
                .click();
        page.locator("#username").fill(user);
        page.locator("#password").fill(user);
        page.locator("#kc-login").click();
        String folder = url.substring(0, url.lastIndexOf('/') + 1);
        page.waitForURL(address -> address.startsWith(folder) && !address.contains("code="));
        page.waitForFunction("() => !!(window.quarkpressAdmin && window.quarkpressAdmin.auth"
                + " && window.quarkpressAdmin.auth.user())");
        return page;
    }

    /** Waits until the editor is mounted: its stores answer and the canvas iframe exists (state only). */
    static void awaitEditor(Page page) {
        page.waitForFunction("() => window.wp && wp.data && wp.blocks && wp.data.select('core/editor')"
                + " && wp.data.select('core/editor').getCurrentPostId()"
                + " && wp.data.select('core/block-editor').getSettings().__experimentalFetchLinkSuggestions"
                + " && document.querySelector('iframe[name=\"editor-canvas\"]')");
    }

    /**
     * Closes the editor's welcome guide, a modal that hides the rest of the editor from clicks, as a user would: with
     * its Close button, or Escape when there is none; then waits for the overlay to go.
     */
    static void closeWelcomeGuide(Page page) {
        Locator overlay = page.locator(".components-modal__screen-overlay");
        if (overlay.count() > 0) {
            Locator close = overlay.first().getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Close"));
            if (close.count() > 0) {
                close.first().click();
            } else {
                page.keyboard().press("Escape");
            }
            overlay.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
        }
    }

    /** The editor's canvas iframe. */
    static FrameLocator canvas(Page page) {
        return page.frameLocator("iframe[name=\"editor-canvas\"]");
    }
}
