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
import io.github.dsheirer.dsp.window.Window;
import io.github.dsheirer.dsp.window.WindowFactory;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.spectrum.NativeBufferManager;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demand-owned, read-only spectrum diagnostics for one already-running tuner.
 *
 * <p>The service has one exclusive session.  That session owns one tuner listener, one bounded latest-loss-tolerant
 * worker, and one output slot.  Opening another tuner or viewer returns {@link OpenStatus#BUSY}.  The service never
 * initializes, retunes, or reconfigures tuner hardware.</p>
 *
 * <p>The worker uses the selected profile's fixed full-band FFT for the overview.  A narrow viewport switches that
 * same worker to a zoom lens: it mixes the requested center to baseband, anti-alias filters and decimates by two,
 * four, eight, sixteen, or thirty-two, then runs the same FFT.  Only one analysis path is active at a time.</p>
 */
public final class TunerDiagnosticService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerDiagnosticService.class);
    public static final int FFT_SIZE = 8_192;
    public static final int MAXIMUM_TRANSMITTED_BINS = FFT_SIZE;
    public static final int FRAMES_PER_SECOND = 10;
    private static final int MAXIMUM_DECIMATION = 32;
    private static final int QUANTIZATION_BITS = 8;
    private static final int MAXIMUM_PROFILE_FRAMES_PER_SECOND = 20;
    private static final long DEFAULT_IQ_QUEUE_DURATION_MILLISECONDS = 200;
    /** Keeps the requested view out of the anti-alias filter transition band. */
    private static final double USABLE_LENS_FRACTION = 0.80;

    private final Object mLifecycleLock = new Object();
    private final TargetSource mTargetSource;
    private final ProcessorFactory mProcessorFactory;
    private final IdentityHashMap<Object, RuntimeIdentity> mRuntimeIdentities = new IdentityHashMap<>();
    private final AtomicLong mNextGeneration = new AtomicLong();
    private Session mActiveSession;
    private boolean mClosed;

    public TunerDiagnosticService(TunerManager tunerManager, DiagnosticFftScheduler scheduler)
    {
        Objects.requireNonNull(tunerManager, "Tuner manager cannot be null");
        Objects.requireNonNull(scheduler, "Diagnostic FFT scheduler cannot be null");
        mTargetSource = () -> availableTargets(tunerManager);
        mProcessorFactory = (target, viewport, profile, consumer) -> new TunerFftProcessor(scheduler,
            target.target().centerFrequencyHz(), target.target().sampleRateHz(), viewport, profile, consumer);
    }

    /** Test seam that keeps lifecycle behavior independent from tuner hardware and FFT implementation. */
    TunerDiagnosticService(TargetSource targetSource, ProcessorFactory processorFactory)
    {
        mTargetSource = Objects.requireNonNull(targetSource, "Tuner target source cannot be null");
        mProcessorFactory = Objects.requireNonNull(processorFactory, "Diagnostic processor factory cannot be null");
    }

    /**
     * Lists enabled tuner targets without starting sample transfer.  IDs are opaque and stable for the lifetime of
     * the underlying tuner object; names and serials identify the administrator-selected hardware.
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
        return tryOpen(targetId, viewport, SpectrumProfile.BALANCED);
    }

    public OpenResult tryOpen(String targetId, Viewport viewport, SpectrumProfile profile)
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
                Session session = new Session(target, mNextGeneration.incrementAndGet(), viewport,
                    Objects.requireNonNull(profile, "Tuner spectrum profile cannot be null"));
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

                RuntimeIdentity identity = mRuntimeIdentities.computeIfAbsent(available.identity(), ignored ->
                    createRuntimeIdentity());
                String tunerClass = available.tunerClass() != null ? available.tunerClass().toString() :
                    TunerClass.UNKNOWN.toString();
                String name = displayIdentity(available.name(), tunerClass);
                String serial = displayIdentity(available.serial(), "Unknown");
                String label = name.toLowerCase().contains(serial.toLowerCase()) ? name : name + " · " + serial;
                Target target = new Target(identity.targetId(), label, name, serial, centerFrequency, sampleRate,
                    activeChannelCount);
                snapshots.add(new TargetSnapshot(available.identity(), controller, available.activeChannelCount(),
                    available.receiverQueueControl(), target));
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

    private RuntimeIdentity createRuntimeIdentity()
    {
        return new RuntimeIdentity(UUID.randomUUID().toString());
    }

    private static String displayIdentity(String value, String fallback)
    {
        return value != null && !value.isBlank() ? value.strip() : fallback;
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
                    ReceiverQueueControl queueControl = channelManager instanceof PolyphaseChannelSourceManager manager ?
                        new PolyphaseReceiverQueueControl(manager) : ReceiverQueueControl.UNSUPPORTED;
                    available.add(new AvailableTarget(tuner, candidate.getTunerClass(), tuner.getPreferredName(),
                        tuner.getUniqueID(), controller,
                        channelManager::getTunerChannelCount, queueControl));
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

    public record Target(String targetId, String label, String name, String serial, long centerFrequencyHz, long sampleRateHz,
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

    public enum SpectrumProfile
    {
        EFFICIENT("efficient", 2_048, 5),
        BALANCED("balanced", FFT_SIZE, FRAMES_PER_SECOND),
        HIGH_DETAIL("high-detail", 16_384, 20),
        MAXIMUM_DETAIL("maximum-detail", 32_768, 20);

        private final String mId;
        private final int mFftSize;
        private final int mFramesPerSecond;

        SpectrumProfile(String id, int fftSize, int framesPerSecond)
        {
            mId = id;
            mFftSize = fftSize;
            mFramesPerSecond = framesPerSecond;
        }

        public String id()
        {
            return mId;
        }

        public int fftSize()
        {
            return mFftSize;
        }

        public int framesPerSecond()
        {
            return mFramesPerSecond;
        }

        public static SpectrumProfile fromId(String id)
        {
            if(id == null || id.isBlank())
            {
                return BALANCED;
            }

            for(SpectrumProfile profile: values())
            {
                if(profile.id().equalsIgnoreCase(id))
                {
                    return profile;
                }
            }

            throw new IllegalArgumentException("Unknown tuner spectrum profile");
        }
    }

    /**
     * State keeps center/sample rate as the full tuner bounds.  visibleStart/End report the actual analysis domain;
     * binary FFT frames carry that same analysis center and sample rate.
     */
    public record State(long revision, long generation, String state, String reason, String targetId, String label,
                        long centerFrequencyHz, long sampleRateHz, int activeChannelCount, String profile, int fftSize,
                        int framesPerSecond, int maximumDecimation, int maximumTransmittedBins,
                        int quantizationBits,
                        long iqQueueDurationMilliseconds, long receiverQueuedMilliseconds,
                        long receiverDroppedBuffers, long receiverDroppedMilliseconds,
                        long diagnosticDroppedBuffers, Long requestedStartFrequencyHz,
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
        private final long mOriginalIqQueueDurationMilliseconds;
        private volatile Target mMetadata;
        private final AtomicReference<AnalysisSelection> mAnalysis;
        private volatile long mMetadataStateRevision;
        private volatile long mViewportStateRevision;
        private volatile String mTerminalState;
        private volatile String mTerminalReason;
        private boolean mBufferListenerAttached;
        private boolean mSourceEventListenerAttached;
        private long mNextAvailabilityCheckNanos;

        private Session(TargetSnapshot target, long generation, Viewport viewport, SpectrumProfile profile)
        {
            mTarget = target;
            mGeneration = generation;
            mMetadata = target.target();
            mAnalysis = new AtomicReference<>(new AnalysisSelection(viewport, profile));
            ReceiverQueueSnapshot originalQueue = target.receiverQueueControl().status();
            mOriginalIqQueueDurationMilliseconds = originalQueue.requestedDurationMilliseconds();
            mMetadataStateRevision = nextStateRevision();
            mViewportStateRevision = nextStateRevision();
            mObservedCenterFrequencyHz.set(target.target().centerFrequencyHz());
            mObservedSampleRateHz.set(target.target().sampleRateHz());
            FrameProcessor processor = mProcessorFactory.create(target, viewport, profile, this::publish);
            mProcessor = Objects.requireNonNull(processor, "Diagnostic processor factory returned null");

            try
            {
                mProcessor.updateMetadata(target.target().centerFrequencyHz(), target.target().sampleRateHz());
                mProcessor.updateConfiguration(viewport, profile);
                target.receiverQueueControl().request(DEFAULT_IQ_QUEUE_DURATION_MILLISECONDS);

                if(!target.controller().getLock().tryLock())
                {
                    throw new IllegalStateException("Selected tuner is busy changing configuration");
                }

                try
                {
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
            updateSelection(viewport, null, true);
        }

        /** Applies a spectrum profile to the existing worker; no tuner hardware is reconfigured. */
        public void updateProfile(SpectrumProfile profile)
        {
            Objects.requireNonNull(profile, "Tuner spectrum profile cannot be null");
            updateSelection(null, profile, false);
        }

        /** Applies a viewport/profile request as one worker epoch, avoiding a transient intermediate zoom plan. */
        public void updateConfiguration(Viewport viewport, SpectrumProfile profile)
        {
            Objects.requireNonNull(profile, "Tuner spectrum profile cannot be null");
            updateSelection(viewport, profile, true);
        }

        private synchronized void updateSelection(Viewport viewport, SpectrumProfile profile,
                                                  boolean replaceViewport)
        {

            if(mSessionClosed.get())
            {
                throw new IllegalStateException("Tuner diagnostic session is closed");
            }

            while(true)
            {
                AnalysisSelection current = mAnalysis.get();
                Viewport nextViewport = replaceViewport ? viewport : current.viewport();
                SpectrumProfile nextProfile = profile != null ? profile : current.profile();
                AnalysisSelection next = new AnalysisSelection(nextViewport, nextProfile);

                if(current.equals(next))
                {
                    return;
                }

                if(mAnalysis.compareAndSet(current, next))
                {
                    mViewportStateRevision = nextStateRevision();
                    mProcessor.updateConfiguration(nextViewport, nextProfile);
                    return;
                }
            }
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

            AnalysisSelection selection = mAnalysis.get();
            SpectrumProfile profile = selection.profile();
            AnalysisPlan expected = analysisPlan(mObservedCenterFrequencyHz.get(), mObservedSampleRateHz.get(),
                selection.viewport(), profile);
            return frame.centerFrequencyHz() == expected.centerFrequencyHz() &&
                frame.sampleRateHz() == expected.sampleRateHz() && frame.fftSize() == profile.fftSize() &&
                frame.firstBin() == 0 && frame.sourceBinCount() == profile.fftSize() &&
                frame.valueCount() == profile.fftSize();
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
                result.bins().length != result.fftSize() || result.fftSize() != mAnalysis.get().profile().fftSize() ||
                result.centerFrequencyHz() <= 0 || result.sampleRateHz() <= 0)
            {
                return;
            }

            long sequence = mSequence.incrementAndGet();
            mFrames.offer(DiagnosticStreamFrame.tunerFft(mGeneration,
                sequence, result.observedAtEpochMs(), result.centerFrequencyHz(), result.sampleRateHz(),
                result.fftSize(), QUANTIZATION_BITS, result.bins()));
        }

        private State state(String state, String reason)
        {
            Target metadata = mMetadata;
            AnalysisSelection selection = mAnalysis.get();
            Viewport viewport = selection.viewport();
            SpectrumProfile profile = selection.profile();
            AnalysisPlan plan = analysisPlan(metadata.centerFrequencyHz(), metadata.sampleRateHz(), viewport,
                profile);
            ReceiverQueueSnapshot receiver = mTarget.receiverQueueControl().status();
            Long requestedStart = viewport != null ? viewport.startFrequencyHz() : null;
            Long requestedEnd = viewport != null ? viewport.endFrequencyHz() : null;
            long revision = Math.max(mViewportStateRevision, mMetadataStateRevision);
            return new State(revision, mGeneration, state, reason, metadata.targetId(), metadata.label(),
                metadata.centerFrequencyHz(), metadata.sampleRateHz(), metadata.activeChannelCount(),
                profile.id(), profile.fftSize(), profile.framesPerSecond(), MAXIMUM_DECIMATION, profile.fftSize(),
                QUANTIZATION_BITS,
                receiver.requestedDurationMilliseconds(), receiver.queuedMilliseconds(), receiver.droppedBuffers(),
                receiver.droppedMilliseconds(), mDroppedIngressBuffers.get() + mProcessor.droppedBufferCount(),
                requestedStart, requestedEnd, plan.startFrequencyHz(), plan.endFrequencyHz(), 0,
                profile.fftSize(), profile.fftSize());
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

            try
            {
                mTarget.receiverQueueControl().request(mOriginalIqQueueDurationMilliseconds);
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
        private static final int MAXIMUM_SOURCE_WORKSPACES = SpectrumProfile.values().length *
            (Integer.numberOfTrailingZeros(MAXIMUM_DECIMATION) + 1);

        private final BoundedSpscReferenceQueue<INativeBuffer> mIngress =
            new BoundedSpscReferenceQueue<>(INGRESS_CAPACITY);
        private final Consumer<FftResult> mConsumer;
        private final DiagnosticComplexFft.Factory mFftFactory;
        private final DiagnosticFftScheduler.Task mTask;
        private final AtomicReference<ProcessorConfiguration> mRequested;
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final AtomicLong mDroppedBuffers = new AtomicLong();
        /* Remaining fields are initialized and used only on the low-priority diagnostic worker. */
        private ProcessorConfiguration mApplied;
        private NativeBufferManager<INativeBuffer> mBufferManager;
        private DiagnosticComplexFft mFft;
        private Window mWindow;
        private float[] mSourceSamples;
        private final Map<Integer,float[]> mSourceWorkspaces = new HashMap<>();
        private float[] mFftSamples;
        private float[] mDecibelBins;
        private DiagnosticZoomDsp mZoomDsp;
        private long mPendingTimestamp;
        private long mNextCalculationNanos;
        private volatile long mAppliedRevision;
        private volatile int mPublishedSourceWorkspaceCount;
        private volatile Thread mInitializationThread;

        TunerFftProcessor(DiagnosticFftScheduler scheduler, long centerFrequencyHz, long sampleRateHz,
                          Consumer<FftResult> consumer)
        {
            this(scheduler, centerFrequencyHz, sampleRateHz, null, SpectrumProfile.BALANCED, consumer);
        }

        TunerFftProcessor(DiagnosticFftScheduler scheduler, long centerFrequencyHz, long sampleRateHz,
                          Viewport viewport, SpectrumProfile profile, Consumer<FftResult> consumer)
        {
            this(scheduler, centerFrequencyHz, sampleRateHz, viewport, profile, consumer,
                SerialDiagnosticFft.FACTORY);
        }

        TunerFftProcessor(DiagnosticFftScheduler scheduler, long centerFrequencyHz, long sampleRateHz,
                          Viewport viewport, SpectrumProfile profile, Consumer<FftResult> consumer,
                          DiagnosticComplexFft.Factory fftFactory)
        {
            mConsumer = Objects.requireNonNull(consumer, "Diagnostic FFT consumer cannot be null");
            Objects.requireNonNull(scheduler, "Diagnostic FFT scheduler cannot be null");
            profile = Objects.requireNonNull(profile, "Tuner spectrum profile cannot be null");
            mFftFactory = Objects.requireNonNull(fftFactory, "Diagnostic FFT factory cannot be null");
            AnalysisPlan plan = analysisPlan(centerFrequencyHz, sampleRateHz, viewport, profile);
            mRequested = new AtomicReference<>(new ProcessorConfiguration(1, centerFrequencyHz, sampleRateHz,
                viewport, profile, plan));
            mTask = scheduler.scheduleWithFixedDelay(this::calculate, MAXIMUM_PROFILE_FRAMES_PER_SECOND);
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

        @Override
        public long droppedBufferCount()
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

        @Override
        public void updateProfile(SpectrumProfile profile)
        {
            Objects.requireNonNull(profile, "Tuner spectrum profile cannot be null");

            while(!mClosed.get())
            {
                ProcessorConfiguration current = mRequested.get();
                AnalysisPlan nextPlan = analysisPlan(current.tunerCenterFrequencyHz(), current.tunerSampleRateHz(),
                    current.viewport(), profile);

                if(current.profile() == profile && current.plan().equals(nextPlan))
                {
                    return;
                }

                ProcessorConfiguration next = new ProcessorConfiguration(current.revision() + 1,
                    current.tunerCenterFrequencyHz(), current.tunerSampleRateHz(), current.viewport(), profile,
                    nextPlan);

                if(mRequested.compareAndSet(current, next))
                {
                    return;
                }
            }
        }

        @Override
        public void updateConfiguration(Viewport viewport, SpectrumProfile profile)
        {
            Objects.requireNonNull(profile, "Tuner spectrum profile cannot be null");

            while(!mClosed.get())
            {
                ProcessorConfiguration current = mRequested.get();
                AnalysisPlan nextPlan = analysisPlan(current.tunerCenterFrequencyHz(),
                    current.tunerSampleRateHz(), viewport, profile);
                boolean sameAnalysis = current.profile() == profile && current.plan().equals(nextPlan);

                if(sameAnalysis && Objects.equals(current.viewport(), viewport))
                {
                    return;
                }

                long nextRevision = sameAnalysis ? current.revision() : current.revision() + 1;
                ProcessorConfiguration next = new ProcessorConfiguration(nextRevision,
                    current.tunerCenterFrequencyHz(), current.tunerSampleRateHz(), viewport, profile, nextPlan);

                if(mRequested.compareAndSet(current, next))
                {
                    return;
                }
            }
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
                AnalysisPlan nextPlan = analysisPlan(nextCenter, nextRate, nextViewport, current.profile());

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
                    nextRate, nextViewport, current.profile(), nextPlan);

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

                //Do not retain native tuner buffers in the ingress queue merely because this profile publishes below
                //the scheduler rate.  Move them into the bounded worker-owned accumulator every scheduler pass, while
                //FFT and transport stay profile-throttled.
                drainIngress(requested.revision());

                long now = System.nanoTime();

                if(now < mNextCalculationNanos)
                {
                    return;
                }

                mNextCalculationNanos = now +
                    TimeUnit.SECONDS.toNanos(1) / requested.profile().framesPerSecond();

                mBufferManager.get(mSourceSamples.length / 2, mSourceSamples);
                prepareFftSamples(requested.plan());
                mWindow.apply(mFftSamples);
                mFft.forward(mFftSamples);
                DiagnosticZoomDsp.decibels(mFftSamples, mDecibelBins);

                if(requested.revision() == mRequested.get().revision() && !mClosed.get())
                {
                    long observedAt = mPendingTimestamp;
                    mPendingTimestamp = 0;
                    AnalysisPlan plan = requested.plan();
                    mConsumer.accept(new FftResult(observedAt > 0 ? observedAt : System.currentTimeMillis(),
                        plan.centerFrequencyHz(), plan.sampleRateHz(), requested.profile().fftSize(), mDecibelBins));
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
            int fftSize = configuration.profile().fftSize();

            if(mFft == null || mFftSamples == null || mFftSamples.length != fftSize * 2)
            {
                mFft = mFftFactory.create(fftSize);
                mWindow = WindowFactory.getWindowProcessor(WindowType.BLACKMAN_HARRIS_7, fftSize * 2);
                mFftSamples = new float[fftSize * 2];
                mDecibelBins = new float[fftSize];
                mInitializationThread = Thread.currentThread();
            }

            mIngress.clear();
            AnalysisPlan plan = configuration.plan();
            int outputSamples = fftSize + (plan.decimation() > 1 ? FILTER_SETTLING_SAMPLES : 0);
            int sourceSampleCount = outputSamples * plan.decimation();
            mBufferManager = new NativeBufferManager<>(sourceSampleCount);
            int requiredLength = sourceSampleCount * 2;
            //There are only 24 possible profile/decimation lengths. Retain each size once so rapid D1-D32 zoom
            //cycling cannot continually allocate multi-megabyte native-conversion arrays.
            mSourceSamples = sourceWorkspace(requiredLength);

            if(plan.decimation() > 1)
            {
                double mixerFrequency = configuration.tunerCenterFrequencyHz() - plan.centerFrequencyHz();
                mZoomDsp = mZoomDsp != null ? mZoomDsp : new DiagnosticZoomDsp();
                mZoomDsp.configure(sourceSampleCount, plan.decimation(), mixerFrequency,
                    configuration.tunerSampleRateHz());
            }

            mPendingTimestamp = 0;
            mNextCalculationNanos = 0;
            mApplied = configuration;
            mAppliedRevision = configuration.revision();
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

            mZoomDsp.process(mSourceSamples, mSourceSamples.length / 2, mFftSamples);
        }

        int sourceWorkspaceCount()
        {
            return mPublishedSourceWorkspaceCount;
        }

        int maximumSourceWorkspaceCount()
        {
            return MAXIMUM_SOURCE_WORKSPACES;
        }

        long appliedConfiguration()
        {
            return mAppliedRevision;
        }

        private float[] sourceWorkspace(int requiredLength)
        {
            float[] workspace = mSourceWorkspaces.get(requiredLength);

            if(workspace != null)
            {
                return workspace;
            }

            if(mSourceWorkspaces.size() >= MAXIMUM_SOURCE_WORKSPACES)
            {
                Integer oldestLength = mSourceWorkspaces.keySet().iterator().next();
                mSourceWorkspaces.remove(oldestLength);
            }

            workspace = new float[requiredLength];
            mSourceWorkspaces.put(requiredLength, workspace);
            mPublishedSourceWorkspaceCount = mSourceWorkspaces.size();
            return workspace;
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
        return analysisPlan(tunerCenterFrequencyHz, tunerSampleRateHz, viewport, SpectrumProfile.BALANCED);
    }

    static AnalysisPlan analysisPlan(long tunerCenterFrequencyHz, long tunerSampleRateHz, Viewport viewport,
                                     SpectrumProfile profile)
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

        int maximumDecimation = maximumDecimation(tunerSampleRateHz, profile);

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

    private static int maximumDecimation(long tunerSampleRateHz, SpectrumProfile profile)
    {
        long samplesPerFrame = Math.max(1, tunerSampleRateHz / profile.framesPerSecond());
        long supported = samplesPerFrame / (profile.fftSize() + TunerFftProcessor.FILTER_SETTLING_SAMPLES);
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
        FrameProcessor create(TargetSnapshot target, Viewport viewport, SpectrumProfile profile,
                              Consumer<FftResult> consumer);
    }

    interface FrameProcessor extends AutoCloseable
    {
        void receive(INativeBuffer buffer, long observedAtEpochMs, long ingressConfiguration);

        long configuration();

        default long droppedBufferCount()
        {
            return 0;
        }

        void updateMetadata(long centerFrequencyHz, long sampleRateHz);

        default void updateViewport(Viewport viewport)
        {
        }

        default void updateProfile(SpectrumProfile profile)
        {
        }

        default void updateConfiguration(Viewport viewport, SpectrumProfile profile)
        {
            updateViewport(viewport);
            updateProfile(profile);
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

    private record AnalysisSelection(Viewport viewport, SpectrumProfile profile)
    {
        private AnalysisSelection
        {
            Objects.requireNonNull(profile, "Tuner spectrum profile cannot be null");
        }
    }

    private record ProcessorConfiguration(long revision, long tunerCenterFrequencyHz, long tunerSampleRateHz,
                                          Viewport viewport, SpectrumProfile profile, AnalysisPlan plan)
    {
    }

    record AvailableTarget(Object identity, TunerClass tunerClass, String name, String serial,
                           TunerController controller,
                           ChannelCount activeChannelCount, ReceiverQueueControl receiverQueueControl)
    {
        AvailableTarget(Object identity, TunerClass tunerClass, TunerController controller,
                        ChannelCount activeChannelCount)
        {
            this(identity, tunerClass, tunerClass != null ? tunerClass.toString() : TunerClass.UNKNOWN.toString(),
                "Unknown", controller, activeChannelCount, ReceiverQueueControl.UNSUPPORTED);
        }

        AvailableTarget(Object identity, TunerClass tunerClass, TunerController controller,
                        ChannelCount activeChannelCount, ReceiverQueueControl receiverQueueControl)
        {
            this(identity, tunerClass, tunerClass != null ? tunerClass.toString() : TunerClass.UNKNOWN.toString(),
                "Unknown", controller, activeChannelCount, receiverQueueControl);
        }

        AvailableTarget
        {
            Objects.requireNonNull(identity, "Tuner target identity cannot be null");
            Objects.requireNonNull(controller, "Tuner target controller cannot be null");
            Objects.requireNonNull(activeChannelCount, "Tuner channel count cannot be null");
            Objects.requireNonNull(receiverQueueControl, "Receiver IQ queue control cannot be null");
        }
    }

    interface ReceiverQueueControl
    {
        ReceiverQueueControl UNSUPPORTED = new ReceiverQueueControl()
        {
            @Override
            public ReceiverQueueSnapshot status()
            {
                return ReceiverQueueSnapshot.UNSUPPORTED;
            }

            @Override
            public void request(long durationMilliseconds)
            {
                if(durationMilliseconds != DEFAULT_IQ_QUEUE_DURATION_MILLISECONDS)
                {
                    throw new IllegalStateException("The selected tuner does not expose an adjustable IQ queue");
                }
            }
        };

        ReceiverQueueSnapshot status();

        void request(long durationMilliseconds);
    }

    private record PolyphaseReceiverQueueControl(PolyphaseChannelSourceManager manager)
        implements ReceiverQueueControl
    {
        @Override
        public ReceiverQueueSnapshot status()
        {
            var status = manager.getNativeBufferQueueStatus();
            return new ReceiverQueueSnapshot(true, status.appliedDurationMilliseconds(),
                status.requestedDurationMilliseconds(), status.queuedMilliseconds(), status.droppedBuffers(),
                status.droppedMilliseconds());
        }

        @Override
        public void request(long durationMilliseconds)
        {
            manager.requestNativeBufferQueueDuration(durationMilliseconds);
        }
    }

    record ReceiverQueueSnapshot(boolean supported, long appliedDurationMilliseconds,
                                 long requestedDurationMilliseconds, long queuedMilliseconds,
                                 long droppedBuffers, long droppedMilliseconds)
    {
        private static final ReceiverQueueSnapshot UNSUPPORTED =
            new ReceiverQueueSnapshot(false, DEFAULT_IQ_QUEUE_DURATION_MILLISECONDS,
                DEFAULT_IQ_QUEUE_DURATION_MILLISECONDS, 0, 0, 0);
    }

    @FunctionalInterface
    interface ChannelCount
    {
        int get();
    }

    record TargetSnapshot(Object identity, TunerController controller, ChannelCount activeChannelCount,
                          ReceiverQueueControl receiverQueueControl, Target target)
    {
    }

    private record RuntimeIdentity(String targetId)
    {
    }

}
