/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * Stable process-local identity for one decoder call leg.
 *
 * <p>A long call can be emitted as multiple linked {@link AudioCallId} chunks.  Every chunk from that same decoder
 * call leg carries this identity so downstream resolution does not have to reconstruct the leg from a link chain.</p>
 */
public final class CallLegId
{
    private static final VarHandle INGRESS_COMPROMISED;

    static
    {
        try
        {
            INGRESS_COMPROMISED = MethodHandles.lookup()
                .findVarHandle(CallLegId.class, "mIngressCompromised", boolean.class);
        }
        catch(ReflectiveOperationException e)
        {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long mProducerId;
    private final long mSequence;
    private final int mTimeslot;
    private volatile boolean mIngressCompromised;

    public CallLegId(long producerId, long sequence, int timeslot)
    {
        mProducerId = producerId;
        mSequence = sequence;
        mTimeslot = timeslot;
    }

    public long producerId()
    {
        return mProducerId;
    }

    public long sequence()
    {
        return mSequence;
    }

    public int timeslot()
    {
        return mTimeslot;
    }

    /**
     * Marks this physical leg unsafe after an ingress rejection.  The latch is created with the stable leg identity,
     * so an overloaded receiver thread performs one allocation-free compare-and-set and returns.  It is deliberately
     * excluded from value equality and hashing.
     *
     * @return true only for the first rejected event for this physical leg
     */
    boolean markIngressCompromised()
    {
        return (boolean)INGRESS_COMPROMISED.compareAndSet(this, false, true);
    }

    boolean isIngressCompromised()
    {
        return mIngressCompromised;
    }

    /** Creates an explicit receiver-copy identity for a call that has exactly one chunk. */
    public static CallLegId from(AudioCallId callId)
    {
        Objects.requireNonNull(callId, "Audio call id is required");
        return new CallLegId(callId.producerId(), callId.sequence(), callId.timeslot());
    }

    @Override
    public boolean equals(Object object)
    {
        if(this == object)
        {
            return true;
        }

        if(!(object instanceof CallLegId other))
        {
            return false;
        }

        return mProducerId == other.mProducerId && mSequence == other.mSequence && mTimeslot == other.mTimeslot;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mProducerId, mSequence, mTimeslot);
    }

    @Override
    public String toString()
    {
        return mProducerId + ":" + mSequence + ":" + mTimeslot;
    }
}
