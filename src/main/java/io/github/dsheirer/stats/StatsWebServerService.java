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

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.application.service.LiveContextResolver;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.stats.activity.P25ActivityCommitListener;
import io.github.dsheirer.stats.activity.P25ActivityLogPath;
import io.github.dsheirer.stats.activity.P25ActivityLogService;
import io.github.dsheirer.stats.activity.P25ActivityLogStatus;
import io.github.dsheirer.spectrum.stream.SpectrumStreamService;
import io.github.dsheirer.spectrum.stream.SpectrumFrameSource;
import io.github.dsheirer.spectrum.stream.SyntheticSpectrumFrameSource;
import io.github.dsheirer.spectrum.stream.TunerSpectrumFrameSource;
import io.github.dsheirer.web.WebApplicationService;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.RemoteAddressAdmissionPolicy;
import io.github.dsheirer.web.access.WebFeature;
import io.github.dsheirer.web.access.WebRequestSubjectResolver;
import io.github.dsheirer.web.access.WebRequestSubjectResolver.WebAuthorization;
import io.github.dsheirer.web.auth.SingleAdminAuthenticationService;
import io.github.dsheirer.web.auth.WebAdminAuthenticationHandler;
import io.github.dsheirer.web.auth.WebAdminCredentialStore;
import io.github.dsheirer.web.config.WebListenAddress;
import io.github.dsheirer.web.signal.SignalOriginPolicy;
import io.github.dsheirer.web.signal.SignalWebSocketTransport;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.TlsMaterialException;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import io.github.dsheirer.web.live.LiveActivityService;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.vector.calibrate.CalibrationManager;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compatibility facade for the existing stats web feature, now mounted in the application's Jetty lifecycle owner.
 * Callers keep the same event and navigation API while HTTP, SSE, static assets, audio, and future WebSockets share
 * one bounded listener.
 */
public class StatsWebServerService implements AutoCloseable, P25ActivityCommitListener
{
    private static final Logger mLog = LoggerFactory.getLogger(StatsWebServerService.class);

    private final UserPreferences mUserPreferences;
    private final StatsWebDatabase mDatabase;
    private final StatsLiveService mLiveService;
    private final LiveActivityService mLiveActivityService;
    private final StatsWebCallService mWebCallService = new StatsWebCallService();
    private final ChannelProcessingManager mChannelProcessingManager;
    private final P25ActivityLogService mActivityLogService;
    private final TunerManager mTunerManager;
    private final InMemoryFeatureAccessPolicy mFeatureAccessPolicy =
        InMemoryFeatureAccessPolicy.currentProfileDefaults();
    private WebApplicationService mWebApplicationService;
    private StatsWebHandler mHandler;
    private SpectrumStreamService mSpectrumStreamService;
    private SignalWebSocketTransport mSignalTransport;
    private SingleAdminAuthenticationService mAuthenticationService;
    private WebAdminAuthenticationHandler mAuthenticationHandler;
    private TunerSpectrumFrameSource mTunerSpectrumFrameSource;
    private String mSignalSourceType = "unavailable";
    private Path mAssetRoot;
    private WebListenAddress mListenAddress;
    private boolean mHttpsEnabled;

    public StatsWebServerService(UserPreferences userPreferences)
    {
        this(userPreferences, null, null, null);
    }

    public StatsWebServerService(UserPreferences userPreferences, ChannelProcessingManager channelProcessingManager)
    {
        this(userPreferences, channelProcessingManager, null, null);
    }

    public StatsWebServerService(UserPreferences userPreferences, ChannelProcessingManager channelProcessingManager,
                                 P25ActivityLogService activityLogService)
    {
        this(userPreferences, channelProcessingManager, activityLogService, null);
    }

