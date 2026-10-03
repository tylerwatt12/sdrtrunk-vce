/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelSource;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderC4FM;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderLSM;
import io.github.dsheirer.module.decode.p25.phase1.P25P1NetworkConfigurationMonitor;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCNetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCRFSSStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.NetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.RFSSStatusBroadcast;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.util.concurrent.BoundedSpscReferenceQueue;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One temporary P25 control-channel probe. The source callback only offers sample references; both existing
 * decoders and all evidence projection run on one lower-priority worker. No processing chain, traffic manager,
 * audio module, recorder, or tuner frequency-correction listener is attached.
 */
public final class P25DiscoveryProbe implements AutoCloseable
{
    static final long TIMEOUT_MILLISECONDS = 30_000;
    static final long MINIMUM_IDENTITY_SPAN_MILLISECONDS = 2_000;
    //The required three fresh broadcasts must already span two seconds. Strong signals do not need another
    //three seconds of idle dwell after proving the same serving identity in both decoder comparisons.
    static final long MINIMUM_OBSERVATION_MILLISECONDS = MINIMUM_IDENTITY_SPAN_MILLISECONDS;
    static final int MINIMUM_IDENTITY_OBSERVATIONS = 3;
    static final int MINIMUM_CONTROL_MESSAGES = 20;
    static final double MINIMUM_QUALITY_PERCENT = 60;
    static final double MINIMUM_QUALITY_SEPARATION = 5;
    static final int SAMPLE_QUEUE_CAPACITY = 8;
    private static final long STATUS_INTERVAL_MILLISECONDS = 250;
    private static final Logger LOGGER = LoggerFactory.getLogger(P25DiscoveryProbe.class);
    private final SourceFactory mSources;
    private final DecoderFactory mDecoders;
    private final LongSupplier mClock;
    private Session mActive;
    private boolean mClosed;

    public P25DiscoveryProbe(TunerDiagnosticService tuners, TunerSettingsService settings)
    {
        this((targetId, frequencyHz) -> allocate(tuners, settings, targetId, frequencyHz),
            P25DiscoveryProbe::decoder, System::currentTimeMillis);
        Objects.requireNonNull(tuners);
        Objects.requireNonNull(settings);
    }

    /** A band search already owns idle hardware; each short probe borrows that reservation. */
    public P25DiscoveryProbe(TunerDiagnosticService tuners, TunerSettingsService.ProbeHold searchHold)
    {
        this((targetId, frequencyHz) -> allocate(tuners, null, targetId, frequencyHz,
            Objects.requireNonNull(searchHold)), P25DiscoveryProbe::decoder, System::currentTimeMillis);
    }

    /** Hardware/decoder seam for exercising saturated and stalled consumers without receiver hardware. */
    P25DiscoveryProbe(SourceFactory sources, DecoderFactory decoders, LongSupplier clock)
    {
        mSources = Objects.requireNonNull(sources);
        mDecoders = Objects.requireNonNull(decoders);
        mClock = Objects.requireNonNull(clock);
    }

    public synchronized Session open(String targetId, long frequencyHz)
    {
        if(mClosed)
        {
            throw new IllegalStateException("P25 discovery is unavailable");
        }
        if(mActive != null)
        {
            throw new IllegalStateException("A P25 discovery probe is already in use");
        }
        if(targetId == null || targetId.isBlank() || frequencyHz <= 0)
        {
            throw new IllegalArgumentException("Choose a tuner and a valid frequency");
        }

        SourceLease source = mSources.open(targetId, frequencyHz);
        try
        {
            Session session = new Session(targetId, frequencyHz, source);
            mActive = session;
            session.start();
            return session;
        }
        catch(RuntimeException exception)
        {
            source.close();
            mActive = null;
            throw exception;
        }
    }

    private static SourceLease allocate(TunerDiagnosticService tuners, TunerSettingsService settings,
                                        String targetId, long frequencyHz)
    {
        return allocate(tuners, settings, targetId, frequencyHz, null);
    }

