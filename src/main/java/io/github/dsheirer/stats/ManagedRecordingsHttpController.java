/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
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
import java.time.Duration;
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
    private final StatsBrowseReadCache mBrowseReads;
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
        this(catalog, preferences, security, maintenance, labels, transcriptionService, new StatsBrowseReadCache(4));
    }

    ManagedRecordingsHttpController(Supplier<ManagedRecordingCatalog> catalog, UserPreferences preferences,
                                    WebRequestSecurity security, ManagedRecordingMaintenance maintenance,
                                    ManagedRecordingLabels labels,
                                    Supplier<ManagedRecordingTranscriptionService> transcriptionService,
                                    StatsBrowseReadCache browseReads)
    {
        mCatalog = catalog;
        mPreferences = preferences;
        mSecurity = security;
        mLabels = labels;
        mMaintenance = maintenance;
        mTranscriptionService = transcriptionService;
        mBrowseReads = java.util.Objects.requireNonNull(browseReads);
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
                ManagedRecordingCatalog catalog = mCatalog.get();
                ApiHttpResponse.sendPreparedJson(exchange, 200, mBrowseReads.get(catalog,
                    exchange.getRequestURI(), () -> ApiHttpResponse.prepareData(browseStatus(catalog))));
                return;
            }
            ManagedRecordingCatalog catalog = available();
            if((BROWSE_PATH + "/calls").equals(path))
            {
                requireMethod(exchange, "GET");
                sendBrowse(exchange, catalog, () -> search(exchange.getRequestURI(), catalog));
                return;
            }
            if((BROWSE_PATH + "/entities").equals(path))
            {
                requireMethod(exchange, "GET");
                sendBrowse(exchange, catalog, () -> entities(exchange.getRequestURI(), catalog));
                return;
            }
            if((BROWSE_PATH + "/identities").equals(path))
            {
                requireMethod(exchange, "GET");
                sendBrowse(exchange, catalog, () -> identities(exchange.getRequestURI(), catalog));
                return;
            }
            if((BROWSE_PATH + "/suggestions").equals(path))
            {
                requireMethod(exchange, "GET");
                sendBrowse(exchange, catalog, () -> suggest(exchange.getRequestURI(), catalog));
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
        finally
        {
            if(!"GET".equals(exchange.getRequestMethod()) && exchange.getResponseCode() >= 200 &&
                exchange.getResponseCode() < 300) mBrowseReads.clear();
        }
    }

    private void sendBrowse(HttpExchange exchange, ManagedRecordingCatalog catalog, BrowseRead read)
        throws IOException, SQLException
    {
        byte[] response = mBrowseReads.get(catalog, exchange.getRequestURI(),
            () -> ApiHttpResponse.prepareData(read.get()));
        ApiHttpResponse.sendPreparedJson(exchange, 200, response);
    }

    @FunctionalInterface
    private interface BrowseRead { Object get() throws IOException, SQLException; }

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
    private Map<String,Object> browseStatus(ManagedRecordingCatalog catalog) throws SQLException
    {
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

    private static ManagedRecordingCatalog.IdentityNameMatch recordingIdentity(String key, String systemKey,
                                                                               boolean group)
    {
        if(key == null) return null;
        try
        {
            RadioSystemIdentityKey.Identity identity = RadioSystemIdentityKey.parse(key);
            if((group ? identity.kindCode() != RadioSystemIdentityKey.KIND_TALKGROUP &&
                identity.kindCode() != RadioSystemIdentityKey.KIND_PATCH_GROUP :
                identity.kindCode() != RadioSystemIdentityKey.KIND_RADIO) || !identity.hasHome() ||
                !RadioSystemKey.isP25Native(systemKey))
            {
                throw new IllegalArgumentException("A complete P25 radio and receiving system are required");
            }
            boolean local = RadioSystemKey.p25(identity.homeWacn(), identity.homeSystemId()).equals(systemKey);
            return new ManagedRecordingCatalog.IdentityNameMatch(identity.identityId(), systemKey,
                identity.homeWacn(), identity.homeSystemId(), local);
        }
        catch(IllegalArgumentException exception)
        {
            throw new StatsApiException(400, group ? "invalid_talkgroup_identity" : "invalid_radio_identity",
                group ? "Select a P25 talkgroup and its receiving system" :
                    "Select a P25 radio and its receiving system",
                group ? "talkgroup_identity_key" : "radio_identity_key");
        }
    }

    private Map<String,Object> search(URI uri, ManagedRecordingCatalog catalog) throws SQLException, IOException
    {
        StatsRequest request = StatsRequest.from(uri,
            Map.of("talkgroups", 16384, "radios", 49152, "channel_ids", 32768), 262144, 48);
        List<ManagedRecordingCatalog.Selection> talkgroups = selections(request, "talkgroups", false);
        List<ManagedRecordingCatalog.Selection> radios = selections(request, "radios", true);
        List<String> channels = channelSelections(request);
        boolean targetsAny = Boolean.TRUE.equals(optionalFlag(request, "targets_any"));
        Boolean hasTranscript = optionalFlag(request, "has_transcript");
        boolean highInterest = Boolean.TRUE.equals(optionalFlag(request, "high_interest"));
        String transcript = request.text("transcript");
        String textRange = request.text("text_range");
        if(textRange != null && !Set.of("recent", "time").contains(textRange))
            throw new StatsApiException(400, "invalid_parameter", "Choose Past 24 hours or Use Time filter", "text_range");
        Long from = request.optionalLong("from_ms"), to = request.optionalLong("to_ms");
        if(transcript != null && !"time".equals(textRange))
        {
            long now = System.currentTimeMillis(), day = Duration.ofDays(1).toMillis();
            // Retain a recent submitted anchor across pages; an old saved custom period needs Use Time filter.
            to = to != null && to >= Math.max(0, now - day) && to <= now ? to : now;
            from = Math.max(0, to - day);
        }
        ManagedRecordingCatalog.SearchFilter period = ManagedRecordingCatalog.SearchFilter.builder()
            .fromMs(from).toMs(to).build();
        Long minimumDuration = request.optionalLong("min_duration_ms");
        if(highInterest) minimumDuration = Math.max(10_000, minimumDuration != null ? minimumDuration : 0);
        Integer talkgroupId = request.optionalInt("talkgroup_id");
        Integer radioId = request.optionalInt("radio_id");
        String systemKey = request.text("system_key");
        ManagedRecordingCatalog.IdentityNameMatch radioIdentity = recordingIdentity(
            request.text("radio_identity_key"), systemKey, false);
        ManagedRecordingCatalog.IdentityNameMatch groupIdentity = recordingIdentity(
            request.text("talkgroup_identity_key"), systemKey, true);
        Integer talkgroupMin = request.optionalInt("talkgroup_min");
        Integer talkgroupMax = request.optionalInt("talkgroup_max");
        Integer radioMin = request.optionalInt("radio_min");
        Integer radioMax = request.optionalInt("radio_max");
        boolean identityConstrained = talkgroupId != null || radioId != null || radioIdentity != null || groupIdentity != null ||
            talkgroupMin != null || radioMin != null || !talkgroups.isEmpty() || !radios.isEmpty();
        ManagedRecordingCatalog.SearchFilter.Builder filter = ManagedRecordingCatalog.SearchFilter.builder()
            .fromMs(period.fromMs)
            .toMs(period.toMs)
            .systemKey(systemKey)
            .channelId(request.text("channel_id"))
            .channelIds(channels)
            .targetsAny(targetsAny)
            .aliasListId(request.optionalLong("alias_list_id"))
            .wacn(request.optionalInt("wacn"))
            .systemId(request.optionalInt("sysid"))
            .rfss(request.optionalInt("rfss"))
            .siteId(request.optionalInt("site_id"))
            .minDurationMs(minimumDuration)
            .maxDurationMs(request.optionalLong("max_duration_ms"))
            .talkgroupId(talkgroupId)
            .sourceId(radioId)
            .talkgroupSelections(talkgroups)
            .radioSelections(radios)
            .radioRole(radioRole(request))
            .pagingTones(request.text("paging_tones"))
            .toneFrequencyHz(optionalDouble(request, "tone_frequency_hz"))
            .toneThenHz(optionalDouble(request, "tone_then_hz"))
            .toneToleranceHz(optionalDouble(request, "tone_tolerance_hz"))
            .dcsCode(request.text("dcs_code"))
            .radioIdentityMatch(radioIdentity)
            .talkgroupIdentityMatch(groupIdentity)
            .talkgroupMin(talkgroupMin)
            .talkgroupMax(talkgroupMax)
            .sourceMin(radioMin)
            .sourceMax(radioMax)
            .frequencyHz(request.optionalLong("frequency_hz"))
            .protocol(request.text("protocol"))
            .callType(request.text("call_type"))
            .voiceType(request.text("voice_type"))
            .transcript(transcript)
            .hasTranscript(hasTranscript)
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
                List<ManagedRecordingCatalog.IdentityNameMatch> identities = mLabels.matchingIdentityNames(
                    query, systemKey, MAXIMUM_SEARCH_IDENTITIES);
                if(identities.isEmpty())
                {
                    request.requireFullyConsumed();
                    return Map.of("calls", List.of(), "from_ms", period.fromMs, "to_ms", period.toMs);
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
        payload.put("from_ms", period.fromMs);
        payload.put("to_ms", period.toMs);
        if(page.nextCursor() != null)
        {
            payload.put("next_cursor", page.nextCursor());
        }
        return payload;
    }

    private static List<ManagedRecordingCatalog.Selection> selections(StatsRequest request, String field,
                                                                        boolean radio)
    {
        String value = request.text(field);
        if(value == null) return List.of();
        try
        {
            JsonNode values = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
            if(values == null || !values.isArray() || values.size() > 100)
                throw new IllegalArgumentException();
            Set<ManagedRecordingCatalog.Selection> selections = new LinkedHashSet<>();
            for(JsonNode entry: values)
            {
                if(!entry.isObject() || !entry.has("id") ||
                    !entry.get("id").isNull() && (!entry.get("id").isIntegralNumber() || !entry.get("id").canConvertToInt()) ||
                    !entry.has("system_key")) throw new IllegalArgumentException();
                rejectUnknownFields(entry, Set.of("id", "system_key", "identity_key", "protocol", "value"));
                JsonNode system = entry.get("system_key");
                JsonNode identity = entry.get("identity_key");
                if(!system.isNull() && !system.isTextual() ||
                    identity != null && !identity.isNull() && !identity.isTextual())
                    throw new IllegalArgumentException();
                String key = identity != null && identity.isTextual() ? identity.textValue() : null;
                if(key != null)
                {
                    int kind = RadioSystemIdentityKey.parse(key).kindCode();
                    if(radio ? kind != RadioSystemIdentityKey.KIND_RADIO :
                        kind != RadioSystemIdentityKey.KIND_TALKGROUP && kind != RadioSystemIdentityKey.KIND_PATCH_GROUP)
                        throw new IllegalArgumentException();
                }
                String protocol = selectionText(entry,"protocol");
                String typedValue = selectionText(entry,"value");
                if(!radio && typedValue != null) throw new IllegalArgumentException();
                selections.add(new ManagedRecordingCatalog.Selection(entry.get("id").isNull() ? null : entry.get("id").intValue(),
                    system.isNull() ? null : system.textValue(), key, protocol, typedValue));
            }
            return List.copyOf(selections);
        }
        catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException exception)
        {
            throw new StatsApiException(400, "invalid_selection", "Select up to 100 valid " +
                (radio ? "radios" : "talkgroups") + " and their systems", field);
        }
    }

    private static String selectionText(JsonNode row, String field)
    {
        JsonNode value = row.get(field);
        if(value == null || value.isNull()) return null;
        if(!value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }

    private static List<String> channelSelections(StatsRequest request)
    {
        String value = request.text("channel_ids");
        if(value == null) return List.of();
        try
        {
            JsonNode entries = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
            if(entries == null || !entries.isArray() || entries.size() > 100) throw new IllegalArgumentException();
            Set<String> channels = new LinkedHashSet<>();
            for(JsonNode entry: entries)
            {
                if(!entry.isTextual() || entry.textValue().isBlank() || entry.textValue().length() > 128 ||
                    entry.textValue().chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
                channels.add(entry.textValue());
            }
            return List.copyOf(channels);
        }
        catch(IOException | IllegalArgumentException exception)
        {
            throw new StatsApiException(400, "invalid_selection", "Select up to 100 valid channels", "channel_ids");
        }
    }

    private static Boolean optionalFlag(StatsRequest request, String field)
    {
        String value = request.text(field);
        if(value == null) return null;
        if("true".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if("false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        throw new StatsApiException(400, "invalid_parameter", "Choose true or false", field);
    }

    private static Double optionalDouble(StatsRequest request, String field)
    {
        String value = request.text(field);
        if(value == null) return null;
        try
        {
            double parsed = Double.parseDouble(value);
            if(!Double.isFinite(parsed)) throw new NumberFormatException();
            return parsed;
        }
        catch(NumberFormatException exception)
        {
            throw new StatsApiException(400, "invalid_parameter", field + " must be a finite number", field);
        }
    }

    private static String radioRole(StatsRequest request)
    {
        String role = request.text("radio_role");
        if(role == null) return "any";
        if(!Set.of("source", "any").contains(role))
            throw new StatsApiException(400, "invalid_parameter", "Choose Source radio or Any participant", "radio_role");
        return role;
    }

    private Map<String,Object> identities(URI uri, ManagedRecordingCatalog catalog) throws SQLException, IOException
    {
        StatsRequest request = StatsRequest.from(uri);
        String kind = request.requiredText("kind");
        if(!Set.of("talkgroup", "radio").contains(kind))
            throw new StatsApiException(400, "invalid_kind", "Choose talkgroups or radios", "kind");
        String query = request.search();
        String system = request.text("system_key");
        if(system != null && !RadioSystemKey.isCanonical(system))
            throw new StatsApiException(400, "invalid_parameter", "Select a valid system", "system_key");
        ManagedRecordingCatalog.SearchFilter period = ManagedRecordingCatalog.SearchFilter.builder()
            .fromMs(request.optionalLong("from_ms")).toMs(request.optionalLong("to_ms")).build();
        String role = radioRole(request);
        int limit = request.limit(100);
        int offset = request.offset();
        String sort = ManagedRecordingBrowseOrder.sort(request.text("sort"));
        boolean grouped = request.booleanValue("grouped", false);
        String groupKey = pickerGroup(request, grouped, offset);
        request.requireFullyConsumed();
        return grouped ? mLabels.identityGroups(catalog.databaseFile(), kind,
                query, system, period.fromMs, period.toMs, offset, limit, role, sort, groupKey) :
                mLabels.identities(catalog.databaseFile(), kind, query,
                    system, period.fromMs, period.toMs, offset, limit, role, sort);
    }

    private Map<String,Object> entities(URI uri, ManagedRecordingCatalog catalog) throws SQLException, IOException
    {
        StatsRequest request = StatsRequest.from(uri);
        String kind = request.requiredText("kind");
        if(!Set.of("system", "site", "channel", "dcs").contains(kind))
            throw new StatsApiException(400, "invalid_kind", "Choose systems, sites, channels, or DCS codes", "kind");
        String query = request.search();
        String system = request.text("system_key");
        if(system != null && !RadioSystemKey.isCanonical(system))
            throw new StatsApiException(400, "invalid_parameter", "Select a valid system", "system_key");
        ManagedRecordingCatalog.SearchFilter period = ManagedRecordingCatalog.SearchFilter.builder()
            .fromMs(request.optionalLong("from_ms")).toMs(request.optionalLong("to_ms")).build();
        String sort = ManagedRecordingBrowseOrder.sort(request.text("sort"));
        int limit = request.limit(100);
        int offset = request.offset();
        boolean grouped = request.booleanValue("grouped", false);
        String groupKey = pickerGroup(request, grouped, offset);
        request.requireFullyConsumed();
        return grouped ? mLabels.entityGroups(catalog.databaseFile(), kind,
                query, system, period.fromMs, period.toMs, offset, limit, sort, groupKey) :
                mLabels.entities(catalog.databaseFile(), kind, query,
                    system, period.fromMs, period.toMs, offset, limit, sort);
    }

    private static String pickerGroup(StatsRequest request, boolean grouped, int offset)
    {
        String key = ManagedRecordingPickerGroups.validateKey(request.text("group_key"));
        if(key != null && !grouped || grouped && offset > 0 && key == null)
            throw new StatsApiException(400, "invalid_parameter", "Choose a group to load more choices", "group_key");
        return key;
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
                if(member instanceof Map<?,?> values)
                {
                    for(String field: List.of("alias", "description", "group", "ota_alias"))
                    {
                        if(values.get(field) instanceof String text &&
                            text.toLowerCase(Locale.ROOT).contains(lowercaseQuery)) return true;
                    }
                }
            }
        }
        return false;
    }

    private List<Map<String,Object>> suggest(URI uri, ManagedRecordingCatalog catalog) throws SQLException, IOException
    {
        StatsRequest request = StatsRequest.from(uri);
        String query = request.search();
        String kind = request.text("kind");
        String systemKey = request.text("system_key");
        ManagedRecordingCatalog.SearchFilter period = ManagedRecordingCatalog.SearchFilter.builder()
            .fromMs(request.optionalLong("from_ms")).toMs(request.optionalLong("to_ms")).build();
        int limit = request.limit(20);
        request.requireFullyConsumed();
        if(query == null || (query.length() < 2 && !query.matches("[0-9]")))
        {
            return List.of();
        }
        if(kind != null && !Set.of("system", "site", "talkgroup", "radio", "channel").contains(kind))
        {
            throw new StatsApiException(400, "invalid_kind", "Suggestion kind is invalid", "kind");
        }
        List<Map<String,Object>> suggestions = new ArrayList<>(
            mLabels.facetSuggestions(query, kind, systemKey, limit, catalog.databaseFile()));
        if(kind == null || "talkgroup".equals(kind) || "radio".equals(kind))
        {
            //Identity suggestions use the same stable selections as the focused picker, including latest TA.
            for(String identityKind: kind == null ? List.of("talkgroup", "radio") : List.of(kind))
            {
                Map<String,Object> discovered = mLabels.identities(catalog.databaseFile(), identityKind,
                    query, systemKey, period.fromMs, period.toMs, 0, limit, "any");
                if(discovered.get("rows") instanceof List<?> identities)
                {
                    for(Object value: identities)
                    {
                        if(!(value instanceof Map<?,?> identity)) continue;
                        Map<String,Object> row = new LinkedHashMap<>();
                        identity.forEach((field, item) -> { if(field instanceof String key) row.put(key, item); });
                        row.put("id", String.valueOf(row.get("id") != null ? row.get("id") : row.get("value")));
                        if(!row.containsKey("alias") && !row.containsKey("ota_alias"))
                            row.put("label", ("talkgroup".equals(identityKind) ? "Talkgroup " : "Radio ") + row.get("id"));
                        List<String> detail = new ArrayList<>();
                        if(row.get("group") instanceof String group) detail.add(group);
                        if(row.get("system_name") instanceof String name) detail.add(name);
                        else if(row.get("system_key") instanceof String key) detail.add(key);
                        if(!detail.isEmpty()) row.put("detail", String.join(" · ", detail));
                        suggestions.add(row);
                    }
                }
            }
        }
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
            List<Map<String,Object>> exact = suggestions.stream().filter(row ->
                ("talkgroup".equals(row.get("kind")) || "radio".equals(row.get("kind"))) &&
                    query.equals(row.get("id"))).toList();
            suggestions.removeAll(exact);
            suggestions.addAll(0, exact);
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
        return mLabels.enrichSystemNames(suggestions.size() > limit ? suggestions.subList(0, limit) : suggestions);
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
