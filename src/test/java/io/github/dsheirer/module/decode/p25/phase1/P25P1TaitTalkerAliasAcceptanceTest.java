/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelEventTracker;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWordFactory;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCGroupVoiceChannelUser;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.tait.LCTaitTalkerAlias;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class P25P1TaitTalkerAliasAcceptanceTest
{
    @Test
    void ownedNameReachesAudioOnlyAfterContextAcceptance()
    {
        for(boolean accepted: new boolean[]{false, true})
        for(RadioIdentifier radio: new RadioIdentifier[]{APCO25RadioIdentifier.createFrom(101),
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xABCDE, 0x123, 101)})
        {
            Channel channel = channel();
            AcceptanceManager manager = new AcceptanceManager(channel, accepted);
            P25P1DecoderState state = new P25P1DecoderState(channel, manager);
            //Phase 1 identifier notifications use slot 0; slot 1 is the traffic manager's logical tracker key.
            MutableAudioCallBuilder audio = new MutableAudioCallBuilder(AliasList.empty("test"),
                state.getIdentifierCollection().getTimeslot());
            state.setIdentifierUpdateListener(audio::receive);
            state.receive(ldu(source(radio), 1, 900));

            state.receive(ldu(alias("UNIT,101")));

            assertEquals(1, manager.mCalls);
            assertNull(manager.mAliasBeforeDecision);
            if(accepted)
            {
                assertEquals("UNIT,101", owned(state.getIdentifierCollection()).getValue());
                assertTrue(owned(audio.getIdentifierCollection()).matchesSource(radio));
            }
            else
            {
                assertNull(owned(state.getIdentifierCollection()));
                assertNull(owned(audio.getIdentifierCollection()));
            }
        }
    }

    @Test
    void unknownAndPlainZeroCannotReceiveATextOnlyName()
    {
        for(boolean zeroPresent: new boolean[]{false, true})
        {
            Channel channel = channel();
            AcceptanceManager manager = new AcceptanceManager(channel, true);
            P25P1DecoderState state = new P25P1DecoderState(channel, manager);
            if(zeroPresent) state.receive(ldu(source(APCO25RadioIdentifier.createFrom(0)), 1, 900));

            state.receive(ldu(alias("ORPHAN")));

            assertNull(owned(state.getIdentifierCollection()));
        }
    }

    @Test
    void explicitUnknownSourceInvalidatesThePreviousAliasAnchorWithoutChangingCallContinuity()
    {
        for(RadioIdentifier next: new RadioIdentifier[]{APCO25RadioIdentifier.createFrom(202),
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xABCDE, 0x123, 202)})
        {
            Channel channel = channel();
            AcceptanceManager manager = new AcceptanceManager(channel, true);
            P25P1DecoderState state = new P25P1DecoderState(channel, manager);
            state.receive(ldu(source(APCO25RadioIdentifier.createFrom(101)), 1, 900));
            CorrectedBinaryMessage unknownBits = new CorrectedBinaryMessage(72);
            unknownBits.load(32, 16, 1201);
            LCGroupVoiceChannelUser unknown = new LCGroupVoiceChannelUser(unknownBits, 1_000, false);
            unknown.setValid(true);
            assertFalse(unknown.hasSourceAddress(), "The production parser omits a plain zero source");
            state.receive(ldu(unknown, 1, 1_000));

            state.receive(ldu(alias("UNKNOWN"), 1, 1_100));

            assertEquals(0, manager.mCalls);
            assertNull(owned(state.getIdentifierCollection()));
            assertEquals(101, state.getIdentifierCollection().getFromIdentifier().getValue(),
                "Generic zero-source call continuity remains separate from name ownership");

            state.receive(ldu(source(next), 1, 1_200));
            state.receive(ldu(alias("NEW NAME"), 1, 1_300));

            assertEquals(1, manager.mCalls);
            assertTrue(owned(state.getIdentifierCollection()).matchesSource(next));
        }
    }

    @Test
    void failedFecAndProtectedTextNeverReachAcceptance()
    {
        for(boolean protectedText: new boolean[]{false, true})
        {
            Channel channel = channel();
            AcceptanceManager manager = new AcceptanceManager(channel, true);
            P25P1DecoderState state = new P25P1DecoderState(channel, manager);
            state.receive(ldu(source(APCO25RadioIdentifier.createFrom(101)), 1, 900));
            LCTaitTalkerAlias alias = alias("UNIT 101");
            if(protectedText) alias.getMessage().set(0);
            else alias.setValid(false);

            state.receive(ldu(alias));

            assertEquals(0, manager.mCalls);
            assertNull(owned(state.getIdentifierCollection()));
        }
    }

    @Test
    void acceptanceCannotCarryNameAcrossSourceReplacementOrReceptionReset()
    {
        for(String boundary: new String[]{"source", "reset", "retune"})
        {
            Channel channel = channel();
            AcceptanceManager manager = new AcceptanceManager(channel, true);
            P25P1DecoderState state = new P25P1DecoderState(channel, manager);
            state.receive(ldu(source(APCO25RadioIdentifier.createFrom(101)), 1, 900));
            manager.mOnDecision = () -> {
                if(boundary.equals("source"))
                {
                    state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(202));
                }
                else
                {
                    if(boundary.equals("reset")) state.reset();
                    else state.receiveDecoderStateEvent(new DecoderStateEvent(this,
                        DecoderStateEvent.Event.NOTIFICATION_SOURCE_FREQUENCY, State.IDLE, 1, 851_012_500));
                    state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(101));
                }
            };

            state.receive(ldu(alias("OLD NAME")));

            assertEquals(1, manager.mCalls);
            assertNull(owned(state.getIdentifierCollection()), boundary);
        }
    }

    @Test
    void companionDoesNotCreateOrReplaceTheObservedRadio()
    {
        Channel channel = channel();
        AcceptanceManager manager = new AcceptanceManager(channel, true);
        P25P1DecoderState state = new P25P1DecoderState(channel, manager);
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(72);
        bits.load(0, 8, 1);
        bits.load(8, 8, 0xD8);
        bits.load(16, 20, 0xABCDE);
        bits.load(36, 12, 0x123);
        bits.load(48, 24, 202);
        LinkControlWord companion = LinkControlWordFactory.create(bits, 1_000, false);
        companion.setValid(true);

        state.receive(ldu(companion));
        state.receive(ldu(alias("ORPHAN")));

        assertNull(state.getIdentifierCollection().getFromIdentifier());
        assertNull(owned(state.getIdentifierCollection()));
        assertEquals(0, manager.mCalls);
    }

    @Test
    void changedNacRequiresANewSourceBearingMessage()
    {
        Channel channel = channel();
        AcceptanceManager manager = new AcceptanceManager(channel, true);
        P25P1DecoderState state = new P25P1DecoderState(channel, manager);
        state.receive(ldu(source(APCO25RadioIdentifier.createFrom(101)), 1, 900));

        state.receive(ldu(alias("OLD NAC"), 2, 1_100));

        assertEquals(0, manager.mCalls);
        assertNull(owned(state.getIdentifierCollection()));
        state.receive(ldu(source(APCO25RadioIdentifier.createFrom(101)), 2, 1_200));
        state.receive(ldu(alias("NEW NAC"), 2, 1_300));
        assertEquals(1, manager.mCalls);
        assertEquals("NEW NAC", owned(state.getIdentifierCollection()).getValue());
    }

    @Test
    void realAcceptanceRequiresMatchingActiveCallAndRetainsQualifiedLocalZero() throws Exception
    {
        for(String context: new String[]{"active", "missing", "complete", "stale", "early", "other-radio", "other-group"})
        for(RadioIdentifier radio: new RadioIdentifier[]{APCO25RadioIdentifier.createFrom(101),
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xABCDE, 0x123, 101)})
        {
            P25TrafficChannelManager manager = new P25TrafficChannelManager(channel());
            MutableIdentifierCollection identifiers = identifiers(1201, radio);
            P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000, null)
                .identifiers(identifiers).build();
            P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event, "p25:abcde:123");
            tracker.updateDurationTraffic(1_050);
            if(context.equals("complete")) tracker.completeTraffic(1_060);
            if(!context.equals("missing")) trackers(manager).put(851_012_500L, tracker);
            RadioIdentifier observed = context.equals("other-radio") ? APCO25RadioIdentifier.createFrom(202) : radio;
            var alias = P25TalkerAliasIdentifier.create("UNIT 101");

            boolean accepted = manager.processP1TaitTalkerAlias(851_012_500L, observed, alias,
                identifiers(context.equals("other-group") ? 1202 : 1201, observed),
                context.equals("early") ? 999 : context.equals("stale") ? 5_000 : 1_100);

            assertEquals(context.equals("active"), accepted, context);
            assertEquals(accepted, manager.getTalkerAliasManager().hasAlias(radio, "p25:abcde:123"), context);
            if(accepted) assertTrue(owned(event.getIdentifierCollection()).matchesSource(radio));
            else assertNull(owned(event.getIdentifierCollection()));
        }
    }

    @Test
    void realAcceptanceCannotLearnPlainZeroOrHomeSubscriberZero() throws Exception
    {
        for(RadioIdentifier radio: new RadioIdentifier[]{APCO25RadioIdentifier.createFrom(0),
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xABCDE, 0x123, 0)})
        {
            P25TrafficChannelManager manager = new P25TrafficChannelManager(channel());
            var identifiers = identifiers(1201, radio);
            var event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000, null)
                .identifiers(identifiers).build();
            var tracker = new P25TrafficChannelEventTracker(event, "p25:abcde:123");
            tracker.updateDurationTraffic(1_050);
            trackers(manager).put(851_012_500L, tracker);

            assertFalse(manager.processP1TaitTalkerAlias(851_012_500L, radio,
                P25TalkerAliasIdentifier.create("ORPHAN"), identifiers, 1_100));
            assertNull(owned(event.getIdentifierCollection()));
        }
    }

    @Test
    void contendedManagerDropsOptionalNameWithoutLearningOrCallingObservers() throws Exception
    {
        P25TrafficChannelManager manager = new P25TrafficChannelManager(channel());
        RadioIdentifier radio = APCO25RadioIdentifier.createFrom(101);
        var identifiers = identifiers(1201, radio);
        var event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000, null)
            .identifiers(identifiers).build();
        var tracker = new P25TrafficChannelEventTracker(event, "p25:abcde:123");
        tracker.updateDurationTraffic(1_050);
        trackers(manager).put(851_012_500L, tracker);
        AtomicInteger observerCalls = new AtomicInteger();
        manager.addDecodeEventListener(ignored -> observerCalls.incrementAndGet());
        Field field = P25TrafficChannelManager.class.getDeclaredField("mLock");
        field.setAccessible(true);
        ReentrantLock lock = (ReentrantLock)field.get(manager);
        lock.lock();
        try
        {
            assertTimeoutPreemptively(Duration.ofMillis(500), () ->
                assertFalse(manager.processP1TaitTalkerAlias(851_012_500L, radio,
                    P25TalkerAliasIdentifier.create("OPTIONAL"), identifiers, 1_100)));
            assertFalse(manager.getTalkerAliasManager().hasAlias(radio, "p25:abcde:123"));
            assertNull(owned(event.getIdentifierCollection()));
            assertEquals(0, observerCalls.get());
        }
        finally
        {
            lock.unlock();
        }
    }

    private static TalkerAliasIdentifier owned(IdentifierCollection identifiers)
    {
        return (TalkerAliasIdentifier)identifiers.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
    }

    private static MutableIdentifierCollection identifiers(int group, RadioIdentifier radio)
    {
        var identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(group));
        identifiers.update(radio);
        return identifiers;
    }

    @SuppressWarnings("unchecked")
    private static Map<Long,P25TrafficChannelEventTracker> trackers(P25TrafficChannelManager manager) throws Exception
    {
        Field field = P25TrafficChannelManager.class.getDeclaredField("mTS1ChannelGrantEventMap");
        field.setAccessible(true);
        return (Map<Long,P25TrafficChannelEventTracker>)field.get(manager);
    }

    private static Channel channel()
    {
        Channel channel = new Channel("Traffic", Channel.ChannelType.TRAFFIC);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        return channel;
    }

    private static LCTaitTalkerAlias alias(String text)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(72);
        bits.load(8, 8, 0xD8);
        for(int index = 0; index < text.length(); index++) bits.load(16 + index * 7, 7, text.charAt(index));
        var alias = new LCTaitTalkerAlias(bits);
        alias.setValid(true);
        return alias;
    }

    private static LDU1Message ldu(LinkControlWord word)
    {
        return ldu(word, 1, 1_100);
    }

    private static LDU1Message ldu(LinkControlWord word, int nac, long timestamp)
    {
        return new LDU1Message(new CorrectedBinaryMessage(1_568), nac, timestamp)
        {
            @Override public LinkControlWord getLinkControlWord() { return word; }
        };
    }

    private static LinkControlWord source(RadioIdentifier radio)
    {
        var word = new LCGroupVoiceChannelUser(new CorrectedBinaryMessage(72), 900, false)
        {
            @Override public List<io.github.dsheirer.identifier.Identifier> getIdentifiers()
            {
                return List.of(APCO25Talkgroup.create(1201), radio);
            }
        };
        word.setValid(true);
        return word;
    }

    private static class AcceptanceManager extends P25TrafficChannelManager
    {
        private final boolean mAccepted;
        private int mCalls;
        private TalkerAliasIdentifier mAliasBeforeDecision;
        private Runnable mOnDecision;

        private AcceptanceManager(Channel channel, boolean accepted)
        {
            super(channel);
            mAccepted = accepted;
        }

        @Override
        public boolean processP1TaitTalkerAlias(long frequency, RadioIdentifier radio, TalkerAliasIdentifier alias,
                                              IdentifierCollection identifiers, long timestamp)
        {
            mCalls++;
            mAliasBeforeDecision = owned(identifiers);
            if(mOnDecision != null) mOnDecision.run();
            return mAccepted;
        }
    }
}
