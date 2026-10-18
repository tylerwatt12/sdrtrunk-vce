/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25CallUpdateEvent;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReceiverActivityCallTimingTest
{
    private static final String CONFIGURATION = "223e4567-e89b-42d3-a456-426614174000";
    private final ReceiverActivityMapper mMapper = new ReceiverActivityMapper();

    @Test
    void onlyCompletedP25ObservationCarriesAcceptedDuration()
    {
        var call = new P25CallStartEvent(CONFIGURATION, DecoderType.P25_PHASE1, DecodeEventType.CALL_GROUP,
            1_000L, List.of(APCO25Talkgroup.create(91), APCO25RadioIdentifier.createFrom(101)),
            851_000_000L, 1, 10, 1, false, false, "p25:bee00:123", "exact-p25-call");

        assertNull(mMapper.map(new P25CallUpdateEvent(call, false, 900L)).activity().durationMilliseconds());
        var completed = mMapper.map(new P25CallUpdateEvent(call, true, 900L)).activity();
        assertEquals(900L, completed.durationMilliseconds());
        assertEquals(900L, completed.withP25Identities(101, completed.targetId(), completed.targetKind(),
            completed.patchMemberTalkgroupIds(), completed.p25SourceIdentity(), completed.p25TargetIdentity(),
            completed.sourceObservedWorkingId(), completed.targetObservedWorkingId()).durationMilliseconds());
        assertNull(mMapper.map(new P25CallUpdateEvent(call, true, -1L)).activity().durationMilliseconds());
        assertEquals(0L, completed.withDurationMilliseconds(0L).durationMilliseconds());
    }

    @Test
    void analogTimingUsesExactAcceptedCallWithoutAudioOrOutputPolicy()
    {
        for(DecoderType decoder: List.of(DecoderType.NBFM, DecoderType.AM))
        {
            CompletedAudioCall call = completed(decoder, ChannelKind.CONVENTIONAL, 1_000L, 3_500L,
                "exact-analog-call", List.of());
            var timing = mMapper.mapActivityCallTiming(call);
            assertEquals("exact-analog-call", timing.activityCallToken());
            assertEquals(CONFIGURATION, timing.configurationId());
            assertEquals(2_500L, timing.durationMilliseconds());
            assertEquals(3_500L, timing.observedAtEpochMilliseconds());
            assertEquals(0L, call.getDuration(), "PCM length is independent of observed call length");
        }
    }

    @Test
    void analogTimingCannotGuessMissingBoundaryOrCallAssociation()
    {
        assertNull(mMapper.mapActivityCallTiming(completed(DecoderType.NBFM, ChannelKind.CONVENTIONAL,
            1_000L, 1_000L, "same-time", List.of(new float[800]))));
        assertNull(mMapper.mapActivityCallTiming(completed(DecoderType.NBFM, ChannelKind.CONVENTIONAL,
            0L, 3_500L, "missing-start", List.of())));
        assertNull(mMapper.mapActivityCallTiming(completed(DecoderType.NBFM, ChannelKind.CONVENTIONAL,
            1_000L, 3_500L, null, List.of())));
        assertNull(mMapper.mapActivityCallTiming(completed(DecoderType.DMR, ChannelKind.CONVENTIONAL,
            1_000L, 3_500L, "digital-call", List.of())));
        assertNull(mMapper.mapActivityCallTiming(completed(DecoderType.NBFM, ChannelKind.TRUNKED,
            1_000L, 3_500L, "wrong-mode", List.of())));
    }

    @Test
    void nativeP25AudioCannotReplaceTrackerTiming()
    {
        var call = completed(DecoderType.P25_PHASE1, ChannelKind.TRUNKED, 1_000L, 3_500L,
            "native-p25-call", List.of(new float[800]));
        assertNull(mMapper.mapResolvedLogicalCall(call).durationMilliseconds());
        assertNull(mMapper.mapActivityCallTiming(call));
    }

    @Test
    void otherTrunkedProtocolsUseAcceptedSpanInsteadOfFileLength()
    {
        for(DecoderType decoder: List.of(DecoderType.DMR, DecoderType.NXDN))
        {
            var call = completed(decoder, ChannelKind.TRUNKED, 1_000L, 3_500L,
                "native-digital-call", List.of(new float[800]));
            assertEquals(2_500L, mMapper.mapResolvedLogicalCall(call).durationMilliseconds());
            assertEquals(100L, call.getDuration());
        }
    }

    private static CompletedAudioCall completed(DecoderType decoder, ChannelKind kind, long start, long end,
                                                String token, List<float[]> audio)
    {
        var id = new AudioCallId(1, 1, 1);
        var source = new CallLegSource(decoder, CONFIGURATION, "Test", null, 0,
            decoder == DecoderType.P25_PHASE1 ? new P25SiteIdentity(0xBEE00, 0x123, 1, 1) : null,
            decoder == DecoderType.NXDN ? TrunkedIdentityDomain.NXDN_TYPE_C : TrunkedIdentityDomain.STANDARD,
            kind, kind == ChannelKind.TRUNKED);
        var snapshot = new AudioCallSnapshot(id, null, null, new IdentifierCollection(), Set.of(), start, end,
            1, 1, start, end, false, true, CallEncryptionState.CLEAR, false, null, VoiceCallQuality.EMPTY,
            CallLegId.from(id), source, null, null, token);
        return new CompletedAudioCall(snapshot, audio);
    }
}
