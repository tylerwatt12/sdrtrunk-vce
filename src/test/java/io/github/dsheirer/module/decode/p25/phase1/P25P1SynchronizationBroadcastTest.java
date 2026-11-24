/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SynchronizationBroadcast;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class P25P1SynchronizationBroadcastTest
{
    //TIA-102.AABC-E (April 2019), 6.2.33 and Figure 6.2.33-1.
    //Constructed fields use zero-based MSB-first TSBK positions independently of decoder flag constants.
    @Test
    void appliesOnlyValidOffsetsWithoutChangingTheUtcTimestamp()
    {
        long utc = Instant.parse("2026-07-29T12:34:00.600Z").toEpochMilli();

        for(int halfHours: new int[]{0, 1, -1, -10, 11, -31, 31})
        {
            for(boolean valid: new boolean[]{false, true})
            {
                SynchronizationBroadcast message = synchronizationBroadcast(7, 29, 80);
                message.getMessage().set(33, valid);
                message.getMessage().set(34, halfHours < 0);
                message.getMessage().setInt(Math.abs(halfHours), IntField.range(35, 39));

                assertEquals(valid, message.isValidLocalTimeOffset());
                assertEquals(valid ? halfHours * 1_800_000 : 0, message.getTimeZone().getRawOffset());
                assertEquals(utc, message.getSystemTime());
            }
        }
    }

    @Test
    void formatsTheBroadcastTimeUsingOnlyTheAdvertisedValidOffset()
    {
        SynchronizationBroadcast message = synchronizationBroadcast(7, 29, 80);
        message.getMessage().set(34);
        message.getMessage().setInt(10, IntField.range(35, 39));
        assertTrue(message.toString().contains("2026-07-29 12:34:00.600 +0000"));

        message.getMessage().set(33);
        assertTrue(message.toString().contains("2026-07-29 07:34:00.600 -0500"));

        message.getMessage().clear(34);
        message.getMessage().setInt(11, IntField.range(35, 39));
        assertTrue(message.toString().contains("2026-07-29 18:04:00.600 +0530"));
    }

    @Test
    void reportsTrafficChannelSynchronizationIndependentlyOfClockAndRolloverFlags()
    {
        for(boolean unsynchronized: new boolean[]{false, true})
        {
            for(boolean unreliableTime: new boolean[]{false, true})
            {
                for(boolean unlockedRollover: new boolean[]{false, true})
                {
                    SynchronizationBroadcast message = synchronizationBroadcast(7, 29, 80);
                    message.getMessage().set(28, unsynchronized);
                    message.getMessage().set(29, unreliableTime);
                    message.getMessage().set(30, unlockedRollover);

                    assertEquals(unsynchronized, message.isTdmaUnsynchronized());
                    assertEquals(unreliableTime, message.isSystemTimeNotLockedToExternalReference());
                    assertEquals(!unlockedRollover, message.isMicroslotsLockedToMinuteRollover());
                    assertEquals(Instant.parse("2026-07-29T12:34:00.600Z").toEpochMilli(), message.getSystemTime());
                    assertEquals(unsynchronized, message.toString().contains("TDMA CHANNELS:NOT SYNCHRONIZED"));
                    assertEquals(unreliableTime, message.toString().contains("SYSTEM TIME UNLOCKED"));
                    assertEquals(unlockedRollover, message.toString().contains("ROLLOVER:UNLOCKED"));
                }
            }
        }
    }

    @Test
    void preservesUnsetCalendarAndCounterResetWithoutTreatingThemAsCivilTime()
    {
        SynchronizationBroadcast before = synchronizationBroadcast(0, 0, 7990);
        before.getMessage().setInt(0, IntField.range(40, 46));
        before.getMessage().setInt(0, IntField.range(56, 60));
        before.getMessage().setInt(2, IntField.range(61, 66));
        SynchronizationBroadcast after = synchronizationBroadcast(0, 0, 70);
        after.getMessage().setInt(0, IntField.range(40, 46));
        after.getMessage().setInt(0, IntField.range(56, 60));
        after.getMessage().setInt(0, IntField.range(61, 66));

        P25P1NetworkConfigurationMonitor monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM);
        for(SynchronizationBroadcast message: new SynchronizationBroadcast[]{before, after})
        {
            assertFalse(message.hasValidDate());
            assertThrows(IllegalStateException.class, message::getSystemTime);
            assertTrue(message.isSystemTimeNotLockedToExternalReference());
            assertTrue(message.isMicroslotsLockedToMinuteRollover());
            assertFalse(message.isValidLocalTimeOffset());
            assertFalse(message.isTdmaUnsynchronized());
            assertTrue(message.toString().contains("SYSTEM TIME UNLOCKED: INVALID DATE"));
            assertFalse(message.toString().contains("NOT SYNCHRONIZED"));
            P25NetworkConfigurationSnapshot observation = monitor.process(message);
            assertNull(observation.siteStatus().broadcastClockEpochMilliseconds());
            assertEquals(message.getMicroSlots(), observation.siteStatus().microSlots());
        }
        assertNull(monitor.getSnapshot().siteStatus().broadcastClockEpochMilliseconds());
        assertEquals(70, monitor.getSnapshot().siteStatus().microSlots());

        after.getMessage().set(28);
        assertTrue(after.toString().contains("INVALID DATE"));
        assertTrue(after.toString().contains("TDMA CHANNELS:NOT SYNCHRONIZED"));
    }

    @Test
    void acceptsUnlockedValidDateAndRejectsInvalidDateWithoutLosingMicroslots()
    {
        SynchronizationBroadcast valid = synchronizationBroadcast(7, 29, 80);
        assertTrue(valid.hasValidDate());
        assertTrue(valid.isSystemTimeNotLockedToExternalReference());
        assertEquals(Instant.parse("2026-07-29T12:34:00.600Z").toEpochMilli(), valid.getSystemTime());
        assertTrue(valid.toString().contains("MICROSLOT-MINUTE ROLLOVER:LOCKED"));

        assertFalse(synchronizationBroadcast(0, 29, 81).hasValidDate());
        assertFalse(synchronizationBroadcast(13, 29, 82).hasValidDate());
        assertFalse(synchronizationBroadcast(7, 0, 83).hasValidDate());
        assertTrue(synchronizationBroadcast(12, 31, 84).hasValidDate());

        P25P1NetworkConfigurationMonitor monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM);
        monitor.process(valid);
        P25NetworkConfigurationSnapshot invalidObservation =
            monitor.process(synchronizationBroadcast(0, 0, 95));

        assertNull(invalidObservation.siteStatus().broadcastClockEpochMilliseconds());
        assertEquals(95, invalidObservation.siteStatus().microSlots());
        assertNull(monitor.getSnapshot().siteStatus().broadcastClockEpochMilliseconds());
        assertEquals(95, monitor.getSnapshot().siteStatus().microSlots());
    }

    @Test
    void rejectsImpossibleCalendarAndTimingFieldsWithoutCalendarNormalization()
    {
        CorrectedBinaryMessage message = synchronizationBroadcast(2, 28, 7999).getMessage();
        message.setInt(31, IntField.range(51, 55));
        assertFalse((new SynchronizationBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x659, 1_000L)).hasValidDate());
        message.setInt(29, IntField.range(51, 55));
        assertFalse((new SynchronizationBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x659, 1_000L)).hasValidDate());
        message.setInt(24, IntField.range(40, 46));
        assertTrue((new SynchronizationBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x659, 1_000L)).hasValidDate());
        message.setInt(31, IntField.range(56, 60));
        assertFalse((new SynchronizationBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x659, 1_000L)).hasValidDate());
        message.setInt(23, IntField.range(56, 60));
        message.setInt(63, IntField.range(61, 66));
        assertFalse((new SynchronizationBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x659, 1_000L)).hasValidDate());
        message.setInt(59, IntField.range(61, 66));
        message.setInt(8000, IntField.range(67, 79));
        assertFalse((new SynchronizationBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x659, 1_000L)).hasValidDate());
        assertFalse((new SynchronizationBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x659, 1_000L)).hasValidMicroSlots());
    }

    private static SynchronizationBroadcast synchronizationBroadcast(int month, int day, int microSlots)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(48, IntField.length6(2));
        message.set(SynchronizationBroadcast.SYSTEM_TIME_NOT_LOCKED_TO_EXTERNAL_REFERENCE_FLAG);
        message.setInt(26, IntField.range(40, 46));
        message.setInt(month, IntField.length4(47));
        message.setInt(day, IntField.range(51, 55));
        message.setInt(12, IntField.range(56, 60));
        message.setInt(34, IntField.length6(61));
        message.setInt(microSlots, IntField.range(67, 79));
        return new SynchronizationBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, 0x659, 1_000L);
    }
}