    private static SourceLease allocate(TunerDiagnosticService tuners, TunerSettingsService settings,
                                        String targetId, long frequencyHz,
                                        TunerSettingsService.ProbeHold borrowedHold)
    {
        Tuner tuner = tuners.tunerForTarget(targetId);
        if(tuner == null || !(tuner.getChannelSourceManager() instanceof PolyphaseChannelSourceManager manager))
        {
            throw new IllegalStateException("Selected tuner is unavailable for P25 discovery");
        }

        TunerSettingsService.ProbeHold hold = borrowedHold != null ? borrowedHold : settings.holdForProbe(tuner);
        try
        {
            long center = tuner.getTunerController().getFrequency();
            long halfUsable = tuner.getTunerController().getUsableBandwidth() / 2L;
            long exclusion = tuner.getTunerController().getMiddleUnusableHalfBandwidth();
            DecodeConfigP25Phase1 configuration = new DecodeConfigP25Phase1();
            int bandwidth = configuration.getChannelSpecification().getBandwidth();
            if(!FrequencyListenService.withinCurrentWindow(frequencyHz, center, bandwidth, halfUsable, exclusion))
            {
                throw new IllegalArgumentException("Frequency is outside this tuner's usable receiver window");
            }
            TunerChannelSource source = manager.getSourceAtCurrentCenter(new TunerChannel(frequencyHz, bandwidth),
                configuration.getChannelSpecification(), "P25 discovery");
            if(source == null)
            {
                throw new IllegalStateException("The tuner cannot probe this frequency without retuning");
            }
            return new SourceLease()
            {
                private final AtomicBoolean mSamplesStopped = new AtomicBoolean();
                private final AtomicBoolean mReleased = new AtomicBoolean();

                @Override
                public double sampleRate()
                {
                    return source.getSampleRate();
                }

                @Override
                public boolean valid()
                {
                    return !mReleased.get() && hold.valid();
                }

                @Override
                public long droppedSampleBatches()
                {
                    return source instanceof PolyphaseChannelSource polyphase ?
                        polyphase.getOutputQueueStatus().droppedBatches() : 0;
                }

                @Override
                public void start(Listener<ComplexSamples> samples, Listener<SourceEvent> events)
                {
                    source.setListener(samples);
                    source.setSourceEventListener(events);
                    source.start();
                }

                @Override
                public synchronized void stopSamples()
                {
                    if(mSamplesStopped.compareAndSet(false, true))
                    {
                        try
                        {
                            source.setListener(null);
                            source.removeSourceEventListener();
                        }
                        finally
                        {
                            source.stop();
                        }
                    }
                }

                @Override
                public synchronized void close()
                {
                    if(mReleased.compareAndSet(false, true))
                    {
                        try
                        {
                            stopSamples();
                        }
                        finally
                        {
                            if(borrowedHold == null) hold.close();
                        }
                    }
                }
            };
        }
        catch(RuntimeException exception)
        {
            if(borrowedHold == null) hold.close();
            throw exception;
        }
    }

    private static ProbeDecoder decoder(Modulation modulation, double sampleRate, Listener<IMessage> messages)
    {
        if(modulation == Modulation.C4FM)
        {
            P25P1DecoderC4FM decoder = new P25P1DecoderC4FM(sampleRate, true);
            decoder.setMessageListener(messages);
            decoder.start();
            return new ProbeDecoder()
            {
                public void receive(ComplexSamples samples) { decoder.receive(samples); }
                public void reset() { decoder.reset(); }
                public void close() { decoder.removeMessageListener(); decoder.stop(); }
            };
        }
        P25P1DecoderLSM decoder = new P25P1DecoderLSM(sampleRate, true);
        decoder.setMessageListener(messages);
        decoder.start();
        return new ProbeDecoder()
        {
            public void receive(ComplexSamples samples) { decoder.receive(samples); }
            public void reset() { decoder.reset(); }
            public void close() { decoder.removeMessageListener(); decoder.stop(); }
        };
    }

