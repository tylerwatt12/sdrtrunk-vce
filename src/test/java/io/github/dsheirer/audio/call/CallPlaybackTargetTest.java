/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.DMRMessage;
import io.github.dsheirer.module.decode.nbfm.NBFMTalkgroup;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
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
        assertEquals("system:p25:bee00:348:talkgroup:101", firstSite.key());
        assertNotEquals(firstSite.key(), otherSystem.key());
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
    void trunkedDmrScopesStaySeparateUntilNativeGroupingIsProven()
    {
        Identifier<?> talkgroup = new DMRTalkgroup(9001);
        CallPlaybackTarget first = target(source(DecoderType.DMR, CHANNEL_A, null,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED), talkgroup, 0);
        CallPlaybackTarget second = target(source(DecoderType.DMR, CHANNEL_B, null,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED), talkgroup, 1);

        assertEquals("system:dmr:channel:" + CHANNEL_A + ":talkgroup:9001", first.key());
        assertEquals("system:dmr:channel:" + CHANNEL_B + ":talkgroup:9001", second.key());
        assertNotEquals(first, second);
    }

    private static CallLegSource source(DecoderType decoderType, String configurationId, P25SiteIdentity p25,
                                        ChannelConfigurationPolicy.ChannelKind channelKind)
    {
        return new CallLegSource(decoderType, configurationId, "Display name", null, 1, p25, channelKind,
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
}
