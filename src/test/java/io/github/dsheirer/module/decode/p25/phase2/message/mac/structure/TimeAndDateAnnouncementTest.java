/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.phase2.P25P2NetworkConfigurationMonitor;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessageFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeAndDateAnnouncementTest
{
    @Test
    void decodesNegativeLocalTimeOffset()
    {
        TimeAndDateAnnouncement announcement = announcement(2026, 7, 31, 10, 15, 0);
        int offset = announcement.getOffset();
        announcement.getMessage().set(12 + offset);
        announcement.getMessage().setInt(240, IntField.range(13 + offset, 23 + offset));

        assertEquals(OffsetDateTime.of(2026, 7, 31, 10, 15, 0, 0, ZoneOffset.ofHours(-4)),
            announcement.getDateAndTime());
    }

    @Test
    void ignoresReservedBitBeforeLocalTimeOffset()
    {
        TimeAndDateAnnouncement announcement = announcement(2026, 7, 31, 10, 15, 0);
        int offset = announcement.getOffset();
        announcement.getMessage().set(11 + offset);
        announcement.getMessage().setInt(330, IntField.range(13 + offset, 23 + offset));

        assertEquals(OffsetDateTime.of(2026, 7, 31, 10, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)),
            announcement.getDateAndTime());
    }

    @Test
    void rejectsMalformedDateAndOffsetWithoutPublishingClock()
    {
        TimeAndDateAnnouncement impossibleDate = announcement(2026, 2, 30, 10, 15, 0);
        assertNull(impossibleDate.getDateAndTime());
        assertTrue(impossibleDate.toString().contains("INVALID DATE/TIME"));

        TimeAndDateAnnouncement invalidOffset = announcement(2026, 7, 31, 10, 15, 0);
        int offset = invalidOffset.getOffset();
        invalidOffset.getMessage().setInt(1081, IntField.range(13 + offset, 23 + offset));
        assertNull(invalidOffset.getDateAndTime());
        assertTrue(invalidOffset.toString().contains("INVALID DATE/TIME"));

        MacMessage macMessage = new MacMessage(1, DataUnitID.UNSCRAMBLED_LCCH, invalidOffset.getMessage(), 1_000L,
            invalidOffset);
        assertNull(new P25P2NetworkConfigurationMonitor().processMacMessage(macMessage));
    }

    private static TimeAndDateAnnouncement announcement(int year, int month, int day, int hours, int minutes,
                                                         int seconds)
    {
        int offset = MacMessageFactory.DEFAULT_MAC_STRUCTURE_INDEX;
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(117, IntField.length8(offset));
        message.set(8 + offset);
        message.set(9 + offset);
        message.set(10 + offset);
        message.setInt(month, IntField.range(24 + offset, 27 + offset));
        message.setInt(day, IntField.range(28 + offset, 32 + offset));
        message.setInt(year, IntField.range(33 + offset, 45 + offset));
        message.setInt(hours, IntField.range(48 + offset, 52 + offset));
        message.setInt(minutes, IntField.range(53 + offset, 58 + offset));
        message.setInt(seconds, IntField.range(59 + offset, 64 + offset));
        return new TimeAndDateAnnouncement(message, offset);
    }
}
