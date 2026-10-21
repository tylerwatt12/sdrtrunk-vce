/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.receiver;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticService;
import io.github.dsheirer.audio.codec.mbe.JmbeLibraryLoader;
import io.github.dsheirer.audio.tone.P25PagingToneService;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.log.ApplicationLog;
import io.github.dsheirer.map.MapSnapshotService;
import io.github.dsheirer.metadata.site.SiteControlChannelLearner;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.module.log.EventLogger;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.record.managed.ManagedRecordingTranscriptionService;
import io.github.dsheirer.remote.RemoteConnectivityService;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayLibraryHelper;
import io.github.dsheirer.stats.ReceiverWebServices;
import io.github.dsheirer.stats.StatsWebServerService;
import io.github.dsheirer.stats.activity.ReceiverActivityService;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.vector.calibrate.CalibrationManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns receiver services and their ordered lifetime, independently of any desktop toolkit. Start composes the
 * graph and attempts the web listener; activate starts hardware and outputs after optional local listener setup.
 */
public final class ReceiverRuntime implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReceiverRuntime.class);
    private static final long SITE_METADATA_DRAIN_MILLISECONDS = 8_000L;
    private static final long CALL_COORDINATOR_STOP_MILLISECONDS = 8_000L;
    private static final long STATISTICS_DRAIN_MILLISECONDS = 2_000L;
    private static final long STATISTICS_STOP_MILLISECONDS = 8_000L;

    private final UserPreferences mPreferences;
    private AutoCloseable mDataRootLock;
    private final Runnable mStartup;
    private final Runnable mActivation;
    private final BooleanSupplier mShutdown;
    private final Runnable mClosePreferences;
    private final boolean mInstallShutdownHook;
    private Thread mShutdownHook;
    private Runnable mBeforeShutdown = () -> {};
    private State mState = State.NEW;
    private boolean mConfiguredChannelsStarted;
    private boolean mActivated;
    private boolean mServicesStopped;
    private boolean mDatabaseBoundarySafe;
    private boolean mPreDrainCleanupSafe = true;

    private ApplicationLog mApplicationLog;
    private TunerManager mTuners;
    private ConfigurationManager mConfiguration;
    private ReceiverActivityService mActivity;
    private AutoCloseable mToneActivitySubscription;
    private LogicalCallDiagnosticService mCallDiagnostics;
    private ManagedRecordingCatalog mRecordingCatalog;
    private ManagedRecordingTranscriptionService mTranscription;
    private ScheduledExecutorService mRecordingMaintenance;
    private ScheduledFuture<?> mRecordingRetention;
    private AudioRecordingManager mRecording;
    private AudioStreamingManager mStreaming;
    private DecodeEventViewService mDecodeEvents;
    private RemoteConnectivityService mRemote;
    private ReceiverWebServices mWebServices;
    private StatsWebServerService mWebServer;
    private AudioCallCoordinator mCalls;
    private MapSnapshotService mMap;

    public ReceiverRuntime(UserPreferences preferences, PortableDataRootLock dataRootLock)
    {
        mPreferences = Objects.requireNonNull(preferences);
        mDataRootLock = Objects.requireNonNull(dataRootLock);
        mStartup = this::startServices;
        mActivation = this::activateServices;
        mShutdown = this::stopServices;
        mClosePreferences = SqlitePreferencesFactory::shutdown;
        mInstallShutdownHook = true;
    }

    /** Exercises the same lifetime boundary without opening hardware, databases or desktop toolkits. */
    ReceiverRuntime(Runnable startup, BooleanSupplier shutdown, AutoCloseable dataRootLock,
                    Runnable closePreferences, boolean installShutdownHook)
    {
        this(startup, () -> {}, shutdown, dataRootLock, closePreferences, installShutdownHook);
    }

    ReceiverRuntime(Runnable startup, Runnable activation, BooleanSupplier shutdown, AutoCloseable dataRootLock,
                    Runnable closePreferences, boolean installShutdownHook)
    {
        mPreferences = null;
        mStartup = Objects.requireNonNull(startup);
        mActivation = Objects.requireNonNull(activation);
        mShutdown = Objects.requireNonNull(shutdown);
        mDataRootLock = Objects.requireNonNull(dataRootLock);
        mClosePreferences = Objects.requireNonNull(closePreferences);
        mInstallShutdownHook = installShutdownHook;
    }

    public synchronized void start()
    {
        if(mState != State.NEW) throw new IllegalStateException("Receiver runtime has already been started or closed");
        mState = State.STARTING;
        try
        {
            if(mInstallShutdownHook)
            {
                mShutdownHook = new Thread(this::closeFromShutdownHook, "sdrtrunk receiver shutdown");
                Runtime.getRuntime().addShutdownHook(mShutdownHook);
            }
            mStartup.run();
            mState = State.RUNNING;
        }
        catch(RuntimeException | Error failure)
        {
            try { shutdown(true); }
            catch(RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    public synchronized boolean isRunning()
    {
        return mState == State.RUNNING;
    }

    /** Starts hardware and outputs once, regardless of whether configured channels will be started. */
    public synchronized void activate()
    {
        if(mState != State.RUNNING) throw new IllegalStateException("Receiver runtime is not running");
        if(mActivated) return;
        try
        {
            mActivation.run();
            mActivated = true;
        }
        catch(RuntimeException | Error failure)
        {
            try { shutdown(true); }
            catch(RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    public ConfigurationManager configuration() { return mConfiguration; }
    public TunerManager tuners() { return mTuners; }
    public StatsWebServerService webServer() { return mWebServer; }

    /** Optional local-shell cleanup; receiver services never depend on the shell's event loop. */
    public synchronized void setBeforeShutdown(Runnable cleanup)
    {
        if(mState != State.RUNNING) throw new IllegalStateException("Receiver runtime is not running");
        mBeforeShutdown = Objects.requireNonNull(cleanup);
    }

    /** Called once after optional desktop launch interactions have finished. */
    public synchronized void startConfiguredChannels(boolean unattended)
    {
        if(mState != State.RUNNING) throw new IllegalStateException("Receiver runtime is not running");
        if(!mActivated) throw new IllegalStateException("Activate the receiver before starting channels");
        if(mConfiguredChannelsStarted) return;
        mConfiguredChannelsStarted = true;
        if(unattended && mPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded())
        {
            EncryptionKeyVaultService vault = mPreferences.getEncryptionKeyPreference().getVaultService();
            if(vault.hasVault() && !vault.isUnlocked() && vault.isPromptOnLaunch()) vault.disableForRun();
        }
        mConfiguration.executeConfigurationTask(() ->
        {
            for(Channel channel: mConfiguration.getChannelModel().getAutoStartChannels())
            {
                try
                {
                    LOGGER.info("Auto-starting channel {}", channel.getName());
                    mConfiguration.getChannelProcessingManager().start(channel);
                }
                catch(ChannelException | RuntimeException exception)
                {
                    LOGGER.error("Channel: {} auto-start failed: {}", channel.getName(), exception.getMessage(), exception);
                }
            }
            return null;
        });
    }

    private void startServices()
    {
        mApplicationLog = new ApplicationLog(mPreferences);
        mApplicationLog.start();
        if(mPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded())
            mPreferences.getEncryptionKeyPreference().getVaultService().tryAutoUnlockSavedPassword();
        //Load before TunerManager initializes generated SDRplay native bindings.
        if(SDRPlayLibraryHelper.LOADED) LOGGER.info("SDRPlay API native library preemptively loaded");
        ThreadPool.logSettings();

        mActivity = new ReceiverActivityService(mPreferences);
        mToneActivitySubscription = P25PagingToneService.getInstance()
            .addCompletedToneListener(mActivity::receiveCompletedToneSequence);
        mTuners = new TunerManager(mPreferences);
        AliasModel aliases = new AliasModel();
        mConfiguration = new ConfigurationManager(mPreferences, mTuners, aliases,
            new EventLogManager(aliases, mPreferences), mActivity::receiveMaintenanceRequest);
        try
        {
            mRemote = new RemoteConnectivityService(mConfiguration);
            mConfiguration.getChannelProcessingManager().setP25RemoteBitstreamService(mRemote);
        }
        catch(IOException exception)
        {
            LOGGER.error("Remote-link settings are unavailable; remote connectivity is disabled", exception);
        }
        CalibrationManager.getInstance(mPreferences);
        mCallDiagnostics = new LogicalCallDiagnosticService();
        try
        {
            Path catalogPath = SdrTrunkDatabasePath.getDatabasePath(mPreferences)
                .resolveSibling("managed-recordings.sqlite");
            mRecordingCatalog = new ManagedRecordingCatalog(catalogPath,
                mPreferences.getDirectoryPreference().getDirectoryManagedRecording());
        }
        catch(Exception exception)
        {
            LOGGER.error("Managed recordings catalog is unavailable", exception);
            LOGGER.warn("Managed recordings are unavailable for this run; the saved recording mode is unchanged");
        }
        mRecording = mRecordingCatalog != null ?
            new AudioRecordingManager(mPreferences, mActivity::receiveRecordedCall, mRecordingCatalog) :
            new AudioRecordingManager(mPreferences, mActivity::receiveRecordedCall);
        if(mRecordingCatalog != null)
            mTranscription = new ManagedRecordingTranscriptionService(mRecordingCatalog,
                mPreferences.getRecordPreference(), () -> mRecording.getQueueStatus().queuedSourceBytes() > 0);
        mStreaming = new AudioStreamingManager(mConfiguration.getBroadcastModel(), BroadcastFormat.MP3,
            mPreferences, mActivity::receiveStreamedCall);
        ChannelProcessingManager processing = mConfiguration.getChannelProcessingManager();
        mDecodeEvents = new DecodeEventViewService(processing, aliases);
        processing.addBoundDecodeEventListener(mDecodeEvents.getBoundDecodeEventListener());

        mWebServices = new ReceiverWebServices(mPreferences, mConfiguration, mActivity, mDecodeEvents, mRemote);
        mWebServices.setManagedRecordingCatalog(mRecordingCatalog);
        mWebServices.setManagedRecordingTranscriptionService(mTranscription);
        mWebServer = new StatsWebServerService(mPreferences, mWebServices);

        mCalls = new AudioCallCoordinator(mRecording, mStreaming, mWebServices::receive, call -> {
            try { mWebServices.receiveCompletedCallForAlerts(call); }
            finally { mActivity.receiveResolvedCall(call); }
        }, mCallDiagnostics);
        mWebServices.setLogicalCallDiagnostics(mCallDiagnostics, mCalls);
        mWebServices.setReceiverHealthOutputSources(mCalls, mRecording, mStreaming, mConfiguration.getBroadcastModel());
        processing.addAudioCallListener(mCalls);
        processing.addChannelDecodeEventListener(mActivity.getDecodeEventListener());
        processing.addControlChannelQualityListener(mActivity.getControlChannelQualityListener());
        processing.addSiteMetadataListener(mActivity);
        processing.addProtocolSiteMetadataListener(mActivity);
        processing.addSiteMetadataListener(mConfiguration.getBroadcastModel());
        processing.addSiteMetadataListener(new SiteControlChannelLearner(mConfiguration));
        mMap = new MapSnapshotService(aliases);
        processing.addDecodeEventListener(mMap);
        mWebServices.setMapSnapshotService(mMap);
        mWebServices.start();
        mWebServer.start();
    }

    private void activateServices()
    {
        mTuners.start();
        mRecording.start();
        if(mTranscription != null) mTranscription.start();
        startRecordingRetention();
        mStreaming.start();
        mConfiguration.init();
        if(mRemote != null) mRemote.start();
    }

    private void startRecordingRetention()
    {
        if(mRecordingCatalog == null) return;
        mRecordingMaintenance = Executors.newSingleThreadScheduledExecutor(
            new NamingThreadFactory("managed recording retention"));
        mRecordingRetention = mRecordingMaintenance.scheduleWithFixedDelay(() -> {
            try
            {
                Integer days = mPreferences.getRecordPreference().getManagedRetentionDays();
                if(days != null) mRecordingCatalog.pruneOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
            }
            catch(Exception exception) { LOGGER.warn("Managed recording age retention failed", exception); }
        }, 1, 24, TimeUnit.HOURS);
    }

    @Override
    public synchronized void close()
    {
        shutdown(true);
    }

    /** Stops every database owner but keeps the portable lock until the replacement/relaunch boundary. */
    public synchronized void closeForDatabaseReplacement()
    {
        shutdown(false);
    }

    private void shutdown(boolean releaseDataRootLock)
    {
        if(mState == State.CLOSED) return;
        mState = State.STOPPING;
        if(!mServicesStopped)
        {
            //A failed local window must not prevent receiver cleanup.
            mPreDrainCleanupSafe &= shutdownStep("local shell", mBeforeShutdown::run);
            mBeforeShutdown = () -> {};
            //If administration could not quiesce, leave the hook and lock intact so shutdown can retry.
            mDatabaseBoundarySafe = mShutdown.getAsBoolean() && mPreDrainCleanupSafe;
            mServicesStopped = true;
        }
        if(!releaseDataRootLock && !mDatabaseBoundarySafe)
            throw new IllegalStateException("Database-owning observers did not fully stop before SQLite database replacement");
        mClosePreferences.run();
        mState = State.CLOSED;
        removeShutdownHook();
        if(releaseDataRootLock && mDatabaseBoundarySafe) releaseDataRootLock();
        else if(releaseDataRootLock)
            LOGGER.warn("Retaining the portable-data lock until process termination because shutdown did not establish a safe database boundary");
    }

    private boolean stopServices()
    {
        LOGGER.info("Receiver shutdown started ...");
        //Stop incoming commands and hardware administration before draining receiver outputs.
        boolean safe = true;
        if(mWebServer != null)
        {
            mPreDrainCleanupSafe &= shutdownStep("HTTP listener", mWebServer::close);
            safe &= mPreDrainCleanupSafe;
        }
        //Outstanding hardware/configuration commands must finish before receiver resources can be stopped.
        //Keep the lock and preference writer available when this boundary needs a retry.
        if(mWebServices != null) mWebServices.quiesceAdministration();
        if(mConfiguration != null) mConfiguration.quiesceAdministration();
        safe &= shutdownStep("pending receiver settings", ApplicationSettingsStore::flushPendingWritesNow);
        ChannelProcessingManager processing = mConfiguration != null ? mConfiguration.getChannelProcessingManager() : null;
        if(processing != null)
        {
            safe &= shutdownStep("channels", processing::close);
            if(mRemote != null) safe &= shutdownStep("remote connectivity", mRemote::close);
            boolean drained = shutdownCheck("site-metadata drain", () ->
                processing.awaitSiteMetadataDrain(SITE_METADATA_DRAIN_MILLISECONDS, TimeUnit.MILLISECONDS));
            safe &= drained;
            if(!drained) LOGGER.warn("Site-metadata observers did not drain before shutdown");
        }
        if(mCalls != null)
        {
            boolean stopped = shutdownCheck("logical-call resolver", () ->
                mCalls.disposeAndAwait(CALL_COORDINATOR_STOP_MILLISECONDS, TimeUnit.MILLISECONDS));
            safe &= stopped;
            if(!stopped) LOGGER.warn("Logical-call resolver did not fully stop before shutdown");
        }
        if(mToneActivitySubscription != null)
        {
            boolean drained = shutdownCheck("tone observations", () ->
                P25PagingToneService.getInstance().awaitWorkerDrain(STATISTICS_DRAIN_MILLISECONDS, TimeUnit.MILLISECONDS));
            safe &= drained;
            if(!drained) LOGGER.warn("Tone observations did not finish before statistics shutdown");
            try { mToneActivitySubscription.close(); }
            catch(Exception exception) { LOGGER.warn("Unable to remove the completed-tone Activity observer", exception); }
            mToneActivitySubscription = null;
        }
        if(mWebServices != null) safe &= shutdownStep("receiver web services", mWebServices::close);
        if(mMap != null) safe &= shutdownStep("map observations", mMap::close);
        if(processing != null && mActivity != null)
        {
            safe &= shutdownStep("Activity subscriptions", () -> {
                processing.removeChannelDecodeEventListener(mActivity.getDecodeEventListener());
                processing.removeControlChannelQualityListener(mActivity.getControlChannelQualityListener());
                processing.removeSiteMetadataListener(mActivity);
                processing.removeProtocolSiteMetadataListener(mActivity);
            });
        }
        if(mDecodeEvents != null)
        {
            if(processing != null) safe &= shutdownStep("decode-event subscription", () ->
                processing.removeBoundDecodeEventListener(mDecodeEvents.getBoundDecodeEventListener()));
            safe &= shutdownStep("decode-event views", mDecodeEvents::close);
        }
        safe &= shutdownStep("event-log writes", EventLogger::flushPendingWrites);
        if(mStreaming != null) safe &= shutdownStep("audio streaming", mStreaming::stop);
        if(mConfiguration != null) safe &= shutdownStep("stream destinations", mConfiguration.getBroadcastModel()::close);
        if(mRecordingRetention != null) { mRecordingRetention.cancel(false); mRecordingRetention = null; }
        if(mRecordingMaintenance != null)
        {
            mRecordingMaintenance.shutdown();
            safe &= shutdownCheck("managed recording retention", () ->
                mRecordingMaintenance.awaitTermination(30, TimeUnit.SECONDS));
        }
        if(mTranscription != null) safe &= shutdownStep("recording transcription", mTranscription::close);
        if(mRecording != null) safe &= shutdownStep("audio recording", mRecording::stop);
        if(mRecordingCatalog != null)
        {
            safe &= shutdownStep("managed recordings catalog", mRecordingCatalog::close);
        }
        if(mActivity != null)
        {
            //Output confirmations must cross the observation barrier before the final database-writer drain.
            boolean drained = shutdownCheck("statistics observations", () ->
                mActivity.awaitObservationDrain(STATISTICS_DRAIN_MILLISECONDS, TimeUnit.MILLISECONDS));
            boolean stopped = shutdownCheck("statistics writer", () ->
                mActivity.disposeAndAwait(STATISTICS_STOP_MILLISECONDS, TimeUnit.MILLISECONDS));
            safe &= drained && stopped;
            if(!drained || !stopped) LOGGER.warn("Statistics service did not fully drain and stop [observations={}, workers={}]", drained, stopped);
        }
        if(mConfiguration != null)
        {
            safe &= shutdownStep("configuration commands", mConfiguration::close);
            safe &= shutdownStep("RadioReference directory", mConfiguration.getRadioReferenceDirectoryService()::close);
        }
        if(mCallDiagnostics != null) safe &= shutdownStep("call diagnostics", mCallDiagnostics::close);
        if(mTuners != null) safe &= shutdownStep("tuners", mTuners::stop);
        if(mPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded())
        {
            safe &= shutdownStep("encryption keys", mPreferences.getEncryptionKeyPreference().getVaultService()::lock);
            safe &= shutdownStep("optional decoder module", mPreferences.getVoiceDecryptionModulePreference().getModuleManager()::close);
        }
        safe &= shutdownStep("JMBE decoder libraries", JmbeLibraryLoader.getInstance()::close);
        safe &= shutdownStep("pending receiver settings", ApplicationSettingsStore::flushPendingWritesNow);
        LOGGER.info("Receiver shutdown complete.");
        if(mApplicationLog != null) safe &= shutdownStep("application logging", mApplicationLog::stop);
        return safe;
    }

    public synchronized void releaseDataRootLock()
    {
        if(mState != State.CLOSED || !mDatabaseBoundarySafe)
            throw new IllegalStateException("Stop receiver database owners before releasing the data lock");
        if(mDataRootLock != null)
        {
            try { mDataRootLock.close(); }
            catch(Exception exception) { throw new IllegalStateException("Unable to release the portable data lock", exception); }
            mDataRootLock = null;
        }
    }

    private void closeFromShutdownHook()
    {
        try { close(); }
        catch(Throwable failure) { LOGGER.error("Receiver shutdown did not complete cleanly", failure); }
    }

    private void removeShutdownHook()
    {
        if(mShutdownHook == null || Thread.currentThread() == mShutdownHook) return;
        try { Runtime.getRuntime().removeShutdownHook(mShutdownHook); }
        catch(IllegalStateException ignored) { /* The JVM has already begun shutdown. */ }
        mShutdownHook = null;
    }

    private static boolean shutdownStep(String component, ShutdownAction action)
    {
        return shutdownCheck(component, () -> { action.run(); return true; });
    }

    private static boolean shutdownCheck(String component, ShutdownCheck action)
    {
        try { return action.run(); }
        catch(Exception | LinkageError failure)
        {
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.warn("Unable to stop {} cleanly", component, failure);
            return false;
        }
    }

    @FunctionalInterface
    private interface ShutdownAction { void run() throws Exception; }

    @FunctionalInterface
    private interface ShutdownCheck { boolean run() throws Exception; }

    private enum State { NEW, STARTING, RUNNING, STOPPING, CLOSED }
}
