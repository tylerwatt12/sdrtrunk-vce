/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import io.github.dsheirer.controller.channel.event.PreloadDataContent;

/**
 * Processing-chain-local radio-system identity.  A template-only event applies to calls that start after the event.
 * A call-activation event also identifies the traffic call whose audio may have started while this event was being
 * handed from the control channel to an already-running traffic chain.
 */
public final class RadioSystemKeyEvent extends PreloadDataContent<String>
{
    private final long mCallStartEpochMilliseconds;
    private final Integer mTimeslot;

    public RadioSystemKeyEvent(String radioSystemKey)
    {
        this(radioSystemKey, 0L, null);
    }

    /**
     * Creates a call-scoped identity update.  The call start and optional timeslot are immutable correlation facts
     * captured by the control-channel call tracker before this event crosses to the traffic chain.
     */
    public RadioSystemKeyEvent(String radioSystemKey, long callStartEpochMilliseconds, Integer timeslot)
    {
        super(canonicalKey(radioSystemKey));
        mCallStartEpochMilliseconds = Math.max(0L, callStartEpochMilliseconds);
        mTimeslot = timeslot != null && timeslot >= 0 ? timeslot : null;
    }

    public String radioSystemKey()
    {
        return getData();
    }

    public long callStartEpochMilliseconds()
    {
        return mCallStartEpochMilliseconds;
    }

    public Integer timeslot()
    {
        return mTimeslot;
    }

    public boolean isCallActivation()
    {
        return mCallStartEpochMilliseconds > 0L;
    }

    public boolean appliesToTimeslot(int timeslot)
    {
        return mTimeslot == null || mTimeslot == timeslot;
    }

    private static String canonicalKey(String radioSystemKey)
    {
        if(radioSystemKey == null)
        {
            return null;
        }

        return RadioSystemKey.parse(radioSystemKey);
    }
}
