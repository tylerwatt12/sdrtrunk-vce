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
        String wizard=Files.readString(Path.of("src/main/java/io/github/dsheirer/gui/setup/SetupWizard.java"));
        for(String forbidden:new String[]{"new TunerManager", "ChannelProcessingManager", "JmbeEditorRequest", "ApplicationMigrationProgressDialog.run"})
            assertFalse(wizard.contains(forbidden),forbidden);
    }
    @Test void digitalVoiceReadinessUsesConfigurationOnly()
    {
        assertTrue(SetupReadiness.requiresJmbe("P25_PHASE1"));
        assertTrue(SetupReadiness.requiresJmbe("DMR"));
        assertFalse(SetupReadiness.requiresJmbe("NBFM"));
        assertFalse(SetupReadiness.requiresJmbe(null));
        assertFalse(SetupReadiness.requiresJmbe("unsupported"));
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
