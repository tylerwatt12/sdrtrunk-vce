/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.message;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.controller.channel.DecodeEventSource;
import io.github.dsheirer.channel.metadata.activity.ConventionalLiveSources;
import io.github.dsheirer.filter.FilterCatalog;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.util.concurrent.BoundedMpscPairQueue;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demand-owned, live-only decoder-message relay for the web interface.
 *
 * <p>Exact viewers and the Conventional aggregate share one listener per configured-frequency source. A decoder
 * callback performs only a bounded, nonblocking reference offer. Chain validation, message classification,
 * {@code toString()} projection, and per-session fan-out run on the observer worker. The service retains no message
 * history and provides no replay.</p>
 */
public class DecodeMessageViewService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DecodeMessageViewService.class);
    static final int LIVE_QUEUE_SIZE = 256;
    static final int INGRESS_QUEUE_SIZE = 1_024;
    private static final int MAXIMUM_DRAIN_PER_PRODUCER = 64;
    private static final int TEXT_MAXIMUM_LENGTH = 2_048;
    private static final int PROTOCOL_MAXIMUM_LENGTH = 64;
    private static final long REBIND_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long MAINTENANCE_INTERVAL_MILLISECONDS = 10;
    private static final long DEFAULT_CLOSE_TIMEOUT_MILLISECONDS = 2_000;
    private final SourceResolver mSourceResolver;
    private final Supplier<List<ConventionalLiveSources.Source>> mConventionalSources;
    private final Map<Scope,Producer> mProducers = new HashMap<>();
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(
        new ObserverThreadFactory("sdrtrunk decode message views"));
    private final Semaphore mWakeup = new Semaphore(0);
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final long mCloseTimeoutMilliseconds;
    private final AtomicLong mAudienceEpoch = new AtomicLong();
    private final AtomicLong mBindingSequence = new AtomicLong();
    private int mDrainStart;
    private volatile Membership mMembership = new Membership(List.of(), Set.of());

    /** Used only by observation and stream workers; the decoder callback never reads or builds membership. */
    private Membership currentMembership()
    {
        List<ConventionalLiveSources.Source> sources = mConventionalSources.get();
        sources = sources != null ? sources : List.of();
        Membership current = mMembership;

        if(sources != current.sources())
        {
            Set<SourceKey> keys = sources.equals(current.sources()) ? current.keys() : Set.copyOf(sources.stream()
                .map(source -> new SourceKey(source.configurationId(), source.frequencyHz())).toList());
            current = new Membership(sources, keys);
            mMembership = current;
        }

        return current;
    }

    /** Constructs a service that binds directly to the exact active processing chain selected by each scope. */
    public DecodeMessageViewService(ChannelProcessingManager channelProcessingManager)
    {
        this(channelProcessingManager, List::of);
    }

    /** Aggregate membership comes from the same immutable Conventional activity snapshot shown by Live. */
    public DecodeMessageViewService(ChannelProcessingManager channelProcessingManager,
        Supplier<List<ConventionalLiveSources.Source>> conventionalSources)
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
        mConventionalSources = Objects.requireNonNull(conventionalSources, "conventionalSources cannot be null");
        mCloseTimeoutMilliseconds = DEFAULT_CLOSE_TIMEOUT_MILLISECONDS;
        startWorker();
    }

    DecodeMessageViewService(SourceResolver sourceResolver)
    {
        this(sourceResolver, DEFAULT_CLOSE_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    DecodeMessageViewService(SourceResolver sourceResolver, long closeTimeout, TimeUnit unit)
    {
        this(sourceResolver, List::of, closeTimeout, unit);
    }

    DecodeMessageViewService(SourceResolver sourceResolver,
        Supplier<List<ConventionalLiveSources.Source>> conventionalSources)
    {
        this(sourceResolver, conventionalSources, DEFAULT_CLOSE_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    DecodeMessageViewService(SourceResolver sourceResolver,
        Supplier<List<ConventionalLiveSources.Source>> conventionalSources, long closeTimeout, TimeUnit unit)
    {
        mSourceResolver = Objects.requireNonNull(sourceResolver, "sourceResolver cannot be null");
        mConventionalSources = Objects.requireNonNull(conventionalSources, "conventionalSources cannot be null");
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
            Session session = producer.openSession(mAudienceEpoch.incrementAndGet());

            //One immutable callback stamp per source records every viewer's live edge, including aggregate viewers.
            for(Producer active: mProducers.values())
            {
                active.updateAudienceStamp();
            }
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

            int start = producers.isEmpty() ? 0 : Math.floorMod(mDrainStart++, producers.size());

            for(int index = 0; index < producers.size(); index++)
            {
                Producer producer = producers.get((start + index) % producers.size());
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

        default String channelName()
        {
            return "";
        }

        /** Built on the observer worker when this source becomes the active binding. */
        default MessageFilterCatalog.Classifier messageFilterClassifier()
        {
            return MessageFilterCatalog.fallback();
        }
    }

    public enum Mode
    {
        CHANNEL, CONVENTIONAL
    }

    /** Exact active-channel selection or the authoritative running Conventional membership. */
    public record Scope(String configurationId, long frequencyHz, Mode mode)
    {
        public Scope(String configurationId, long frequencyHz)
        {
            this(configurationId, frequencyHz, Mode.CHANNEL);
        }

        public static Scope conventional()
        {
            return new Scope(null, 0, Mode.CONVENTIONAL);
        }

        public boolean aggregate()
        {
            return mode == Mode.CONVENTIONAL;
        }

        public Scope
        {
            mode = Objects.requireNonNull(mode, "mode cannot be null");

            if(mode == Mode.CONVENTIONAL)
            {
                if(configurationId != null || frequencyHz != 0)
                {
                    throw new IllegalArgumentException("Conventional scope cannot select a channel or frequency");
                }
            }
            else
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
    }

    /** Safe, bounded fields needed by the web message table and its browser-owned filters. */
    public record MessageView(String messageId, long timestampMs, String protocol, int timeslot, boolean valid,
                              String filterKey, String filterLabel, String text, String configurationId,
                              String channelName, long frequencyHz, @JsonIgnore long sourceGeneration,
                              @JsonIgnore long sourceBindingSequence)
    {
        public MessageView(String messageId, long timestampMs, String protocol, int timeslot, boolean valid,
                           String filterKey, String filterLabel, String text, String configurationId,
                           String channelName, long frequencyHz, long sourceGeneration)
        {
            this(messageId, timestampMs, protocol, timeslot, valid, filterKey, filterLabel, text, configurationId,
                channelName, frequencyHz, sourceGeneration, 0);
        }

        public MessageView(String messageId, long timestampMs, String protocol, int timeslot, boolean valid,
                           String filterKey, String filterLabel, String text, String configurationId,
                           String channelName, long frequencyHz)
        {
            this(messageId, timestampMs, protocol, timeslot, valid, filterKey, filterLabel, text, configurationId,
                channelName, frequencyHz, 0);
        }

        public MessageView(String messageId, long timestampMs, String protocol, int timeslot, boolean valid,
                           String filterKey, String filterLabel, String text)
        {
            this(messageId, timestampMs, protocol, timeslot, valid, filterKey, filterLabel, text, "", "", 0);
        }
    }

    /** One coherent lifecycle snapshot used for each browser source-change event. */
    public record SourceState(long generation, boolean bound, String configurationId, long frequencyHz,
                              FilterCatalog filterCatalog, boolean aggregate)
    {
        public SourceState(long generation, boolean bound, String configurationId, long frequencyHz,
                           FilterCatalog filterCatalog)
        {
            this(generation, bound, configurationId, frequencyHz, filterCatalog, false);
        }
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
            return sourceState().bound();
        }

        public long generation()
        {
            return sourceState().generation();
        }

        public FilterCatalog filterCatalog()
        {
            return sourceState().filterCatalog();
        }

        public SourceState sourceState()
        {
            return mProducer.sourceState(!mClosed.get() && !mService.mClosed.get());
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

            long deadline = System.nanoTime() + unit.toNanos(timeout);

            while(!mClosed.get() && !mService.mClosed.get())
            {
                long remaining = Math.max(0, deadline - System.nanoTime());
                MessageView view = timeout == 0 ? mQueue.poll() : mQueue.poll(remaining, TimeUnit.NANOSECONDS);

                if(view == null)
                {
                    return null;
                }

                if(isCurrent(view))
                {
                    return view;
                }
            }

            return null;
        }

        /** Recheck a polled row immediately before stream encoding; observer membership may change in between. */
        public boolean isCurrent(MessageView view)
        {
            if(view == null || mClosed.get() || mService.mClosed.get())
            {
                return false;
            }

            SourceState state = sourceState();

            if(view.sourceGeneration() != state.generation() || !state.bound())
            {
                return false;
            }

            if(!mProducer.mScope.aggregate())
            {
                return mProducer.isCurrentBinding(view);
            }

            if(mService.currentMembership().keys().contains(new SourceKey(view.configurationId(), view.frequencyHz())))
            {
                for(Member member: mProducer.mMembers)
                {
                    Scope scope = member.producer().mScope;

                    if(scope.configurationId().equals(view.configurationId()) && scope.frequencyHz() == view.frequencyHz())
                    {
                        return member.producer().isCurrentBinding(view);
                    }
                }
            }

            return false;
        }

        int queuedCount()
        {
            return mQueue.size();
        }

        private void publish(MessageView view)
        {
            if(mClosed.get() || mService.mClosed.get())
            {
                return;
            }

            if(!mQueue.offer(view))
            {
                boolean removed = false;

                if(mProducer.mScope.aggregate())
                {
                    //A busy decoder should replace its own stale diagnostics before displacing a quiet channel.
                    //This bounded scan runs on the observer worker, never on the decoder callback.
                    for(MessageView queued: mQueue)
                    {
                        if(queued.configurationId().equals(view.configurationId()) &&
                            queued.frequencyHz() == view.frequencyHz() && mQueue.remove(queued))
                        {
                            removed = true;
                            break;
                        }
                    }
                }

                if(removed || mQueue.poll() != null)
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
        private volatile Producer mAggregate;
        private final AtomicLong mDroppedObservations = new AtomicLong();
        private volatile Binding mBinding;
        private volatile IngressStamp mIngressStamp;
        private volatile long mGeneration;
        private volatile long mNextRefreshNanos;
        private volatile boolean mRetirementRequested;
        private volatile List<Member> mMembers = List.of();
        private volatile SourceState mAggregateState;
        private List<MemberState> mMemberStates = List.of();
        private final Map<Producer,Long> mMemberDropCounts = new HashMap<>();
        private final Map<Producer,Long> mPublishedGenerations = new HashMap<>();
        private List<ConventionalLiveSources.Source> mAggregateSources = List.of();

        private Producer(Scope scope)
        {
            mScope = scope;
            mAggregateState = new SourceState(0, false, scope.configurationId(), scope.frequencyHz(), null,
                scope.aggregate());
        }

        /**
         * Opens a session at a new live edge. Lifecycle updates are serialized with source binding changes, while the
         * decoder callback only reads the resulting immutable stamp.
         */
        private synchronized Session openSession(long audienceEpoch)
        {
            if(mScope.aggregate())
            {
                accountAggregateDrops();
            }

            Session session = new Session(DecodeMessageViewService.this, this, audienceEpoch);
            mSessions.addIfAbsent(session);
            mRetirementRequested = false;
            Binding binding = mBinding;
            mIngressStamp = binding != null ? new IngressStamp(binding.mToken, audienceEpoch) : null;
            requestRefresh();
            return session;
        }

        private synchronized void updateAudienceStamp()
        {
            Binding binding = mBinding;
            mIngressStamp = binding != null ? new IngressStamp(binding.mToken, mAudienceEpoch.get()) : null;
        }

        private synchronized void remove(Session session)
        {
            mSessions.remove(session);

            if(!hasDemand())
            {
                mRetirementRequested = true;
            }
        }

        private boolean hasSessions()
        {
            return !mSessions.isEmpty();
        }

        private boolean hasDemand()
        {
            if(hasSessions())
            {
                return true;
            }

            Producer aggregate = mAggregate;
            return aggregate != null && aggregate.hasSessions();
        }

        private synchronized void addAggregate(Producer aggregate)
        {
            mAggregate = aggregate;
            mRetirementRequested = false;
            updateAudienceStamp();
            requestRefresh();
        }

        private synchronized void removeAggregate(Producer aggregate)
        {
            if(mAggregate == aggregate)
            {
                mAggregate = null;
            }

            mRetirementRequested = !hasDemand();
        }

        private boolean shouldRetire()
        {
            return mRetirementRequested && !hasDemand();
        }

        private void requestRefresh()
        {
            mNextRefreshNanos = 0;
        }

        private synchronized SourceState sourceState(boolean sessionActive)
        {
            if(mScope.aggregate())
            {
                SourceState state = mAggregateState;
                return sessionActive ? state : new SourceState(state.generation(), false, null, 0, null, true);
            }

            Binding binding = mBinding;
            boolean bound = sessionActive && binding != null && binding.mSource.matches(mScope);
            return new SourceState(mGeneration, bound, mScope.configurationId(), mScope.frequencyHz(),
                bound ? binding.mClassifier.catalog() : null);
        }

        private boolean isCurrentBinding(MessageView view)
        {
            Binding binding = mBinding;
            return binding != null && binding.mSequence == view.sourceBindingSequence() &&
                binding.mSource.matches(mScope);
        }

        private void refreshIfDue()
        {
            long now = System.nanoTime();

            if(now < mNextRefreshNanos)
            {
                return;
            }

            mNextRefreshNanos = now + REBIND_INTERVAL_NANOS;

            if(mScope.aggregate())
            {
                refreshAggregate(currentMembership().sources());
                return;
            }

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

                for(Session session: mSessions)
                {
                    session.reset();
                }

                if(nextSource != null)
                {
                    Object token = new Object();
                    Listener<IMessage> listener = message -> receive(token, message);
                    MessageFilterCatalog.Classifier classifier;

                    try
                    {
                        classifier = nextSource.messageFilterClassifier();
                    }
                    catch(RuntimeException exception)
                    {
                        mLog.warn("Unable to build decoder-message filter catalog for {}; using fallback",
                            mScope, exception);
                        classifier = MessageFilterCatalog.fallback();
                    }

                    Binding next = new Binding(nextSource, listener, token, classifier,
                        mBindingSequence.incrementAndGet());
                    mBinding = next;
                    mIngressStamp = new IngressStamp(token, mAudienceEpoch.get());

                    try
                    {
                        nextSource.addListener(listener);

                        if(!nextSource.matches(mScope))
                        {
                            nextSource.removeListener(listener);
                            mBinding = null;
                            mIngressStamp = null;
                        }
                    }
                    catch(RuntimeException exception)
                    {
                        mBinding = null;
                        mIngressStamp = null;
                        mGeneration++;
                        throw exception;
                    }
                }

                //Publish the generation only after the binding and catalog snapshot are complete.  A reader that
                //observes this volatile write cannot see the new generation with the preceding unbound state.
                mGeneration++;
            }
        }

        private void receive(Object token, IMessage message)
        {
            Binding binding = mBinding;
            IngressStamp stamp = mIngressStamp;

            if(message == null || message instanceof StuffBitsMessage || binding == null ||
                binding.mToken != token || stamp == null || stamp.mBindingToken != token ||
                !hasDemand() || mClosed.get())
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
            if(mScope.aggregate())
            {
                refreshAggregateMembership();
                refreshAggregateState();
                return;
            }

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

                MessageView projected = view(observation.second(), binding.mClassifier, mScope,
                    binding.mSource.channelName(), binding.mSequence, mGeneration);

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

                    Producer aggregate = mAggregate;

                    if(aggregate != null)
                    {
                        aggregate.publishAggregate(this, stamp.mAudienceEpoch, projected);
                    }
                }
            }
        }

        private boolean shouldAbandonIngress()
        {
            return mClosed.get() || !hasDemand();
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

            Producer aggregate = mAggregate;
            return aggregate != null && aggregate.hasAudience(audienceEpoch);
        }

        private synchronized void detach()
        {
            for(Member member: mMembers)
            {
                member.producer().removeAggregate(this);
            }

            mMembers = List.of();
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
            mAggregate = null;
            mAggregateState = new SourceState(mGeneration, false, null, 0, null, mScope.aggregate());
        }

        private void refreshAggregateMembership()
        {
            List<ConventionalLiveSources.Source> sources = currentMembership().sources();

            if(!sources.equals(mAggregateSources))
            {
                refreshAggregate(sources);
            }
        }

        private void refreshAggregate(List<ConventionalLiveSources.Source> sources)
        {
            mAggregateSources = sources;
            Map<Scope,Member> previous = new HashMap<>();

            for(Member member: mMembers)
            {
                previous.put(member.producer().mScope, member);
            }

            Map<Scope,Member> next = new HashMap<>();

            if(sources != null && !shouldAbandonIngress())
            {
                for(ConventionalLiveSources.Source source: sources)
                {
                    Scope scope = new Scope(source.configurationId(), source.frequencyHz());

                    synchronized(mProducers)
                    {
                        if(mClosed.get() || !hasSessions())
                        {
                            break;
                        }

                        Producer producer = mProducers.computeIfAbsent(scope, Producer::new);
                        producer.addAggregate(this);
                        next.put(scope, new Member(producer, bounded(source.channelName(), TEXT_MAXIMUM_LENGTH)));
                    }
                }
            }

            for(Map.Entry<Scope,Member> entry: previous.entrySet())
            {
                if(!next.containsKey(entry.getKey()))
                {
                    entry.getValue().producer().removeAggregate(this);
                }
            }

            List<Member> ordered = new ArrayList<>(next.values());
            ordered.sort(Comparator.comparing((Member member) -> member.producer().mScope.configurationId())
                .thenComparingLong(member -> member.producer().mScope.frequencyHz()));
            synchronized(this)
            {
                accountAggregateDrops();
                mMembers = List.copyOf(ordered);
                mMemberDropCounts.keySet().retainAll(next.values().stream().map(Member::producer).toList());

                for(Member member: mMembers)
                {
                    mMemberDropCounts.putIfAbsent(member.producer(), member.producer().mDroppedObservations.get());
                }
            }

            refreshAggregateState();
        }

        private void refreshAggregateState()
        {
            accountAggregateDrops();
            List<MemberState> states = new ArrayList<>();
            List<MessageFilterCatalog.CatalogSource> catalogs = new ArrayList<>();

            for(Member member: mMembers)
            {
                Producer producer = member.producer();
                SourceState state = producer.sourceState(hasSessions());
                states.add(new MemberState(producer.mScope, state.generation(), state.bound(), member.channelName()));

                if(state.bound() && state.filterCatalog() != null)
                {
                    catalogs.add(new MessageFilterCatalog.CatalogSource(filterPrefix(producer.mScope),
                        member.channelName(), state.filterCatalog()));
                }
            }

            if(!states.equals(mMemberStates))
            {
                mMemberStates = List.copyOf(states);
                mPublishedGenerations.clear();

                for(Member member: mMembers)
                {
                    mPublishedGenerations.put(member.producer(), member.producer().mGeneration);
                }

                for(Session session: mSessions)
                {
                    session.reset();
                }

                mGeneration++;
                mAggregateState = new SourceState(mGeneration, !catalogs.isEmpty(), null, 0,
                    !catalogs.isEmpty() ? MessageFilterCatalog.aggregate(catalogs) : null, true);
            }
        }

        private synchronized void accountAggregateDrops()
        {
            for(Member member: mMembers)
            {
                Producer producer = member.producer();
                long dropped = producer.mDroppedObservations.get();
                Long previous = mMemberDropCounts.put(producer, dropped);

                if(previous != null && dropped > previous)
                {
                    mDroppedObservations.addAndGet(dropped - previous);
                }
            }
        }

        private void publishAggregate(Producer producer, long audienceEpoch, MessageView view)
        {
            if(!hasSessions() || mClosed.get())
            {
                return;
            }

            //Honor a membership refresh requested while another source's projection was in flight.
            refreshIfDue();
            refreshAggregateMembership();

            if(!Objects.equals(mPublishedGenerations.get(producer), producer.mGeneration))
            {
                refreshAggregateState();
            }

            for(Member member: mMembers)
            {
                if(member.producer() == producer)
                {
                    String key = view.filterKey().isEmpty() ? "" : filterPrefix(producer.mScope) + view.filterKey();
                    MessageView aggregateView = new MessageView(view.messageId(), view.timestampMs(), view.protocol(),
                        view.timeslot(), view.valid(), key, view.filterLabel(), view.text(), view.configurationId(),
                        member.channelName(), view.frequencyHz(), mGeneration, view.sourceBindingSequence());

                    for(Session session: mSessions)
                    {
                        if(session.accepts(audienceEpoch))
                        {
                            session.publish(aggregateView);
                        }
                    }

                    return;
                }
            }
        }
    }

    private static String filterPrefix(Scope scope)
    {
        return "source/" + scope.configurationId() + "/" + scope.frequencyHz() + "/";
    }

    private static MessageView view(IMessage message, MessageFilterCatalog.Classifier classifier, Scope scope,
                                    String channelName, long bindingSequence, long sourceGeneration)
    {
        if(message == null || message instanceof StuffBitsMessage)
        {
            return null;
        }

        long timestamp = timestamp(message);
        ProtocolInfo protocol = protocol(message);
        MessageFilterCatalog.Match match = classifier.classify(message);
        return new MessageView(scope.configurationId() + "/" + scope.frequencyHz() + "/" + bindingSequence + "/" +
            messageId(message, timestamp), timestamp, protocol.display(), timeslot(message),
            valid(message), match != null ? match.filterKey() : "", match != null ? match.filterLabel() : "Unknown",
            text(message), scope.configurationId(), bounded(channelName, TEXT_MAXIMUM_LENGTH), scope.frequencyHz(),
            sourceGeneration, bindingSequence);
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
            return protocol != null ? new ProtocolInfo(bounded(protocol.toString(), PROTOCOL_MAXIMUM_LENGTH)) :
                ProtocolInfo.UNKNOWN;
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

    record ChainMessageSource(ProcessingChain mChain, DecodeEventSource mOrigin) implements MessageSource
    {
        ChainMessageSource(ProcessingChain chain)
        {
            this(chain, chain.getDecodeEventSource());
        }

        @Override
        public boolean equals(Object other)
        {
            return other instanceof ChainMessageSource source && mChain == source.mChain && mOrigin == source.mOrigin;
        }

        @Override
        public int hashCode()
        {
            return 31 * System.identityHashCode(mChain) + System.identityHashCode(mOrigin);
        }

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
            return mOrigin != null && mChain.getDecodeEventSource() == mOrigin && channel != null && source != null &&
                scope.configurationId().equals(channel.getConfigurationId()) &&
                source.getFrequency() == scope.frequencyHz();
        }

        @Override
        public String channelName()
        {
            Channel channel = mChain.getCurrentChannel();
            return channel != null && channel.getName() != null ? channel.getName() : "";
        }

        @Override
        public MessageFilterCatalog.Classifier messageFilterClassifier()
        {
            Channel channel = mChain.getCurrentChannel();
            int[] timeslots = channel != null && channel.getDecodeConfiguration() != null ?
                channel.getDecodeConfiguration().getTimeslots() : new int[0];
            return MessageFilterCatalog.fromModules(mChain.getModules(), timeslots);
        }
    }

    private record Binding(MessageSource mSource, Listener<IMessage> mListener, Object mToken,
                           MessageFilterCatalog.Classifier mClassifier, long mSequence)
    {
    }

    private record Member(Producer producer, String channelName)
    {
    }

    private record MemberState(Scope scope, long generation, boolean bound, String channelName)
    {
    }

    private record SourceKey(String configurationId, long frequencyHz)
    {
    }

    private record Membership(List<ConventionalLiveSources.Source> sources, Set<SourceKey> keys)
    {
    }

    private record IngressStamp(Object mBindingToken, long mAudienceEpoch)
    {
    }

    private record ProtocolInfo(String display)
    {
        private static final ProtocolInfo UNKNOWN = new ProtocolInfo("Unknown");
    }
}
