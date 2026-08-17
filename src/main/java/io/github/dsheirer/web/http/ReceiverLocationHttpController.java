/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.preference.location.ReceiverLocation;
import io.github.dsheirer.stats.StatsApiV1;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Administrator-only adapter for the receiver coordinates used by location-aware integrations. */
public final class ReceiverLocationHttpController
{
    public static final String PATH = StatsApiV1.RECEIVER_LOCATION;
    private static final int MAXIMUM_BODY_BYTES = 1024;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final Supplier<Optional<ReceiverLocation>> mLocation;
    private final Consumer<Optional<ReceiverLocation>> mUpdate;

    public ReceiverLocationHttpController(Supplier<Optional<ReceiverLocation>> location,
                                          Consumer<Optional<ReceiverLocation>> update)
    {
        mLocation = Objects.requireNonNull(location);
        mUpdate = Objects.requireNonNull(update);
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
                    requireEmptyBody(exchange, "GET");
                    ApiHttpResponse.sendData(exchange, 200, response());
                }
                case "PUT" -> {
                    Request request = read(exchange);
                    mUpdate.accept(Optional.of(new ReceiverLocation(required(request.latitude(), "latitude"),
                        required(request.longitude(), "longitude"))));
                    ApiHttpResponse.sendData(exchange, 200, response());
                }
                case "DELETE" -> {
                    requireEmptyBody(exchange, "DELETE");
                    mUpdate.accept(Optional.empty());
                    ApiHttpResponse.sendData(exchange, 200, response());
                }
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, PUT, DELETE");
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
    }

    private Response response()
    {
        Optional<ReceiverLocation> configured = mLocation.get();
        return configured.map(location -> new Response(true, location.latitude(), location.longitude()))
            .orElseGet(() -> new Response(false, null, null));
    }

    private static double required(Double value, String field)
    {
        if(value == null || !Double.isFinite(value))
        {
            throw new IllegalArgumentException(field + " is required and must be finite");
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

    private static void requireEmptyBody(HttpExchange exchange, String method) throws RequestException
    {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");

        if(contentLength != null && !"0".equals(contentLength) ||
            exchange.getRequestHeaders().containsKey("Transfer-Encoding"))
        {
            throw new RequestException(400, "invalid_request", method + " requests cannot include a body");
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

    private record Request(Double latitude, Double longitude)
    {
    }

    private record Response(boolean configured, Double latitude, Double longitude)
    {
    }
}
