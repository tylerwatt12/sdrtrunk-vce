/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JavaFxWindowLoadingLifecycleTest
{
    @Test
    void coalescesRequestsWhileOneEditorLoadOwnsTheLifecycle()
    {
        JavaFxWindowManager.LoadingRequestGate<String> gate =
            new JavaFxWindowManager.LoadingRequestGate<>();

        assertTrue(gate.offer("first"));
        assertFalse(gate.offer("second"));
        assertFalse(gate.offer("latest"));
        assertEquals("latest", gate.complete());

        assertTrue(gate.offer("after completion"));
        assertEquals("after completion", gate.complete());
    }

    @Test
    void failedEditorLoadReleasesTheLifecycleForRetry()
    {
        JavaFxWindowManager.LoadingRequestGate<String> gate =
            new JavaFxWindowManager.LoadingRequestGate<>();

        assertTrue(gate.offer("failed request"));
        gate.fail();

        assertTrue(gate.offer("retry request"));
        assertEquals("retry request", gate.complete());
    }

    @Test
    void injectedRevealSetupFailureReleasesGateBeforeRecoveryAndAllowsRetry()
    {
        JavaFxWindowManager.LoadingRequestGate<String> gate =
            new JavaFxWindowManager.LoadingRequestGate<>();
        RuntimeException failure = new RuntimeException("injected reveal failure");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AtomicBoolean recoveryClaimedRetry = new AtomicBoolean();

        assertTrue(gate.offer("initial request"));

        JavaFxWindowManager.guardLoadingSetup(gate, () -> {
            throw failure;
        }, throwable -> {
            reported.set(throwable);
            recoveryClaimedRetry.set(gate.offer("retry request"));
        });

        assertSame(failure, reported.get());
        assertTrue(recoveryClaimedRetry.get(), "Failure handler must observe a released lifecycle gate");
        assertEquals("retry request", gate.complete());
    }
}
