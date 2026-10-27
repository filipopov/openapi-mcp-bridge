package io.github.filipopov.openapimcp.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BridgeConfigTest {

    @Test
    void parsesCurlStyleHeaders() {
        assertThat(BridgeConfig.parseHeader("Authorization: Bearer abc=="))
                .isEqualTo(Map.entry("Authorization", "Bearer abc=="));
        assertThat(BridgeConfig.parseHeader("X-Api-Key=k:v")).isEqualTo(Map.entry("X-Api-Key", "k:v"));
    }

    @Test
    void rejectsInvalidHeadersWithoutEchoingTheValue() {
        assertThatThrownBy(() -> BridgeConfig.parseHeader("no-separator-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> BridgeConfig.parseHeader("Bad Name: secretvalue"))
                .hasMessageNotContaining("secretvalue");
    }

    @Test
    void readsHeadersFromEnvironment() {
        Map<String, String> env = Map.of(
                "OPENAPI_MCP_HEADER_AUTHORIZATION", "Bearer t0ken",
                "OPENAPI_MCP_HEADER_X_API_KEY", "k3y",
                "OPENAPI_MCP_HEADER_", "ignored",
                "PATH", "/usr/bin");

        assertThat(BridgeConfig.headersFromEnvironment(env)).containsExactly(
                Map.entry("Authorization", "Bearer t0ken"),
                Map.entry("X-Api-Key", "k3y"));
    }

    @Test
    void toStringNeverContainsHeaderValues() {
        BridgeConfig config = new BridgeConfig("spec.yaml", null, false, List.of(), List.of(),
                Map.of("Authorization", "Bearer very-secret"), Duration.ofSeconds(5), 1000);

        assertThat(config.toString()).contains("Authorization").doesNotContain("very-secret");
    }
}
