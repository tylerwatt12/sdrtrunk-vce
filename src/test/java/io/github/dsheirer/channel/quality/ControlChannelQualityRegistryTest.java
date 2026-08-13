/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.channel.quality;

import io.github.dsheirer.controller.channel.Channel;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlChannelQualityRegistryTest
{
    @Test
    void exposesOnlyFreshActiveFiniteDecodeHealth()
    {
        AtomicLong now = new AtomicLong(10_000L);
        ControlChannelQualityRegistry registry = new ControlChannelQualityRegistry(2_000L, now::get);
        registry.receive(quality("fresh", 9_000L, true, 91.5d));
        registry.receive(quality("stale", 7_999L, true, 99.0d));
        registry.receive(quality("inactive", 9_900L, false, 100.0d));
        registry.receive(quality("missing-health", 9_900L, true, null));
        registry.receive(quality("future", 10_001L, true, 100.0d));

        assertEquals(91.5d, registry.getDecodeHealthPercent("fresh").orElseThrow());
        assertTrue(registry.getDecodeHealthPercent("stale").isEmpty());
        assertTrue(registry.getDecodeHealthPercent("inactive").isEmpty());
        assertTrue(registry.getDecodeHealthPercent("missing-health").isEmpty());
        assertTrue(registry.getDecodeHealthPercent("future").isEmpty());
        assertTrue(registry.getDecodeHealthPercent("unknown").isEmpty());
    }

    @Test
    void olderConcurrentObservationCannotReplaceNewerSiteState()
    {
        AtomicLong now = new AtomicLong(10_000L);
        ControlChannelQualityRegistry registry = new ControlChannelQualityRegistry(2_000L, now::get);
        registry.receive(quality("site-a", 9_900L, true, 75.0d));
        registry.receive(quality("site-a", 9_800L, false, null));

        assertEquals(75.0d, registry.getDecodeHealthPercent("site-a").orElseThrow());
        assertEquals(1, registry.size());

        registry.receive(quality("site-a", 9_901L, false, null));
        assertTrue(registry.getDecodeHealthPercent("site-a").isEmpty());

        registry.clear();
        assertEquals(0, registry.size());
    }

    @Test
    void invalidIdentityTimestampAndHealthNeverBecomeElectionData()
    {
        AtomicLong now = new AtomicLong(10_000L);
        ControlChannelQualityRegistry registry = new ControlChannelQualityRegistry(2_000L, now::get);
        registry.receive(quality(" ", 9_900L, true, 90.0d));
        registry.receive(quality("zero-time", 0L, true, 90.0d));
        registry.receive(quality("nan", 9_900L, true, Double.NaN));
        registry.receive(quality("too-high", 9_900L, true, 101.0d));

        assertEquals(2, registry.size());
        assertTrue(registry.getDecodeHealthPercent("nan").isEmpty());
        assertTrue(registry.getDecodeHealthPercent("too-high").isEmpty());
    }

    @Test
    void diagnosticProjectionIsBoundedAndExcludesMutableChannelObject()
    {
        ControlChannelQualityRegistry registry = new ControlChannelQualityRegistry();

        for(int x = 0; x < 30; x++)
        {
            registry.receive(quality(String.format("site-%02d", x), 9_000L + x, true, 90.0d));
        }

        ControlChannelQualityRegistry.DiagnosticQualityBatch batch = registry.getDiagnosticSnapshots(4);
        assertEquals(30, batch.totalCount());
        assertEquals(4, batch.snapshots().size());
        assertTrue(batch.snapshots().stream().allMatch(snapshot -> snapshot.frequencyHz() == 851_012_500L));
        assertFalse(batch.snapshots().stream().anyMatch(snapshot -> snapshot.siteIdentity().isBlank()));
        assertTrue(java.util.Arrays.stream(
                ControlChannelQualityRegistry.DiagnosticQualitySnapshot.class.getRecordComponents())
            .noneMatch(component -> component.getType() == Channel.class));
        assertThrows(IllegalArgumentException.class, () -> registry.getDiagnosticSnapshots(-1));
    }

    private static ControlChannelQualitySnapshot quality(String guid, long observedAt, boolean active,
                                                         Double decodeHealth)
    {
        return new ControlChannelQualitySnapshot(null, guid, 851_012_500L, observedAt, active, -45.0d, -46.0d,
            -50.0d, -40.0d, decodeHealth, 100L, 1L, 0L, 0L, 0L, observedAt);
    }
}
