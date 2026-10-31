/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallPlaybackTarget;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.http.ApiRequestDecoder;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** On-demand alias editing context and presentation for retained browser audio calls. */
final class StatsCallAliasHttpController
{
    static final String PATH = StatsApiV1.ROOT + "/admin/call-aliases";
    static final int MAXIMUM_PRESENTATION_CALLS = 128;
    private final Function<String,StatsWebCallService.CachedCall> mCalls;

    StatsCallAliasHttpController(StatsWebCallService calls) { this(calls::get); }

    StatsCallAliasHttpController(Function<String,StatsWebCallService.CachedCall> calls)
    {
        mCalls = Objects.requireNonNull(calls);
    }

    void handle(HttpExchange exchange) throws IOException
    {
        try
        {
            if(!"GET".equals(exchange.getRequestMethod()))
            {
                exchange.getResponseHeaders().set("Allow", "GET");
                throw new StatsApiException(405, "Use GET to load alias settings");
            }
            if(ApiRequestDecoder.readBody(exchange, 0).length != 0)
                throw new StatsApiException(400, "This request does not accept a body");
            ApiHttpResponse.sendData(exchange, 200, read(exchange.getRequestURI()));
        }
        catch(StatsApiException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", "The call alias request is invalid");
        }
    }

    Map<String,Object> read(URI uri)
    {
        String path = uri != null ? uri.getRawPath() : null;
        Map<String,List<String>> query = query(uri);
        if((PATH + "/presentation").equals(path))
        {
            if(query.size() != 1 || !query.containsKey("call_id") ||
                query.get("call_id").size() > MAXIMUM_PRESENTATION_CALLS)
                throw new StatsApiException(400, "Choose a bounded set of retained calls");
            List<Map<String,Object>> presentations = new ArrayList<>();
            for(String id: new LinkedHashSet<>(query.get("call_id")))
            {
                requireId(id);
                StatsWebCallService.CachedCall call = mCalls.apply(id);
                if(call != null) presentations.add(presentation(call));
            }
            return Map.of("calls", List.copyOf(presentations));
        }
        String prefix = PATH + "/";
        if(path == null || !path.startsWith(prefix)) throw new StatsApiException(404, "Call not found");
        String id = path.substring(prefix.length());
        requireId(id);
        if(query.size() != 1 || !query.containsKey("side") || query.get("side").size() != 1 ||
            !List.of("source", "target").contains(query.get("side").getFirst()))
            throw new StatsApiException(400, "Choose the source or target identity");
        StatsWebCallService.CachedCall call = mCalls.apply(id);
        if(call == null) throw new StatsApiException(404, "This call is no longer available");
        return context(call, query.get("side").getFirst());
    }

    static Map<String,Object> context(StatsWebCallService.CachedCall call, String side)
    {
        AudioCallSnapshot snapshot = call.snapshot();
        AliasList list = snapshot != null ? snapshot.aliasList() : null;
        IdentifierCollection identifiers = snapshot != null ? snapshot.identifierCollection() : null;
        Identifier<?> identity = identifiers == null ? null : "source".equals(side) ?
            identifiers.getFromIdentifier() : identifiers.getToIdentifier();
        // A patch's primary group must not select an unrelated member's alias for editing.
        if(identity instanceof PatchGroupIdentifier patch)
            identity = patch.getValue() != null ? patch.getValue().getPatchGroup() : null;
        Map<String,Object> matcher = matcher(identity);
        boolean available = list != null && list.getId() > 0 && matcher != null;
        Alias existing = available ? list.getAliases(identity).stream()
            .filter(alias -> alias.getId() > 0 && alias.getAliasListId() == list.getId()).findFirst().orElse(null) : null;
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("call_id", call.id());
        value.put("side", side);
        value.put("available", available);
        value.put("alias_list_id", list != null && list.getId() > 0 ? list.getId() : null);
        value.put("alias_id", existing != null ? existing.getId() : null);
        value.put("matcher", matcher);
        value.put("presentation", presentation(call));
        return value;
    }

