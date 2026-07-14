/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeAndDateAnnouncementTest
{
    @Test
    void decodesPre2000ClockFromPhase1ControlChannel()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.set(16);
        message.set(17);
        message.load(32, 4, 11);
        message.load(36, 5, 30);
        message.load(41, 13, 1999);
        message.load(56, 5, 0);
        message.load(61, 6, 2);
        message.load(67, 6, 44);
        TimeAndDateAnnouncement announcement = new TimeAndDateAnnouncement(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x293, 0L);

        assertTrue(announcement.hasValidDate());
        assertTrue(announcement.hasValidTime());
        assertEquals(OffsetDateTime.of(1999, 11, 30, 0, 2, 44, 0, ZoneOffset.UTC),
            announcement.getDateAndTime());
    }
}
