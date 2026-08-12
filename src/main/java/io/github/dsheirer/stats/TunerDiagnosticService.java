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
import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.SourceEvent;
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
    /** One stable source-side resolution; browser viewports only crop/pool this worker-produced result. */
    public static final int FFT_SIZE = 16_384;
    public static final int MAXIMUM_TRANSMITTED_BINS = 4_096;
    public static final int FRAMES_PER_SECOND = 10;
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
        mProcessorFactory = (target, consumer) -> new SharedFftProcessor(scheduler, target, consumer);
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
                    producer = new Producer(target, mNextGeneration.incrementAndGet());
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
        private volatile Viewport mViewport;
        private volatile long mViewportStateRevision;

        private Session(Producer producer, Viewport viewport)
        {
            mProducer = producer;
            mViewport = viewport;
            mViewportStateRevision = producer.nextStateRevision();
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

        /**
         * Changes only this viewer's worker-side crop.  The shared tuner listener and source FFT remain attached and
         * unchanged, so browser zoom and pan cannot interrupt sample ingress or reset the producer.
         */
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
            mViewportStateRevision = mProducer.nextStateRevision();
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
                DiagnosticStreamFrame frame = mFrames.poll(Duration.ZERO);
                return matchesViewport(frame) ? frame : null;
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

                if(matchesViewport(frame))
                {
                    return frame;
                }
            }

            return null;
        }

        private boolean matchesViewport(DiagnosticStreamFrame frame)
        {
            if(frame == null)
            {
                return false;
            }

            if(frame.type() != DiagnosticStreamFrame.TYPE_TUNER_FFT)
            {
                return true;
            }

            FrameLayout expected = frameLayout(frame.centerFrequencyHz(), frame.sampleRateHz(), frame.fftSize(),
                mViewport);
            return frame.firstBin() == expected.firstBin() &&
                frame.sourceBinCount() == expected.sourceBinCount() &&
                frame.valueCount() == expected.transmittedBinCount();
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
            mViewportStateRevision = mProducer.nextStateRevision();
            mFrames.close();
            return true;
        }

        @Override
        public void close()
        {
            closeSession(this);
        }
    }

    private final class Producer implements Listener<INativeBuffer>, ISourceEventProcessor, AutoCloseable
    {
        private final TargetSnapshot mTarget;
        private final long mGeneration;
        private final CopyOnWriteArrayList<Session> mSessions = new CopyOnWriteArrayList<>();
        private final AtomicBoolean mProducerClosed = new AtomicBoolean();
        private final AtomicLong mSequence = new AtomicLong();
        private final AtomicLong mStateRevisionSequence = new AtomicLong();
        private final AtomicLong mObservedCenterFrequencyHz = new AtomicLong();
        private final AtomicLong mObservedSampleRateHz = new AtomicLong();
        private final AtomicLong mDroppedIngressBuffers = new AtomicLong();
        private final FrameProcessor mProcessor;
        private volatile Target mMetadata;
        private volatile long mMetadataStateRevision;
        private boolean mBufferListenerAttached;
        private boolean mSourceEventListenerAttached;
        private long mNextAvailabilityCheckNanos;

        private Producer(TargetSnapshot target, long generation)
        {
            mTarget = target;
            mGeneration = generation;
            mMetadata = target.target();
            mMetadataStateRevision = nextStateRevision();
            mObservedCenterFrequencyHz.set(target.target().centerFrequencyHz());
            mObservedSampleRateHz.set(target.target().sampleRateHz());
            FrameProcessor processor = mProcessorFactory.create(target, this::publish);
            mProcessor = Objects.requireNonNull(processor, "Diagnostic processor factory returned null");
            mProcessor.updateMetadata(target.target().centerFrequencyHz(), target.target().sampleRateHz());

            try
            {
                if(!target.controller().getLock().tryLock())
                {
                    throw new IllegalStateException("Selected tuner is busy changing configuration");
                }

                try
                {
                    //USB and RSP controllers use this same reentrant lock for listener changes.  Rechecking while
                    //holding it guarantees that this diagnostic tap cannot become the first hardware listener.
                    if(target.activeChannelCount().get() <= 0 || !target.controller().hasBufferListeners())
                    {
                        throw new IllegalStateException("Selected tuner is no longer actively receiving");
                    }

                    //Refresh the initial immutable metadata before registering the source-event listener.  This is
                    //lifecycle work under the controller's existing lock, never work performed by a sample callback.
                    long centerFrequencyHz = target.controller().getFrequency();
                    double rawSampleRate = target.controller().getSampleRate();
                    long sampleRateHz = Double.isFinite(rawSampleRate) ? Math.round(rawSampleRate) : 0;

                    if(centerFrequencyHz > 0 && sampleRateHz > 0)
                    {
                        mObservedCenterFrequencyHz.set(centerFrequencyHz);
                        mObservedSampleRateHz.set(sampleRateHz);
                        mProcessor.updateMetadata(centerFrequencyHz, sampleRateHz);
                    }

                    //Some controllers can register the listener before their sample-transfer startup throws.  Mark
                    //the listener as cleanup-owned first; removal is safe when registration failed before insertion.
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
            mMetadataStateRevision = nextStateRevision();
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
                //The native callback does only a timestamp read and bounded ingress offer.  Tuning metadata is
                //captured by source events, and FFT/configuration work is performed on the diagnostic worker.
                long ingressConfiguration = mProcessor.configuration();
                mProcessor.receive(buffer, System.currentTimeMillis(), ingressConfiguration);
            }
            catch(RuntimeException exception)
            {
                //Never log or invoke cleanup from a hardware-transfer callback.  Diagnostics are expendable.
                mDroppedIngressBuffers.incrementAndGet();
            }
        }

        @Override
        public void process(SourceEvent event)
        {
            if(mProducerClosed.get() || event == null || !event.hasValue())
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
                if(session.isClosed())
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
            Viewport viewport = session.mViewport;
            FrameLayout layout = frameLayout(metadata.centerFrequencyHz(), metadata.sampleRateHz(), FFT_SIZE,
                viewport);
            Long requestedStart = viewport != null ? viewport.startFrequencyHz() : null;
            Long requestedEnd = viewport != null ? viewport.endFrequencyHz() : null;

            //State contains session-specific viewport fields.  Assign its revision when the snapshot is created so
            //two different viewports can never advertise different content with the same generation and revision.
            long revision = Math.max(session.mViewportStateRevision, mMetadataStateRevision);
            return new State(revision, mGeneration, state, reason, metadata.targetId(),
                metadata.label(),
                metadata.centerFrequencyHz(), metadata.sampleRateHz(), metadata.activeChannelCount(), FFT_SIZE,
                FRAMES_PER_SECOND, MAXIMUM_TRANSMITTED_BINS, requestedStart, requestedEnd,
                visibleStart(metadata.centerFrequencyHz(), metadata.sampleRateHz(), FFT_SIZE, layout),
                visibleEnd(metadata.centerFrequencyHz(), metadata.sampleRateHz(), FFT_SIZE, layout),
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

            mSessions.clear();

            if(cleanupFailure != null)
            {
                throw cleanupFailure;
            }
        }
    }

    private static final class SharedFftProcessor implements FrameProcessor
    {
        private final Consumer<FftResult> mConsumer;
        private final DemandDftProcessor mProcessor;
        private final AtomicLong mCenterFrequencyHz = new AtomicLong();
        private final AtomicLong mSampleRateHz = new AtomicLong();
        private final AtomicLong mMetadataUpdate = new AtomicLong();
        private final AtomicLong mMetadataConfiguration = new AtomicLong(1);
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private SharedFftProcessor(DiagnosticFftScheduler scheduler, TargetSnapshot target,
                                   Consumer<FftResult> consumer)
        {
            mConsumer = consumer;
            mCenterFrequencyHz.set(target.target().centerFrequencyHz());
            mSampleRateHz.set(target.target().sampleRateHz());
            mProcessor = new DemandDftProcessor(scheduler, dftSize(FFT_SIZE), FRAMES_PER_SECOND,
                this::publish);
        }

        @Override
        public void receive(INativeBuffer buffer, long observedAtEpochMs, long ingressConfiguration)
        {
            if(!mClosed.get())
            {
                mProcessor.receive(buffer, observedAtEpochMs, ingressConfiguration);
            }
        }

        @Override
        public long configuration()
        {
            return mProcessor.configuration();
        }

        @Override
        public void updateMetadata(long centerFrequencyHz, long sampleRateHz)
        {
            if(mClosed.get() || centerFrequencyHz <= 0 || sampleRateHz <= 0)
            {
                return;
            }

            long previousCenter = mCenterFrequencyHz.get();
            long previousRate = mSampleRateHz.get();

            if(previousCenter != centerFrequencyHz || previousRate != sampleRateHz)
            {
                //A nonblocking sequence guard prevents an old FFT from being labelled with new tuning metadata.
                mMetadataUpdate.incrementAndGet();
                mCenterFrequencyHz.set(centerFrequencyHz);
                mSampleRateHz.set(sampleRateHz);
                mMetadataConfiguration.set(mProcessor.requestReset());
                mMetadataUpdate.incrementAndGet();
            }
        }

        private void publish(long observedAtEpochMs, float[] bins, long configuration)
        {
            if(mClosed.get() || bins == null || bins.length < 1)
            {
                return;
            }

            try
            {
                long update = mMetadataUpdate.get();

                if((update & 1) != 0 || configuration != mMetadataConfiguration.get())
                {
                    return;
                }

                long centerFrequencyHz = mCenterFrequencyHz.get();
                long sampleRateHz = mSampleRateHz.get();

                if(update == mMetadataUpdate.get() && centerFrequencyHz > 0 && sampleRateHz > 0 && !mClosed.get())
                {
                    mConsumer.accept(new FftResult(observedAtEpochMs, centerFrequencyHz, sampleRateHz, bins.length,
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
            if(mClosed.compareAndSet(false, true))
            {
                mProcessor.close();
            }
        }
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
        FrameProcessor create(TargetSnapshot target, Consumer<FftResult> consumer);
    }

    interface FrameProcessor extends AutoCloseable
    {
        void receive(INativeBuffer buffer, long observedAtEpochMs, long ingressConfiguration);

        long configuration();

        void updateMetadata(long centerFrequencyHz, long sampleRateHz);

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
