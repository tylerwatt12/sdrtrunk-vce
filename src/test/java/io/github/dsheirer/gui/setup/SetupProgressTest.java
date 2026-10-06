package io.github.dsheirer.gui.setup;

import java.sql.SQLException;
import io.github.dsheirer.preference.record.RecordingMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.dsheirer.gui.setup.SetupProgress.State.*;

class SetupProgressTest
{
    @Test void fixedLineageAndOnlyValidCompletionIsSkipped()
    {
        assertEquals(10, SetupStep.values().length);
        var progress = new SetupProgress(false,true);
        progress.set(SetupStep.ADMINISTRATOR,CARRIED_OVER);
        progress.set(SetupStep.WEB,COMPLETE);
        progress.set(SetupStep.JMBE,DEFERRED);
        assertEquals(SetupStep.RADIO_REFERENCE,progress.next(SetupStep.SOURCE));
        assertFalse(progress.isDone(SetupStep.JMBE));
        progress.set(SetupStep.JMBE,NEEDS_ATTENTION);
        assertEquals(SetupStep.JMBE,progress.next(SetupStep.SOURCE));
        progress.set(SetupStep.JMBE,COMPLETE);
        assertEquals(SetupStep.RADIO_REFERENCE,progress.next(SetupStep.SOURCE));
        assertEquals(SetupStep.HARDWARE,SetupStep.values()[SetupStep.CALIBRATION.ordinal()-1]);
    }

    @Test void interruptedJobsResumePendingWithoutRepeatingAcceptedWork() throws Exception
    {
        var progress = new SetupProgress(false,true);
        progress.set(SetupStep.SOURCE,COMPLETE);
        progress.set(SetupStep.ADMINISTRATOR,CARRIED_OVER);
        progress.set(SetupStep.JMBE,RUNNING);
        progress.set(SetupStep.RADIO_REFERENCE,DEFERRED);
        var restored=SetupProgress.decode(progress.encode());
        assertTrue(restored.isDone(SetupStep.SOURCE));
        assertEquals(CARRIED_OVER,restored.get(SetupStep.ADMINISTRATOR));
        assertEquals(PENDING,restored.get(SetupStep.JMBE));
        assertEquals(DEFERRED,restored.get(SetupStep.RADIO_REFERENCE));
        assertFalse(restored.isComplete());
        assertTrue(restored.isImported());
    }

    @Test void boundedRecordRefusesExtraDraftDataAndUnknownStates()
    {
        String record=new SetupProgress(false,false).encode();
        assertThrows(SQLException.class,()->SetupProgress.decode(record.replace("PENDING","SECRET")));
        assertThrows(SQLException.class,()->SetupProgress.decode(record.replace("\"complete\":false","\"complete\":false,\"password\":\"draft\"")));
        assertThrows(SQLException.class,()->SetupProgress.decode("x".repeat(4097)));
        assertThrows(SQLException.class,()->SetupProgress.decode(null));
        assertThrows(SQLException.class,()->SetupProgress.decode(record + " {}"));
        assertThrows(SQLException.class,()->SetupProgress.decode(record.replace("\"complete\":false", "\"complete\":false,\"complete\":true")));
    }

    @Test void noisyOutputRetainsOnlyBoundedRecentDiagnostics()
    {
        SetupJobOutput output=new SetupJobOutput();
        for(int i=0;i<10000;i++) output.accept("x".repeat(4000));
        output.accept("final status");
        assertTrue(output.snapshot().length()<=65536);
        assertTrue(output.snapshot().endsWith("final status\n"));
    }

    @Test void freshRecordingChoiceRecommendsManagedButExistingAndImportedModesArePreserved()
    {
        SetupProgress fresh = new SetupProgress(false, false);
        assertTrue(SetupWizard.selectManagedByDefault(fresh, RecordingMode.CLASSIC));
        fresh.set(SetupStep.RECORDINGS, COMPLETE);
        assertFalse(SetupWizard.selectManagedByDefault(fresh, RecordingMode.CLASSIC));
        assertTrue(SetupWizard.selectManagedByDefault(fresh, RecordingMode.MANAGED));
        SetupProgress imported = SetupProgress.replacementReview();
        assertFalse(SetupWizard.selectManagedByDefault(imported, RecordingMode.CLASSIC));
        assertTrue(SetupWizard.selectManagedByDefault(imported, RecordingMode.MANAGED));
        assertFalse(SetupWizard.selectManagedByDefault(new SetupProgress(true, false), RecordingMode.CLASSIC));
    }

    @Test void legacyProgressHasExactNineStepShapeAndCarriesOverRecordingChoice() throws Exception
    {
        SetupProgress legacy = new SetupProgress(false, false);
        legacy.set(SetupStep.ACTIVITY, COMPLETE);
        SetupProgress upgraded = SetupProgress.decodeLegacy(legacy.encodeLegacy());
        assertEquals(COMPLETE, upgraded.get(SetupStep.ACTIVITY));
        assertEquals(CARRIED_OVER, upgraded.get(SetupStep.RECORDINGS));
        assertThrows(SQLException.class, () -> SetupProgress.decode(legacy.encodeLegacy()));
        assertThrows(SQLException.class, () -> SetupProgress.decodeLegacy(legacy.encode()));
    }

    @Test void copiedProfileSkipsItsExplicitRecordingChoiceAndKeepsTheSelectedMode() throws Exception
    {
        for(RecordingMode mode: RecordingMode.values())
        {
            SetupProgress imported = new SetupProgress(false, true);
            SetupReadiness.carryOverRecordingChoice(imported, mode);
            assertEquals(CARRIED_OVER, imported.get(SetupStep.RECORDINGS));
            assertEquals(SetupStep.HARDWARE, imported.next(SetupStep.ACTIVITY));
            SetupProgress restored = SetupProgress.decode(imported.encode());
            assertEquals(CARRIED_OVER, restored.get(SetupStep.RECORDINGS));
            assertEquals(mode == RecordingMode.MANAGED, SetupWizard.selectManagedByDefault(restored, mode),
                "Manually revisiting Recordings must retain the imported selection");
        }
    }

    @Test void missingImportedChoiceAndFreshProfilesStillNeedRecordingReview()
    {
        SetupProgress imported = new SetupProgress(false, true);
        SetupReadiness.carryOverRecordingChoice(imported, null);
        assertEquals(PENDING, imported.get(SetupStep.RECORDINGS));
        assertEquals(SetupStep.RECORDINGS, imported.next(SetupStep.ACTIVITY));

        SetupProgress fresh = new SetupProgress(false, false);
        SetupReadiness.carryOverRecordingChoice(fresh, RecordingMode.CLASSIC);
        assertEquals(PENDING, fresh.get(SetupStep.RECORDINGS));
        assertTrue(SetupWizard.selectManagedByDefault(fresh, RecordingMode.CLASSIC));
    }

    @Test void recordingCarryOverPreservesExistingReviewAndCompletionStates()
    {
        for(SetupProgress.State state: new SetupProgress.State[]{NEEDS_ATTENTION, DEFERRED, COMPLETE, CARRIED_OVER})
        {
            SetupProgress imported = new SetupProgress(false, true);
            imported.set(SetupStep.RECORDINGS, state);
            SetupReadiness.carryOverRecordingChoice(imported, RecordingMode.MANAGED);
            SetupReadiness.carryOverRecordingChoice(imported, null);
            assertEquals(state, imported.get(SetupStep.RECORDINGS));
        }
    }
}
