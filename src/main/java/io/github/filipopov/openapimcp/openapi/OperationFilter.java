package io.github.filipopov.openapimcp.openapi;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which operations become tools.
 *
 * <p>Rules, applied in order:
 * <ol>
 *   <li>Unless writes are allowed, only {@code GET} and {@code HEAD} operations pass.</li>
 *   <li>If include patterns are given, the operation must match at least one.</li>
 *   <li>The operation must not match any exclude pattern.</li>
 * </ol>
 *
 * <p>A pattern is a glob ({@code *} = any characters except {@code /}, {@code **} = any characters,
 * {@code ?} = one character). An unprefixed pattern is tested against the operationId, every tag and the path
 * template. Prefix it to target one of them: {@code op:get*}, {@code tag:store}, {@code path:/pet/**}. Matching is
 * case-sensitive, as operationIds and paths are.
 */
public final class OperationFilter {

    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD");

    private final boolean allowWrites;
    private final List<Matcher> includes;
    private final List<Matcher> excludes;

    public OperationFilter(boolean allowWrites, List<String> includes, List<String> excludes) {
        this.allowWrites = allowWrites;
        this.includes = includes.stream().filter(s -> !s.isBlank()).map(Matcher::parse).toList();
        this.excludes = excludes.stream().filter(s -> !s.isBlank()).map(Matcher::parse).toList();
    }

    public static boolean isReadOnly(String method) {
        return READ_ONLY_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    /**
     * @return {@code null} if the operation is accepted, otherwise a human-readable reason for skipping it
     */
    public String rejectionReason(String method, String path, String operationId, List<String> tags) {
        if (!allowWrites && !isReadOnly(method)) {
            return "write operation (" + method + "); start with --allow-writes to expose it";
        }
        if (!includes.isEmpty() && includes.stream().noneMatch(m -> m.matches(path, operationId, tags))) {
            return "not matched by any --include pattern";
        }
        for (Matcher m : excludes) {
            if (m.matches(path, operationId, tags)) {
                return "matched --exclude pattern '" + m.source + "'";
            }
        }
        return null;
    }

    private record Matcher(String source, String target, Pattern regex) {

        static Matcher parse(String raw) {
            String pattern = raw.trim();
            String target = "any";
            int colon = pattern.indexOf(':');
            if (colon > 0) {
                String prefix = pattern.substring(0, colon);
                if (Set.of("op", "tag", "path").contains(prefix)) {
                    target = prefix;
                    pattern = pattern.substring(colon + 1);
                }
            }
            return new Matcher(raw.trim(), target, globToRegex(pattern));
        }

        boolean matches(String path, String operationId, List<String> tags) {
            boolean op = operationId != null && regex.matcher(operationId).matches();
            boolean tag = tags.stream().anyMatch(t -> regex.matcher(t).matches());
            boolean p = regex.matcher(path).matches();
            return switch (target) {
                case "op" -> op;
                case "tag" -> tag;
                case "path" -> p;
                default -> op || tag || p;
            };
        }
    }

    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString());
    }
}
