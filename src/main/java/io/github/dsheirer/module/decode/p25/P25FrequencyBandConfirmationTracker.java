/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.metadata.site.FactConfirmationPolicy;
import io.github.dsheirer.metadata.site.StableFactTracker;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import java.util.HashMap;
import java.util.Map;

/**
 * Confirms repeated over-the-air identifier-update messages before making a band plan operational.  Preloaded
 * configuration is trusted immediately.  A clean message requires two matching observations; a corrected message
 * requires three.
 */
public class P25FrequencyBandConfirmationTracker
{
    private static final long CANDIDATE_TTL_MILLISECONDS = 120_000L;
    private static final FactConfirmationPolicy CLEAN_POLICY =
        new FactConfirmationPolicy(2, 1L, CANDIDATE_TTL_MILLISECONDS, false);
    private static final FactConfirmationPolicy CORRECTED_POLICY =
        new FactConfirmationPolicy(3, 1L, CANDIDATE_TTL_MILLISECONDS, false);
    private final Map<Integer,StableFactTracker<IFrequencyBand,BandKey>> mTrackers = new HashMap<>();

    public ObservationResult observe(Map<Integer,IFrequencyBand> frequencyBands, IFrequencyBand candidate,
                                     boolean trusted)
    {
        long timestamp = candidate instanceof IMessage message && message.getTimestamp() > 0 ?
            message.getTimestamp() : System.currentTimeMillis();
        return observe(frequencyBands, candidate, trusted, timestamp);
    }

    ObservationResult observe(Map<Integer,IFrequencyBand> frequencyBands, IFrequencyBand candidate,
                              boolean trusted, long timestamp)
    {
        P25FrequencyBandValidator.RejectReason rejectReason = P25FrequencyBandValidator.validate(candidate);

        if(rejectReason != null)
        {
            return ObservationResult.rejected(
                P25FrequencyBandValidator.RegistrationResult.rejected(candidate, null, rejectReason));
        }

        IFrequencyBand existing = frequencyBands.get(candidate.getIdentifier());

        if(existing != null && P25FrequencyBandValidator.matches(existing, candidate))
        {
            return ObservationResult.accepted(P25FrequencyBandValidator.register(frequencyBands, candidate));
        }

        //Plain P25FrequencyBand instances are constructed from already accepted preload/configuration data. OTA
        //identifier-update implementations also implement IMessage and must pass the repetition gate.
        if(trusted || !(candidate instanceof IMessage))
        {
            return ObservationResult.accepted(P25FrequencyBandValidator.register(frequencyBands, candidate));
        }

        StableFactTracker<IFrequencyBand,BandKey> tracker = mTrackers.computeIfAbsent(candidate.getIdentifier(),
            ignored -> new StableFactTracker<>(BandKey::from));
        FactConfirmationPolicy policy = P25FrequencyBandValidator.getCorrectedBitCount(candidate) > 0 ?
            CORRECTED_POLICY : CLEAN_POLICY;
        StableFactTracker.Result result = tracker.observe(candidate, timestamp, policy, ignored -> true);

        if(result != StableFactTracker.Result.PROMOTED)
        {
            return ObservationResult.pendingResult();
        }

        P25FrequencyBandValidator.RegistrationResult registration =
            P25FrequencyBandValidator.register(frequencyBands, candidate);
        tracker.resetCandidate();
        return registration.accepted() ? ObservationResult.accepted(registration) :
            ObservationResult.rejected(registration);
    }

    public void reset()
    {
        mTrackers.clear();
    }

    private record BandKey(int identifier, long baseFrequency, long channelSpacing, int bandwidth,
                           long transmitOffset, int timeslots)
    {
        private static BandKey from(IFrequencyBand band)
        {
            return new BandKey(band.getIdentifier(), band.getBaseFrequency(), band.getChannelSpacing(),
                band.getBandwidth(), band.getTransmitOffset(), band.getTimeslotCount());
        }
    }

    public enum State
    {
        ACCEPTED,
        PENDING,
        REJECTED
    }

    public record ObservationResult(State state, P25FrequencyBandValidator.RegistrationResult registration)
    {
        private static ObservationResult accepted(P25FrequencyBandValidator.RegistrationResult registration)
        {
            return new ObservationResult(State.ACCEPTED, registration);
        }

        private static ObservationResult pendingResult()
        {
            return new ObservationResult(State.PENDING, null);
        }

        private static ObservationResult rejected(P25FrequencyBandValidator.RegistrationResult registration)
        {
            return new ObservationResult(State.REJECTED, registration);
        }

        public boolean accepted()
        {
            return state == State.ACCEPTED;
        }

        public boolean pending()
        {
            return state == State.PENDING;
        }

        public boolean rejected()
        {
            return state == State.REJECTED;
        }
    }
}
