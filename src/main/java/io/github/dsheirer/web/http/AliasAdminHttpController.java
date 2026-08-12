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
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchDescriptor;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioFormat;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
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
    private static final int MAXIMUM_TONES = 64;
    private static final int MAXIMUM_ADMIN_COLLECTION_ITEMS = 500;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final AliasAdministrationService mService;
    private final Runnable mAliasChanged;
    private final AliasListDeletion mAliasListDeletion;

    public AliasAdminHttpController(AliasAdministrationService service)
    {
        this(service, () -> {});
    }

    public AliasAdminHttpController(AliasAdministrationService service, Runnable aliasChanged)
    {
        this(service, aliasChanged, new AliasListDeletion()
        {
            @Override
            public AliasAdministrationService.DeleteImpact impact(long aliasListId, int maximumCount)
            {
                return service.aliasListDeleteImpact(aliasListId, maximumCount);
            }

            @Override
            public AliasAdministrationService.MutationResult delete(long aliasListId, long revision,
                                                                     boolean confirmed)
            {
                return service.deleteAliasList(aliasListId, revision, confirmed);
            }
        });
    }

    AliasAdminHttpController(AliasAdministrationService service, Runnable aliasChanged,
                             AliasListDeletion aliasListDeletion)
    {
        mService = Objects.requireNonNull(service, "Alias administration service cannot be null");
        mAliasChanged = Objects.requireNonNull(aliasChanged, "Alias change callback cannot be null");
        mAliasListDeletion = Objects.requireNonNull(aliasListDeletion, "Alias-list deletion cannot be null");
    }

    /** Handles all alias-administration contexts. */
    public void handle(HttpExchange exchange) throws IOException
    {
        WebAccessHttpController.prepareSecurityHeaders(exchange);

        try
        {
            String path = exchange.getRequestURI().getRawPath();

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
            sendError(exchange, exception.status(), exception.code(), exception.getMessage(), exception.field());
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
                    sendData(exchange, 200, Map.of("revision", catalog.revision(), "aliasLists",
                        boundedCollection(catalog.aliasLists(), "alias_lists").stream()
                            .map(AliasAdminHttpController::aliasListView).toList()));
                }
                case "POST" -> {
                    if(mService.catalog().aliasLists().size() >= MAXIMUM_ADMIN_COLLECTION_ITEMS)
                    {
                        throw error(409, "collection_limit_reached",
                            "alias_lists cannot exceed " + MAXIMUM_ADMIN_COLLECTION_ITEMS + " items");
                    }

                    CreateListRequest request = readJson(exchange, CreateListRequest.class);
                    AliasAdministrationService.MutationResult result = changed(mService.createAliasList(
                        requiredText(request.name(), "name", MAXIMUM_TEXT_CHARACTERS),
                        requiredAliasListFamily(request.family()), requiredRevision(request.revision())));
                    exchange.getResponseHeaders().set("Location", ALIAS_LISTS_PATH + "/" + result.aliasListId());
                    sendData(exchange, 201, mutationResponse(result));
                }
                default -> methodNotAllowed(exchange, "GET, POST");
            }
            return;
        }

        String impactSuffix = "/delete-impact";
        String policySuffix = "/unmatched-talkgroups";
        boolean impact = path.endsWith(impactSuffix);
        boolean policy = path.endsWith(policySuffix);
        String itemPath = impact ? path.substring(0, path.length() - impactSuffix.length()) :
            policy ? path.substring(0, path.length() - policySuffix.length()) : path;
        long aliasListId = requiredItemId(itemPath,
            ALIAS_LISTS_PATH);
        requireNoQuery(exchange);

        if(impact)
        {
            requireMethod(exchange, "GET");
            requireNoBody(exchange);
            AliasAdministrationService.DeleteImpact deleteImpact =
                mAliasListDeletion.impact(aliasListId, MAXIMUM_ADMIN_COLLECTION_ITEMS);
            requireBoundedDeleteImpact(deleteImpact);
            sendData(exchange, 200, deleteImpact);
            return;
        }

        if(policy)
        {
            requireMethod(exchange, "PUT");
            UnmatchedPolicyRequest request = readJson(exchange, UnmatchedPolicyRequest.class);
            UnmatchedTalkgroupPolicy replacement = new UnmatchedTalkgroupPolicy(
                unmatchedPlaybackPriority(required(request.listenEnabled(), "listen_enabled"), request.priority()),
                required(request.recordable(), "recordable"), requiredChannels(request.broadcastChannels()));
            sendData(exchange, 200, mutationResponse(changed(mService.updateUnmatchedTalkgroupPolicy(aliasListId,
                replacement, requiredRevision(request.revision())))));
            return;
        }

        switch(exchange.getRequestMethod())
        {
            case "DELETE" -> {
                DeleteListRequest request = readJson(exchange, DeleteListRequest.class);
                long revision = requiredRevision(request.revision());
                boolean confirmed = required(request.confirmed(), "confirmed");
                AliasAdministrationService.DeleteImpact deleteImpact =
                    mAliasListDeletion.impact(aliasListId, MAXIMUM_ADMIN_COLLECTION_ITEMS);

                if(deleteImpact.revision() != revision)
                {
                    throw new AliasAdministrationService.StaleRevisionException(revision, deleteImpact.revision());
                }

                requireBoundedDeleteImpact(deleteImpact);
                AliasAdministrationService.MutationResult result = changed(
                    mAliasListDeletion.delete(aliasListId, revision, confirmed));
                sendData(exchange, 200, mutationResponse(result));
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
                    AliasAdministrationService.MutationResult result = changed(mService.createAlias(
                        toAlias(request.alias()), requiredRevision(request.revision())));

                    if(!result.aliasIds().isEmpty())
                    {
                        exchange.getResponseHeaders().set("Location", ALIASES_PATH + "/" +
                            result.aliasIds().getFirst());
                    }
                    sendData(exchange, 201, mutationResponse(result));
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
                sendData(exchange, 200, Map.of("revision", entry.revision(), "alias", aliasView(entry.alias())));
            }
            case "PUT" -> {
                AliasRequest request = readJson(exchange, AliasRequest.class);
                sendData(exchange, 200, mutationResponse(changed(mService.replaceAlias(aliasId,
                    toAlias(request.alias()), requiredRevision(request.revision())))));
            }
            case "DELETE" -> {
                RevisionRequest request = readJson(exchange, RevisionRequest.class);
                sendData(exchange, 200, mutationResponse(changed(mService.deleteAlias(aliasId,
                    requiredRevision(request.revision())))));
            }
            default -> methodNotAllowed(exchange, "GET, PUT, DELETE");
        }
    }

    private void handleOptions(HttpExchange exchange) throws Exception
    {
        requireMethod(exchange, "GET");
        requireNoBody(exchange);
        AliasAdministrationService.Options options = mService.options(requiredIdQuery(exchange, "alias_list_id"));
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("revision", options.revision());
        response.put("aliasList", aliasListView(options.aliasList()));
        response.put("matchers", boundedCollection(options.matchers(), "matchers").stream()
            .map(descriptor -> matcherOption(descriptor, options.aliasList())).toList());
        response.put("iconNames", boundedCollection(options.iconNames(), "icon_names"));
        response.put("streamNames", boundedCollection(options.streamNames(), "stream_names"));
        response.put("groupNames", boundedCollection(options.groupNames(), "group_names"));
        response.put("playbackPriorities", boundedCollection(options.playbackPriorities(),
            "playback_priorities"));

        if(options.matchers().stream().anyMatch(descriptor -> descriptor.type() == AliasIDType.DCS))
        {
            response.put("dcsCodes", Arrays.stream(DCSCode.values()).filter(code -> code != DCSCode.UNKNOWN)
                .map(AliasAdminHttpController::dcsCodeName).toList());
        }
        if(options.matchers().stream().anyMatch(descriptor -> descriptor.type() == AliasIDType.TONES))
        {
            response.put("tones", AmbeTone.ALL_VALID_TONES.stream()
                .map(AliasAdminHttpController::toneName).sorted().toList());
        }
        sendData(exchange, 200, response);
    }

    private void handleBulk(HttpExchange exchange) throws Exception
    {
        requireMethod(exchange, "POST");
        requireNoQuery(exchange);
        BulkRequest request = readJson(exchange, BulkRequest.class);
        AliasAdministrationService.BulkEdit edit = new AliasAdministrationService.BulkEdit(
            requiredIds(request.aliasIds()), optionalPositive(request.aliasListId(), "alias_list_id"), request.color(),
            optionalText(request.iconName(), "icon_name", MAXIMUM_TEXT_CHARACTERS),
            bulkPlaybackPriority(request.listenEnabled(), request.priority()), request.recordable(),
            groupOperation(request.groupOperation()), optionalText(request.group(), "group", MAXIMUM_TEXT_CHARACTERS),
            streamOperation(request.streamOperation()), optionalChannels(request.broadcastChannels()),
            Boolean.TRUE.equals(request.delete()));
        sendData(exchange, 200,
            mutationResponse(changed(mService.bulkEdit(edit, requiredRevision(request.revision())))));
    }

    private AliasAdministrationService.MutationResult changed(AliasAdministrationService.MutationResult result)
    {
        mAliasChanged.run();
        return result;
    }

    private static Map<String,Object> mutationResponse(AliasAdministrationService.MutationResult result)
    {
        List<Long> aliasIds = result.aliasIds();
        int returned = Math.min(aliasIds.size(), MAXIMUM_ADMIN_COLLECTION_ITEMS);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("revision", result.revision());
        response.put("aliasListId", result.aliasListId());
        response.put("aliasIds", List.copyOf(aliasIds.subList(0, returned)));
        response.put("aliasIdsTotal", aliasIds.size());
        response.put("aliasIdsTruncated", returned < aliasIds.size());
        response.put("affected", result.affected());
        return response;
    }

    private static Alias toAlias(AliasPayload payload) throws RequestException
    {
        required(payload, "alias");
        Alias alias = new Alias(requiredText(payload.name(), "name", MAXIMUM_TEXT_CHARACTERS));
        alias.setAliasListId(requiredPositive(payload.aliasListId(), "alias_list_id"));
        alias.setDescription(optionalText(payload.description(), "description", MAXIMUM_DESCRIPTION_CHARACTERS));
        alias.setGroup(optionalText(payload.group(), "group", MAXIMUM_TEXT_CHARACTERS));
        alias.setColor(required(payload.color(), "color"));
        alias.setIconName(optionalText(payload.iconName(), "icon_name", MAXIMUM_TEXT_CHARACTERS));
        alias.setCallPriority(playbackPriority(required(payload.listenEnabled(), "listen_enabled"), payload.priority()));
        alias.setRecordable(required(payload.recordable(), "recordable"));
        alias.setBroadcastChannels(requiredChannels(payload.broadcastChannels()).stream()
            .map(BroadcastChannel::new).toList());

        if(payload.streamAsTalkgroup() != null)
        {
            alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(bounded(payload.streamAsTalkgroup(),
                "stream_as_talkgroup", 1, 0xFFFF)));
        }
        alias.setMatchIdentifier(toMatcher(required(payload.matcher(), "matcher")));
        return alias;
    }

    private static AliasID toMatcher(MatcherPayload payload) throws RequestException
    {
        AliasIDType type = matcherType(payload.type());
        boolean protocolMatcher = type == AliasIDType.TALKGROUP || type == AliasIDType.TALKGROUP_RANGE ||
            type == AliasIDType.RADIO_ID || type == AliasIDType.RADIO_ID_RANGE;

        if(!protocolMatcher && payload.variant() != null)
        {
            throw invalid("variant is only valid for protocol matchers");
        }

        int populated = (payload.protocol() != null ? 1 : 0) + (payload.value() != null ? 1 : 0) +
            (payload.minimum() != null ? 1 : 0) + (payload.maximum() != null ? 1 : 0) +
            (payload.status() != null ? 1 : 0) + (payload.code() != null ? 1 : 0) +
            (payload.esn() != null ? 1 : 0) + (payload.tones() != null ? 1 : 0);
        int expected = switch(type)
        {
            case TALKGROUP, RADIO_ID -> 2;
            case TALKGROUP_RANGE, RADIO_ID_RANGE -> 3;
            case STATUS, UNIT_STATUS, DCS, ESN, TONES -> 1;
            default -> throw invalid("matcher type is invalid");
        };
        if(populated != expected)
        {
            throw invalid("matcher fields do not match its type");
        }

        AliasID matcher = switch(type)
        {
            case TALKGROUP -> new Talkgroup(requiredProtocol(payload.protocol(), payload.variant()),
                required(payload.value(), "value"));
            case RADIO_ID -> new Radio(requiredProtocol(payload.protocol(), payload.variant()),
                required(payload.value(), "value"));
            case TALKGROUP_RANGE -> new TalkgroupRange(requiredProtocol(payload.protocol(), payload.variant()),
                required(payload.minimum(), "minimum"), required(payload.maximum(), "maximum"));
            case RADIO_ID_RANGE -> new RadioRange(requiredProtocol(payload.protocol(), payload.variant()),
                required(payload.minimum(), "minimum"), required(payload.maximum(), "maximum"));
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
                DCSCode code = dcsCode(payload.code());
                if(code == DCSCode.UNKNOWN)
                {
                    throw invalid("code is invalid");
                }
                Dcs dcs = new Dcs();
                dcs.setDCSCode(code);
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
            AmbeTone value = tone != null ? tone(tone.tone()) : null;
            if(value == null || !AmbeTone.ALL_VALID_TONES.contains(value))
            {
                throw invalid("tone is invalid");
            }
            result.add(new Tone(value, bounded(tone.duration(), "duration", 1, 50)));
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
        response.put("type", matcherTypeName(matcher.getType()));

        switch(matcher)
        {
            case TalkgroupRange value -> {
                addProtocol(response, value.getProtocol());
                response.put("minimum", value.getMinTalkgroup());
                response.put("maximum", value.getMaxTalkgroup());
            }
            case Talkgroup value -> {
                addProtocol(response, value.getProtocol());
                response.put("value", value.getValue());
            }
            case RadioRange value -> {
                addProtocol(response, value.getProtocol());
                response.put("minimum", value.getMinRadio());
                response.put("maximum", value.getMaxRadio());
            }
            case Radio value -> {
                addProtocol(response, value.getProtocol());
                response.put("value", value.getValue());
            }
            case UserStatusID value -> response.put("status", value.getStatus());
            case UnitStatusID value -> response.put("status", value.getStatus());
            case Dcs value -> response.put("code", dcsCodeName(value.getDCSCode()));
            case Esn value -> response.put("esn", value.getEsn());
            case TonesID value -> response.put("tones", value.getToneSequence().getTones().stream()
                .map(tone -> new TonePayload(toneName(tone.getAmbeTone()), tone.getDuration())).toList());
            default -> throw new IllegalArgumentException("Unsupported alias matcher");
        }
        return response;
    }

    private static Map<String,Object> matcherOption(AliasMatchDescriptor descriptor, AliasListDefinition definition)
    {
        AliasID example = descriptor.create(definition);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("type", matcherTypeName(descriptor.type()));
        response.put("label", descriptor.label());
        response.put("fields", matcherFields(descriptor.type()));

        if(descriptor.type() == AliasIDType.TALKGROUP && example instanceof Talkgroup talkgroup)
        {
            addProtocol(response, talkgroup.getProtocol());
            TalkgroupFormat format = TalkgroupFormat.get(talkgroup.getProtocol());
            response.put("minimum", format.getMinimumValidValue());
            response.put("maximum", format.getMaximumValidValue());
        }
        else if(descriptor.type() == AliasIDType.TALKGROUP_RANGE && example instanceof TalkgroupRange range)
        {
            addProtocol(response, range.getProtocol());
            TalkgroupFormat format = TalkgroupFormat.get(range.getProtocol());
            response.put("minimum", format.getMinimumValidValue());
            response.put("maximum", format.getMaximumValidValue());
        }
        else if(descriptor.type() == AliasIDType.RADIO_ID && example instanceof Radio radio)
        {
            addProtocol(response, radio.getProtocol());
            RadioFormat format = RadioFormat.get(radio.getProtocol());
            response.put("minimum", format.getMinimumValidValue());
            response.put("maximum", format.getMaximumValidValue());
        }
        else if(descriptor.type() == AliasIDType.RADIO_ID_RANGE && example instanceof RadioRange range)
        {
            addProtocol(response, range.getProtocol());
            RadioFormat format = RadioFormat.get(range.getProtocol());
            response.put("minimum", format.getMinimumValidValue());
            response.put("maximum", format.getMaximumValidValue());
        }
        return response;
    }

    private static Map<String,Object> aliasListView(AliasListDefinition definition)
    {
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("aliasListId", definition.getId());
        response.put("name", definition.getName());
        response.put("family", familyName(definition.getFamily()));
        response.put("unmatchedTalkgroupPolicy", unmatchedTalkgroupPolicyView(
            definition.getUnmatchedTalkgroupPolicy()));
        return response;
    }

    private static Map<String,Object> unmatchedTalkgroupPolicyView(UnmatchedTalkgroupPolicy policy)
    {
        int playbackPriority = policy.getPlaybackPriority();
        boolean listenEnabled = playbackPriority != Priority.DO_NOT_MONITOR;
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("listenEnabled", listenEnabled);
        response.put("priority", listenEnabled ? playbackPriority : null);
        response.put("recordable", policy.isRecordEnabled());
        response.put("broadcastChannels", policy.getStreamDestinationNames());
        return response;
    }

    private static List<String> matcherFields(AliasIDType type)
    {
        return switch(type)
        {
            case TALKGROUP, RADIO_ID -> List.of("value");
            case TALKGROUP_RANGE, RADIO_ID_RANGE -> List.of("minimum", "maximum");
            case STATUS, UNIT_STATUS -> List.of("status");
            case TONES -> List.of("tones");
            case DCS -> List.of("code");
            case ESN -> List.of("esn");
            default -> List.of();
        };
    }

    private static AliasIDType matcherType(String value) throws RequestException
    {
        return switch(requiredText(value, "matcher.type", 32))
        {
            case "talkgroup" -> AliasIDType.TALKGROUP;
            case "talkgroup_range" -> AliasIDType.TALKGROUP_RANGE;
            case "radio" -> AliasIDType.RADIO_ID;
            case "radio_range" -> AliasIDType.RADIO_ID_RANGE;
            case "user_status" -> AliasIDType.STATUS;
            case "unit_status" -> AliasIDType.UNIT_STATUS;
            case "tone_sequence" -> AliasIDType.TONES;
            case "dcs" -> AliasIDType.DCS;
            case "esn" -> AliasIDType.ESN;
            default -> throw invalid("matcher type is invalid");
        };
    }

    private static String matcherTypeName(AliasIDType type)
    {
        return switch(type)
        {
            case TALKGROUP -> "talkgroup";
            case TALKGROUP_RANGE -> "talkgroup_range";
            case RADIO_ID -> "radio";
            case RADIO_ID_RANGE -> "radio_range";
            case STATUS -> "user_status";
            case UNIT_STATUS -> "unit_status";
            case TONES -> "tone_sequence";
            case DCS -> "dcs";
            case ESN -> "esn";
            default -> throw new IllegalArgumentException("Unsupported alias matcher type");
        };
    }

    private static AliasListFamily requiredAliasListFamily(String value) throws RequestException
    {
        return switch(requiredText(value, "family", 16))
        {
            case "p25" -> AliasListFamily.P25;
            case "dmr" -> AliasListFamily.DMR;
            case "nxdn" -> AliasListFamily.NXDN;
            case "nbfm" -> AliasListFamily.NBFM;
            default -> throw invalid("family is invalid");
        };
    }

    private static String familyName(AliasListFamily family)
    {
        return switch(Objects.requireNonNull(family, "Alias-list family cannot be null"))
        {
            case P25 -> "p25";
            case DMR -> "dmr";
            case NXDN -> "nxdn";
            case NBFM -> "nbfm";
        };
    }

    private static void addProtocol(Map<String,Object> response, Protocol protocol)
    {
        response.put("protocol", protocolName(protocol));

        if(protocol == Protocol.APCO25)
        {
            response.put("variant", "phase_1");
        }
        else if(protocol == Protocol.APCO25_PHASE2)
        {
            response.put("variant", "phase_2");
        }
    }

    private static String protocolName(Protocol protocol)
    {
        return switch(Objects.requireNonNull(protocol, "Protocol cannot be null"))
        {
            case AM -> "am";
            case APCO25, APCO25_PHASE2 -> "p25";
            case DMR -> "dmr";
            case NXDN -> "nxdn";
            case NBFM -> "nbfm";
            case FLEETSYNC -> "fleetsync";
            case MDC1200 -> "mdc1200";
            default -> throw new IllegalArgumentException("Unsupported alias protocol");
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
                throw invalid("listen_enabled is required when priority is supplied");
            }
            return null;
        }
        return playbackPriority(listenEnabled, priority);
    }

    private static int unmatchedPlaybackPriority(boolean listenEnabled, Integer priority) throws RequestException
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
            Priority.MAX_PRIORITY);
    }

    private static Protocol requiredProtocol(String protocol, String variant) throws RequestException
    {
        if(protocol == null)
        {
            throw invalid("protocol is invalid");
        }

        return switch(protocol)
        {
            case "am" -> protocolWithoutVariant(Protocol.AM, variant);
            case "p25" -> switch(variant)
            {
                case "phase_1" -> Protocol.APCO25;
                case "phase_2" -> Protocol.APCO25_PHASE2;
                case null, default -> throw invalid("variant is invalid for p25");
            };
            case "dmr" -> protocolWithoutVariant(Protocol.DMR, variant);
            case "nxdn" -> protocolWithoutVariant(Protocol.NXDN, variant);
            case "nbfm" -> protocolWithoutVariant(Protocol.NBFM, variant);
            case "fleetsync" -> protocolWithoutVariant(Protocol.FLEETSYNC, variant);
            case "mdc1200" -> protocolWithoutVariant(Protocol.MDC1200, variant);
            default -> throw invalid("protocol is invalid");
        };
    }

    private static Protocol protocolWithoutVariant(Protocol protocol, String variant) throws RequestException
    {
        if(variant != null)
        {
            throw invalid("variant is only valid for p25");
        }

        return protocol;
    }

    private static DCSCode dcsCode(String value) throws RequestException
    {
        if(value != null)
        {
            for(DCSCode code: DCSCode.values())
            {
                if(dcsCodeName(code).equals(value))
                {
                    return code;
                }
            }
        }

        throw invalid("code is invalid");
    }

    private static String dcsCodeName(DCSCode code)
    {
        return code.name().toLowerCase(Locale.ROOT);
    }

    private static AmbeTone tone(String value)
    {
        if(value != null)
        {
            for(AmbeTone tone: AmbeTone.ALL_VALID_TONES)
            {
                if(toneName(tone).equals(value))
                {
                    return tone;
                }
            }
        }

        return null;
    }

    private static String toneName(AmbeTone tone)
    {
        return tone.name().toLowerCase(Locale.ROOT);
    }

    private static AliasAdministrationService.GroupOperation groupOperation(String value) throws RequestException
    {
        return switch(value)
        {
            case null -> null;
            case "set" -> AliasAdministrationService.GroupOperation.SET;
            case "clear" -> AliasAdministrationService.GroupOperation.CLEAR;
            default -> throw invalid("group_operation is invalid");
        };
    }

    private static AliasAdministrationService.StreamOperation streamOperation(String value) throws RequestException
    {
        return switch(value)
        {
            case null -> null;
            case "add" -> AliasAdministrationService.StreamOperation.ADD;
            case "remove" -> AliasAdministrationService.StreamOperation.REMOVE;
            case "replace" -> AliasAdministrationService.StreamOperation.REPLACE;
            case "clear" -> AliasAdministrationService.StreamOperation.CLEAR;
            default -> throw invalid("stream_operation is invalid");
        };
    }

    private static List<String> requiredChannels(List<String> channels) throws RequestException
    {
        if(channels == null || channels.size() > AliasAdministrationService.MAX_BROADCAST_CHANNELS)
        {
            throw invalid("broadcast_channels is invalid");
        }

        Set<String> unique = new HashSet<>();
        for(String channel: channels)
        {
            String checked = requiredText(channel, "broadcast_channels",
                AliasAdministrationService.MAX_BROADCAST_CHANNEL_NAME_LENGTH);
            if(!unique.add(checked))
            {
                throw invalid("broadcast_channels contains a duplicate");
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
            throw invalid("alias_ids is invalid");
        }
        Set<Long> unique = new HashSet<>();
        for(Long id: ids)
        {
            if(id == null || id <= 0 || !unique.add(id))
            {
                throw invalid("alias_ids must be positive and unique");
            }
        }
        return List.copyOf(ids);
    }

    private static long requiredIdQuery(HttpExchange exchange, String name) throws RequestException
    {
        String query = exchange.getRequestURI().getRawQuery();
        String prefix = name + "=";
        if(query == null || query.length() > 128 || !query.startsWith(prefix) || query.indexOf('&') >= 0)
        {
            throw invalid(name + " is required");
        }
        try
        {
            return positiveDecimal(ApiRequestDecoder.decodeComponent(query.substring(prefix.length()), true), name);
        }
        catch(IllegalArgumentException exception)
        {
            throw invalid(name + " contains invalid percent encoding");
        }
    }

    private static long requiredItemId(String path, String collection) throws RequestException
    {
        String prefix = collection + "/";
        if(path == null || !path.startsWith(prefix) || path.substring(prefix.length()).contains("/"))
        {
            throw error(404, "not_found", "Not found");
        }
        try
        {
            return positiveDecimal(ApiRequestDecoder.decodeComponent(path.substring(prefix.length()), false), "id");
        }
        catch(IllegalArgumentException exception)
        {
            throw invalid("id contains invalid percent encoding");
        }
    }

    private static long positiveDecimal(String value, String field) throws RequestException
    {
        try
        {
            if(value == null || !value.matches("[0-9]+"))
            {
                throw new NumberFormatException();
            }

            long parsed = Long.parseLong(value);
            if(parsed <= 0)
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

        byte[] bytes = ApiRequestDecoder.readBody(exchange, MAXIMUM_JSON_BODY_BYTES);

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

    private static <T> List<T> boundedCollection(List<T> values, String field) throws RequestException
    {
        if(values == null || values.size() > MAXIMUM_ADMIN_COLLECTION_ITEMS)
        {
            throw error(422, "collection_limit_exceeded",
                field + " exceeds the maximum of " + MAXIMUM_ADMIN_COLLECTION_ITEMS + " items");
        }

        return values;
    }

    private static void requireBoundedDeleteImpact(AliasAdministrationService.DeleteImpact impact)
        throws RequestException
    {
        if(impact.aliasCount() > MAXIMUM_ADMIN_COLLECTION_ITEMS)
        {
            throw error(413, "alias_list_delete_too_large",
                "alias_count exceeds the maximum deletable alias-list size of " +
                    MAXIMUM_ADMIN_COLLECTION_ITEMS, "alias_count");
        }
        if(impact.channelCount() > MAXIMUM_ADMIN_COLLECTION_ITEMS)
        {
            throw error(413, "alias_list_delete_too_large",
                "channel_count exceeds the maximum deletable alias-list size of " +
                    MAXIMUM_ADMIN_COLLECTION_ITEMS, "channel_count");
        }
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException
    {
        sendError(exchange, status, code, message, null);
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message, String field)
        throws IOException
    {
        WebAccessHttpController.prepareSecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Vary", "Cookie");
        ApiHttpResponse.sendError(exchange, status, code, message, field);
    }

    private static void sendData(HttpExchange exchange, int status, Object value) throws IOException
    {
        WebAccessHttpController.prepareSecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Vary", "Cookie");
        ApiHttpResponse.sendData(exchange, status, value);
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
        return error(status, code, message, null);
    }

    private static RequestException error(int status, String code, String message, String field)
    {
        return new RequestException(status, code, message, field);
    }

    private record CreateListRequest(Long revision, String name, String family) {}
    private record DeleteListRequest(Long revision, Boolean confirmed) {}
    private record UnmatchedPolicyRequest(Long revision, Boolean listenEnabled, Integer priority,
                                          Boolean recordable, List<String> broadcastChannels) {}
    private record RevisionRequest(Long revision) {}
    private record AliasRequest(Long revision, AliasPayload alias) {}
    private record BulkRequest(Long revision, List<Long> aliasIds, Long aliasListId, Integer color, String iconName,
                               Boolean listenEnabled, Integer priority, Boolean recordable,
                               String groupOperation, String group, String streamOperation,
                               List<String> broadcastChannels, Boolean delete) {}
    private record AliasPayload(Long aliasListId, String name, String description, String group, Integer color,
                                String iconName, Boolean listenEnabled, Integer priority, Boolean recordable,
                                List<String> broadcastChannels, Integer streamAsTalkgroup, MatcherPayload matcher) {}
    private record AliasView(long id, long aliasListId, String name, String description, String group, int color,
                             String iconName, boolean listenEnabled, Integer priority, boolean recordable,
                             List<String> broadcastChannels, Integer streamAsTalkgroup, boolean overlap,
                             Map<String,Object> matcher) {}
    private record TonePayload(String tone, Integer duration) {}

    private record MatcherPayload(String type, String protocol, String variant, Integer value, Integer minimum,
                                  Integer maximum, Integer status, String code, String esn, List<TonePayload> tones)
    {}

    private static final class RequestException extends Exception
    {
        private final int mStatus;
        private final String mCode;
        private final String mField;

        private RequestException(int status, String code, String message, String field)
        {
            super(message);
            mStatus = status;
            mCode = code;
            mField = field;
        }

        private int status()
        {
            return mStatus;
        }

        private String code()
        {
            return mCode;
        }

        private String field()
        {
            return mField;
        }
    }

    interface AliasListDeletion
    {
        AliasAdministrationService.DeleteImpact impact(long aliasListId, int maximumCount);

        AliasAdministrationService.MutationResult delete(long aliasListId, long revision, boolean confirmed);
    }
}
