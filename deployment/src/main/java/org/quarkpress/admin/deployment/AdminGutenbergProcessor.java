package org.quarkpress.admin.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import org.quarkpress.admin.runtime.AdminConfig;
import org.quarkpress.admin.runtime.AdminPages;
import org.quarkpress.admin.runtime.AdminRoute;
import org.quarkpress.admin.runtime.AssetFiles;
import org.quarkpress.admin.runtime.AssetRegistry;
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

    /**
     * The route that serves the admin screens' files and pages, the files it finds them through, the pages and
     * WordPress's handle registry they are rendered from.
     */
    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(AdminRoute.class, AssetFiles.class, AdminPages.class, AssetRegistry.class)
                .setUnremovable()
                .build();
    }

    /**
     * WordPress's files, the vendored client, the shell and the lists, which a native executable reads as resources
     * (the extension's classes are not among them).
     */
    @BuildStep
    NativeImageResourcePatternsBuildItem resources() {
        return NativeImageResourcePatternsBuildItem.builder()
                .includeGlobs(
                        "org/quarkpress/admin/wp/**",
                        "org/quarkpress/admin/vendor/**",
                        "org/quarkpress/admin/shell/**",
                        "org/quarkpress/admin/wordpress-files.txt",
                        "org/quarkpress/admin/wordpress-handles.json",
                        "org/quarkpress/admin/oidc-client-ts-files.txt")
                .build();
    }
}
