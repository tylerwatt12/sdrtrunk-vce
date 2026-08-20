/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.settings.WebDisplayConfiguration;
import io.github.dsheirer.web.settings.WebDisplaySettingsService;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administrator-only HTTP adapter for receiver-wide browser display settings. */
public final class WebDisplaySettingsHttpController
{
    public static final String PATH = "/api/v1/admin/web-display";
    private static final int MAXIMUM_BODY_BYTES = 1024;
    private static final Logger mLog = LoggerFactory.getLogger(WebDisplaySettingsHttpController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final WebDisplaySettingsService mService;

    public WebDisplaySettingsHttpController(WebDisplaySettingsService service)
    {
        mService = Objects.requireNonNull(service, "Web display settings service cannot be null");
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebAccessHttpController.prepareSecurityHeaders(exchange);

        if(!PATH.equals(exchange.getRequestURI().getRawPath()) || exchange.getRequestURI().getRawQuery() != null)
        {
            ApiHttpResponse.sendError(exchange, 404, "not_found", "Not found");
            return;
        }

        try
        {
            switch(exchange.getRequestMethod())
            {
                case "GET" -> {
                    requireEmptyBody(exchange);
                    ApiHttpResponse.sendData(exchange, 200, mService.configuration());
                }
                case "PUT" -> {
                    Request request = read(exchange);

                    if(request.showEncryptionDetails() == null)
                    {
                        throw new RequestException(400, "invalid_request",
                            "show_encryption_details is required");
                    }

                    WebDisplayConfiguration updated;

                    try
                    {
                        updated = mService.setShowEncryptionDetails(request.showEncryptionDetails());
                    }
                    catch(IOException | SQLException exception)
                    {
                        mLog.error("Unable to save web display settings", exception);
                        ApiHttpResponse.sendError(exchange, 500, "settings_save_failed",
                            "Web display settings could not be saved");
                        return;
                    }

                    ApiHttpResponse.sendData(exchange, 200, updated);
                }
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, PUT");
                    ApiHttpResponse.sendError(exchange, 405, "method_not_allowed", "Method not allowed");
                }
            }
        }
        catch(RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
    }

    private static Request read(HttpExchange exchange) throws IOException, RequestException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        if(contentType == null || !"application/json".equals(contentType.toLowerCase(Locale.ROOT)
            .split(";", 2)[0].strip()))
        {
            throw new RequestException(415, "invalid_content_type", "Content-Type must be application/json");
        }

        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");

        if(contentLength != null)
        {
            try
            {
                long parsed = Long.parseLong(contentLength);

                if(parsed < 0)
                {
                    throw new RequestException(400, "invalid_request", "Content-Length is invalid");
                }

                if(parsed > MAXIMUM_BODY_BYTES)
                {
                    throw new RequestException(413, "request_too_large", "Request body is too large");
                }
            }
            catch(NumberFormatException exception)
            {
                throw new RequestException(400, "invalid_request", "Content-Length is invalid");
            }
        }

        byte[] body = ApiRequestDecoder.readBody(exchange, MAXIMUM_BODY_BYTES);

        try
        {
            if(body.length == 0)
            {
                throw new RequestException(400, "invalid_request", "Request body is required");
            }

            if(body.length > MAXIMUM_BODY_BYTES)
            {
                throw new RequestException(413, "request_too_large", "Request body is too large");
            }

            try
            {
                return OBJECT_MAPPER.readValue(body, Request.class);
            }
            catch(IOException exception)
            {
                throw new RequestException(400, "invalid_request", "Request body must be valid JSON");
            }
        }
        finally
        {
            Arrays.fill(body, (byte)0);
        }
    }

    private static void requireEmptyBody(HttpExchange exchange) throws RequestException
    {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");

        if(contentLength != null && !"0".equals(contentLength) ||
            exchange.getRequestHeaders().containsKey("Transfer-Encoding"))
        {
            throw new RequestException(400, "invalid_request", "GET requests cannot include a body");
        }
    }

    private static final class RequestException extends Exception
    {
        private final int mStatus;
        private final String mCode;

        private RequestException(int status, String code, String message)
        {
            super(message);
            mStatus = status;
            mCode = code;
        }

        private int status()
        {
            return mStatus;
        }

        private String code()
        {
            return mCode;
        }
    }

    private record Request(Boolean showEncryptionDetails)
    {
    }
}
