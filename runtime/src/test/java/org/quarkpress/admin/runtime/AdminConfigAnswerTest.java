package org.quarkpress.admin.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The post lock refresh interval the shell gets: whole seconds, never less than one. */
class AdminConfigAnswerTest {

    @Test
    void theIntervalIsSentInWholeSeconds() {
        assertThat(AdminConfigAnswer.refreshSeconds(Duration.ofSeconds(1))).isEqualTo(1);
        assertThat(AdminConfigAnswer.refreshSeconds(Duration.ofMillis(1500))).isEqualTo(1);
        assertThat(AdminConfigAnswer.refreshSeconds(Duration.ofMinutes(2))).isEqualTo(120);
    }

    @Test
    void anIntervalUnderOneSecondIsRefused() {
        assertThatThrownBy(() -> AdminConfigAnswer.refreshSeconds(Duration.ofMillis(999)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("QuarkPress admin: quarkpress.admin.lock-refresh-interval must be at least one second,"
                        + " not PT0.999S");
        assertThatThrownBy(() -> AdminConfigAnswer.refreshSeconds(Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminConfigAnswer.refreshSeconds(Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalStateException.class);
    }
}
