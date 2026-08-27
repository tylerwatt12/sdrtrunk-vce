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

package io.github.dsheirer.module.decode.p25.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.CallEncryptionEvidence;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HeaderData;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
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

class P25P1AudioModuleTest
{
    private static final IntField ALGORITHM_ID = IntField.length8(80);
    private static final IntField KEY_ID = IntField.length16(88);
    private static final IntField TALKGROUP_ID = IntField.length16(104);
    private P25P1AudioModule mAudioModule;
    private List<AudioCallEvent> mEvents;

    @BeforeEach
    void setUp()
    {
        mAudioModule = new P25P1AudioModule(new UserPreferences(), AliasList.empty("test"))
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
    void encryptedHeaderCompletesMetadataOnlyCallWithoutCodec()
    {
        setDecoderState(DecoderStateEvent.Event.START, State.ENCRYPTED);
        mAudioModule.receive(encryptedHdu(1_000L, 0x84, 0xBEEF, 56_132, 1));
        setDecoderState(DecoderStateEvent.Event.END, State.IDLE);

        AudioCallEvent completed = completedEvents().getFirst();
        CallEncryptionEvidence evidence = completed.snapshot().callEncryptionEvidence();
        assertEquals(CallEncryptionState.ENCRYPTED, completed.snapshot().encryptionState());
        assertEquals(1_000L, completed.snapshot().startTimestamp());
        assertEquals(1_000L, completed.snapshot().lastActivityTimestamp());
        assertEquals(0x84, evidence.algorithmId());
        assertEquals(0xBEEF, evidence.keyId());
        assertTrue(evidence.hasMessageIndicator());
        assertFalse(mEvents.stream().anyMatch(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME));
    }

    @Test
    void clearHeaderEstablishesClearStateWithoutDecodedAudio()
    {
        setDecoderState(DecoderStateEvent.Event.START, State.CALL);
        mAudioModule.receive(encryptedHdu(1_000L, 0x80, 0, 56_132, 1));
        setDecoderState(DecoderStateEvent.Event.END, State.IDLE);

        AudioCallEvent completed = completedEvents().getFirst();
        assertEquals(CallEncryptionState.CLEAR, completed.snapshot().encryptionState());
        assertFalse(mEvents.stream().anyMatch(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME));
    }

    @Test
    void newHeaderCompletesOldCallWhenTerminatorWasMissed()
    {
        setDecoderState(DecoderStateEvent.Event.START, State.ENCRYPTED);
        mAudioModule.receive(encryptedHdu(1_000L, 0x84, 0x1001, 56_132, 1));
        mAudioModule.receive(encryptedHdu(2_000L, 0x84, 0x2002, 56_132, 2));
        setDecoderState(DecoderStateEvent.Event.END, State.IDLE);

        List<AudioCallEvent> completed = completedEvents();
        assertEquals(2, completed.size());
        assertEquals(2_000L, completed.getFirst().snapshot().lastActivityTimestamp());
        assertEquals(2_000L, completed.getLast().snapshot().startTimestamp());
        assertEquals(0x1001, completed.getFirst().snapshot().callEncryptionEvidence().keyId());
        assertEquals(0x2002, completed.getLast().snapshot().callEncryptionEvidence().keyId());
        assertNotEquals(completed.getFirst().callId(), completed.getLast().callId());
        assertNotEquals(completed.getFirst().snapshot().callLegId(), completed.getLast().snapshot().callLegId());
    }

    @Test
    void malformedEncryptedHeaderStillCompletesAsEncryptedWithoutEvidence()
    {
        setDecoderState(DecoderStateEvent.Event.START, State.ENCRYPTED);
        mAudioModule.receive(malformedEncryptedHdu(1_000L));
        setDecoderState(DecoderStateEvent.Event.END, State.IDLE);

        AudioCallEvent completed = completedEvents().getFirst();
        assertEquals(CallEncryptionState.ENCRYPTED, completed.snapshot().encryptionState());
        assertNull(completed.snapshot().callEncryptionEvidence());
        assertEquals(1_000L, completed.snapshot().startTimestamp());
        assertFalse(mEvents.stream().anyMatch(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME));
    }

    @Test
    void pendingLateEntryLdusDropOldestAtFixedBoundAndReset() throws ReflectiveOperationException
    {
        for(int index = 0; index < P25P1AudioModule.MAX_PENDING_ENCRYPTION_LDUS + 20; index++)
        {
            mAudioModule.receive(new LDU1Message(new CorrectedBinaryMessage(1_568), 0x123,
                1_000L + index));
        }

        assertEquals(P25P1AudioModule.MAX_PENDING_ENCRYPTION_LDUS, pendingLduCount());
        setDecoderState(DecoderStateEvent.Event.END, State.IDLE);
        assertEquals(0, pendingLduCount());
    }

    @Test
    void publishesDistinctTwentyMillisecondCarrierTimesForImbeFrames()
    {
        IAudioCodec codec = new TestAudioCodec();
        P25P1AudioModule module = new P25P1AudioModule(new UserPreferences(), AliasList.empty("timing"))
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
            module.getDecoderStateListener().receive(new DecoderStateEvent(this,
                DecoderStateEvent.Event.START, State.CALL));
            module.receive(encryptedHdu(1_000L, 0x80, 0, 56_132, 1));
            module.receive(new LDU1Message(new CorrectedBinaryMessage(1_568), 0x123, 1_180L));

            List<Long> timestamps = events.stream()
                .filter(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME)
                .map(AudioCallEvent::voiceFrameTimestamp).toList();
            assertEquals(9, timestamps.size());
            assertEquals(1_020L, timestamps.getFirst());
            assertEquals(1_180L, timestamps.getLast());

            for(int index = 1; index < timestamps.size(); index++)
            {
                assertEquals(20L, timestamps.get(index) - timestamps.get(index - 1));
            }
        }
        finally
        {
            module.dispose();
        }
    }

