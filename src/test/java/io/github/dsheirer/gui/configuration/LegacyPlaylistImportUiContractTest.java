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
    private static final Path DIALOG =
        Path.of("src/main/java/io/github/dsheirer/gui/configuration/LegacyPlaylistImportDialog.java");
    private static final Path CONFIGURATION_MANAGER =
        Path.of("src/main/java/io/github/dsheirer/configuration/ConfigurationManager.java");

    @Test
    void helpWizardReplacesTheFileMenuImport() throws Exception
    {
        String source = Files.readString(SDRTRUNK);
        assertFalse(source.contains("new JMenuItem(\"Import Legacy Playlist XML...\")"));
        assertTrue(source.contains("new JMenuItem(\"Setup Wizard…\")"));
        String wizard = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java"));
        assertTrue(wizard.contains("LegacyPlaylistImportDialog.choose(this, database, root)"));
        assertTrue(wizard.contains("new LegacyPlaylistImportService(database).execute(selected)"));
    }

    @Test
    void previewExplainsTheNonDestructiveImportContract() throws Exception
    {
        String source = Files.readString(DIALOG);
        assertTrue(source.contains("Existing configuration will not be replaced."));
        assertTrue(source.contains("database backup will be created before the import"));
        assertTrue(source.contains("Unsupported legacy entries are omitted."));
    }

    @Test
    void previewDoesNotConstructRuntimeServicesAndExistingRuntimeImportIsolationIsRetained() throws Exception
    {
        String dialog = Files.readString(DIALOG);
        String manager = Files.readString(CONFIGURATION_MANAGER);
        assertFalse(dialog.contains("ConfigurationManager"));
        assertFalse(dialog.contains("Platform.runLater"));
        assertFalse(dialog.contains(".execute("));
        assertTrue(manager.contains("mExternalConfigurationOperation = true;"));
        assertTrue(manager.contains("if(mExternalConfigurationOperation)"));
        assertTrue(manager.contains("saveNow(true);"));
        assertTrue(manager.contains("if(hasDirtyConfiguration())"));
        assertTrue(manager.contains("save(allowDuringExternalOperation);"));
    }
}
