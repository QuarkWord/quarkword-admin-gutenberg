package org.quarkpress.admin.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.quarkus.runtime.StartupEvent;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Both pages are rendered when the application starts, so a broken registry stops the start, not a request. */
class AdminPagesTest {

    @Test
    void thePagesAreRenderedAtStartUp() {
        AdminPages pages = pages(new AssetRegistry());
        assertThat(pages.rendered()).isFalse();

        pages.start(new StartupEvent());

        assertThat(pages.rendered()).isTrue();
        assertThat(pages.editor()).contains("id=\"wp-edit-post-js\"");
        assertThat(pages.shell()).contains("quarkpress-admin/app.js");
    }

    @Test
    void aBrokenRegistryFailsTheStart() throws IOException {
        String broken = AssetRegistryTest.edited(tree -> {
            for (var script : (ArrayNode) tree.get("scripts")) {
                if (script.get("handle").asText().equals("wp-edit-post")) {
                    ((ArrayNode) script.get("deps")).add("nope");
                }
            }
        });
        AdminPages pages = pages(new AssetRegistry(() -> broken));

        assertThatThrownBy(() -> pages.start(new StartupEvent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("QuarkPress admin: the handle wp-edit-post depends on nope, which is not registered");
        assertThat(pages.rendered()).isFalse();
    }

    private static AdminPages pages(AssetRegistry registry) {
        AdminPages pages = new AdminPages();
        pages.config = new AdminConfig() {
            @Override
            public String path() {
                return "/wp-admin/";
            }

            @Override
            public Oidc oidc() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Duration lockRefreshInterval() {
                return Duration.ofSeconds(60);
            }
        };
        pages.registry = registry;
        pages.files = new AssetFiles();
        return pages;
    }
}
