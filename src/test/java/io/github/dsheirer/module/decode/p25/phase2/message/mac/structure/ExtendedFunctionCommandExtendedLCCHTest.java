/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import org.junit.jupiter.api.Test;

/** TIA-102.BBAC-1 section 8.3.1.21, Figure 8-43. */
class ExtendedFunctionCommandExtendedLCCHTest
{
    @Test
    void decodesTheCompleteFinalSourceSuidField()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(144);
        bits.setInt(765_432, IntField.length24(56));
        bits.setInt(0xBEE00, IntField.length20(80));
        bits.setInt(0x348, IntField.length12(100));
        bits.setInt(2_115_288, IntField.length24(112));
        ExtendedFunctionCommandExtendedLCCH command = new ExtendedFunctionCommandExtendedLCCH(bits, 0);

        APCO25FullyQualifiedRadioIdentifier source = assertInstanceOf(
            APCO25FullyQualifiedRadioIdentifier.class, command.getSourceSuid());
        assertEquals(2_115_288, source.getValue());
        assertEquals(0xBEE00, source.getWacn());
        assertEquals(0x348, source.getSystem());
        assertEquals(2_115_288, source.getRadio());
        assertEquals(0xBEE00, command.getSourceWacn().getValue());
        assertEquals(0x348, command.getSourceSystem().getValue());
        assertEquals(2, command.getIdentifiers().size());
        assertSame(source, command.getIdentifiers().get(1));
    }
}
