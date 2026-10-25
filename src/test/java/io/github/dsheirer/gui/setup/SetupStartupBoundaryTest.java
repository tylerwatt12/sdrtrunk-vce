package io.github.dsheirer.gui.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetupStartupBoundaryTest
{
    @Test
    void unavailableOptionalCatalogDoesNotRewriteTheSavedRecordingMode() throws Exception
    {
        String startup = Files.readString(Path.of("src/main/java/io/github/dsheirer/receiver/ReceiverRuntime.java"));
        int catalog = startup.indexOf("mRecordingCatalog = new ManagedRecordingCatalog");
        int audio = startup.indexOf("mRecording =", catalog);
        assertTrue(catalog >= 0 && audio > catalog);
        String fallback = startup.substring(catalog, audio);
        assertTrue(fallback.contains("catch(Exception exception)"));
        assertTrue(fallback.contains("saved recording mode is unchanged"));
        assertFalse(fallback.contains("setRecordingMode("));
    }

    @Test void graphicalSetupPrecedesReceiverConstructionAndActualWebBindPrecedesActivation() throws Exception
    {
        String startup=Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java"));
        String runtime=Files.readString(Path.of("src/main/java/io/github/dsheirer/receiver/ReceiverRuntime.java"));
        String main=startup.substring(startup.indexOf("public static void main"));
        assertTrue(main.indexOf("SetupWizard.run(")<main.indexOf("new SDRTrunk("));
        assertTrue(main.indexOf("SetupWizard.run(")<main.indexOf("new ReceiverRuntime("));
        int listenerRecovery = startup.indexOf("SetupWizard.ensureListener(");
        int activation = startup.indexOf("mRuntime.activate();");
        assertTrue(listenerRecovery >= 0 && activation > listenerRecovery);
        String composition = runtime.substring(runtime.indexOf("private void startServices()"),
            runtime.indexOf("private void activateServices()"));
        assertTrue(composition.contains("mWebServer.start();"));
        assertFalse(composition.contains("mTuners.start();"));
        assertFalse(composition.contains("mStreaming.start();"));
        assertFalse(composition.contains("mRecording.start();"));
        String activationBody = runtime.substring(runtime.indexOf("private void activateServices()"),
            runtime.indexOf("private void startRecordingRetention()"));
        assertTrue(activationBody.contains("mTuners.start();"));
        assertTrue(activationBody.contains("mStreaming.start();"));
        assertTrue(activationBody.contains("mRecording.start();"));
        int serverActivation = main.indexOf("runtime.activate();");
        int autostart = main.indexOf("if(startConfiguredChannels) runtime.startConfiguredChannels(true);");
        assertTrue(serverActivation >= 0 && autostart > serverActivation);
        assertFalse(startup.contains("CoordinatedStartupDialog"));
        assertTrue(main.indexOf("if(setup.replacement() != null)") < main.indexOf("new SDRTrunk("));
        String replacement = startup.substring(startup.indexOf("private static int replaceSetupDatabase"),
            startup.indexOf("private void flushConfigurationForDatabaseReplacement"));
        assertTrue(replacement.indexOf("SqlitePreferencesFactory.shutdown()") < replacement.indexOf(".replaceCurrentDatabase("));
        assertTrue(replacement.indexOf(".replaceCurrentDatabase(") < replacement.indexOf("lock.close()"));
        assertTrue(replacement.contains("response.cancelQuit()"));
        assertTrue(replacement.contains("return 1;"));
        String wizard=Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java"));
        for(String forbidden:new String[]{"new TunerManager", "ChannelProcessingManager", "JmbeEditorRequest", "ApplicationMigrationProgressDialog.run"})
            assertFalse(wizard.contains(forbidden),forbidden);
    }

    @Test void manualHardwareScanSkipRequiresAcknowledgedCancellation() throws Exception
    {
        String wizard = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java"));
        String hardware = wizard.substring(wizard.indexOf("private void hardwarePage()"), wizard.indexOf("private void calibrationPage()"));
        assertFalse(hardware.contains("defer("));
        assertTrue(hardware.contains("stopped.set(true)"));
        assertTrue(hardware.contains("persist() && stopped.get()"));
        assertTrue(wizard.contains("? \"Skip discovery\" : \"Cancel operation\""));
        assertTrue(wizard.contains("cancel.setVisible(false)"));
        assertFalse(wizard.contains("Summary statistics — recommended"));
    }

    @Test void setupFailuresUsePlainGeneralGuidance() throws Exception
    {
        String wizard = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java"));
        assertTrue(wizard.contains("This step couldn’t finish. Try again, or return to it later."));
        assertTrue(wizard.contains("Your saved data could not be updated or imported. Use Copy error when reporting this problem."));
        assertFalse(wizard.contains("Copy the technical details below"));
        assertFalse(wizard.contains("make sure there is enough free space"));
    }

    @Test void migrationChoicesAndElapsedStatusRemainExplicit() throws Exception
    {
        assertEquals("0:00 elapsed", SetupWizard.elapsedLabel(-1));
        assertEquals("0:59 elapsed", SetupWizard.elapsedLabel(TimeUnit.SECONDS.toNanos(59)));
        assertEquals("1:01 elapsed", SetupWizard.elapsedLabel(TimeUnit.SECONDS.toNanos(61)));

        String wizard = Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java"));
        String sourcePage = wizard.substring(wizard.indexOf("private void sourcePage()"),
            wizard.indexOf("private void migrate(Path source"));
        assertTrue(sourcePage.contains("migrationDetails(database, startupMigrationPlan)"));
        assertTrue(sourcePage.contains("migrationDetails(selection.database(), plan)"));
        assertTrue(sourcePage.contains("approval.withContent(migrationContent)"));
        assertTrue(wizard.contains("private MigrationContent migrationContent = MigrationContent.FULL"));
        assertTrue(sourcePage.contains("upgradeBackupChoice()"));
        assertTrue(sourcePage.contains("next.setText(\"Update\")"));
        assertTrue(wizard.contains("private boolean createUpgradeBackup = true"));
        assertFalse(sourcePage.contains("readMigrationApproval(database"));
        assertFalse(wizard.contains("directUpdateRequested"));
        assertFalse(wizard.contains("Need the receiver back sooner?"));
        assertFalse(wizard.contains("Faster update available"));
        assertFalse(wizard.contains("Skip backup & safety checks"));
        assertTrue(wizard.contains("service.migrateCurrent(root, createUpgradeBackup, selectedContent, this::migrationProgress)"));
        assertTrue(wizard.contains("elapsedLabel(System.nanoTime() - operationStartedNanos)"));
    }

    @Test void replacementReviewIsDurableAndNeverMarksHardwareAsDetected() throws Exception
    {
        SetupProgress progress = SetupProgress.decode(SetupProgress.replacementReview().encode());
        assertFalse(progress.isComplete());
        assertTrue(progress.isImported());
        assertEquals(SetupProgress.State.CARRIED_OVER, progress.get(SetupStep.SOURCE));
        assertEquals(SetupProgress.State.DEFERRED, progress.get(SetupStep.HARDWARE));
        assertEquals(SetupProgress.State.PENDING, progress.get(SetupStep.REVIEW));
        assertEquals(SetupStep.ADMINISTRATOR, progress.resumeAt());
        for(SetupStep step : SetupStep.values())
            if(step != SetupStep.HARDWARE && step != SetupStep.REVIEW) progress.set(step, SetupProgress.State.CARRIED_OVER);
        assertEquals(SetupStep.REVIEW, SetupReadiness.initialStep(progress, false, false, false, false));
    }
    @Test void digitalVoiceReadinessUsesConfigurationOnly()
    {
        assertTrue(SetupReadiness.requiresJmbe("P25_PHASE1"));
        assertTrue(SetupReadiness.requiresJmbe("DMR"));
        assertFalse(SetupReadiness.requiresJmbe("NBFM"));
        assertFalse(SetupReadiness.requiresJmbe(null));
        assertFalse(SetupReadiness.requiresJmbe("unsupported"));
    }

    @Test void migratedCompatibleVoiceLibraryIsReviewedEvenWithoutDigitalAutoStart() throws Exception
    {
        SetupProgress progress = new SetupProgress(true, false);
        SetupReadiness.prepareMigratedJmbe(progress, true, true);
        progress.setComplete(false);
        //Existing progress storage retains the pending review across an interrupted upgrade.
        SetupProgress resumed = SetupProgress.decode(progress.encode());
        assertEquals(SetupProgress.State.NEEDS_ATTENTION, resumed.get(SetupStep.JMBE));
        assertEquals(SetupStep.JMBE,
            SetupReadiness.nextStep(resumed, SetupStep.SOURCE, true, true, false, true));
        assertEquals(SetupStep.JMBE, SetupReadiness.initialStep(resumed, false, true, true, false));
        assertTrue(SetupReadiness.jmbeUpdateCheckPending(resumed, false));
        resumed.set(SetupStep.JMBE, SetupProgress.State.RUNNING);
        SetupProgress interrupted = SetupProgress.decode(resumed.encode());
        assertTrue(SetupReadiness.jmbeUpdateCheckPending(interrupted, false));
        assertFalse(SetupReadiness.jmbeUpdateCheckPending(interrupted, true));
    }

    @Test void copiedLibraryReviewResumesOnceAndHonorsKeepOrDefer() throws Exception
    {
        SetupProgress imported = SetupProgress.replacementReview();
        SetupReadiness.prepareMigratedJmbe(imported, false, true);
        assertEquals(SetupProgress.State.NEEDS_ATTENTION, imported.get(SetupStep.JMBE));
        imported.set(SetupStep.JMBE, SetupProgress.State.CARRIED_OVER);
        SetupReadiness.prepareMigratedJmbe(imported, false, true);
        assertEquals(SetupProgress.State.NEEDS_ATTENTION, imported.get(SetupStep.JMBE));
        for(SetupProgress.State choice : new SetupProgress.State[]{SetupProgress.State.COMPLETE, SetupProgress.State.DEFERRED})
        {
            imported.set(SetupStep.JMBE, choice);
            SetupProgress resumed = SetupProgress.decode(imported.encode());
            SetupReadiness.prepareMigratedJmbe(resumed, false, true);
            assertEquals(choice, resumed.get(SetupStep.JMBE));
        }
    }

    @Test void missingLibrariesAndOrdinaryLaunchesDoNotCreateAnUpdateReview()
    {
        SetupProgress missing = SetupProgress.replacementReview();
        SetupReadiness.prepareMigratedJmbe(missing, true, false);
        assertEquals(SetupProgress.State.PENDING, missing.get(SetupStep.JMBE));
        SetupProgress current = new SetupProgress(true, false);
        SetupReadiness.prepareMigratedJmbe(current, false, true);
        assertEquals(SetupProgress.State.COMPLETE, current.get(SetupStep.JMBE));
    }

    @Test void autoStartReviewAcceptsUnnamedChannels()
    {
        assertEquals("(Unnamed channel)", SetupWizard.autoStartLabel(null));
        assertEquals("(Unnamed channel)", SetupWizard.autoStartLabel("  "));
        assertEquals("Dispatch", SetupWizard.autoStartLabel("Dispatch"));
    }

    @Test void completedSessionOffersDeferredBenchmarkOnNextLaunch()
    {
        SetupProgress progress = new SetupProgress(true, false);
        progress.set(SetupStep.JMBE, SetupProgress.State.DEFERRED);
        progress.set(SetupStep.CALIBRATION, SetupProgress.State.DEFERRED);
        //The shell marks a newly opened session incomplete before choosing its initial page.
        progress.setComplete(false);
        assertEquals(SetupStep.CALIBRATION, SetupReadiness.initialStep(progress, false, true, false, true));
        assertEquals(SetupStep.JMBE, SetupReadiness.initialStep(progress, false, true, true, true));
        assertEquals(SetupStep.REVIEW, SetupReadiness.initialStep(progress, false, true, false, false));
        assertEquals(SetupStep.SOURCE, SetupReadiness.initialStep(progress, true, true, true, true));
        assertEquals(SetupStep.REVIEW, SetupReadiness.initialStep(progress, false, false, false, true));
    }
}
