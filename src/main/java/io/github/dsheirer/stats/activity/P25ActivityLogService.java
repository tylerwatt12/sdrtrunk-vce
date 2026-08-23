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
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataListener;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataListener;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DMRConventionalCallEvent;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallEvent;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelConfirmationEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallAttributionEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns statistics collection and maintenance and keeps SQLite work off decoder/UI threads.
 */
public class P25ActivityLogService implements SiteMetadataListener, ProtocolSiteMetadataListener
{
    private static final Logger mLog = LoggerFactory.getLogger(P25ActivityLogService.class);
    private static final long DEDUPE_RETENTION_MILLISECONDS = 60000;
    static final long PROTOCOL_SIGNAL_DEDUPE_WINDOW_MILLISECONDS = 500;
    static final int OBSERVATION_QUEUE_SIZE = 4_096;
    private static final int MAXIMUM_DRAIN_PER_RUN = 1_024;
    private static final Object SINGLE_OBSERVATION = new Object();
    private static final long DEFAULT_DISPOSE_TIMEOUT_MILLISECONDS = 2_000;

    private final UserPreferences mUserPreferences;
    private final P25ActivityLogMapper mMapper = new P25ActivityLogMapper();
    private final TrunkedCallActivityMapper mTrunkedCallMapper = new TrunkedCallActivityMapper();
    private final CallAttributionTracker mCallAttributionTracker = new CallAttributionTracker();
    private final CallOutputDeduplicator mCallOutputDeduplicator = new CallOutputDeduplicator();
    private final P25GrantFactConfirmationTracker mGrantFactConfirmationTracker =
        new P25GrantFactConfirmationTracker();
    private final BiConsumer<Channel,IDecodeEvent> mDecodeEventListener = this::receiveDecodeEvent;
    private final Listener<ControlChannelQualitySnapshot> mQualityListener = this::receiveControlChannelQuality;
    private final Map<String,Long> mRecentDedupeKeys = new LinkedHashMap<>(256, 0.75f, true);
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
    private final long mDisposeTimeoutMilliseconds;
    private final Runnable mAfterIngressSnapshotForTest;
    private final Runnable mBeforeWriterActivationForTest;
    private volatile P25ActivityLogWriter mWriter;
    private volatile boolean mCollectionEnabled;
    private volatile boolean mObservationWorkerStarted;
    private BoundedMpscPairQueue<Object,Object> mWorkerObservationIngress;
    private Path mCurrentDatabasePath;
    private P25ActivityLogWriter.WriterStatus mLastWriterStatus;

    public P25ActivityLogService(UserPreferences userPreferences)
    {
        this(userPreferences, DEFAULT_DISPOSE_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS, null, null);
    }

    P25ActivityLogService(UserPreferences userPreferences, long disposeTimeout, TimeUnit unit)
    {
        this(userPreferences, disposeTimeout, unit, null, null);
    }

    P25ActivityLogService(UserPreferences userPreferences, long disposeTimeout, TimeUnit unit,
                          Runnable afterIngressSnapshotForTest)
    {
        this(userPreferences, disposeTimeout, unit, afterIngressSnapshotForTest, null);
    }

    P25ActivityLogService(UserPreferences userPreferences, long disposeTimeout, TimeUnit unit,
                          Runnable afterIngressSnapshotForTest, Runnable beforeWriterActivationForTest)
    {
        mUserPreferences = userPreferences;
        java.util.Objects.requireNonNull(unit, "unit cannot be null");
        mDisposeTimeoutMilliseconds = Math.max(0, unit.toMillis(disposeTimeout));
        mAfterIngressSnapshotForTest = afterIngressSnapshotForTest;
        mBeforeWriterActivationForTest = beforeWriterActivationForTest;
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
        receiveCallOutput(call, P25ActivityLogRecords.CallOutput.RECORDED);
    }

    public void receiveStreamedCall(CompletedAudioCall call)
    {
        receiveCallOutput(call, P25ActivityLogRecords.CallOutput.STREAMED);
    }

    private void receiveCallOutput(CompletedAudioCall call, P25ActivityLogRecords.CallOutput output)
    {
        offerObservation(call != null ? call.snapshot() : null, output);
    }

    private void processCallOutput(AudioCallSnapshot snapshot, P25ActivityLogRecords.CallOutput output)
    {
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.CompletedCallOutput completedCallOutput =
            mMapper.mapCompletedCallOutput(snapshot, output);

        if(completedCallOutput != null &&
            mCallOutputDeduplicator.firstOutput(snapshot, output, System.currentTimeMillis()))
        {
            enqueueObservation(writer, completedCallOutput);
        }
    }

