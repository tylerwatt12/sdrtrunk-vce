/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.record.RecordingMode;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.http.ApiRequestDecoder;
import io.github.dsheirer.web.http.WebRequestSecurity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded HTTP surface for the separate Managed Recordings catalog. */
final class ManagedRecordingsHttpController
{
    static final String BROWSE_PATH = "/api/v1/recordings";
    static final String ADMIN_PATH = "/api/v1/admin/recordings";
    private static final Logger mLog = LoggerFactory.getLogger(ManagedRecordingsHttpController.class);
    private static final int MAXIMUM_DELETE_IDS = 100;
    private static final int MAXIMUM_JSON_BYTES = 8 * 1024;
    private static final int MAXIMUM_SEARCH_IDENTITIES = 200;
    private static final Semaphore AUDIO_RESPONSES = new Semaphore(16);
    private static final Semaphore LABEL_LOOKUPS = new Semaphore(4);
    private final Supplier<ManagedRecordingCatalog> mCatalog;
    private final UserPreferences mPreferences;
    private final WebRequestSecurity mSecurity;
    private final ManagedRecordingLabels mLabels;
    private final ManagedRecordingMaintenance mMaintenance;

    ManagedRecordingsHttpController(Supplier<ManagedRecordingCatalog> catalog, UserPreferences preferences,
                                    WebRequestSecurity security, ManagedRecordingMaintenance maintenance)
    {
        this(catalog, preferences, security, maintenance, new ManagedRecordingLabels(preferences));
    }

    ManagedRecordingsHttpController(Supplier<ManagedRecordingCatalog> catalog, UserPreferences preferences,
                                    WebRequestSecurity security, ManagedRecordingMaintenance maintenance,
                                    ManagedRecordingLabels labels)
    {
        mCatalog = catalog;
        mPreferences = preferences;
        mSecurity = security;
        mLabels = labels;
        mMaintenance = maintenance;
    }

