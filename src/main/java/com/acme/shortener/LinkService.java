package com.acme.shortener;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Core use-cases: create, resolve (redirect with click analytics), inspect, delete, purge expired.
 * Analytics keep only counts per day and per referrer host: no IP, user agent or full referrer URL.
 *
 * @concepts service create redirect resolve alias expiry ttl analytics stats clicks referrer privacy storage
 */
public final class LinkService {
    public record Link(String code, String url, Instant createdAt, Instant expiresAt) {
        boolean expired(Instant now) {
            return expiresAt != null && !now.isBefore(expiresAt);
        }
    }

    public record Stats(String code, long totalClicks, Instant lastClickAt, Map<String, Long> clicksPerDay,
                        Map<String, Long> referrers) {
    }

    private static final class Counters {
        long total;
        Instant last;
        final TreeMap<String, Long> perDay = new TreeMap<>();
        final Map<String, Long> referrers = new LinkedHashMap<>();
    }

    private static final int MAX_REFERRERS = 50;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final Map<String, Counters> counters = new ConcurrentHashMap<>();
    private final Clock clock;
    private final String baseUrl;
    private final int maxLinks;
    private final long maxTtlSeconds;
    private final Supplier<String> codes;

    public LinkService(Clock clock, String baseUrl, int maxLinks) {
        this(clock, baseUrl, maxLinks, LinkService::randomCode);
    }

    public LinkService(Clock clock, String baseUrl, int maxLinks, Supplier<String> codes) {
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.maxLinks = maxLinks;
        this.maxTtlSeconds = Duration.ofDays(365).toSeconds();
        this.codes = codes;
    }

    static String randomCode() {
        char[] c = new char[7];
        for (int i = 0; i < c.length; i++) {
            c[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
        }
        return new String(c);
    }

    public Link create(String url, String alias, Long ttlSeconds) {
        UrlValidator.validateUrl(url);
        Instant now = clock.instant();
        Instant exp = null;
        if (ttlSeconds != null) {
            if (ttlSeconds < 1 || ttlSeconds > maxTtlSeconds) {
                throw ShortenerException.badRequest("ttlSeconds must be between 1 and " + maxTtlSeconds);
            }
            exp = now.plusSeconds(ttlSeconds);
        }
        if (alias != null) {
            UrlValidator.validateAlias(alias);
            Link l = new Link(alias, url, now, exp);
            if (!insert(l)) {
                throw ShortenerException.conflict("alias '" + alias + "' is already taken");
            }
            return l;
        }
        for (int i = 0; i < 5; i++) {
            Link l = new Link(codes.get(), url, now, exp);
            if (insert(l)) {
                return l;
            }
        }
        throw ShortenerException.unavailable("could not allocate a unique code, retry");
    }

    private synchronized boolean insert(Link l) {
        if (links.containsKey(l.code())) {
            return false;
        }
        if (links.size() >= maxLinks) {
            throw ShortenerException.unavailable("link capacity reached");
        }
        links.put(l.code(), l);
        return true;
    }

    public Link resolve(String code, String referrer) {
        Link l = load(code);
        Counters c = counters.computeIfAbsent(code, k -> new Counters());
        Instant now = clock.instant();
        String host = referrerHost(referrer);
        synchronized (c) {
            c.total++;
            c.last = now;
            c.perDay.merge(now.atZone(ZoneOffset.UTC).toLocalDate().toString(), 1L, Long::sum);
            String key = !c.referrers.containsKey(host) && c.referrers.size() >= MAX_REFERRERS ? "(other)" : host;
            c.referrers.merge(key, 1L, Long::sum);
        }
        return l;
    }

    public Link get(String code) {
        return load(code);
    }

    public Stats stats(String code) {
        load(code);
        Counters c = counters.get(code);
        if (c == null) {
            return new Stats(code, 0, null, Map.of(), Map.of());
        }
        synchronized (c) {
            return new Stats(code, c.total, c.last, new LinkedHashMap<>(c.perDay), new LinkedHashMap<>(c.referrers));
        }
    }

    public void delete(String code) {
        if (links.remove(code) == null) {
            throw ShortenerException.notFound(code);
        }
        counters.remove(code);
    }

    public synchronized int purgeExpired() {
        Instant now = clock.instant();
        int n = 0;
        for (Link l : links.values()) {
            if (l.expired(now) && links.remove(l.code(), l)) {
                counters.remove(l.code());
                n++;
            }
        }
        return n;
    }

    public int size() {
        return links.size();
    }

    public String shortUrl(Link l) {
        return baseUrl + "/" + l.code();
    }

    private Link load(String code) {
        Link l = links.get(code);
        if (l == null) {
            throw ShortenerException.notFound(code);
        }
        if (l.expired(clock.instant())) {
            throw ShortenerException.gone("link '" + code + "' has expired");
        }
        return l;
    }

    static String referrerHost(String ref) {
        if (ref == null || ref.isBlank()) {
            return "(direct)";
        }
        try {
            String h = java.net.URI.create(ref.trim()).getHost();
            return h == null ? "(direct)" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "(direct)";
        }
    }
}
