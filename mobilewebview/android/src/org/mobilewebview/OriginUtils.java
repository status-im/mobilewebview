package org.mobilewebview;

import java.net.URI;
import java.util.List;

final class OriginUtils {
    private OriginUtils() {
    }

    static String extractOrigin(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }

        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();

            if (scheme == null || host == null) {
                return "";
            }

            StringBuilder origin = new StringBuilder();
            origin.append(scheme).append("://").append(host);

            // Only include port if non-standard.
            if (port != -1 && port != 80 && port != 443) {
                origin.append(":").append(port);
            }

            return origin.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static boolean isOriginAllowed(String origin, List<String> allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            return false;
        }

        if (origin == null || origin.isEmpty()) {
            return false;
        }

        if (allowedOrigins.contains(origin) || allowedOrigins.contains("*")) {
            return true;
        }

        for (String pattern : allowedOrigins) {
            if (pattern != null && pattern.contains("*")) {
                String regex = pattern.replace(".", "\\.").replace("*", ".*");
                if (origin.matches(regex)) {
                    return true;
                }
            }
        }

        return false;
    }
}
