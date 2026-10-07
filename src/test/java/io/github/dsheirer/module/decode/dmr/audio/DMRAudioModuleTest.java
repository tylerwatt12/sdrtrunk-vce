/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.dmr.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.dmr.message.data.header.VoiceHeader;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.GroupVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceAMessage;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.preference.UserPreferences;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import jmbe.iface.IAudioCodec;
import jmbe.iface.IAudioWithMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DMRAudioModuleTest
{
    private static final int TIMESLOT = 1;
    private DMRAudioModule mAudioModule;
    private List<AudioCallEvent> mEvents;

    @BeforeEach
    void setUp()
    {
        mAudioModule = new DMRAudioModule(new UserPreferences(), AliasList.empty("test"), TIMESLOT)
        {
            @Override
            protected boolean hasAudioCodec()
            {
                return false;
            }
        };
        mEvents = new ArrayList<>();
        mAudioModule.setAudioCallEventListener(mEvents::add);
    }

    @AfterEach
    void tearDown()
    {
        mAudioModule.dispose();
    }

    @Test
    void encryptedVoiceSignalingCompletesMetadataOnlyCallWithoutCodec()
    {
        mAudioModule.receive(encryptedVoiceHeader(1_000L));
        mAudioModule.receive(new VoiceAMessage(DMRSyncPattern.BASE_STATION_VOICE,
            new CorrectedBinaryMessage(288), null, 1_200L, TIMESLOT));
        mAudioModule.receive(new Terminator(DMRSyncPattern.BASE_STATION_DATA,
            new CorrectedBinaryMessage(288), null, null, 2_000L, TIMESLOT, null));

        AudioCallEvent completed = mEvents.stream()
            .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).findFirst().orElseThrow();
        assertTrue(completed.snapshot().isEncrypted());
        assertEquals(1_000L, completed.snapshot().startTimestamp());
        assertEquals(2_000L, completed.snapshot().lastActivityTimestamp());
        assertFalse(mEvents.stream().anyMatch(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME));
    }

    @Test
    void clearSignalingCompletesMetadataOnlyCallWithoutCodec()
    {
        mAudioModule.receive(clearVoiceHeader(1_000L));
        mAudioModule.receive(voice(1_200L));
        mAudioModule.receive(terminator(2_000L));

        assertEquals(CallEncryptionState.CLEAR, completedEvents().getFirst().snapshot().encryptionState());
        assertFalse(mEvents.stream().anyMatch(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME));
    }

    @Test
    void standaloneSignalingWaitsForVoiceAndPublishesBothKnownStatesWithoutCodec()
    {
        for(boolean encrypted: new boolean[]{false, true})
        {
            mEvents.clear();
            mAudioModule.receive(voiceChannelUser(900L, encrypted, TIMESLOT));
            assertTrue(mEvents.isEmpty(), "Link control alone must not create an audio call");
            mAudioModule.receive(voice(1_000L));
            mAudioModule.receive(terminator(2_000L));

            assertEquals(CallEncryptionState.fromEncrypted(encrypted),
                completedEvents().getFirst().snapshot().encryptionState());
            assertEquals(1_000L, completedEvents().getFirst().snapshot().startTimestamp());
        }
    }

    @Test
    void lateClearLinkControlEnrichesTheExistingCallWithoutDuplicatingIt()
    {
        mAudioModule.receive(voice(1_000L));
        assertEquals(CallEncryptionState.UNKNOWN, mEvents.getLast().snapshot().encryptionState());
        mAudioModule.receive(voiceChannelUser(1_200L, false, TIMESLOT));
        assertEquals(CallEncryptionState.CLEAR, mEvents.getLast().snapshot().encryptionState());
        mAudioModule.receive(voiceChannelUser(1_400L, false, TIMESLOT));
        mAudioModule.receive(terminator(2_000L));

        assertEquals(1, completedEvents().size());
        assertEquals(CallEncryptionState.CLEAR, completedEvents().getFirst().snapshot().encryptionState());
        assertEquals(1_000L, completedEvents().getFirst().snapshot().startTimestamp());
    }

    @Test
    void contradictoryValidSignalingRemainsUnknownEvenAfterAnotherClearObservation()
    {
        mAudioModule.receive(clearVoiceHeader(1_000L));
        mAudioModule.receive(voiceChannelUser(1_200L, true, TIMESLOT));
        assertEquals(CallEncryptionState.UNKNOWN, mEvents.getLast().snapshot().encryptionState());
        mAudioModule.receive(voiceChannelUser(1_400L, false, TIMESLOT));
        mAudioModule.receive(voice(1_600L));
        mAudioModule.receive(terminator(2_000L));

        assertEquals(1, completedEvents().size());
        assertEquals(CallEncryptionState.UNKNOWN, completedEvents().getFirst().snapshot().encryptionState());
    }

    @Test
    void contradictoryLinkControlDoesNotSwitchTheEstablishedAudioDecision()
    {
        for(boolean encrypted: new boolean[]{false, true})
        {
            DMRAudioModule module = new DMRAudioModule(new UserPreferences(), AliasList.empty("conflict"), TIMESLOT)
            {
                @Override
                protected boolean hasAudioCodec()
                {
                    return true;
                }

                @Override
                public IAudioCodec getAudioCodec()
                {
                    return new TestAudioCodec();
                }
            };
            List<AudioCallEvent> events = new ArrayList<>();
            module.setAudioCallEventListener(events::add);

            try
            {
                module.receive(voiceHeader(1_000L, encrypted));
                module.receive(voiceChannelUser(1_200L, !encrypted, TIMESLOT));
                module.receive(voice(1_400L));
                module.receive(terminator(2_000L));

                assertEquals(!encrypted, events.stream().anyMatch(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME),
                    "Repeated contradictory LC must not change the established audio decoding decision");
                assertEquals(CallEncryptionState.UNKNOWN, events.stream()
                    .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).findFirst().orElseThrow()
                    .snapshot().encryptionState());
            }
            finally
            {
                module.dispose();
            }
        }
    }

    @Test
    void missingInvalidAndOtherSlotSignalingNeverAssertClear()
    {
        GroupVoiceChannelUser invalid = voiceChannelUser(900L, false, TIMESLOT);
        invalid.setValid(false);
        mAudioModule.receive(invalid);
        mAudioModule.receive(voiceChannelUser(950L, false, 2));
        mAudioModule.receive(voice(1_000L));
        mAudioModule.receive(terminator(2_000L));

        assertEquals(CallEncryptionState.UNKNOWN, completedEvents().getFirst().snapshot().encryptionState());
    }

    @Test
    void invalidLateLinkControlDoesNotExtendAnEstablishedCall()
    {
        mAudioModule.receive(clearVoiceHeader(1_000L));
        GroupVoiceChannelUser invalid = voiceChannelUser(5_000L, true, TIMESLOT);
        invalid.setValid(false);
        mAudioModule.receive(invalid);
        mAudioModule.receive(terminator(2_000L));

        assertEquals(CallEncryptionState.CLEAR, completedEvents().getFirst().snapshot().encryptionState());
        assertEquals(2_000L, completedEvents().getFirst().snapshot().lastActivityTimestamp());
    }

    @Test
    void directModePlaybackAssumptionDoesNotAssertClearWithoutDecodedAudio()
    {
        mAudioModule.receive(new VoiceAMessage(DMRSyncPattern.DIRECT_VOICE_TIMESLOT_1,
            new CorrectedBinaryMessage(288), null, 1_000L, TIMESLOT));
        mAudioModule.receive(terminator(2_000L));

        assertEquals(CallEncryptionState.UNKNOWN, completedEvents().getFirst().snapshot().encryptionState());
    }

    @Test
    void completedCallsDoNotLeakKnownEncryptionStateIntoTheNextCall()
    {
        mAudioModule.receive(clearVoiceHeader(1_000L));
        mAudioModule.receive(terminator(2_000L));
        mAudioModule.receive(voice(3_000L));
        mAudioModule.receive(terminator(4_000L));
        mAudioModule.receive(encryptedVoiceHeader(5_000L));
        mAudioModule.receive(terminator(6_000L));

        assertEquals(List.of(CallEncryptionState.CLEAR, CallEncryptionState.UNKNOWN, CallEncryptionState.ENCRYPTED),
            completedEvents().stream().map(event -> event.snapshot().encryptionState()).toList());
    }

    @Test
    void nonP25AudioCarriesCarrierTimestampWithoutDuplicateFingerprint()
    {
        IAudioCodec codec = new TestAudioCodec();
        DMRAudioModule module = new DMRAudioModule(new UserPreferences(), AliasList.empty("timestamp"), TIMESLOT)
        {
            @Override
            protected boolean hasAudioCodec()
            {
                return true;
            }

            @Override
            public IAudioCodec getAudioCodec()
            {
                return codec;
            }
        };
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);

        try
        {
            module.receive(clearVoiceHeader(1_000L));
            module.receive(voice(1_200L));
            AudioCallEvent audio = events.stream()
                .filter(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME).findFirst().orElseThrow();
            assertEquals(0L, audio.voiceFrameFingerprint());
            assertEquals(1_200L, audio.voiceFrameTimestamp());
        }
        finally
        {
            module.dispose();
        }
    }

    @Test
    void pendingPreEncryptionFramesDropOldestAtFixedBoundAndReset() throws ReflectiveOperationException
    {
        DMRAudioModule module = new DMRAudioModule(new UserPreferences(), AliasList.empty("bounded"), TIMESLOT)
        {
            @Override
            protected boolean hasAudioCodec()
            {
                return true;
            }
        };

        try
        {
            int voiceMessages = DMRAudioModule.MAX_PENDING_AMBE_FRAMES / 3 + 20;

            for(int index = 0; index < voiceMessages; index++)
            {
                module.receive(new VoiceAMessage(DMRSyncPattern.BASE_STATION_VOICE,
                    new CorrectedBinaryMessage(288), null, 1_000L + index, TIMESLOT));
            }

            assertEquals(DMRAudioModule.MAX_PENDING_AMBE_FRAMES, pendingFrameCount(module));
            module.receive(new Terminator(DMRSyncPattern.BASE_STATION_DATA,
                new CorrectedBinaryMessage(288), null, null, 2_000L, TIMESLOT, null));
            assertEquals(0, pendingFrameCount(module));
        }
        finally
        {
            module.dispose();
        }
    }

    @Test
    void newVoiceHeaderAfterPayloadClosesMissedTerminatorButRepeatedStartHeadersStayTogether()
    {
        mAudioModule.receive(encryptedVoiceHeader(1_000L));
        mAudioModule.receive(encryptedVoiceHeader(1_040L));
        mAudioModule.receive(voice(1_200L));

        //The prior terminator was lost.  This header starts the next transmission; its repeated copy must not create
        //another boundary before that transmission's first voice payload.
        mAudioModule.receive(encryptedVoiceHeader(2_000L));
        mAudioModule.receive(encryptedVoiceHeader(2_040L));
        mAudioModule.receive(voice(2_200L));
        mAudioModule.receive(terminator(3_000L));

        List<AudioCallEvent> completed = completedEvents();
        assertEquals(2, completed.size());
        assertEquals(1_000L, completed.getFirst().snapshot().startTimestamp());
        assertEquals(2_000L, completed.getFirst().snapshot().lastActivityTimestamp());
        assertEquals(2_000L, completed.getLast().snapshot().startTimestamp());
        assertEquals(3_000L, completed.getLast().snapshot().lastActivityTimestamp());
        assertNotEquals(completed.getFirst().snapshot().callLegId(), completed.getLast().snapshot().callLegId());
    }

    private static int pendingFrameCount(DMRAudioModule module) throws ReflectiveOperationException
    {
        Field field = DMRAudioModule.class.getDeclaredField("mQueuedAmbeFrames");
        field.setAccessible(true);
        return ((Collection<?>)field.get(module)).size();
    }

    private List<AudioCallEvent> completedEvents()
    {
        return mEvents.stream().filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).toList();
    }

    private static VoiceAMessage voice(long timestamp)
    {
        return new VoiceAMessage(DMRSyncPattern.BASE_STATION_VOICE,
            new CorrectedBinaryMessage(288), null, timestamp, TIMESLOT);
    }

    private static Terminator terminator(long timestamp)
    {
        return new Terminator(DMRSyncPattern.BASE_STATION_DATA,
            new CorrectedBinaryMessage(288), null, null, timestamp, TIMESLOT, null);
    }

    private static VoiceHeader encryptedVoiceHeader(long timestamp)
    {
        return voiceHeader(timestamp, true);
    }

    private static VoiceHeader clearVoiceHeader(long timestamp)
    {
        return voiceHeader(timestamp, false);
    }

    private static VoiceHeader voiceHeader(long timestamp, boolean encrypted)
    {
        return new VoiceHeader(DMRSyncPattern.BASE_STATION_DATA, new CorrectedBinaryMessage(288),
            null, null, timestamp, TIMESLOT, voiceChannelUser(timestamp, encrypted, TIMESLOT));
    }

    private static GroupVoiceChannelUser voiceChannelUser(long timestamp, boolean encrypted, int timeslot)
    {
        CorrectedBinaryMessage linkControlBits = new CorrectedBinaryMessage(72);

        if(encrypted)
        {
            linkControlBits.load(16, 8, 0x40); //Service-options encryption flag
        }

        linkControlBits.load(24, 24, 91);
        linkControlBits.load(48, 24, 1_234_567);
        return new GroupVoiceChannelUser(linkControlBits, timestamp, timeslot);
    }

    private static final class TestAudioCodec implements IAudioCodec
    {
        @Override
        public String getCodecName()
        {
            return "TEST AMBE";
        }

        @Override
        public float[] getAudio(byte[] frame)
        {
            return new float[160];
        }

        @Override
        public IAudioWithMetadata getAudioWithMetadata(byte[] frame)
        {
            return new IAudioWithMetadata()
            {
                @Override
                public float[] getAudio()
                {
                    return new float[160];
                }

                @Override
                public boolean hasMetadata()
                {
                    return false;
                }

                @Override
                public Map<String,String> getMetadata()
                {
                    return Map.of();
                }
            };
        }

        @Override
        public void reset()
        {
        }
    }
}
