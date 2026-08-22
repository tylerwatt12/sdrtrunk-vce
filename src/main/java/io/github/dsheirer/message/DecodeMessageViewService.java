/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.message;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.util.concurrent.BoundedMpscPairQueue;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demand-owned, live-only decoder-message relay for the web interface.
 *
 * <p>All browser sessions for one exact configured-frequency scope share one processing-chain listener. A decoder
 * callback performs only a bounded, nonblocking reference offer. Chain validation, message classification,
 * {@code toString()} projection, and per-session fan-out run on the observer worker. The service retains no message
 * history and provides no replay.</p>
 */
public class DecodeMessageViewService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DecodeMessageViewService.class);
    static final int LIVE_QUEUE_SIZE = 256;
    static final int INGRESS_QUEUE_SIZE = 1_024;
    private static final int MAXIMUM_DRAIN_PER_PRODUCER = 512;
    private static final int TEXT_MAXIMUM_LENGTH = 2_048;
    private static final int PROTOCOL_MAXIMUM_LENGTH = 64;
    private static final int FILTER_MAXIMUM_LENGTH = 128;
    private static final long REBIND_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long MAINTENANCE_INTERVAL_MILLISECONDS = 10;
    private static final long DEFAULT_CLOSE_TIMEOUT_MILLISECONDS = 2_000;
    private final SourceResolver mSourceResolver;
    private final Map<Scope,Producer> mProducers = new HashMap<>();
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(
        new ObserverThreadFactory("sdrtrunk decode message views"));
    private final Semaphore mWakeup = new Semaphore(0);
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final long mCloseTimeoutMilliseconds;

    /** Constructs a service that binds directly to the exact active processing chain selected by each scope. */
    public DecodeMessageViewService(ChannelProcessingManager channelProcessingManager)
    {
        this(channelProcessingManager, DEFAULT_CLOSE_TIMEOUT_MILLISECONDS);
    }

    private DecodeMessageViewService(ChannelProcessingManager channelProcessingManager,
                                     long closeTimeoutMilliseconds)
    {
        Objects.requireNonNull(channelProcessingManager, "channelProcessingManager cannot be null");
        mSourceResolver = scope -> {
            List<ProcessingChain> chains = channelProcessingManager.getProcessingChainsByConfiguration(
                scope.configurationId(), scope.frequencyHz());

            if(chains != null)
            {
                for(ProcessingChain chain: chains)
                {
                    ChainMessageSource source = chain != null ? new ChainMessageSource(chain) : null;

                    if(source != null && source.matches(scope))
                    {
                        return source;
                    }
                }
            }

            return null;
        };
        mCloseTimeoutMilliseconds = closeTimeoutMilliseconds;
        startWorker();
    }

    DecodeMessageViewService(SourceResolver sourceResolver)
    {
        this(sourceResolver, DEFAULT_CLOSE_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    DecodeMessageViewService(SourceResolver sourceResolver, long closeTimeout, TimeUnit unit)
    {
        mSourceResolver = Objects.requireNonNull(sourceResolver, "sourceResolver cannot be null");
        Objects.requireNonNull(unit, "unit cannot be null");
        mCloseTimeoutMilliseconds = Math.max(0, unit.toMillis(closeTimeout));
        startWorker();
    }

    private void startWorker()
    {
        mWorker.execute(this::runWorker);
    }

    private void runWorker()
    {
        try
        {
            while(!mClosed.get())
            {
                boolean hasProducers = maintainSafely();

                try
                {
                    if(hasProducers)
                    {
                        mWakeup.tryAcquire(MAINTENANCE_INTERVAL_MILLISECONDS, TimeUnit.MILLISECONDS);
                    }
                    else
                    {
                        //No open viewer means no periodic observer work.
                        mWakeup.acquire();
                    }

                    mWakeup.drainPermits();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        finally
        {
            detachAllOnWorker();
        }
    }

    /** Opens an empty live session. Multiple sessions for the same scope share one source listener and projection. */
    public Session openSession(Scope scope)
    {
        Objects.requireNonNull(scope, "scope cannot be null");

        synchronized(mProducers)
        {
            if(mClosed.get())
            {
                throw new IllegalStateException("decode message view service is closed");
            }

            Producer producer = mProducers.computeIfAbsent(scope, Producer::new);
            Session session = producer.openSession();
            mWakeup.release();
            return session;
        }
    }

    private void release(Session session)
    {
        synchronized(mProducers)
        {
            Producer producer = session.mProducer;
            producer.remove(session);
            mWakeup.release();
        }
    }

    private boolean maintainSafely()
    {
        try
        {
            List<Producer> producers;

            synchronized(mProducers)
            {
                producers = List.copyOf(mProducers.values());
            }

            for(Producer producer: producers)
            {
                try
                {
                    if(producer.shouldRetire())
                    {
                        boolean removed = false;

                        synchronized(mProducers)
                        {
                            if(producer.shouldRetire() && mProducers.get(producer.mScope) == producer)
                            {
                                mProducers.remove(producer.mScope);
                                removed = true;
                            }
                        }

                        if(removed)
                        {
                            producer.detach();
                        }

                        continue;
                    }

                    producer.refreshIfDue();
                    producer.drain();
                }
                catch(RuntimeException exception)
                {
                    mLog.warn("Error processing decoder messages for {}", producer.mScope, exception);
                }
            }

            synchronized(mProducers)
            {
                return !mProducers.isEmpty();
            }
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Error processing decoder message observations", exception);
            return true;
        }
    }

    private void detachAllOnWorker()
    {
        List<Producer> producers;

        synchronized(mProducers)
        {
            producers = List.copyOf(mProducers.values());
            mProducers.clear();
        }

        for(Producer producer: producers)
        {
            producer.detach();
        }
    }

    long getDroppedObservationCount(Scope scope)
    {
        synchronized(mProducers)
        {
            Producer producer = mProducers.get(scope);
            return producer != null ? producer.mDroppedObservations.get() : 0;
        }
    }

    int getProducerCount()
    {
        synchronized(mProducers)
        {
            return mProducers.size();
        }
    }

    int getPendingObservationCount(Scope scope)
    {
        synchronized(mProducers)
        {
            Producer producer = mProducers.get(scope);
            return producer != null ? producer.mIngress.size() : 0;
        }
    }

    boolean isWorkerTerminated()
    {
        return mWorker.isTerminated();
    }

    @Override
    public void close()
    {
        synchronized(mProducers)
        {
            if(!mClosed.compareAndSet(false, true))
            {
                return;
            }
        }

        //The worker owns listener detach and ingress cleanup even when it is currently blocked in projection.
        mWakeup.release();
        mWorker.shutdown();

        try
        {
            if(!mWorker.awaitTermination(mCloseTimeoutMilliseconds, TimeUnit.MILLISECONDS))
            {
                mLog.warn("Timed out waiting for decoder-message observer cleanup");
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface SourceResolver
    {
        MessageSource resolve(Scope scope);
    }

    interface MessageSource
    {
        void addListener(Listener<IMessage> listener);
        void removeListener(Listener<IMessage> listener);
        boolean matches(Scope scope);
    }

    /** Exact active-channel selection. Configuration identifiers are normalized UUID strings. */
    public record Scope(String configurationId, long frequencyHz)
    {
        public Scope
        {
            if(configurationId == null)
            {
                throw new IllegalArgumentException("configurationId is required");
            }

            try
            {
                configurationId = UUID.fromString(configurationId.strip()).toString();
            }
            catch(IllegalArgumentException exception)
            {
                throw new IllegalArgumentException("configurationId must be a UUID", exception);
            }

            if(frequencyHz <= 0)
            {
                throw new IllegalArgumentException("frequencyHz must be positive");
            }
        }
    }

    /** Safe, bounded fields needed by the web message table and its browser-owned filters. */
    public record MessageView(String messageId, long timestampMs, String protocol, int timeslot, boolean valid,
                              String filterGroup, String filterType, String text)
    {
    }

    /** Per-client empty live queue over one shared per-scope producer. */
    public static class Session implements AutoCloseable
    {
        private final DecodeMessageViewService mService;
        private final Producer mProducer;
        private final ArrayBlockingQueue<MessageView> mQueue = new ArrayBlockingQueue<>(LIVE_QUEUE_SIZE);
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final AtomicLong mDroppedMessages = new AtomicLong();
        private final long mStartingAudienceEpoch;
        private final long mStartingProducerDrops;

        private Session(DecodeMessageViewService service, Producer producer, long startingAudienceEpoch)
        {
            mService = service;
            mProducer = producer;
            mStartingAudienceEpoch = startingAudienceEpoch;
            mStartingProducerDrops = producer.mDroppedObservations.get();
        }

        public Scope getScope()
        {
            return mProducer.mScope;
        }

        /** Requests an immediate worker-side source check. */
        public void refresh()
        {
            mProducer.requestRefresh();
            mService.mWakeup.release();
        }

        public boolean isBound()
        {
            return mProducer.mBinding != null && !mClosed.get() && !mService.mClosed.get();
        }

        public long generation()
        {
            return mProducer.mGeneration;
        }

        /** Monotonic count of shared ingress and this session's bounded output drops. */
        public long droppedCount()
        {
            long producerDrops = mProducer.mDroppedObservations.get();
            return Math.max(0, producerDrops - mStartingProducerDrops) + mDroppedMessages.get();
        }

        public MessageView poll(long timeout, TimeUnit unit) throws InterruptedException
        {
            Objects.requireNonNull(unit, "unit cannot be null");

            if(timeout < 0)
            {
                throw new IllegalArgumentException("timeout cannot be negative");
            }

            if(mClosed.get() || mService.mClosed.get())
            {
                return null;
            }

            return timeout == 0 ? mQueue.poll() : mQueue.poll(timeout, unit);
        }

        private void publish(MessageView view)
        {
            if(mClosed.get() || mService.mClosed.get())
            {
                return;
            }

            if(!mQueue.offer(view))
            {
                if(mQueue.poll() != null)
                {
                    mDroppedMessages.incrementAndGet();
                }

                mQueue.offer(view);
            }
        }

        private boolean accepts(long audienceEpoch)
        {
            return !mClosed.get() && audienceEpoch >= mStartingAudienceEpoch;
        }

        private void reset()
        {
            mQueue.clear();
        }

        private void closeFromWorker()
        {
            mClosed.set(true);
            mQueue.clear();
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mQueue.clear();
                mService.release(this);
            }
        }
    }

    private final class Producer
    {
        private final Scope mScope;
        private final BoundedMpscPairQueue<IngressStamp,IMessage> mIngress =
            new BoundedMpscPairQueue<>(INGRESS_QUEUE_SIZE);
        private final CopyOnWriteArrayList<Session> mSessions = new CopyOnWriteArrayList<>();
        private final AtomicLong mDroppedObservations = new AtomicLong();
        private volatile Binding mBinding;
        private volatile IngressStamp mIngressStamp;
        private volatile long mGeneration;
        private volatile long mNextRefreshNanos;
        private volatile boolean mRetirementRequested;
        private long mAudienceEpoch;

        private Producer(Scope scope)
        {
            mScope = scope;
        }

        /**
         * Opens a session at a new live edge. Lifecycle updates are serialized with source binding changes, while the
         * decoder callback only reads the resulting immutable stamp.
         */
        private synchronized Session openSession()
        {
            long audienceEpoch = ++mAudienceEpoch;
            Session session = new Session(DecodeMessageViewService.this, this, audienceEpoch);
            mSessions.addIfAbsent(session);
            mRetirementRequested = false;
            Binding binding = mBinding;
            mIngressStamp = binding != null ? new IngressStamp(binding.mToken, audienceEpoch) : null;
            requestRefresh();
            return session;
        }

        private synchronized void remove(Session session)
        {
            mSessions.remove(session);

            if(mSessions.isEmpty())
            {
                mRetirementRequested = true;
            }
        }

        private boolean hasSessions()
        {
            return !mSessions.isEmpty();
        }

        private boolean shouldRetire()
        {
            return mRetirementRequested && !hasSessions();
        }

        private void requestRefresh()
        {
            mNextRefreshNanos = 0;
        }

        private void refreshIfDue()
        {
            long now = System.nanoTime();

            if(now < mNextRefreshNanos)
            {
                return;
            }

            mNextRefreshNanos = now + REBIND_INTERVAL_NANOS;
            MessageSource nextSource = mSourceResolver.resolve(mScope);

            if(nextSource != null && !nextSource.matches(mScope))
            {
                nextSource = null;
            }

            if(mClosed.get())
            {
                return;
            }

            synchronized(this)
            {
                if(mClosed.get() || shouldRetire())
                {
                    return;
                }

                Binding current = mBinding;
                boolean currentMatches = current != null && current.mSource.matches(mScope);

                if((current == null && nextSource == null) ||
                    (currentMatches && current.mSource.equals(nextSource)))
                {
                    return;
                }

                if(current != null)
                {
                    current.mSource.removeListener(current.mListener);
                }

                mBinding = null;
                mIngressStamp = null;
                mIngress.clear();
                mGeneration++;

                for(Session session: mSessions)
                {
                    session.reset();
                }

                if(nextSource != null)
                {
                    Object token = new Object();
                    Listener<IMessage> listener = message -> receive(token, message);
                    Binding next = new Binding(nextSource, listener, token);
                    mBinding = next;
                    mIngressStamp = new IngressStamp(token, mAudienceEpoch);

                    try
                    {
                        nextSource.addListener(listener);

                        if(!nextSource.matches(mScope))
                        {
                            nextSource.removeListener(listener);
                            mBinding = null;
                            mIngressStamp = null;
                            mGeneration++;
                        }
                    }
                    catch(RuntimeException exception)
                    {
                        mBinding = null;
                        mIngressStamp = null;
                        throw exception;
                    }
                }
            }
        }

        private void receive(Object token, IMessage message)
        {
            Binding binding = mBinding;
            IngressStamp stamp = mIngressStamp;

            if(message == null || message instanceof StuffBitsMessage || binding == null ||
                binding.mToken != token || stamp == null || stamp.mBindingToken != token ||
                mRetirementRequested || mClosed.get())
            {
                return;
            }

            if(!mIngress.offer(stamp, message))
            {
                mDroppedObservations.incrementAndGet();
            }
        }

        private void drain()
        {
            for(int count = 0; count < MAXIMUM_DRAIN_PER_PRODUCER; count++)
            {
                if(shouldAbandonIngress())
                {
                    mIngress.clear();
                    return;
                }

                BoundedMpscPairQueue.Entry<IngressStamp,IMessage> observation = mIngress.poll();

                if(observation == null)
                {
                    break;
                }

                if(shouldAbandonIngress())
                {
                    mIngress.clear();
                    return;
                }

                IngressStamp stamp = observation.first();
                Binding binding = mBinding;

                if(binding == null || binding.mToken != stamp.mBindingToken ||
                    !binding.mSource.matches(mScope) || !hasAudience(stamp.mAudienceEpoch))
                {
                    continue;
                }

                MessageView projected = view(observation.second());

                //A DMR REST handoff can change the chain's functional channel while projection is in progress.
                if(projected != null && !mClosed.get() && mBinding == binding &&
                    binding.mSource.matches(mScope))
                {
                    for(Session session: mSessions)
                    {
                        if(session.accepts(stamp.mAudienceEpoch))
                        {
                            session.publish(projected);
                        }
                    }
                }
            }
        }

        private boolean shouldAbandonIngress()
        {
            return mClosed.get() || (mRetirementRequested && !hasSessions());
        }

        private boolean hasAudience(long audienceEpoch)
        {
            for(Session session: mSessions)
            {
                if(session.accepts(audienceEpoch))
                {
                    return true;
                }
            }

            return false;
        }

        private synchronized void detach()
        {
            Binding binding = mBinding;
            mBinding = null;
            mIngressStamp = null;
            mGeneration++;

            if(binding != null)
            {
                binding.mSource.removeListener(binding.mListener);
            }

            mIngress.clear();

            for(Session session: mSessions)
            {
                session.closeFromWorker();
            }

            mSessions.clear();
        }
    }

    private static MessageView view(IMessage message)
    {
        if(message == null || message instanceof StuffBitsMessage)
        {
            return null;
        }

        long timestamp = timestamp(message);
        ProtocolInfo protocol = protocol(message);
        return new MessageView(messageId(message, timestamp), timestamp, protocol.display(), timeslot(message),
            valid(message), protocol.filterGroup(), filterType(message), text(message));
    }

    private static String messageId(IMessage message, long timestamp)
    {
        return Long.toUnsignedString(timestamp, 36) + "-" +
            Integer.toUnsignedString(System.identityHashCode(message), 36);
    }

    private static long timestamp(IMessage message)
    {
        try
        {
            return message.getTimestamp();
        }
        catch(RuntimeException _)
        {
            return 0;
        }
    }

    private static ProtocolInfo protocol(IMessage message)
    {
        try
        {
            Protocol protocol = message.getProtocol();
            return protocol != null ? new ProtocolInfo(bounded(protocol.toString(), PROTOCOL_MAXIMUM_LENGTH),
                bounded(protocol.name(), FILTER_MAXIMUM_LENGTH)) : ProtocolInfo.UNKNOWN;
        }
        catch(RuntimeException _)
        {
            return ProtocolInfo.UNKNOWN;
        }
    }

    private static int timeslot(IMessage message)
    {
        try
        {
            return message.getTimeslot();
        }
        catch(RuntimeException _)
        {
            return 0;
        }
    }

    private static boolean valid(IMessage message)
    {
        try
        {
            return message.isValid();
        }
        catch(RuntimeException _)
        {
            return false;
        }
    }

    private static String filterType(IMessage message)
    {
        try
        {
            String type = bounded(message.getClass().getSimpleName(), FILTER_MAXIMUM_LENGTH);
            return !type.isBlank() ? type : "UnknownMessage";
        }
        catch(RuntimeException _)
        {
            return "UnknownMessage";
        }
    }

    private static String text(IMessage message)
    {
        try
        {
            return bounded(message.toString(), TEXT_MAXIMUM_LENGTH);
        }
        catch(RuntimeException _)
        {
            return "MESSAGE ITEM ENCOUNTERED PARSING ERROR";
        }
    }

    private static String bounded(String value, int maximumLength)
    {
        if(value == null)
        {
            return "";
        }

        String stripped = value.strip();
        return stripped.length() <= maximumLength ? stripped :
            stripped.substring(0, maximumLength - 1) + "…";
    }

    private record ChainMessageSource(ProcessingChain mChain) implements MessageSource
    {
        @Override
        public void addListener(Listener<IMessage> listener)
        {
            mChain.addMessageListener(listener);
        }

        @Override
        public void removeListener(Listener<IMessage> listener)
        {
            mChain.removeMessageListener(listener);
        }

        @Override
        public boolean matches(Scope scope)
        {
            Channel channel = mChain.getCurrentChannel();
            Source source = mChain.getSource();
            return channel != null && source != null &&
                scope.configurationId().equals(channel.getConfigurationId()) &&
                source.getFrequency() == scope.frequencyHz();
        }
    }

    private record Binding(MessageSource mSource, Listener<IMessage> mListener, Object mToken)
    {
    }

    private record IngressStamp(Object mBindingToken, long mAudienceEpoch)
    {
    }

    private record ProtocolInfo(String display, String filterGroup)
    {
        private static final ProtocolInfo UNKNOWN = new ProtocolInfo("Unknown", "UNKNOWN");
    }
}