    /** Names only: captured recording/output decisions, audio, cursors and queue eligibility stay frozen. */
    static Map<String,Object> presentation(StatsWebCallService.CachedCall call)
    {
        AudioCallSnapshot snapshot = call.snapshot();
        IdentifierCollection identifiers = snapshot != null ? snapshot.identifierCollection() : null;
        AliasList list = snapshot != null ? snapshot.aliasList() : null;
        Identifier<?> source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Identifier<?> target = identifiers != null ? identifiers.getToIdentifier() : null;
        AudioCallRecordingMetadata.SourceDecision from = AudioCallRecordingMetadata.captureSource(list, source);
        AudioCallRecordingMetadata.DestinationDecision to = AudioCallRecordingMetadata.captureDestination(list, target);
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("call_id", call.id());
        value.put("source_alias", from.aliasName());
        value.put("source_description", from.aliasDescription());
        value.put("source_group", from.aliasGroup());
        value.put("target_alias", to.aliasName());
        value.put("target_description", to.aliasDescription());
        value.put("target_group", to.aliasGroup());
        AudioCallRecordingMetadata current = AudioCallRecordingMetadata.captureAtSnapshot(list, identifiers);
        CallPlaybackTarget playback = snapshot != null ? CallPlaybackTarget.from(snapshot, target) : null;
        if(playback != null && (playback.kind() == CallPlaybackTarget.Kind.CHANNEL ||
            playback.kind() == CallPlaybackTarget.Kind.CHANNEL_TIMESLOT)) current = snapshot.recordingMetadata();
        value.put("playback_target_label", snapshot != null ? StatsWebCallService.playbackTargetLabel(
            playback, current, target,
            Objects.toString(call.metadata().get("serving_system_key"), null)) : null);
        return value;
    }

    private static Map<String,Object> matcher(Identifier<?> identity)
    {
        if(identity == null || !identity.isValid() ||
            identity.getForm() != Form.RADIO && identity.getForm() != Form.TALKGROUP) return null;
        P25SubscriberIdentity canonical = P25SubscriberIdentity.from(identity);
        if(canonical != null) return Map.of("type", "p25_subscriber_identity", "home_wacn", canonical.homeWacn(),
            "home_system_id", canonical.homeSystemId(), "subscriber_id", canonical.subscriberId());
        int minimum = identity.getForm() == Form.TALKGROUP && identity.getProtocol() == Protocol.NXDN ? 0 : 1;
        if(!(identity.getValue() instanceof Number number) || number.longValue() < minimum ||
            number.longValue() > Integer.MAX_VALUE) return null;
        String protocol = switch(identity.getProtocol())
        {
            case APCO25, APCO25_PHASE2 -> "p25";
            case DMR -> "dmr";
            case NXDN -> "nxdn";
            case NBFM -> "nbfm";
            case AM -> "am";
            case FLEETSYNC -> "fleetsync";
            case MDC1200 -> "mdc1200";
            case null, default -> null;
        };
        if(protocol == null) return null;
        Map<String,Object> matcher = new LinkedHashMap<>();
        matcher.put("type", identity.getForm() == Form.RADIO ? "radio" : "talkgroup");
        matcher.put("protocol", protocol);
        matcher.put("value", number.intValue());
        if("p25".equals(protocol)) matcher.put("variant", identity.getProtocol() == Protocol.APCO25_PHASE2 ?
            "phase_2" : "phase_1");
        return matcher;
    }

    private static void requireId(String id)
    {
        if(id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
            throw new StatsApiException(400, "The call identity is invalid");
    }

    private static Map<String,List<String>> query(URI uri)
    {
        String raw = uri != null ? uri.getRawQuery() : null;
        if(raw == null || raw.isEmpty()) return Map.of();
        if(raw.length() > MAXIMUM_PRESENTATION_CALLS * 160)
            throw new StatsApiException(400, "The call alias request is too large");
        Map<String,List<String>> query = new LinkedHashMap<>();
        for(String parameter: raw.split("&", -1))
        {
            String[] parts = parameter.split("=", 2);
            if(parts.length != 2) throw new StatsApiException(400, "The call alias request is invalid");
            String key = ApiRequestDecoder.decodeComponent(parts[0], true);
            String value = ApiRequestDecoder.decodeComponent(parts[1], true);
            query.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return query;
    }
}
