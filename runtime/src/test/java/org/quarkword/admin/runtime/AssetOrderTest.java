package org.quarkword.admin.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkword.admin.runtime.AssetRegistry.Asset;
import org.quarkword.admin.runtime.AssetRegistry.Group;

/** WordPress's dependency resolution over a synthetic registry. */
class AssetOrderTest {

    @Test
    void dependenciesPrintFirstInRegisteredOrder() {
        Map<String, Asset> registry = registry(footer("a"), footer("b", "a"), footer("c", "b", "a"));

        assertThat(handles(AssetOrder.resolve(registry, List.of("c")))).containsExactly("a", "b", "c");
        // the registered order of the dependencies, not their names' order
        Map<String, Asset> reversed = registry(footer("a"), footer("b"), footer("c", "b", "a"));
        assertThat(handles(AssetOrder.resolve(reversed, List.of("c")))).containsExactly("b", "a", "c");
    }

    @Test
    void aHandleAlreadyPrintedIsSkipped() {
        Map<String, Asset> registry = registry(footer("a"), footer("b", "a"), footer("c", "a"));

        assertThat(handles(AssetOrder.resolve(registry, List.of("b", "c")))).containsExactly("a", "b", "c");
        assertThat(handles(AssetOrder.resolve(registry, List.of("b", "c", "b", "a"))))
                .containsExactly("a", "b", "c");
    }

    @Test
    void aHeadDependentPullsItsDependenciesIntoTheHead() {
        Map<String, Asset> registry = registry(
                footer("f0"),
                footer("f", "f0"),
                asset("h", Group.HEAD, "f"),
                footer("f2"),
                footer("g", "f2"),
                asset("h2", Group.HEAD));

        AssetOrder.Grouped grouped = AssetOrder.groups(AssetOrder.resolve(registry, List.of("g", "h", "h2")));

        assertThat(handles(grouped.head())).containsExactly("f0", "f", "h", "h2");
        assertThat(handles(grouped.footer())).containsExactly("f2", "g");
    }

    @Test
    void aFooterDependentLeavesAHeadDependencyInTheHead() {
        Map<String, Asset> registry = registry(asset("j", Group.HEAD), footer("k", "j"));

        AssetOrder.Grouped grouped = AssetOrder.groups(AssetOrder.resolve(registry, List.of("k")));

        assertThat(handles(grouped.head())).containsExactly("j");
        assertThat(handles(grouped.footer())).containsExactly("k");
    }

    @Test
    void anAliasPrintsNoTag() {
        Asset alias = new Asset("jquery", Optional.empty(), List.of("core", "migrate"), "3.7.1", Group.FOOTER, false);
        Map<String, Asset> registry = registry(footer("core"), footer("migrate"), alias, footer("ui", "jquery"));

        List<Asset> resolved = AssetOrder.resolve(registry, List.of("ui"));

        // the alias is listed in its place, so its inline scripts print there, but it has no file of its own
        assertThat(handles(resolved)).containsExactly("core", "migrate", "jquery", "ui");
        assertThat(resolved.get(2).file()).isEmpty();
    }

    @Test
    void aCycleOrAnUnknownHandleIsRefused() {
        Map<String, Asset> cycle = registry(footer("a", "b"), footer("b", "a"));
        assertThatThrownBy(() -> AssetOrder.resolve(cycle, List.of("a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a")
                .hasMessageContaining("b");

        Map<String, Asset> registry = registry(footer("a"));
        assertThatThrownBy(() -> AssetOrder.resolve(registry, List.of("nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    static List<String> handles(List<Asset> assets) {
        return assets.stream().map(Asset::handle).toList();
    }

    private static Map<String, Asset> registry(Asset... assets) {
        Map<String, Asset> registry = new LinkedHashMap<>();
        for (Asset asset : assets) {
            registry.put(asset.handle(), asset);
        }
        return registry;
    }

    private static Asset footer(String handle, String... deps) {
        return asset(handle, Group.FOOTER, deps);
    }

    private static Asset asset(String handle, Group group, String... deps) {
        return new Asset(handle, Optional.of("js/" + handle + ".js"), List.of(deps), "1", group, false);
    }
}
