package io.github.filipopov.openapimcp.server;

import io.github.filipopov.openapimcp.http.StubServer;
import io.github.filipopov.openapimcp.http.StubServer.Stub;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Launches the packaged fat jar as a child process, exactly like Claude Desktop / Claude Code would, and talks to
 * it over stdio with the MCP SDK's client. Runs in the {@code integration-test} phase (after {@code package}).
 */
class StdioEndToEndIT {

    private static final String SECRET = "Bearer e2e-secret-token";

    private StubServer api;
    private McpSyncClient client;
    private final List<String> stderr = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        Path jar = Path.of("target", "openapi-mcp-bridge.jar").toAbsolutePath();
        assertThat(jar).as("fat jar must be built before integration tests").exists();

        api = new StubServer()
                .stub("/pet/7", Stub.json(200, "{\"id\":7,\"name\":\"Rex\",\"photoUrls\":[]}"))
                .stub("/pet/404", Stub.json(404, "{\"message\":\"Pet not found\"}"));

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ServerParameters params = ServerParameters.builder(java)
                .args("-jar", jar.toString(),
                        "--spec", Path.of("examples", "petstore.yaml").toAbsolutePath().toString(),
                        "--base-url", api.baseUrl(),
                        "--exclude", "tag:user")
                .addEnvVar("OPENAPI_MCP_HEADER_AUTHORIZATION", SECRET)
                .build();
        StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
        transport.setStdErrorHandler(stderr::add);

        client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .initializationTimeout(Duration.ofSeconds(30))
                .clientInfo(McpSchema.Implementation.builder("e2e-test", "1.0").build())
                .build();
        client.initialize();
    }

    private static CallToolRequest call(String tool, Map<String, Object> arguments) {
        return CallToolRequest.builder(tool).arguments(arguments).build();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.closeGracefully();
        }
        if (api != null) {
            api.close();
        }
    }

    @Test
    void listsReadOnlyToolsAndCallsOne() {
        assertThat(client.getServerInfo().name()).isEqualTo("openapi-mcp-bridge");

        List<Tool> tools = client.listTools().tools();
        assertThat(tools).extracting(Tool::name)
                .containsExactly("findPetsByStatus", "findPetsByTags", "getPetById", "getInventory", "getOrderById");

        Tool getPet = tools.stream().filter(t -> t.name().equals("getPetById")).findFirst().orElseThrow();
        assertThat(getPet.annotations().readOnlyHint()).isTrue();
        assertThat(getPet.inputSchema()).containsEntry("required", List.of("petId"));

        CallToolResult ok = client.callTool(call("getPetById", Map.of("petId", 7)));
        assertThat(ok.isError()).isFalse();
        assertThat(((TextContent) ok.content().getFirst()).text()).startsWith("HTTP 200").contains("\"name\":\"Rex\"");
        assertThat(api.lastRequest().header("Authorization")).isEqualTo(SECRET);

        CallToolResult notFound = client.callTool(call("getPetById", Map.of("petId", 404)));
        assertThat(notFound.isError()).isTrue();
        assertThat(((TextContent) notFound.content().getFirst()).text()).startsWith("HTTP 404");
    }

    @Test
    void invalidArgumentsAreRejectedBySchemaValidation() {
        CallToolResult result = client.callTool(call("getPetById", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).contains("petId");
        assertThat(api.requests()).isEmpty();
    }

    @Test
    void secretsNeverReachTheLogs() throws Exception {
        client.callTool(call("getPetById", Map.of("petId", 7)));
        client.closeGracefully();
        client = null;

        Thread.sleep(200); // let the stderr reader drain
        assertThat(String.join("\n", stderr)).contains("GET /pet/7 -> 200").doesNotContain("e2e-secret-token");
    }
}
