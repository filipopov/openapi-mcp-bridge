package io.github.filipopov.openapimcp.http;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    @Test
    void masksFullValueAndTokenPart() {
        Redactor redactor = new Redactor(List.of("Bearer abc123xyz", "k3y-value"));

        assertThat(redactor.redact("header was Bearer abc123xyz")).isEqualTo("header was ****");
        assertThat(redactor.redact("token abc123xyz leaked")).isEqualTo("token **** leaked");
        assertThat(redactor.redact("api key k3y-value")).isEqualTo("api key ****");
        assertThat(redactor.redact(null)).isNull();
    }

    @Test
    void ignoresVeryShortValues() {
        assertThat(new Redactor(List.of("ab")).redact("abc")).isEqualTo("abc");
    }
}
