package io.github.dsheirer.gui.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetupStartupBoundaryTest
{
    @Test void graphicalSetupPrecedesReceiverConstructionAndActualWebBindPrecedesActivation() throws Exception
    {
        String startup=Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java"));
        String main=startup.substring(startup.indexOf("public static void main"));
        assertTrue(main.indexOf("SetupWizard.run(")<main.indexOf("new SDRTrunk("));
        assertTrue(startup.indexOf("SetupWizard.ensureListener(")<startup.indexOf("mTunerManager.start()"));
        assertTrue(startup.indexOf("SetupWizard.ensureListener(")<startup.indexOf("mAudioStreamingManager.start()"));
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

    @Test void hardwareSkipOnlyExistsAsAnAcknowledgedScanCancellation() throws Exception
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
        assertTrue(wizard.contains("The original is unchanged. Use Copy error when reporting this problem."));
        assertFalse(wizard.contains("Copy the technical details below"));
        assertFalse(wizard.contains("make sure there is enough free space"));
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
