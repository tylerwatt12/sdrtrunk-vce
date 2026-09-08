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

package io.github.dsheirer.stats;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.http.WebRequestSecurity;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Version-one read, export, and discovery controller.
 */
final class StatsApiV1Controller
{
    private static final Logger LOGGER = LoggerFactory.getLogger(StatsApiV1Controller.class);
    private static final int MAX_PATH_SEGMENT_LENGTH = 512;
    private static final Set<WebCapability> ACTIVITY_CAPABILITIES = Set.of(WebCapability.RADIO_VIEW);
    private static final Set<WebCapability> CSV_ROUTE_CAPABILITIES = Set.of(WebCapability.CSV_EXPORT,
        WebCapability.DASHBOARD_VIEW, WebCapability.RADIO_VIEW, WebCapability.ADMIN_ALIASES);

    private final StatsWebDatabase mDatabase;
    private final Supplier<Map<String,Object>> mStatusSupplier;
    private final WebRequestSecurity mRequestSecurity;
    private final TunerDiagnosticService mTunerDiagnosticService;
    private final Supplier<Map<String,Object>> mReceiverHealthSupplier;
    private final Semaphore mCsvExportPermit = new Semaphore(1);
    private final Semaphore mActivityRadioPermit = new Semaphore(1, true);

    StatsApiV1Controller(StatsWebDatabase database, Supplier<Map<String,Object>> statusSupplier,
                         WebRequestSecurity requestSecurity, TunerDiagnosticService tunerDiagnosticService)
    {
        this(database, statusSupplier, requestSecurity, tunerDiagnosticService, Map::of);
    }

    StatsApiV1Controller(StatsWebDatabase database, Supplier<Map<String,Object>> statusSupplier,
                         WebRequestSecurity requestSecurity, TunerDiagnosticService tunerDiagnosticService,
                         Supplier<Map<String,Object>> receiverHealthSupplier)
    {
        mDatabase = database;
        mStatusSupplier = statusSupplier;
        mRequestSecurity = requestSecurity;
        mTunerDiagnosticService = tunerDiagnosticService;
        mReceiverHealthSupplier = receiverHealthSupplier;
    }

    void register(HttpServer server)
    {
        create(server, StatsApiV1.STATUS, WebCapability.DASHBOARD_VIEW,
            exchange -> handleJson(exchange, StatsApiV1.STATUS, (request, segments) -> {
                requireNoSegments(segments);
                request.requireOnly();
                return mStatusSupplier.get();
            }));
        create(server, StatsApiV1.DASHBOARD, WebCapability.DASHBOARD_VIEW,
            exchange -> handleJson(exchange, StatsApiV1.DASHBOARD, (request, segments) -> {
                requireNoSegments(segments);
                request.requireOnly();
                return mDatabase.dashboard();
            }));
        create(server, StatsApiV1.QUALITY, WebCapability.DASHBOARD_VIEW,
            exchange -> handleJson(exchange, StatsApiV1.QUALITY, (request, segments) -> {
                requireNoSegments(segments);
                request.requireOnly("range", "points", "include_history", "limit", "offset");
                return collection(mDatabase.qualityHistory(request), "channels");
            }));
        create(server, StatsApiV1.ALIAS_LISTS, WebCapability.ADMIN_ALIASES,
            exchange -> handleJson(exchange, StatsApiV1.ALIAS_LISTS, this::aliasLists));
        create(server, StatsApiV1.ALIASES, WebCapability.ADMIN_ALIASES,
            exchange -> handleJson(exchange, StatsApiV1.ALIASES, this::aliases));
        create(server, StatsApiV1.RADIO_SYSTEMS, WebCapability.RADIO_VIEW,
            exchange -> handleJson(exchange, StatsApiV1.RADIO_SYSTEMS, this::radioSystems));
        create(server, StatsApiV1.CHANNELS, WebCapability.RADIO_VIEW,
            exchange -> handleJson(exchange, StatsApiV1.CHANNELS, this::channels));
        server.createContext(StatsApiV1.ACTIVITY, mRequestSecurity.protectAny(ACTIVITY_CAPABILITIES,
            exchange -> handleJson(exchange, StatsApiV1.ACTIVITY, (request, segments) -> {
                requireNoSegments(segments);
                request.requireOnly("before_id", "group_identity_key", "radio_identity_key",
                    "radio_system_key", "configuration_id", "hide_grants", "limit");
                return page(mDatabase.activity(request));
            })));
        create(server, StatsApiV1.ACTIVITY_ACTIONS, WebCapability.DASHBOARD_VIEW,
            exchange -> handleJson(exchange, StatsApiV1.ACTIVITY_ACTIONS, (request, segments) -> {
                requireNoSegments(segments);
                request.requireOnly("range");
                return collection(mDatabase.dashboardActivityActions(request), "rows");
            }));
        create(server, StatsApiV1.ACTIVITY_RADIOS, WebCapability.RADIO_VIEW,
            exchange -> handleJson(exchange, StatsApiV1.ACTIVITY_RADIOS, this::dashboardActivityRadios));
        server.createContext(StatsApiV1.EXPORTS,
            mRequestSecurity.protectAny(CSV_ROUTE_CAPABILITIES, this::handleCsvExport));
        create(server, StatsApiV1.RECEIVER_HEALTH, WebCapability.RECEIVER_HEALTH,
            exchange -> handleJson(exchange, StatsApiV1.RECEIVER_HEALTH, (request, segments) -> {
                requireNoSegments(segments);
                request.requireOnly();
                return mReceiverHealthSupplier.get();
            }));

        if(mTunerDiagnosticService != null)
        {
            create(server, StatsApiV1.TUNER_DIAGNOSTICS, WebCapability.TUNER_SPECTRUM_VIEW,
                exchange -> handleJson(exchange, StatsApiV1.TUNER_DIAGNOSTICS, (request, segments) -> {
                    requireNoSegments(segments);
                    request.requireOnly();
                    return mTunerDiagnosticService.targets();
                }));
        }
    }