    private synchronized void release(Session session)
    {
        if(mActive == session)
        {
            mActive = null;
        }
    }

    @Override
    public synchronized void close()
    {
        mClosed = true;
        if(mActive != null)
        {
            mActive.close();
        }
    }

    public record Identity(int wacn, int system, int rfss, int site, int nac)
    {
    }

    public record ModeMetrics(long validMessages, long validControlMessages, long invalidControlMessages,
                              long correctedBits, long syncLossBits, int networkObservations, int siteObservations,
                              double qualityPct, boolean confirmed, Identity identity, String reason,
                              Long servingControlFrequencyHz)
    {
        public ModeMetrics(long validMessages, long validControlMessages, long invalidControlMessages,
                           long correctedBits, long syncLossBits, int networkObservations, int siteObservations,
                           double qualityPct, boolean confirmed, Identity identity, String reason)
        {
            this(validMessages, validControlMessages, invalidControlMessages, correctedBits, syncLossBits,
                networkObservations, siteObservations, qualityPct, confirmed, identity, reason, null);
        }
    }

    /** Envelope variation is receiver-side evidence, not a physical modulation classification. */
    public record SignalMetrics(long sampleCount, Double meanPowerDbfs, Double envelopeVariation)
    {
    }

    public record Status(String state, String reason, String targetId, long frequencyHz, long startedAtMs,
                         long elapsedMs, long timeoutMs, long droppedBuffers, ModeMetrics c4fm,
                         ModeMetrics cqpsk, SignalMetrics signal, String selectedModulation, Identity identity)
    {
    }

    public final class Session implements AutoCloseable
    {
        private final String mTargetId;
        private final long mFrequencyHz;
        private final long mStartedAt;
        private final SourceLease mSource;
        private final double mSampleRate;
        private final BoundedSpscReferenceQueue<ComplexSamples> mSamples =
            new BoundedSpscReferenceQueue<>(SAMPLE_QUEUE_CAPACITY);
        private final AtomicLong mDroppedBuffers = new AtomicLong();
        private final AtomicReference<String> mSourceFailure = new AtomicReference<>();
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final ModeEvidence mC4fm = new ModeEvidence(Modulation.C4FM);
        private final ModeEvidence mCqpsk = new ModeEvidence(Modulation.CQPSK);
        private final SignalEvidence mSignal = new SignalEvidence();
        private final Thread mWorker;
        private final AtomicReference<Status> mStatus = new AtomicReference<>();

        private Session(String targetId, long frequencyHz, SourceLease source)
        {
            mTargetId = targetId;
            mFrequencyHz = frequencyHz;
            mSource = source;
            mSampleRate = source.sampleRate();
            mStartedAt = mClock.getAsLong();
            mStatus.set(snapshot("running", "Testing C4FM and CQPSK control decoding…", null, null));
            mWorker = new Thread(this::drain, "P25 discovery decoder");
            mWorker.setDaemon(true);
            mWorker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        }

        private void start()
        {
            mSource.start(this::offer, this::sourceEvent);
            mWorker.start();
        }

        /** Called only by the channel-source producer. Never decodes, projects, copies, or waits. */
        void offer(ComplexSamples samples)
        {
            if(mClosed.get() || !"running".equals(mStatus.get().state()) || samples == null)
            {
                return;
            }
            if(!mSamples.offer(samples, mDroppedBuffers.get()))
            {
                mDroppedBuffers.incrementAndGet();
            }
            else
            {
                //A nonblocking wake-up avoids adding the idle poll interval to every newly received batch.
                //The producer still offers references only; all decoding/projection stays on the worker.
                LockSupport.unpark(mWorker);
            }
        }

