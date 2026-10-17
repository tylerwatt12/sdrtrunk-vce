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
import io.github.dsheirer.audio.call.CallPlaybackTarget;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CallLegSummary;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.output.JmbeAudioOutput;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.audio.tone.ToneSummaryPayload;
import io.github.dsheirer.audio.tone.P25PagingToneService;
import io.github.dsheirer.dsp.gain.PeakAudioGain;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.P25IdentityFormatter;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.P25TalkgroupIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.protocol.Protocol;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
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
    private static final int MAXIMUM_PATCH_MEMBERS_PER_KIND = 32;
    static final int MAXIMUM_CALL_AUDIO_BYTES = 16 * 1024 * 1024;
    static final int WAVE_HEADER_BYTES = 44;
    private static final long MAXIMUM_AGE_MS = TimeUnit.MINUTES.toMillis(30);
    private static final int SAMPLE_RATE = 8000;
    private final CompletedCallScanListMatcher mScanListMatcher;
    private final WebEntityNavigationCatalog mNavigationCatalog;
    private final java.util.function.Function<CompletedAudioCall,JmbeAudioOutput.RenderedAudio> mAudioRenderer;
    private final java.util.function.Function<AudioCallSnapshot,CompletedToneSequence> mToneLookup;
    private final AutoCloseable mToneSubscription;
    private final BoundedMpscPairQueue<CompletedToneSequence,Object> mToneIngress = new BoundedMpscPairQueue<>(128);
    private final Deque<ToneMetadataUpdate> mToneMetadataUpdates = new ArrayDeque<>();
    private final AtomicLong mDroppedToneMetadata = new AtomicLong();
    private final AtomicLong mToneMetadataFailures = new AtomicLong();
    private static final int MAXIMUM_TONE_METADATA_UPDATES = 256;
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
    private final AtomicLong mOutputFailures = new AtomicLong();
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
        this(scanListModel, navigationCatalog, null, P25PagingToneService.getInstance()::lookupCompleted,
            P25PagingToneService.getInstance()::addCompletedToneListener);
    }

    StatsWebCallService(ScanListModel scanListModel, WebEntityNavigationCatalog navigationCatalog,
                       java.util.function.Supplier<java.nio.file.Path> libraryPath)
    {
        this(scanListModel, navigationCatalog, call -> JmbeAudioOutput.render(call, libraryPath.get()),
            P25PagingToneService.getInstance()::lookupCompleted,
            P25PagingToneService.getInstance()::addCompletedToneListener);
    }

    /** Worker-only output seam. Tone evidence always belongs to the original captured audio. */
    StatsWebCallService(ScanListModel scanListModel, WebEntityNavigationCatalog navigationCatalog,
                       java.util.function.Function<CompletedAudioCall,JmbeAudioOutput.RenderedAudio> renderer,
                       java.util.function.Function<AudioCallSnapshot,CompletedToneSequence> toneLookup)
    {
        this(scanListModel, navigationCatalog, renderer, toneLookup, ignored -> () -> {});
    }

    private StatsWebCallService(ScanListModel scanListModel, WebEntityNavigationCatalog navigationCatalog,
                       java.util.function.Function<CompletedAudioCall,JmbeAudioOutput.RenderedAudio> renderer,
                       java.util.function.Function<AudioCallSnapshot,CompletedToneSequence> toneLookup,
                       java.util.function.Function<io.github.dsheirer.sample.Listener<CompletedToneSequence>,AutoCloseable> subscribe)
    {
        mScanListMatcher = scanListModel != null ? new CompletedCallScanListMatcher(scanListModel) : null;
        mNavigationCatalog = navigationCatalog;
        mAudioRenderer = renderer != null ? renderer : call -> new JmbeAudioOutput.RenderedAudio(call.audioBuffers(), SAMPLE_RATE);
        mToneLookup = java.util.Objects.requireNonNull(toneLookup);
        mEncoderThread = new NamingThreadFactory("stats completed call audio").newThread(this::runEncoder);
        mEncoderThread.setPriority(Thread.MIN_PRIORITY + 1);
        mToneSubscription = subscribe.apply(this::receiveToneSequence);
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

    /** Shared observer callback: bounded references only; no cache locks, projection or client fan-out. */
    void receiveToneSequence(CompletedToneSequence sequence)
    {
        long generation = mRunGeneration.get();
        if(!mRunning || mClosed.get() || sequence == null || !hasActiveFeed()) return;
        if(mToneIngress.offer(sequence, ENCODER_WORK, generation)) mEncoderWakeup.release();
        else mDroppedToneMetadata.incrementAndGet();
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

        List<FeedEntry> available = new java.util.ArrayList<>();
        for(CachedCall call: mCalls)
        {
            if(call.cursor() > requestedCursor && intersects(call.scanListIds(), selected))
                available.add(new FeedEntry(call.cursor(), call.metadata()));
        }
        for(ToneMetadataUpdate update: mToneMetadataUpdates)
        {
            if(update.cursor() > requestedCursor && intersects(update.scanListIds(), selected))
                available.add(new FeedEntry(update.cursor(), update.metadata()));
        }
        // Metadata updates have their own cursor without reordering retained audio calls.
        available.sort(Comparator.comparingLong(FeedEntry::cursor));
        List<Map<String,Object>> calls = new java.util.ArrayList<>(MAXIMUM_FEED_CALLS);
        long nextCursor = mLatestCursor;
        for(FeedEntry entry: available)
        {
            calls.add(entry.metadata());
            if(calls.size() == MAXIMUM_FEED_CALLS)
            {
                nextCursor = entry.cursor();
                break;
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
            Map.entry("encoder_failures", mEncoderFailures.get()),
            Map.entry("audio_output_failures", mOutputFailures.get()),
            Map.entry("tone_metadata_queue_depth", mToneIngress.size()),
            Map.entry("dropped_tone_metadata", mDroppedToneMetadata.get()),
            Map.entry("tone_metadata_failures", mToneMetadataFailures.get()));
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
                    drainToneMetadataSafely();
                }
                drainToneMetadataSafely();
            }
        }
        finally
        {
            mEncoderIngress.clear();
            mToneIngress.clear();
        }
    }

    private CompletedToneSequence lookupTones(AudioCallSnapshot snapshot)
    {
        try { return mToneLookup.apply(snapshot); }
        catch(RuntimeException exception)
        {
            mToneMetadataFailures.incrementAndGet();
            return null;
        }
    }

    /** Late results update details only; audio bytes and scheduling remain unchanged. */
    private void drainToneMetadataSafely()
    {
        try { drainToneMetadata(); }
        catch(RuntimeException exception) { mToneMetadataFailures.incrementAndGet(); }
    }

    private void drainToneMetadata()
    {
        BoundedMpscPairQueue.Entry<CompletedToneSequence,Object> work;
        while(!mClosed.get() && (work = mToneIngress.poll()) != null)
        {
            CompletedToneSequence sequence = work.first();
            if(!isCurrentGeneration(work.stamp()) || sequence.tones().isEmpty() ||
                lookupTones(sequence.snapshot()) != sequence) continue;
            Map<String,Object> summary = ToneSummaryPayload.summary(sequence);
            synchronized(this)
            {
                if(!isCurrentGeneration(work.stamp()) || lookupTones(sequence.snapshot()) != sequence) continue;
                evictExpired(System.currentTimeMillis());
                Deque<CachedCall> retained = new ArrayDeque<>(mCalls.size());
                for(CachedCall call: mCalls)
                {
                    if(sequence.matches(call.snapshot()) && !summary.equals(call.metadata().get("paging_tone_summary")))
                    {
                        Map<String,Object> metadata = new LinkedHashMap<>(call.metadata());
                        metadata.put("paging_tone_summary", summary);
                        Map<String,Object> immutable = java.util.Collections.unmodifiableMap(metadata);
                        retained.addLast(new CachedCall(call.cursor(), call.id(), call.wave(), immutable,
                            call.scanListIds(), call.createdAtMs(), call.snapshot()));
                        Map<String,Object> update = new LinkedHashMap<>(immutable);
                        update.put("audio_metadata_updated", true);
                        mToneMetadataUpdates.addLast(new ToneMetadataUpdate(++mLatestCursor, call.id(),
                            java.util.Collections.unmodifiableMap(update), call.scanListIds()));
                        while(mToneMetadataUpdates.size() > MAXIMUM_TONE_METADATA_UPDATES)
                        {
                            mToneMetadataUpdates.removeFirst();
                            mDroppedToneMetadata.incrementAndGet();
                        }
                        notifyAll();
                    }
                    else retained.addLast(call);
                }
                mCalls.clear();
                mCalls.addAll(retained);
            }
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

        //All analysis and inference remain on this one bounded low-priority worker. Native call PCM is read-only.
        boolean phase1 = snapshot.callLegSource().decoderType() == DecoderType.P25_PHASE1 ||
            snapshot.callLegSource().decoderType() == DecoderType.P25_CONVENTIONAL;
        //The shared receiver observer owns both PCM detection and encoded-tone interpretation.
        AudioCallSnapshot nativeSnapshot = call.nativeAudioSnapshot();
        CompletedToneSequence tones = lookupTones(nativeSnapshot);
        CompletedAudioCall processingCall = tones != null && tones.matches(nativeSnapshot) ?
            call.withCompletedTones(tones) : call;
        JmbeAudioOutput.RenderedAudio rendered;
        try
        {
            rendered = mAudioRenderer.apply(processingCall);
            if(rendered == null) throw new IllegalStateException("Audio output is unavailable");
        }
        catch(RuntimeException | LinkageError failure)
        {
            mOutputFailures.incrementAndGet();
            rendered = new JmbeAudioOutput.RenderedAudio(call.audioBuffers(), SAMPLE_RATE);
        }
        if(checkedWaveLength(rendered.buffers()) < 0)
        {
            mOutputFailures.incrementAndGet();
            rendered = JmbeAudioOutput.nativeAudio(call);
        }
        List<float[]> output = rendered.buffers();
        int sampleRate = rendered.sampleRate();
        float gain = phase1 ? new PeakAudioGain(5.0f, 0.95f).calculateGain(output) : 1.0f;
        byte[] wave = wave(output, checkedWaveLength(output), sampleRate, gain);

        if(wave == null)
        {
            return;
        }

        long callIdSequence = mCallIdSequence.incrementAndGet();
        String id = mInstanceId + "-" + Long.toUnsignedString(callIdSequence, 36);
        long created = System.currentTimeMillis();
        Map<String,Object> metadata = new LinkedHashMap<>(metadata(id, call, created, scanListIds));
        metadata.put("audio_sample_rate", sampleRate);
        synchronized(this)
        {
            if(!isCurrentGeneration(generation))
            {
                return;
            }

            commitPendingLoss();
            evictExpired(created);
            if(tones != null && !tones.tones().isEmpty() && tones.matches(nativeSnapshot) && lookupTones(nativeSnapshot) == tones)
                metadata.put("paging_tone_summary", ToneSummaryPayload.summary(tones));
            long cursor = ++mLatestCursor;
            CachedCall cachedCall = new CachedCall(cursor, id, wave, java.util.Collections.unmodifiableMap(metadata),
                Set.copyOf(scanListIds), created, nativeSnapshot);
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
            mDiscardedThroughCursor = Math.max(mDiscardedThroughCursor, mLatestCursor);
        }

        mCalls.clear();
        mToneMetadataUpdates.clear();
        mAudioBytes = 0;
    }

    private void removeFirst()
    {
        CachedCall removed = mCalls.pollFirst();

        if(removed != null)
        {
            mDiscardedThroughCursor = Math.max(mDiscardedThroughCursor, removed.cursor());
            mAudioBytes -= removed.wave().length;
            mToneMetadataUpdates.removeIf(update -> update.callId().equals(removed.id()));
        }
    }

    private Map<String,Object> metadata(String id, CompletedAudioCall call, long completedAt, Set<Long> scanListIds)
    {
        AudioCallSnapshot snapshot = call.snapshot();
        IdentifierCollection identifiers = snapshot.identifierCollection();
        Identifier<?> source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Identifier<?> target = identifiers != null ? identifiers.getToIdentifier() : null;
        AudioCallRecordingMetadata recordingMetadata = snapshot.recordingMetadata();
        CallLegSource callLegSource = snapshot.callLegSource();
        String servingSystemKey = RadioSystemKey.p25Serving(
            callLegSource != null ? callLegSource.radioSystemKey() : null,
            callLegSource != null ? callLegSource.p25SiteIdentity() : null, call.recordingIdentifierCollection());
        CallPlaybackTarget playbackTarget = CallPlaybackTarget.from(snapshot, target);
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        putText(value, "call_id", id);
        putText(value, "audio_url", StatsApiV1.CALLS + "/" + id + "/audio");
        value.put("started_at_ms", snapshot.startTimestamp());
        value.put("completed_at_ms", completedAt);
        value.put("duration_ms", call.getDuration());
        putText(value, "system", recordingMetadata != null ? recordingMetadata.systemName() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY));
        putText(value, "radio_system_key", playbackTarget != null ? playbackTarget.radioSystemKey() : null);
        value.put("serving_system_key", servingSystemKey);
        putText(value, "site", recordingMetadata != null ? recordingMetadata.siteName() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.SITE, Role.ANY));
        putText(value, "channel", recordingMetadata != null ? recordingMetadata.channelName() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY));
        Object identifierConfigurationId = identifierValue(identifiers, IdentifierClass.CONFIGURATION,
            Form.UNIQUE_ID, Role.ANY);
        String configurationId = callLegSource != null && callLegSource.channelConfigurationId() != null ?
            callLegSource.channelConfigurationId() : identifierConfigurationId != null ?
                String.valueOf(identifierConfigurationId) : null;
        putText(value, "configuration_id", configurationId);
        putText(value, "alias_list", recordingMetadata != null ? recordingMetadata.aliasListName() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.ALIAS_LIST, Role.ANY));
        putText(value, "decoder", callLegSource != null && callLegSource.decoderType() != null ?
            callLegSource.decoderType().name() :
            identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.DECODER_TYPE, Role.ANY));
        String sourceValue = recordingMetadata != null ? recordingMetadata.sourceValue() : value(source);
        if(sourceValue == null && source != null && source.getForm() == Form.TALKGROUP)
        {
            sourceValue = value(source);
        }
        putText(value, "source_id", sourceValue);
        if(recordingMetadata != null && recordingMetadata.sourceP25Identity() != null)
        {
            value.put("source_canonical_identity", Map.of(
                "wacn", recordingMetadata.sourceP25Identity().homeWacn(),
                "system_id", recordingMetadata.sourceP25Identity().homeSystemId(),
                "subscriber_id", recordingMetadata.sourceP25Identity().subscriberId()));
            if(validWorkingSubscriber(recordingMetadata.sourceObservedWorkingId()))
            {
                value.put("source_observed_working_id", recordingMetadata.sourceObservedWorkingId());
            }
        }
        putP25Identity(value, "source_", source);
        putText(value, "source_alias", recordingMetadata != null ? recordingMetadata.sourceAlias() : null);
        putText(value, "source_description", recordingMetadata != null ? recordingMetadata.sourceDescription() : null,
            MAXIMUM_ALIAS_METADATA_TEXT_CHARACTERS);
        putText(value, "source_group", recordingMetadata != null ? recordingMetadata.sourceGroup() : null,
            MAXIMUM_ALIAS_METADATA_TEXT_CHARACTERS);
        putText(value, "source_form", form(source));
        putText(value, "talker_alias", TalkerAliasIdentifier.valueFor(identifiers));
        putText(value, "target_id", recordingMetadata != null ? recordingMetadata.destinationValue() : value(target));
        if(recordingMetadata != null && recordingMetadata.destinationP25Identity() != null)
        {
            value.put("target_canonical_identity", Map.of(
                "wacn", recordingMetadata.destinationP25Identity().homeWacn(),
                "system_id", recordingMetadata.destinationP25Identity().homeSystemId(),
                "subscriber_id", recordingMetadata.destinationP25Identity().subscriberId()));
            if(validWorkingSubscriber(recordingMetadata.destinationObservedWorkingId()))
            {
                value.put("target_observed_working_id", recordingMetadata.destinationObservedWorkingId());
            }
        }
        putP25Identity(value, "target_", target);
        if(target instanceof PatchGroupIdentifier patch && patch.getValue() != null)
        {
            value.put("patch_group_id", patch.getValue().getPatchGroup().getValue());
            List<Map<String,Object>> members = Stream.concat(
                patch.getValue().getPatchedTalkgroupIdentifiers().stream().limit(MAXIMUM_PATCH_MEMBERS_PER_KIND),
                patch.getValue().getPatchedRadioIdentifiers().stream().limit(MAXIMUM_PATCH_MEMBERS_PER_KIND))
                .map(member -> {
                    Map<String,Object> facts = new LinkedHashMap<>();
                    facts.put("kind", member.getForm() == Form.RADIO ? "radio" : "talkgroup");
                    facts.put("id", member.getValue());
                    facts.put("serving_system_key", servingSystemKey);
                    putP25Identity(facts, "", member);
                    return java.util.Collections.unmodifiableMap(facts);
                }).toList();
            int memberCount = patch.getValue().getPatchedTalkgroupIdentifiers().size() +
                patch.getValue().getPatchedRadioIdentifiers().size();
            value.put("patch_members", members);
            value.put("patch_member_count", memberCount);
            value.put("patch_members_truncated", memberCount > members.size());
        }
        putText(value, "target_alias", recordingMetadata != null ? recordingMetadata.destinationAlias() : null);
        putText(value, "target_description",
            recordingMetadata != null ? recordingMetadata.destinationDescription() : null,
            MAXIMUM_ALIAS_METADATA_TEXT_CHARACTERS);
        putText(value, "target_group", recordingMetadata != null ? recordingMetadata.destinationGroup() : null,
            MAXIMUM_ALIAS_METADATA_TEXT_CHARACTERS);
        putText(value, "target_form", form(target));
        Protocol sourceProtocol = callLegSource != null && callLegSource.decoderType() != null ?
            canonicalProtocol(callLegSource.decoderType().getProtocol()) : null;
        putText(value, "protocol", sourceProtocol != null ? sourceProtocol.name() :
            recordingMetadata != null && recordingMetadata.destinationProtocol() != null ?
                recordingMetadata.destinationProtocol() :
                target != null && target.getProtocol() != null ? canonicalProtocol(target.getProtocol()).name() :
                    null);
        if(playbackTarget != null)
        {
            value.put("playback_target", playbackTarget.toMap(playbackTargetLabel(playbackTarget,
                recordingMetadata, target, servingSystemKey)));
        }
        value.put("scan_list_ids", scanListIds != null ? scanListIds.stream()
            .sorted(Comparator.naturalOrder()).toList() : List.of());
        value.put("frequency_hz", longValue(identifiers, Form.CHANNEL_FREQUENCY));
        putText(value, "lcn", identifierValue(identifiers, IdentifierClass.DECODER, Form.CHANNEL_NAME,
            Role.BROADCAST));
        putIdentifierValue(value, "network_id", identifiers, Form.NETWORK);
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
        WebEntityNavigationCatalog.Channel channel = navigation.channel(configurationId);

        if(channel != null)
        {
            WebEntityRef.put(value, channel.entityRef());

            String eventRadioSystemKey = playbackTarget != null ? playbackTarget.radioSystemKey() : null;
            boolean catalogMatchesEvent = eventRadioSystemKey != null && channel.radioSystemRef() != null &&
                eventRadioSystemKey.equals(channel.radioSystemRef().key());

            if(catalogMatchesEvent)
            {
                value.put("radio_system_entity_ref", channel.radioSystemRef().toMap());
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
        return java.util.Collections.unmodifiableMap(value);
    }

    private static boolean validWorkingSubscriber(Integer value)
    {
        return value != null && value >= 1 && value <= RadioSystemIdentityKey.MAX_P25_WORKING_UNIT_ID;
    }

    /** Projects this completed call's detached facts; no live assignment, alias or database lookup is needed. */
    private static void putP25Identity(Map<String,Object> values, String prefix, Identifier<?> identifier)
    {
        CallLegSummary.P25IdentityObservation observation = CallLegSummary.p25IdentityObservation(identifier,
            identifier instanceof PatchGroupIdentifier);
        if(observation == null) return;
        values.put(prefix + "observed_local_id", observation.form() == Form.RADIO ?
            Integer.valueOf(observation.observedLocalId()) : observation.explicitlyObservedLocalAddress());

        Identifier<?> primary = identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null ?
            patch.getValue().getPatchGroup() : identifier;
        P25TalkgroupIdentity group = P25TalkgroupIdentity.from(primary);
        if(group != null)
        {
            values.put(prefix + "canonical_group_identity", Map.of("wacn", group.homeWacn(),
                "system_id", group.homeSystemId(), "talkgroup_id", group.talkgroupId()));
        }
        else if(!values.containsKey(prefix + "canonical_identity"))
        {
            P25SubscriberIdentity radio = P25SubscriberIdentity.from(primary);
            if(radio != null)
            {
                values.put(prefix + "canonical_identity", Map.of("wacn", radio.homeWacn(),
                    "system_id", radio.homeSystemId(), "subscriber_id", radio.subscriberId()));
                if(validWorkingSubscriber(observation.observedWorkingId()))
                {
                    values.put(prefix + "observed_working_id", observation.observedWorkingId());
                }
            }
        }
    }

    private static WebEntityRef navigationReference(WebEntityNavigationCatalog.Channel channel,
                                                    Identifier<?> identifier)
    {
        if(channel == null || identifier == null)
        {
            return null;
        }

        if(identifier instanceof FullyQualifiedRadioIdentifier radio)
        {
            return channel.identity(Form.RADIO, identifier.getProtocol(), radio.getRadio(), radio.getWacn(),
                radio.getSystem());
        }
        if(identifier instanceof FullyQualifiedTalkgroupIdentifier talkgroup)
        {
            return channel.identity(Form.TALKGROUP, identifier.getProtocol(), talkgroup.getTalkgroup(),
                talkgroup.getWacn(), talkgroup.getSystem());
        }

        int value;

        if(identifier instanceof PatchGroupIdentifier patchIdentifier && patchIdentifier.getValue() != null &&
            patchIdentifier.getValue().getPatchGroup() instanceof FullyQualifiedTalkgroupIdentifier talkgroup)
        {
            return channel.identity(Form.PATCH_GROUP, identifier.getProtocol(), talkgroup.getTalkgroup(),
                talkgroup.getWacn(), talkgroup.getSystem());
        }
        else if(identifier instanceof PatchGroupIdentifier patchIdentifier && patchIdentifier.getValue() != null &&
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

    private static String playbackTargetLabel(CallPlaybackTarget playbackTarget,
                                              AudioCallRecordingMetadata recordingMetadata,
                                              Identifier<?> target, String servingSystemKey)
    {
        if(playbackTarget == null)
        {
            return null;
        }

        if(playbackTarget.kind() == CallPlaybackTarget.Kind.CHANNEL ||
            playbackTarget.kind() == CallPlaybackTarget.Kind.CHANNEL_TIMESLOT)
        {
            String channel = recordingMetadata != null ? recordingMetadata.channelName() : null;
            String label = channel != null && !channel.isBlank() ? channel.strip() : "Saved channel";
            return playbackTarget.kind() == CallPlaybackTarget.Kind.CHANNEL_TIMESLOT ?
                label + " · Timeslot " + playbackTarget.timeslot() : label;
        }

        String alias = recordingMetadata != null ? recordingMetadata.destinationAlias() : null;
        String canonicalRadio = playbackTarget.kind() == CallPlaybackTarget.Kind.RADIO ?
            canonicalRadioLabel(recordingMetadata, target, servingSystemKey) : null;

        if(alias != null && !alias.isBlank())
        {
            return canonicalRadio != null ? alias.strip() + " · " + canonicalRadio : alias.strip();
        }

        if(canonicalRadio != null)
        {
            return canonicalRadio;
        }

        String identifier = target instanceof FullyQualifiedTalkgroupIdentifier || target instanceof PatchGroupIdentifier ?
            P25IdentityFormatter.display(target, servingSystemKey) :
            recordingMetadata != null ? recordingMetadata.destinationValue() : null;

        if(identifier == null || identifier.isBlank())
        {
            identifier = target != null && target.getValue() != null ? target.getValue().toString() : null;
        }

        String kind = switch(playbackTarget.kind())
        {
            case TALKGROUP -> "Talkgroup";
            case PATCH_GROUP -> "Patch group";
            case RADIO -> "Radio";
            default -> "Target";
        };
        return identifier != null && !identifier.isBlank() ? kind + ' ' + identifier : kind;
    }

    private static String canonicalRadioLabel(AudioCallRecordingMetadata recordingMetadata, Identifier<?> target,
                                              String servingSystemKey)
    {
        P25SubscriberIdentity identity = recordingMetadata != null ?
            recordingMetadata.destinationP25Identity() : null;
        Integer workingId = recordingMetadata != null ? recordingMetadata.destinationObservedWorkingId() : null;
        if(identity == null)
        {
            identity = P25SubscriberIdentity.from(target);
            workingId = target instanceof FullyQualifiedRadioIdentifier radio ? radio.getWorkingAddress() : null;
        }
        if(identity == null)
        {
            return null;
        }

        return P25IdentityFormatter.display(identity.homeWacn(), identity.homeSystemId(),
            identity.subscriberId(), validWorkingSubscriber(workingId) ? workingId : null, servingSystemKey);
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
        return wave(audioBuffers, waveLength, SAMPLE_RATE, 1.0f);
    }

    private static byte[] wave(List<float[]> audioBuffers, int waveLength, int sampleRate, float gain)
    {

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
        wave.putInt(sampleRate);
        wave.putInt(sampleRate * Short.BYTES);
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
                sample *= gain;
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

    private static Protocol canonicalProtocol(Protocol protocol)
    {
        return protocol == Protocol.APCO25_PHASE2 ? Protocol.APCO25 : protocol;
    }

    @Override
    public void close()
    {
        stop();
        if(mClosed.compareAndSet(false, true))
        {
            try { mToneSubscription.close(); }
            catch(Exception exception) { /* Closing a best-effort observer cannot prevent audio worker shutdown. */ }
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
                      long createdAtMs, AudioCallSnapshot snapshot)
    {
    }

    private record ToneMetadataUpdate(long cursor, String callId, Map<String,Object> metadata, Set<Long> scanListIds) {}
    private record FeedEntry(long cursor, Map<String,Object> metadata) {}
}
