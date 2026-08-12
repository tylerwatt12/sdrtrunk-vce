/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.preference.UserPreferences;
import org.junit.jupiter.api.Test;

class ChannelActivityPanelLifecycleTest
{
    @Test
    void hiddenPanelDoesNotAttachToWebOwnedActivityState()
    {
        UserPreferences preferences = new UserPreferences();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, preferences);
        ChannelActivityPanel panel = new ChannelActivityPanel(manager, null, preferences);

        try
        {
            assertFalse(panel.isActive());
            assertEquals(0, manager.getChannelActivityModel().getTableListenerCount());

            assertEquals(0, manager.getChannelActivityModel().getTableListenerCount(),
                "the always-running activity core must not attach the hidden Swing renderer");
        }
        finally
        {
            panel.dispose();
            manager.shutdown();
        }
    }
}
