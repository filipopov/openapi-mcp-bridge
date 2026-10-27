package io.github.filipopov.openapimcp.http;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Masks known secret values (the configured static header values) in strings before they are logged.
 */
public final class Redactor {

    public static final String MASK = "****";

    /** Values shorter than this are not masked: replacing e.g. "1" everywhere would only destroy the log line. */
    private static final int MIN_SECRET_LENGTH = 4;

    private final List<String> secrets;

    public Redactor(Collection<String> secrets) {
        this.secrets = secrets.stream()
                .filter(Objects::nonNull)
                // "Bearer abc" -> also mask "abc" on its own
                .flatMap(s -> s.indexOf(' ') > 0 ? Stream.of(s, s.substring(s.indexOf(' ') + 1).trim()) : Stream.of(s))
                .filter(s -> s.length() >= MIN_SECRET_LENGTH)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    public String redact(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, MASK);
        }
        return result;
    }
}
