/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.event.DecodeEventViewService.EventView;
import io.github.dsheirer.module.decode.event.DecodeEventViewService.PartyIdentifier;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityEligibility;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.stats.WebEntityReferences;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Immutable public-navigation snapshot. Neither rules nor observed text may supply a URL. */
public record AlertAction(String kind, @JsonProperty("entity_ref") Map<String,Object> entityRef)
{
    public static final AlertAction NONE = new AlertAction("none", null);
    public static final AlertAction LIVE = new AlertAction("live", null);
    public AlertAction
    {
        if(!Set.of("none", "live", "channel", "radio", "talkgroup", "patch_group", "radio_system").contains(kind))
            throw new IllegalArgumentException("Unsupported action destination");
        if(kind.equals("none") || kind.equals("live"))
        {
            if(entityRef != null) throw new IllegalArgumentException("This action has no entity reference");
        }
        else
        {
            entityRef = WebEntityReferences.validate(entityRef);
            if(!kind.equals(entityRef.get("kind"))) throw new IllegalArgumentException("Action kind does not match its reference");
        }
    }

    public Map<String,Object> toMap()
    {
        Map<String,Object> value = new LinkedHashMap<>(); value.put("kind", kind); value.put("entity_ref", entityRef);
        return Collections.unmodifiableMap(value);
    }

    public static AlertAction automatic(String trigger, Context context, boolean enabled)
    {
        if(!enabled) return NONE;
        if("health".equals(trigger)) return LIVE;
        Context source = context != null ? context : Context.EMPTY;
        Map<String,Object> preferred = switch(trigger)
        {
            case "call", "tone" -> source.targetGroup();
            case "emergency", "affiliation" -> source.sourceRadio();
            case "paging", "command" -> source.targetRadio() != null ? source.targetRadio() : source.sourceRadio();
            default -> null;
        };
        Map<String,Object> target = preferred != null ? preferred : source.system() != null ? source.system() : source.channel();
        return target != null ? new AlertAction((String)target.get("kind"), target) : LIVE;
    }

    /** Called only after source validation on the detached event observer worker; no lookup or I/O occurs here. */
    public static Context capture(EventView view)
    {
        Map<String,Object> channel = safe(() -> WebEntityReferences.channel(view.configurationId()));
        if(view.sourceBinding() != null)
        {
            try
            {
                if(ChannelConfigurationPolicy.requireChannelKind(view.sourceBinding().channel()) !=
                    ChannelConfigurationPolicy.ChannelKind.TRUNKED) return channelContext(channel);
            }
            catch(IllegalArgumentException | NullPointerException unavailable) { return channelContext(channel); }
        }
        String serving = view.servingSystemKey();
        String projected = view.alertFacts() != null ? view.alertFacts().systemKey() : null;
        String key = serving != null && !serving.isBlank() ? serving : projected;
        if(serving != null && !serving.isBlank() && projected != null && !projected.isBlank() && !serving.equals(projected)) key = null;
        final String systemKey = key;
        Protocol protocol;
        try { protocol = Protocol.valueOf(view.protocol()); } catch(Exception ignored) { protocol = Protocol.UNKNOWN; }
        final Protocol sourceProtocol = protocol;
        Map<String,Object> system = compatibleSystem(systemKey, sourceProtocol, view.configurationId()) ?
            safe(() -> WebEntityReferences.radioSystem(systemKey)) : null;
        if(system == null) return new Context(channel, null, null, null, null);
        return new Context(channel, system, unique(view.fromParty(), true, systemKey, sourceProtocol),
            unique(view.toParty(), true, systemKey, sourceProtocol), unique(view.toParty(), false, systemKey, sourceProtocol));
    }

    /** Completed calls use their immutable source classification, including conventional digital calls. */
    public static Context capture(EventView view, CallLegSource source)
    {
        return source != null && source.isTrunked() ? capture(view) :
            channelContext(safe(() -> WebEntityReferences.channel(view.configurationId())));
    }

    private static Context channelContext(Map<String,Object> channel)
    {
        return new Context(channel, null, null, null, null);
    }

    private static boolean compatibleSystem(String key, Protocol protocol, String channel)
    {
        if(key == null) return false;
        if(protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2) return key.startsWith("p25:");
        if(protocol == Protocol.DMR && !key.startsWith("dmr:") || protocol == Protocol.NXDN && !key.startsWith("nxdn-")) return false;
        if(protocol != Protocol.DMR && protocol != Protocol.NXDN) return false;
        return !key.contains(":channel:") || key.endsWith(":channel:" + channel);
    }

