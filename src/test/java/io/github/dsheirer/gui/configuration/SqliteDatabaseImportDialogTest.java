/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.gui.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import io.github.dsheirer.database.upgrade.DatabaseFormatCatalog;
import io.github.dsheirer.database.upgrade.DatabaseMigrationChain;
import io.github.dsheirer.database.upgrade.Format60TestDatabase;
import io.github.dsheirer.database.upgrade.MigrationContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDatabaseImportDialogTest
{
    @TempDir Path temporary;

    @Test
    void inspectionKeepsBothFilesUnchangedAndReturnsAnApprovalForInlineReview() throws Exception
    {
        Path source = temporary.resolve("selected.sqlite"), active = temporary.resolve("active.sqlite");
        Format60TestDatabase.create(source);
        Files.writeString(active, "Destination must not be opened by source inspection");
        byte[] sourceBefore = Files.readAllBytes(source), activeBefore = Files.readAllBytes(active);
        var inspected = SqliteDatabaseImportDialog.inspect(source, active);
        assertEquals(source.toAbsolutePath(), inspected.sourceDatabase());
        assertEquals(60, inspected.approval().plan().source().version());
        assertEquals(MigrationContent.FULL, inspected.approval().content());
        assertEquals(MigrationContent.CONFIGURATION_ONLY,
            inspected.approval().withContent(MigrationContent.CONFIGURATION_ONLY).content());
        assertArrayEquals(sourceBefore, Files.readAllBytes(source));
        assertArrayEquals(activeBefore, Files.readAllBytes(active));
    }

    @Test
    void inspectionRefusesTheActiveFileBeforeTryingToReadIt() throws Exception
    {
        Path active = temporary.resolve("active.sqlite");
        Files.writeString(active, "Not a database: refusal must precede inspection");
        IOException refusal = assertThrows(IOException.class, () -> SqliteDatabaseImportDialog.inspect(active, active));
        assertTrue(refusal.getMessage().contains("Choose a different file"));
        assertEquals("Not a database: refusal must precede inspection", Files.readString(active));
    }

    @Test
    void olderSourceReviewBindsTheSelectedContentAndKeepsReplacementAndScopeWarnings() throws Exception
    {
        var current = DatabaseFormatCatalog.current();
        var older = DatabaseFormatCatalog.formats().stream().filter(format -> format.version() == 60)
            .findFirst().orElseThrow();
        var plan = new DatabaseMigrationChain.PreflightReport(
            new DatabaseFormatCatalog.DetectedFormat(older, true), current, List.of());
        SwingUtilities.invokeAndWait(() -> {
            var review = SqliteDatabaseImportDialog.confirmationPanel(Path.of("selected.sqlite"),
                Path.of("active.sqlite"), plan);
            assertEquals(MigrationContent.FULL, review.content());
            assertNotNull(review.choices());
            components(review.choices()).stream().filter(JCheckBox.class::isInstance)
                .map(JCheckBox.class::cast).findFirst().orElseThrow().doClick();
            assertEquals(MigrationContent.CONFIGURATION_ONLY, review.content());
            String copy = copy(review.panel());
            assertTrue(copy.contains("Activity, statistics, and alert history start fresh."));
            assertTrue(copy.contains("Settings and recordings are kept."));
            assertTrue(copy.contains("replace this installation’s saved settings"));
            assertTrue(copy.contains("A backup of your current settings is kept first."));
            assertTrue(copy.contains("Only this database is imported."));
            assertTrue(copy.contains("Recordings, vaults, digital audio tools, and other files are not copied."));
            assertTrue(copy.contains("Existing files and saved output folders stay in place."));
            assertTrue(copy.contains("VCE restarts setup for review."));
            assertTrue(copy.contains("You may need a new administrator password."));
            assertFalse(copy.contains("password verifiers"));
        });
    }

    @Test
    void currentFormatRepairDoesNotOfferConfigurationOnly() throws Exception
    {
        var current = DatabaseFormatCatalog.current();
        var repair = new DatabaseMigrationChain.StepPreflight("repair", "Repair saved settings",
            current.version(), current.version(), List.of());
        var plan = new DatabaseMigrationChain.PreflightReport(
            new DatabaseFormatCatalog.DetectedFormat(current, true), current, List.of(repair));
        assertTrue(plan.requiresMigration());
        SwingUtilities.invokeAndWait(() -> {
            var review = SqliteDatabaseImportDialog.confirmationPanel(Path.of("selected.sqlite"),
                Path.of("active.sqlite"), plan);
            assertNull(review.choices());
            assertEquals(MigrationContent.FULL, review.content());
            assertFalse(copy(review.panel()).contains("Time estimate unavailable."));
        });
    }

    @Test
    void historyChoicesAndReviewCopyReflowInsideANarrowViewport() throws Exception
    {
        var current = DatabaseFormatCatalog.current();
        var older = DatabaseFormatCatalog.formats().stream().filter(format -> format.version() == 60)
            .findFirst().orElseThrow();
        var plan = new DatabaseMigrationChain.PreflightReport(
            new DatabaseFormatCatalog.DetectedFormat(older, true), current, List.of());
        SwingUtilities.invokeAndWait(() -> {
            var review = SqliteDatabaseImportDialog.confirmationPanel(Path.of("selected.sqlite"),
                Path.of("active.sqlite"), plan);
            JScrollPane scroll = components(review.panel()).stream().filter(JScrollPane.class::isInstance)
                .map(JScrollPane.class::cast).findFirst().orElseThrow();
            scroll.setSize(480, 400);
            scroll.doLayout();
            scroll.getViewport().doLayout();
            Container body = (Container)scroll.getViewport().getView();
            body.doLayout();
            scroll.doLayout();
            scroll.getViewport().doLayout();
            body.doLayout();
            assertEquals(scroll.getViewport().getExtentSize().width, body.getWidth());
            assertFalse(scroll.getHorizontalScrollBar().isVisible());
            assertEquals(0, review.choices().getX());
            assertTrue(review.choices().getWidth() <= body.getWidth());
        });
    }

    @Test
    void replacementWarningIsClearWithoutShouting()
    {
        JTextArea warning = SqliteDatabaseImportDialog.warningNotice();

        assertEquals("This will replace this installation’s saved settings.", warning.getText());
        Color foreground = warning.getForeground();
        assertTrue(foreground.getRed() > foreground.getGreen());
        assertTrue(foreground.getRed() > foreground.getBlue());
        assertTrue((warning.getFont().getStyle() & Font.BOLD) != 0);
        assertTrue(warning.getLineWrap());
        assertTrue(warning.getWrapStyleWord());
        assertTrue(warning.getColumns() <= 68);
    }

    @Test
    void fileMenuStartsTheWizardOwnedDatabaseImportBeforeReception() throws Exception
    {
        String application = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java"));

        assertTrue(application.contains("new JMenuItem(\"Import SQLite Database…\")"));
        assertTrue(application.contains("\"--setup-wizard\", \"--import-sqlite\""));
        String wizard = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java"));
        assertTrue(wizard.contains("SqliteDatabaseImportDialog.inspect("));
        assertTrue(wizard.contains("SwingUtilities.invokeLater(wizard::chooseSource)"));
        assertFalse(wizard.contains("SqliteDatabaseImportDialog.choose("), "The wizard owns the inline review");
        assertTrue(wizard.contains("prepared.approval().withContent(migrationContent)"));
        assertTrue(application.contains("replaceCurrentDatabase(prepared.sourceDatabase()"));
        assertTrue(application.contains("ApplicationRelauncher.relaunch()"));
        assertTrue(application.contains("processShutdown(false)"));
        assertTrue(application.contains("if(mDatabaseReplacementInProgress)"));
        assertTrue(application.contains("WindowConstants.DO_NOTHING_ON_CLOSE"));
        assertTrue(application.contains("quitResponse.cancelQuit()"));
    }

    private static String copy(Container parent)
    {
        return String.join("\n", components(parent).stream().filter(JTextArea.class::isInstance)
            .map(component -> ((JTextArea)component).getText()).toList());
    }

    private static List<Component> components(Container parent)
    {
        List<Component> result = new ArrayList<>();
        for(Component component: parent.getComponents())
        {
            result.add(component);
            if(component instanceof Container child) result.addAll(components(child));
        }
        return result;
    }
}
