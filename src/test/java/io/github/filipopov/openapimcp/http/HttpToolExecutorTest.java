package io.github.filipopov.openapimcp.http;

import io.github.filipopov.openapimcp.http.StubServer.Recorded;
import io.github.filipopov.openapimcp.http.StubServer.Stub;
import io.github.filipopov.openapimcp.openapi.OperationFilter;
import io.github.filipopov.openapimcp.openapi.OperationMapper;
import io.github.filipopov.openapimcp.openapi.SpecLoader;
import io.github.filipopov.openapimcp.openapi.ToolDefinition;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpToolExecutorTest {

    private static final Map<String, ToolDefinition> PETSTORE = tools("examples/petstore.yaml");
    private static final Map<String, ToolDefinition> TRICKY = tools("src/test/resources/tricky.yaml");

    private StubServer stub;
    private HttpToolExecutor executor;

    private static Map<String, ToolDefinition> tools(String spec) {
        OperationMapper.Result result = new OperationMapper(SpecLoader.load(spec),
                new OperationFilter(true, List.of(), List.of())).map();
        Map<String, ToolDefinition> byName = new LinkedHashMap<>();
        result.tools().forEach(t -> byName.put(t.name(), t));
        return byName;
    }

    @BeforeEach
    void setUp() {
        stub = new StubServer();
        executor = new HttpToolExecutor(stub.baseUrl() + "/api/v3/", Map.of("Authorization", "Bearer s3cr3t-token"),
                Duration.ofSeconds(2), 64);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    @Test
    void substitutesPathParameterAndSendsAuthHeader() {
        stub.stub("/api/v3/pet/42", Stub.json(200, "{\"id\":42}"));

        CallToolResult result = executor.execute(PETSTORE.get("getPetById"), Map.of("petId", 42));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo("HTTP 200\nContent-Type: application/json\n\n{\"id\":42}");
        Recorded req = stub.lastRequest();
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.rawPath()).isEqualTo("/api/v3/pet/42");
        assertThat(req.header("Authorization")).isEqualTo("Bearer s3cr3t-token");
        assertThat(req.header("Accept")).isEqualTo("application/json, */*;q=0.5");
    }

    @Test
    void encodesPathSegmentsSoTheyCannotEscapeTheTemplate() {
        executor.execute(PETSTORE.get("getUserByName"), Map.of("username", "a/../b c"));

        assertThat(stub.lastRequest().rawPath()).isEqualTo("/api/v3/user/a%2F..%2Fb%20c");
    }

    @Test
    void integralDoublesAreRenderedWithoutFraction() {
        executor.execute(PETSTORE.get("getPetById"), Map.of("petId", 7.0));

        assertThat(stub.lastRequest().rawPath()).isEqualTo("/api/v3/pet/7");
    }

    @Test
    void explodedArrayQueryParameter() {
        executor.execute(PETSTORE.get("findPetsByTags"), Map.of("tags", List.of("red dog", "a&b")));

        assertThat(stub.lastRequest().rawQuery()).isEqualTo("tags=red%20dog&tags=a%26b");
    }

    @Test
    void nonExplodedArrayDeepObjectAndOptionalParameters() {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("color", "blue");
        filter.put("size", 3);

        executor.execute(TRICKY.get("get_items"), Map.of("tags", List.of("x", "y"), "filter", filter));

        assertThat(stub.lastRequest().rawPath()).isEqualTo("/api/v3/items");
        assertThat(stub.lastRequest().rawQuery()).isEqualTo("tags=x,y&filter%5Bcolor%5D=blue&filter%5Bsize%5D=3");
    }

    @Test
    void headerCookieAndRenamedQueryParameters() {
        executor.execute(TRICKY.get("items_get_by-id"),
                Map.of("id", 5, "query_id", "q", "X-Trace", "trace-1", "session", "abc"));

        Recorded req = stub.lastRequest();
        assertThat(req.rawPath()).isEqualTo("/api/v3/items/5");
        assertThat(req.rawQuery()).isEqualTo("id=q");
        assertThat(req.header("X-Trace")).isEqualTo("trace-1");
        assertThat(req.header("Cookie")).isEqualTo("session=abc");
    }

    @Test
    void jsonBodyIsSerializedWithContentType() {
        stub.stub("/api/v3/pet", Stub.json(200, "{\"id\":1}"));

        Map<String, Object> pet = new LinkedHashMap<>();
        pet.put("name", "Rex");
        pet.put("photoUrls", List.of("http://x/1.png"));
        CallToolResult result = executor.execute(PETSTORE.get("addPet"), Map.of("body", pet));

        assertThat(result.isError()).isFalse();
        Recorded req = stub.lastRequest();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.header("Content-Type")).isEqualTo("application/json");
        assertThat(req.body()).isEqualTo("{\"name\":\"Rex\",\"photoUrls\":[\"http://x/1.png\"]}");
    }

    @Test
    void textBodyIsSentVerbatimAndBodyParamNamedBodyStillWorks() {
        ToolDefinition notes = TRICKY.values().stream().filter(t -> t.path().equals("/notes")).findFirst().orElseThrow();
        executor.execute(notes, Map.of("body", "hello\nworld"));
        assertThat(stub.lastRequest().header("Content-Type")).isEqualTo("text/plain");
        assertThat(stub.lastRequest().body()).isEqualTo("hello\nworld");

        executor.execute(TRICKY.get("replaceItem"), Map.of("id", 1, "body", "query-value", "requestBody", Map.of("name", "n")));
        assertThat(stub.lastRequest().method()).isEqualTo("PUT");
        assertThat(stub.lastRequest().rawQuery()).isEqualTo("body=query-value");
        assertThat(stub.lastRequest().body()).isEqualTo("{\"name\":\"n\"}");
    }

    @Test
    void httpErrorsBecomeErrorResultsWithStatusAndBody() {
        stub.stub("/api/v3/pet/999", Stub.json(404, "{\"message\":\"Pet not found\"}"));

        CallToolResult result = executor.execute(PETSTORE.get("getPetById"), Map.of("petId", 999));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).startsWith("HTTP 404").contains("Pet not found");
    }

    @Test
    void serverErrorsAreErrorsToo() {
        stub.stub("/api/v3/store/inventory", new Stub(503, "text/plain", "down".getBytes(StandardCharsets.UTF_8), 0));

        CallToolResult result = executor.execute(PETSTORE.get("getInventory"), Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("HTTP 503\nContent-Type: text/plain\n\ndown");
    }

    @Test
    void largeBodiesAreTruncatedWithANote() {
        stub.stub("/api/v3/store/inventory", Stub.json(200, "x".repeat(500)));

        CallToolResult result = executor.execute(PETSTORE.get("getInventory"), Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("x".repeat(64) + "\n\n[Response truncated: showing the first 64 bytes of 500.")
                .doesNotContain("x".repeat(65));
    }

    @Test
    void binaryBodiesAreSummarized() {
        stub.stub("/api/v3/store/inventory", new Stub(200, "image/png", new byte[] {1, 2, 3}, 0));

        assertThat(text(executor.execute(PETSTORE.get("getInventory"), Map.of())))
                .endsWith("(binary response body not shown, 3 bytes)");
    }

    @Test
    void emptyBodies() {
        stub.stub("/api/v3/user/logout", new Stub(200, null, new byte[0], 0));

        assertThat(text(executor.execute(PETSTORE.get("logoutUser"), Map.of()))).isEqualTo("HTTP 200\n\n(empty body)");
    }

    @Test
    void timeoutsBecomeErrorResults() {
        stub.stub("/api/v3/store/inventory", new Stub(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), 3_000));
        HttpToolExecutor fast = new HttpToolExecutor(stub.baseUrl() + "/api/v3", Map.of(), Duration.ofMillis(300), 1000);

        CallToolResult result = fast.execute(PETSTORE.get("getInventory"), Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).startsWith("Request timed out");
    }

    @Test
    void connectionFailuresBecomeErrorResults() {
        String deadBaseUrl = stub.baseUrl();
        stub.close();
        HttpToolExecutor dead = new HttpToolExecutor(deadBaseUrl, Map.of(), Duration.ofSeconds(1), 1000);

        CallToolResult result = dead.execute(PETSTORE.get("getInventory"), Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).startsWith("Request failed: ");
    }

    @Test
    void missingRequiredArgumentsAreReportedWithoutSendingARequest() {
        CallToolResult result = executor.execute(PETSTORE.get("getPetById"), Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("missing required path parameter 'petId'");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void headerInjectionIsRejected() {
        CallToolResult result = executor.execute(TRICKY.get("items_get_by-id"),
                Map.of("id", 1, "X-Trace", "x\r\nEvil: 1"));

        assertThat(result.isError()).isTrue();
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void restrictedStaticHeadersFailFastWithoutLeakingTheValue() {
        assertThatThrownBy(() -> new HttpToolExecutor("http://localhost", Map.of("Host", "secret-host"),
                Duration.ofSeconds(1), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Host")
                .hasMessageNotContaining("secret-host");
    }
}
