package io.github.dsheirer.gui.setup;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.preference.record.RecordingMode;

/** Configuration-only readiness checks; never instantiate channel/DSP consumers to inspect launch requirements. */
public final class SetupReadiness
{
    private SetupReadiness() {}

    /** A carried-over voice library still needs an update check in the destination's setup review. */
    static void prepareMigratedJmbe(SetupProgress progress, boolean migrationCommitted, boolean compatible)
    {
        boolean importedPending = progress.isImported() && !progress.isComplete() &&
            (progress.get(SetupStep.JMBE) == SetupProgress.State.PENDING ||
                progress.get(SetupStep.JMBE) == SetupProgress.State.CARRIED_OVER);
        if(compatible && (migrationCommitted || importedPending))
            progress.set(SetupStep.JMBE, SetupProgress.State.NEEDS_ATTENTION);
    }

    static boolean jmbeUpdateCheckPending(SetupProgress progress, boolean checked)
    {
        //An interrupted lookup is saved as RUNNING and resumes as PENDING.
        return !checked && (progress.get(SetupStep.JMBE) == SetupProgress.State.NEEDS_ATTENTION ||
            progress.get(SetupStep.JMBE) == SetupProgress.State.PENDING);
    }

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

    /** A completed profile revisits only the settings required to start it. */
    static SetupStep nextStep(SetupProgress progress, SetupStep current, boolean limitedVisit)
    {
        if(!limitedVisit) return progress.next(current);
        for(SetupStep candidate: java.util.List.of(SetupStep.ADMINISTRATOR, SetupStep.WEB))
        {
            if(candidate.ordinal() <= current.ordinal() || progress.isDone(candidate)) continue;
            return candidate;
        }
        return SetupStep.REVIEW;
    }

    /** Interrupted setup resumes; a completed profile opens only its required repair. */
    static SetupStep initialStep(SetupProgress progress, boolean limitedVisit)
    {
        if(!progress.isDone(SetupStep.ADMINISTRATOR)) return SetupStep.ADMINISTRATOR;
        if(!progress.isDone(SetupStep.WEB)) return SetupStep.WEB;
        if(!limitedVisit) return progress.resumeAt();
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
