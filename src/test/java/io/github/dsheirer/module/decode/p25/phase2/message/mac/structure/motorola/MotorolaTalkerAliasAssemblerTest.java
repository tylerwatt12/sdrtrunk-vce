/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.CRC16;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola.MotorolaTalkerAliasComplete;
import org.junit.jupiter.api.Test;

class MotorolaTalkerAliasAssemblerTest
{
    //The CRC-valid payload from the Phase 1 regression capture, transported in the Phase 2 header/block layout.
    private static final String PAYLOAD =
        "BEE002AEE6785283ED1081E33C03E9B3E35647DE0C00C8A83E351E4079F592CF3794B3";

    @Test
    void validPayloadPreservesItsEmbeddedRadioAndPhysicalTimeslot()
    {
        assertTrue(CRC16.check(CorrectedBinaryMessage.loadHex(PAYLOAD)));
        for(int timeslot = 1; timeslot <= 2; timeslot++)
        {
            MotorolaTalkerAliasAssembler assembler = new MotorolaTalkerAliasAssembler(timeslot);
            assertFalse(assembler.add(header(), 1_000));
            MotorolaTalkerAliasComplete complete = addAllBlocks(assembler, 1_100);
            assertTrue(complete.isValid());
            assertEquals(0xE67852, complete.getRadio().getRadio());
            assertEquals(timeslot, complete.getTimeslot());
            assertEquals(4, complete.getSequence());
        }
    }

    @Test
    void orphanBodyCannotBeReplayedByALaterHeaderWithTheSameSequence()
    {
        MotorolaTalkerAliasAssembler assembler = new MotorolaTalkerAliasAssembler(1);
        for(int number = 1; number <= 3; number++)
            assertFalse(assembler.add(block(number), 1_000 + number));
        assertFalse(assembler.add(header(), 2_000));
        assertTrue(addAllBlocks(assembler, 2_100).isValid());
    }

    @Test
    void everyHeaderStartsANewBodyEvenWhenItsSequenceRepeats()
    {
        MotorolaTalkerAliasAssembler assembler = new MotorolaTalkerAliasAssembler(1);
        assembler.add(header(), 1_000);
        assembler.add(block(1), 1_100);
        assembler.add(header(), 2_000);
        assertFalse(assembler.add(block(2), 2_100));
        assertFalse(assembler.add(block(3), 2_200));
        assertTrue(assembler.add(block(1), 2_300));
        assertTrue(assembler.assemble().isValid());
    }

    @Test
    void completedBodyIsConsumedOnceAndAFreshRepeatedSequenceRemainsValid()
    {
        MotorolaTalkerAliasAssembler assembler = new MotorolaTalkerAliasAssembler(1);
        for(int repeat = 0; repeat < 2; repeat++)
        {
            assertFalse(assembler.add(header(), 1_000 + repeat * 1_000));
            assertTrue(addAllBlocks(assembler, 1_100 + repeat * 1_000).isValid());
            assertThrows(IllegalStateException.class, assembler::assemble);
            assertFalse(assembler.add(block(3), 1_200 + repeat * 1_000));
        }
    }

    @Test
    void blocksMayBeReorderedAfterTheHeaderButCannotPredateIt()
    {
        MotorolaTalkerAliasAssembler assembler = new MotorolaTalkerAliasAssembler(1);
        assembler.add(header(), 2_000);
        assertFalse(assembler.add(block(1), 1_999));
        assertFalse(assembler.add(block(3), 2_100));
        assertFalse(assembler.add(block(2), 2_200));
        assertTrue(assembler.add(block(1), 2_300));
        assertTrue(assembler.assemble().isValid());
    }

    @Test
    void separateTimeslotsAndResetCannotSharePartialBodies()
    {
        MotorolaTalkerAliasAssembler first = new MotorolaTalkerAliasAssembler(1);
        MotorolaTalkerAliasAssembler second = new MotorolaTalkerAliasAssembler(2);
        first.add(header(), 1_000);
        first.add(block(1), 1_100);
        second.add(header(), 1_000);
        assertFalse(second.add(block(2), 1_100));
        assertFalse(second.add(block(3), 1_200));
        first.reset();
        assertFalse(first.add(block(2), 1_300));
        assertFalse(first.add(block(3), 1_400));
        assertTrue(second.add(block(1), 1_300));
        assertTrue(second.assemble().isValid());
        first.add(header(), 2_000);
        assertTrue(addAllBlocks(first, 2_100).isValid());
    }

    @Test
    void foreignSequenceAndCorruptedCrcCannotBecomeAValidName()
    {
        MotorolaTalkerAliasAssembler assembler = new MotorolaTalkerAliasAssembler(1);
        assembler.add(header(), 1_000);
        CorrectedBinaryMessage foreign = blockBits(1);
        foreign.load(32, 4, 5);
        assertFalse(assembler.add(new MotorolaTalkerAliasDataBlock(foreign, 0), 1_100));
        assertFalse(assembler.add(block(2), 1_200));
        assertFalse(assembler.add(block(3), 1_300));
        CorrectedBinaryMessage corrupt = blockBits(1);
        corrupt.flip(36);
        assertTrue(assembler.add(new MotorolaTalkerAliasDataBlock(corrupt, 0), 1_400));
        assertFalse(assembler.assemble().isValid());
    }

    private static MotorolaTalkerAliasComplete addAllBlocks(MotorolaTalkerAliasAssembler assembler, long timestamp)
    {
        assertFalse(assembler.add(block(1), timestamp));
        assertFalse(assembler.add(block(2), timestamp + 1));
        assertTrue(assembler.add(block(3), timestamp + 2));
        return assembler.assemble();
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
        return new MotorolaTalkerAliasDataBlock(blockBits(number), 0);
    }

    private static CorrectedBinaryMessage blockBits(int number)
    {
        CorrectedBinaryMessage payload = CorrectedBinaryMessage.loadHex(PAYLOAD);
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(136);
        message.load(24, 8, number);
        message.load(32, 4, 4);
        int payloadOffset = 64 + (number - 1) * 100;
        for(int bit = 0; bit < 100 && payloadOffset + bit < payload.size(); bit++)
            message.set(36 + bit, payload.get(payloadOffset + bit));
        return message;
    }
}
