package io.github.filipopov.openapimcp.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable runtime configuration of the bridge.
 *
 * @param specLocation     file path or http(s) URL of the OpenAPI document
 * @param baseUrl          base URL of the target API; {@code null} means "use the first server of the spec"
 * @param allowWrites      expose POST/PUT/PATCH/DELETE/... operations in addition to GET/HEAD
 * @param includes         include patterns (empty = include everything that passes the other rules)
 * @param excludes         exclude patterns
 * @param staticHeaders    headers sent with every request (typically auth); values are secrets
 * @param timeout          per-request timeout (also used as connect timeout)
 * @param maxResponseBytes response bodies larger than this are truncated
 */
public record BridgeConfig(
        String specLocation,
        String baseUrl,
        boolean allowWrites,
        List<String> includes,
        List<String> excludes,
        Map<String, String> staticHeaders,
        Duration timeout,
        int maxResponseBytes) {

    /** Prefix of environment variables that define static headers, e.g. {@code OPENAPI_MCP_HEADER_AUTHORIZATION}. */
    public static final String HEADER_ENV_PREFIX = "OPENAPI_MCP_HEADER_";

    public BridgeConfig {
        Objects.requireNonNull(specLocation, "specLocation");
        includes = List.copyOf(includes);
        excludes = List.copyOf(excludes);
        staticHeaders = Map.copyOf(staticHeaders);
        Objects.requireNonNull(timeout, "timeout");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
    }

    /**
     * Parses a {@code "Name: value"} header argument (curl style). {@code "Name=value"} is accepted as well.
     */
    public static Map.Entry<String, String> parseHeader(String raw) {
        int idx = raw.indexOf(':');
        int eq = raw.indexOf('=');
        if (idx < 0 || (eq >= 0 && eq < idx)) {
            idx = eq;
        }
        if (idx <= 0) {
            throw new IllegalArgumentException("Header must look like 'Name: value' (got a value without a name)");
        }
        String name = raw.substring(0, idx).trim();
        String value = raw.substring(idx + 1).trim();
        if (name.isEmpty() || !name.chars().allMatch(BridgeConfig::isTokenChar)) {
            throw new IllegalArgumentException("Invalid header name: '" + name + "'");
        }
        return Map.entry(name, value);
    }

    /**
     * Collects headers from environment variables named {@code OPENAPI_MCP_HEADER_<NAME>}. Underscores in
     * {@code <NAME>} become dashes and the name is title-cased: {@code OPENAPI_MCP_HEADER_X_API_KEY} becomes
     * {@code X-Api-Key}. (HTTP header names are case-insensitive, so the casing is cosmetic.)
     */
    public static Map<String, String> headersFromEnvironment(Map<String, String> env) {
        Map<String, String> headers = new LinkedHashMap<>();
        env.entrySet().stream()
                .filter(e -> e.getKey().startsWith(HEADER_ENV_PREFIX) && e.getKey().length() > HEADER_ENV_PREFIX.length())
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> headers.put(envSuffixToHeaderName(e.getKey().substring(HEADER_ENV_PREFIX.length())),
                        e.getValue()));
        return headers;
    }

    static String envSuffixToHeaderName(String suffix) {
        StringBuilder sb = new StringBuilder();
        for (String part : suffix.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('-');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static boolean isTokenChar(int c) {
        return c > 32 && c < 127 && "()<>@,;:\\\"/[]?={}".indexOf(c) < 0;
    }

    /** Header values are secrets: never print them. */
    @Override
    public String toString() {
        return "BridgeConfig[specLocation=" + specLocation
                + ", baseUrl=" + baseUrl
                + ", allowWrites=" + allowWrites
                + ", includes=" + includes
                + ", excludes=" + excludes
                + ", staticHeaders=" + staticHeaders.keySet() + " (values redacted)"
                + ", timeout=" + timeout
                + ", maxResponseBytes=" + maxResponseBytes + "]";
    }
}
