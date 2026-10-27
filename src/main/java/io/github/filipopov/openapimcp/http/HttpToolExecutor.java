package io.github.filipopov.openapimcp.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.filipopov.openapimcp.openapi.ToolDefinition;
import io.github.filipopov.openapimcp.openapi.ToolDefinition.BodyBinding;
import io.github.filipopov.openapimcp.openapi.ToolDefinition.ParameterBinding;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a {@link ToolDefinition} as an HTTP request and turns the response into a {@link CallToolResult}.
 *
 * <p>HTTP errors, timeouts and I/O failures are reported as tool results with {@code isError: true} so that the
 * model can see what happened; they are never thrown to the MCP layer.
 *
 * <p>Logging: one line per request with method, path (no query string), status and duration. Header values,
 * query strings and bodies are never logged.
 */
public final class HttpToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpToolExecutor.class);
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([^\";\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_ACCEPT = "application/json, */*;q=0.5";

    private final HttpClient client;
    private final String baseUrl;
    private final Map<String, String> staticHeaders;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final Redactor redactor;
    private final ObjectMapper json = new ObjectMapper();

    public HttpToolExecutor(String baseUrl, Map<String, String> staticHeaders, Duration timeout, int maxResponseBytes) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.staticHeaders = Map.copyOf(staticHeaders);
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.redactor = new Redactor(staticHeaders.values());
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        validateStaticHeaders();
    }

    public CallToolResult execute(ToolDefinition tool, Map<String, Object> arguments) {
        Map<String, Object> args = arguments != null ? arguments : Map.of();
        HttpRequest request;
        try {
            request = buildRequest(tool, args);
        } catch (IllegalArgumentException e) {
            return error("Invalid arguments for " + tool.name() + ": " + redactor.redact(e.getMessage()));
        }

        long start = System.nanoTime();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("{} {} -> {} ({} ms)", tool.method(), redactor.redact(request.uri().getRawPath()),
                    response.statusCode(), ms);
            return toResult(response);
        } catch (HttpTimeoutException e) {
            log.warn("{} {} timed out after {}", tool.method(), redactor.redact(request.uri().getRawPath()), timeout);
            return error("Request timed out after " + timeout.toSeconds() + "s: " + tool.method() + " " + tool.path());
        } catch (IOException e) {
            String msg = redactor.redact(String.valueOf(e.getMessage()));
            log.warn("{} {} failed: {}: {}", tool.method(), redactor.redact(request.uri().getRawPath()),
                    e.getClass().getSimpleName(), msg);
            return error("Request failed: " + e.getClass().getSimpleName() + ": " + msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("Request interrupted");
        }
    }

    // ---- request -------------------------------------------------------------------------------------------

    HttpRequest buildRequest(ToolDefinition tool, Map<String, Object> args) {
        String path = tool.path();
        StringJoiner query = new StringJoiner("&");
        List<String> cookies = new ArrayList<>();
        HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(timeout);

        staticHeaders.forEach(builder::header);
        boolean acceptSet = staticHeaders.keySet().stream().anyMatch(h -> h.equalsIgnoreCase("Accept"));
        if (!acceptSet) {
            builder.header("Accept", DEFAULT_ACCEPT);
        }

        for (ParameterBinding p : tool.parameters()) {
            Object value = args.get(p.argName());
            if (value == null) {
                if (p.required()) {
                    throw new IllegalArgumentException("missing required " + p.location().name().toLowerCase(Locale.ROOT)
                            + " parameter '" + p.argName() + "'");
                }
                continue;
            }
            switch (p.location()) {
                case PATH -> path = path.replace("{" + p.wireName() + "}", encodePathSegment(simpleStyle(value, p.explode())));
                case QUERY -> appendQuery(query, p, value);
                case HEADER -> builder.header(p.wireName(), simpleStyle(value, p.explode()));
                case COOKIE -> cookies.add(p.wireName() + "=" + simpleStyle(value, false));
            }
        }
        if (!cookies.isEmpty()) {
            builder.header("Cookie", String.join("; ", cookies));
        }

        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        BodyBinding body = tool.body();
        if (body != null) {
            Object value = args.get(body.argName());
            if (value == null && body.required()) {
                throw new IllegalArgumentException("missing required request body '" + body.argName() + "'");
            }
            if (value != null) {
                publisher = HttpRequest.BodyPublishers.ofString(encodeBody(body, value), StandardCharsets.UTF_8);
                builder.header("Content-Type", body.mediaType());
            }
        }

        String uri = baseUrl + path + (query.length() > 0 ? "?" + query : "");
        return builder.uri(URI.create(uri)).method(tool.method(), publisher).build();
    }

    private void appendQuery(StringJoiner query, ParameterBinding p, Object value) {
        String name = encodeQuery(p.wireName());
        if (value instanceof Collection<?> list) {
            if (p.explode()) {
                list.forEach(v -> query.add(name + "=" + encodeQuery(scalar(v))));
            } else {
                String delimiter = switch (p.style()) {
                    case "spaceDelimited" -> "%20";
                    case "pipeDelimited" -> "|";
                    default -> ",";
                };
                query.add(name + "=" + String.join(delimiter, list.stream().map(v -> encodeQuery(scalar(v))).toList()));
            }
        } else if (value instanceof Map<?, ?> map) {
            if ("deepObject".equals(p.style())) {
                map.forEach((k, v) -> query.add(name + "%5B" + encodeQuery(String.valueOf(k)) + "%5D=" + encodeQuery(scalar(v))));
            } else if (p.explode()) {
                map.forEach((k, v) -> query.add(encodeQuery(String.valueOf(k)) + "=" + encodeQuery(scalar(v))));
            } else {
                List<String> parts = new ArrayList<>();
                map.forEach((k, v) -> {
                    parts.add(encodeQuery(String.valueOf(k)));
                    parts.add(encodeQuery(scalar(v)));
                });
                query.add(name + "=" + String.join(",", parts));
            }
        } else {
            query.add(name + "=" + encodeQuery(scalar(value)));
        }
    }

    /** OpenAPI "simple" style, used for path and header parameters. */
    private static String simpleStyle(Object value, boolean explode) {
        if (value instanceof Collection<?> list) {
            return String.join(",", list.stream().map(HttpToolExecutor::scalar).toList());
        }
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            map.forEach((k, v) -> parts.add(explode ? k + "=" + scalar(v) : k + "," + scalar(v)));
            return String.join(",", parts);
        }
        return scalar(value);
    }

    private String encodeBody(BodyBinding body, Object value) {
        return switch (body.encoding()) {
            case JSON -> {
                try {
                    yield json.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("request body is not serializable as JSON", e);
                }
            }
            case FORM -> {
                if (!(value instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("form request body must be an object");
                }
                StringJoiner form = new StringJoiner("&");
                map.forEach((k, v) -> {
                    if (v instanceof Collection<?> list) {
                        list.forEach(item -> form.add(encodeQuery(String.valueOf(k)) + "=" + encodeQuery(scalar(item))));
                    } else if (v != null) {
                        form.add(encodeQuery(String.valueOf(k)) + "=" + encodeQuery(scalar(v)));
                    }
                });
                yield form.toString();
            }
            case TEXT -> value instanceof String s ? s : scalar(value);
        };
    }

    /** Renders a JSON scalar the way it would appear in a URL: integral doubles lose their ".0". */
    private static String scalar(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        if (v instanceof Float f && f == Math.rint(f) && !f.isInfinite()) {
            return Long.toString(f.longValue());
        }
        return String.valueOf(v);
    }

    private static String encodePathSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void validateStaticHeaders() {
        HttpRequest.Builder probe = HttpRequest.newBuilder(URI.create("http://localhost/"));
        staticHeaders.forEach((name, value) -> {
            try {
                probe.header(name, value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Header '" + name + "' cannot be set (restricted or invalid)");
            }
        });
    }

    // ---- response ------------------------------------------------------------------------------------------

    private CallToolResult toResult(HttpResponse<InputStream> response) throws IOException {
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        byte[] bytes;
        boolean truncated;
        try (InputStream in = response.body()) {
            bytes = in.readNBytes(maxResponseBytes + 1);
            truncated = bytes.length > maxResponseBytes;
        }

        StringBuilder text = new StringBuilder();
        text.append("HTTP ").append(response.statusCode());
        if (contentType != null) {
            text.append("\nContent-Type: ").append(contentType);
        }
        response.headers().firstValue("Location").ifPresent(l -> text.append("\nLocation: ").append(l));
        text.append("\n\n");

        int shown = truncated ? maxResponseBytes : bytes.length;
        if (shown == 0) {
            text.append("(empty body)");
        } else if (isTextual(contentType)) {
            text.append(new String(bytes, 0, shown, charsetOf(contentType)));
        } else {
            text.append("(binary response body not shown");
            text.append(truncated ? ", more than " + maxResponseBytes + " bytes)" : ", " + shown + " bytes)");
            truncated = false;
        }
        if (truncated) {
            String total = response.headers().firstValue("Content-Length").map(l -> " of " + l).orElse("");
            text.append("\n\n[Response truncated: showing the first ").append(maxResponseBytes).append(" bytes")
                    .append(total).append(". Raise --max-response-bytes to see more.]");
        }

        boolean isError = response.statusCode() >= 400;
        return CallToolResult.builder().addTextContent(text.toString()).isError(isError).build();
    }

    static boolean isTextual(String contentType) {
        if (contentType == null) {
            return true; // best effort: most APIs that omit it return text/JSON
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("yaml")
                || ct.contains("javascript") || ct.contains("x-www-form-urlencoded") || ct.contains("graphql");
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (IllegalArgumentException ignored) {
                    // unknown charset label: fall back to UTF-8 rather than failing the whole call
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
