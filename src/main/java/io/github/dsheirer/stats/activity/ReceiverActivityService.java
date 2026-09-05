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

package io.github.dsheirer.stats.activity;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataListener;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataListener;
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DMRConventionalCallEvent;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallEvent;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelConfirmationEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallAttributionEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.concurrent.BoundedMpscPairQueue;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns statistics collection and maintenance and keeps SQLite work off decoder/UI threads.
 */
public class ReceiverActivityService implements SiteMetadataListener, ProtocolSiteMetadataListener
{
    private static final Logger mLog = LoggerFactory.getLogger(ReceiverActivityService.class);
    private static final long DEDUPE_RETENTION_MILLISECONDS = 60000;
    private static final long LOGICAL_NOTIFICATION_RETENTION_MILLISECONDS = TimeUnit.HOURS.toMillis(24);
    private static final int MAXIMUM_LOGICAL_NOTIFICATIONS = 65_536;
    static final long PROTOCOL_SIGNAL_DEDUPE_WINDOW_MILLISECONDS = 500;
    static final int OBSERVATION_QUEUE_SIZE = 4_096;
    private static final int MAXIMUM_DRAIN_PER_RUN = 1_024;
    private static final Object SINGLE_OBSERVATION = new Object();
    private static final long DEFAULT_DISPOSE_TIMEOUT_MILLISECONDS = 2_000;
    private static final long DRAIN_BARRIER_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final UserPreferences mUserPreferences;
    private final ReceiverActivityMapper mMapper = new ReceiverActivityMapper();
    private final TrunkedCallActivityMapper mTrunkedCallMapper = new TrunkedCallActivityMapper();
    private final P25GrantFactConfirmationTracker mGrantFactConfirmationTracker =
        new P25GrantFactConfirmationTracker();
    private final BiConsumer<Channel,IDecodeEvent> mDecodeEventListener = this::receiveDecodeEvent;
    private final Listener<ControlChannelQualitySnapshot> mQualityListener = this::receiveControlChannelQuality;
    private final Map<String,Long> mRecentDedupeKeys = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<String,Long> mRecentLogicalNotifications = new LinkedHashMap<>(1024, 0.75f, true);
    private final Map<String,TrunkedSiteEvidence> mObservedTrunkedSites = new ConcurrentHashMap<>();
    /* One preallocated queue per collection epoch preserves callback order across all observation types. */
    private volatile BoundedMpscPairQueue<Object,Object> mObservationIngress =
        new BoundedMpscPairQueue<>(OBSERVATION_QUEUE_SIZE);
    private final ExecutorService mObservationWorker = Executors.newSingleThreadExecutor(
        new ObserverThreadFactory("sdrtrunk activity observation mapper"));
    private final Semaphore mObservationWakeup = new Semaphore(0);
    private final AtomicLong mObservationDrops = new AtomicLong();
    private final AtomicBoolean mDisposed = new AtomicBoolean();
    private final AtomicBoolean mObservationStateClearRequested = new AtomicBoolean();
    private final AtomicBoolean mWriterTransitionActive = new AtomicBoolean();
    private final AtomicReference<WriterTransition> mWriterTransition = new AtomicReference<>();
    /* Includes active, retired, and not-yet-installed candidates until their executor is confirmed terminated. */
    private final Set<ReceiverActivityWriter> mStartedWriters = ConcurrentHashMap.newKeySet();
    private final long mDisposeTimeoutMilliseconds;
    private final Runnable mAfterIngressSnapshotForTest;
    private final Runnable mBeforeWriterActivationForTest;
    private final WriterFactory mWriterFactory;
    private volatile ReceiverActivityWriter mWriter;
    private volatile boolean mCollectionEnabled;
    private volatile boolean mObservationWorkerStarted;
    private BoundedMpscPairQueue<Object,Object> mWorkerObservationIngress;
    private Path mCurrentDatabasePath;
    private ReceiverActivityWriter.WriterStatus mLastWriterStatus;

    public ReceiverActivityService(UserPreferences userPreferences)
    {
        this(userPreferences, DEFAULT_DISPOSE_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS, null, null,
            ReceiverActivityWriter::new);
    }

    ReceiverActivityService(UserPreferences userPreferences, long disposeTimeout, TimeUnit unit)
    {
        this(userPreferences, disposeTimeout, unit, null, null, ReceiverActivityWriter::new);
    }

    ReceiverActivityService(UserPreferences userPreferences, long disposeTimeout, TimeUnit unit,
                          Runnable afterIngressSnapshotForTest)
    {
        this(userPreferences, disposeTimeout, unit, afterIngressSnapshotForTest, null,
            ReceiverActivityWriter::new);
    }

    ReceiverActivityService(UserPreferences userPreferences, long disposeTimeout, TimeUnit unit,
                          Runnable afterIngressSnapshotForTest, Runnable beforeWriterActivationForTest)
    {
        this(userPreferences, disposeTimeout, unit, afterIngressSnapshotForTest, beforeWriterActivationForTest,
            ReceiverActivityWriter::new);
    }

