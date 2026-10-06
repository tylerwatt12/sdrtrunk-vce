package io.github.dsheirer.gui.setup;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.preference.record.RecordingMode;

/** Configuration-only readiness checks; never instantiate channel/DSP consumers to inspect launch requirements. */
public final class SetupReadiness
{
    private SetupReadiness() {}

    /** A later launch offers outstanding work again, even if it was deferred in the completed session. */
    static SetupStep initialStep(SetupProgress progress, boolean forced, boolean limitedVisit,
                                 boolean jmbeNeeded, boolean benchmarkNeeded)
    {
        if(forced) return SetupStep.SOURCE;
        if(!progress.isDone(SetupStep.ADMINISTRATOR)) return SetupStep.ADMINISTRATOR;
        if(!progress.isDone(SetupStep.WEB)) return SetupStep.WEB;
        if(!limitedVisit) return progress.resumeAt();
        if(jmbeNeeded) return SetupStep.JMBE;
        if(benchmarkNeeded) return SetupStep.CALIBRATION;
        return SetupStep.REVIEW;
    }

    /** A copied profile retains its explicit recording choice even though destination review resets the steps. */
    static void carryOverRecordingChoice(SetupProgress progress, RecordingMode configuredMode)
    {
        if(progress.isImported() && configuredMode != null &&
            progress.get(SetupStep.RECORDINGS) == SetupProgress.State.PENDING)
        {
            progress.set(SetupStep.RECORDINGS, SetupProgress.State.CARRIED_OVER);
        }
    }

    public static boolean requiresJmbe(String decoderType)
    {
        if(decoderType == null) return false;
        try { return DecoderType.valueOf(decoderType).providesMBEAudioFrames(); }
        catch(IllegalArgumentException e) { return false; }
    }
}
