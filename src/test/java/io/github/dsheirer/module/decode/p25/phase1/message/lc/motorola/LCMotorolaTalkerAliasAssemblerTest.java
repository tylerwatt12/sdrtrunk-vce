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

    private static LCMotorolaTalkerAliasDataBlock block(String hex)
    {
        return new LCMotorolaTalkerAliasDataBlock(CorrectedBinaryMessage.loadHex(hex));
    }
}
