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

package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TEMPORARY diagnostic that measures repeated, matching P25 encryption algorithm/key observations within the same
 * tracked call.  Remove this class and its call sites after enough live receiver data has been collected.
 */
final class P25EncryptionRepeatDiagnostic
{
    static final String ENABLED_PROPERTY = "sdrtrunk.temp.p25EncryptionRepeatDiagnostic";
    private static final Logger LOGGER = LoggerFactory.getLogger(P25EncryptionRepeatDiagnostic.class);
    private static final long CALL_INACTIVITY_TIMEOUT_MS = 5000;
    private static final int DEFAULT_REPORT_INTERVAL = 100;
    private static final P25EncryptionRepeatDiagnostic INSTANCE = new P25EncryptionRepeatDiagnostic(
        Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true")), DEFAULT_REPORT_INTERVAL,
        message -> LOGGER.info(message));

    private final boolean mEnabled;
    private final int mReportInterval;
    private final Consumer<String> mReporter;
    private final Map<P25ChannelGrantEvent,CallObservation> mActiveCalls = new IdentityHashMap<>();
    private long mCompletedCalls;
    private long mPhase1Calls;
    private long mPhase2Calls;
    private long mPhase1CallsWithMatchingRepeat;
    private long mPhase2CallsWithMatchingRepeat;
    private long mCallsWithoutMatchingRepeat;
    private long mCallsWithMatchingRepeat;
    private long mCallsWithThreeMatchingObservations;
    private long mRecognizedAlgorithmCalls;
    private long mRecognizedAlgorithmCallsWithMatchingRepeat;
    private long mCallsWithConflictingPairs;
    private long mTotalObservations;
    private long mHduObservations;
    private long mLdu2Observations;
    private long mPushToTalkObservations;
    private long mEssObservations;

    P25EncryptionRepeatDiagnostic(boolean enabled, int reportInterval, Consumer<String> reporter)
    {
        mEnabled = enabled;
        mReportInterval = Math.max(1, reportInterval);
        mReporter = reporter != null ? reporter : ignored -> {};
    }

    static void observe(Phase phase, ObservationSource source, P25ChannelGrantEvent event,
                        EncryptionKeyIdentifier identifier, long timestamp)
    {
        INSTANCE.observeCall(phase, source, event, identifier, timestamp);
    }

    static void complete(P25ChannelGrantEvent event, long timestamp)
    {
        INSTANCE.completeCall(event, timestamp);
    }

    synchronized void observeCall(Phase phase, ObservationSource source, P25ChannelGrantEvent event,
                                  EncryptionKeyIdentifier identifier, long timestamp)
    {
        if(!mEnabled || phase == null || source == null || event == null || identifier == null ||
            !identifier.isEncrypted())
        {
            return;
        }

        EncryptionKey key = identifier.getValue();

        if(key == null)
        {
            return;
        }

        long observedAt = timestamp > 0 ? timestamp : System.currentTimeMillis();
        expireInactiveCalls(observedAt);
        CallObservation call = mActiveCalls.computeIfAbsent(event,
            ignored -> new CallObservation(phase, observedAt));
        call.observe(new AlgorithmKey(key.getAlgorithm(), key.getKey()), source, observedAt);
    }

    synchronized void completeCall(P25ChannelGrantEvent event, long timestamp)
    {
        if(!mEnabled || event == null)
        {
            return;
        }

        finalizeCall(mActiveCalls.remove(event));
        expireInactiveCalls(timestamp > 0 ? timestamp : System.currentTimeMillis());
    }

    synchronized Snapshot snapshot()
    {
        return new Snapshot(mCompletedCalls, mPhase1Calls, mPhase2Calls, mPhase1CallsWithMatchingRepeat,
            mPhase2CallsWithMatchingRepeat, mCallsWithoutMatchingRepeat, mCallsWithMatchingRepeat,
            mCallsWithThreeMatchingObservations, mRecognizedAlgorithmCalls,
            mRecognizedAlgorithmCallsWithMatchingRepeat, mCallsWithConflictingPairs, mTotalObservations,
            mHduObservations, mLdu2Observations, mPushToTalkObservations, mEssObservations, mActiveCalls.size());
    }

    private void expireInactiveCalls(long timestamp)
    {
        Iterator<Map.Entry<P25ChannelGrantEvent,CallObservation>> iterator = mActiveCalls.entrySet().iterator();

        while(iterator.hasNext())
        {
            CallObservation call = iterator.next().getValue();

            if(timestamp - call.mLastObservedAt > CALL_INACTIVITY_TIMEOUT_MS)
            {
                iterator.remove();
                finalizeCall(call);
            }
        }
    }

