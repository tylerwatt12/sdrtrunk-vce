/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.dcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DCSCodeTest
{
    @Test
    void includesCompleteModernCodeSet()
    {
        assertEquals(105, DCSCode.STANDARD_CODES.size());
        assertEquals(105, DCSCode.INVERTED_CODES.size());
        assertTrue(DCSCode.STANDARD_CODES.contains(DCSCode.N212));
        assertTrue(DCSCode.INVERTED_CODES.contains(DCSCode.I212));
        assertFalse(DCSCode.N212.isInverted());
        assertTrue(DCSCode.I212.isInverted());
    }

    @Test
    void everySupportedCodeRoundTripsThroughItsTransmittedValue()
    {
        for(DCSCode code: DCSCode.values())
        {
            if(code != DCSCode.UNKNOWN)
            {
                assertTrue(DCSCode.hasValue(code.getValue()), code.name());
                assertSame(code, DCSCode.fromValue(code.getValue()), code.name());
            }
        }

        assertSame(DCSCode.UNKNOWN, DCSCode.fromValue(Integer.MIN_VALUE));
    }
}
