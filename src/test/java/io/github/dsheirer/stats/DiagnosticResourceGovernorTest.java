/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DiagnosticResourceGovernorTest
{
    private static final int FPS = 20;
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void immediatelyPausesForReceiverDropsAndRecoversWithHysteresis()
    {
        DiagnosticResourceGovernor governor = new DiagnosticResourceGovernor();
        assertTrue(governor.evaluate(0, FPS, 0, 200, 10).renderFrame());

        var dropped = governor.evaluate(SECOND, FPS, 0, 200, 11);
        assertFalse(dropped.renderFrame());
        assertEquals(DiagnosticResourceGovernor.Mode.PROTECTING, dropped.status().mode());
        assertEquals(2, dropped.status().effectiveFramesPerSecond());

        governor.evaluate(SECOND + 1, FPS, 0, 200, 11);
        var reduced = governor.evaluate(SECOND * 4 + 1, FPS, 0, 200, 11);
        assertEquals(DiagnosticResourceGovernor.Mode.REDUCED, reduced.status().mode());
        assertEquals(10, reduced.status().effectiveFramesPerSecond());

        var full = governor.evaluate(SECOND * 7 + 1, FPS, 0, 200, 11);
        assertEquals(DiagnosticResourceGovernor.Mode.FULL, full.status().mode());
        assertFalse(full.status().throttled());
    }

    @Test
    void queuePressureReducesThenPausesBeforeCapacity()
    {
        DiagnosticResourceGovernor governor = new DiagnosticResourceGovernor();
        governor.evaluate(0, FPS, 0, 200, 0);

        var elevated = governor.evaluate(SECOND, FPS, 50, 200, 0);
        assertFalse(elevated.renderFrame());
        assertEquals(DiagnosticResourceGovernor.Mode.REDUCED, elevated.status().mode());

        var critical = governor.evaluate(SECOND * 2, FPS, 100, 200, 0);
        assertFalse(critical.renderFrame());
        assertEquals(DiagnosticResourceGovernor.Mode.PROTECTING, critical.status().mode());
        assertEquals(2, critical.status().throttleEvents());
    }

    @Test
    void repeatedHeavyFramesReduceAndOneOverBudgetFramePauses()
    {
        DiagnosticResourceGovernor governor = new DiagnosticResourceGovernor();
        governor.evaluate(0, FPS, 0, 0, 0);
        long frameBudget = SECOND / FPS;

        governor.recordFrameDuration(frameBudget * 3 / 4, FPS);
        assertEquals(DiagnosticResourceGovernor.Mode.FULL,
            governor.evaluate(SECOND, FPS, 0, 0, 0).status().mode());
        governor.recordFrameDuration(frameBudget * 3 / 4, FPS);
        assertEquals(DiagnosticResourceGovernor.Mode.REDUCED,
            governor.evaluate(SECOND * 2, FPS, 0, 0, 0).status().mode());

        governor.recordFrameDuration(frameBudget * 2, FPS);
        var protecting = governor.evaluate(SECOND * 3, FPS, 0, 0, 0);
        assertEquals(DiagnosticResourceGovernor.Mode.PROTECTING, protecting.status().mode());
        assertFalse(protecting.renderFrame());
    }

    @Test
    void unsupportedQueueCapacityCannotCauseAFalsePermanentPause()
    {
        DiagnosticResourceGovernor governor = new DiagnosticResourceGovernor();
        governor.evaluate(0, FPS, Long.MAX_VALUE, 0, 4);

        var decision = governor.evaluate(SECOND, FPS, Long.MAX_VALUE, 0, 4);
        assertEquals(DiagnosticResourceGovernor.Mode.FULL, decision.status().mode());
        assertTrue(decision.renderFrame());
    }

    @Test
    void cumulativeDropBaselineAndCounterResetDoNotCreateFalsePressure()
    {
        DiagnosticResourceGovernor governor = new DiagnosticResourceGovernor();
        assertEquals(DiagnosticResourceGovernor.Mode.FULL,
            governor.evaluate(0, FPS, 0, 200, 9_000).status().mode());
        assertEquals(DiagnosticResourceGovernor.Mode.FULL,
            governor.evaluate(SECOND, FPS, 0, 200, 0).status().mode());

        var repeatedPressure = governor.evaluate(SECOND * 2, FPS, 150, 200, 0);
        assertEquals(DiagnosticResourceGovernor.Mode.PROTECTING, repeatedPressure.status().mode());
        assertTrue(repeatedPressure.status().reason().contains("queue pressure"));
        repeatedPressure = governor.evaluate(SECOND * 3, FPS, 150, 200, 0);
        assertTrue(repeatedPressure.status().reason().contains("queue pressure"));
    }

    @Test
    void slowWorkConvergesAgainstTheReducedFrameBudget()
    {
        DiagnosticResourceGovernor governor = new DiagnosticResourceGovernor();
        long eightyMilliseconds = TimeUnit.MILLISECONDS.toNanos(80);
        governor.evaluate(0, FPS, 0, 200, 0);
        governor.recordFrameDuration(eightyMilliseconds, FPS);
        assertEquals(DiagnosticResourceGovernor.Mode.PROTECTING,
            governor.evaluate(SECOND, FPS, 0, 200, 0).status().mode());

        governor.evaluate(SECOND * 2, FPS, 0, 200, 0);
        governor.recordFrameDuration(eightyMilliseconds, FPS);
        assertEquals(DiagnosticResourceGovernor.Mode.REDUCED,
            governor.evaluate(SECOND * 5, FPS, 0, 200, 0).status().mode());

        governor.recordFrameDuration(eightyMilliseconds, FPS);
        assertEquals(DiagnosticResourceGovernor.Mode.REDUCED,
            governor.evaluate(SECOND * 6, FPS, 0, 200, 0).status().mode(),
            "an 80 ms frame fits the reduced 100 ms budget and must not re-enter protection");
    }
}
