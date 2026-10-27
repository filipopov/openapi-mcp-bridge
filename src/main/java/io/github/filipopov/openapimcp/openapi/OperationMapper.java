package io.github.filipopov.openapimcp.openapi;

import io.github.filipopov.openapimcp.openapi.ToolDefinition.BodyBinding;
import io.github.filipopov.openapimcp.openapi.ToolDefinition.BodyEncoding;
import io.github.filipopov.openapimcp.openapi.ToolDefinition.Location;
import io.github.filipopov.openapimcp.openapi.ToolDefinition.ParameterBinding;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps the operations of an {@link OpenAPI} document to {@link ToolDefinition}s.
 *
 * <p>The tool input schema is a single object that merges:
 * <ul>
 *   <li>every path, query, header and cookie parameter as a top-level property (path-level parameters are
 *       inherited, operation-level ones override them);</li>
 *   <li>the request body (if any) under a {@code body} property.</li>
 * </ul>
 * If two parameters share a name (e.g. {@code id} in path and query) the later one is prefixed with its location
 * ({@code query_id}); if a parameter is literally called {@code body}, the request body becomes
 * {@code requestBody}.
 */
public final class OperationMapper {

    /** Header parameters that OpenAPI says must be ignored (they are controlled elsewhere). */
    private static final Set<String> IGNORED_HEADERS = Set.of("accept", "content-type", "authorization");

    private final OpenAPI openApi;
    private final OperationFilter filter;
    private final SchemaConverter schemas;

    public OperationMapper(OpenAPI openApi, OperationFilter filter) {
        this.openApi = openApi;
        this.filter = filter;
        this.schemas = new SchemaConverter(openApi);
    }

    /** Result of mapping: the exposed tools and the operations that were skipped, with the reason. */
    public record Result(List<ToolDefinition> tools, List<Skipped> skipped) {
    }

    /** An operation that did not become a tool. */
    public record Skipped(String method, String path, String operationId, String reason) {
    }

