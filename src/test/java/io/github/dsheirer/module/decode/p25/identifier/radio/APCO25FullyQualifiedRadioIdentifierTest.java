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
package io.github.dsheirer.module.decode.p25.identifier.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class APCO25FullyQualifiedRadioIdentifierTest
{
    @Test
    void canonicalSubscriberIsPrimaryAndWorkingIdIsSeparate()
    {
        APCO25FullyQualifiedRadioIdentifier roamed =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                501, 0xBEE00, 0x348, 2_115_288);

        assertEquals("BEE00.348.2115288 (Working ID 501)",
            roamed.toString());
    }

    @Test
    void identityOnlySubscriberDoesNotInventAnIdenticalWorkingId()
    {
        APCO25FullyQualifiedRadioIdentifier nativeSubscriber =
            APCO25FullyQualifiedRadioIdentifier.createFrom(2_115_288, 0xBEE00, 0x348, 2_115_288);

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

        assertTrue(nativeSubscriber.hasExplicitWorkingAddress());
        assertEquals(2_115_288, nativeSubscriber.getWorkingAddress());
        assertEquals("BEE00.348.2115288 (Working ID 2115288)", nativeSubscriber.toString());
    }

    @Test
    void explicitReservedAddressIsNotPresentedAsAWorkingSubscriber()
    {
        APCO25FullyQualifiedRadioIdentifier reserved =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                0xFFFFFD, 0xBEE00, 0x348, 2_115_288);

        assertTrue(reserved.hasExplicitWorkingAddress());
        assertNull(reserved.getWorkingAddress());
        assertEquals("BEE00.348.2115288", reserved.toString());
    }
}
