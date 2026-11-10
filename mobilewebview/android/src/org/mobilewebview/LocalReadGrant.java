package org.mobilewebview;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Read Grant: the local content one Web View may read. A browsing view holds
 * {@link #NONE} and reaches no file:// or content:// URL at all. A local
 * preview holds the file it shows (or the directory granted with it) over
 * file://, or the one content:// URI it was handed. Pure Java, so the JVM
 * tests state the whole rule.
 */
final class LocalReadGrant {
    static final LocalReadGrant NONE = new LocalReadGrant(null, null);

    private static final String FILE = "file";
    private static final String CONTENT = "content";

    private final String mScheme;
    // file: the granted canonical path (the file itself or a directory);
    // content: the decoded URI without its fragment.
    private final String mRoot;

    private LocalReadGrant(String scheme, String root) {
        mScheme = scheme;
        mRoot = root;
    }

    static boolean isLocal(String url) {
        String scheme = schemeOf(url);
        return FILE.equals(scheme) || CONTENT.equals(scheme);
    }

    /**
     * The grant for a preview of {@code url}. For file:// the
     * {@code readAccessUrl} names the file itself or a directory holding it;
     * empty means the file's own directory. For content:// the grant is the
     * URI itself, which must name a provider, and the access URL is empty or
     * that URI. {@link #NONE} when the URL is not local or the access URL does
     * not cover it. The host checks the same cases (tests/read_grant_cases.txt).
     */
    static LocalReadGrant forPreview(String url, String readAccessUrl) {
        String scheme = schemeOf(url);
        if (CONTENT.equals(scheme)) {
            boolean ownAccess = readAccessUrl == null || readAccessUrl.isEmpty()
                    || readAccessUrl.equals(url);
            return ownAccess && hasAuthority(url) ? new LocalReadGrant(CONTENT, contentIdentity(url))
                                                  : NONE;
        }
        if (!FILE.equals(scheme)) {
            return NONE;
        }
        String path = canonicalPath(url);
        if (path == null) {
            return NONE;
        }
        String root;
        if (readAccessUrl == null || readAccessUrl.isEmpty()) {
            root = new File(path).getParent();
        } else if (FILE.equals(schemeOf(readAccessUrl))) {
            root = canonicalPath(readAccessUrl);
        } else {
            return NONE;
        }
        if (root == null || !within(path, root)) {
            return NONE;
        }
        return new LocalReadGrant(FILE, root);
    }

    boolean allowsFileScheme() {
        return FILE.equals(mScheme);
    }

    boolean allowsContentScheme() {
        return CONTENT.equals(mScheme);
    }

    /** True for every non-local URL; for a local one, whether the grant covers it. */
    boolean permits(String url) {
        String scheme = schemeOf(url);
        if (!FILE.equals(scheme) && !CONTENT.equals(scheme)) {
            return true;
        }
        if (!scheme.equals(mScheme)) {
            return false;
        }
        if (CONTENT.equals(scheme)) {
            return contentIdentity(url).equals(mRoot);
        }
        String path = canonicalPath(url);
        return path != null && within(path, mRoot);
    }

    /** The decoded local path of a file:// URL, without query or fragment. */
    static String filePath(String fileUrl) {
        if (!FILE.equals(schemeOf(fileUrl))) {
            return null;
        }
        String rest = fileUrl.substring(FILE.length() + 1);
        int end = indexOfAny(rest, '?', '#');
        if (end >= 0) {
            rest = rest.substring(0, end);
        }
        if (rest.startsWith("//")) {
            int slash = rest.indexOf('/', 2);
            String authority = slash < 0 ? rest.substring(2) : rest.substring(2, slash);
            if (!authority.isEmpty() && !"localhost".equalsIgnoreCase(authority)) {
                return null;
            }
            rest = slash < 0 ? "/" : rest.substring(slash);
        }
        String path = percentDecode(rest);
        return path.startsWith("/") ? path : null;
    }

    // Null when the file system cannot name the path (a NUL byte, a symlink
    // loop), which refuses the URL.
    private static String canonicalPath(String fileUrl) {
        String path = filePath(fileUrl);
        if (path == null) {
            return null;
        }
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean within(String path, String root) {
        if (path.equals(root)) {
            return true;
        }
        return path.startsWith(root.endsWith("/") ? root : root + "/");
    }

    private static boolean hasAuthority(String url) {
        int start = url.indexOf("://");
        return start > 0 && start + 3 < url.length() && url.charAt(start + 3) != '/';
    }

    private static String contentIdentity(String url) {
        int hash = url.indexOf('#');
        String withoutFragment = hash >= 0 ? url.substring(0, hash) : url;
        return percentDecode(withoutFragment);
    }

    private static String schemeOf(String url) {
        if (url == null) {
            return null;
        }
        int colon = url.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        for (int i = 0; i < colon; i++) {
            char c = url.charAt(i);
            boolean ok = Character.isLetter(c)
                    || (i > 0 && (Character.isDigit(c) || c == '+' || c == '-' || c == '.'));
            if (!ok) {
                return null;
            }
        }
        return url.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    // Lenient: decodes %XX as UTF-8 bytes and keeps every other character, so
    // a pretty-decoded URL and its fully encoded form decode alike.
    private static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()
                    && hexValue(s.charAt(i + 1)) >= 0 && hexValue(s.charAt(i + 2)) >= 0) {
                out.write(hexValue(s.charAt(i + 1)) * 16 + hexValue(s.charAt(i + 2)));
                i += 3;
                continue;
            }
            int end = i + 1;
            if (Character.isHighSurrogate(c) && end < s.length()) {
                end++;
            }
            byte[] bytes = s.substring(i, end).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
            i = end;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        return ib < 0 ? ia : Math.min(ia, ib);
    }
}
