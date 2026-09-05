/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class CopyableErrorDialogTest
{
    @Test
    void keepsDetailsCollapsedAndCopiesTheCompleteError() throws Exception
    {
        String summary = "The database could not be imported. No changes were made.";
        String details = "A long migration diagnostic\nwith the exact database error.";
        AtomicReference<String> copied = new AtomicReference<>();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger resizes = new AtomicInteger();
        AtomicReference<CopyableErrorDialog.ErrorPanel> reference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> reference.set(new CopyableErrorDialog.ErrorPanel(summary, details,
            copied::set, closes::incrementAndGet, resizes::incrementAndGet)));
        CopyableErrorDialog.ErrorPanel panel = reference.get();

        assertFalse(panel.detailsVisible());
        assertEquals("Show details", panel.detailsButton().getText());
        SwingUtilities.invokeAndWait(() -> panel.detailsButton().doClick());
        assertTrue(panel.detailsVisible());
        assertEquals("Hide details", panel.detailsButton().getText());
        assertEquals(1, resizes.get());

        SwingUtilities.invokeAndWait(() -> panel.copyButton().doClick());
        assertEquals(summary + "\n\nTechnical details:\n" + details, copied.get());
        assertEquals("Copied", panel.copyButton().getText());
        assertEquals("Full error copied.", panel.copyStatus());
        assertEquals(copied.get(), panel.report());

        SwingUtilities.invokeAndWait(() -> panel.closeButton().doClick());
        assertEquals(1, closes.get());
    }

    @Test
    void reportsTheDeepestUsefulCause()
    {
        Exception failure = new Exception("outer", new IllegalStateException("specific migration failure"));
        assertEquals("specific migration failure", CopyableErrorDialog.message(failure));
    }
}
