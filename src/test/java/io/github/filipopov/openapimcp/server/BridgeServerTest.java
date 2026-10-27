package io.github.filipopov.openapimcp.server;

import io.github.filipopov.openapimcp.openapi.ToolDefinition;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeServerTest {

    private static ToolDefinition tool(String method) {
        return new ToolDefinition("t", "Title", "desc", method, "/x", null, List.of(), null,
                Map.of("type", "object", "properties", Map.of()));
    }

    @Test
    void readOperationsAreAnnotatedReadOnlyAndIdempotent() {
        McpSchema.Tool tool = BridgeServer.toMcpTool(tool("GET"));

        assertThat(tool.name()).isEqualTo("t");
        assertThat(tool.title()).isEqualTo("Title");
        assertThat(tool.description()).isEqualTo("desc");
        assertThat(tool.inputSchema()).containsEntry("type", "object");
        assertThat(tool.annotations().readOnlyHint()).isTrue();
        assertThat(tool.annotations().destructiveHint()).isFalse();
        assertThat(tool.annotations().idempotentHint()).isTrue();
        assertThat(tool.annotations().openWorldHint()).isTrue();
    }

    @Test
    void writeOperationsCarryDestructiveAndIdempotentHints() {
        assertThat(BridgeServer.toMcpTool(tool("POST")).annotations())
                .returns(false, McpSchema.ToolAnnotations::readOnlyHint)
                .returns(false, McpSchema.ToolAnnotations::idempotentHint);
        assertThat(BridgeServer.toMcpTool(tool("DELETE")).annotations())
                .returns(true, McpSchema.ToolAnnotations::destructiveHint)
                .returns(true, McpSchema.ToolAnnotations::idempotentHint);
    }

    @Test
    void versionComesFromFilteredResource() {
        assertThat(BridgeServer.version()).matches("\\d+\\.\\d+\\.\\d+.*");
    }
}
