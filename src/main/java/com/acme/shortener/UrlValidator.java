package com.acme.shortener;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates target URLs and aliases: http(s) only, no credentials, no loopback/private hosts (basic SSRF guard).
 * Hostnames are not resolved, so DNS-rebinding style abuse is out of scope.
 *
 * @concepts validation security ssrf alias url create
 */
public final class UrlValidator {
    public static final int MAX_URL_LENGTH = 2048;
    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Set<String> RESERVED = Set.of("api", "health", "admin", "static", "favicon");

    private UrlValidator() {
    }

    public static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw ShortenerException.badRequest("url is required");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw ShortenerException.badRequest("url exceeds " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw ShortenerException.badRequest("url is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw ShortenerException.badRequest("only http and https URLs are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw ShortenerException.badRequest("credentials in URL are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ShortenerException.badRequest("url must contain a host");
        }
        if (isInternalHost(host.toLowerCase(Locale.ROOT))) {
            throw ShortenerException.badRequest("url points to a private or internal host");
        }
    }

    public static void validateAlias(String alias) {
        if (!ALIAS.matcher(alias).matches()) {
            throw ShortenerException.badRequest("alias must be 3-32 chars of letters, digits, '_' or '-'");
        }
        if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
            throw ShortenerException.badRequest("alias is reserved");
        }
    }

    static boolean isInternalHost(String h) {
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            return true;
        }
        if (h.startsWith("[")) {
            String v6 = h.substring(1, h.length() - 1);
            return v6.equals("::1") || v6.equals("::") || v6.startsWith("fc") || v6.startsWith("fd")
                    || v6.startsWith("fe80");
        }
        var m = IPV4.matcher(h);
        if (m.matches()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168);
        }
        return false;
    }
}
