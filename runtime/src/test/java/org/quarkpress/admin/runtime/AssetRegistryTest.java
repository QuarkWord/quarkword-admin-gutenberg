package org.quarkpress.admin.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quarkpress.admin.runtime.AssetOrderTest.handles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.quarkpress.admin.runtime.AssetRegistry.Asset;
import org.quarkpress.admin.runtime.AssetRegistry.Inline;
import org.quarkpress.admin.runtime.AssetRegistry.Position;

/** The real {@code wordpress-handles.json}: it loads, and its queue resolves to WordPress's own printed order. */
class AssetRegistryTest {

    /**
     * The script tags ({@code <handle>-js}) of WordPress 7.1.3's uncompressed editor page as a real browser received it
     * (the browser facts' page {@code s01.html}), in their order, restricted to the handles the jar ships, without the
     * five head scripts.
     */
    static final List<String> S01_FOOTER_SHIPPED = List.of(
            "wp-dom-ready",
            "wp-hooks",
            "wp-i18n",
            "wp-a11y",
            "underscore",
            "backbone",
            "wp-util",
            "wp-backbone",
            "media-models",
            "wp-plupload",
            "jquery-ui-core",
            "jquery-ui-mouse",
            "jquery-ui-sortable",
            "mediaelement-core",
            "mediaelement-migrate",
            "wp-mediaelement",
            "wp-api-request",
            "clipboard",
            "media-views",
            "wp-tooltip",
            "postbox",
            "react",
            "react-dom",
            "react-jsx-runtime",
            "wp-private-apis",
            "wp-url",
            "wp-api-fetch",
            "wp-blob",
            "wp-block-serialization-default-parser",
            "wp-autop",
            "wp-deprecated",
            "wp-dom",
            "wp-escape-html",
            "wp-element",
            "wp-is-shallow-equal",
            "wp-keycodes",
            "wp-priority-queue",
            "wp-undo-manager",
            "wp-compose",
            "wp-redux-routine",
            "wp-data",
            "wp-html-entities",
            "wp-rich-text",
            "wp-shortcode",
            "wp-warning",
            "wp-blocks",
            "moment",
            "wp-date",
            "wp-primitives",
            "wp-theme",
            "wp-components",
            "wp-keyboard-shortcuts",
            "wp-preferences-persistence",
            "wp-preferences",
            "wp-commands",
            "wp-notices",
            "wp-style-engine",
            "wp-token-list",
            "wp-upload-media",
            "wp-block-editor",
            "wp-core-data",
            "wp-patterns",
            "wp-server-side-render",
            "wp-wordcount",
            "editor",
            "wp-block-library",
            "wp-media-utils",
            "wp-plugins",
            "wp-viewport",
            "wp-editor",
            "wp-widgets",
            "wp-edit-post",
            "wp-block-directory",
            "wp-format-library",
            "wp-router",
            "wp-core-commands",
            "wp-tinymce-root",
            "wp-tinymce");

    /**
     * The stylesheet tags of the same page in their order, restricted to the shipped handles, plus the two a classic
     * theme adds (that page ran a block theme): the classic layout styles before the block-library editor styles, the
     * classic theme styles last.
     */
    static final List<String> S01_STYLES_SHIPPED = List.of(
            "dashicons",
            "buttons",
            "editor-buttons",
            "wp-theme",
            "wp-components",
            "wp-preferences",
            "wp-block-editor",
            "wp-reusable-blocks",
            "wp-patterns",
            "wp-media-utils",
            "wp-editor",
            "wp-base-styles",
            "common",
            "forms",
            "wp-reset-editor-styles",
            "wp-block-library",
            "wp-block-editor-content",
            "wp-editor-classic-layout-styles",
            "wp-edit-blocks",
            "wp-commands",
            "wp-edit-post",
            "wp-block-directory",
            "wp-format-library",
            "colors",
            "classic-theme-styles");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theRealRegistryResolves() {
        AssetRegistry registry = AssetRegistry.load();

        AssetOrder.Grouped grouped =
                AssetOrder.groups(AssetOrder.resolve(registry.scriptsByHandle(), registry.scriptQueue()));
        List<String> head = tags(grouped.head());
        List<String> footer = tags(grouped.footer());

        assertThat(head).containsExactly("jquery-core", "jquery-migrate", "utils", "moxiejs", "plupload");
        assertThat(footer).containsExactlyElementsOf(S01_FOOTER_SHIPPED);
        assertThat(handles(grouped.footer()))
                .doesNotContain(
                        "heartbeat",
                        "wp-auth-check",
                        "common",
                        "admin-bar",
                        "media-editor",
                        "quicktags",
                        "wplink",
                        "thickbox");
        // the aliases print their inline scripts in place, in the footer
        assertThat(handles(grouped.footer())).contains("mediaelement", "wp-block-styles");

        List<Asset> styles = AssetOrder.resolve(registry.stylesByHandle(), registry.styleQueue());
        assertThat(tags(styles)).containsExactlyElementsOf(S01_STYLES_SHIPPED);
    }

