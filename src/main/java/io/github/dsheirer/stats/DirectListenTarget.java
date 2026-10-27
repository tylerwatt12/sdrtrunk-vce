/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.ResolvedCallPolicy;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.protocol.Protocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated browser-session selection, independent of shared Alias and Scan List configuration. */
record DirectListenTarget(String kind, String key, String radioSystemKey, String identityKey, Integer timeslot)
{
    static final int MAXIMUM_TARGETS = 32;
    static final int MAXIMUM_JSON_CHARACTERS = 512;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    DirectListenTarget
    {
        if("channel".equals(kind))
        {
            key = WebEntityRef.channel(key).key();
            if(radioSystemKey != null || timeslot != null && (timeslot < 1 || timeslot > 2))
                throw new IllegalArgumentException("Invalid channel listening target");
        }
        else if("talkgroup".equals(kind) || "patch_group".equals(kind))
        {
            if(key != null || timeslot != null) throw new IllegalArgumentException("Invalid group listening target");
            WebEntityRef.ScopedIdentityRef reference = "talkgroup".equals(kind) ?
                WebEntityRef.talkgroup(radioSystemKey, identityKey) : WebEntityRef.patchGroup(radioSystemKey, identityKey);
            radioSystemKey = reference.radioSystemKey();
            identityKey = reference.identityKey();
            RadioSystemIdentityKey.Identity identity = RadioSystemIdentityKey.parse(identityKey);
            if(!RadioSystemKey.isP25Native(radioSystemKey) && identity.hasHome() ||
                RadioSystemKey.isP25Native(radioSystemKey) && identity.identityId() > RadioSystemIdentityKey.MAX_P25_GROUP_ID)
                throw new IllegalArgumentException("Group identity does not match its radio system");
        }
        else throw new IllegalArgumentException("Unsupported listening target");

        if(identityKey != null)
        {
            int identityKind = RadioSystemIdentityKey.parse(identityKey).kindCode();
            if(identityKind != RadioSystemIdentityKey.KIND_TALKGROUP &&
                identityKind != RadioSystemIdentityKey.KIND_PATCH_GROUP)
                throw new IllegalArgumentException("Listening targets require a talkgroup or patch group");
        }
    }

    static DirectListenTarget parse(String value)
    {
        if(value == null || value.length() > MAXIMUM_JSON_CHARACTERS)
            throw new IllegalArgumentException("Listening target is too large");
        try
        {
            JsonNode node = JSON.readTree(value);
            if(node == null || !node.isObject()) throw new IllegalArgumentException("Listening target must be an object");
            Set<String> fields = Set.of("kind", "key", "radio_system_key", "identity_key", "timeslot");
            var names = node.fieldNames();
            while(names.hasNext()) if(!fields.contains(names.next()))
                throw new IllegalArgumentException("Unknown listening target field");
            JsonNode slot = node.get("timeslot");
            if(slot != null && (!slot.isIntegralNumber() || !slot.canConvertToInt()))
                throw new IllegalArgumentException("Invalid listening timeslot");
            return new DirectListenTarget(text(node, "kind"), text(node, "key"),
                text(node, "radio_system_key"), text(node, "identity_key"), slot != null ? slot.intValue() : null);
        }
        catch(IOException exception)
        {
            throw new IllegalArgumentException("Invalid listening target JSON", exception);
        }
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if(value == null) return null;
        if(!value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("Invalid listening target field");
        return value.textValue();
    }

    /** Property order is shared with browser normalization and does not include client-supplied labels. */
    String canonicalKey()
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("kind", kind);
        if("channel".equals(kind)) value.put("key", key);
        else value.put("radio_system_key", radioSystemKey);
        if(identityKey != null) value.put("identity_key", identityKey);
        if(timeslot != null) value.put("timeslot", timeslot);
        try { return JSON.writeValueAsString(value); }
        catch(IOException exception) { throw new IllegalStateException(exception); }
    }

    boolean matches(Evidence evidence)
    {
        for(Scope scope: evidence.scopes())
        {
            if("channel".equals(kind))
            {
                if(scope.channelKind() != ChannelKind.CONVENTIONAL || !key.equals(scope.channelKey()) ||
                    timeslot != null && !timeslot.equals(scope.timeslot())) continue;
                if(identityKey == null) return true;
            }
            else if(scope.channelKind() != ChannelKind.TRUNKED || !radioSystemKey.equals(scope.systemKey())) continue;

            RadioSystemIdentityKey.Identity identity = RadioSystemIdentityKey.parse(identityKey);
            if(scope.protocol() == Protocol.APCO25 && identity.identityId() > RadioSystemIdentityKey.MAX_P25_GROUP_ID)
                continue;
            ResolvedCallPolicy.DestinationQualifier qualifier = identity.hasHome() ?
                ResolvedCallPolicy.DestinationQualifier.networkAndSystem(identity.homeWacn(), identity.homeSystemId()) : null;
            ResolvedCallPolicy.DestinationIdentity wanted = new ResolvedCallPolicy.DestinationIdentity(scope.protocol(),
                identity.kindCode() == RadioSystemIdentityKey.KIND_PATCH_GROUP ? Form.PATCH_GROUP : Form.TALKGROUP,
                identity.hasHome() ? 0 : identity.identityId(), identity.identityId(), qualifier);
            for(var destination: scope.destinations()) if(wanted.matches(destination, scope.systemKey())) return true;
        }
        return false;
    }

    /** Worker-only immutable matching facts; retains no PCM or mutable Alias List, and never truncates patch members. */
    static Evidence evidence(CompletedAudioCall call)
    {
        List<Scope> scopes = new ArrayList<>();
        for(var context: call.resolvedPolicy().matchContexts())
        {
            for(var leg: call.callLegSummaries())
            {
                CallLegSource source = leg.source();
                if(context.channelConfigurationId() == null ||
                    !context.channelConfigurationId().equals(source.channelConfigurationId()) || source.decoderType() == null)
                    continue;
                Protocol protocol = source.decoderType().getProtocol();
                if(protocol == Protocol.APCO25_PHASE2) protocol = Protocol.APCO25;
                String systemKey = protocol == Protocol.APCO25 ? RadioSystemKey.p25Serving(source.radioSystemKey(),
                    source.p25SiteIdentity(), leg.winner() ? call.snapshot().identifierCollection() : null) :
                    source.radioSystemKey() != null ? source.radioSystemKey() :
                        RadioSystemKey.channelScoped(protocol, source.identityDomain(), source.channelConfigurationId());
                scopes.add(new Scope(context.channelConfigurationId(), context.channelKind(), systemKey, protocol,
                    leg.timeslot(), context.destinationIdentities()));
            }
        }
        return new Evidence(List.copyOf(scopes));
    }

    record Evidence(List<Scope> scopes) {}
    private record Scope(String channelKey, ChannelKind channelKind, String systemKey, Protocol protocol,
                         Integer timeslot, List<ResolvedCallPolicy.DestinationIdentity> destinations) {}
}
