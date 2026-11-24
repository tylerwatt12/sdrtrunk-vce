/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.l3harris;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class L3HarrisTalkerAliasTest
{
    @Test
    void allFourteenCharactersSurviveAtEveryStructureOffset()
    {
        for(int offset: new int[]{0, 8, 32})
        {
            var alias = structure(offset, 17, "ABCDEFGHIJKLMN", offset + 136);
            assertEquals("ABCDEFGHIJKLMN", alias.getAlias().getValue());
            assertEquals(1, alias.getIdentifiers().size());
        }
    }

    @Test
    void nullAndLegacySpacePaddingDoNotRemoveInteriorOrLeadingSpaces()
    {
        assertEquals(" UNIT  42", structure(8, 17, " UNIT  42", 144).getAlias().getValue());
        assertEquals(" UNIT  42", structure(8, 17, " UNIT  42     ", 144).getAlias().getValue());
        assertEquals("LEGACY", structure(8, 9, "LEGACY", 80).getAlias().getValue());
    }

    @Test
    void truncatedMissingAndOversizedDeclaredStructuresProduceNoNameOrIdentifier()
    {
        for(var alias: new L3HarrisTalkerAlias[]{
            structure(8, 17, "ABCDEFGHIJKLM", 136),
            structure(8, 3, "", 32),
            structure(8, 18, "ABCDEFGHIJKLMN", 152),
            new L3HarrisTalkerAlias(new CorrectedBinaryMessage(16), 0)})
        {
            assertNull(alias.getAlias());
            assertTrue(alias.getIdentifiers().isEmpty());
        }
    }

    @Test
    void nonAsciiControlsAndTextAfterNullPaddingAreRejected()
    {
        for(int value: new int[]{0x01, 0x7F, 0x80})
        {
            var alias = structure(8, 17, "UNIT", 144);
            alias.getMessage().load(32, 8, value);
            assertNull(alias.getAlias());
        }
        var afterNull = structure(8, 17, "UNIT", 144);
        afterNull.getMessage().load(32 + 6 * 8, 8, 'X');
        assertNull(afterNull.getAlias());
    }

    private static L3HarrisTalkerAlias structure(int offset, int length, String text, int size)
    {
        var bits = new CorrectedBinaryMessage(size);
        bits.load(offset, 8, 0xA8);
        bits.load(offset + 8, 8, 0xA4);
        bits.load(offset + 16, 8, length);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        for(int index = 0; index < bytes.length && offset + 24 + (index + 1) * 8 <= size; index++)
            bits.load(offset + 24 + index * 8, 8, Byte.toUnsignedInt(bytes[index]));
        return new L3HarrisTalkerAlias(bits, offset);
    }
}
