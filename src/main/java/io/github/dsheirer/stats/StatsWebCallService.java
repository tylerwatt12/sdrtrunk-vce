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

import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.scanlist.ScanListModel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded in-memory cache and announcement stream for completed calls played independently by web browsers.
 */
final class StatsWebCallService implements AutoCloseable
{
    private static final int EVENT_QUEUE_CAPACITY = 256;
    static final int MAXIMUM_SNAPSHOT_CALLS = 256;
    static final int MAXIMUM_METADATA_TEXT_CHARACTERS = 256;
    /** Maximum encoded size demonstrated for 256 calls when every externally derived field needs JSON escaping. */
    static final int MAXIMUM_SNAPSHOT_JSON_BYTES = 4 * 1024 * 1024;
    static final int MAXIMUM_CALL_AUDIO_BYTES = 16 * 1024 * 1024;
    static final long MAXIMUM_PENDING_AUDIO_BYTES = MAXIMUM_CALL_AUDIO_BYTES;
    static final int WAVE_HEADER_BYTES = 44;
    private static final long MAXIMUM_AGE_MS = TimeUnit.MINUTES.toMillis(30);
    private static final int SAMPLE_RATE = 8000;
    private final StatsLiveEventHub mEventHub;
    private final CompletedCallScanListMatcher mScanListMatcher;
    private final ThreadPoolExecutor mEncoderExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2), new NamingThreadFactory("stats completed call audio"),
        new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,CachedCall> mCalls = new LinkedHashMap<>();
    /** Prevents browser call-ID deduplication from mistaking post-restart calls for an earlier server session. */
    private final String mInstanceId = UUID.randomUUID().toString().replace("-", "");
    private final AtomicLong mSequence = new AtomicLong();
    private final AtomicLong mPendingAudioBytes = new AtomicLong();
    private final AtomicLong mReceivedCalls = new AtomicLong();
    private final AtomicLong mPublishedCalls = new AtomicLong();
    private final AtomicLong mDroppedNoListeners = new AtomicLong();
    private final AtomicLong mDroppedNoMatchingListeners = new AtomicLong();
    private final AtomicLong mDroppedInvalidCalls = new AtomicLong();
    private final AtomicLong mDroppedNoScanList = new AtomicLong();
    private final AtomicLong mDroppedPendingCapacity = new AtomicLong();
    private final AtomicLong mDroppedEncoderCapacity = new AtomicLong();
    private final AtomicLong mAudioFetchMisses = new AtomicLong();
    private final AtomicLong mAgeEvictions = new AtomicLong();
    private final AtomicLong mCapacityEvictions = new AtomicLong();
    private final AtomicLong mRunGeneration = new AtomicLong();
    private final AtomicInteger mActiveAudioResponses = new AtomicInteger();
    private final AtomicLong mRejectedAudioResponses = new AtomicLong();
    private long mAudioBytes;
    private volatile WebCallConfiguration mConfiguration;
    private volatile boolean mRunning;

    StatsWebCallService()
    {
        this(null, WebCallConfiguration.defaults());
    }

    StatsWebCallService(WebCallConfiguration configuration)
    {
        this(null, configuration);
    }

    StatsWebCallService(ScanListModel scanListModel, WebCallConfiguration configuration)
    {
        mConfiguration = configuration != null ? configuration : WebCallConfiguration.defaults();
        mEventHub = new StatsLiveEventHub(mConfiguration.maximumListeners(), EVENT_QUEUE_CAPACITY);
        mScanListMatcher = scanListModel != null ? new CompletedCallScanListMatcher(scanListModel) : null;
    }

    synchronized void configure(WebCallConfiguration configuration)
    {
        mConfiguration = configuration != null ? configuration : WebCallConfiguration.defaults();
        mEventHub.setMaximumSubscribers(mConfiguration.maximumListeners());
        evictToLimits();
    }

    synchronized void start()
    {
        if(!mRunning)
        {
            mRunGeneration.incrementAndGet();
            mRunning = true;
        }
    }

    synchronized void stop()
    {
        if(mRunning)
        {
            mRunning = false;
            mRunGeneration.incrementAndGet();
        }

        mCalls.clear();
        mAudioBytes = 0;
    }

    void receive(CompletedAudioCall call)
    {
        mReceivedCalls.incrementAndGet();
        long generation = mRunGeneration.get();
        AudioCallSnapshot snapshot = call != null ? call.snapshot() : null;

        if(!mRunning || !mEventHub.hasSubscribers())
        {
            mDroppedNoListeners.incrementAndGet();
            return;
        }

        if(call == null || !call.hasAudio() || snapshot == null || snapshot.duplicate() ||
            isUnresolvedTrafficCall(snapshot))
        {
            mDroppedInvalidCalls.incrementAndGet();
            return;
        }

        Set<Long> scanListIds = mScanListMatcher != null ? mScanListMatcher.match(call) : Set.of();

        if(mScanListMatcher != null && scanListIds.isEmpty())
        {
            mDroppedNoScanList.incrementAndGet();
            return;
        }

        if(!mEventHub.hasMatchingSubscriber("call", Map.of("scan_list_ids", scanListIds)))
        {
            mDroppedNoMatchingListeners.incrementAndGet();
            return;
        }

        int waveLength = checkedWaveLength(call.audioBuffers());

        if(waveLength < 0)
        {
            mDroppedInvalidCalls.incrementAndGet();
            return;
        }

        if(!reservePendingAudio(waveLength))
        {
            mDroppedPendingCapacity.incrementAndGet();
            publishMissed(generation, scanListIds, "pending_audio_capacity");
            return;
        }

        try
        {
            mEncoderExecutor.execute(() -> {
                try
                {
                    cache(call, waveLength, scanListIds, generation);
                }
                finally
                {
                    mPendingAudioBytes.addAndGet(-waveLength);
                }
            });
        }
        catch(RuntimeException e)
        {
            mPendingAudioBytes.addAndGet(-waveLength);
            mDroppedEncoderCapacity.incrementAndGet();
            publishMissed(generation, scanListIds, "encoder_capacity");
        }
    }

    /** Bounds concurrent WAV responses independently from the bounded SSE listener set. */
    boolean tryAcquireAudioResponse()
    {
        while(true)
        {
            int current = mActiveAudioResponses.get();

            if(current >= mConfiguration.maximumListeners())
            {
                mRejectedAudioResponses.incrementAndGet();
                return false;
            }

            if(mActiveAudioResponses.compareAndSet(current, current + 1))
            {
                return true;
            }
        }
    }

    void releaseAudioResponse()
    {
        mActiveAudioResponses.updateAndGet(current -> Math.max(0, current - 1));
    }

    synchronized StatsLiveEventHub.Subscription subscribe()
    {
        return mRunning ? mEventHub.subscribe() : null;
    }

    /**
     * Returns a bounded oldest-first metadata snapshot so a browser can fill a gap without replaying calls it has
     * already accepted. Audio remains in the existing bounded cache and is fetched only if the browser plays it.
     */
    synchronized List<Map<String,Object>> snapshot()
    {
        return snapshot(Set.of());
    }

    /** Returns the bounded recovery snapshot visible to the selected scan lists. */
    synchronized List<Map<String,Object>> snapshot(Set<Long> scanListIds)
    {
        evictExpired(System.currentTimeMillis());
        Set<Long> selected = scanListIds != null ? Set.copyOf(scanListIds) : Set.of();
        List<CachedCall> cached = mCalls.values().stream()
            .filter(call -> selected.isEmpty() || matchesScanLists(call.metadata(), selected)).toList();
        int first = Math.max(0, cached.size() - MAXIMUM_SNAPSHOT_CALLS);
        List<Map<String,Object>> result = new ArrayList<>(cached.size() - first);

        for(int index = first; index < cached.size(); index++)
        {
            result.add(cached.get(index).metadata());
        }

        return List.copyOf(result);
    }

    synchronized StatsLiveEventHub.Subscription subscribe(Set<Long> scanListIds)
    {
        if(!mRunning)
        {
            return null;
        }

        Set<Long> selected = scanListIds != null ? Set.copyOf(scanListIds) : Set.of();
        return mEventHub.subscribe(event -> matchesScanLists(event, selected));
    }

    private static boolean matchesScanLists(StatsLiveEventHub.LiveEvent event, Set<Long> selected)
    {
        if(selected.isEmpty() || event == null || !(event.data() instanceof Map<?,?> metadata) ||
            !(metadata.get("scan_list_ids") instanceof Collection<?> matches))
        {
            return false;
        }

        for(Object value : matches)
        {
            if(value instanceof Number number && selected.contains(number.longValue()))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean matchesScanLists(Map<?,?> metadata, Set<Long> selected)
    {
        return matchesScanLists(new StatsLiveEventHub.LiveEvent("call", metadata), selected);
    }

    private static boolean isUnresolvedTrafficCall(AudioCallSnapshot snapshot)
    {
        IdentifierCollection identifiers = snapshot != null ? snapshot.identifierCollection() : null;
        return identifiers != null && identifiers.getToIdentifier() == null &&
            identifiers.getIdentifier(IdentifierClass.DECODER, Form.TRAFFIC_CHANNEL, Role.ANY) != null;
    }

    synchronized CachedCall get(String id)
    {
        evictExpired(System.currentTimeMillis());
        CachedCall call = mCalls.get(id);

        if(call == null)
        {
            mAudioFetchMisses.incrementAndGet();
        }

        return call;
    }

    synchronized Map<String,Object> status()
    {
        evictExpired(System.currentTimeMillis());
        WebCallConfiguration configuration = mConfiguration;
        Map<String,Object> status = new LinkedHashMap<>();
        status.put("cached_calls", mCalls.size());
        status.put("cached_audio_bytes", mAudioBytes);
        status.put("active_listeners", mEventHub.subscriberCount());
        status.put("maximum_listeners", configuration.maximumListeners());
        status.put("active_audio_responses", mActiveAudioResponses.get());
        status.put("maximum_audio_responses", configuration.maximumListeners());
        status.put("rejected_audio_responses", mRejectedAudioResponses.get());
        status.put("maximum_selected_scan_lists", configuration.maximumSelectedScanLists());
        status.put("waiting_calls_per_listener", configuration.waitingCallsPerListener());
        status.put("maximum_calls", configuration.maximumCachedCalls());
        status.put("maximum_audio_bytes", configuration.maximumCachedAudioBytes());
        status.put("maximum_call_audio_bytes", MAXIMUM_CALL_AUDIO_BYTES);
        status.put("pending_audio_bytes", mPendingAudioBytes.get());
        status.put("maximum_pending_audio_bytes", MAXIMUM_PENDING_AUDIO_BYTES);
        status.put("encoder_queue_depth", mEncoderExecutor.getQueue().size());
        status.put("event_queue_capacity", mEventHub.queueCapacity());
        status.put("received_calls", mReceivedCalls.get());
        status.put("published_calls", mPublishedCalls.get());
        status.put("dropped_no_listeners", mDroppedNoListeners.get());
        status.put("dropped_no_matching_listeners", mDroppedNoMatchingListeners.get());
        status.put("dropped_invalid_calls", mDroppedInvalidCalls.get());
        status.put("dropped_no_scan_list", mDroppedNoScanList.get());
        status.put("dropped_pending_capacity", mDroppedPendingCapacity.get());
        status.put("dropped_encoder_capacity", mDroppedEncoderCapacity.get());
        status.put("dropped_sse_events", mEventHub.droppedEvents());
        status.put("rejected_listeners", mEventHub.rejectedSubscriptions());
        status.put("audio_fetch_misses", mAudioFetchMisses.get());
        status.put("age_evictions", mAgeEvictions.get());
        status.put("capacity_evictions", mCapacityEvictions.get());
        return Map.copyOf(status);
    }

    /**
     * Lightweight observer counters for the receiver-health sampler.  This deliberately avoids cache eviction and
     * cache-map inspection performed by the full public status projection.
     */
    Map<String,Object> observerStatus()
    {
        return Map.ofEntries(
            Map.entry("active_listeners", mEventHub.subscriberCount()),
            Map.entry("active_audio_responses", mActiveAudioResponses.get()),
            Map.entry("rejected_audio_responses", mRejectedAudioResponses.get()),
            Map.entry("pending_audio_bytes", mPendingAudioBytes.get()),
            Map.entry("encoder_queue_depth", mEncoderExecutor.getQueue().size()),
            Map.entry("published_calls", mPublishedCalls.get()),
            Map.entry("dropped_pending_capacity", mDroppedPendingCapacity.get()),
            Map.entry("dropped_encoder_capacity", mDroppedEncoderCapacity.get()),
            Map.entry("dropped_sse_events", mEventHub.droppedEvents()),
            Map.entry("rejected_listeners", mEventHub.rejectedSubscriptions()));
    }

    private boolean reservePendingAudio(int waveLength)
    {
        long current;
        long updated;

        do
        {
            current = mPendingAudioBytes.get();
            updated = current + waveLength;

            if(updated > MAXIMUM_PENDING_AUDIO_BYTES)
            {
                return false;
            }
        }
        while(!mPendingAudioBytes.compareAndSet(current, updated));

        return true;
    }

    private void cache(CompletedAudioCall call, int waveLength, Set<Long> scanListIds, long generation)
    {
        if(!isCurrentGeneration(generation))
        {
            return;
        }

        byte[] wave = wave(call, waveLength);

        if(wave == null)
        {
            return;
        }

        long sequence = mSequence.incrementAndGet();
        String id = mInstanceId + "-" + Long.toUnsignedString(sequence, 36);
        long created = System.currentTimeMillis();
        Map<String,Object> metadata = metadata(id, sequence, call, created, scanListIds);
        CachedCall cachedCall = new CachedCall(metadata, wave, created);

        synchronized(this)
        {
            if(!isCurrentGeneration(generation))
            {
                return;
            }

            evictExpired(created);
            mCalls.put(id, cachedCall);
            mAudioBytes += wave.length;
            evictToLimits();
            mEventHub.publish("call", metadata);
            mPublishedCalls.incrementAndGet();
        }
    }

    private boolean isCurrentGeneration(long generation)
    {
        return mRunning && generation == mRunGeneration.get();
    }

    private void publishMissed(long generation, Set<Long> scanListIds, String reason)
    {
        if(isCurrentGeneration(generation))
        {
            mEventHub.publish("missed", Map.of("scan_list_ids", scanListIds.stream().sorted().toList(),
                "missed_calls", 1, "exact", true, "reason", reason));
        }
    }

    private synchronized void evictExpired(long now)
    {
        List<String> expired = new ArrayList<>();

        for(Map.Entry<String,CachedCall> entry: mCalls.entrySet())
        {
            if(now - entry.getValue().createdAtMs() > MAXIMUM_AGE_MS)
            {
                expired.add(entry.getKey());
            }
        }

        expired.forEach(id -> {
            remove(id);
            mAgeEvictions.incrementAndGet();
        });
    }

    private void evictToLimits()
    {
        WebCallConfiguration configuration = mConfiguration;

        while(mCalls.size() > configuration.maximumCachedCalls() ||
            mAudioBytes > configuration.maximumCachedAudioBytes())
        {
            remove(mCalls.keySet().iterator().next());
            mCapacityEvictions.incrementAndGet();
        }
    }

    private void remove(String id)
    {
        CachedCall removed = mCalls.remove(id);

        if(removed != null)
        {
            mAudioBytes -= removed.wave().length;
        }
    }

    private static Map<String,Object> metadata(String id, long sequence, CompletedAudioCall call, long completedAt,
                                               Set<Long> scanListIds)
    {
        AudioCallSnapshot snapshot = call.snapshot();
        IdentifierCollection identifiers = snapshot.identifierCollection();
        Identifier<?> source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Identifier<?> target = identifiers != null ? identifiers.getToIdentifier() : null;
        AudioCallRecordingMetadata recordingMetadata = snapshot.recordingMetadata();
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        putText(value, "call_id", id);
        putText(value, "audio_url", StatsApiV1.CALLS + "/" + id + "/audio");
        value.put("order_sequence", sequence);
        value.put("started_at_ms", snapshot.startTimestamp());
        value.put("completed_at_ms", completedAt);
        value.put("duration_ms", call.getDuration());
        putText(value, "system", recordingMetadata != null ? recordingMetadata.systemName() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY));
        putText(value, "channel", recordingMetadata != null ? recordingMetadata.channelName() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY));
        putText(value, "decoder", identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.DECODER_TYPE,
            Role.ANY));
        putText(value, "source_id", recordingMetadata != null ? recordingMetadata.sourceValue() : value(source));
        putText(value, "source_alias", recordingMetadata != null ? recordingMetadata.sourceAlias() : null);
        putText(value, "source_form", form(source));
        putText(value, "target_id", recordingMetadata != null ? recordingMetadata.destinationValue() : value(target));
        putText(value, "target_alias", recordingMetadata != null ? recordingMetadata.destinationAlias() : null);
        putText(value, "target_form", form(target));
        putText(value, "conversation_key", conversationKey(identifiers, target));
        value.put("scan_list_ids", scanListIds != null ? scanListIds.stream()
            .sorted(Comparator.naturalOrder()).toList() : List.of());
        value.put("frequency_hz", longValue(identifiers, Form.CHANNEL_FREQUENCY));
        value.put("timeslot", snapshot.timeslot());
        value.put("encrypted", snapshot.encrypted());
        if(snapshot.voiceCallQuality() != null && snapshot.voiceCallQuality().hasMeasurements())
        {
            value.put("vc_quality_pct", snapshot.voiceCallQuality().qualityPercent());
            value.put("vc_decoded_frames", snapshot.voiceCallQuality().decodedFrameCount());
            value.put("vc_repeated_frames", snapshot.voiceCallQuality().repeatedFrameCount());
            value.put("vc_concealed_frames", snapshot.voiceCallQuality().concealedFrameCount());
            value.put("vc_missing_frames", snapshot.voiceCallQuality().missingFrameCount());
            value.put("vc_fec_errors", snapshot.voiceCallQuality().fecErrorCount());
            value.put("vc_fec_protected_bits", snapshot.voiceCallQuality().fecProtectedBitCount());
        }
        return Map.copyOf(value);
    }

    private static String conversationKey(IdentifierCollection identifiers, Identifier<?> target)
    {
        Object systemValue = identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY);
        String system = systemValue != null ? systemValue.toString().trim().toLowerCase(Locale.ROOT) : "unknown";
        String protocol = target != null && target.getProtocol() != null ?
            target.getProtocol().name().toLowerCase(Locale.ROOT) : "unknown";
        Object destination = target != null ? target.getValue() : null;

        if(target instanceof TalkgroupIdentifier talkgroup)
        {
            destination = talkgroup.getValue();
        }
        else if(target instanceof PatchGroupIdentifier patchIdentifier)
        {
            PatchGroup patchGroup = patchIdentifier.getValue();

            if(patchGroup != null && patchGroup.getPatchGroup() != null)
            {
                destination = patchGroup.getPatchGroup().getValue();
            }
        }

        if(destination != null)
        {
            return protocol + ":" + system + ":" + destination;
        }

        long frequency = longValue(identifiers, Form.CHANNEL_FREQUENCY);
        return protocol + ":" + system + ":frequency:" + frequency;
    }

    private static Object identifierValue(IdentifierCollection identifiers, IdentifierClass identifierClass,
                                          Form form, Role role)
    {
        return value(identifiers != null ? identifiers.getIdentifier(identifierClass, form, role) : null);
    }

    private static String value(Identifier<?> identifier)
    {
        Object value = identifier != null ? identifier.getValue() : null;
        return value != null ? value.toString() : null;
    }

    private static String form(Identifier<?> identifier)
    {
        return identifier != null && identifier.getForm() != null ? identifier.getForm().name() : null;
    }

    private static long longValue(IdentifierCollection identifiers, Form form)
    {
        if(identifiers != null)
        {
            List<Identifier> matches = identifiers.getIdentifiers(form);

            if(!matches.isEmpty())
            {
                Object value = matches.getFirst().getValue();

                if(value instanceof Number number)
                {
                    return number.longValue();
                }

                try
                {
                    return Long.parseLong(String.valueOf(value));
                }
                catch(NumberFormatException e)
                {
                    // Unknown frequency representation.
                }
            }
        }

        return 0;
    }

    private static void putText(Map<String,Object> values, String key, Object value)
    {
        String bounded = boundedText(value);

        if(bounded != null)
        {
            values.put(key, bounded);
        }
    }

    static String boundedText(Object value)
    {
        if(value == null)
        {
            return null;
        }

        String text = value.toString();

        if(text.length() <= MAXIMUM_METADATA_TEXT_CHARACTERS)
        {
            return text;
        }

        int end = MAXIMUM_METADATA_TEXT_CHARACTERS;

        if(Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end)))
        {
            end--;
        }

        return text.substring(0, end);
    }

    static byte[] wave(CompletedAudioCall call)
    {
        List<float[]> audioBuffers = call != null ? call.audioBuffers() : null;
        int waveLength = checkedWaveLength(audioBuffers);

        if(waveLength < 0)
        {
            return null;
        }

        return wave(call, waveLength);
    }

    private static byte[] wave(CompletedAudioCall call, int waveLength)
    {
        List<float[]> audioBuffers = call != null ? call.audioBuffers() : null;

        if(audioBuffers == null || waveLength < WAVE_HEADER_BYTES || waveLength > MAXIMUM_CALL_AUDIO_BYTES)
        {
            return null;
        }

        int pcmLength = waveLength - WAVE_HEADER_BYTES;
        ByteBuffer wave = ByteBuffer.allocate(waveLength).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(36 + pcmLength);
        wave.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        wave.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(16);
        wave.putShort((short)1);
        wave.putShort((short)1);
        wave.putInt(SAMPLE_RATE);
        wave.putInt(SAMPLE_RATE * Short.BYTES);
        wave.putShort((short)Short.BYTES);
        wave.putShort((short)16);
        wave.put("data".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(pcmLength);

        for(float[] audioBuffer: audioBuffers)
        {
            if(audioBuffer == null || audioBuffer.length > wave.remaining() / Short.BYTES)
            {
                return null;
            }

            for(float sample: audioBuffer)
            {
                wave.putShort(sample > 1.0f ? Short.MAX_VALUE : sample < -1.0f ? (short)-Short.MAX_VALUE :
                    (short)(sample * Short.MAX_VALUE));
            }
        }

        if(wave.hasRemaining())
        {
            return null;
        }

        return wave.array();
    }

    static int checkedWaveLength(List<float[]> audioBuffers)
    {
        if(audioBuffers == null || audioBuffers.isEmpty())
        {
            return -1;
        }

        long pcmBytes = 0;

        try
        {
            for(float[] audioBuffer: audioBuffers)
            {
                if(audioBuffer == null)
                {
                    return -1;
                }

                pcmBytes = Math.addExact(pcmBytes,
                    Math.multiplyExact((long)audioBuffer.length, Short.BYTES));

                if(pcmBytes > MAXIMUM_CALL_AUDIO_BYTES - WAVE_HEADER_BYTES)
                {
                    return -1;
                }
            }

            if(pcmBytes == 0)
            {
                return -1;
            }

            return Math.toIntExact(Math.addExact(WAVE_HEADER_BYTES, pcmBytes));
        }
        catch(ArithmeticException e)
        {
            return -1;
        }
    }

    @Override
    public void close()
    {
        stop();
        mEncoderExecutor.shutdownNow();
        mEventHub.close();
    }

    record CachedCall(Map<String,Object> metadata, byte[] wave, long createdAtMs)
    {
    }
}
