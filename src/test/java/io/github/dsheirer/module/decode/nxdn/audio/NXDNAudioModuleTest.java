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

package io.github.dsheirer.module.decode.nxdn.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.Audio;
import io.github.dsheirer.module.decode.nxdn.layer3.call.TransmissionRelease;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.CallInfo;
import io.github.dsheirer.module.decode.nxdn.layer3.type.AudioCodec;
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

class NXDNAudioModuleTest
{
    private NXDNAudioModule mAudioModule;
    private List<AudioCallEvent> mEvents;

    @BeforeEach
    void setUp()
    {
        mAudioModule = new NXDNAudioModule(new UserPreferences(), AliasList.empty("test"))
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
        mAudioModule.receive(encryptedVoiceCall(1_000L));
        mAudioModule.receive(transmissionRelease(2_000L));

        AudioCallEvent completed = mEvents.stream()
            .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).findFirst().orElseThrow();
        assertTrue(completed.snapshot().isEncrypted());
        assertEquals(1_000L, completed.snapshot().startTimestamp());
        assertEquals(2_000L, completed.snapshot().lastActivityTimestamp());
        assertFalse(mEvents.stream().anyMatch(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME));
    }

    @Test
    void nonP25AudioCarriesCarrierTimestampWithoutDuplicateFingerprint()
    {
        IAudioCodec codec = new TestAudioCodec();
        NXDNAudioModule module = new NXDNAudioModule(new UserPreferences(), AliasList.empty("timestamp"))
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
            module.receive(voiceCall(1_000L, 101, 202, LICH.RTCH_OUTBOUND_SINGLE_FACCH1_FACCH1));
            module.receive(audio(1_200L, LICH.RTCH_OUTBOUND_SUPER_VOICE_VOICE));
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
    void pendingPreEncryptionAudioDropsOldestAtFixedBoundAndReset() throws ReflectiveOperationException
    {
        NXDNAudioModule module = new NXDNAudioModule(new UserPreferences(), AliasList.empty("bounded"))
        {
            @Override
            protected boolean hasAudioCodec()
            {
                return true;
            }
        };

        try
        {
            for(int index = 0; index < NXDNAudioModule.MAX_PENDING_AUDIO_MESSAGES + 20; index++)
            {
                module.receive(new Audio(AudioCodec.HALF_RATE, List.of(new byte[9]), 1_000L + index, 3,
                    LICH.RTCH_OUTBOUND_SUPER_VOICE_VOICE));
            }

            assertEquals(NXDNAudioModule.MAX_PENDING_AUDIO_MESSAGES, pendingAudioCount(module));
            module.receive(transmissionRelease(2_000L));
            assertEquals(0, pendingAudioCount(module));
        }
        finally
        {
            module.dispose();
        }
    }

    @Test
    void initialFacchCopiesCoalescePeriodicVoiceCallDoesNotSplitAndNextInitialClosesMissedRelease()
    {
        LICH initial = LICH.RTCH_OUTBOUND_SINGLE_FACCH1_FACCH1;
        LICH periodic = LICH.RTCH_OUTBOUND_SUPER_VOICE_VOICE;

        mAudioModule.receive(voiceCall(1_000L, 101, 202, initial));
        mAudioModule.receive(voiceCall(1_000L, 101, 202, initial));
        mAudioModule.receive(audio(1_200L, periodic));
        mAudioModule.receive(voiceCall(1_400L, 101, 202, periodic));

        //The prior release was lost.  The next non-superframe FACCH1 header is an explicit new transmission even
        //though the same radio and destination are reused.
        mAudioModule.receive(voiceCall(2_000L, 101, 202, initial));
        mAudioModule.receive(voiceCall(2_000L, 101, 202, initial));
        mAudioModule.receive(audio(2_200L, periodic));
        mAudioModule.receive(transmissionRelease(3_000L));

        List<AudioCallEvent> completed = completedEvents();
        assertEquals(2, completed.size());
        assertEquals(1_000L, completed.getFirst().snapshot().startTimestamp());
        assertEquals(2_000L, completed.getFirst().snapshot().lastActivityTimestamp());
        assertEquals(2_000L, completed.getLast().snapshot().startTimestamp());
        assertEquals(3_000L, completed.getLast().snapshot().lastActivityTimestamp());
        assertNotEquals(completed.getFirst().snapshot().callLegId(), completed.getLast().snapshot().callLegId());
    }

    @Test
    void periodicTypeDCallInfoDoesNotSplitCall()
    {
        mAudioModule.receive(voiceCall(1_000L, 101, 202,
            LICH.RTCH_2_OUTBOUND_SINGLE_FACCH1_FACCH1, NXDNMessageType.TYPE_D_OUT_01_CC_VOICE_CALL));
        mAudioModule.receive(callInfo(1_200L));
        mAudioModule.receive(callInfo(1_400L));
        mAudioModule.receive(transmissionRelease(2_000L));

        List<AudioCallEvent> completed = completedEvents();
        assertEquals(1, completed.size());
        assertEquals(1_000L, completed.getFirst().snapshot().startTimestamp());
        assertEquals(2_000L, completed.getFirst().snapshot().lastActivityTimestamp());
    }

    @Test
    void changedVoiceCallIdentityClosesOldCallWhenInitialFrameWasMissed()
    {
        LICH periodic = LICH.RTCH_OUTBOUND_SUPER_VOICE_VOICE;
        mAudioModule.receive(voiceCall(1_000L, 101, 202, periodic));
        mAudioModule.receive(audio(1_200L, periodic));
        mAudioModule.receive(voiceCall(2_000L, 303, 404, periodic));
        mAudioModule.receive(transmissionRelease(3_000L));

        List<AudioCallEvent> completed = completedEvents();
        assertEquals(2, completed.size());
        assertEquals(2_000L, completed.getFirst().snapshot().lastActivityTimestamp());
        assertEquals(2_000L, completed.getLast().snapshot().startTimestamp());
        assertNotEquals(completed.getFirst().snapshot().callLegId(), completed.getLast().snapshot().callLegId());
    }

    private static int pendingAudioCount(NXDNAudioModule module) throws ReflectiveOperationException
    {
        Field field = NXDNAudioModule.class.getDeclaredField("mCachedAudioMessages");
        field.setAccessible(true);
        return ((Collection<?>)field.get(module)).size();
    }

    private List<AudioCallEvent> completedEvents()
    {
        return mEvents.stream().filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).toList();
    }

    private static Audio audio(long timestamp, LICH lich)
    {
        return new Audio(AudioCodec.HALF_RATE, List.of(new byte[9]), timestamp, 3, lich);
    }

    private static VoiceCall voiceCall(long timestamp, int source, int destination, LICH lich)
    {
        return voiceCall(timestamp, source, destination, lich, NXDNMessageType.TRAFFIC_OUT_01_CC_VOICE_CALL);
    }

    private static VoiceCall voiceCall(long timestamp, int source, int destination, LICH lich, NXDNMessageType type)
    {
        return new VoiceCall(callBits(source, destination), timestamp, type, 3, lich);
    }

    private static CallInfo callInfo(long timestamp)
    {
        return new CallInfo(new CorrectedBinaryMessage(32), timestamp,
            NXDNMessageType.TYPE_D_SCCH_OUT_INFO_1_CALL_INFO, 3,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE);
    }

    private static VoiceCall encryptedVoiceCall(long timestamp)
    {
        CorrectedBinaryMessage message = callBits();
        message.load(56, 2, 1);
        message.load(58, 6, 7);
        return new VoiceCall(message, timestamp, NXDNMessageType.TRAFFIC_OUT_01_CC_VOICE_CALL, 3,
            LICH.RTCH_OUTBOUND_SUPER_FACCH1_FACCH1);
    }

    private static TransmissionRelease transmissionRelease(long timestamp)
    {
        return new TransmissionRelease(callBits(), timestamp,
            NXDNMessageType.TRAFFIC_OUT_08_CC_TRANSMISSION_RELEASE, 3,
            LICH.RTCH_OUTBOUND_SUPER_FACCH1_FACCH1);
    }

    private static CorrectedBinaryMessage callBits()
    {
        return callBits(101, 202);
    }

    private static CorrectedBinaryMessage callBits(int source, int destination)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(176);
        message.load(24, 16, source);
        message.load(40, 16, destination);
        return message;
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
