package com.acme.shortener;

import java.time.Clock;

/** Entry point: {@code java -cp out com.acme.shortener.ShortenerApp [port] [baseUrl]}. */
public final class ShortenerApp {
    private ShortenerApp() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String base = args.length > 1 ? args[1] : "http://localhost:" + port;
        Clock clock = Clock.systemUTC();
        LinkService service = new LinkService(clock, base, 1_000_000);
        ShortenerServer server = new ShortenerServer(service, new RateLimiter(30, 30, clock),
                new RateLimiter(600, 600, clock));
        server.start(port);
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Thread.sleep(60_000);
                    service.purgeExpired();
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.println("URL shortener listening on port " + server.port());
    }
}
