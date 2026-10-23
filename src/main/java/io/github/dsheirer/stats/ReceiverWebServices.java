/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.audio.broadcast.StreamingAdministrationService;
import io.github.dsheirer.alerts.TopicAlertService;
import io.github.dsheirer.alerts.delivery.PushDeliveryService;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.tone.P25PagingToneService;
import io.github.dsheirer.audio.tone.PagingToneSettingsService;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticService;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.message.DecodeMessageViewService;
import io.github.dsheirer.channel.metadata.activity.ConventionalLiveSources;
import io.github.dsheirer.map.MapSnapshotService;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.remote.RemoteLinkAdministrationService;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.record.managed.ManagedRecordingTranscriptionService;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.scanlist.ScanListModel;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService;
import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver;
import io.github.dsheirer.service.radioreference.RadioReferenceImportService;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.stats.activity.ReceiverActivityService;
import io.github.dsheirer.stats.health.ReceiverHealthService;
import io.github.dsheirer.support.ApplicationLogService;
import io.github.dsheirer.support.SupportBundleService;
import io.github.dsheirer.web.tuner.TunerAllocationMonitorService;
import io.github.dsheirer.web.settings.SpectrumSnapSettingsService;
import io.github.dsheirer.web.settings.WebReceiverSettingsService;
import io.github.dsheirer.web.tuner.TunerAdministrationService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.dsheirer.configuration.ConfigurationManager;
/** Receiver-lifetime services exposed by HTTP. Listener replacement never reconstructs these owners. */
public final class ReceiverWebServices implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ReceiverWebServices.class);
    private final UserPreferences mUserPreferences;
    final Path mWebAccessDatabasePath;
    private boolean mStarted;
    private volatile boolean mClosed;
    private boolean mAdministrationQuiesced;
    private final ScheduledExecutorService mFeatureMaintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "receiver web features");
        thread.setDaemon(true);
        return thread;
    });
    final StatsWebDatabase mDatabase;
    final WebEntityNavigationCatalog mEntityCatalog;
    final StatsLiveService mLiveService;
    final P25ActivityLiveService mP25ActivityLiveService;
    final StatsP25AssignmentService mP25AssignmentService;
    final DecodeEventViewService mDecodeEventViewService;
    final DecodeMessageViewService mDecodeMessageViewService;
    final ConventionalLiveSources mConventionalLiveSources;
    final DiagnosticFftScheduler mDiagnosticFftScheduler;
    final ChannelDiagnosticService mChannelDiagnosticService;
    final TunerDiagnosticService mTunerDiagnosticService;
    final TunerAdministrationService mTunerAdministrationService;
    final TunerAllocationMonitorService mTunerAllocationMonitorService;
    final TunerAllocationFrequencySeedService mTunerAllocationFrequencySeedService;
    final TunerSettingsService mTunerSettingsService;
    final TunerManager mTunerManager;
    final FrequencyListenService mFrequencyListenService;
    final SpectrumDiscoveryService mSpectrumDiscoveryService;
    final SpectrumSearchService mSpectrumSearchService;
    final ReceiverHealthService mReceiverHealthService;
    final TopicAlertService mTopicAlertService;
    final PushDeliveryService mPushDeliveryService;
    final Listener<DecodeEventViewService.EventView> mTopicAlertEventListener;
    boolean mTopicAlertObservationActive;
    AutoCloseable mRecordingReadySubscription;
    final SupportBundleService mSupportBundleService;
    final ApplicationLogService mApplicationLogService;
    final StatsWebCallService mWebCallService;
    final PagingToneSettingsService mPagingToneSettingsService;
    final ChannelProcessingManager mChannelProcessingManager;
    final ReceiverActivityService mActivityLogService;
    final AliasAdministrationService mAliasAdministrationService;
    final ChannelAdministrationService mChannelAdministrationService;
    final StatsWebSnapshot<List<ChannelAdministrationService.ChannelSummary>> mWebChannelCatalog;
    final StreamingAdministrationService mStreamingAdministrationService;
    final RemoteLinkAdministrationService mRemoteLinkAdministrationService;
    volatile LogicalCallDiagnosticService mLogicalCallDiagnosticService;
    volatile AudioCallCoordinator mAudioCallCoordinator;
    volatile ManagedRecordingCatalog mManagedRecordingCatalog;
    volatile ManagedRecordingTranscriptionService mManagedRecordingTranscriptionService;
    final ManagedRecordingMaintenance mManagedRecordingMaintenance = new ManagedRecordingMaintenance();
    volatile MapSnapshotService mMapSnapshotService;
    final ScanListModel mScanListModel;
    final RadioReferenceDirectoryService mRadioReferenceDirectoryService;
    final RadioReferenceImportService mRadioReferenceImportService;
    final SpectrumSnapSettingsService mSpectrumSnapSettingsService;
    final WebReceiverSettingsService mWebReceiverSettingsService;
    final RetainedStatisticsCatalog mRetainedStatisticsCatalog;
    final RetainedStatisticsJobs mRetainedStatisticsJobs;

    public ReceiverWebServices(UserPreferences preferences, ConfigurationManager configuration,
                               ReceiverActivityService activity, DecodeEventViewService events,
                               RemoteLinkAdministrationService remoteLinks)
    {
        mUserPreferences = Objects.requireNonNull(preferences);
        Objects.requireNonNull(configuration);
        mActivityLogService = Objects.requireNonNull(activity);
        mDecodeEventViewService = Objects.requireNonNull(events);
        mChannelProcessingManager = Objects.requireNonNull(configuration.getChannelProcessingManager());
        mAliasAdministrationService = configuration.getAliasAdministrationService();
        mChannelAdministrationService = configuration.getChannelAdministrationService();
        mStreamingAdministrationService = configuration.getStreamingAdministrationService();
        mRemoteLinkAdministrationService = remoteLinks;
        mScanListModel = configuration.getScanListModel();
        mRadioReferenceDirectoryService = configuration.getRadioReferenceDirectoryService();
        mRadioReferenceImportService = configuration.getRadioReferenceImportService();
        java.util.ArrayList<AutoCloseable> constructed = new java.util.ArrayList<>();
        constructed.add(() -> mFeatureMaintenance.shutdownNow());
        constructed.add(mManagedRecordingMaintenance);
        try
        {
            UserPreferences userPreferences = preferences;
            ChannelProcessingManager channelProcessingManager = mChannelProcessingManager;
            ReceiverActivityService activityLogService = activity;
            TunerManager tunerManager = Objects.requireNonNull(configuration.getTunerManager());
            RemoteLinkAdministrationService remoteLinkAdministrationService = remoteLinks;
            mDatabase = new StatsWebDatabase(userPreferences);
            mEntityCatalog = new WebEntityNavigationCatalog(mDatabase::webEntityNavigationSnapshot);
            ApplicationSettingsStore receiverSettings = new ApplicationSettingsStore(
                SdrTrunkDatabasePath.getDatabasePath(userPreferences));
            mPagingToneSettingsService = new PagingToneSettingsService(receiverSettings,
                P25PagingToneService.getInstance()::setEnabled);
            mWebCallService = new StatsWebCallService(mScanListModel, mEntityCatalog,
                () -> mUserPreferences.getJmbeLibraryPreference().getPathJmbeLibrary());
            constructed.add(mWebCallService);
            mP25ActivityLiveService = new P25ActivityLiveService(
                new P25ActivityLiveService.Source()
                {
                    @Override
                    public P25ActivityLiveService.RawSnapshot snapshot()
                    {
                        return mDatabase.p25RawSnapshot();
                    }
                    @Override
                    public Object identity()
                    {
                        return mDatabase.p25SourceIdentity();
                    }
                    @Override
                    public long presentationGeneration()
                    {
                        return mDatabase.affiliationPresentationGeneration();
                    }
                    @Override
                    public long historyGeneration()
                    {
                        return mActivityLogService.historyGeneration();
                    }
                    @Override
                    public long watermark()
                    {
                        return mDatabase.savedActivityWatermark();
                    }
                    @Override
                    public Map<String,Object> after(long afterId, Long watermarkId)
                    {
                        return mDatabase.savedActivityAfter(afterId, watermarkId);
                    }
                    @Override
                    public List<ObjectNode> presentAffiliations(List<Map<String,Object>> rows)
                    {
                        return mDatabase.presentP25Affiliations(rows);
                    }
                }, mUserPreferences.getNowPlayingPreference()::getTrafficGrantAgeOutMilliseconds,
                () -> mActivityLogService.getStatus().detailedHistoryActive());
            constructed.add(mP25ActivityLiveService);
            mWebChannelCatalog = new StatsWebSnapshot<>(Duration.ofSeconds(2), () ->
                mChannelAdministrationService != null ? mChannelAdministrationService.currentCatalog().channels() : List.of());
            mTunerManager = tunerManager;
            mConventionalLiveSources = new ConventionalLiveSources(channelProcessingManager.getChannelActivityModel());
            mDecodeMessageViewService = new DecodeMessageViewService(channelProcessingManager, mConventionalLiveSources);
            constructed.add(mDecodeMessageViewService);
            mDiagnosticFftScheduler = new DiagnosticFftScheduler();
            constructed.add(mDiagnosticFftScheduler);
            mChannelDiagnosticService = new ChannelDiagnosticService(channelProcessingManager, mDiagnosticFftScheduler);
            constructed.add(mChannelDiagnosticService);
            mTunerDiagnosticService = new TunerDiagnosticService(tunerManager, mDiagnosticFftScheduler);
            constructed.add(mTunerDiagnosticService);
            mTunerSettingsService = new TunerSettingsService(tunerManager, channelProcessingManager);
            constructed.add(mTunerSettingsService);
            mTunerAdministrationService = new TunerAdministrationService(tunerManager, mTunerDiagnosticService,
                mTunerSettingsService);
            mTunerAllocationMonitorService = new TunerAllocationMonitorService(tunerManager, channelProcessingManager);
            mTunerAllocationFrequencySeedService = new TunerAllocationFrequencySeedService(mDatabase, tunerManager,
                channelProcessingManager);
            constructed.add(mTunerAllocationFrequencySeedService);
            mFrequencyListenService = new FrequencyListenService(mTunerDiagnosticService);
            constructed.add(mFrequencyListenService);
            mSpectrumDiscoveryService = new SpectrumDiscoveryService(mChannelAdministrationService,
                mTunerAdministrationService, mTunerDiagnosticService, mTunerSettingsService, mDatabase);
            constructed.add(mSpectrumDiscoveryService);
            mSpectrumSearchService = new SpectrumSearchService(mChannelAdministrationService, mTunerAdministrationService,
                mTunerDiagnosticService, mTunerSettingsService, mDatabase);
            constructed.add(mSpectrumSearchService);
            RadioReferenceDiscoveryResolver discoveryDirectory =
                new RadioReferenceDiscoveryResolver(mRadioReferenceDirectoryService);
            mSpectrumDiscoveryService.setRadioReferenceResolver(discoveryDirectory);
            mSpectrumSearchService.setRadioReferenceResolver(discoveryDirectory);
            mSpectrumDiscoveryService.setAliasImporter(mRadioReferenceImportService);
            mSpectrumSearchService.setAliasImporter(mRadioReferenceImportService);
            mLiveService = new StatsLiveService(channelProcessingManager, mEntityCatalog,
                remoteLinkAdministrationService);
            constructed.add(mLiveService);
            mP25AssignmentService = new StatsP25AssignmentService(userPreferences.getP25IdentityResolver(), mDatabase);
            constructed.add(mP25AssignmentService);
            mWebAccessDatabasePath = SdrTrunkDatabasePath.getDatabasePath(mUserPreferences);
            mRetainedStatisticsCatalog = new RetainedStatisticsCatalog(mWebAccessDatabasePath);
            mRetainedStatisticsJobs = new RetainedStatisticsJobs(mRetainedStatisticsCatalog,
                mActivityLogService::receiveMaintenanceRequest);
            constructed.add(mRetainedStatisticsJobs);
            mSpectrumSnapSettingsService = new SpectrumSnapSettingsService(mWebAccessDatabasePath);
            mWebReceiverSettingsService = new WebReceiverSettingsService(mUserPreferences.getNowPlayingPreference());
            mReceiverHealthService = new ReceiverHealthService(userPreferences, tunerManager,
                channelProcessingManager, activityLogService);
            constructed.add(mReceiverHealthService);
            TopicAlertService topicAlerts = null;
            PushDeliveryService pushDelivery = null;
            {
                try
                {
                    topicAlerts = new TopicAlertService(mWebAccessDatabasePath);
                    constructed.add(topicAlerts);
                    pushDelivery = new PushDeliveryService(mWebAccessDatabasePath, topicAlerts);
                }
                catch(Exception exception)
                {
                    if(topicAlerts != null)
                    {
                        topicAlerts.close();
                        constructed.remove(topicAlerts);
                    }
                    topicAlerts = null;
                    mLog.warn("Topic alerts are unavailable; receiver and web service remain active ({})",
                        exception.getClass().getSimpleName());
                }
            }
            mTopicAlertService = topicAlerts;
            mPushDeliveryService = pushDelivery;
            if(mPushDeliveryService != null) constructed.add(mPushDeliveryService);
            mTopicAlertEventListener = topicAlerts != null ? topicAlerts::receiveEventView : event -> { };
            if(topicAlerts != null)
            {
                topicAlerts.setEventValidator(mDecodeEventViewService::isCurrent);
                topicAlerts.setToneProjector(mDecodeEventViewService::completedToneView);
                topicAlerts.setCallProjector(mDecodeEventViewService::completedCallView);
            }
            mSupportBundleService = new SupportBundleService(mWebAccessDatabasePath,
                mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog(),
                mReceiverHealthService::snapshot);
            constructed.add(mSupportBundleService);
            mApplicationLogService = new ApplicationLogService(
                () -> mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog());
            constructed.add(mApplicationLogService);
        }
        catch(RuntimeException | Error failure)
        {
            for(AutoCloseable resource: constructed.reversed())
            {
                try { resource.close(); }
                catch(Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        }
    }

    public synchronized void start()
    {
        if(mClosed) throw new IllegalStateException("Receiver web services are closed");
        if(mStarted) return;
        try
        {
            mStarted = true;
            mLiveService.start();
            mP25AssignmentService.start();
            mWebCallService.start();
            mReceiverHealthService.start();
            if(mTopicAlertService != null)
            {
                reconcileTopicAlertObservation();
                mPushDeliveryService.start();
                mFeatureMaintenance.scheduleWithFixedDelay(() -> {
                    if(!mClosed && mTopicAlertService.configuration().push().enabled())
                        mTopicAlertService.observeHealth(mReceiverHealthService.snapshot());
                }, 5, 5, TimeUnit.SECONDS);
            }
            mTunerAllocationFrequencySeedService.start();
        }
        catch(RuntimeException | Error failure)
        {
            try { close(); }
            catch(Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    /** Stop hardware-changing administration before channels and tuners are torn down. */
    public synchronized void quiesceAdministration()
    {
        if(mAdministrationQuiesced) return;
        closeOwned(List.of(mRetainedStatisticsJobs, mSpectrumSearchService, mSpectrumDiscoveryService,
            mTunerSettingsService));
        mAdministrationQuiesced = true;
    }

    /** Shares the recording worker's separate catalog with request handlers; it is owned by the receiver. */
    public synchronized void setManagedRecordingCatalog(ManagedRecordingCatalog catalog)
    {
        closeRecordingReadySubscription();
        mManagedRecordingCatalog = catalog;
        if(!mClosed && catalog != null && mTopicAlertService != null)
        {
            try
            {
                mRecordingReadySubscription = catalog.addRecordingReadyListener(ready -> {
                    if(!mClosed && mManagedRecordingCatalog == catalog)
                        mTopicAlertService.receiveRecordingReady(ready.sourceCallReferences());
                });
            }
            catch(RuntimeException unavailable)
            {
                mLog.warn("Recording readiness is unavailable; alerts will use their audio wait timeout", unavailable);
            }
        }
    }

    private void closeRecordingReadySubscription()
    {
        if(mRecordingReadySubscription == null) return;
        try { mRecordingReadySubscription.close(); }
        catch(Exception failure) { mLog.warn("Unable to remove the recording readiness observer", failure); }
        mRecordingReadySubscription = null;
    }

    public void setManagedRecordingTranscriptionService(ManagedRecordingTranscriptionService service)
    {
        mManagedRecordingTranscriptionService = service;
    }

    /**
     * Receives completed calls independently from local Java playback.
     */
    public void receive(CompletedAudioCall call)
    {
        mWebCallService.receive(call);
    }

    /** The completed-call coordinator invokes this independently of audio, recording policy and Activity logging. */
    public void receiveCompletedCallForAlerts(CompletedAudioCall call)
    {
        if(!mClosed && mTopicAlertService != null) mTopicAlertService.receiveCompletedCall(call);
    }

    /**
     * Adds the application-owned audio/output queue sources after the completed-call coordinator is constructed.
     */
    public void setReceiverHealthOutputSources(AudioCallCoordinator coordinator,
                                               AudioRecordingManager recordingManager,
                                               AudioStreamingManager streamingManager,
                                               io.github.dsheirer.audio.broadcast.BroadcastModel broadcastModel)
    {
        mReceiverHealthService.setOutputSources(coordinator, recordingManager, streamingManager);
        mReceiverHealthService.setRadioResolveAcceptedCalls(
            broadcastModel != null ? broadcastModel::getRadioResolveAcceptedCallCount : null);
    }

    /** Attaches the application-owned bounded diagnostic sources after the coordinator is constructed. */
    public synchronized void setLogicalCallDiagnostics(LogicalCallDiagnosticService service,
                                                       AudioCallCoordinator coordinator)
    {
        if(mClosed)
        {
            return;
        }
        mLogicalCallDiagnosticService = service;
        mAudioCallCoordinator = coordinator;
    }

    /** Attaches receiver-owned map snapshots; the application owns the decode listener and service lifecycle. */
    public void setMapSnapshotService(MapSnapshotService service)
    {
        mMapSnapshotService = service;
    }

    synchronized void reconcileTopicAlertObservation()
    {
        if(mTopicAlertService == null) return;
        boolean needed = !mClosed && mTopicAlertService.configuration().push().enabled();
        if(needed == mTopicAlertObservationActive) return;
        if(needed) mDecodeEventViewService.addListener(mTopicAlertEventListener);
        else mDecodeEventViewService.removeListener(mTopicAlertEventListener);
        mTopicAlertObservationActive = needed;
    }

    Map<String,Object> topicAlertOptions()
    {
        return TopicAlertOptions.read(mWebAccessDatabasePath);
    }

    @Override
    public synchronized void close()
    {
        if(mClosed) return;
        // A failed hardware quiescence is not a completed shutdown: the caller must not tear down its tuners.
        quiesceAdministration();
        mClosed = true;
        java.util.ArrayList<AutoCloseable> owned = new java.util.ArrayList<>(List.of(
            this::closeRecordingReadySubscription, mManagedRecordingMaintenance, mSupportBundleService,
            mTunerAllocationFrequencySeedService, mApplicationLogService));
        if(mPushDeliveryService != null) owned.add(mPushDeliveryService);
        if(mTopicAlertService != null)
        {
            owned.add(() -> {
                if(mTopicAlertObservationActive) mDecodeEventViewService.removeListener(mTopicAlertEventListener);
                mTopicAlertObservationActive = false;
            });
            owned.add(mTopicAlertService);
        }
        mAudioCallCoordinator = null;
        mLogicalCallDiagnosticService = null;
        owned.addAll(List.of(mReceiverHealthService, () -> mFeatureMaintenance.shutdownNow(), mLiveService,
            mP25ActivityLiveService,
            mP25AssignmentService, mDecodeMessageViewService, mChannelDiagnosticService, mFrequencyListenService,
            mTunerDiagnosticService, mDiagnosticFftScheduler, mWebCallService));
        closeOwned(owned);
    }

    private static void closeOwned(List<? extends AutoCloseable> owned)
    {
        Throwable failure = null;
        for(AutoCloseable resource: owned)
        {
            try { resource.close(); }
            catch(Throwable cleanupFailure)
            {
                if(failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        if(failure instanceof Error error) throw error;
        if(failure instanceof RuntimeException exception) throw exception;
        if(failure != null) throw new IllegalStateException("Receiver web cleanup did not complete", failure);
    }
}
