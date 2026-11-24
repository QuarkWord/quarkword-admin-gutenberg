package org.quarkword.admin.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quarkword.admin.runtime.AdminPaths.Kind.ADMIN_FOLDER;
import static org.quarkword.admin.runtime.AdminPaths.Kind.CONFIG;
import static org.quarkword.admin.runtime.AdminPaths.Kind.EDITOR_PAGE;
import static org.quarkword.admin.runtime.AdminPaths.Kind.SHELL_FILE;
import static org.quarkword.admin.runtime.AdminPaths.Kind.SHELL_PAGE;
import static org.quarkword.admin.runtime.AdminPaths.Kind.VENDOR_FILE;
import static org.quarkword.admin.runtime.AdminPaths.Kind.WP_FILE;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkword.admin.runtime.AdminPaths.Target;

/** Which request paths the admin route answers, under a site path of up to four segments. */
class AdminPathsTest {

    @Test
    void classifiesWordPressLayoutUnderSites() {
        AdminPaths paths = AdminPaths.of("/wp-admin/");
        assertThat(paths.classify("/wp-includes/js/dist/edit-post.min.js"))
                .contains(new Target(WP_FILE, "wp-includes/js/dist/edit-post.min.js"));
        assertThat(paths.classify("/fv01/wp-admin/css/common.min.css"))
                .contains(new Target(WP_FILE, "wp-admin/css/common.min.css"));
        assertThat(paths.classify("/a/b/c/d/wp-admin/post.php")).contains(new Target(EDITOR_PAGE, "post.php"));
        assertThat(paths.classify("/a/b/c/d/e/wp-admin/post.php")).isEmpty(); // five site segments
        assertThat(paths.classify("/wp-admin/quarkword-admin/config.json")).contains(new Target(CONFIG, "config.json"));
        assertThat(paths.classify("/wp-admin/quarkword-admin/vendor/oidc-client-ts.js"))
                .contains(new Target(VENDOR_FILE, "vendor/oidc-client-ts.js"));
        assertThat(paths.classify("/fv01/wp-admin/quarkword-admin/screens/lists.js"))
                .contains(new Target(SHELL_FILE, "screens/lists.js"));
        assertThat(paths.classify("/wp-admin/quarkword-admin/screens/%2e%2e/x.js"))
                .isEmpty();
        assertThat(paths.classify("/wp-admin/quarkword-admin/../x")).isEmpty();
        assertThat(paths.classify("/wp-includes/%2E%2E/x")).isEmpty();
        assertThat(paths.classify("/wp-admin/admin-ajax.php")).isEmpty();
    }

    @Test
    void theShellAndEditorPages() {
        AdminPaths paths = AdminPaths.of("/wp-admin/");
        assertThat(paths.classify("/wp-admin/")).contains(new Target(SHELL_PAGE, ""));
        for (String page : List.of("index.php", "edit.php", "upload.php")) {
            assertThat(paths.classify("/wp-admin/" + page)).contains(new Target(SHELL_PAGE, page));
            assertThat(paths.classify("/fv01/wp-admin/" + page)).contains(new Target(SHELL_PAGE, page));
        }
        for (String page : List.of("post.php", "post-new.php", "revision.php")) {
            assertThat(paths.classify("/wp-admin/" + page)).contains(new Target(EDITOR_PAGE, page));
        }
        // the admin folder without its slash is redirected to the folder, as WordPress's own address is
        assertThat(paths.classify("/wp-admin")).contains(new Target(ADMIN_FOLDER, ""));
        assertThat(paths.classify("/fv01/wp-admin")).contains(new Target(ADMIN_FOLDER, ""));
        assertThat(AdminPaths.of("/a/b/").classify("/fv01/a/b")).contains(new Target(ADMIN_FOLDER, ""));
        assertThat(AdminPaths.of("/a/b/").classify("/a")).isEmpty();
        assertThat(paths.classify("//wp-admin")).isEmpty();
        assertThat(paths.classify("/wp-admin/edit.php/")).isEmpty();
        assertThat(paths.classify("/wp-admin/nope.php")).isEmpty();
        assertThat(paths.classify("/wp-admin/quarkword-admin/")).isEmpty();
        assertThat(paths.classify("/wp-admin/quarkword-admin/vendor/a/b.js")).isEmpty();
    }

    @Test
    void onlyTheWordPressFoldersAreFiles() {
        AdminPaths paths = AdminPaths.of("/wp-admin/");
        assertThat(paths.classify("/wp-admin/js/postbox.min.js"))
                .contains(new Target(WP_FILE, "wp-admin/js/postbox.min.js"));
        assertThat(paths.classify("/wp-admin/images/stars.png"))
                .contains(new Target(WP_FILE, "wp-admin/images/stars.png"));
        assertThat(paths.classify("/wp-admin/includes/post.php")).isEmpty();
        assertThat(paths.classify("/license.txt")).isEmpty();
        assertThat(paths.classify("/wp-includes/")).isEmpty();
        assertThat(paths.classify("/wp-includes/js//x.js")).isEmpty();
        assertThat(paths.classify("/wp-includes/js/.hidden")).isEmpty();
        assertThat(paths.classify("/wp-includes/js\\x.js")).isEmpty();
        assertThat(paths.classify("/wp-includes/js/a%2Eb.js")).isEmpty();
        assertThat(paths.classify("wp-includes/js/x.js")).isEmpty();
        assertThat(paths.classify("/wp-includes")).isEmpty();
        assertThat(paths.classify("/fv01/wp-includes")).isEmpty();
        assertThat(paths.classify("/wp-admin/js")).isEmpty();
        assertThat(paths.classify("/wp-admin/css")).isEmpty();
        assertThat(paths.classify("")).isEmpty();
    }

    @Test
    void aConfiguredPathReplacesWpAdminForTheShellOnly() {
        AdminPaths paths = AdminPaths.of("/a/b/");
        assertThat(paths.adminSegments()).containsExactly("a", "b");
        assertThat(paths.classify("/a/b/post.php")).contains(new Target(EDITOR_PAGE, "post.php"));
        assertThat(paths.classify("/fv01/a/b/edit.php")).contains(new Target(SHELL_PAGE, "edit.php"));
        assertThat(paths.classify("/a/b/quarkword-admin/app.js")).contains(new Target(SHELL_FILE, "app.js"));
        assertThat(paths.classify("/wp-admin/post.php")).isEmpty();
        assertThat(paths.classify("/fv01/wp-admin/css/forms.min.css"))
                .contains(new Target(WP_FILE, "wp-admin/css/forms.min.css"));
    }

    @Test
    void aTwoSegmentAdminPathPrefixesTwice() {
        assertThat(AdminPaths.of("/a/b/").relativePrefix()).isEqualTo("../../");
        assertThat(AdminPaths.of("/wp-admin/").relativePrefix()).isEqualTo("../");
    }

    @Test
    void refusesBadAdminPaths() {
        for (String bad : List.of(
                "", "/", "wp-admin", "/wp-admin", "wp-admin/", "/./", "/wp-admin/../", "//", "/a//b/", "/a\\b/")) {
            assertThatThrownBy(() -> AdminPaths.of(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("quarkword.admin.path must be a path below the site's root that starts and ends with"
                            + " /, such as /wp-admin/: " + bad);
        }
    }
}
