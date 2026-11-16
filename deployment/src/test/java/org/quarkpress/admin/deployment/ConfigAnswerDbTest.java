package org.quarkpress.admin.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The shell's configuration answer when the application sets none of the admin settings: the sign-in server is the
 * application's own OIDC server ({@link AdminApp} sets {@code quarkus.oidc.auth-server-url}).
 */
class ConfigAnswerDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = AdminApp.app(ConfigAnswerDbTest.class);

    @Test
    void theConfigJson() {
        Response response = given().get("/wp-admin/quarkpress-admin/config.json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("application/json");
        assertThat(response.header("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.asString())
                .isEqualTo("{\"adminPath\":\"/wp-admin/\",\"restPath\":\"/wp-json\",\"oidc\":{\"authority\":"
                        + "\"http://kc.test/realms/q\",\"clientId\":\"quarkpress-admin\",\"scope\":\"openid profile"
                        + " email\"},\"lockRefreshInterval\":60,\"wordpress\":\"7.1.3\"}");
    }

    @Test
    void everySiteGetsTheSameAnswerAndAVersionDoesNotMakeItImmutable() {
        String main = given().get("/wp-admin/quarkpress-admin/config.json").asString();
        for (String path : List.of(
                "/fv01/wp-admin/quarkpress-admin/config.json", "/wp-admin/quarkpress-admin/config.json?ver=7.1.3")) {
            Response response = given().get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.header("Cache-Control")).as(path).isEqualTo("no-cache");
            assertThat(response.asString()).as(path).isEqualTo(main);
        }
        assertThat(JsonPath.from(main).getString("oidc.authority")).isEqualTo("http://kc.test/realms/q");

        Response head = given().head("/wp-admin/quarkpress-admin/config.json");
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.asByteArray()).isEmpty();
    }
}
