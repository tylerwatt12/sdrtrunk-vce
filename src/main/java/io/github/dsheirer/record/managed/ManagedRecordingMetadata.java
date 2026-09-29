/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.record.managed;

import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CallLegSummary;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.dcs.DCSIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.protocol.Protocol;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A small immutable copy of call facts. No source audio or decoder object is retained by the catalog queue. */
record ManagedRecordingMetadata(long startMs, long endMs, long durationMs, long sizeBytes,
                                String relativePath, String systemKey, String channelId, long aliasListId,
                                int protocol, int callType, int voiceType, Integer sourceId,
                                Integer sourceHomeWacn, Integer sourceHomeSystem, Integer sourceHomeId,
                                Integer targetId, Integer targetHomeWacn, Integer targetHomeSystem,
                                Integer targetHomeId, Long frequencyHz, Integer timeslot,
                                Integer nac, Integer toneKind, String tone,
                                ManagedRecordingCatalog.Site winnerSite,
                                List<ManagedRecordingCatalog.Site> observedSites,
                                List<ManagedRecordingCatalog.Member> patchMembers)
{
    static ManagedRecordingMetadata from(CompletedAudioCall call, String relativePath, long sizeBytes)
    {
        AudioCallSnapshot snapshot = call.snapshot();
        CallLegSource source = snapshot.callLegSource();
        CallLegSummary winner = call.callLegSummaries().stream().filter(CallLegSummary::winner)
            .findFirst().orElseThrow();
        IdentifierCollection identifiers = snapshot.identifierCollection();
        Identifier<?> sourceIdentifier = identifiers != null ? identifiers.getFromIdentifier() : null;
        Identifier<?> targetIdentifier = identifiers != null ? identifiers.getToIdentifier() : null;
        CallLegSummary.P25IdentityObservation sourceIdentity = winner.p25SourceIdentity();
        CallLegSummary.P25IdentityObservation targetIdentity = winner.p25DestinationIdentity();
        Integer sourceId = sourceIdentity != null ? Integer.valueOf(sourceIdentity.observedLocalId()) :
            number(sourceIdentifier);
        Integer targetId = targetIdentity != null ? Integer.valueOf(targetIdentity.observedLocalId()) :
            number(unwrapPatch(targetIdentifier));
        Form targetForm = targetIdentity != null ? targetIdentity.form() :
            targetIdentifier != null ? targetIdentifier.getForm() : null;
        int callType = switch(targetForm != null ? targetForm : Form.ANY)
        {
            case PATCH_GROUP -> ManagedRecordingCatalog.CALL_PATCH;
            case TALKGROUP -> ManagedRecordingCatalog.CALL_GROUP;
            case RADIO, TELEPHONE_NUMBER -> ManagedRecordingCatalog.CALL_DIRECT;
            default -> ManagedRecordingCatalog.CALL_CONVENTIONAL;
        };
        Protocol protocol = source != null && source.decoderType() != null ?
            source.decoderType().getProtocol() : targetIdentifier != null ? targetIdentifier.getProtocol() : null;
        int voiceType = snapshot.encryptionState() == CallEncryptionState.ENCRYPTED ?
            ManagedRecordingCatalog.VOICE_ENCRYPTED : snapshot.encryptionState() == CallEncryptionState.CLEAR ?
                ManagedRecordingCatalog.VOICE_CLEAR : ManagedRecordingCatalog.VOICE_UNKNOWN;
        Set<ManagedRecordingCatalog.Site> sites = new LinkedHashSet<>();
        ManagedRecordingCatalog.Site winningSite = site(source != null ? source.p25SiteIdentity() : null);

        for(CallLegSummary leg : call.callLegSummaries())
        {
            ManagedRecordingCatalog.Site observed = site(leg.source().p25SiteIdentity());
            if(observed != null)
            {
                sites.add(observed);
                if(leg.winner())
                {
                    winningSite = observed;
                }
            }
        }

        if(winningSite != null)
        {
            sites.add(winningSite);
        }

        Set<ManagedRecordingCatalog.Member> members = new LinkedHashSet<>();
        for(CallLegSummary leg : call.callLegSummaries())
        {
            for(CallLegSummary.P25IdentityObservation member : leg.p25PatchMemberIdentities())
            {
                if(member.form() == Form.TALKGROUP || member.form() == Form.RADIO)
                {
                    members.add(new ManagedRecordingCatalog.Member(member.form() == Form.TALKGROUP ?
                        "talkgroup" : "radio", member.observedLocalId(), member.homeWacn(),
                        member.homeSystemId(), member.homeIdentityId()));
                }
            }
        }

        // The selected leg owns frequency, while every confirmed site remains available to a site filter.
        Long frequency = winner.frequencyHz();
        if(frequency == null && identifiers != null)
        {
            Identifier<?> identifier = identifiers.getIdentifier(IdentifierClass.CONFIGURATION,
                Form.CHANNEL_FREQUENCY, Role.ANY);
            frequency = positiveLong(identifier);
        }

        ToneFact tone = tone(identifiers);
        return new ManagedRecordingMetadata(snapshot.startTimestamp(), snapshot.lastActivityTimestamp(),
            Math.max(0L, call.getDuration()), sizeBytes, relativePath,
            source != null ? source.radioSystemKey() : null,
            source != null ? source.channelConfigurationId() : null,
            source != null ? source.aliasListId() : 0L,
            ManagedRecordingCatalog.protocolCode(protocol), callType, voiceType, sourceId,
            sourceIdentity != null ? sourceIdentity.homeWacn() : null,
            sourceIdentity != null ? sourceIdentity.homeSystemId() : null,
            sourceIdentity != null ? sourceIdentity.homeIdentityId() : null,
            targetId,
            targetIdentity != null ? targetIdentity.homeWacn() : null,
            targetIdentity != null ? targetIdentity.homeSystemId() : null,
            targetIdentity != null ? targetIdentity.homeIdentityId() : null,
            frequency, winner.timeslot() != null ? winner.timeslot() : snapshot.timeslot() > 0 ?
                snapshot.timeslot() : null,
            integer(identifiers, Form.NETWORK_ACCESS_CODE), tone != null ? tone.kind() : null,
            tone != null ? tone.value() : null, winningSite,
            List.copyOf(sites), List.copyOf(members));
    }

    private static Identifier<?> unwrapPatch(Identifier<?> identifier)
    {
        return identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null ?
            patch.getValue().getPatchGroup() : identifier;
    }

    private static ManagedRecordingCatalog.Site site(P25SiteIdentity identity)
    {
        return identity != null ? new ManagedRecordingCatalog.Site(identity.wacn(), identity.system(),
            identity.rfss(), identity.site()) : null;
    }

    private static Integer number(Identifier<?> identifier)
    {
        Object value = identifier != null ? identifier.getValue() : null;
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long positiveLong(Identifier<?> identifier)
    {
        Object value = identifier != null ? identifier.getValue() : null;
        return value instanceof Number number && number.longValue() > 0L ? number.longValue() : null;
    }

    private static Integer integer(IdentifierCollection identifiers, Form form)
    {
        if(identifiers == null)
        {
            return null;
        }
        for(Identifier<?> identifier : identifiers.getIdentifiers(form))
        {
            if(identifier.getValue() instanceof Number number)
            {
                return number.intValue();
            }
        }
        return null;
    }

    private static ToneFact tone(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return null;
        }
        for(Identifier<?> identifier : identifiers.getIdentifiers(Form.TONE))
        {
            if(identifier instanceof DCSIdentifier && identifier.getValue() != null)
            {
                String value = String.valueOf(identifier.getValue());
                return value.length() <= 32 ? new ToneFact(1, value) : null;
            }
        }
        return null;
    }

    private record ToneFact(int kind, String value)
    {
    }
}
