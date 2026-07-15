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
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Confirms P25 call encryption for metrics after two matching raw observations of the same known algorithm and key.
 * Unknown algorithms are ignored and conflicting known pairs fail closed for the remainder of the tracked call.
 */
public final class P25EncryptionConfirmationTracker
{
    private static final int REQUIRED_MATCHING_OBSERVATIONS = 2;
    private static final long INACTIVE_CALL_RETENTION_MS = 60000;
    private static final Map<P25ChannelGrantEvent,CallEvidence> CALL_EVIDENCE = new IdentityHashMap<>();

    private P25EncryptionConfirmationTracker()
    {
    }

    /**
     * Observes one raw HDU, LDU2, PTT or ESS encryption identifier for the tracked call.
     */
    public static synchronized void observe(P25ChannelGrantEvent event, EncryptionKeyIdentifier identifier,
                                            long timestamp)
    {
        if(event == null || identifier == null || !identifier.isEncrypted() || identifier.getValue() == null)
        {
            return;
        }

        EncryptionKey encryptionKey = identifier.getValue();

        if(!isKnownAlgorithm(encryptionKey.getAlgorithm()))
        {
            return;
        }

        long observedAt = timestamp > 0 ? timestamp : System.currentTimeMillis();
        expireInactiveCalls(observedAt);
        CALL_EVIDENCE.computeIfAbsent(event, ignored -> new CallEvidence(observedAt))
            .observe(new AlgorithmKey(encryptionKey.getAlgorithm(), encryptionKey.getKey()), observedAt);
    }

    /**
     * Indicates whether this exact algorithm/key pair has two matching raw observations for the tracked call.
     */
    public static synchronized boolean isConfirmed(P25ChannelGrantEvent event, Integer algorithm, Integer key)
    {
        if(event == null || algorithm == null || key == null || !isKnownAlgorithm(algorithm))
        {
            return false;
        }

        CallEvidence evidence = CALL_EVIDENCE.get(event);
        return evidence != null && evidence.isConfirmed(new AlgorithmKey(algorithm, key));
    }

    /**
     * Discards evidence when the tracked call completes.
     */
    public static synchronized void complete(P25ChannelGrantEvent event, long timestamp)
    {
        if(event != null)
        {
            CALL_EVIDENCE.remove(event);
        }

        expireInactiveCalls(timestamp > 0 ? timestamp : System.currentTimeMillis());
    }

    /**
     * Known APCO-25 encrypted algorithm IDs.  Algorithm 0x80 is explicitly unencrypted and is not in this list.
     */
    public static boolean isKnownAlgorithm(int algorithm)
    {
        return VoiceEncryptionAlgorithm.fromValue(VoiceEncryptionProtocol.APCO25, algorithm) != null;
    }

    private static void expireInactiveCalls(long timestamp)
    {
        Iterator<CallEvidence> iterator = CALL_EVIDENCE.values().iterator();

        while(iterator.hasNext())
        {
            if(timestamp - iterator.next().mLastObservedAt > INACTIVE_CALL_RETENTION_MS)
            {
                iterator.remove();
            }
        }
    }

    private record AlgorithmKey(int algorithm, int key)
    {
    }

    private static class CallEvidence
    {
        private AlgorithmKey mPair;
        private int mMatchingObservations;
        private boolean mConflicted;
        private long mLastObservedAt;

        private CallEvidence(long observedAt)
        {
            mLastObservedAt = observedAt;
        }

        private void observe(AlgorithmKey pair, long observedAt)
        {
            mLastObservedAt = Math.max(mLastObservedAt, observedAt);

            if(mConflicted)
            {
                return;
            }

            if(mPair == null)
            {
                mPair = pair;
                mMatchingObservations = 1;
            }
            else if(mPair.equals(pair))
            {
                mMatchingObservations++;
            }
            else
            {
                mConflicted = true;
            }
        }

        private boolean isConfirmed(AlgorithmKey pair)
        {
            return !mConflicted && mPair != null && mPair.equals(pair) &&
                mMatchingObservations >= REQUIRED_MATCHING_OBSERVATIONS;
        }
    }
}
