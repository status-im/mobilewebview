package org.mobilewebview;

import java.util.Locale;

/**
 * Restores a content:// URI's authority to its provider's spelling. A host that
 * passes the URI through QUrl gets the host lowercased, while Android provider
 * authorities are case-sensitive. Pure Java; the caller supplies the visible
 * providers' authorities.
 */
final class ContentAuthorities {
    private static final String PREFIX = "content://";

    private ContentAuthorities() { }

    /**
     * {@code uri} with its lowercase authority spelled as the one provider in
     * {@code providerAuthorities} that matches it ignoring case (each entry may
     * list several, separated by ';'). Unchanged when the authority has capitals
     * of its own or matches no provider; null when providers spell it in more
     * than one way, since the URI then names none of them for sure.
     */
    static String restoreCase(String uri, Iterable<String> providerAuthorities) {
        if (uri == null || !uri.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return uri;
        }
        int start = PREFIX.length();
        int end = uri.length();
        for (int i = start; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = uri.substring(start, end);
        if (!authority.equals(authority.toLowerCase(Locale.ROOT))) {
            return uri;
        }
        String spelling = null;
        for (String entry : providerAuthorities) {
            if (entry == null) {
                continue;
            }
            for (String candidate : entry.split(";")) {
                if (!candidate.equalsIgnoreCase(authority)) {
                    continue;
                }
                if (spelling != null && !spelling.equals(candidate)) {
                    return null;
                }
                spelling = candidate;
            }
        }
        return spelling == null ? uri : uri.substring(0, start) + spelling + uri.substring(end);
    }
}