    private void receiveControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        offerObservation(snapshot);
    }

    private void processControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        P25ActivityLogWriter writer = getCollectionWriter();

        if(snapshot != null && !snapshot.active() && snapshot.guid() != null)
        {
            mObservedTrunkedSites.computeIfPresent(snapshot.guid(), (guid, evidence) ->
                evidence.channel() == snapshot.channel() ? null : evidence);
        }

        TrunkedSiteEvidence evidence = snapshot != null && snapshot.guid() != null ?
            mObservedTrunkedSites.get(snapshot.guid()) : null;
        boolean observedTrunkedSite = hasCurrentTrunkedSiteEvidence(snapshot, evidence);

        if(evidence != null && !observedTrunkedSite)
        {
            mObservedTrunkedSites.remove(snapshot.guid(), evidence);
        }

        if(writer != null && shouldPersistControlChannelQuality(snapshot, observedTrunkedSite) &&
            snapshot.active() && snapshot.guid() != null && !snapshot.guid().isBlank() && snapshot.frequencyHz() > 0)
        {
            enqueueObservation(writer, new P25ActivityLogRecords.ControlChannelQuality(snapshot.observedAtMs(), snapshot.guid(),
                snapshot.frequencyHz(), snapshot.signalDbfs(), snapshot.averageSignalDbfs(),
                snapshot.minimumSignalDbfs(), snapshot.maximumSignalDbfs(), snapshot.decodeHealthPercent(),
                snapshot.validFrames(), snapshot.invalidFrames(), snapshot.correctedBits(), snapshot.syncLossBits(),
                snapshot.droppedBits(), snapshot.lastValidDecodeMs()));
        }
    }

    /**
     * Requires metadata evidence from the same running channel and decoder configuration. Explicit DMR and NXDN modes
     * are also checked so a conventional channel cannot inherit evidence through a reused GUID. The quality monitor's
     * inactive snapshot clears this evidence when the channel stops.
     */
    static boolean hasCurrentTrunkedSiteEvidence(ControlChannelQualitySnapshot snapshot,
                                                  TrunkedSiteEvidence evidence)
    {
        if(snapshot == null || snapshot.channel() == null || evidence == null ||
            evidence.channel() != snapshot.channel())
        {
            return false;
        }

        DecoderType decoderType = decoderType(snapshot.channel());
        DecodeConfiguration configuration = snapshot.channel().getDecodeConfiguration();

        if(decoderType == DecoderType.DMR &&
            (!(configuration instanceof DecodeConfigDMR dmr) || !dmr.isTrunked()))
        {
            return false;
        }

        if(decoderType == DecoderType.NXDN &&
            (!(configuration instanceof DecodeConfigNXDN nxdn) || !nxdn.isTrunked()))
        {
            return false;
        }

        return configuration == evidence.decodeConfiguration() && decoderType == evidence.decoderType();
    }

    /**
     * Identifies control-channel decoders that publish the shared trunked-site quality contract.  The existing
     * GUID-keyed quality bucket table is structurally protocol-neutral; its historical P25 name is retained for
     * deployed-schema compatibility.
     */
    static boolean isTrunkedControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        DecoderType decoderType = snapshot != null ? decoderType(snapshot.channel()) : null;
        DecodeConfiguration configuration = snapshot != null && snapshot.channel() != null ?
            snapshot.channel().getDecodeConfiguration() : null;
        return decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2 ||
            (decoderType == DecoderType.NXDN && configuration instanceof DecodeConfigNXDN nxdn &&
                nxdn.isTrunked()) ||
            (decoderType == DecoderType.DMR && configuration instanceof DecodeConfigDMR dmr && dmr.isTrunked());
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

        DecoderType decoderType = snapshot != null ? decoderType(snapshot.channel()) : null;

        if(decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2)
        {
            return true;
        }

        if(decoderType == DecoderType.DMR)
        {
            return snapshot.channel().getDecodeConfiguration() instanceof DecodeConfigDMR dmr && dmr.isTrunked();
        }

        return observedTrunkedSite;
    }

    private static DecoderType decoderType(Channel channel)
    {
        return channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
    }

    public void dispose()
    {
        synchronized(this)
        {
            if(!mDisposed.compareAndSet(false, true))
            {
                return;
            }

            mCollectionEnabled = false;
            mObservationIngress = new BoundedMpscPairQueue<>(OBSERVATION_QUEUE_SIZE);
        }

        MyEventBus.getGlobalEventBus().unregister(this);
        //The observer worker remains the only ingress consumer and owns state cleanup, even when disposal times out.
        mObservationWakeup.release();
        mObservationWorker.shutdown();

        try
        {
            if(!mObservationWorker.awaitTermination(mDisposeTimeoutMilliseconds, TimeUnit.MILLISECONDS))
            {
                mLog.warn("Timed out waiting for statistics observer cleanup");
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
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
        Path databasePath = P25ActivityLogPath.getDatabasePath(mUserPreferences);
        int retentionDays = preference.getStatsLoggingRetentionDays();
        boolean detailedEventHistoryEnabled = preference.isStatsDetailedHistoryEnabled();
        WriterTransition transition = new WriterTransition(databasePath, retentionDays,
            detailedEventHistoryEnabled, collectionEnabled);
        P25ActivityLogWriter writer = mWriter;
        boolean replaceWriter = mWriterTransitionActive.get() || writer == null ||
            !databasePath.equals(mCurrentDatabasePath) ||
            writer.getStatus().state() == P25ActivityLogStatus.State.FAILED ||
            writer.getStatus().state() == P25ActivityLogStatus.State.STOPPED;

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
        P25ActivityLogWriter writer = new P25ActivityLogWriter(transition.databasePath(),
            transition.retentionDays(), transition.detailedEventHistoryEnabled());
        writer.start();
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

        P25ActivityLogWriter nextWriter = new P25ActivityLogWriter(transition.databasePath(),
            transition.retentionDays(), transition.detailedEventHistoryEnabled());
        nextWriter.start();
        beforeWriterActivationForTest();
        boolean installed = false;

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

        if(installed)
        {
            mLog.info("Stats database writer started for collection and retention maintenance [{}]",
                transition.databasePath());
            mObservationWakeup.release();
        }
        else
        {
            nextWriter.close();
        }
    }

    private synchronized void stopWriter()
    {
        if(mWriter != null)
        {
            mWriter.close();
            mLastWriterStatus = mWriter.getStatus();
            mWriter = null;
            mCurrentDatabasePath = null;

            mLog.info("Stats database writer stopped");
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
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null || P25ActivityLogMapper.isTypedCallOwnedObservation(channel, event))
        {
            return;
        }

        P25ActivityLogRecords.ActivityEvent record = mMapper.map(channel, event);

        if(record != null)
        {
            CallAttributionTracker.AttributionResult attribution = mCallAttributionTracker.enrich(record);

            if(attribution.attribution() != null)
            {
                enqueueObservation(writer, attribution.attribution());
            }

            if(!attribution.tracked() && shouldLog(record))
            {
                enqueueObservation(writer, record);
            }
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
        mCallAttributionTracker.clear();
        mCallOutputDeduplicator.clear();
        mRecentDedupeKeys.clear();
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
                else if(observation.first() instanceof AudioCallSnapshot snapshot &&
                    observation.second() instanceof P25ActivityLogRecords.CallOutput output)
                {
                    processCallOutput(snapshot, output);
                }
            }
            finally
            {
                mWorkerObservationIngress = null;
            }
        }
    }

    private void enqueueObservation(P25ActivityLogWriter writer, P25ActivityLogRecord record)
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
        if(observation instanceof ControlChannelQualitySnapshot quality)
        {
            processControlChannelQuality(quality);
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

    @Subscribe
    public void receiveCallStart(P25CallStartEvent event)
    {
        offerObservation(event);
    }

    private void processCallStart(P25CallStartEvent event)
    {
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.ActivityEvent record = mMapper.map(event);

        if(record != null)
        {
            mCallAttributionTracker.register(record);
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
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.ActivityEvent record = mTrunkedCallMapper.map(event);

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
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.TrunkedCallAttribution record = mTrunkedCallMapper.map(event);

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
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.DmrConventionalCall record = mMapper.map(event);

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
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.NxdnConventionalCall record = mMapper.map(event);

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
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer != null)
        {
            for(P25ActivityLogRecords.ChannelFact channelFact: mGrantFactConfirmationTracker.confirm(event))
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
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.ActivityEvent record = mMapper.map(event);

        if(record != null)
        {
            enqueueObservation(writer, record);
            P25ActivityLogRecords.ChannelFact channelFact =
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
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.TalkerAliasUpdate update = mMapper.map(event);

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
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        P25ActivityLogRecords.SiteSnapshot record = mMapper.map(event);

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
        P25ActivityLogWriter writer = getCollectionWriter();

        if(writer == null)
        {
            return;
        }

        var snapshot = TrunkedSiteMetadataMapper.map(event);
        Channel channel = event != null ? event.channel() : null;
        String guid = channel != null ? channel.getRadresGuid() : null;

        if(channel != null &&
            (channel.getDecodeConfiguration() instanceof DecodeConfigDMR dmr && dmr.isConventional() ||
                channel.getDecodeConfiguration() instanceof DecodeConfigNXDN nxdn && nxdn.isConventional()))
        {
            if(guid != null && !guid.isBlank())
            {
                mObservedTrunkedSites.remove(guid);
            }

            return;
        }

        if(snapshot != null)
        {
            if(snapshot.guid() != null && !snapshot.guid().isBlank())
            {
                mObservedTrunkedSites.put(snapshot.guid(),
                    new TrunkedSiteEvidence(channel,
                        channel != null ? channel.getDecodeConfiguration() : null, decoderType(channel)));
            }

            enqueueObservation(writer, new P25ActivityLogRecords.TrunkedSiteSnapshot(
                snapshot.observedAtEpochMilliseconds(), snapshot));
        }
        else if(guid != null && !guid.isBlank())
        {
            mObservedTrunkedSites.remove(guid);
        }
    }

    record TrunkedSiteEvidence(Channel channel, DecodeConfiguration decodeConfiguration, DecoderType decoderType)
    {
    }

    /**
     * Routes runtime database maintenance through the same connection and background writer used for observations.
     */
    @Subscribe
    public void receiveMaintenanceRequest(StatsDatabaseMaintenanceRequest request)
    {
        P25ActivityLogWriter writer = !mDisposed.get() ? mWriter : null;

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

    private P25ActivityLogWriter getCollectionWriter()
    {
        return mCollectionEnabled && !mDisposed.get() ? mWriter : null;
    }

    private boolean shouldLog(P25ActivityLogRecords.ActivityEvent record)
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
        long window = key != null && key.startsWith(P25ActivityLogMapper.PROTOCOL_SIGNAL_DEDUPE_PREFIX) ?
            PROTOCOL_SIGNAL_DEDUPE_WINDOW_MILLISECONDS : DEDUPE_RETENTION_MILLISECONDS;
        return now >= previous && now - previous <= window;
    }

    private boolean shouldLogTalkerAlias(P25ActivityLogRecords.TalkerAliasUpdate update)
    {
        long now = System.currentTimeMillis();
        String key = String.join("|", "talker-alias", update.contextKey(), Integer.toString(update.radioId()),
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
    public synchronized P25ActivityLogStatus getStatus()
    {
        ApplicationPreference preference = mUserPreferences.getApplicationPreference();
        boolean summaryConfigured = preference.isStatsLoggingEnabled();
        boolean historyConfigured = preference.isStatsDetailedHistoryEnabled();
        P25ActivityLogWriter.WriterStatus writerStatus = mWriter != null ? mWriter.getStatus() : mLastWriterStatus;
        P25ActivityLogStatus.State state = summaryConfigured ? P25ActivityLogStatus.State.STOPPED :
            P25ActivityLogStatus.State.DISABLED;
        long lastSuccessfulWriteMs = 0;
        long recordsWritten = 0;
        long recordsDropped = 0;
        String lastError = null;
        boolean historyWriterEnabled = false;

        if(writerStatus != null)
        {
            if(summaryConfigured || writerStatus.state() == P25ActivityLogStatus.State.FAILED)
            {
                state = writerStatus.state();
            }

            lastSuccessfulWriteMs = writerStatus.lastSuccessfulWriteMs();
            recordsWritten = writerStatus.recordsWritten();
            recordsDropped = writerStatus.recordsDropped() + mObservationDrops.get();
            lastError = writerStatus.lastError();
            historyWriterEnabled = writerStatus.detailedHistoryEnabled();
        }

        boolean summaryActive = summaryConfigured && state == P25ActivityLogStatus.State.RUNNING;
        boolean historyActive = summaryActive && historyConfigured && historyWriterEnabled;
        return new P25ActivityLogStatus(summaryConfigured, historyConfigured, summaryActive, historyActive,
            preference.getStatsLoggingRetentionDays(), state,
            P25ActivityLogPath.getDatabasePath(mUserPreferences).toString(), lastSuccessfulWriteMs,
            recordsWritten, recordsDropped, lastError);
    }

    private record WriterTransition(Path databasePath, int retentionDays,
                                    boolean detailedEventHistoryEnabled, boolean collectionEnabled)
    {
    }

}
