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
import io.github.dsheirer.database.upgrade.MigrationContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SqliteDatabaseImportDialogTest
{
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
            components(review.choices()).stream().filter(component -> component instanceof JRadioButton radio &&
                radio.getAccessibleContext().getAccessibleName().equals("Configuration only"))
                .map(component -> (JRadioButton)component).findFirst().orElseThrow().doClick();
            assertEquals(MigrationContent.CONFIGURATION_ONLY, review.content());
            String copy = copy(review.panel());
            assertTrue(copy.contains("Past activity, statistics, and alert history will start fresh."));
            assertTrue(copy.contains("separate Managed Recordings catalog are kept."));
            assertTrue(copy.contains("REPLACE THE ACTIVE DATABASE"));
            assertTrue(copy.contains("timestamped safety backup"));
            assertTrue(copy.contains("Only data inside the selected SQLite file will be imported"));
            assertTrue(copy.contains("other files will not be copied"));
            assertTrue(copy.contains("not remapped"));
            assertTrue(copy.contains("Time estimate unavailable."));
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
    void fileMenuStartsTheWizardOwnedDatabaseImportBeforeReception() throws Exception
    {
        String application = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java"));

        assertTrue(application.contains("new JMenuItem(\"Import SQLite Database…\")"));
        assertTrue(application.contains("\"--setup-wizard\", \"--import-sqlite\""));
        String wizard = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java"));
        assertTrue(wizard.contains("SqliteDatabaseImportDialog.choose(this, database, root)"));
        assertTrue(wizard.contains("SwingUtilities.invokeLater(wizard::promptForDatabaseReplacement)"));
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
        assertTrue(dialog.contains("Reproducible activity or statistics data may be reset"));
        assertTrue(dialog.contains("no usable administrator credential can be preserved"));
        assertTrue(dialog.contains("restart automatically"));
        assertTrue(dialog.contains("approval.withContent(review.content())"));
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
