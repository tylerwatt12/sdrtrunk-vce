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
import io.github.dsheirer.web.settings.WebDisplaySettings;
import io.github.dsheirer.web.settings.WebDisplaySettingsService;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.prefs.BackingStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administrator-only HTTP adapter for receiver-wide browser display settings. */
public final class WebDisplaySettingsHttpController
{
    public static final String PATH = "/api/v1/admin/web-display";
    public static final String LIVE_PATH = "/api/v1/live/settings";
    private static final int MAXIMUM_BODY_BYTES = 1024;
    private static final Logger mLog = LoggerFactory.getLogger(WebDisplaySettingsHttpController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
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
                    ApiHttpResponse.sendData(exchange, 200, mService.settings());
                }
                case "PUT" -> {
                    Request request = read(exchange);

                    if(request == null || request.isEmpty())
                    {
                        throw new RequestException(400, "invalid_request",
                            "At least one web display setting is required");
                    }

                    WebDisplaySettings updated;

                    try
                    {
                        updated = mService.update(request::applyTo);
                    }
                    catch(IOException | SQLException | BackingStoreException exception)
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
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", exception.getMessage());
        }
    }

    /** Serves the same non-sensitive receiver-wide presentation policy to viewers authorized for Live. */
    public void handleLive(HttpExchange exchange) throws IOException
    {
        WebAccessHttpController.prepareSecurityHeaders(exchange);

        if(!LIVE_PATH.equals(exchange.getRequestURI().getRawPath()) || exchange.getRequestURI().getRawQuery() != null)
        {
            ApiHttpResponse.sendError(exchange, 404, "not_found", "Not found");
            return;
        }

        if(!"GET".equals(exchange.getRequestMethod()))
        {
            exchange.getResponseHeaders().set("Allow", "GET");
            ApiHttpResponse.sendError(exchange, 405, "method_not_allowed", "Method not allowed");
            return;
        }

        try
        {
            requireEmptyBody(exchange);
            ApiHttpResponse.sendData(exchange, 200, mService.settings());
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

    private record Request(Boolean showEncryptionDetails, Boolean retainIdleCallDetails,
                           Boolean showControlDecodeQuality, Boolean showVoiceDecodeQuality,
                           Boolean clearVoiceDecodeQualityOnCallEnd, String decodeQualityDisplayMode,
                           Integer trafficGrantAgeOutMilliseconds, Integer liveDetailMatchingRowLimit)
    {
        private boolean isEmpty()
        {
            return showEncryptionDetails == null && retainIdleCallDetails == null &&
                showControlDecodeQuality == null && showVoiceDecodeQuality == null &&
                clearVoiceDecodeQualityOnCallEnd == null && decodeQualityDisplayMode == null &&
                trafficGrantAgeOutMilliseconds == null && liveDetailMatchingRowLimit == null;
        }

        private WebDisplaySettings applyTo(WebDisplaySettings current)
        {
            return new WebDisplaySettings(WebDisplaySettings.CURRENT_FORMAT_VERSION,
                showEncryptionDetails != null ? showEncryptionDetails : current.showEncryptionDetails(),
                retainIdleCallDetails != null ? retainIdleCallDetails : current.retainIdleCallDetails(),
                showControlDecodeQuality != null ? showControlDecodeQuality : current.showControlDecodeQuality(),
                showVoiceDecodeQuality != null ? showVoiceDecodeQuality : current.showVoiceDecodeQuality(),
                clearVoiceDecodeQualityOnCallEnd != null ? clearVoiceDecodeQualityOnCallEnd :
                    current.clearVoiceDecodeQualityOnCallEnd(),
                decodeQualityDisplayMode != null ? decodeQualityDisplayMode : current.decodeQualityDisplayMode(),
                trafficGrantAgeOutMilliseconds != null ? trafficGrantAgeOutMilliseconds :
                    current.trafficGrantAgeOutMilliseconds(),
                liveDetailMatchingRowLimit != null ? liveDetailMatchingRowLimit :
                    current.liveDetailMatchingRowLimit());
        }
    }
}
