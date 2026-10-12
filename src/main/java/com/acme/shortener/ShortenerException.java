package com.acme.shortener;

/** Domain error carrying the HTTP status and a stable machine-readable code. */
public class ShortenerException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String errorCode;

    public ShortenerException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public int status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public static ShortenerException badRequest(String m) {
        return new ShortenerException(400, "BAD_REQUEST", m);
    }

    public static ShortenerException notFound(String code) {
        return new ShortenerException(404, "NOT_FOUND", "No link exists for code '" + code + "'");
    }

    public static ShortenerException conflict(String m) {
        return new ShortenerException(409, "CONFLICT", m);
    }

    public static ShortenerException gone(String m) {
        return new ShortenerException(410, "GONE", m);
    }

    public static ShortenerException tooManyRequests(long retryAfter) {
        return new ShortenerException(429, "RATE_LIMITED", "Too many requests; retry after " + retryAfter + "s");
    }

    public static ShortenerException unavailable(String m) {
        return new ShortenerException(503, "UNAVAILABLE", m);
    }
}
