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
        CorrectedBinaryMessage linkControlBits = new CorrectedBinaryMessage(72);

        if(encrypted)
        {
            linkControlBits.load(16, 8, 0x40); //Service-options encryption flag
        }

        linkControlBits.load(24, 24, 91);
        linkControlBits.load(48, 24, 1_234_567);
        GroupVoiceChannelUser linkControl = new GroupVoiceChannelUser(linkControlBits, timestamp, TIMESLOT);
        return new VoiceHeader(DMRSyncPattern.BASE_STATION_DATA, new CorrectedBinaryMessage(288),
            null, null, timestamp, TIMESLOT, linkControl);
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
