package io.github.dsheirer.gui.setup;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.preference.record.RecordingMode;

/** Configuration-only readiness checks; never instantiate channel/DSP consumers to inspect launch requirements. */
public final class SetupReadiness
{
    private SetupReadiness() {}

    /** Older setup could defer the account before location was offered. Run once after a source migration commits. */
    static void prepareMigratedRadioReferenceLocation(SetupProgress progress, int countryId, int stateId)
    {
        if((countryId <= 0 || stateId <= 0) &&
            progress.get(SetupStep.RADIO_REFERENCE) == SetupProgress.State.DEFERRED)
            progress.set(SetupStep.RADIO_REFERENCE, SetupProgress.State.PENDING);
    }

    /** Saved accounts alone cannot supply the regional frequency matching used by discovery. */
    static void prepareRadioReference(SetupProgress progress, boolean storedCredentials, int countryId, int stateId)
    {
        boolean ready = storedCredentials && countryId > 0 && stateId > 0;
        SetupProgress.State state = progress.get(SetupStep.RADIO_REFERENCE);
        if(ready && !progress.isDone(SetupStep.RADIO_REFERENCE) &&
            state != SetupProgress.State.NEEDS_ATTENTION && state != SetupProgress.State.DEFERRED)
            progress.set(SetupStep.RADIO_REFERENCE, progress.isImported() ?
                SetupProgress.State.CARRIED_OVER : SetupProgress.State.COMPLETE);
        if(!ready && state != SetupProgress.State.DEFERRED)
            progress.set(SetupStep.RADIO_REFERENCE, SetupProgress.State.PENDING);
    }

    /** Routine launch repairs stay brief; a completed migration also offers a missing directory location. */
    static SetupStep nextStep(SetupProgress progress, SetupStep current, boolean limitedVisit,
                              boolean jmbeNeeded, boolean benchmarkAllowed, boolean migrationCommitted)
    {
        if(!limitedVisit) return progress.next(current);
        for(SetupStep candidate: java.util.List.of(SetupStep.ADMINISTRATOR, SetupStep.WEB, SetupStep.JMBE,
            SetupStep.RADIO_REFERENCE, SetupStep.CALIBRATION))
        {
            if(candidate.ordinal() <= current.ordinal() || progress.isDone(candidate)) continue;
            if(candidate == SetupStep.JMBE && !jmbeNeeded) continue;
            if(candidate == SetupStep.RADIO_REFERENCE &&
                (!migrationCommitted || progress.get(candidate) == SetupProgress.State.DEFERRED)) continue;
            if(candidate == SetupStep.CALIBRATION && !benchmarkAllowed) continue;
            return candidate;
        }
        return SetupStep.REVIEW;
    }

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

    /** Import review does not need another inventory scan; normal launch still discovers connected radios. */
    static void prepareHardwareDiscovery(SetupProgress progress)
    {
        if(progress.isImported())
        {
            if(progress.get(SetupStep.HARDWARE) == SetupProgress.State.PENDING)
                progress.set(SetupStep.HARDWARE, SetupProgress.State.DEFERRED);
        }
        else if(progress.get(SetupStep.HARDWARE) != SetupProgress.State.DEFERRED)
        {
            progress.set(SetupStep.HARDWARE, SetupProgress.State.PENDING);
        }
    }

    public static boolean requiresJmbe(String decoderType)
    {
        if(decoderType == null) return false;
        try { return DecoderType.valueOf(decoderType).providesMBEAudioFrames(); }
        catch(IllegalArgumentException e) { return false; }
    }
}
