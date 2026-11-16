package org.quarkpress.admin.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.quarkpress.admin.runtime.AssetRegistry.Asset;
import org.quarkpress.admin.runtime.AssetRegistry.Group;

/**
 * WordPress's order for printing handles: a depth-first walk over the queue in enqueue order, each handle's
 * dependencies first, in the order they were registered, and every handle once, where it is first reached. Scripts are
 * then split into the head and the footer: a script prints in the head when it was registered there or when a head
 * script depends on it, directly or not; styles all print in the head, in resolution order.
 */
public final class AssetOrder {

    /** The resolved scripts of each group, each in resolution order. */
    public record Grouped(List<Asset> head, List<Asset> footer) {}

    private AssetOrder() {}

    /** The queue's handles and everything they depend on, in printing order; aliases are listed in their place. */
    public static List<Asset> resolve(Map<String, Asset> registry, List<String> queue) {
        Set<String> done = new LinkedHashSet<>();
        Set<String> walking = new HashSet<>();
        List<Asset> ordered = new ArrayList<>();
        for (String handle : queue) {
            visit(registry, handle, null, done, walking, ordered);
        }
        return List.copyOf(ordered);
    }

    /** Resolved scripts split into the head group and the footer group. */
    public static Grouped groups(List<Asset> ordered) {
        Map<String, Asset> byHandle = new HashMap<>();
        for (Asset asset : ordered) {
            byHandle.put(asset.handle(), asset);
        }
        Set<String> head = new HashSet<>();
        for (Asset asset : ordered) {
            if (asset.group() == Group.HEAD) {
                markHead(byHandle, asset, head);
            }
        }
        List<Asset> headAssets = new ArrayList<>();
        List<Asset> footerAssets = new ArrayList<>();
        for (Asset asset : ordered) {
            (head.contains(asset.handle()) ? headAssets : footerAssets).add(asset);
        }
        return new Grouped(List.copyOf(headAssets), List.copyOf(footerAssets));
    }

    private static void visit(
            Map<String, Asset> registry,
            String handle,
            String dependent,
            Set<String> done,
            Set<String> walking,
            List<Asset> ordered) {
        if (done.contains(handle)) {
            return;
        }
        Asset asset = registry.get(handle);
        if (asset == null) {
            throw new IllegalStateException(
                    dependent == null
                            ? "QuarkPress admin: the queued handle " + handle + " is not registered"
                            : "QuarkPress admin: the handle " + dependent + " depends on " + handle
                                    + ", which is not registered");
        }
        if (!walking.add(handle)) {
            throw new IllegalStateException(
                    "QuarkPress admin: the handle " + handle + " depends on itself through " + dependent);
        }
        for (String dep : asset.deps()) {
            visit(registry, dep, handle, done, walking, ordered);
        }
        walking.remove(handle);
        done.add(handle);
        ordered.add(asset);
    }

    private static void markHead(Map<String, Asset> byHandle, Asset asset, Set<String> head) {
        if (!head.add(asset.handle())) {
            return;
        }
        for (String dep : asset.deps()) {
            Asset dependency = byHandle.get(dep);
            if (dependency != null) {
                markHead(byHandle, dependency, head);
            }
        }
    }
}
