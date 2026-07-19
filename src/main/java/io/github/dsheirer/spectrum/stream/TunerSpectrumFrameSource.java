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
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.spectrum.ComplexDftProcessor;
import io.github.dsheirer.spectrum.DFTSize;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Read-only, demand-driven FFT source for one already-running tuner.
 *
 * <p>The tuner callback performs only the existing bounded native-buffer enqueue. FFT conversion and frame
 * publication run on the dedicated DFT executor; no WebSocket, JSON, database, disk, or network operation can execute
 * on the USB/sample callback. This source never initializes, retunes, stops, or otherwise owns the selected tuner.
 * Attaching to an initialized USB tuner that has no other buffer listener can activate that controller's sample
 * transfer loop until this source detaches.</p>
 */
public final class TunerSpectrumFrameSource implements SpectrumFrameSource, ISourceEventProcessor
{
    public static final String PREFERRED_TUNER_PROPERTY = "sdrtrunk.web.signal.tuner";
    public static final String TUNER_CLASS_PROPERTY = "sdrtrunk.web.signal.tuner.class";

    private final Configuration mConfiguration;
    private final Supplier<List<Tuner>> mTunersSupplier;
    private final Object mLifecycleLock = new Object();
    private final AtomicLong mSequence = new AtomicLong();
    private final AtomicLong mGeneration = new AtomicLong();
    private final AtomicLong mPublishedFrameCount = new AtomicLong();
    private final AtomicLong mPublicationErrorCount = new AtomicLong();
    private volatile boolean mRunning;
    private volatile boolean mClosed;
    private volatile long mCenterFrequencyHz;
    private volatile long mSampleRateHz;
    private volatile Consumer<SpectrumFrame> mFrameConsumer;
    private volatile String mTargetLabel = "unavailable";
    private Tuner mTuner;
    private ComplexDftProcessor mDftProcessor;
    private ComplexDecibelConverter mConverter;
    private boolean mSourceEventListenerRegistered;
    private boolean mBufferListenerRegistered;

    public TunerSpectrumFrameSource(Configuration configuration, TunerManager tunerManager)
    {
        this(configuration, tunerSupplier(tunerManager));
    }

    private static Supplier<List<Tuner>> tunerSupplier(TunerManager tunerManager)
    {
        TunerManager manager = Objects.requireNonNull(tunerManager, "Tuner manager cannot be null");
        return () -> manager.getAvailableTuners().stream().filter(Objects::nonNull)
            .filter(DiscoveredTuner::isAvailable).filter(DiscoveredTuner::hasTuner)
            .map(DiscoveredTuner::getTuner).filter(Objects::nonNull).toList();
    }

    TunerSpectrumFrameSource(Configuration configuration, Supplier<List<Tuner>> tunersSupplier)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Tuner spectrum configuration cannot be null");
        mTunersSupplier = Objects.requireNonNull(tunersSupplier, "Tuner supplier cannot be null");
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

            Tuner tuner = selectTuner();

            if(tuner == null)
            {
                throw new IllegalStateException("No already-running tuner is available for the signal view");
            }

            TunerController controller = tuner.getTunerController();
            mTuner = tuner;

            try
            {
                ComplexDftProcessor processor = new ComplexDftProcessor();
                mDftProcessor = processor;
                processor.setDFTSize(mConfiguration.dftSize());
                processor.setFrameRate(mConfiguration.framesPerSecond());
                ComplexDecibelConverter converter = new ComplexDecibelConverter();
                mConverter = converter;
                converter.addListener(this::publish);
                processor.addConverter(converter);

                mFrameConsumer = frameConsumer;
                mCenterFrequencyHz = controller.getFrequency();
                mSampleRateHz = Math.round(controller.getSampleRate());
                mTargetLabel = tuner.getTunerClass().toString();
                mGeneration.incrementAndGet();
                mRunning = true;

                controller.addListener(this);
                mSourceEventListenerRegistered = true;
                controller.addBufferListener(processor);
                mBufferListenerRegistered = true;
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
    }

    private Tuner selectTuner()
    {
        List<Tuner> supplied = mTunersSupplier.get();

        if(supplied == null || supplied.isEmpty())
        {
            return null;
        }

        List<TunerCandidate> candidates = new ArrayList<>(supplied.size());

        for(Tuner tuner: supplied)
        {
            if(tuner != null)
            {
                TunerController controller = tuner.getTunerController();
                ChannelSourceManager channelSourceManager = tuner.getChannelSourceManager();
                TunerClass tunerClass = tuner.getTunerClass();

                if(controller != null && channelSourceManager != null && tunerClass != null)
                {
                    candidates.add(new TunerCandidate(tuner, channelSourceManager.getTunerChannelCount(),
                        tunerClass.name(), tunerClass.toString()));
                }
            }
        }

        if(candidates.isEmpty())
        {
            return null;
        }

        String requested = System.getProperty(PREFERRED_TUNER_PROPERTY, "").trim();

        if(!requested.isEmpty())
        {
            for(TunerCandidate candidate: candidates)
            {
                if(requested.equalsIgnoreCase(candidate.tuner().getPreferredName()))
                {
                    return candidate.tuner();
                }
            }

            // An explicit selection is fail-closed so that a stale identity never taps a different receiver.
            return null;
        }

        String requestedClass = System.getProperty(TUNER_CLASS_PROPERTY, "").trim();

        if(!requestedClass.isEmpty())
        {
            for(TunerCandidate candidate: candidates)
            {
                if(requestedClass.equalsIgnoreCase(candidate.className()) ||
                    requestedClass.equalsIgnoreCase(candidate.classLabel()))
                {
                    return candidate.tuner();
                }
            }

            // Class selectors use stable, non-identifying values such as AIRSPY or RTL2832 and also fail closed.
            return null;
        }

        // Prefer the least-loaded already-running device, keeping the first web FFT away from active decoder chains.
        return candidates.stream().min(Comparator.comparingInt(TunerCandidate::channelCount)
            .thenComparing(TunerCandidate::classLabel)).map(TunerCandidate::tuner).orElse(null);
    }

    private void publish(float[] bins)
    {
        Consumer<SpectrumFrame> consumer = mFrameConsumer;

        if(!mRunning || consumer == null || bins == null || bins.length == 0)
        {
            return;
        }

        try
        {
            long sequence = mSequence.getAndIncrement();
            SpectrumFrame frame = SpectrumFrame.float32Owned(SpectrumFrame.FLAG_CAPTURE_TIMESTAMP_VALID,
                mGeneration.get(), sequence, System.nanoTime(),
                TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()), mCenterFrequencyHz, mSampleRateHz, bins);
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
            case NOTIFICATION_FREQUENCY_CHANGE -> mCenterFrequencyHz = event.getValue().longValue();
            case NOTIFICATION_SAMPLE_RATE_CHANGE -> mSampleRateHz = Math.round(event.getValue().doubleValue());
            default ->
            {
            }
        }
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
        Tuner tuner = mTuner;
        ComplexDftProcessor processor = mDftProcessor;
        ComplexDecibelConverter converter = mConverter;
        mTuner = null;
        mDftProcessor = null;
        mConverter = null;
        boolean sourceEventListenerRegistered = mSourceEventListenerRegistered;
        boolean bufferListenerRegistered = mBufferListenerRegistered;
        mSourceEventListenerRegistered = false;
        mBufferListenerRegistered = false;
        RuntimeException failure = null;

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
            stopLocked();
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

    private record TunerCandidate(Tuner tuner, int channelCount, String className, String classLabel)
    {
    }
}
