/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HarrisTalkerAliasAssemblerTest
{
    @Test
    void eachConveyanceIndependentlySuppliesACompleteNameAndConsumesItsFirstPart()
    {
        var assembler = new HarrisTalkerAliasAssembler();
        assertNull(assembler.process(part(0x32, "ODDUNIT"), 1000));
        assertEquals("ODDUNIT1", assembler.process(part(0x33, "1"), 1200).getTalkerAlias().getValue());
        assertNull(assembler.process(part(0x33, "1"), 1250));
        assertNull(assembler.process(part(0x34, "EVENUNI"), 1300));
        assertEquals("EVENUNIT2", assembler.process(part(0x35, "T2"), 1500).getTalkerAlias().getValue());
        assertNull(assembler.process(part(0x35, "T2"), 1550));
    }

    @Test
    void repeatedHalvesAndSpacesAcrossThePartBoundaryAreLiteralText()
    {
        var assembler = new HarrisTalkerAliasAssembler();
        assembler.process(part(0x32, "ABCDEFG"), 1000);
        assertEquals("ABCDEFGABCDEFG", assembler.process(part(0x33, "ABCDEFG"), 1100).getTalkerAlias().getValue());
        assembler.process(part(0x34, "ABC DEF"), 1200);
        assertEquals("ABC DEF GHIJKL", assembler.process(part(0x35, " GHIJKL"), 1300).getTalkerAlias().getValue());
        assembler.process(part(0x32, " UNIT  "), 1400);
        assertEquals(" UNIT  2", assembler.process(part(0x33, "2      "), 1500).getTalkerAlias().getValue());
    }

    @Test
    void oddAndEvenPartsCannotBeJoinedAndOrphansCannotWaitForANewFirstPart()
    {
        var assembler = new HarrisTalkerAliasAssembler();
        assertNull(assembler.process(part(0x33, "OLDTAIL"), 900));
        assembler.process(part(0x32, "NEWHEAD"), 1000);
        assertNull(assembler.process(part(0x35, "WRONG"), 1100));
        assertEquals("NEWHEADRIGHT", assembler.process(part(0x33, "RIGHT"), 1200).getTalkerAlias().getValue());
        assertNull(assembler.process(part(0x34, "EVENNEW"), 1300));
        assertNull(assembler.process(part(0x33, "WRONG"), 1400));
        assertEquals("EVENNEWTAIL", assembler.process(part(0x35, "TAIL"), 1500).getTalkerAlias().getValue());
    }

    @Test
    void bothPairsCanBePendingAndARepeatedFirstPartReplacesOnlyItsOwnPair()
    {
        var assembler = new HarrisTalkerAliasAssembler();
        assembler.process(part(0x32, "OLDHEAD"), 1000);
        assembler.process(part(0x34, "EVENONE"), 1100);
        assembler.process(part(0x32, "NEWHEAD"), 1200);
        assertEquals("EVENONETWO", assembler.process(part(0x35, "TWO"), 1300).getTalkerAlias().getValue());
        assertEquals("NEWHEADTAIL", assembler.process(part(0x33, "TAIL"), 1400).getTalkerAlias().getValue());
    }

    @Test
    void resetInvalidProtectedExpiredAndEarlierPartsCannotCompleteAnOldPair()
    {
        for(int boundary = 0; boundary < 5; boundary++)
        {
            var assembler = new HarrisTalkerAliasAssembler();
            assembler.process(part(0x32, "OLDHEAD"), 1000);
            assembler.process(part(0x34, "EVENOLD"), 1000);
            if(boundary == 0) assembler.reset();
            if(boundary == 1)
            {
                var invalid = part(0x33, "TAIL");
                invalid.setValid(false);
                assertNull(assembler.process(invalid, 1100));
            }
            if(boundary == 2)
            {
                var protectedPart = part(0x33, "TAIL");
                protectedPart.getMessage().set(0);
                assertNull(assembler.process(protectedPart, 1100));
            }
            long timestamp = boundary == 3 ? 3001 : boundary == 4 ? 999 : 1200;
            assertNull(assembler.process(part(0x33, "TAIL"), timestamp));
            if(boundary < 3) assertNull(assembler.process(part(0x35, "TAIL"), 1200));
        }
    }

    @Test
    void nullPaddingEndsTheJoinedNameAndMalformedTextIsRejected()
    {
        var assembler = new HarrisTalkerAliasAssembler();
        assembler.process(part(0x34, "UNIT"), 1000);
        assertEquals("UNIT", assembler.process(part(0x35, ""), 1100).getTalkerAlias().getValue());
        assembler.process(part(0x32, "UNIT"), 1200);
        assertNull(assembler.process(part(0x33, "TAIL"), 1300), "text after null padding is not a second name");
        var highBit = part(0x32, "BADNAME");
        highBit.getMessage().load(16, 8, 0x80);
        assembler.process(highBit, 1400);
        assertNull(assembler.process(part(0x33, ""), 1500));
    }

    private static LCHarrisTalkerAliasBase part(int opcode, String text)
    {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        assertTrue(bytes.length <= 7);
        var bits = new CorrectedBinaryMessage(72);
        bits.load(0, 8, opcode);
        bits.load(8, 8, 0xA4);
        for(int index = 0; index < bytes.length; index++) bits.load(16 + index * 8, 8, Byte.toUnsignedInt(bytes[index]));
        LCHarrisTalkerAliasBase part = switch(opcode)
        {
            case 0x32 -> new LCHarrisTalkerAliasBlock1(bits);
            case 0x33 -> new LCHarrisTalkerAliasBlock2(bits);
            case 0x34 -> new LCHarrisTalkerAliasBlock3(bits);
            case 0x35 -> new LCHarrisTalkerAliasBlock4(bits);
            default -> throw new IllegalArgumentException();
        };
        part.setValid(true);
        return part;
    }
}
