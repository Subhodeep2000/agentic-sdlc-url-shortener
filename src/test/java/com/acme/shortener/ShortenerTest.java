package com.acme.shortener;

import static com.acme.testkit.Assert.assertEquals;
import static com.acme.testkit.Assert.assertFalse;
import static com.acme.testkit.Assert.assertNotNull;
import static com.acme.testkit.Assert.assertThrows;
import static com.acme.testkit.Assert.assertTrue;

import com.acme.common.Json;
import com.acme.testkit.MutableClock;
import com.acme.testkit.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Unit tests for validation, rate limiting, service logic, plus end-to-end HTTP tests. */
public class ShortenerTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
    private final LinkService svc = new LinkService(clock, "http://sho.rt", 100);

    private static void rejects(String url) {
        assertEquals(400, assertThrows(ShortenerException.class, () -> UrlValidator.validateUrl(url)).status(), url);
    }

    @Test
    public void validatorAcceptsHttpAndRejectsDangerousTargets() {
        UrlValidator.validateUrl("https://example.com/a?b=c#d");
        UrlValidator.validateUrl("http://172.32.0.1/");
        for (String bad : new String[] {"javascript:alert(1)", "data:text/html;base64,AA", "file:///etc/passwd",
            "ftp://example.com/x", "http://localhost/", "http://127.0.0.1/", "http://10.1.2.3/", "http://192.168.0.1/",
            "http://172.20.1.1/", "http://169.254.169.254/latest", "http://[::1]/", "http://svc.internal/",
            "https://user:pw@example.com/", "", "   ", "not a url", "https://e.com/" + "a".repeat(2048)}) {
            rejects(bad);
        }
        rejects(null);
    }

    @Test
    public void aliasRules() {
        UrlValidator.validateAlias("my-link_1");
        for (String bad : new String[] {"ab", "has space", "api", "x".repeat(33)}) {
            assertThrows(ShortenerException.class, () -> UrlValidator.validateAlias(bad));
        }
    }

    @Test
    public void rateLimiterBurstRefillAndIsolation() {
        RateLimiter rl = new RateLimiter(2, 60, clock); // 1 token per second
        assertTrue(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"), "burst exhausted");
        assertTrue(rl.tryAcquire("b"), "keys are independent");
        clock.advance(Duration.ofSeconds(1));
        assertTrue(rl.tryAcquire("a"), "refilled");
        clock.advance(Duration.ofDays(1));
        assertTrue(rl.tryAcquire("a") && rl.tryAcquire("a") && !rl.tryAcquire("a"), "capped at capacity");
    }

    @Test
    public void createAliasConflictAndTtl() {
        LinkService.Link l = svc.create("https://example.com/p", null, null);
        assertEquals(7, l.code().length());
        assertEquals("http://sho.rt/" + l.code(), svc.shortUrl(l));
        svc.create("https://example.com/a", "my-alias", null);
        assertEquals(409, assertThrows(ShortenerException.class,
                () -> svc.create("https://example.com/b", "my-alias", null)).status());
        assertEquals(400, assertThrows(ShortenerException.class,
                () -> svc.create("https://example.com", null, 0L)).status());
        assertEquals(400, assertThrows(ShortenerException.class,
                () -> svc.create("https://example.com", null, Duration.ofDays(400).toSeconds())).status());
    }

    @Test
    public void codeCollisionRetriedThenGivesUp() {
        int[] n = {0};
        LinkService s = new LinkService(clock, "http://x", 10, () -> n[0]++ < 2 ? "AAAAAAA" : "BBBBBBB");
        assertEquals("AAAAAAA", s.create("https://example.com/1", null, null).code());
        assertEquals("BBBBBBB", s.create("https://example.com/2", null, null).code());
        LinkService c = new LinkService(clock, "http://x", 10, () -> "CCCCCCC");
        c.create("https://example.com/1", null, null);
        assertEquals(503, assertThrows(ShortenerException.class,
                () -> c.create("https://example.com/2", null, null)).status());
    }

    @Test
    public void expiryGonePurgeAndCapacity() {
        LinkService.Link shortLived = svc.create("https://example.com/1", null, 60L);
        LinkService.Link keep = svc.create("https://example.com/2", null, null);
        svc.resolve(shortLived.code(), null);
        clock.advance(Duration.ofSeconds(60));
        assertEquals(410, assertThrows(ShortenerException.class, () -> svc.resolve(shortLived.code(), null)).status());
        assertEquals(1, svc.purgeExpired());
        assertNotNull(svc.get(keep.code()), "permanent link survives");
        assertEquals(404, assertThrows(ShortenerException.class, () -> svc.get(shortLived.code())).status());
        LinkService small = new LinkService(clock, "http://x", 1);
        small.create("https://example.com/1", null, null);
        assertEquals(503, assertThrows(ShortenerException.class,
                () -> small.create("https://example.com/2", null, null)).status());
    }

    @Test
    public void analyticsAggregateOnlyAndBounded() {
        LinkService.Link l = svc.create("https://example.com", null, null);
        svc.resolve(l.code(), "https://news.example.org/story?id=1");
        svc.resolve(l.code(), "https://news.example.org/other");
        svc.resolve(l.code(), null);
        clock.advance(Duration.ofDays(1));
        svc.resolve(l.code(), "https://blog.example.net/");
        LinkService.Stats s = svc.stats(l.code());
        assertEquals(4L, s.totalClicks());
        assertEquals(3L, s.clicksPerDay().get("2026-03-01"));
        assertEquals(2L, s.referrers().get("news.example.org"));
        assertEquals(1L, s.referrers().get("(direct)"));
        assertTrue(s.referrers().keySet().stream().noneMatch(k -> k.contains("/")), "hosts only, no URLs");
        LinkService.Link l2 = svc.create("https://example.com/2", null, null);
        for (int i = 0; i < 80; i++) {
            svc.resolve(l2.code(), "https://host" + i + ".example.com/");
        }
        assertEquals(30L, svc.stats(l2.code()).referrers().get("(other)"));
    }

    @Test
    public void deleteRemovesLinkAndStats() {
        LinkService.Link l = svc.create("https://example.com", null, null);
        svc.resolve(l.code(), null);
        svc.delete(l.code());
        assertEquals(404, assertThrows(ShortenerException.class, () -> svc.get(l.code())).status());
        assertEquals(404, assertThrows(ShortenerException.class, () -> svc.delete(l.code())).status());
    }

    @Test
    public void concurrentClicksAreNotLost() throws Exception {
        LinkService.Link l = svc.create("https://example.com", null, null);
        Thread[] ts = new Thread[8];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    svc.resolve(l.code(), null);
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(4000L, svc.stats(l.code()).totalClicks());
    }

    @Test
    public void jsonRoundTripAndHostileInput() {
        String original = "line\nquote\" back\\ tab\t é";
        assertEquals(original, Json.parseObject(Json.write(Map.of("k", original))).get("k"));
        for (String bad : new String[] {"{", "{\"a\":}", "[1,2] x", "\"open", "[1]"}) {
            assertThrows(IllegalArgumentException.class, () -> Json.parseObject(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(200) + "]".repeat(200)));
    }

    // ---- HTTP -----------------------------------------------------------------------------------------------

    private static final class Fx implements AutoCloseable {
        final ShortenerServer server;
        final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        final String base;

        Fx(int createCapacity) throws Exception {
            Clock c = Clock.systemUTC();
            server = new ShortenerServer(new LinkService(c, "http://sho.rt", 1000),
                    new RateLimiter(createCapacity, 1, c), new RateLimiter(1000, 1000, c));
            server.start(0);
            base = "http://127.0.0.1:" + server.port();
        }

        HttpResponse<String> post(String path, String body, String ct) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", ct)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path, String... h) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
            for (int i = 0; i < h.length; i += 2) {
                b.header(h[i], h[i + 1]);
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> delete(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    @Test
    public void httpLifecycle() throws Exception {
        try (Fx f = new Fx(100)) {
            HttpResponse<String> created = f.post("/api/v1/links",
                    "{\"url\":\"https://example.com/landing\",\"alias\":\"promo\",\"ttlSeconds\":3600}",
                    "application/json");
            assertEquals(201, created.statusCode());
            assertEquals("http://sho.rt/promo", Json.parseObject(created.body()).get("shortUrl"));
            HttpResponse<String> r = f.get("/promo", "Referer", "https://social.example.org/post/9");
            assertEquals(302, r.statusCode());
            assertEquals("https://example.com/landing", r.headers().firstValue("Location").orElse(""));
            assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
            Map<String, Object> st = Json.parseObject(f.get("/api/v1/links/promo/stats").body());
            assertEquals(1L, st.get("totalClicks"));
            assertEquals(200, f.get("/api/v1/links/promo").statusCode());
            assertEquals(204, f.delete("/api/v1/links/promo").statusCode());
            assertEquals(404, f.get("/promo").statusCode());
        }
    }

    @Test
    public void httpErrorContract() throws Exception {
        try (Fx f = new Fx(100)) {
            HttpResponse<String> bad = f.post("/api/v1/links", "{\"url\":\"javascript:alert(1)\"}", "application/json");
            assertEquals(400, bad.statusCode());
            assertEquals("BAD_REQUEST", ((Map<?, ?>) Json.parseObject(bad.body()).get("error")).get("code"));
            assertNotNull(bad.headers().firstValue("X-Request-Id").orElse(null), "request id");
            assertEquals(400, f.post("/api/v1/links", "{not json", "application/json").statusCode());
            assertEquals(400, f.post("/api/v1/links", "{\"url\":123}", "application/json").statusCode());
            assertEquals(415, f.post("/api/v1/links", "{}", "text/plain").statusCode());
            assertEquals(413, f.post("/api/v1/links", "{\"url\":\"https://e.com/" + "a".repeat(9000) + "\"}",
                    "application/json").statusCode());
            assertEquals(405, f.get("/api/v1/links").statusCode());
            assertEquals(404, f.get("/does-not-exist/x").statusCode());
            assertEquals(404, f.get("/bad%20code").statusCode());
            String req = "{\"url\":\"https://example.com/x\",\"alias\":\"dup-alias\"}";
            assertEquals(201, f.post("/api/v1/links", req, "application/json").statusCode());
            assertEquals(409, f.post("/api/v1/links", req, "application/json").statusCode());
        }
    }

    @Test
    public void httpCreateIsRateLimited() throws Exception {
        try (Fx f = new Fx(2)) {
            String req = "{\"url\":\"https://example.com/x\"}";
            assertEquals(201, f.post("/api/v1/links", req, "application/json").statusCode());
            assertEquals(201, f.post("/api/v1/links", req, "application/json").statusCode());
            HttpResponse<String> limited = f.post("/api/v1/links", req, "application/json");
            assertEquals(429, limited.statusCode());
            assertTrue(limited.headers().firstValue("Retry-After").isPresent(), "Retry-After");
        }
    }
}
