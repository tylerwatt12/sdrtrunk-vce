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
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchDescriptor;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.radio.P25FullyQualifiedRadio;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioFormat;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.P25FullyQualifiedTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormat;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.protocol.Protocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strict JSON adapter for administrator alias-list changes. Register each context with
 * {@link WebAccessHttpController#protectApi} so every request is administrator-only and mutations also require the
 * existing same-origin and CSRF checks.
 */
public final class AliasAdminHttpController
{
    public static final String ALIAS_LISTS_PATH = "/api/v1/admin/alias-lists";
    public static final String ALIASES_PATH = "/api/v1/admin/aliases";
    public static final String OPTIONS_PATH = ALIASES_PATH + "/options";
    public static final String BULK_PATH = ALIASES_PATH + "/bulk";
    private static final Logger mLog = LoggerFactory.getLogger(AliasAdminHttpController.class);
    private static final int MAXIMUM_JSON_BODY_BYTES = 16 * 1024;
    private static final int MAXIMUM_TEXT_CHARACTERS = 256;
    private static final int MAXIMUM_DESCRIPTION_CHARACTERS = 4096;
    private static final int MAXIMUM_BROADCAST_CHANNELS = 64;
    private static final int MAXIMUM_TONES = 64;
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final Set<Protocol> SUPPORTED_PROTOCOLS = Set.of(Protocol.APCO25, Protocol.APCO25_PHASE2,
        Protocol.DMR, Protocol.NXDN, Protocol.NBFM, Protocol.FLEETSYNC, Protocol.MDC1200);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final AliasAdministrationService mService;

    public AliasAdminHttpController(AliasAdministrationService service)
    {
        mService = Objects.requireNonNull(service, "Alias administration service cannot be null");
    }

    /** Handles all alias-administration contexts. */
    public void handle(HttpExchange exchange) throws IOException
    {
        WebAccessHttpController.prepareSecurityHeaders(exchange);

        try
        {
            String path = exchange.getRequestURI().getPath();

            if(OPTIONS_PATH.equals(path))
            {
                handleOptions(exchange);
            }
            else if(BULK_PATH.equals(path))
            {
                handleBulk(exchange);
            }
            else if(path.equals(ALIAS_LISTS_PATH) || path.startsWith(ALIAS_LISTS_PATH + "/"))
            {
                handleAliasLists(exchange, path);
            }
            else if(path.equals(ALIASES_PATH) || path.startsWith(ALIASES_PATH + "/"))
            {
                handleAliases(exchange, path);
            }
            else
            {
                throw error(404, "not_found", "Not found");
            }
        }
        catch(RequestException exception)
        {
            sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(AliasAdministrationService.NotFoundException exception)
        {
            sendError(exchange, 404, "not_found", safeMessage(exception, "Alias configuration was not found"));
        }
        catch(AliasAdministrationService.StaleRevisionException exception)
        {
            sendError(exchange, 409, "stale_revision", "Alias configuration changed; reload and try again");
        }
        catch(AliasAdministrationService.ConfirmationRequiredException exception)
        {
            sendError(exchange, 409, "confirmation_required",
                "Confirm the alias-list deletion after reviewing its impact");
        }
        catch(AliasAdministrationService.NotInitializedException exception)
        {
            sendError(exchange, 503, "configuration_loading", "Alias configuration is still loading");
        }
        catch(AliasAdministrationService.ConfigurationBusyException exception)
        {
            exchange.getResponseHeaders().set("Retry-After", "1");
            sendError(exchange, 429, "configuration_busy", "Alias configuration is busy; try again");
        }
        catch(AliasAdministrationService.PersistenceException exception)
        {
            mLog.warn("Unable to persist alias administration change", exception);
            sendError(exchange, 503, "storage_unavailable", "The alias change could not be saved");
        }
        catch(IllegalArgumentException exception)
        {
            sendError(exchange, 400, "invalid_request", safeMessage(exception, "The alias request is invalid"));
        }
        catch(IllegalStateException exception)
        {
            sendError(exchange, 409, "conflict",
                safeMessage(exception, "The alias request conflicts with current configuration"));
        }
        catch(Exception exception)
        {
            if(exchange.getResponseCode() >= 0 && exception instanceof IOException ioException)
            {
                throw ioException;
            }

            mLog.warn("Unable to complete alias administration request", exception);
            sendError(exchange, 503, "storage_unavailable", "The alias change could not be saved");
        }
    }

    private void handleAliasLists(HttpExchange exchange, String path) throws Exception
    {
        if(ALIAS_LISTS_PATH.equals(path))
        {
            requireNoQuery(exchange);

            switch(exchange.getRequestMethod())
            {
                case "GET" -> {
                    requireNoBody(exchange);
                    AliasAdministrationService.Catalog catalog = mService.catalog();
                    sendJson(exchange, 200, Map.of("revision", catalog.revision(), "aliasLists",
                        catalog.aliasLists()));
                }
                case "POST" -> {
                    CreateListRequest request = readJson(exchange, CreateListRequest.class);
                    AliasAdministrationService.MutationResult result = mService.createAliasList(
                        requiredText(request.name(), "name", MAXIMUM_TEXT_CHARACTERS),
                        required(request.family(), "family"), requiredRevision(request.revision()));
                    exchange.getResponseHeaders().set("Location", ALIAS_LISTS_PATH + "/" + result.aliasListId());
                    sendJson(exchange, 201, result);
                }
                default -> methodNotAllowed(exchange, "GET, POST");
            }
            return;
        }

        String suffix = "/delete-impact";
        boolean impact = path.endsWith(suffix);
        long aliasListId = requiredItemId(impact ? path.substring(0, path.length() - suffix.length()) : path,
            ALIAS_LISTS_PATH);
        requireNoQuery(exchange);

        if(impact)
        {
            requireMethod(exchange, "GET");
            requireNoBody(exchange);
            sendJson(exchange, 200, mService.aliasListDeleteImpact(aliasListId));
            return;
        }

        switch(exchange.getRequestMethod())
        {
            case "DELETE" -> {
                DeleteListRequest request = readJson(exchange, DeleteListRequest.class);
                AliasAdministrationService.MutationResult result = mService.deleteAliasList(aliasListId,
                    requiredRevision(request.revision()), required(request.confirmed(), "confirmed"));
                sendJson(exchange, 200, result);
            }
            default -> methodNotAllowed(exchange, "DELETE");
        }
    }

    private void handleAliases(HttpExchange exchange, String path) throws Exception
    {
        if(ALIASES_PATH.equals(path))
        {
            requireNoQuery(exchange);

            switch(exchange.getRequestMethod())
            {
                case "POST" -> {
                    AliasRequest request = readJson(exchange, AliasRequest.class);
                    AliasAdministrationService.MutationResult result = mService.createAlias(
                        toAlias(request.alias()), requiredRevision(request.revision()));

                    if(!result.aliasIds().isEmpty())
                    {
                        exchange.getResponseHeaders().set("Location", ALIASES_PATH + "/" +
                            result.aliasIds().getFirst());
                    }
                    sendJson(exchange, 201, result);
                }
                default -> methodNotAllowed(exchange, "POST");
            }
            return;
        }

        requireNoQuery(exchange);
        long aliasId = requiredItemId(path, ALIASES_PATH);

        switch(exchange.getRequestMethod())
        {
            case "GET" -> {
                requireNoBody(exchange);
                AliasAdministrationService.AliasEntry entry = mService.getAlias(aliasId);
                sendJson(exchange, 200, Map.of("revision", entry.revision(), "alias", aliasView(entry.alias())));
            }
            case "PUT" -> {
                AliasRequest request = readJson(exchange, AliasRequest.class);
                sendJson(exchange, 200, mService.replaceAlias(aliasId, toAlias(request.alias()),
                    requiredRevision(request.revision())));
            }
            case "DELETE" -> {
                RevisionRequest request = readJson(exchange, RevisionRequest.class);
                sendJson(exchange, 200, mService.deleteAlias(aliasId, requiredRevision(request.revision())));
            }
            default -> methodNotAllowed(exchange, "GET, PUT, DELETE");
        }
    }

    private void handleOptions(HttpExchange exchange) throws Exception
    {
        requireMethod(exchange, "GET");
        requireNoBody(exchange);
        AliasAdministrationService.Options options = mService.options(requiredIdQuery(exchange, "aliasListId"));
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("revision", options.revision());
        response.put("aliasList", options.aliasList());
        response.put("matchers", options.matchers().stream()
            .map(descriptor -> matcherOption(descriptor, options.aliasList())).toList());
        response.put("iconNames", options.iconNames());
        response.put("streamNames", options.streamNames());
        response.put("groupNames", options.groupNames());
        response.put("playbackPriorities", options.playbackPriorities());

        if(options.matchers().stream().anyMatch(descriptor -> descriptor.type() == AliasIDType.DCS))
        {
            response.put("dcsCodes", Arrays.stream(DCSCode.values()).filter(code -> code != DCSCode.UNKNOWN)
                .map(Enum::name).toList());
        }
        if(options.matchers().stream().anyMatch(descriptor -> descriptor.type() == AliasIDType.TONES))
        {
            response.put("tones", AmbeTone.ALL_VALID_TONES.stream().map(Enum::name).sorted().toList());
        }
        sendJson(exchange, 200, response);
    }

    private void handleBulk(HttpExchange exchange) throws Exception
    {
        requireMethod(exchange, "POST");
        requireNoQuery(exchange);
        BulkRequest request = readJson(exchange, BulkRequest.class);
        AliasAdministrationService.BulkEdit edit = new AliasAdministrationService.BulkEdit(
            requiredIds(request.aliasIds()), optionalPositive(request.aliasListId(), "aliasListId"), request.color(),
            optionalText(request.iconName(), "iconName", MAXIMUM_TEXT_CHARACTERS),
            bulkPlaybackPriority(request.listenEnabled(), request.priority()), request.recordable(),
            request.groupOperation(), optionalText(request.group(), "group", MAXIMUM_TEXT_CHARACTERS),
            request.streamOperation(), optionalChannels(request.broadcastChannels()),
            Boolean.TRUE.equals(request.delete()));
        sendJson(exchange, 200, mService.bulkEdit(edit, requiredRevision(request.revision())));
    }

    private static Alias toAlias(AliasPayload payload) throws RequestException
    {
        required(payload, "alias");
        Alias alias = new Alias(requiredText(payload.name(), "name", MAXIMUM_TEXT_CHARACTERS));
        alias.setAliasListId(requiredPositive(payload.aliasListId(), "aliasListId"));
        alias.setDescription(optionalText(payload.description(), "description", MAXIMUM_DESCRIPTION_CHARACTERS));
        alias.setGroup(optionalText(payload.group(), "group", MAXIMUM_TEXT_CHARACTERS));
        alias.setColor(required(payload.color(), "color"));
        alias.setIconName(optionalText(payload.iconName(), "iconName", MAXIMUM_TEXT_CHARACTERS));
        alias.setCallPriority(playbackPriority(required(payload.listenEnabled(), "listenEnabled"), payload.priority()));
        alias.setRecordable(required(payload.recordable(), "recordable"));
        alias.setBroadcastChannels(requiredChannels(payload.broadcastChannels()).stream()
            .map(BroadcastChannel::new).toList());

        if(payload.streamAsTalkgroup() != null)
        {
            alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(bounded(payload.streamAsTalkgroup(),
                "streamAsTalkgroup", 1, 0xFFFF)));
        }
        alias.setMatchIdentifier(toMatcher(required(payload.matcher(), "matcher")));
        return alias;
    }

    private static AliasID toMatcher(MatcherPayload payload) throws RequestException
    {
        AliasIDType type = required(payload.type(), "matcher.type");
        int populated = (payload.protocol() != null ? 1 : 0) + (payload.value() != null ? 1 : 0) +
            (payload.minimum() != null ? 1 : 0) + (payload.maximum() != null ? 1 : 0) +
            (payload.wacn() != null ? 1 : 0) + (payload.system() != null ? 1 : 0) +
            (payload.status() != null ? 1 : 0) + (payload.code() != null ? 1 : 0) +
            (payload.esn() != null ? 1 : 0) + (payload.tones() != null ? 1 : 0);
        int expected = switch(type)
        {
            case TALKGROUP, RADIO_ID -> 2;
            case TALKGROUP_RANGE, RADIO_ID_RANGE -> 3;
            case P25_FULLY_QUALIFIED_TALKGROUP, P25_FULLY_QUALIFIED_RADIO_ID -> 3;
            case STATUS, UNIT_STATUS, DCS, ESN, TONES -> 1;
            default -> throw invalid("matcher type is invalid");
        };
        if(populated != expected)
        {
            throw invalid("matcher fields do not match its type");
        }

        AliasID matcher = switch(type)
        {
            case TALKGROUP -> new Talkgroup(requiredProtocol(payload.protocol()), required(payload.value(), "value"));
            case RADIO_ID -> new Radio(requiredProtocol(payload.protocol()), required(payload.value(), "value"));
            case TALKGROUP_RANGE -> new TalkgroupRange(requiredProtocol(payload.protocol()),
                required(payload.minimum(), "minimum"), required(payload.maximum(), "maximum"));
            case RADIO_ID_RANGE -> new RadioRange(requiredProtocol(payload.protocol()),
                required(payload.minimum(), "minimum"), required(payload.maximum(), "maximum"));
            case P25_FULLY_QUALIFIED_TALKGROUP -> new P25FullyQualifiedTalkgroup(
                bounded(payload.wacn(), "wacn", 0, 0xFFFFF), bounded(payload.system(), "system", 0, 0xFFF),
                bounded(payload.value(), "value", 0, 0xFFFF));
            case P25_FULLY_QUALIFIED_RADIO_ID -> new P25FullyQualifiedRadio(
                bounded(payload.wacn(), "wacn", 0, 0xFFFFF), bounded(payload.system(), "system", 0, 0xFFF),
                bounded(payload.value(), "value", 0, 0xFFFFFF));
            case STATUS -> {
                UserStatusID status = new UserStatusID();
                status.setStatus(bounded(payload.status(), "status", 0, 255));
                yield status;
            }
            case UNIT_STATUS -> {
                UnitStatusID status = new UnitStatusID();
                status.setStatus(bounded(payload.status(), "status", 0, 255));
                yield status;
            }
            case DCS -> {
                if(payload.code() == DCSCode.UNKNOWN)
                {
                    throw invalid("code is invalid");
                }
                Dcs dcs = new Dcs();
                dcs.setDCSCode(required(payload.code(), "code"));
                yield dcs;
            }
            case ESN -> {
                Esn esn = new Esn();
                esn.setEsn(requiredText(payload.esn(), "esn", MAXIMUM_TEXT_CHARACTERS));
                yield esn;
            }
            case TONES -> new TonesID(toToneSequence(payload.tones()));
            default -> throw invalid("matcher type is invalid");
        };

        if(!matcher.isValid())
        {
            throw invalid("matcher value is invalid");
        }
        return matcher;
    }

    private static ToneSequence toToneSequence(List<TonePayload> tones) throws RequestException
    {
        if(tones == null || tones.isEmpty() || tones.size() > MAXIMUM_TONES)
        {
            throw invalid("tones is invalid");
        }

        List<Tone> result = new ArrayList<>(tones.size());
        for(TonePayload tone: tones)
        {
            if(tone == null || tone.tone() == null || !AmbeTone.ALL_VALID_TONES.contains(tone.tone()))
            {
                throw invalid("tone is invalid");
            }
            result.add(new Tone(tone.tone(), bounded(tone.duration(), "duration", 1, 50)));
        }
        return new ToneSequence(result);
    }

    private static AliasView aliasView(Alias alias)
    {
        int priority = alias.getPlaybackPriority();
        return new AliasView(alias.getId(), alias.getAliasListId(), alias.getName(), alias.getDescription(),
            alias.getGroup(), alias.getColor(), alias.getIconName(), priority != Priority.DO_NOT_MONITOR,
            alias.hasCallPriority() && priority != Priority.DO_NOT_MONITOR ? priority : null, alias.isRecordable(),
            alias.getBroadcastChannels().stream().map(BroadcastChannel::getChannelName).toList(),
            alias.getStreamTalkgroupAlias() != null ? alias.getStreamTalkgroupAlias().getValue() : null,
            alias.overlapProperty().get(), matcherView(alias.getMatchIdentifier()));
    }

    private static Map<String,Object> matcherView(AliasID matcher)
    {
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("type", matcher.getType().name());

        switch(matcher)
        {
            case P25FullyQualifiedTalkgroup value -> {
                response.put("wacn", value.getWacn());
                response.put("system", value.getSystem());
                response.put("value", value.getValue());
            }
            case P25FullyQualifiedRadio value -> {
                response.put("wacn", value.getWacn());
                response.put("system", value.getSystem());
                response.put("value", value.getValue());
            }
            case TalkgroupRange value -> {
                response.put("protocol", value.getProtocol().name());
                response.put("minimum", value.getMinTalkgroup());
                response.put("maximum", value.getMaxTalkgroup());
            }
            case Talkgroup value -> {
                response.put("protocol", value.getProtocol().name());
                response.put("value", value.getValue());
            }
            case RadioRange value -> {
                response.put("protocol", value.getProtocol().name());
                response.put("minimum", value.getMinRadio());
                response.put("maximum", value.getMaxRadio());
            }
            case Radio value -> {
                response.put("protocol", value.getProtocol().name());
                response.put("value", value.getValue());
            }
            case UserStatusID value -> response.put("status", value.getStatus());
            case UnitStatusID value -> response.put("status", value.getStatus());
            case Dcs value -> response.put("code", value.getDCSCode().name());
            case Esn value -> response.put("esn", value.getEsn());
            case TonesID value -> response.put("tones", value.getToneSequence().getTones().stream()
                .map(tone -> new TonePayload(tone.getAmbeTone(), tone.getDuration())).toList());
            default -> throw new IllegalArgumentException("Unsupported alias matcher");
        }
        return response;
    }

    private static Map<String,Object> matcherOption(AliasMatchDescriptor descriptor, AliasListDefinition definition)
    {
        AliasID example = descriptor.create(definition);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("type", descriptor.type().name());
        response.put("label", descriptor.label());
        response.put("fields", matcherFields(descriptor.type()));

        if(descriptor.type() == AliasIDType.TALKGROUP && example instanceof Talkgroup talkgroup)
        {
            response.put("protocol", talkgroup.getProtocol().name());
            TalkgroupFormat format = TalkgroupFormat.get(talkgroup.getProtocol());
            response.put("minimum", format.getMinimumValidValue());
            response.put("maximum", format.getMaximumValidValue());
        }
        else if(descriptor.type() == AliasIDType.TALKGROUP_RANGE && example instanceof TalkgroupRange range)
        {
            response.put("protocol", range.getProtocol().name());
            TalkgroupFormat format = TalkgroupFormat.get(range.getProtocol());
            response.put("minimum", format.getMinimumValidValue());
            response.put("maximum", format.getMaximumValidValue());
        }
        else if(descriptor.type() == AliasIDType.RADIO_ID && example instanceof Radio radio)
        {
            response.put("protocol", radio.getProtocol().name());
            RadioFormat format = RadioFormat.get(radio.getProtocol());
            response.put("minimum", format.getMinimumValidValue());
            response.put("maximum", format.getMaximumValidValue());
        }
        else if(descriptor.type() == AliasIDType.RADIO_ID_RANGE && example instanceof RadioRange range)
        {
            response.put("protocol", range.getProtocol().name());
            RadioFormat format = RadioFormat.get(range.getProtocol());
            response.put("minimum", format.getMinimumValidValue());
            response.put("maximum", format.getMaximumValidValue());
        }
        return response;
    }

    private static List<String> matcherFields(AliasIDType type)
    {
        return switch(type)
        {
            case TALKGROUP, RADIO_ID -> List.of("value");
            case TALKGROUP_RANGE, RADIO_ID_RANGE -> List.of("minimum", "maximum");
            case P25_FULLY_QUALIFIED_TALKGROUP, P25_FULLY_QUALIFIED_RADIO_ID ->
                List.of("wacn", "system", "value");
            case STATUS, UNIT_STATUS -> List.of("status");
            case TONES -> List.of("tones");
            case DCS -> List.of("code");
            case ESN -> List.of("esn");
            default -> List.of();
        };
    }

    private static int playbackPriority(boolean listenEnabled, Integer priority) throws RequestException
    {
        if(!listenEnabled)
        {
            if(priority != null)
            {
                throw invalid("priority must be null when listening is disabled");
            }
            return Priority.DO_NOT_MONITOR;
        }
        return priority == null ? Priority.DEFAULT_PRIORITY : bounded(priority, "priority", Priority.MIN_PRIORITY,
            Priority.MAX_PRIORITY - 1);
    }

    private static Integer bulkPlaybackPriority(Boolean listenEnabled, Integer priority) throws RequestException
    {
        if(listenEnabled == null)
        {
            if(priority != null)
            {
                throw invalid("listenEnabled is required when priority is supplied");
            }
            return null;
        }
        return playbackPriority(listenEnabled, priority);
    }

    private static Protocol requiredProtocol(Protocol protocol) throws RequestException
    {
        if(protocol == null || !SUPPORTED_PROTOCOLS.contains(protocol))
        {
            throw invalid("protocol is invalid");
        }
        return protocol;
    }

    private static List<String> requiredChannels(List<String> channels) throws RequestException
    {
        if(channels == null || channels.size() > MAXIMUM_BROADCAST_CHANNELS)
        {
            throw invalid("broadcastChannels is invalid");
        }

        Set<String> unique = new HashSet<>();
        for(String channel: channels)
        {
            String checked = requiredText(channel, "broadcastChannels", MAXIMUM_TEXT_CHARACTERS);
            if(!unique.add(checked))
            {
                throw invalid("broadcastChannels contains a duplicate");
            }
        }
        return List.copyOf(channels);
    }

    private static List<String> optionalChannels(List<String> channels) throws RequestException
    {
        return channels != null ? requiredChannels(channels) : null;
    }

    private static List<Long> requiredIds(List<Long> ids) throws RequestException
    {
        if(ids == null || ids.isEmpty() || ids.size() > AliasAdministrationService.MAX_BULK_ALIASES)
        {
            throw invalid("aliasIds is invalid");
        }
        Set<Long> unique = new HashSet<>();
        for(Long id: ids)
        {
            if(id == null || id <= 0 || !unique.add(id))
            {
                throw invalid("aliasIds must be positive and unique");
            }
        }
        return List.copyOf(ids);
    }

    private static long requiredIdQuery(HttpExchange exchange, String name) throws RequestException
    {
        String query = exchange.getRequestURI().getRawQuery();
        String prefix = name + "=";
        if(query == null || !query.startsWith(prefix) || query.indexOf('&') >= 0)
        {
            throw invalid(name + " is required");
        }
        return parseLong(query.substring(prefix.length()), name, 1, Long.MAX_VALUE);
    }

    private static long requiredItemId(String path, String collection) throws RequestException
    {
        String prefix = collection + "/";
        if(path == null || !path.startsWith(prefix) || path.substring(prefix.length()).contains("/"))
        {
            throw error(404, "not_found", "Not found");
        }
        return parseLong(path.substring(prefix.length()), "id", 1, Long.MAX_VALUE);
    }

    private static long parseLong(String value, String field, long minimum, long maximum) throws RequestException
    {
        try
        {
            long parsed = Long.parseLong(value);
            if(parsed < minimum || parsed > maximum)
            {
                throw invalid(field + " is invalid");
            }
            return parsed;
        }
        catch(NumberFormatException exception)
        {
            throw invalid(field + " is invalid");
        }
    }

    private static <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException, RequestException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if(contentType == null || !"application/json".equals(contentType.toLowerCase(Locale.ROOT)
            .split(";", 2)[0].strip()))
        {
            throw error(415, "invalid_content_type", "Content-Type must be application/json");
        }

        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if(length != null)
        {
            try
            {
                long parsed = Long.parseLong(length);
                if(parsed < 0)
                {
                    throw invalid("Content-Length is invalid");
                }
                if(parsed > MAXIMUM_JSON_BODY_BYTES)
                {
                    throw error(413, "request_too_large", "The JSON request is too large");
                }
            }
            catch(NumberFormatException exception)
            {
                throw invalid("Content-Length is invalid");
            }
        }

        byte[] bytes;
        try(InputStream inputStream = exchange.getRequestBody())
        {
            bytes = inputStream.readNBytes(MAXIMUM_JSON_BODY_BYTES + 1);
        }

        try
        {
            if(bytes.length == 0)
            {
                throw invalid("A JSON request body is required");
            }
            if(bytes.length > MAXIMUM_JSON_BODY_BYTES)
            {
                throw error(413, "request_too_large", "The JSON request is too large");
            }
            return OBJECT_MAPPER.readValue(bytes, type);
        }
        catch(RequestException exception)
        {
            throw exception;
        }
        catch(Exception exception)
        {
            throw invalid("The JSON body is invalid");
        }
        finally
        {
            Arrays.fill(bytes, (byte)0);
        }
    }

    private static void requireNoQuery(HttpExchange exchange) throws RequestException
    {
        if(exchange.getRequestURI().getRawQuery() != null)
        {
            throw invalid("query parameters are not supported");
        }
    }

    private static void requireNoBody(HttpExchange exchange) throws RequestException
    {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if(length != null && !"0".equals(length) || exchange.getRequestHeaders().containsKey("Transfer-Encoding"))
        {
            throw invalid("This request cannot include a body");
        }
    }

    private static void requireMethod(HttpExchange exchange, String method) throws RequestException
    {
        if(!method.equals(exchange.getRequestMethod()))
        {
            exchange.getResponseHeaders().set("Allow", method);
            throw error(405, "method_not_allowed", "Method not allowed");
        }
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException
    {
        exchange.getResponseHeaders().set("Allow", allow);
        sendError(exchange, 405, "method_not_allowed", "Method not allowed");
    }

    private static long requiredRevision(Long revision) throws RequestException
    {
        return requiredPositiveOrZero(revision, "revision");
    }

    private static long requiredPositive(Long value, String field) throws RequestException
    {
        if(value == null || value <= 0)
        {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private static Long optionalPositive(Long value, String field) throws RequestException
    {
        return value == null ? null : requiredPositive(value, field);
    }

    private static long requiredPositiveOrZero(Long value, String field) throws RequestException
    {
        if(value == null || value < 0)
        {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private static int bounded(Integer value, String field, int minimum, int maximum) throws RequestException
    {
        if(value == null || value < minimum || value > maximum)
        {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private static String requiredText(String value, String field, int maximum) throws RequestException
    {
        if(value == null || value.isBlank() || value.length() > maximum)
        {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private static String optionalText(String value, String field, int maximum) throws RequestException
    {
        if(value != null && value.length() > maximum)
        {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private static <T> T required(T value, String field) throws RequestException
    {
        if(value == null)
        {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException
    {
        sendJson(exchange, status, Map.of("error", code, "message", message, "status", status));
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException
    {
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(value);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", JSON_CONTENT_TYPE);
        headers.set("Cache-Control", "no-store");
        headers.set("Vary", "Cookie");
        exchange.sendResponseHeaders(status, body.length);
        try(OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(body);
        }
    }

    private static String safeMessage(RuntimeException exception, String fallback)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() || message.length() > 240 ? fallback : message;
    }

    private static RequestException invalid(String message)
    {
        return error(400, "invalid_request", message);
    }

    private static RequestException error(int status, String code, String message)
    {
        return new RequestException(status, code, message);
    }

    private record CreateListRequest(Long revision, String name, AliasListFamily family) {}
    private record DeleteListRequest(Long revision, Boolean confirmed) {}
    private record RevisionRequest(Long revision) {}
    private record AliasRequest(Long revision, AliasPayload alias) {}
    private record BulkRequest(Long revision, List<Long> aliasIds, Long aliasListId, Integer color, String iconName,
                               Boolean listenEnabled, Integer priority, Boolean recordable,
                               AliasAdministrationService.GroupOperation groupOperation, String group,
                               AliasAdministrationService.StreamOperation streamOperation,
                               List<String> broadcastChannels, Boolean delete) {}
    private record AliasPayload(Long aliasListId, String name, String description, String group, Integer color,
                                String iconName, Boolean listenEnabled, Integer priority, Boolean recordable,
                                List<String> broadcastChannels, Integer streamAsTalkgroup, MatcherPayload matcher) {}
    private record AliasView(long id, long aliasListId, String name, String description, String group, int color,
                             String iconName, boolean listenEnabled, Integer priority, boolean recordable,
                             List<String> broadcastChannels, Integer streamAsTalkgroup, boolean overlap,
                             Map<String,Object> matcher) {}
    private record TonePayload(AmbeTone tone, Integer duration) {}

    private record MatcherPayload(AliasIDType type, Protocol protocol, Integer value, Integer minimum, Integer maximum,
                                  Integer wacn, Integer system, Integer status, DCSCode code, String esn,
                                  List<TonePayload> tones)
    {}

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
