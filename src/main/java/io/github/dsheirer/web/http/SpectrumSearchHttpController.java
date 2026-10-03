/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.stats.SpectrumSearchService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Function;

/** Bounded request adapter. RF evidence and saved-result identities remain owned by the search service. */
public final class SpectrumSearchHttpController
{
    public static final String PATH = "/api/v1/admin/spectrum-search";
    private final SpectrumSearchService mService;
    private final Function<Object,Object> mPresenter;

    public SpectrumSearchHttpController(SpectrumSearchService service) { this(service, Function.identity()); }

    public SpectrumSearchHttpController(SpectrumSearchService service, Function<Object,Object> presenter)
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
            if(!WebHttpSupport.requireNoQuery(exchange)) return;
            String path = exchange.getRequestURI().getRawPath();
            if((PATH + "/catalog").equals(path))
            {
                if(!method(exchange, "GET")) return;
                noBody(exchange);
                sendData(exchange, 200, mService.catalog());
                return;
            }
            if(PATH.equals(path))
            {
                if(!method(exchange, "POST")) return;
                JsonNode body = WebHttpSupport.readJsonObject(exchange,
                    Set.of("tuner_id", "browse_lease_id", "ranges", "dwell_ms"));
                List<SpectrumSearchService.Range> ranges = new ArrayList<>();
                for(JsonNode range: array(body, "ranges", 8))
                {
                    fields(range, Set.of("minimum_hz", "maximum_hz"));
                    ranges.add(new SpectrumSearchService.Range(integer(range, "minimum_hz", true),
                        integer(range, "maximum_hz", true)));
                }
                SpectrumSearchService.validateRanges(ranges);
                long dwell = body.has("dwell_ms") ? integer(body, "dwell_ms", true) : 1500;
                if(dwell < 750 || dwell > 5000) throw new IllegalArgumentException("Choose a dwell between 750 and 5000 ms");
                sendData(exchange, 201, mService.open(
                    WebHttpSupport.requiredText(body, "tuner_id", 80),
                    WebHttpSupport.requiredText(body, "browse_lease_id", 80), ranges, dwell));
                return;
            }
            if(!path.startsWith(PATH + "/")) { WebHttpSupport.notFound(exchange); return; }
            String[] parts = path.substring(PATH.length() + 1).split("/", -1);
            String id = UUID.fromString(parts[0]).toString();
            if(parts.length == 1)
            {
                noBody(exchange);
                if("GET".equals(exchange.getRequestMethod())) sendData(exchange, 200, mService.status(id));
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
                JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("revision", "candidates", "alias_groups"));
                List<SpectrumSearchService.SaveCandidate> candidates = new ArrayList<>();
                for(JsonNode candidate: array(body, "candidates", 32))
                {
                    fields(candidate, Set.of("candidate_id", "name", "auto_start"));
                    if(!candidate.has("auto_start") || !candidate.get("auto_start").isBoolean())
                        throw new IllegalArgumentException("Choose whether each channel should start automatically");
                    candidates.add(new SpectrumSearchService.SaveCandidate(
                        WebHttpSupport.requiredText(candidate, "candidate_id", 80), text(candidate, "name", 256),
                        candidate.get("auto_start").booleanValue()));
                }
                List<SpectrumSearchService.AliasChoice> groups = new ArrayList<>();
                JsonNode rawGroups = body.get("alias_groups");
                if(rawGroups == null || !rawGroups.isArray() || rawGroups.size() > 32)
                    throw new IllegalArgumentException("alias_groups is invalid");
                for(JsonNode group: rawGroups)
                {
                    fields(group, Set.of("group_id", "alias_list_id", "new_alias_list_name"));
                    groups.add(new SpectrumSearchService.AliasChoice(WebHttpSupport.requiredText(group, "group_id", 80),
                        integer(group, "alias_list_id", false), text(group, "new_alias_list_name", 25)));
                }
                sendData(exchange, 200, mService.save(id,
                    new SpectrumSearchService.SaveRequest(integer(body, "revision", false), candidates, groups)));
                return;
            }
            if(parts.length == 2 && "start".equals(parts[1]))
            {
                if(!method(exchange, "POST")) return;
                JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("candidate_ids", "first_candidate_id"));
                List<String> ids = new ArrayList<>();
                for(JsonNode candidate: array(body, "candidate_ids", 32))
                {
                    if(!candidate.isTextual() || candidate.textValue().isBlank() || candidate.textValue().length() > 80)
                        throw new IllegalArgumentException("candidate_ids is invalid");
                    ids.add(candidate.textValue());
                }
                sendData(exchange, 200, mService.start(id, ids, text(body, "first_candidate_id", 80)));
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
        catch(ConfigurationManager.ConfigurationPublicationException exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "channel_saved_restart_required",
                exception.committedConfigurationId() != null ?
                    "A channel was saved, but receiver configuration could not refresh. Restart VCE before continuing." :
                    "Receiver configuration needs to refresh. Restart VCE before continuing.");
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", exception.getMessage());
        }
        catch(SpectrumSearchService.SearchExpiredException exception)
        {
            ApiHttpResponse.sendError(exchange, 410, "search_expired", exception.getMessage());
        }
        catch(IllegalStateException exception)
        {
            ApiHttpResponse.sendError(exchange, 409, "search_conflict", exception.getMessage());
        }
        catch(Exception exception)
        {
            ApiHttpResponse.sendError(exchange, 503, "search_unavailable", "Signal search is unavailable; retry");
        }
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException
    {
        if(expected.equals(exchange.getRequestMethod())) return true;
        WebHttpSupport.methodNotAllowed(exchange, expected);
        return false;
    }
    private static void noBody(HttpExchange exchange)
    {
        if(WebHttpSupport.hasRequestBody(exchange)) throw new IllegalArgumentException("This request does not accept a body");
    }
    private static JsonNode array(JsonNode body, String field, int maximum)
    {
        JsonNode value = body.get(field);
        if(value == null || !value.isArray() || value.isEmpty() || value.size() > maximum)
            throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
    private static void fields(JsonNode value, Set<String> allowed)
    {
        if(!value.isObject()) throw new IllegalArgumentException("Expected a request object");
        value.fieldNames().forEachRemaining(field -> {
            if(!allowed.contains(field)) throw new IllegalArgumentException("Unknown request field: " + field);
        });
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
}
