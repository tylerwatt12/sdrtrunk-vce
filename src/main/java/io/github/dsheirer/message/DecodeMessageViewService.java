/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.message;

import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.concurrent.BoundedMpscPairQueue;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Shared, demand-driven decoder-message observation service.
 *
 * <p>Each active processing-chain history has one listener regardless of browser count.  The decoder callback only
 * performs a bounded, nonblocking reference offer.  Chain resolution, history snapshots, message projection and
 * per-client fan-out all run on the service worker.</p>
 */
public class DecodeMessageViewService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DecodeMessageViewService.class);
    static final int LIVE_QUEUE_SIZE = 256;
    static final int INGRESS_QUEUE_SIZE = 1_024;
    private static final int SHARED_HISTORY_SIZE = 200;
    private static final int MAXIMUM_DRAIN_PER_PRODUCER = 512;
    private static final int TEXT_MAXIMUM_LENGTH = 2_048;
    private static final int PROTOCOL_MAXIMUM_LENGTH = 64;
    private static final long REBIND_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long MAINTENANCE_INTERVAL_MILLISECONDS = 10;
    private static final long DEFAULT_CLOSE_TIMEOUT_MILLISECONDS = 2_000;
    private final HistoryResolver mHistoryResolver;
    private final Map<Scope,Producer> mProducers = new HashMap<>();
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(
        new ObserverThreadFactory("sdrtrunk decode message views"));
    private final Semaphore mWakeup = new Semaphore(0);
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final long mCloseTimeoutMilliseconds;

    /**
     * Constructs an instance that resolves the processing chain by exact configuration and source frequency.
     */
    public DecodeMessageViewService(ChannelProcessingManager channelProcessingManager)
    {
        this(channelProcessingManager, DEFAULT_CLOSE_TIMEOUT_MILLISECONDS);
    }

    private DecodeMessageViewService(ChannelProcessingManager channelProcessingManager,
                                     long closeTimeoutMilliseconds)
    {
        Objects.requireNonNull(channelProcessingManager, "channelProcessingManager cannot be null");
        mHistoryResolver = scope -> {
            List<ProcessingChain> chains = channelProcessingManager.getProcessingChainsByConfiguration(
                scope.configurationId(), scope.frequencyHz());

            if(chains != null)
            {
                for(ProcessingChain chain: chains)
                {
                    if(chain != null)
                    {
                        return chain.getMessageHistory();
                    }
                }
            }

            return null;
        };
        mCloseTimeoutMilliseconds = closeTimeoutMilliseconds;
        startWorker();
    }

    DecodeMessageViewService(HistoryResolver historyResolver)
    {
        this(historyResolver, DEFAULT_CLOSE_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    DecodeMessageViewService(HistoryResolver historyResolver, long closeTimeout, TimeUnit unit)
    {
        mHistoryResolver = Objects.requireNonNull(historyResolver, "historyResolver cannot be null");
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
                        //No sessions means no periodic observer wakeups.
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

    /**
     * Opens a lightweight view session.  Multiple sessions for the same scope share one history listener and one
     * projection cache.
     */
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
            producer.cancelRetirement();
            Session session = new Session(this, producer);
            producer.add(session);
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

            if(!producer.hasSessions())
            {
                producer.requestRetirement();
            }

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
        Producer producer;

        synchronized(mProducers)
        {
            producer = mProducers.get(scope);
        }

        return producer != null ? producer.mDroppedObservations.get() : 0;
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

        //The worker owns listener detach and ingress cleanup even when it is currently blocked in a resolver.
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
    interface HistoryResolver
    {
        MessageHistory resolve(Scope scope);
    }

    /**
     * Exact active-channel selection. Configuration identifiers are normalized UUID strings.
     */
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

    /**
     * Safe, bounded fields needed by the web message table.
     */
    public record MessageView(String messageId, long timestampMs, String protocol, int timeslot, boolean valid,
                              String text)
    {
    }

    /**
     * Per-client view over a shared producer.  Only the service worker writes the bounded live queue.
     */
    public static class Session implements AutoCloseable
    {
        private final DecodeMessageViewService mService;
        private final Producer mProducer;
        private final ArrayBlockingQueue<MessageView> mQueue = new ArrayBlockingQueue<>(LIVE_QUEUE_SIZE);
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final AtomicLong mDroppedMessages = new AtomicLong();

        private Session(DecodeMessageViewService service, Producer producer)
        {
            mService = service;
            mProducer = producer;
        }

        public Scope getScope()
        {
            return mProducer.mScope;
        }

        /**
         * Requests an immediate worker-side rebind check.
         *
         * @return true when the producer generation has changed since this session was opened
         */
        public boolean refresh()
        {
            mProducer.requestRefresh();
            mService.mWakeup.release();
            return mProducer.mGeneration > 0;
        }

        public boolean isBound()
        {
            return mProducer.mBinding != null && !mClosed.get() && !mService.mClosed.get();
        }

        public long generation()
        {
            return mProducer.mGeneration;
        }

        /**
         * Returns the worker-owned, newest-first immutable cache.  It never locks decoder history.
         */
        public List<MessageView> snapshot()
        {
            return !mClosed.get() && !mService.mClosed.get() ? mProducer.mPublishedHistory : List.of();
        }

        /**
         * Monotonic count of raw ingress and this session's bounded output drops.
         */
        public long droppedCount()
        {
            return mProducer.mDroppedObservations.get() + mDroppedMessages.get();
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
        private final BoundedMpscPairQueue<Object,IMessage> mIngress =
            new BoundedMpscPairQueue<>(INGRESS_QUEUE_SIZE);
        private final CopyOnWriteArrayList<Session> mSessions = new CopyOnWriteArrayList<>();
        private final LinkedHashMap<String,MessageView> mHistory = new LinkedHashMap<>();
        private final AtomicLong mDroppedObservations = new AtomicLong();
        private volatile Binding mBinding;
        private volatile List<MessageView> mPublishedHistory = List.of();
        private volatile long mGeneration;
        private volatile long mNextRefreshNanos;
        private volatile boolean mRetirementRequested;

        private Producer(Scope scope)
        {
            mScope = scope;
        }

        private void add(Session session)
        {
            mSessions.addIfAbsent(session);
            mRetirementRequested = false;
            requestRefresh();
        }

        private void remove(Session session)
        {
            mSessions.remove(session);
        }

        private boolean hasSessions()
        {
            return !mSessions.isEmpty();
        }

        private void requestRetirement()
        {
            mRetirementRequested = true;
        }

        private void cancelRetirement()
        {
            mRetirementRequested = false;
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
            MessageHistory nextHistory = mHistoryResolver.resolve(mScope);

            if(mClosed.get())
            {
                return;
            }

            Binding current = mBinding;

            if((current == null && nextHistory == null) ||
                (current != null && current.mHistory == nextHistory))
            {
                return;
            }

            if(current != null)
            {
                current.mHistory.removeListener(current.mListener);
            }

            mBinding = null;
            mIngress.clear();
            mHistory.clear();
            mPublishedHistory = List.of();
            mGeneration++;

            for(Session session: mSessions)
            {
                session.reset();
            }

            if(nextHistory != null)
            {
                Object token = new Object();
                Listener<IMessage> listener = message -> receive(token, message);
                Binding next = new Binding(nextHistory, listener, token);
                mBinding = next;
                nextHistory.addListener(listener);
                seed(nextHistory.getItems());
            }
        }

        private void receive(Object token, IMessage message)
        {
            Binding binding = mBinding;

            if(message == null || message instanceof StuffBitsMessage || binding == null ||
                binding.mToken != token || mRetirementRequested || mClosed.get())
            {
                return;
            }

            if(!mIngress.offer(token, message))
            {
                mDroppedObservations.incrementAndGet();
            }
        }

        private void seed(List<IMessage> messages)
        {
            if(messages == null)
            {
                return;
            }

            for(IMessage message: messages)
            {
                MessageView projected = view(message);

                if(projected != null)
                {
                    remember(projected);
                }
            }

            publishHistory();
        }

        private void drain()
        {
            List<MessageView> batch = new ArrayList<>();

            for(int count = 0; count < MAXIMUM_DRAIN_PER_PRODUCER; count++)
            {
                BoundedMpscPairQueue.Entry<Object,IMessage> observation = mIngress.poll();

                if(observation == null)
                {
                    break;
                }

                Binding binding = mBinding;

                if(binding == null || binding.mToken != observation.first())
                {
                    continue;
                }

                IMessage message = observation.second();

                MessageView projected = view(message);

                if(projected == null)
                {
                    continue;
                }

                remember(projected);
                batch.add(projected);
            }

            if(!batch.isEmpty())
            {
                //Publish authoritative cache before any live item can trigger a client's resync read.
                publishHistory();

                for(MessageView projected: batch)
                {
                    for(Session session: mSessions)
                    {
                        session.publish(projected);
                    }
                }
            }
        }

        private void remember(MessageView view)
        {
            mHistory.remove(view.messageId());
            mHistory.put(view.messageId(), view);

            while(mHistory.size() > SHARED_HISTORY_SIZE)
            {
                mHistory.remove(mHistory.keySet().iterator().next());
            }
        }

        private void publishHistory()
        {
            List<MessageView> newestFirst = new ArrayList<>(mHistory.size());
            List<MessageView> oldestFirst = new ArrayList<>(mHistory.values());

            for(int x = oldestFirst.size() - 1; x >= 0; x--)
            {
                newestFirst.add(oldestFirst.get(x));
            }

            mPublishedHistory = List.copyOf(newestFirst);
        }

        private void detach()
        {
            Binding binding = mBinding;
            mBinding = null;
            mGeneration++;

            if(binding != null)
            {
                binding.mHistory.removeListener(binding.mListener);
            }

            mIngress.clear();
            mHistory.clear();
            mPublishedHistory = List.of();

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
        return new MessageView(messageId(message, timestamp), timestamp, protocol(message), timeslot(message),
            valid(message), text(message));
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

    private static String protocol(IMessage message)
    {
        try
        {
            Protocol protocol = message.getProtocol();
            return protocol != null ? bounded(protocol.toString(), PROTOCOL_MAXIMUM_LENGTH) : "Unknown";
        }
        catch(RuntimeException _)
        {
            return "Unknown";
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

    private record Binding(MessageHistory mHistory, Listener<IMessage> mListener, Object mToken)
    {
    }
}
