package io.github.filipopov.openapimcp.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal in-process HTTP stub on top of the JDK's {@link HttpServer}: canned responses per path, and a log of
 * every request received.
 */
public final class StubServer implements AutoCloseable {

    /** A request as seen by the stub. Header names are lower-cased. */
    public record Recorded(String method, String rawPath, String rawQuery, Map<String, List<String>> headers,
                           String body) {
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
            return values == null ? null : String.join(",", values);
        }
    }

    /** A canned response. */
    public record Stub(int status, String contentType, byte[] body, long delayMillis) {
        public static Stub json(int status, String json) {
            return new Stub(status, "application/json", json.getBytes(StandardCharsets.UTF_8), 0);
        }
    }

    private final HttpServer server;
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, List<String>> headers = new java.util.HashMap<>();
            Headers h = exchange.getRequestHeaders();
            h.forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), List.copyOf(v)));
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), headers, body));

            Stub stub = stubs.getOrDefault(exchange.getRequestURI().getPath(), Stub.json(404, "{\"error\":\"no stub\"}"));
            if (stub.delayMillis() > 0) {
                try {
                    Thread.sleep(stub.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (stub.contentType() != null) {
                exchange.getResponseHeaders().add("Content-Type", stub.contentType());
            }
            boolean noBody = stub.body().length == 0 || "HEAD".equals(exchange.getRequestMethod());
            exchange.sendResponseHeaders(stub.status(), noBody ? -1 : stub.body().length);
            if (!noBody) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(stub.body());
                }
            }
            exchange.close();
        });
        server.start();
    }

    public StubServer stub(String path, Stub stub) {
        stubs.put(path, stub);
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Recorded> requests() {
        return requests;
    }

    public Recorded lastRequest() {
        if (requests.isEmpty()) {
            throw new AssertionError("no request received");
        }
        return requests.getLast();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