    @Test
    void theRegistryCarriesTheStaticInlineScriptsAndTheModule() {
        AssetRegistry registry = AssetRegistry.load();

        assertThat(registry.inlines())
                .extracting(Inline::handle, Inline::position)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("media-views", Position.BEFORE),
                        org.assertj.core.groups.Tuple.tuple("editor", Position.AFTER),
                        org.assertj.core.groups.Tuple.tuple("wp-editor", Position.BEFORE),
                        org.assertj.core.groups.Tuple.tuple("wp-editor", Position.AFTER),
                        org.assertj.core.groups.Tuple.tuple("wp-tinymce-root", Position.BEFORE),
                        org.assertj.core.groups.Tuple.tuple("wp-tinymce", Position.AFTER));
        assertThat(registry.inlines())
                .noneMatch(inline -> inline.script().contains("localhost")
                        || inline.script().contains("http://")
                        || inline.script().contains("https://"));
        // WordPress prints the Classic block's empty external plugins as a JSON array
        assertThat(registry.inlines())
                .filteredOn(inline -> inline.handle().equals("wp-block-library"))
                .singleElement()
                .satisfies(inline -> assertThat(inline.script()).contains("external_plugins:[],"));
        assertThat(registry.module().handle()).isEqualTo("@wordpress/latex-to-mathml/loader");
        assertThat(registry.module().file())
                .contains("wp-includes/js/dist/script-modules/latex-to-mathml/loader.min.js");
        assertThat(registry.importMap().get("imports").size()).isEqualTo(2);
        // insertion-ordered, as the file lists them
        assertThat(registry.scriptsByHandle().keySet()).first().isEqualTo("react");
    }

    @Test
    void anUnregisteredDependencyFailsTheLoad() throws IOException {
        String json = edited(tree -> {
            for (var script : (ArrayNode) tree.get("scripts")) {
                if (script.get("handle").asText().equals("wp-edit-post")) {
                    ((ArrayNode) script.get("deps")).add("nope");
                }
            }
        });

        assertThatThrownBy(() -> AssetRegistry.parse(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("QuarkPress admin: the handle wp-edit-post depends on nope, which is not registered");
    }

    @Test
    void aHandleNamedTwiceOrAnUnknownFieldFailsTheLoad() throws IOException {
        String twice = edited(tree ->
                ((ArrayNode) tree.get("styles")).add(tree.get("styles").get(0).deepCopy()));
        assertThatThrownBy(() -> AssetRegistry.parse(twice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dashicons");

        String unknown = edited(tree -> ((ObjectNode) tree.get("scripts").get(0)).put("strategy", "defer"));
        assertThatThrownBy(() -> AssetRegistry.parse(unknown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strategy");

        String queued = edited(tree -> ((ArrayNode) tree.get("queue").get("scripts")).add("heartbeat"));
        assertThatThrownBy(() -> AssetRegistry.parse(queued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heartbeat");

        String closing = edited(tree -> ((ObjectNode) tree.get("inline").get(0)).put("script", "a</script>b"));
        assertThatThrownBy(() -> AssetRegistry.parse(closing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jquery-ui-core");
        // the check ignores case, as the HTML parser does
        String upper = edited(tree -> ((ObjectNode) tree.get("inline").get(1)).put("script", "a</SCRIPT >b"));
        assertThatThrownBy(() -> AssetRegistry.parse(upper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mediaelement-core");
    }

    /** The handles that print a tag of their own: aliases have no file. */
    private static List<String> tags(List<Asset> assets) {
        return assets.stream()
                .filter(asset -> asset.file().isPresent())
                .map(Asset::handle)
                .toList();
    }

    static String edited(Consumer<ObjectNode> edit) throws IOException {
        try (InputStream in = AssetRegistryTest.class
                .getClassLoader()
                .getResourceAsStream("org/quarkpress/admin/wordpress-handles.json")) {
            ObjectNode tree = (ObjectNode) JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            edit.accept(tree);
            return JSON.writeValueAsString(tree);
        }
    }
}
