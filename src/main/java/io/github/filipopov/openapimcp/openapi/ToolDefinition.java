package io.github.filipopov.openapimcp.openapi;

import java.util.List;
import java.util.Map;

/**
 * Everything needed to advertise one OpenAPI operation as an MCP tool and to execute it.
 *
 * @param name        MCP tool name (sanitized, unique)
 * @param title       short human-readable title (the operation summary), may be {@code null}
 * @param description description shown to the model
 * @param method      upper-case HTTP method
 * @param path        path template, e.g. {@code /pet/{petId}}
 * @param operationId original operationId, may be {@code null}
 * @param parameters  path/query/header/cookie parameter bindings
 * @param body        request body binding, or {@code null} if the operation takes no body
 * @param inputSchema JSON Schema (as a plain map) of the tool arguments
 */
public record ToolDefinition(
        String name,
        String title,
        String description,
        String method,
        String path,
        String operationId,
        List<ParameterBinding> parameters,
        BodyBinding body,
        Map<String, Object> inputSchema) {

    public ToolDefinition {
        parameters = List.copyOf(parameters);
    }

    public boolean readOnly() {
        return OperationFilter.isReadOnly(method);
    }

    /** Where an OpenAPI parameter goes in the HTTP request. */
    public enum Location {
        PATH, QUERY, HEADER, COOKIE
    }

    /**
     * Maps a tool argument to an HTTP parameter.
     *
     * @param argName  name of the property in the tool's input schema
     * @param wireName name of the parameter on the wire
     * @param location where the parameter is sent
     * @param style    OpenAPI serialization style ({@code form}, {@code simple}, {@code deepObject}, ...)
     * @param explode  OpenAPI explode flag
     * @param required whether the parameter is required
     */
    public record ParameterBinding(
            String argName, String wireName, Location location, String style, boolean explode, boolean required) {
    }

    /** How the request body argument is encoded. */
    public enum BodyEncoding {
        /** Serialized as JSON. */
        JSON,
        /** Object serialized as {@code application/x-www-form-urlencoded}. */
        FORM,
        /** Sent verbatim as a string (text/plain, XML, ...). */
        TEXT
    }

    /**
     * @param argName   name of the body property in the tool's input schema
     * @param mediaType media type sent as {@code Content-Type}
     * @param encoding  how the argument value is serialized
     * @param required  whether the body is required
     */
    public record BodyBinding(String argName, String mediaType, BodyEncoding encoding, boolean required) {
    }
}
