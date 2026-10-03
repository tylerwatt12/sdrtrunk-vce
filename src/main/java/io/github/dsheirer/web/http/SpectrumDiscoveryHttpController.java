/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.stats.SpectrumDiscoveryService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Function;

/** Strict administrator adapter for the temporary spectrum channel wizard. */
public final class SpectrumDiscoveryHttpController
{
    public static final String PATH = "/api/v1/admin/spectrum-discovery";
    private final SpectrumDiscoveryService mService;
    private final Function<Object,Object> mPresenter;

    public SpectrumDiscoveryHttpController(SpectrumDiscoveryService service) { this(service, Function.identity()); }

    public SpectrumDiscoveryHttpController(SpectrumDiscoveryService service, Function<Object,Object> presenter)
    {
        mService = service;
        mPresenter = Objects.requireNonNull(presenter);
    }

    private void sendData(HttpExchange exchange, int status, Object data) throws IOException
    {
        ApiHttpResponse.sendData(exchange, status, mPresenter.apply(data));
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        try
        {
            String path = exchange.getRequestURI().getRawPath();
            if((PATH + "/eligibility").equals(path))
            {
                if(!method(exchange, "GET")) return;
                requireNoBody(exchange);
                Map<String,String> query = query(exchange);
                sendData(exchange, 200, mService.eligibility(query.get("tuner_id"),
                    positive(query.get("frequency_hz"))));
                return;
            }
            if(!WebHttpSupport.requireNoQuery(exchange)) return;
            if(PATH.equals(path))
            {
                if(!method(exchange, "POST")) return;
                JsonNode body = WebHttpSupport.readJsonObject(exchange,
                    Set.of("tuner_id", "frequency_hz", "protocol_id", "browse_lease_id"));
                sendData(exchange, 201, mService.open(
                    WebHttpSupport.requiredText(body, "tuner_id", 80), integer(body, "frequency_hz", true),
                    WebHttpSupport.requiredText(body, "protocol_id", 32), text(body, "browse_lease_id", 80)));
                return;
            }
            if(!path.startsWith(PATH + "/")) { WebHttpSupport.notFound(exchange); return; }
            String[] parts = path.substring(PATH.length() + 1).split("/", -1);
            String id = UUID.fromString(parts[0]).toString();
            if(parts.length == 1)
            {
                requireNoBody(exchange);
                if("GET".equals(exchange.getRequestMethod()))
                    sendData(exchange, 200, mService.status(id));
                else if("DELETE".equals(exchange.getRequestMethod()))
                {
                    mService.cancel(id);
                    sendData(exchange, 200, Map.of("closed", true));
                }
                else WebHttpSupport.methodNotAllowed(exchange, "GET, DELETE");
                return;
            }
            if(parts.length == 2 && "save".equals(parts[1]))
            {
                if(!method(exchange, "POST")) return;
                JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("system", "site", "name",
                    "alias_list_id", "new_alias_list_name", "settings", "revision", "browse_lease_id"));
                Map<String,Object> settings = new LinkedHashMap<>();
                JsonNode raw = body.get("settings");
                if(raw != null)
                {
                    if(!raw.isObject()) throw new IllegalArgumentException("Decoder settings must be an object");
                    var fields = raw.fields();
                    while(fields.hasNext())
                    {
                        var entry = fields.next();
                        JsonNode value = entry.getValue();
                        Object scalar;
                        if(value.isNull()) scalar = null;
                        else if(value.isTextual()) scalar = value.textValue();
                        else if(value.isBoolean()) scalar = value.booleanValue();
                        else if(value.isIntegralNumber() && value.canConvertToLong()) scalar = value.longValue();
                        else if(value.isFloatingPointNumber()) scalar = value.doubleValue();
                        else
                            throw new IllegalArgumentException("Decoder settings must contain scalar values");
                        settings.put(entry.getKey(), scalar);
                    }
                }
                sendData(exchange, 200, mService.save(id, new SpectrumDiscoveryService.SaveRequest(
                    text(body, "system", 256), text(body, "site", 256),
                    WebHttpSupport.requiredText(body, "name", 256), integer(body, "alias_list_id", false),
                    text(body, "new_alias_list_name", 25), settings, integer(body, "revision", false))));
                return;
            }
            if(parts.length == 2 && "start".equals(parts[1]))
            {
                if(!method(exchange, "POST")) return;
                requireNoBody(exchange);
                sendData(exchange, 200, mService.start(id));
                return;
            }
            WebHttpSupport.notFound(exchange);
        }
        catch(WebHttpSupport.RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(ChannelAdministrationService.StaleRevisionException exception)
        {
            ApiHttpResponse.sendError(exchange, 409, "stale_revision", "Channel configuration changed; review again");
        }
        catch(ChannelAdministrationService.ConfigurationBusyException exception)
        {
            ApiHttpResponse.sendError(exchange, 429, "configuration_busy", "Configuration is busy; retry shortly");
        }
        catch(ChannelAdministrationService.PersistenceException exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "storage_unavailable", "The channel could not be saved; retry");
        }
        catch(ConfigurationManager.ConfigurationPublicationException exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "channel_saved_restart_required",
                "The channel was saved, but receiver configuration could not refresh. Restart VCE before continuing.");
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", exception.getMessage());
        }
        catch(IllegalStateException exception)
        {
            ApiHttpResponse.sendError(exchange, 409, "discovery_conflict", exception.getMessage());
        }
        catch(Exception exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "discovery_unavailable", "Channel discovery is unavailable; retry");
        }
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException
    {
        if(expected.equals(exchange.getRequestMethod())) return true;
        WebHttpSupport.methodNotAllowed(exchange, expected);
        return false;
    }

    private static void requireNoBody(HttpExchange exchange)
    {
        if(WebHttpSupport.hasRequestBody(exchange)) throw new IllegalArgumentException("This request does not accept a body");
    }

    private static String text(JsonNode body, String field, int maximum)
    {
        JsonNode value = body.get(field);
        if(value == null || value.isNull()) return null;
        if(!value.isTextual() || value.textValue().length() > maximum)
            throw new IllegalArgumentException(field + " is invalid");
        return value.textValue().isBlank() ? null : value.textValue().strip();
    }

    private static long integer(JsonNode body, String field, boolean positive)
    {
        JsonNode value = body.get(field);
        if(value == null || !value.isIntegralNumber() || !value.canConvertToLong() ||
            value.longValue() < (positive ? 1 : 0) || value.longValue() > (1L << 53) - 1)
            throw new IllegalArgumentException(field + " is invalid");
        return value.longValue();
    }

    private static long positive(String value)
    {
        try
        {
            long frequency = Long.parseLong(value);
            if(frequency <= 0 || frequency > (1L << 53) - 1) throw new NumberFormatException();
            return frequency;
        }
        catch(RuntimeException exception) { throw new IllegalArgumentException("frequency_hz is invalid"); }
    }

    private static Map<String,String> query(HttpExchange exchange)
    {
        Map<String,String> query = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if(raw == null || raw.length() > 512) throw new IllegalArgumentException("Select a tuner and frequency");
        for(String part: raw.split("&", -1))
        {
            String[] pair = part.split("=", 2);
            String key = ApiRequestDecoder.decodeComponent(pair[0], true);
            if(pair.length != 2 || !Set.of("tuner_id", "frequency_hz").contains(key) || query.containsKey(key))
                throw new IllegalArgumentException("The frequency request is invalid");
            query.put(key, ApiRequestDecoder.decodeComponent(pair[1], true));
        }
        if(query.size() != 2) throw new IllegalArgumentException("Select a tuner and frequency");
        return query;
    }
}
