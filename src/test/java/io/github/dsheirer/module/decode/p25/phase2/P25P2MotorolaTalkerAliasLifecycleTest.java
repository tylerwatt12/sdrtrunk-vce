/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola.MotorolaTalkerAliasComplete;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.SuperFrameFragment;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacPduType;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructureFailedPDUCRC;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.NullInformation;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaTalkerAliasDataBlock;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaTalkerAliasHeader;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.AbstractSignalingTimeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.ScramblingSequence;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.Timeslot;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P2MotorolaTalkerAliasLifecycleTest
{
    private static final String PAYLOAD =
        "BEE002AEE6785283ED1081E33C03E9B3E35647DE0C00C8A83E351E4079F592CF3794B3";

    @Test
    void listenerResetDiscardsTheOldCompletionAndItsRecursiveReceive()
    {
        P25P2MessageProcessor processor = new P25P2MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = new ArrayList<>();
        MacStructure last = block(3);
        processor.setMessageListener(message -> {
            if(message instanceof MacMessage mac && mac.getMacStructure() == last)
            {
                processor.resetForSourceFrequencyChange();
                complete(processor, 1, 2_000);
            }
            if(message instanceof MotorolaTalkerAliasComplete alias && alias.isValid()) aliases.add(alias);
        });
        receive(processor, header(), 1, 1_000, true);
        receive(processor, block(1), 1, 1_100, true);
        receive(processor, block(2), 1, 1_200, true);
        receive(processor, last, 1, 1_300, true);
        assertTrue(aliases.isEmpty());
        complete(processor, 1, 3_000);
        assertEquals(1, aliases.size());
    }

    @Test
    void syncLossAndNewPttDiscardThePartialBodyOnThatTimeslot()
    {
        for(int boundary = 0; boundary < 2; boundary++)
        {
            P25P2MessageProcessor processor = new P25P2MessageProcessor();
            List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
            receive(processor, header(), 1, 1_000, true);
            receive(processor, block(1), 1, 1_100, true);
            if(boundary == 0)
                processor.receive(new SyncLossMessage(1_200, 1_000, Protocol.APCO25_PHASE2, 1));
            else
                boundary(processor, 1, MacPduType.MAC_1_PTT, 1_200);
            receive(processor, block(2), 1, 1_300, true);
            receive(processor, block(3), 1, 1_400, true);
            assertTrue(aliases.isEmpty());
            complete(processor, 1, 2_000);
            assertEquals(1, aliases.size());
            assertEquals(0xE67852, aliases.getFirst().getRadio().getRadio());
        }
    }

    @Test
    void endPttPreservesTheAuthoritativeLateAliasBody()
    {
        P25P2MessageProcessor processor = new P25P2MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
        receive(processor, header(), 1, 1_000, true);
        receive(processor, block(1), 1, 1_100, true);
        boundary(processor, 1, MacPduType.MAC_2_END_PTT, 1_200);
        receive(processor, block(2), 1, 1_300, true);
        receive(processor, block(3), 1, 1_400, true);
        assertEquals(1, aliases.size());
    }

    @Test
    void aBoundaryOnOneTimeslotCannotDiscardTheOtherTimeslotsBody()
    {
        P25P2MessageProcessor processor = new P25P2MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
        receive(processor, header(), 2, 1_000, true);
        receive(processor, block(1), 2, 1_100, true);
        boundary(processor, 1, MacPduType.MAC_1_PTT, 1_200);
        processor.receive(new SyncLossMessage(1_300, 1_000, Protocol.APCO25_PHASE2, 1));
        receive(processor, block(2), 2, 1_400, true);
        receive(processor, block(3), 2, 1_500, true);
        assertEquals(1, aliases.size());
        assertEquals(2, aliases.getFirst().getTimeslot());
    }

    @Test
    void invalidProtectedHeaderCannotBorrowACrcValidBody()
    {
        P25P2MessageProcessor processor = new P25P2MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
        receive(processor, header(), 1, 1_000, false);
        for(int number = 1; number <= 3; number++)
            receive(processor, block(number), 1, 1_100 + number, true);
        assertTrue(aliases.isEmpty());
        complete(processor, 1, 2_000);
        assertEquals(1, aliases.size());
    }

    @Test
    void failedPduCrcDiscardsTheHeaderEvenWhenTheOpcodeIsUnavailable()
    {
        P25P2MessageProcessor processor = new P25P2MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
        receive(processor, header(), 1, 1_000, true);
        receive(processor, block(1), 1, 1_100, true);
        receive(processor, new MacStructureFailedPDUCRC(new CorrectedBinaryMessage(180), 0), 1, 1_200, false);
        receive(processor, block(2), 1, 1_300, true);
        receive(processor, block(3), 1, 1_400, true);
        assertTrue(aliases.isEmpty());
        complete(processor, 1, 2_000);
        assertEquals(1, aliases.size());
    }

    @Test
    void invalidProtectedBodyAndRetuneRequireAFreshHeader()
    {
        for(boolean retune: List.of(false, true))
        {
            P25P2MessageProcessor processor = new P25P2MessageProcessor();
            List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
            receive(processor, header(), 1, 1_000, true);
            receive(processor, block(1), 1, 1_100, true);
            if(retune) processor.resetForSourceFrequencyChange();
            else receive(processor, block(2), 1, 1_200, false);
            receive(processor, block(2), 1, 1_300, true);
            receive(processor, block(3), 1, 1_400, true);
            assertTrue(aliases.isEmpty());
            complete(processor, 1, 2_000);
            assertEquals(1, aliases.size());
        }
    }

    private static List<MotorolaTalkerAliasComplete> aliases(P25P2MessageProcessor processor)
    {
        List<MotorolaTalkerAliasComplete> aliases = new ArrayList<>();
        processor.setMessageListener(message -> {
            if(message instanceof MotorolaTalkerAliasComplete alias && alias.isValid()) aliases.add(alias);
        });
        return aliases;
    }

    private static void complete(P25P2MessageProcessor processor, int timeslot, long timestamp)
    {
        receive(processor, header(), timeslot, timestamp, true);
        for(int number = 1; number <= 3; number++)
            receive(processor, block(number), timeslot, timestamp + number, true);
    }

    private static void boundary(P25P2MessageProcessor processor, int timeslot, MacPduType type, long timestamp)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(180);
        message.load(0, 3, type.ordinal());
        receive(processor, new MacMessage(timeslot, DataUnitID.UNSCRAMBLED_FACCH, message, timestamp,
            new NullInformation(message, 0)));
    }

    private static void receive(P25P2MessageProcessor processor, MacStructure structure, int timeslot,
                                long timestamp, boolean valid)
    {
        MacMessage message = new MacMessage(timeslot, DataUnitID.UNSCRAMBLED_FACCH,
            new CorrectedBinaryMessage(180), timestamp, structure);
        message.setValid(valid);
        receive(processor, message);
    }

    private static void receive(P25P2MessageProcessor processor, MacMessage message)
    {
        processor.receive(new TestSuperFrameFragment(new TestSignalingTimeslot(message)));
    }

    private static MotorolaTalkerAliasHeader header()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(136);
        message.load(24, 16, 0x1BBD);
        message.load(40, 8, 3);
        message.load(64, 4, 4);
        message.load(72, CorrectedBinaryMessage.loadHex(PAYLOAD).getSubMessage(0, 64));
        return new MotorolaTalkerAliasHeader(message, 0);
    }

    private static MotorolaTalkerAliasDataBlock block(int number)
    {
        CorrectedBinaryMessage payload = CorrectedBinaryMessage.loadHex(PAYLOAD);
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(136);
        message.load(24, 8, number);
        message.load(32, 4, 4);
        int offset = 64 + (number - 1) * 100;
        for(int bit = 0; bit < 100 && offset + bit < payload.size(); bit++)
            message.set(36 + bit, payload.get(offset + bit));
        return new MotorolaTalkerAliasDataBlock(message, 0);
    }

    private static class TestSignalingTimeslot extends AbstractSignalingTimeslot
    {
        private final List<MacMessage> mMessages;

        private TestSignalingTimeslot(MacMessage message)
        {
            super(new CorrectedBinaryMessage(320), message.getDataUnitID(), message.getTimeslot(), message.getTimestamp());
            mMessages = List.of(message);
        }

        @Override public List<MacMessage> getMacMessages() { return mMessages; }
    }

    private static class TestSuperFrameFragment extends SuperFrameFragment
    {
        private final List<Timeslot> mTimeslots;

        private TestSuperFrameFragment(Timeslot timeslot)
        {
            super(new CorrectedBinaryMessage(1_440), timeslot.getTimestamp(), new ScramblingSequence());
            mTimeslots = List.of(timeslot);
        }

        @Override public List<Timeslot> getTimeslots() { return mTimeslots; }
    }
}
