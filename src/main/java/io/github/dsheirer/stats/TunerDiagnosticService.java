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

package io.github.dsheirer.stats;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.mixer.ComplexMixer;
import io.github.dsheirer.dsp.mixer.ComplexMixerFactory;
import io.github.dsheirer.dsp.window.WindowFactory;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.spectrum.NativeBufferManager;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import io.github.dsheirer.util.concurrent.BoundedSpscReferenceQueue;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jtransforms.fft.FloatFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demand-owned, read-only spectrum diagnostics for one already-running tuner.
 *
 * <p>The service has one exclusive session.  That session owns one tuner listener, one bounded latest-loss-tolerant
 * worker, and one output slot.  Opening another tuner or viewer returns {@link OpenStatus#BUSY}.  The service never
 * initializes, retunes, or reconfigures tuner hardware.</p>
 *
 * <p>The worker uses a fixed high-resolution full-band FFT for the overview.  A narrow viewport switches that same
 * worker to a zoom lens: it mixes the requested center to baseband, anti-alias filters and decimates by two, four,
 * eight, sixteen, or thirty-two, then runs the same FFT.  Only one analysis path is active at a time.</p>
 */
public final class TunerDiagnosticService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerDiagnosticService.class);
    public static final int FFT_SIZE = 4_096;
    public static final int MAXIMUM_TRANSMITTED_BINS = FFT_SIZE;
    public static final int FRAMES_PER_SECOND = 10;
    private static final int MAXIMUM_DECIMATION = 32;
    /** Keeps the requested view out of the anti-alias filter transition band. */
    private static final double USABLE_LENS_FRACTION = 0.80;

    private final Object mLifecycleLock = new Object();
    private final TargetSource mTargetSource;
    private final ProcessorFactory mProcessorFactory;
    private final IdentityHashMap<Object, RuntimeIdentity> mRuntimeIdentities = new IdentityHashMap<>();
    private final Map<TunerClass, Integer> mNextClassOrdinal = new HashMap<>();
    private final AtomicLong mNextGeneration = new AtomicLong();
    private Session mActiveSession;
    private boolean mClosed;

    public TunerDiagnosticService(TunerManager tunerManager, DiagnosticFftScheduler scheduler)
    {
        Objects.requireNonNull(tunerManager, "Tuner manager cannot be null");
        Objects.requireNonNull(scheduler, "Diagnostic FFT scheduler cannot be null");
        mTargetSource = () -> availableTargets(tunerManager);
        mProcessorFactory = (target, consumer) -> new TunerFftProcessor(scheduler,
            target.target().centerFrequencyHz(), target.target().sampleRateHz(), consumer);
    }

    /** Test seam that keeps lifecycle behavior independent from tuner hardware and FFT implementation. */
    TunerDiagnosticService(TargetSource targetSource, ProcessorFactory processorFactory)
    {
        mTargetSource = Objects.requireNonNull(targetSource, "Tuner target source cannot be null");
        mProcessorFactory = Objects.requireNonNull(processorFactory, "Diagnostic processor factory cannot be null");
    }

    /**
     * Lists currently active targets without starting a worker.  IDs are opaque and stable for the lifetime of the
     * underlying tuner object; labels intentionally contain no hardware identity.
     */
    public List<Target> targets()
    {
        synchronized(mLifecycleLock)
        {
            if(mClosed)
            {
                return List.of();
            }

            return snapshotsLocked().stream().map(TargetSnapshot::target)
                .sorted(Comparator.comparing(Target::label, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Target::targetId)).toList();
        }
    }

    public OpenResult tryOpen(String targetId)
    {
        return tryOpen(targetId, null);
    }

    /** Opens the service's one exclusive latest-only session. */
    public OpenResult tryOpen(String targetId, Viewport viewport)
    {
        if(targetId == null || targetId.isBlank())
        {
            return new OpenResult(OpenStatus.NOT_FOUND, null);
        }

        synchronized(mLifecycleLock)
        {
            if(mClosed)
            {
                return new OpenResult(OpenStatus.CLOSED, null);
            }

            if(mActiveSession != null)
            {
                return new OpenResult(OpenStatus.BUSY, null);
            }

            TargetSnapshot target = snapshotsLocked().stream()
                .filter(candidate -> targetId.equals(candidate.target().targetId())).findFirst().orElse(null);

            if(target == null)
            {
                return new OpenResult(OpenStatus.NOT_FOUND, null);
            }

            try
            {
                Session session = new Session(target, mNextGeneration.incrementAndGet(), viewport);
                mActiveSession = session;
                return new OpenResult(OpenStatus.OPEN, session);
            }
            catch(RuntimeException exception)
            {
                mLog.warn("Unable to start tuner spectrum diagnostics", exception);
                return new OpenResult(OpenStatus.UNAVAILABLE, null);
            }
        }
    }

    public int activeProducerCount()
    {
        synchronized(mLifecycleLock)
        {
            return mActiveSession != null ? 1 : 0;
        }
    }

    public int activeSessionCount()
    {
        return activeProducerCount();
    }

    public void closeActiveSessions()
    {
        synchronized(mLifecycleLock)
        {
            closeActiveSessionLocked("Tuner diagnostics were stopped.");
        }
    }

    private void closeActiveSessionLocked(String reason)
    {
        Session session = mActiveSession;
        mActiveSession = null;

        if(session != null)
        {
            session.markUnavailable(reason);
            detach(session);
        }
    }

    private void closeSession(Session session)
    {
        synchronized(mLifecycleLock)
        {
            if(!session.markClosed())
            {
                return;
            }

            if(mActiveSession == session)
            {
                mActiveSession = null;
            }

            detach(session);
        }
    }

    private void detach(Session session)
    {
        try
        {
            session.detach();
        }
        catch(RuntimeException exception)
        {
            mLog.debug("Unable to completely detach tuner spectrum diagnostics", exception);
        }
    }

    private void checkAvailable(Session session, boolean force)
    {
        synchronized(mLifecycleLock)
        {
            if(mClosed || mActiveSession != session || session.isClosed() || !session.shouldCheckAvailability(force))
            {
                return;
            }

            TargetSnapshot current = snapshotsLocked().stream()
                .filter(candidate -> session.targetId().equals(candidate.target().targetId()))
                .findFirst().orElse(null);

            if(current == null || !session.matches(current))
            {
                mActiveSession = null;
                session.markUnavailable("Tuner is no longer available.");
                detach(session);
            }
            else
            {
                session.updateMetadata(current.target());
            }
        }
    }

    private List<TargetSnapshot> snapshotsLocked()
    {
        List<AvailableTarget> supplied;

        try
        {
            supplied = mTargetSource.availableTargets();
        }
        catch(RuntimeException exception)
        {
            mLog.debug("Unable to enumerate tuner diagnostic targets", exception);
            return List.of();
        }

        if(supplied == null || supplied.isEmpty())
        {
            pruneRuntimeIdentitiesLocked(new IdentityHashMap<>());
            return List.of();
        }

        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        List<TargetSnapshot> snapshots = new ArrayList<>(supplied.size());

        for(AvailableTarget available: supplied)
        {
            if(available == null || seen.put(available.identity(), Boolean.TRUE) != null)
            {
                continue;
            }

            try
            {
                TunerController controller = available.controller();
                long centerFrequency = controller.getFrequency();
                double rawSampleRate = controller.getSampleRate();

                if(centerFrequency <= 0 || !Double.isFinite(rawSampleRate) || rawSampleRate <= 0)
                {
                    continue;
                }

                long sampleRate = Math.round(rawSampleRate);
                int activeChannelCount = Math.max(0, available.activeChannelCount().get());

                if(activeChannelCount == 0 || !controller.hasBufferListeners())
                {
                    continue;
                }

                RuntimeIdentity identity = mRuntimeIdentities.computeIfAbsent(available.identity(), ignored ->
                    createRuntimeIdentity(available.tunerClass()));
                Target target = new Target(identity.targetId(), identity.label(), centerFrequency, sampleRate,
                    activeChannelCount);
                snapshots.add(new TargetSnapshot(available.identity(), controller, available.activeChannelCount(),
                    target));
            }
            catch(RuntimeException exception)
            {
                mLog.debug("A tuner diagnostic target became unavailable during discovery", exception);
            }
        }

        pruneRuntimeIdentitiesLocked(seen);
        return snapshots;
    }

    private void pruneRuntimeIdentitiesLocked(IdentityHashMap<Object, Boolean> available)
    {
        mRuntimeIdentities.entrySet().removeIf(entry -> !available.containsKey(entry.getKey()) &&
            (mActiveSession == null || mActiveSession.identity() != entry.getKey()));
    }

    private RuntimeIdentity createRuntimeIdentity(TunerClass tunerClass)
    {
        TunerClass safeClass = tunerClass != null ? tunerClass : TunerClass.UNKNOWN;
        int ordinal = mNextClassOrdinal.merge(safeClass, 1, Integer::sum);
        return new RuntimeIdentity(UUID.randomUUID().toString(), safeClass + " " + ordinal);
    }

    @Override
    public void close()
    {
        synchronized(mLifecycleLock)
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;
            closeActiveSessionLocked("Tuner diagnostics are closed.");
            mRuntimeIdentities.clear();
            mNextClassOrdinal.clear();
        }
    }

    private static List<AvailableTarget> availableTargets(TunerManager tunerManager)
    {
        List<DiscoveredTuner> discovered = tunerManager.getAvailableTuners();

        if(discovered == null || discovered.isEmpty())
        {
            return List.of();
        }

        List<AvailableTarget> available = new ArrayList<>(discovered.size());

        for(DiscoveredTuner candidate: discovered)
        {
            try
            {
                if(candidate == null || !candidate.isAvailable() || !candidate.hasTuner())
                {
                    continue;
                }

                Tuner tuner = candidate.getTuner();
                TunerController controller = tuner != null ? tuner.getTunerController() : null;
                ChannelSourceManager channelManager = tuner != null ? tuner.getChannelSourceManager() : null;

                if(tuner != null && controller != null && channelManager != null)
                {
                    available.add(new AvailableTarget(tuner, candidate.getTunerClass(), controller,
                        channelManager::getTunerChannelCount));
                }
            }
            catch(RuntimeException exception)
            {
                mLog.debug("A tuner became unavailable during diagnostic target enumeration", exception);
            }
        }

        return available;
    }

    public enum OpenStatus
    {
        OPEN,
        BUSY,
        NOT_FOUND,
        UNAVAILABLE,
        CLOSED
    }

    public record OpenResult(OpenStatus status, Session session)
    {
    }

    public record Target(String targetId, String label, long centerFrequencyHz, long sampleRateHz,
                         int activeChannelCount)
    {
    }

    /** Requested browser viewport.  It is also the desired high-resolution analysis lens. */
    public record Viewport(long startFrequencyHz, long endFrequencyHz)
    {
        public Viewport
        {
            if(startFrequencyHz < 0 || endFrequencyHz <= startFrequencyHz)
            {
                throw new IllegalArgumentException("Tuner diagnostic viewport is invalid");
            }
        }
    }

    /**
     * State keeps center/sample rate as the full tuner bounds.  visibleStart/End report the actual analysis domain;
     * binary FFT frames carry that same analysis center and sample rate.
     */
    public record State(long revision, long generation, String state, String reason, String targetId, String label,
                        long centerFrequencyHz, long sampleRateHz, int activeChannelCount, int fftSize,
                        int framesPerSecond, int maximumTransmittedBins, Long requestedStartFrequencyHz,
                        Long requestedEndFrequencyHz, double visibleStartFrequencyHz,
                        double visibleEndFrequencyHz, int firstBin, int sourceBinCount, int transmittedBinCount)
    {
    }

    public final class Session implements Listener<INativeBuffer>, ISourceEventProcessor, AutoCloseable
    {
        private final TargetSnapshot mTarget;
        private final long mGeneration;
        private final DiagnosticFrameQueue mFrames = new DiagnosticFrameQueue();
        private final AtomicBoolean mSessionClosed = new AtomicBoolean();
        private final AtomicBoolean mDetached = new AtomicBoolean();
        private final AtomicLong mSequence = new AtomicLong();
        private final AtomicLong mStateRevisionSequence = new AtomicLong();
        private final AtomicLong mObservedCenterFrequencyHz = new AtomicLong();
        private final AtomicLong mObservedSampleRateHz = new AtomicLong();
        private final AtomicLong mDroppedIngressBuffers = new AtomicLong();
        private final FrameProcessor mProcessor;
        private volatile Target mMetadata;
        private volatile Viewport mViewport;
        private volatile long mMetadataStateRevision;
        private volatile long mViewportStateRevision;
        private volatile String mTerminalState;
        private volatile String mTerminalReason;
        private boolean mBufferListenerAttached;
        private boolean mSourceEventListenerAttached;
        private long mNextAvailabilityCheckNanos;

        private Session(TargetSnapshot target, long generation, Viewport viewport)
        {
            mTarget = target;
            mGeneration = generation;
            mMetadata = target.target();
            mViewport = viewport;
            mMetadataStateRevision = nextStateRevision();
            mViewportStateRevision = nextStateRevision();
            mObservedCenterFrequencyHz.set(target.target().centerFrequencyHz());
            mObservedSampleRateHz.set(target.target().sampleRateHz());
            FrameProcessor processor = mProcessorFactory.create(target, this::publish);
            mProcessor = Objects.requireNonNull(processor, "Diagnostic processor factory returned null");

            try
            {
                mProcessor.updateMetadata(target.target().centerFrequencyHz(), target.target().sampleRateHz());
                mProcessor.updateViewport(viewport);

                if(!target.controller().getLock().tryLock())
                {
                    throw new IllegalStateException("Selected tuner is busy changing configuration");
                }

                try
                {
                    if(target.activeChannelCount().get() <= 0 || !target.controller().hasBufferListeners())
                    {
                        throw new IllegalStateException("Selected tuner is no longer actively receiving");
                    }

                    long centerFrequencyHz = target.controller().getFrequency();
                    double rawSampleRate = target.controller().getSampleRate();
                    long sampleRateHz = Double.isFinite(rawSampleRate) ? Math.round(rawSampleRate) : 0;

                    if(centerFrequencyHz > 0 && sampleRateHz > 0)
                    {
                        mObservedCenterFrequencyHz.set(centerFrequencyHz);
                        mObservedSampleRateHz.set(sampleRateHz);
                        mProcessor.updateMetadata(centerFrequencyHz, sampleRateHz);
                    }

                    mSourceEventListenerAttached = true;
                    target.controller().addListener(this);
                    mBufferListenerAttached = true;
                    target.controller().addBufferListener(this);
                }
                finally
                {
                    target.controller().getLock().unlock();
                }
            }
            catch(RuntimeException exception)
            {
                try
                {
                    detach();
                }
                catch(RuntimeException cleanupException)
                {
                    exception.addSuppressed(cleanupException);
                }

                throw exception;
            }
        }

        public State state()
        {
            checkAvailable(this, true);
            String terminalState = mTerminalState;
            return state(terminalState != null ? terminalState : "live",
                terminalState != null ? mTerminalReason : "");
        }

        /** Reconfigures the one worker between overview and zoom without touching the tuner listener. */
        public void updateViewport(Viewport viewport)
        {
            if(mSessionClosed.get())
            {
                throw new IllegalStateException("Tuner diagnostic session is closed");
            }

            if(Objects.equals(mViewport, viewport))
            {
                return;
            }

            mViewport = viewport;
            mViewportStateRevision = nextStateRevision();
            mProcessor.updateViewport(viewport);
        }

        public DiagnosticStreamFrame poll(Duration timeout) throws InterruptedException
        {
            Objects.requireNonNull(timeout, "Diagnostic poll timeout cannot be null");

            if(timeout.isNegative())
            {
                throw new IllegalArgumentException("Diagnostic poll timeout cannot be negative");
            }

            if(timeout.isZero())
            {
                checkAvailable(this, false);
                DiagnosticStreamFrame frame = mFrames.poll(Duration.ZERO);
                return matchesAnalysis(frame) ? frame : null;
            }

            long started = System.nanoTime();
            long timeoutNanos = timeout.toNanos();
            long livenessIntervalNanos = Duration.ofSeconds(1).toNanos();

            while(!mSessionClosed.get())
            {
                checkAvailable(this, false);

                if(mSessionClosed.get())
                {
                    return null;
                }

                long remaining = timeoutNanos - (System.nanoTime() - started);

                if(remaining <= 0)
                {
                    return null;
                }

                DiagnosticStreamFrame frame = mFrames.poll(Duration.ofNanos(Math.min(remaining,
                    livenessIntervalNanos)));

                if(matchesAnalysis(frame))
                {
                    return frame;
                }
            }

            return null;
        }

        private boolean matchesAnalysis(DiagnosticStreamFrame frame)
        {
            if(frame == null)
            {
                return false;
            }

            if(frame.type() != DiagnosticStreamFrame.TYPE_TUNER_FFT)
            {
                return true;
            }

            AnalysisPlan expected = analysisPlan(mObservedCenterFrequencyHz.get(), mObservedSampleRateHz.get(),
                mViewport);
            return frame.centerFrequencyHz() == expected.centerFrequencyHz() &&
                frame.sampleRateHz() == expected.sampleRateHz() && frame.fftSize() == FFT_SIZE &&
                frame.firstBin() == 0 && frame.sourceBinCount() == FFT_SIZE && frame.valueCount() == FFT_SIZE;
        }

        public boolean isClosed()
        {
            return mSessionClosed.get();
        }

        private boolean markClosed()
        {
            return markTerminal("closed", "Tuner diagnostic session is closed.");
        }

        private boolean markUnavailable(String reason)
        {
            return markTerminal("unavailable", reason);
        }

        private boolean markTerminal(String state, String reason)
        {
            if(!mSessionClosed.compareAndSet(false, true))
            {
                return false;
            }

            mTerminalState = state;
            mTerminalReason = reason;
            mViewportStateRevision = nextStateRevision();
            mFrames.close();
            return true;
        }

        private boolean matches(TargetSnapshot target)
        {
            return mTarget.identity() == target.identity() && mTarget.controller() == target.controller();
        }

        private boolean shouldCheckAvailability(boolean force)
        {
            long now = System.nanoTime();

            if(force || now >= mNextAvailabilityCheckNanos)
            {
                mNextAvailabilityCheckNanos = now + Duration.ofSeconds(1).toNanos();
                return true;
            }

            return false;
        }

        private void updateMetadata(Target metadata)
        {
            if(metadata == null || metadata.equals(mMetadata))
            {
                return;
            }

            mMetadata = metadata;
            mMetadataStateRevision = nextStateRevision();
            mObservedCenterFrequencyHz.set(metadata.centerFrequencyHz());
            mObservedSampleRateHz.set(metadata.sampleRateHz());
            mProcessor.updateMetadata(metadata.centerFrequencyHz(), metadata.sampleRateHz());
        }

        private long nextStateRevision()
        {
            return mStateRevisionSequence.incrementAndGet();
        }

        private String targetId()
        {
            return mTarget.target().targetId();
        }

        private Object identity()
        {
            return mTarget.identity();
        }

        @Override
        public void receive(INativeBuffer buffer)
        {
            if(mSessionClosed.get() || buffer == null)
            {
                return;
            }

            try
            {
                long ingressConfiguration = mProcessor.configuration();
                mProcessor.receive(buffer, System.currentTimeMillis(), ingressConfiguration);
            }
            catch(RuntimeException exception)
            {
                //Diagnostics are expendable.  Never log or clean up on the hardware-transfer callback.
                mDroppedIngressBuffers.incrementAndGet();
            }
        }

        @Override
        public void process(SourceEvent event)
        {
            if(mSessionClosed.get() || event == null || !event.hasValue())
            {
                return;
            }

            if(event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE)
            {
                long centerFrequencyHz = event.getValue().longValue();

                if(centerFrequencyHz > 0)
                {
                    mObservedCenterFrequencyHz.set(centerFrequencyHz);
                    mProcessor.updateMetadata(centerFrequencyHz, mObservedSampleRateHz.get());
                }
            }
            else if(event.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
            {
                double rawSampleRate = event.getValue().doubleValue();
                long sampleRateHz = Double.isFinite(rawSampleRate) ? Math.round(rawSampleRate) : 0;

                if(sampleRateHz > 0)
                {
                    mObservedSampleRateHz.set(sampleRateHz);
                    mProcessor.updateMetadata(mObservedCenterFrequencyHz.get(), sampleRateHz);
                }
            }
        }

        private void publish(FftResult result)
        {
            if(mSessionClosed.get() || result == null || result.bins() == null ||
                result.bins().length != FFT_SIZE || result.fftSize() != FFT_SIZE ||
                result.centerFrequencyHz() <= 0 || result.sampleRateHz() <= 0)
            {
                return;
            }

            long sequence = mSequence.incrementAndGet();
            mFrames.offer(DiagnosticStreamFrame.float32(DiagnosticStreamFrame.TYPE_TUNER_FFT, mGeneration,
                sequence, result.observedAtEpochMs(), result.centerFrequencyHz(), result.sampleRateHz(), FFT_SIZE,
                0, FFT_SIZE, result.bins()));
        }

        private State state(String state, String reason)
        {
            Target metadata = mMetadata;
            Viewport viewport = mViewport;
            AnalysisPlan plan = analysisPlan(metadata.centerFrequencyHz(), metadata.sampleRateHz(), viewport);
            Long requestedStart = viewport != null ? viewport.startFrequencyHz() : null;
            Long requestedEnd = viewport != null ? viewport.endFrequencyHz() : null;
            long revision = Math.max(mViewportStateRevision, mMetadataStateRevision);
            return new State(revision, mGeneration, state, reason, metadata.targetId(), metadata.label(),
                metadata.centerFrequencyHz(), metadata.sampleRateHz(), metadata.activeChannelCount(), FFT_SIZE,
                FRAMES_PER_SECOND, MAXIMUM_TRANSMITTED_BINS, requestedStart, requestedEnd,
                plan.startFrequencyHz(), plan.endFrequencyHz(), 0, FFT_SIZE, FFT_SIZE);
        }

        private void detach()
        {
            if(!mDetached.compareAndSet(false, true))
            {
                return;
            }

            RuntimeException cleanupFailure = null;

            if(mBufferListenerAttached)
            {
                mBufferListenerAttached = false;

                try
                {
                    mTarget.controller().removeBufferListener(this);
                }
                catch(RuntimeException exception)
                {
                    cleanupFailure = exception;
                }
            }

            if(mSourceEventListenerAttached)
            {
                mSourceEventListenerAttached = false;

                try
                {
                    mTarget.controller().removeListener(this);
                }
                catch(RuntimeException exception)
                {
                    if(cleanupFailure == null)
                    {
                        cleanupFailure = exception;
                    }
                    else
                    {
                        cleanupFailure.addSuppressed(exception);
                    }
                }
            }

            try
            {
                mProcessor.close();
            }
            catch(RuntimeException exception)
            {
                if(cleanupFailure == null)
                {
                    cleanupFailure = exception;
                }
                else
                {
                    cleanupFailure.addSuppressed(exception);
                }
            }

            if(cleanupFailure != null)
            {
                throw cleanupFailure;
            }
        }

        @Override
        public void close()
        {
            closeSession(this);
        }
    }

    /**
     * One worker-owned tuner FFT pipeline.  The tuner callback only offers an existing native-buffer reference to a
     * fixed SPSC queue; conversion, mixing, filtering, FFT, dB conversion, and encoding are all off that callback.
     */
    static final class TunerFftProcessor implements FrameProcessor
    {
        /** Retains one maximum-detail analysis window from tuners that publish small native buffers. */
        private static final int INGRESS_CAPACITY = 128;
        private static final int FILTER_SETTLING_SAMPLES = 64;

        private final BoundedSpscReferenceQueue<INativeBuffer> mIngress =
            new BoundedSpscReferenceQueue<>(INGRESS_CAPACITY);
        private final Consumer<FftResult> mConsumer;
        private final DiagnosticFftScheduler.Task mTask;
        private final AtomicReference<ProcessorConfiguration> mRequested;
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final AtomicLong mDroppedBuffers = new AtomicLong();
        /* Remaining fields are initialized and used only on the low-priority diagnostic worker. */
        private ProcessorConfiguration mApplied;
        private NativeBufferManager<INativeBuffer> mBufferManager;
        private FloatFFT_1D mFft;
        private float[] mWindow;
        private float[] mSourceSamples;
        private float[] mSourceI;
        private float[] mSourceQ;
        private float[] mFftSamples;
        private ComplexMixer mMixer;
        private IRealDecimationFilter mIDecimator;
        private IRealDecimationFilter mQDecimator;
        private long mPendingTimestamp;
        private volatile Thread mInitializationThread;

        TunerFftProcessor(DiagnosticFftScheduler scheduler, long centerFrequencyHz, long sampleRateHz,
                          Consumer<FftResult> consumer)
        {
            mConsumer = Objects.requireNonNull(consumer, "Diagnostic FFT consumer cannot be null");
            AnalysisPlan plan = analysisPlan(centerFrequencyHz, sampleRateHz, null);
            mRequested = new AtomicReference<>(new ProcessorConfiguration(1, centerFrequencyHz, sampleRateHz,
                null, plan));
            mTask = scheduler.scheduleWithFixedDelay(this::calculate, FRAMES_PER_SECOND);
        }

        @Override
        public void receive(INativeBuffer buffer, long observedAtEpochMs, long ingressConfiguration)
        {
            if(buffer == null || mClosed.get())
            {
                return;
            }

            long timestamp = observedAtEpochMs > 0 ? observedAtEpochMs : buffer.getTimestamp();

            if(!mIngress.offer(buffer, Math.max(0, timestamp), ingressConfiguration))
            {
                mDroppedBuffers.incrementAndGet();
            }
        }

        @Override
        public long configuration()
        {
            return mRequested.get().revision();
        }

        long droppedBufferCount()
        {
            return mDroppedBuffers.get();
        }

        Thread initializationThread()
        {
            return mInitializationThread;
        }

        @Override
        public void updateMetadata(long centerFrequencyHz, long sampleRateHz)
        {
            if(centerFrequencyHz <= 0 || sampleRateHz <= 0)
            {
                return;
            }

            reconfigure(centerFrequencyHz, sampleRateHz, null, false);
        }

        @Override
        public void updateViewport(Viewport viewport)
        {
            reconfigure(0, 0, viewport, true);
        }

        private void reconfigure(long centerFrequencyHz, long sampleRateHz, Viewport viewport,
                                 boolean viewportUpdate)
        {
            while(!mClosed.get())
            {
                ProcessorConfiguration current = mRequested.get();
                long nextCenter = viewportUpdate ? current.tunerCenterFrequencyHz() : centerFrequencyHz;
                long nextRate = viewportUpdate ? current.tunerSampleRateHz() : sampleRateHz;
                Viewport nextViewport = viewportUpdate ? viewport : current.viewport();
                AnalysisPlan nextPlan = analysisPlan(nextCenter, nextRate, nextViewport);

                boolean sameAnalysis = current.tunerCenterFrequencyHz() == nextCenter &&
                    current.tunerSampleRateHz() == nextRate && current.plan().equals(nextPlan);

                if(sameAnalysis && Objects.equals(current.viewport(), nextViewport))
                {
                    return;
                }

                //Browser pan/zoom within the current guarded lens changes only its crop.  Retain the worker epoch so
                //we neither reset filters nor discard an otherwise valid FFT frame.
                long nextRevision = sameAnalysis ? current.revision() : current.revision() + 1;
                ProcessorConfiguration next = new ProcessorConfiguration(nextRevision, nextCenter,
                    nextRate, nextViewport, nextPlan);

                if(mRequested.compareAndSet(current, next))
                {
                    return;
                }
            }
        }

        private void calculate()
        {
            if(mClosed.get())
            {
                return;
            }

            ProcessorConfiguration requested = mRequested.get();

            try
            {
                if(mApplied == null || mApplied.revision() != requested.revision())
                {
                    apply(requested);
                    return;
                }

                drainIngress(requested.revision());
                mBufferManager.get(mSourceSamples.length / 2, mSourceSamples);
                prepareFftSamples(requested.plan());
                WindowFactory.apply(mWindow, mFftSamples);
                mFft.complexForward(mFftSamples);
                float[] bins = ComplexDecibelConverter.convert(mFftSamples);

                if(requested.revision() == mRequested.get().revision() && !mClosed.get())
                {
                    long observedAt = mPendingTimestamp;
                    mPendingTimestamp = 0;
                    AnalysisPlan plan = requested.plan();
                    mConsumer.accept(new FftResult(observedAt > 0 ? observedAt : System.currentTimeMillis(),
                        plan.centerFrequencyHz(), plan.sampleRateHz(), FFT_SIZE, bins));
                }
            }
            catch(IOException exception)
            {
                //Keep accumulated worker-owned samples for the next bounded pass.
            }
            catch(RuntimeException exception)
            {
                if(!mClosed.get())
                {
                    mLog.warn("Unable to calculate a tuner diagnostic FFT frame", exception);
                }
            }
        }

        private void apply(ProcessorConfiguration configuration)
        {
            if(mFft == null)
            {
                mFft = new FloatFFT_1D(FFT_SIZE);
                mWindow = WindowFactory.getWindow(WindowType.BLACKMAN_HARRIS_7, FFT_SIZE * 2);
                mFftSamples = new float[FFT_SIZE * 2];
                mInitializationThread = Thread.currentThread();
            }

            mIngress.clear();
            AnalysisPlan plan = configuration.plan();
            int outputSamples = FFT_SIZE + (plan.decimation() > 1 ? FILTER_SETTLING_SAMPLES : 0);
            int sourceSampleCount = outputSamples * plan.decimation();
            mBufferManager = new NativeBufferManager<>(sourceSampleCount);
            mSourceSamples = new float[sourceSampleCount * 2];
            mSourceI = null;
            mSourceQ = null;
            mMixer = null;
            mIDecimator = null;
            mQDecimator = null;

            if(plan.decimation() > 1)
            {
                mSourceI = new float[sourceSampleCount];
                mSourceQ = new float[sourceSampleCount];
                double mixerFrequency = configuration.tunerCenterFrequencyHz() - plan.centerFrequencyHz();
                mMixer = ComplexMixerFactory.getMixer(mixerFrequency, configuration.tunerSampleRateHz());
                mIDecimator = DecimationFilterFactory.getRealDecimationFilter(plan.decimation());
                mQDecimator = DecimationFilterFactory.getRealDecimationFilter(plan.decimation());
            }

            mPendingTimestamp = 0;
            mApplied = configuration;
        }

        private void drainIngress(long revision)
        {
            INativeBuffer buffer;

            while((buffer = mIngress.poll()) != null)
            {
                if(mIngress.lastPolledSecondaryMetadata() != revision)
                {
                    continue;
                }

                if(mPendingTimestamp <= 0 && mIngress.lastPolledMetadata() > 0)
                {
                    mPendingTimestamp = mIngress.lastPolledMetadata();
                }

                mBufferManager.add(buffer);
            }
        }

        private void prepareFftSamples(AnalysisPlan plan)
        {
            if(plan.decimation() == 1)
            {
                System.arraycopy(mSourceSamples, 0, mFftSamples, 0, mFftSamples.length);
                return;
            }

            for(int source = 0, output = 0; source < mSourceSamples.length; source += 2, output++)
            {
                mSourceI[output] = mSourceSamples[source];
                mSourceQ[output] = mSourceSamples[source + 1];
            }

            ComplexSamples mixed = mMixer.mix(mSourceI, mSourceQ, mPendingTimestamp);
            float[] decimatedI = mIDecimator.decimateReal(mixed.i());
            float[] decimatedQ = mQDecimator.decimateReal(mixed.q());
            int start = decimatedI.length - FFT_SIZE;

            if(start < 0 || decimatedQ.length != decimatedI.length)
            {
                throw new IllegalStateException("Tuner diagnostic decimator returned an invalid sample count");
            }

            for(int input = start, output = 0; output < mFftSamples.length; input++, output += 2)
            {
                mFftSamples[output] = decimatedI[input];
                mFftSamples[output + 1] = decimatedQ[input];
            }
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mTask.close();
            }
        }
    }

    /** Chooses the highest power-of-two decimation whose central 80% still contains the requested viewport. */
    static AnalysisPlan analysisPlan(long tunerCenterFrequencyHz, long tunerSampleRateHz, Viewport viewport)
    {
        if(tunerCenterFrequencyHz <= 0 || tunerSampleRateHz <= 0)
        {
            throw new IllegalArgumentException("Tuner diagnostic analysis metadata is invalid");
        }

        if(viewport == null)
        {
            return new AnalysisPlan(tunerCenterFrequencyHz, tunerSampleRateHz, 1);
        }

        double tunerStart = tunerCenterFrequencyHz - tunerSampleRateHz / 2.0;
        double tunerEnd = tunerStart + tunerSampleRateHz;

        if(viewport.startFrequencyHz() < tunerStart || viewport.endFrequencyHz() > tunerEnd)
        {
            return new AnalysisPlan(tunerCenterFrequencyHz, tunerSampleRateHz, 1);
        }

        double requestedCenter = ((double)viewport.startFrequencyHz() + viewport.endFrequencyHz()) / 2.0;

        int maximumDecimation = maximumDecimation(tunerSampleRateHz);

        for(int candidate = maximumDecimation; candidate >= 2; candidate /= 2)
        {
            long analysisSampleRateHz = Math.max(1, Math.round(tunerSampleRateHz / (double)candidate));
            double halfAnalysis = analysisSampleRateHz / 2.0;
            double halfUsable = halfAnalysis * USABLE_LENS_FRACTION;
            double minimumCenter = Math.max(tunerStart + halfAnalysis,
                viewport.endFrequencyHz() - halfUsable);
            double maximumCenter = Math.min(tunerEnd - halfAnalysis,
                viewport.startFrequencyHz() + halfUsable);

            if(minimumCenter <= maximumCenter)
            {
                long analysisCenter = Math.round(Math.max(minimumCenter,
                    Math.min(maximumCenter, requestedCenter)));
                return new AnalysisPlan(analysisCenter, analysisSampleRateHz, candidate);
            }
        }

        return new AnalysisPlan(tunerCenterFrequencyHz, tunerSampleRateHz, 1);
    }

    private static int maximumDecimation(long tunerSampleRateHz)
    {
        long samplesPerFrame = Math.max(1, tunerSampleRateHz / FRAMES_PER_SECOND);
        long supported = samplesPerFrame / (FFT_SIZE + TunerFftProcessor.FILTER_SETTLING_SAMPLES);
        int decimation = 1;

        while(decimation * 2 <= MAXIMUM_DECIMATION && decimation * 2 <= supported)
        {
            decimation *= 2;
        }

        return decimation;
    }

    @FunctionalInterface
    interface TargetSource
    {
        List<AvailableTarget> availableTargets();
    }

    @FunctionalInterface
    interface ProcessorFactory
    {
        FrameProcessor create(TargetSnapshot target, Consumer<FftResult> consumer);
    }

    interface FrameProcessor extends AutoCloseable
    {
        void receive(INativeBuffer buffer, long observedAtEpochMs, long ingressConfiguration);

        long configuration();

        void updateMetadata(long centerFrequencyHz, long sampleRateHz);

        default void updateViewport(Viewport viewport)
        {
        }

        @Override
        void close();
    }

    record FftResult(long observedAtEpochMs, long centerFrequencyHz, long sampleRateHz, int fftSize, float[] bins)
    {
    }

    record AnalysisPlan(long centerFrequencyHz, long sampleRateHz, int decimation)
    {
        double startFrequencyHz()
        {
            return centerFrequencyHz - sampleRateHz / 2.0;
        }

        double endFrequencyHz()
        {
            return startFrequencyHz() + sampleRateHz;
        }
    }

    private record ProcessorConfiguration(long revision, long tunerCenterFrequencyHz, long tunerSampleRateHz,
                                          Viewport viewport, AnalysisPlan plan)
    {
    }

    record AvailableTarget(Object identity, TunerClass tunerClass, TunerController controller,
                           ChannelCount activeChannelCount)
    {
        AvailableTarget
        {
            Objects.requireNonNull(identity, "Tuner target identity cannot be null");
            Objects.requireNonNull(controller, "Tuner target controller cannot be null");
            Objects.requireNonNull(activeChannelCount, "Tuner channel count cannot be null");
        }
    }

    @FunctionalInterface
    interface ChannelCount
    {
        int get();
    }

    record TargetSnapshot(Object identity, TunerController controller, ChannelCount activeChannelCount, Target target)
    {
    }

    private record RuntimeIdentity(String targetId, String label)
    {
    }
}
