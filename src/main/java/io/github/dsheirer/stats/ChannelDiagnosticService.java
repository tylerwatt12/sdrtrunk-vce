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
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.PrimaryDecoder;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.spectrum.DFTSize;
import io.github.dsheirer.util.concurrent.BoundedSpscFloatBatchQueue;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demand-owned selected-channel diagnostics.  Viewers of the same processing chain share one signal FFT and one
 * decoder symbol observer.  Web and HTTP threads only create leases; source resolution and processing-chain probe
 * attachment are performed asynchronously on the shared low-priority diagnostic worker.  With no viewers this
 * service owns no receiver listener, scheduled task, or worker.
 */
public final class ChannelDiagnosticService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelDiagnosticService.class);
    public static final int MAXIMUM_SESSIONS = 32;
    public static final int MAXIMUM_PRODUCERS = 4;
    public static final int MAXIMUM_FFT_SIZE = 1_024;
    public static final int SIGNAL_FRAMES_PER_SECOND = 20;
    public static final int SYMBOL_BATCH_SIZE = 240;
    public static final int MAXIMUM_VISIBLE_SYMBOLS = 4_800;
    private static final long BINDING_REFRESH_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final float MAXIMUM_SYMBOL_PHASE = (float)Math.PI;

    private final ChannelProcessingManager mChannelProcessingManager;
    private final DiagnosticFftScheduler mFftScheduler;
    private final boolean mOwnsFftScheduler;
    private final ChannelDiagnosticBindingScheduler mBindingScheduler = new ChannelDiagnosticBindingScheduler();
    private final Map<Scope,ScopeBinding> mBindings = new LinkedHashMap<>();
    private final Map<ProcessingChain,Producer> mProducers = new IdentityHashMap<>();
    private final Set<Session> mSessions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicLong mGeneration = new AtomicLong();
    private ChannelDiagnosticBindingScheduler.Task mBindingTask;
    private boolean mClosed;

    public ChannelDiagnosticService(ChannelProcessingManager channelProcessingManager)
    {
        this(channelProcessingManager, new DiagnosticFftScheduler(), true);
    }

    ChannelDiagnosticService(ChannelProcessingManager channelProcessingManager, DiagnosticFftScheduler fftScheduler)
    {
        this(channelProcessingManager, fftScheduler, false);
    }

    private ChannelDiagnosticService(ChannelProcessingManager channelProcessingManager,
                                     DiagnosticFftScheduler fftScheduler, boolean ownsFftScheduler)
    {
        mChannelProcessingManager = Objects.requireNonNull(channelProcessingManager,
            "Channel processing manager cannot be null");
        mFftScheduler = Objects.requireNonNull(fftScheduler, "Diagnostic FFT scheduler cannot be null");
        mOwnsFftScheduler = ownsFftScheduler;
    }

    public OpenResult tryOpen(Scope scope)
    {
        Objects.requireNonNull(scope, "Diagnostic scope cannot be null");
        ScopeBinding binding;
        Session session;

        synchronized(this)
        {
            if(mClosed)
            {
                return new OpenResult(OpenStatus.CLOSED, null);
            }

            if(mSessions.size() >= MAXIMUM_SESSIONS)
            {
                return new OpenResult(OpenStatus.BUSY, null);
            }

            binding = mBindings.computeIfAbsent(scope, ScopeBinding::new);
            session = new Session(binding);
            mSessions.add(session);
            binding.add(session);
            ensureBindingTask();
        }

        return new OpenResult(OpenStatus.OPEN, session);
    }

    /**
     * Stops every currently active diagnostic session.  Retained for the web-listener lifecycle call site.
     */
    public void closeActiveSession()
    {
        closeSessions();
    }

    private void closeSessions()
    {
        List<Session> sessions;

        synchronized(this)
        {
            sessions = List.copyOf(mSessions);
        }

        sessions.forEach(Session::close);
    }

    synchronized int activeSessionCount()
    {
        return mSessions.size();
    }

    synchronized int activeProducerCount()
    {
        return mProducers.size();
    }

    boolean hasBindingWorker()
    {
        return mBindingScheduler.hasWorker();
    }

    private synchronized Producer acquireProducer(ProcessingChain processingChain, BindingInfo info,
                                                  List<Session> sessions)
    {
        Producer producer = mProducers.get(processingChain);

        if(producer != null && !producer.isCompatible(info))
        {
            mProducers.remove(processingChain, producer);
            producer.close();
            producer = null;
        }

        if(producer == null)
        {
            if(mProducers.size() >= MAXIMUM_PRODUCERS)
            {
                return null;
            }

            producer = new Producer(processingChain, info, mGeneration.incrementAndGet());
            mProducers.put(processingChain, producer);
        }
        else
        {
            //The chain can retune without being replaced.  Keep frame metadata current without rebuilding an
            //otherwise compatible FFT and symbol tap.
            producer.updateInfo(info);
        }

        sessions.forEach(producer::add);
        return producer;
    }

    private synchronized void releaseProducer(Producer producer, List<Session> sessions)
    {
        if(producer == null)
        {
            return;
        }

        sessions.forEach(producer::remove);

        if(producer.isEmpty() && mProducers.remove(producer.processingChain(), producer))
        {
            producer.close();
        }
    }

    private synchronized void closeSession(Session session, ScopeBinding binding)
    {
        if(!mSessions.remove(session))
        {
            return;
        }

        binding.remove(session);

        if(binding.isEmpty())
        {
            mBindings.remove(binding.scope(), binding);
            binding.close();
        }

        if(mSessions.isEmpty() && mBindingTask != null)
        {
            mBindingTask.close();
            mBindingTask = null;
        }
    }

    private synchronized void ensureBindingTask()
    {
        if(mBindingTask == null && !mClosed)
        {
            mBindingTask = mBindingScheduler.scheduleWithFixedDelay(this::refreshBindingsSafely, 4);
        }
    }

    private void refreshBindingsSafely()
    {
        List<ScopeBinding> bindings;

        synchronized(this)
        {
            if(mClosed)
            {
                return;
            }

            bindings = List.copyOf(mBindings.values());
        }

        for(ScopeBinding binding: bindings)
        {
            try
            {
                binding.refresh(false);
            }
            catch(RuntimeException exception)
            {
                mLog.warn("Unable to refresh selected-channel diagnostic binding", exception);
            }
        }
    }

    @Override
    public void close()
    {
        synchronized(this)
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;
        }

        closeSessions();

        synchronized(this)
        {
            for(Producer producer: List.copyOf(mProducers.values()))
            {
                producer.close();
            }

            mProducers.clear();
            mBindings.clear();

            if(mBindingTask != null)
            {
                mBindingTask.close();
                mBindingTask = null;
            }
        }

        if(mOwnsFftScheduler)
        {
            mFftScheduler.close();
        }

        mBindingScheduler.close();
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
                        String decoderProfile,
                        int fftSize, int signalFramesPerSecond, int symbolBatchSize, int maximumVisibleSymbols)
    {
    }

    public final class Session implements AutoCloseable
    {
        private final ScopeBinding mBinding;
        private final DiagnosticFrameQueue mFrames = new DiagnosticFrameQueue();
        private final AtomicBoolean mSessionClosed = new AtomicBoolean();

        private Session(ScopeBinding binding)
        {
            mBinding = binding;
        }

        public State refresh()
        {
            if(!mSessionClosed.get())
            {
                mBinding.requestRefresh();
            }

            return mBinding.state();
        }

        public State state()
        {
            return mBinding.state();
        }

        public DiagnosticStreamFrame poll(Duration timeout) throws InterruptedException
        {
            return mFrames.poll(timeout);
        }

        public boolean isClosed()
        {
            return mSessionClosed.get();
        }

        private void offer(DiagnosticStreamFrame frame)
        {
            if(!mSessionClosed.get())
            {
                mFrames.offer(frame);
            }
        }

        @Override
        public void close()
        {
            if(mSessionClosed.compareAndSet(false, true))
            {
                mFrames.close();
                closeSession(this, mBinding);
            }
        }
    }

    private final class ScopeBinding implements AutoCloseable
    {
        private final Scope mScope;
        private final CopyOnWriteArrayList<Session> mSessions = new CopyOnWriteArrayList<>();
        private ProcessingChain mProcessingChain;
        private BindingInfo mBindingInfo;
        private Producer mProducer;
        private boolean mRetryProducer;
        private long mNextRefreshNanos;
        private long mStateRevision;
        private volatile boolean mRefreshRequested = true;
        private volatile State mState;

        private ScopeBinding(Scope scope)
        {
            mScope = scope;
            mState = state(0, "waiting", "Waiting for the selected channel to become active.",
                "waiting", "Waiting for signal data.", "waiting", "Waiting for symbol data.",
                scope.frequencyHz(), 0, "", "", 0);
        }

        private Scope scope()
        {
            return mScope;
        }

        private State state()
        {
            return mState;
        }

        private void add(Session session)
        {
            mSessions.add(session);

            if(mProducer != null)
            {
                mProducer.add(session);
            }
        }

        private void remove(Session session)
        {
            mSessions.remove(session);

            if(mProducer != null)
            {
                releaseProducer(mProducer, List.of(session));
            }
        }

        private boolean isEmpty()
        {
            return mSessions.isEmpty();
        }

        private void refresh(boolean force)
        {
            synchronized(ChannelDiagnosticService.this)
            {
                refreshLocked(force);
            }
        }

        private void refreshLocked(boolean force)
        {
            if(mClosed || isEmpty())
            {
                return;
            }

            long now = System.nanoTime();

            if(!force && !mRefreshRequested && now < mNextRefreshNanos)
            {
                return;
            }

            mRefreshRequested = false;
            mNextRefreshNanos = now + BINDING_REFRESH_NANOS;
            List<ProcessingChain> matches = mChannelProcessingManager.getProcessingChainsByConfiguration(
                mScope.configurationId(), mScope.frequencyHz());
            ProcessingChain next = matches.stream().filter(ProcessingChain::isProcessing).findFirst().orElse(null);
            BindingInfo info = next != null ? bindingInfo(next, mScope.frequencyHz()) : null;

            if(next == mProcessingChain && !mRetryProducer)
            {
                if(Objects.equals(mBindingInfo, info) && (mProducer == null || !mProducer.isClosed()))
                {
                    return;
                }

                if(mProducer != null && !mProducer.isClosed() && mProducer.isCompatible(info))
                {
                    BindingInfo previous = mProducer.info();
                    mProducer.updateInfo(info);
                    mBindingInfo = info;

                    if(!previous.equals(info))
                    {
                        updateProducerState(mProducer, info);
                    }

                    return;
                }
            }

            detach();
            mProcessingChain = next;
            mBindingInfo = info;

            if(next == null)
            {
                mState = state(0, "waiting", "Waiting for the selected channel to become active.",
                    "waiting", "Waiting for signal data.", "waiting", "Waiting for symbol data.",
                    mScope.frequencyHz(), 0, "", "", 0);
                return;
            }

            if(!info.signalSupported() && !info.symbolsSupported())
            {
                mRetryProducer = false;
                mState = state(0, "unsupported", "Channel diagnostics are not available for this channel.",
                    "unsupported", "Signal is not available for this channel.",
                    "unsupported", "Symbols are not available for this decoder.", info.frequencyHz(),
                    info.sampleRateHz(), info.protocol(), info.decoderProfile(), 0);
                return;
            }

            Producer producer;

            try
            {
                producer = acquireProducer(next, info, List.copyOf(mSessions));
            }
            catch(RuntimeException exception)
            {
                mLog.warn("Unable to attach selected-channel diagnostics", exception);
                mRetryProducer = false;
                mState = state(0, "unavailable", "Channel diagnostics could not be started.",
                    info.signalSupported() ? "unavailable" : "unsupported",
                    info.signalSupported() ? "Signal diagnostics could not be started." :
                        "Signal is not available for this channel.",
                    info.symbolsSupported() ? "unavailable" : "unsupported",
                    info.symbolsSupported() ? "Symbol diagnostics could not be started." :
                        "Symbols are not available for this decoder.",
                    info.frequencyHz(), info.sampleRateHz(), info.protocol(), info.decoderProfile(), info.fftSize());
                return;
            }

            if(producer == null)
            {
                mRetryProducer = true;
                mState = state(0, "capacity", "Too many different channels are already being viewed.",
                    "waiting", "Waiting for diagnostic capacity.", "waiting", "Waiting for diagnostic capacity.",
                    info.frequencyHz(), info.sampleRateHz(), info.protocol(), info.decoderProfile(), info.fftSize());
                return;
            }

            mProducer = producer;
            mRetryProducer = producer.hasTransientAttachmentFailure();
            updateProducerState(producer, info);
        }

        private void requestRefresh()
        {
            mRefreshRequested = true;
        }

        private void updateProducerState(Producer producer, BindingInfo info)
        {
            mState = state(producer.generation(), producer.isLive() ? "live" : "unavailable",
                producer.isLive() ? "" : "Channel diagnostics could not be started.",
                producer.signalState(), producer.signalReason(), producer.symbolsState(), producer.symbolsReason(),
                info.frequencyHz(), info.sampleRateHz(), info.protocol(), info.decoderProfile(), info.fftSize());
        }

        private State state(long generation, String state, String reason, String signalState, String signalReason,
                            String symbolsState, String symbolsReason, long frequency, long sampleRate,
                            String protocol, String decoderProfile, int fftSize)
        {
            return new State(++mStateRevision, generation, state, reason, signalState, signalReason, symbolsState,
                symbolsReason, frequency, sampleRate, mScope.timeslot(), protocol, decoderProfile, fftSize,
                SIGNAL_FRAMES_PER_SECOND, SYMBOL_BATCH_SIZE, MAXIMUM_VISIBLE_SYMBOLS);
        }

        private void detach()
        {
            Producer producer = mProducer;
            mProducer = null;

            if(producer != null)
            {
                releaseProducer(producer, List.copyOf(mSessions));
            }
        }

        @Override
        public void close()
        {
            detach();
            mProcessingChain = null;
            mBindingInfo = null;
            mRetryProducer = false;
        }
    }

    private BindingInfo bindingInfo(ProcessingChain processingChain, long fallbackFrequencyHz)
    {
        Source source = processingChain.getSource();
        PrimaryDecoder decoder = processingChain.getModules().stream().filter(PrimaryDecoder.class::isInstance)
            .map(PrimaryDecoder.class::cast).findFirst().orElse(null);
        long frequency = source != null && source.getFrequency() > 0 ? source.getFrequency() : 0;
        long sampleRate = source != null && Double.isFinite(source.getSampleRate()) && source.getSampleRate() > 0 ?
            Math.round(source.getSampleRate()) : 0;
        boolean signalSupported = source != null && source.getSampleType() == SampleType.COMPLEX && sampleRate > 0;
        boolean symbolsSupported = decoder instanceof FeedbackDecoder;
        String protocol = decoder != null && decoder.getDecoderType() != null ?
            decoder.getDecoderType().getDisplayString() : "";
        String decoderProfile = protocol;

        if(decoder instanceof FeedbackDecoder feedbackDecoder)
        {
            decoderProfile = feedbackDecoder.getProtocolDescription();
        }

        int fftSize = signalSupported ? fftSize(sampleRate) : 0;
        return new BindingInfo(source, decoder, frequency > 0 ? frequency : fallbackFrequencyHz, sampleRate,
            protocol, decoderProfile, fftSize, signalSupported, symbolsSupported);
    }

    private static int fftSize(long sampleRate)
    {
        return sampleRate >= (long)MAXIMUM_FFT_SIZE * SIGNAL_FRAMES_PER_SECOND ? MAXIMUM_FFT_SIZE :
            DFTSize.FFT00512.getSize();
    }

    private record BindingInfo(Source source, PrimaryDecoder decoder, long frequencyHz, long sampleRateHz,
                               String protocol, String decoderProfile, int fftSize, boolean signalSupported,
                               boolean symbolsSupported)
    {
    }

    private final class Producer implements AutoCloseable
    {
        private final ProcessingChain mProcessingChain;
        private final long mGeneration;
        private final CopyOnWriteArrayList<Session> mSubscribers = new CopyOnWriteArrayList<>();
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private volatile BindingInfo mInfo;
        private final SignalSource mSignalSource;
        private final SymbolSource mSymbolSource;
        private final String mSignalState;
        private final String mSignalReason;
        private final String mSymbolsState;
        private final String mSymbolsReason;

        private Producer(ProcessingChain processingChain, BindingInfo info, long generation)
        {
            mProcessingChain = processingChain;
            mGeneration = generation;
            mInfo = info;
            SignalSource signalSource = null;
            SymbolSource symbolSource = null;
            String signalState;
            String signalReason;
            String symbolsState;
            String symbolsReason;

            if(info.signalSupported())
            {
                try
                {
                    signalSource = new SignalSource(this, processingChain, info);
                    signalState = "live";
                    signalReason = "";
                }
                catch(RuntimeException exception)
                {
                    mLog.warn("Unable to attach selected-channel signal diagnostics", exception);
                    signalState = "unavailable";
                    signalReason = "Signal diagnostics could not be started.";
                }
            }
            else
            {
                signalState = "unsupported";
                signalReason = "Signal is not available for this channel.";
            }

            if(info.decoder() instanceof FeedbackDecoder feedbackDecoder)
            {
                try
                {
                    symbolSource = new SymbolSource(this, feedbackDecoder);
                    symbolsState = "live";
                    symbolsReason = "";
                }
                catch(RuntimeException exception)
                {
                    mLog.warn("Unable to attach selected-channel symbol diagnostics", exception);
                    symbolSource = null;
                    symbolsState = "unavailable";
                    symbolsReason = "Symbol diagnostics could not be started.";
                }
            }
            else
            {
                symbolsState = "unsupported";
                symbolsReason = "Symbols are not available for this decoder.";
            }

            mSignalSource = signalSource;
            mSymbolSource = symbolSource;
            mSignalState = signalState;
            mSignalReason = signalReason;
            mSymbolsState = symbolsState;
            mSymbolsReason = symbolsReason;
        }

        private ProcessingChain processingChain()
        {
            return mProcessingChain;
        }

        private long generation()
        {
            return mGeneration;
        }

        private BindingInfo info()
        {
            return mInfo;
        }

        private boolean isCompatible(BindingInfo info)
        {
            BindingInfo current = mInfo;
            return current.source() == info.source() && current.decoder() == info.decoder() &&
                current.sampleRateHz() == info.sampleRateHz() && current.fftSize() == info.fftSize() &&
                current.signalSupported() == info.signalSupported() &&
                current.symbolsSupported() == info.symbolsSupported();
        }

        private void updateInfo(BindingInfo info)
        {
            mInfo = info;
        }

        private String signalState()
        {
            return mSignalState;
        }

        private String signalReason()
        {
            return mSignalReason;
        }

        private String symbolsState()
        {
            return mSymbolsState;
        }

        private String symbolsReason()
        {
            return mSymbolsReason;
        }

        private boolean isLive()
        {
            return "live".equals(mSignalState) || "live".equals(mSymbolsState);
        }

        private boolean hasTransientAttachmentFailure()
        {
            return (mInfo.signalSupported() && mSignalSource == null) ||
                (mInfo.symbolsSupported() && mSymbolSource == null);
        }

        private boolean isClosed()
        {
            return mClosed.get();
        }

        private DiagnosticFftScheduler scheduler()
        {
            return mFftScheduler;
        }

        private void add(Session session)
        {
            if(!mClosed.get())
            {
                mSubscribers.addIfAbsent(session);
            }
        }

        private void remove(Session session)
        {
            mSubscribers.remove(session);
        }

        private boolean isEmpty()
        {
            return mSubscribers.isEmpty();
        }

        private void publish(DiagnosticStreamFrame frame)
        {
            if(mClosed.get())
            {
                return;
            }

            for(Session subscriber: mSubscribers)
            {
                subscriber.offer(frame);
            }
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                if(mSignalSource != null)
                {
                    mSignalSource.close();
                }

                if(mSymbolSource != null)
                {
                    mSymbolSource.close();
                }

                mSubscribers.clear();
            }
        }
    }

    private static final class SignalSource implements AutoCloseable
    {
        private final Producer mProducer;
        private final ProcessingChain mProcessingChain;
        private final AtomicLong mSequence = new AtomicLong();
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final SignalTap mTap;
        private final DemandDftProcessor mDftProcessor;

        private SignalSource(Producer producer, ProcessingChain processingChain, BindingInfo info)
        {
            mProducer = producer;
            mProcessingChain = processingChain;
            DFTSize dftSize = info.fftSize() == DFTSize.FFT00512.getSize() ? DFTSize.FFT00512 : DFTSize.FFT01024;
            mDftProcessor = new DemandDftProcessor(producer.scheduler(), dftSize,
                SIGNAL_FRAMES_PER_SECOND, this::publish);
            mTap = new SignalTap(mDftProcessor);
            boolean moduleAdded = false;

            try
            {
                mProcessingChain.addModule(mTap);
                moduleAdded = true;
            }
            catch(RuntimeException exception)
            {
                mTap.close();

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

                mDftProcessor.close();
                throw exception;
            }
        }

        private void publish(Long observedAtEpochMs, float[] bins)
        {
            if(!mClosed.get())
            {
                BindingInfo info = mProducer.info();
                mProducer.publish(DiagnosticStreamFrame.float32(DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL,
                    mProducer.generation(), mSequence.incrementAndGet(), observedAtEpochMs,
                    info.frequencyHz(), info.sampleRateHz(), info.fftSize(), bins));
            }
        }

        @Override
        public void close()
        {
            if(!mClosed.compareAndSet(false, true))
            {
                return;
            }

            mTap.close();

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
                mDftProcessor.close();
            }
        }
    }

    /**
     * Processing-chain tap whose producer callback only offers the existing ComplexSamples reference to a fixed
     * SPSC ingress.  Native-buffer adaptation and FFT work occur later on the diagnostic worker.
     */
    private static final class SignalTap extends Module implements IComplexSamplesListener, Listener<ComplexSamples>,
        AutoCloseable
    {
        private final DemandDftProcessor mProcessor;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private SignalTap(DemandDftProcessor processor)
        {
            mProcessor = processor;
        }

        @Override
        public Listener<ComplexSamples> getComplexSamplesListener()
        {
            return this;
        }

        @Override
        public void receive(ComplexSamples samples)
        {
            if(!mClosed.get())
            {
                mProcessor.receive(samples);
            }
        }

        @Override
        public void close()
        {
            mClosed.set(true);
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
        }

        @Override
        public void stop()
        {
        }
    }

    private static final class SymbolSource implements FeedbackDecoder.SymbolObserver, AutoCloseable
    {
        private final Producer mProducer;
        private final FeedbackDecoder mDecoder;
        private final BoundedSpscFloatBatchQueue mBatches =
            new BoundedSpscFloatBatchQueue(SYMBOL_BATCH_SIZE, 4);
        private final AtomicLong mSequence = new AtomicLong();
        private final AtomicLong mDroppedSymbols = new AtomicLong();
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final DiagnosticFftScheduler.Task mDrainTask;

        private SymbolSource(Producer producer, FeedbackDecoder decoder)
        {
            mProducer = producer;
            mDecoder = decoder;
            mDrainTask = producer.scheduler().scheduleWithFixedDelay(this::drain, 40);

            try
            {
                mDecoder.addSymbolObserver(this);
            }
            catch(RuntimeException exception)
            {
                mDrainTask.close();
                throw exception;
            }
        }

        @Override
        public void receive(float symbol)
        {
            if(mClosed.get() || !Float.isFinite(symbol) || symbol < -MAXIMUM_SYMBOL_PHASE ||
                symbol > MAXIMUM_SYMBOL_PHASE)
            {
                return;
            }

            if(!mBatches.offer(symbol))
            {
                mDroppedSymbols.incrementAndGet();
            }
        }

        private void drain()
        {
            float[] batch;

            while(!mClosed.get() && (batch = mBatches.poll()) != null)
            {
                try
                {
                    BindingInfo info = mProducer.info();
                    mProducer.publish(DiagnosticStreamFrame.float32(DiagnosticStreamFrame.TYPE_CHANNEL_SYMBOLS,
                        mProducer.generation(), mSequence.incrementAndGet(), System.currentTimeMillis(),
                        info.frequencyHz(), info.sampleRateHz(), 0, batch));
                }
                finally
                {
                    //DiagnosticStreamFrame copied the values into its encoded byte array, so this preallocated batch
                    //can be returned to the producer without retaining or allocating another float array.
                    mBatches.release();
                }
            }
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mDecoder.removeSymbolObserver(this);
                mDrainTask.close();
            }
        }
    }

}
