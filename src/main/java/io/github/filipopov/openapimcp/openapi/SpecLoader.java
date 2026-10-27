package io.github.filipopov.openapimcp.openapi;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads an OpenAPI document from a file or URL and determines the API base URL.
 *
 * <p>Swagger 2.0 documents are accepted too: {@link OpenAPIParser} converts them to OpenAPI 3.
 */
public final class SpecLoader {

    private static final Logger log = LoggerFactory.getLogger(SpecLoader.class);
    private static final int MAX_LOGGED_MESSAGES = 10;

    private SpecLoader() {
    }

    public static OpenAPI load(String location) {
        String resolvedLocation = location;
        if (!isHttpUrl(location)) {
            Path path = Path.of(location).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("OpenAPI spec not found: " + path);
            }
            resolvedLocation = path.toString();
        }
        ParseOptions options = new ParseOptions();
        options.setResolve(true); // load external $refs into components; internal refs are inlined by us
        SwaggerParseResult result = new OpenAPIParser().readLocation(resolvedLocation, null, options);

        List<String> messages = result.getMessages() != null ? result.getMessages() : List.of();
        messages.stream().limit(MAX_LOGGED_MESSAGES).forEach(m -> log.warn("Spec: {}", m));
        if (messages.size() > MAX_LOGGED_MESSAGES) {
            log.warn("Spec: ... and {} more parser messages", messages.size() - MAX_LOGGED_MESSAGES);
        }
        if (result.getOpenAPI() == null) {
            throw new IllegalArgumentException("Could not parse OpenAPI spec at " + location
                    + (messages.isEmpty() ? "" : ": " + String.join("; ", messages)));
        }
        return result.getOpenAPI();
    }

    /**
     * Picks the base URL: the explicit one if given, otherwise the first {@code servers} entry of the spec (server
     * variables replaced by their defaults; relative URLs resolved against the spec URL).
     *
     * @throws IllegalArgumentException if no absolute base URL can be determined
     */
    public static String resolveBaseUrl(String explicitBaseUrl, OpenAPI openApi, String specLocation) {
        String candidate = explicitBaseUrl;
        if (candidate == null || candidate.isBlank()) {
            List<Server> servers = openApi.getServers();
            if (servers != null && !servers.isEmpty() && servers.getFirst().getUrl() != null) {
                candidate = substituteVariables(servers.getFirst());
                if (!isHttpUrl(candidate) && isHttpUrl(specLocation)) {
                    candidate = URI.create(specLocation).resolve(candidate).toString();
                }
            }
        }
        if (candidate == null || !isHttpUrl(candidate)) {
            throw new IllegalArgumentException("No absolute base URL: pass --base-url (spec servers: "
                    + (candidate == null ? "none" : "'" + candidate + "'") + ")");
        }
        URI.create(candidate); // fail fast on malformed URLs
        return candidate.endsWith("/") ? candidate.substring(0, candidate.length() - 1) : candidate;
    }

    private static String substituteVariables(Server server) {
        String url = server.getUrl();
        if (server.getVariables() != null) {
            for (Map.Entry<String, ServerVariable> v : server.getVariables().entrySet()) {
                String value = v.getValue() != null && v.getValue().getDefault() != null ? v.getValue().getDefault() : "";
                url = url.replace("{" + v.getKey() + "}", value);
            }
        }
        return url;
    }

    static boolean isHttpUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }
}
