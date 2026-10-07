/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.record.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CallLegSummary;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ManagedRecordingMetadataTest
{
    private static final String CHANNEL_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final P25SiteIdentity SITE = new P25SiteIdentity(0xBEE00, 0x49F, 1, 1);
    private static final ChannelConfigurationPolicy.ChannelKind TRUNKED =
        ChannelConfigurationPolicy.ChannelKind.TRUNKED;

    @Test
    void derivesP25SystemKeyFromConsistentWinningAndObservedSites()
    {
        P25SiteIdentity secondSite = new P25SiteIdentity(0xBEE00, 0x49F, 2, 3);
        for(DecoderType decoder: List.of(DecoderType.P25_PHASE1, DecoderType.P25_PHASE2))
        {
            ManagedRecordingMetadata metadata = metadata(decoder, TRUNKED, SITE, null,
                List.of(secondSite));
            assertEquals("p25:bee00:49f", metadata.systemKey(), decoder.name());
            assertEquals(new ManagedRecordingCatalog.Site(0xBEE00, 0x49F, 1, 1),
                metadata.winnerSite());
            assertEquals(2, metadata.observedSites().size());
        }
    }

    @Test
    void usesConsistentObservedSiteWhenWinnerHasNoSiteIdentity()
    {
        ManagedRecordingMetadata metadata = metadata(DecoderType.P25_PHASE1, TRUNKED, null, null,
            List.of(SITE, new P25SiteIdentity(0xBEE00, 0x49F, 2, 3)));
        assertEquals("p25:bee00:49f", metadata.systemKey());
        assertNull(metadata.winnerSite());
    }

    @Test
    void winningSiteRemainsAuthoritativeWhenOtherReceiverObservedAnotherSystem()
    {
        ManagedRecordingMetadata metadata = metadata(DecoderType.P25_PHASE1, TRUNKED, SITE, null,
            List.of(new P25SiteIdentity(0xAAAAA, 0x111, 1, 1)));
        assertEquals("p25:bee00:49f", metadata.systemKey());
    }

    @Test
    void preservesExistingSourceKeyEvenIfSiteObservationsDisagree()
    {
        ManagedRecordingMetadata metadata = metadata(DecoderType.P25_PHASE1, TRUNKED, SITE,
            "p25:12345:678", List.of(new P25SiteIdentity(0xAAAAA, 0x111, 1, 1)));
        assertEquals("p25:12345:678", metadata.systemKey());
    }

    @Test
    void leavesKeyUnknownWithoutConsistentP25Evidence()
    {
        assertNull(metadata(DecoderType.P25_PHASE1, TRUNKED, null, null, List.of()).systemKey());
        assertNull(metadata(DecoderType.P25_PHASE1, TRUNKED, null, null,
            List.of(SITE, new P25SiteIdentity(0xAAAAA, 0x111, 1, 1))).systemKey());
        assertNull(metadata(DecoderType.DMR, TRUNKED, SITE, null, List.of()).systemKey());
    }

    @Test
    void usesValidatedP25SiteWhenChannelKindIsUnavailable()
    {
        assertEquals("p25:bee00:49f",
            metadata(DecoderType.P25_PHASE1, null, SITE, null, List.of()).systemKey());
    }

    @Test
    void nativeSourceAndPrivateTargetUseCapturedRecordingOwnerWithoutInventingWorkingIds()
    {
        for(DecoderType decoder: List.of(DecoderType.P25_PHASE1, DecoderType.P25_PHASE2))
        {
            var source = APCO25RadioIdentifier.createFrom(10_900_077);
            var target = APCO25RadioIdentifier.createTo(10_900_078);
            var metadata = metadata(decoder, TRUNKED, SITE, "p25:12345:678", List.of(),
                new IdentifierCollection(List.of(source, target)));
            assertEquals(10_900_077, metadata.sourceId());
            assertEquals(0x12345, metadata.sourceHomeWacn());
            assertEquals(0x678, metadata.sourceHomeSystem());
            assertEquals(10_900_077, metadata.sourceHomeId());
            assertEquals(10_900_078, metadata.targetId());
            assertEquals(0x12345, metadata.targetHomeWacn());
            assertEquals(0x678, metadata.targetHomeSystem());
            assertEquals(10_900_078, metadata.targetHomeId());
        }
    }

    @Test
    void qualifiedForeignRecordingOwnersPreserveCanonicalAndLocalNumbers()
    {
        var source = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501,
            0xABCDE, 0x321, 777);
        var target = APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(502,
            0xABCDF, 0x322, 778);
        var metadata = metadata(DecoderType.P25_PHASE1, TRUNKED, SITE, "p25:bee00:49f", List.of(),
            new IdentifierCollection(List.of(source, target)));
        assertEquals(501, metadata.sourceId());
        assertEquals(0xABCDE, metadata.sourceHomeWacn());
        assertEquals(0x321, metadata.sourceHomeSystem());
        assertEquals(777, metadata.sourceHomeId());
        assertEquals(502, metadata.targetId());
        assertEquals(0xABCDF, metadata.targetHomeWacn());
        assertEquals(0x322, metadata.targetHomeSystem());
        assertEquals(778, metadata.targetHomeId());
    }

    @Test
    void unknownOrAmbiguousRecordingScopeDoesNotGuessANativeHome()
    {
        var identifiers = new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(501),
            APCO25RadioIdentifier.createTo(502)));
        var unknown = metadata(DecoderType.P25_PHASE1, TRUNKED, null, null, List.of(), identifiers);
        var ambiguous = metadata(DecoderType.P25_PHASE1, TRUNKED, null, null,
            List.of(SITE, new P25SiteIdentity(0xABCDE, 0x321, 1, 2)), identifiers);
        for(var metadata: List.of(unknown, ambiguous))
        {
            assertNull(metadata.sourceHomeWacn());
            assertNull(metadata.sourceHomeSystem());
            assertNull(metadata.sourceHomeId());
            assertNull(metadata.targetHomeWacn());
            assertNull(metadata.targetHomeSystem());
            assertNull(metadata.targetHomeId());
        }
    }

    @Test
    void nativeRadioPatchMemberUsesItsRecordedLegOwner()
    {
        var patch = APCO25PatchGroup.create(10_003);
        patch.getValue().addPatchedRadio(APCO25RadioIdentifier.createTo(501));
        var metadata = metadata(DecoderType.P25_PHASE1, TRUNKED, SITE, "p25:bee00:49f", List.of(),
            new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(502), patch)));
        assertEquals(List.of(new ManagedRecordingCatalog.Member("radio", 501, 0xBEE00, 0x49F, 501)),
            metadata.patchMembers());
        assertNull(metadata.targetHomeWacn(), "Ordinary group ownership is unchanged");
    }

    private static ManagedRecordingMetadata metadata(DecoderType decoder,
                                                     ChannelConfigurationPolicy.ChannelKind kind,
                                                     P25SiteIdentity winnerSite, String existingKey,
                                                     List<P25SiteIdentity> otherSites)
    {
        return metadata(decoder, kind, winnerSite, existingKey, otherSites, new IdentifierCollection());
    }

    private static ManagedRecordingMetadata metadata(DecoderType decoder,
                                                     ChannelConfigurationPolicy.ChannelKind kind,
                                                     P25SiteIdentity winnerSite, String existingKey,
                                                     List<P25SiteIdentity> otherSites,
                                                     IdentifierCollection identifiers)
    {
        CallLegSource source = source(decoder, kind, winnerSite, existingKey);
        AudioCallId callId = new AudioCallId(1L, 1L, 0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), 1_000L, 2_000L, 1, 1L, 1_000L, 2_000L,
            false, true, CallEncryptionState.CLEAR, false, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), source, null);
        CompletedAudioCall single = new CompletedAudioCall(snapshot, List.of(new float[160]));
        List<CallLegSummary> legs = new ArrayList<>(single.callLegSummaries());
        for(int index = 0; index < otherSites.size(); index++)
        {
            legs.add(new CallLegSummary(new CallLegId(index + 2L, 1L, 0),
                source(decoder, kind, otherSites.get(index), null), 1_000L, 2_000L,
                VoiceCallQuality.EMPTY, 0L, false, false, false, null));
        }
        CompletedAudioCall call = new CompletedAudioCall(new LogicalCallId(1L, 1L), snapshot,
            single.audioBuffers(), null, legs);
        return ManagedRecordingMetadata.from(call, "call.mp3", 5L);
    }

    private static CallLegSource source(DecoderType decoder,
                                        ChannelConfigurationPolicy.ChannelKind kind,
                                        P25SiteIdentity site, String key)
    {
        return new CallLegSource(decoder, CHANNEL_ID, "Recorded channel", null, 1L, site,
            TrunkedIdentityDomain.STANDARD, kind, kind == TRUNKED, key);
    }
}