    private Object aliasLists(StatsRequest request, List<String> segments)
    {
        if(segments.isEmpty())
        {
            request.requireOnly("limit", "offset");
            return page(mDatabase.aliasLists(request));
        }
        else if(segments.size() == 2 && "observed-group-identities".equals(segments.get(1)))
        {
            request.requireOnly("include_exact", "q", "sort", "direction", "limit", "offset");
            return page(mDatabase.observedGroupIdentities(pathIdentifier("alias_list_id", segments.get(0)), request));
        }

        throw notFound();
    }

    private JsonBody dashboardActivityRadios(StatsRequest request, List<String> segments)
    {
        requireNoSegments(segments);
        request.requireOnly("range", "action", "limit", "offset");

        if(!mActivityRadioPermit.tryAcquire())
        {
            throw new StatsApiException(429, "activity_query_busy",
                "Another exact activity query is already running");
        }

        try
        {
            return page(mDatabase.dashboardActivityRadios(request));
        }
        finally
        {
            mActivityRadioPermit.release();
        }
    }

    private Object aliases(StatsRequest request, List<String> segments)
    {
        if(segments.isEmpty())
        {
            request.requireOnly("family", "type", "matcher", "list", "group", "scan_list_id", "record",
                "stream", "q", "sort", "direction", "evidence", "use", "last_activity_after",
                "last_activity_before", "include_activity", "limit", "offset");
            return page(mDatabase.aliases(request));
        }
        else if(segments.size() == 1 && "ids".equals(segments.getFirst()))
        {
            request.requireOnly("family", "type", "matcher", "list", "group", "scan_list_id", "record",
                "stream", "q", "evidence", "use", "last_activity_after", "last_activity_before");
            List<Long> ids = mDatabase.matchingAliasIds(request);
            return Map.of("alias_ids", ids, "count", ids.size());
        }
        else if(segments.size() == 1)
        {
            request.requireOnly();
            return aliasDetail(mDatabase.alias(pathIdentifier("alias_id", segments.get(0))));
        }

        throw notFound();
    }