        /** Called by source notification threads. Terminal projection and teardown belong to the worker. */
        void sourceEvent(SourceEvent event)
        {
            if(event == null || mClosed.get())
            {
                return;
            }
            String failure = switch(event.getEvent())
            {
                case NOTIFICATION_ERROR_STATE, NOTIFICATION_TUNER_SHUTDOWN, NOTIFICATION_STOP_SAMPLE_STREAM ->
                    "The tuner stopped supplying samples. Retry when it is available.";
                case NOTIFICATION_FREQUENCY_CHANGE -> event.hasValue() &&
                    event.getValue().longValue() != mFrequencyHz ? "The probe frequency changed. Retry discovery." : null;
                case NOTIFICATION_SAMPLE_RATE_CHANGE, NOTIFICATION_CHANNEL_SAMPLE_RATE_CHANGE -> event.hasValue() &&
                    Double.compare(event.getValue().doubleValue(), mSampleRate) != 0 ?
                    "The tuner sample rate changed. Retry discovery." : null;
                default -> null;
            };
            if(failure != null)
            {
                mSourceFailure.compareAndSet(null, failure);
            }
        }

        private void drain()
        {
            long lastPublication = 0;
            long sampleGeneration = 0;
            try(ProbeDecoder c4fm = mDecoders.create(Modulation.C4FM, mSampleRate, mC4fm::receive);
                ProbeDecoder cqpsk = mDecoders.create(Modulation.CQPSK, mSampleRate, mCqpsk::receive))
            {
                while(!mClosed.get() && "running".equals(mStatus.get().state()))
                {
                    String failure = mSourceFailure.get();
                    if(failure != null || !mSource.valid())
                    {
                        finish("failed", failure != null ? failure : "The tuner changed. Retry discovery.", null);
                        break;
                    }
                    if(mSource.droppedSampleBatches() > 0)
                    {
                        finish("failed", "The receiver dropped sample batches. Retry discovery.", null);
                        break;
                    }
                    long now = mClock.getAsLong();
                    long elapsed = Math.max(0, now - mStartedAt);
                    if(elapsed >= TIMEOUT_MILLISECONDS)
                    {
                        complete(elapsed, true, sampleGeneration == mDroppedBuffers.get());
                        break;
                    }
                    ComplexSamples samples = mSamples.poll();
                    if(samples != null)
                    {
                        long generation = mSamples.lastPolledMetadata();
                        if(generation != sampleGeneration)
                        {
                            //Samples on opposite sides of a dropped buffer must not assemble one trusted message
                            //or complete identity. The queue retains generation metadata with each accepted buffer.
                            c4fm.reset();
                            cqpsk.reset();
                            mC4fm.reset();
                            mCqpsk.reset();
                            mSignal.reset();
                            sampleGeneration = generation;
                        }
                        mSignal.receive(samples);
                        c4fm.receive(samples);
                        cqpsk.receive(samples);
                        //The upstream channel-output dispatcher also has a bounded queue. Its drops have no
                        //per-buffer boundary metadata, so discard this attempt rather than trust a split frame.
                        if(mSource.droppedSampleBatches() > 0)
                        {
                            finish("failed", "The receiver dropped sample batches. Retry discovery.", null);
                            break;
                        }
                        //Both modes have now consumed the same contiguous batch. Publish a strong result at this
                        //boundary instead of waiting for the next UI-status tick; ambiguous and weak evidence
                        //continues through the existing conservative timeout.
                        if(complete(Math.max(0, mClock.getAsLong() - mStartedAt), false,
                            sampleGeneration == mDroppedBuffers.get()))
                        {
                            break;
                        }
                    }
                    if(now - lastPublication >= STATUS_INTERVAL_MILLISECONDS)
                    {
                        if(!complete(elapsed, false, sampleGeneration == mDroppedBuffers.get()))
                        {
                            Status previous = mStatus.get();
                            if(!mClosed.get() && "running".equals(previous.state()))
                            {
                                mStatus.compareAndSet(previous,
                                    snapshot("running", "Testing C4FM and CQPSK control decoding…", null, null));
                            }
                        }
                        lastPublication = now;
                    }
                    if(samples == null)
                    {
                        LockSupport.parkNanos(5_000_000L);
                    }
                }
            }
            catch(RuntimeException exception)
            {
                LOGGER.warn("P25 discovery decoder failed", exception);
                finish("failed", "P25 discovery could not decode this signal. Retry discovery.", null);
            }
            finally
            {
                mSamples.clear();
            }
        }

