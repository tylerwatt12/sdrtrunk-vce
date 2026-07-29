/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.SynchronizationBroadcast;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class P25P1SynchronizationBroadcastTest
{
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