    private Object radioSystems(StatsRequest request, List<String> segments)
    {
        if(segments.isEmpty())
        {
            request.requireOnly("q", "sort", "direction", "include_channel_preview", "limit", "offset");
            return page(mDatabase.radioSystemDirectory(request));
        }

        String radioSystemKey = segments.get(0);

        if(segments.size() == 1)
        {
            request.requireOnly();
            return unwrap(mDatabase.radioSystem(radioSystemKey), "radio_system");
        }

        String resource = segments.get(1);

        if("channels".equals(resource) && segments.size() == 2)
        {
            request.requireOnly("q", "sort", "direction", "limit", "offset");
            return page(mDatabase.radioSystemChannels(radioSystemKey, request));
        }
        else if("group-identities".equals(resource))
        {
            return groupIdentities(request, radioSystemKey, segments);
        }
        else if("radios".equals(resource))
        {
            if(segments.size() == 2)
            {
                request.requireOnly("q", "affiliated", "configuration_id", "sort", "direction", "limit", "offset");
                return page(mDatabase.radioSystemRadios(radioSystemKey, request));
            }
            else if(segments.size() == 3)
            {
                request.requireOnly();
                return unwrap(mDatabase.radio(radioSystemKey, segments.get(2)), "radio");
            }
        }
        else if("talker-aliases".equals(resource) && segments.size() == 2)
        {
            request.requireOnly("q", "sort", "direction", "limit", "offset");
            return page(mDatabase.radioSystemTalkerAliases(radioSystemKey, request));
        }
        else if("relationships".equals(resource) && segments.size() == 2)
        {
            request.requireOnly("group_identity_key", "radio_identity_key", "affiliated",
                "configuration_id", "sort", "direction", "limit", "offset");
            return page(mDatabase.radioSystemRelationships(radioSystemKey, request));
        }

        throw notFound();
    }

    private Object groupIdentities(StatsRequest request, String radioSystemKey, List<String> segments)
    {
        if(segments.size() == 2)
        {
            request.requireOnly("q", "sort", "direction", "limit", "offset");
            return page(mDatabase.radioSystemGroupIdentities(radioSystemKey, request));
        }
        else if(segments.size() != 3 && segments.size() != 4)
        {
            throw notFound();
        }

        String identityKey = segments.get(2);

        if(segments.size() == 3)
        {
            request.requireOnly();
            return unwrap(mDatabase.radioSystemGroupIdentity(radioSystemKey, identityKey), "group_identity");
        }
        else if("activity".equals(segments.get(3)))
        {
            request.requireOnly("range");
            return mDatabase.radioSystemGroupIdentityActivity(radioSystemKey, identityKey, request);
        }

        throw notFound();
    }

    private Object channels(StatsRequest request, List<String> segments)
    {
        if(segments.isEmpty())
        {
            request.requireOnly("q", "type", "protocol", "sort", "direction", "limit", "offset");
            return page(mDatabase.channelDirectory(request));
        }

        String configurationId = segments.get(0);

        if(segments.size() == 1)
        {
            request.requireOnly("limit", "offset");
            return compound(mDatabase.channelDetail(configurationId, request), "channel", "summaries");
        }
        else if(segments.size() != 2)
        {
            throw notFound();
        }

        return switch(segments.get(1))
        {
            case "frequencies" -> {
                request.requireOnly("limit", "offset");
                yield page(mDatabase.channelFrequencies(configurationId, request));
            }
            case "group-identities" -> {
                request.requireOnly("q", "range", "sort", "direction", "limit", "offset");
                yield page(mDatabase.channelGroupIdentities(configurationId, request));
            }
            case "radios" -> {
                request.requireOnly("q", "sort", "direction", "limit", "offset");
                yield page(mDatabase.channelRadios(configurationId, request));
            }
            case "quality" -> {
                request.requireOnly("range", "points", "include_history");
                yield collection(mDatabase.channelQuality(configurationId, request), "channels");
            }
            case "frequency-bands" -> {
                request.requireOnly("limit", "offset");
                yield frequencyBands(mDatabase.channelBands(configurationId, request));
            }
            case "neighbors" -> {
                request.requireOnly("limit", "offset");
                yield page(mDatabase.channelNeighbors(configurationId, request));
            }
            case "patch-groups" -> {
                request.requireOnly("limit", "offset");
                yield compound(mDatabase.channelPatches(configurationId, request), "groups", "talkgroups", "radios");
            }
            default -> throw notFound();
        };
    }

