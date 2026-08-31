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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideProfile;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administrator-only replacement endpoint for the complete set of manual P25 bandplan overrides. */
public final class P25BandplanOverrideHttpController
{
    public static final String PATH = "/api/v1/admin/p25-bandplan-overrides";
    private static final int MAXIMUM_BODY_BYTES = 256 * 1024;
    private static final Logger mLog = LoggerFactory.getLogger(P25BandplanOverrideHttpController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final P25BandplanOverrideRegistry mRegistry;

    public P25BandplanOverrideHttpController(P25BandplanOverrideRegistry registry)
    {
        mRegistry = Objects.requireNonNull(registry, "P25 bandplan override registry cannot be null");
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);

        if(!PATH.equals(exchange.getRequestURI().getRawPath()))
        {
            ApiHttpResponse.sendError(exchange, 404, "not_found", "Not found");
            return;
        }
        if(exchange.getRequestURI().getRawQuery() != null)
        {
            ApiHttpResponse.sendError(exchange, 400, "unknown_parameter", "Query parameters are not supported");
            return;
        }

        try
        {
            switch(exchange.getRequestMethod())
            {
                case "GET" -> {
                    requireEmptyBody(exchange);
                    ApiHttpResponse.sendDocument(exchange, 200, new Document(mRegistry.getProfiles()));
                }
                case "PUT" -> {
                    Document document = read(exchange);
                    try
                    {
                        mRegistry.setProfiles(document.profiles());
                        ApiHttpResponse.sendDocument(exchange, 200, new Document(mRegistry.getProfiles()));
                    }
                    catch(IOException | SQLException exception)
                    {
                        mLog.error("Unable to save P25 bandplan overrides", exception);
                        ApiHttpResponse.sendError(exchange, 500, "p25_bandplan_overrides_save_failed",
                            "P25 bandplan overrides could not be saved");
                    }
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
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 422, "invalid_p25_bandplan_overrides", exception.getMessage());
        }
    }

    private static Document read(HttpExchange exchange) throws IOException, RequestException
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
                long length = Long.parseLong(contentLength);

                if(length < 0)
                {
                    throw new RequestException(400, "invalid_request", "Content-Length is invalid");
                }
                if(length > MAXIMUM_BODY_BYTES)
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

            try
            {
                Document document = MAPPER.readValue(body, Document.class);

                if(document == null || document.profiles() == null)
                {
                    throw new RequestException(422, "invalid_p25_bandplan_overrides",
                        "P25 bandplan overrides are invalid");
                }

                return document;
            }
            catch(IOException exception)
            {
                throw new RequestException(422, "invalid_p25_bandplan_overrides",
                    "P25 bandplan overrides are invalid");
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

    public record Document(List<P25BandplanOverrideProfile> profiles)
    {
        public Document
        {
            profiles = profiles != null ? List.copyOf(profiles) : null;
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
}
