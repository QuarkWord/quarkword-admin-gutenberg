package org.quarkpress.admin.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * WordPress's script and style handles for the editor page, as {@code wordpress-handles.json} registers them: each
 * handle's file (none for an alias, which only gathers dependencies), dependencies, version and group; the handles the
 * page enqueues; the static inline scripts printed beside a handle's tag; the import map and the one script module the
 * editor loads. Read with Jackson's tree model; a field the file format does not know, a handle registered twice, a
 * dependency, queued handle or inline script naming a handle that is not registered, or an inline script that would
 * close its own tag stops the load.
 */
@ApplicationScoped
public class AssetRegistry {

    static final String RESOURCE = AssetFiles.ROOT + "wordpress-handles.json";

    private static final Set<String> TOP_FIELDS =
            Set.of("version", "scripts", "styles", "queue", "inline", "importmap", "module");

    private static final Set<String> SCRIPT_FIELDS = Set.of("handle", "file", "deps", "ver", "group");

    private static final Set<String> STYLE_FIELDS = Set.of("handle", "file", "deps", "ver", "rtl");

    private static final Set<String> QUEUE_FIELDS = Set.of("scripts", "styles");

    private static final Set<String> INLINE_FIELDS = Set.of("handle", "position", "script");

    private static final Set<String> MODULE_FIELDS = Set.of("id", "file", "ver");

    /** Where a script prints: in the page's head, or at the end of its body. */
    public enum Group {
        HEAD,
        FOOTER
    }

    /** Where an inline script prints: right before its handle's tag, or right after it. */
    public enum Position {
        BEFORE,
        AFTER
    }

    /**
     * A script or style handle. A style's group is always {@link Group#HEAD}; {@code rtl} says whether a right-to-left
     * page swaps its file for the {@code -rtl} one (scripts: false).
     */
    public record Asset(
            String handle, Optional<String> file, List<String> deps, String ver, Group group, boolean rtl) {}

    /** A static inline script printed beside a handle's tag. */
    public record Inline(String handle, Position position, String script) {}

    private record Data(
            Map<String, Asset> scripts,
            Map<String, Asset> styles,
            List<String> scriptQueue,
            List<String> styleQueue,
            List<Inline> inlines,
            ObjectNode importMap,
            Asset module) {}

    private final Supplier<String> source;

    private volatile Data data;

    /** The registry the jar carries, read on first use. */
    public AssetRegistry() {
        this(AssetRegistry::resourceText);
    }

    /** A registry built on first use from the text a source gives. */
    AssetRegistry(Supplier<String> source) {
        this.source = source;
    }

    private AssetRegistry(Data data) {
        this.source = AssetRegistry::resourceText;
        this.data = data;
    }

    /** The registry the jar carries. */
    public static AssetRegistry load() {
        return new AssetRegistry(build(resourceText()));
    }

    /** A registry from the text of a {@code wordpress-handles.json}. */
    static AssetRegistry parse(String json) {
        return new AssetRegistry(build(json));
    }

    /** The script handles in the order the file registers them. */
    public List<Asset> scripts() {
        return List.copyOf(data().scripts().values());
    }

    /** The style handles in the order the file registers them. */
    public List<Asset> styles() {
        return List.copyOf(data().styles().values());
    }

    /** The scripts the editor page enqueues, in enqueue order. */
    public List<String> scriptQueue() {
        return data().scriptQueue();
    }

    /** The styles the editor page enqueues, in enqueue order. */
    public List<String> styleQueue() {
        return data().styleQueue();
    }

    /** The static inline scripts, in the order the file lists them. */
    public List<Inline> inlines() {
        return data().inlines();
    }

    /** The script handles by name, in registration order. */
    public Map<String, Asset> scriptsByHandle() {
        return data().scripts();
    }

    /** The style handles by name, in registration order. */
    public Map<String, Asset> stylesByHandle() {
        return data().styles();
    }

    /** The import map: {@code imports} from a module id to its file below the site's root, with its version. */
    public ObjectNode importMap() {
        return data().importMap().deepCopy();
    }

    /** The script module the editor page loads: its id as the handle, its file and version. */
    public Asset module() {
        return data().module();
    }

    private Data data() {
        Data loaded = data;
        if (loaded == null) {
            loaded = build(source.get());
            data = loaded;
        }
        return loaded;
    }

