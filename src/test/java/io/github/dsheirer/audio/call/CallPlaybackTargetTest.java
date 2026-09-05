/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.am.AMTalkgroup;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.DMRMessage;
import io.github.dsheirer.module.decode.nbfm.NBFMTalkgroup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25IncompleteRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CallPlaybackTargetTest
{
    private static final String CHANNEL_A = "00000000-0000-0000-0000-000000000001";
    private static final String CHANNEL_B = "00000000-0000-0000-0000-000000000002";

    @Test
    void conventionalSyntheticTalkgroupsAreScopedToTheSavedChannel()
    {
        Identifier<?> talkgroup = new NBFMTalkgroup(1);
        CallPlaybackTarget first = target(source(DecoderType.NBFM, CHANNEL_A, null,
            ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL), talkgroup, 0);
        CallPlaybackTarget second = target(source(DecoderType.NBFM, CHANNEL_B, null,
            ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL), talkgroup, 0);

        assertEquals(CallPlaybackTarget.Kind.CHANNEL, first.kind());
        assertEquals("channel:" + CHANNEL_A, first.key());
        assertEquals("channel:" + CHANNEL_B, second.key());
        assertNotEquals(first, second);
    }

    @Test
    void everyConventionalProtocolUsesTheSavedChannelInsteadOfItsDecodedTarget()
    {
        for(ConventionalCase testCase: List.of(
            new ConventionalCase(DecoderType.AM, new AMTalkgroup(1)),
            new ConventionalCase(DecoderType.NBFM, new NBFMTalkgroup(1)),
            new ConventionalCase(DecoderType.P25_CONVENTIONAL, APCO25Talkgroup.create(3101)),
            new ConventionalCase(DecoderType.NXDN, NXDNTalkgroupIdentifier.createTo(4201))))
        {
            CallPlaybackTarget first = target(source(testCase.decoderType(), CHANNEL_A, null,
                ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL), testCase.target(), 0);
            CallPlaybackTarget second = target(source(testCase.decoderType(), CHANNEL_B, null,
                ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL), testCase.target(), 0);

            assertEquals(CallPlaybackTarget.Kind.CHANNEL, first.kind(), testCase.decoderType().name());
            assertEquals("channel:" + CHANNEL_A, first.key(), testCase.decoderType().name());
            assertEquals("channel:" + CHANNEL_B, second.key(), testCase.decoderType().name());
            assertNotEquals(first, second, testCase.decoderType().name());
        }
    }

    @Test
    void p25TalkgroupIsSharedAcrossSitesOnlyInsideOneNativeSystem()
    {
        Identifier<?> talkgroup = APCO25Talkgroup.create(101);
        CallPlaybackTarget firstSite = target(source(DecoderType.P25_PHASE1, CHANNEL_A,
            new P25SiteIdentity(0xBEE00, 0x348, 1, 1), ChannelConfigurationPolicy.ChannelKind.TRUNKED),
            talkgroup, 0);
        CallPlaybackTarget secondSite = target(source(DecoderType.P25_PHASE1, CHANNEL_B,
            new P25SiteIdentity(0xBEE00, 0x348, 2, 7), ChannelConfigurationPolicy.ChannelKind.TRUNKED),
            talkgroup, 0);
        CallPlaybackTarget otherSystem = target(source(DecoderType.P25_PHASE1, CHANNEL_B,
            new P25SiteIdentity(0x00001, 0x018, 1, 1), ChannelConfigurationPolicy.ChannelKind.TRUNKED),
            talkgroup, 0);

        assertEquals(firstSite.key(), secondSite.key());
        assertEquals("system:p25:bee00:348:v1-g-bee00-348-101", firstSite.key());
        assertNotEquals(firstSite.key(), otherSystem.key());
    }

    @Test
    void conventionalP25TalkgroupsShareTheSavedChannelTarget()
    {
        CallLegSource source = source(DecoderType.P25_CONVENTIONAL, CHANNEL_A, null,
            ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL);
        CallPlaybackTarget first = target(source, APCO25Talkgroup.create(101), 0);
        CallPlaybackTarget second = target(source, APCO25Talkgroup.create(202), 0);

        assertEquals(CallPlaybackTarget.Kind.CHANNEL, first.kind());
        assertEquals("channel:" + CHANNEL_A, first.key());
        assertEquals(first, second);
    }

    @Test
    void trunkedP25TalkgroupsRemainSeparateInsideOneNativeSystem()
    {
        CallLegSource source = source(DecoderType.P25_PHASE1, CHANNEL_A,
            new P25SiteIdentity(0xBEE00, 0x348, 1, 1), ChannelConfigurationPolicy.ChannelKind.TRUNKED);
        CallPlaybackTarget first = target(source, APCO25Talkgroup.create(101), 0);
        CallPlaybackTarget second = target(source, APCO25Talkgroup.create(202), 0);

        assertEquals("system:p25:bee00:348:v1-g-bee00-348-101", first.key());
        assertEquals("system:p25:bee00:348:v1-g-bee00-348-202", second.key());
        assertNotEquals(first, second);
    }

    @Test
    void p25PrivateRadioUsesTheCanonicalHomeIdentityWithoutLosingServingSystemScope()
    {
        CallLegSource source = source(DecoderType.P25_PHASE1, CHANNEL_A,
            new P25SiteIdentity(0xBEE00, 0x348, 1, 1), ChannelConfigurationPolicy.ChannelKind.TRUNKED);
        CallPlaybackTarget ordinary = target(source, APCO25RadioIdentifier.createTo(123), 0);
        CallPlaybackTarget equivalentHome = target(source,
            APCO25FullyQualifiedRadioIdentifier.createTo(123, 0xBEE00, 0x348, 123), 0);
        CallPlaybackTarget roaming = target(source,
            APCO25FullyQualifiedRadioIdentifier.createTo(123, 0xABCDE, 0x321, 9_001), 0);

        assertEquals("system:p25:bee00:348:v1-r-bee00-348-123", ordinary.key());
        assertEquals(ordinary, equivalentHome);
        assertEquals("system:p25:bee00:348:v1-r-abcde-321-9001", roaming.key());
        assertNotEquals(ordinary, roaming);
    }

    @Test
    void incompleteP25RadioCannotBecomeAPlaybackTarget()
    {
        CallLegSource source = source(DecoderType.P25_PHASE1, CHANNEL_A,
            new P25SiteIdentity(0xBEE00, 0x348, 1, 1), ChannelConfigurationPolicy.ChannelKind.TRUNKED);

        assertNull(target(source, APCO25IncompleteRadioIdentifier.createTo(123), 0));
    }

    @Test
    void conventionalDmrTimeslotsRemainIndependent()
    {
        CallLegSource source = source(DecoderType.DMR, CHANNEL_A, null,
            ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL);
        Identifier<?> talkgroup = new DMRTalkgroup(1);
        CallPlaybackTarget first = target(source, talkgroup, DMRMessage.TIMESLOT_1);
        CallPlaybackTarget second = target(source, talkgroup, DMRMessage.TIMESLOT_2);

        assertEquals(CallPlaybackTarget.Kind.CHANNEL_TIMESLOT, first.kind());
        assertEquals("channel:" + CHANNEL_A + ":timeslot:1", first.key());
        assertEquals("channel:" + CHANNEL_A + ":timeslot:2", second.key());
        assertEquals(1, first.toMap("DMR").get("timeslot"));
        assertEquals(2, second.toMap("DMR").get("timeslot"));
        assertNotEquals(first, second);
    }

    @Test
    void conventionalDmrTalkgroupsShareTheSavedChannelAndTimeslotTarget()
    {
        CallLegSource source = source(DecoderType.DMR, CHANNEL_A, null,
            ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL);
        CallPlaybackTarget first = target(source, new DMRTalkgroup(101), DMRMessage.TIMESLOT_1);
        CallPlaybackTarget second = target(source, new DMRTalkgroup(202), DMRMessage.TIMESLOT_1);
        CallPlaybackTarget otherSlot = target(source, new DMRTalkgroup(202), DMRMessage.TIMESLOT_2);

        assertEquals("channel:" + CHANNEL_A + ":timeslot:1", first.key());
        assertEquals(first, second);
        assertNotEquals(first, otherSlot);
    }

    @Test
    void trunkedDmrScopesStaySeparateUntilNativeGroupingIsProven()
    {
        Identifier<?> talkgroup = new DMRTalkgroup(9001);
        CallPlaybackTarget first = target(source(DecoderType.DMR, CHANNEL_A, null,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED), talkgroup, 0);
        CallPlaybackTarget second = target(source(DecoderType.DMR, CHANNEL_B, null,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED), talkgroup, 1);

        assertEquals("system:dmr:channel:" + CHANNEL_A + ":v1-g-x-x-9001", first.key());
        assertEquals("system:dmr:channel:" + CHANNEL_B + ":v1-g-x-x-9001", second.key());
        assertNotEquals(first, second);
    }

    @Test
    void trunkedNxdnScopesStaySeparateUntilNativeGroupingIsProven()
    {
        Identifier<?> talkgroup = NXDNTalkgroupIdentifier.createTo(9001);
        CallPlaybackTarget first = target(source(DecoderType.NXDN, CHANNEL_A, null,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED), talkgroup, 0);
        CallPlaybackTarget second = target(source(DecoderType.NXDN, CHANNEL_B, null,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED), talkgroup, 0);

        assertEquals("system:nxdn-c:channel:" + CHANNEL_A + ":v1-g-x-x-9001", first.key());
        assertEquals("system:nxdn-c:channel:" + CHANNEL_B + ":v1-g-x-x-9001", second.key());
        assertNotEquals(first, second);
    }

    @Test
    void nxdnPlaybackUsesTheSavedAddressDomainAndRejectsContradictoryIdentifiers()
    {
        Identifier<?> typeCReserved = NXDNTalkgroupIdentifier.createTo(0xFFF0);
        Identifier<?> typeD = NXDNTalkgroupIdentifier.createTypeDTo(0xFFF0);
        CallLegSource typeCSource = source(DecoderType.NXDN, CHANNEL_A, null,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED, TrunkedIdentityDomain.NXDN_TYPE_C);
        CallLegSource typeDSource = source(DecoderType.NXDN, CHANNEL_A, null,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED, TrunkedIdentityDomain.NXDN_TYPE_D);

        assertNull(target(typeCSource, typeCReserved, 0), "Type-C reserves 0xFFF0");
        assertNull(target(typeCSource, typeD, 0), "Decoded Type-D evidence cannot override saved Type-C");
        assertNull(target(typeDSource, typeCReserved, 0), "Decoded Type-C evidence cannot override saved Type-D");
        assertEquals("system:nxdn-d:channel:" + CHANNEL_A + ":v1-g-x-x-65520",
            target(typeDSource, typeD, 0).key());
    }

    @Test
    void incompleteSourceMetadataCannotBecomeAPlaybackTarget()
    {
        CallLegSource missingDecoder = new CallLegSource(null, CHANNEL_A, "Display name", null, 1, null,
            TrunkedIdentityDomain.STANDARD, ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL, false);
        CallLegSource missingKind = new CallLegSource(DecoderType.NBFM, CHANNEL_A, "Display name", null, 1, null,
            TrunkedIdentityDomain.STANDARD, null, false);

        assertNull(target(missingDecoder, new NBFMTalkgroup(1), 0));
        assertNull(target(missingKind, new NBFMTalkgroup(1), 0));
    }

    private static CallLegSource source(DecoderType decoderType, String configurationId, P25SiteIdentity p25,
                                        ChannelConfigurationPolicy.ChannelKind channelKind)
    {
        return source(decoderType, configurationId, p25, channelKind,
            decoderType == DecoderType.NXDN ? TrunkedIdentityDomain.NXDN_TYPE_C :
                TrunkedIdentityDomain.STANDARD);
    }

    private static CallLegSource source(DecoderType decoderType, String configurationId, P25SiteIdentity p25,
                                        ChannelConfigurationPolicy.ChannelKind channelKind,
                                        TrunkedIdentityDomain identityDomain)
    {
        return new CallLegSource(decoderType, configurationId, "Display name", null, 1, p25,
            identityDomain, channelKind,
            channelKind == ChannelConfigurationPolicy.ChannelKind.TRUNKED);
    }

    private static CallPlaybackTarget target(CallLegSource source, Identifier<?> target, int timeslot)
    {
        AudioCallId callId = new AudioCallId(1, 1, timeslot);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            new IdentifierCollection(List.of(target)), Set.of(), 1, 2, 1, 1, 1, 2,
            false, true, CallEncryptionState.CLEAR, false, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), source, null);
        return CallPlaybackTarget.from(snapshot, target);
    }

    private record ConventionalCase(DecoderType decoderType, Identifier<?> target)
    {
    }
}
