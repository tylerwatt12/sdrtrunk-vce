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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.Subscribe;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.application.WebCertificateMode;
import io.github.dsheirer.stats.activity.P25ActivityCommitListener;
import io.github.dsheirer.stats.activity.P25ActivityLogPath;
import io.github.dsheirer.stats.activity.P25ActivityLogService;
import io.github.dsheirer.stats.activity.P25ActivityLogStatus;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.TlsMaterialException;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.AliasAdminHttpController;
import io.github.dsheirer.web.http.WebAccessHttpController;
import io.github.dsheirer.web.network.WebCertificateIdentity;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.dsheirer.eventbus.MyEventBus;

/**
 * Embedded stats web server. Static assets are served only from an external filesystem folder.
 */
public class StatsWebServerService implements AutoCloseable, P25ActivityCommitListener
{
    private static final Logger mLog = LoggerFactory.getLogger(StatsWebServerService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static final Duration AUTOMATIC_CERTIFICATE_RENEWAL_WINDOW = Duration.ofDays(30);
    private static final long AUTOMATIC_CERTIFICATE_INITIAL_CHECK_MINUTES = 1;
    private static final long AUTOMATIC_CERTIFICATE_MAINTENANCE_MINUTES = TimeUnit.HOURS.toMinutes(12);

    private final UserPreferences mUserPreferences;
    private final StatsWebDatabase mDatabase;
    private final StatsLiveService mLiveService;
    private final StatsWebCallService mWebCallService = new StatsWebCallService();
    private final ChannelProcessingManager mChannelProcessingManager;
    private final P25ActivityLogService mActivityLogService;
    private final AliasAdministrationService mAliasAdministrationService;
    private final Semaphore mCsvExportPermit = new Semaphore(1);
    private final Path mWebAccessDatabasePath;
    private final WebTlsMaterialService mTlsMaterialService;
    private final ScheduledExecutorService mTlsMaintenanceExecutor;
    private volatile ListenerRuntime mListener;
    private volatile WebServerRuntimeState mRuntimeState = stoppedState("Web server is disabled.");
    private WebAccessService mWebAccessService;
    private WebAuthenticationService mWebAuthenticationService;
    private volatile WebAccessHttpController mWebAccessHttpController;
    private boolean mRuntimeServicesStarted;
    private boolean mClosed;

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
                                 P25ActivityLogService activityLogService,
                                 AliasAdministrationService aliasAdministrationService)
    {
        mUserPreferences = userPreferences;
        mChannelProcessingManager = channelProcessingManager;
        mActivityLogService = activityLogService;
        mAliasAdministrationService = aliasAdministrationService;
        mDatabase = new StatsWebDatabase(userPreferences);
        mLiveService = new StatsLiveService(mDatabase, channelProcessingManager);
        mWebAccessDatabasePath = SdrTrunkDatabasePath.getDatabasePath(mUserPreferences);
        mTlsMaterialService = new WebTlsMaterialService(
            mUserPreferences.getDirectoryPreference().getDirectoryApplicationRoot());
        mTlsMaintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stats web TLS maintenance");
            thread.setDaemon(true);
            return thread;
        });
        MyEventBus.getGlobalEventBus().register(this);
        updateServerState();
        mTlsMaintenanceExecutor.scheduleWithFixedDelay(this::maintainAutomaticCertificate,
            AUTOMATIC_CERTIFICATE_INITIAL_CHECK_MINUTES, AUTOMATIC_CERTIFICATE_MAINTENANCE_MINUTES,
            TimeUnit.MINUTES);
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
        reconcileListener(false);
    }

    /**
     * Reloads the requested listener configuration and, for HTTPS, the currently installed certificate material.
     * Existing browser authentication sessions are owned outside the listener and survive a successful recycle.
     *
     * @return actual listener state after the reload attempt
     */
    public synchronized WebServerRuntimeState reloadActiveListener()
    {
        return reconcileListener(true);
    }

    /**
     * Returns the listener state that is actually active.  This never substitutes requested preferences after a
     * failed start or reload.
     */
    public WebServerRuntimeState getRuntimeState()
    {
        return mRuntimeState;
    }

    /**
     * Shared TLS-material owner used by the local JavaFX certificate controls. Keeping certificate reads and atomic
     * installs on this one synchronized service prevents maintenance and operator imports from sharing staging files
     * concurrently.
     */
    public WebTlsMaterialService getTlsMaterialService()
    {
        return mTlsMaterialService;
    }

    /**
     * Thread-safe JavaFX query for the fixed primary web administrator.
     */
    public synchronized boolean isPrimaryAdminConfigured() throws IOException, SQLException
    {
        ensureOpen();
        ensureAuthenticationServices();
        return mWebAccessService.isPrimaryAdminConfigured();
    }

    /**
     * Thread-safe JavaFX bootstrap/recovery operation for the fixed primary web administrator.  The persisted
     * credential version changes before this method returns, so every old session fails its next credential-version
     * check; eagerly removing those sessions also releases their bounded session capacity immediately.
     */
    public synchronized WebAccessAccount provisionOrResetPrimaryAdmin(char[] password) throws IOException, SQLException
    {
        ensureOpen();
        ensureAuthenticationServices();
        WebAccessAccount account = mWebAccessService.provisionOrResetPrimaryAdmin(password);
        mWebAuthenticationService.invalidateAccountSessions(WebAccessService.PRIMARY_ADMIN_USERNAME);
        return account;
    }

    private WebServerRuntimeState reconcileListener(boolean forceReload)
    {
        if(mClosed)
        {
            return mRuntimeState;
        }

        ApplicationPreference preference = mUserPreferences.getApplicationPreference();

        if(!preference.isStatsWebServerEnabled())
        {
            if(mWebAuthenticationService != null)
            {
                mWebAuthenticationService.invalidateAllSessions();
            }

            stopActiveListener();
            mRuntimeState = stoppedState("Web server is disabled.");
            return mRuntimeState;
        }

        boolean anyIpEnabled = preference.isStatsWebServerAnyIpEnabled();
        RequestedConfiguration requested = new RequestedConfiguration(StatsWebPath.getAssetsPath(),
            preference.getStatsWebServerPort(), anyIpEnabled, anyIpEnabled);
        ListenerRuntime previous = mListener;

        if(!forceReload && previous != null && previous.configuration().requested().equals(requested))
        {
            return mRuntimeState;
        }

        PreparedConfiguration prepared;

        try
        {
            ensureAuthenticationServices();
            prepared = prepareConfiguration(requested);
        }
        catch(IOException | SQLException | GeneralSecurityException | TlsMaterialException | RuntimeException exception)
        {
            return recordReloadFailure(previous, requested, exception, false);
        }

        boolean releasePreviousFirst = previous != null && bindingsConflict(previous, requested);

        if(releasePreviousFirst)
        {
            mListener = null;
            stopListener(previous);
        }

        try
        {
            startRuntimeServices();
            ListenerRuntime replacement = startListener(prepared);
            mListener = replacement;
            mRuntimeState = runningState(replacement, "Web server is running.");

            if(previous != null && !releasePreviousFirst)
            {
                stopListener(previous);
            }

            mLog.info("Stats web server started at {}://{}:{}/ using assets [{}]",
                prepared.requested().httpsEnabled() ? "https" : "http",
                prepared.requested().anyIpEnabled() ? "0.0.0.0" : "127.0.0.1",
                replacement.server().getAddress().getPort(), prepared.requested().assetRoot());
            return mRuntimeState;
        }
        catch(IOException | RuntimeException exception)
        {
            ListenerRuntime restored = null;

            if(releasePreviousFirst)
            {
                try
                {
                    restored = startListener(previous.configuration());
                    mListener = restored;
                }
                catch(IOException | RuntimeException restoreException)
                {
                    exception.addSuppressed(restoreException);
                    mListener = null;
                }
            }

            if(mListener == null)
            {
                stopRuntimeServices();
            }

            return recordReloadFailure(restored != null ? restored :
                releasePreviousFirst ? null : previous, requested, exception, releasePreviousFirst);
        }
    }

    private PreparedConfiguration prepareConfiguration(RequestedConfiguration requested)
        throws IOException, GeneralSecurityException, TlsMaterialException
    {
        Files.createDirectories(requested.assetRoot());

        if(!requested.httpsEnabled())
        {
            return new PreparedConfiguration(requested, null, null);
        }

        TlsMaterial material;
        ApplicationPreference preference = mUserPreferences.getApplicationPreference();

        if(!preference.isStatsWebServerCertificateModeConfigured())
        {
            boolean existingMaterial = Files.exists(mTlsMaterialService.certificatePath()) ||
                Files.exists(mTlsMaterialService.privateKeyPath());
            preference.initializeStatsWebServerCertificateMode(existingMaterial ?
                WebCertificateMode.CUSTOM : WebCertificateMode.AUTOMATIC);
        }

        if(preference.getStatsWebServerCertificateMode() == WebCertificateMode.AUTOMATIC)
        {
            WebCertificateIdentity identity = WebCertificateIdentity.discover();

            try
            {
                material = mTlsMaterialService.validateInstalledMaterial();
                boolean coversCurrentIdentity = true;

                for(String name: identity.requiredSubjectAlternativeNames())
                {
                    if(!material.coversHost(name))
                    {
                        coversCurrentIdentity = false;
                        break;
                    }
                }

                if(!coversCurrentIdentity || automaticCertificateRequiresRenewal(material, Instant.now()))
                {
                    material = mTlsMaterialService.generateSelfSigned(identity.commonName(),
                        identity.subjectAlternativeNames());
                }
            }
            catch(TlsMaterialException exception)
            {
                material = mTlsMaterialService.generateSelfSigned(identity.commonName(),
                    identity.subjectAlternativeNames());
            }
        }
        else
        {
            material = mTlsMaterialService.validateInstalledMaterial();
        }

        return new PreparedConfiguration(requested, material.createServerSslContext(),
            material.leafSha256Fingerprint());
    }

    /**
     * Periodically replaces an automatic certificate before it expires or when the preferred network identity has
     * changed.  A listener reload failure leaves the previous in-memory TLS listener active; a fingerprint mismatch
     * causes the next maintenance pass to retry activation of the installed material.
     */
    private synchronized void maintainAutomaticCertificate()
    {
        if(mClosed || mListener == null || !mListener.configuration().requested().httpsEnabled() ||
            mUserPreferences.getApplicationPreference().getStatsWebServerCertificateMode() !=
                WebCertificateMode.AUTOMATIC)
        {
            return;
        }

        try
        {
            TlsMaterial material = mTlsMaterialService.validateInstalledMaterial();
            WebCertificateIdentity identity = WebCertificateIdentity.discover();
            boolean identityChanged = identity.requiredSubjectAlternativeNames().stream()
                .anyMatch(name -> !material.coversHost(name));
            boolean installedMaterialIsInactive =
                !material.leafSha256Fingerprint().equals(mRuntimeState.certificateFingerprint());

            if(identityChanged || installedMaterialIsInactive ||
                automaticCertificateRequiresRenewal(material, Instant.now()))
            {
                reconcileListener(true);
            }
        }
        catch(IOException | GeneralSecurityException | TlsMaterialException | RuntimeException exception)
        {
            // Reconciliation regenerates invalid/missing automatic material and retains the old listener on failure.
            reconcileListener(true);
        }
    }

    static boolean automaticCertificateRequiresRenewal(TlsMaterial material, Instant now)
    {
        return !material.notAfter().isAfter(now.plus(AUTOMATIC_CERTIFICATE_RENEWAL_WINDOW));
    }

    /**
     * Installs administrator-supplied material, transfers ownership to custom mode, and activates it as one
     * synchronized operation so automatic maintenance cannot replace it between those steps.
     */
    public synchronized TlsActivation installAndActivateCustomCertificate(TlsMaterial material)
        throws TlsMaterialException, GeneralSecurityException
    {
        ensureOpen();
        TlsMaterial installed = mTlsMaterialService.install(material);
        mUserPreferences.getApplicationPreference().setStatsWebServerCertificateMode(WebCertificateMode.CUSTOM);
        return new TlsActivation(installed, activateInstalledCertificateIfNeeded(installed));
    }

    /**
     * Creates and activates a new app-managed certificate without allowing maintenance to interleave.
     */
    public synchronized TlsActivation generateAndActivateAutomaticCertificate()
        throws IOException, TlsMaterialException, GeneralSecurityException
    {
        ensureOpen();
        WebCertificateIdentity identity = WebCertificateIdentity.discover();
        TlsMaterial installed = mTlsMaterialService.generateSelfSigned(identity.commonName(),
            identity.subjectAlternativeNames());
        mUserPreferences.getApplicationPreference().setStatsWebServerCertificateMode(WebCertificateMode.AUTOMATIC);
        return new TlsActivation(installed, activateInstalledCertificateIfNeeded(installed));
    }

    private WebServerRuntimeState activateInstalledCertificateIfNeeded(TlsMaterial installed)
        throws GeneralSecurityException
    {
        ApplicationPreference preference = mUserPreferences.getApplicationPreference();

        if(preference.isStatsWebServerEnabled() && preference.isStatsWebServerAnyIpEnabled() &&
            (!mRuntimeState.running() || !mRuntimeState.https() ||
                !installed.leafSha256Fingerprint().equals(mRuntimeState.certificateFingerprint())))
        {
            return reconcileListener(true);
        }

        return mRuntimeState;
    }

    private ListenerRuntime startListener(PreparedConfiguration configuration) throws IOException
    {
        RequestedConfiguration requested = configuration.requested();
        InetSocketAddress bindAddress = createBindAddress(requested.port(), requested.anyIpEnabled());
        HttpServer server;

        if(requested.httpsEnabled())
        {
            HttpsServer httpsServer = HttpsServer.create(bindAddress, 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(configuration.sslContext()));
            server = httpsServer;
        }
        else
        {
            server = HttpServer.create(bindAddress, 0);
        }

        ExecutorService executor = Executors.newCachedThreadPool(new NamingThreadFactory("stats web server"));

        try
        {
            server.setExecutor(executor);
            registerContexts(server, requested.assetRoot());
            server.start();
            return new ListenerRuntime(server, executor, configuration);
        }
        catch(RuntimeException exception)
        {
            server.stop(0);
            executor.shutdownNow();
            throw exception;
        }
    }

    private void registerContexts(HttpServer server, Path assetRoot)
    {
        mWebAccessHttpController.register(server);

        if(mAliasAdministrationService != null)
        {
            AliasAdminHttpController aliasController = new AliasAdminHttpController(mAliasAdministrationService);
            HttpHandler protectedAliases = mWebAccessHttpController.protectApi(
                WebCapability.ADMIN_ALIASES, aliasController::handle);
            server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, protectedAliases);
            server.createContext(AliasAdminHttpController.ALIASES_PATH, protectedAliases);
        }

        createProtectedContext(server, "/api/status", WebCapability.DASHBOARD_VIEW,
            exchange -> handleJson(exchange, "/api/status", this::status));
        createProtectedContext(server, "/api/dashboard", WebCapability.DASHBOARD_VIEW,
            exchange -> handleJson(exchange, "/api/dashboard", mDatabase::dashboard));
        createProtectedContext(server, "/api/alias-lists", WebCapability.ALIASES_VIEW,
            exchange -> handleJson(exchange, "/api/alias-lists", mDatabase::aliasLists));
        createProtectedContext(server, "/api/aliases", WebCapability.ALIASES_VIEW,
            exchange -> handleJson(exchange, "/api/aliases",
            () -> mDatabase.aliases(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/alias", WebCapability.ALIASES_VIEW,
            exchange -> handleJson(exchange, "/api/alias",
            () -> mDatabase.alias(StatsRequest.from(exchange.getRequestURI()))));
        server.createContext("/api/quality", exchange -> mWebAccessHttpController.protect(
            qualityCapability(exchange.getRequestURI()),
            protectedExchange -> handleJson(protectedExchange, "/api/quality",
                () -> mDatabase.qualityHistory(StatsRequest.from(protectedExchange.getRequestURI()))))
            .handle(exchange));
        createProtectedContext(server, "/api/system-directory", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/system-directory",
            () -> mDatabase.systemDirectory(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/system", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/system",
            () -> mDatabase.system(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/system/sites", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/system/sites",
            () -> mDatabase.systemSites(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/system/talkgroups", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/system/talkgroups",
            () -> mDatabase.systemTalkgroups(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/system/radios", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/system/radios",
            () -> mDatabase.systemRadios(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/system/talker-aliases", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/system/talker-aliases",
            () -> mDatabase.systemTalkerAliases(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/talkgroup", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/talkgroup",
            () -> mDatabase.talkgroup(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/talkgroup/activity", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/talkgroup/activity",
            () -> mDatabase.talkgroupActivity(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/radio", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/radio",
            () -> mDatabase.radio(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/affiliations", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/affiliations",
            () -> mDatabase.currentAffiliations(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/relationships", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/relationships",
            () -> mDatabase.radioTalkgroupRelationships(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/site", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/site",
            () -> mDatabase.site(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/site/channels", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/site/channels",
            () -> mDatabase.siteChannels(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/site/talkgroups", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/site/talkgroups",
            () -> mDatabase.siteTalkgroups(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/site/quality", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/site/quality",
            () -> mDatabase.siteQuality(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/site/bands", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/site/bands",
            () -> mDatabase.siteBands(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/site/neighbors", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/site/neighbors",
            () -> mDatabase.siteNeighbors(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/site/patches", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/site/patches",
            () -> mDatabase.sitePatches(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/activity", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/activity",
            () -> mDatabase.activity(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/activity/recent", WebCapability.SYSTEMS_VIEW,
            exchange -> handleJson(exchange, "/api/activity/recent",
            () -> mDatabase.activity(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/conventional", WebCapability.CONVENTIONAL_VIEW,
            exchange -> handleJson(exchange, "/api/conventional",
            () -> mDatabase.conventional(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/conventional/detail", WebCapability.CONVENTIONAL_VIEW,
            exchange -> handleJson(exchange, "/api/conventional/detail",
            () -> mDatabase.conventionalDetail(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/conventional/talkgroups", WebCapability.CONVENTIONAL_VIEW,
            exchange -> handleJson(exchange, "/api/conventional/talkgroups",
            () -> mDatabase.conventionalTalkgroups(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/conventional/radios", WebCapability.CONVENTIONAL_VIEW,
            exchange -> handleJson(exchange, "/api/conventional/radios",
            () -> mDatabase.conventionalRadios(StatsRequest.from(exchange.getRequestURI()))));
        createProtectedContext(server, "/api/export.csv", WebCapability.CSV_EXPORT, this::handleCsvExport);
        createProtectedContext(server, "/live/systems", WebCapability.LIVE_VIEW, this::handleSystemsSse);
        createProtectedContext(server, "/live/sites", WebCapability.LIVE_VIEW, this::handleSitesSse);
        createProtectedContext(server, "/live/web-calls", WebCapability.WEB_AUDIO_LISTEN, this::handleWebCallsSse);
        createProtectedContext(server, "/live/activity", WebCapability.SYSTEMS_VIEW, this::handleActivitySse);
        createProtectedContext(server, "/api/web-player/calls/", WebCapability.WEB_AUDIO_LISTEN,
            this::handleWebCallAudio);
        server.createContext("/", exchange -> handleStatic(exchange, assetRoot));
    }

    private void ensureAuthenticationServices() throws IOException, SQLException
    {
        if(mWebAccessHttpController == null)
        {
            WebAccessService accessService = new WebAccessService(mWebAccessDatabasePath);
            WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
            WebAccessHttpController controller =
                new WebAccessHttpController(accessService, authenticationService);
            mWebAccessService = accessService;
            mWebAuthenticationService = authenticationService;
            mWebAccessHttpController = controller;
        }
    }

    private void ensureOpen()
    {
        if(mClosed)
        {
            throw new IllegalStateException("Stats web server service is closed");
        }
    }

    private void startRuntimeServices()
    {
        if(!mRuntimeServicesStarted)
        {
            mRuntimeServicesStarted = true;

            try
            {
                if(mChannelProcessingManager != null)
                {
                    mChannelProcessingManager.setChannelActivityEnabled("stats-web", true);
                }

                mLiveService.start();
                mWebCallService.start();
            }
            catch(RuntimeException exception)
            {
                stopRuntimeServices();
                throw exception;
            }
        }
    }

    private void stopRuntimeServices()
    {
        if(!mRuntimeServicesStarted)
        {
            return;
        }

        mLiveService.stop();
        mWebCallService.stop();
        mRuntimeServicesStarted = false;

        if(mChannelProcessingManager != null)
        {
            mChannelProcessingManager.setChannelActivityEnabled("stats-web", false);
        }
    }

    private void stopActiveListener()
    {
        ListenerRuntime listener = mListener;
        mListener = null;

        if(listener != null)
        {
            stopListener(listener);
            mLog.info("Stats web server stopped");
        }

        stopRuntimeServices();
    }

    private static void stopListener(ListenerRuntime listener)
    {
        listener.server().stop(0);
        listener.executor().shutdownNow();
    }

    private static boolean bindingsConflict(ListenerRuntime previous, RequestedConfiguration requested)
    {
        int requestedPort = requested.port();
        return requestedPort != 0 && previous.configuration().requested().port() == requestedPort;
    }

    private WebServerRuntimeState recordReloadFailure(ListenerRuntime active, RequestedConfiguration requested,
                                                       Exception exception, boolean restorationAttempted)
    {
        mLog.warn("Unable to start or reload stats web server on requested port [{}]", requested.port(), exception);

        if(active != null)
        {
            mRuntimeState = runningState(active,
                "Web server reload failed; the previous listener remains active.");
        }
        else if(restorationAttempted)
        {
            mRuntimeState = stoppedState(
                "Web server reload failed and the previous listener could not be restored; check the application log.");
        }
        else
        {
            mRuntimeState = stoppedState("Web server failed to start; check the application log.");
        }

        return mRuntimeState;
    }

    private static WebServerRuntimeState runningState(ListenerRuntime listener, String statusMessage)
    {
        PreparedConfiguration configuration = listener.configuration();
        return new WebServerRuntimeState(true, listener.server().getAddress().getPort(),
            configuration.requested().anyIpEnabled(), configuration.requested().httpsEnabled(),
            configuration.certificateFingerprint(), statusMessage);
    }

    private static WebServerRuntimeState stoppedState(String statusMessage)
    {
        return new WebServerRuntimeState(false, 0, false, false, null, statusMessage);
    }

    private void createProtectedContext(HttpServer server, String path, WebCapability capability, HttpHandler handler)
    {
        server.createContext(path, mWebAccessHttpController.protect(capability, handler));
    }

    /**
     * Site-scoped quality data belongs to the Systems capability; global quality belongs to Dashboard.  Parameter
     * names must be decoded before authorization so percent-encoding cannot select a less restrictive policy.
     */
    static WebCapability qualityCapability(URI uri)
    {
        try
        {
            return StatsRequest.from(uri).text("guid") != null ? WebCapability.SYSTEMS_VIEW :
                WebCapability.DASHBOARD_VIEW;
        }
        catch(RuntimeException exception)
        {
            // Malformed or ambiguous requests take the more restrictive route and are rejected by the handler.
            return WebCapability.SYSTEMS_VIEW;
        }
    }

    private synchronized Map<String,Object> status()
    {
        Map<String,Object> status = new LinkedHashMap<>();
        WebServerRuntimeState runtimeState = mRuntimeState;
        ListenerRuntime listener = mListener;
        Map<String,Object> server = new LinkedHashMap<>();
        server.put("enabled", runtimeState.running());
        server.put("port", runtimeState.port());
        server.put("https", runtimeState.https());
        server.put("accessMode", runtimeState.anyIpEnabled() ? "any_ip" : "local_only");
        server.put("certificateFingerprint", runtimeState.certificateFingerprint());
        server.put("statusMessage", runtimeState.statusMessage());
        server.put("assetsAvailable", listener != null &&
            Files.isRegularFile(listener.configuration().requested().assetRoot().resolve("index.html")));
        server.put("liveChannels", Map.of(
            "systems", "/live/systems",
            "sites", "/live/sites",
            "webCalls", "/live/web-calls",
            "activity", "/live/activity"));
        status.put("server", server);
        status.put("database", mDatabase.status());
        status.put("statsLogging", statsLoggingStatusResponse());
        status.put("webPlayer", mWebCallService.status());
        NowPlayingPreference nowPlaying = mUserPreferences.getNowPlayingPreference();
        status.put("decodeDisplay", Map.of(
            "showControl", nowPlaying.isShowControlDecodeQuality(),
            "showVoice", nowPlaying.isShowVoiceDecodeQuality(),
            "clearVoiceOnCallEnd", nowPlaying.isClearVoiceDecodeQualityOnCallEnd(),
            "mode", nowPlaying.getDecodeQualityDisplayMode().name().toLowerCase()
        ));
        return status;
    }

    private Map<String,Object> statsLoggingStatusResponse()
    {
        P25ActivityLogStatus current = statsLoggingStatus();
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("summaryConfigured", current.summaryConfigured());
        response.put("detailedHistoryConfigured", current.detailedHistoryConfigured());
        response.put("summaryActive", current.summaryActive());
        response.put("detailedHistoryActive", current.detailedHistoryActive());
        response.put("retentionDays", current.retentionDays());
        response.put("state", current.state());
        response.put("lastSuccessfulWriteMs", current.lastSuccessfulWriteMs());
        response.put("recordsWritten", current.recordsWritten());
        response.put("recordsDropped", current.recordsDropped());
        response.put("lastError", current.lastError() == null || current.lastError().isBlank() ? "" :
            "Statistics logging failed; check the application log.");
        return response;
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
        WebServerRuntimeState runtimeState = mRuntimeState;
        return new StatsWebNavigationState(runtimeState.running(), runtimeState.port(), runtimeState.https(),
            loggingStatus.summaryActive(),
            loggingStatus.detailedHistoryActive());
    }

    /**
     * Resolves a configured receiver GUID to the durable, protocol-neutral system scope used by web URLs.
     */
    public String getScopeToken(String guid)
    {
        return mDatabase.scopeTokenForGuid(guid);
    }

    private void handleJson(HttpExchange exchange, String expectedPath, JsonSupplier supplier) throws IOException
    {
        if(!hasExactPath(exchange.getRequestURI(), expectedPath))
        {
            sendJson(exchange, 404, Map.of("error", "Not found", "status", 404));
            return;
        }

        if(!requireMethod(exchange, "GET"))
        {
            return;
        }

        try
        {
            sendJson(exchange, 200, supplier.get());
        }
        catch(StatsApiException e)
        {
            sendJson(exchange, e.status(), Map.of("error", e.getMessage(), "status", e.status()));
        }
        catch(RuntimeException e)
        {
            mLog.warn("Stats web request failed [{}]", exchange.getRequestURI().getPath(), e);
            sendJson(exchange, 500, Map.of("error", "Stats request failed", "status", 500));
        }
    }

    private void handleCsvExport(HttpExchange exchange) throws IOException
    {
        if(!hasExactPath(exchange.getRequestURI(), "/api/export.csv"))
        {
            sendJson(exchange, 404, Map.of("error", "Not found", "status", 404));
            return;
        }

        if(!requireMethod(exchange, "GET"))
        {
            return;
        }

        if(!mCsvExportPermit.tryAcquire())
        {
            sendJson(exchange, 429, Map.of("error", "Another CSV export is already running", "status", 429));
            return;
        }

        try
        {
            StatsCsvExport export = mDatabase.csvExport(StatsRequest.from(exchange.getRequestURI()));
            Headers headers = exchange.getResponseHeaders();
            applyCsvHeaders(headers, export.fileName());
            exchange.sendResponseHeaders(200, export.content().length);

            try(OutputStream outputStream = exchange.getResponseBody())
            {
                outputStream.write(export.content());
            }
        }
        catch(StatsApiException e)
        {
            sendJson(exchange, e.status(), Map.of("error", e.getMessage(), "status", e.status()));
        }
        catch(RuntimeException e)
        {
            mLog.warn("Stats CSV export failed", e);
            sendJson(exchange, 500, Map.of("error", "CSV export failed", "status", 500));
        }
        finally
        {
            mCsvExportPermit.release();
        }
    }

    static void applyCsvHeaders(Headers headers, String fileName)
    {
        headers.set("Content-Type", "text/csv; charset=utf-8");
        headers.set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
    }

    static boolean hasExactPath(URI uri, String expectedPath)
    {
        return uri != null && expectedPath != null && expectedPath.equals(uri.getPath());
    }

    private static boolean requireExactTextPath(HttpExchange exchange, String expectedPath) throws IOException
    {
        if(hasExactPath(exchange.getRequestURI(), expectedPath))
        {
            return true;
        }

        sendText(exchange, 404, "Not found");
        return false;
    }

    private static void sendJson(HttpExchange exchange, int status, Map<String,Object> value) throws IOException
    {
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(value);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);

        try(OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(body);
        }
    }

    private void handleSystemsSse(HttpExchange exchange) throws IOException
    {
        if(!requireExactTextPath(exchange, "/live/systems") || !requireMethod(exchange, "GET"))
        {
            return;
        }

        StatsLiveEventHub.Subscription subscription = mLiveService.subscribeSystems();

        if(subscription == null)
        {
            sendText(exchange, 429, "Too many live Stats Server clients");
            return;
        }

        streamSse(exchange, subscription, "snapshot", mLiveService.snapshot(), event -> true);
    }

    private void handleSitesSse(HttpExchange exchange) throws IOException
    {
        if(!requireExactTextPath(exchange, "/live/sites") || !requireMethod(exchange, "GET"))
        {
            return;
        }

        StatsLiveEventHub.Subscription subscription = mLiveService.subscribeSites();

        if(subscription == null)
        {
            sendText(exchange, 429, "Too many live Stats Server clients");
            return;
        }

        streamSse(exchange, subscription, "snapshot", mLiveService.siteSnapshot(),
            event -> "site_metadata".equals(event.name()) || "site_removed".equals(event.name()));
    }

    private void handleWebCallsSse(HttpExchange exchange) throws IOException
    {
        if(!requireExactTextPath(exchange, "/live/web-calls") || !requireMethod(exchange, "GET"))
        {
            return;
        }

        StatsLiveEventHub.Subscription subscription = mWebCallService.subscribe();

        if(subscription == null)
        {
            sendText(exchange, 429, "Too many live Stats Server clients");
            return;
        }

        streamSse(exchange, subscription, "ready", Map.of("state", "live"),
            event -> "call".equals(event.name()));
    }

    private void handleActivitySse(HttpExchange exchange) throws IOException
    {
        if(!requireExactTextPath(exchange, "/live/activity") || !requireMethod(exchange, "GET"))
        {
            return;
        }

        StatsRequest request = StatsRequest.from(exchange.getRequestURI());

        try
        {
            validateActivityRequest(request);
        }
        catch(StatsApiException e)
        {
            sendText(exchange, e.status(), e.getMessage());
            return;
        }

        StatsLiveEventHub.Subscription subscription = mLiveService.subscribeActivity();

        if(subscription == null)
        {
            sendText(exchange, 429, "Too many live Stats Server clients");
            return;
        }

        streamSse(exchange, subscription, "ready", Map.of("state", "live"),
            event -> event.data() instanceof Map<?,?> row && matchesActivity(row, request));
    }

    private void streamSse(HttpExchange exchange, StatsLiveEventHub.Subscription subscription, String initialEvent,
                           Object initialData, java.util.function.Predicate<StatsLiveEventHub.LiveEvent> filter)
        throws IOException
    {
        WebAccessHttpController accessController = mWebAccessHttpController;

        if(accessController == null || !accessController.isRequestStillAuthorized(exchange))
        {
            subscription.close();
            sendText(exchange, 403, "Access changed before the live stream started");
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        HttpServer server = exchange.getHttpContext().getServer();

        try(subscription; OutputStream outputStream = exchange.getResponseBody())
        {
            if(!accessController.isRequestStillAuthorized(exchange))
            {
                return;
            }

            writeSseEvent(outputStream, initialEvent, initialData);

            while(mListener != null && mListener.server() == server && !subscription.isClosed() &&
                accessController.isRequestStillAuthorized(exchange))
            {
                StatsLiveEventHub.LiveEvent event = subscription.poll(15, TimeUnit.SECONDS);

                // Revocation can happen while poll is blocked.  Recheck immediately before every heartbeat or
                // event write so a demoted/deleted account cannot receive one final post-revocation event.
                if(!accessController.isRequestStillAuthorized(exchange))
                {
                    break;
                }

                if(event == null)
                {
                    outputStream.write((": heartbeat " + System.currentTimeMillis() + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
                else if(filter.test(event))
                {
                    writeSseEvent(outputStream, event.name(), event.data());
                }
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch(IOException e)
        {
            // Client disconnected.
        }
    }

    private void handleWebCallAudio(HttpExchange exchange) throws IOException
    {
        if(!requireMethod(exchange, "GET"))
        {
            return;
        }

        String prefix = "/api/web-player/calls/";
        String path = exchange.getRequestURI().getPath();

        if(path == null || !path.startsWith(prefix) || !path.endsWith("/audio"))
        {
            sendText(exchange, 404, "Call audio not found");
            return;
        }

        String id = path.substring(prefix.length(), path.length() - "/audio".length());
        StatsWebCallService.CachedCall call = mWebCallService.get(id);

        if(call == null)
        {
            sendText(exchange, 404, "Call audio is no longer available");
            return;
        }

        byte[] wave = call.wave();
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "audio/wav");
        headers.set("Cache-Control", "no-store, no-transform");
        headers.set("Accept-Ranges", "none");
        exchange.sendResponseHeaders(200, wave.length);

        try(OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(wave);
        }
    }

    private static void writeSseEvent(OutputStream outputStream, String event, Object data)
        throws IOException
    {
        outputStream.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        outputStream.write(("data: " + OBJECT_MAPPER.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    static boolean matchesActivity(Map<?,?> row, StatsRequest request)
    {
        Integer talkgroup = request.optionalIdentifier("talkgroup_id");
        Integer radio = request.optionalIdentifier("radio_id");
        String scope = request.text("scope");
        String guid = request.text("guid");
        String context = request.text("context");

        if(scope != null && !scope.equals(row.get("scope_token")) ||
            guid != null && !guid.equals(row.get("guid")) ||
            context != null && !context.equals(row.get("context_key")))
        {
            return false;
        }

        if(talkgroup != null)
        {
            boolean patch = "patch".equalsIgnoreCase(request.text("kind"));
            boolean directTarget = numberEquals(row.get("target_id"), talkgroup) &&
                numberEquals(row.get("target_kind_code"), patch ? 3 : 1);

            if(!directTarget && (patch || !numberListContains(row.get("member_talkgroup_ids"), talkgroup)))
            {
                return false;
            }
        }

        return radio == null || numberEquals(row.get("source_radio_id"), radio) ||
            numberEquals(row.get("target_id"), radio) && numberEquals(row.get("target_kind_code"), 2);
    }

    private static boolean numberEquals(Object value, int expected)
    {
        return value instanceof Number number && number.intValue() == expected;
    }

    private static boolean numberListContains(Object value, int expected)
    {
        if(value instanceof Iterable<?> values)
        {
            for(Object member: values)
            {
                if(numberEquals(member, expected))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static void validateActivityRequest(StatsRequest request)
    {
        request.optionalIdentifier("talkgroup_id");
        request.optionalIdentifier("radio_id");
        String kind = request.text("kind");

        if(kind != null && !"talkgroup".equalsIgnoreCase(kind) && !"patch".equalsIgnoreCase(kind))
        {
            throw new StatsApiException(400, "kind must be talkgroup or patch");
        }
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

    private void handleStatic(HttpExchange exchange, Path root) throws IOException
    {
        WebAccessHttpController.prepareSecurityHeaders(exchange);

        if(!requireMethod(exchange, "GET", "HEAD"))
        {
            return;
        }

        if(root == null)
        {
            sendText(exchange, 503, "Stats web server assets folder is unavailable.");
            return;
        }

        String requestPath = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);

        if(requestPath == null || requestPath.equals("/") || requestPath.isBlank())
        {
            requestPath = "/index.html";
        }

        Path file = root.resolve(requestPath.substring(1)).normalize();

        if(!file.startsWith(root))
        {
            sendText(exchange, 403, "Forbidden");
            return;
        }

        if(!Files.isRegularFile(file))
        {
            if("/index.html".equals(requestPath))
            {
                sendMissingAssetsPage(exchange, root);
            }
            else
            {
                sendText(exchange, 404, "Not found");
            }

            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(file));
        headers.set("Cache-Control", "no-cache");
        long length = Files.size(file);
        exchange.sendResponseHeaders(200, "HEAD".equals(exchange.getRequestMethod()) ? -1 : length);

        if(!"HEAD".equals(exchange.getRequestMethod()))
        {
            try(OutputStream outputStream = exchange.getResponseBody())
            {
                Files.copy(file, outputStream);
            }
        }
        else
        {
            exchange.close();
        }
    }

    private void sendMissingAssetsPage(HttpExchange exchange, Path root) throws IOException
    {
        sendHtml(exchange, 200, """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>sdrtrunk-vce</title>
              <style>body{font-family:Arial,sans-serif;margin:2rem;line-height:1.4}</style>
            </head>
            <body>
              <h1>sdrtrunk-vce</h1>
              <p>No web assets were found in:</p>
              <pre>%s</pre>
            </body>
            </html>
            """.formatted(root));
    }

    static InetSocketAddress createBindAddress(int port, boolean anyIpEnabled)
    {
        return anyIpEnabled ? new InetSocketAddress("0.0.0.0", port) :
            new InetSocketAddress("127.0.0.1", port);
    }

    private boolean requireMethod(HttpExchange exchange, String... methods) throws IOException
    {
        String actual = exchange.getRequestMethod();

        for(String method: methods)
        {
            if(method.equals(actual))
            {
                return true;
            }
        }

        sendText(exchange, 405, "Method not allowed");
        return false;
    }

    private static String contentType(Path file)
    {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if(name.endsWith(".html"))
        {
            return "text/html; charset=utf-8";
        }
        else if(name.endsWith(".css"))
        {
            return "text/css; charset=utf-8";
        }
        else if(name.endsWith(".js"))
        {
            return "application/javascript; charset=utf-8";
        }
        else if(name.endsWith(".json"))
        {
            return "application/json; charset=utf-8";
        }
        else if(name.endsWith(".txt"))
        {
            return "text/plain; charset=utf-8";
        }
        else if(name.endsWith(".svg"))
        {
            return "image/svg+xml";
        }
        else if(name.endsWith(".png"))
        {
            return "image/png";
        }

        return "application/octet-stream";
    }

    private static void sendHtml(HttpExchange exchange, int status, String body) throws IOException
    {
        send(exchange, status, "text/html; charset=utf-8", body);
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException
    {
        send(exchange, status, "text/plain; charset=utf-8", body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);

        try(OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(bytes);
        }
    }

    @Override
    public synchronized void close()
    {
        if(mClosed)
        {
            return;
        }

        mClosed = true;
        mTlsMaintenanceExecutor.shutdownNow();
        MyEventBus.getGlobalEventBus().unregister(this);
        stopActiveListener();
        mRuntimeState = stoppedState("Web server is stopped.");

        if(mWebAccessHttpController != null)
        {
            mWebAccessHttpController.close();
            mWebAccessHttpController = null;
            mWebAuthenticationService = null;
            mWebAccessService = null;
        }

        mLiveService.close();
        mWebCallService.close();
    }

    public record TlsActivation(TlsMaterial material, WebServerRuntimeState runtimeState)
    {
        public TlsActivation
        {
            if(material == null || runtimeState == null)
            {
                throw new IllegalArgumentException("TLS activation result cannot contain null values");
            }
        }
    }

    private record RequestedConfiguration(Path assetRoot, int port, boolean anyIpEnabled, boolean httpsEnabled)
    {
        private RequestedConfiguration
        {
            if(assetRoot == null)
            {
                throw new IllegalArgumentException("Stats web asset root cannot be null");
            }

            assetRoot = assetRoot.toAbsolutePath().normalize();

            if(port < 0 || port > 65_535)
            {
                throw new IllegalArgumentException("Stats web server port is invalid");
            }
        }
    }

    private record PreparedConfiguration(RequestedConfiguration requested, SSLContext sslContext,
                                         String certificateFingerprint)
    {
        private PreparedConfiguration
        {
            if(requested == null || requested.httpsEnabled() != (sslContext != null) ||
                requested.httpsEnabled() != (certificateFingerprint != null))
            {
                throw new IllegalArgumentException("Prepared web listener TLS state is invalid");
            }
        }
    }

    private record ListenerRuntime(HttpServer server, ExecutorService executor, PreparedConfiguration configuration)
    {
        private ListenerRuntime
        {
            if(server == null || executor == null || configuration == null)
            {
                throw new IllegalArgumentException("Active web listener state cannot contain null values");
            }
        }
    }

    private interface JsonSupplier
    {
        Map<String,Object> get();
    }
}
