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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.stats.WebCallConfiguration;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administrator-only adapter for the small set of useful browser-audio capacity controls. */
public final class WebCallConfigurationHttpController
{
    private static final Logger mLog = LoggerFactory.getLogger(WebCallConfigurationHttpController.class);
    public static final String PATH = "/api/v1/admin/web-audio";
    private static final int MAXIMUM_BODY_BYTES = 4096;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final Supplier<WebCallConfiguration> mConfiguration;
    private final Consumer<WebCallConfiguration> mUpdate;
    private final Supplier<Map<String,Object>> mStatus;

    public WebCallConfigurationHttpController(Supplier<WebCallConfiguration> configuration,
                                              Consumer<WebCallConfiguration> update,
                                              Supplier<Map<String,Object>> status)
    {
        mConfiguration = Objects.requireNonNull(configuration);
        mUpdate = Objects.requireNonNull(update);
        mStatus = Objects.requireNonNull(status);
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
                    ApiHttpResponse.sendData(exchange, 200, response());
                }
                case "PUT" -> {
                    Request request = read(exchange);
                    WebCallConfiguration configuration = validated(request);
                    mUpdate.accept(configuration);
                    ApiHttpResponse.sendData(exchange, 200, response());
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
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", exception.getMessage());
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Unable to load or persist web-audio settings", exception);
            ApiHttpResponse.sendError(exchange, 503, "storage_unavailable",
                "Web-audio settings are temporarily unavailable");
        }
    }

    private Map<String,Object> response()
    {
        return Map.of("configuration", mConfiguration.get(), "limits", Map.of(
            "maximumListeners", range(WebCallConfiguration.MINIMUM_LISTENERS,
                WebCallConfiguration.MAXIMUM_LISTENERS),
            "maximumSelectedScanLists", range(WebCallConfiguration.MINIMUM_SELECTED_SCAN_LISTS,
                WebCallConfiguration.MAXIMUM_SELECTED_SCAN_LISTS),
            "waitingCallsPerListener", range(WebCallConfiguration.MINIMUM_WAITING_CALLS_PER_LISTENER,
                WebCallConfiguration.MAXIMUM_WAITING_CALLS_PER_LISTENER),
            "maximumCachedCalls", range(WebCallConfiguration.MINIMUM_CACHED_CALLS,
                WebCallConfiguration.MAXIMUM_CACHED_CALLS),
            "maximum_cached_audio_mib", range(WebCallConfiguration.MINIMUM_CACHED_AUDIO_MIB,
                WebCallConfiguration.MAXIMUM_CACHED_AUDIO_MIB)), "status", mStatus.get());
    }

    private static Map<String,Integer> range(int minimum, int maximum)
    {
        return Map.of("minimum", minimum, "maximum", maximum);
    }

    private static WebCallConfiguration validated(Request request)
    {
        if(request == null)
        {
            throw new IllegalArgumentException("Request body is required");
        }

        int listeners = bounded(request.maximumListeners(), "maximum_listeners",
            WebCallConfiguration.MINIMUM_LISTENERS, WebCallConfiguration.MAXIMUM_LISTENERS);
        int selected = bounded(request.maximumSelectedScanLists(), "maximum_selected_scan_lists",
            WebCallConfiguration.MINIMUM_SELECTED_SCAN_LISTS,
            WebCallConfiguration.MAXIMUM_SELECTED_SCAN_LISTS);
        int queue = bounded(request.waitingCallsPerListener(), "waiting_calls_per_listener",
            WebCallConfiguration.MINIMUM_WAITING_CALLS_PER_LISTENER,
            WebCallConfiguration.MAXIMUM_WAITING_CALLS_PER_LISTENER);
        int cached = bounded(request.maximumCachedCalls(), "maximum_cached_calls",
            WebCallConfiguration.MINIMUM_CACHED_CALLS, WebCallConfiguration.MAXIMUM_CACHED_CALLS);
        int audio = bounded(request.maximumCachedAudioMiB(), "maximum_cached_audio_mib",
            WebCallConfiguration.MINIMUM_CACHED_AUDIO_MIB,
            WebCallConfiguration.MAXIMUM_CACHED_AUDIO_MIB);
        return new WebCallConfiguration(listeners, selected, queue, cached, audio);
    }

    private static int bounded(Integer value, String field, int minimum, int maximum)
    {
        if(value == null || value < minimum || value > maximum)
        {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }

        return value;
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

    private record Request(Integer maximumListeners, Integer maximumSelectedScanLists,
                           Integer waitingCallsPerListener, Integer maximumCachedCalls,
                           @JsonProperty("maximum_cached_audio_mib") Integer maximumCachedAudioMiB)
    {
    }
}
