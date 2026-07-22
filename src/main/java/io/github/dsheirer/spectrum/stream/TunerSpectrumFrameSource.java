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

package io.github.dsheirer.spectrum.stream;

import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerRegistry;
import io.github.dsheirer.spectrum.ComplexDftProcessor;
import io.github.dsheirer.spectrum.DFTSize;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only, demand-driven FFT source for one already-running tuner.
 *
 * <p>The tuner callback performs only the existing bounded native-buffer enqueue. FFT conversion and frame
 * publication run on the dedicated DFT executor; no WebSocket, JSON, database, disk, or network operation can execute
 * on the USB/sample callback. This source never initializes, retunes, stops, or otherwise owns the selected tuner.
 * Attaching to an initialized USB tuner that has no other buffer listener can activate that controller's sample
 * transfer loop until this source detaches.</p>
 */
public final class TunerSpectrumFrameSource implements InteractiveSpectrumFrameSource, ISourceEventProcessor
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerSpectrumFrameSource.class);
    public static final String PREFERRED_TUNER_PROPERTY = "sdrtrunk.web.signal.tuner";
    public static final String TUNER_CLASS_PROPERTY = "sdrtrunk.web.signal.tuner.class";
    private static final long CONTROL_DEBOUNCE_MILLISECONDS = 150;
    private static final long CONTROL_SHUTDOWN_SECONDS = 5;
    private static final long METADATA_REFRESH_INTERVAL_MILLISECONDS = 50;
    private static final long TARGET_LIVENESS_INTERVAL_SECONDS = 1;

    private final Configuration mConfiguration;
    private final TunerRegistry mTunerRegistry;
    private final Object mLifecycleLock = new Object();
    private final Object mControlLock = new Object();
    private final AtomicLong mSequence = new AtomicLong();
    private final AtomicLong mGeneration = new AtomicLong();
    private final AtomicLong mPublishedFrameCount = new AtomicLong();
    private final AtomicLong mPublicationErrorCount = new AtomicLong();
    private final AtomicBoolean mMetadataRefreshRequested = new AtomicBoolean();
    private final AtomicReference<ViewRequest> mRequestedView = new AtomicReference<>();
    private final ScheduledThreadPoolExecutor mControlExecutor;
    private volatile boolean mRunning;
    private volatile boolean mClosed;
    private volatile Consumer<SpectrumFrame> mFrameConsumer;
    private volatile String mTargetLabel = "unavailable";
    private volatile String mTargetId;
    private volatile PreparedView mPreparedView;
    private volatile AppliedView mAppliedView;
    private volatile long mLastViewRevision;
    private Tuner mTuner;
    private ComplexDftProcessor mDftProcessor;
    private ComplexDecibelConverter mConverter;
    private boolean mSourceEventListenerRegistered;
    private boolean mBufferListenerRegistered;
    private ScheduledFuture<?> mPendingControl;
    private ScheduledFuture<?> mMetadataRefreshTask;
    private ScheduledFuture<?> mTargetLivenessTask;

    public TunerSpectrumFrameSource(Configuration configuration, TunerManager tunerManager)
    {
        this(configuration, new TunerRegistry(tunerManager));
    }

    /**
     * Creates a demand-owned spectrum source over the shared neutral tuner registry.
     */
    public TunerSpectrumFrameSource(Configuration configuration, TunerRegistry tunerRegistry)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Tuner spectrum configuration cannot be null");
        mTunerRegistry = Objects.requireNonNull(tunerRegistry, "Tuner registry cannot be null");
        mControlExecutor = new ScheduledThreadPoolExecutor(1, runnable ->
        {
            Thread thread = new Thread(runnable, "sdrtrunk spectrum control");
            thread.setDaemon(true);
            return thread;
        });
        mControlExecutor.setRemoveOnCancelPolicy(true);
        mControlExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        mControlExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    TunerSpectrumFrameSource(Configuration configuration, Supplier<List<Tuner>> tunersSupplier)
    {
        this(configuration, TunerRegistry.fromTuners(tunersSupplier));
    }

    @Override
    public void start(Consumer<SpectrumFrame> frameConsumer)
    {
        Objects.requireNonNull(frameConsumer, "Spectrum frame consumer cannot be null");

        synchronized(mLifecycleLock)
        {
            if(mClosed)
            {
                throw new IllegalStateException("Tuner spectrum source is closed");
            }

            if(mRunning)
            {
                return;
            }

            startLocked(frameConsumer, mRequestedView.get());
        }
    }

    private void startLocked(Consumer<SpectrumFrame> frameConsumer, ViewRequest request)
    {
        String requestedTargetId = request != null && request.targetId() != null ? request.targetId() : mTargetId;
        Tuner tuner = selectTuner(requestedTargetId);

        if(tuner == null)
        {
            throw new IllegalStateException("No unambiguous already-running tuner is available for the signal view");
        }

        TunerController controller = tuner.getTunerController();
        TunerCandidate candidate = candidateFor(tuner);

        if(controller == null || candidate == null)
        {
            throw new IllegalStateException("Selected spectrum tuner is no longer available");
        }

        mTuner = tuner;

        try
        {
            long centerFrequencyHz = controller.getFrequency();
            long sampleRateHz = Math.round(controller.getSampleRate());
            PreparedView preparedView = prepareView(request, candidate.id(), candidate.label(),
                centerFrequencyHz, sampleRateHz);
            ComplexDftProcessor processor = new ComplexDftProcessor();
            mDftProcessor = processor;
            processor.setRepeatLastFrameWhenIdle(false);
            processor.setDFTSize(dftSize(preparedView.fftSize()));
            processor.setFrameRate(mConfiguration.framesPerSecond());
            ComplexDecibelConverter converter = new ComplexDecibelConverter();
            mConverter = converter;
            converter.addListener(this::publish);
            processor.addConverter(converter);

            mFrameConsumer = frameConsumer;
            mTargetId = candidate.id();
            mTargetLabel = candidate.label();
            long generation = mGeneration.incrementAndGet();
            mPreparedView = preparedView.withTargetGeneration(generation);
            mLastViewRevision = preparedView.revision();
            mAppliedView = null;
            mRunning = true;

            controller.addListener(this);
            mSourceEventListenerRegistered = true;
            // Mark cleanup as required before registration. Some tuner controllers register the listener and then
            // start their sample-transfer machinery; if that startup throws, the listener is already live and must
            // still be removed. Removal is safe when registration failed before adding the listener and also stops
            // any partially-started transfer loop when this was the first listener.
            mBufferListenerRegistered = true;
            controller.addBufferListener(processor);
            mMetadataRefreshTask = mControlExecutor.scheduleWithFixedDelay(this::refreshMetadataIfRequested,
                METADATA_REFRESH_INTERVAL_MILLISECONDS, METADATA_REFRESH_INTERVAL_MILLISECONDS,
                TimeUnit.MILLISECONDS);
            mTargetLivenessTask = mControlExecutor.scheduleWithFixedDelay(this::checkTargetLiveness,
                TARGET_LIVENESS_INTERVAL_SECONDS, TARGET_LIVENESS_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
        catch(RuntimeException exception)
        {
            try
            {
                stopLocked();
            }
            catch(RuntimeException cleanupException)
            {
                exception.addSuppressed(cleanupException);
            }

            throw exception;
        }
    }

    private Tuner selectTuner(String targetId)
    {
        List<TunerCandidate> candidates = candidates();

        if(candidates.isEmpty())
        {
            return null;
        }

        if(targetId != null)
        {
            return candidates.stream().filter(candidate -> targetId.equals(candidate.id()))
                .map(TunerCandidate::tuner).findFirst().orElse(null);
        }

        String requested = System.getProperty(PREFERRED_TUNER_PROPERTY, "").trim();

        if(!requested.isEmpty())
        {
            List<TunerCandidate> matches = candidates.stream().filter(candidate ->
                requested.equalsIgnoreCase(candidate.id()) || requested.equalsIgnoreCase(candidate.label())).toList();

            // Passive target IDs/labels replace hardware-backed preferred-name reads.  Ambiguity fails closed.
            return matches.size() == 1 ? matches.getFirst().tuner() : null;
        }

        String requestedClass = System.getProperty(TUNER_CLASS_PROPERTY, "").trim();

        if(!requestedClass.isEmpty())
        {
            List<TunerCandidate> matches = candidates.stream().filter(candidate ->
                    requestedClass.equalsIgnoreCase(candidate.id()) ||
                    requestedClass.equalsIgnoreCase(candidate.tunerClass().name()) ||
                    requestedClass.equalsIgnoreCase(candidate.tunerClass().toString()))
                .toList();

            // Class selectors use stable, non-identifying values and fail closed when duplicate hardware is present.
            return matches.size() == 1 ? matches.getFirst().tuner() : null;
        }

        // Prefer the least-loaded already-running device, keeping the first web FFT away from active decoder chains.
        return candidates.stream().min(Comparator.comparingInt(TunerCandidate::channelCount)
            .thenComparing(TunerCandidate::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TunerCandidate::id)).map(TunerCandidate::tuner).orElse(null);
    }

    private List<TunerCandidate> candidates()
    {
        List<TunerRegistry.AvailableTunerTarget> supplied = mTunerRegistry.availableTargets();

        if(supplied == null || supplied.isEmpty())
        {
            return List.of();
        }

        List<TunerCandidate> candidates = new ArrayList<>(supplied.size());

        for(TunerRegistry.AvailableTunerTarget target: supplied)
        {
            Tuner tuner = target.tuner();

            if(tuner != null)
            {
                TunerController controller = tuner.getTunerController();
                ChannelSourceManager channelSourceManager = tuner.getChannelSourceManager();

                if(controller != null && channelSourceManager != null)
                {
                    candidates.add(new TunerCandidate(tuner, channelSourceManager.getTunerChannelCount(), target.id(),
                        target.label(), target.tunerClass()));
                }
            }
        }

        return candidates;
    }

    private TunerCandidate candidateFor(Tuner tuner)
    {
        return candidates().stream().filter(candidate -> candidate.tuner() == tuner).findFirst().orElse(null);
    }

    @Override
    public List<Target> getTargets()
    {
        return candidates().stream().sorted(Comparator.comparing(TunerCandidate::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TunerCandidate::id))
            .map(candidate -> new Target(candidate.id(), candidate.label())).toList();
    }

    @Override
    public void requestView(ViewRequest request)
    {
        Objects.requireNonNull(request, "Spectrum view request cannot be null");

        if(request.targetId() != null && mTargetId != null && !request.targetId().equals(mTargetId) &&
            request.viewport() != null)
        {
            throw new IllegalArgumentException("Changing spectrum target requires a full-width view");
        }

        if(request.targetId() != null && getTargets().stream().noneMatch(target -> target.id().equals(request.targetId())))
        {
            throw new IllegalArgumentException("Spectrum target is unavailable or ambiguous");
        }

        mRequestedView.set(request);

        synchronized(mControlLock)
        {
            if(mClosed)
            {
                throw new IllegalStateException("Tuner spectrum source is closed");
            }

            if(mPendingControl != null)
            {
                mPendingControl.cancel(false);
            }

            mPendingControl = mControlExecutor.schedule(this::applyNewestViewSafely,
                CONTROL_DEBOUNCE_MILLISECONDS, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public AppliedView getAppliedView()
    {
        return mAppliedView;
    }

    private void applyNewestView()
    {
        synchronized(mControlLock)
        {
            mPendingControl = null;
        }

        ViewRequest requested = mRequestedView.get();

        if(requested == null)
        {
            return;
        }

        synchronized(mLifecycleLock)
        {
            if(mClosed || !mRunning || requested != mRequestedView.get())
            {
                return;
            }

            String requestedTargetId = requested.targetId() != null ? requested.targetId() : mTargetId;
            Tuner target = selectTuner(requestedTargetId);

            if(target == null)
            {
                return;
            }

            if(target != mTuner)
            {
                Consumer<SpectrumFrame> consumer = mFrameConsumer;
                stopLocked();

                if(consumer != null && !mClosed && requested == mRequestedView.get())
                {
                    startLocked(consumer, requested);
                }

                return;
            }

            TunerController controller = target.getTunerController();
            TunerCandidate candidate = candidateFor(target);

            if(controller == null || candidate == null)
            {
                return;
            }

            PreparedView prepared = prepareView(requested, candidate.id(), candidate.label(),
                controller.getFrequency(), Math.round(controller.getSampleRate())).withTargetGeneration(mGeneration.get());
            ComplexDftProcessor processor = mDftProcessor;

            if(processor != null)
            {
                processor.setDFTSize(dftSize(prepared.fftSize()));
                mPreparedView = prepared;
                mLastViewRevision = prepared.revision();
                mAppliedView = null;
            }
        }
    }

    private void applyNewestViewSafely()
    {
        try
        {
            applyNewestView();
        }
        catch(RuntimeException exception)
        {
            mPublicationErrorCount.incrementAndGet();

            synchronized(mLifecycleLock)
            {
                if(mRunning)
                {
                    try
                    {
                        stopLocked();
                    }
                    catch(RuntimeException cleanupException)
                    {
                        exception.addSuppressed(cleanupException);
                    }
                }
            }

            mLog.warn("Unable to apply the requested web spectrum receiver or view; source was released", exception);
        }
    }

    /**
     * A disabled, errored, or unplugged tuner disappears from the manager's available-tuner snapshot.  Check that
     * snapshot on the low-rate control worker so stale DFT frames cannot keep a removed receiver looking live.  This
     * path never runs on the tuner sample callback.
     */
    private void checkTargetLiveness()
    {
        try
        {
            synchronized(mLifecycleLock)
            {
                Tuner active = mTuner;

                if(mRunning && active != null &&
                    candidates().stream().noneMatch(candidate -> candidate.tuner() == active))
                {
                    stopLocked();
                }
            }
        }
        catch(RuntimeException exception)
        {
            mPublicationErrorCount.incrementAndGet();
        }
    }

    private PreparedView prepareView(ViewRequest request, String targetId, String targetLabel,
                                     long centerFrequencyHz, long sampleRateHz)
    {
        if(centerFrequencyHz < 0 || sampleRateHz <= 0)
        {
            throw new IllegalStateException("Selected spectrum tuner has invalid frequency metadata");
        }

        long revision = request != null ? request.revision() : 0;
        Viewport viewport = request != null ? request.viewport() : null;
        int fftSize = request == null ? mConfiguration.dftSize().getSize() : selectFftSize(sampleRateHz, viewport);
        int firstBin = 0;
        int binCount = Math.min(fftSize, MAXIMUM_TRANSMITTED_BINS);

        if(viewport != null)
        {
            double fullStart = centerFrequencyHz - sampleRateHz / 2.0;
            double fullEnd = fullStart + sampleRateHz;
            double requestedSpan = Math.min(sampleRateHz,
                (double)viewport.endFrequencyHz() - viewport.startFrequencyHz());
            double requestedCenter = ((double)viewport.startFrequencyHz() + viewport.endFrequencyHz()) / 2.0;
            double halfSpan = requestedSpan / 2.0;
            double boundedCenter = Math.max(fullStart + halfSpan, Math.min(fullEnd - halfSpan, requestedCenter));
            double binWidth = (double)sampleRateHz / fftSize;
            binCount = Math.max(1, Math.min(MAXIMUM_TRANSMITTED_BINS,
                (int)Math.round(requestedSpan / binWidth)));
            double centerBin = (boundedCenter - fullStart) / binWidth;
            firstBin = (int)Math.round(centerBin - binCount / 2.0);
            firstBin = Math.max(0, Math.min(fftSize - binCount, firstBin));
        }

        return new PreparedView(revision, targetId, targetLabel, 0, centerFrequencyHz, sampleRateHz, fftSize,
            firstBin, binCount);
    }

    private static int selectFftSize(long sampleRateHz, Viewport viewport)
    {
        if(viewport == null)
        {
            return BASE_FFT_SIZE;
        }

        double span = Math.min(sampleRateHz,
            (double)viewport.endFrequencyHz() - viewport.startFrequencyHz());
        double zoom = sampleRateHz / span;
        int fftSize = BASE_FFT_SIZE;

        while(fftSize < MAXIMUM_FFT_SIZE && zoom >= 2.0)
        {
            fftSize *= 2;
            zoom /= 2.0;
        }

        return fftSize;
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

        throw new IllegalArgumentException("Unsupported spectrum FFT size: " + size);
    }

    private void publish(float[] bins)
    {
        Consumer<SpectrumFrame> consumer = mFrameConsumer;
        PreparedView prepared = mPreparedView;

        if(!mRunning || consumer == null || prepared == null || bins == null ||
            bins.length != prepared.fftSize() || prepared != mPreparedView)
        {
            return;
        }

        try
        {
            float[] visibleBins = prepared.firstBin() == 0 && prepared.binCount() == bins.length ? bins :
                Arrays.copyOfRange(bins, prepared.firstBin(), prepared.firstBin() + prepared.binCount());
            long sequence = mSequence.getAndIncrement();
            SpectrumFrame frame = SpectrumFrame.float32Owned(SpectrumFrame.FLAG_CAPTURE_TIMESTAMP_VALID,
                prepared.targetGeneration(), sequence, System.nanoTime(),
                TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()), prepared.centerFrequencyHz(),
                prepared.sampleRateHz(), prepared.revision(), prepared.fftSize(), prepared.firstBin(), visibleBins);
            mAppliedView = prepared.applied();
            consumer.accept(frame);
            mPublishedFrameCount.incrementAndGet();
        }
        catch(RuntimeException exception)
        {
            // A downstream failure is counted and dropped; it must not terminate the scheduled DFT task.
            mPublicationErrorCount.incrementAndGet();
        }
    }

    @Override
    public void process(SourceEvent event)
    {
        if(event == null || !event.hasValue())
        {
            return;
        }

        switch(event.getEvent())
        {
            case NOTIFICATION_FREQUENCY_CHANGE ->
            {
                scheduleMetadataRefresh();
            }
            case NOTIFICATION_SAMPLE_RATE_CHANGE ->
            {
                scheduleMetadataRefresh();
            }
            default ->
            {
            }
        }
    }

    private void scheduleMetadataRefresh()
    {
        // Stop publication immediately and signal the already-running low-rate control worker.  The tuner/source-event
        // callback performs only volatile/atomic stores: no locks, scheduling, allocation, FFT, or transport work.
        mPreparedView = null;
        mAppliedView = null;
        mMetadataRefreshRequested.set(true);
    }

    private void refreshMetadataIfRequested()
    {
        if(!mMetadataRefreshRequested.getAndSet(false))
        {
            return;
        }

        ViewRequest current = mRequestedView.get();

        if(current == null)
        {
            current = new ViewRequest(mLastViewRevision, mTargetId, null);
            mRequestedView.compareAndSet(null, current);
        }

        applyNewestViewSafely();
    }

    @Override
    public void stop()
    {
        synchronized(mLifecycleLock)
        {
            stopLocked();
        }
    }

    private void stopLocked()
    {
        mRunning = false;
        mFrameConsumer = null;
        mPreparedView = null;
        mAppliedView = null;
        Tuner tuner = mTuner;
        ComplexDftProcessor processor = mDftProcessor;
        ComplexDecibelConverter converter = mConverter;
        mTuner = null;
        mDftProcessor = null;
        mConverter = null;
        boolean sourceEventListenerRegistered = mSourceEventListenerRegistered;
        boolean bufferListenerRegistered = mBufferListenerRegistered;
        ScheduledFuture<?> metadataRefreshTask = mMetadataRefreshTask;
        ScheduledFuture<?> targetLivenessTask = mTargetLivenessTask;
        mSourceEventListenerRegistered = false;
        mBufferListenerRegistered = false;
        mMetadataRefreshTask = null;
        mTargetLivenessTask = null;
        RuntimeException failure = null;

        if(metadataRefreshTask != null)
        {
            metadataRefreshTask.cancel(false);
        }

        if(targetLivenessTask != null)
        {
            targetLivenessTask.cancel(false);
        }

        if(tuner != null)
        {
            TunerController controller = tuner.getTunerController();

            if(controller != null && sourceEventListenerRegistered)
            {
                try
                {
                    controller.removeListener(this);
                }
                catch(RuntimeException exception)
                {
                    failure = exception;
                }
            }

            if(controller != null && processor != null && bufferListenerRegistered)
            {
                try
                {
                    controller.removeBufferListener(processor);
                }
                catch(RuntimeException exception)
                {
                    failure = appendFailure(failure, exception);
                }
            }
        }

        if(processor != null)
        {
            try
            {
                processor.dispose();
            }
            catch(RuntimeException exception)
            {
                failure = appendFailure(failure, exception);
            }
        }

        if(converter != null)
        {
            try
            {
                converter.dispose();
            }
            catch(RuntimeException exception)
            {
                failure = appendFailure(failure, exception);
            }
        }

        if(failure != null)
        {
            throw failure;
        }
    }

    private static RuntimeException appendFailure(RuntimeException existing, RuntimeException addition)
    {
        if(existing == null)
        {
            return addition;
        }

        existing.addSuppressed(addition);
        return existing;
    }

    @Override
    public boolean isRunning()
    {
        return mRunning;
    }

    public String getTargetLabel()
    {
        return mTargetLabel;
    }

    public long getPublishedFrameCount()
    {
        return mPublishedFrameCount.get();
    }

    public long getPublicationErrorCount()
    {
        return mPublicationErrorCount.get();
    }

    boolean isControlExecutorTerminated()
    {
        return mControlExecutor.isTerminated();
    }

    @Override
    public void close()
    {
        RuntimeException failure = null;

        synchronized(mLifecycleLock)
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;

            try
            {
                stopLocked();
            }
            catch(RuntimeException exception)
            {
                failure = exception;
            }
        }

        synchronized(mControlLock)
        {
            if(mPendingControl != null)
            {
                mPendingControl.cancel(false);
                mPendingControl = null;
            }
        }

        mControlExecutor.shutdownNow();

        try
        {
            if(!mControlExecutor.awaitTermination(CONTROL_SHUTDOWN_SECONDS, TimeUnit.SECONDS))
            {
                failure = appendFailure(failure,
                    new IllegalStateException("Spectrum control executor did not terminate"));
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            failure = appendFailure(failure,
                new IllegalStateException("Interrupted while stopping spectrum control executor", exception));
        }

        if(failure != null)
        {
            throw failure;
        }
    }

    public record Configuration(DFTSize dftSize, int framesPerSecond)
    {
        public Configuration
        {
            Objects.requireNonNull(dftSize, "DFT size cannot be null");

            if(framesPerSecond < 1 || framesPerSecond > 60)
            {
                throw new IllegalArgumentException("Tuner spectrum frame rate must be between 1 and 60");
            }
        }

        public static Configuration defaults()
        {
            return new Configuration(DFTSize.FFT04096, 20);
        }
    }

    private record TunerCandidate(Tuner tuner, int channelCount, String id, String label, TunerClass tunerClass)
    {
    }

    private record PreparedView(long revision, String targetId, String targetLabel, long targetGeneration,
                                long centerFrequencyHz, long sampleRateHz, int fftSize, int firstBin, int binCount)
    {
        private PreparedView withTargetGeneration(long generation)
        {
            return new PreparedView(revision, targetId, targetLabel, generation, centerFrequencyHz, sampleRateHz,
                fftSize, firstBin, binCount);
        }

        private AppliedView applied()
        {
            return new AppliedView(revision, targetId, targetLabel, targetGeneration, centerFrequencyHz, sampleRateHz,
                fftSize, firstBin, binCount);
        }
    }
}
