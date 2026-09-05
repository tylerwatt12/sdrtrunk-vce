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
import io.github.dsheirer.web.settings.SpectrumSnapSettingsService;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Read endpoint for the active spectrum-snap catalog and administrator endpoint for selecting its country. */
public final class SpectrumSnapPresetHttpController
{
    public static final String PATH = "/api/v1/spectrum-snap-presets";
    public static final String ADMIN_PATH = "/api/v1/admin/spectrum-snap-presets";
    private static final int MAXIMUM_BODY_BYTES = 128;
    private static final Pattern ETAG = Pattern.compile("\"([1-9][0-9]*)\"");
    private static final Logger mLog = LoggerFactory.getLogger(SpectrumSnapPresetHttpController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final SpectrumSnapSettingsService mSettings;

    public SpectrumSnapPresetHttpController(SpectrumSnapSettingsService settings)
    {
        mSettings = Objects.requireNonNull(settings);
    }

    public void handleRead(HttpExchange exchange) throws IOException
    {
        handle(exchange, false);
    }

    public void handleAdmin(HttpExchange exchange) throws IOException
    {
        handle(exchange, true);
    }

    private void handle(HttpExchange exchange, boolean administrator) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        String expectedPath = administrator ? ADMIN_PATH : PATH;
        if(!expectedPath.equals(exchange.getRequestURI().getRawPath()))
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
                    send(exchange, 200, mSettings.snapshot());
                }
                case "PUT" -> {
                    if(!administrator)
                    {
                        exchange.getResponseHeaders().set("Allow", "GET");
                        ApiHttpResponse.sendError(exchange, 405, "method_not_allowed", "Method not allowed");
                        return;
                    }
                    long revision = requireRevision(exchange);
                    Selection selection = read(exchange);
                    SpectrumSnapSettingsService.ReplaceResult result =
                        mSettings.replace(revision, selection.countryCode());
                    send(exchange, result.updated() ? 200 : 409, result.snapshot());
                }
                default -> {
                    exchange.getResponseHeaders().set("Allow", administrator ? "GET, PUT" : "GET");
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
            ApiHttpResponse.sendError(exchange, 422, "invalid_spectrum_snap_settings", exception.getMessage());
        }
        catch(SQLException | IOException exception)
        {
            mLog.error("Unable to access spectrum-snap settings", exception);
            ApiHttpResponse.sendError(exchange, 500, "spectrum_snap_settings_failed",
                "Spectrum-snap settings could not be accessed");
        }
    }

    private static void send(HttpExchange exchange, int status, SpectrumSnapSettingsService.Snapshot snapshot)
        throws IOException
    {
        exchange.getResponseHeaders().set("ETag", '"' + Long.toString(snapshot.revision()) + '"');
        ApiHttpResponse.sendDocument(exchange, status, snapshot);
    }

    private static Selection read(HttpExchange exchange) throws IOException, RequestException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if(contentType == null || !"application/json".equals(contentType.toLowerCase(Locale.ROOT)
            .split(";", 2)[0].strip()))
        {
            throw new RequestException(415, "invalid_content_type", "Content-Type must be application/json");
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
                Selection selection = MAPPER.readValue(body, Selection.class);
                if(selection == null || selection.countryCode() == null)
                {
                    throw new RequestException(422, "invalid_spectrum_snap_settings",
                        "Spectrum-snap settings are invalid");
                }
                return selection;
            }
            catch(IOException exception)
            {
                throw new RequestException(422, "invalid_spectrum_snap_settings",
                    "Spectrum-snap settings are invalid");
            }
        }
        finally
        {
            Arrays.fill(body, (byte)0);
        }
    }

    private static long requireRevision(HttpExchange exchange) throws RequestException
    {
        List<String> values = exchange.getRequestHeaders().get("If-Match");
        if(values == null || values.size() != 1)
        {
            throw new RequestException(values == null ? 428 : 400, "revision_required",
                "If-Match must contain one quoted revision");
        }
        Matcher matcher = ETAG.matcher(values.getFirst());
        if(!matcher.matches())
        {
            throw new RequestException(400, "invalid_revision", "If-Match must contain one quoted revision");
        }
        try
        {
            return Long.parseLong(matcher.group(1));
        }
        catch(NumberFormatException exception)
        {
            throw new RequestException(400, "invalid_revision", "If-Match revision is too large");
        }
    }

    private static void requireEmptyBody(HttpExchange exchange) throws RequestException
    {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if(contentLength != null && !"0".equals(contentLength) ||
            exchange.getRequestHeaders().containsKey("Transfer-Encoding"))
        {
            throw new RequestException(400, "invalid_request", "Request body is not supported");
        }
    }

    private record Selection(String countryCode)
    {
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
