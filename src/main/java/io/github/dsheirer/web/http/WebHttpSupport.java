/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.Pbkdf2PasswordHasher;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded request parsing and response helpers shared by the small web-access controllers. */
final class WebHttpSupport
{
    private static final int MAXIMUM_JSON_BODY_BYTES = 16 * 1024;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private WebHttpSupport()
    {
    }

    static JsonNode readJsonObject(HttpExchange exchange, Set<String> allowedFields)
        throws IOException, RequestException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if(contentType == null || !contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].strip()
            .equals("application/json"))
        {
            throw new RequestException(415, "invalid_content_type", "Content-Type must be application/json");
        }

        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if(contentLength != null)
        {
            try
            {
                long length = Long.parseLong(contentLength);
                if(length < 0)
                {
                    throw new RequestException(400, "invalid_request", "Content-Length is invalid");
                }
                if(length > MAXIMUM_JSON_BODY_BYTES)
                {
                    throw new RequestException(413, "request_too_large", "The JSON request is too large");
                }
            }
            catch(NumberFormatException exception)
            {
                throw new RequestException(400, "invalid_request", "Content-Length is invalid");
            }
        }

        byte[] bytes = ApiRequestDecoder.readBody(exchange, MAXIMUM_JSON_BODY_BYTES);
        try
        {
            if(bytes.length == 0)
            {
                throw new RequestException(400, "invalid_request", "A JSON request body is required");
            }
            if(bytes.length > MAXIMUM_JSON_BODY_BYTES)
            {
                throw new RequestException(413, "request_too_large", "The JSON request is too large");
            }

            JsonNode value;
            try
            {
                value = OBJECT_MAPPER.readTree(bytes);
            }
            catch(IOException exception)
            {
                throw new RequestException(400, "invalid_request", "The JSON body is invalid");
            }
            if(value == null || !value.isObject())
            {
                throw new RequestException(400, "invalid_request", "The JSON body must be an object");
            }

            var names = value.fieldNames();
            while(names.hasNext())
            {
                if(!allowedFields.contains(names.next()))
                {
                    throw new RequestException(400, "invalid_request", "The JSON body contains an unknown field");
                }
            }
            return value;
        }
        finally
        {
            Arrays.fill(bytes, (byte)0);
        }
    }

    static String requiredText(JsonNode request, String field, int maximumCharacters) throws RequestException
    {
        JsonNode value = request.get(field);
        if(value == null || !value.isTextual() || value.textValue().isBlank() ||
            value.textValue().length() > maximumCharacters)
        {
            throw new RequestException(400, "invalid_request", field + " is invalid");
        }
        return value.textValue();
    }

    static char[] requiredPassword(JsonNode request) throws RequestException
    {
        return requiredText(request, "password", Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS).toCharArray();
    }

    static AccessTier requiredAccountTier(JsonNode request) throws RequestException
    {
        AccessTier tier = requiredTier(request, "tier");
        if(!tier.isAccountTier())
        {
            throw new RequestException(400, "invalid_request", "A user tier must be user or admin");
        }
        return tier;
    }

    static AccessTier requiredTier(JsonNode request, String field) throws RequestException
    {
        return switch(requiredText(request, field, 16))
        {
            case "public" -> AccessTier.PUBLIC;
            case "user" -> AccessTier.USER;
            case "admin" -> AccessTier.ADMIN;
            default -> throw new RequestException(400, "invalid_request", field + " is invalid");
        };
    }

    static String tierName(AccessTier tier)
    {
        return switch(Objects.requireNonNull(tier, "Access tier cannot be null"))
        {
            case PUBLIC -> "public";
            case USER -> "user";
            case ADMIN -> "admin";
        };
    }

    static Map<String,Object> accountResponse(WebAccessAccount account)
    {
        return Map.of(
            "id", account.id(),
            "username", account.username(),
            "tier", tierName(account.tier()),
            "passwordChangedAtEpochMillis", account.passwordChangedAtEpochMillis(),
            "authRevision", account.authRevision(),
            "primary", account.primaryAdmin());
    }

    static Map<String,Object> policyResponse(WebAccessService.CapabilityPolicy policy)
    {
        return Map.of(
            "id", policy.id(),
            "displayName", policy.displayName(),
            "requiredTier", tierName(policy.requiredTier()),
            "defaultTier", tierName(policy.defaultTier()),
            "configurable", policy.configurable());
    }

    static boolean hasExactPath(HttpExchange exchange, String expected)
    {
        return expected.equals(exchange.getRequestURI().getRawPath());
    }

    static boolean hasRequestBody(HttpExchange exchange)
    {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        return contentLength != null && !"0".equals(contentLength) ||
            exchange.getRequestHeaders().getFirst("Transfer-Encoding") != null;
    }

    static boolean requireNoQuery(HttpExchange exchange) throws IOException
    {
        if(exchange.getRequestURI().getRawQuery() == null)
        {
            return true;
        }
        sendError(exchange, 400, "unknown_parameter", "Query parameters are not supported", "query");
        return false;
    }

    static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException
    {
        exchange.getResponseHeaders().set("Allow", allow);
        sendError(exchange, 405, "method_not_allowed", "Method not allowed");
    }

    static void notFound(HttpExchange exchange) throws IOException
    {
        sendError(exchange, 404, "not_found", "Not found");
    }

    static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Vary", "Cookie");
        ApiHttpResponse.sendError(exchange, status, code, message);
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message, String field)
        throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Vary", "Cookie");
        ApiHttpResponse.sendError(exchange, status, code, message, field);
    }

    static void sendData(HttpExchange exchange, int status, Object value) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Vary", "Cookie");
        ApiHttpResponse.sendData(exchange, status, value);
    }

    static String safeMessage(RuntimeException exception, String fallback)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() || message.length() > 240 ? fallback : message;
    }

    static final class RequestException extends Exception
    {
        private final int mStatus;
        private final String mCode;

        RequestException(int status, String code, String message)
        {
            super(message);
            mStatus = status;
            mCode = code;
        }

        int status()
        {
            return mStatus;
        }

        String code()
        {
            return mCode;
        }
    }
}
