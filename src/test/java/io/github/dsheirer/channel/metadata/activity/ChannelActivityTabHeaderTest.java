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

import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ChannelActivityTabHeaderTest
{
    @Test
    void closeButtonTargetsItsModelAndDoesNotDependOnTabGeometry() throws Exception
    {
        AtomicReference<ChannelActivityTableModel> closed = new AtomicReference<>();
        AtomicBoolean selected = new AtomicBoolean();
        ChannelActivityTableModel model = new ChannelActivityTableModel(
            new ChannelActivityTableState("County Site", null, true, null));

        SwingUtilities.invokeAndWait(() -> {
            ChannelActivityTabHeader header = new ChannelActivityTabHeader(model, () -> selected.set(true),
                closed::set);

            assertEquals("County Site", header.getTitleLabel().getText());
            assertFalse(header.getActiveIndicator().isVisible());
            assertTrue(header.getCloseButton().isVisible());
            assertTrue(header.getCloseButton().isEnabled());

            header.getTitleLabel().dispatchEvent(new MouseEvent(header.getTitleLabel(), MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 2, 2, 1, false, MouseEvent.BUTTON1));
            assertTrue(selected.get());

            header.getCloseButton().doClick();
            assertSame(model, closed.get());
        });
    }

    @Test
    void activeControlReplacesCloseButtonWithStatusAndBlocksStaleAction() throws Exception
    {
        AtomicReference<ChannelActivityTableModel> closed = new AtomicReference<>();
        ChannelActivityTableState state = new ChannelActivityTableState("Old Title", null, true, null);
        ChannelActivityTableModel model = new ChannelActivityTableModel(state);
        AtomicReference<ChannelActivityTabHeader> headerReference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            ChannelActivityTabHeader header = new ChannelActivityTabHeader(model, () -> {}, closed::set);
            headerReference.set(header);
        });

        state.setTitle("Current Site");
        state.setControlActive(true);

        SwingUtilities.invokeAndWait(() -> {
            ChannelActivityTabHeader header = headerReference.get();
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
