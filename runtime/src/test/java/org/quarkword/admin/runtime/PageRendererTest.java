package org.quarkword.admin.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What the pages' text may never contain: a tag closed early by data printed inside it. */
class PageRendererTest {

    @Test
    void anImportTargetCannotCloseTheImportMap() throws IOException {
        AssetRegistry registry = AssetRegistry.parse(AssetRegistryTest.edited(
                tree -> ((ObjectNode) tree.get("importmap").get("imports")).put("@wordpress/route", "x</script><b>y")));

        String page = new PageRenderer(registry, "/wp-admin/", Optional.empty()).editorPage("../");

        assertThat(page)
                .contains("\"@wordpress/route\":\"../x<\\/script><b>y\"")
                .doesNotContain("x</script>");
    }

    @Test
    void anUnsafeViewTransitionsStylesheetFailsTheRender() {
        PageRenderer renderer = new PageRenderer(
                AssetRegistry.load(), "/wp-admin/", Optional.of("@media{}</STYLE><script>alert(1)</script>"));

        assertThatThrownBy(() -> renderer.editorPage("../"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("view-transitions.min.css");
    }

    @Test
    void theViewTransitionsStylesheetIsPrintedInline() {
        String page =
                new PageRenderer(AssetRegistry.load(), "/wp-admin/", Optional.of("@media{a{b:c}}\n")).editorPage("../");

        assertThat(page)
                .contains("<style id=\"wp-view-transitions-admin-inline-css\">\n@media{a{b:c}}\n"
                        + "/*# sourceURL=wp-view-transitions-admin-inline-css */\n</style>\n</head>");
    }

    @Test
    void theShellPagePrintsTheSameViewTransitionsStylesheet() {
        PageRenderer renderer = new PageRenderer(AssetRegistry.load(), "/wp-admin/", Optional.of("@media{a{b:c}}\n"));

        // every admin page opts in, as WordPress's admin screens do, so a navigation between two of them keeps its
        // transition
        assertThat(renderer.shellPage("../"))
                .contains("<link rel=\"stylesheet\" href=\"quarkword-admin/shell.css\">\n"
                        + "<style id=\"wp-view-transitions-admin-inline-css\">\n@media{a{b:c}}\n"
                        + "/*# sourceURL=wp-view-transitions-admin-inline-css */\n</style>\n</head>");
    }

    @Test
    void anUnsafeViewTransitionsStylesheetFailsTheShellPage() {
        PageRenderer renderer = new PageRenderer(
                AssetRegistry.load(), "/wp-admin/", Optional.of("@media{}</style><script>alert(1)</script>"));

        assertThatThrownBy(() -> renderer.shellPage("../"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("view-transitions.min.css");
    }
}
