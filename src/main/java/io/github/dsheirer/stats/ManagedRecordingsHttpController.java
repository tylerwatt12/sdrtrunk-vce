/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.record.RecordPreference;
import io.github.dsheirer.preference.record.RecordingMode;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.record.managed.ManagedRecordingTranscriptionService;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.http.ApiRequestDecoder;
import io.github.dsheirer.web.http.WebRequestSecurity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
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
    private static final Semaphore RECORDING_SEARCHES = new Semaphore(4);
    private static final Semaphore LABEL_LOOKUPS = new Semaphore(4);
    private final Supplier<ManagedRecordingCatalog> mCatalog;
    private final UserPreferences mPreferences;
    private final WebRequestSecurity mSecurity;
    private final ManagedRecordingLabels mLabels;
    private final ManagedRecordingMaintenance mMaintenance;
    private final Supplier<ManagedRecordingTranscriptionService> mTranscriptionService;

    ManagedRecordingsHttpController(Supplier<ManagedRecordingCatalog> catalog, UserPreferences preferences,
                                    WebRequestSecurity security, ManagedRecordingMaintenance maintenance)
    {
        this(catalog, preferences, security, maintenance, new ManagedRecordingLabels(preferences), () -> null);
    }

    ManagedRecordingsHttpController(Supplier<ManagedRecordingCatalog> catalog, UserPreferences preferences,
                                    WebRequestSecurity security, ManagedRecordingMaintenance maintenance,
                                    Supplier<ManagedRecordingTranscriptionService> transcriptionService)
    {
        this(catalog, preferences, security, maintenance, new ManagedRecordingLabels(preferences),
            transcriptionService);
    }

    ManagedRecordingsHttpController(Supplier<ManagedRecordingCatalog> catalog, UserPreferences preferences,
                                    WebRequestSecurity security, ManagedRecordingMaintenance maintenance,
                                    ManagedRecordingLabels labels)
    {
        this(catalog, preferences, security, maintenance, labels, () -> null);
    }

    ManagedRecordingsHttpController(Supplier<ManagedRecordingCatalog> catalog, UserPreferences preferences,
                                    WebRequestSecurity security, ManagedRecordingMaintenance maintenance,
                                    ManagedRecordingLabels labels,
                                    Supplier<ManagedRecordingTranscriptionService> transcriptionService)
    {
        mCatalog = catalog;
        mPreferences = preferences;
        mSecurity = security;
        mLabels = labels;
        mMaintenance = maintenance;
        mTranscriptionService = transcriptionService;
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
                if(!RECORDING_SEARCHES.tryAcquire())
                {
                    throw new StatsApiException(429, "search_busy", "Too many recording searches are active. Try again.");
                }
                try
                {
                    search(exchange, catalog);
                }
                finally
                {
                    RECORDING_SEARCHES.release();
                }
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
                    Map<String,Object> detail = mLabels.decorate(List.of(call.toMap())).getFirst();
                    ManagedRecordingCatalog.Transcript storedTranscript = catalog.transcription(id);
                    if(storedTranscript == null)
                    {
                        throw new StatsApiException(404, "recording_not_found", "Recording was not found");
                    }
                    Map<String,Object> transcription = new LinkedHashMap<>(storedTranscript.toMap());
                    if("pending".equals(transcription.get("status")) &&
                        !mPreferences.getRecordPreference().isTranscriptionEnabled())
                    {
                        transcription.put("status", "disabled");
                    }
                    else if("pending".equals(transcription.get("status")) &&
                        call.durationMs() < mPreferences.getRecordPreference().getTranscriptionMinimumDurationMs())
                    {
                        transcription.put("status", "too_short");
                    }
                    detail.put("transcription", transcription);
                    ApiHttpResponse.sendData(exchange, 200, detail);
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
            if(exception.getErrorCode() == 9)
            {
                ApiHttpResponse.sendError(exchange, 503, "recording_search_timeout",
                    "This search took too long. Choose a shorter time range or add a system or talkgroup filter.");
                return;
            }
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
                Map<String,Object> transcription = new LinkedHashMap<>(catalog.transcriptionCounts(
                    mPreferences.getRecordPreference().getTranscriptionMinimumDurationMs()).toMap());
                ManagedRecordingTranscriptionService service = mTranscriptionService.get();
                if(service != null)
                {
                    transcription.putAll(service.status());
                }
                else
                {
                    transcription.put("active", false);
                    transcription.put("last_error", null);
                }
                status.put("transcription", transcription);
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
            String retryPrefix = ADMIN_PATH + "/calls/";
            String retrySuffix = "/transcription/retry";
            if(path.startsWith(retryPrefix) && path.endsWith(retrySuffix))
            {
                requireMethod(exchange, "POST");
                long id = positiveId(path.substring(retryPrefix.length(), path.length() - retrySuffix.length()));
                if(hasBody(exchange))
                {
                    throw new StatsApiException(400, "invalid_request", "This operation takes no request body");
                }
                ManagedRecordingCatalog.RecordingCall call = catalog.find(id);
                if(call == null)
                {
                    throw new StatsApiException(404, "recording_not_found", "Recording was not found");
                }
                if(call.durationMs() < mPreferences.getRecordPreference().getTranscriptionMinimumDurationMs())
                {
                    throw new StatsApiException(409, "recording_too_short",
                        "Recording is shorter than the minimum transcription duration");
                }
                if(!catalog.retryTranscription(id))
                {
                    throw new StatsApiException(409, "transcription_not_retryable",
                        "Recording transcription is not retryable");
                }
                ApiHttpResponse.sendData(exchange, 202, Map.of("status",
                    mPreferences.getRecordPreference().isTranscriptionEnabled() ? "pending" : "disabled"));
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
            .transcript(request.text("transcript"))
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
                List<ManagedRecordingCatalog.IdentityNameMatch> identities;
                try
                {
                    identities = mLabels.matchingIdentityNames(query, request.text("system_key"),
                        MAXIMUM_SEARCH_IDENTITIES);
                }
                finally
                {
                    LABEL_LOOKUPS.release();
                }
                if(identities.isEmpty())
                {
                    request.requireFullyConsumed();
                    ApiHttpResponse.sendData(exchange, 200, Map.of("calls", List.of()));
                    return;
                }
                filter.identityNameMatches(identities);
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
        Map<Long,String> excerpts = catalog.transcriptExcerpts(rows.stream()
            .map(row -> ((Number)row.get("id")).longValue()).toList());
        rows = rows.stream().map(row -> {
            Map<String,Object> enriched = new LinkedHashMap<>(row);
            String excerpt = excerpts.get(((Number)row.get("id")).longValue());
            if(excerpt != null && !excerpt.isBlank()) enriched.put("transcript_excerpt", excerpt);
            return enriched;
        }).toList();
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
            "destination_radio_description", "destination_radio_group", "destination_radio_ota_alias"))
        {
            Object value = row.get(field);
            if(value instanceof String text && text.toLowerCase(Locale.ROOT).contains(lowercaseQuery))
            {
                return true;
            }
        }
        if(row.get("patch_members") instanceof List<?> members)
        {
            for(Object member: members)
            {
                if(member instanceof Map<?,?> values && values.get("ota_alias") instanceof String text &&
                    text.toLowerCase(Locale.ROOT).contains(lowercaseQuery)) return true;
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
        if(query == null || (query.length() < 2 && !query.matches("[0-9]")))
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
        try
        {
            List<Map<String,Object>> suggestions = new ArrayList<>(
                mLabels.suggestions(query, kind, systemKey, limit, catalog.databaseFile()));
            Set<String> seen = new LinkedHashSet<>();
            Set<String> seenSites = new LinkedHashSet<>();
            for(Map<String,Object> row: suggestions)
            {
                seen.add(row.get("kind") + ":" + row.get("id"));
                if("site".equals(row.get("kind")) && row.get("wacn") != null && row.get("sysid") != null &&
                    row.get("rfss") != null && row.get("site_id") != null)
                {
                    seenSites.add(row.get("wacn") + ":" + row.get("sysid") + ":" + row.get("rfss") +
                        ":" + row.get("site_id"));
                }
            }
            if(query.matches("[0-9]{1,10}"))
            {
                List<String> identityKinds = kind == null ? List.of("talkgroup", "radio") :
                    Set.of("talkgroup", "radio").contains(kind) ? List.of(kind) : List.of();
                List<Map<String,Object>> exact = new ArrayList<>();
                // Verify exact numeric matches even when substring labels have filled the requested limit.
                for(String identityKind: identityKinds)
                {
                    List<Integer> identities = catalog.identitySuggestions(systemKey, query,
                        "radio".equals(identityKind), 1);
                    if(identities.isEmpty() || !identities.getFirst().toString().equals(query)) continue;
                    Map<String,Object> row = suggestions.stream().filter(candidate ->
                        identityKind.equals(candidate.get("kind")) && query.equals(candidate.get("id")))
                        .findFirst().orElse(null);
                    if(row == null)
                    {
                        // A general finder can fill its first page with systems or channels before reaching aliases.
                        row = mLabels.suggestions(query, identityKind, systemKey, 1, catalog.databaseFile())
                            .stream().filter(candidate -> identityKind.equals(candidate.get("kind")) &&
                                query.equals(candidate.get("id"))).findFirst().orElse(null);
                    }
                    if(row == null) row = numericSuggestion(identityKind, identities.getFirst(), systemKey);
                    suggestions.removeIf(candidate -> identityKind.equals(candidate.get("kind")) &&
                        query.equals(candidate.get("id")));
                    exact.add(row);
                    seen.add(identityKind + ":" + query);
                }
                suggestions.addAll(0, exact);
                for(String identityKind: identityKinds)
                {
                    if(suggestions.size() >= limit) break;
                    for(Integer identity: catalog.identitySuggestions(systemKey, query,
                        "radio".equals(identityKind), limit))
                    {
                        if(seen.add(identityKind + ":" + identity))
                        {
                            suggestions.add(numericSuggestion(identityKind, identity, systemKey));
                            if(suggestions.size() >= limit) break;
                        }
                    }
                }
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
                for(String id: catalog.channelIds(systemKey, query, limit - suggestions.size()))
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
                for(ManagedRecordingCatalog.Site site: catalog.sites(systemKey, query, limit))
                {
                    if(!seenSites.add(site.wacn() + ":" + site.systemId() + ":" + site.rfss() + ":" + site.siteId()))
                    {
                        continue;
                    }
                    String label = "RFSS " + site.rfss() + " · Site " + site.siteId();
                    Map<String,Object> row = new LinkedHashMap<>(site.toMap());
                    row.put("kind", "site");
                    row.put("id", site.rfss() + ":" + site.siteId());
                    row.put("label", label);
                    if(systemKey != null) row.put("system_key", systemKey);
                    row.put("sysid", site.systemId());
                    row.put("rfss", site.rfss());
                    suggestions.add(row);
                    if(suggestions.size() >= limit) break;
                }
            }
            ApiHttpResponse.sendData(exchange, 200,
                mLabels.enrichSystemNames(suggestions.size() > limit ? suggestions.subList(0, limit) : suggestions));
        }
        finally
        {
            LABEL_LOOKUPS.release();
        }
    }

    private static Map<String,Object> numericSuggestion(String kind, Integer identity, String systemKey)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("id", identity.toString());
        row.put("label", ("talkgroup".equals(kind) ? "Talkgroup " : "Radio ") + identity);
        if(systemKey != null) row.put("system_key", systemKey);
        return row;
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
        RecordPreference record = mPreferences.getRecordPreference();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("mode", record.getRecordingMode().name());
        result.put("retention_days", record.getManagedRetentionDays());
        result.put("managed_directory",
            mPreferences.getDirectoryPreference().getDirectoryManagedRecording().toString());
        result.put("available", mCatalog.get() != null);
        result.put("transcription_enabled", record.isTranscriptionEnabled());
        result.put("transcription_url", record.getTranscriptionUrl());
        result.put("transcription_model", record.getTranscriptionModel());
        result.put("transcription_min_duration_ms", record.getTranscriptionMinimumDurationMs());
        result.put("transcription_key_configured", record.isTranscriptionApiKeyConfigured());
        return result;
    }

    private void updateSettings(HttpExchange exchange) throws IOException
    {
        JsonNode body = json(exchange);
        rejectUnknownFields(body, Set.of("mode", "retention_days", "transcription_enabled",
            "transcription_url", "transcription_model", "transcription_min_duration_ms",
            "transcription_api_key", "transcription_clear_api_key"));
        JsonNode rawMode = body.get("mode");
        JsonNode rawRetention = body.get("retention_days");
        JsonNode rawEnabled = body.get("transcription_enabled");
        JsonNode rawUrl = body.get("transcription_url");
        JsonNode rawModel = body.get("transcription_model");
        JsonNode rawMinimum = body.get("transcription_min_duration_ms");
        JsonNode rawKey = body.get("transcription_api_key");
        JsonNode rawClearKey = body.get("transcription_clear_api_key");
        if(body.isEmpty())
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
        RecordPreference record = mPreferences.getRecordPreference();
        boolean enabled = record.isTranscriptionEnabled();
        if(rawEnabled != null)
        {
            if(!rawEnabled.isBoolean())
            {
                throw new StatsApiException(400, "invalid_transcription_enabled",
                    "Transcription enabled must be true or false", "transcription_enabled");
            }
            enabled = rawEnabled.booleanValue();
        }
        String url = record.getTranscriptionUrl();
        if(rawUrl != null)
        {
            if(!rawUrl.isTextual() || rawUrl.textValue().length() > 2048)
            {
                throw new StatsApiException(400, "invalid_transcription_url",
                    "Transcription URL is invalid", "transcription_url");
            }
            url = rawUrl.textValue().trim();
            validateTranscriptionUrl(url);
        }
        String model = record.getTranscriptionModel();
        if(rawModel != null)
        {
            if(!rawModel.isTextual() || rawModel.textValue().length() > 200)
            {
                throw new StatsApiException(400, "invalid_transcription_model",
                    "Transcription model is invalid", "transcription_model");
            }
            model = rawModel.textValue().trim();
            if(model.indexOf('\n') >= 0 || model.indexOf('\r') >= 0)
            {
                throw new StatsApiException(400, "invalid_transcription_model",
                    "Transcription model is invalid", "transcription_model");
            }
        }
        Long minimum = null;
        if(rawMinimum != null)
        {
            if(!rawMinimum.isIntegralNumber() || !rawMinimum.canConvertToLong() ||
                rawMinimum.longValue() < RecordPreference.DEFAULT_TRANSCRIPTION_MIN_DURATION_MS ||
                rawMinimum.longValue() > RecordPreference.MAX_TRANSCRIPTION_MIN_DURATION_MS)
            {
                throw new StatsApiException(400, "invalid_transcription_min_duration_ms",
                    "Minimum duration must be 500 to 600000 milliseconds", "transcription_min_duration_ms");
            }
            minimum = rawMinimum.longValue();
        }
        String key = null;
        if(rawKey != null)
        {
            if(!rawKey.isTextual() || rawKey.textValue().length() > 1024 ||
                rawKey.textValue().chars().anyMatch(Character::isISOControl))
            {
                throw new StatsApiException(400, "invalid_transcription_api_key",
                    "Transcription API key is invalid", "transcription_api_key");
            }
            key = rawKey.textValue().trim();
        }
        boolean clearKey = false;
        if(rawClearKey != null)
        {
            if(!rawClearKey.isBoolean())
            {
                throw new StatsApiException(400, "invalid_transcription_clear_api_key",
                    "Clear API key must be true or false", "transcription_clear_api_key");
            }
            clearKey = rawClearKey.booleanValue();
        }
        if(clearKey && key != null && !key.isEmpty())
        {
            throw new StatsApiException(400, "invalid_request",
                "API key cannot be set and cleared together");
        }
        if(enabled)
        {
            if(mCatalog.get() == null)
            {
                throw new StatsApiException(503, "recordings_unavailable",
                    "Transcription cannot be enabled while the catalog is unavailable");
            }
            if(url.isEmpty() || model.isEmpty())
            {
                throw new StatsApiException(400, "incomplete_transcription_settings",
                    "Set the transcription URL and model before enabling transcription");
            }
        }
        if(rawRetention != null)
        {
            record.setManagedRetentionDays(retention);
        }
        if(mode != null)
        {
            record.setRecordingMode(mode);
        }
        if(rawUrl != null)
        {
            record.setTranscriptionUrl(url);
        }
        if(rawModel != null)
        {
            record.setTranscriptionModel(model);
        }
        if(minimum != null)
        {
            record.setTranscriptionMinimumDurationMs(minimum);
        }
        if(clearKey)
        {
            record.setTranscriptionApiKey("");
        }
        else if(key != null && !key.isEmpty())
        {
            record.setTranscriptionApiKey(key);
        }
        if(rawEnabled != null)
        {
            record.setTranscriptionEnabled(enabled);
        }
        ApiHttpResponse.sendData(exchange, 200, settings());
    }

    private static void validateTranscriptionUrl(String value)
    {
        if(value.isEmpty())
        {
            return;
        }
        try
        {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) ||
                uri.getHost() == null || uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null || uri.getRawFragment() != null ||
                !uri.getPath().endsWith("/audio/transcriptions"))
            {
                throw new IllegalArgumentException("Invalid URL");
            }
        }
        catch(IllegalArgumentException exception)
        {
            throw new StatsApiException(400, "invalid_transcription_url",
                "Enter a full HTTP or HTTPS /audio/transcriptions endpoint", "transcription_url");
        }
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
