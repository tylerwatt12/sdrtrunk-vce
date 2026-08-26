/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/**
 * Compact physical-leg state. It deliberately contains no audio, raw fingerprint, encryption message indicator,
 * mutable identifier collection, alias object, or decoder object graph.
 */
public record LogicalCallDiagnosticLeg(String legId, String decoder, String channelConfigurationId,
                                       String channelName, String siteGuid, long durableAliasListId, Integer wacn,
                                       Integer system, Integer rfss, Integer site, long startTimestamp, long endTimestamp,
                                       long durationMilliseconds, long expectedFrameCount, long observedFrameCount,
                                       long usableFrameCount, long decodedFrameCount, long repeatedFrameCount,
                                       long concealedFrameCount, long missingFrameCount, long fecErrorCount,
                                       long fecProtectedBitCount, double qualityPercent,
                                       double missingAndConcealedRate, double repeatedFrameRate,
                                       double normalizedFecErrorRate, long retainedAudioSampleCount,
                                       boolean ingressLoss, boolean audioTruncated, boolean winner)
{
    public LogicalCallDiagnosticLeg
    {
        durableAliasListId = Math.max(0L, durableAliasListId);
        startTimestamp = Math.max(0L, startTimestamp);
        endTimestamp = Math.max(startTimestamp, endTimestamp);
        durationMilliseconds = Math.max(0L, durationMilliseconds);
        expectedFrameCount = Math.max(0L, expectedFrameCount);
        observedFrameCount = Math.max(0L, observedFrameCount);
        usableFrameCount = Math.max(0L, usableFrameCount);
        decodedFrameCount = Math.max(0L, decodedFrameCount);
        repeatedFrameCount = Math.max(0L, repeatedFrameCount);
        concealedFrameCount = Math.max(0L, concealedFrameCount);
        missingFrameCount = Math.max(0L, missingFrameCount);
        fecErrorCount = Math.max(0L, fecErrorCount);
        fecProtectedBitCount = Math.max(0L, fecProtectedBitCount);
        qualityPercent = finiteNonnegative(qualityPercent);
        missingAndConcealedRate = finiteNonnegative(missingAndConcealedRate);
        repeatedFrameRate = finiteNonnegative(repeatedFrameRate);
        normalizedFecErrorRate = finiteNonnegative(normalizedFecErrorRate);
        retainedAudioSampleCount = Math.max(0L, retainedAudioSampleCount);
    }

    private static double finiteNonnegative(double value)
    {
        return Double.isFinite(value) ? Math.max(0.0d, value) : 0.0d;
    }
}
