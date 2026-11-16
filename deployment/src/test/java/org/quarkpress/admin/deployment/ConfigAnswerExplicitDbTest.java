package org.quarkpress.admin.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The shell's configuration answer carries the application's admin and REST settings. */
class ConfigAnswerExplicitDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = AdminApp.app(ConfigAnswerExplicitDbTest.class)
            .overrideConfigKey("quarkpress.admin.oidc.authority", "http://a.test/x")
            .overrideConfigKey("quarkpress.admin.oidc.client-id", "my-admin")
            .overrideConfigKey("quarkpress.admin.oidc.scope", "openid")
            .overrideConfigKey("quarkpress.admin.lock-refresh-interval", "90s")
            .overrideConfigKey("quarkpress.admin.path", "/a/b/")
            .overrideConfigKey("quarkpress.rest-wp-v2.path", "/api");

    @Test
    void anExplicitAuthorityWins() {
        Response response = given().get("/a/b/quarkpress-admin/config.json");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonPath json = JsonPath.from(response.asString());
        assertThat(json.getString("oidc.authority")).isEqualTo("http://a.test/x");
        assertThat(json.getString("oidc.clientId")).isEqualTo("my-admin");
        assertThat(json.getString("oidc.scope")).isEqualTo("openid");
        assertThat(json.getInt("lockRefreshInterval")).isEqualTo(90);
        assertThat(json.getString("adminPath")).isEqualTo("/a/b/");
        assertThat(json.getString("restPath")).isEqualTo("/api");
        assertThat(given().get("/wp-admin/quarkpress-admin/config.json").statusCode())
                .isEqualTo(404);
    }
}
