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

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.EndPushToTalk;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.PushToTalk;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.Voice2Timeslot;
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

    private static MacMessage endPushToTalk(long timestamp)
    {
        CorrectedBinaryMessage bits = messageBits(2);
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
