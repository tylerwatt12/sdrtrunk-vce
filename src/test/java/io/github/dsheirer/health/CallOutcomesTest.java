/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.health;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CallOutcomesTest
{
    @Test
    void disabled_callbacks_keep_maps_and_pending_storage_absent() throws Exception
    {
        CallTally tally = new CallTally();
        tally.setEnabled(false);
        CallOutcomes calls = new CallOutcomes(tally);
        for(int index = 0; index < 100; index++)
        {
            calls.tally("call", true, CallOutcome.RECORDED, index);
            calls.unmapped("call", "target", index);
            calls.bindFrequency("call", 851_000_000, 1);
            calls.touch("call", index);
            calls.rejected("call");
            calls.resolved("call", "target", index);
        }
        var maps = CallOutcomes.class.getDeclaredField("mMaps");
        maps.setAccessible(true);
        assertNull(((java.util.concurrent.atomic.AtomicReference<?>)maps.get(calls)).get());
        assertEquals(0, tally.getStarted());
        assertEquals(0, tally.totals().losses());
    }

    @Test
    void generation_change_replaces_old_maps_without_finishing_retired_calls() throws Exception
    {
        CallTally tally = new CallTally();
        CallOutcomes calls = new CallOutcomes(tally);
        calls.tally("resource", true, CallOutcome.NO_SOURCE, 1_000);
        var field = CallOutcomes.class.getDeclaredField("mMaps");
        field.setAccessible(true);
        var maps = (java.util.concurrent.atomic.AtomicReference<?>)field.get(calls);
        Object previous = maps.get();
        tally.setEnabled(false);
        assertFalse(calls.isEnabled());
        assertNull(maps.get());
        tally.setEnabled(true);
        calls.tally("resource", false, CallOutcome.RECORDED, 2_000);
        assertNotSame(previous, maps.get());
        calls.clear();
        assertEquals(1, tally.getStarted());
        assertEquals(1, tally.get(CallOutcome.RECORDED));
        assertEquals(0, tally.get(CallOutcome.NO_SOURCE));
        assertEquals(0, tally.totals().losses());
    }
}
