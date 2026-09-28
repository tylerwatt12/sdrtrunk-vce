/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.settings.OperationalPreferencesService;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administrator endpoint for small, one-field updates to receiver-wide operational preferences. */
public final class OperationalPreferencesHttpController
{
    public static final String PATH = "/api/v1/admin/operational-preferences";
    private static final Pattern FIELD_PATH = Pattern.compile("^" + PATH + "/([a-z][a-z0-9_]*)$");
    private static final Pattern ETAG = Pattern.compile("\"([0-9a-f]{64})\"");
    private static final Logger mLog = LoggerFactory.getLogger(OperationalPreferencesHttpController.class);
    private final OperationalPreferencesService mPreferences;

    public OperationalPreferencesHttpController(OperationalPreferencesService preferences)
    {
        mPreferences = Objects.requireNonNull(preferences);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!WebHttpSupport.requireNoQuery(exchange)) return;

        String path = exchange.getRequestURI().getRawPath();
        try
        {
            if(PATH.equals(path))
            {
                if(!"GET".equals(exchange.getRequestMethod()))
                {
                    WebHttpSupport.methodNotAllowed(exchange, "GET");
                    return;
                }
                if(WebHttpSupport.hasRequestBody(exchange))
                {
                    ApiHttpResponse.sendError(exchange, 400, "invalid_request", "GET requests cannot include a body");
                    return;
                }
                send(exchange, 200, mPreferences.snapshot());
                return;
            }

            Matcher match = FIELD_PATH.matcher(path);
            if(!match.matches())
            {
                WebHttpSupport.notFound(exchange);
                return;
            }
            if(!"PUT".equals(exchange.getRequestMethod()))
            {
                WebHttpSupport.methodNotAllowed(exchange, "PUT");
                return;
            }

            OperationalPreferencesService.Field field;
            try
            {
                field = OperationalPreferencesService.Field.fromPath(match.group(1));
            }
            catch(IllegalArgumentException ignored)
            {
                WebHttpSupport.notFound(exchange);
                return;
            }
            String expectedRevision = requireRevision(exchange);
            JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("value"));
            if(body.size() != 1 || body.get("value") == null)
            {
                throw new IllegalArgumentException("A setting value is required");
            }
            JsonNode raw = body.get("value");
            Object value = raw.isTextual() ? raw.textValue() : raw.isBoolean() ? raw.booleanValue() :
                raw.isIntegralNumber() && raw.canConvertToInt() ? raw.intValue() : null;
            if(value == null)
            {
                throw new IllegalArgumentException("The setting value is invalid");
            }

            OperationalPreferencesService.UpdateResult result = mPreferences.update(expectedRevision, field, value);
            send(exchange, result.updated() ? 200 : 409, result.snapshot());
        }
        catch(WebHttpSupport.RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 422, "invalid_operational_preference", exception.getMessage());
        }
        catch(RuntimeException exception)
        {
            mLog.error("Unable to update operational preference", exception);
            ApiHttpResponse.sendError(exchange, 500, "operational_preference_failed",
                "The setting could not be saved. Reload and try again");
        }
    }

    private static String requireRevision(HttpExchange exchange) throws WebHttpSupport.RequestException
    {
        List<String> values = exchange.getRequestHeaders().get("If-Match");
        if(values == null || values.isEmpty())
        {
            throw new WebHttpSupport.RequestException(428, "revision_required",
                "Reload the saved settings before saving.");
        }
        if(values.size() != 1)
        {
            throw new WebHttpSupport.RequestException(400, "invalid_revision",
                "Reload the saved settings before saving.");
        }
        Matcher match = ETAG.matcher(values.getFirst());
        if(!match.matches())
        {
            throw new WebHttpSupport.RequestException(400, "invalid_revision",
                "Reload the saved settings before saving.");
        }
        return match.group(1);
    }

    private static void send(HttpExchange exchange, int status, OperationalPreferencesService.Snapshot snapshot)
        throws IOException
    {
        exchange.getResponseHeaders().set("ETag", '"' + snapshot.revision() + '"');
        ApiHttpResponse.sendDocument(exchange, status, snapshot);
    }
}
