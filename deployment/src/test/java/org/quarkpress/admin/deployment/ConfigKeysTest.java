package org.quarkpress.admin.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A key under the extension's configuration root that the extension does not know stops the application. */
class ConfigKeysTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = AdminApp.buildOnly(ConfigKeysTest.class)
            .overrideConfigKey("quarkpress.admin.nope", "1")
            .assertException(failure -> assertThat(Failures.messages(failure)).contains("quarkpress.admin.nope"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