    public Result map() {
        List<Draft> drafts = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        if (openApi.getPaths() != null) {
            openApi.getPaths().forEach((path, item) -> {
                if (item == null) {
                    return;
                }
                item.readOperationsMap().forEach((httpMethod, op) -> {
                    String method = httpMethod.name();
                    String opId = op.getOperationId();
                    List<String> tags = op.getTags() != null ? op.getTags() : List.of();
                    String reason = filter.rejectionReason(method, path, opId, tags);
                    if (reason == null) {
                        reason = Optional.ofNullable(op.getRequestBody())
                                .map(this::deref)
                                .map(OperationMapper::unsupportedBodyReason)
                                .orElse(null);
                    }
                    if (reason != null) {
                        skipped.add(new Skipped(method, path, opId, reason));
                    } else {
                        drafts.add(new Draft(method, path, item, op));
                    }
                });
            });
        }

        List<String> names = ToolNames.deduplicate(drafts.stream().map(Draft::candidateName).toList());
        List<ToolDefinition> tools = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            tools.add(build(names.get(i), drafts.get(i)));
        }
        return new Result(List.copyOf(tools), List.copyOf(skipped));
    }

    private record Draft(String method, String path, PathItem item, Operation op) {
        String candidateName() {
            String fromId = ToolNames.sanitize(op.getOperationId());
            return fromId.isEmpty() ? ToolNames.fromMethodAndPath(method, path) : fromId;
        }
    }

    private ToolDefinition build(String name, Draft d) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<ParameterBinding> bindings = new ArrayList<>();
        Set<String> usedArgNames = new HashSet<>();

        for (Parameter p : effectiveParameters(d.item(), d.op())) {
            Location location = Location.valueOf(p.getIn().toUpperCase(Locale.ROOT));
            String argName = uniqueArgName(p.getName(), location.name().toLowerCase(Locale.ROOT) + "_" + p.getName(),
                    usedArgNames);
            boolean isRequired = location == Location.PATH || Boolean.TRUE.equals(p.getRequired());

            Map<String, Object> schema = schemas.convert(parameterSchema(p));
            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                schema.put("description", p.getDescription().strip());
            }
            if (!argName.equals(p.getName())) {
                schema.put("description", (schema.containsKey("description") ? schema.get("description") + " " : "")
                        + "(" + location.name().toLowerCase(Locale.ROOT) + " parameter '" + p.getName() + "')");
            }
            if (Boolean.TRUE.equals(p.getDeprecated())) {
                schema.put("deprecated", true);
            }
            properties.put(argName, schema);
            if (isRequired) {
                required.add(argName);
            }
            bindings.add(new ParameterBinding(argName, p.getName(), location, style(p, location),
                    explode(p, location), isRequired));
        }

        BodyBinding body = null;
        RequestBody requestBody = deref(d.op().getRequestBody());
        if (requestBody != null && requestBody.getContent() != null && !requestBody.getContent().isEmpty()) {
            Map.Entry<String, MediaType> media = pickMediaType(requestBody.getContent());
            BodyEncoding encoding = encodingFor(media.getKey());
            String argName = uniqueArgName("body", "requestBody", usedArgNames);
            Map<String, Object> schema = encoding == BodyEncoding.TEXT
                    ? new LinkedHashMap<>(Map.of("type", "string"))
                    : schemas.convert(media.getValue() != null ? media.getValue().getSchema() : null);
            String bodyDescription = Optional.ofNullable(requestBody.getDescription()).map(String::strip)
                    .filter(s -> !s.isEmpty()).orElse("Request body");
            schema.putIfAbsent("description", bodyDescription + " (" + media.getKey() + ")");
            properties.put(argName, schema);
            boolean bodyRequired = Boolean.TRUE.equals(requestBody.getRequired());
            if (bodyRequired) {
                required.add(argName);
            }
            body = new BodyBinding(argName, media.getKey(), encoding, bodyRequired);
        }

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) {
            inputSchema.put("required", required);
        }
        inputSchema.put("additionalProperties", false);

        return new ToolDefinition(name, blankToNull(d.op().getSummary()), description(d), d.method(), d.path(),
                d.op().getOperationId(), bindings, body, inputSchema);
    }

    private List<Parameter> effectiveParameters(PathItem item, Operation op) {
        Map<String, Parameter> byKey = new LinkedHashMap<>();
        for (List<Parameter> list : List.of(
                Optional.ofNullable(item.getParameters()).orElse(List.of()),
                Optional.ofNullable(op.getParameters()).orElse(List.of()))) {
            for (Parameter raw : list) {
                Parameter p = deref(raw);
                if (p == null || p.getName() == null || p.getIn() == null) {
                    continue;
                }
                if ("header".equalsIgnoreCase(p.getIn()) && IGNORED_HEADERS.contains(p.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                byKey.put(p.getIn().toLowerCase(Locale.ROOT) + ":" + p.getName(), p);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static Schema<?> parameterSchema(Parameter p) {
        if (p.getSchema() != null) {
            return p.getSchema();
        }
        if (p.getContent() != null && !p.getContent().isEmpty()) {
            MediaType mt = p.getContent().values().iterator().next();
            return mt != null ? mt.getSchema() : null;
        }
        return null;
    }

    private static String style(Parameter p, Location location) {
        if (p.getStyle() != null) {
            return p.getStyle().toString();
        }
        return switch (location) {
            case QUERY, COOKIE -> "form";
            case PATH, HEADER -> "simple";
        };
    }

    private static boolean explode(Parameter p, Location location) {
        if (p.getExplode() != null) {
            return p.getExplode();
        }
        return "form".equals(style(p, location));
    }

    private static String uniqueArgName(String preferred, String alternative, Set<String> used) {
        String name = used.contains(preferred) ? alternative : preferred;
        String base = name;
        for (int i = 2; used.contains(name); i++) {
            name = base + "_" + i;
        }
        used.add(name);
        return name;
    }

    private static String description(Draft d) {
        List<String> parts = new ArrayList<>();
        String summary = blankToNull(d.op().getSummary());
        String details = blankToNull(d.op().getDescription());
        if (summary != null) {
            parts.add(summary.strip());
        }
        if (details != null && (summary == null || !details.strip().equals(summary.strip()))) {
            parts.add(details.strip());
        }
        parts.add("HTTP " + d.method() + " " + d.path());
        String text = String.join("\n\n", parts);
        return Boolean.TRUE.equals(d.op().getDeprecated()) ? "[DEPRECATED] " + text : text;
    }

    // ---- request bodies ------------------------------------------------------------------------------------

    private static Map.Entry<String, MediaType> pickMediaType(Content content) {
        for (String key : content.keySet()) {
            if (isJson(key)) {
                return Map.entry(key, nullSafe(content.get(key)));
            }
        }
        for (String key : content.keySet()) {
            if (encodingFor(key) != null) {
                return Map.entry(key, nullSafe(content.get(key)));
            }
        }
        String first = content.keySet().iterator().next();
        return Map.entry(first, nullSafe(content.get(first)));
    }

    private static MediaType nullSafe(MediaType mt) {
        return mt != null ? mt : new MediaType();
    }

    private static String unsupportedBodyReason(RequestBody body) {
        if (body.getContent() == null || body.getContent().isEmpty()) {
            return null;
        }
        boolean supported = body.getContent().keySet().stream().anyMatch(k -> encodingFor(k) != null);
        return supported ? null
                : "request body media type(s) " + body.getContent().keySet() + " not supported (multipart/binary)";
    }

    static BodyEncoding encodingFor(String mediaType) {
        String mt = mediaType.toLowerCase(Locale.ROOT);
        if (isJson(mt)) {
            return BodyEncoding.JSON;
        }
        if (mt.startsWith("application/x-www-form-urlencoded")) {
            return BodyEncoding.FORM;
        }
        if (mt.startsWith("text/") || mt.contains("xml") || mt.contains("yaml")) {
            return BodyEncoding.TEXT;
        }
        return null;
    }

    private static boolean isJson(String mediaType) {
        String mt = mediaType.toLowerCase(Locale.ROOT);
        return mt.startsWith("application/json") || mt.contains("+json") || mt.equals("*/*");
    }

    // ---- $ref handling for parameters and request bodies ---------------------------------------------------

    private Parameter deref(Parameter p) {
        Set<String> seen = new HashSet<>();
        while (p != null && p.get$ref() != null) {
            String name = refName(p.get$ref(), "#/components/parameters/");
            if (name == null || !seen.add(name) || openApi.getComponents() == null
                    || openApi.getComponents().getParameters() == null) {
                return null;
            }
            p = openApi.getComponents().getParameters().get(name);
        }
        return p;
    }

    private RequestBody deref(RequestBody b) {
        Set<String> seen = new HashSet<>();
        while (b != null && b.get$ref() != null) {
            String name = refName(b.get$ref(), "#/components/requestBodies/");
            if (name == null || !seen.add(name) || openApi.getComponents() == null
                    || openApi.getComponents().getRequestBodies() == null) {
                return null;
            }
            b = openApi.getComponents().getRequestBodies().get(name);
        }
        return b;
    }

    private static String refName(String ref, String prefix) {
        return ref.startsWith(prefix) ? ref.substring(prefix.length()) : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