    private void handleJson(HttpExchange exchange, String prefix, JsonRoute route) throws IOException
    {
        if(!requireMethod(exchange, "GET"))
        {
            return;
        }

        try
        {
            StatsRequest request = StatsRequest.from(exchange.getRequestURI());
            Object result = route.handle(request, segments(exchange.getRequestURI(), prefix));
            request.requireFullyConsumed();

            if(result instanceof JsonBody body)
            {
                ApiHttpResponse.sendDataWithMeta(exchange, 200, StatsApiV1Payload.present(body.data()),
                    StatsApiV1Payload.present(body.meta()));
            }
            else
            {
                ApiHttpResponse.sendData(exchange, 200, StatsApiV1Payload.present(result));
            }
        }
        catch(StatsApiException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage(),
                exception.field());
        }
        catch(RuntimeException exception)
        {
            LOGGER.warn("Stats API request failed [{}]", exchange.getRequestURI().getPath(), exception);
            ApiHttpResponse.sendError(exchange, 500, "internal_error", "The request could not be completed");
        }
    }

    private void handleCsvExport(HttpExchange exchange) throws IOException
    {
        if(!requireMethod(exchange, "GET"))
        {
            return;
        }

        if(!mCsvExportPermit.tryAcquire())
        {
            ApiHttpResponse.sendError(exchange, 429, "export_busy", "Another CSV export is already running");
            return;
        }

        try
        {
            List<String> segments = segments(exchange.getRequestURI(), StatsApiV1.EXPORTS);

            if(segments.size() != 1 || !segments.getFirst().endsWith(".csv"))
            {
                throw notFound();
            }

            String dataset = segments.getFirst().substring(0, segments.getFirst().length() - 4);
            requireAuthorized(exchange, WebCapability.CSV_EXPORT);
            requireAuthorized(exchange, csvDatasetCapability(dataset));

            StatsRequest request = StatsRequest.from(exchange.getRequestURI());
            validateExportQuery(request, dataset);
            StatsCsvExport export = mDatabase.csvExport(dataset, request);
            request.requireFullyConsumed();
            Headers headers = exchange.getResponseHeaders();
            StatsWebServerService.applyCsvHeaders(headers, export.fileName());
            exchange.sendResponseHeaders(200, export.content().length);

            try(OutputStream outputStream = exchange.getResponseBody())
            {
                outputStream.write(export.content());
            }
        }
        catch(StatsApiException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage(),
                exception.field());
        }
        catch(RuntimeException exception)
        {
            LOGGER.warn("Stats CSV export failed", exception);
            ApiHttpResponse.sendError(exchange, 500, "export_failed", "CSV export failed");
        }
        finally
        {
            mCsvExportPermit.release();
        }
    }

    private void create(HttpServer server, String path, WebCapability capability, HttpHandler handler)
    {
        server.createContext(path, mRequestSecurity.protect(capability, handler));
    }

    private void requireAuthorized(HttpExchange exchange, WebCapability capability)
    {
        if(!mRequestSecurity.isRequestStillAuthorized(exchange, capability))
        {
            throw new StatsApiException(403, "access_denied", "Access is denied");
        }
    }

    private static WebCapability csvDatasetCapability(String dataset)
    {
        return switch(dataset)
        {
            case "radio-system-group-identities", "radio-system-radios", "radio-system-talker-aliases", "channels",
                 "channel-frequencies", "channel-group-identities", "channel-radios", "channel-neighbors",
                 "channel-quality" ->
                WebCapability.RADIO_VIEW;
            case "signal-health" -> WebCapability.DASHBOARD_VIEW;
            case "aliases" -> WebCapability.ADMIN_ALIASES;
            default -> throw invalidExport();
        };
    }

    private static StatsApiException invalidExport()
    {
        return new StatsApiException(400, "invalid_export", "Unsupported CSV dataset", "dataset");
    }

    private static void validateExportQuery(StatsRequest request, String dataset)
    {
        switch(dataset)
        {
            case "radio-system-group-identities", "radio-system-talker-aliases" ->
                request.requireOnly("radio_system_key", "q", "sort", "direction");
            case "radio-system-radios" ->
                request.requireOnly("radio_system_key", "q", "affiliated", "configuration_id", "sort",
                    "direction");
            case "channels" -> request.requireOnly("q", "type", "protocol", "sort", "direction");
            case "channel-frequencies", "channel-neighbors" -> request.requireOnly("configuration_id");
            case "channel-group-identities", "channel-radios" ->
                request.requireOnly("configuration_id", "q", "sort", "direction");
            case "signal-health" -> request.requireOnly();
            case "channel-quality" -> request.requireOnly("configuration_id", "range", "points");
            case "aliases" -> request.requireOnly("family", "type", "matcher", "list", "group",
                "scan_list_id", "record", "stream", "q", "sort", "direction", "evidence", "use",
                "last_activity_after", "last_activity_before");
            default -> throw invalidExport();
        }
    }

    private static List<String> segments(URI uri, String prefix)
    {
        String rawPath = uri != null ? uri.getRawPath() : null;

        if(rawPath == null || !rawPath.equals(prefix) && !rawPath.startsWith(prefix + "/"))
        {
            throw notFound();
        }
        else if(rawPath.equals(prefix))
        {
            return List.of();
        }

        String remainder = rawPath.substring(prefix.length() + 1);

        if(remainder.isEmpty() || remainder.endsWith("/"))
        {
            throw notFound();
        }

        List<String> values = new ArrayList<>();

        for(String rawSegment: remainder.split("/", -1))
        {
            String value;

            value = StatsRequest.decodePathSegment(rawSegment);

            if(value.isBlank() || value.length() > MAX_PATH_SEGMENT_LENGTH || value.contains("/") ||
                value.contains("\\") || value.contains("%"))
            {
                throw new StatsApiException(400, "invalid_path", "Path segment is invalid");
            }

            values.add(value);
        }

        return List.copyOf(values);
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException
    {
        if(method.equalsIgnoreCase(exchange.getRequestMethod()))
        {
            return true;
        }

        exchange.getResponseHeaders().set("Allow", method);
        ApiHttpResponse.sendError(exchange, 405, "method_not_allowed", "Method not allowed");
        return false;
    }

    private static void requireNoSegments(List<String> segments)
    {
        if(!segments.isEmpty())
        {
            throw notFound();
        }
    }

    private static int pathIdentifier(String field, String value)
    {
        if(value == null || !value.matches("[0-9]+"))
        {
            throw new StatsApiException(400, "invalid_path", field + " must be a non-negative decimal integer",
                field);
        }

        try
        {
            int identifier = Integer.parseInt(value);

            if(identifier < 0)
            {
                throw new NumberFormatException();
            }

            return identifier;
        }
        catch(NumberFormatException e)
        {
            throw new StatsApiException(400, "invalid_path", field + " must be a non-negative decimal integer",
                field);
        }
    }

    private static StatsApiException notFound()
    {
        return new StatsApiException(404, "not_found", "Resource not found");
    }

    private static JsonBody page(Map<String,Object> result)
    {
        return collection(result, "rows");
    }

    private static JsonBody collection(Map<String,Object> result, String field)
    {
        if(result == null || field == null || !result.containsKey(field))
        {
            throw new IllegalStateException("Collection API result is missing " + field);
        }

        LinkedHashMap<String,Object> meta = new LinkedHashMap<>(result);
        Object data = meta.remove(field);
        return new JsonBody(data, java.util.Collections.unmodifiableMap(meta));
    }

    private static JsonBody compound(Map<String,Object> result, String... dataFields)
    {
        if(result == null)
        {
            throw new IllegalStateException("Compound API result is missing");
        }

        LinkedHashMap<String,Object> data = new LinkedHashMap<>();
        LinkedHashMap<String,Object> meta = new LinkedHashMap<>(result);

        for(String field: dataFields)
        {
            if(!meta.containsKey(field))
            {
                throw new IllegalStateException("Compound API result is missing " + field);
            }

            data.put(field, meta.remove(field));
        }

        return new JsonBody(java.util.Collections.unmodifiableMap(data),
            java.util.Collections.unmodifiableMap(meta));
    }

    private static JsonBody frequencyBands(Map<String,Object> result)
    {
        JsonBody split = compound(result, "rows", "foreign_rows");
        Map<?,?> source = (Map<?,?>)split.data();
        Map<String,Object> data = Map.of(
            "home_bands", source.get("rows"),
            "foreign_bands", source.get("foreign_rows"));
        return new JsonBody(data, split.meta());
    }

    private static Object unwrap(Map<String,Object> result, String field)
    {
        if(result == null || !result.containsKey(field))
        {
            throw new IllegalStateException("Resource API result is missing " + field);
        }

        return result.get(field);
    }

    private static Map<String,Object> aliasDetail(Map<String,Object> result)
    {
        if(result == null || !(result.get("alias") instanceof Map<?,?> alias) ||
            !result.containsKey("breakdown"))
        {
            throw new IllegalStateException("Alias detail API result is incomplete");
        }

        LinkedHashMap<String,Object> detail = new LinkedHashMap<>();
        alias.forEach((key, value) -> {
            if(key instanceof String name)
            {
                detail.put(name, value);
            }
        });
        detail.put("breakdown", result.get("breakdown"));
        return java.util.Collections.unmodifiableMap(detail);
    }

    @FunctionalInterface
    private interface JsonRoute
    {
        Object handle(StatsRequest request, List<String> segments);
    }

    private record JsonBody(Object data, Map<String,Object> meta)
    {
    }
}