    void handleBrowse(HttpExchange exchange) throws IOException
    {
        try
        {
            String path = exchange.getRequestURI().getRawPath();
            if((BROWSE_PATH + "/status").equals(path))
            {
                requireMethod(exchange, "GET");
                if(exchange.getRequestURI().getRawQuery() != null)
                {
                    throw new StatsApiException(400, "unknown_parameter", "Query is not supported");
                }
                ApiHttpResponse.sendData(exchange, 200, browseStatus());
                return;
            }
            ManagedRecordingCatalog catalog = available();
            if((BROWSE_PATH + "/calls").equals(path))
            {
                requireMethod(exchange, "GET");
                search(exchange, catalog);
                return;
            }
            if((BROWSE_PATH + "/suggestions").equals(path))
            {
                requireMethod(exchange, "GET");
                suggest(exchange, catalog);
                return;
            }
            String callPrefix = BROWSE_PATH + "/calls/";
            if(path.startsWith(callPrefix))
            {
                String remaining = path.substring(callPrefix.length());
                boolean audio = remaining.endsWith("/audio");
                String idText = audio ? remaining.substring(0, remaining.length() - "/audio".length()) : remaining;
                long id = positiveId(idText);
                requireMethod(exchange, "GET");
                if(exchange.getRequestURI().getRawQuery() != null)
                {
                    throw new StatsApiException(400, "unknown_parameter", "Query is not supported");
                }
                if(audio)
                {
                    audio(exchange, catalog, id);
                }
                else
                {
                    ManagedRecordingCatalog.RecordingCall call = catalog.find(id);
                    if(call == null)
                    {
                        throw new StatsApiException(404, "recording_not_found", "Recording was not found");
                    }
                    ApiHttpResponse.sendData(exchange, 200,
                        mLabels.decorate(List.of(call.toMap())).getFirst());
                }
                return;
            }
            throw new StatsApiException(404, "not_found", "Not found");
        }
        catch(StatsApiException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage(),
                exception.field());
        }
        catch(SQLException exception)
        {
            mLog.warn("Managed recording read failed", exception);
            ApiHttpResponse.sendError(exchange, 503, "recordings_unavailable", "Recordings are unavailable");
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", exception.getMessage());
        }
        catch(IllegalStateException exception)
        {
            ApiHttpResponse.sendError(exchange, 409, "recordings_busy",
                "Recording maintenance is already running");
        }
    }

    void handleAdmin(HttpExchange exchange) throws IOException
    {
        if(mSecurity.authenticatedAccount(exchange).map(account -> account.primaryAdmin()).orElse(false) == false)
        {
            ApiHttpResponse.sendError(exchange, 403, "primary_admin_required",
                "Only the primary administrator can manage recordings");
            return;
        }
        try
        {
            String path = exchange.getRequestURI().getRawPath();
            if(exchange.getRequestURI().getRawQuery() != null)
            {
                throw new StatsApiException(400, "unknown_parameter", "Query is not supported");
            }
            if((ADMIN_PATH + "/settings").equals(path))
            {
                if("GET".equals(exchange.getRequestMethod()))
                {
                    ApiHttpResponse.sendData(exchange, 200, settings());
                }
                else if("PUT".equals(exchange.getRequestMethod()))
                {
                    updateSettings(exchange);
                }
                else
                {
                    requireMethod(exchange, "GET, PUT");
                }
                return;
            }
            ManagedRecordingCatalog catalog = available();
            if((ADMIN_PATH + "/status").equals(path))
            {
                requireMethod(exchange, "GET");
                Map<String,Object> status = new LinkedHashMap<>(settings());
                status.put("catalog", catalog.stats());
                status.put("maintenance", mMaintenance.status());
                ApiHttpResponse.sendData(exchange, 200, status);
                return;
            }
            if((ADMIN_PATH + "/recount").equals(path) || (ADMIN_PATH + "/reindex").equals(path))
            {
                requireMethod(exchange, "POST");
                if(hasBody(exchange))
                {
                    throw new StatsApiException(400, "invalid_request", "This operation takes no request body");
                }
                ManagedRecordingMaintenance.Status started = mMaintenance.start(
                    path.endsWith("/recount") ? "recount" : "reindex", catalog);
                if(started == null)
                {
                    throw new StatsApiException(409, "maintenance_running",
                        "A recording maintenance operation is already running");
                }
                ApiHttpResponse.sendData(exchange, 202, started);
                return;
            }
            if((ADMIN_PATH + "/calls").equals(path))
            {
                requireMethod(exchange, "DELETE");
                delete(exchange, catalog);
                return;
            }
            throw new StatsApiException(404, "not_found", "Not found");
        }
        catch(StatsApiException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage(),
                exception.field());
        }
        catch(SQLException exception)
        {
            mLog.warn("Managed recording administration failed", exception);
            ApiHttpResponse.sendError(exchange, 503, "recordings_unavailable", "Recordings are unavailable");
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", exception.getMessage());
        }
        catch(IllegalStateException exception)
        {
            ApiHttpResponse.sendError(exchange, 409, "recordings_busy",
                "Recording maintenance is already running");
        }
    }

    private ManagedRecordingCatalog available()
    {
        ManagedRecordingCatalog catalog = mCatalog.get();
        if(catalog == null)
        {
            throw new StatsApiException(503, "recordings_unavailable", "Recordings are unavailable");
        }
        return catalog;
    }

    /** Exposes only the mode and catalog presence needed to render the public recordings page. */
    private Map<String,Object> browseStatus() throws SQLException
    {
        ManagedRecordingCatalog catalog = mCatalog.get();
        Map<String,Object> status = new LinkedHashMap<>();
        status.put("mode", mPreferences.getRecordPreference().getRecordingMode().name());
        status.put("available", catalog != null);
        if(catalog == null)
        {
            status.put("call_count", null);
            status.put("has_calls", false);
        }
        else
        {
            long callCount = catalog.stats().callCount();
            status.put("call_count", callCount);
            status.put("has_calls", callCount > 0);
        }
        return status;
    }

    private void search(HttpExchange exchange, ManagedRecordingCatalog catalog) throws SQLException, IOException
    {
        StatsRequest request = StatsRequest.from(exchange.getRequestURI());
        Integer talkgroupId = request.optionalInt("talkgroup_id");
        Integer radioId = request.optionalInt("radio_id");
        Integer talkgroupMin = request.optionalInt("talkgroup_min");
        Integer talkgroupMax = request.optionalInt("talkgroup_max");
        Integer radioMin = request.optionalInt("radio_min");
        Integer radioMax = request.optionalInt("radio_max");
        boolean identityConstrained = talkgroupId != null || radioId != null ||
            talkgroupMin != null || radioMin != null;
        ManagedRecordingCatalog.SearchFilter.Builder filter = ManagedRecordingCatalog.SearchFilter.builder()
            .fromMs(request.optionalLong("from_ms"))
            .toMs(request.optionalLong("to_ms"))
            .systemKey(request.text("system_key"))
            .channelId(request.text("channel_id"))
            .aliasListId(request.optionalLong("alias_list_id"))
            .wacn(request.optionalInt("wacn"))
            .systemId(request.optionalInt("sysid"))
            .rfss(request.optionalInt("rfss"))
            .siteId(request.optionalInt("site_id"))
            .minDurationMs(request.optionalLong("min_duration_ms"))
            .maxDurationMs(request.optionalLong("max_duration_ms"))
            .talkgroupId(talkgroupId)
            .sourceId(radioId)
            .talkgroupMin(talkgroupMin)
            .talkgroupMax(talkgroupMax)
            .sourceMin(radioMin)
            .sourceMax(radioMax)
            .frequencyHz(request.optionalLong("frequency_hz"))
            .protocol(request.text("protocol"))
            .callType(request.text("call_type"))
            .voiceType(request.text("voice_type"))
            .cursor(request.text("cursor"))
            .limit(request.limit(100));
        String sort = request.sort("desc");
        if(!"asc".equals(sort) && !"desc".equals(sort))
        {
            throw new StatsApiException(400, "invalid_sort", "sort must be asc or desc", "sort");
        }
        filter.sortAscending("asc".equals(sort));
        String query = request.search();
        boolean nameQuery = query != null && !query.matches("[0-9]{1,8}");
        if(query != null)
        {
            if(!nameQuery)
            {
                filter.anyIdentityId(Integer.parseInt(query));
            }
            else if(!identityConstrained)
            {
                if(!LABEL_LOOKUPS.tryAcquire())
                {
                    throw new StatsApiException(429, "search_busy", "Too many recording searches are active");
                }
                List<Integer> ids;
                try
                {
                    ids = mLabels.matchingIdentityIds(query, request.text("system_key"),
                        MAXIMUM_SEARCH_IDENTITIES);
                }
                finally
                {
                    LABEL_LOOKUPS.release();
                }
                if(ids.isEmpty())
                {
                    request.requireFullyConsumed();
                    ApiHttpResponse.sendData(exchange, 200, Map.of("calls", List.of()));
                    return;
                }
                filter.anyIdentityIds(ids);
            }
        }
        request.requireFullyConsumed();
        ManagedRecordingCatalog.SearchPage page;
        List<Map<String,Object>> rows;
        int skippedEmptyCandidatePages = 0;
        do
        {
            page = catalog.search(filter.build());
            rows = mLabels.decorate(page.calls().stream()
                .map(ManagedRecordingCatalog.RecordingCall::toMap).toList());
            if(nameQuery)
            {
                String match = query.toLowerCase(Locale.ROOT);
                rows = rows.stream().filter(row -> matchesCurrentLabel(row, match)).toList();
            }
            if(!rows.isEmpty() || page.nextCursor() == null || ++skippedEmptyCandidatePages >= 5)
            {
                break;
            }
            filter.cursor(page.nextCursor());
        }
        while(true);
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("calls", rows);
        if(page.nextCursor() != null)
        {
            payload.put("next_cursor", page.nextCursor());
        }
        ApiHttpResponse.sendData(exchange, 200, payload);
    }

    private static boolean matchesCurrentLabel(Map<String,Object> row, String lowercaseQuery)
    {
        for(String field: List.of("group_alias", "group_description", "group_group", "source_alias",
            "source_description", "source_group", "source_ota_alias", "destination_radio_alias",
            "destination_radio_description", "destination_radio_group"))
        {
            Object value = row.get(field);
            if(value instanceof String text && text.toLowerCase(Locale.ROOT).contains(lowercaseQuery))
            {
                return true;
            }
        }
        return false;
    }

    private void suggest(HttpExchange exchange, ManagedRecordingCatalog catalog) throws SQLException, IOException
    {
        StatsRequest request = StatsRequest.from(exchange.getRequestURI());
        String query = request.search();
        String kind = request.text("kind");
        String systemKey = request.text("system_key");
        int limit = request.limit(20);
        request.requireFullyConsumed();
        if(query == null || query.length() < 2)
        {
            ApiHttpResponse.sendData(exchange, 200, List.of());
            return;
        }
        if(kind != null && !Set.of("system", "site", "talkgroup", "radio", "channel").contains(kind))
        {
            throw new StatsApiException(400, "invalid_kind", "Suggestion kind is invalid", "kind");
        }
        if(!LABEL_LOOKUPS.tryAcquire())
        {
            throw new StatsApiException(429, "search_busy", "Too many recording suggestions are active");
        }
        List<Map<String,Object>> suggestions;
        try
        {
            suggestions = new ArrayList<>(mLabels.suggestions(query, kind, systemKey, limit));
        }
        finally
        {
            LABEL_LOOKUPS.release();
        }
        Set<String> seen = new LinkedHashSet<>();
        for(Map<String,Object> row: suggestions)
        {
            seen.add(row.get("kind") + ":" + row.get("id"));
        }
        if((kind == null || "system".equals(kind)) && suggestions.size() < limit)
        {
            for(String key: catalog.systemKeys(query, limit - suggestions.size()))
            {
                if(seen.add("system:" + key))
                {
                    suggestions.add(Map.of("kind", "system", "id", key, "label", key,
                        "system_key", key));
                }
            }
        }
        if((kind == null || "channel".equals(kind)) && suggestions.size() < limit)
        {
            for(String id: catalog.channelIds(query, limit - suggestions.size()))
            {
                if(seen.add("channel:" + id))
                {
                    suggestions.add(Map.of("kind", "channel", "id", id, "label", id,
                        "channel_id", id));
                }
            }
        }
        if("site".equals(kind) && suggestions.size() < limit)
        {
            for(ManagedRecordingCatalog.Site site: catalog.sites(systemKey, query, limit - suggestions.size()))
            {
                String label = "RFSS " + site.rfss() + " · Site " + site.siteId();
                Map<String,Object> row = new LinkedHashMap<>(site.toMap());
                row.put("kind", "site");
                row.put("id", site.rfss() + ":" + site.siteId());
                row.put("label", label);
                if(systemKey != null) row.put("system_key", systemKey);
                row.put("sysid", site.systemId());
                row.put("rfss", site.rfss());
                suggestions.add(row);
            }
        }
        ApiHttpResponse.sendData(exchange, 200,
            suggestions.size() > limit ? suggestions.subList(0, limit) : suggestions);
    }

    private void audio(HttpExchange exchange, ManagedRecordingCatalog catalog, long id)
        throws IOException, SQLException
    {
        if(!AUDIO_RESPONSES.tryAcquire())
        {
            throw new StatsApiException(429, "too_many_audio_responses", "Too many audio responses are active");
        }
        try
        {
            Path path;
            try
            {
                path = catalog.audioPath(id);
            }
            catch(IOException exception)
            {
                throw new StatsApiException(404, "recording_not_found", "Recording audio was not found");
            }
            if(path == null)
            {
                throw new StatsApiException(404, "recording_not_found", "Recording audio was not found");
            }
            long length = Files.size(path);
            long start = 0;
            long end = length - 1;
            int status = 200;
            String range = exchange.getRequestHeaders().getFirst("Range");
            if(range != null)
            {
                if(!range.matches("bytes=[0-9]+-[0-9]*"))
                {
                    throw new StatsApiException(416, "invalid_range", "Audio range is invalid");
                }
                String[] parts = range.substring(6).split("-", -1);
                try
                {
                    start = Long.parseLong(parts[0]);
                    if(!parts[1].isBlank())
                    {
                        end = Math.min(length - 1, Long.parseLong(parts[1]));
                    }
                }
                catch(NumberFormatException exception)
                {
                    throw new StatsApiException(416, "invalid_range", "Audio range is invalid");
                }
                if(start < 0 || start >= length || end < start)
                {
                    throw new StatsApiException(416, "invalid_range", "Audio range is invalid");
                }
                status = 206;
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + '-' + end + '/' + length);
            }
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Cache-Control", "private, no-store, no-transform");
            try(InputStream input = Files.newInputStream(path))
            {
                input.skipNBytes(start);
                exchange.sendResponseHeaders(status, end - start + 1);
                try(OutputStream output = exchange.getResponseBody())
                {
                    byte[] buffer = new byte[16 * 1024];
                    long remaining = end - start + 1;
                    int chunksSinceAuthorization = 64;
                    while(remaining > 0)
                    {
                        if(chunksSinceAuthorization++ >= 64)
                        {
                            chunksSinceAuthorization = 0;
                            if(!mSecurity.isRequestStillAuthorized(exchange))
                            {
                                break;
                            }
                        }
                        int read = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                        if(read < 0)
                        {
                            break;
                        }
                        output.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
            }
        }
        finally
        {
            AUDIO_RESPONSES.release();
        }
    }

    private Map<String,Object> settings()
    {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("mode", mPreferences.getRecordPreference().getRecordingMode().name());
        result.put("retention_days", mPreferences.getRecordPreference().getManagedRetentionDays());
        result.put("managed_directory",
            mPreferences.getDirectoryPreference().getDirectoryManagedRecording().toString());
        result.put("available", mCatalog.get() != null);
        return result;
    }

    private void updateSettings(HttpExchange exchange) throws IOException
    {
        JsonNode body = json(exchange);
        rejectUnknownFields(body, Set.of("mode", "retention_days"));
        JsonNode rawMode = body.get("mode");
        JsonNode rawRetention = body.get("retention_days");
        if(rawMode == null && rawRetention == null)
        {
            throw new StatsApiException(400, "invalid_request", "At least one setting is required");
        }
        RecordingMode mode = null;
        if(rawMode != null)
        {
            if(!rawMode.isTextual())
            {
                throw new StatsApiException(400, "invalid_mode", "Recording mode is invalid");
            }
            try
            {
                mode = RecordingMode.valueOf(rawMode.asText().toUpperCase(Locale.ROOT));
            }
            catch(IllegalArgumentException exception)
            {
                throw new StatsApiException(400, "invalid_mode", "Recording mode is invalid");
            }
            if(mode == RecordingMode.MANAGED && mCatalog.get() == null)
            {
                throw new StatsApiException(503, "recordings_unavailable",
                    "Managed recording cannot be enabled while its catalog is unavailable");
            }
        }
        Integer retention = null;
        if(rawRetention != null && !rawRetention.isNull())
        {
            if(!rawRetention.canConvertToInt() || rawRetention.intValue() < 1 || rawRetention.intValue() > 3650)
            {
                throw new StatsApiException(400, "invalid_retention", "Retention must be 1 to 3650 days");
            }
            retention = rawRetention.intValue();
        }
        if(rawRetention != null)
        {
            mPreferences.getRecordPreference().setManagedRetentionDays(retention);
        }
        if(mode != null)
        {
            mPreferences.getRecordPreference().setRecordingMode(mode);
        }
        ApiHttpResponse.sendData(exchange, 200, settings());
    }

    private void delete(HttpExchange exchange, ManagedRecordingCatalog catalog) throws IOException, SQLException
    {
        JsonNode body = json(exchange);
        rejectUnknownFields(body, Set.of("ids"));
        JsonNode ids = body.get("ids");
        if(ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > MAXIMUM_DELETE_IDS)
        {
            throw new StatsApiException(400, "invalid_ids", "Select 1 to 100 recordings");
        }
        Set<Long> unique = new LinkedHashSet<>();
        for(JsonNode id: ids)
        {
            if(!id.canConvertToLong() || id.longValue() <= 0)
            {
                throw new StatsApiException(400, "invalid_ids", "Recording IDs must be positive integers");
            }
            unique.add(id.longValue());
        }
        int deleted = 0;
        for(long id: unique)
        {
            boolean removed;
            try
            {
                removed = catalog.delete(id);
            }
            catch(IOException exception)
            {
                mLog.warn("Unable to delete managed recording audio", exception);
                throw new StatsApiException(503, "recording_delete_failed",
                    "A recording could not be deleted");
            }
            if(removed)
            {
                deleted++;
            }
        }
        ApiHttpResponse.sendData(exchange, 200, Map.of("requested", unique.size(), "deleted", deleted));
    }

    private static JsonNode json(HttpExchange exchange) throws IOException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if(contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json"))
        {
            throw new StatsApiException(415, "invalid_content_type", "Content-Type must be application/json");
        }
        byte[] bytes = ApiRequestDecoder.readBody(exchange, MAXIMUM_JSON_BYTES);
        if(bytes.length == 0 || bytes.length > MAXIMUM_JSON_BYTES)
        {
            throw new StatsApiException(413, "invalid_body", "JSON body is missing or too large");
        }
        try
        {
            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
            if(body == null || !body.isObject())
            {
                throw new StatsApiException(400, "invalid_body", "JSON object is required");
            }
            return body;
        }
        catch(com.fasterxml.jackson.core.JsonProcessingException exception)
        {
            throw new StatsApiException(400, "invalid_body", "JSON body is invalid");
        }
    }

    private static void rejectUnknownFields(JsonNode body, Set<String> allowed)
    {
        body.fieldNames().forEachRemaining(name -> {
            if(!allowed.contains(name))
            {
                throw new StatsApiException(400, "unknown_field", "Unknown setting field");
            }
        });
    }

    private static boolean hasBody(HttpExchange exchange)
    {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        return length != null && !"0".equals(length) ||
            exchange.getRequestHeaders().getFirst("Transfer-Encoding") != null;
    }

    private static long positiveId(String value)
    {
        if(value == null || !value.matches("[0-9]{1,18}"))
        {
            throw new StatsApiException(404, "not_found", "Recording was not found");
        }
        try
        {
            long id = Long.parseLong(value);
            if(id > 0)
            {
                return id;
            }
        }
        catch(NumberFormatException ignored)
        {
        }
        throw new StatsApiException(404, "not_found", "Recording was not found");
    }

    private static void requireMethod(HttpExchange exchange, String method)
    {
        if(!method.equals(exchange.getRequestMethod()))
        {
            exchange.getResponseHeaders().set("Allow", method);
            throw new StatsApiException(405, "method_not_allowed", "Method not allowed");
        }
    }

}
