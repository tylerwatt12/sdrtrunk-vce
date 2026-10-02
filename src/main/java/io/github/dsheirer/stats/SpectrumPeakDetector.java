/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Bounded, worker-only tracking of steady spectral peaks. A peak is a candidate, never proof of a protocol. */
public final class SpectrumPeakDetector
{
    private static final int MAX_TRACKS = 128;
    private static final int MIN_FRAMES = 6;
    private final long mCenter;
    private final long mRate;
    private final long mHalfUsable;
    private final long mExclusion;
    private final List<Track> mTracks = new ArrayList<>();
    private int mFrames;

    SpectrumPeakDetector(long center, long rate, long usableBandwidth, long exclusion)
    {
        mCenter = center;
        mRate = rate;
        mHalfUsable = usableBandwidth / 2;
        mExclusion = Math.max(exclusion, 12_500);
    }

    void receive(DiagnosticStreamFrame frame)
    {
        if(frame == null || frame.type() != DiagnosticStreamFrame.TYPE_TUNER_FFT ||
            frame.centerFrequencyHz() != mCenter || frame.sampleRateHz() != mRate ||
            frame.valueCount() != frame.fftSize() || frame.firstBin() != 0) return;
        // TunerDiagnosticService transmits 8-bit full-band frames. Decode only on the search worker.
        int count = frame.valueCount();
        if(count < 64 || frame.encoded().length != DiagnosticStreamFrame.HEADER_BYTES + count) return;
        float[] bins = new float[count];
        ByteBuffer payload = ByteBuffer.wrap(frame.encoded()).order(ByteOrder.LITTLE_ENDIAN);
        payload.position(DiagnosticStreamFrame.HEADER_BYTES);
        for(int index = 0; index < count; index++) bins[index] = (payload.get() & 255) * 216.0f / 255 - 196;
        receive(bins);
    }

    void receive(float[] bins)
    {
        if(bins == null || bins.length < 64) return;
        double binWidth = (double)mRate / bins.length;
        float[] floor = bins.clone();
        Arrays.sort(floor);
        double noise = floor[(int)(floor.length * 0.35)];
        if(!Double.isFinite(noise)) return;
        double threshold = noise + 8;
        mFrames++;
        List<Peak> peaks = new ArrayList<>();
        for(int index = 0; index < bins.length; index++)
        {
            if(!Float.isFinite(bins[index]) || bins[index] < threshold) continue;
            int first = index;
            double strongest = bins[index];
            while(index + 1 < bins.length && bins[index + 1] >= threshold)
                strongest = Math.max(strongest, bins[++index]);
            int last = index;
            double width = (last - first + 1) * binWidth;
            if(width < 2_000 || width > 40_000) continue;
            double weight = 0;
            double weighted = 0;
            for(int bin = first; bin <= last; bin++)
            {
                double value = Math.pow(10, (bins[bin] - strongest) / 10);
                weight += value;
                weighted += (mCenter - mRate / 2.0 + (bin + 0.5) * binWidth) * value;
            }
            long frequency = Math.round(weighted / weight / 1_250) * 1_250;
            long offset = Math.abs(frequency - mCenter);
            if(offset + 6_250 > mHalfUsable || offset - 6_250 < mExclusion) continue;
            peaks.add(new Peak(frequency, strongest, strongest - noise, 1));
        }
        peaks.sort(Comparator.comparingDouble(Peak::powerDbfs).reversed());
        for(Peak peak: peaks)
        {
            Track track = mTracks.stream().filter(existing -> Math.abs(existing.frequency() - peak.frequencyHz()) <= 5_000)
                .min(Comparator.comparingLong(existing -> Math.abs(existing.frequency() - peak.frequencyHz())))
                .orElse(null);
            if(track == null && mTracks.size() < MAX_TRACKS)
            {
                track = new Track();
                mTracks.add(track);
            }
            if(track != null && track.lastFrame != mFrames)
            {
                track.lastFrame = mFrames;
                track.count++;
                track.frequencySum += peak.frequencyHz();
                track.powerSum += peak.powerDbfs();
                track.prominenceSum += peak.prominenceDb();
            }
        }
    }

    int frames() { return mFrames; }

    List<Peak> peaks()
    {
        if(mFrames < MIN_FRAMES) return List.of();
        return mTracks.stream().filter(track -> track.count >= MIN_FRAMES && track.count >= mFrames * 0.75)
            .map(track -> new Peak(Math.round(track.frequency() / 1_250.0) * 1_250,
                track.powerSum / track.count, track.prominenceSum / track.count, track.count))
            .sorted(Comparator.comparingDouble(Peak::powerDbfs).reversed()).limit(64).toList();
    }

    public record Peak(long frequencyHz, double powerDbfs, double prominenceDb, int observations) { }

    private static final class Track
    {
        long frequencySum;
        double powerSum;
        double prominenceSum;
        int count;
        int lastFrame;
        long frequency() { return count == 0 ? 0 : Math.round((double)frequencySum / count); }
    }
}
