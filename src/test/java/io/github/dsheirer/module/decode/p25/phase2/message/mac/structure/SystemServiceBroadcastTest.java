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
    void decodesTheFiniteEndpointsAndNoExpiryAtTheMacOffset()
    {
        int offset = 16;
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        SystemServiceBroadcast broadcast = new SystemServiceBroadcast(message, offset);
        int[] received = {0, 1, 254, 255};
        int[] minutes = {240, 270, 7_860, 0};
        String[] descriptions = {"4:00 HOURS", "4:30 HOURS", "131:00 HOURS", " NO EXPIRY"};
        for(int index = 0; index < received.length; index++)
        {
            message.setInt(received[index], IntField.length8(8 + offset));
            assertEquals(minutes[index], broadcast.getTemporaryWUIDValidityMinutes());
            assertEquals("ROAMING WUIDS VALID FOR:" + descriptions[index], broadcast.getTemporaryWUIDValidity());
        }
    }
}