    private void finalizeCall(CallObservation call)
    {
        if(call == null || call.mObservationCount == 0)
        {
            return;
        }

        mCompletedCalls++;
        mTotalObservations += call.mObservationCount;
        mHduObservations += call.sourceCount(ObservationSource.HDU);
        mLdu2Observations += call.sourceCount(ObservationSource.LDU2);
        mPushToTalkObservations += call.sourceCount(ObservationSource.PUSH_TO_TALK);
        mEssObservations += call.sourceCount(ObservationSource.ESS);

        if(call.mPhase == Phase.PHASE_1)
        {
            mPhase1Calls++;
        }
        else
        {
            mPhase2Calls++;
        }

        int maximumMatchingObservations = call.maximumMatchingObservations(false);
        int maximumRecognizedMatchingObservations = call.maximumMatchingObservations(true);

        if(maximumMatchingObservations >= 2)
        {
            mCallsWithMatchingRepeat++;

            if(call.mPhase == Phase.PHASE_1)
            {
                mPhase1CallsWithMatchingRepeat++;
            }
            else
            {
                mPhase2CallsWithMatchingRepeat++;
            }
        }
        else
        {
            mCallsWithoutMatchingRepeat++;
        }

        if(maximumMatchingObservations >= 3)
        {
            mCallsWithThreeMatchingObservations++;
        }

        if(maximumRecognizedMatchingObservations >= 1)
        {
            mRecognizedAlgorithmCalls++;

            if(maximumRecognizedMatchingObservations >= 2)
            {
                mRecognizedAlgorithmCallsWithMatchingRepeat++;
            }
        }

        if(call.mPairCounts.size() > 1)
        {
            mCallsWithConflictingPairs++;
        }

        if(mCompletedCalls % mReportInterval == 0)
        {
            report();
        }
    }

    private void report()
    {
        double repeatPercent = percent(mCallsWithMatchingRepeat, mCompletedCalls);
        double phase1RepeatPercent = percent(mPhase1CallsWithMatchingRepeat, mPhase1Calls);
        double phase2RepeatPercent = percent(mPhase2CallsWithMatchingRepeat, mPhase2Calls);
        double recognizedRepeatPercent = percent(mRecognizedAlgorithmCallsWithMatchingRepeat,
            mRecognizedAlgorithmCalls);
        mReporter.accept(String.format(Locale.US,
            "TEMPORARY P25 encryption-repeat diagnostic: calls=%d phase1=%d phase1Repeat=%d (%.1f%%) " +
                "phase2=%d phase2Repeat=%d (%.1f%%) noMatchingRepeat=%d matchingAtLeastTwice=%d (%.1f%%) " +
                "matchingAtLeastThreeTimes=%d recognizedAlgorithmCalls=%d recognizedMatchingAtLeastTwice=%d " +
                "(%.1f%%) conflictingPairs=%d rawObservations=%d [HDU=%d LDU2=%d PTT=%d ESS=%d] active=%d",
            mCompletedCalls, mPhase1Calls, mPhase1CallsWithMatchingRepeat, phase1RepeatPercent, mPhase2Calls,
            mPhase2CallsWithMatchingRepeat, phase2RepeatPercent, mCallsWithoutMatchingRepeat, mCallsWithMatchingRepeat,
            repeatPercent, mCallsWithThreeMatchingObservations, mRecognizedAlgorithmCalls,
            mRecognizedAlgorithmCallsWithMatchingRepeat, recognizedRepeatPercent, mCallsWithConflictingPairs,
            mTotalObservations, mHduObservations, mLdu2Observations, mPushToTalkObservations, mEssObservations,
            mActiveCalls.size()));
    }

    private static double percent(long numerator, long denominator)
    {
        return denominator > 0 ? 100.0 * numerator / denominator : 0.0;
    }

    enum Phase
    {
        PHASE_1,
        PHASE_2
    }

    enum ObservationSource
    {
        HDU,
        LDU2,
        PUSH_TO_TALK,
        ESS
    }

    record Snapshot(long completedCalls, long phase1Calls, long phase2Calls, long phase1CallsWithMatchingRepeat,
                    long phase2CallsWithMatchingRepeat, long callsWithoutMatchingRepeat, long callsWithMatchingRepeat,
                    long callsWithThreeMatchingObservations, long recognizedAlgorithmCalls,
                    long recognizedAlgorithmCallsWithMatchingRepeat, long callsWithConflictingPairs,
                    long totalObservations, long hduObservations, long ldu2Observations,
                    long pushToTalkObservations, long essObservations, int activeCalls)
    {
    }

    private record AlgorithmKey(int algorithm, int key)
    {
        boolean isRecognized()
        {
            return VoiceEncryptionAlgorithm.fromValue(VoiceEncryptionProtocol.APCO25, algorithm) != null;
        }
    }

    private static class CallObservation
    {
        private final Phase mPhase;
        private final Map<AlgorithmKey,Integer> mPairCounts = new HashMap<>();
        private final Map<ObservationSource,Integer> mSourceCounts = new EnumMap<>(ObservationSource.class);
        private long mLastObservedAt;
        private int mObservationCount;

        private CallObservation(Phase phase, long observedAt)
        {
            mPhase = phase;
            mLastObservedAt = observedAt;
        }

        private void observe(AlgorithmKey pair, ObservationSource source, long observedAt)
        {
            mPairCounts.merge(pair, 1, Integer::sum);
            mSourceCounts.merge(source, 1, Integer::sum);
            mObservationCount++;
            mLastObservedAt = Math.max(mLastObservedAt, observedAt);
        }

        private int sourceCount(ObservationSource source)
        {
            return mSourceCounts.getOrDefault(source, 0);
        }

        private int maximumMatchingObservations(boolean recognizedOnly)
        {
            int maximum = 0;

            for(Map.Entry<AlgorithmKey,Integer> entry: mPairCounts.entrySet())
            {
                if(!recognizedOnly || entry.getKey().isRecognized())
                {
                    maximum = Math.max(maximum, entry.getValue());
                }
            }

            return maximum;
        }
    }
}
