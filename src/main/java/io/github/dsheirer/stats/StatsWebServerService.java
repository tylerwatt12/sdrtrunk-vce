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
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.stats.activity.P25ActivityCommitListener;
import io.github.dsheirer.stats.activity.P25ActivityLogPath;
import io.github.dsheirer.stats.activity.P25ActivityLogService;
import io.github.dsheirer.stats.activity.P25ActivityLogStatus;
import io.github.dsheirer.spectrum.stream.SpectrumStreamService;
import io.github.dsheirer.spectrum.stream.SpectrumFrameSource;
import io.github.dsheirer.spectrum.stream.SyntheticSpectrumFrameSource;
import io.github.dsheirer.spectrum.stream.TunerSpectrumFrameSource;
import io.github.dsheirer.web.WebApplicationService;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.WebFeature;
import io.github.dsheirer.web.access.WebRequestSubjectResolver;
import io.github.dsheirer.web.signal.SignalOriginPolicy;
import io.github.dsheirer.web.signal.SignalSubjectResolver;
import io.github.dsheirer.web.signal.SignalWebSocketTransport;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private TunerSpectrumFrameSource mTunerSpectrumFrameSource;
    private String mSignalSourceType = "unavailable";
    private Path mAssetRoot;
    private int mPort;
    private boolean mLanEnabled;

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

        int requestedPort = preference.getStatsWebServerPort();
        boolean requestedLan = preference.isStatsWebServerLanEnabled();
        Path requestedRoot = StatsWebPath.getAssetsPath();

        if(isRunning() && requestedPort == mPort && requestedLan == mLanEnabled && requestedRoot.equals(mAssetRoot))
        {
            return;
        }

        stop();
        start(requestedRoot, requestedPort, requestedLan);
    }

    private void start(Path assetRoot, int port, boolean lanEnabled)
    {
        try
        {
            if(mChannelProcessingManager != null)
            {
                mChannelProcessingManager.setChannelActivityEnabled("stats-web", true);
            }

            mLiveService.start();
            mWebCallService.start();
            Files.createDirectories(assetRoot);
            InetAddress bindAddress = lanEnabled ? InetAddress.getByName("0.0.0.0") : InetAddress.getLoopbackAddress();
            mAssetRoot = assetRoot;
            mPort = port;
            mLanEnabled = lanEnabled;
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
                new SpectrumStreamService.Configuration(16, Duration.ofSeconds(3),
                    "sdrtrunk spectrum lifecycle"), frameSource);
            mSignalTransport = new SignalWebSocketTransport(SignalWebSocketTransport.Configuration.defaults(),
                mSpectrumStreamService, mFeatureAccessPolicy, SignalSubjectResolver.anonymous(),
                SignalOriginPolicy.sameOrigin());
            mHandler = new StatsWebHandler(assetRoot, lanEnabled, mDatabase, mLiveService, mWebCallService,
                this::status, mFeatureAccessPolicy, WebRequestSubjectResolver.anonymous());
            mWebApplicationService = new WebApplicationService(
                WebApplicationService.Configuration.application(bindAddress, port), mHandler,
                mSignalTransport::configure);
            mWebApplicationService.start();

            mLog.info("Stats routes mounted at http://{}:{}/ using assets [{}]",
                lanEnabled ? "0.0.0.0" : "127.0.0.1", mWebApplicationService.getLocalPort(), assetRoot);
        }
        catch(IOException | RuntimeException exception)
        {
            mLog.warn("Unable to start web application on port [{}]", port, exception);
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
        mWebCallService.stop();
        mAssetRoot = null;
        mPort = 0;
        mLanEnabled = false;

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
        WebApplicationService webApplicationService = mWebApplicationService;
        boolean running = webApplicationService != null && webApplicationService.isRunning();
        int localPort = running ? webApplicationService.getLocalPort() : mPort;
        Map<String,Object> status = new LinkedHashMap<>();
        status.put("server", Map.of(
            "enabled", running,
            "port", localPort,
            "lanEnabled", mLanEnabled,
            "assetRoot", assetRoot != null ? assetRoot.toString() : "",
            "assetsAvailable", assetRoot != null && Files.isRegularFile(assetRoot.resolve("index.html")),
            "liveChannels", Map.of(
                "systems", "/live/systems",
                "webCalls", "/live/web-calls",
                "activity", "/live/activity"
            )
        ));
        status.put("database", mDatabase.status());
        status.put("statsLogging", statsLoggingStatus());
        status.put("webPlayer", mWebCallService.status());
        Map<String,String> featureModes = new LinkedHashMap<>();

        for(WebFeature feature: WebFeature.values())
        {
            featureModes.put(feature.getId(), mFeatureAccessPolicy.getMode(feature).name());
        }

        status.put("featureAccess", Map.of(
            "revision", mFeatureAccessPolicy.getRevision(),
            "modes", featureModes
        ));
        SpectrumStreamService spectrumStreamService = mSpectrumStreamService;
        SignalWebSocketTransport signalTransport = mSignalTransport;
        TunerSpectrumFrameSource tunerSpectrumFrameSource = mTunerSpectrumFrameSource;
        status.put("signal", Map.of(
            "source", mSignalSourceType,
            "target", tunerSpectrumFrameSource != null ? tunerSpectrumFrameSource.getTargetLabel() :
                ("synthetic".equals(mSignalSourceType) ? "Synthetic signal source" : "Available tuner"),
            "subscribers", spectrumStreamService != null ? spectrumStreamService.getSubscriberCount() : 0,
            "sourceRunning", spectrumStreamService != null && spectrumStreamService.isSourceRunning(),
            "sessions", signalTransport != null ? signalTransport.getActiveSessionCount() : 0,
            "webSocket", SignalWebSocketTransport.PATH
        ));
        return status;
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
     * Current state for desktop controls that open embedded-web pages.
     */
    public synchronized StatsWebNavigationState getNavigationState()
    {
        P25ActivityLogStatus loggingStatus = statsLoggingStatus();
        int port = isRunning() ? mWebApplicationService.getLocalPort() :
            mUserPreferences.getApplicationPreference().getStatsWebServerPort();
        return new StatsWebNavigationState(isRunning(), port, loggingStatus.summaryActive(),
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
        mWebCallService.close();
    }
}
