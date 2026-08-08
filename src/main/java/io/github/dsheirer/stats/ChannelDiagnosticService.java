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

import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.PrimaryDecoder;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.sample.complex.ComplexSamplesToNativeBufferModule;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.spectrum.ComplexDftProcessor;
import io.github.dsheirer.spectrum.DFTSize;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demand-owned, memory-only diagnostics for one selected live channel. Only one browser diagnostic lease can be
 * active, and each source retains one replaceable frame so a slow client cannot block receiver processing.
 */
public final class ChannelDiagnosticService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelDiagnosticService.class);
    public static final int FFT_SIZE = 1_024;
    public static final int SIGNAL_FRAMES_PER_SECOND = 5;
    public static final int SYMBOL_BATCH_SIZE = 240;
    public static final int MAXIMUM_VISIBLE_SYMBOLS = 4_800;
    private static final float MAXIMUM_SYMBOL_PHASE = (float)Math.PI;
    private static final String SIGNAL = "signal";
    private static final String SYMBOLS = "symbols";

    private final ChannelProcessingManager mChannelProcessingManager;
    private final AtomicReference<Session> mActiveSession = new AtomicReference<>();
    private final AtomicBoolean mClosed = new AtomicBoolean();

    public ChannelDiagnosticService(ChannelProcessingManager channelProcessingManager)
    {
        mChannelProcessingManager = Objects.requireNonNull(channelProcessingManager,
            "Channel processing manager cannot be null");
    }

    public synchronized OpenResult tryOpen(Scope scope, UUID clientId)
    {
        Objects.requireNonNull(scope, "Diagnostic scope cannot be null");
        Objects.requireNonNull(clientId, "Diagnostic client ID cannot be null");

        if(mClosed.get())
        {
            return new OpenResult(OpenStatus.CLOSED, null);
        }

        Session active = mActiveSession.get();

        if(active != null)
        {
            if(!active.ownedBy(clientId))
            {
                return new OpenResult(OpenStatus.BUSY, null);
            }

            active.close();
        }

        Session candidate = new Session(scope, clientId);

        if(!mActiveSession.compareAndSet(null, candidate))
        {
            return new OpenResult(OpenStatus.BUSY, null);
        }

        try
        {
            candidate.refresh();
            return new OpenResult(OpenStatus.OPEN, candidate);
        }
        catch(RuntimeException exception)
        {
            candidate.close();
            throw exception;
        }
    }

    public synchronized void closeActiveSession()
    {
        Session session = mActiveSession.get();

        if(session != null)
        {
            session.close();
        }
    }

    @Override
    public synchronized void close()
    {
        if(mClosed.compareAndSet(false, true))
        {
            closeActiveSession();
        }
    }

    public enum OpenStatus
    {
        OPEN,
        BUSY,
        CLOSED
    }

    public record OpenResult(OpenStatus status, Session session)
    {
    }

    public record Scope(String configurationId, long frequencyHz, Integer timeslot)
    {
        public Scope
        {
            if(configurationId == null || configurationId.isBlank() || frequencyHz <= 0 ||
                (timeslot != null && timeslot <= 0))
            {
                throw new IllegalArgumentException("Diagnostic scope is invalid");
            }
        }
    }

    public record State(long revision, long generation, String state, String reason,
                        String signalState, String signalReason, String symbolsState, String symbolsReason,
                        long frequencyHz, long sampleRateHz, Integer timeslot, String protocol,
                        int fftSize, int signalFramesPerSecond, int symbolBatchSize, int maximumVisibleSymbols)
    {
    }

    public record Frame(String type, long generation, long sequence, long observedAtMs, float[] values)
    {
    }

    public final class Session implements AutoCloseable
    {
        private final Scope mScope;
        private final UUID mClientId;
        private final AtomicBoolean mSessionClosed = new AtomicBoolean();
        private final LatestFrameQueue mFrames = new LatestFrameQueue();
        private ProcessingChain mProcessingChain;
        private SignalSource mSignalSource;
        private SymbolSource mSymbolSource;
        private long mGeneration;
        private long mStateRevision;
        private volatile State mState;

        private Session(Scope scope, UUID clientId)
        {
            mScope = scope;
            mClientId = clientId;
            mState = createState("waiting", "Waiting for the selected channel to become active.",
                "waiting", "Waiting for signal data.", "waiting", "Waiting for symbol data.",
                scope.frequencyHz(), 0, "");
        }

        /**
         * Rebinds when the selected processing chain starts, stops, or is replaced. This is invoked only by the SSE
         * request thread and never by a decoder callback.
         */
        public synchronized State refresh()
        {
            if(mSessionClosed.get())
            {
                return mState;
            }

            List<ProcessingChain> matches = mChannelProcessingManager.getProcessingChainsByConfiguration(
                mScope.configurationId(), mScope.frequencyHz());
            ProcessingChain next = matches.stream().filter(ProcessingChain::isProcessing).findFirst().orElse(null);

            if(next == mProcessingChain)
            {
                return mState;
            }

            detach();
            mProcessingChain = next;
            mGeneration++;

            if(next == null)
            {
                mState = createState("waiting", "Waiting for the selected channel to become active.",
                    "waiting", "Waiting for signal data.", "waiting", "Waiting for symbol data.",
                    mScope.frequencyHz(), 0, "");
                return mState;
            }

            Source source = next.getSource();
            PrimaryDecoder decoder = next.getModules().stream().filter(PrimaryDecoder.class::isInstance)
                .map(PrimaryDecoder.class::cast).findFirst().orElse(null);
            long actualFrequency = source != null && source.getFrequency() > 0 ? source.getFrequency() :
                mScope.frequencyHz();
            long sampleRate = source != null && Double.isFinite(source.getSampleRate()) && source.getSampleRate() > 0 ?
                Math.round(source.getSampleRate()) : 0;
            String protocol = decoder != null && decoder.getDecoderType() != null ?
                decoder.getDecoderType().getDisplayString() : "";

            String signalState;
            String signalReason;

            if(source == null || source.getSampleType() != SampleType.COMPLEX || sampleRate <= 0)
            {
                signalState = "unsupported";
                signalReason = "Signal is not available for this channel.";
            }
            else
            {
                try
                {
                    mSignalSource = new SignalSource(next, mGeneration, mFrames);
                    signalState = "live";
                    signalReason = "";
                }
                catch(RuntimeException exception)
                {
                    mFrames.clear();
                    mLog.warn("Unable to attach selected-channel signal diagnostics", exception);
                    signalState = "unavailable";
                    signalReason = "Signal diagnostics could not be started.";
                }
            }

            String symbolsState;
            String symbolsReason;

            if(decoder instanceof FeedbackDecoder feedbackDecoder)
            {
                try
                {
                    mSymbolSource = new SymbolSource(feedbackDecoder, mGeneration, mFrames);
                    symbolsState = "live";
                    symbolsReason = "";
                    protocol = feedbackDecoder.getProtocolDescription();
                }
                catch(RuntimeException exception)
                {
                    mLog.warn("Unable to attach selected-channel symbol diagnostics", exception);
                    symbolsState = "unavailable";
                    symbolsReason = "Symbol diagnostics could not be started.";
                }
            }
            else
            {
                symbolsState = "unsupported";
                symbolsReason = "Symbols are not available for this decoder.";
            }

            boolean live = "live".equals(signalState) || "live".equals(symbolsState);
            boolean unavailable = "unavailable".equals(signalState) || "unavailable".equals(symbolsState);
            mState = createState(live ? "live" : (unavailable ? "unavailable" : "unsupported"),
                live ? "" : "Channel diagnostics are not available for this channel.",
                signalState, signalReason, symbolsState, symbolsReason, actualFrequency, sampleRate, protocol);

            return mState;
        }

        public State state()
        {
            return mState;
        }

        public Frame poll(Duration timeout) throws InterruptedException
        {
            return mFrames.poll(timeout);
        }

        public boolean isClosed()
        {
            return mSessionClosed.get();
        }

        private boolean ownedBy(UUID clientId)
        {
            return mClientId.equals(clientId);
        }

        private State createState(String state, String reason, String signalState, String signalReason,
                                  String symbolsState, String symbolsReason, long frequency, long sampleRate,
                                  String protocol)
        {
            return new State(++mStateRevision, mGeneration, state, reason, signalState, signalReason, symbolsState,
                symbolsReason, frequency, sampleRate, mScope.timeslot(), protocol, FFT_SIZE,
                SIGNAL_FRAMES_PER_SECOND, SYMBOL_BATCH_SIZE, MAXIMUM_VISIBLE_SYMBOLS);
        }

        private void detach()
        {
            SignalSource signalSource = mSignalSource;
            SymbolSource symbolSource = mSymbolSource;
            mSignalSource = null;
            mSymbolSource = null;

            if(signalSource != null)
            {
                signalSource.close();
            }

            if(symbolSource != null)
            {
                symbolSource.close();
            }

            mFrames.clear();
        }

        @Override
        public synchronized void close()
        {
            if(mSessionClosed.compareAndSet(false, true))
            {
                detach();
                mFrames.close();
                mProcessingChain = null;
                mActiveSession.compareAndSet(this, null);
            }
        }
    }

    private abstract static class LatestFrameSource implements AutoCloseable
    {
        private final String mType;
        private final long mGeneration;
        private final LatestFrameQueue mFrames;
        private final AtomicLong mSequence = new AtomicLong();
        protected final AtomicBoolean mSourceClosed = new AtomicBoolean();

        private LatestFrameSource(String type, long generation, LatestFrameQueue frames)
        {
            mType = type;
            mGeneration = generation;
            mFrames = frames;
        }

        protected void publish(float[] values)
        {
            if(mSourceClosed.get() || values == null || values.length == 0)
            {
                return;
            }

            mFrames.publish(new Frame(mType, mGeneration, mSequence.incrementAndGet(), System.currentTimeMillis(),
                values), mSourceClosed);
        }

        @Override
        public abstract void close();
    }

    /**
     * Retains at most one signal frame and one symbol frame for the SSE writer.
     */
    static final class LatestFrameQueue implements AutoCloseable
    {
        private final AtomicReference<Frame> mSignalFrame = new AtomicReference<>();
        private final AtomicReference<Frame> mSymbolFrame = new AtomicReference<>();
        private final Semaphore mAvailable = new Semaphore(0);
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private boolean mSignalFirst = true;

        void publish(Frame frame, AtomicBoolean sourceClosed)
        {
            if(mClosed.get() || sourceClosed.get())
            {
                return;
            }

            AtomicReference<Frame> destination = SIGNAL.equals(frame.type()) ? mSignalFrame : mSymbolFrame;
            Frame replaced = destination.getAndSet(frame);

            if(mClosed.get() || sourceClosed.get())
            {
                destination.compareAndSet(frame, null);
            }
            else if(replaced == null)
            {
                mAvailable.release();
            }
        }

        Frame poll(Duration timeout) throws InterruptedException
        {
            boolean acquired = timeout.isZero() ? mAvailable.tryAcquire() :
                mAvailable.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);

            if(!acquired)
            {
                return null;
            }

            Frame frame = mSignalFirst ? mSignalFrame.getAndSet(null) : mSymbolFrame.getAndSet(null);

            if(frame == null)
            {
                frame = mSignalFirst ? mSymbolFrame.getAndSet(null) : mSignalFrame.getAndSet(null);
            }

            if(frame != null)
            {
                mSignalFirst = SYMBOLS.equals(frame.type());
            }

            return frame;
        }

        void clear()
        {
            mSignalFrame.set(null);
            mSymbolFrame.set(null);
            mAvailable.drainPermits();
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                clear();
                mAvailable.release();
            }
        }
    }

    private static final class SignalSource extends LatestFrameSource
    {
        private final ProcessingChain mProcessingChain;
        private final ComplexDftProcessor mProcessor = new ComplexDftProcessor();
        private final ComplexSamplesToNativeBufferModule mTap = new ComplexSamplesToNativeBufferModule();

        private SignalSource(ProcessingChain processingChain, long generation, LatestFrameQueue frames)
        {
            super(SIGNAL, generation, frames);
            mProcessingChain = processingChain;
            boolean moduleAdded = false;

            try
            {
                mProcessor.setRepeatLastFrameWhenIdle(false);
                mProcessor.setDFTSize(DFTSize.FFT01024);
                mProcessor.setFrameRate(SIGNAL_FRAMES_PER_SECOND);
                ComplexDecibelConverter converter = new ComplexDecibelConverter();
                converter.addListener(this::publishSignal);
                mProcessor.addConverter(converter);
                mTap.setListener(mProcessor);
                mProcessingChain.addModule(mTap);
                moduleAdded = true;
            }
            catch(RuntimeException exception)
            {
                mSourceClosed.set(true);
                mTap.removeListener();

                if(moduleAdded || mProcessingChain.getModules().contains(mTap))
                {
                    try
                    {
                        mProcessingChain.removeModule(mTap);
                    }
                    catch(RuntimeException cleanupException)
                    {
                        exception.addSuppressed(cleanupException);
                    }
                }

                mProcessor.dispose();
                throw exception;
            }
        }

        private void publishSignal(float[] bins)
        {
            if(mSourceClosed.get() || bins == null || bins.length != FFT_SIZE)
            {
                return;
            }

            float[] safe = new float[bins.length];

            for(int x = 0; x < bins.length; x++)
            {
                float value = Float.isFinite(bins[x]) ? Math.max(-196.0f, Math.min(20.0f, bins[x])) : -196.0f;
                safe[x] = Math.round(value * 10.0f) / 10.0f;
            }

            publish(safe);
        }

        @Override
        public void close()
        {
            if(!mSourceClosed.compareAndSet(false, true))
            {
                return;
            }

            mTap.removeListener();

            try
            {
                if(mProcessingChain.getModules().contains(mTap))
                {
                    mProcessingChain.removeModule(mTap);
                }
            }
            catch(RuntimeException exception)
            {
                mLog.debug("Selected-channel signal tap was already detached", exception);
            }
            finally
            {
                mProcessor.dispose();
            }
        }
    }

    private static final class SymbolSource extends LatestFrameSource implements FeedbackDecoder.SymbolObserver
    {
        private final FeedbackDecoder mDecoder;
        private float[] mBatch = new float[SYMBOL_BATCH_SIZE];
        private int mPointer;

        private SymbolSource(FeedbackDecoder decoder, long generation, LatestFrameQueue frames)
        {
            super(SYMBOLS, generation, frames);
            mDecoder = decoder;
            mDecoder.addSymbolObserver(this);
        }

        @Override
        public void receive(float symbol)
        {
            if(mSourceClosed.get() || !Float.isFinite(symbol) || symbol < -MAXIMUM_SYMBOL_PHASE ||
                symbol > MAXIMUM_SYMBOL_PHASE)
            {
                return;
            }

            float[] batch = mBatch;
            batch[mPointer++] = symbol;

            if(mPointer == batch.length)
            {
                mBatch = new float[SYMBOL_BATCH_SIZE];
                mPointer = 0;
                publish(batch);
            }
        }

        @Override
        public void close()
        {
            if(mSourceClosed.compareAndSet(false, true))
            {
                mDecoder.removeSymbolObserver(this);
            }
        }
    }
}
