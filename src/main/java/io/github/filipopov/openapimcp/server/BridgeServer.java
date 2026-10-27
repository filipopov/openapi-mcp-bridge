package io.github.filipopov.openapimcp.server;

import io.github.filipopov.openapimcp.http.HttpToolExecutor;
import io.github.filipopov.openapimcp.openapi.ToolDefinition;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

/**
 * Wires {@link ToolDefinition}s into an MCP server built with the official MCP Java SDK.
 */
public final class BridgeServer {

    public static final String NAME = "openapi-mcp-bridge";

    private BridgeServer() {
    }

    /**
     * Builds and starts a synchronous MCP server on the given transport.
     *
     * @param transport    transport provider, e.g. {@link io.modelcontextprotocol.server.transport.StdioServerTransportProvider}
     * @param tools        tools to expose
     * @param executor     executes tool calls
     * @param instructions optional server instructions shown to the client
     */
    public static McpSyncServer start(McpServerTransportProvider transport, List<ToolDefinition> tools,
                                      HttpToolExecutor executor, String instructions) {
        List<SyncToolSpecification> specs = tools.stream()
                .map(tool -> SyncToolSpecification.builder()
                        .tool(toMcpTool(tool))
                        .callHandler((exchange, request) -> executor.execute(tool, request.arguments()))
                        .build())
                .toList();

        return McpServer.sync(transport)
                .serverInfo(NAME, version())
                .instructions(instructions)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(specs)
                .build();
    }

    public static McpSchema.Tool toMcpTool(ToolDefinition tool) {
        String method = tool.method();
        boolean readOnly = tool.readOnly();
        McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(
                tool.title(),
                readOnly,
                "DELETE".equals(method),
                readOnly || "PUT".equals(method) || "DELETE".equals(method),
                true,
                null);
        return McpSchema.Tool.builder(tool.name(), tool.inputSchema())
                .title(tool.title())
                .description(tool.description())
                .annotations(annotations)
                .build();
    }

    /** JSON mapper used for the MCP wire protocol (Jackson 2, shared with swagger-parser). */
    public static McpJsonMapper jsonMapper() {
        return McpJsonDefaults.getMapper();
    }

    public static String version() {
        try (InputStream in = BridgeServer.class.getResourceAsStream("/openapi-mcp-bridge.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", "dev");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
