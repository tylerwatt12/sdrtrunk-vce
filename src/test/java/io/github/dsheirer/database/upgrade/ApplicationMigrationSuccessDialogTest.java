/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ApplicationMigrationSuccessDialogTest
{
    @Test
    void countdownAutoAcceptsExactlyOnce()
    {
        AtomicInteger accepts = new AtomicInteger();
        ApplicationMigrationSuccessDialog.CountdownController controller =
            new ApplicationMigrationSuccessDialog.CountdownController("report", 2, ignored -> { },
                accepts::incrementAndGet);

        assertEquals(2, controller.secondsRemaining());
        controller.tick();
        assertEquals(1, controller.secondsRemaining());
        assertFalse(controller.isAccepted());
        controller.tick();
        assertEquals(0, controller.secondsRemaining());
        assertTrue(controller.isAccepted());
        assertEquals(1, accepts.get());

        controller.tick();
        controller.accept();
        assertEquals(1, accepts.get());
        assertEquals(0, controller.secondsRemaining());
    }

    @Test
    void immediateContinueIsIdempotent()
    {
        AtomicInteger accepts = new AtomicInteger();
        ApplicationMigrationSuccessDialog.CountdownController controller =
            new ApplicationMigrationSuccessDialog.CountdownController("report", 10, ignored -> { },
                accepts::incrementAndGet);

        controller.accept();
        controller.accept();
        controller.tick();

        assertTrue(controller.isAccepted());
        assertEquals(1, accepts.get());
        assertEquals(10, controller.secondsRemaining());
    }

    @Test
    void copyUsesTheCompleteImmutableReportWithoutClosingOrResettingCountdown() throws Exception
    {
        String report = "Migration complete.\n\nRESULT: preserved\nBackup: /tmp/example.sqlite";
        AtomicReference<String> copied = new AtomicReference<>();
        AtomicInteger accepts = new AtomicInteger();
        ApplicationMigrationSuccessDialog.CountdownController controller =
            new ApplicationMigrationSuccessDialog.CountdownController(report, 3, copied::set,
                accepts::incrementAndGet);

        controller.tick();
        controller.copy();

        assertEquals(report, copied.get());
        assertEquals(2, controller.secondsRemaining());
        assertFalse(controller.isAccepted());
        assertEquals(0, accepts.get());
    }

    @Test
    void clipboardFailureLeavesCountdownActive()
    {
        ApplicationMigrationSuccessDialog.CountdownController controller =
            new ApplicationMigrationSuccessDialog.CountdownController("complete report", 4,
                ignored -> { throw new IOException("clipboard busy"); }, () -> { });

        IOException exception = assertThrows(IOException.class, controller::copy);

        assertEquals("clipboard busy", exception.getMessage());
        assertEquals(4, controller.secondsRemaining());
        assertFalse(controller.isAccepted());
    }

    @Test
    void completionPanelWiresCopyOkAndVisibleCountdown() throws Exception
    {
        String report = "Full migration report\nwith backup path";
        AtomicReference<String> copied = new AtomicReference<>();
        AtomicInteger accepts = new AtomicInteger();
        AtomicReference<ApplicationMigrationSuccessDialog.CompletionPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(
            new ApplicationMigrationSuccessDialog.CompletionPanel(report, 2, copied::set,
                accepts::incrementAndGet)));
        ApplicationMigrationSuccessDialog.CompletionPanel panel = panelReference.get();

        assertEquals("OK", panel.okButton().getText());
        assertEquals("Copy Message", panel.copyButton().getText());
        assertEquals("Continuing automatically in 2 seconds.", panel.countdownStatus());

        SwingUtilities.invokeAndWait(() -> panel.copyButton().doClick());
        assertEquals(report, copied.get());
        assertEquals("Copied", panel.copyButton().getText());
        assertEquals("Full message copied.", panel.copyStatus());
        assertEquals(0, accepts.get());

        SwingUtilities.invokeAndWait(panel::tick);
        assertEquals("Continuing automatically in 1 second.", panel.countdownStatus());
        SwingUtilities.invokeAndWait(() -> panel.okButton().doClick());
        SwingUtilities.invokeAndWait(panel::tick);
        assertEquals(1, accepts.get());
    }

    @Test
    void zeroDurationIsImmediatelyExpired()
    {
        AtomicInteger accepts = new AtomicInteger();
        ApplicationMigrationSuccessDialog.CountdownController controller =
            new ApplicationMigrationSuccessDialog.CountdownController("report", 0, ignored -> { },
                accepts::incrementAndGet);

        assertEquals(0, controller.secondsRemaining());
        assertFalse(controller.isAccepted());
        controller.tick();
        assertTrue(controller.isAccepted());
        assertEquals(1, accepts.get());
    }

    @Test
    void reportBuildersRetainHelperOutputScopeAndBackupPaths()
    {
        Path backup = Path.of("/portable/database/backups/before.sqlite");
        String helper = "Detected format 1.\nRESULT: migrated and validated.";
        ApplicationMigrationService.MigrationResult current = new ApplicationMigrationService.MigrationResult(
            false, backup, null, helper, PreviousBuildLocator.InputScope.DATABASE_FILE);
        assertEquals("Your database was updated successfully.\n\n" + helper +
            "\n\nBackup of your previous database:\n" + backup,
            ApplicationMigrationSuccessDialog.currentDatabaseReport(current));

        ApplicationMigrationService.MigrationResult portable = new ApplicationMigrationService.MigrationResult(
            true, null, null, helper, PreviousBuildLocator.InputScope.PORTABLE_PROFILE);
        assertEquals("Your previous installation was imported successfully. The original installation was not " +
            "changed. Details about copied files and any items needing attention are listed below.\n\n" + helper,
            ApplicationMigrationSuccessDialog.previousImportReport(portable));

        ApplicationMigrationService.MigrationResult databaseOnly = new ApplicationMigrationService.MigrationResult(
            false, null, null, helper, PreviousBuildLocator.InputScope.DATABASE_FILE);
        assertEquals("The selected database was imported successfully. The original file and nearby files were " +
            "not changed. Files outside the database were not copied.\n\n" + helper,
            ApplicationMigrationSuccessDialog.previousImportReport(databaseOnly));

        Path source = Path.of("/old/profile.sqlite");
        String replacement = ApplicationMigrationSuccessDialog.replacementImportReport(current, source);
        assertEquals("The selected database was imported successfully and is now in use. The original file was " +
            "not changed. Files outside the database were not copied.\n\nIf the administrator account could not be " +
            "kept, setup will ask you to create a new administrator password after restart.\n\n" + helper +
            "\n\nSelected source:\n" + source.toAbsolutePath().normalize() +
            "\n\nBackup of the database that was replaced:\n" + backup +
            "\n\nSDRTrunk will restart so you can review the imported settings before receiving.", replacement);

        String repairedHelper = "OUTCOME: Migration completed with itemized repairs, resets, or skipped items.";
        ApplicationMigrationService.MigrationResult repaired = new ApplicationMigrationService.MigrationResult(
            false, backup, null, repairedHelper, PreviousBuildLocator.InputScope.DATABASE_FILE);
        String repairedReport = ApplicationMigrationSuccessDialog.currentDatabaseReport(repaired);
        assertTrue(repairedReport.startsWith("Your database was updated successfully. Some items were repaired, " +
            "reset, or skipped. Details are listed below."));
    }

    @Test
    void firstRunMigrationReportsAreInlineAndReplacementRetainsItsAutoAcceptDialog() throws Exception
    {
        String bootstrap = Files.readString(Path.of(
            "src/main/java/io/github/dsheirer/database/SdrTrunkDatabaseBootstrap.java"));
        assertFalse(bootstrap.contains("JOptionPane"));
        assertTrue(bootstrap.contains("System.out.println(\"Safety backup: \""));
        String wizard = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java"));
        assertTrue(wizard.contains("ApplicationMigrationSuccessDialog.currentDatabaseReport"));
        assertTrue(wizard.contains("ApplicationMigrationSuccessDialog.previousImportReport"));
        assertTrue(wizard.contains("Copy Message"));
        assertTrue(wizard.contains("countdown.start()"));
        assertFalse(wizard.contains("ApplicationMigrationSuccessDialog.show("));
    }
}
