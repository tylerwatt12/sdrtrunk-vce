/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LCMotorolaTalkerAliasAssemblerTest
{
    private static final String HEADER = "15901BBD0701004955";
    private static final String[] BLOCKS = {
        "1790014BEE002AEE67", "179002485283ED1081", "1790034E33C03E9B3E",
        "179004435647DE0C00", "1790054C8A83E351E4", "1790064079F592CF37",
        "179007494B30000000"
    };

    @Test
    void orphanBodyCannotBeReplayedByALaterHeaderWithTheSameSequence()
    {
        LCMotorolaTalkerAliasAssembler assembler = new LCMotorolaTalkerAliasAssembler();
        for(int x = 0; x < BLOCKS.length; x++)
            assertFalse(assembler.add(block(BLOCKS[x]), 1_000 + x));
        assertFalse(assembler.add(header(), 2_000));
        assertTrue(addAllBlocks(assembler, 2_100).isValid());
    }

    @Test
    void everyHeaderStartsANewBodyEvenWhenItsSequenceRepeats()
    {
        LCMotorolaTalkerAliasAssembler assembler = new LCMotorolaTalkerAliasAssembler();
        assembler.add(header(), 1_000);
        for(int x = 0; x < 3; x++) assembler.add(block(BLOCKS[x]), 1_100 + x);
        assertFalse(assembler.add(header(), 2_000));
        for(int x = 3; x < BLOCKS.length; x++)
            assertFalse(assembler.add(block(BLOCKS[x]), 2_100 + x));
        for(int x = 0; x < 2; x++)
            assertFalse(assembler.add(block(BLOCKS[x]), 2_200 + x));
        assertTrue(assembler.add(block(BLOCKS[2]), 2_300));
        assertTrue(assembler.assemble().isValid());
    }

    @Test
    void completedBodyIsConsumedOnceAndAFreshRepeatedSequenceRemainsValid()
    {
        LCMotorolaTalkerAliasAssembler assembler = new LCMotorolaTalkerAliasAssembler();
        for(int sequence = 0; sequence < 2; sequence++)
        {
            assertFalse(assembler.add(header(), 1_000 + sequence * 1_000));
            assertTrue(addAllBlocks(assembler, 1_100 + sequence * 1_000).isValid());
            assertThrows(IllegalStateException.class, assembler::assemble);
            assertFalse(assembler.add(block(BLOCKS[BLOCKS.length - 1]), 1_200 + sequence * 1_000));
        }
    }

    @Test
    void blocksMayBeReorderedAfterTheHeaderButCannotPredateIt()
    {
        LCMotorolaTalkerAliasAssembler assembler = new LCMotorolaTalkerAliasAssembler();
        assembler.add(header(), 2_000);
        assertFalse(assembler.add(block(BLOCKS[0]), 1_999));
        for(int x = BLOCKS.length - 1; x >= 1; x--)
            assertFalse(assembler.add(block(BLOCKS[x]), 2_100 + BLOCKS.length - x));
        assertTrue(assembler.add(block(BLOCKS[0]), 2_200));
        MotorolaTalkerAliasComplete complete = assembler.assemble();
        assertTrue(complete.isValid());
        assertEquals(0xE67852, complete.getRadio().getRadio());
    }

    @Test
    void resetDropsThePartialHeaderAndBody()
    {
        LCMotorolaTalkerAliasAssembler assembler = new LCMotorolaTalkerAliasAssembler();
        assembler.add(header(), 1_000);
        assembler.add(block(BLOCKS[0]), 1_100);
        assembler.reset();
        for(int x = 1; x < BLOCKS.length; x++)
            assertFalse(assembler.add(block(BLOCKS[x]), 1_200 + x));
        assertFalse(assembler.add(header(), 2_000));
        assertTrue(addAllBlocks(assembler, 2_100).isValid());
    }

    @Test
    void corruptedBodyStillFailsItsPayloadCrc()
    {
        LCMotorolaTalkerAliasAssembler assembler = new LCMotorolaTalkerAliasAssembler();
        assembler.add(header(), 1_000);
        CorrectedBinaryMessage corrupt = CorrectedBinaryMessage.loadHex(BLOCKS[0]);
        corrupt.flip(30);
        assembler.add(new LCMotorolaTalkerAliasDataBlock(corrupt), 1_100);
        for(int x = 1; x < BLOCKS.length - 1; x++)
            assertFalse(assembler.add(block(BLOCKS[x]), 1_200 + x));
        assertTrue(assembler.add(block(BLOCKS[BLOCKS.length - 1]), 1_300));
        assertFalse(assembler.assemble().isValid());
    }

    @Test
    void assemblesMainlineChannelLocalSequence()
    {
        LCMotorolaTalkerAliasAssembler assembler = new LCMotorolaTalkerAliasAssembler();
        assertFalse(assembler.add(header(), 1_000));

        for(int x = 0; x < BLOCKS.length - 1; x++)
        {
            assertFalse(assembler.add(block(BLOCKS[x]), 1_100 + x));
        }

        assertTrue(assembler.add(block(BLOCKS[BLOCKS.length - 1]), 1_200));
        MotorolaTalkerAliasComplete complete = assembler.assemble();
        assertNotNull(complete);
        assertTrue(complete.isValid());
        assertEquals(0xE67852, complete.getRadio().getValue());
        assertEquals(4, complete.getSequence());
    }

    @Test
    void rejectsBlocksFromAnotherSequence()
    {
        LCMotorolaTalkerAliasAssembler assembler = new LCMotorolaTalkerAliasAssembler();
        assertFalse(assembler.add(header(), 2_000));
        CorrectedBinaryMessage differentSequence = CorrectedBinaryMessage.loadHex(BLOCKS[0]);
        differentSequence.setInt(5, io.github.dsheirer.bits.IntField.length4(24));
        assertFalse(assembler.add(new LCMotorolaTalkerAliasDataBlock(differentSequence), 2_100));

        for(int x = 1; x < BLOCKS.length; x++)
        {
            assertFalse(assembler.add(block(BLOCKS[x]), 2_200 + x));
        }
    }

    private static LCMotorolaTalkerAliasHeader header()
    {
        return new LCMotorolaTalkerAliasHeader(CorrectedBinaryMessage.loadHex(HEADER));
    }

    private static MotorolaTalkerAliasComplete addAllBlocks(LCMotorolaTalkerAliasAssembler assembler, long timestamp)
    {
        for(int x = 0; x < BLOCKS.length - 1; x++)
            assertFalse(assembler.add(block(BLOCKS[x]), timestamp + x));
        assertTrue(assembler.add(block(BLOCKS[BLOCKS.length - 1]), timestamp + BLOCKS.length));
        return assembler.assemble();
    }

    private static LCMotorolaTalkerAliasDataBlock block(String hex)
    {
        return new LCMotorolaTalkerAliasDataBlock(CorrectedBinaryMessage.loadHex(hex));
    }
}
