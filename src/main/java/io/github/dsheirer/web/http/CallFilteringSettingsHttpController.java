/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.preference.call.CallFilteringSettings;
import io.github.dsheirer.web.settings.CallFilteringSettingsService;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.prefs.BackingStoreException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administrator endpoint for receiver-wide completed-call admission. */
public final class CallFilteringSettingsHttpController
{
    public static final String PATH = "/api/v1/admin/call-filtering";
    private static final Pattern ETAG = Pattern.compile("\"([0-9a-f]{64})\"");
    private static final Logger mLog = LoggerFactory.getLogger(CallFilteringSettingsHttpController.class);
    private final CallFilteringSettingsService mSettings;

    public CallFilteringSettingsHttpController(CallFilteringSettingsService settings)
    {
        mSettings = Objects.requireNonNull(settings);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!WebHttpSupport.hasExactPath(exchange, PATH))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!WebHttpSupport.requireNoQuery(exchange)) return;
        try
        {
            switch(exchange.getRequestMethod())
            {
                case "GET" -> {
                    if(WebHttpSupport.hasRequestBody(exchange))
                    {
                        throw new WebHttpSupport.RequestException(400, "invalid_request",
                            "GET requests cannot include a body");
                    }
                    send(exchange, 200, mSettings.snapshot());
                }
                case "PUT" -> {
                    String revision = requireRevision(exchange);
                    JsonNode body = WebHttpSupport.readJsonObject(exchange,
                        Set.of("enabled", "minimum_usable_voice_percent"));
                    JsonNode enabled = body.get("enabled");
                    JsonNode minimum = body.get("minimum_usable_voice_percent");
                    if(body.size() != 2 || enabled == null || !enabled.isBoolean() || minimum == null ||
                        (!minimum.isNull() && (!minimum.isIntegralNumber() || !minimum.canConvertToInt())))
                    {
                        throw new IllegalArgumentException("Choose a minimum usable voice percentage from 1 to 100");
                    }
                    CallFilteringSettings requested = new CallFilteringSettings(enabled.booleanValue(),
                        minimum.isNull() ? null : minimum.intValue());
                    CallFilteringSettingsService.ReplaceResult result = mSettings.replace(revision, requested);
                    send(exchange, result.updated() ? 200 : 409, result.snapshot());
                }
                default -> WebHttpSupport.methodNotAllowed(exchange, "GET, PUT");
            }
        }
        catch(WebHttpSupport.RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 422, "invalid_call_filtering", exception.getMessage());
        }
        catch(BackingStoreException | RuntimeException exception)
        {
            mLog.error("Unable to save call filtering settings", exception);
            ApiHttpResponse.sendError(exchange, 500, "call_filtering_save_failed",
                "Call filtering could not be saved. Reload and try again");
        }
    }

    private static String requireRevision(HttpExchange exchange) throws WebHttpSupport.RequestException
    {
        List<String> values = exchange.getRequestHeaders().get("If-Match");
        if(values == null || values.isEmpty())
        {
            throw new WebHttpSupport.RequestException(428, "revision_required",
                "Reload call filtering before saving.");
        }
        Matcher match = ETAG.matcher(values.getFirst());
        if(values.size() != 1 || !match.matches())
        {
            throw new WebHttpSupport.RequestException(400, "invalid_revision",
                "Reload call filtering before saving.");
        }
        return match.group(1);
    }

    private static void send(HttpExchange exchange, int status, CallFilteringSettingsService.Snapshot snapshot)
        throws IOException
    {
        exchange.getResponseHeaders().set("ETag", '"' + snapshot.revision() + '"');
        ApiHttpResponse.sendDocument(exchange, status, snapshot);
    }
}
