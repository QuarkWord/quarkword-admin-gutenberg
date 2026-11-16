package org.quarkpress.admin.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The manifests and the files the build copied into the jar's resources. */
class AssetFilesTest {

    final AssetFiles files = new AssetFiles();

    @Test
    void everyListedWordPressFileIsInTheJar() throws IOException {
        List<String> lines = listedLines("wordpress-files.txt");
        assertThat(files.wordpressPaths()).hasSize(lines.size()).containsAll(lines);
        assertThat(files.wordpressPaths()).contains("wp-includes/js/dist/edit-post.min.js", "license.txt");
        for (String path : files.wordpressPaths()) {
            assertThat(files.wordpress(path)).as(path).isPresent();
        }
    }

    @Test
    void theVendorFilesAreInTheJarUnderTheirTargets() {
        assertThat(files.vendorPaths())
                .containsExactlyInAnyOrder("vendor/oidc-client-ts.js", "vendor/oidc-client-ts.LICENSE");
        for (String path : files.vendorPaths()) {
            assertThat(files.vendor(path)).as(path).isPresent();
        }
    }

    @Test
    void onlyListedFilesAreFound() {
        assertThat(files.wordpress("wp-includes/js/nope.js")).isEmpty();
        assertThat(files.wordpress("../wordpress-files.txt")).isEmpty();
        assertThat(files.vendor("package/dist/browser/oidc-client-ts.js")).isEmpty();
        assertThat(files.vendor("vendor/../wordpress-files.txt")).isEmpty();
        assertThat(files.shell("nope.js")).isEmpty();
        assertThat(files.shell("shell-files.txt")).isEmpty();
        assertThat(files.shell("../wordpress-files.txt")).isEmpty();
        assertThat(files.shell("x/../../wordpress-files.txt")).isEmpty();
    }

    @Test
    void aShellFolderIsNeverAFile() {
        // the test resources hold shell/test-folder/probe.js, which the class loader finds but the list does not name
        assertThat(AssetFiles.class.getClassLoader().getResource(AssetFiles.ROOT + "shell/test-folder/probe.js"))
                .isNotNull();
        assertThat(files.shell("test-folder/probe.js")).isEmpty();
        assertThat(files.shell("test-folder")).isEmpty();
        assertThat(files.shell("test-folder/")).isEmpty();
        // the shell's own folder named like a file
        assertThat(files.shell("screens")).isEmpty();
    }

    @Test
    void aShellListNamingAnythingButAServedFileStopsTheLoad() {
        assertThat(AssetFiles.shellTargets(Set.of("app.js", "screens/lists.js", "notes.txt")))
                .hasSize(3);
        for (String bad :
                List.of("../x.js", "a//b.js", ".hidden.js", "a/.b/c.js", "a\\b.js", "notes.bin", "folder/", "")) {
            assertThatThrownBy(() -> AssetFiles.shellTargets(Set.of(bad)))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("QuarkPress admin: shell/shell-files.txt names " + bad
                            + ", which is no file the shell serves");
        }
    }

