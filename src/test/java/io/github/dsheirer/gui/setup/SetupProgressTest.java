package io.github.dsheirer.gui.setup;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.dsheirer.gui.setup.SetupProgress.State.*;

class SetupProgressTest
{
    @Test void fixedLineageAndOnlyValidCompletionIsSkipped()
    {
        assertEquals(9, SetupStep.values().length);
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
}
