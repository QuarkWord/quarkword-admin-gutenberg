package org.quarkword.admin.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Without a sign-in server, neither the admin's own nor the application's, the application does not start. */
class ConfigAnswerUnsetDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = AdminApp.app(ConfigAnswerUnsetDbTest.class)
            .overrideConfigKey("quarkus.oidc.auth-server-url", "")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkWord admin: set quarkword.admin.oidc.authority or quarkus.oidc.auth-server-url"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
