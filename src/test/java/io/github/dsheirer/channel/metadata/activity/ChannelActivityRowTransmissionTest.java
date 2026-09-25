/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierCollection;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChannelActivityRowTransmissionTest
{
    @Test
    void grantStateNeverClaimsTransmissionAndObservedLifecycleUsesExactLegOwnership()
    {
        Channel channel = new Channel("Traffic", Channel.ChannelType.TRAFFIC);
        ChannelActivityTableState table = new ChannelActivityTableState("System", channel, null);
        ChannelActivityRow row = table.getOrCreate("traffic:1", channel, ChannelActivityRow.Role.TRAFFIC,
            851_012_500L, 1);

        row.setState(State.CALL);
        assertNull(row.getTransmissionState(), "a grant/activity row is not RF evidence");

        AudioCallId firstChunk = new AudioCallId(7, 1, 1);
        CallLegId firstLeg = CallLegId.from(firstChunk);
        row.observeTransmission(event(AudioCallEventType.CALL_CREATED,
            snapshot(firstChunk, null, firstLeg, 1_000L, 1_000L, false, false), false));
        assertEquals(ChannelActivityRow.TransmissionState.PENDING, row.getTransmissionState());
        assertFalse(row.isTransmissionEndCertain());

        row.observeTransmission(event(AudioCallEventType.BURST_STARTED,
            snapshot(firstChunk, null, firstLeg, 1_000L, 1_100L, true, false), false));
        assertEquals(ChannelActivityRow.TransmissionState.ACTIVE, row.getTransmissionState());

        //A chunk rollover is not the end of the physical call leg.
        row.observeTransmission(event(AudioCallEventType.CALL_COMPLETED,
            snapshot(firstChunk, null, firstLeg, 1_000L, 2_000L, false, true), true));
        assertEquals(ChannelActivityRow.TransmissionState.ACTIVE, row.getTransmissionState());

        AudioCallId linkedChunk = new AudioCallId(7, 2, 1);
        row.observeTransmission(event(AudioCallEventType.CALL_CREATED,
            snapshot(linkedChunk, firstChunk, firstLeg, 2_000L, 2_000L, false, false), false));
        assertEquals(ChannelActivityRow.TransmissionState.ACTIVE, row.getTransmissionState());
        assertEquals(1_000L, row.getTransmissionStart());

        //A late terminal callback from the retired chunk cannot end its replacement.
        assertFalse(row.observeTransmission(event(AudioCallEventType.CALL_COMPLETED,
            snapshot(firstChunk, null, firstLeg, 1_000L, 2_100L, false, true), false)));
        assertEquals(ChannelActivityRow.TransmissionState.ACTIVE, row.getTransmissionState());

        row.observeTransmission(event(AudioCallEventType.BURST_ENDED,
            snapshot(linkedChunk, firstChunk, firstLeg, 2_000L, 2_200L, false, false), false));
        assertEquals(ChannelActivityRow.TransmissionState.ENDED, row.getTransmissionState());
        assertTrue(row.isTransmissionEndCertain());

        ChannelActivitySnapshot.Transmission wire = ChannelActivitySnapshot.from(table).rows().getFirst()
            .transmission();
        assertEquals(firstLeg.toString(), wire.callLegId());
        assertEquals("ended", wire.state());
        assertTrue(wire.endCertain());
    }

    @Test
    void idleTimeoutIsUncertainButAnExplicitTerminalEventIsCertain()
    {
        Channel channel = new Channel("Traffic", Channel.ChannelType.TRAFFIC);
        ChannelActivityRow row = new ChannelActivityRow("traffic:1", channel,
            ChannelActivityRow.Role.TRAFFIC, 851_012_500L, 1);
        AudioCallId callId = new AudioCallId(8, 1, 1);
        CallLegId callLegId = CallLegId.from(callId);

        row.setState(State.CALL);
        row.observeTransmission(event(AudioCallEventType.CALL_CREATED,
            snapshot(callId, null, callLegId, 3_000L, 3_000L, false, false), false));
        row.observeTransmission(event(AudioCallEventType.BURST_STARTED,
            snapshot(callId, null, callLegId, 3_000L, 3_100L, true, false), false));
        row.setState(State.IDLE);
        assertEquals(ChannelActivityRow.TransmissionState.UNCERTAIN, row.getTransmissionState());
        assertFalse(row.isTransmissionEndCertain());

        row.observeTransmission(event(AudioCallEventType.CALL_COMPLETED,
            snapshot(callId, null, callLegId, 3_000L, 3_200L, false, true), false));
        assertEquals(ChannelActivityRow.TransmissionState.ENDED, row.getTransmissionState());
        assertTrue(row.isTransmissionEndCertain());
    }

    @Test
    void semanticBurstGenerationTracksKeyUpsAcrossOneLegButNotLinkedChunkRollover()
    {
        Channel channel = new Channel("Traffic", Channel.ChannelType.TRAFFIC);
        ChannelActivityRow row = new ChannelActivityRow("traffic:1", channel,
            ChannelActivityRow.Role.TRAFFIC, 851_012_500L, 1);
        AudioCallId firstChunk = new AudioCallId(9, 1, 1);
        CallLegId firstLeg = CallLegId.from(firstChunk);

        row.observeTransmission(event(AudioCallEventType.CALL_CREATED,
            snapshot(firstChunk, null, firstLeg, 1_000L, 1_000L, false, false, 0, 0), false));
        row.observeTransmission(event(AudioCallEventType.BURST_STARTED,
            snapshot(firstChunk, null, firstLeg, 1_000L, 1_100L, true, false, 1, 1_100L), false));
        assertEquals(1L, row.getTransmissionBurstGeneration());
        assertEquals(1_100L, row.getTransmissionBurstStart());

        //Duplicate start and sampled audio evidence remain one visual key-up.
        row.observeTransmission(event(AudioCallEventType.BURST_STARTED,
            snapshot(firstChunk, null, firstLeg, 1_000L, 1_125L, true, false, 1, 1_100L), false));
        row.observeTransmission(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME,
            snapshot(firstChunk, null, firstLeg, 1_000L, 1_150L, true, false, 1, 1_100L),
            new float[]{0.0f}, false, 1L, 1_150L));
        assertEquals(1L, row.getTransmissionBurstGeneration());

        row.observeTransmission(event(AudioCallEventType.BURST_ENDED,
            snapshot(firstChunk, null, firstLeg, 1_000L, 1_200L, false, false, 1, 1_100L), false));
        row.observeTransmission(event(AudioCallEventType.BURST_STARTED,
            snapshot(firstChunk, null, firstLeg, 1_000L, 1_300L, true, false, 2, 1_300L), false));
        assertEquals(2L, row.getTransmissionBurstGeneration());
        assertEquals(1_300L, row.getTransmissionBurstStart());

        //A linked storage chunk is still the same live RF burst.
        row.observeTransmission(event(AudioCallEventType.CALL_COMPLETED,
            snapshot(firstChunk, null, firstLeg, 1_000L, 1_350L, false, true, 2, 1_300L), true));
        AudioCallId linkedChunk = new AudioCallId(9, 2, 1);
        row.observeTransmission(event(AudioCallEventType.CALL_CREATED,
            snapshot(linkedChunk, firstChunk, firstLeg, 1_350L, 1_350L, false, false, 0, 0), false));
        row.observeTransmission(event(AudioCallEventType.BURST_STARTED,
            snapshot(linkedChunk, firstChunk, firstLeg, 1_350L, 1_400L, true, false, 1, 1_400L), false));
        assertEquals(2L, row.getTransmissionBurstGeneration());
        assertEquals(1_300L, row.getTransmissionBurstStart());

        //The same rollover recovers if its CALL_CREATED queue observation is dropped.
        row.observeTransmission(event(AudioCallEventType.CALL_COMPLETED,
            snapshot(linkedChunk, firstChunk, firstLeg, 1_350L, 1_450L, false, true, 1, 1_400L), true));
        AudioCallId recoveredChunk = new AudioCallId(9, 3, 1);
        row.observeTransmission(event(AudioCallEventType.BURST_STARTED,
            snapshot(recoveredChunk, linkedChunk, firstLeg, 1_450L, 1_500L, true, false, 1, 1_500L), false));
        assertEquals(2L, row.getTransmissionBurstGeneration());
        assertEquals(1_300L, row.getTransmissionBurstStart());

        //A terminal callback can also transfer ownership when every intermediate observation for the successor
        //chunk was dropped.  It ends the existing leg without manufacturing another key-up.
        row.observeTransmission(event(AudioCallEventType.CALL_COMPLETED,
            snapshot(recoveredChunk, linkedChunk, firstLeg, 1_450L, 1_525L, false, true, 1, 1_500L), true));
        AudioCallId terminalChunk = new AudioCallId(9, 4, 1);
        assertTrue(row.observeTransmission(event(AudioCallEventType.CALL_COMPLETED,
            snapshot(terminalChunk, recoveredChunk, firstLeg, 1_525L, 1_550L, false, true, 0, 0), false)));
        assertEquals(ChannelActivityRow.TransmissionState.ENDED, row.getTransmissionState());
        assertEquals(2L, row.getTransmissionBurstGeneration());

        //A new leg can also recover when its CALL_CREATED observation was dropped.
        AudioCallId nextChunk = new AudioCallId(10, 1, 1);
        CallLegId nextLeg = CallLegId.from(nextChunk);
        row.observeTransmission(event(AudioCallEventType.BURST_STARTED,
            snapshot(nextChunk, null, nextLeg, 2_000L, 2_100L, true, false, 1, 2_100L), false));
        assertEquals(1L, row.getTransmissionBurstGeneration());
        assertEquals(2_100L, row.getTransmissionBurstStart());

        ChannelActivityRow copy = row.copy();
        assertEquals(nextLeg, copy.getCallLegId());
        assertEquals(ChannelActivityRow.TransmissionState.ACTIVE, copy.getTransmissionState());
        assertEquals(1L, copy.getTransmissionBurstGeneration());
        assertEquals(2_100L, copy.getTransmissionBurstStart());
    }

    private static AudioCallEvent event(AudioCallEventType type, AudioCallSnapshot snapshot,
                                        boolean continuationExpected)
    {
        return new AudioCallEvent(type, snapshot, null, continuationExpected, 0L, 0L);
    }

    private static AudioCallSnapshot snapshot(AudioCallId callId, AudioCallId linkedCallId, CallLegId callLegId,
                                              long start, long last, boolean burstActive, boolean complete)
    {
        return snapshot(callId, linkedCallId, callLegId, start, last, burstActive, complete, 1, start);
    }

    private static AudioCallSnapshot snapshot(AudioCallId callId, AudioCallId linkedCallId, CallLegId callLegId,
                                              long start, long last, boolean burstActive, boolean complete,
                                              long burstGeneration, long burstStart)
    {
        return new AudioCallSnapshot(callId, linkedCallId, null, new IdentifierCollection(), Set.of(), start, last,
            1, burstGeneration, burstStart, last, burstActive, complete, CallEncryptionState.CLEAR, false, null,
            VoiceCallQuality.EMPTY, callLegId, null, null);
    }
}
