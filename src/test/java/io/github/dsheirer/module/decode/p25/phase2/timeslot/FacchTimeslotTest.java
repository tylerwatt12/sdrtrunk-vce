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

package io.github.dsheirer.module.decode.p25.phase2.timeslot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructureFailedRS;
import java.util.List;
import org.junit.jupiter.api.Test;

class FacchTimeslotTest
{
    @Test
    void doesNotParseAnIrrecoverableReedSolomonCodeword()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(320);
        message.set(0, message.size());

        List<MacMessage> macMessages = new FacchTimeslot(message, 0, 1000L).getMacMessages();

        assertEquals(1, macMessages.size());
        assertFalse(macMessages.get(0).isValid());
        assertInstanceOf(MacStructureFailedRS.class, macMessages.get(0).getMacStructure());
    }
}
