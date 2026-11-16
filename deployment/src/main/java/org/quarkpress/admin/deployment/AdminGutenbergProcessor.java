package org.quarkpress.admin.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkpress.admin.runtime.AdminConfig;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;

class AdminGutenbergProcessor {

    static final String FEATURE = "quarkpress-admin-gutenberg";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** The extension's settings, so that a key under its root it does not know stops the application. */
    @BuildStep
    QuarkPressConfigKeysBuildItem keys() {
        return QuarkPressConfigKeysBuildItem.of("admin", "quarkpress.admin", AdminConfig.class);
    }
}
