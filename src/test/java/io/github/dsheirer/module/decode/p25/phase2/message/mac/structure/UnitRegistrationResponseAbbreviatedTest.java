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

    /** TIA-102.AABC-B Sections 2.3.27 and 2.3.30: zero means no unit; FFFFFC is assignable; FFFFFD is reserved. */
    @Test
    void preservesWorkingUnitIdAssignmentBoundariesWithoutInventingARegistrationTarget()
    {
        assertWorkingUnitId(0, false);
        assertWorkingUnitId(0xFFFFFC, true);
        assertWorkingUnitId(0xFFFFFD, false);
    }

    private static void assertWorkingUnitId(int workingUnitId, boolean canonical)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(0x954, IntField.length12(20));
        message.setInt(831_102, IntField.length24(32));
        message.setInt(workingUnitId, IntField.length24(56));
        UnitRegistrationResponseAbbreviated response = new UnitRegistrationResponseAbbreviated(message, 0);

        assertEquals(workingUnitId, response.getSourceAddress());
        Identifier<?> observed = response.getTargetAddress();
        assertInstanceOf(APCO25IncompleteRadioIdentifier.class, observed);
        assertEquals(workingUnitId, observed.getValue());

        Identifier<?> resolved = response.getTargetAddress(0xBEE00);
        if(canonical)
        {
            APCO25FullyQualifiedRadioIdentifier complete = assertInstanceOf(
                APCO25FullyQualifiedRadioIdentifier.class, resolved);
            assertEquals(workingUnitId, complete.getValue());
            assertEquals(831_102, complete.getRadio());
        }
        else
        {
            assertInstanceOf(APCO25IncompleteRadioIdentifier.class, resolved);
            assertEquals(workingUnitId, resolved.getValue());
        }
    }
}
