/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.preference.identifier.talkgroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.preference.identifier.IntegerFormat;
import org.junit.jupiter.api.Test;

class APCO25TalkgroupFormatterTest
{
    @Test
    void canonicalSubscriberIsPrimaryAndWorkingIdIsSeparate()
    {
        APCO25FullyQualifiedRadioIdentifier roamed =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                501, 0xBEE00, 0x348, 2_115_288);

        assertEquals("BEE00.348.2115288 (Working ID 501)",
            APCO25TalkgroupFormatter.format(roamed, IntegerFormat.FORMATTED, true));
        assertEquals("BEE00.348.2115288 (Working ID 501)",
            APCO25TalkgroupFormatter.format(roamed, IntegerFormat.DECIMAL, false));
    }

    @Test
    void identityOnlySubscriberDoesNotInventAnIdenticalWorkingId()
    {
        APCO25FullyQualifiedRadioIdentifier nativeSubscriber =
            APCO25FullyQualifiedRadioIdentifier.createFrom(2_115_288, 0xBEE00, 0x348, 2_115_288);

        assertEquals("BEE00.348.2115288",
            APCO25TalkgroupFormatter.format(nativeSubscriber, IntegerFormat.FORMATTED, false));
        assertFalse(nativeSubscriber.hasExplicitWorkingAddress());
        assertNull(nativeSubscriber.getWorkingAddress());
        assertEquals("BEE00.348.2115288", nativeSubscriber.toString());
    }

    @Test
    void explicitEqualWorkingAddressRemainsVisible()
    {
        APCO25FullyQualifiedRadioIdentifier nativeSubscriber =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                2_115_288, 0xBEE00, 0x348, 2_115_288);

        assertEquals("BEE00.348.2115288 (Working ID 2115288)",
            APCO25TalkgroupFormatter.format(nativeSubscriber, IntegerFormat.FORMATTED, false));
        assertTrue(nativeSubscriber.hasExplicitWorkingAddress());
        assertEquals(2_115_288, nativeSubscriber.getWorkingAddress());
        assertEquals("BEE00.348.2115288 (Working ID 2115288)", nativeSubscriber.toString());
    }

    @Test
    void numericPreferenceNeverChangesCanonicalSubscriberPresentation()
    {
        APCO25FullyQualifiedRadioIdentifier roamed =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                501, 0xBEE00, 0x348, 2_115_288);

        assertEquals("BEE00.348.2115288 (Working ID 501)",
            APCO25TalkgroupFormatter.format(roamed, IntegerFormat.HEXADECIMAL, true));
    }

    @Test
    void explicitReservedAddressIsNotPresentedAsAWorkingSubscriber()
    {
        APCO25FullyQualifiedRadioIdentifier reserved =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                0xFFFFFD, 0xBEE00, 0x348, 2_115_288);

        assertTrue(reserved.hasExplicitWorkingAddress());
        assertNull(reserved.getWorkingAddress());
        assertEquals("BEE00.348.2115288",
            APCO25TalkgroupFormatter.format(reserved, IntegerFormat.FORMATTED, false));
    }
}