    private void setDecoderState(DecoderStateEvent.Event event, State state)
    {
        mAudioModule.getDecoderStateListener().receive(new DecoderStateEvent(this, event, state));
    }

    private List<AudioCallEvent> completedEvents()
    {
        return mEvents.stream().filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).toList();
    }

    private int pendingLduCount() throws ReflectiveOperationException
    {
        Field field = P25P1AudioModule.class.getDeclaredField("mPendingEncryptionLdus");
        field.setAccessible(true);
        return ((Collection<?>)field.get(mAudioModule)).size();
    }

    private static HDUMessage encryptedHdu(long timestamp, int algorithmId, int keyId, int talkgroup, int miSeed)
    {
        BinaryMessage headerBits = new BinaryMessage(120);
        headerBits.set(miSeed % 72);
        headerBits.set((miSeed * 17) % 72);
        headerBits.setInt(algorithmId, ALGORITHM_ID);
        headerBits.setInt(keyId, KEY_ID);
        headerBits.setInt(talkgroup, TALKGROUP_ID);
        HeaderData headerData = new HeaderData(headerBits);

        return new HDUMessage(new CorrectedBinaryMessage(648), 0x123, timestamp)
        {
            @Override
            public HeaderData getHeaderData()
            {
                return headerData;
            }
        };
    }

    private static HDUMessage malformedEncryptedHdu(long timestamp)
    {
        HeaderData headerData = new HeaderData(new BinaryMessage(120))
        {
            @Override
            public boolean isEncryptedAudio()
            {
                return true;
            }

            @Override
            public EncryptionKeyIdentifier getEncryptionKey()
            {
                return null;
            }

            @Override
            public String getMessageIndicator()
            {
                return "malformed";
            }
        };

        return new HDUMessage(new CorrectedBinaryMessage(648), 0x123, timestamp)
        {
            @Override
            public HeaderData getHeaderData()
            {
                return headerData;
            }
        };
    }

    private static final class TestAudioCodec implements IAudioCodec
    {
        @Override
        public String getCodecName()
        {
            return "TEST IMBE";
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
