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
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.spectrum.DFTSize;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demand-owned, read-only spectrum diagnostics for already-running tuners.
 *
 * <p>Construction and target discovery never attach a sample listener or create a DFT producer.  The first session
 * for a target attaches one bounded processor to that tuner's controller, and all further sessions for the same
 * target share its frames.  Closing the last session detaches and disposes that processor immediately.  Only tuners
 * with an active channel and an existing sample listener are exposed, so this diagnostic tap cannot be the listener
 * that starts hardware sample transfer.  The service never initializes, retunes, or reconfigures a tuner.</p>
 */
public final class TunerDiagnosticService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerDiagnosticService.class);
    public static final int BASE_FFT_SIZE = 4_096;
    public static final int MAXIMUM_FFT_SIZE = 32_768;
    public static final int MAXIMUM_TRANSMITTED_BINS = 4_096;
    /** Retained name for channel and transport callers that use the default full-width detail. */
    public static final int FFT_SIZE = BASE_FFT_SIZE;
    public static final int FRAMES_PER_SECOND = 20;
    public static final int MAXIMUM_PRODUCERS = 2;
    public static final int MAXIMUM_SESSIONS = 32;

    private final Object mLifecycleLock = new Object();
    private final TargetSource mTargetSource;
    private final ProcessorFactory mProcessorFactory;
    private final IdentityHashMap<Object, RuntimeIdentity> mRuntimeIdentities = new IdentityHashMap<>();
    private final Map<TunerClass, Integer> mNextClassOrdinal = new HashMap<>();
    private final Map<String, Producer> mProducers = new LinkedHashMap<>();
    private final AtomicLong mNextGeneration = new AtomicLong();
    private boolean mClosed;
    private int mSessionCount;

    /**
     * Constructs a passive tuner diagnostic service over the shared FFT scheduler.
     */
    public TunerDiagnosticService(TunerManager tunerManager, DiagnosticFftScheduler scheduler)
    {
        Objects.requireNonNull(tunerManager, "Tuner manager cannot be null");
        Objects.requireNonNull(scheduler, "Diagnostic FFT scheduler cannot be null");
        mTargetSource = () -> availableTargets(tunerManager);
        mProcessorFactory = (target, initialFftSize, consumer) ->
            new SharedFftProcessor(scheduler, target, initialFftSize, consumer);
    }

    /**
     * Test seam that keeps lifecycle and fan-out behavior independent from tuner hardware and FFT implementation.
     */
    TunerDiagnosticService(TargetSource targetSource, ProcessorFactory processorFactory)
    {
        mTargetSource = Objects.requireNonNull(targetSource, "Tuner target source cannot be null");
        mProcessorFactory = Objects.requireNonNull(processorFactory, "Diagnostic processor factory cannot be null");
    }

    /**
     * Lists currently available targets without starting a producer.  Runtime IDs are opaque and stable for the
     * lifetime of each underlying tuner object.  Labels intentionally use only the non-identifying tuner class.
     */
    public List<Target> targets()
    {
        synchronized(mLifecycleLock)
        {
            if(mClosed)
            {
                return List.of();
            }

            List<TargetSnapshot> snapshots = snapshotsLocked();
            return snapshots.stream().map(TargetSnapshot::target)
                .sorted(Comparator.comparing(Target::label, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Target::targetId)).toList();
        }
    }

    /**
     * Opens a latest-only session for a tuner target without changing that tuner's settings.
     */
    public OpenResult tryOpen(String targetId)
    {
        return tryOpen(targetId, null);
    }

    /**
     * Opens a latest-only session for a tuner target and an optional bounded frequency viewport.
     */
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

            if(mSessionCount >= MAXIMUM_SESSIONS)
            {
                return new OpenResult(OpenStatus.BUSY, null);
            }

            TargetSnapshot target = snapshotsLocked().stream()
                .filter(candidate -> targetId.equals(candidate.target().targetId())).findFirst().orElse(null);

            if(target == null)
            {
                return new OpenResult(OpenStatus.NOT_FOUND, null);
            }

            Producer producer = mProducers.get(targetId);

            if(producer != null && !producer.matches(target))
            {
                closeProducerLocked(producer, "Tuner is no longer available.");
                producer = null;
            }

            boolean created = false;

            if(producer == null)
            {
                if(mProducers.size() >= MAXIMUM_PRODUCERS)
                {
                    return new OpenResult(OpenStatus.BUSY, null);
                }

                try
                {
                    int initialFftSize = requiredFftSize(target.target().sampleRateHz(), viewport);
                    producer = new Producer(target, mNextGeneration.incrementAndGet(), initialFftSize);
                    mProducers.put(targetId, producer);
                    created = true;
                }
                catch(RuntimeException exception)
                {
                    mLog.warn("Unable to start tuner spectrum diagnostics", exception);
                    return new OpenResult(OpenStatus.UNAVAILABLE, null);
                }
            }

            try
            {
                Session session = new Session(producer, viewport);
                producer.add(session);
                mSessionCount++;
                return new OpenResult(OpenStatus.OPEN, session);
            }
            catch(RuntimeException exception)
            {
                if(created)
                {
                    mProducers.remove(targetId, producer);

                    try
                    {
                        producer.close();
                    }
                    catch(RuntimeException cleanupException)
                    {
                        exception.addSuppressed(cleanupException);
                    }
                }

                mLog.warn("Unable to configure tuner spectrum diagnostics", exception);
                return new OpenResult(OpenStatus.UNAVAILABLE, null);
            }
        }
    }

    /**
     * Number of currently attached tuner producers.  Primarily used for bounded runtime telemetry and tests.
     */
    public int activeProducerCount()
    {
        synchronized(mLifecycleLock)
        {
            return mProducers.size();
        }
    }

    /**
     * Number of currently open diagnostic sessions.
     */
    public int activeSessionCount()
    {
        synchronized(mLifecycleLock)
        {
            return mSessionCount;
        }
    }

    /**
     * Releases all active streams and tuner listeners while keeping this passive service available for a later web
     * listener restart.
     */
    public void closeActiveSessions()
    {
        synchronized(mLifecycleLock)
        {
            closeActiveSessionsLocked("Tuner diagnostics were stopped.");
        }
    }

    private void closeActiveSessionsLocked(String reason)
    {
        for(Producer producer: List.copyOf(mProducers.values()))
        {
            closeProducerLocked(producer, reason);
        }

        mProducers.clear();
        mSessionCount = 0;
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
                //A tuner can disappear while the manager snapshot is being read.  Omit that transient target.
                mLog.debug("A tuner diagnostic target became unavailable during discovery", exception);
            }
        }

        pruneRuntimeIdentitiesLocked(seen);
        return snapshots;
    }

    private void pruneRuntimeIdentitiesLocked(IdentityHashMap<Object, Boolean> available)
    {
        mRuntimeIdentities.entrySet().removeIf(entry -> !available.containsKey(entry.getKey()) &&
            mProducers.values().stream().noneMatch(producer -> producer.identity() == entry.getKey()));
    }

    private RuntimeIdentity createRuntimeIdentity(TunerClass tunerClass)
    {
        TunerClass safeClass = tunerClass != null ? tunerClass : TunerClass.UNKNOWN;
        int ordinal = mNextClassOrdinal.merge(safeClass, 1, Integer::sum);
        return new RuntimeIdentity(UUID.randomUUID().toString(), safeClass + " " + ordinal);
    }

    private void closeSession(Session session)
    {
        synchronized(mLifecycleLock)
        {
            if(!session.markClosed())
            {
                return;
            }

            Producer producer = session.mProducer;
            producer.remove(session);
            mSessionCount = Math.max(0, mSessionCount - 1);

            if(producer.isEmpty())
            {
                mProducers.remove(producer.targetId(), producer);

                try
                {
                    producer.close();
                }
                catch(RuntimeException exception)
                {
                    mLog.debug("Unable to completely detach a tuner diagnostic producer", exception);
                }
            }
        }
    }

    private void checkAvailable(Producer producer, boolean force)
    {
        synchronized(mLifecycleLock)
        {
            if(mClosed || mProducers.get(producer.targetId()) != producer || !producer.shouldCheckAvailability(force))
            {
                return;
            }

            TargetSnapshot current = snapshotsLocked().stream()
                .filter(candidate -> producer.targetId().equals(candidate.target().targetId()))
                .findFirst().orElse(null);

            if(current == null || !producer.matches(current))
            {
                closeProducerLocked(producer, "Tuner is no longer available.");
            }
            else
            {
                producer.updateMetadata(current.target());
            }
        }
    }

    private void closeProducerLocked(Producer producer, String reason)
    {
        mProducers.remove(producer.targetId(), producer);

        for(Session session: producer.sessions())
        {
            if(session.markUnavailable(reason))
            {
                mSessionCount = Math.max(0, mSessionCount - 1);
            }
        }

        try
        {
            producer.close();
        }
        catch(RuntimeException exception)
        {
            //The producer is already closed and removed from service ownership.  A disappearing tuner can make its
            //controller reject a redundant detach; do not strand the web request or the remaining service cleanup.
            mLog.debug("Unable to completely detach a tuner diagnostic producer", exception);
        }
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

            closeActiveSessionsLocked("Tuner diagnostics are closed.");
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
                //Tuner hot removal can invalidate one entry while iterating the manager's current snapshot.
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

    /**
     * Public, non-sensitive tuner metadata used by target selection.
     */
    public record Target(String targetId, String label, long centerFrequencyHz, long sampleRateHz,
                         int activeChannelCount)
    {
    }

    /**
     * Requested frequency viewport for one stream.  The service clamps it to the tuner's current sampled bandwidth
     * and to the supported eight-times maximum zoom.
     */
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
     * Current bounded stream state and counters for one session's shared producer.
     */
    public record State(long revision, long generation, String state, String reason, String targetId, String label,
                        long centerFrequencyHz, long sampleRateHz, int activeChannelCount, int fftSize,
                        int framesPerSecond, int maximumTransmittedBins, Long requestedStartFrequencyHz,
                        Long requestedEndFrequencyHz, double visibleStartFrequencyHz,
                        double visibleEndFrequencyHz, int firstBin, int sourceBinCount, int transmittedBinCount)
    {
    }

    public final class Session implements AutoCloseable
    {
        private final Producer mProducer;
        private final DiagnosticFrameQueue mFrames = new DiagnosticFrameQueue();
        private final AtomicBoolean mSessionClosed = new AtomicBoolean();
        private volatile String mTerminalState;
        private volatile String mTerminalReason;
        private final Viewport mViewport;

        private Session(Producer producer, Viewport viewport)
        {
            mProducer = producer;
            mViewport = viewport;
        }

        public State state()
        {
            checkAvailable(mProducer, true);
            String terminalState = mTerminalState;

            if(terminalState != null)
            {
                return mProducer.state(this, terminalState, mTerminalReason);
            }

            return mProducer.state(this, "live", "");
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
                checkAvailable(mProducer, false);
                return mFrames.poll(Duration.ZERO);
            }

            long started = System.nanoTime();
            long timeoutNanos = timeout.toNanos();
            long livenessIntervalNanos = Duration.ofSeconds(1).toNanos();

            while(!mSessionClosed.get())
            {
                checkAvailable(mProducer, false);

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

                if(frame != null)
                {
                    return frame;
                }
            }

            return null;
        }

        public boolean isClosed()
        {
            return mSessionClosed.get();
        }

        private void offer(DiagnosticStreamFrame frame)
        {
            mFrames.offer(frame);
        }

        private boolean markClosed()
        {
            if(!mSessionClosed.compareAndSet(false, true))
            {
                return false;
            }

            mTerminalState = "closed";
            mTerminalReason = "Tuner diagnostic session is closed.";
            mFrames.close();
            return true;
        }

        private boolean markUnavailable(String reason)
        {
            if(!mSessionClosed.compareAndSet(false, true))
            {
                return false;
            }

            mTerminalState = "unavailable";
            mTerminalReason = reason;
            mFrames.close();
            return true;
        }

        @Override
        public void close()
        {
            closeSession(this);
        }
    }

    private final class Producer implements Listener<INativeBuffer>, AutoCloseable
    {
        private final TargetSnapshot mTarget;
        private final long mGeneration;
        private final CopyOnWriteArrayList<Session> mSessions = new CopyOnWriteArrayList<>();
        private final AtomicBoolean mProducerClosed = new AtomicBoolean();
        private final AtomicLong mSequence = new AtomicLong();
        private final AtomicLong mStateRevision = new AtomicLong(1);
        private final FrameProcessor mProcessor;
        private volatile Target mMetadata;
        private boolean mBufferListenerAttached;
        private long mNextAvailabilityCheckNanos;

        private Producer(TargetSnapshot target, long generation, int initialFftSize)
        {
            mTarget = target;
            mGeneration = generation;
            mMetadata = target.target();
            FrameProcessor processor = mProcessorFactory.create(target, initialFftSize, this::publish);
            mProcessor = Objects.requireNonNull(processor, "Diagnostic processor factory returned null");

            try
            {
                target.controller().getLock().lock();

                try
                {
                    //USB and RSP controllers use this same reentrant lock for listener changes.  Rechecking while
                    //holding it guarantees that this diagnostic tap cannot become the first hardware listener.
                    if(target.activeChannelCount().get() <= 0 || !target.controller().hasBufferListeners())
                    {
                        throw new IllegalStateException("Selected tuner is no longer actively receiving");
                    }

                    //Some controllers can register the listener before their sample-transfer startup throws.  Mark
                    //the listener as cleanup-owned first; removal is safe when registration failed before insertion.
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
                    close();
                }
                catch(RuntimeException cleanupException)
                {
                    exception.addSuppressed(cleanupException);
                }

                throw exception;
            }
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

            try
            {
                configureProcessor(null);
            }
            catch(RuntimeException exception)
            {
                //The existing resolution is still correct and usable.  A later session or metadata refresh retries.
                mLog.debug("Unable to resize tuner diagnostics after a tuner metadata change", exception);
            }
        }

        private String targetId()
        {
            return mTarget.target().targetId();
        }

        private Object identity()
        {
            return mTarget.identity();
        }

        private void add(Session session)
        {
            configureProcessor(session);
            mSessions.add(session);
        }

        private void remove(Session session)
        {
            mSessions.remove(session);

            if(!mSessions.isEmpty())
            {
                try
                {
                    configureProcessor(null);
                }
                catch(RuntimeException exception)
                {
                    //Keeping a larger FFT after a downshift failure is correct; retry on the next lifecycle change.
                    mLog.debug("Unable to reduce tuner diagnostic FFT detail", exception);
                }
            }
        }

        private void configureProcessor(Session additional)
        {
            Target metadata = mMetadata;
            int required = BASE_FFT_SIZE;

            for(Session session: mSessions)
            {
                required = Math.max(required, requiredFftSize(metadata.sampleRateHz(), session.mViewport));
            }

            if(additional != null)
            {
                required = Math.max(required, requiredFftSize(metadata.sampleRateHz(), additional.mViewport));
            }

            int previous = mProcessor.fftSize();

            if(required != previous)
            {
                mProcessor.setFftSize(required);
            }
        }

        private boolean isEmpty()
        {
            return mSessions.isEmpty();
        }

        private List<Session> sessions()
        {
            return List.copyOf(mSessions);
        }

        @Override
        public void receive(INativeBuffer buffer)
        {
            if(mProducerClosed.get() || buffer == null)
            {
                return;
            }

            try
            {
                //Capture the wall-clock observation at the tuner callback boundary.  Downstream FFT scheduling and
                //transport delay can therefore be measured without doing conversion or encoding on this thread.
                //Capture tuning under the controller lock as well, so an asynchronous result can never label old
                //samples with settings read after a retune.
                long observedAtEpochMs = System.currentTimeMillis();
                TunerController controller = mTarget.controller();
                long centerFrequencyHz;
                long sampleRateHz;
                controller.getLock().lock();

                try
                {
                    centerFrequencyHz = controller.getFrequency();
                    double sampleRate = controller.getSampleRate();
                    sampleRateHz = Double.isFinite(sampleRate) ? Math.round(sampleRate) : 0;
                }
                finally
                {
                    controller.getLock().unlock();
                }

                if(centerFrequencyHz > 0 && sampleRateHz > 0)
                {
                    mProcessor.receive(buffer, observedAtEpochMs, centerFrequencyHz, sampleRateHz);
                }
            }
            catch(RuntimeException exception)
            {
                mLog.debug("Unable to enqueue a tuner diagnostic sample buffer", exception);
            }
        }

        private void publish(FftResult result)
        {
            if(mProducerClosed.get() || result == null || result.bins() == null || result.fftSize() < 1 ||
                result.bins().length != result.fftSize() || result.centerFrequencyHz() <= 0 ||
                result.sampleRateHz() <= 0)
            {
                return;
            }

            long sequence = mSequence.incrementAndGet();
            Map<FrameLayout, DiagnosticStreamFrame> encoded = new HashMap<>();

            for(Session session: mSessions)
            {
                if(session.isClosed() || result.fftSize() < requiredFftSize(result.sampleRateHz(),
                    session.mViewport))
                {
                    continue;
                }

                FrameLayout layout = frameLayout(result.centerFrequencyHz(), result.sampleRateHz(),
                    result.fftSize(), session.mViewport);
                DiagnosticStreamFrame frame = encoded.computeIfAbsent(layout,
                    key -> encode(result, key, mGeneration, sequence));
                session.offer(frame);
            }
        }

        private State state(Session session, String state, String reason)
        {
            Target metadata = mMetadata;
            int fftSize = mProcessor.fftSize();
            FrameLayout layout = frameLayout(metadata.centerFrequencyHz(), metadata.sampleRateHz(), fftSize,
                session.mViewport);
            Long requestedStart = session.mViewport != null ? session.mViewport.startFrequencyHz() : null;
            Long requestedEnd = session.mViewport != null ? session.mViewport.endFrequencyHz() : null;

            //State contains session-specific viewport fields.  Assign its revision when the snapshot is created so
            //two different viewports can never advertise different content with the same generation and revision.
            return new State(mStateRevision.getAndIncrement(), mGeneration, state, reason, metadata.targetId(),
                metadata.label(),
                metadata.centerFrequencyHz(), metadata.sampleRateHz(), metadata.activeChannelCount(), fftSize,
                FRAMES_PER_SECOND, MAXIMUM_TRANSMITTED_BINS, requestedStart, requestedEnd,
                visibleStart(metadata.centerFrequencyHz(), metadata.sampleRateHz(), fftSize, layout),
                visibleEnd(metadata.centerFrequencyHz(), metadata.sampleRateHz(), fftSize, layout),
                layout.firstBin(), layout.sourceBinCount(), layout.transmittedBinCount());
        }

        @Override
        public void close()
        {
            if(!mProducerClosed.compareAndSet(false, true))
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

            mSessions.clear();

            if(cleanupFailure != null)
            {
                throw cleanupFailure;
            }
        }
    }

    private static final class SharedFftProcessor implements FrameProcessor
    {
        private final Object mLock = new Object();
        private final DiagnosticFftScheduler mScheduler;
        private final Consumer<FftResult> mConsumer;
        private volatile DemandDftProcessor mProcessor;
        private volatile int mFftSize;
        private volatile long mConfiguration;
        private long mCenterFrequencyHz;
        private long mSampleRateHz;
        private volatile boolean mClosed;

        private SharedFftProcessor(DiagnosticFftScheduler scheduler, TargetSnapshot target, int initialFftSize,
                                   Consumer<FftResult> consumer)
        {
            mScheduler = scheduler;
            mConsumer = consumer;
            mCenterFrequencyHz = target.target().centerFrequencyHz();
            mSampleRateHz = target.target().sampleRateHz();
            mProcessor = create(initialFftSize, ++mConfiguration, mCenterFrequencyHz, mSampleRateHz);
            mFftSize = initialFftSize;
        }

        @Override
        public void receive(INativeBuffer buffer, long observedAtEpochMs, long centerFrequencyHz, long sampleRateHz)
        {
            if(centerFrequencyHz <= 0 || sampleRateHz <= 0)
            {
                return;
            }

            DemandDftProcessor processor;
            DemandDftProcessor previous = null;
            boolean tuningChanged = false;

            synchronized(mLock)
            {
                if(mClosed)
                {
                    return;
                }

                if(centerFrequencyHz != mCenterFrequencyHz || sampleRateHz != mSampleRateHz)
                {
                    long configuration = mConfiguration + 1;
                    DemandDftProcessor replacement = create(mFftSize, configuration, centerFrequencyHz,
                        sampleRateHz);
                    previous = mProcessor;
                    mConfiguration = configuration;
                    mCenterFrequencyHz = centerFrequencyHz;
                    mSampleRateHz = sampleRateHz;
                    mProcessor = replacement;
                    processor = replacement;
                    tuningChanged = true;
                }
                else
                {
                    processor = mProcessor;
                }
            }

            if(previous != null)
            {
                previous.close();
            }

            //The first buffer observed across a tuning boundary may contain transition samples.  Start the new
            //processor with the next buffer so that a displayed FFT never spans both tunings.
            if(!tuningChanged && processor != null)
            {
                processor.receive(buffer, observedAtEpochMs);
            }
        }

        @Override
        public void setFftSize(int fftSize)
        {
            if(fftSize != BASE_FFT_SIZE && fftSize != 8_192 && fftSize != 16_384 &&
                fftSize != MAXIMUM_FFT_SIZE)
            {
                throw new IllegalArgumentException("Unsupported tuner diagnostic FFT size: " + fftSize);
            }

            DemandDftProcessor previous;

            synchronized(mLock)
            {
                if(mClosed)
                {
                    throw new IllegalStateException("Tuner diagnostic processor is closed");
                }

                if(fftSize == mFftSize)
                {
                    return;
                }

                long configuration = mConfiguration + 1;
                DemandDftProcessor replacement = create(fftSize, configuration, mCenterFrequencyHz,
                    mSampleRateHz);
                previous = mProcessor;
                mConfiguration = configuration;
                mProcessor = replacement;
                mFftSize = fftSize;
            }

            if(previous != null)
            {
                previous.close();
            }
        }

        @Override
        public int fftSize()
        {
            return mFftSize;
        }

        private DemandDftProcessor create(int fftSize, long configuration, long centerFrequencyHz,
                                          long sampleRateHz)
        {
            return new DemandDftProcessor(mScheduler, dftSize(fftSize), FRAMES_PER_SECOND,
                (observedAt, bins) -> publish(configuration, centerFrequencyHz, sampleRateHz, fftSize, observedAt,
                    bins));
        }

        private void publish(long configuration, long centerFrequencyHz, long sampleRateHz, int fftSize,
                             long observedAtEpochMs, float[] bins)
        {
            if(mClosed || configuration != mConfiguration || bins == null || bins.length != fftSize)
            {
                return;
            }

            try
            {
                if(configuration == mConfiguration && !mClosed)
                {
                    mConsumer.accept(new FftResult(observedAtEpochMs, centerFrequencyHz, sampleRateHz, fftSize,
                        bins));
                }
            }
            catch(RuntimeException exception)
            {
                //A session can close while this low-priority result is being published.
            }
        }

        @Override
        public void close()
        {
            DemandDftProcessor processor;

            synchronized(mLock)
            {
                if(mClosed)
                {
                    return;
                }

                mClosed = true;
                mConfiguration++;
                processor = mProcessor;
                mProcessor = null;
            }

            if(processor != null)
            {
                processor.close();
            }
        }
    }

    static int requiredFftSize(long sampleRateHz, Viewport viewport)
    {
        if(sampleRateHz <= 0 || viewport == null)
        {
            return BASE_FFT_SIZE;
        }

        double span = Math.min(sampleRateHz,
            (double)viewport.endFrequencyHz() - viewport.startFrequencyHz());
        double zoom = sampleRateHz / Math.max(1.0, span);
        int fftSize = BASE_FFT_SIZE;

        while(fftSize < MAXIMUM_FFT_SIZE && zoom >= 2.0)
        {
            fftSize *= 2;
            zoom /= 2.0;
        }

        return fftSize;
    }

    static FrameLayout frameLayout(long centerFrequencyHz, long sampleRateHz, int fftSize, Viewport viewport)
    {
        if(centerFrequencyHz <= 0 || sampleRateHz <= 0 || fftSize <= 0)
        {
            throw new IllegalArgumentException("Tuner diagnostic frame metadata is invalid");
        }

        double fullStart = centerFrequencyHz - sampleRateHz / 2.0;
        double fullEnd = fullStart + sampleRateHz;
        double requestedSpan = viewport == null ? sampleRateHz : Math.min(sampleRateHz,
            (double)viewport.endFrequencyHz() - viewport.startFrequencyHz());
        double span = Math.max(sampleRateHz / 8.0, requestedSpan);
        double requestedCenter = viewport == null ? centerFrequencyHz :
            ((double)viewport.startFrequencyHz() + viewport.endFrequencyHz()) / 2.0;
        double boundedCenter = Math.max(fullStart + span / 2.0,
            Math.min(fullEnd - span / 2.0, requestedCenter));
        double binWidth = (double)sampleRateHz / fftSize;
        int sourceBinCount = Math.max(1, Math.min(fftSize, (int)Math.round(span / binWidth)));
        int firstBin = (int)Math.round((boundedCenter - fullStart) / binWidth - sourceBinCount / 2.0);
        firstBin = Math.max(0, Math.min(fftSize - sourceBinCount, firstBin));
        return new FrameLayout(firstBin, sourceBinCount,
            Math.min(sourceBinCount, MAXIMUM_TRANSMITTED_BINS));
    }

    static float[] projectBins(float[] bins, FrameLayout layout)
    {
        Objects.requireNonNull(bins, "Tuner diagnostic FFT bins cannot be null");
        Objects.requireNonNull(layout, "Tuner diagnostic frame layout cannot be null");

        if(layout.firstBin() < 0 || layout.sourceBinCount() < 1 ||
            (long)layout.firstBin() + layout.sourceBinCount() > bins.length ||
            layout.transmittedBinCount() < 1 || layout.transmittedBinCount() > layout.sourceBinCount() ||
            layout.transmittedBinCount() > MAXIMUM_TRANSMITTED_BINS)
        {
            throw new IllegalArgumentException("Tuner diagnostic frame layout is outside the FFT result");
        }

        if(layout.sourceBinCount() == layout.transmittedBinCount())
        {
            return Arrays.copyOfRange(bins, layout.firstBin(), layout.firstBin() + layout.sourceBinCount());
        }

        float[] values = new float[layout.transmittedBinCount()];

        for(int output = 0; output < values.length; output++)
        {
            int start = layout.firstBin() + (int)((long)output * layout.sourceBinCount() / values.length);
            int end = layout.firstBin() + (int)((long)(output + 1) * layout.sourceBinCount() / values.length);
            float maximum = Float.NEGATIVE_INFINITY;

            for(int input = start; input < Math.max(start + 1, end); input++)
            {
                maximum = Math.max(maximum, bins[input]);
            }

            values[output] = maximum;
        }

        return values;
    }

    private static DiagnosticStreamFrame encode(FftResult result, FrameLayout layout, long generation, long sequence)
    {
        return DiagnosticStreamFrame.float32(DiagnosticStreamFrame.TYPE_TUNER_FFT, generation, sequence,
            result.observedAtEpochMs(), result.centerFrequencyHz(), result.sampleRateHz(), result.fftSize(),
            layout.firstBin(), layout.sourceBinCount(), projectBins(result.bins(), layout));
    }

    private static double visibleStart(long centerFrequencyHz, long sampleRateHz, int fftSize, FrameLayout layout)
    {
        return centerFrequencyHz - sampleRateHz / 2.0 +
            layout.firstBin() * ((double)sampleRateHz / fftSize);
    }

    private static double visibleEnd(long centerFrequencyHz, long sampleRateHz, int fftSize, FrameLayout layout)
    {
        return visibleStart(centerFrequencyHz, sampleRateHz, fftSize, layout) +
            layout.sourceBinCount() * ((double)sampleRateHz / fftSize);
    }

    private static DFTSize dftSize(int size)
    {
        for(DFTSize candidate: DFTSize.values())
        {
            if(candidate.getSize() == size)
            {
                return candidate;
            }
        }

        throw new IllegalArgumentException("Unsupported tuner diagnostic FFT size: " + size);
    }

    @FunctionalInterface
    interface TargetSource
    {
        List<AvailableTarget> availableTargets();
    }

    @FunctionalInterface
    interface ProcessorFactory
    {
        FrameProcessor create(TargetSnapshot target, int initialFftSize, Consumer<FftResult> consumer);
    }

    interface FrameProcessor extends AutoCloseable
    {
        void receive(INativeBuffer buffer, long observedAtEpochMs, long centerFrequencyHz, long sampleRateHz);

        void setFftSize(int fftSize);

        int fftSize();

        @Override
        void close();
    }

    record FftResult(long observedAtEpochMs, long centerFrequencyHz, long sampleRateHz, int fftSize, float[] bins)
    {
    }

    record FrameLayout(int firstBin, int sourceBinCount, int transmittedBinCount)
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
