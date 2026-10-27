package io.github.filipopov.openapimcp.openapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Derives MCP tool names from operations.
 *
 * <p>The MCP spec allows {@code [A-Za-z0-9_.-]}, up to 128 characters. Several clients (and the model APIs behind
 * them) are stricter, typically {@code ^[a-zA-Z0-9_-]{1,64}$}, so this class targets that common denominator:
 * no dots, at most {@value #MAX_LENGTH} characters.
 */
public final class ToolNames {

    public static final int MAX_LENGTH = 64;

    private static final int HASH_LENGTH = 8;

    private ToolNames() {
    }

    /**
     * Sanitizes a raw name: invalid characters become {@code _}, runs of {@code _} collapse, leading/trailing
     * {@code _}/{@code -} are trimmed. Names longer than {@link #MAX_LENGTH} are shortened and suffixed with a short
     * hash of the original so that two long names sharing a prefix stay distinct.
     *
     * @return the sanitized name, or an empty string if nothing usable is left
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9_-]", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^[_-]+|[_-]+$", "");
        if (cleaned.length() <= MAX_LENGTH) {
            return cleaned;
        }
        String hash = shortHash(raw);
        String prefix = cleaned.substring(0, MAX_LENGTH - HASH_LENGTH - 1).replaceAll("[_-]+$", "");
        return prefix + "_" + hash;
    }

    /**
     * Fallback name for operations without a usable {@code operationId}: {@code get /pets/{petId}} becomes
     * {@code get_pets_petId}.
     */
    public static String fromMethodAndPath(String method, String path) {
        String name = sanitize(method.toLowerCase(Locale.ROOT) + "_" + path.replaceAll("[{}]", ""));
        return name.isEmpty() ? method.toLowerCase(Locale.ROOT) : name;
    }

    /**
     * Makes a list of candidate names unique, in order. The first occurrence keeps its name, later duplicates get
     * {@code _2}, {@code _3}, ... (shortening the base if needed to stay within {@link #MAX_LENGTH}).
     */
    public static List<String> deduplicate(List<String> candidates) {
        Set<String> used = new HashSet<>();
        List<String> result = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            String name = candidate;
            for (int i = 2; !used.add(name); i++) {
                String suffix = "_" + i;
                String base = candidate.length() + suffix.length() > MAX_LENGTH
                        ? candidate.substring(0, MAX_LENGTH - suffix.length())
                        : candidate;
                name = base + suffix;
            }
            result.add(name);
        }
        return result;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to be available on every JVM", e);
        }
    }
}
