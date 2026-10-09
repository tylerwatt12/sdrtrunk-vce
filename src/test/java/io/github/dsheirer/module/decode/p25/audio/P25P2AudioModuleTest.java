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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.CallEncryptionEvidence;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.codec.mbe.IEncryptionSyncParameters;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.P25P2DecoderState;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase2.message.EncryptionSynchronizationSequence;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.EndPushToTalk;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.NullInformation;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.PushToTalk;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.Voice2Timeslot;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.record.wave.AudioMetadata;
import io.github.dsheirer.record.wave.AudioMetadataUtils;
import io.github.dsheirer.source.config.SourceConfigRemote;
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

class P25P2AudioModuleTest
{
    private static final int TIMESLOT = 0;
    private static final long FREQUENCY = 851_012_500L;
    private static final IntField NAC = IntField.length12(12);
    private static final IntField SOURCE = IntField.length24(104);
    private static final IntField GROUP = IntField.length16(128);
    private P25P2AudioModule mAudioModule;
    private List<AudioCallEvent> mEvents;

    @BeforeEach
    void setUp()
    {
        mAudioModule = new P25P2AudioModule(new UserPreferences(), TIMESLOT, AliasList.empty("test"));
        mEvents = new ArrayList<>();
        mAudioModule.setAudioCallEventListener(mEvents::add);
    }

    @AfterEach
    void tearDown()
    {
        mAudioModule.dispose();
    }

    @Test
    void endPushToTalkCompletesEachBurstAsASeparateCall()
    {
        updateSource(1001);
        mAudioModule.receive(pushToTalk(1_000L));
        mAudioModule.receive(endPushToTalk(2_000L));

        updateSource(2002);
        mAudioModule.receive(pushToTalk(3_000L));
        mAudioModule.receive(endPushToTalk(4_000L));

        List<AudioCallEvent> completed = completedEvents();
        assertEquals(2, completed.size());
        assertNotEquals(completed.get(0).callId(), completed.get(1).callId());
        assertNotEquals(completed.get(0).snapshot().callLegId(), completed.get(1).snapshot().callLegId());
        assertEquals(1_000L, completed.get(0).snapshot().startTimestamp());
        assertEquals(2_000L, completed.get(0).snapshot().lastActivityTimestamp());
        assertEquals(1_000L, completed.get(0).snapshot().lastBurstStartTimestamp());
        assertEquals(2_000L, completed.get(0).snapshot().lastBurstEndTimestamp());
        assertEquals(CallEncryptionState.CLEAR, completed.get(0).snapshot().encryptionState());
        assertEquals(CallEncryptionState.CLEAR, completed.get(1).snapshot().encryptionState());
        assertEquals("1001", completed.get(0).snapshot().recordingMetadata().sourceValue());
        assertEquals("2002", completed.get(1).snapshot().recordingMetadata().sourceValue());
    }

    @Test
    void endPushToTalkRecoversIdentifiersBeforeAudioCompletes()
    {
        int source = 1_880_997;
        int group = 56_132;
        int nac = 0x123;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Parent"));
        Channel traffic = new Channel("Traffic", ChannelType.TRAFFIC);
        traffic.setDecodeConfiguration(new DecodeConfigP25Phase2());
        P25P2DecoderState decoderState = new P25P2DecoderState(traffic, TIMESLOT, manager,
            new PatchGroupManager());
        decoderState.setCurrentFrequency(FREQUENCY);
        decoderState.setIdentifierUpdateListener(mAudioModule.getIdentifierUpdateListener());
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(group));
        manager.processP2TrafficCurrentUser(FREQUENCY, TIMESLOT, new StandardChannel(FREQUENCY),
            VoiceServiceOptions.createUnencrypted(), MacOpcode.PUSH_TO_TALK, identifiers, 1_000L, null);
        manager.processP2TrafficVoice(FREQUENCY, TIMESLOT, 1_100L);
        mAudioModule.receive(pushToTalk(1_000L));
        MacMessage endPushToTalk = endPushToTalk(2_000L, nac, source, group);

        decoderState.receive(endPushToTalk);
        mAudioModule.receive(endPushToTalk);

