/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.l3harris.L3HarrisTalkerAlias;
import org.junit.jupiter.api.Test;

class P25P2HarrisTalkerAliasAcceptanceTest
{
    @Test
    void newPushToTalkWithoutASourceCannotInheritThePreviousRadiosName()
    {
        Channel channel = channel();
        AcceptanceManager manager = new AcceptanceManager(channel, true);
        var state = new P25P2DecoderState(channel, 1, manager, new PatchGroupManager());
        state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(101));
        state.receive(message(1, "OLD UNIT"));
        assertEquals("OLD UNIT", alias(state.getIdentifierCollection()).getValue());

        var bits = new CorrectedBinaryMessage(180);
        bits.load(80, 8, 0x80);
        bits.load(128, 16, 100);
        var ptt = new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.PushToTalk(bits);
        state.receive(new MacMessage(1, DataUnitID.UNSCRAMBLED_FACCH, bits, 1200, ptt));
        state.receive(message(2, "OTHER SLOT"));
        state.receive(message(1, "UNKNOWN UNIT"));
        assertNull(alias(state.getIdentifierCollection()));
        assertEquals(1, manager.mCalls, "an unknown source is rejected before name acceptance");

        var qualified = io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier
            .createFrom(0, 0xABCDE, 0x123, 202);
        state.getIdentifierCollection().update(qualified);
        state.receive(message(1, "KNOWN HOME"));
        assertEquals("KNOWN HOME", alias(state.getIdentifierCollection()).getValue());
        assertTrue(((TalkerAliasIdentifier)alias(state.getIdentifierCollection())).matchesSource(qualified));
    }

    @Test
    void malformedAliasStructureCannotReachAcceptance()
    {
        Channel channel = channel();
        AcceptanceManager manager = new AcceptanceManager(channel, true);
        var state = new P25P2DecoderState(channel, 1, manager, new PatchGroupManager());
        state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(101));
        var bits = new CorrectedBinaryMessage(32);
        bits.load(0, 8, 0xA8);
        bits.load(8, 8, 0xA4);
        bits.load(16, 8, 17);
        state.receive(new MacMessage(1, DataUnitID.UNSCRAMBLED_FACCH, bits, 1100,
            new L3HarrisTalkerAlias(bits, 0)));
        assertEquals(0, manager.mCalls);
        assertNull(alias(state.getIdentifierCollection()));
    }

    @Test
    void rejectedAliasNeverReachesDecoderOrAudioMetadata()
    {
        Channel channel = channel();
        AcceptanceManager manager = new AcceptanceManager(channel, false);
        P25P2DecoderState state = new P25P2DecoderState(channel, 1, manager, new PatchGroupManager());
        MutableAudioCallBuilder audio = new MutableAudioCallBuilder(AliasList.empty("test"), 1);
        state.setIdentifierUpdateListener(audio::receive);
        state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(101));

        state.receive(message(1, "UNACCEPTED"));

        assertEquals(1, manager.mCalls);
        assertNull(manager.mAliasBeforeDecision);
        assertNull(alias(state.getIdentifierCollection()));
        assertNull(alias(audio.getIdentifierCollection()));
    }

    @Test
    void acceptedAliasIsPublishedOnlyAfterSourceValidationAndStaysOnItsTimeslot()
    {
        Channel channel = channel();
        AcceptanceManager manager = new AcceptanceManager(channel, true);
        P25P2DecoderState state = new P25P2DecoderState(channel, 1, manager, new PatchGroupManager());
        MutableAudioCallBuilder audio = new MutableAudioCallBuilder(AliasList.empty("test"), 1);
        state.setIdentifierUpdateListener(audio::receive);
        state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(101));

        state.receive(message(2, "OTHER TIMESLOT"));
        assertEquals(0, manager.mCalls);
        assertNull(alias(audio.getIdentifierCollection()));
        state.receive(message(1, "UNIT 101"));

        assertEquals(1, manager.mCalls);
        assertNull(manager.mAliasBeforeDecision);
        assertEquals("UNIT 101", alias(state.getIdentifierCollection()).getValue());
        assertEquals("UNIT 101", alias(audio.getIdentifierCollection()).getValue());
        assertTrue(((TalkerAliasIdentifier)alias(audio.getIdentifierCollection()))
            .matchesSource(APCO25RadioIdentifier.createFrom(101)));
        assertEquals(101, audio.getIdentifierCollection().getFromIdentifier().getValue());
    }

    @Test
    void unknownSourceCannotPublishAnAlias()
    {
        Channel channel = channel();
        AcceptanceManager manager = new AcceptanceManager(channel, true);
        P25P2DecoderState state = new P25P2DecoderState(channel, 1, manager, new PatchGroupManager());

        state.receive(message(1, "ORPHAN"));

        assertNull(alias(state.getIdentifierCollection()));
    }

    @Test
    void rejectedReplacementPreservesThePreviouslyAcceptedAlias()
    {
        Channel channel = channel();
        AcceptanceManager manager = new AcceptanceManager(channel, true);
        P25P2DecoderState state = new P25P2DecoderState(channel, 1, manager, new PatchGroupManager());
        state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(101));
        state.receive(message(1, "UNIT 101"));
        manager.mAccepted = false;

        state.receive(message(1, "WRONG REPLACEMENT"));

        assertEquals("UNIT 101", manager.mAliasBeforeDecision.getValue());
        assertEquals("UNIT 101", alias(state.getIdentifierCollection()).getValue());
    }

    @Test
    void acceptanceObserverCannotTransferTheNameToAReplacementSource()
    {
        Channel channel = channel();
        AcceptanceManager manager = new AcceptanceManager(channel, true);
        P25P2DecoderState state = new P25P2DecoderState(channel, 1, manager, new PatchGroupManager());
        MutableAudioCallBuilder audio = new MutableAudioCallBuilder(AliasList.empty("test"), 1);
        state.setIdentifierUpdateListener(audio::receive);
        state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(101));
        manager.mOnDecision = () -> state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(202));

        state.receive(message(1, "UNIT 101"));

        assertEquals(1, manager.mCalls);
        assertEquals(202, state.getIdentifierCollection().getFromIdentifier().getValue());
        assertNull(alias(state.getIdentifierCollection()));
        assertNull(alias(audio.getIdentifierCollection()));
    }

    @Test
    void acceptanceObserverCannotRestoreANameAfterResetOrRetuneWithTheSameNumericRadio()
    {
        for(boolean retune: new boolean[]{false, true})
        {
            Channel channel = channel();
            AcceptanceManager manager = new AcceptanceManager(channel, true);
            P25P2DecoderState state = new P25P2DecoderState(channel, 1, manager, new PatchGroupManager());
            MutableAudioCallBuilder audio = new MutableAudioCallBuilder(AliasList.empty("test"), 1);
            state.setIdentifierUpdateListener(audio::receive);
            state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(101));
            manager.mOnDecision = () ->
            {
                if(retune)
                {
                    state.receiveDecoderStateEvent(new DecoderStateEvent(this,
                        DecoderStateEvent.Event.NOTIFICATION_SOURCE_FREQUENCY, State.IDLE, 1, 851_012_500));
                }
                else
                {
                    state.reset();
                }
                state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(101));
            };

            state.receive(message(1, "OLD RECEPTION"));

            assertEquals(1, manager.mCalls);
            assertEquals(101, state.getIdentifierCollection().getFromIdentifier().getValue());
            assertNull(alias(state.getIdentifierCollection()));
            assertNull(alias(audio.getIdentifierCollection()));
        }
    }

    private static Identifier<?> alias(IdentifierCollection identifiers)
    {
        return identifiers.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
    }

    private static Channel channel()
    {
        Channel channel = new Channel("Traffic", Channel.ChannelType.TRAFFIC);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        return channel;
    }

    private static MacMessage message(int timeslot, String text)
    {
        L3HarrisTalkerAlias structure = new L3HarrisTalkerAlias(new CorrectedBinaryMessage(180), 0)
        {
            @Override public MacOpcode getOpcode() { return MacOpcode.L3HARRIS_A8_TALKER_ALIAS; }
            @Override public P25TalkerAliasIdentifier getAlias() { return P25TalkerAliasIdentifier.create(text); }
        };
        return new MacMessage(timeslot, DataUnitID.UNSCRAMBLED_FACCH, new CorrectedBinaryMessage(180), 1_100,
            structure);
    }

    private static class AcceptanceManager extends P25TrafficChannelManager
    {
        private boolean mAccepted;
        private int mCalls;
        private Identifier<?> mAliasBeforeDecision;
        private Runnable mOnDecision;

        private AcceptanceManager(Channel channel, boolean accepted)
        {
            super(channel);
            mAccepted = accepted;
        }

        @Override
        public boolean processP2HarrisTalkerAlias(long frequency, int timeslot, RadioIdentifier radio,
                                                  TalkerAliasIdentifier alias, IdentifierCollection identifiers,
                                                  long timestamp)
        {
            mAliasBeforeDecision = alias(identifiers);
            mCalls++;
            if(mOnDecision != null) mOnDecision.run();
            return mAccepted && radio != null;
        }
    }
}
