/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import org.junit.jupiter.api.Test;

class SystemServiceBroadcastTest
{
    @Test
    void keepsNoExpiryDistinctFromAnAbsentLease()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(80);
        SystemServiceBroadcast broadcast = new SystemServiceBroadcast(message, 0);
        assertEquals(0, broadcast.getTemporaryWUIDValidityMinutes());

        message.setInt(1, IntField.length8(8));
        assertEquals(270, broadcast.getTemporaryWUIDValidityMinutes());
    }
}
