/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MigrationTimingsTest
{
    @Test
    void usesMonotonicElapsedTimeAndReportsSuccessfulAndFailedPhasesOnce()
    {
        List<String> lines = new ArrayList<>();
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        MigrationTimings timings = new MigrationTimings(lines::add, clock::get);
        var completed = timings.phase("Copy database");
        clock.addAndGet(1_234_567_890L);
        completed.complete();
        completed.close();
        clock.addAndGet(100_000_000L);
        completed.close();
        try(var ignored = timings.phase("Validate database"))
        {
            clock.addAndGet(4_567_890_123L);
        }
        assertEquals(List.of("TIMING: Copy database — 1234 ms (completed)",
            "TIMING: Validate database — 4567 ms (failed)"), lines);
    }

    @Test
    void aTimingObserverCannotTurnSuccessfulDatabaseWorkIntoAFailure()
    {
        MigrationTimings timings = new MigrationTimings(line -> { throw new IllegalStateException("observer"); });
        assertDoesNotThrow(() -> {
            try(var phase = timings.phase("Commit")) { phase.complete(); }
        });
    }
}
