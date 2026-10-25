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

package io.github.dsheirer.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LegacyPlaylistImportUiContractTest
{
    private static final Path SDRTRUNK =
        Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java");
    private static final Path CONFIGURATION_MANAGER =
        Path.of("src/main/java/io/github/dsheirer/configuration/ConfigurationManager.java");

    @Test
    void helpWizardReplacesTheFileMenuImport() throws Exception
    {
        String source = Files.readString(SDRTRUNK);
        assertFalse(source.contains("new JMenuItem(\"Import Legacy Playlist XML...\")"));
        assertTrue(source.contains("new JMenuItem(\"Setup Wizard…\")"));
    }

    @Test
    void configurationFlushIsSerializedAndTheRetiredRuntimeImportBridgeIsAbsent() throws Exception
    {
        String manager = Files.readString(CONFIGURATION_MANAGER);
        assertTrue(manager.contains("mExternalConfigurationOperation = true;"));
        assertTrue(manager.contains("if(mExternalConfigurationOperation)"));
        assertFalse(manager.contains("applyExternalConfigurationSnapshot"));
        assertFalse(manager.contains("ExternalConfigurationReloadException"));
        assertFalse(manager.contains("hasDirtyConfiguration"));
        String flush = manager.substring(manager.indexOf("public void flushConfiguration()"),
            manager.indexOf("/** Executes one detached configuration command"));
        assertTrue(flush.contains("executeConfigurationTask("));
        assertTrue(flush.contains("if(mExternalConfigurationOperation)"));
        assertFalse(manager.contains("allowDuringExternalOperation"));
    }
}
