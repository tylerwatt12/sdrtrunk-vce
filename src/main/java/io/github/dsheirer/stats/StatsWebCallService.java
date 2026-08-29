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
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNFullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.scanlist.ScanListModel;
import io.github.dsheirer.util.concurrent.BoundedMpscPairQueue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One shared, bounded completed-call feed for independent browser playback.
 *
 * <p>The receiver-side {@link #receive(CompletedAudioCall)} method deliberately performs only an active-feed check
 * and a non-blocking offer to the bounded encoder executor.  Scan-list matching, metadata projection, audio sizing,
 * and WAV encoding all run on the single low-priority worker.</p>
 */
final class StatsWebCallService implements AutoCloseable
{
    static final int MAXIMUM_ACTIVE_FEEDS = 16;
    static final int MAXIMUM_SELECTED_SCAN_LISTS = 16;
    static final int MAXIMUM_FEED_CALLS = 64;
    static final long FEED_WAIT_MILLISECONDS = 5_000L;
    static final long NO_FEED_GENERATION = -1L;
    private static final long FEED_ACTIVITY_GRACE_NANOS = TimeUnit.SECONDS.toNanos(5);
    static final int MAXIMUM_CACHED_CALLS = 512;
    static final long MAXIMUM_CACHED_AUDIO_BYTES = 128L * 1024L * 1024L;
    private static final int ENCODER_QUEUE_CAPACITY = 2;
    private static final Object ENCODER_WORK = new Object();
    static final int MAXIMUM_METADATA_TEXT_CHARACTERS = 256;
    static final int MAXIMUM_ALIAS_METADATA_TEXT_CHARACTERS = 160;
    static final int MAXIMUM_CALL_AUDIO_BYTES = 16 * 1024 * 1024;
    static final int WAVE_HEADER_BYTES = 44;
    private static final long MAXIMUM_AGE_MS = TimeUnit.MINUTES.toMillis(30);
    private static final int SAMPLE_RATE = 8000;
    private final CompletedCallScanListMatcher mScanListMatcher;
    private final WebEntityNavigationCatalog mNavigationCatalog;
    private final BoundedMpscPairQueue<CompletedAudioCall,Object> mEncoderIngress =
        new BoundedMpscPairQueue<>(ENCODER_QUEUE_CAPACITY);
    private final Semaphore mEncoderWakeup = new Semaphore(0);
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final Thread mEncoderThread;
    private final Deque<CachedCall> mCalls = new ArrayDeque<>();
    /** Prevents browser call-ID deduplication from mistaking post-restart calls for an earlier server session. */
    private final String mInstanceId = UUID.randomUUID().toString().replace("-", "");
    private final AtomicLong mCallIdSequence = new AtomicLong();
    private final AtomicLong mPublishedCalls = new AtomicLong();
    private final AtomicLong mDroppedEncoderCapacity = new AtomicLong();
    private final AtomicLong mEncoderFailures = new AtomicLong();
    private final AtomicBoolean mLossPending = new AtomicBoolean();
    private final AtomicLong mRunGeneration = new AtomicLong();
    private final AtomicInteger mActiveFeeds = new AtomicInteger();
    private final AtomicLong mFeedActiveUntilNanos = new AtomicLong();
    private final AtomicLong mRejectedFeeds = new AtomicLong();
    private final AtomicInteger mActiveAudioResponses = new AtomicInteger();
    private final AtomicLong mRejectedAudioResponses = new AtomicLong();
    private long mAudioBytes;
    private long mLatestCursor;
    private long mLatestLossCursor;
    private long mDiscardedThroughCursor;
    private volatile boolean mRunning;

    StatsWebCallService()
    {
        this(null, null);
    }

    StatsWebCallService(ScanListModel scanListModel)
    {
        this(scanListModel, null);
    }

    StatsWebCallService(ScanListModel scanListModel, WebEntityNavigationCatalog navigationCatalog)
    {
        mScanListMatcher = scanListModel != null ? new CompletedCallScanListMatcher(scanListModel) : null;
        mNavigationCatalog = navigationCatalog;
        mEncoderThread = new NamingThreadFactory("stats completed call audio").newThread(this::runEncoder);
        mEncoderThread.setPriority(Thread.MIN_PRIORITY + 1);
    }

    synchronized void start()
    {
        if(!mRunning)
        {
            if(mClosed.get() || mEncoderThread.getState() == Thread.State.TERMINATED)
            {
                throw new IllegalStateException("Browser call service cannot restart after closing");
            }

            if(mEncoderThread.getState() == Thread.State.NEW)
            {
                mEncoderThread.start();
            }

            mRunGeneration.incrementAndGet();
            mRunning = true;
        }
    }

    synchronized void stop()
    {
        if(mRunning)
        {
            commitPendingLoss();
            mLatestLossCursor = ++mLatestCursor;
            mRunning = false;
            mRunGeneration.incrementAndGet();
        }

        mLossPending.set(false);
        mActiveFeeds.set(0);
        mFeedActiveUntilNanos.set(0L);
        clearCalls();
        mEncoderWakeup.release();
        notifyAll();
    }

    /** Receiver/coordinator path: constant-time atomics and one bounded, non-blocking executor offer only. */
    void receive(CompletedAudioCall call)
    {
        if(!mRunning || call == null)
        {
            return;
        }

        long generation = mRunGeneration.get();

        if(!hasActiveFeed())
        {
            markLoss(generation);
            return;
        }

        if(mEncoderIngress.offer(call, ENCODER_WORK, generation))
        {
            mEncoderWakeup.release();
        }
        else
        {
            mDroppedEncoderCapacity.incrementAndGet();
            markLoss(generation);
        }
    }

    boolean isRunning()
    {
        return mRunning;
    }

    private boolean hasActiveFeed()
    {
        return mActiveFeeds.get() > 0 || System.nanoTime() <= mFeedActiveUntilNanos.get();
    }

    /** Admits one bounded long-poll request. No listener session or queue is retained after the request ends. */
    synchronized long tryAcquireFeed()
    {
        if(!mRunning)
        {
            return NO_FEED_GENERATION;
        }

        if(mActiveFeeds.get() >= MAXIMUM_ACTIVE_FEEDS)
        {
            mRejectedFeeds.incrementAndGet();
            return NO_FEED_GENERATION;
        }

        mActiveFeeds.incrementAndGet();
        return mRunGeneration.get();
    }

    synchronized void releaseFeed(long generation)
    {
        if(!isCurrentGeneration(generation))
        {
            return;
        }

        int remaining = mActiveFeeds.updateAndGet(current -> Math.max(0, current - 1));

        if(remaining == 0)
        {
            long deadline = System.nanoTime() + FEED_ACTIVITY_GRACE_NANOS;
            mFeedActiveUntilNanos.accumulateAndGet(deadline, Math::max);
        }
    }

    /**
     * Reads one cursor page. A missing cursor starts at the current live edge and never exposes retained history.
     * A stale or future cursor resets to the current live edge instead of reconstructing a gap.
     */
    FeedResult feed(Set<Long> selectedScanListIds, Long requestedCursor, long wait, TimeUnit unit, long generation)
        throws InterruptedException
    {
        Set<Long> selected = selectedScanListIds != null ? Set.copyOf(selectedScanListIds) : Set.of();
        long waitNanos = Math.max(0L, unit != null ? unit.toNanos(wait) : 0L);
        long deadline = System.nanoTime() + waitNanos;

        synchronized(this)
        {
            if(!isCurrentGeneration(generation))
            {
                return new FeedResult(Long.toString(mLatestCursor), true, List.of());
            }

            FeedResult result = feedNow(selected, requestedCursor);

            while(shouldWait(result, requestedCursor) && isCurrentGeneration(generation) && waitNanos > 0L)
            {
                long remaining = deadline - System.nanoTime();

                if(remaining <= 0L)
                {
                    break;
                }

                TimeUnit.NANOSECONDS.timedWait(this, remaining);
                result = feedNow(selected, requestedCursor);
            }

            if(!isCurrentGeneration(generation))
            {
                return new FeedResult(Long.toString(mLatestCursor), true, List.of());
            }

            return result;
        }
    }

    private static boolean shouldWait(FeedResult result, Long requestedCursor)
    {
        return requestedCursor != null && !result.reset() && result.calls().isEmpty() &&
            result.cursor().equals(Long.toString(requestedCursor));
    }

    private FeedResult feedNow(Set<Long> selected, Long requestedCursor)
    {
        commitPendingLoss();
        evictExpired(System.currentTimeMillis());

        if(requestedCursor == null)
        {
            return new FeedResult(Long.toString(mLatestCursor), false, List.of());
        }

        if(requestedCursor > mLatestCursor || requestedCursor < mDiscardedThroughCursor)
        {
            return new FeedResult(Long.toString(mLatestCursor), true, List.of());
        }

        List<Map<String,Object>> calls = new java.util.ArrayList<>(MAXIMUM_FEED_CALLS);
        long nextCursor = mLatestCursor;

        for(CachedCall call: mCalls)
        {
            if(call.cursor() <= requestedCursor)
            {
                continue;
            }

            if(intersects(call.scanListIds(), selected))
            {
                calls.add(call.metadata());

                if(calls.size() == MAXIMUM_FEED_CALLS)
                {
                    nextCursor = call.cursor();
                    break;
                }
            }
        }

        // One coalesced boundary is enough to tell every older cursor that continuity was lost.  Retaining a loss
        // history merely to identify the exact page that crossed it would add state without changing recovery.
        boolean reset = requestedCursor < mLatestLossCursor;
        return new FeedResult(Long.toString(nextCursor), reset, List.copyOf(calls));
    }

    private static boolean intersects(Set<Long> matched, Set<Long> selected)
    {
        if(matched.isEmpty())
        {
            return selected.isEmpty();
        }

        if(selected.isEmpty())
        {
            return false;
        }

        for(Long id: selected)
        {
            if(matched.contains(id))
            {
                return true;
            }
        }

        return false;
    }

    /** Bounds concurrent WAV responses independently from the bounded call-feed request set. */
    boolean tryAcquireAudioResponse()
    {
        while(true)
        {
            int current = mActiveAudioResponses.get();

            if(current >= MAXIMUM_ACTIVE_FEEDS)
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

    private static boolean isUnresolvedTrafficCall(AudioCallSnapshot snapshot)
    {
        IdentifierCollection identifiers = snapshot != null ? snapshot.identifierCollection() : null;
        return identifiers != null && identifiers.getToIdentifier() == null &&
            identifiers.getIdentifier(IdentifierClass.DECODER, Form.TRAFFIC_CHANNEL, Role.ANY) != null;
    }

    synchronized CachedCall get(String id)
    {
        evictExpired(System.currentTimeMillis());
        CachedCall call = null;

        for(CachedCall candidate: mCalls)
        {
            if(candidate.id().equals(id))
            {
                call = candidate;
                break;
            }
        }

        return call;
    }

    /** Lightweight observer counters for the receiver-health sampler. */
    Map<String,Object> observerStatus()
    {
        return Map.ofEntries(
            Map.entry("active_feeds", mActiveFeeds.get()),
            Map.entry("rejected_feeds", mRejectedFeeds.get()),
            Map.entry("rejected_audio_responses", mRejectedAudioResponses.get()),
            Map.entry("encoder_queue_depth", mEncoderIngress.size()),
            Map.entry("published_calls", mPublishedCalls.get()),
            Map.entry("dropped_encoder_capacity", mDroppedEncoderCapacity.get()),
            Map.entry("encoder_failures", mEncoderFailures.get()));
    }

    private void encodeSafely(CompletedAudioCall call, long generation)
    {
        try
        {
            encode(call, generation);
        }
        catch(RuntimeException exception)
        {
            mEncoderFailures.incrementAndGet();
            markLoss(generation);
        }
    }

    /** Single low-priority consumer for the lock-free receiver-side handoff. */
    private void runEncoder()
    {
        try
        {
            while(!mClosed.get())
            {
                try
                {
                    mEncoderWakeup.acquire();
                    mEncoderWakeup.drainPermits();
                }
                catch(InterruptedException exception)
                {
                    if(mClosed.get())
                    {
                        break;
                    }

                    continue;
                }

                BoundedMpscPairQueue.Entry<CompletedAudioCall,Object> work;

                while(!mClosed.get() && (work = mEncoderIngress.poll()) != null)
                {
                    encodeSafely(work.first(), work.stamp());
                }
            }
        }
        finally
        {
            mEncoderIngress.clear();
        }
    }

    private void markLoss(long generation)
    {
        if(isCurrentGeneration(generation))
        {
            mLossPending.set(true);
        }
    }

    /** Commits all coalesced producer-side loss as one cursor boundary while holding this service's monitor. */
    private void commitPendingLoss()
    {
        if(mLossPending.getAndSet(false))
        {
            mLatestLossCursor = ++mLatestCursor;
        }
    }

    private void encode(CompletedAudioCall call, long generation)
    {
        if(!isCurrentGeneration(generation))
        {
            return;
        }

        AudioCallSnapshot snapshot = call.snapshot();

        if(!call.hasAudio() || snapshot == null || isUnresolvedTrafficCall(snapshot))
        {
            return;
        }

        Set<Long> scanListIds = mScanListMatcher != null ? mScanListMatcher.match(call) : Set.of();

        if(mScanListMatcher != null && scanListIds.isEmpty())
        {
            return;
        }

        int waveLength = checkedWaveLength(call.audioBuffers());

        if(waveLength < 0)
        {
            return;
        }

        byte[] wave = wave(call, waveLength);

        if(wave == null)
        {
            return;
        }

        long callIdSequence = mCallIdSequence.incrementAndGet();
        String id = mInstanceId + "-" + Long.toUnsignedString(callIdSequence, 36);
        long created = System.currentTimeMillis();
        Map<String,Object> metadata = metadata(id, call, created, scanListIds);

        synchronized(this)
        {
            if(!isCurrentGeneration(generation))
            {
                return;
            }

            commitPendingLoss();
            evictExpired(created);
            long cursor = ++mLatestCursor;
            CachedCall cachedCall = new CachedCall(cursor, id, wave, metadata, Set.copyOf(scanListIds), created);
            mCalls.addLast(cachedCall);
            mAudioBytes += wave.length;
            evictToLimits();
            mPublishedCalls.incrementAndGet();
            notifyAll();
        }
    }

    private boolean isCurrentGeneration(long generation)
    {
        return mRunning && generation == mRunGeneration.get();
    }

    private void evictExpired(long now)
    {
        while(!mCalls.isEmpty() && now - mCalls.getFirst().createdAtMs() > MAXIMUM_AGE_MS)
        {
            removeFirst();
        }
    }

    private void evictToLimits()
    {
        while(mCalls.size() > MAXIMUM_CACHED_CALLS || mAudioBytes > MAXIMUM_CACHED_AUDIO_BYTES)
        {
            removeFirst();
        }
    }

    /** Runs on the existing receiver-health sampler and releases the shared ring when no browser feed is active. */
    synchronized void maintain()
    {
        if(hasActiveFeed())
        {
            evictExpired(System.currentTimeMillis());
        }
        else
        {
            clearCalls();
        }
    }

    private void clearCalls()
    {
        if(!mCalls.isEmpty())
        {
            mDiscardedThroughCursor = Math.max(mDiscardedThroughCursor, mCalls.getLast().cursor());
        }

        mCalls.clear();
        mAudioBytes = 0;
    }

    private void removeFirst()
    {
        CachedCall removed = mCalls.pollFirst();

        if(removed != null)
        {
            mDiscardedThroughCursor = Math.max(mDiscardedThroughCursor, removed.cursor());
            mAudioBytes -= removed.wave().length;
        }
    }

    private Map<String,Object> metadata(String id, CompletedAudioCall call, long completedAt, Set<Long> scanListIds)
    {
        AudioCallSnapshot snapshot = call.snapshot();
        IdentifierCollection identifiers = snapshot.identifierCollection();
        Identifier<?> source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Identifier<?> target = identifiers != null ? identifiers.getToIdentifier() : null;
        AudioCallRecordingMetadata recordingMetadata = snapshot.recordingMetadata();
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        putText(value, "call_id", id);
        putText(value, "audio_url", StatsApiV1.CALLS + "/" + id + "/audio");
        value.put("started_at_ms", snapshot.startTimestamp());
        value.put("completed_at_ms", completedAt);
        value.put("duration_ms", call.getDuration());
        putText(value, "system", recordingMetadata != null ? recordingMetadata.systemName() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY));
        putText(value, "system_identity", recordingMetadata != null ? recordingMetadata.systemIdentity() : null);
        putText(value, "site", recordingMetadata != null ? recordingMetadata.siteName() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.SITE, Role.ANY));
        putText(value, "site_identity", recordingMetadata != null ? recordingMetadata.siteIdentity() : null);
        Object siteGuid = identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.RADRES_GUID, Role.ANY);
        putText(value, "site_guid", siteGuid);
        putText(value, "channel", recordingMetadata != null ? recordingMetadata.channelName() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY));
        putText(value, "channel_identity", recordingMetadata != null ? recordingMetadata.channelIdentity() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.UNIQUE_ID, Role.ANY));
        Object configurationId = identifierValue(identifiers, IdentifierClass.CONFIGURATION,
            Form.UNIQUE_ID, Role.ANY);
        putText(value, "configuration_id", configurationId);
        putText(value, "alias_list", recordingMetadata != null ? recordingMetadata.aliasListName() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.ALIAS_LIST, Role.ANY));
        putText(value, "decoder", identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.DECODER_TYPE,
            Role.ANY));
        putText(value, "source_id", recordingMetadata != null ? recordingMetadata.sourceValue() : value(source));
        putText(value, "source_alias", recordingMetadata != null ? recordingMetadata.sourceAlias() : null);
        putText(value, "source_description", recordingMetadata != null ? recordingMetadata.sourceDescription() : null,
            MAXIMUM_ALIAS_METADATA_TEXT_CHARACTERS);
        putText(value, "source_group", recordingMetadata != null ? recordingMetadata.sourceGroup() : null,
            MAXIMUM_ALIAS_METADATA_TEXT_CHARACTERS);
        putText(value, "source_form", form(source));
        putText(value, "talker_alias", identifierValue(identifiers, IdentifierClass.USER, Form.TALKER_ALIAS,
            Role.FROM));
        putText(value, "target_id", recordingMetadata != null ? recordingMetadata.destinationValue() : value(target));
        putText(value, "target_alias", recordingMetadata != null ? recordingMetadata.destinationAlias() : null);
        putText(value, "target_description",
            recordingMetadata != null ? recordingMetadata.destinationDescription() : null,
            MAXIMUM_ALIAS_METADATA_TEXT_CHARACTERS);
        putText(value, "target_group", recordingMetadata != null ? recordingMetadata.destinationGroup() : null,
            MAXIMUM_ALIAS_METADATA_TEXT_CHARACTERS);
        putText(value, "target_form", form(target));
        putText(value, "protocol", target != null && target.getProtocol() != null ? target.getProtocol().name() :
            recordingMetadata != null ? recordingMetadata.destinationProtocol() : null);
        putText(value, "conversation_key", conversationKey(snapshot, target));
        value.put("scan_list_ids", scanListIds != null ? scanListIds.stream()
            .sorted(Comparator.naturalOrder()).toList() : List.of());
        value.put("frequency_hz", longValue(identifiers, Form.CHANNEL_FREQUENCY));
        putText(value, "lcn", identifierValue(identifiers, IdentifierClass.DECODER, Form.CHANNEL_NAME,
            Role.BROADCAST));
        putIdentifierValue(value, "network_id", identifiers, Form.NETWORK);
        CallLegSource callLegSource = snapshot.callLegSource();
        P25SiteIdentity learnedP25Site = callLegSource != null ? callLegSource.p25SiteIdentity() : null;

        if(learnedP25Site != null)
        {
            putText(value, "wacn", learnedP25Site.wacn());
            putText(value, "system_id", learnedP25Site.system());
            putText(value, "rfss_id", learnedP25Site.rfss());
            putText(value, "site_id", learnedP25Site.site());
        }
        else
        {
            putIdentifierValue(value, "wacn", identifiers, Form.WACN);
            putIdentifierValue(value, "system_id", identifiers, Form.SYSTEM);
            putIdentifierValue(value, "rfss_id", identifiers, Form.RF_SUBSYSTEM);
            putIdentifierValue(value, "site_id", identifiers, Form.SITE);
        }

        putIdentifierValue(value, "nac", identifiers, Form.NETWORK_ACCESS_CODE);
        putIdentifierValue(value, "ran", identifiers, Form.RAN);
        WebEntityNavigationCatalog.Snapshot navigation = mNavigationCatalog != null ?
            mNavigationCatalog.snapshot() : WebEntityNavigationCatalog.Snapshot.empty();
        WebEntityNavigationCatalog.Channel channel = navigation.channel(
            configurationId != null ? String.valueOf(configurationId) : null,
            siteGuid != null ? String.valueOf(siteGuid) : null);

        if(channel != null)
        {
            WebEntityRef.put(value, channel.entityRef());

            if(channel.systemRef() != null)
            {
                value.put("system_entity_ref", channel.systemRef().toMap());
            }

            WebEntityRef sourceReference = navigationReference(channel, source);
            WebEntityRef targetReference = navigationReference(channel, target);

            if(sourceReference != null)
            {
                value.put("source_entity_ref", sourceReference.toMap());
            }
            if(targetReference != null)
            {
                value.put("target_entity_ref", targetReference.toMap());
            }
        }

        value.put("timeslot", snapshot.timeslot());
        value.put("encrypted", snapshot.isEncrypted());
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

    private static WebEntityRef navigationReference(WebEntityNavigationCatalog.Channel channel,
                                                    Identifier<?> identifier)
    {
        if(channel == null || identifier == null || identifier instanceof FullyQualifiedRadioIdentifier ||
            identifier instanceof FullyQualifiedTalkgroupIdentifier)
        {
            return null;
        }

        int value;

        if(identifier instanceof PatchGroupIdentifier patchIdentifier && patchIdentifier.getValue() != null &&
            patchIdentifier.getValue().getPatchGroup() != null)
        {
            value = patchIdentifier.getValue().getPatchGroup().getValue();
        }
        else if(identifier.getValue() instanceof Number number)
        {
            value = number.intValue();
        }
        else
        {
            return null;
        }

        return channel.identity(identifier.getForm(), identifier.getProtocol(), value);
    }

    private static void putIdentifierValue(Map<String,Object> values, String key, IdentifierCollection identifiers,
                                           Form form)
    {
        Object identifier = identifierValue(identifiers, IdentifierClass.NETWORK, form, Role.BROADCAST);

        if(identifier != null)
        {
            values.put(key, identifier);
        }
    }

    private static String conversationKey(AudioCallSnapshot snapshot, Identifier<?> target)
    {
        IdentifierCollection identifiers = snapshot != null ? snapshot.identifierCollection() : null;
        AudioCallRecordingMetadata recordingMetadata = snapshot != null ? snapshot.recordingMetadata() : null;
        Object configuredSystem = identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY);
        String stableSystem = recordingMetadata != null ? recordingMetadata.systemIdentity() : null;

        if(stableSystem == null || stableSystem.isBlank())
        {
            stableSystem = configuredSystem != null ? configuredSystem.toString() : "unknown";
        }

        String protocol = target != null && target.getProtocol() != null ? target.getProtocol().name() :
            recordingMetadata != null ? recordingMetadata.destinationProtocol() : null;

        if(protocol == null || protocol.isBlank())
        {
            Object decoder = identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.DECODER_TYPE, Role.ANY);
            protocol = decoder != null ? decoder.toString() : "unknown";
        }

        String prefix = protocol.trim().toLowerCase(Locale.ROOT) + ":" +
            stableSystem.trim().toLowerCase(Locale.ROOT);

        String destinationIdentity = recordingMetadata != null ? recordingMetadata.destinationIdentity() : null;

        if(target instanceof FullyQualifiedTalkgroupIdentifier fullyQualified)
        {
            return prefix + ":talkgroup:fq:" + (destinationIdentity != null && !destinationIdentity.isBlank() ?
                destinationIdentity.trim().toLowerCase(Locale.ROOT) :
                fullyQualified.getWacn() + ":" + fullyQualified.getSystem() + ":" +
                    fullyQualified.getTalkgroup());
        }
        else if(target instanceof NXDNFullyQualifiedTalkgroupIdentifier fullyQualified)
        {
            return prefix + ":talkgroup:fq:" + (destinationIdentity != null && !destinationIdentity.isBlank() ?
                destinationIdentity.trim().toLowerCase(Locale.ROOT) :
                fullyQualified.getSystem() + ":" + fullyQualified.getValue());
        }
        else if(target instanceof TalkgroupIdentifier talkgroup)
        {
            return prefix + ":talkgroup:" + talkgroup.getValue();
        }
        else if(target instanceof PatchGroupIdentifier patchIdentifier)
        {
            PatchGroup patchGroup = patchIdentifier.getValue();

            if(patchGroup != null && patchGroup.getPatchGroup() != null)
            {
                return prefix + ":patch:" + patchGroup.getPatchGroup().getValue();
            }
        }

        Object configuredChannel = identifierValue(identifiers, IdentifierClass.CONFIGURATION,
            Form.UNIQUE_ID, Role.ANY);
        String channel = recordingMetadata != null ? recordingMetadata.channelIdentity() : null;
        channel = channel != null && !channel.isBlank() ? channel :
            configuredChannel != null ? configuredChannel.toString() : "unknown";
        long frequency = longValue(identifiers, Form.CHANNEL_FREQUENCY);
        int timeslot = snapshot != null ? snapshot.timeslot() : 0;
        return prefix + ":channel:" + channel.trim().toLowerCase(Locale.ROOT) + ":frequency:" + frequency +
            ":slot:" + timeslot;
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

    private static void putText(Map<String,Object> values, String key, Object value, int maximumCharacters)
    {
        String bounded = boundedText(value, maximumCharacters);

        if(bounded != null)
        {
            values.put(key, bounded);
        }
    }

    static String boundedText(Object value)
    {
        return boundedText(value, MAXIMUM_METADATA_TEXT_CHARACTERS);
    }

    private static String boundedText(Object value, int maximumCharacters)
    {
        if(value == null)
        {
            return null;
        }

        String text = value.toString();

        if(text.length() <= maximumCharacters)
        {
            return text;
        }

        int end = maximumCharacters;

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
        if(mClosed.compareAndSet(false, true))
        {
            mEncoderWakeup.release();
            mEncoderThread.interrupt();
            try
            {
                mEncoderThread.join(TimeUnit.SECONDS.toMillis(2));
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** reset may accompany calls when the shared encoder lost work before those calls were published. */
    record FeedResult(String cursor, boolean reset, List<Map<String,Object>> calls)
    {
    }

    record CachedCall(long cursor, String id, byte[] wave, Map<String,Object> metadata, Set<Long> scanListIds,
                      long createdAtMs)
    {
    }
}