    @Test
    void everyShellFileIsListedAndInTheJar() throws IOException {
        Path folder = Path.of("src/main/resources", AssetFiles.ROOT, "shell");
        List<String> onDisk = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.filter(Files::isRegularFile)
                    .map(file -> folder.relativize(file).toString().replace('\\', '/'))
                    .filter(name -> !name.equals("shell-files.txt"))
                    .forEach(onDisk::add);
        }
        assertThat(files.shellPaths()).containsExactlyInAnyOrderElementsOf(onDisk);
        assertThat(files.shellPaths()).contains("app.js", "shell.css", "screens/lists.js");
        for (String path : files.shellPaths()) {
            assertThat(files.shell(path)).as(path).isPresent();
            assertThat(AssetFiles.contentType(path)).as(path).isNotEqualTo("application/octet-stream");
        }
    }

    @Test
    void theShellsBrowserFilesCarryTheLicenceLine() throws IOException {
        for (String path : files.shellPaths()) {
            String first;
            try (InputStream in = files.shell(path).orElseThrow().openStream()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                first = text.substring(0, text.indexOf('\n'));
            }
            String expected = path.endsWith(".css")
                    ? "/* SPDX-License-Identifier: GPL-2.0-or-later */"
                    : "// SPDX-License-Identifier: GPL-2.0-or-later";
            assertThat(first).as(path).isEqualTo(expected);
        }
    }

    @Test
    void theManifestsAgree() throws IOException {
        JsonNode handles;
        try (InputStream in = resource("wordpress-handles.json")) {
            handles = new ObjectMapper().readTree(in);
        }
        List<String> named = new ArrayList<>();
        for (String group : List.of("scripts", "styles")) {
            for (JsonNode handle : handles.get(group)) {
                if (!handle.get("file").isNull()) {
                    named.add(handle.get("file").asText());
                }
            }
        }
        assertThat(named).hasSizeGreaterThan(100);
        assertThat(files.wordpressPaths()).containsAll(named);
        List<String> rtl = new ArrayList<>();
        for (JsonNode style : handles.get("styles")) {
            if (style.get("rtl").asBoolean()) {
                String file = style.get("file").asText();
                assertThat(file).as(style.get("handle").asText()).endsWith(".min.css");
                rtl.add(file.substring(0, file.length() - ".min.css".length()) + "-rtl.min.css");
            }
        }
        assertThat(rtl).isNotEmpty();
        assertThat(files.wordpressPaths()).containsAll(rtl);
    }

    @Test
    void contentTypes() {
        assertThat(AssetFiles.contentType("wp-includes/js/dist/hooks.min.js"))
                .isEqualTo("text/javascript; charset=utf-8");
        assertThat(AssetFiles.contentType("a/b.mjs")).isEqualTo("text/javascript; charset=utf-8");
        assertThat(AssetFiles.contentType("a/style.min.css")).isEqualTo("text/css; charset=utf-8");
        assertThat(AssetFiles.contentType("a/b.JSON")).isEqualTo("application/json");
        assertThat(AssetFiles.contentType("a/b.js.map")).isEqualTo("application/json");
        assertThat(AssetFiles.contentType("index.html")).isEqualTo("text/html; charset=utf-8");
        assertThat(AssetFiles.contentType("fonts/dashicons.svg")).isEqualTo("image/svg+xml");
        assertThat(AssetFiles.contentType("stars.png")).isEqualTo("image/png");
        assertThat(AssetFiles.contentType("spinner.gif")).isEqualTo("image/gif");
        assertThat(AssetFiles.contentType("a.jpg")).isEqualTo("image/jpeg");
        assertThat(AssetFiles.contentType("a.jpeg")).isEqualTo("image/jpeg");
        assertThat(AssetFiles.contentType("a.webp")).isEqualTo("image/webp");
        assertThat(AssetFiles.contentType("tinymce.woff")).isEqualTo("font/woff");
        assertThat(AssetFiles.contentType("dashicons.woff2")).isEqualTo("font/woff2");
        assertThat(AssetFiles.contentType("dashicons.ttf")).isEqualTo("font/ttf");
        assertThat(AssetFiles.contentType("dashicons.eot")).isEqualTo("application/vnd.ms-fontobject");
        assertThat(AssetFiles.contentType("license.txt")).isEqualTo("text/plain; charset=utf-8");
        assertThat(AssetFiles.contentType("x")).isEqualTo("application/octet-stream");
        assertThat(AssetFiles.contentType("a.d/x")).isEqualTo("application/octet-stream");
        assertThat(AssetFiles.contentType("vendor/oidc-client-ts.LICENSE")).isEqualTo("application/octet-stream");
    }

    /** The manifest's targets, read here independently of {@link AssetFiles}. */
    private static List<String> listedLines(String name) throws IOException {
        String text;
        try (InputStream in = resource(name)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> targets = new ArrayList<>();
        for (String line : text.lines().map(String::strip).toList()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int arrow = line.indexOf(" -> ");
            targets.add(arrow < 0 ? line : line.substring(arrow + 4).strip());
        }
        return targets;
    }

    private static InputStream resource(String name) {
        InputStream in = AssetFilesTest.class.getClassLoader().getResourceAsStream("org/quarkpress/admin/" + name);
        assertThat(in).as(name).isNotNull();
        return in;
    }
}
