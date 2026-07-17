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
package io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HarrisTalkerAliasAssemblerTest
{
    @Test
    void emitsEachTwoBlockAliasOnlyOnce()
    {
        HarrisTalkerAliasAssembler assembler = new HarrisTalkerAliasAssembler();
        assertNull(assembler.process(block1("32A443445020233032"), 1_000L));

        LCHarrisTalkerAliasComplete complete = assembler.process(block2("33A433312020202020"), 1_500L);

        assertNotNull(complete);
        assertEquals("CDP #0231", complete.getTalkerAlias().getValue());
        assertNull(assembler.process(block3("34A443445020233032"), 2_000L));
        assertNull(assembler.process(block4("35A433312020202020"), 2_500L));

        assertNull(assembler.process(block1("32A44E455720202020"), 3_000L));
        assertNotNull(assembler.process(block2("33A4414C4941532020"), 3_500L));
    }

    private static LCHarrisTalkerAliasBlock1 block1(String hex)
    {
        LCHarrisTalkerAliasBlock1 block = new LCHarrisTalkerAliasBlock1(CorrectedBinaryMessage.loadHex(hex));
        block.setValid(true);
        return block;
    }

    private static LCHarrisTalkerAliasBlock2 block2(String hex)
    {
        LCHarrisTalkerAliasBlock2 block = new LCHarrisTalkerAliasBlock2(CorrectedBinaryMessage.loadHex(hex));
        block.setValid(true);
        return block;
    }

    private static LCHarrisTalkerAliasBlock3 block3(String hex)
    {
        LCHarrisTalkerAliasBlock3 block = new LCHarrisTalkerAliasBlock3(CorrectedBinaryMessage.loadHex(hex));
        block.setValid(true);
        return block;
    }

    private static LCHarrisTalkerAliasBlock4 block4(String hex)
    {
        LCHarrisTalkerAliasBlock4 block = new LCHarrisTalkerAliasBlock4(CorrectedBinaryMessage.loadHex(hex));
        block.setValid(true);
        return block;
    }
}
