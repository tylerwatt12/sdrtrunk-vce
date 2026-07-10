/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.audio.playback.AudioPlaybackCall;
import io.github.dsheirer.audio.playback.AudioPlaybackState;
import io.github.dsheirer.audio.playback.IAudioPlaybackSession;
import io.github.dsheirer.audio.playback.PlaybackAudioFrame;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight owner of live Stats Server state. It consumes shared playback and Systems activity snapshots instead
 * of rebuilding either state from raw decoder events.
 */
final class StatsLiveService implements AutoCloseable
{
    private static final int MAXIMUM_SSE_CLIENTS = 32;
    private static final int SSE_QUEUE_CAPACITY = 256;
    private static final int MAXIMUM_AUDIO_CLIENTS = 8;
    private static final int AUDIO_QUEUE_CAPACITY = 64;
    private final StatsWebDatabase mDatabase;
    private final IAudioPlaybackSession mPlaybackSession;
    private final ChannelActivityModel mActivityModel;
    private final StatsLiveEventHub mSystemsHub = new StatsLiveEventHub(MAXIMUM_SSE_CLIENTS, SSE_QUEUE_CAPACITY);
    private final StatsLiveEventHub mActivityHub = new StatsLiveEventHub(MAXIMUM_SSE_CLIENTS, SSE_QUEUE_CAPACITY);
    private final StatsLiveEventHub mPlaybackHub = new StatsLiveEventHub(MAXIMUM_SSE_CLIENTS, SSE_QUEUE_CAPACITY);
    private final Map<String,Map<String,Object>> mTables = new ConcurrentHashMap<>();
    private final ExecutorService mEventExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2048), new NamingThreadFactory("stats live events"),
        new ThreadPoolExecutor.DiscardOldestPolicy());
    private final PlaybackAudioStream mPlaybackAudioStream = new PlaybackAudioStream();
    private final Listener<AudioPlaybackState> mPlaybackStateListener = state -> execute(() -> process(state));
    private final Listener<ChannelActivityEvent> mChannelActivityListener = event -> execute(() -> process(event));
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private volatile Map<String,Object> mPlaybackState = emptyPlaybackState();

    StatsLiveService(StatsWebDatabase database, IAudioPlaybackSession playbackSession,
                     ChannelActivityModel activityModel)
    {
        mDatabase = database;
        mPlaybackSession = playbackSession;
        mActivityModel = activityModel;

        if(mPlaybackSession != null)
        {
            mPlaybackState = playbackState(mPlaybackSession.getPlaybackState());
        }
    }

    void start()
    {
        if(mRunning.compareAndSet(false, true))
        {
            if(mPlaybackSession != null)
            {
                mPlaybackState = playbackState(mPlaybackSession.getPlaybackState());
                mPlaybackSession.addPlaybackStateListener(mPlaybackStateListener);
            }

            if(mActivityModel != null)
            {
                mActivityModel.addActivityListener(mChannelActivityListener);
            }
        }
    }

    void stop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            if(mPlaybackSession != null)
            {
                mPlaybackSession.removePlaybackStateListener(mPlaybackStateListener);
            }

            if(mActivityModel != null)
            {
                mActivityModel.removeActivityListener(mChannelActivityListener);
            }

            mPlaybackAudioStream.stop();
            mTables.clear();
        }
    }

    void activityCommitted(List<Long> rowIds)
    {
        if(rowIds != null && !rowIds.isEmpty() && mActivityHub.hasSubscribers())
        {
            List<Long> committed = List.copyOf(rowIds);
            execute(() -> {
                for(Map<String,Object> row: mDatabase.activityByIds(committed))
                {
                    mActivityHub.publish("activity", row);
                }
            });
        }
    }

    private void execute(Runnable task)
    {
        try
        {
            mEventExecutor.execute(task);
        }
        catch(RuntimeException e)
        {
            // Service is shutting down.
        }
    }

    StatsLiveEventHub.Subscription subscribeSystems()
    {
        return mSystemsHub.subscribe();
    }

    StatsLiveEventHub.Subscription subscribeActivity()
    {
        return mActivityHub.subscribe();
    }

    StatsLiveEventHub.Subscription subscribePlayback()
    {
        return mPlaybackHub.subscribe();
    }

    Map<String,Object> snapshot()
    {
        List<Map<String,Object>> tables = new ArrayList<>(mTables.values());
        tables.sort(Comparator.comparing(row -> String.valueOf(row.getOrDefault("table_id", ""))));
        return Map.of("tables", tables, "playback", mPlaybackState);
    }

    Map<String,Object> playbackState()
    {
        return mPlaybackState;
    }

    Map<String,Object> controlPlayback(String action)
    {
        if(mPlaybackSession == null || action == null)
        {
            return mPlaybackState;
        }

        switch(action.toLowerCase())
        {
            case "hold" -> mPlaybackSession.toggleHoldOnCurrentCall();
            case "avoid" -> mPlaybackSession.avoidCurrentCall();
            case "clear" -> mPlaybackSession.clearAvoids();
            default -> throw new StatsApiException(400, "Unknown playback action");
        }

        mPlaybackState = playbackState(mPlaybackSession.getPlaybackState());
        return mPlaybackState;
    }

    AudioSubscription subscribeAudio()
    {
        return mPlaybackAudioStream.subscribe();
    }

    private void process(AudioPlaybackState state)
    {
        Map<String,Object> updated = playbackState(state);

        if(!updated.equals(mPlaybackState))
        {
            mPlaybackState = updated;
            mPlaybackHub.publish("playback", updated);
            mSystemsHub.publish("playback", updated);
        }
    }

    private void process(ChannelActivityEvent event)
    {
        if(event == null || event.snapshot() == null)
        {
            return;
        }

        Map<String,Object> table = activityTable(event.snapshot());
        String tableId = event.snapshot().tableId();

        if(event.operation() == ChannelActivityEvent.Operation.REMOVE)
        {
            if(mTables.remove(tableId) == null)
            {
                return;
            }
        }
        else
        {
            Map<String,Object> previous = mTables.put(tableId, table);

            if(table.equals(previous))
            {
                return;
            }
        }

        mSystemsHub.publish("activity_table", Map.of("operation", event.operation().name().toLowerCase(),
            "table_id", tableId, "table", table));
    }

    private static Map<String,Object> activityTable(ChannelActivitySnapshot snapshot)
    {
        LinkedHashMap<String,Object> table = new LinkedHashMap<>();
        table.put("table_id", snapshot.tableId());
        table.put("title", snapshot.title());
        table.put("channel_name", snapshot.channelName());
        put(table, "guid", snapshot.guid());
        table.put("closeable", snapshot.closeable());
        table.put("control_active", snapshot.controlActive());
        table.put("rows", snapshot.rows().stream().map(StatsLiveService::activityRow).toList());
        return Map.copyOf(table);
    }

    private static Map<String,Object> activityRow(ChannelActivitySnapshot.Row snapshot)
    {
        LinkedHashMap<String,Object> row = new LinkedHashMap<>();
        row.put("key", snapshot.key());
        row.put("status", snapshot.status());
        row.put("role", snapshot.role());
        row.put("control_role", snapshot.controlRole());
        put(row, "lcn", snapshot.lcn());
        row.put("frequency_hz", snapshot.frequencyHz());
        put(row, "timeslot", snapshot.timeslot());
        put(row, "source_id", snapshot.sourceId());
        put(row, "source_alias", snapshot.sourceAlias());
        put(row, "target_id", snapshot.targetId());
        put(row, "target_alias", snapshot.targetAlias());
        put(row, "decoder", snapshot.decoder());
        put(row, "encryption_details", snapshot.encryptionDetails());
        return Map.copyOf(row);
    }

    private static Map<String,Object> playbackState(AudioPlaybackState state)
    {
        if(state == null)
        {
            return emptyPlaybackState();
        }

        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        value.put("local_muted", state.localMuted());
        value.put("playing", state.playing().stream().map(StatsLiveService::playbackCall).toList());
        value.put("queued", state.queued().stream().map(StatsLiveService::playbackCall).toList());
        value.put("queued_calls", state.queuedCallCount());
        put(value, "current_target", state.currentTarget());
        put(value, "hold_target", state.holdTarget());
        value.put("avoided_targets", state.avoidedTargets());
        return Map.copyOf(value);
    }

    private static Map<String,Object> playbackCall(AudioPlaybackCall call)
    {
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        value.put("call_id", call.callId());
        put(value, "output", call.output());
        put(value, "system", call.system());
        put(value, "source_id", call.sourceId());
        put(value, "source_alias", call.sourceAlias());
        put(value, "target_id", call.targetId());
        put(value, "target_alias", call.targetAlias());
        value.put("frequency_hz", call.frequencyHz());
        value.put("timeslot", call.timeslot());
        value.put("encrypted", call.encrypted());
        value.put("priority", call.priority());
        return Map.copyOf(value);
    }

    private static Map<String,Object> emptyPlaybackState()
    {
        return Map.of("local_muted", false, "playing", List.of(), "queued", List.of(), "queued_calls", 0,
            "avoided_targets", List.of());
    }

    private static void put(Map<String,Object> values, String key, Object value)
    {
        if(value != null)
        {
            values.put(key, value);
        }
    }

    @Override
    public void close()
    {
        stop();
        mPlaybackAudioStream.close();
        mEventExecutor.shutdownNow();
        mSystemsHub.close();
        mActivityHub.close();
        mPlaybackHub.close();
        mTables.clear();
    }

    final class AudioSubscription implements AutoCloseable
    {
        private static final byte[] END = new byte[0];
        private final ArrayBlockingQueue<byte[]> mQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
        private final AtomicBoolean mClosed = new AtomicBoolean();

        byte[] poll(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mQueue.poll(timeout, unit);
        }

        boolean isEnd(byte[] bytes)
        {
            return bytes == END;
        }

        private void offer(byte[] bytes)
        {
            if(!mClosed.get() && !mQueue.offer(bytes))
            {
                mQueue.clear();
                mQueue.offer(END);
            }
        }

        private void finish()
        {
            mQueue.clear();
            mQueue.offer(END);
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mQueue.clear();
                mPlaybackAudioStream.remove(this);
            }
        }
    }

    private final class PlaybackAudioStream implements Listener<PlaybackAudioFrame>, AutoCloseable
    {
        private static final long TIMELINE_HEARTBEAT_SAMPLES = 8000;
        private final List<AudioSubscription> mSubscriptions = new ArrayList<>();
        private final AtomicInteger mClientCount = new AtomicInteger();
        private final ExecutorService mEncoderExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), new NamingThreadFactory("stats playback audio"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
        private StatsPlaybackAudioEncoder mEncoder;
        private long mSamplePosition;
        private long mLastTimelinePosition;
        private List<AudioPlaybackCall> mLastTimelineCalls = List.of();
        private boolean mForceTimeline;

        private synchronized AudioSubscription subscribe()
        {
            if(mPlaybackSession == null || mClientCount.get() >= MAXIMUM_AUDIO_CLIENTS)
            {
                return null;
            }

            AudioSubscription subscription = new AudioSubscription();
            mSubscriptions.add(subscription);
            mClientCount.incrementAndGet();
            mForceTimeline = true;

            if(mSubscriptions.size() == 1)
            {
                mEncoder = new StatsPlaybackAudioEncoder();
                mSamplePosition = 0;
                mLastTimelinePosition = -TIMELINE_HEARTBEAT_SAMPLES;
                mLastTimelineCalls = List.of();
                mPlaybackSession.addPlaybackAudioListener(this);
            }

            return subscription;
        }

        @Override
        public void receive(PlaybackAudioFrame frame)
        {
            try
            {
                mEncoderExecutor.execute(() -> encode(frame));
            }
            catch(RuntimeException e)
            {
                // Service is shutting down.
            }
        }

        private synchronized void encode(PlaybackAudioFrame frame)
        {
            if(mEncoder != null && !mSubscriptions.isEmpty())
            {
                long framePosition = mSamplePosition;

                for(byte[] chunk: mEncoder.encode(frame))
                {
                    for(AudioSubscription subscription: List.copyOf(mSubscriptions))
                    {
                        subscription.offer(chunk);
                    }
                }

                mSamplePosition += frame.sampleCount();
                List<AudioPlaybackCall> calls = frame.playing();

                if(mForceTimeline || !calls.equals(mLastTimelineCalls) ||
                    framePosition - mLastTimelinePosition >= TIMELINE_HEARTBEAT_SAMPLES)
                {
                    mForceTimeline = false;
                    mLastTimelinePosition = framePosition;
                    mLastTimelineCalls = calls;
                    mPlaybackHub.publish("audio_timeline", Map.of(
                        "position_ms", framePosition * 1000L / 8000L,
                        "playing", calls.stream().map(StatsLiveService::playbackCall).toList()));
                }
            }
        }

        private synchronized void remove(AudioSubscription subscription)
        {
            if(mSubscriptions.remove(subscription))
            {
                mClientCount.decrementAndGet();
            }

            if(mSubscriptions.isEmpty())
            {
                mPlaybackSession.removePlaybackAudioListener(this);
                mEncoder = null;
            }
        }

        @Override
        public synchronized void close()
        {
            stop();
            mEncoderExecutor.shutdownNow();
        }

        private synchronized void stop()
        {
            if(mPlaybackSession != null)
            {
                mPlaybackSession.removePlaybackAudioListener(this);
            }

            for(AudioSubscription subscription: List.copyOf(mSubscriptions))
            {
                subscription.finish();
            }

            mSubscriptions.clear();
            mClientCount.set(0);
            mEncoder = null;
            mSamplePosition = 0;
            mLastTimelinePosition = 0;
            mLastTimelineCalls = List.of();
            mForceTimeline = false;
        }
    }
}
