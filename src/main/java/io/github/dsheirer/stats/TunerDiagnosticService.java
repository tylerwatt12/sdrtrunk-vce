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
    public static final int FFT_SIZE = 4_096;
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
        mProcessorFactory = (target, generation, consumer) ->
            new SharedFftProcessor(scheduler, target, generation, consumer);
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

            if(producer == null)
            {
                if(mProducers.size() >= MAXIMUM_PRODUCERS)
                {
                    return new OpenResult(OpenStatus.BUSY, null);
                }

                try
                {
                    producer = new Producer(target, mNextGeneration.incrementAndGet());
                    mProducers.put(targetId, producer);
                }
                catch(RuntimeException exception)
                {
                    mLog.warn("Unable to start tuner spectrum diagnostics", exception);
                    return new OpenResult(OpenStatus.UNAVAILABLE, null);
                }
            }

            Session session = new Session(producer);
            producer.add(session);
            mSessionCount++;
            return new OpenResult(OpenStatus.OPEN, session);
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
     * Current bounded stream state and counters for one session's shared producer.
     */
    public record State(long revision, long generation, String state, String reason, String targetId, String label,
                        long centerFrequencyHz, long sampleRateHz, int activeChannelCount, int fftSize,
                        int framesPerSecond)
    {
    }

    public final class Session implements AutoCloseable
    {
        private final Producer mProducer;
        private final DiagnosticFrameQueue mFrames = new DiagnosticFrameQueue();
        private final AtomicBoolean mSessionClosed = new AtomicBoolean();
        private volatile String mTerminalState;
        private volatile String mTerminalReason;

        private Session(Producer producer)
        {
            mProducer = producer;
        }

        public State state()
        {
            checkAvailable(mProducer, true);
            String terminalState = mTerminalState;

            if(terminalState != null)
            {
                return mProducer.state(terminalState, mTerminalReason);
            }

            return mProducer.state("live", "");
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
        private final FrameProcessor mProcessor;
        private volatile Target mMetadata;
        private boolean mBufferListenerAttached;
        private long mNextAvailabilityCheckNanos;

        private Producer(TargetSnapshot target, long generation)
        {
            mTarget = target;
            mGeneration = generation;
            mMetadata = target.target();
            FrameProcessor processor = mProcessorFactory.create(target, generation, this::publish);
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
            mMetadata = metadata;
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
            mSessions.add(session);
        }

        private void remove(Session session)
        {
            mSessions.remove(session);
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
                mProcessor.receive(buffer, System.currentTimeMillis());
            }
            catch(RuntimeException exception)
            {
                mLog.debug("Unable to enqueue a tuner diagnostic sample buffer", exception);
            }
        }

        private void publish(DiagnosticStreamFrame frame)
        {
            if(mProducerClosed.get() || frame == null)
            {
                return;
            }

            //The immutable, already-encoded frame instance is shared by all session queues.
            for(Session session: mSessions)
            {
                session.offer(frame);
            }
        }

        private State state(String state, String reason)
        {
            Target metadata = mMetadata;

            return new State(1, mGeneration, state, reason, metadata.targetId(), metadata.label(),
                metadata.centerFrequencyHz(), metadata.sampleRateHz(), metadata.activeChannelCount(), FFT_SIZE,
                FRAMES_PER_SECOND);
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
        private final TargetSnapshot mTarget;
        private final long mGeneration;
        private final Consumer<DiagnosticStreamFrame> mConsumer;
        private final AtomicLong mSequence = new AtomicLong();
        private final DemandDftProcessor mProcessor;

        private SharedFftProcessor(DiagnosticFftScheduler scheduler, TargetSnapshot target, long generation,
                                   Consumer<DiagnosticStreamFrame> consumer)
        {
            mTarget = target;
            mGeneration = generation;
            mConsumer = consumer;
            mProcessor = new DemandDftProcessor(scheduler, DFTSize.FFT04096, FRAMES_PER_SECOND,
                this::publish);
        }

        @Override
        public void receive(INativeBuffer buffer, long observedAtEpochMs)
        {
            mProcessor.receive(buffer, observedAtEpochMs);
        }

        private void publish(long observedAtEpochMs, float[] bins)
        {
            if(bins == null || bins.length != FFT_SIZE)
            {
                return;
            }

            long frequency;
            long sampleRate;

            try
            {
                frequency = mTarget.controller().getFrequency();
                sampleRate = Math.round(mTarget.controller().getSampleRate());
            }
            catch(RuntimeException exception)
            {
                return;
            }

            if(frequency <= 0 || sampleRate <= 0)
            {
                return;
            }

            DiagnosticStreamFrame frame = DiagnosticStreamFrame.float32(DiagnosticStreamFrame.TYPE_TUNER_FFT,
                mGeneration, mSequence.incrementAndGet(), observedAtEpochMs, frequency, sampleRate, FFT_SIZE, bins);
            mConsumer.accept(frame);
        }

        @Override
        public void close()
        {
            mProcessor.close();
        }
    }

    @FunctionalInterface
    interface TargetSource
    {
        List<AvailableTarget> availableTargets();
    }

    @FunctionalInterface
    interface ProcessorFactory
    {
        FrameProcessor create(TargetSnapshot target, long generation, Consumer<DiagnosticStreamFrame> consumer);
    }

    interface FrameProcessor extends AutoCloseable
    {
        void receive(INativeBuffer buffer, long observedAtEpochMs);

        @Override
        void close();
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
