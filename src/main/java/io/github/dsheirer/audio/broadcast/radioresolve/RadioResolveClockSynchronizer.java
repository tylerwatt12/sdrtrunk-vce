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
package io.github.dsheirer.audio.broadcast.radioresolve;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Maintains a short-lived bound between the receiver wall clock and the RadioResolve server clock.  The midpoint
 * estimate follows the same conservative rule used by simple time protocols: the server timestamp occurred
 * somewhere during the measured request round trip, so half of that trip is uncertainty rather than precision.
 */
final class RadioResolveClockSynchronizer
{
    /**
     * Allows a remote or VPN receiver to establish proof through roughly a one-second round trip. Two receivers at
     * opposite ends of this bound can still differ by at most one second, safely inside RadioResolve's two-second
     * cross-receiver call matching window.
     */
    static final long MAXIMUM_UNCERTAINTY_MILLISECONDS = 500L;
    static final long REFRESH_AFTER_NANOSECONDS = TimeUnit.SECONDS.toNanos(30L);
    static final long EXPIRE_AFTER_NANOSECONDS = TimeUnit.SECONDS.toNanos(60L);
    private static final long NANOSECONDS_PER_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1L);

    private final LongSupplier mEpochMilliseconds;
    private final LongSupplier mMonotonicNanoseconds;
    private final AtomicReference<Sample> mSample = new AtomicReference<>();

    RadioResolveClockSynchronizer()
    {
        this(System::currentTimeMillis, System::nanoTime);
    }

    RadioResolveClockSynchronizer(LongSupplier epochMilliseconds, LongSupplier monotonicNanoseconds)
    {
        mEpochMilliseconds = epochMilliseconds;
        mMonotonicNanoseconds = monotonicNanoseconds;
    }

    RequestTiming beginRequest()
    {
        long monotonic = mMonotonicNanoseconds.getAsLong();
        long epoch = mEpochMilliseconds.getAsLong();
        return new RequestTiming(epoch, monotonic);
    }

    /**
     * Completes a server-clock sample.  An imprecise response never replaces a still-useful earlier proof.
     */
    boolean completeRequest(RequestTiming request, long serverEpochMilliseconds)
    {
        long receivedMonotonic = mMonotonicNanoseconds.getAsLong();
        long receivedEpoch = mEpochMilliseconds.getAsLong();

        if(request == null || request.sentEpochMilliseconds() <= 0L || serverEpochMilliseconds <= 0L ||
            receivedEpoch <= 0L || receivedMonotonic < request.sentMonotonicNanoseconds())
        {
            return false;
        }

        long elapsedNanoseconds = receivedMonotonic - request.sentMonotonicNanoseconds();
        long elapsedMilliseconds = roundedMilliseconds(elapsedNanoseconds);
        long halfRoundTripMilliseconds = ceilingHalfMilliseconds(elapsedNanoseconds);
        long wallElapsedMilliseconds;
        long expectedReceivedEpoch;
        long midpointEpoch;

        try
        {
            wallElapsedMilliseconds = Math.subtractExact(receivedEpoch, request.sentEpochMilliseconds());
            expectedReceivedEpoch = Math.addExact(request.sentEpochMilliseconds(), elapsedMilliseconds);
            midpointEpoch = Math.addExact(request.sentEpochMilliseconds(),
                roundedHalfMilliseconds(elapsedNanoseconds));
        }
        catch(ArithmeticException exception)
        {
            return false;
        }

        long wallClockDeviation = absoluteDifference(receivedEpoch, expectedReceivedEpoch);
        long uncertainty = saturatingAdd(halfRoundTripMilliseconds, wallClockDeviation, 1L);

        if(wallElapsedMilliseconds < 0L || uncertainty > MAXIMUM_UNCERTAINTY_MILLISECONDS)
        {
            return false;
        }

        long offset;
        try
        {
            offset = Math.subtractExact(serverEpochMilliseconds, midpointEpoch);
        }
        catch(ArithmeticException exception)
        {
            return false;
        }

        Sample candidate = new Sample(offset, uncertainty, receivedEpoch, receivedMonotonic);
        mSample.accumulateAndGet(candidate, (existing, replacement) -> existing == null ||
            replacement.receivedMonotonicNanoseconds() >= existing.receivedMonotonicNanoseconds() ?
                replacement : existing);
        return true;
    }

    ClockProof currentProof()
    {
        return currentProof(mEpochMilliseconds.getAsLong(), mMonotonicNanoseconds.getAsLong());
    }

    ClockProof currentProof(long currentEpochMilliseconds, long currentMonotonicNanoseconds)
    {
        Sample sample = mSample.get();

        if(sample == null || currentEpochMilliseconds <= 0L ||
            currentMonotonicNanoseconds < sample.receivedMonotonicNanoseconds())
        {
            return null;
        }

        long ageNanoseconds = currentMonotonicNanoseconds - sample.receivedMonotonicNanoseconds();

        if(ageNanoseconds > EXPIRE_AFTER_NANOSECONDS)
        {
            return null;
        }

        long expectedCurrentEpoch;
        try
        {
            expectedCurrentEpoch = Math.addExact(sample.receivedEpochMilliseconds(),
                roundedMilliseconds(ageNanoseconds));
        }
        catch(ArithmeticException exception)
        {
            return null;
        }

        long wallClockDeviation = absoluteDifference(currentEpochMilliseconds, expectedCurrentEpoch);
        long uncertainty = saturatingAdd(sample.uncertaintyMilliseconds(), wallClockDeviation);

        if(uncertainty > MAXIMUM_UNCERTAINTY_MILLISECONDS)
        {
            return null;
        }

        long signedDeviation;
        long effectiveOffset;
        try
        {
            signedDeviation = Math.subtractExact(currentEpochMilliseconds, expectedCurrentEpoch);
            effectiveOffset = Math.subtractExact(sample.offsetMilliseconds(), signedDeviation);
        }
        catch(ArithmeticException exception)
        {
            return null;
        }

        return new ClockProof(effectiveOffset, uncertainty, sample.receivedMonotonicNanoseconds(), ageNanoseconds);
    }

    boolean needsRefresh()
    {
        long nowMonotonic = mMonotonicNanoseconds.getAsLong();
        Sample sample = mSample.get();
        return currentProof(mEpochMilliseconds.getAsLong(), nowMonotonic) == null || sample == null ||
            nowMonotonic < sample.receivedMonotonicNanoseconds() ||
            nowMonotonic - sample.receivedMonotonicNanoseconds() >= REFRESH_AFTER_NANOSECONDS;
    }

    void clear()
    {
        mSample.set(null);
    }

    private static long roundedMilliseconds(long nanoseconds)
    {
        long whole = nanoseconds / NANOSECONDS_PER_MILLISECOND;
        long remainder = nanoseconds % NANOSECONDS_PER_MILLISECOND;
        return remainder >= NANOSECONDS_PER_MILLISECOND / 2L ? saturatingAdd(whole, 1L) : whole;
    }

    private static long roundedHalfMilliseconds(long nanoseconds)
    {
        return roundedMilliseconds(nanoseconds / 2L);
    }

    private static long ceilingHalfMilliseconds(long nanoseconds)
    {
        long divisor = NANOSECONDS_PER_MILLISECOND * 2L;
        long whole = nanoseconds / divisor;
        return nanoseconds % divisor == 0L ? whole : saturatingAdd(whole, 1L);
    }

    private static long absoluteDifference(long first, long second)
    {
        try
        {
            long difference = Math.subtractExact(first, second);
            return difference == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(difference);
        }
        catch(ArithmeticException exception)
        {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(long... values)
    {
        long result = 0L;

        for(long value : values)
        {
            if(value > 0L && result > Long.MAX_VALUE - value)
            {
                return Long.MAX_VALUE;
            }

            result += value;
        }

        return result;
    }

    record RequestTiming(long sentEpochMilliseconds, long sentMonotonicNanoseconds)
    {
    }

    record ClockProof(long offsetMilliseconds, long uncertaintyMilliseconds, long sampledAtMonotonicNanoseconds,
                      long ageNanoseconds)
    {
        long adjust(long epochMilliseconds)
        {
            return Math.addExact(epochMilliseconds, offsetMilliseconds);
        }
    }

    private record Sample(long offsetMilliseconds, long uncertaintyMilliseconds, long receivedEpochMilliseconds,
                          long receivedMonotonicNanoseconds)
    {
    }
}
