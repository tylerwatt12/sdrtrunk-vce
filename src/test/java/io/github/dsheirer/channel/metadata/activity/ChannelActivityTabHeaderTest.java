/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ChannelActivityTabHeaderTest
{
    @Test
    void closeButtonTargetsItsModelAndDoesNotDependOnTabGeometry() throws Exception
    {
        AtomicReference<ChannelActivityTableModel> closed = new AtomicReference<>();
        ChannelActivityTableModel model = new ChannelActivityTableModel("County Site", null, true);

        SwingUtilities.invokeAndWait(() -> {
            ChannelActivityTabHeader header = new ChannelActivityTabHeader(model, closed::set);

            assertEquals("County Site", header.getTitleLabel().getText());
            assertFalse(header.getActiveIndicator().isVisible());
            assertTrue(header.getCloseButton().isVisible());
            assertTrue(header.getCloseButton().isEnabled());

            header.getCloseButton().doClick();
            assertSame(model, closed.get());
        });
    }

    @Test
    void activeControlReplacesCloseButtonWithStatusAndBlocksStaleAction() throws Exception
    {
        AtomicReference<ChannelActivityTableModel> closed = new AtomicReference<>();
        ChannelActivityTableModel model = new ChannelActivityTableModel("Old Title", null, true);

        SwingUtilities.invokeAndWait(() -> {
            ChannelActivityTabHeader header = new ChannelActivityTabHeader(model, closed::set);
            model.setTitle("Current Site");
            model.setControlActive(true);
            header.getCloseButton().doClick();
            assertNull(closed.get());

            header.update();

            assertEquals("Current Site", header.getTitleLabel().getText());
            assertTrue(header.getActiveIndicator().isVisible());
            assertFalse(header.getCloseButton().isVisible());
            assertFalse(header.getCloseButton().isEnabled());

        });
    }
}
