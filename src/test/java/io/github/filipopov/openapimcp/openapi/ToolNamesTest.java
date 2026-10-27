package io.github.filipopov.openapimcp.openapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolNamesTest {

    @ParameterizedTest
    @CsvSource({
            "getPetById, getPetById",
            "list-users, list-users",
            "users.list, users_list",
            "'  Get /pets {id}  ', Get_pets_id",
            "__weird__name__, weird_name",
            "überPet, berPet",
            "'???', ''",
    })
    void sanitize(String raw, String expected) {
        assertThat(ToolNames.sanitize(raw)).isEqualTo(expected == null ? "" : expected);
    }

    @Test
    void sanitizeNullIsEmpty() {
        assertThat(ToolNames.sanitize(null)).isEmpty();
    }

    @Test
    void longNamesAreShortenedWithAStableHash() {
        String a = "x".repeat(100) + "A";
        String b = "x".repeat(100) + "B";

        assertThat(ToolNames.sanitize(a)).hasSize(ToolNames.MAX_LENGTH).isEqualTo(ToolNames.sanitize(a));
        assertThat(ToolNames.sanitize(a)).isNotEqualTo(ToolNames.sanitize(b));
        assertThat(ToolNames.sanitize("y".repeat(64))).isEqualTo("y".repeat(64));
    }

    @Test
    void fallbackFromMethodAndPath() {
        assertThat(ToolNames.fromMethodAndPath("GET", "/pets/{petId}/photos")).isEqualTo("get_pets_petId_photos");
        assertThat(ToolNames.fromMethodAndPath("DELETE", "/")).isEqualTo("delete");
    }

    @Test
    void deduplicateKeepsFirstAndSuffixesTheRest() {
        assertThat(ToolNames.deduplicate(List.of("a", "b", "a", "a", "a_2")))
                .containsExactly("a", "b", "a_2", "a_3", "a_2_2");

        String max = "z".repeat(64);
        assertThat(ToolNames.deduplicate(List.of(max, max)).get(1)).hasSize(64).endsWith("_2");
    }
}
