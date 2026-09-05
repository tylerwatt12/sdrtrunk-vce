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

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlChannelQualityRegistryTest
{
    private static final String CHANNEL_A = "00000000-0000-0000-0000-000000000001";
    private static final String CHANNEL_B = "00000000-0000-0000-0000-000000000002";
    private static final String CHANNEL_C = "00000000-0000-0000-0000-000000000003";
    private static final String CHANNEL_D = "00000000-0000-0000-0000-000000000004";
    private static final String CHANNEL_E = "00000000-0000-0000-0000-000000000005";

    @Test
    void exposesOnlyFreshActiveFiniteDecodeHealth()
    {
        AtomicLong now = new AtomicLong(10_000L);
        ControlChannelQualityRegistry registry = new ControlChannelQualityRegistry(2_000L, now::get);
        registry.receive(quality(CHANNEL_A, 9_000L, true, 91.5d));
        registry.receive(quality(CHANNEL_B, 7_999L, true, 99.0d));
        registry.receive(quality(CHANNEL_C, 9_900L, false, 100.0d));
        registry.receive(quality(CHANNEL_D, 9_900L, true, null));
        registry.receive(quality(CHANNEL_E, 10_001L, true, 100.0d));

        assertEquals(91.5d, registry.getDecodeHealthPercent(CHANNEL_A).orElseThrow());
        assertTrue(registry.getDecodeHealthPercent(CHANNEL_B).isEmpty());
        assertTrue(registry.getDecodeHealthPercent(CHANNEL_C).isEmpty());
        assertTrue(registry.getDecodeHealthPercent(CHANNEL_D).isEmpty());
        assertTrue(registry.getDecodeHealthPercent(CHANNEL_E).isEmpty());
        assertTrue(registry.getDecodeHealthPercent("00000000-0000-0000-0000-000000000099").isEmpty());
    }

    @Test
    void olderConcurrentObservationCannotReplaceNewerSiteState()
    {
        AtomicLong now = new AtomicLong(10_000L);
        ControlChannelQualityRegistry registry = new ControlChannelQualityRegistry(2_000L, now::get);
        registry.receive(quality(CHANNEL_A, 9_900L, true, 75.0d));
        registry.receive(quality(CHANNEL_A, 9_800L, false, null));

        assertEquals(75.0d, registry.getDecodeHealthPercent(CHANNEL_A).orElseThrow());
        assertEquals(1, registry.size());

        registry.receive(quality(CHANNEL_A, 9_901L, false, null));
        assertTrue(registry.getDecodeHealthPercent(CHANNEL_A).isEmpty());

        registry.clear();
        assertEquals(0, registry.size());
    }

    @Test
    void invalidIdentityTimestampAndHealthNeverBecomeElectionData()
    {
        AtomicLong now = new AtomicLong(10_000L);
        ControlChannelQualityRegistry registry = new ControlChannelQualityRegistry(2_000L, now::get);
        registry.receive(quality(" ", 9_900L, true, 90.0d));
        registry.receive(quality(CHANNEL_A, 0L, true, 90.0d));
        registry.receive(quality(CHANNEL_B, 9_900L, true, Double.NaN));
        registry.receive(quality(CHANNEL_C, 9_900L, true, 101.0d));

        assertEquals(2, registry.size());
        assertTrue(registry.getDecodeHealthPercent(CHANNEL_B).isEmpty());
        assertTrue(registry.getDecodeHealthPercent(CHANNEL_C).isEmpty());
    }

    private static ControlChannelQualitySnapshot quality(String configurationId, long observedAt, boolean active,
                                                         Double decodeHealth)
    {
        return new ControlChannelQualitySnapshot(null, configurationId, 851_012_500L, observedAt, active, -45.0d, -46.0d,
            -50.0d, -40.0d, decodeHealth, 100L, 1L, 0L, 0L, 0L, observedAt);
    }
}
