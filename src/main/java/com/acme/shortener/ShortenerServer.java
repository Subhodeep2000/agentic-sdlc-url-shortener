package com.acme.shortener;

import com.acme.common.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * HTTP adapter on the JDK built-in server.
 *
 * <pre>
 * POST   /api/v1/links              {url, alias?, ttlSeconds?} -> 201
 * GET    /api/v1/links/{code}       -> 200 link metadata
 * GET    /api/v1/links/{code}/stats -> 200 analytics
 * DELETE /api/v1/links/{code}       -> 204
 * GET    /{code}                    -> 302 redirect
 * GET    /health                    -> 200
 * </pre>
 *
 * @concepts api http routes endpoint rate-limit security headers redirect client-ip proxy stats
 */
public final class ShortenerServer {
    private static final int MAX_BODY = 8 * 1024;
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    private final LinkService service;
    private final RateLimiter createLimiter;
    private final RateLimiter redirectLimiter;
    private HttpServer server;

    public ShortenerServer(LinkService service, RateLimiter createLimiter, RateLimiter redirectLimiter) {
        this.service = service;
        this.createLimiter = createLimiter;
        this.redirectLimiter = redirectLimiter;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void dispatch(HttpExchange ex) throws IOException {
        String rid = UUID.randomUUID().toString();
        ex.getResponseHeaders().set("X-Request-Id", rid);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        try {
            route(ex);
        } catch (ShortenerException e) {
            if (e.status() == 429) {
                ex.getResponseHeaders().set("Retry-After", String.valueOf(createLimiter.retryAfterSeconds()));
            }
            error(ex, e.status(), e.errorCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            error(ex, 400, "BAD_REQUEST", "Malformed request: " + e.getMessage());
        } catch (RuntimeException e) {
            error(ex, 500, "INTERNAL", "Unexpected error, reference " + rid);
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getRawPath();
        String client = ex.getRemoteAddress().getAddress().getHostAddress();

        if (path.equals("/health")) {
            need(method, "GET");
            json(ex, 200, Map.of("status", "UP"));
        } else if (path.equals("/api/v1/links")) {
            need(method, "POST");
            throttle(createLimiter, client);
            Map<String, Object> body = body(ex);
            LinkService.Link l = service.create(str(body.get("url"), "url"), str(body.get("alias"), "alias"),
                    lng(body.get("ttlSeconds")));
            ex.getResponseHeaders().set("Location", "/api/v1/links/" + l.code());
            json(ex, 201, view(l));
        } else if (path.startsWith("/api/v1/links/")) {
            String[] parts = path.substring("/api/v1/links/".length()).split("/");
            String code = checkCode(parts[0]);
            if (parts.length == 1 && method.equals("GET")) {
                json(ex, 200, view(service.get(code)));
            } else if (parts.length == 1 && method.equals("DELETE")) {
                service.delete(code);
                ex.sendResponseHeaders(204, -1);
            } else if (parts.length == 2 && parts[1].equals("stats")) {
                need(method, "GET");
                json(ex, 200, statsView(service.stats(code)));
            } else if (parts.length == 1) {
                throw new ShortenerException(405, "METHOD_NOT_ALLOWED", "Method not allowed");
            } else {
                throw ShortenerException.notFound(code);
            }
        } else if (path.length() > 1 && path.indexOf('/', 1) < 0) {
            need(method, "GET");
            String code = checkCode(path.substring(1));
            throttle(redirectLimiter, client);
            LinkService.Link l = service.resolve(code, ex.getRequestHeaders().getFirst("Referer"));
            ex.getResponseHeaders().set("Location", l.url());
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(302, -1);
        } else {
            throw new ShortenerException(404, "NOT_FOUND", "Unknown path");
        }
    }

    private void throttle(RateLimiter limiter, String client) {
        if (!limiter.tryAcquire(client)) {
            throw ShortenerException.tooManyRequests(limiter.retryAfterSeconds());
        }
    }

    private static String checkCode(String c) {
        if (!CODE.matcher(c).matches()) {
            throw ShortenerException.notFound(c.length() > 32 ? c.substring(0, 32) : c);
        }
        return c;
    }

    private static void need(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new ShortenerException(405, "METHOD_NOT_ALLOWED", "Method not allowed");
        }
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("application/json")) {
            throw new ShortenerException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
        }
        try (var in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) {
                throw new ShortenerException(413, "PAYLOAD_TOO_LARGE", "Body exceeds " + MAX_BODY + " bytes");
            }
            return Json.parseObject(new String(b, StandardCharsets.UTF_8));
        }
    }

    private static String str(Object v, String f) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        throw ShortenerException.badRequest(f + " must be a string");
    }

    private static Long lng(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Long l) {
            return l;
        }
        throw ShortenerException.badRequest("ttlSeconds must be an integer");
    }

    private Map<String, Object> view(LinkService.Link l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", l.code());
        m.put("shortUrl", service.shortUrl(l));
        m.put("url", l.url());
        m.put("createdAt", l.createdAt().toString());
        m.put("expiresAt", l.expiresAt() == null ? null : l.expiresAt().toString());
        return m;
    }

    private static Map<String, Object> statsView(LinkService.Stats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.code());
        m.put("totalClicks", s.totalClicks());
        m.put("lastClickAt", s.lastClickAt() == null ? null : s.lastClickAt().toString());
        m.put("clicksPerDay", s.clicksPerDay());
        m.put("referrers", s.referrers());
        return m;
    }

    private static void error(HttpExchange ex, int status, String code, String msg) throws IOException {
        json(ex, status, Map.of("error", Map.of("code", code, "message", msg)));
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
