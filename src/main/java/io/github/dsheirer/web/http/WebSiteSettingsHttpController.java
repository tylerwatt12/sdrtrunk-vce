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
import io.github.dsheirer.web.settings.WebSiteSettingsService;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.prefs.BackingStoreException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administrator-only endpoint for the three settings that change receiver behavior for everyone. */
public final class WebSiteSettingsHttpController
{
    public static final String PATH = "/api/v1/admin/site-settings";
    private static final int MAXIMUM_BODY_BYTES = 512;
    private static final Pattern ETAG = Pattern.compile("\"([1-9][0-9]*)\"");
    private static final Logger mLog = LoggerFactory.getLogger(WebSiteSettingsHttpController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final WebSiteSettingsService mSettings;

    public WebSiteSettingsHttpController(WebSiteSettingsService settings)
    {
        mSettings = Objects.requireNonNull(settings, "Web site settings service cannot be null");
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
                    send(exchange, 200, mSettings.snapshot());
                }
                case "PUT" -> {
                    long expectedRevision = requireRevision(exchange);
                    WebSiteSettingsService.Settings requested = read(exchange);
                    try
                    {
                        WebSiteSettingsService.ReplaceResult result = mSettings.replace(expectedRevision, requested);
                        send(exchange, result.updated() ? 200 : 409, result.snapshot());
                    }
                    catch(BackingStoreException exception)
                    {
                        mLog.error("Unable to save site settings", exception);
                        ApiHttpResponse.sendError(exchange, 500, "settings_save_failed",
                            "Site settings could not be saved");
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
            ApiHttpResponse.sendError(exchange, 422, "invalid_site_settings", exception.getMessage());
        }
    }

    private static void send(HttpExchange exchange, int status, WebSiteSettingsService.Snapshot snapshot)
        throws IOException
    {
        exchange.getResponseHeaders().set("ETag", quoteRevision(snapshot.revision()));
        ApiHttpResponse.sendDocument(exchange, status, snapshot);
    }

    private static long requireRevision(HttpExchange exchange) throws RequestException
    {
        List<String> values = exchange.getRequestHeaders().get("If-Match");
        if(values == null || values.isEmpty())
        {
            throw new RequestException(428, "revision_required", "If-Match is required");
        }
        if(values.size() != 1)
        {
            throw new RequestException(400, "invalid_revision", "If-Match must contain one quoted revision");
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

    private static String quoteRevision(long revision)
    {
        if(revision < 1)
        {
            throw new IllegalArgumentException("Site-settings revision must be positive");
        }
        return "\"" + revision + "\"";
    }

    private static WebSiteSettingsService.Settings read(HttpExchange exchange) throws IOException, RequestException
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
            if(body.length > MAXIMUM_BODY_BYTES)
            {
                throw new RequestException(413, "request_too_large", "Request body is too large");
            }
            try
            {
                return MAPPER.readValue(body, WebSiteSettingsService.Settings.class);
            }
            catch(IOException exception)
            {
                throw new RequestException(422, "invalid_site_settings", "Site settings are invalid");
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
}