    private static String resourceText() {
        try (InputStream in = AssetRegistry.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("QuarkPress admin: the jar has no " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Data build(String json) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("QuarkPress admin: wordpress-handles.json is not JSON", failure);
        }
        fields(root, TOP_FIELDS, "the file");

        Map<String, Asset> scripts = new LinkedHashMap<>();
        for (JsonNode node : array(root, "scripts")) {
            fields(node, SCRIPT_FIELDS, "a script");
            Asset asset = new Asset(
                    text(node, "handle"),
                    file(node),
                    strings(node, "deps"),
                    text(node, "ver"),
                    group(text(node, "group")),
                    false);
            register(scripts, asset);
        }
        Map<String, Asset> styles = new LinkedHashMap<>();
        for (JsonNode node : array(root, "styles")) {
            fields(node, STYLE_FIELDS, "a style");
            JsonNode rtl = node.get("rtl");
            if (rtl == null || !rtl.isBoolean()) {
                throw new IllegalStateException("QuarkPress admin: the style " + text(node, "handle")
                        + " has no rtl true or false in wordpress-handles.json");
            }
            Asset asset = new Asset(
                    text(node, "handle"),
                    file(node),
                    strings(node, "deps"),
                    text(node, "ver"),
                    Group.HEAD,
                    rtl.asBoolean());
            register(styles, asset);
        }
        checkDependencies(scripts);
        checkDependencies(styles);

        JsonNode queue = object(root, "queue");
        fields(queue, QUEUE_FIELDS, "the queue");
        List<String> scriptQueue = strings(queue, "scripts");
        List<String> styleQueue = strings(queue, "styles");
        checkRegistered(scripts, scriptQueue, "the script queue");
        checkRegistered(styles, styleQueue, "the style queue");

        List<Inline> inlines = new ArrayList<>();
        for (JsonNode node : array(root, "inline")) {
            fields(node, INLINE_FIELDS, "an inline script");
            String handle = text(node, "handle");
            checkRegistered(scripts, List.of(handle), "an inline script");
            String script = text(node, "script");
            if (lowerAscii(script).contains("</script")) {
                throw new IllegalStateException(
                        "QuarkPress admin: an inline script of " + handle + " would close its own tag");
            }
            inlines.add(new Inline(handle, position(text(node, "position")), script));
        }

        ObjectNode importMap = (ObjectNode) object(root, "importmap");
        if (!importMap.has("imports") || !importMap.get("imports").isObject() || importMap.size() != 1) {
            throw new IllegalStateException("QuarkPress admin: the import map must hold only imports");
        }
        for (JsonNode target : importMap.get("imports")) {
            if (!target.isTextual()) {
                throw new IllegalStateException("QuarkPress admin: an import map target is not a path");
            }
        }
        JsonNode moduleNode = object(root, "module");
        fields(moduleNode, MODULE_FIELDS, "the module");
        Asset module = new Asset(
                text(moduleNode, "id"),
                Optional.of(text(moduleNode, "file")),
                List.of(),
                text(moduleNode, "ver"),
                Group.FOOTER,
                false);

        return new Data(
                Collections.unmodifiableMap(scripts),
                Collections.unmodifiableMap(styles),
                scriptQueue,
                styleQueue,
                List.copyOf(inlines),
                importMap,
                module);
    }

    private static void register(Map<String, Asset> registry, Asset asset) {
        if (registry.putIfAbsent(asset.handle(), asset) != null) {
            throw new IllegalStateException("QuarkPress admin: the handle " + asset.handle() + " is registered twice");
        }
    }

    private static void checkDependencies(Map<String, Asset> registry) {
        for (Asset asset : registry.values()) {
            for (String dep : asset.deps()) {
                if (!registry.containsKey(dep)) {
                    throw new IllegalStateException("QuarkPress admin: the handle " + asset.handle() + " depends on "
                            + dep + ", which is not registered");
                }
            }
        }
    }

    private static void checkRegistered(Map<String, Asset> registry, List<String> handles, String where) {
        for (String handle : handles) {
            if (!registry.containsKey(handle)) {
                throw new IllegalStateException(
                        "QuarkPress admin: " + where + " names " + handle + ", which is not registered");
            }
        }
    }

    private static void fields(JsonNode node, Set<String> known, String what) {
        if (!node.isObject()) {
            throw new IllegalStateException(
                    "QuarkPress admin: " + what + " in wordpress-handles.json is not an object");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!known.contains(name)) {
                throw new IllegalStateException(
                        "QuarkPress admin: " + what + " in wordpress-handles.json has an unknown field " + name);
            }
        }
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException("QuarkPress admin: wordpress-handles.json has no array " + field);
        }
        return value;
    }

    private static JsonNode object(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("QuarkPress admin: wordpress-handles.json has no object " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalStateException(
                    "QuarkPress admin: a " + field + " in wordpress-handles.json is not a text");
        }
        return value.asText();
    }

    private static Optional<String> file(JsonNode node) {
        JsonNode value = node.get("file");
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        return Optional.of(text(node, "file"));
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array(node, field)) {
            if (!value.isTextual()) {
                throw new IllegalStateException("QuarkPress admin: " + field + " in wordpress-handles.json holds a"
                        + " value that is not a text");
            }
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    private static Group group(String group) {
        return switch (group) {
            case "head" -> Group.HEAD;
            case "footer" -> Group.FOOTER;
            default -> throw new IllegalStateException("QuarkPress admin: unknown script group " + group);
        };
    }

    private static Position position(String position) {
        return switch (position) {
            case "before" -> Position.BEFORE;
            case "after" -> Position.AFTER;
            default -> throw new IllegalStateException("QuarkPress admin: unknown inline position " + position);
        };
    }

    private static String lowerAscii(String text) {
        StringBuilder lower = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return lower.toString();
    }
}