    ReceiverActivityService(UserPreferences userPreferences, long disposeTimeout, TimeUnit unit,
                          Runnable afterIngressSnapshotForTest, Runnable beforeWriterActivationForTest,
                          WriterFactory writerFactory)
    {
        mUserPreferences = userPreferences;
        java.util.Objects.requireNonNull(unit, "unit cannot be null");
        mDisposeTimeoutMilliseconds = Math.max(0, unit.toMillis(disposeTimeout));
        mAfterIngressSnapshotForTest = afterIngressSnapshotForTest;
        mBeforeWriterActivationForTest = beforeWriterActivationForTest;
        mWriterFactory = java.util.Objects.requireNonNull(writerFactory, "writerFactory cannot be null");
        MyEventBus.getGlobalEventBus().register(this);
        updateWriterState();
        mObservationWorkerStarted = true;
        mObservationWorker.execute(this::runObservationWorker);
    }

    private void runObservationWorker()
    {
        try
        {
            while(!mDisposed.get())
            {
                WriterTransition writerTransition = mWriterTransition.getAndSet(null);

                if(writerTransition != null)
                {
                    replaceWriterOnWorker(writerTransition);
                    continue;
                }
                else if(mCollectionEnabled)
                {
                    drainObservationsSafely();
                }
                else if(mObservationStateClearRequested.compareAndSet(true, false))
                {
                    clearObservationStateOnWorker();
                }

                try
                {
                    if(mCollectionEnabled)
                    {
                        mObservationWakeup.tryAcquire(10, TimeUnit.MILLISECONDS);
                    }
                    else
                    {
                        mObservationWakeup.acquire();
                    }

                    mObservationWakeup.drainPermits();
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
            cleanupObservationsOnWorker();
            stopWriter();
        }
    }

    /**
     * Listener for decoded events.
     */
    public BiConsumer<Channel,IDecodeEvent> getDecodeEventListener()
    {
        return mDecodeEventListener;
    }

    public Listener<ControlChannelQualitySnapshot> getControlChannelQualityListener()
    {
        return mQualityListener;
    }

    public void receiveRecordedCall(CompletedAudioCall call)
    {
        receiveCallOutput(call, ReceiverActivityRecords.CallOutput.RECORDED);
    }

    public void receiveStreamedCall(CompletedAudioCall call)
    {
        receiveCallOutput(call, ReceiverActivityRecords.CallOutput.STREAMED);
    }

    private void receiveCallOutput(CompletedAudioCall call, ReceiverActivityRecords.CallOutput output)
    {
        offerObservation(withoutAudio(call), output);
    }

    /** Receives the one global winner after all eligible receiver legs have been resolved. */
    public void receiveResolvedCall(CompletedAudioCall call)
    {
        offerObservation(withoutAudio(call));
    }

    /**
     * Waits until the observation worker has processed every observation accepted before this method publishes its
     * barrier. Callers must first stop the decoder, resolver, recording, and streaming producers whose final
     * notifications they need to preserve. The barrier only drains the observer handoff; {@link #dispose()} then
     * closes the database writer and drains its already-mapped records.
     *
     * <p>This method is for bounded lifecycle coordination and must never be called from a decoder or receiver
     * callback.</p>
     *
     * @return true when the barrier was processed, or false when the service changed collection epochs, was disposed,
     * or did not drain within the supplied timeout
     */
    public boolean awaitObservationDrain(long timeout, TimeUnit unit)
    {
        java.util.Objects.requireNonNull(unit, "unit cannot be null");
        long timeoutNanos = Math.max(0L, unit.toNanos(timeout));
        long startedNanos = System.nanoTime();
        ObservationDrainBarrier barrier = new ObservationDrainBarrier();
        BoundedMpscPairQueue<Object,Object> ingress;

        while(true)
        {
            if(mDisposed.get())
            {
                return false;
            }

            if(!mCollectionEnabled)
            {
                return true;
            }

            ingress = mObservationIngress;

            if(ingress.offer(barrier, SINGLE_OBSERVATION))
            {
                break;
            }

            long elapsedNanos = System.nanoTime() - startedNanos;

            if(elapsedNanos >= timeoutNanos || Thread.currentThread().isInterrupted() ||
                ingress != mObservationIngress || !mCollectionEnabled || mDisposed.get())
            {
                return false;
            }

            mObservationWakeup.release();
            LockSupport.parkNanos(this, Math.min(DRAIN_BARRIER_RETRY_NANOS, timeoutNanos - elapsedNanos));
        }

        mObservationWakeup.release();

        if(ingress != mObservationIngress || !mCollectionEnabled || mDisposed.get())
        {
            return false;
        }

        long elapsedNanos = System.nanoTime() - startedNanos;
        long remainingNanos = Math.max(0L, timeoutNanos - elapsedNanos);

        try
        {
            boolean drained = barrier.await(remainingNanos, TimeUnit.NANOSECONDS);
            return drained && ingress == mObservationIngress && mCollectionEnabled && !mDisposed.get();
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static CompletedAudioCall withoutAudio(CompletedAudioCall call)
    {
        return call != null ? new CompletedAudioCall(call.logicalCallId(), call.snapshot(), List.of(),
            call.resolvedPolicy(), call.callLegSummaries()) : null;
    }

    private void processResolvedCall(CompletedAudioCall call)
    {
        ReceiverActivityWriter writer = getCollectionWriter();
        if(writer == null)
        {
            return;
        }

        ReceiverActivityRecords.ResolvedLogicalCall resolved = mMapper.mapResolvedLogicalCall(call);
        if(resolved != null && firstLogicalNotification(resolved.logicalCallId().toString() + "|resolved"))
        {
            enqueueObservation(writer, resolved);
        }
    }

    private void processCallOutput(CompletedAudioCall call, ReceiverActivityRecords.CallOutput output)
    {
        ReceiverActivityWriter writer = getCollectionWriter();
        if(writer == null)
        {
            return;
        }

        ReceiverActivityRecords.ResolvedLogicalCall resolved = mMapper.mapResolvedLogicalCall(call);
        ReceiverActivityRecords.ConventionalCallOutput conventional = resolved == null ?
            mMapper.mapConventionalCallOutput(call, output) : null;
        if(resolved != null && firstLogicalNotification(resolved.logicalCallId() + "|" + output.name()))
        {
            enqueueObservation(writer, new ReceiverActivityRecords.LogicalCallOutput(resolved, output));
        }
        else if(conventional != null && call != null &&
            firstLogicalNotification(call.logicalCallId() + "|conventional|" + output.name()))
        {
            enqueueObservation(writer, conventional);
        }
    }

    private boolean firstLogicalNotification(String key)
    {
        long now = System.currentTimeMillis();
        //Logical output retries may arrive much later than signaling repeats, so they have a separate bounded
        //24-hour idempotency window instead of the short protocol-event cache.
        synchronized(mRecentLogicalNotifications)
        {
            Iterator<Map.Entry<String,Long>> iterator = mRecentLogicalNotifications.entrySet().iterator();
            while(iterator.hasNext())
            {
                Map.Entry<String,Long> entry = iterator.next();
                if(now >= entry.getValue() && now - entry.getValue() > LOGICAL_NOTIFICATION_RETENTION_MILLISECONDS)
                {
                    iterator.remove();
                }
            }
            if(mRecentLogicalNotifications.put(key, now) != null)
            {
                return false;
            }
            while(mRecentLogicalNotifications.size() > MAXIMUM_LOGICAL_NOTIFICATIONS)
            {
                Iterator<String> keys = mRecentLogicalNotifications.keySet().iterator();
                if(keys.hasNext())
                {
                    keys.next();
                    keys.remove();
                }
            }
            return true;
        }
    }

    private void receiveControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        offerObservation(snapshot);
    }

    private void processControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        if(snapshot == null || !snapshot.matchesCurrentChannel())
        {
            //A queued measurement whose retained channel no longer has the captured receiver configuration must not
            //update current site state.  The quality monitor separately rejects callbacks from a stopped lifecycle.
            return;
        }

        ReceiverActivityWriter writer = getCollectionWriter();

        if(snapshot != null && !snapshot.active() && snapshot.configurationId() != null)
        {
            mObservedTrunkedSites.computeIfPresent(snapshot.configurationId(), (_, evidence) ->
                evidence.receiverContext().isSameReceiver(snapshot.receiverContext()) ? null : evidence);
        }

        TrunkedSiteEvidence evidence = snapshot != null && snapshot.configurationId() != null ?
            mObservedTrunkedSites.get(snapshot.configurationId()) : null;
        boolean observedTrunkedSite = hasCurrentTrunkedSiteEvidence(snapshot, evidence);

        if(evidence != null && !observedTrunkedSite)
        {
            mObservedTrunkedSites.remove(snapshot.configurationId(), evidence);
        }

        if(writer != null && shouldPersistControlChannelQuality(snapshot, observedTrunkedSite) &&
            snapshot.active() && snapshot.configurationId() != null && !snapshot.configurationId().isBlank() &&
                snapshot.frequencyHz() > 0)
        {
            SiteReceiverContext receiverContext = snapshot.receiverContext();
            DecoderType decoderType = receiverContext != null ? receiverContext.decoderType() : null;
            TrunkedIdentityDomain identityDomain = receiverContext != null &&
                receiverContext.identityDomain() != null ? receiverContext.identityDomain() :
                TrunkedIdentityDomain.STANDARD;

            enqueueObservation(writer, new ReceiverActivityRecords.ControlChannelQuality(snapshot.observedAtMs(),
                snapshot.configurationId(), decoderType != null && decoderType.getProtocol() != null ?
                    decoderType.getProtocol().name() : "UNKNOWN", identityDomain, snapshot.frequencyHz(),
                snapshot.signalDbfs(), snapshot.averageSignalDbfs(),
                snapshot.minimumSignalDbfs(), snapshot.maximumSignalDbfs(), snapshot.decodeHealthPercent(),
                snapshot.validFrames(), snapshot.invalidFrames(), snapshot.correctedBits(), snapshot.syncLossBits(),
                snapshot.droppedBits(), snapshot.lastValidDecodeMs()));
        }
    }

    /**
     * Requires metadata evidence from the same running channel and decoder configuration. Explicit DMR and NXDN modes
     * are also checked so a conventional channel cannot inherit evidence through a reused configuration UUID. The quality monitor's
     * inactive snapshot clears this evidence when the channel stops.
     */
    static boolean hasCurrentTrunkedSiteEvidence(ControlChannelQualitySnapshot snapshot,
                                                  TrunkedSiteEvidence evidence)
    {
        if(snapshot == null || snapshot.receiverContext() == null || evidence == null ||
            evidence.receiverContext() == null ||
            !evidence.receiverContext().isSameReceiver(snapshot.receiverContext()))
        {
            return false;
        }

        SiteReceiverContext receiverContext = snapshot.receiverContext();
        return receiverContext.isTrunked() &&
            (receiverContext.decoderType() == DecoderType.DMR ||
                receiverContext.decoderType() == DecoderType.NXDN);
    }

    /**
     * Identifies control-channel decoders that publish the shared trunked-site quality contract.
     */
    static boolean isTrunkedControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        SiteReceiverContext receiverContext = snapshot != null ? snapshot.receiverContext() : null;
        DecoderType decoderType = receiverContext != null ? receiverContext.decoderType() : null;
        return decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2 ||
            (decoderType == DecoderType.NXDN || decoderType == DecoderType.DMR) &&
                receiverContext.isTrunked();
    }

    /**
     * P25 preserves its existing persistence behavior. Explicitly trunked DMR is accepted immediately. NXDN requires
     * useful trunked-site metadata. Explicitly conventional DMR and NXDN channels are rejected.
     */
    static boolean shouldPersistControlChannelQuality(ControlChannelQualitySnapshot snapshot,
                                                       boolean observedTrunkedSite)
    {
        if(!isTrunkedControlChannelQuality(snapshot))
        {
            return false;
        }

        DecoderType decoderType = snapshot != null && snapshot.receiverContext() != null ?
            snapshot.receiverContext().decoderType() : null;

        if(decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2)
        {
            return true;
        }

        if(decoderType == DecoderType.DMR)
        {
            return snapshot.receiverContext().isTrunked();
        }

        return observedTrunkedSite;
    }

    public void dispose()
    {
        if(!disposeAndAwait(mDisposeTimeoutMilliseconds, TimeUnit.MILLISECONDS))
        {
            mLog.warn("Timed out waiting for statistics observer and database writer cleanup");
        }
    }

    /**
     * Requests disposal and waits for both the observation worker and its owned SQLite writer to terminate.
     * Repeated calls continue waiting for an earlier disposal request, which lets a bounded caller distinguish a
     * short UI cleanup attempt from the stronger shutdown boundary required before replacing the active database.
     * This is bounded lifecycle coordination and must never be called from a decoder or receiver callback.
     *
     * @return true only when no statistics worker can still access the database
     */
    public boolean disposeAndAwait(long timeout, TimeUnit unit)
    {
        java.util.Objects.requireNonNull(unit, "unit cannot be null");
        long timeoutNanos = Math.max(0L, unit.toNanos(timeout));
        long startedNanos = System.nanoTime();
        requestDispose();
        boolean observerTerminated = mObservationWorker.isTerminated();

        while(!observerTerminated)
        {
            long remainingNanos = timeoutNanos - (System.nanoTime() - startedNanos);

            if(remainingNanos <= 0)
            {
                break;
            }

            try
            {
                observerTerminated = mObservationWorker.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        if(!observerTerminated)
        {
            return false;
        }

        try
        {
            return awaitStartedWritersUntil(startedNanos, timeoutNanos);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean awaitStartedWritersUntil(long startedNanos, long timeoutNanos) throws InterruptedException
    {
        while(true)
        {
            ReceiverActivityWriter[] writers = mStartedWriters.toArray(ReceiverActivityWriter[]::new);

            if(writers.length == 0)
            {
                return true;
            }

            for(ReceiverActivityWriter writer: writers)
            {
                if(writer.isWorkerTerminated())
                {
                    mStartedWriters.remove(writer);
                    continue;
                }

                long remainingNanos = timeoutNanos - (System.nanoTime() - startedNanos);

                if(remainingNanos <= 0L ||
                    !writer.awaitWorkerTermination(remainingNanos, TimeUnit.NANOSECONDS))
                {
                    return false;
                }

                mStartedWriters.remove(writer);
            }
        }
    }

    private void requestDispose()
    {
        if(mDisposed.compareAndSet(false, true))
        {
            //Publish the disposal fence without waiting for the service monitor. The observer may hold that monitor
            //while a database writer is performing its own bounded close, and this method's timeout is a total bound.
            mCollectionEnabled = false;
            mObservationIngress = new BoundedMpscPairQueue<>(OBSERVATION_QUEUE_SIZE);
            MyEventBus.getGlobalEventBus().unregister(this);
            //The observer worker remains the only ingress consumer and owns state and writer cleanup.
            mObservationWakeup.release();
            mObservationWorker.shutdown();
        }
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.APPLICATION || preferenceType == PreferenceType.DIRECTORY)
        {
            updateWriterState();
        }
    }

    private synchronized void updateWriterState()
    {
        if(mDisposed.get())
        {
            return;
        }

        ApplicationPreference preference = mUserPreferences.getApplicationPreference();
        boolean collectionEnabled = preference.isStatsLoggingEnabled();
        Path databasePath = ReceiverActivityPath.getDatabasePath(mUserPreferences);
        int retentionDays = preference.getStatsLoggingRetentionDays();
        boolean detailedEventHistoryEnabled = preference.isStatsDetailedHistoryEnabled();
        WriterTransition transition = new WriterTransition(databasePath, retentionDays,
            detailedEventHistoryEnabled, collectionEnabled);
        ReceiverActivityWriter writer = mWriter;
        boolean replaceWriter = mWriterTransitionActive.get() || writer == null ||
            !databasePath.equals(mCurrentDatabasePath) ||
            writer.getStatus().state() == ReceiverActivityStatus.State.FAILED ||
            writer.getStatus().state() == ReceiverActivityStatus.State.STOPPED;

        if(replaceWriter)
        {
            if(!mObservationWorkerStarted)
            {
                installInitialWriter(transition);
            }
            else
            {
                beginWriterTransition(transition);
            }

            return;
        }

        writer.setRetentionDays(retentionDays);
        writer.setDetailedEventHistoryEnabled(detailedEventHistoryEnabled);
        updateCollectionEpoch(collectionEnabled);
    }

    private void installInitialWriter(WriterTransition transition)
    {
        ReceiverActivityWriter writer = startWriter(transition);
        mCurrentDatabasePath = transition.databasePath();
        mWriter = writer;
        mObservationIngress = new BoundedMpscPairQueue<>(OBSERVATION_QUEUE_SIZE);
        mCollectionEnabled = transition.collectionEnabled();
        mLog.info("Stats database writer started for collection and retention maintenance [{}]",
            transition.databasePath());
    }

    private void beginWriterTransition(WriterTransition transition)
    {
        //An inactive queue catches callbacks that began during the transition and is never used as the next active
        //epoch. The observer worker owns state clearing and writer replacement.
        mCollectionEnabled = false;
        mObservationIngress = new BoundedMpscPairQueue<>(OBSERVATION_QUEUE_SIZE);
        mWriterTransitionActive.set(true);
        mWriterTransition.set(transition);
        mObservationWakeup.release();
    }

    private void updateCollectionEpoch(boolean collectionEnabled)
    {
        if(mCollectionEnabled && !collectionEnabled)
        {
            mCollectionEnabled = false;
            mObservationIngress = new BoundedMpscPairQueue<>(OBSERVATION_QUEUE_SIZE);
            requestObservationStateClear();
        }
        else if(!mCollectionEnabled && collectionEnabled)
        {
            //Never reuse the disabled queue: a callback may have captured it before observing the disabled flag.
            mObservationIngress = new BoundedMpscPairQueue<>(OBSERVATION_QUEUE_SIZE);
            mCollectionEnabled = true;
            mObservationWakeup.release();
        }
    }

    private void replaceWriterOnWorker(WriterTransition transition)
    {
        clearObservationStateOnWorker();
        mObservationStateClearRequested.set(false);
        stopWriter();

        if(mDisposed.get())
        {
            return;
        }

        ReceiverActivityWriter nextWriter = startWriter(transition);
        boolean installed = false;

        try
        {
            beforeWriterActivationForTest();

            synchronized(this)
            {
                if(!mDisposed.get() && mWriterTransition.get() == null)
                {
                    mCurrentDatabasePath = transition.databasePath();
                    mWriter = nextWriter;
                    //The inactive transition queue is always abandoned. Publish a distinct active epoch before enabling.
                    mObservationIngress = new BoundedMpscPairQueue<>(OBSERVATION_QUEUE_SIZE);
                    mCollectionEnabled = transition.collectionEnabled();
                    mWriterTransitionActive.set(false);
                    installed = true;
                }
            }
        }
        finally
        {
            if(!installed)
            {
                closeTrackedWriter(nextWriter);
            }
        }

        if(!installed)
        {
            return;
        }

        mLog.info("Stats database writer started for collection and retention maintenance [{}]",
            transition.databasePath());
        mObservationWakeup.release();
    }

    private ReceiverActivityWriter startWriter(WriterTransition transition)
    {
        ReceiverActivityWriter writer = java.util.Objects.requireNonNull(
            mWriterFactory.create(transition.databasePath(), transition.retentionDays(),
                transition.detailedEventHistoryEnabled()), "writerFactory returned null");
        mStartedWriters.add(writer);

        try
        {
            writer.start();
            return writer;
        }
        catch(RuntimeException | Error startFailure)
        {
            try
            {
                closeTrackedWriter(writer);
            }
            catch(RuntimeException | Error closeFailure)
            {
                startFailure.addSuppressed(closeFailure);
            }

            throw startFailure;
        }
    }

    private void closeTrackedWriter(ReceiverActivityWriter writer)
    {
        try
        {
            writer.close();
        }
        finally
        {
            if(writer.isWorkerTerminated())
            {
                mStartedWriters.remove(writer);
            }
        }
    }

    private synchronized void stopWriter()
    {
        ReceiverActivityWriter writer = mWriter;

        if(writer != null)
        {
            try
            {
                closeTrackedWriter(writer);
            }
            finally
            {
                mLastWriterStatus = writer.getStatus();
                mWriter = null;
                mCurrentDatabasePath = null;

                if(writer.isWorkerTerminated())
                {
                    mLog.info("Stats database writer stopped");
                }
                else
                {
                    mLog.warn("Stats database writer close returned before its worker terminated");
                }
            }
        }
    }

    private void receiveDecodeEvent(Channel channel, IDecodeEvent event)
    {
        BoundedMpscPairQueue<Object,Object> ingress = mObservationIngress;
        afterIngressSnapshotForTest();

        if(channel == null || event == null || !mCollectionEnabled || mDisposed.get())
        {
            return;
        }

        if(!ingress.offer(channel, event))
        {
            mObservationDrops.incrementAndGet();
        }
    }

    private void processDecodeEvent(Channel channel, IDecodeEvent event)
    {
        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer == null || ReceiverActivityMapper.isTypedCallOwnedObservation(channel, event))
        {
            return;
        }

        ReceiverActivityRecords.ActivityEvent record = mMapper.map(channel, event);

        if(record != null && shouldLog(record))
        {
            enqueueObservation(writer, record);
        }
    }

    private void offerObservation(Object observation)
    {
        offerObservation(observation, SINGLE_OBSERVATION);
    }

    private void offerObservation(Object first, Object second)
    {
        BoundedMpscPairQueue<Object,Object> ingress = mObservationIngress;
        afterIngressSnapshotForTest();

        if(first == null || second == null || !mCollectionEnabled || mDisposed.get())
        {
            return;
        }

        if(!ingress.offer(first, second))
        {
            mObservationDrops.incrementAndGet();
        }
    }

    private void drainObservationsSafely()
    {
        try
        {
            drainObservations();
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Error processing a statistics observation", exception);
        }
    }

    private void cleanupObservationsOnWorker()
    {
        mObservationIngress.clear();
        clearObservationStateOnWorker();
    }

    private void afterIngressSnapshotForTest()
    {
        if(mAfterIngressSnapshotForTest != null)
        {
            mAfterIngressSnapshotForTest.run();
        }
    }

    private void beforeWriterActivationForTest()
    {
        if(mBeforeWriterActivationForTest != null)
        {
            mBeforeWriterActivationForTest.run();
        }
    }

    private void requestObservationStateClear()
    {
        mObservationStateClearRequested.set(true);
        mObservationWakeup.release();
    }

    private void clearObservationStateOnWorker()
    {
        mGrantFactConfirmationTracker.reset();
        mRecentDedupeKeys.clear();
        mRecentLogicalNotifications.clear();
        mObservedTrunkedSites.clear();
    }

    private void drainObservations()
    {
        if(mObservationStateClearRequested.compareAndSet(true, false))
        {
            clearObservationStateOnWorker();
        }

        BoundedMpscPairQueue<Object,Object> ingress = mObservationIngress;
        int drained = 0;

        while(!mDisposed.get() && drained++ < MAXIMUM_DRAIN_PER_RUN)
        {
            BoundedMpscPairQueue.Entry<Object,Object> observation = ingress.poll();

            if(observation == null)
            {
                break;
            }

            if(ingress != mObservationIngress || !mCollectionEnabled)
            {
                break;
            }

            mWorkerObservationIngress = ingress;

            try
            {
                if(observation.second() == SINGLE_OBSERVATION)
                {
                    processObservation(observation.first());
                }
                else if(observation.first() instanceof Channel channel &&
                    observation.second() instanceof IDecodeEvent event)
                {
                    processDecodeEvent(channel, event);
                }
                else if(observation.first() instanceof CompletedAudioCall call &&
                    observation.second() instanceof ReceiverActivityRecords.CallOutput output)
                {
                    processCallOutput(call, output);
                }
            }
            finally
            {
                mWorkerObservationIngress = null;
            }
        }
    }

    private void enqueueObservation(ReceiverActivityWriter writer, ReceiverActivityRecord record)
    {
        synchronized(this)
        {
            if(writer != null && record != null && writer == mWriter && mCollectionEnabled && !mDisposed.get() &&
                !mWriterTransitionActive.get() && mWorkerObservationIngress == mObservationIngress)
            {
                writer.enqueue(record);
            }
        }
    }

    private void processObservation(Object observation)
    {
        if(observation instanceof ObservationDrainBarrier barrier)
        {
            barrier.complete();
        }
        else if(observation instanceof ControlChannelQualitySnapshot quality)
        {
            processControlChannelQuality(quality);
        }
        else if(observation instanceof CompletedAudioCall call)
        {
            processResolvedCall(call);
        }
        else if(observation instanceof P25CallStartEvent callStart)
        {
            processCallStart(callStart);
        }
        else if(observation instanceof TrunkedCallStartEvent callStart)
        {
            processTrunkedCallStart(callStart);
        }
        else if(observation instanceof TrunkedCallAttributionEvent attribution)
        {
            processTrunkedCallAttribution(attribution);
        }
        else if(observation instanceof DMRConventionalCallEvent dmrCall)
        {
            processDmrConventionalCall(dmrCall);
        }
        else if(observation instanceof NXDNConventionalCallEvent nxdnCall)
        {
            processNxdnConventionalCall(nxdnCall);
        }
        else if(observation instanceof P25TrafficChannelConfirmationEvent confirmation)
        {
            processTrafficChannelConfirmation(confirmation);
        }
        else if(observation instanceof P25GrantObservationEvent grant)
        {
            processGrantObservation(grant);
        }
        else if(observation instanceof TrunkedTalkerAliasEvent alias)
        {
            processTalkerAlias(alias);
        }
        else if(observation instanceof SiteMetadataEvent siteMetadata)
        {
            processSiteMetadata(siteMetadata);
        }
        else if(observation instanceof ProtocolSiteMetadataEvent protocolSiteMetadata)
        {
            processProtocolSiteMetadata(protocolSiteMetadata);
        }
    }

    long getObservationDropCount()
    {
        return mObservationDrops.get();
    }

    int getPendingObservationCount()
    {
        return mObservationIngress.size();
    }

    BoundedMpscPairQueue<Object,Object> getObservationIngressForTest()
    {
        return mObservationIngress;
    }

    boolean isWriterTransitionActiveForTest()
    {
        return mWriterTransitionActive.get();
    }

    synchronized Path getCurrentDatabasePathForTest()
    {
        return mCurrentDatabasePath;
    }

    boolean isObservationWorkerTerminated()
    {
        return mObservationWorker.isTerminated();
    }

    int getStartedWriterCountForTest()
    {
        return mStartedWriters.size();
    }

    @Subscribe
    public void receiveCallStart(P25CallStartEvent event)
    {
        offerObservation(event);
    }

    private void processCallStart(P25CallStartEvent event)
    {
        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        ReceiverActivityRecords.ActivityEvent record = mMapper.map(event);

        if(record != null)
        {
            enqueueObservation(writer, record);
        }
    }

    /**
     * Receives the exactly-once DMR/NXDN trunked call-start notification. Traffic-channel allocation and audio are
     * intentionally not prerequisites for this observation.
     */
    @Subscribe
    public void receiveTrunkedCallStart(TrunkedCallStartEvent event)
    {
        offerObservation(event);
    }

    private void processTrunkedCallStart(TrunkedCallStartEvent event)
    {
        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        ReceiverActivityRecords.ActivityEvent record = mTrunkedCallMapper.map(event);

        if(record != null)
        {
            enqueueObservation(writer, record);
        }
    }

    /**
     * Receives one-time DMR/NXDN identity or encryption enrichment for an already-counted call.
     */
    @Subscribe
    public void receiveTrunkedCallAttribution(TrunkedCallAttributionEvent event)
    {
        offerObservation(event);
    }

    private void processTrunkedCallAttribution(TrunkedCallAttributionEvent event)
    {
        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        ReceiverActivityRecords.TrunkedCallAttribution record = mTrunkedCallMapper.map(event);

        if(record != null)
        {
            enqueueObservation(writer, record);
        }
    }

    /**
     * Receives the immutable one-time completion snapshot instead of the mutable DMR decode-event rebroadcasts.
     */
    @Subscribe
    public void receiveDmrConventionalCall(DMRConventionalCallEvent event)
    {
        offerObservation(event);
    }

    private void processDmrConventionalCall(DMRConventionalCallEvent event)
    {
        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        ReceiverActivityRecords.DmrConventionalCall record = mMapper.map(event);

        if(record != null)
        {
            enqueueObservation(writer, record);
        }
    }

    /**
     * Receives the immutable one-time completion snapshot instead of the mutable NXDN decode-event rebroadcasts.
     */
    @Subscribe
    public void receiveNxdnConventionalCall(NXDNConventionalCallEvent event)
    {
        offerObservation(event);
    }

    private void processNxdnConventionalCall(NXDNConventionalCallEvent event)
    {
        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        ReceiverActivityRecords.NxdnConventionalCall record = mMapper.map(event);

        if(record != null)
        {
            enqueueObservation(writer, record);
        }
    }

    @Subscribe
    public void receiveTrafficChannelConfirmation(P25TrafficChannelConfirmationEvent event)
    {
        offerObservation(event);
    }

    private void processTrafficChannelConfirmation(P25TrafficChannelConfirmationEvent event)
    {
        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer != null)
        {
            for(ReceiverActivityRecords.ChannelFact channelFact: mGrantFactConfirmationTracker.confirm(event))
            {
                enqueueObservation(writer, channelFact);
            }
        }
    }

    @Subscribe
    public void receiveGrantObservation(P25GrantObservationEvent event)
    {
        offerObservation(event);
    }

    private void processGrantObservation(P25GrantObservationEvent event)
    {
        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        ReceiverActivityRecords.ActivityEvent record = mMapper.map(event);

        if(record != null)
        {
            enqueueObservation(writer, record);
            ReceiverActivityRecords.ChannelFact channelFact =
                mGrantFactConfirmationTracker.observe(event, record);

            if(channelFact != null)
            {
                enqueueObservation(writer, channelFact);
            }
        }
    }

    @Subscribe
    public void receiveTalkerAlias(TrunkedTalkerAliasEvent event)
    {
        offerObservation(event);
    }

    private void processTalkerAlias(TrunkedTalkerAliasEvent event)
    {
        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        ReceiverActivityRecords.TalkerAliasUpdate update = mMapper.map(event);

        if(update != null && shouldLogTalkerAlias(update))
        {
            enqueueObservation(writer, update);
        }
    }

    @Override
    public void receiveSiteMetadata(SiteMetadataEvent event)
    {
        offerObservation(event);
    }

    private void processSiteMetadata(SiteMetadataEvent event)
    {
        if(event == null || !event.matchesCurrentChannel())
        {
            //History may describe the old receiver, but this writer also owns current receiver-to-site assignment.
            //Fail closed instead of letting a delayed observation rewind that assignment.
            return;
        }

        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        ReceiverActivityRecords.SiteSnapshot record = mMapper.map(event);

        if(record != null)
        {
            enqueueObservation(writer, record);
        }
    }

    @Override
    public void receiveProtocolSiteMetadata(ProtocolSiteMetadataEvent event)
    {
        offerObservation(event);
    }

    private void processProtocolSiteMetadata(ProtocolSiteMetadataEvent event)
    {
        if(event == null || !event.matchesCurrentChannel())
        {
            //Do not let a delayed site observation from a preceding channel generation replace current assignment.
            return;
        }

        ReceiverActivityWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        var snapshot = TrunkedSiteMetadataMapper.map(event);
        SiteReceiverContext receiverContext = event != null ? event.receiverContext() : null;
        String configurationId = receiverContext != null ? receiverContext.configurationId() : null;

        if(receiverContext != null && receiverContext.isConventional())
        {
            if(configurationId != null)
            {
                mObservedTrunkedSites.remove(configurationId);
            }

            return;
        }

        if(snapshot != null)
        {
            if(snapshot.configurationId() != null)
            {
                mObservedTrunkedSites.put(snapshot.configurationId(),
                    new TrunkedSiteEvidence(receiverContext));
            }

            enqueueObservation(writer, new ReceiverActivityRecords.TrunkedSiteSnapshot(
                snapshot.observedAtEpochMilliseconds(), snapshot));
        }
        else if(configurationId != null)
        {
            mObservedTrunkedSites.remove(configurationId);
        }
    }

    record TrunkedSiteEvidence(SiteReceiverContext receiverContext)
    {
    }

    /**
     * Routes runtime database maintenance through the same connection and background writer used for observations.
     */
    @Subscribe
    public void receiveMaintenanceRequest(StatsDatabaseMaintenanceRequest request)
    {
        ReceiverActivityWriter writer = !mDisposed.get() ? mWriter : null;

        if(writer != null)
        {
            writer.submitMaintenance(request);
        }
        else if(request != null)
        {
            request.result().completeExceptionally(
                new IllegalStateException("Statistics database writer is not available"));
        }
    }

    private ReceiverActivityWriter getCollectionWriter()
    {
        return mCollectionEnabled && !mDisposed.get() ? mWriter : null;
    }

    private boolean shouldLog(ReceiverActivityRecords.ActivityEvent record)
    {
        if(record.dedupeKey() == null)
        {
            return true;
        }

        long now = System.currentTimeMillis();

        synchronized(mRecentDedupeKeys)
        {
            cleanupDedupeKeys(now);
            Long previous = mRecentDedupeKeys.put(record.dedupeKey(), now);
            return previous == null || !isWithinDedupeWindow(record.dedupeKey(), previous, now);
        }
    }

    /**
     * DMR and NXDN control messages are normally repeated in a tight transmission burst. Coalesce only uninterrupted
     * repeats inside the short signaling window. The mapper's semantic key keeps distinct event types, subtypes,
     * participants, channels and slots independent. Existing mutable call-event dedupe retains its longer window.
     */
    static boolean isWithinDedupeWindow(String key, long previous, long now)
    {
        long window = key != null && key.startsWith(ReceiverActivityMapper.PROTOCOL_SIGNAL_DEDUPE_PREFIX) ?
            PROTOCOL_SIGNAL_DEDUPE_WINDOW_MILLISECONDS : DEDUPE_RETENTION_MILLISECONDS;
        return now >= previous && now - previous <= window;
    }

    private boolean shouldLogTalkerAlias(ReceiverActivityRecords.TalkerAliasUpdate update)
    {
        long now = System.currentTimeMillis();
        String key = String.join("|", "talker-alias", update.configurationId(), Integer.toString(update.radioId()),
            update.talkerAlias(), update.identityDomain().name());

        synchronized(mRecentDedupeKeys)
        {
            cleanupDedupeKeys(now);
            Long previous = mRecentDedupeKeys.put(key, now);
            return previous == null;
        }
    }

    private void cleanupDedupeKeys(long now)
    {
        Iterator<Map.Entry<String,Long>> iterator = mRecentDedupeKeys.entrySet().iterator();

        while(iterator.hasNext())
        {
            Map.Entry<String,Long> entry = iterator.next();

            if(now - entry.getValue() > DEDUPE_RETENTION_MILLISECONDS)
            {
                iterator.remove();
            }
            else
            {
                break;
            }
        }
    }

    /**
     * Configured preferences and current effective writer health for the web status API and desktop diagnostics.
     */
    public synchronized ReceiverActivityStatus getStatus()
    {
        ApplicationPreference preference = mUserPreferences.getApplicationPreference();
        boolean summaryConfigured = preference.isStatsLoggingEnabled();
        boolean historyConfigured = preference.isStatsDetailedHistoryEnabled();
        ReceiverActivityWriter.WriterStatus writerStatus = mWriter != null ? mWriter.getStatus() : mLastWriterStatus;
        ReceiverActivityStatus.State state = summaryConfigured ? ReceiverActivityStatus.State.STOPPED :
            ReceiverActivityStatus.State.DISABLED;
        long lastSuccessfulWriteMs = 0;
        long recordsWritten = 0;
        long recordsDropped = 0;
        String lastError = null;
        boolean historyWriterEnabled = false;

        if(writerStatus != null)
        {
            if(summaryConfigured || writerStatus.state() == ReceiverActivityStatus.State.FAILED)
            {
                state = writerStatus.state();
            }

            lastSuccessfulWriteMs = writerStatus.lastSuccessfulWriteMs();
            recordsWritten = writerStatus.recordsWritten();
            recordsDropped = writerStatus.recordsDropped() + mObservationDrops.get();
            lastError = writerStatus.lastError();
            historyWriterEnabled = writerStatus.detailedHistoryEnabled();
        }

        boolean summaryActive = summaryConfigured && state == ReceiverActivityStatus.State.RUNNING;
        boolean historyActive = summaryActive && historyConfigured && historyWriterEnabled;
        return new ReceiverActivityStatus(summaryConfigured, historyConfigured, summaryActive, historyActive,
            preference.getStatsLoggingRetentionDays(), state,
            ReceiverActivityPath.getDatabasePath(mUserPreferences).toString(), lastSuccessfulWriteMs,
            recordsWritten, recordsDropped, lastError);
    }

    private record WriterTransition(Path databasePath, int retentionDays,
                                    boolean detailedEventHistoryEnabled, boolean collectionEnabled)
    {
    }

    @FunctionalInterface
    interface WriterFactory
    {
        ReceiverActivityWriter create(Path databasePath, int retentionDays, boolean detailedEventHistoryEnabled);
    }

    private static final class ObservationDrainBarrier
    {
        private final CountDownLatch mProcessed = new CountDownLatch(1);

        private void complete()
        {
            mProcessed.countDown();
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mProcessed.await(timeout, unit);
        }
    }

}
