/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.call;

/**
 * Small immutable voice-quality summary.  It is carried with the call and is not persisted in the activity database.
 */
public record VoiceCallQuality(long decodedFrameCount, long repeatedFrameCount, long concealedFrameCount,
                               long missingFrameCount, long fecErrorCount, long fecProtectedBitCount)
{
    public static final long VOICE_FRAME_DURATION_MILLISECONDS = 20L;
    public static final VoiceCallQuality EMPTY = new VoiceCallQuality(0, 0, 0, 0, 0, 0);

    public VoiceCallQuality
    {
        decodedFrameCount = Math.max(0, decodedFrameCount);
        repeatedFrameCount = Math.max(0, repeatedFrameCount);
        concealedFrameCount = Math.max(0, concealedFrameCount);
        missingFrameCount = Math.max(0, missingFrameCount);
        fecErrorCount = Math.max(0, fecErrorCount);
        fecProtectedBitCount = Math.max(0, fecProtectedBitCount);
    }

    public long observedFrameCount()
    {
        return decodedFrameCount + repeatedFrameCount + concealedFrameCount;
    }

    public long expectedFrameCount()
    {
        return observedFrameCount() + missingFrameCount;
    }

    public boolean hasMeasurements()
    {
        return observedFrameCount() > 0;
    }

    public double qualityPercent()
    {
        long expected = expectedFrameCount();
        return expected > 0 ? 100.0d * decodedFrameCount / expected : 0.0d;
    }

    public double missingAndConcealedRate()
    {
        long expected = expectedFrameCount();
        return expected > 0 ? (double)(missingFrameCount + concealedFrameCount) / expected : 1.0d;
    }

    public double repeatedRate()
    {
        long expected = expectedFrameCount();
        return expected > 0 ? (double)repeatedFrameCount / expected : 1.0d;
    }

    public double normalizedFecCorrectionRate()
    {
        return fecProtectedBitCount > 0 ? (double)fecErrorCount / fecProtectedBitCount : 1.0d;
    }

    public VoiceCallQuality withExpectedFrameCount(long expectedFrameCount)
    {
        long expected = Math.max(expectedFrameCount, observedFrameCount());
        return new VoiceCallQuality(decodedFrameCount, repeatedFrameCount, concealedFrameCount,
            expected - observedFrameCount(), fecErrorCount, fecProtectedBitCount);
    }

    public static long expectedFrameCount(long startTimestamp, long lastActivityTimestamp)
    {
        long duration = Math.max(0L, lastActivityTimestamp - startTimestamp);
        return Math.max(1L, (duration + VOICE_FRAME_DURATION_MILLISECONDS - 1L) /
            VOICE_FRAME_DURATION_MILLISECONDS);
    }
}
