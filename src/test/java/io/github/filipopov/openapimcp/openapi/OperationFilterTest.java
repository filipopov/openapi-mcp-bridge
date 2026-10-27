package io.github.filipopov.openapimcp.openapi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperationFilterTest {

    @Test
    void readOnlyByDefault() {
        OperationFilter filter = new OperationFilter(false, List.of(), List.of());

        assertThat(filter.rejectionReason("GET", "/a", "a", List.of())).isNull();
        assertThat(filter.rejectionReason("HEAD", "/a", "a", List.of())).isNull();
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")) {
            assertThat(filter.rejectionReason(method, "/a", "a", List.of())).contains("--allow-writes");
        }
    }

    @Test
    void allowWrites() {
        OperationFilter filter = new OperationFilter(true, List.of(), List.of());
        assertThat(filter.rejectionReason("DELETE", "/a", "a", List.of())).isNull();
    }

    @Test
    void unprefixedPatternMatchesOperationIdTagOrPath() {
        OperationFilter filter = new OperationFilter(false, List.of("store"), List.of());

        assertThat(filter.rejectionReason("GET", "/x", "store", List.of())).isNull();
        assertThat(filter.rejectionReason("GET", "/x", "other", List.of("store"))).isNull();
        assertThat(filter.rejectionReason("GET", "/x", "other", List.of("pet"))).contains("--include");
    }

    @Test
    void prefixedPatternsTargetOneField() {
        OperationFilter tagOnly = new OperationFilter(false, List.of("tag:store"), List.of());
        assertThat(tagOnly.rejectionReason("GET", "/x", "store", List.of())).isNotNull();

        OperationFilter pathOnly = new OperationFilter(false, List.of("path:/store/**"), List.of());
        assertThat(pathOnly.rejectionReason("GET", "/store/order/{id}", null, List.of())).isNull();
        assertThat(pathOnly.rejectionReason("GET", "/pets", null, List.of())).isNotNull();
    }

    @Test
    void excludeWinsOverInclude() {
        OperationFilter filter = new OperationFilter(false, List.of("op:get*"), List.of("op:getSecret*"));

        assertThat(filter.rejectionReason("GET", "/a", "getThing", List.of())).isNull();
        assertThat(filter.rejectionReason("GET", "/a", "getSecretKey", List.of())).contains("op:getSecret*");
    }

    @Test
    void globSemantics() {
        assertThat(OperationFilter.globToRegex("/pet/*").matcher("/pet/{id}").matches()).isTrue();
        assertThat(OperationFilter.globToRegex("/pet/*").matcher("/pet/{id}/photos").matches()).isFalse();
        assertThat(OperationFilter.globToRegex("/pet/**").matcher("/pet/{id}/photos").matches()).isTrue();
        assertThat(OperationFilter.globToRegex("get?").matcher("getX").matches()).isTrue();
        assertThat(OperationFilter.globToRegex("a.b").matcher("aXb").matches()).isFalse();
    }
}
