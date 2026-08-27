/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * Shared JSON wire contract for every version-one HTTP controller.
 */
public final class ApiHttpResponse
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ApiHttpResponse()
    {
    }

    /** Sends one explicitly selected resource value in the shared success envelope. */
    public static void sendData(HttpExchange exchange, int status, Object value) throws IOException
    {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.set("data", normalize(OBJECT_MAPPER.valueToTree(value)));
        sendJson(exchange, status, response);
    }

    /** Sends a complete endpoint-owned JSON document without the shared data envelope. */
    public static void sendDocument(HttpExchange exchange, int status, Object value) throws IOException
    {
        sendJson(exchange, status, normalize(OBJECT_MAPPER.valueToTree(value)));
    }

    /** Sends explicitly separated collection data and metadata; field names never determine envelope structure. */
    public static void sendDataWithMeta(HttpExchange exchange, int status, Object data, Object meta)
        throws IOException
    {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.set("data", normalize(OBJECT_MAPPER.valueToTree(data)));
        JsonNode normalizedMeta = normalize(OBJECT_MAPPER.valueToTree(meta));

        if(normalizedMeta != null && normalizedMeta.isObject() && !normalizedMeta.isEmpty())
        {
            response.set("meta", normalizedMeta);
        }

        sendJson(exchange, status, response);
    }

    public static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException
    {
        sendError(exchange, status, code, message, null);
    }

    public static void sendError(HttpExchange exchange, int status, String code, String message, String field)
        throws IOException
    {
        ObjectNode error = OBJECT_MAPPER.createObjectNode();
        error.put("code", code != null && !code.isBlank() ? code : "request_failed");
        error.put("message", message != null && !message.isBlank() ? message : "Request failed");
        error.put("status", status);

        if(field != null && !field.isBlank())
        {
            error.put("field", field);
        }

        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.set("error", error);
        sendJson(exchange, status, response);
    }

    /** Encodes a live-event or diagnostic-state payload with the same snake-case convention as JSON responses. */
    public static byte[] encodePayload(Object value) throws IOException
    {
        return OBJECT_MAPPER.writeValueAsBytes(normalize(OBJECT_MAPPER.valueToTree(value)));
    }

    /** Converts arbitrary maps and Java records to the v1 snake-case field convention. */
    public static JsonNode normalizePayload(Object value)
    {
        return normalize(OBJECT_MAPPER.valueToTree(value));
    }

    private static void sendJson(HttpExchange exchange, int status, JsonNode value) throws IOException
    {
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(value);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);

        try(OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(body);
        }
    }

    private static JsonNode normalize(JsonNode value)
    {
        if(value == null || value.isNull() || value.isValueNode())
        {
            return value;
        }
        else if(value.isArray())
        {
            ArrayNode normalized = OBJECT_MAPPER.createArrayNode();
            value.forEach(item -> normalized.add(normalize(item)));
            return normalized;
        }

        ObjectNode normalized = OBJECT_MAPPER.createObjectNode();
        Iterator<Map.Entry<String,JsonNode>> fields = value.fields();

        while(fields.hasNext())
        {
            Map.Entry<String,JsonNode> field = fields.next();
            normalized.set(snakeCase(field.getKey()), normalize(field.getValue()));
        }

        return normalized;
    }

    static String snakeCase(String value)
    {
        if(value == null || value.isEmpty())
        {
            return value;
        }

        StringBuilder normalized = new StringBuilder(value.length() + 8);

        for(int x = 0; x < value.length(); x++)
        {
            char character = value.charAt(x);

            if(Character.isUpperCase(character))
            {
                if(x > 0 && normalized.charAt(normalized.length() - 1) != '_')
                {
                    normalized.append('_');
                }

                normalized.append(Character.toLowerCase(character));
            }
            else
            {
                normalized.append(character);
            }
        }

        return normalized.toString();
    }
}
