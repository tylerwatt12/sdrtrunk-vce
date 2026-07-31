/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.gui.theme;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.LookAndFeel;
import org.junit.jupiter.api.Test;

class ThemeManagerTest
{
    @Test
    void stylesheetDataUriUsesPercentEncodedSpaces()
    {
        String uri = ThemeManager.toDataStylesheetUrl(".root { -fx-font-size: calc(12px + 1px); }");

        assertTrue(uri.startsWith("data:text/css,"));
        assertTrue(uri.contains("%20"));
        assertTrue(uri.contains("%2B"));
        assertFalse(uri.substring("data:text/css,".length()).contains("+"));
    }

    @Test
    void everyConfiguredLookAndFeelIsAvailable() throws Exception
    {
        for(Theme theme: Theme.values())
        {
            Object lookAndFeel = Class.forName(theme.getLafClassName()).getDeclaredConstructor().newInstance();
            assertInstanceOf(LookAndFeel.class, lookAndFeel, theme.getDisplayName());
        }
    }

    @Test
    void swingAppearanceChangesRunOnTheEventDispatchThread()
    {
        AtomicBoolean ranOnEventThread = new AtomicBoolean();

        ThemeManager.runOnSwingEventThreadAndWait(() -> ranOnEventThread.set(EventQueue.isDispatchThread()));

        assertTrue(ranOnEventThread.get());
    }
}
