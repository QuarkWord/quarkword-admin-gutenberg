package org.quarkpress.admin.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** WordPress's files and the vendored sign-in client are served from the jar, each on its own, under any site. */
class AssetServingDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = AdminApp.app(AssetServingDbTest.class);

    private static final String HOOKS = "/wp-includes/js/dist/hooks.min.js";

    @TestHTTPResource("/")
    URL root;

    @Test
    void servesAFileWithImmutableCachingWhenVersioned() throws IOException {
        Response response = given().get(HOOKS + "?ver=f0f188028580e8dc1255");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("text/javascript; charset=utf-8");
        assertThat(response.header("Cache-Control")).isEqualTo("public, max-age=31536000, immutable");
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.header("ETag")).isNull();
        assertThat(response.asByteArray()).isEqualTo(resource("wp" + HOOKS));
    }

    @Test
    void unversionedFilesAreNotCachedLong() {
        Response response = given().get(HOOKS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.header("ETag")).isNull();
        assertThat(given().get(HOOKS + "?x=1").header("Cache-Control")).isEqualTo("no-cache");
    }

    @Test
    void underASitePath() throws IOException {
        Response response = given().get("/fv01/wp-admin/css/forms.min.css?ver=7.1.3");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("text/css; charset=utf-8");
        assertThat(response.asByteArray()).isEqualTo(resource("wp/wp-admin/css/forms.min.css"));
        assertThat(given().get("/a/b/c/d/wp-includes/fonts/dashicons.woff2").header("Content-Type"))
                .isEqualTo("font/woff2");
    }

    @Test
    void headAndMissing() throws IOException {
        Response head = given().head(HOOKS);

        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.header("Content-Length")).isEqualTo(Integer.toString(resource("wp" + HOOKS).length));
        assertThat(head.header("Content-Type")).isEqualTo("text/javascript; charset=utf-8");
        assertThat(head.asByteArray()).isEmpty();

        Response missing = given().get("/wp-includes/js/nope.js");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.asByteArray()).isEmpty();
        // in the zip, but not shipped
        assertThat(given().get("/wp-includes/js/dist/edit-site.min.js").statusCode())
                .isEqualTo(404);
        // in the jar, but not under a served folder
        assertThat(given().get("/license.txt").statusCode()).isEqualTo(404);
    }

    @Test
    void theVendorFileIsServedUnderTheShell() throws IOException {
        Response response = given().get("/wp-admin/quarkpress-admin/vendor/oidc-client-ts.js");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("text/javascript; charset=utf-8");
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.asByteArray()).isEqualTo(resource("vendor/oidc-client-ts.js"));
        assertThat(given().get("/wp-admin/quarkpress-admin/vendor/nope.js").statusCode())
                .isEqualTo(404);
    }

    @Test
    void theVendorLicenceIsServedAsText() throws IOException {
        Response response = given().get("/wp-admin/quarkpress-admin/vendor/oidc-client-ts.LICENSE");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("text/plain; charset=utf-8");
        assertThat(response.asByteArray()).isEqualTo(resource("vendor/oidc-client-ts.LICENSE"));
    }

    @Test
    void aMissingShellFileIsNotFound() {
        assertThat(given().get("/wp-admin/quarkpress-admin/nope.js").statusCode())
                .isEqualTo(404);
    }

    @Test
    void aMalformedQueryIsAnsweredPromptly() throws IOException {
        // each on a file no other test asks for, so that the answer is the first read from the jar, not from the cache;
        // the HTTP layer may refuse the query (400) before the route sees it, but the request must never stay open
        String[][] cases = {
            {"/wp-includes/js/dist/url.min.js", "?ver=%zz"},
            {"/wp-includes/js/dist/blob.min.js", "?%zz=1"},
            {"/wp-includes/js/dist/autop.min.js", "?ver=%"},
            {"/wp-includes/js/dist/warning.min.js", "?a=%zz&ver=1"}
        };
        for (String[] request : cases) {
            String head = rawHead(request[0] + request[1]);
            assertThat(head)
                    .as(request[1])
                    .satisfiesAnyOf(
                            answer -> assertThat(answer).startsWith("HTTP/1.1 200 "),
                            answer -> assertThat(answer).startsWith("HTTP/1.1 400 "));
        }
    }

    @Test
    void onlyAParameterNamedExactlyVerMakesTheAnswerImmutable() {
        assertThat(given().get(HOOKS + "?VER=1").header("Cache-Control")).isEqualTo("no-cache");
        assertThat(given().get(HOOKS + "?vers=1&xver=2").header("Cache-Control"))
                .isEqualTo("no-cache");
        assertThat(given().get(HOOKS + "?ver=1").header("Cache-Control"))
                .isEqualTo("public, max-age=31536000, immutable");
        assertThat(given().get(HOOKS + "?a=1&ver").header("Cache-Control"))
                .isEqualTo("public, max-age=31536000, immutable");
    }

    /** The status line and headers of a GET sent byte for byte as written; fails when no answer comes in 10 s. */
    private String rawHead(String target) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(root.getHost(), root.getPort()), 10_000);
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream answer = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                answer.write(buffer, 0, read);
            }
            String text = answer.toString(StandardCharsets.ISO_8859_1);
            int end = text.indexOf("\r\n\r\n");
            return end < 0 ? text : text.substring(0, end);
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in =
                Thread.currentThread().getContextClassLoader().getResourceAsStream("org/quarkpress/admin/" + path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }
}
