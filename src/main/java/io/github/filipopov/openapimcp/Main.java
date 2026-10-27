package io.github.filipopov.openapimcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.filipopov.openapimcp.config.BridgeConfig;
import io.github.filipopov.openapimcp.http.HttpToolExecutor;
import io.github.filipopov.openapimcp.openapi.OperationFilter;
import io.github.filipopov.openapimcp.openapi.OperationMapper;
import io.github.filipopov.openapimcp.openapi.SpecLoader;
import io.github.filipopov.openapimcp.openapi.ToolDefinition;
import io.github.filipopov.openapimcp.server.BridgeServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.swagger.v3.oas.models.OpenAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Command-line entry point. Every option also has an environment variable equivalent, which is convenient in MCP
 * client configs (Claude Desktop's {@code env} block, {@code claude mcp add -e ...}).
 */
@Command(name = "openapi-mcp-bridge",
        mixinStandardHelpOptions = true,
        versionProvider = Main.VersionProvider.class,
        sortOptions = false,
        description = {
                "Expose the operations of an OpenAPI 3 spec as MCP tools over stdio.",
                "",
                "Static headers can also be supplied as environment variables named OPENAPI_MCP_HEADER_<NAME>,",
                "e.g. OPENAPI_MCP_HEADER_AUTHORIZATION='Bearer ...' or OPENAPI_MCP_HEADER_X_API_KEY=...",
                ""})
public final class Main implements Callable<Integer> {

    @Option(names = "--spec", paramLabel = "<file|url>", defaultValue = "${env:OPENAPI_MCP_SPEC}",
            description = "OpenAPI 3 (or Swagger 2) document, as a file path or http(s) URL. Env: OPENAPI_MCP_SPEC")
    String spec;

    @Option(names = "--base-url", paramLabel = "<url>", defaultValue = "${env:OPENAPI_MCP_BASE_URL}",
            description = "Base URL of the API. Default: first 'servers' entry of the spec. Env: OPENAPI_MCP_BASE_URL")
    String baseUrl;

    @Option(names = "--allow-writes", defaultValue = "${env:OPENAPI_MCP_ALLOW_WRITES:-false}", fallbackValue = "true",
            arity = "0..1",
            description = "Also expose POST/PUT/PATCH/DELETE operations (default: GET/HEAD only). Env: OPENAPI_MCP_ALLOW_WRITES")
    boolean allowWrites;

    @Option(names = "--include", paramLabel = "<pattern>", split = ",", defaultValue = "${env:OPENAPI_MCP_INCLUDE}",
            description = "Only expose operations matching a glob on operationId, tag or path; prefix with op:, tag: "
                    + "or path: to be specific. Repeatable / comma-separated. Env: OPENAPI_MCP_INCLUDE")
    List<String> includes = new ArrayList<>();

    @Option(names = "--exclude", paramLabel = "<pattern>", split = ",", defaultValue = "${env:OPENAPI_MCP_EXCLUDE}",
            description = "Hide operations matching a pattern (same syntax as --include). Env: OPENAPI_MCP_EXCLUDE")
    List<String> excludes = new ArrayList<>();

    @Option(names = {"-H", "--header"}, paramLabel = "<'Name: value'>",
            description = "Static header sent with every request, e.g. 'Authorization: Bearer ...'. Repeatable. "
                    + "Values are never logged.")
    List<String> headers = new ArrayList<>();

    @Option(names = "--timeout", paramLabel = "<seconds>", defaultValue = "${env:OPENAPI_MCP_TIMEOUT:-30}",
            description = "Connect and request timeout in seconds (default: ${DEFAULT-VALUE}). Env: OPENAPI_MCP_TIMEOUT")
    int timeoutSeconds;

    @Option(names = "--max-response-bytes", paramLabel = "<bytes>",
            defaultValue = "${env:OPENAPI_MCP_MAX_RESPONSE_BYTES:-102400}",
            description = "Truncate response bodies larger than this (default: ${DEFAULT-VALUE}). "
                    + "Env: OPENAPI_MCP_MAX_RESPONSE_BYTES")
    int maxResponseBytes;

    @Option(names = "--list-tools",
            description = "Print the generated tool definitions as JSON to stdout and exit (does not start the server).")
    boolean listTools;

    @Option(names = {"-v", "--verbose"}, description = "Debug logging (to stderr).")
    boolean verbose;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    private boolean serving;

    public static void main(String[] args) {
        Main main = new Main();
        CommandLine cmd = new CommandLine(main);
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            commandLine.getErr().println("openapi-mcp-bridge: " + ex.getMessage());
            if (main.verbose) {
                ex.printStackTrace(commandLine.getErr());
            }
            return 1;
        });
        int exitCode = cmd.execute(args);
        // When serving, the SDK's stdio threads keep the JVM alive until stdin closes; don't exit here.
        if (!main.serving) {
            System.exit(exitCode);
        }
    }

    @Override
    public Integer call() throws Exception {
        if (verbose) {
            // must happen before the first logger is created
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        }
        Logger log = LoggerFactory.getLogger(Main.class);

        BridgeConfig config = toConfig();
        log.debug("Configuration: {}", config);

        OpenAPI openApi = SpecLoader.load(config.specLocation());
        String resolvedBaseUrl = SpecLoader.resolveBaseUrl(config.baseUrl(), openApi, config.specLocation());

        OperationFilter filter = new OperationFilter(config.allowWrites(), config.includes(), config.excludes());
        OperationMapper.Result result = new OperationMapper(openApi, filter).map();
        result.skipped().forEach(s -> log.debug("Skipped {} {} ({}): {}", s.method(), s.path(),
                s.operationId() == null ? "no operationId" : s.operationId(), s.reason()));

        String title = openApi.getInfo() != null && openApi.getInfo().getTitle() != null
                ? openApi.getInfo().getTitle() : config.specLocation();
        log.info("'{}': exposing {} tool(s), skipped {} operation(s){}", title, result.tools().size(),
                result.skipped().size(), config.allowWrites() ? "" : " (read-only mode; --allow-writes to expose writes)");

        if (listTools) {
            printTools(result.tools());
            return 0;
        }
        if (result.tools().isEmpty()) {
            log.warn("No operations matched; the server will expose no tools");
        }

        HttpToolExecutor executor = new HttpToolExecutor(resolvedBaseUrl, config.staticHeaders(), config.timeout(),
                config.maxResponseBytes());
        String instructions = "Tools generated from the OpenAPI document '" + title + "'. Each tool performs one "
                + "HTTP request against " + resolvedBaseUrl + " and returns the status line, content type and body.";
        McpSyncServer server = BridgeServer.start(new StdioServerTransportProvider(BridgeServer.jsonMapper()),
                result.tools(), executor, instructions);
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully, "mcp-shutdown"));
        serving = true;
        log.info("Serving MCP over stdio; API base URL {}", resolvedBaseUrl);
        return 0;
    }

    BridgeConfig toConfig() {
        if (spec == null || spec.isBlank()) {
            throw new CommandLine.ParameterException(commandSpec.commandLine(),
                    "Missing required option --spec (or environment variable OPENAPI_MCP_SPEC)");
        }
        if (timeoutSeconds <= 0 || maxResponseBytes <= 0) {
            throw new CommandLine.ParameterException(commandSpec.commandLine(),
                    "--timeout and --max-response-bytes must be positive");
        }
        Map<String, String> allHeaders = new LinkedHashMap<>(BridgeConfig.headersFromEnvironment(System.getenv()));
        for (String raw : headers) {
            try {
                Map.Entry<String, String> h = BridgeConfig.parseHeader(raw);
                allHeaders.put(h.getKey(), h.getValue());
            } catch (IllegalArgumentException e) {
                // the message never contains the header value
                throw new CommandLine.ParameterException(commandSpec.commandLine(), e.getMessage());
            }
        }
        return new BridgeConfig(spec.trim(), baseUrl, allowWrites, nonNull(includes), nonNull(excludes), allHeaders,
                Duration.ofSeconds(timeoutSeconds), maxResponseBytes);
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? List.of() : list.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private void printTools(List<ToolDefinition> tools) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolDefinition t : tools) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", t.name());
            entry.put("method", t.method());
            entry.put("path", t.path());
            entry.put("description", t.description());
            entry.put("inputSchema", t.inputSchema());
            out.add(entry);
        }
        commandSpec.commandLine().getOut().println(
                new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(out));
        commandSpec.commandLine().getOut().flush();
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {BridgeServer.NAME + " " + BridgeServer.version()};
        }
    }
}