        private boolean complete(long elapsed, boolean timeout, boolean contiguous)
        {
            ModeMetrics c4fm = mC4fm.metrics();
            ModeMetrics cqpsk = mCqpsk.metrics();
            Modulation selected = select(c4fm, cqpsk);
            if(contiguous && elapsed >= MINIMUM_OBSERVATION_MILLISECONDS && selected != null && mSignal.mCount > 0)
            {
                finish("ready", "A P25 control channel and site identity were confirmed.", selected);
                return true;
            }
            if(timeout)
            {
                boolean control = c4fm.validControlMessages() > 0 || cqpsk.validControlMessages() > 0;
                String reason = !control ? "No P25 Phase 1 control channel was confirmed. Adjust the frequency and retry." :
                    c4fm.confirmed() && cqpsk.confirmed() ?
                        "The decoder results were too close or the site identities disagreed. Retry discovery." :
                        "The signal did not provide enough consistent system and site broadcasts. Retry discovery.";
                finish(control ? "inconclusive" : "failed", reason, null);
                return true;
            }
            return false;
        }

        private void finish(String state, String reason, Modulation modulation)
        {
            Status previous = mStatus.get();
            if(mClosed.get() || !"running".equals(previous.state()))
            {
                return;
            }
            if("ready".equals(state) && !valid())
            {
                state = "failed";
                reason = "The tuner changed. Retry discovery.";
                modulation = null;
            }
            ModeMetrics mode = modulation == Modulation.C4FM ? mC4fm.metrics() : mCqpsk.metrics();
            if(!mStatus.compareAndSet(previous, snapshot(state, reason, modulation != null ? modulation.name() : null,
                modulation != null ? mode.identity() : null)))
            {
                return;
            }
            try
            {
                mSource.stopSamples();
            }
            catch(RuntimeException exception)
            {
                //A teardown error must not leave a previously published READY snapshot eligible to save.
                mSourceFailure.compareAndSet(null, "The receiver could not finish discovery. Retry discovery.");
                mStatus.updateAndGet(status -> "closed".equals(status.state()) ? status :
                    new Status("failed", mSourceFailure.get(), status.targetId(),
                    status.frequencyHz(), status.startedAtMs(), status.elapsedMs(), status.timeoutMs(),
                    status.droppedBuffers(), status.c4fm(), status.cqpsk(), status.signal(), null, null));
                state = "failed";
            }
            //A successful review retains the tuner hold until the caller saves or cancels. Failed probes release
            //it immediately, so a new session can retry without keeping a hidden receiver reservation.
            if(!"ready".equals(state))
            {
                try
                {
                    mSource.close();
                }
                finally
                {
                    release(this);
                }
            }
        }

        private Status snapshot(String state, String reason, String modulation, Identity identity)
        {
            return new Status(state, reason, mTargetId, mFrequencyHz, mStartedAt,
                Math.max(0, mClock.getAsLong() - mStartedAt), TIMEOUT_MILLISECONDS, mDroppedBuffers.get(),
                mC4fm.metrics(), mCqpsk.metrics(), mSignal.metrics(), modulation, identity);
        }

        /** Returns only an immutable worker-published snapshot; HTTP clients never visit mutable decoder state. */
        public Status status()
        {
            Status status = mStatus.get();
            if("ready".equals(status.state()) && !valid())
            {
                return new Status("failed", "The tuner changed after discovery. Retry discovery.", status.targetId(),
                    status.frequencyHz(), status.startedAtMs(), status.elapsedMs(), status.timeoutMs(),
                    status.droppedBuffers(), status.c4fm(), status.cqpsk(), status.signal(), null, null);
            }
            return status;
        }

