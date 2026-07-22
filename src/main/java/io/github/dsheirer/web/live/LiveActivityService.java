/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.live;

import io.github.dsheirer.application.service.LiveContext;
import io.github.dsheirer.application.service.LiveContextResolver;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.MessageHistory;
import io.github.dsheirer.message.StuffBitsMessage;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.event.DecodeEventHistory;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.stats.StatsLiveEventHub;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demand-driven, memory-only Events and Messages service for the selected Live context.
 *
 * <p>Decoder callbacks perform only a shallow immutable capture and a bounded lock-free queue offer.  Text rendering,
 * filtering, DTO construction, JSON serialization, and network delivery run elsewhere.  Contexts detach after a
 * short idle period, all histories are bounded, and this service has no database dependency.</p>
 */
public final class LiveActivityService implements AutoCloseable
{
    public enum FeedType
    {
        EVENTS("events"),
        MESSAGES("messages");

        private final String mPath;

        FeedType(String path)
        {
            mPath = path;
        }

        public String path()
        {
            return mPath;
        }

        public static FeedType fromPath(String path)
        {
            for(FeedType type: values())
            {
                if(type.mPath.equals(path))
                {
                    return type;
                }
            }

            throw new IllegalArgumentException("Unsupported live activity feed: " + path);
        }
    }

    private static final Logger mLog = LoggerFactory.getLogger(LiveActivityService.class);
    private static final int MAXIMUM_CONTEXTS = 8;
    private static final int MAXIMUM_ROWS = 2_000;
    private static final int MAXIMUM_PENDING_CAPTURES = 2_048;
    private static final int MAXIMUM_BATCH_ROWS = 100;
    private static final int MAXIMUM_SUBSCRIBERS_PER_FEED = 8;
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 64;
    private static final int REPLAY_CAPACITY = 64;
    private static final long WORK_INTERVAL_MILLISECONDS = 50;
    private static final long BINDING_REFRESH_MILLISECONDS = 250;
    private static final Duration IDLE_RETENTION = Duration.ofSeconds(30);

    private final LiveContextResolver mContextResolver;
    private final Map<String,ContextFeed> mFeeds = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor mWorker;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private ScheduledFuture<?> mWorkTask;

