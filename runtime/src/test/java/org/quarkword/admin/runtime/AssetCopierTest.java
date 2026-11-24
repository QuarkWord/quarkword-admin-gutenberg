package org.quarkword.admin.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkword.admin.build.AssetCopier;

/** The build-time copier takes exactly the listed files from the release zip, and nothing when anything is wrong. */
class AssetCopierTest {

    private static final Path BASE = Path.of("target", "asset-copier-test");

    Path zip;
    Path list;
    Path target;

    @BeforeEach
    void release() throws IOException {
        Files.createDirectories(BASE);
        Path work = Files.createTempDirectory(BASE, "run");
        zip = work.resolve("release.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            entry(out, "wordpress/");
            entry(out, "wordpress/a.js", "a script");
            entry(out, "wordpress/b/c.css", "a sheet");
            entry(out, "wordpress/d.js", "not listed");
            entry(out, "outside/evil.js", "outside the release folder");
        }
        list = work.resolve("files.txt");
        target = work.resolve("out");
    }

    @Test
    void copiesListedEntriesFromAZip() throws IOException {
        Files.writeString(list, "# a comment\n\na.js\n  b/c.css -> x/c.css  \n");

        AssetCopier.copy(list, zip, target, Optional.of(sha256(zip).toUpperCase(Locale.ROOT)));

        assertThat(Files.readString(target.resolve("a.js"))).isEqualTo("a script");
        assertThat(Files.readString(target.resolve("x/c.css"))).isEqualTo("a sheet");
        assertThat(files(target)).containsExactlyInAnyOrder("a.js", "x/c.css");
    }

    @Test
    void copiesListedFilesFromADirectory() throws IOException {
        Path unpacked = list.resolveSibling("unpacked");
        Files.createDirectories(unpacked.resolve("package/dist"));
        Files.writeString(unpacked.resolve("package/dist/client.js"), "client");
        Files.writeString(unpacked.resolve("package/LICENSE"), "licence");
        Files.writeString(unpacked.resolve("package/README.md"), "not listed");
        Files.writeString(
                list, "package/dist/client.js -> vendor/client.js\npackage/LICENSE -> vendor/client.LICENSE\n");

        AssetCopier.copy(list, unpacked, target, Optional.empty());

        assertThat(Files.readString(target.resolve("vendor/client.js"))).isEqualTo("client");
        assertThat(files(target)).containsExactlyInAnyOrder("vendor/client.js", "vendor/client.LICENSE");
    }

    @Test
    void aChecksumMismatchFailsBeforeCopying() throws IOException {
        Files.writeString(list, "a.js\n");
        String pinned = "00".repeat(32);

        assertThatThrownBy(() -> AssetCopier.copy(list, zip, target, Optional.of(pinned)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("QuarkWord admin: " + zip + " has SHA-256 " + sha256(zip) + ", the build pins " + pinned);
        assertThat(files(target)).isEmpty();
    }

    @Test
    void aMissingFileFailsTheBuild() throws IOException {
        Files.writeString(list, "a.js\nnope.js\nalso/missing.css\n");

        assertThatThrownBy(() -> AssetCopier.copy(list, zip, target, Optional.of(sha256(zip))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("QuarkWord admin: nope.js is not in " + zip + "\n"
                        + "QuarkWord admin: also/missing.css is not in " + zip);
        assertThat(files(target)).isEmpty();
    }

    @Test
    void aFolderOfTheZipIsNotAFile() throws IOException {
        Files.writeString(list, "a.js\nb\n");

        assertThatThrownBy(() -> AssetCopier.copy(list, zip, target, Optional.empty()))
                .hasMessage("QuarkWord admin: b is not in " + zip);
        assertThat(files(target)).isEmpty();
    }

    @Test
    void refusesEscapingTargets() throws IOException {
        Files.writeString(list, "a.js\na.js -> ../x\n");

        assertThatThrownBy(() -> AssetCopier.copy(list, zip, target, Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("../x");
        assertThat(files(target)).isEmpty();
        assertThat(target.resolveSibling("x")).doesNotExist();
    }

    @Test
    void refusesADrivePath() throws IOException {
        Files.writeString(list, "a.js\na.js -> c:/x\n");

        assertThatThrownBy(() -> AssetCopier.copy(list, zip, target, Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("c:/x");
        assertThat(files(target)).isEmpty();
    }

    @Test
    void neverReadsAnEntryOutsideTheReleaseFolder() throws IOException {
        Files.writeString(list, "../outside/evil.js -> evil.js\n");

        assertThatThrownBy(() -> AssetCopier.copy(list, zip, target, Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("../outside/evil.js");
        assertThat(files(target)).isEmpty();
    }

    @Test
    void refusesADigestForADirectory() throws IOException {
        Path unpacked = list.resolveSibling("unpacked");
        Files.createDirectories(unpacked);
        Files.writeString(unpacked.resolve("a.js"), "a");
        Files.writeString(list, "a.js\n");

        assertThatThrownBy(() -> AssetCopier.copy(list, unpacked, target, Optional.of("00".repeat(32))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(unpacked.toString());
        assertThat(files(target)).isEmpty();
    }

    private static void entry(ZipOutputStream out, String name) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.closeEntry();
    }

    private static void entry(ZipOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static List<String> files(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .toList();
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