        public boolean valid()
        {
            return !mClosed.get() && mSourceFailure.get() == null && mSource.valid();
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                //Do not touch decoder objects on the HTTP thread. Interrupting ends the worker at its next
                //boundary; source detachment prevents any additional work entering the bounded queue.
                mStatus.updateAndGet(previous -> "running".equals(previous.state()) ?
                    new Status("closed", "Discovery was cancelled.", previous.targetId(),
                        previous.frequencyHz(), previous.startedAtMs(), previous.elapsedMs(), previous.timeoutMs(),
                        mDroppedBuffers.get(), previous.c4fm(), previous.cqpsk(), previous.signal(), null, null) : previous);
                try
                {
                    mSource.close();
                }
                finally
                {
                    mWorker.interrupt();
                    release(this);
                }
            }
        }
    }

    /** A clear quality separation is required; neither decoder order nor a metadata guess resolves a tie. */
    static Modulation select(ModeMetrics c4fm, ModeMetrics cqpsk)
    {
        if(c4fm.confirmed() && cqpsk.confirmed())
        {
            if(!Objects.equals(c4fm.identity(), cqpsk.identity()) ||
                Math.abs(c4fm.qualityPct() - cqpsk.qualityPct()) < MINIMUM_QUALITY_SEPARATION)
            {
                return null;
            }
            return c4fm.qualityPct() > cqpsk.qualityPct() ? Modulation.C4FM : Modulation.CQPSK;
        }
        return c4fm.confirmed() ? Modulation.C4FM : cqpsk.confirmed() ? Modulation.CQPSK : null;
    }

    /** Worker-confined evidence. Discovery intentionally does not reuse the one-observation startup policy. */
    static final class ModeEvidence
    {
        private final P25P1NetworkConfigurationMonitor mMonitor;
        private long mValidMessages;
        private long mValidControl;
        private long mInvalidControl;
        private long mCorrectedBits;
        private long mSyncLossBits;
        private P25NetworkConfigurationSnapshot.Network mNetwork;
        private P25NetworkConfigurationSnapshot.CurrentSite mSite;
        private int mNetworkObservations;
        private int mSiteObservations;
        private long mFirstNetworkTimestamp;
        private long mLastNetworkTimestamp;
        private long mFirstSiteTimestamp;
        private long mLastSiteTimestamp;
        private ModeMetrics mMetrics;
        private Long mServingControlFrequency;
        private int mControlFrequencyObservations;
        private long mFirstControlFrequencyTimestamp;
        private long mLastControlFrequencyTimestamp;

        ModeEvidence(Modulation modulation)
        {
            mMonitor = new P25P1NetworkConfigurationMonitor(modulation);
        }

        void receive(IMessage message)
        {
            if(message == null)
            {
                return;
            }
            mMetrics = null;
            if(message instanceof SyncLossMessage loss)
            {
                mSyncLossBits += Math.max(0, loss.getBitsProcessed());
                return;
            }
            if(message.isValid())
            {
                mValidMessages++;
            }
            P25NetworkConfigurationSnapshot observation = null;
            if(message instanceof TSBKMessage tsbk)
            {
                count(tsbk.isValid(), Math.max(0, tsbk.getMessage().getCorrectedBitCount()));
                if(tsbk.isValid() && !tsbk.isEncrypted() &&
                    (tsbk instanceof NetworkStatusBroadcast || tsbk instanceof RFSSStatusBroadcast))
                {
                    observation = mMonitor.process(tsbk);
                }
            }
            else if(message instanceof AMBTCMessage ambtc)
            {
                boolean valid = ambtc.isValid() && ambtc.getPDUSequence().isValid();
                count(valid, Math.max(0, ambtc.getBitErrorsCount()));
                if(valid && (ambtc instanceof AMBTCNetworkStatusBroadcast || ambtc instanceof AMBTCRFSSStatusBroadcast))
                {
                    observation = mMonitor.process(ambtc);
                }
            }
            if(observation != null)
            {
                observe(observation, message.getTimestamp());
            }
        }

        private void count(boolean valid, int correctedBits)
        {
            if(valid)
            {
                mValidControl++;
            }
            else
            {
                mInvalidControl++;
            }
            mCorrectedBits += correctedBits;
        }

        private void observe(P25NetworkConfigurationSnapshot observation, long timestamp)
        {
            if(timestamp <= 0)
            {
                return;
            }
            P25NetworkConfigurationSnapshot.Network network = observation.network();
            P25NetworkConfigurationSnapshot.CurrentSite site = observation.currentSite();
            if(network != null)
            {
                if(mNetwork != null && (!Objects.equals(mNetwork.wacn(), network.wacn()) ||
                    !Objects.equals(mNetwork.system(), network.system()) || !Objects.equals(mNetwork.nac(), network.nac())))
                {
                    clearIdentity();
                }
                if(timestamp > mLastNetworkTimestamp)
                {
                    mNetwork = network;
                    mNetworkObservations++;
                    mFirstNetworkTimestamp = mFirstNetworkTimestamp == 0 ? timestamp : mFirstNetworkTimestamp;
                    mLastNetworkTimestamp = timestamp;
                }
            }
            if(site != null)
            {
                if(mSite != null && (!Objects.equals(mSite.system(), site.system()) ||
                    !Objects.equals(mSite.nac(), site.nac()) || !Objects.equals(mSite.rfss(), site.rfss()) ||
                    !Objects.equals(mSite.site(), site.site())))
                {
                    clearIdentity();
                }
                if(timestamp > mLastSiteTimestamp)
                {
                    mSite = site;
                    mSiteObservations++;
                    mFirstSiteTimestamp = mFirstSiteTimestamp == 0 ? timestamp : mFirstSiteTimestamp;
                    mLastSiteTimestamp = timestamp;
                    observeServingFrequency(observation, timestamp);
                }
            }
        }

        private void observeServingFrequency(P25NetworkConfigurationSnapshot observation, long timestamp)
        {
            //The decoder message processor has already validated and confirmed frequency bands before applying
            //them to this fresh serving RFSS descriptor. Neighbors, secondary controls and unresolved channels
            //cannot become exact-carrier evidence for directory matching.
            Long frequency = null;
            for(var channel: observation.channels())
            {
                if("primary_control".equals(channel.role()) && channel.downlink() != null)
                {
                    if(frequency != null && !frequency.equals(channel.downlink()))
                    {
                        frequency = null;
                        break;
                    }
                    frequency = channel.downlink();
                }
            }
            if(frequency == null || !Objects.equals(mServingControlFrequency, frequency))
            {
                mServingControlFrequency = frequency;
                mControlFrequencyObservations = 0;
                mFirstControlFrequencyTimestamp = mLastControlFrequencyTimestamp = 0;
            }
            if(frequency != null && timestamp > mLastControlFrequencyTimestamp)
            {
                mControlFrequencyObservations++;
                mFirstControlFrequencyTimestamp = mFirstControlFrequencyTimestamp == 0 ? timestamp :
                    mFirstControlFrequencyTimestamp;
                mLastControlFrequencyTimestamp = timestamp;
            }
        }

        void reset()
        {
            mMetrics = null;
            clearIdentity();
            mMonitor.reset();
            mValidMessages = mValidControl = mInvalidControl = mCorrectedBits = mSyncLossBits = 0;
        }

        private void clearIdentity()
        {
            mNetwork = null;
            mSite = null;
            mNetworkObservations = mSiteObservations = 0;
            mFirstNetworkTimestamp = mLastNetworkTimestamp = mFirstSiteTimestamp = mLastSiteTimestamp = 0;
            mServingControlFrequency = null;
            mControlFrequencyObservations = 0;
            mFirstControlFrequencyTimestamp = mLastControlFrequencyTimestamp = 0;
        }

        ModeMetrics metrics()
        {
            if(mMetrics != null)
            {
                return mMetrics;
            }
            P25SiteIdentity site = P25SiteIdentity.from(mNetwork, mSite);
            Identity identity = site != null && mNetwork.nac() != null ?
                new Identity(site.wacn(), site.system(), site.rfss(), site.site(), mNetwork.nac()) : null;
            double attempted = mValidControl + mInvalidControl + (double)mSyncLossBits / 196;
            double correctedFraction = mValidControl > 0 ? Math.min(1, (double)mCorrectedBits / (mValidControl * 196)) : 1;
            double quality = attempted > 0 ? Math.max(0, 100 * mValidControl / attempted * (1 - correctedFraction)) : 0;
            boolean repeated = identity != null && mNetworkObservations >= MINIMUM_IDENTITY_OBSERVATIONS &&
                mSiteObservations >= MINIMUM_IDENTITY_OBSERVATIONS &&
                mLastNetworkTimestamp - mFirstNetworkTimestamp >= MINIMUM_IDENTITY_SPAN_MILLISECONDS &&
                mLastSiteTimestamp - mFirstSiteTimestamp >= MINIMUM_IDENTITY_SPAN_MILLISECONDS;
            boolean confirmed = repeated && mValidControl >= MINIMUM_CONTROL_MESSAGES && quality >= MINIMUM_QUALITY_PERCENT;
            String reason = confirmed ? "Control channel and serving site confirmed." :
                !repeated ? "Waiting for repeated current system and site broadcasts." :
                    "Waiting for reliable control-channel decoding.";
            Long trustedFrequency = confirmed && mControlFrequencyObservations >= MINIMUM_IDENTITY_OBSERVATIONS &&
                mLastControlFrequencyTimestamp - mFirstControlFrequencyTimestamp >= MINIMUM_IDENTITY_SPAN_MILLISECONDS ?
                mServingControlFrequency : null;
            mMetrics = new ModeMetrics(mValidMessages, mValidControl, mInvalidControl, mCorrectedBits, mSyncLossBits,
                mNetworkObservations, mSiteObservations, quality, confirmed, identity, reason, trustedFrequency);
            return mMetrics;
        }
    }

    /** Measurements use untouched channel IQ, before either decoder's adaptive gain and demodulation. */
    static final class SignalEvidence
    {
        private long mCount;
        private double mPower;
        private double mAmplitude;

        void receive(ComplexSamples samples)
        {
            int length = Math.min(samples.i().length, samples.q().length);
            for(int x = 0; x < length; x++)
            {
                double power = (double)samples.i()[x] * samples.i()[x] + (double)samples.q()[x] * samples.q()[x];
                if(Double.isFinite(power))
                {
                    mCount++;
                    mPower += power;
                    mAmplitude += Math.sqrt(power);
                }
            }
        }

        void reset()
        {
            mCount = 0;
            mPower = mAmplitude = 0;
        }

        SignalMetrics metrics()
        {
            double meanPower = mCount > 0 ? mPower / mCount : 0;
            double meanAmplitude = mCount > 0 ? mAmplitude / mCount : 0;
            Double variation = meanAmplitude > 0 ? Math.sqrt(Math.max(0, meanPower - meanAmplitude * meanAmplitude)) /
                meanAmplitude : null;
            return new SignalMetrics(mCount, meanPower > 0 ? 10 * Math.log10(meanPower) : null, variation);
        }
    }

    interface SourceLease extends AutoCloseable
    {
        double sampleRate();
        boolean valid();
        default long droppedSampleBatches() { return 0; }
        void start(Listener<ComplexSamples> samples, Listener<SourceEvent> events);
        void stopSamples();
        void close();
    }

    @FunctionalInterface
    interface SourceFactory
    {
        SourceLease open(String targetId, long frequencyHz);
    }

    interface ProbeDecoder extends AutoCloseable
    {
        void receive(ComplexSamples samples);
        void reset();
        void close();
    }

    @FunctionalInterface
    interface DecoderFactory
    {
        ProbeDecoder create(Modulation modulation, double sampleRate, Listener<IMessage> messages);
    }
}
