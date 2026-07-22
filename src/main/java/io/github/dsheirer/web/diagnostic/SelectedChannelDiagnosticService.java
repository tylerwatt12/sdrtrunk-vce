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

package io.github.dsheirer.web.diagnostic;

import io.github.dsheirer.application.service.LiveContext;
import io.github.dsheirer.application.service.LiveContextResolver;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.dsp.symbol.stream.SelectedChannelSymbolSource;
import io.github.dsheirer.dsp.symbol.stream.SymbolFrame;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.PrimaryDecoder;
import io.github.dsheirer.module.decode.p25.phase2.P25P2Decoder;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.spectrum.stream.SelectedChannelSpectrumSource;
import io.github.dsheirer.spectrum.stream.SpectrumFrame;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bounded, memory-only selected-channel diagnostic workspace.
 *
 * <p>The service resolves short-lived Live selection IDs and attaches only the source required by the visible view.
 * Signal and symbol callbacks publish into replaceable in-memory slots. Network, encoding, JSON, disk, and database
 * work remain outside the decoder and sample callbacks.</p>
 */
public final class SelectedChannelDiagnosticService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(SelectedChannelDiagnosticService.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private final LiveContextResolver mContextResolver;
    private final Configuration mConfiguration;
    private final ScheduledThreadPoolExecutor mRefreshExecutor;
    private final AtomicReference<Session> mSession = new AtomicReference<>();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final AtomicLong mOpenedSessions = new AtomicLong();
    private final AtomicLong mRejectedSessions = new AtomicLong();
    private final AtomicLong mSignalFrames = new AtomicLong();
    private final AtomicLong mSignalDrops = new AtomicLong();
    private final AtomicLong mSymbolFrames = new AtomicLong();
    private final AtomicLong mSymbolDrops = new AtomicLong();
    private final AtomicLong mDiscardedSymbols = new AtomicLong();

    public SelectedChannelDiagnosticService(LiveContextResolver contextResolver)
    {
        this(contextResolver, Configuration.defaults());
    }

    public SelectedChannelDiagnosticService(LiveContextResolver contextResolver, Configuration configuration)
    {
        mContextResolver = Objects.requireNonNull(contextResolver, "Live context resolver cannot be null");
        mConfiguration = Objects.requireNonNull(configuration, "Diagnostic service configuration cannot be null");
        mRefreshExecutor = new ScheduledThreadPoolExecutor(1, runnable ->
        {
            Thread thread = new Thread(runnable, configuration.refreshThreadName());
            thread.setDaemon(true);
            return thread;
        });
        mRefreshExecutor.setRemoveOnCancelPolicy(true);
        mRefreshExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        mRefreshExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public OpenResult tryOpen(String selectionId, View view, long requestId)
    {
        if(mClosed.get())
        {
            return new OpenResult(OpenStatus.CLOSED, null);
        }

        if(selectionId == null || selectionId.isBlank() || selectionId.length() > 96 || requestId < 0 || view == null)
        {
            return new OpenResult(OpenStatus.INVALID, null);
        }

        Optional<LiveContext> resolved = mContextResolver.resolve(selectionId);

        if(resolved.isEmpty())
        {
            return new OpenResult(OpenStatus.ENDED, null);
        }

        Session candidate = new Session(selectionId, view, requestId);

        if(!mSession.compareAndSet(null, candidate))
        {
            candidate.closeInternal();
            mRejectedSessions.incrementAndGet();
            return new OpenResult(OpenStatus.BUSY, null);
        }

        try
        {
            if(mClosed.get())
            {
                candidate.close();
                return new OpenResult(OpenStatus.CLOSED, null);
            }

            // Ownership is published before touching the processing chain.  A competing opener can only observe
            // BUSY and therefore can never attach a second FFT or symbol observer, even briefly.
            candidate.bind(resolved.get(), true);
            candidate.startRefresh();
            mOpenedSessions.incrementAndGet();
            return new OpenResult(OpenStatus.OPEN, candidate);
        }
        catch(RuntimeException exception)
        {
            candidate.close();
            throw exception;
        }
    }

    private void release(Session session)
    {
        mSession.compareAndSet(session, null);
    }

    public int getActiveSessionCount()
    {
        return mSession.get() != null ? 1 : 0;
    }

    public long getOpenedSessionCount()
    {
        return mOpenedSessions.get();
    }

    public long getRejectedSessionCount()
    {
        return mRejectedSessions.get();
    }

    public long getSignalFrameCount()
    {
        Session session = mSession.get();
        return mSignalFrames.get() + (session != null ? session.currentSignalFrames() : 0);
    }

    public long getSignalDropCount()
    {
        Session session = mSession.get();
        return mSignalDrops.get() + (session != null ? session.currentSignalDrops() : 0);
    }

    public long getSymbolFrameCount()
    {
        Session session = mSession.get();
        return mSymbolFrames.get() + (session != null ? session.currentSymbolFrames() : 0);
    }

    public long getSymbolDropCount()
    {
        Session session = mSession.get();
        return mSymbolDrops.get() + (session != null ? session.currentSymbolDrops() : 0);
    }

    public long getDiscardedSymbolCount()
    {
        Session session = mSession.get();
        return mDiscardedSymbols.get() + (session != null ? session.currentDiscardedSymbols() : 0);
    }

    public boolean isRefreshExecutorTerminated()
    {
        return mRefreshExecutor.isTerminated();
    }

    @Override
    public void close()
    {
        if(!mClosed.compareAndSet(false, true))
        {
            return;
        }

        Session session = mSession.getAndSet(null);
        RuntimeException failure = null;

        if(session != null)
        {
            try
            {
                session.closeInternal();
            }
            catch(RuntimeException exception)
            {
                failure = exception;
            }
        }

        mRefreshExecutor.shutdownNow();

        try
        {
            if(!mRefreshExecutor.awaitTermination(SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS))
            {
                IllegalStateException exception =
                    new IllegalStateException("Selected-channel diagnostic refresh executor did not terminate");

                if(failure != null)
                {
                    exception.addSuppressed(failure);
                }

                throw exception;
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping selected-channel diagnostics", exception);
        }

        if(failure != null)
        {
            throw failure;
        }
    }

    public enum View
    {
        SIGNAL("signal"),
        SYMBOLS("symbols");

        private final String mId;

        View(String id)
        {
            mId = id;
        }

        public String id()
        {
            return mId;
        }

        public static View fromId(String id)
        {
            if(id != null)
            {
                for(View view: values())
                {
                    if(view.mId.equals(id.toLowerCase(Locale.ROOT)))
                    {
                        return view;
                    }
                }
            }

            throw new IllegalArgumentException("Unsupported diagnostic view");
        }
    }

    public enum StateType
    {
        BINDING("binding"),
        LIVE("live"),
        UNSUPPORTED("unsupported"),
        ENDED("ended");

        private final String mId;

        StateType(String id)
        {
            mId = id;
        }

        public String id()
        {
            return mId;
        }
    }

    public enum OpenStatus
    {
        OPEN,
        BUSY,
        ENDED,
        INVALID,
        CLOSED
    }

    public record OpenResult(OpenStatus status, Session session)
    {
        public OpenResult
        {
            Objects.requireNonNull(status, "Diagnostic open status cannot be null");

            if((status == OpenStatus.OPEN) != (session != null))
            {
                throw new IllegalArgumentException("Only an open result can contain a diagnostic session");
            }
        }
    }

    public record ContextDetails(String selectionId, String tableTitle, String channelName, String scope,
                                 long frequencyHz, Integer timeslot, String decoder, String protocol,
                                 long sampleRateHz, int channelBandwidthHz, boolean signalSupported,
                                 boolean symbolsSupported)
    {
    }

    public record State(long revision, long requestId, long generation, View view, StateType state,
                        String reason, ContextDetails context)
    {
    }

    public final class Session implements AutoCloseable
    {
        private final String mSelectionId;
        private final AtomicBoolean mSessionClosed = new AtomicBoolean();
        private volatile View mView;
        private volatile long mRequestId;
        private volatile long mGeneration;
        private volatile State mState;
        private long mStateRevision;
        private LiveContext mContext;
        private ProcessingChain mProcessingChain;
        private volatile SelectedChannelSpectrumSource mSpectrumSource;
        private volatile SelectedChannelSymbolSource mSymbolSource;
        private ScheduledFuture<?> mRefreshTask;

        private Session(String selectionId, View view, long requestId)
        {
            mSelectionId = selectionId;
            mView = view;
            mRequestId = requestId;
        }

        private synchronized void startRefresh()
        {
            if(!mSessionClosed.get())
            {
                mRefreshTask = mRefreshExecutor.scheduleWithFixedDelay(this::refreshSafely,
                    mConfiguration.refreshInterval().toNanos(), mConfiguration.refreshInterval().toNanos(),
                    TimeUnit.NANOSECONDS);
            }
        }

        private void refreshSafely()
        {
            try
            {
                refresh();
            }
            catch(RuntimeException exception)
            {
                synchronized(this)
                {
                    if(!mSessionClosed.get())
                    {
                        try
                        {
                            detachSources();
                        }
                        catch(RuntimeException cleanupException)
                        {
                            exception.addSuppressed(cleanupException);
                        }

                        publishState(StateType.UNSUPPORTED, "diagnostic-source-failed");
                        mLog.warn("Selected-channel diagnostic source failed", exception);
                    }
                }
            }
        }

        private synchronized void refresh()
        {
            if(mSessionClosed.get() || mState != null && mState.state() == StateType.ENDED)
            {
                return;
            }

            Optional<LiveContext> resolved = mContextResolver.resolve(mSelectionId);

            if(resolved.isEmpty())
            {
                detachSources();
                mProcessingChain = null;
                mGeneration++;
                publishState(StateType.ENDED, "selection-ended");
                return;
            }

            LiveContext next = resolved.get();
            boolean contextChanged = !Objects.equals(next.selection(), mContext != null ? mContext.selection() : null);

            if(next.processingChain() != mProcessingChain || contextChanged)
            {
                bind(next, false);
            }
        }

        public synchronized void update(View view, long requestId)
        {
            if(mSessionClosed.get())
            {
                throw new IllegalStateException("Diagnostic session is closed");
            }

            if(view == null || requestId <= mRequestId)
            {
                throw new IllegalArgumentException("Diagnostic request IDs must increase and include a view");
            }

            mRequestId = requestId;

            if(view != mView)
            {
                detachSources();
                mView = view;
                mGeneration++;
                attachRequestedSource();
            }
            else
            {
                publishState(mState != null ? mState.state() : StateType.BINDING,
                    mState != null ? mState.reason() : "waiting-for-processing-chain");
            }
        }

        private synchronized void bind(LiveContext context, boolean initial)
        {
            if(mSessionClosed.get())
            {
                return;
            }

            detachSources();
            mContext = context;
            mProcessingChain = context.processingChain();
            mGeneration += initial ? 0 : 1;
            attachRequestedSource();
        }

        private void attachRequestedSource()
        {
            if(mProcessingChain == null)
            {
                publishState(StateType.BINDING, "waiting-for-processing-chain");
                return;
            }

            ContextDetails details = details();

            if(mView == View.SIGNAL)
            {
                if(!details.signalSupported())
                {
                    publishState(StateType.UNSUPPORTED, "signal-source-unavailable");
                    return;
                }

                mSpectrumSource = new SelectedChannelSpectrumSource(mProcessingChain, mGeneration,
                    details.frequencyHz(), details.sampleRateHz());
                publishState(StateType.LIVE, "");
                return;
            }

            PrimaryDecoder decoder = mProcessingChain.getPrimaryDecoder();

            if(!details.symbolsSupported() || !(decoder instanceof FeedbackDecoder feedbackDecoder))
            {
                publishState(StateType.UNSUPPORTED, "decoder-does-not-provide-symbols");
                return;
            }

            mSymbolSource = new SelectedChannelSymbolSource(feedbackDecoder, mGeneration);
            publishState(StateType.LIVE, "");
        }

        private void detachSources()
        {
            RuntimeException failure = null;
            SelectedChannelSpectrumSource spectrum = mSpectrumSource;
            mSpectrumSource = null;

            if(spectrum != null)
            {
                try
                {
                    spectrum.close();
                }
                catch(RuntimeException exception)
                {
                    failure = exception;
                }

                mSignalFrames.addAndGet(spectrum.getPublishedFrameCount());
                mSignalDrops.addAndGet(spectrum.getDroppedFrameCount());
            }

            SelectedChannelSymbolSource symbols = mSymbolSource;
            mSymbolSource = null;

            if(symbols != null)
            {
                try
                {
                    symbols.close();
                }
                catch(RuntimeException exception)
                {
                    if(failure != null)
                    {
                        failure.addSuppressed(exception);
                    }
                    else
                    {
                        failure = exception;
                    }
                }

                mSymbolFrames.addAndGet(symbols.getPublishedFrameCount());
                mSymbolDrops.addAndGet(symbols.getDroppedFrameCount());
                mDiscardedSymbols.addAndGet(symbols.getDiscardedSymbolCount());
            }

            if(failure != null)
            {
                throw failure;
            }
        }

        private void publishState(StateType state, String reason)
        {
            mState = new State(++mStateRevision, mRequestId, mGeneration, mView, state,
                reason != null ? reason : "", details());
        }

        private ContextDetails details()
        {
            ChannelActivitySelectionDescriptor selection = mContext != null ? mContext.selection() : null;
            ProcessingChain chain = mProcessingChain;
            PrimaryDecoder decoder = chain != null ? chain.getPrimaryDecoder() : null;
            Source source = chain != null ? chain.getSource() : null;
            long frequency = selection != null ? Math.max(0, selection.frequencyHz()) : 0;
            long sampleRate = 0;
            boolean signalSupported = false;

            if(source != null)
            {
                try
                {
                    frequency = Math.max(0, source.getFrequency());
                    sampleRate = Math.max(0, Math.round(source.getSampleRate()));
                    signalSupported = source.getSampleType() == SampleType.COMPLEX && sampleRate > 0;
                }
                catch(RuntimeException ignored)
                {
                    signalSupported = false;
                }
            }

            boolean symbolsSupported = decoder instanceof FeedbackDecoder && !(decoder instanceof P25P2Decoder);
            String protocol = decoder instanceof FeedbackDecoder feedbackDecoder ?
                feedbackDecoder.getProtocolDescription() : selection != null ? selection.decoderHint() : "";
            return new ContextDetails(mSelectionId,
                bounded(selection != null ? selection.tableTitle() : "", 160),
                bounded(selection != null ? selection.channelName() : "", 120),
                selection != null ? selection.scope().name() : "",
                frequency, selection != null ? selection.timeslot() : null,
                bounded(selection != null ? selection.decoderHint() : "", 80), bounded(protocol, 80), sampleRate,
                channelBandwidth(mContext), signalSupported, symbolsSupported);
        }

        private int channelBandwidth(LiveContext context)
        {
            Channel channel = context != null && context.rowChannel() != null ? context.rowChannel() :
                context != null ? context.ownerChannel() : null;

            if(channel != null)
            {
                java.util.List<TunerChannel> tunerChannels = channel.getTunerChannels();

                if(!tunerChannels.isEmpty())
                {
                    return Math.max(0, tunerChannels.getFirst().getBandwidth());
                }
            }

            return 0;
        }

        public State state()
        {
            return mState;
        }

        public View view()
        {
            return mView;
        }

        public SpectrumFrame pollSpectrum(Duration timeout) throws InterruptedException
        {
            SelectedChannelSpectrumSource source = mSpectrumSource;
            return source != null ? source.poll(timeout) : null;
        }

        public SymbolFrame pollSymbols(Duration timeout) throws InterruptedException
        {
            SelectedChannelSymbolSource source = mSymbolSource;
            return source != null ? source.poll(timeout) : null;
        }

        public boolean isClosed()
        {
            return mSessionClosed.get();
        }

        private long currentSignalFrames()
        {
            SelectedChannelSpectrumSource source = mSpectrumSource;
            return source != null ? source.getPublishedFrameCount() : 0;
        }

        private long currentSignalDrops()
        {
            SelectedChannelSpectrumSource source = mSpectrumSource;
            return source != null ? source.getDroppedFrameCount() : 0;
        }

        private long currentSymbolFrames()
        {
            SelectedChannelSymbolSource source = mSymbolSource;
            return source != null ? source.getPublishedFrameCount() : 0;
        }

        private long currentSymbolDrops()
        {
            SelectedChannelSymbolSource source = mSymbolSource;
            return source != null ? source.getDroppedFrameCount() : 0;
        }

        private long currentDiscardedSymbols()
        {
            SelectedChannelSymbolSource source = mSymbolSource;
            return source != null ? source.getDiscardedSymbolCount() : 0;
        }

        @Override
        public void close()
        {
            try
            {
                closeInternal();
            }
            finally
            {
                release(this);
            }
        }

        private synchronized void closeInternal()
        {
            if(!mSessionClosed.compareAndSet(false, true))
            {
                return;
            }

            ScheduledFuture<?> refreshTask = mRefreshTask;
            mRefreshTask = null;

            if(refreshTask != null)
            {
                refreshTask.cancel(false);
            }

            detachSources();
            mContext = null;
            mProcessingChain = null;
        }
    }

    private static String bounded(String value, int maximumLength)
    {
        if(value == null)
        {
            return "";
        }

        String normalized = value.strip();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }

    public record Configuration(Duration refreshInterval, String refreshThreadName)
    {
        public Configuration
        {
            if(refreshInterval == null || refreshInterval.isNegative() || refreshInterval.isZero() ||
                refreshInterval.toNanos() <= 0)
            {
                throw new IllegalArgumentException("Diagnostic refresh interval must be positive");
            }

            if(refreshThreadName == null || refreshThreadName.isBlank())
            {
                throw new IllegalArgumentException("Diagnostic refresh thread name cannot be blank");
            }
        }

        public static Configuration defaults()
        {
            return new Configuration(Duration.ofMillis(250), "sdrtrunk selected-channel diagnostic refresh");
        }
    }
}
