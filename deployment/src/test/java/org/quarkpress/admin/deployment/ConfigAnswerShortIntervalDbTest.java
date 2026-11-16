package org.quarkpress.admin.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A post lock refresh interval under one second would reach the shell as zero seconds (the shell counts whole
 * seconds), so the application does not start with one.
 */
class ConfigAnswerShortIntervalDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = AdminApp.app(ConfigAnswerShortIntervalDbTest.class)
            .overrideConfigKey("quarkpress.admin.lock-refresh-interval", "999ms")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress admin: quarkpress.admin.lock-refresh-interval must be at least one second,"
                            + " not PT0.999S"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