    public StatsWebServerService(UserPreferences userPreferences, ChannelProcessingManager channelProcessingManager,
                                 P25ActivityLogService activityLogService, TunerManager tunerManager)
    {
        mUserPreferences = userPreferences;
        mChannelProcessingManager = channelProcessingManager;
        mActivityLogService = activityLogService;
        mTunerManager = tunerManager;
        mDatabase = new StatsWebDatabase(userPreferences);
        mLiveService = new StatsLiveService(mDatabase,
            channelProcessingManager != null ? channelProcessingManager.getChannelActivityModel() : null);
        mLiveActivityService = channelProcessingManager != null ?
            new LiveActivityService(new LiveContextResolver(channelProcessingManager)) : null;
        MyEventBus.getGlobalEventBus().register(this);
        updateServerState();
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.APPLICATION || preferenceType == PreferenceType.DIRECTORY)
        {
            updateServerState();
        }
    }

    private synchronized void updateServerState()
    {
        ApplicationPreference preference = mUserPreferences.getApplicationPreference();

        if(!preference.isStatsWebServerEnabled())
        {
            stop();
            return;
        }

        WebListenAddress requestedListenAddress;

        try
        {
            requestedListenAddress = WebListenAddress.parse(preference.getStatsWebServerListenAddress());
        }
        catch(IllegalArgumentException exception)
        {
            mLog.warn("Embedded web-server listen address is invalid", exception);
            stop();
            return;
        }

        boolean requestedHttps = preference.isStatsWebServerHttpsEnabled();
        Path requestedRoot = StatsWebPath.getAssetsPath();

        if(isRunning() && requestedListenAddress.equals(mListenAddress) && requestedHttps == mHttpsEnabled &&
            requestedRoot.equals(mAssetRoot))
        {
            return;
        }

        stop();
        start(requestedRoot, requestedListenAddress, requestedHttps);
    }

    private void start(Path assetRoot, WebListenAddress listenAddress, boolean httpsEnabled)
    {
        try
        {
            Files.createDirectories(assetRoot);
            InetAddress bindAddress = listenAddress.resolveBindHost();
            TlsMaterial tlsMaterial = httpsEnabled ?
                new WebTlsMaterialService(PortableApplicationPaths.getDataRoot()).validateInstalledMaterial() : null;

            if(mChannelProcessingManager != null)
            {
                mChannelProcessingManager.setChannelActivityEnabled("stats-web", true);
            }

            mLiveService.start();
            if(mLiveActivityService != null)
            {
                mLiveActivityService.start();
            }
            mWebCallService.start();
            mAssetRoot = assetRoot;
            mListenAddress = listenAddress;
            mHttpsEnabled = httpsEnabled;
            SpectrumFrameSource frameSource;

            if(mTunerManager != null)
            {
                mTunerSpectrumFrameSource = new TunerSpectrumFrameSource(
                    TunerSpectrumFrameSource.Configuration.defaults(), mTunerManager);
                frameSource = mTunerSpectrumFrameSource;
                mSignalSourceType = "tuner";
            }
            else
            {
                frameSource = new SyntheticSpectrumFrameSource(
                    new SyntheticSpectrumFrameSource.Configuration(1, 851_000_000L, 10_000_000L, 4_096,
                        Duration.ofMillis(50), "sdrtrunk synthetic spectrum"));
                mSignalSourceType = "synthetic";
            }

            mSpectrumStreamService = new SpectrumStreamService(
                new SpectrumStreamService.Configuration(1, Duration.ofSeconds(3),
                    "sdrtrunk spectrum lifecycle"), frameSource);
            RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy = RemoteAddressAdmissionPolicy.allowAll();
            mAuthenticationService = new SingleAdminAuthenticationService(new WebAdminCredentialStore(
                SdrTrunkDatabasePath.getDatabasePath(mUserPreferences)));
            AtomicReference<WebAdminAuthenticationHandler> authenticationHandler = new AtomicReference<>();
            WebRequestSubjectResolver subjectResolver = new WebRequestSubjectResolver()
            {
                @Override
                public AuthorizationSubject resolve(Request request)
                {
                    WebAdminAuthenticationHandler current = authenticationHandler.get();
                    return current != null ? current.webRequestSubjectResolver().resolve(request) :
                        AuthorizationSubject.ANONYMOUS;
                }

                @Override
                public WebAuthorization resolveAuthorization(Request request)
                {
                    WebAdminAuthenticationHandler current = authenticationHandler.get();
                    return current != null ? current.webRequestSubjectResolver().resolveAuthorization(request) :
                        WebAuthorization.permanent(AuthorizationSubject.ANONYMOUS);
                }
            };
            mHandler = new StatsWebHandler(assetRoot, mDatabase, mLiveService, mWebCallService,
                this::status, mLiveActivityService, mFeatureAccessPolicy, subjectResolver,
                remoteAddressAdmissionPolicy);
            mAuthenticationHandler = new WebAdminAuthenticationHandler(mAuthenticationService, mHandler,
                remoteAddressAdmissionPolicy);
            authenticationHandler.set(mAuthenticationHandler);
            mSignalTransport = new SignalWebSocketTransport(SignalWebSocketTransport.Configuration.defaults(),
                mSpectrumStreamService, mFeatureAccessPolicy, mAuthenticationHandler.signalSubjectResolver(),
                SignalOriginPolicy.sameOrigin(), remoteAddressAdmissionPolicy);
            String browserHost = bindAddress.isAnyLocalAddress() ?
                (bindAddress.getAddress().length == 16 ? "::1" : "127.0.0.1") : listenAddress.host();
            mWebApplicationService = new WebApplicationService(
                WebApplicationService.Configuration.application(bindAddress, listenAddress.port(), browserHost,
                    tlsMaterial),
                mAuthenticationHandler,
                mSignalTransport::configure);
            mWebApplicationService.start();

            mLog.info("Stats routes mounted at {}://{}/ using assets [{}]",
                httpsEnabled ? "https" : "http", listenAddress, assetRoot);
        }
        catch(IOException | SQLException | TlsMaterialException | RuntimeException exception)
        {
            mLog.warn("Unable to start web application at [{}]", listenAddress, exception);
            stop();
        }
    }

    private synchronized void stop()
    {
        SignalWebSocketTransport signalTransport = mSignalTransport;
        mSignalTransport = null;

        if(signalTransport != null)
        {
            try
            {
                signalTransport.close();
            }
            catch(RuntimeException exception)
            {
                mLog.warn("Unable to stop signal WebSocket transport cleanly", exception);
            }
        }

        WebApplicationService webApplicationService = mWebApplicationService;
        mWebApplicationService = null;

        if(webApplicationService != null)
        {
            try
            {
                webApplicationService.close();
            }
            catch(RuntimeException exception)
            {
                mLog.warn("Unable to stop web application cleanly", exception);
            }
        }

        StatsWebHandler handler = mHandler;
        mHandler = null;

        if(handler != null)
        {
            handler.close();
        }

        mAuthenticationHandler = null;
        SingleAdminAuthenticationService authenticationService = mAuthenticationService;
        mAuthenticationService = null;

        if(authenticationService != null)
        {
            authenticationService.close();
        }

        SpectrumStreamService spectrumStreamService = mSpectrumStreamService;
        mSpectrumStreamService = null;

        if(spectrumStreamService != null)
        {
            try
            {
                spectrumStreamService.close();
            }
            catch(RuntimeException exception)
            {
                mLog.warn("Unable to stop spectrum stream service cleanly", exception);
            }
        }

        mTunerSpectrumFrameSource = null;
        mSignalSourceType = "unavailable";

        mLiveService.stop();
        if(mLiveActivityService != null)
        {
            mLiveActivityService.stop();
        }
        mWebCallService.stop();
        mAssetRoot = null;
        mListenAddress = null;
        mHttpsEnabled = false;

        if(mChannelProcessingManager != null)
        {
            mChannelProcessingManager.setChannelActivityEnabled("stats-web", false);
        }
    }

    private synchronized boolean isRunning()
    {
        return mWebApplicationService != null && mWebApplicationService.isRunning();
    }

    private Map<String,Object> status()
    {
        Path assetRoot = mAssetRoot;
        WebListenAddress listenAddress = mListenAddress;
        WebApplicationService webApplicationService = mWebApplicationService;
        boolean running = webApplicationService != null && webApplicationService.isRunning();
        int localPort = running ? webApplicationService.getLocalPort() :
            (listenAddress != null ? listenAddress.port() : -1);
        WebApplicationService.ThreadPoolSnapshot threadPool = webApplicationService != null ?
            webApplicationService.getThreadPoolSnapshot() : new WebApplicationService.ThreadPoolSnapshot(0, 0, 0, 0, 0);
        Map<String,Object> status = new LinkedHashMap<>();
        status.put("server", Map.of(
            "enabled", running,
            "port", localPort,
            "listenAddress", listenAddress != null ? listenAddress.toString() : "",
            "https", mHttpsEnabled,
            "assetsAvailable", assetRoot != null && Files.isRegularFile(assetRoot.resolve("index.html")),
            "liveChannels", Map.of(
                "systems", "/live/systems",
                "webCalls", "/live/web-calls",
                "activity", "/live/activity"
            ),
            "threadPool", Map.of(
                "threads", threadPool.threads(),
                "busy", threadPool.busyThreads(),
                "idle", threadPool.idleThreads(),
                "queued", threadPool.queuedTasks(),
                "maximum", threadPool.maximumThreads()
            )
        ));
        status.put("database", mDatabase.status());
        status.put("statsLogging", publicStatsLoggingStatus());
        status.put("webPlayer", mWebCallService.status());
        status.put("runtime", runtimeStatus());
        Map<String,String> featureModes = new LinkedHashMap<>();

        for(WebFeature feature: WebFeature.values())
        {
            featureModes.put(feature.getId(), mFeatureAccessPolicy.getMode(feature).name());
        }

        status.put("featureAccess", Map.of(
            "revision", mFeatureAccessPolicy.getRevision(),
            "modes", featureModes
        ));
        SingleAdminAuthenticationService authenticationService = mAuthenticationService;
        status.put("authentication", Map.of(
            "configured", authenticationService != null && authenticationService.isConfigured(),
            "activeSessions", authenticationService != null ? authenticationService.getActiveSessionCount() : 0
        ));
        SpectrumStreamService spectrumStreamService = mSpectrumStreamService;
        SignalWebSocketTransport signalTransport = mSignalTransport;
        TunerSpectrumFrameSource tunerSpectrumFrameSource = mTunerSpectrumFrameSource;
        status.put("signal", Map.of(
            "available", spectrumStreamService != null,
            "subscribers", spectrumStreamService != null ? spectrumStreamService.getSubscriberCount() : 0,
            "sourceRunning", spectrumStreamService != null && spectrumStreamService.isSourceRunning(),
            "sessions", signalTransport != null ? signalTransport.getActiveSessionCount() : 0,
            "webSocket", SignalWebSocketTransport.PATH,
            "metrics", Map.ofEntries(
                Map.entry("publishedFrames", spectrumStreamService != null ?
                    spectrumStreamService.getPublishedFrameCount() : 0),
                Map.entry("sourceStarts", spectrumStreamService != null ? spectrumStreamService.getSourceStartCount() : 0),
                Map.entry("sourceStops", spectrumStreamService != null ? spectrumStreamService.getSourceStopCount() : 0),
                Map.entry("deliveredFrames", signalTransport != null ? signalTransport.getDeliveredFrameCount() : 0),
                Map.entry("failedSends", signalTransport != null ? signalTransport.getFailedSendCount() : 0),
                Map.entry("maximumSendMicros", signalTransport != null ?
                    TimeUnit.NANOSECONDS.toMicros(signalTransport.getMaximumSendNanos()) : 0),
                Map.entry("maximumDeliveryGapMicros", signalTransport != null ?
                    TimeUnit.NANOSECONDS.toMicros(signalTransport.getMaximumDeliveryGapNanos()) : 0),
                Map.entry("rejectedHandshakes", signalTransport != null ?
                    signalTransport.getRejectedHandshakeCount() : 0),
                Map.entry("revokedSessions", signalTransport != null ? signalTransport.getRevokedSessionCount() : 0),
                Map.entry("tunerFrames", tunerSpectrumFrameSource != null ?
                    tunerSpectrumFrameSource.getPublishedFrameCount() : 0),
                Map.entry("tunerPublicationErrors", tunerSpectrumFrameSource != null ?
                    tunerSpectrumFrameSource.getPublicationErrorCount() : 0)
            )
        ));
        return status;
    }

    private Map<String,Object> runtimeStatus()
    {
        Map<String,Object> runtime = new LinkedHashMap<>();
        Map<String,Object> calibration = new LinkedHashMap<>();

        try
        {
            CalibrationManager calibrationManager = CalibrationManager.getInstance(mUserPreferences);
            int pending = calibrationManager.getUncalibrated().size();
            calibration.put("available", true);
            calibration.put("pending", pending);
            calibration.put("total", calibrationManager.getCalibrationTypes().size());
            calibration.put("ready", pending == 0);
        }
        catch(RuntimeException e)
        {
            calibration.put("available", false);
            calibration.put("pending", -1);
            calibration.put("total", -1);
            calibration.put("ready", false);
        }

        runtime.put("calibration", calibration);
        Map<String,Object> voiceDecryption = new LinkedHashMap<>();

        try
        {
            boolean moduleLoaded = mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded();
            EncryptionKeyVaultService vaultService =
                mUserPreferences.getEncryptionKeyPreference().getVaultService();
            voiceDecryption.put("moduleLoaded", moduleLoaded);
            voiceDecryption.put("vaultPresent", vaultService.hasVault());
            voiceDecryption.put("vaultState", vaultService.getState().name());
            voiceDecryption.put("available", moduleLoaded && vaultService.isUnlocked());
        }
        catch(RuntimeException e)
        {
            voiceDecryption.put("moduleLoaded", false);
            voiceDecryption.put("vaultPresent", false);
            voiceDecryption.put("vaultState", "ERROR");
            voiceDecryption.put("available", false);
        }

        runtime.put("voiceDecryption", voiceDecryption);
        return runtime;
    }

    /**
     * Shared fixed-cardinality policy owner for the future authenticated administration surface.
     */
    public InMemoryFeatureAccessPolicy getFeatureAccessPolicy()
    {
        return mFeatureAccessPolicy;
    }

    private P25ActivityLogStatus statsLoggingStatus()
    {
        if(mActivityLogService != null)
        {
            return mActivityLogService.getStatus();
        }

        ApplicationPreference preference = mUserPreferences.getApplicationPreference();
        boolean summaryConfigured = preference.isStatsLoggingEnabled();
        return new P25ActivityLogStatus(summaryConfigured, preference.isStatsDetailedHistoryEnabled(), false, false,
            preference.getStatsLoggingRetentionDays(), summaryConfigured ? P25ActivityLogStatus.State.STOPPED :
            P25ActivityLogStatus.State.DISABLED, P25ActivityLogPath.getDatabasePath(mUserPreferences).toString(),
            0, 0, 0, null);
    }

    /**
     * Public status deliberately omits the portable database path and raw exception text.  Both are useful in the
     * local JavaFX diagnostics surface, but neither belongs in an anonymously available web response.
     */
    private Map<String,Object> publicStatsLoggingStatus()
    {
        P25ActivityLogStatus logging = statsLoggingStatus();
        return Map.ofEntries(
            Map.entry("summaryConfigured", logging.summaryConfigured()),
            Map.entry("detailedHistoryConfigured", logging.detailedHistoryConfigured()),
            Map.entry("summaryActive", logging.summaryActive()),
            Map.entry("detailedHistoryActive", logging.detailedHistoryActive()),
            Map.entry("retentionDays", logging.retentionDays()),
            Map.entry("state", logging.state()),
            Map.entry("lastSuccessfulWriteMs", logging.lastSuccessfulWriteMs()),
            Map.entry("recordsWritten", logging.recordsWritten()),
            Map.entry("recordsDropped", logging.recordsDropped()),
            Map.entry("hasError", logging.lastError() != null && !logging.lastError().isBlank())
        );
    }

    /**
     * Current state for desktop controls that open embedded-web pages.
     */
    public synchronized StatsWebNavigationState getNavigationState()
    {
        P25ActivityLogStatus loggingStatus = statsLoggingStatus();
        boolean running = isRunning();
        java.net.URI baseUri;

        if(running)
        {
            baseUri = mWebApplicationService.getBaseUri();
        }
        else
        {
            ApplicationPreference preference = mUserPreferences.getApplicationPreference();
            WebListenAddress configured = WebListenAddress.parse(preference.getStatsWebServerListenAddress());
            String host = configured.host().equals("0.0.0.0") || configured.host().equals("::") ?
                "127.0.0.1" : configured.host();

            try
            {
                baseUri = new java.net.URI(preference.isStatsWebServerHttpsEnabled() ? "https" : "http", null,
                    host, configured.port(), "/", null, null);
            }
            catch(java.net.URISyntaxException exception)
            {
                throw new IllegalStateException("Unable to construct the configured web URL", exception);
            }
        }

        return new StatsWebNavigationState(running, baseUri, loggingStatus.summaryActive(),
            loggingStatus.detailedHistoryActive());
    }

    @Override
    public void activityCommitted(java.util.List<Long> rowIds)
    {
        mLiveService.activityCommitted(rowIds);
    }

    /**
     * Receives completed calls independently from local Java playback.
     */
    public void receive(CompletedAudioCall call)
    {
        mWebCallService.receive(call);
    }

    @Override
    public void close()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
        stop();
        mLiveService.close();
        if(mLiveActivityService != null)
        {
            mLiveActivityService.close();
        }
        mWebCallService.close();
    }
}
