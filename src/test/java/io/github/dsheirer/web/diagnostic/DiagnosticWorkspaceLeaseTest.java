/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.web.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiagnosticWorkspaceLeaseTest
{
    @Test
    void excludesAllOtherDiagnosticOwnersUntilReleased()
    {
        DiagnosticWorkspaceLease workspace = new DiagnosticWorkspaceLease();
        DiagnosticWorkspaceLease.Lease wideband = workspace.tryAcquire(
            DiagnosticWorkspaceLease.Owner.WIDEBAND_SIGNAL).orElseThrow();

        assertTrue(workspace.isActive());
        assertEquals(DiagnosticWorkspaceLease.Owner.WIDEBAND_SIGNAL, workspace.getOwner().orElseThrow());
        assertTrue(workspace.tryAcquire(DiagnosticWorkspaceLease.Owner.WIDEBAND_SIGNAL).isEmpty());
        assertTrue(workspace.tryAcquire(DiagnosticWorkspaceLease.Owner.SELECTED_CHANNEL).isEmpty());

        wideband.close();

        assertFalse(workspace.isActive());
        assertTrue(workspace.getOwner().isEmpty());
        DiagnosticWorkspaceLease.Lease selected = workspace.tryAcquire(
            DiagnosticWorkspaceLease.Owner.SELECTED_CHANNEL).orElseThrow();
        assertEquals(DiagnosticWorkspaceLease.Owner.SELECTED_CHANNEL, selected.owner());
        selected.close();
    }

    @Test
    void releaseIsIdempotentAndCannotReleaseANewerLease()
    {
        DiagnosticWorkspaceLease workspace = new DiagnosticWorkspaceLease();
        DiagnosticWorkspaceLease.Lease first = workspace.tryAcquire(
            DiagnosticWorkspaceLease.Owner.WIDEBAND_SIGNAL).orElseThrow();

        first.close();
        assertTrue(first.isClosed());

        DiagnosticWorkspaceLease.Lease second = workspace.tryAcquire(
            DiagnosticWorkspaceLease.Owner.SELECTED_CHANNEL).orElseThrow();
        first.close();

        assertTrue(workspace.isActive());
        assertEquals(DiagnosticWorkspaceLease.Owner.SELECTED_CHANNEL, workspace.getOwner().orElseThrow());
        assertFalse(second.isClosed());

        second.close();
        second.close();
        assertTrue(second.isClosed());
        assertFalse(workspace.isActive());
    }

    @Test
    void rejectsMissingOwnerWithoutChangingWorkspaceState()
    {
        DiagnosticWorkspaceLease workspace = new DiagnosticWorkspaceLease();

        assertThrows(NullPointerException.class, () -> workspace.tryAcquire(null));
        assertFalse(workspace.isActive());
    }
}