        AudioCallEvent completed = completedEvents().getFirst();
        assertEquals(Integer.toString(source), completed.snapshot().recordingMetadata().sourceValue());
        assertEquals(Integer.toString(group), completed.snapshot().recordingMetadata().destinationValue());
        assertTrue(completed.snapshot().identifierCollection().hasIdentifier(APCO25Nac.create(nac)));
    }

    @Test
    void encryptedPushToTalkDisplaysHexKeyIdInDecodeEvent()
    {
        int source = 1_880_997;
        int group = 56_132;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Parent"));
        List<IDecodeEvent> events = new ArrayList<>();
        manager.addDecodeEventListener(events::add);
        Channel traffic = new Channel("Traffic", ChannelType.TRAFFIC);
        traffic.setDecodeConfiguration(new DecodeConfigP25Phase2());
        P25P2DecoderState decoderState = new P25P2DecoderState(traffic, TIMESLOT, manager,
            new PatchGroupManager());
        decoderState.setCurrentFrequency(FREQUENCY);
        MacMessage message = encryptedPushToTalk(1_000L, 0x84, 0xBEEF, source, group);

        decoderState.receive(message);

        assertEquals("AES256 K:BEEF", events.getLast().getDetails());
        PushToTalk pushToTalk = (PushToTalk)message.getMacStructure();
        assertEquals("ENCRYPTION:AES-256 KEY:48879", pushToTalk.getEncryptionKey().toString());
    }

    @Test
    void undecryptableEncryptedPushToTalkCompletesMetadataOnlyCall()
    {
        mAudioModule.receive(encryptedPushToTalk(1_000L, 0x84, 0xBEEF, 1_880_997, 56_132));
        mAudioModule.receive(endPushToTalk(2_000L));

        AudioCallEvent completed = completedEvents().getFirst();
        CallEncryptionEvidence evidence = completed.snapshot().callEncryptionEvidence();
        assertEquals(CallEncryptionState.ENCRYPTED, completed.snapshot().encryptionState());
        assertEquals(1_000L, completed.snapshot().startTimestamp());
        assertEquals(2_000L, completed.snapshot().lastActivityTimestamp());
        assertEquals(0x84, evidence.algorithmId());
        assertEquals(0xBEEF, evidence.keyId());
        assertTrue(evidence.hasMessageIndicator());
        assertFalse(mEvents.stream().anyMatch(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME));
    }

    @Test
    void malformedEncryptedPushToTalkStillCompletesAsEncryptedWithoutEvidence()
    {
        mAudioModule.receive(malformedEncryptedPushToTalk(1_000L));
        mAudioModule.receive(endPushToTalk(2_000L));

        AudioCallEvent completed = completedEvents().getFirst();
        assertEquals(CallEncryptionState.ENCRYPTED, completed.snapshot().encryptionState());
        assertNull(completed.snapshot().callEncryptionEvidence());
        assertEquals(1_000L, completed.snapshot().startTimestamp());
        assertEquals(2_000L, completed.snapshot().lastActivityTimestamp());
        assertFalse(mEvents.stream().anyMatch(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME));
    }

    @Test
    void newPushToTalkCompletesOldCallWhenTerminatorWasMissed()
    {
        mAudioModule.receive(pushToTalk(1_000L));
        mAudioModule.receive(pushToTalk(2_000L));
        mAudioModule.receive(endPushToTalk(3_000L));

        List<AudioCallEvent> completed = completedEvents();
        assertEquals(2, completed.size());
        assertEquals(2_000L, completed.getFirst().snapshot().lastActivityTimestamp());
        assertEquals(2_000L, completed.getLast().snapshot().startTimestamp());
        assertNotEquals(completed.getFirst().callId(), completed.getLast().callId());
        assertNotEquals(completed.getFirst().snapshot().callLegId(), completed.getLast().snapshot().callLegId());
    }

    @Test
    void repeatedEndPushToTalkDoesNotCompleteAnotherCall()
    {
        mAudioModule.receive(pushToTalk(1_000L));
        mAudioModule.receive(endPushToTalk(2_000L));
        mAudioModule.receive(endPushToTalk(3_000L));

        assertEquals(1, completedEvents().size());
    }

    @Test
    void hangtimeCompletesCallWhenEndPushToTalkIsMissing()
    {
        mAudioModule.receive(pushToTalk(1_000L));
        mAudioModule.receive(hangtime(2_000L));

        assertEquals(1, completedEvents().size());
    }

    @Test
    void callBoundariesClearPendingLateEntryAudio() throws ReflectiveOperationException
    {
        mAudioModule.receive(voiceTimeslot(1_000L));
        assertEquals(1, pendingVoiceTimeslotCount());
        mAudioModule.receive(endPushToTalk(2_000L));
        assertEquals(0, pendingVoiceTimeslotCount());

        mAudioModule.receive(voiceTimeslot(3_000L));
        assertEquals(1, pendingVoiceTimeslotCount());
        mAudioModule.receive(hangtime(4_000L));
        assertEquals(0, pendingVoiceTimeslotCount());
    }

    @Test
    void pendingLateEntryTimeslotsDropOldestAtFixedBound() throws ReflectiveOperationException
    {
        for(int index = 0; index < P25P2AudioModule.MAX_PENDING_VOICE_TIMESLOTS + 20; index++)
        {
            mAudioModule.receive(voiceTimeslot(1_000L + index));
        }

        assertEquals(P25P2AudioModule.MAX_PENDING_VOICE_TIMESLOTS, pendingVoiceTimeslotCount());
        mAudioModule.receive(endPushToTalk(2_000L));
        assertEquals(0, pendingVoiceTimeslotCount());
    }

    @Test
    void publishesDistinctTwentyMillisecondCarrierTimesForAmbeFrames()
    {
        IAudioCodec codec = new TestAudioCodec();
        P25P2AudioModule module = new P25P2AudioModule(new UserPreferences(), TIMESLOT,
            AliasList.empty("timing"))
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
            module.receive(pushToTalk(1_000L));
            module.receive(voiceTimeslot(1_060L));
            List<Long> timestamps = events.stream()
                .filter(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME)
                .map(AudioCallEvent::voiceFrameTimestamp).toList();
            assertEquals(List.of(1_040L, 1_060L), timestamps);
        }
        finally
        {
            module.dispose();
        }
    }

    @Test
    void lateControlRadioReachesShortRecordingBeforeZeroSourceEndPushToTalk()
    {
        for(boolean phaseOneControl: List.of(false, true))
        {
            for(int timeslot: List.of(1, 2))
            {
                try(LateSourceCall call = new LateSourceCall(timeslot, phaseOneControl, 1_000L))
                {
                    call.startPushToTalk(1_100L);
                    call.voice(1_160L);
                    call.grant(APCO25RadioIdentifier.createFrom(700_001), 1_200L);
                    call.endPushToTalk(1_300L);

                    CompletedAudioCall completed = call.completedCalls().getFirst();
                    assertTrue(completed.hasAudio());
                    assertTrue(completed.getDuration() > 0 && completed.getDuration() < 1_000L);
                    assertTrue(completed.snapshot().recordAudio());
                    assertEquals("700001", completed.snapshot().recordingMetadata().sourceValue());
                    assertEquals("700001", AudioMetadataUtils.getMetadataMap(
                        completed.snapshot().identifierCollection(), completed.snapshot().aliasList())
                        .get(AudioMetadata.ARTIST_NAME));
                    assertNull(call.audio.getIdentifierCollection().getFromIdentifier(),
                        "recording recovery must not seed the next caller's cached source");
                }
            }
        }
    }

    @Test
    void lateControlRadioReachesRecordingWhenHangtimeEndsBurst()
    {
        try(LateSourceCall call = new LateSourceCall(1, false, 1_000L))
        {
            call.startPushToTalk(1_100L);
            call.voice(1_160L);
            call.grant(APCO25RadioIdentifier.createFrom(700_001), 1_200L);
            call.hangtime(1_300L);

            assertEquals("700001", call.completedCalls().getFirst().snapshot().recordingMetadata().sourceValue());
            assertNull(call.audio.getIdentifierCollection().getFromIdentifier());
        }
    }

    @Test
    void lateEntryVoiceQueuedBeforeEssKeepsOriginalCallBinding()
    {
        try(LateSourceCall call = new LateSourceCall(2, true, 1_000L))
        {
            call.voice(1_100L);
            assertFalse(call.events.stream().anyMatch(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME));
            call.clearEss(1_160L);
            call.grant(APCO25RadioIdentifier.createFrom(700_001), 1_200L);
            call.endPushToTalk(1_300L);

            CompletedAudioCall completed = call.completedCalls().getFirst();
            assertTrue(completed.hasAudio());
            assertEquals("700001", completed.snapshot().recordingMetadata().sourceValue());
        }
    }

    @Test
    void replacementTalkerCannotSupplyIdToPreviousRecording()
    {
        try(LateSourceCall call = new LateSourceCall(1, false, 1_000L))
        {
            call.startPushToTalk(1_100L);
            call.voice(1_160L);
            call.grant(APCO25RadioIdentifier.createFrom(700_001), 1_200L);
            call.grant(APCO25RadioIdentifier.createFrom(700_002), 1_250L);
            call.endPushToTalk(1_300L);
            assertNull(call.completedCalls().getFirst().snapshot().recordingMetadata().sourceValue());

            call.startPushToTalk(1_400L);
            call.voice(1_460L);
            call.endPushToTalk(1_500L);
            assertEquals("700002", call.completedCalls().getLast().snapshot().recordingMetadata().sourceValue());
            assertNull(call.audio.getIdentifierCollection().getFromIdentifier());
        }
    }

    @Test
    void recoveredRadioDoesNotLeakIntoNextSourceLessBurst()
    {
        try(LateSourceCall call = new LateSourceCall(1, false, 1_000L))
        {
            call.startPushToTalk(1_100L);
            call.voice(1_160L);
            call.grant(APCO25RadioIdentifier.createFrom(700_001), 1_200L);
            call.endPushToTalk(1_300L);
            assertEquals("700001", call.completedCalls().getFirst().snapshot().recordingMetadata().sourceValue());

            call.grant(null, 1_400L);
            call.startPushToTalk(1_500L);
            call.voice(1_560L);
            call.endPushToTalk(1_600L);
            assertNull(call.completedCalls().getLast().snapshot().recordingMetadata().sourceValue());
        }
    }

    @Test
    void retuneCannotBorrowSourceFromOriginalFrequency()
    {
        try(LateSourceCall call = new LateSourceCall(1, false, 1_000L))
        {
            call.startPushToTalk(1_100L);
            call.voice(1_160L);
            call.grant(APCO25RadioIdentifier.createFrom(700_001), 1_200L);
            call.frequency(FREQUENCY + 12_500L);
            call.hangtime(1_300L);

            assertNull(call.completedCalls().getFirst().snapshot().recordingMetadata().sourceValue());
        }
    }

    @Test
    void recoveredRecordingRetainsQualifiedRadioIdentity()
    {
        try(LateSourceCall call = new LateSourceCall(2, false, 1_000L))
        {
            RadioIdentifier source = APCO25FullyQualifiedRadioIdentifier.createFrom(700_001, 0xABCDE, 0x123, 900_001);
            call.startPushToTalk(1_100L);
            call.voice(1_160L);
            call.grant(source, 1_200L);
            call.endPushToTalk(1_300L);

            CompletedAudioCall completed = call.completedCalls().getFirst();
            assertEquals(source, completed.snapshot().identifierCollection().getFromIdentifier());
            assertEquals(900_001, completed.snapshot().recordingMetadata().sourceP25Identity().subscriberId());
        }
    }

    @Test
    void trackedRadioCannotReplaceEstablishedAudioSource()
    {
        try(LateSourceCall call = new LateSourceCall(1, false, 1_000L))
        {
            call.audio.getIdentifierUpdateListener().receive(new IdentifierUpdateNotification(
                APCO25RadioIdentifier.createFrom(700_009), IdentifierUpdateNotification.Operation.ADD, 1));
            call.startPushToTalk(1_100L);
            call.voice(1_160L);
            call.grant(APCO25RadioIdentifier.createFrom(700_001), 1_200L);
            call.endPushToTalk(1_300L);

            assertEquals("700009", call.completedCalls().getFirst().snapshot().recordingMetadata().sourceValue());
        }
    }

    @Test
    void squelchClosureRecoversRecentTrackedRadioBeforeReset()
    {
        long start = System.currentTimeMillis() - 500L;
        try(LateSourceCall call = new LateSourceCall(1, false, start))
        {
            call.startPushToTalk(start + 100L);
            call.voice(start + 160L);
            call.grant(APCO25RadioIdentifier.createFrom(700_001), start + 200L);
            call.audio.getSquelchStateListener().receive(new SquelchStateEvent(SquelchState.SQUELCH, 1));

            assertEquals("700001", call.completedCalls().getFirst().snapshot().recordingMetadata().sourceValue());
            assertNull(call.audio.getIdentifierCollection().getFromIdentifier());
        }
    }

    @Test
    void automaticSegmentSplitRetainsLateRadioBindingWithoutCachingSource() throws ReflectiveOperationException
    {
        try(LateSourceCall call = new LateSourceCall(1, false, 1_000L))
        {
            call.startPushToTalk(1_100L);
            call.voice(1_160L);
            call.grant(APCO25RadioIdentifier.createFrom(700_001), 1_200L);
            Field maximum = AbstractAudioModule.class.getDeclaredField("mMaxSegmentAudioSampleLength");
            Field count = AbstractAudioModule.class.getDeclaredField("mAudioSampleCount");
            maximum.setAccessible(true);
            count.setAccessible(true);
            count.setInt(call.audio, maximum.getInt(call.audio));

            call.voice(1_260L);
            assertEquals(1, call.completedCalls().size());
            assertNull(call.audio.getIdentifierCollection().getFromIdentifier());
            call.endPushToTalk(1_300L);

            List<CompletedAudioCall> completed = call.completedCalls();
            assertEquals(2, completed.size());
            assertTrue(completed.stream().allMatch(CompletedAudioCall::hasAudio));
            assertEquals(List.of("700001", "700001"), completed.stream()
                .map(segment -> segment.snapshot().recordingMetadata().sourceValue()).toList());
            assertNotEquals(completed.getFirst().snapshot().callId(), completed.getLast().snapshot().callId());
            assertEquals(completed.getFirst().snapshot().callId(), completed.getLast().snapshot().linkedCallId());
            assertEquals(completed.getFirst().snapshot().callLegId(), completed.getLast().snapshot().callLegId());
            assertNull(call.audio.getIdentifierCollection().getFromIdentifier());
        }
    }

    @Test
    void recoveredRadioUsesItsAliasRecordingAndStreamingPolicy()
    {
        Alias source = new Alias("Late source radio");
        source.setMatchIdentifier(new Radio(Protocol.APCO25, 700_001));
        source.setRecordable(true);
        BroadcastChannel stream = new BroadcastChannel("00000000-0000-0000-0000-000000000701", "Test stream");
        source.addBroadcastChannel(stream);
        AliasList aliases = new AliasList(new AliasListDefinition("Source policies", AliasListFamily.P25));
        aliases.addAlias(source);

        try(LateSourceCall call = new LateSourceCall(1, false, 1_000L, aliases, false))
        {
            call.startPushToTalk(1_100L);
            call.voice(1_160L);
            assertFalse(call.events.getLast().snapshot().recordAudio());
            assertTrue(call.events.getLast().snapshot().broadcastChannels().isEmpty());
            call.grant(APCO25RadioIdentifier.createFrom(700_001), 1_200L);
            call.endPushToTalk(1_300L);

            CompletedAudioCall completed = call.completedCalls().getFirst();
            assertEquals("Late source radio", completed.snapshot().recordingMetadata().sourceAlias());
            assertTrue(completed.snapshot().recordAudio());
            assertTrue(completed.snapshot().broadcastChannels().contains(stream));
            assertNull(call.audio.getIdentifierCollection().getFromIdentifier());
        }
    }

    private static final class LateSourceCall implements AutoCloseable
    {
        private static final int TALKGROUP = 56_132;
        private final int timeslot;
        private final boolean phaseOneControl;
        private final P25TrafficChannelManager manager;
        private final APCO25Channel descriptor;
        private final P25P2DecoderState state;
        private final P25P2AudioModule audio;
        private final List<AudioCallEvent> events = new ArrayList<>();

        private LateSourceCall(int timeslot, boolean phaseOneControl, long start)
        {
            this(timeslot, phaseOneControl, start, AliasList.empty("late source"), true);
        }

        private LateSourceCall(int timeslot, boolean phaseOneControl, long start, AliasList aliases, boolean recordAll)
        {
            this.timeslot = timeslot;
            this.phaseOneControl = phaseOneControl;
            Channel parent = new Channel("Control", ChannelType.STANDARD);
            parent.setDecodeConfiguration(phaseOneControl ? new DecodeConfigP25Phase1() : new DecodeConfigP25Phase2());
            parent.setSourceConfiguration(new SourceConfigRemote());
            manager = new P25TrafficChannelManager(parent);
            P25FrequencyBand band = new P25FrequencyBand(0, FREQUENCY, -45_000_000L, 12_500L, 12_500, 2);
            manager.processFrequencyBand(band);
            manager.processFrequencyBand(band);
            descriptor = APCO25Channel.create(0, timeslot - 1);
            descriptor.setFrequencyBand(band);
            IAudioCodec codec = new TestAudioCodec();
            audio = new P25P2AudioModule(new UserPreferences(), timeslot, aliases,
                CallLegSource.UNKNOWN, manager)
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
            audio.setRecordAudio(recordAll);
            audio.setAudioCallEventListener(events::add);
            Channel traffic = new Channel("Traffic", ChannelType.TRAFFIC);
            traffic.setDecodeConfiguration(new DecodeConfigP25Phase2());
            state = new P25P2DecoderState(traffic, timeslot, manager, new PatchGroupManager());
            state.setCurrentChannel(descriptor);
            state.setIdentifierUpdateListener(audio.getIdentifierUpdateListener());
            frequency(FREQUENCY);
            audio.getIdentifierUpdateListener().receive(new IdentifierUpdateNotification(
                APCO25Talkgroup.create(TALKGROUP), IdentifierUpdateNotification.Operation.ADD, timeslot));
            grant(null, start);
        }

        private void frequency(long frequency)
        {
            state.setCurrentFrequency(frequency);
            audio.getIdentifierUpdateListener().receive(new IdentifierUpdateNotification(
                FrequencyConfigurationIdentifier.create(frequency), IdentifierUpdateNotification.Operation.ADD, timeslot));
        }

        private void grant(RadioIdentifier source, long timestamp)
        {
            MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
            identifiers.update(APCO25Talkgroup.create(TALKGROUP));
            identifiers.update(source);
            if(phaseOneControl)
            {
                manager.processP1ControlDirectedChannelGrant(descriptor, VoiceServiceOptions.createUnencrypted(),
                    identifiers, Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, timestamp);
            }
            else
            {
                manager.processP2ChannelGrant(descriptor, VoiceServiceOptions.createUnencrypted(), identifiers,
                    MacOpcode.PHASE1_40_GROUP_VOICE_CHANNEL_GRANT_IMPLICIT, timestamp);
            }
        }

        private void send(IMessage message)
        {
            state.receive(message);
            audio.receive(message);
        }

        private void startPushToTalk(long timestamp)
        {
            CorrectedBinaryMessage bits = messageBits(1);
            bits.setInt(0x80, IntField.length8(80));
            bits.setInt(TALKGROUP, GROUP);
            send(new MacMessage(timeslot, DataUnitID.UNSCRAMBLED_FACCH, bits, timestamp, new PushToTalk(bits)));
        }

        private void voice(long timestamp)
        {
            send(new Voice2Timeslot(new CorrectedBinaryMessage(320), new BinaryMessage(320), timeslot, timestamp));
        }

        private void clearEss(long timestamp)
        {
            CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
            bits.setInt(0x80, IntField.length8(0));
            send(new EncryptionSynchronizationSequence(bits, timeslot, timestamp));
        }

        private void endPushToTalk(long timestamp)
        {
            CorrectedBinaryMessage bits = messageBits(2);
            bits.setInt(0x123, NAC);
            bits.setInt(TALKGROUP, GROUP);
            send(new MacMessage(timeslot, DataUnitID.UNSCRAMBLED_FACCH, bits, timestamp, new EndPushToTalk(bits)));
        }

        private void hangtime(long timestamp)
        {
            CorrectedBinaryMessage bits = messageBits(6);
            send(new MacMessage(timeslot, DataUnitID.UNSCRAMBLED_FACCH, bits, timestamp, new NullInformation(bits, 8)));
        }

        private List<CompletedAudioCall> completedCalls()
        {
            return events.stream().filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED)
                .map(event -> new CompletedAudioCall(event.snapshot(), events.stream()
                    .filter(frame -> frame.eventType() == AudioCallEventType.AUDIO_FRAME && frame.callId().equals(event.callId()))
                    .map(AudioCallEvent::audioFrame).toList())).toList();
        }

        @Override
        public void close()
        {
            audio.dispose();
        }
    }

    private void updateSource(int radio)
    {
        mAudioModule.getIdentifierUpdateListener().receive(new IdentifierUpdateNotification(
            APCO25RadioIdentifier.createFrom(radio), IdentifierUpdateNotification.Operation.ADD, TIMESLOT));
    }

    private List<AudioCallEvent> completedEvents()
    {
        return mEvents.stream().filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).toList();
    }

    private int pendingVoiceTimeslotCount() throws ReflectiveOperationException
    {
        Field field = P25P2AudioModule.class.getDeclaredField("mQueuedAudioTimeslots");
        field.setAccessible(true);
        return ((Collection<?>)field.get(mAudioModule)).size();
    }

    private static Voice2Timeslot voiceTimeslot(long timestamp)
    {
        return new Voice2Timeslot(new CorrectedBinaryMessage(320), new BinaryMessage(320), TIMESLOT, timestamp);
    }

    private static MacMessage pushToTalk(long timestamp)
    {
        CorrectedBinaryMessage bits = messageBits(1);
        bits.setInt(0x80, IntField.length8(80)); //Unencrypted
        return new MacMessage(TIMESLOT, DataUnitID.UNSCRAMBLED_FACCH, bits, timestamp, new PushToTalk(bits));
    }

    private static MacMessage encryptedPushToTalk(long timestamp, int algorithm, int keyId, int source, int group)
    {
        CorrectedBinaryMessage bits = messageBits(1);
        bits.setInt(algorithm, IntField.length8(80));
        bits.setInt(keyId, IntField.length16(88));
        bits.setInt(source, SOURCE);
        bits.setInt(group, GROUP);
        return new MacMessage(TIMESLOT, DataUnitID.UNSCRAMBLED_FACCH, bits, timestamp, new PushToTalk(bits));
    }

    private static MacMessage malformedEncryptedPushToTalk(long timestamp)
    {
        CorrectedBinaryMessage bits = messageBits(1);
        PushToTalk pushToTalk = new PushToTalk(bits)
        {
            @Override
            public boolean isEncrypted()
            {
                return true;
            }

            @Override
            public IEncryptionSyncParameters getEncryptionSyncParameters()
            {
                return null;
            }
        };
        return new MacMessage(TIMESLOT, DataUnitID.UNSCRAMBLED_FACCH, bits, timestamp, pushToTalk);
    }

    private static MacMessage endPushToTalk(long timestamp)
    {
        CorrectedBinaryMessage bits = messageBits(2);
        return new MacMessage(TIMESLOT, DataUnitID.UNSCRAMBLED_FACCH, bits, timestamp, new EndPushToTalk(bits));
    }

    private static MacMessage endPushToTalk(long timestamp, int nac, int source, int group)
    {
        CorrectedBinaryMessage bits = messageBits(2);
        bits.setInt(nac, NAC);
        bits.setInt(source, SOURCE);
        bits.setInt(group, GROUP);
        return new MacMessage(TIMESLOT, DataUnitID.UNSCRAMBLED_FACCH, bits, timestamp, new EndPushToTalk(bits));
    }

    private static MacMessage hangtime(long timestamp)
    {
        CorrectedBinaryMessage bits = messageBits(6);
        return new MacMessage(TIMESLOT, DataUnitID.UNSCRAMBLED_FACCH, bits, timestamp, null);
    }

    private static CorrectedBinaryMessage messageBits(int pduType)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(320);
        bits.setInt(pduType, IntField.range(0, 2));
        return bits;
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
