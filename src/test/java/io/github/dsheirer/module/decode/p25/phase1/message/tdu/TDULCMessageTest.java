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

package io.github.dsheirer.module.decode.p25.phase1.message.tdu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import org.junit.jupiter.api.Test;

class TDULCMessageTest
{
    @Test
    void rejectsShortenedGolayPayloadWithoutReadingAPartialCodeword()
    {
        TDULCMessage message = new TDULCMessage(new CorrectedBinaryMessage(276), 0x293, 1_000L);

        assertDoesNotThrow(message::getLinkControlWord);
        assertFalse(message.getLinkControlWord().isValid());
    }
}