    public LiveActivityService(LiveContextResolver contextResolver)
    {
        mContextResolver = Objects.requireNonNull(contextResolver, "Live context resolver cannot be null");
        mWorker = new ScheduledThreadPoolExecutor(1, runnable ->
        {
            Thread thread = new Thread(runnable, "sdrtrunk live activity worker");
            thread.setDaemon(true);
            return thread;
        });
        mWorker.setRemoveOnCancelPolicy(true);
        mWorker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        mWorker.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public void start()
    {
        if(mClosed.get())
        {
            throw new IllegalStateException("Live activity service is closed");
        }

        if(mRunning.compareAndSet(false, true))
        {
            mContextResolver.start();
            mWorkTask = mWorker.scheduleWithFixedDelay(this::workSafely, 0, WORK_INTERVAL_MILLISECONDS,
                TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Returns a bounded point-in-time snapshot without retaining a permanent reader.
     */
    public Optional<FeedSnapshot> snapshot(String selectionId, FeedType type)
    {
        ContextFeed feed = getOrCreate(selectionId);

        if(feed == null)
        {
            return Optional.empty();
        }

        feed.touch(type);
        feed.refreshBinding(true);
        return Optional.of(feed.snapshot(type));
    }

    /**
     * Atomically opens a stream and captures its authoritative snapshot/high-water state.
     */
    public Optional<OpenStream> openStream(String selectionId, FeedType type, Long lastEventId)
    {
        ContextFeed feed = getOrCreate(selectionId);

        if(feed == null)
        {
            return Optional.empty();
        }

        return Optional.ofNullable(feed.open(type, lastEventId));
    }

    private ContextFeed getOrCreate(String selectionId)
    {
        if(!mRunning.get() || selectionId == null || selectionId.isBlank() || selectionId.length() > 96)
        {
            return null;
        }

        ContextFeed current = mFeeds.get(selectionId);

        if(current != null)
        {
            return current;
        }

        Optional<LiveContext> resolved = mContextResolver.resolve(selectionId);

        if(resolved.isEmpty())
        {
            return null;
        }

        synchronized(mFeeds)
        {
            current = mFeeds.get(selectionId);

            if(current != null)
            {
                return current;
            }

            evictIdleLocked(System.nanoTime());

            if(mFeeds.size() >= MAXIMUM_CONTEXTS)
            {
                return null;
            }

            ContextFeed created = new ContextFeed(resolved.get());
            mFeeds.put(selectionId, created);
            return created;
        }
    }

    private void workSafely()
    {
        if(!mRunning.get())
        {
            return;
        }

        try
        {
            long now = System.nanoTime();

            for(ContextFeed feed: List.copyOf(mFeeds.values()))
            {
                feed.work(now);
            }

            synchronized(mFeeds)
            {
                evictIdleLocked(now);
            }
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Transient Live activity worker failed", exception);
        }
    }

    private void evictIdleLocked(long now)
    {
        for(Map.Entry<String,ContextFeed> entry: List.copyOf(mFeeds.entrySet()))
        {
            ContextFeed feed = entry.getValue();

            if(feed.isEvictable(now) && mFeeds.remove(entry.getKey(), feed))
            {
                feed.close();
            }
        }
    }

    public int contextCount()
    {
        return mFeeds.size();
    }

    public void stop()
    {
        if(!mRunning.compareAndSet(true, false))
        {
            return;
        }

        ScheduledFuture<?> workTask = mWorkTask;
        mWorkTask = null;

        if(workTask != null)
        {
            workTask.cancel(false);
        }

        for(ContextFeed feed: List.copyOf(mFeeds.values()))
        {
            feed.close();
        }

        mFeeds.clear();
        mContextResolver.stop();
    }

    @Override
    public void close()
    {
        if(!mClosed.compareAndSet(false, true))
        {
            return;
        }

        stop();
        mWorker.shutdownNow();

        try
        {
            if(!mWorker.awaitTermination(5, TimeUnit.SECONDS))
            {
                mLog.warn("Live activity worker did not stop within five seconds");
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }

    public record FeedSnapshot(String streamId, Map<String,Object> context, String status, long generation, long sequence,
                               List<Map<String,String>> filters, List<?> rows)
    {
        public FeedSnapshot
        {
            context = Map.copyOf(context);
            filters = List.copyOf(filters);
            rows = List.copyOf(rows);
        }
    }

    public final class OpenStream implements AutoCloseable
    {
        private final ContextFeed mFeed;
        private final FeedType mType;
        private final StatsLiveEventHub.Subscription mSubscription;
        private final FeedSnapshot mSnapshot;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private OpenStream(ContextFeed feed, FeedType type, StatsLiveEventHub.Subscription subscription,
                           FeedSnapshot snapshot)
        {
            mFeed = feed;
            mType = type;
            mSubscription = subscription;
            mSnapshot = snapshot;
        }

        public StatsLiveEventHub.Subscription subscription()
        {
            return mSubscription;
        }

        public FeedSnapshot snapshot()
        {
            return mSnapshot;
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mSubscription.close();
                mFeed.readerClosed(mType);
            }
        }
    }

    private final class ContextFeed implements AutoCloseable
    {
        private final String mSelectionId;
        private final String mStreamId = UUID.randomUUID().toString();
        private final StatsLiveEventHub mEventHub = new StatsLiveEventHub(MAXIMUM_SUBSCRIBERS_PER_FEED,
            SUBSCRIBER_QUEUE_CAPACITY, REPLAY_CAPACITY);
        private final StatsLiveEventHub mMessageHub = new StatsLiveEventHub(MAXIMUM_SUBSCRIBERS_PER_FEED,
            SUBSCRIBER_QUEUE_CAPACITY, REPLAY_CAPACITY);
        private final BoundedCaptureQueue<EventCapture> mEventCaptures =
            new BoundedCaptureQueue<>(MAXIMUM_PENDING_CAPTURES);
        private final BoundedCaptureQueue<MessageCapture> mMessageCaptures =
            new BoundedCaptureQueue<>(MAXIMUM_PENDING_CAPTURES);
        private final LinkedHashMap<String,LiveDecodeEventDto> mEvents = new LinkedHashMap<>();
        private final LinkedHashMap<String,LiveMessageDto> mMessages = new LinkedHashMap<>();
        private final IdentityHashMap<IDecodeEvent,String> mEventIds = new IdentityHashMap<>();
        private final IdentityHashMap<IMessage,String> mMessageIds = new IdentityHashMap<>();
        private final AtomicInteger mEventReaders = new AtomicInteger();
        private final AtomicInteger mMessageReaders = new AtomicInteger();
        private final AtomicLong mRowSequence = new AtomicLong();
        private Listener<IDecodeEvent> mEventListener;
        private Listener<IMessage> mMessageListener;
        private LiveContext mContext;
        private ProcessingChain mEventChain;
        private ProcessingChain mMessageChain;
        private long mEventBindingRevision;
        private long mMessageBindingRevision;
        private long mGeneration = 1;
        private long mLastBindingRefreshNanos;
        private long mEventLastAccessNanos;
        private long mMessageLastAccessNanos;
        private boolean mClosed;

        private ContextFeed(LiveContext context)
        {
            mContext = context;
            mSelectionId = context.selectionId();
        }

        private synchronized void touch(FeedType type)
        {
            long now = System.nanoTime();

            if(type == FeedType.EVENTS)
            {
                mEventLastAccessNanos = now;
            }
            else
            {
                mMessageLastAccessNanos = now;
            }
        }

        private synchronized OpenStream open(FeedType type, Long lastEventId)
        {
            if(mClosed)
            {
                return null;
            }

            touch(type);
            incrementReaders(type);
            refreshBinding(true);
            StatsLiveEventHub hub = hub(type);
            StatsLiveEventHub.Subscription subscription;

            try
            {
                subscription = lastEventId != null ? hub.subscribe(lastEventId) : hub.subscribe();
            }
            catch(RuntimeException exception)
            {
                decrementReaders(type);
                throw exception;
            }

            if(subscription == null)
            {
                decrementReaders(type);
                return null;
            }

            FeedSnapshot snapshot = snapshotLocked(type, subscription.registrationHighWaterEventId());
            return new OpenStream(this, type, subscription, snapshot);
        }

        private void incrementReaders(FeedType type)
        {
            (type == FeedType.EVENTS ? mEventReaders : mMessageReaders).incrementAndGet();
        }

        private void decrementReaders(FeedType type)
        {
            AtomicInteger readers = type == FeedType.EVENTS ? mEventReaders : mMessageReaders;
            readers.updateAndGet(value -> Math.max(0, value - 1));
        }

        private void readerClosed(FeedType type)
        {
            decrementReaders(type);
            touch(type);
        }

        private StatsLiveEventHub hub(FeedType type)
        {
            return type == FeedType.EVENTS ? mEventHub : mMessageHub;
        }

        private synchronized FeedSnapshot snapshot(FeedType type)
        {
            return snapshotLocked(type, hub(type).highWaterEventId());
        }

        private FeedSnapshot snapshotLocked(FeedType type, long sequence)
        {
            List<?> rows = type == FeedType.EVENTS ? newestFirst(mEvents.values()) : newestFirst(mMessages.values());
            return new FeedSnapshot(mStreamId, contextMap(), status(type), mGeneration, sequence, filters(type), rows);
        }

        private synchronized void work(long now)
        {
            if(mClosed)
            {
                return;
            }

            if(now - mLastBindingRefreshNanos >= TimeUnit.MILLISECONDS.toNanos(BINDING_REFRESH_MILLISECONDS))
            {
                refreshBinding(false);
            }

            processEvents();
            processMessages();
            detachIdle(now);
        }

        private synchronized void refreshBinding(boolean force)
        {
            long now = System.nanoTime();

            if(!force && now - mLastBindingRefreshNanos <
                TimeUnit.MILLISECONDS.toNanos(BINDING_REFRESH_MILLISECONDS))
            {
                return;
            }

            mLastBindingRefreshNanos = now;
            Optional<LiveContext> resolved = mContextResolver.resolve(mSelectionId);
            LiveContext next = resolved.orElse(null);
            ProcessingChain desiredEvent = next != null ? next.eventProcessingChain() : null;
            ProcessingChain desiredMessage = next != null ? next.processingChain() : null;
            desiredEvent = isActive(FeedType.EVENTS, now) ? desiredEvent : null;
            desiredMessage = isActive(FeedType.MESSAGES, now) ? desiredMessage : null;
            boolean eventChanged = desiredEvent != mEventChain;
            boolean messageChanged = desiredMessage != mMessageChain;
            boolean changed = eventChanged || messageChanged;

            if(changed)
            {
                mGeneration++;

                if(eventChanged)
                {
                    mEventCaptures.clear();
                    mEvents.clear();
                    mEventIds.clear();
                }

                if(messageChanged)
                {
                    mMessageCaptures.clear();
                    mMessages.clear();
                    mMessageIds.clear();
                }
            }

            mContext = next;
            bindEvents(desiredEvent);
            bindMessages(desiredMessage);

            if(eventChanged)
            {
                mEventHub.publish(StatsLiveEventHub.RESNAPSHOT_EVENT_NAME,
                    Map.of("reason", "decoder_binding_changed"));
            }

            if(messageChanged)
            {
                mMessageHub.publish(StatsLiveEventHub.RESNAPSHOT_EVENT_NAME,
                    Map.of("reason", "decoder_binding_changed"));
            }
        }

        private boolean isActive(FeedType type, long now)
        {
            int readers = type == FeedType.EVENTS ? mEventReaders.get() : mMessageReaders.get();
            long lastAccess = type == FeedType.EVENTS ? mEventLastAccessNanos : mMessageLastAccessNanos;
            return readers > 0 || lastAccess > 0 && now - lastAccess < IDLE_RETENTION.toNanos();
        }

        private void bindEvents(ProcessingChain desired)
        {
            if(desired == mEventChain)
            {
                return;
            }

            if(mEventChain != null && mEventListener != null)
            {
                mEventChain.getDecodeEventHistory().removeListener(mEventListener);
            }

            long bindingRevision = ++mEventBindingRevision;
            mEventChain = desired;
            mEventListener = null;

            if(desired != null)
            {
                DecodeEventHistory history = desired.getDecodeEventHistory();
                mEventListener = event -> mEventCaptures.offer(EventCapture.from(event, bindingRevision));
                history.addListener(mEventListener);
                history.getItems().forEach(event ->
                    mEventCaptures.offer(EventCapture.from(event, bindingRevision)));
            }
        }

        private void bindMessages(ProcessingChain desired)
        {
            if(desired == mMessageChain)
            {
                return;
            }

            if(mMessageChain != null && mMessageListener != null)
            {
                mMessageChain.getMessageHistory().removeListener(mMessageListener);
            }

            long bindingRevision = ++mMessageBindingRevision;
            mMessageChain = desired;
            mMessageListener = null;

            if(desired != null)
            {
                MessageHistory history = desired.getMessageHistory();
                mMessageListener = message -> mMessageCaptures.offer(MessageCapture.from(message, bindingRevision));
                history.addListener(mMessageListener);
                history.getItems().forEach(message ->
                    mMessageCaptures.offer(MessageCapture.from(message, bindingRevision)));
            }
        }

        private void processEvents()
        {
            if(mEventCaptures.consumeOverflow())
            {
                rebuildEvents();
                mEventHub.publish(StatsLiveEventHub.RESNAPSHOT_EVENT_NAME, Map.of("reason", "capture_overflow"));
            }

            List<LiveDecodeEventDto> upserts = new ArrayList<>();
            List<String> removes = new ArrayList<>();

            for(int count = 0; count < MAXIMUM_BATCH_ROWS; count++)
            {
                EventCapture capture = mEventCaptures.poll();

                if(capture == null)
                {
                    break;
                }

                if(capture.bindingRevision() != mEventBindingRevision)
                {
                    continue;
                }

                String id = mEventIds.get(capture.identity());

                if(id == null)
                {
                    id = nextId("e");
                }

                ChannelActivitySelectionDescriptor selection = mContext != null ? mContext.selection() : null;
                long fallbackFrequency = selection != null && !selection.isSite() ? selection.frequencyHz() : 0;
                LiveDecodeEventDto dto = capture.toDto(id, mGeneration, fallbackFrequency);

                if(dto != null && matchesEvent(dto))
                {
                    mEventIds.putIfAbsent(capture.identity(), id);
                    mEvents.put(id, dto);
                    upserts.add(dto);
                }
            }

            trim(mEvents, mEventIds, removes);

            if(!upserts.isEmpty() || !removes.isEmpty())
            {
                mEventHub.publish("delta", delta(upserts, removes));
            }
        }

        private void processMessages()
        {
            if(mMessageCaptures.consumeOverflow())
            {
                rebuildMessages();
                mMessageHub.publish(StatsLiveEventHub.RESNAPSHOT_EVENT_NAME, Map.of("reason", "capture_overflow"));
            }

            List<LiveMessageDto> upserts = new ArrayList<>();
            List<String> removes = new ArrayList<>();

            for(int count = 0; count < MAXIMUM_BATCH_ROWS; count++)
            {
                MessageCapture capture = mMessageCaptures.poll();

                if(capture == null)
                {
                    break;
                }

                if(capture.bindingRevision() != mMessageBindingRevision)
                {
                    continue;
                }

                if(mMessageIds.containsKey(capture.identity()))
                {
                    continue;
                }

                String id = nextId("m");
                LiveMessageDto dto = capture.toDto(id, mGeneration, mRowSequence.get());

                if(dto != null && matchesMessage(dto))
                {
                    mMessageIds.put(capture.identity(), id);
                    mMessages.put(id, dto);
                    upserts.add(dto);
                }
            }

            trim(mMessages, mMessageIds, removes);

            if(!upserts.isEmpty() || !removes.isEmpty())
            {
                mMessageHub.publish("delta", delta(upserts, removes));
            }
        }

        private void rebuildEvents()
        {
            if(mEventChain == null)
            {
                return;
            }

            for(IDecodeEvent event: mEventChain.getDecodeEventHistory().getItems())
            {
                mEventCaptures.offer(EventCapture.from(event, mEventBindingRevision));
            }
        }

        private void rebuildMessages()
        {
            if(mMessageChain == null)
            {
                return;
            }

            for(IMessage message: mMessageChain.getMessageHistory().getItems())
            {
                mMessageCaptures.offer(MessageCapture.from(message, mMessageBindingRevision));
            }
        }

        private String nextId(String kind)
        {
            return mSelectionId + ":" + mGeneration + ":" + kind + ":" + mRowSequence.incrementAndGet();
        }

        private boolean matchesEvent(LiveDecodeEventDto dto)
        {
            ChannelActivitySelectionDescriptor selection = mContext != null ? mContext.selection() : null;

            if(selection == null || selection.isSite())
            {
                return selection != null;
            }

            return selection.timeslot() == null || Objects.equals(dto.timeslot(), selection.timeslot());
        }

        private boolean matchesMessage(LiveMessageDto dto)
        {
            ChannelActivitySelectionDescriptor selection = mContext != null ? mContext.selection() : null;
            return selection != null && (selection.timeslot() == null ||
                Objects.equals(dto.timeslot(), selection.timeslot()));
        }

        private Map<String,Object> delta(List<?> upserts, List<String> removes)
        {
            return Map.of("generation", mGeneration, "upserts", List.copyOf(upserts),
                "removes", List.copyOf(removes));
        }

        private void detachIdle(long now)
        {
            if(!isActive(FeedType.EVENTS, now))
            {
                bindEvents(null);
            }

            if(!isActive(FeedType.MESSAGES, now))
            {
                bindMessages(null);
            }
        }

        private synchronized boolean isEvictable(long now)
        {
            return !mClosed && mEventReaders.get() == 0 && mMessageReaders.get() == 0 &&
                now - Math.max(mEventLastAccessNanos, mMessageLastAccessNanos) >= IDLE_RETENTION.toNanos();
        }

        private String status(FeedType type)
        {
            if(mContext == null)
            {
                return "ENDED";
            }

            ProcessingChain chain = type == FeedType.EVENTS ? mContext.eventProcessingChain() :
                mContext.processingChain();
            return chain != null ? "ACTIVE" : "REBINDING";
        }

        private Map<String,Object> contextMap()
        {
            ChannelActivitySelectionDescriptor selection = mContext != null ? mContext.selection() : null;

            if(selection == null)
            {
                return Map.of("selectionId", mSelectionId);
            }

            Map<String,Object> context = new LinkedHashMap<>();
            context.put("selectionId", selection.selectionId());
            context.put("tableId", selection.tableId());
            context.put("rowKey", selection.rowKey());
            context.put("scope", selection.scope().name());
            context.put("frequencyHz", selection.frequencyHz());
            putIfPresent(context, "tableTitle", selection.tableTitle());
            putIfPresent(context, "channelName", selection.channelName());
            putIfPresent(context, "timeslot", selection.timeslot());
            putIfPresent(context, "decoder", selection.decoderHint());
            return Collections.unmodifiableMap(context);
        }

        private void putIfPresent(Map<String,Object> values, String key, Object value)
        {
            if(value != null)
            {
                values.put(key, value);
            }
        }

        private List<Map<String,String>> filters(FeedType type)
        {
            if(type == FeedType.EVENTS)
            {
                return List.of(filter("voice", "Voice calls"), filter("protected-voice", "Protected voice calls"),
                    filter("data", "Data calls"), filter("commands", "Commands"),
                    filter("registrations", "Registrations"), filter("other", "Other events"));
            }

            Set<String> categories = new LinkedHashSet<>();
            mMessages.values().forEach(message -> categories.add(message.category()));

            if(categories.isEmpty() && mContext != null && mContext.processingChain() != null)
            {
                Protocol protocol = Optional.ofNullable(mContext.processingChain().getPrimaryDecoder())
                    .map(decoder -> decoder.getDecoderType().getProtocol()).orElse(null);
                categories.add(protocol != null ? protocol.toString() : "Messages");
            }

            return categories.stream().limit(64).map(category -> filter(category, category)).toList();
        }

        private Map<String,String> filter(String id, String label)
        {
            return Map.of("id", id, "label", label);
        }

        @Override
        public synchronized void close()
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;
            bindEvents(null);
            bindMessages(null);
            mEventHub.close();
            mMessageHub.close();
            mEventCaptures.clear();
            mMessageCaptures.clear();
            mEvents.clear();
            mMessages.clear();
            mEventIds.clear();
            mMessageIds.clear();
            mContext = null;
        }
    }

    private static <T> List<T> newestFirst(java.util.Collection<T> rows)
    {
        List<T> copy = new ArrayList<>(rows);
        Collections.reverse(copy);
        return List.copyOf(copy);
    }

    private static <T> void trim(LinkedHashMap<String,T> rows, IdentityHashMap<?,String> identities,
                                 List<String> removes)
    {
        while(rows.size() > MAXIMUM_ROWS)
        {
            String removed = rows.keySet().iterator().next();
            rows.remove(removed);
            identities.values().removeIf(removed::equals);
            removes.add(removed);
        }
    }

    private static final class BoundedCaptureQueue<T>
    {
        private final int mCapacity;
        private final ConcurrentLinkedQueue<T> mQueue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger mSize = new AtomicInteger();
        private final AtomicBoolean mOverflow = new AtomicBoolean();

        private BoundedCaptureQueue(int capacity)
        {
            mCapacity = capacity;
        }

        private void offer(T capture)
        {
            if(capture == null)
            {
                return;
            }

            while(true)
            {
                int size = mSize.get();

                if(size >= mCapacity)
                {
                    mOverflow.set(true);
                    return;
                }

                if(mSize.compareAndSet(size, size + 1))
                {
                    mQueue.offer(capture);
                    return;
                }
            }
        }

        private T poll()
        {
            T value = mQueue.poll();

            if(value != null)
            {
                mSize.decrementAndGet();
            }

            return value;
        }

        private boolean consumeOverflow()
        {
            return mOverflow.getAndSet(false);
        }

        private void clear()
        {
            while(poll() != null)
            {
                // Drain through poll so concurrent producer reservations cannot corrupt the size counter.
            }

            mOverflow.set(false);
        }
    }

    private record EventCapture(long bindingRevision, IDecodeEvent identity, long timeStart, long duration,
                                DecodeEventType type,
                                IdentifierCollection identifiers, IChannelDescriptor channel, String details,
                                Protocol protocol, boolean hasTimeslot, int timeslot)
    {
        private static EventCapture from(IDecodeEvent event, long bindingRevision)
        {
            if(event == null)
            {
                return null;
            }

            return new EventCapture(bindingRevision, event, event.getTimeStart(), Math.max(0, event.getDuration()),
                event.getEventType(), event.getIdentifierCollection(), event.getChannelDescriptor(),
                event.getDetails(), event.getProtocol(), event.hasTimeslot(), event.getTimeslot());
        }

        private LiveDecodeEventDto toDto(String id, long generation, long fallbackFrequency)
        {
            List<Identifier> all = identifiers != null ? identifiers.getIdentifiers() : List.of();
            List<LiveIdentifierDto> from = identifierDtos(all, io.github.dsheirer.identifier.Role.FROM);
            List<LiveIdentifierDto> to = identifierDtos(all, io.github.dsheirer.identifier.Role.TO);
            long frequency = channel != null ? Math.max(0, channel.getDownlinkFrequency()) :
                Math.max(0, fallbackFrequency);
            String channelLabel = channel != null ? LiveText.normalize(channel.toString(), 120) : "";
            return new LiveDecodeEventDto(id, generation, timeStart, duration,
                type != null ? type.name() : "UNKNOWN", type != null ? type.getLabel() : "Unknown",
                category(type), protocol != null ? protocol.toString() : "Unknown", from, to, channelLabel,
                frequency, hasTimeslot ? Math.max(0, timeslot) : null,
                LiveText.normalize(details, LiveActivityMapper.MAXIMUM_DETAILS_CHARACTERS));
        }
    }

    private record MessageCapture(long bindingRevision, IMessage identity)
    {
        private static MessageCapture from(IMessage message, long bindingRevision)
        {
            if(message == null || message instanceof StuffBitsMessage)
            {
                return null;
            }

            return new MessageCapture(bindingRevision, message);
        }

        private LiveMessageDto toDto(String id, long generation, long sequence)
        {
            return LiveActivityMapper.message(id, generation, sequence, identity);
        }
    }

    private static List<LiveIdentifierDto> identifierDtos(List<Identifier> identifiers,
                                                           io.github.dsheirer.identifier.Role role)
    {
        if(identifiers == null || identifiers.isEmpty())
        {
            return List.of();
        }

        List<LiveIdentifierDto> result = new ArrayList<>();

        for(Identifier<?> identifier: identifiers)
        {
            if(identifier != null && (role == null || identifier.getRole() == role))
            {
                LiveIdentifierDto dto = LiveIdentifierDto.from(identifier);

                if(dto != null)
                {
                    result.add(dto);
                }

                if(result.size() == LiveActivityMapper.MAXIMUM_IDENTIFIERS)
                {
                    break;
                }
            }
        }

        return List.copyOf(result);
    }

    private static String category(DecodeEventType type)
    {
        if(type == null)
        {
            return "other";
        }
        else if(DecodeEventType.VOICE_CALLS.contains(type))
        {
            return "voice";
        }
        else if(DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(type))
        {
            return "protected-voice";
        }
        else if(DecodeEventType.DATA_CALLS.contains(type))
        {
            return "data";
        }
        else if(DecodeEventType.COMMANDS.contains(type))
        {
            return "commands";
        }
        else if(DecodeEventType.REGISTRATION.contains(type))
        {
            return "registrations";
        }

        return "other";
    }
}
