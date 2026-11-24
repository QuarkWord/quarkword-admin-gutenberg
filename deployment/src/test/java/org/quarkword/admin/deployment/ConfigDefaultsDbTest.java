package org.quarkword.admin.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.admin.runtime.AdminConfig;

/** The extension's settings when the application sets none of them. */
class ConfigDefaultsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = AdminApp.app(ConfigDefaultsDbTest.class);

    @Inject
    AdminConfig config;

    @Test
    void theAdminScreensLiveUnderWpAdmin() {
        assertThat(config.path()).isEqualTo("/wp-admin/");
    }

    @Test
    void theShellSignsInAsTheAdminClientWithTheStandardScopes() {
        assertThat(config.oidc().clientId()).isEqualTo("quarkword-admin");
        assertThat(config.oidc().scope()).isEqualTo("openid profile email");
    }

    @Test
    void theAuthorityIsUnsetByDefault() {
        assertThat(config.oidc().authority()).isEmpty();
    }

    @Test
    void theLockIsRefreshedEveryMinute() {
        assertThat(config.lockRefreshInterval()).isEqualTo(Duration.ofSeconds(60));
    }
}
