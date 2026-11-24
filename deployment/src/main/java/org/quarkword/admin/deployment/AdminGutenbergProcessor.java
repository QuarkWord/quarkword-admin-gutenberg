package org.quarkword.admin.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import org.quarkword.admin.runtime.AdminConfig;
import org.quarkword.admin.runtime.AdminPages;
import org.quarkword.admin.runtime.AdminRoute;
import org.quarkword.admin.runtime.AssetFiles;
import org.quarkword.admin.runtime.AssetRegistry;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;

class AdminGutenbergProcessor {

    static final String FEATURE = "quarkword-admin-gutenberg";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** The extension's settings, so that a key under its root it does not know stops the application. */
    @BuildStep
    QuarkWordConfigKeysBuildItem keys() {
        return QuarkWordConfigKeysBuildItem.of("admin", "quarkword.admin", AdminConfig.class);
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
                        "org/quarkword/admin/wp/**",
                        "org/quarkword/admin/vendor/**",
                        "org/quarkword/admin/shell/**",
                        "org/quarkword/admin/wordpress-files.txt",
                        "org/quarkword/admin/wordpress-handles.json",
                        "org/quarkword/admin/oidc-client-ts-files.txt")
                .build();
    }
}
