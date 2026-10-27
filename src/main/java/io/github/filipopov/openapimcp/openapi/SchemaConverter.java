package io.github.filipopov.openapimcp.openapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Converts OpenAPI schema objects into self-contained JSON Schema maps suitable for an MCP tool's
 * {@code inputSchema}.
 *
 * <ul>
 *   <li>{@code $ref}s to {@code #/components/schemas/*} are inlined (many MCP clients do not follow refs).
 *       Recursive references are cut off and replaced by a plain {@code object} with an explanatory
 *       description; the expansion depth is bounded as well.</li>
 *   <li>OpenAPI-only keywords ({@code nullable}, {@code example}, {@code xml}, {@code discriminator},
 *       {@code externalDocs}, {@code x-*}) are dropped; {@code nullable: true} becomes a {@code "null"} type.</li>
 *   <li>Keywords whose values are data, not schemas ({@code enum}, {@code default}, {@code const}, ...), are copied
 *       verbatim and never searched for refs.</li>
 * </ul>
 */
public final class SchemaConverter {

    static final int MAX_DEPTH = 16;

    private static final String COMPONENT_PREFIX = "#/components/schemas/";
    private static final Set<String> DROPPED = Set.of("nullable", "example", "xml", "externalDocs", "discriminator",
            "exampleSetFlag", "$schema", "$id", "$anchor", "$comment");
    private static final Set<String> SCHEMA_MAPS = Set.of("properties", "patternProperties", "dependentSchemas");
    private static final Set<String> SCHEMA_ARRAYS = Set.of("allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> SCHEMA_VALUES = Set.of("items", "not", "additionalProperties",
            "unevaluatedProperties", "unevaluatedItems", "contains", "propertyNames", "if", "then", "else");

    private final ObjectMapper mapper;
    @SuppressWarnings("rawtypes") // swagger-models exposes components as raw Map<String, Schema>
    private final Map<String, Schema> components;
    private final Map<String, JsonNode> componentNodes = new HashMap<>();

    public SchemaConverter(OpenAPI openApi) {
        boolean is31 = openApi.getSpecVersion() == io.swagger.v3.oas.models.SpecVersion.V31;
        this.mapper = is31 ? Json31.mapper() : Json.mapper();
        this.components = openApi.getComponents() != null && openApi.getComponents().getSchemas() != null
                ? openApi.getComponents().getSchemas()
                : Map.of();
    }

    /** Converts a schema (possibly a bare {@code $ref}) to a JSON Schema map. {@code null} becomes {@code {}}. */
    public Map<String, Object> convert(Schema<?> schema) {
        if (schema == null) {
            return new LinkedHashMap<>();
        }
        JsonNode resolved = resolve(mapper.valueToTree(schema), new ArrayDeque<>(), 0);
        return mapper.convertValue(resolved, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private JsonNode resolve(JsonNode node, Deque<String> stack, int depth) {
        if (!node.isObject()) {
            return node; // boolean schemas (3.1) and anything unexpected pass through
        }
        if (node.hasNonNull("$ref")) {
            return resolveRef(node, stack, depth);
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (DROPPED.contains(key) || key.startsWith("x-")) {
                continue;
            }
            if (SCHEMA_MAPS.contains(key) && value.isObject()) {
                ObjectNode props = out.putObject(key);
                value.properties().forEach(p -> props.set(p.getKey(), resolve(p.getValue(), stack, depth + 1)));
            } else if (SCHEMA_ARRAYS.contains(key) && value.isArray()) {
                ArrayNode arr = out.putArray(key);
                value.forEach(v -> arr.add(resolve(v, stack, depth + 1)));
            } else if (SCHEMA_VALUES.contains(key)) {
                out.set(key, resolve(value, stack, depth + 1));
            } else {
                out.set(key, value);
            }
        }
        if (node.path("nullable").asBoolean(false) && out.path("type").isTextual()) {
            String type = out.get("type").asText();
            out.putArray("type").add(type).add("null");
        }
        return out;
    }

    private JsonNode resolveRef(JsonNode node, Deque<String> stack, int depth) {
        String ref = node.get("$ref").asText();
        String name = ref.startsWith(COMPONENT_PREFIX) ? ref.substring(COMPONENT_PREFIX.length()) : null;
        ObjectNode result;
        if (name == null || !components.containsKey(name)) {
            result = JsonNodeFactory.instance.objectNode();
            result.put("description", "Unresolved schema reference: " + ref);
        } else if (stack.contains(name) || depth >= MAX_DEPTH) {
            result = JsonNodeFactory.instance.objectNode();
            result.put("type", "object");
            result.put("description", "Recursive reference to '" + name + "' (not expanded further)");
        } else {
            stack.push(name);
            JsonNode target = componentNodes.computeIfAbsent(name, n -> mapper.valueToTree(components.get(n)));
            result = ((ObjectNode) resolve(target, stack, depth + 1)).deepCopy();
            stack.pop();
        }
        // OpenAPI 3.1 allows siblings next to $ref (e.g. a property-specific description); they win.
        ObjectNode siblings = ((ObjectNode) node).deepCopy();
        siblings.remove("$ref");
        if (!siblings.isEmpty()) {
            JsonNode resolvedSiblings = resolve(siblings, stack, depth + 1);
            resolvedSiblings.properties().forEach(f -> result.set(f.getKey(), f.getValue()));
        }
        return result;
    }
}
