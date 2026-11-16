package org.quarkpress.admin.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which request paths the admin screens answer. Under a site path of up to {@value #SITE_SEGMENTS} segments:
 * WordPress's files under {@code wp-includes/} and {@code wp-admin/{css,js,images}/} (WordPress's own layout, whatever
 * the admin path), and below the configured admin path the shell's pages, the editor's pages, and the shell's own files
 * under {@code quarkpress-admin/}. A path with a {@code .} or {@code ..} segment, a segment starting with a dot, an encoded
 * dot ({@code %2e}), a backslash or an empty segment is answered by none of them.
 */
public final class AdminPaths {

    /** The most segments of a site path in front of the admin screens' paths. */
    static final int SITE_SEGMENTS = 4;

    /** The folder below the admin path that holds the shell's own files. */
    static final String SHELL_FOLDER = "quarkpress-admin";

    private static final Set<String> SHELL_PAGES = Set.of("", "index.php", "edit.php", "upload.php");

    private static final Set<String> EDITOR_PAGES = Set.of("post.php", "post-new.php", "revision.php");

    private static final Set<String> ADMIN_FILE_FOLDERS = Set.of("css", "js", "images");

    /** What a request path names. */
    public enum Kind {
        WP_FILE,
        SHELL_FILE,
        VENDOR_FILE,
        SHELL_PAGE,
        EDITOR_PAGE,
        CONFIG
    }

    /** A request path's kind and the path that names it in its kind: a file's path, or a page's file name. */
    public record Target(Kind kind, String path) {}

    private final List<String> adminSegments;

    private AdminPaths(List<String> adminSegments) {
        this.adminSegments = adminSegments;
    }

    /**
     * The paths for an admin path such as {@code /wp-admin/}; refused unless it starts and ends with {@code /} and has
     * at least one segment, none of them empty or starting with a dot.
     */
    public static AdminPaths of(String adminPath) {
        boolean bad = adminPath == null
                || adminPath.length() < 3
                || adminPath.charAt(0) != '/'
                || adminPath.charAt(adminPath.length() - 1) != '/';
        List<String> segments = bad ? List.of() : segments(adminPath.substring(1, adminPath.length() - 1));
        for (String segment : segments) {
            bad |= segment.isEmpty() || segment.charAt(0) == '.' || segment.indexOf('\\') >= 0;
        }
        if (bad) {
            throw new IllegalStateException("quarkpress.admin.path must be a path below the site's root that starts and"
                    + " ends with /, such as /wp-admin/: " + adminPath);
        }
        return new AdminPaths(List.copyOf(segments));
    }

    /** The admin path's segments, such as {@code [wp-admin]}. */
    public List<String> adminSegments() {
        return adminSegments;
    }

    /** What leads from a page below the admin path back to the site's root: {@code ../} once per admin segment. */
    public String relativePrefix() {
        return "../".repeat(adminSegments.size());
    }

    /**
     * What a raw request path (no query string) names, decided at the first of its leading segments, after a site path
     * of up to {@value #SITE_SEGMENTS} of them, where the WordPress layout or the admin path starts; empty when it
     * names nothing the admin screens answer.
     */
    public Optional<Target> classify(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.charAt(0) != '/') {
            return Optional.empty();
        }
        List<String> segments = segments(rawPath.substring(1));
        for (int i = 0; i < segments.size(); i++) {
            boolean last = i == segments.size() - 1;
            // the shell's root page ends in an empty segment; any other empty segment is refused
            if (!allowed(segments.get(i)) && !(last && segments.get(i).isEmpty())) {
                return Optional.empty();
            }
        }
        for (int offset = 0; offset <= SITE_SEGMENTS && offset < segments.size(); offset++) {
            List<String> rest = segments.subList(offset, segments.size());
            if (rest.get(0).equals("wp-includes")
                    || (rest.get(0).equals("wp-admin")
                            && rest.size() > 2
                            && ADMIN_FILE_FOLDERS.contains(rest.get(1)))) {
                return rest.size() < 2 ? Optional.empty() : file(Kind.WP_FILE, rest);
            }
            if (rest.size() > adminSegments.size()
                    && rest.subList(0, adminSegments.size()).equals(adminSegments)) {
                return admin(rest.subList(adminSegments.size(), rest.size()));
            }
        }
        return Optional.empty();
    }

    /** What follows the admin path. */
    private static Optional<Target> admin(List<String> rest) {
        if (rest.size() == 1 && SHELL_PAGES.contains(rest.get(0))) {
            return Optional.of(new Target(Kind.SHELL_PAGE, rest.get(0)));
        }
        if (rest.size() == 1 && EDITOR_PAGES.contains(rest.get(0))) {
            return Optional.of(new Target(Kind.EDITOR_PAGE, rest.get(0)));
        }
        if (rest.size() < 2 || !rest.get(0).equals(SHELL_FOLDER)) {
            return Optional.empty();
        }
        List<String> file = rest.subList(1, rest.size());
        if (file.size() == 1 && file.get(0).equals("config.json")) {
            return Optional.of(new Target(Kind.CONFIG, "config.json"));
        }
        if (file.get(0).equals("vendor")) {
            return file.size() == 2 ? file(Kind.VENDOR_FILE, file) : Optional.empty();
        }
        return file(Kind.SHELL_FILE, file);
    }

    /** A file path: its segments joined, refused when the last one is empty (a folder). */
    private static Optional<Target> file(Kind kind, List<String> segments) {
        if (segments.get(segments.size() - 1).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Target(kind, String.join("/", segments)));
    }

    /** A segment a file path may have: not empty, no leading dot, no encoded dot, no backslash. */
    private static boolean allowed(String segment) {
        return !segment.isEmpty()
                && segment.charAt(0) != '.'
                && segment.indexOf('\\') < 0
                && !containsEncodedDot(segment);
    }

    private static boolean containsEncodedDot(String segment) {
        for (int i = 0; i + 2 < segment.length(); i++) {
            if (segment.charAt(i) == '%'
                    && segment.charAt(i + 1) == '2'
                    && (segment.charAt(i + 2) == 'e' || segment.charAt(i + 2) == 'E')) {
                return true;
            }
        }
        return false;
    }

    /** The {@code /}-separated segments of a path, empty ones included. */
    private static List<String> segments(String path) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        while (true) {
            int end = path.indexOf('/', start);
            if (end < 0) {
                segments.add(path.substring(start));
                return segments;
            }
            segments.add(path.substring(start, end));
            start = end + 1;
        }
    }
}
