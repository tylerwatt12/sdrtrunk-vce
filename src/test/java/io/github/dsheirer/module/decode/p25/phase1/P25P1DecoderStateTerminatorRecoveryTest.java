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

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.message.TimeslotMessage;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlOpcode;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDULCMessage;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class P25P1DecoderStateTerminatorRecoveryTest
{
    private static final long FREQUENCY = 851_012_500L;
    private static final int TALKGROUP = 56_132;

    @Test
    void recoversMissingSourceBeforeCompletingCallAndClearsDecoderUsers()
    {
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Parent"));
        P25ChannelGrantEvent call = startCall(manager, TALKGROUP, null, 1_000L);
        P25P1DecoderState decoderState = decoderState(manager);
        RadioIdentifier source = APCO25RadioIdentifier.createFrom(1_880_997);
        List<String> ordering = new ArrayList<>();

        decoderState.setIdentifierUpdateListener(notification ->
        {
            if(notification.isAdd() && source.equals(notification.getIdentifier()))
            {
                ordering.add("source");
            }
        });
        decoderState.setDecoderStateListener(event ->
        {
            if(event.getEvent() == Event.DECODE && event.getState() == State.ACTIVE)
            {
                ordering.add("complete");
            }
        });

        decoderState.receive(tdulc(1_300L, APCO25Talkgroup.create(TALKGROUP), source));

        assertSame(source, call.getIdentifierCollection().getFromIdentifier());
        assertEquals(List.of("source", "complete"), ordering);
        assertTrue(decoderState.getIdentifierCollection().getIdentifiers(IdentifierClass.USER).isEmpty());
    }

    @Test
    void preservesEstablishedSourceAndTargetConflicts()
    {
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Parent"));
        RadioIdentifier establishedSource = APCO25RadioIdentifier.createFrom(1_880_997);
        P25ChannelGrantEvent call = startCall(manager, TALKGROUP, establishedSource, 1_000L);

        List<Identifier> recovered = manager.recoverP25TrafficEndFrameIdentifiers(FREQUENCY,
            TimeslotMessage.TIMESLOT_1, Protocol.APCO25, new MutableIdentifierCollection(),
            List.of(APCO25Talkgroup.create(TALKGROUP + 1), APCO25RadioIdentifier.createFrom(1_880_998)), 1_300L);

        assertTrue(recovered.isEmpty());
        assertEquals(APCO25Talkgroup.create(TALKGROUP), call.getIdentifierCollection().getToIdentifier());
        assertSame(establishedSource, call.getIdentifierCollection().getFromIdentifier());
    }

    @Test
    void decoderConflictDoesNotPopulateMissingTrackerSource()
    {
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Parent"));
        P25ChannelGrantEvent call = startCall(manager, TALKGROUP, null, 1_000L);
        P25P1DecoderState decoderState = decoderState(manager);
        decoderState.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(1_880_997));

        decoderState.receive(tdulc(1_300L, APCO25Talkgroup.create(TALKGROUP),
            APCO25RadioIdentifier.createFrom(1_880_998)));

        assertNull(call.getIdentifierCollection().getFromIdentifier());
    }

    @Test
    void invalidTerminatorDoesNotRecoverOrCompleteCall()
    {
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Parent"));
        P25ChannelGrantEvent call = startCall(manager, TALKGROUP, null, 1_000L);
        P25P1DecoderState decoderState = decoderState(manager);
        TDULCMessage message = tdulc(1_300L, APCO25Talkgroup.create(TALKGROUP),
            APCO25RadioIdentifier.createFrom(1_880_997));
        message.setValid(false);

        decoderState.receive(message);

        assertNull(call.getIdentifierCollection().getFromIdentifier());
        assertEquals(100L, call.getDuration());
    }

    @Test
    void delayedTerminatorMetadataDoesNotAttachToNewerCall()
    {
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Parent"));
        P25ChannelGrantEvent call = startCall(manager, TALKGROUP, null, 2_000L);

        assertTrue(manager.recoverP25TrafficEndFrameIdentifiers(FREQUENCY, TimeslotMessage.TIMESLOT_1,
            Protocol.APCO25, new MutableIdentifierCollection(),
            List.of(APCO25RadioIdentifier.createFrom(1_880_997)), 1_999L).isEmpty());
        assertNull(call.getIdentifierCollection().getFromIdentifier());
    }

    @Test
    void recoversValidNacAndPreservesDecoderConflict()
    {
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Parent"));
        P25ChannelGrantEvent call = startCall(manager, TALKGROUP, null, 1_000L);
        Identifier nac = APCO25Nac.create(0x123);

        assertEquals(List.of(nac), manager.recoverP25TrafficEndFrameIdentifiers(FREQUENCY,
            TimeslotMessage.TIMESLOT_1, Protocol.APCO25_PHASE2, new MutableIdentifierCollection(), List.of(nac),
            1_300L));
        assertTrue(call.getIdentifierCollection().hasIdentifier(nac));

        P25TrafficChannelManager conflictManager = new P25TrafficChannelManager(new Channel("Conflict"));
        P25ChannelGrantEvent conflictCall = startCall(conflictManager, TALKGROUP, null, 2_000L);
        MutableIdentifierCollection decoderIdentifiers = new MutableIdentifierCollection();
        decoderIdentifiers.update(APCO25Nac.create(0x456));

        assertTrue(conflictManager.recoverP25TrafficEndFrameIdentifiers(FREQUENCY,
            TimeslotMessage.TIMESLOT_1, Protocol.APCO25_PHASE2, decoderIdentifiers, List.of(nac), 2_300L).isEmpty());
        assertFalse(conflictCall.getIdentifierCollection().hasIdentifier(nac));
    }

    @Test
    void rejectsReservedSourceAndDoesNotCreateTracker()
    {
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Parent"));
        P25ChannelGrantEvent call = startCall(manager, TALKGROUP, null, 1_000L);
        Identifier reservedSource = APCO25RadioIdentifier.createFrom(0xFFFFFC);

        assertTrue(manager.recoverP25TrafficEndFrameIdentifiers(FREQUENCY, TimeslotMessage.TIMESLOT_1,
            Protocol.APCO25, new MutableIdentifierCollection(), List.of(reservedSource), 1_300L).isEmpty());
        assertFalse(call.getIdentifierCollection().hasIdentifier(reservedSource));

        P25TrafficChannelManager emptyManager = new P25TrafficChannelManager(new Channel("Empty"));
        assertTrue(emptyManager.recoverP25TrafficEndFrameIdentifiers(FREQUENCY, TimeslotMessage.TIMESLOT_1,
            Protocol.APCO25, new MutableIdentifierCollection(), List.of(APCO25Talkgroup.create(TALKGROUP),
                APCO25RadioIdentifier.createFrom(1_880_997)), 1_300L).isEmpty());
    }

    private static P25P1DecoderState decoderState(P25TrafficChannelManager manager)
    {
        Channel channel = new Channel("Traffic", ChannelType.TRAFFIC);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, manager);
        decoderState.setCurrentFrequency(FREQUENCY);
        return decoderState;
    }

    private static P25ChannelGrantEvent startCall(P25TrafficChannelManager manager, int talkgroup,
                                                   RadioIdentifier source, long timestamp)
    {
        AtomicReference<P25ChannelGrantEvent> event = new AtomicReference<>();
        manager.addDecodeEventListener(decodeEvent -> event.set((P25ChannelGrantEvent)decodeEvent));
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(talkgroup));
        identifiers.update(source);
        StandardChannel channel = new StandardChannel(FREQUENCY);

        manager.processP1TrafficCurrentUser(FREQUENCY, channel, DecodeEventType.CALL_GROUP,
            VoiceServiceOptions.createUnencrypted(), identifiers, timestamp, null);
        manager.processP1TrafficCurrentUser(FREQUENCY, channel, DecodeEventType.CALL_GROUP,
            VoiceServiceOptions.createUnencrypted(), identifiers, timestamp + 100L, null);
        return event.get();
    }

    private static TDULCMessage tdulc(long timestamp, Identifier... identifiers)
    {
        TestLinkControlWord linkControl = new TestLinkControlWord(List.of(identifiers));
        linkControl.setValid(true);
        return new TestTDULCMessage(linkControl, timestamp);
    }

    private static class TestTDULCMessage extends TDULCMessage
    {
        private final LinkControlWord mLinkControlWord;

        private TestTDULCMessage(LinkControlWord linkControlWord, long timestamp)
        {
            super(new CorrectedBinaryMessage(288), 0x123, timestamp);
            mLinkControlWord = linkControlWord;
        }

        @Override
        public LinkControlWord getLinkControlWord()
        {
            return mLinkControlWord;
        }
    }

    private static class TestLinkControlWord extends LinkControlWord
    {
        private final List<Identifier> mIdentifiers;

        private TestLinkControlWord(List<Identifier> identifiers)
        {
            super(new CorrectedBinaryMessage(72));
            mIdentifiers = identifiers;
        }

        @Override
        public LinkControlOpcode getOpcode()
        {
            return LinkControlOpcode.GROUP_VOICE_CHANNEL_USER;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return mIdentifiers;
        }
    }
}
