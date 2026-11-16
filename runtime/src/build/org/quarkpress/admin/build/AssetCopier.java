package org.quarkpress.admin.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Copies the files a list names from a release archive into the build's resources. Run by the build as a single-file
 * program: {@code java AssetCopier.java <list> <source> <target> [<sha256>]}.
 *
 * <p>The source is a {@code .zip} file, whose files are read below its {@code wordpress/} folder, or a directory. A
 * zip's SHA-256 is checked against the pinned one first; then every listed file must be in the source; only then is
 * anything copied, so a build either carries the whole list or fails. Each list line is a path in the source, or
 * {@code source -> target} to copy it under another name; blank lines and lines starting with {@code #} are skipped.
 */
public final class AssetCopier {

    /** The folder of the WordPress release zip that holds the release's files. */
    static final String ZIP_ROOT = "wordpress/";

    private static final String PREFIX = "QuarkPress admin: ";

    private AssetCopier() {}

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            System.err.println("usage: AssetCopier <list> <source> <target> [<sha256>]");
            System.exit(1);
        }
        try {
            copy(
                    Path.of(args[0]),
                    Path.of(args[1]),
                    Path.of(args[2]),
                    args.length == 4 ? Optional.of(args[3]) : Optional.empty());
        } catch (IllegalStateException | IOException failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        }
    }

    /**
     * Copies the files {@code list} names from {@code source} into {@code target}; nothing when the source's digest
     * differs from {@code sha256} (compared ignoring case), when a line is malformed or leaves its folder, or when a
     * listed file is missing (all missing paths reported, one per line).
     */
    public static void copy(Path list, Path source, Path target, Optional<String> sha256) throws IOException {
        List<Line> lines = lines(list);
        boolean directory = Files.isDirectory(source);
        if (sha256.isPresent()) {
            if (directory) {
                throw new IllegalStateException(PREFIX + source + " is a directory: only a file's SHA-256 is checked");
            }
            String actual = sha256(source);
            if (!actual.equalsIgnoreCase(sha256.get().strip())) {
                throw new IllegalStateException(
                        PREFIX + source + " has SHA-256 " + actual + ", the build pins " + sha256.get());
            }
        }
        if (directory) {
            copyFromDirectory(lines, source, target);
        } else {
            copyFromZip(lines, source, target);
        }
    }

    private static void copyFromZip(List<Line> lines, Path source, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            List<String> missing = new ArrayList<>();
            List<ZipEntry> entries = new ArrayList<>();
            for (Line line : lines) {
                ZipEntry entry = zip.getEntry(ZIP_ROOT + line.source());
                if (entry == null || entry.isDirectory()) {
                    missing.add(line.source());
                }
                entries.add(entry);
            }
            requireNoneMissing(missing, source);
            for (int i = 0; i < lines.size(); i++) {
                try (InputStream in = zip.getInputStream(entries.get(i))) {
                    write(in, target.resolve(lines.get(i).target()));
                }
            }
        }
    }

    private static void copyFromDirectory(List<Line> lines, Path source, Path target) throws IOException {
        List<String> missing = new ArrayList<>();
        for (Line line : lines) {
            if (!Files.isRegularFile(source.resolve(line.source()))) {
                missing.add(line.source());
            }
        }
        requireNoneMissing(missing, source);
        for (Line line : lines) {
            try (InputStream in = Files.newInputStream(source.resolve(line.source()))) {
                write(in, target.resolve(line.target()));
            }
        }
    }

    private static void requireNoneMissing(List<String> missing, Path source) {
        if (!missing.isEmpty()) {
            StringBuilder message = new StringBuilder();
            for (String path : missing) {
                if (!message.isEmpty()) {
                    message.append('\n');
                }
                message.append(PREFIX).append(path).append(" is not in ").append(source);
            }
            throw new IllegalStateException(message.toString());
        }
    }

    private static void write(InputStream in, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** One copy: a path in the source and the path it gets below the target. */
    record Line(String source, String target) {}

    /** The list's lines, every path checked to stay inside its folder before anything is read. */
    static List<Line> lines(Path list) throws IOException {
        List<Line> lines = new ArrayList<>();
        for (String raw : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int arrow = line.indexOf("->");
            String from = arrow < 0 ? line : line.substring(0, arrow).strip();
            String to = arrow < 0 ? line : line.substring(arrow + 2).strip();
            requireInside(from, line);
            requireInside(to, line);
            lines.add(new Line(from, to));
        }
        return lines;
    }

    /** Refuses an empty or absolute path, a backslash, an empty segment, and a {@code .} or {@code ..} segment. */
    private static void requireInside(String path, String line) {
        boolean bad = path.isEmpty() || path.charAt(0) == '/' || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0;
        int start = 0;
        while (!bad && start <= path.length()) {
            int end = path.indexOf('/', start);
            if (end < 0) {
                end = path.length();
            }
            String segment = path.substring(start, end);
            bad = segment.isEmpty() || segment.equals(".") || segment.equals("..");
            start = end + 1;
        }
        if (bad) {
            throw new IllegalStateException(
                    PREFIX + "the list line \"" + line + "\" names a path outside its folder: " + path);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
