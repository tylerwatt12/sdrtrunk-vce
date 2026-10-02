/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.web.tuner.TunerAdministrationService;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns one idle receiver, the existing bounded FFT worker, and sequential short P25 checks. */
public final class SpectrumSearchHardware
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SpectrumSearchHardware.class);
    private final TunerAdministrationService mTuners;
    private final TunerDiagnosticService mDiagnostics;
    private final TunerSettingsService mSettings;

    public SpectrumSearchHardware(TunerAdministrationService tuners, TunerDiagnosticService diagnostics,
                                  TunerSettingsService settings)
    {
        mTuners = tuners;
        mDiagnostics = diagnostics;
        mSettings = settings;
    }

    public Lease open(String tunerId, String browseLeaseId)
    {
        DiscoveredTuner selected = mTuners.find(tunerId);
        if(selected == null) throw new IllegalArgumentException("The selected receiver is unavailable");
        TunerSettingsService.ProbeHold hold = mSettings.holdForSearch(selected, browseLeaseId);
        try { return new ReceiverLease(selected, browseLeaseId, hold); }
        catch(RuntimeException exception) { hold.close(); throw exception; }
    }

    public interface Lease extends AutoCloseable
    {
        Tuner tuner();
        String targetId();
        long usableBandwidthHz();
        long sampleRateHz();
        long middleUnusableHalfBandwidthHz();
        long minimumFrequencyHz();
        long maximumFrequencyHz();
        long centerFrequencyHz();
        void tune(long frequencyHz);
        List<SpectrumPeakDetector.Peak> observe(long dwellMillis, BooleanSupplier cancelled) throws InterruptedException;
        P25DiscoveryProbe.Session probe(long frequencyHz);
        boolean valid();
        <T> T handoff(long centerHz, Supplier<T> start);
        @Override void close();
    }

    /** Expected search-worker observation failure with safe operator-facing copy. */
    public static final class ObservationException extends IllegalStateException
    {
        public ObservationException(String message) { super(message); }
    }

    static void requireFreshFrames(long dwellMillis, int frames)
    {
        if(frames < 6)
        {
            // Called only by the search worker, after bounded polling. Never log receiver IDs or frame contents.
            LOGGER.warn("Spectrum search observation received {} fresh frames; required 6 within {} ms", frames, dwellMillis);
            throw new ObservationException("The receiver did not provide enough new spectrum data. Retry the search or choose another receiver.");
        }
    }

    private final class ReceiverLease implements Lease
    {
        private final DiscoveredTuner selected;
        private final Tuner runtime;
        private final String browseId;
        private final String target;
        private final TunerSettingsService.ProbeHold hold;
        private final P25DiscoveryProbe probes;
        private TunerDiagnosticService.Session spectrum;
        private P25DiscoveryProbe.Session activeProbe;
        private long freshAfter;
        private boolean closed;

        ReceiverLease(DiscoveredTuner selected, String browseId, TunerSettingsService.ProbeHold hold)
        {
            this.selected = selected;
            this.browseId = browseId;
            this.hold = hold;
            runtime = selected.getTuner();
            target = mDiagnostics.targetIdFor(runtime);
            probes = new P25DiscoveryProbe(mDiagnostics, hold);
            freshAfter = System.currentTimeMillis() + 250;
        }

        public Tuner tuner() { return runtime; }
        public String targetId() { return target; }
        public long usableBandwidthHz() { return runtime.getTunerController().getUsableBandwidth(); }
        public long sampleRateHz() { return Math.round(runtime.getTunerController().getSampleRate()); }
        public long middleUnusableHalfBandwidthHz() { return runtime.getTunerController().getMiddleUnusableHalfBandwidth(); }
        public long minimumFrequencyHz() { return runtime.getTunerController().getMinimumFrequency(); }
        public long maximumFrequencyHz() { return runtime.getTunerController().getMaximumFrequency(); }
        public long centerFrequencyHz() { return runtime.getTunerController().getFrequency(); }
        public boolean valid() { return !closed && hold.valid() && mSettings.verifyBrowse(selected, browseId); }

        public void tune(long frequencyHz)
        {
            if(!valid()) throw new IllegalStateException("The search receiver changed; begin again");
            if(activeProbe != null) { activeProbe.close(); activeProbe = null; }
            hold.tune(frequencyHz);
            freshAfter = System.currentTimeMillis() + 250;
        }

        private void openSpectrum() throws InterruptedException
        {
            if(spectrum != null && !spectrum.isClosed()) return;
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while(valid())
            {
                var result = mDiagnostics.tryOpen(target, null, TunerDiagnosticService.SpectrumProfile.HIGH_DETAIL);
                if(result.status() == TunerDiagnosticService.OpenStatus.OPEN)
                {
                    spectrum = result.session();
                    return;
                }
                if(result.status() != TunerDiagnosticService.OpenStatus.BUSY || System.nanoTime() >= deadline)
                    throw new ObservationException("Close other Spectrum views and retry the search");
                Thread.sleep(50);
            }
            throw new ObservationException("The search receiver is unavailable. Check its connection and retry.");
        }

        public List<SpectrumPeakDetector.Peak> observe(long dwellMillis, BooleanSupplier cancelled) throws InterruptedException
        {
            openSpectrum();
            long center = centerFrequencyHz();
            SpectrumPeakDetector detector = new SpectrumPeakDetector(center, sampleRateHz(), usableBandwidthHz(),
                middleUnusableHalfBandwidthHz());
            long deadline = System.nanoTime() + Duration.ofMillis(dwellMillis).toNanos();
            while(System.nanoTime() < deadline && !cancelled.getAsBoolean())
            {
                if(!valid()) throw new ObservationException("The search receiver changed. Choose an idle receiver and retry.");
                DiagnosticStreamFrame frame = spectrum.poll(Duration.ofMillis(100));
                if(frame != null && frame.observedAtEpochMs() >= freshAfter) detector.receive(frame);
                if(spectrum.isClosed()) throw new ObservationException("The receiver stopped providing spectrum data. Check its connection and retry.");
            }
            if(!cancelled.getAsBoolean()) requireFreshFrames(dwellMillis, detector.frames());
            return detector.peaks();
        }

        public P25DiscoveryProbe.Session probe(long frequencyHz)
        {
            if(!valid()) throw new IllegalStateException("The search receiver changed; begin again");
            if(spectrum != null) { spectrum.close(); spectrum = null; }
            if(activeProbe != null) activeProbe.close();
            activeProbe = probes.open(target, frequencyHz);
            return activeProbe;
        }

        public <T> T handoff(long centerHz, Supplier<T> start)
        {
            if(!valid()) throw new IllegalStateException("The search receiver changed; channels are saved for later");
            probes.close();
            if(spectrum != null) { spectrum.close(); spectrum = null; }
            tune(centerHz);
            hold.close();
            closed = true;
            return mSettings.handoffBrowse(selected, browseId, start).join();
        }

        public void close()
        {
            if(closed) return;
            try
            {
                probes.close();
                if(spectrum != null) { spectrum.close(); spectrum = null; }
                if(hold.valid() && runtime.getChannelSourceManager().getTunerChannelCount() == 0)
                    hold.restoreSearchCenter();
            }
            finally { closed = true; hold.close(); }
        }
    }
}
