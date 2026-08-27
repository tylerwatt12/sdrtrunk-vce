/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

class SqliteDatabaseImportDialogTest
{
    @Test
    void replacementWarningIsBoldRedAndBoundedByExclamationPoints()
    {
        JTextArea warning = SqliteDatabaseImportDialog.warningNotice();

        assertTrue(warning.getText().startsWith("!!!"));
        assertTrue(warning.getText().endsWith("!!!"));
        assertTrue(warning.getText().contains("REPLACE THE ACTIVE DATABASE"));
        Color foreground = warning.getForeground();
        assertTrue(foreground.getRed() > foreground.getGreen());
        assertTrue(foreground.getRed() > foreground.getBlue());
        assertTrue((warning.getFont().getStyle() & Font.BOLD) != 0);
        assertTrue(warning.getLineWrap());
        assertTrue(warning.getWrapStyleWord());
        assertTrue(warning.getColumns() <= 68);
    }

    @Test
    void fileMenuExposesTheAfterSetupDatabaseImport() throws Exception
    {
        String application = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java"));

        assertTrue(application.contains("new JMenuItem(\"Import SQLite Database...\")"));
        assertTrue(application.contains("SqliteDatabaseImportDialog.choose(mMainGui"));
        assertTrue(application.contains("replaceCurrentDatabase(prepared.sourceDatabase()"));
        assertTrue(application.contains("ApplicationRelauncher.relaunch()"));
        assertTrue(application.contains("processShutdown(false)"));
        assertTrue(application.contains("if(mDatabaseReplacementInProgress)"));
        assertTrue(application.contains("WindowConstants.DO_NOTHING_ON_CLOSE"));
        assertTrue(application.contains("quitResponse.cancelQuit()"));
    }

    @Test
    void dialogExplainsReplacementBackupAndDatabaseOnlyScope() throws Exception
    {
        String dialog = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/gui/configuration/SqliteDatabaseImportDialog.java"));

        assertTrue(dialog.contains("completely replaced"));
        assertTrue(dialog.contains("timestamped safety backup"));
        assertTrue(dialog.contains("Only data inside the selected SQLite file will be imported"));
        assertTrue(dialog.contains("neighboring vault, JMBE library"));
        assertTrue(dialog.contains("restart automatically"));
    }
}
