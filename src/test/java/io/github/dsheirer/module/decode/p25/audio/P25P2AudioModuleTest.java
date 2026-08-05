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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.P25P2DecoderState;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.EndPushToTalk;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.PushToTalk;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.Voice2Timeslot;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.preference.UserPreferences;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
}