    private static Map<String,Object> unique(List<PartyIdentifier> parties, boolean radio, String system, Protocol protocol)
    {
        Map<String,Object> candidate = null;
        for(PartyIdentifier party: parties)
        {
            Map<String,Object> current = safe(() -> identity(party, radio, system, protocol));
            if(current == null) continue;
            if(candidate != null && !candidate.equals(current)) return null;
            candidate = current;
        }
        return candidate;
    }

    private static Map<String,Object> identity(PartyIdentifier party, boolean radio, String system, Protocol protocol)
    {
        Form form = Form.valueOf(party.form());
        if(radio ? form != Form.RADIO : form != Form.TALKGROUP && form != Form.PATCH_GROUP) return null;
        if(radio && party.canonicalGroupIdentity() != null || !radio && party.canonicalIdentity() != null) return null;
        Protocol observed = Protocol.valueOf(party.protocol());
        boolean p25 = protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2;
        if(observed != protocol && !(p25 && (observed == Protocol.APCO25 || observed == Protocol.APCO25_PHASE2))) return null;
        int homeWacn = RadioSystemIdentityKey.NO_HOME, homeSystem = RadioSystemIdentityKey.NO_HOME;
        Integer id = party.observedLocalId();
        if(p25)
        {
            String[] tuple = system.split(":"); homeWacn = Integer.parseInt(tuple[1], 16); homeSystem = Integer.parseInt(tuple[2], 16);
            if(radio && party.canonicalIdentity() != null)
            {
                var identity = party.canonicalIdentity(); homeWacn = identity.wacn(); homeSystem = identity.systemId(); id = identity.subscriberId();
            }
            else if(!radio && party.canonicalGroupIdentity() != null)
            {
                var identity = party.canonicalGroupIdentity(); homeWacn = identity.wacn(); homeSystem = identity.systemId(); id = identity.talkgroupId();
            }
        }
        else if(party.canonicalIdentity() != null || party.canonicalGroupIdentity() != null) return null;
        TrunkedIdentityDomain domain = system.startsWith("nxdn-d:") ? TrunkedIdentityDomain.NXDN_TYPE_D :
            system.startsWith("nxdn-c:") ? TrunkedIdentityDomain.NXDN_TYPE_C : TrunkedIdentityDomain.STANDARD;
        Integer local = radio ? party.observedWorkingId() : party.observedLocalId();
        boolean canonical = radio ? party.canonicalIdentity() != null : party.canonicalGroupIdentity() != null;
        if(canonical && local != null && local != 0 && !TrunkedIdentityEligibility.isObservedLocalEligible(protocol, domain, form, local, true)) return null;
        if(!TrunkedIdentityEligibility.isEligible(protocol, domain, form, id)) return null;
        String kind = radio ? "radio" : form == Form.PATCH_GROUP ? "patch_group" : "talkgroup";
        int code = radio ? RadioSystemIdentityKey.KIND_RADIO : form == Form.PATCH_GROUP ? RadioSystemIdentityKey.KIND_PATCH_GROUP : RadioSystemIdentityKey.KIND_TALKGROUP;
        return WebEntityReferences.identity(kind, system, RadioSystemIdentityKey.format(code, homeWacn, homeSystem, id));
    }

    private static Map<String,Object> safe(Supplier<Map<String,Object>> value)
    {
        try { return value.get(); } catch(IllegalArgumentException | NullPointerException ignored) { return null; }
    }

    public record Context(Map<String,Object> channel, Map<String,Object> system, Map<String,Object> sourceRadio,
                          Map<String,Object> targetRadio, Map<String,Object> targetGroup)
    {
        public static final Context EMPTY = new Context(null, null, null, null, null);
        public Context
        {
            channel = validate(channel, Set.of("channel")); system = validate(system, Set.of("radio_system"));
            sourceRadio = validate(sourceRadio, Set.of("radio")); targetRadio = validate(targetRadio, Set.of("radio"));
            targetGroup = validate(targetGroup, Set.of("talkgroup", "patch_group"));
        }
        private static Map<String,Object> validate(Map<String,Object> reference, Set<String> kinds)
        {
            if(reference == null) return null;
            Map<String,Object> value = WebEntityReferences.validate(reference);
            if(!kinds.contains(value.get("kind"))) throw new IllegalArgumentException("Unexpected event action context");
            return value;
        }
    }
}
