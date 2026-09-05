/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25IncompleteRadioIdentifier;
import org.junit.jupiter.api.Test;

class UnitRegistrationResponseAbbreviatedTest
{
    @Test
    void doesNotInventAZeroWacnForAnAbbreviatedRegistration()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(0x954, IntField.length12(20));
        message.setInt(831_102, IntField.length24(32));
        message.setInt(0xFFFD26, IntField.length24(56));
        UnitRegistrationResponseAbbreviated response = new UnitRegistrationResponseAbbreviated(message, 0);

        Identifier<?> incomplete = response.getTargetAddress();
        assertInstanceOf(APCO25IncompleteRadioIdentifier.class, incomplete);
        assertEquals(0xFFFD26, incomplete.getValue());

        APCO25FullyQualifiedRadioIdentifier complete = assertInstanceOf(
            APCO25FullyQualifiedRadioIdentifier.class, response.getTargetAddress(0xBEE00));
        assertEquals(0xFFFD26, complete.getValue());
        assertEquals(0xBEE00, complete.getWacn());
        assertEquals(0x954, complete.getSystem());
        assertEquals(831_102, complete.getRadio());
    }
}
