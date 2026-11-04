package org.mobilewebview;

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
     * {@code uri} with its authority spelled as one of {@code providerAuthorities}
     * spells it (each entry may list several, separated by ';'); unchanged when
     * the authority already matches exactly or matches none.
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
        String caseless = null;
        for (String entry : providerAuthorities) {
            if (entry == null) {
                continue;
            }
            for (String candidate : entry.split(";")) {
                if (candidate.equals(authority)) {
                    return uri;
                }
                if (caseless == null && candidate.equalsIgnoreCase(authority)) {
                    caseless = candidate;
                }
            }
        }
        return caseless == null ? uri : uri.substring(0, start) + caseless + uri.substring(end);
    }
}
