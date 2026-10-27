package io.github.filipopov.openapimcp.openapi;

import io.github.filipopov.openapimcp.openapi.ToolDefinition.BodyEncoding;
import io.github.filipopov.openapimcp.openapi.ToolDefinition.Location;
import io.github.filipopov.openapimcp.openapi.ToolDefinition.ParameterBinding;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class OperationMapperTest {

    static final String PETSTORE = "examples/petstore.yaml";
    static final String TRICKY = "src/test/resources/tricky.yaml";

    static OperationMapper.Result map(String spec, boolean allowWrites, List<String> includes, List<String> excludes) {
        OpenAPI api = SpecLoader.load(spec);
        return new OperationMapper(api, new OperationFilter(allowWrites, includes, excludes)).map();
    }

    static ToolDefinition tool(OperationMapper.Result result, String name) {
        return result.tools().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no tool " + name + " in " + names(result)));
    }

    static List<String> names(OperationMapper.Result result) {
        return result.tools().stream().map(ToolDefinition::name).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> prop(ToolDefinition tool, String name) {
        return (Map<String, Object>) ((Map<String, Object>) tool.inputSchema().get("properties")).get(name);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> child(Map<String, Object> schema, String... path) {
        Map<String, Object> current = schema;
        for (String key : path) {
            current = (Map<String, Object>) current.get(key);
        }
        return current;
    }

    @Nested
    class Petstore {

        @Test
        void exposesOnlyReadOperationsByDefault() {
            OperationMapper.Result result = map(PETSTORE, false, List.of(), List.of());

            assertThat(names(result)).containsExactly("findPetsByStatus", "findPetsByTags", "getPetById",
                    "getInventory", "getOrderById", "loginUser", "logoutUser", "getUserByName");
            assertThat(result.tools()).allMatch(ToolDefinition::readOnly);
            assertThat(result.skipped()).hasSize(10)
                    .allSatisfy(s -> assertThat(s.reason()).contains("--allow-writes"));
        }

        @Test
        void allowWritesExposesWritesButSkipsUnsupportedBinaryUpload() {
            OperationMapper.Result result = map(PETSTORE, true, List.of(), List.of());

            assertThat(names(result)).hasSize(17).contains("addPet", "updatePet", "deletePet", "placeOrder")
                    .doesNotContain("uploadFile");
            assertThat(result.skipped()).singleElement()
                    .satisfies(s -> {
                        assertThat(s.operationId()).isEqualTo("uploadFile");
                        assertThat(s.reason()).contains("application/octet-stream");
                    });
        }

        @Test
        void pathParameterIsRequiredAndTyped() {
            ToolDefinition getPet = tool(map(PETSTORE, false, List.of(), List.of()), "getPetById");

            assertThat(getPet.method()).isEqualTo("GET");
            assertThat(getPet.path()).isEqualTo("/pet/{petId}");
            assertThat(getPet.inputSchema()).containsEntry("type", "object")
                    .containsEntry("required", List.of("petId"))
                    .containsEntry("additionalProperties", false);
            assertThat(prop(getPet, "petId")).containsEntry("type", "integer")
                    .containsEntry("description", "ID of pet to return");
            assertThat(getPet.parameters()).containsExactly(
                    new ParameterBinding("petId", "petId", Location.PATH, "simple", false, true));
            assertThat(getPet.description()).isEqualTo("Find pet by ID.\n\nReturns a single pet.\n\nHTTP GET /pet/{petId}");
            assertThat(getPet.title()).isEqualTo("Find pet by ID.");
        }

        @Test
        void requestBodyRefIsInlinedUnderBodyProperty() {
            ToolDefinition addPet = tool(map(PETSTORE, true, List.of(), List.of()), "addPet");

            assertThat(addPet.inputSchema()).containsEntry("required", List.of("body"));
            assertThat(addPet.body().encoding()).isEqualTo(BodyEncoding.JSON);
            assertThat(addPet.body().mediaType()).isEqualTo("application/json");

            Map<String, Object> body = prop(addPet, "body");
            assertThat(body).containsEntry("type", "object")
                    .containsEntry("required", List.of("name", "photoUrls"))
                    .doesNotContainKey("$ref");
            assertThat(child(body, "properties", "category")).containsEntry("type", "object");
            assertThat(child(body, "properties", "category", "properties", "name")).containsEntry("type", "string")
                    .doesNotContainKey("example");
            assertThat(child(body, "properties", "tags", "items", "properties")).containsKeys("id", "name");
        }

        @Test
        void optionalQueryParametersAreNotRequired() {
            ToolDefinition login = tool(map(PETSTORE, false, List.of(), List.of()), "loginUser");

            assertThat(login.inputSchema()).doesNotContainKey("required");
            assertThat(login.parameters()).extracting(ParameterBinding::location).containsOnly(Location.QUERY);
        }

        @Test
        void includeAndExcludeFilters() {
            assertThat(names(map(PETSTORE, true, List.of("tag:store"), List.of())))
                    .containsExactly("getInventory", "placeOrder", "getOrderById", "deleteOrder");
            assertThat(names(map(PETSTORE, false, List.of("path:/pet/**"), List.of("findPetsBy*"))))
                    .containsExactly("getPetById");
            assertThat(names(map(PETSTORE, false, List.of("op:get*"), List.of("user"))))
                    .containsExactly("getPetById", "getInventory", "getOrderById");
        }

        @Test
        void filtersNeverOverrideReadOnlyMode() {
            OperationMapper.Result result = map(PETSTORE, false, List.of("addPet"), List.of());
            assertThat(result.tools()).isEmpty();
        }
    }

    @Nested
    class Tricky {

        final OperationMapper.Result result = map(TRICKY, true, List.of(), List.of());

        @Test
        void namesAreSanitizedDeduplicatedAndBounded() {
            List<String> names = names(result);

            assertThat(names).hasSize(10).allMatch(n -> n.matches("[A-Za-z0-9_-]{1,64}")).doesNotHaveDuplicates();
            assertThat(names.subList(0, 7)).containsExactly(
                    "items_get_by-id", // "items.get by-id!" sanitized
                    "replaceItem", "deleteItem",
                    "get_items",       // no operationId: method + path
                    "createItem",
                    "getTree", "getTree_2"); // duplicate operationId
            assertThat(names.get(9)).isEqualTo("ping");

            // two >64-char operationIds sharing a long prefix: truncated + hashed, still distinct
            assertThat(names.get(7)).hasSize(64).startsWith("aVeryLongOperationId");
            assertThat(names.get(8)).hasSize(64).startsWith("aVeryLongOperationId").isNotEqualTo(names.get(7));
        }

        @Test
        void multipartOperationIsSkipped() {
            assertThat(result.skipped()).singleElement()
                    .satisfies(s -> assertThat(s.operationId()).isEqualTo("uploadAttachment"));
        }

        @Test
        void pathLevelParametersAreInheritedAndOverriddenAndNameClashesArePrefixed() {
            ToolDefinition get = tool(result, "items_get_by-id");

            assertThat(get.parameters()).extracting(ParameterBinding::argName, ParameterBinding::wireName,
                            ParameterBinding::location)
                    .containsExactlyInAnyOrder(
                            tuple("id", "id", Location.PATH),
                            tuple("X-Trace", "X-Trace", Location.HEADER),
                            tuple("query_id", "id", Location.QUERY),
                            tuple("session", "session", Location.COOKIE));
            assertThat(get.inputSchema()).containsEntry("required", List.of("id", "X-Trace"));
            assertThat(prop(get, "id")).containsEntry("type", "integer").containsEntry("description", "Item identifier");
            assertThat(prop(get, "X-Trace")).containsEntry("description", "operation-level header");
            assertThat((String) prop(get, "query_id").get("description")).contains("query parameter 'id'");
            assertThat(get.parameters()).noneMatch(p -> p.wireName().equals("Accept"));
        }

        @Test
        void requestBodyRefAndParameterNamedBody() {
            ToolDefinition replace = tool(result, "replaceItem");

            assertThat(replace.body().argName()).isEqualTo("requestBody");
            assertThat(replace.body().required()).isTrue();
            assertThat(replace.inputSchema()).containsEntry("required", List.of("id", "requestBody"));
            Map<String, Object> body = prop(replace, "requestBody");
            assertThat(body).containsEntry("description", "The full item (application/json)")
                    .containsEntry("required", List.of("name"));
        }

        @Test
        void openApiOnlyKeywordsAreRemovedAndNullableBecomesNullType() {
            Map<String, Object> item = prop(tool(result, "replaceItem"), "requestBody");

            assertThat(child(item, "properties", "name")).isEqualTo(Map.of("type", "string"));
            assertThat(child(item, "properties", "nickname")).containsEntry("type", List.of("string", "null"))
                    .doesNotContainKey("nullable");
            assertThat(child(item, "properties", "owner", "properties", "email")).containsEntry("format", "email");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allOf = (List<Map<String, Object>>) child(item, "properties", "attributes").get("allOf");
            assertThat(child(allOf.getFirst(), "properties", "createdAt")).containsEntry("format", "date-time");
        }

        @Test
        void recursiveSchemasAreCutOff() {
            Map<String, Object> tree = prop(tool(result, "getTree_2"), "body");
            Map<String, Object> children = child(tree, "properties", "children", "items");

            assertThat(children).containsEntry("type", "object");
            assertThat((String) children.get("description")).contains("Recursive reference to 'TreeNode'");
        }

        @Test
        void jsonMediaTypeIsPreferredOverForm() {
            ToolDefinition create = tool(result, "createItem");
            assertThat(create.body().mediaType()).isEqualTo("application/merge-patch+json");
            assertThat(create.body().encoding()).isEqualTo(BodyEncoding.JSON);
        }

        @Test
        void textBodyAndQueryStyles() {
            ToolDefinition notes = result.tools().get(7);
            assertThat(notes.body().encoding()).isEqualTo(BodyEncoding.TEXT);
            assertThat(prop(notes, "body")).containsEntry("type", "string");

            ToolDefinition list = tool(result, "get_items");
            assertThat(list.parameters()).extracting(ParameterBinding::style, ParameterBinding::explode)
                    .containsExactly(
                            tuple("form", false),
                            tuple("deepObject", false),
                            tuple("form", true));
            assertThat(prop(list, "limit")).containsEntry("type", "integer");
            assertThat(((Number) prop(list, "limit").get("minimum")).intValue()).isEqualTo(1);
            assertThat(((Number) prop(list, "limit").get("maximum")).intValue()).isEqualTo(100);
            assertThat(list.description()).isEqualTo("List items (no operationId)\n\nHTTP GET /items");
        }

        @Test
        void deprecatedOperationsAreFlagged() {
            assertThat(tool(result, "deleteItem").description()).startsWith("[DEPRECATED] Delete an item");
        }

        @Test
        void baseUrlComesFromServerVariablesUnlessGiven() {
            OpenAPI api = SpecLoader.load(TRICKY);
            assertThat(SpecLoader.resolveBaseUrl(null, api, TRICKY)).isEqualTo("https://eu.example.com/api/v2");
            assertThat(SpecLoader.resolveBaseUrl("http://localhost:8080/", api, TRICKY)).isEqualTo("http://localhost:8080");
        }
    }

    @Test
    void openApi31SpecIsSupported() {
        OperationMapper.Result result = map("src/test/resources/openapi31.yaml", false, List.of(), List.of());

        ToolDefinition get = tool(result, "getThing");
        Map<String, Object> filter = prop(get, "filter");
        assertThat(filter).containsEntry("type", List.of("string", "null"));
        assertThat(prop(get, "thingId")).containsEntry("type", "string")
                .containsEntry("description", "The thing");
    }

    @Test
    void swagger2SpecIsConverted() {
        OperationMapper.Result result = map("src/test/resources/swagger2.json", false, List.of(), List.of());
        assertThat(names(result)).containsExactly("getUser");
        assertThat(tool(result, "getUser").inputSchema()).containsEntry("required", List.of("userId"));
    }
}
