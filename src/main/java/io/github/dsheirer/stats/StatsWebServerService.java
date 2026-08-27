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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.eventbus.Subscribe;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.filter.FilterCatalog;
import io.github.dsheirer.message.DecodeMessageViewService;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.application.WebCertificateMode;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListModel;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.stats.activity.P25ActivityLogPath;
import io.github.dsheirer.stats.activity.P25ActivityLogService;
import io.github.dsheirer.stats.activity.P25ActivityLogStatus;
import io.github.dsheirer.stats.health.ReceiverHealthService;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.TlsMaterialException;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.AliasAdminHttpController;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.http.ApiRequestDecoder;
import io.github.dsheirer.web.http.EmbeddedHttpServerPolicy;
import io.github.dsheirer.web.http.EmbeddedHttpServerShutdown;
import io.github.dsheirer.web.http.RadioReferenceHttpController;
import io.github.dsheirer.web.http.WebAccessPolicyHttpController;
import io.github.dsheirer.web.http.WebCallConfigurationHttpController;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.http.WebSessionHttpController;
import io.github.dsheirer.web.http.WebSiteSettingsHttpController;
import io.github.dsheirer.web.http.WebUserAdminHttpController;
import io.github.dsheirer.web.http.WebUserPreferencesHttpController;
import io.github.dsheirer.web.auth.WebUserPreferencesService;
import io.github.dsheirer.web.settings.WebSiteSettingsService;
import io.github.dsheirer.web.network.WebCertificateIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded stats web server. Static assets are served only from an external filesystem folder.
 */
public class StatsWebServerService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(StatsWebServerService.class);
    static final Duration AUTOMATIC_CERTIFICATE_RENEWAL_WINDOW = Duration.ofDays(30);
    private static final long AUTOMATIC_CERTIFICATE_INITIAL_CHECK_MINUTES = 1;
    private static final long AUTOMATIC_CERTIFICATE_MAINTENANCE_MINUTES = TimeUnit.HOURS.toMinutes(12);
    private static final int REQUEST_MAXIMUM_THREADS = 96;
    private static final int REQUEST_QUEUE_CAPACITY = 256;
    private static final int MAXIMUM_WEB_INDEX_BYTES = 64 * 1024;
    private static final int MAXIMUM_MULTIPLEX_CLIENTS = 32;
    private static final int MAXIMUM_MULTIPLEX_CONTROL_BYTES = 32 * 1024;
    private static final int MULTIPLEX_MAGIC = 0x534C4D58;
    private static final int MULTIPLEX_VERSION = 1;
    private static final int MULTIPLEX_HEADER_BYTES = 16;
    private static final int MULTIPLEX_JSON = 1;
    private static final int MULTIPLEX_DIAGNOSTIC = 2;
    private static final int TOPIC_CONTROL = 0;
    private static final int TOPIC_CHANNEL_ACTIVITY = 1;
    private static final int TOPIC_CALLS = 2;
    private static final int TOPIC_DECODE_EVENTS = 3;
    private static final int TOPIC_DECODE_MESSAGES = 4;
    private static final int TOPIC_CHANNEL_DIAGNOSTICS = 5;
    private static final int TOPIC_TUNER_DIAGNOSTICS = 6;
    private static final int TOPIC_MAXIMUM = TOPIC_TUNER_DIAGNOSTICS;
    private static final ObjectMapper MULTIPLEX_OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final Set<String> MULTIPLEX_TOPICS = Set.of("channel_activity", "calls", "decode_events",
        "decode_messages", "channel_diagnostics", "tuner_diagnostics");
    static final Set<WebCapability> MULTIPLEX_CAPABILITIES = Set.of(WebCapability.LIVE_VIEW,
        WebCapability.TUNER_SPECTRUM_VIEW, WebCapability.WEB_AUDIO_LISTEN);

    private final UserPreferences mUserPreferences;
    private final StatsWebDatabase mDatabase;
    private final StatsLiveService mLiveService;
    private final DecodeEventViewService mDecodeEventViewService;
    private final DecodeMessageViewService mDecodeMessageViewService;
    private final DiagnosticFftScheduler mDiagnosticFftScheduler;
    private final ChannelDiagnosticService mChannelDiagnosticService;
    private final TunerDiagnosticService mTunerDiagnosticService;
    private final ReceiverHealthService mReceiverHealthService;
    private final StatsLiveEventHub mDecodeEventHub = new StatsLiveEventHub(32, 256);
    private final Object mDecodeEventSubscriptionLock = new Object();
    private final Listener<DecodeEventViewService.EventView> mDecodeEventViewListener =
        event -> mDecodeEventHub.publish("decode_event", event);
    private final StatsWebCallService mWebCallService;
    private final Semaphore mDecodeMessageClients = new Semaphore(16);
    private final Semaphore mDiagnosticClients = new Semaphore(32);
    private final Semaphore mMultiplexClientPermits = new Semaphore(MAXIMUM_MULTIPLEX_CLIENTS);
    private final Map<String,MultiplexClient> mMultiplexClients = new ConcurrentHashMap<>();
    private final AtomicLong mMultiplexRejectedClients = new AtomicLong();
    private final AtomicLong mMultiplexSlowDisconnects = new AtomicLong();
    private final AtomicLong mMultiplexEventDrops = new AtomicLong();
    private final ChannelProcessingManager mChannelProcessingManager;
    private final P25ActivityLogService mActivityLogService;
    private final AliasAdministrationService mAliasAdministrationService;
    private final ScanListModel mScanListModel;
    private final RadioReferenceDirectoryService mRadioReferenceDirectoryService;
    private final Path mWebAccessDatabasePath;
    private final WebSiteSettingsService mWebSiteSettingsService;
    private final WebTlsMaterialService mTlsMaterialService;
    private final ScheduledExecutorService mTlsMaintenanceExecutor;
    private volatile ListenerRuntime mListener;
    private volatile WebServerRuntimeState mRuntimeState = stoppedState("Web server is disabled.");
    private WebAccessService mWebAccessService;
    private WebAuthenticationService mWebAuthenticationService;
    private volatile WebRequestSecurity mWebRequestSecurity;
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
        this(userPreferences, channelProcessingManager, activityLogService, aliasAdministrationService, null);
    }

    public StatsWebServerService(UserPreferences userPreferences, ChannelProcessingManager channelProcessingManager,
                                 P25ActivityLogService activityLogService,
                                 AliasAdministrationService aliasAdministrationService,
                                 DecodeEventViewService decodeEventViewService)
    {
        this(userPreferences, channelProcessingManager, activityLogService, aliasAdministrationService,
            decodeEventViewService, null);
    }

    public StatsWebServerService(UserPreferences userPreferences, ChannelProcessingManager channelProcessingManager,
                                 P25ActivityLogService activityLogService,
                                 AliasAdministrationService aliasAdministrationService,
                                 DecodeEventViewService decodeEventViewService, TunerManager tunerManager)
    {
        this(userPreferences, channelProcessingManager, activityLogService, aliasAdministrationService,
            decodeEventViewService, tunerManager, null);
    }

    public StatsWebServerService(UserPreferences userPreferences, ChannelProcessingManager channelProcessingManager,
                                 P25ActivityLogService activityLogService,
                                 AliasAdministrationService aliasAdministrationService,
                                 DecodeEventViewService decodeEventViewService, TunerManager tunerManager,
                                 ScanListModel scanListModel)
    {
        EmbeddedHttpServerPolicy.configureBeforeServerInitialization();
        mUserPreferences = userPreferences;
        mScanListModel = scanListModel;
        mRadioReferenceDirectoryService = new RadioReferenceDirectoryService();
        mDatabase = new StatsWebDatabase(userPreferences);
        WebEntityNavigationCatalog entityCatalog =
            new WebEntityNavigationCatalog(mDatabase::webEntityNavigationSnapshot);
        mWebCallService = new StatsWebCallService(mScanListModel,
            mUserPreferences.getApplicationPreference().getWebCallConfiguration(), entityCatalog);
        mChannelProcessingManager = channelProcessingManager;
        mActivityLogService = activityLogService;
        mAliasAdministrationService = aliasAdministrationService;
        mDecodeEventViewService = decodeEventViewService;
        mDecodeMessageViewService = channelProcessingManager != null ?
            new DecodeMessageViewService(channelProcessingManager) : null;
        mDiagnosticFftScheduler = new DiagnosticFftScheduler();
        mChannelDiagnosticService = channelProcessingManager != null ?
            new ChannelDiagnosticService(channelProcessingManager, mDiagnosticFftScheduler) : null;
        mTunerDiagnosticService = tunerManager != null ?
            new TunerDiagnosticService(tunerManager, mDiagnosticFftScheduler) : null;
        mLiveService = new StatsLiveService(channelProcessingManager, entityCatalog);
        mWebAccessDatabasePath = SdrTrunkDatabasePath.getDatabasePath(mUserPreferences);
        mWebSiteSettingsService = new WebSiteSettingsService(mUserPreferences.getNowPlayingPreference());
        mTlsMaterialService = new WebTlsMaterialService(
            mUserPreferences.getDirectoryPreference().getDirectoryApplicationRoot());
        mTlsMaintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stats web TLS maintenance");
            thread.setDaemon(true);
            return thread;
        });
        mReceiverHealthService = new ReceiverHealthService(userPreferences, tunerManager,
            channelProcessingManager, activityLogService);
        mReceiverHealthService.setWebStatusSupplier(this::receiverHealthObserverStatus);
        MyEventBus.getGlobalEventBus().register(this);
        updateServerState();
        mTlsMaintenanceExecutor.scheduleWithFixedDelay(this::maintainAutomaticCertificate,
            AUTOMATIC_CERTIFICATE_INITIAL_CHECK_MINUTES, AUTOMATIC_CERTIFICATE_MAINTENANCE_MINUTES,
            TimeUnit.MINUTES);
        mReceiverHealthService.start();
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.APPLICATION || preferenceType == PreferenceType.DIRECTORY)
        {
            if(preferenceType == PreferenceType.APPLICATION)
            {
                mWebCallService.configure(mUserPreferences.getApplicationPreference().getWebCallConfiguration());
            }
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
     * authentication revision changes before this method returns, so every old session fails its next snapshot
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
        boolean httpsEnabled = anyIpEnabled || preference.isStatsWebServerHttpsEnabled();
        RequestedConfiguration requested = new RequestedConfiguration(StatsWebPath.getAssetsPath(),
            preference.getStatsWebServerPort(), anyIpEnabled, httpsEnabled);
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

        ExecutorService executor = new ThreadPoolExecutor(REQUEST_MAXIMUM_THREADS, REQUEST_MAXIMUM_THREADS,
            60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(REQUEST_QUEUE_CAPACITY),
            new NamingThreadFactory("stats web server"), new ThreadPoolExecutor.AbortPolicy());

        try
        {
            String webClientRevision = readWebClientRevision(requested.assetRoot());
            server.setExecutor(executor);
            registerContexts(server, requested.assetRoot(), webClientRevision);
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

    private static String readWebClientRevision(Path assetRoot)
    {
        if(assetRoot == null)
        {
            return null;
        }

        Path index = assetRoot.resolve("index.html");

        try(InputStream input = Files.newInputStream(index))
        {
            byte[] bytes = input.readNBytes(MAXIMUM_WEB_INDEX_BYTES + 1);

            if(bytes.length > MAXIMUM_WEB_INDEX_BYTES)
            {
                return null;
            }

            String html = new String(bytes, StandardCharsets.UTF_8);
            String prefix = "<meta name=\"sdrtrunk-web-revision\" content=\"";
            int start = html.indexOf(prefix);

            if(start < 0)
            {
                return null;
            }

            start += prefix.length();
            int end = html.indexOf('"', start);
            String revision = end > start ? html.substring(start, end).trim() : "";
            return revision.matches("[A-Za-z0-9._-]{1,64}") ? revision : null;
        }
        catch(IOException exception)
        {
            return null;
        }
    }

    private void registerContexts(HttpServer server, Path assetRoot, String webClientRevision)
    {
        new WebSessionHttpController(mWebAccessService, mWebAuthenticationService, mWebRequestSecurity)
            .register(server);
        WebUserAdminHttpController users =
            new WebUserAdminHttpController(mWebAccessService, mWebAuthenticationService);
        server.createContext(WebUserAdminHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_USERS, users::handle));
        WebAccessPolicyHttpController access = new WebAccessPolicyHttpController(mWebAccessService);
        server.createContext(WebAccessPolicyHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_ACCESS, access::handle));

        if(mAliasAdministrationService != null)
        {
            AliasAdminHttpController aliasController = new AliasAdminHttpController(mAliasAdministrationService);
            HttpHandler protectedAliases = mWebRequestSecurity.protectApi(
                WebCapability.ADMIN_ALIASES, aliasController::handle);
            server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, protectedAliases);
            server.createContext(AliasAdminHttpController.ALIASES_PATH, protectedAliases);
            server.createContext(AliasAdminHttpController.SCAN_LISTS_PATH, protectedAliases);
        }

        WebCallConfigurationHttpController webCallConfigurationController =
            new WebCallConfigurationHttpController(
                () -> mUserPreferences.getApplicationPreference().getWebCallConfiguration(),
                configuration -> mUserPreferences.getApplicationPreference().setWebCallConfiguration(configuration),
                mWebCallService::status);
        server.createContext(WebCallConfigurationHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_AUDIO, webCallConfigurationController::handle));

        RadioReferenceHttpController radioReferenceController = new RadioReferenceHttpController(
            mRadioReferenceDirectoryService, mUserPreferences.getRadioReferencePreference());
        server.createContext(RadioReferenceHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, radioReferenceController::handle));

        WebSiteSettingsHttpController siteSettingsController =
            new WebSiteSettingsHttpController(mWebSiteSettingsService);
        server.createContext(WebSiteSettingsHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, siteSettingsController::handle));

        WebUserPreferencesHttpController userPreferencesController = new WebUserPreferencesHttpController(
            mWebRequestSecurity, new WebUserPreferencesService(mWebAccessDatabasePath));
        server.createContext(WebUserPreferencesHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.USER_SETTINGS, userPreferencesController::handle));

        new StatsApiV1Controller(mDatabase, this::status, mWebRequestSecurity, mTunerDiagnosticService,
            mReceiverHealthService::snapshot)
            .register(server);
        server.createContext(StatsApiV1.LIVE_MULTIPLEX,
            mWebRequestSecurity.protectAny(MULTIPLEX_CAPABILITIES, this::handleLiveMultiplex));
        server.createContext(StatsApiV1.LIVE_MULTIPLEX_CONTROL,
            mWebRequestSecurity.protectAnyViewerControl(MULTIPLEX_CAPABILITIES,
                this::handleLiveMultiplexControl));
        createProtectedContext(server, StatsApiV1.SCAN_LISTS, WebCapability.WEB_AUDIO_LISTEN,
            this::handleScanLists);
        createProtectedContext(server, StatsApiV1.CALLS + "/", WebCapability.WEB_AUDIO_LISTEN,
            this::handleWebCallAudio);
        server.createContext(StatsApiV1.ROOT, StatsWebServerService::handleApiNotFound);
        server.createContext("/api", StatsWebServerService::handleApiNotFound);
        server.createContext("/live", StatsWebServerService::handleApiNotFound);
        server.createContext("/", exchange -> handleStatic(exchange, assetRoot, webClientRevision));
    }

    private void ensureAuthenticationServices() throws IOException, SQLException
    {
        if(mWebRequestSecurity == null)
        {
            WebAccessService accessService = new WebAccessService(mWebAccessDatabasePath);
            WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
            mWebAccessService = accessService;
            mWebAuthenticationService = authenticationService;
            mWebRequestSecurity = new WebRequestSecurity(accessService, authenticationService);
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

        List<MultiplexClient> clients = List.copyOf(mMultiplexClients.values());

        for(MultiplexClient client: clients)
        {
            client.requestClose();
        }

        long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        for(MultiplexClient client: clients)
        {
            client.awaitOwnerClose(closeDeadline);
        }

        if(mDecodeEventViewService != null)
        {
            synchronized(mDecodeEventSubscriptionLock)
            {
                mDecodeEventViewService.removeListener(mDecodeEventViewListener);
            }
        }

        if(mChannelDiagnosticService != null)
        {
            mChannelDiagnosticService.closeActiveSession();
        }

        if(mTunerDiagnosticService != null)
        {
            mTunerDiagnosticService.closeActiveSessions();
        }

        mLiveService.stop();
        mWebCallService.stop();
        mRuntimeServicesStarted = false;

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
        EmbeddedHttpServerShutdown.stop(listener.server(), listener.executor());
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
        server.createContext(path, mWebRequestSecurity.protect(capability, handler));
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
        server.put("liveTransport", Map.of(
            "stream", StatsApiV1.LIVE_MULTIPLEX,
            "control", StatsApiV1.LIVE_MULTIPLEX_CONTROL,
            "maximumClients", MAXIMUM_MULTIPLEX_CLIENTS,
            "activeClients", mMultiplexClients.size(),
            "rejectedClients", mMultiplexRejectedClients.get(),
            "slowDisconnects", mMultiplexSlowDisconnects.get(),
            "eventDrops", mMultiplexEventDrops.get()));
        status.put("server", server);
        status.put("database", mDatabase.status());
        status.put("statsLogging", statsLoggingStatusResponse());
        status.put("webPlayer", mWebCallService.status());
        return status;
    }

    /**
     * Internal-only observer measurements for the administrator health sampler.  These are intentionally not added to
     * the public status endpoint.
     */
    private Map<String,Object> receiverHealthObserverStatus()
    {
        Map<String,Object> transport = Map.of(
            "activeClients", mMultiplexClients.size(),
            "rejectedClients", mMultiplexRejectedClients.get(),
            "slowDisconnects", mMultiplexSlowDisconnects.get(),
            "eventDrops", mMultiplexEventDrops.get());
        return Map.of(
            "server", Map.of("liveTransport", transport),
            "webPlayer", mWebCallService.observerStatus(),
            "diagnostics", Map.of(
            "channel_sessions", mChannelDiagnosticService != null ? mChannelDiagnosticService.activeSessionCount() : 0,
            "channel_producers", mChannelDiagnosticService != null ? mChannelDiagnosticService.activeProducerCount() : 0,
            "tuner_sessions", mTunerDiagnosticService != null ? mTunerDiagnosticService.activeSessionCount() : 0,
            "tuner_producers", mTunerDiagnosticService != null ? mTunerDiagnosticService.activeProducerCount() : 0));
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

        ApiHttpResponse.sendError(exchange, 404, "not_found", "Resource not found");
        return false;
    }

    private static boolean requireNoQuery(HttpExchange exchange) throws IOException
    {
        StatsRequest request;

        try
        {
            request = StatsRequest.from(exchange.getRequestURI());
            request.requireFullyConsumed();
            return true;
        }
        catch(StatsApiException exception)
        {
            sendApiException(exchange, exception);
            return false;
        }
    }

    private static void sendApiException(HttpExchange exchange, StatsApiException exception) throws IOException
    {
        ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage(),
            exception.field());
    }

    private static void sendApiError(HttpExchange exchange, int status, String code, String message)
        throws IOException
    {
        ApiHttpResponse.sendError(exchange, status, code, message);
    }

    private void handleLiveMultiplex(HttpExchange exchange) throws IOException
    {
        if(!requireExactTextPath(exchange, StatsApiV1.LIVE_MULTIPLEX) || !requireMethod(exchange, "GET"))
        {
            return;
        }

        String clientId;

        try
        {
            StatsRequest request = StatsRequest.from(exchange.getRequestURI());
            clientId = UUID.fromString(request.requiredText("client_id")).toString();
            request.requireFullyConsumed();
        }
        catch(StatsApiException | IllegalArgumentException exception)
        {
            sendApiError(exchange, 400, "invalid_parameter", "client_id must be a UUID");
            return;
        }

        if(!mMultiplexClientPermits.tryAcquire())
        {
            mMultiplexRejectedClients.incrementAndGet();
            sendApiError(exchange, 429, "too_many_clients", "Too many live browser connections");
            return;
        }

        MultiplexClient client = new MultiplexClient(clientId, exchange);

        if(mMultiplexClients.putIfAbsent(clientId, client) != null)
        {
            mMultiplexClientPermits.release();
            sendApiError(exchange, 409, "client_in_use", "The live browser connection identifier is already in use");
            return;
        }

        AtomicBoolean connectionReleased = new AtomicBoolean();
        Runnable releaseConnection = () -> {
            if(connectionReleased.compareAndSet(false, true))
            {
                mMultiplexClients.remove(clientId, client);
                mMultiplexClientPermits.release();
            }
        };
        MultiplexOutput output = null;

        try(client)
        {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/vnd.sdrtrunk.live+binary");
            headers.set("Cache-Control", "no-store, no-transform");
            headers.set("Connection", "keep-alive");
            headers.set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);
            HttpServer server = exchange.getHttpContext().getServer();
            MultiplexOutput connectedOutput = new MultiplexOutput(exchange.getResponseBody(), Duration.ofSeconds(8),
                false, () -> {}, releaseConnection);
            output = connectedOutput;

            try(connectedOutput)
            {
                connectedOutput.start();
                writeMultiplexJson(connectedOutput, TOPIC_CONTROL, "ready", Map.of("clientId", clientId));
                long lastHeartbeat = System.nanoTime();
                long lastAuthorizationCheck = 0;

                while(mListener != null && mListener.server() == server && !client.isClosed() &&
                    !connectedOutput.isFailed())
                {
                    long now = System.nanoTime();

                    if(now - lastAuthorizationCheck >= TimeUnit.SECONDS.toNanos(1))
                    {
                        if(!mWebRequestSecurity.isRequestStillAuthorized(exchange))
                        {
                            break;
                        }

                        client.refreshAuthorization();
                        lastAuthorizationCheck = now;
                    }

                    boolean wrote = client.pump(connectedOutput);

                    if(now - lastHeartbeat >= TimeUnit.SECONDS.toNanos(10))
                    {
                        writeMultiplexJson(connectedOutput, TOPIC_CONTROL, "heartbeat",
                            Map.of("time", System.currentTimeMillis()));
                        wrote = true;
                        lastHeartbeat = now;
                    }

                    if(connectedOutput.isPersistentlySlow())
                    {
                        mMultiplexSlowDisconnects.incrementAndGet();
                        mLog.debug("Closing slow multiplex client [{}]", clientId);
                        break;
                    }

                    if(wrote)
                    {
                        lastHeartbeat = now;
                    }
                    else
                    {
                        Thread.sleep(20);
                    }
                }
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
        catch(IOException exception)
        {
            // Browser disconnected or a bounded slow-client connection was closed by the HTTP runtime.
        }
        finally
        {
            if(output != null)
            {
                mMultiplexEventDrops.addAndGet(output.eventDrops());
            }

            if(output == null || !output.isWriterStarted() || output.isWriterTerminated())
            {
                releaseConnection.run();
            }
        }
    }

    private void handleLiveMultiplexControl(HttpExchange exchange) throws IOException
    {
        if(!requireExactTextPath(exchange, StatsApiV1.LIVE_MULTIPLEX_CONTROL) ||
            !requireMethod(exchange, "POST") || !requireNoQuery(exchange))
        {
            return;
        }

        JsonNode request;

        try
        {
            byte[] body = ApiRequestDecoder.readBody(exchange, MAXIMUM_MULTIPLEX_CONTROL_BYTES);

            if(body.length == 0 || body.length > MAXIMUM_MULTIPLEX_CONTROL_BYTES)
            {
                sendApiError(exchange, body.length == 0 ? 400 : 413,
                    body.length == 0 ? "invalid_request" : "request_too_large",
                    body.length == 0 ? "A viewer control body is required" : "The viewer control body is too large");
                return;
            }

            request = MULTIPLEX_OBJECT_MAPPER.readTree(body);
        }
        catch(Exception exception)
        {
            sendApiError(exchange, 400, "invalid_request", "The viewer control body is invalid");
            return;
        }

        try
        {
            if(request == null || !request.isObject() || request.size() != 3 ||
                !request.has("client_id") || !request.has("revision") || !request.has("subscriptions"))
            {
                throw new IllegalArgumentException("The viewer control fields are invalid");
            }

            String clientId = UUID.fromString(requiredMultiplexText(request, "client_id")).toString();
            long revision = request.path("revision").longValue();
            JsonNode subscriptions = request.path("subscriptions");

            if(!request.path("revision").isIntegralNumber() || !request.path("revision").canConvertToLong() ||
                revision <= 0 || !subscriptions.isObject() ||
                subscriptions.size() > MULTIPLEX_TOPICS.size())
            {
                throw new IllegalArgumentException("The viewer control values are invalid");
            }

            java.util.Iterator<String> names = subscriptions.fieldNames();

            while(names.hasNext())
            {
                String name = names.next();
                JsonNode parameters = subscriptions.get(name);

                if(!MULTIPLEX_TOPICS.contains(name) || parameters == null || !parameters.isObject())
                {
                    throw new IllegalArgumentException("The viewer subscription is invalid");
                }

                validateMultiplexSubscription(name, parameters);
            }

            MultiplexClient client = mMultiplexClients.get(clientId);

            if(client == null || client.isClosed())
            {
                sendApiError(exchange, 404, "client_not_found", "The live browser connection is not available");
                return;
            }

            Map<String,JsonNode> requested = new LinkedHashMap<>();
            subscriptions.fields().forEachRemaining(entry -> requested.put(entry.getKey(), entry.getValue().deepCopy()));
            client.configure(new MultiplexConfiguration(revision, Map.copyOf(requested)));

            //An accepted empty control document is the browser's final unsubscribe handshake.  Request owner-thread
            //shutdown before acknowledging it so tuner/channel diagnostic leases cannot outlive a locally closed
            //multiplex connection when the browser aborts the GET immediately after this response.
            if(requested.isEmpty())
            {
                client.requestClose();
            }

            ApiHttpResponse.sendData(exchange, 200, Map.of("revision", revision));
        }
        catch(IllegalArgumentException | StatsApiException exception)
        {
            sendApiError(exchange, 400, "invalid_request", exception.getMessage());
        }
    }

    private static String requiredMultiplexText(JsonNode request, String field)
    {
        JsonNode value = request.get(field);

        if(value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 256)
        {
            throw new IllegalArgumentException(field + " is invalid");
        }

        return value.textValue();
    }

    private void validateMultiplexSubscription(String name, JsonNode parameters)
    {
        URI uri = multiplexSubscriptionUri(name, parameters);

        switch(name)
        {
            case "channel_activity" -> {
                if(parameters.size() != 0)
                {
                    throw new StatsApiException(400, "This live subscription does not accept parameters");
                }
            }
            case "calls" -> selectedScanListIds(uri);
            case "decode_events" -> decodeEventScope(uri);
            case "decode_messages" -> decodeMessageScope(uri);
            case "channel_diagnostics" -> channelDiagnosticScope(uri);
            case "tuner_diagnostics" -> tunerDiagnosticRequest(uri);
            default -> throw new StatsApiException(400, "Unknown live subscription");
        }
    }

    private static URI multiplexSubscriptionUri(String name, JsonNode parameters)
    {
        if(parameters.size() > 16)
        {
            throw new StatsApiException(400, "Too many live subscription parameters");
        }

        StringBuilder query = new StringBuilder();
        AtomicInteger valueCount = new AtomicInteger();
        parameters.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();

            if(value == null)
            {
                throw new StatsApiException(400, "Live subscription parameters cannot be null");
            }

            if(value.isArray())
            {
                for(JsonNode item: value)
                {
                    appendMultiplexParameter(query, entry.getKey(), item, valueCount);
                }
            }
            else
            {
                appendMultiplexParameter(query, entry.getKey(), value, valueCount);
            }
        });
        return URI.create("/multiplex/" + name + (query.isEmpty() ? "" : "?" + query));
    }

    private static void appendMultiplexParameter(StringBuilder query, String name, JsonNode value,
                                                 AtomicInteger valueCount)
    {
        if(value == null || (!value.isTextual() && !value.isNumber() && !value.isBoolean()) ||
            valueCount.incrementAndGet() > WebCallConfiguration.MAXIMUM_SELECTED_SCAN_LISTS)
        {
            throw new StatsApiException(400, "Live subscription parameters must contain bounded scalar values");
        }

        if(query.length() > 0)
        {
            query.append('&');
        }

        query.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
            .append(URLEncoder.encode(value.asText(), StandardCharsets.UTF_8));
    }

    private static TunerDiagnosticRequest tunerDiagnosticRequest(URI uri)
    {
        StatsRequest request = StatsRequest.from(uri);
        String targetId = request.requiredText("target_id");
        Long viewportStart = request.optionalLong("viewport_start_hz");
        Long viewportEnd = request.optionalLong("viewport_end_hz");
        String profileId = request.text("profile");

        if((viewportStart == null) != (viewportEnd == null))
        {
            throw new StatsApiException(400, "viewport_start_hz and viewport_end_hz must be supplied together");
        }

        TunerDiagnosticService.Viewport viewport = null;

        if(viewportStart != null)
        {
            try
            {
                viewport = new TunerDiagnosticService.Viewport(viewportStart, viewportEnd);
            }
            catch(IllegalArgumentException exception)
            {
                throw new StatsApiException(400, "Tuner diagnostic viewport is invalid");
            }
        }

        TunerDiagnosticService.SpectrumProfile profile;

        try
        {
            profile = TunerDiagnosticService.SpectrumProfile.fromId(profileId);
        }
        catch(IllegalArgumentException exception)
        {
            throw new StatsApiException(400, exception.getMessage());
        }

        request.requireFullyConsumed();
        return new TunerDiagnosticRequest(targetId, viewport, profile);
    }

    private static void writeMultiplexJson(MultiplexOutput output, int topic, String event, Object data)
        throws IOException
    {
        output.offerEvent(topic, encodeMultiplexEnvelope(MULTIPLEX_JSON, topic,
            ApiHttpResponse.encodePayload(Map.of("event", event, "data", data))));
    }

    private static void writeMultiplexRecoveryJson(MultiplexOutput output, int topic, String event, Object data)
        throws IOException
    {
        output.offerRecovery(topic, encodeMultiplexEnvelope(MULTIPLEX_JSON, topic,
            ApiHttpResponse.encodePayload(Map.of("event", event, "data", data))));
    }

    private static void writeMultiplexDiagnostic(MultiplexOutput output, int topic, DiagnosticStreamFrame frame)
    {
        byte[] encoded = encodeMultiplexEnvelope(MULTIPLEX_DIAGNOSTIC, topic, frame.encoded());

        if(frame.type() == DiagnosticStreamFrame.TYPE_STATE)
        {
            output.offerState(topic, encoded);
        }
        else
        {
            output.offerLatest(topic, encoded);
        }
    }

    private static byte[] encodeMultiplexEnvelope(int kind, int topic, byte[] payload)
    {
        ByteBuffer envelope = ByteBuffer.allocate(Math.addExact(MULTIPLEX_HEADER_BYTES, payload.length))
            .order(ByteOrder.BIG_ENDIAN);
        envelope.putInt(MULTIPLEX_MAGIC);
        envelope.put((byte)MULTIPLEX_VERSION);
        envelope.put((byte)kind);
        envelope.putShort((short)topic);
        envelope.putInt(payload.length);
        envelope.putInt(0);
        envelope.put(payload);
        return envelope.array();
    }

    static DecodeEventViewService.Scope decodeEventScope(URI uri)
    {
        return decodeEventRequest(uri).scope();
    }

    private static DecodeEventRequest decodeEventRequest(URI uri)
    {
        StatsRequest request = StatsRequest.from(uri);
        String configurationId;

        try
        {
            configurationId = UUID.fromString(request.requiredText("configuration_id")).toString();
        }
        catch(IllegalArgumentException e)
        {
            throw new StatsApiException(400, "configuration_id is invalid");
        }

        Long frequency = request.optionalLong("frequency_hz");
        Integer timeslot = request.optionalInt("timeslot");
        String subscriptionId = request.text("subscription_id");

        if(frequency != null)
        {
            if(frequency <= 0)
            {
                throw new StatsApiException(400, "invalid_parameter", "frequency_hz must be positive",
                    "frequency_hz");
            }
        }

        if(timeslot != null && timeslot <= 0)
        {
            throw new StatsApiException(400, "invalid_parameter", "timeslot must be positive", "timeslot");
        }

        if(subscriptionId != null)
        {
            try
            {
                subscriptionId = UUID.fromString(subscriptionId).toString();
            }
            catch(IllegalArgumentException exception)
            {
                throw new StatsApiException(400, "subscription_id is invalid");
            }
        }

        request.requireFullyConsumed();
        return new DecodeEventRequest(new DecodeEventViewService.Scope(configurationId, frequency, timeslot),
            subscriptionId);
    }

    static DecodeMessageViewService.Scope decodeMessageScope(URI uri)
    {
        return decodeMessageRequest(uri).scope();
    }

    private static DecodeMessageRequest decodeMessageRequest(URI uri)
    {
        StatsRequest request = StatsRequest.from(uri);
        String configurationId;

        try
        {
            configurationId = UUID.fromString(request.requiredText("configuration_id")).toString();
        }
        catch(IllegalArgumentException e)
        {
            throw new StatsApiException(400, "configuration_id is invalid");
        }

        Long frequency = request.optionalLong("frequency_hz");
        String subscriptionId = request.text("subscription_id");

        if(frequency == null)
        {
            throw new StatsApiException(400, "frequency_hz is required");
        }

        if(frequency <= 0)
        {
            throw new StatsApiException(400, "invalid_parameter", "frequency_hz must be positive",
                "frequency_hz");
        }

        if(subscriptionId != null)
        {
            try
            {
                subscriptionId = UUID.fromString(subscriptionId).toString();
            }
            catch(IllegalArgumentException exception)
            {
                throw new StatsApiException(400, "subscription_id is invalid");
            }
        }

        request.requireFullyConsumed();
        return new DecodeMessageRequest(new DecodeMessageViewService.Scope(configurationId, frequency),
            subscriptionId);
    }

    static ChannelDiagnosticService.Scope channelDiagnosticScope(URI uri)
    {
        return channelDiagnosticRequest(uri).scope();
    }

    private static ChannelDiagnosticRequest channelDiagnosticRequest(URI uri)
    {
        DecodeEventRequest selected = decodeEventRequest(uri);

        if(selected.scope().frequencyHz() == null)
        {
            throw new StatsApiException(400, "frequency_hz is required");
        }

        return new ChannelDiagnosticRequest(new ChannelDiagnosticService.Scope(selected.scope().configurationId(),
            selected.scope().frequencyHz(), selected.scope().timeslot()), selected.subscriptionId());
    }

    private void handleScanLists(HttpExchange exchange) throws IOException
    {
        if(!requireMethod(exchange, "GET") || !requireNoQuery(exchange))
        {
            return;
        }

        if(mScanListModel == null)
        {
            sendApiError(exchange, 503, "service_unavailable", "Scan lists are unavailable");
            return;
        }

        if(hasExactPath(exchange.getRequestURI(), StatsApiV1.SCAN_LISTS))
        {
            sendScanLists(exchange);
            return;
        }

        Long coverageId = scanListCoverageId(exchange.getRequestURI());

        if(coverageId == null || mAliasAdministrationService == null)
        {
            sendApiError(exchange, 404, "not_found", "Resource not found");
            return;
        }

        try
        {
            AliasAdministrationService.ScanListCoverage coverage =
                mAliasAdministrationService.scanListCoverage(coverageId);

            if(!coverage.scanList().isPublished())
            {
                sendApiError(exchange, 404, "not_found", "Scan list not found");
                return;
            }

            Map<String,Object> scanList = new LinkedHashMap<>();
            scanList.put("id", coverage.scanList().getId());
            scanList.put("name", coverage.scanList().getName());
            scanList.put("description", coverage.scanList().getDescription());
            List<Map<String,Object>> aliases = coverage.aliases().stream().map(alias ->
            {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("alias_id", alias.aliasId());
                row.put("alias_list_id", alias.aliasListId());
                row.put("alias_list", alias.aliasListName());
                row.put("group", alias.group());
                row.put("name", alias.name());
                row.put("description", alias.description());
                row.put("matcher_type", alias.matcherType());
                row.put("matcher", alias.matcher());
                return row;
            }).toList();
            List<Map<String,Object>> unmatched = coverage.unmatchedAliasLists().stream().map(aliasList ->
                Map.<String,Object>of("alias_list_id", aliasList.aliasListId(), "name", aliasList.name(),
                    "family", aliasList.family())).toList();
            ApiHttpResponse.sendData(exchange, 200, Map.of("scan_list", scanList, "aliases", aliases,
                "unmatched_alias_lists", unmatched, "alias_count", coverage.aliasCount(),
                "aliases_truncated", coverage.truncated(), "maximum_aliases",
                AliasAdministrationService.MAX_SCAN_LIST_COVERAGE_ALIASES));
        }
        catch(IllegalArgumentException exception)
        {
            sendApiError(exchange, 404, "not_found", "Scan list not found");
        }
    }

    private void sendScanLists(HttpExchange exchange) throws IOException
    {
        List<Map<String,Object>> rows = mScanListModel.configuration().scanLists().stream()
            .filter(ScanList::isPublished).map(scanList -> {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("id", scanList.getId());
                row.put("name", scanList.getName());
                if(scanList.getDescription() != null)
                {
                    row.put("description", scanList.getDescription());
                }
                row.put("default", scanList.isDefault());
                return Map.copyOf(row);
            }).toList();
        WebCallConfiguration configuration =
            mUserPreferences.getApplicationPreference().getWebCallConfiguration();
        ApiHttpResponse.sendData(exchange, 200, Map.of("scan_lists", rows,
            "maximum_selected_scan_lists", configuration.maximumSelectedScanLists(),
            "waiting_calls_per_listener", configuration.waitingCallsPerListener()));
    }

    static Long scanListCoverageId(URI uri)
    {
        String path = uri != null ? uri.getPath() : null;
        String prefix = StatsApiV1.SCAN_LISTS + "/";

        if(path == null || !path.startsWith(prefix) || !path.endsWith("/coverage"))
        {
            return null;
        }

        String id = path.substring(prefix.length(), path.length() - "/coverage".length());

        if(!id.matches("[1-9][0-9]*"))
        {
            return null;
        }

        try
        {
            long value = Long.parseLong(id);
            return value > 0 ? value : null;
        }
        catch(NumberFormatException exception)
        {
            return null;
        }
    }

    Set<Long> selectedScanListIds(URI uri)
    {
        return selectedScanListIds(uri, mScanListModel,
            mUserPreferences.getApplicationPreference().getWebCallConfiguration().maximumSelectedScanLists());
    }

    static Set<Long> selectedScanListIds(URI uri, ScanListModel scanListModel, int maximum)
    {
        if(scanListModel == null)
        {
            throw new StatsApiException(503, "service_unavailable", "Scan lists are unavailable");
        }

        String query = uri != null ? uri.getRawQuery() : null;
        Set<Long> selected = new LinkedHashSet<>();

        if(query != null && !query.isBlank())
        {
            if(query.length() > StatsRequest.MAX_QUERY_LENGTH)
            {
                throw new StatsApiException(400, "invalid_parameter", "query is too long", "query");
            }

            String[] parameters = query.split("&", -1);

            if(parameters.length > StatsRequest.MAX_PARAMETER_COUNT)
            {
                throw new StatsApiException(400, "invalid_parameter", "query contains too many parameters",
                    "query");
            }

            for(String parameter : parameters)
            {
                String[] parts = parameter.split("=", 2);
                String name;
                String value;

                try
                {
                    name = ApiRequestDecoder.decodeComponent(parts[0], true);
                    value = parts.length == 2 ? ApiRequestDecoder.decodeComponent(parts[1], true) : "";
                }
                catch(IllegalArgumentException exception)
                {
                    throw new StatsApiException(400, "invalid_parameter", "query encoding is invalid", "query");
                }

                if(!"scan_list_id".equals(name) || !value.matches("[1-9][0-9]*"))
                {
                    throw new StatsApiException(400, "invalid_parameter",
                        "Only positive scan_list_id values are allowed", "scan_list_id");
                }

                try
                {
                    selected.add(Long.parseLong(value));
                }
                catch(NumberFormatException exception)
                {
                    throw new StatsApiException(400, "invalid_parameter", "scan_list_id is invalid",
                        "scan_list_id");
                }

                if(selected.size() > maximum)
                {
                    throw new StatsApiException(400, "invalid_parameter",
                        "Select no more than " + maximum + " scan lists", "scan_list_id");
                }
            }
        }

        if(selected.isEmpty())
        {
            ScanList defaultScanList = scanListModel.defaultScanList();

            if(defaultScanList.isPublished())
            {
                selected.add(defaultScanList.getId());
            }
        }

        for(Long scanListId : selected)
        {
            ScanList scanList = scanListModel.scanList(scanListId);

            if(scanList == null || !scanList.isPublished())
            {
                throw new StatsApiException(400, "invalid_parameter", "scan_list_id is unavailable",
                    "scan_list_id");
            }
        }

        return Set.copyOf(selected);
    }

    private void handleWebCallAudio(HttpExchange exchange) throws IOException
    {
        if(!requireMethod(exchange, "GET"))
        {
            return;
        }

        if(!requireNoQuery(exchange))
        {
            return;
        }

        if(!mWebCallService.tryAcquireAudioResponse())
        {
            sendApiError(exchange, 429, "too_many_audio_responses",
                "Too many call-audio responses are active");
            return;
        }

        try
        {
            handleAdmittedWebCallAudio(exchange);
        }
        finally
        {
            mWebCallService.releaseAudioResponse();
        }
    }

    private void handleAdmittedWebCallAudio(HttpExchange exchange) throws IOException
    {

        String id = callAudioId(exchange.getRequestURI());

        if(id == null)
        {
            sendApiError(exchange, 404, "not_found", "Call audio not found");
            return;
        }

        StatsWebCallService.CachedCall call = mWebCallService.get(id);

        if(call == null)
        {
            sendApiError(exchange, 404, "not_found", "Call audio is no longer available");
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

    static String callAudioId(URI uri)
    {
        String prefix = StatsApiV1.CALLS + "/";
        String suffix = "/audio";
        String rawPath = uri != null ? uri.getRawPath() : null;

        if(rawPath == null || !rawPath.startsWith(prefix) || !rawPath.endsWith(suffix))
        {
            return null;
        }

        String rawId = rawPath.substring(prefix.length(), rawPath.length() - suffix.length());

        if(rawId.isBlank() || rawId.indexOf('/') >= 0 || rawId.indexOf('\\') >= 0)
        {
            return null;
        }

        try
        {
            String id = ApiRequestDecoder.decodeComponent(rawId, false);
            return id.indexOf('/') < 0 && id.indexOf('\\') < 0 && id.indexOf('%') < 0 &&
                id.matches("[0-9a-f]{32}-[0-9a-z]{1,13}") ? id : null;
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }

    private static void handleApiNotFound(HttpExchange exchange) throws IOException
    {
        ApiHttpResponse.sendError(exchange, 404, "not_found", "Resource not found");
    }

    /**
     * Captures the source drop baseline before constructing an authoritative snapshot. Drops that happen while a
     * snapshot is being built therefore remain newer than the returned baseline and force a second recovery pass.
     */
    static <T> RecoveryCapture<T> captureRecovery(LongSupplier droppedCount, Supplier<T> snapshotSupplier)
    {
        long baseline = droppedCount.getAsLong();
        return new RecoveryCapture<>(baseline, snapshotSupplier.get());
    }

    /**
     * Receives completed calls independently from local Java playback.
     */
    public void receive(CompletedAudioCall call)
    {
        mWebCallService.receive(call);
    }

    /**
     * Adds the application-owned audio/output queue sources after the completed-call coordinator is constructed.
     */
    public void setReceiverHealthOutputSources(AudioCallCoordinator coordinator,
                                               AudioRecordingManager recordingManager,
                                               AudioStreamingManager streamingManager)
    {
        mReceiverHealthService.setOutputSources(coordinator, recordingManager, streamingManager);
    }

    private void handleStatic(HttpExchange exchange, Path root, String webClientRevision) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);

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

        if(webClientRevision != null)
        {
            headers.set("X-Sdrtrunk-Web-Revision", webClientRevision);
        }

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

        exchange.getResponseHeaders().set("Allow", String.join(", ", methods));
        ApiHttpResponse.sendError(exchange, 405, "method_not_allowed", "Method not allowed");
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
        mReceiverHealthService.close();
        mTlsMaintenanceExecutor.shutdownNow();
        MyEventBus.getGlobalEventBus().unregister(this);
        stopActiveListener();
        mRuntimeState = stoppedState("Web server is stopped.");

        if(mWebRequestSecurity != null)
        {
            mWebRequestSecurity.close();
            mWebRequestSecurity = null;
            mWebAuthenticationService = null;
            mWebAccessService = null;
        }

        mLiveService.close();
        mDecodeEventHub.close();
        if(mDecodeMessageViewService != null)
        {
            mDecodeMessageViewService.close();
        }
        if(mChannelDiagnosticService != null)
        {
            mChannelDiagnosticService.close();
        }
        if(mTunerDiagnosticService != null)
        {
            mTunerDiagnosticService.close();
        }
        mDiagnosticFftScheduler.close();
        mWebCallService.close();
        mRadioReferenceDirectoryService.close();
    }

    /**
     * Bounded per-document writer. Each metadata topic has its own count- and byte-bounded FIFO so a burst in one
     * feature cannot evict another feature's events. Dense diagnostics use one latest-value slot per topic, while
     * authoritative recovery and diagnostic state use priority coalesced slots. Closing stops new work immediately;
     * connection capacity remains charged until a blocked network writer actually terminates. Receiver and diagnostic
     * producers never call this object directly.
     */
    static final class MultiplexOutput implements AutoCloseable
    {
        private static final int EVENT_CAPACITY_PER_TOPIC = 64;
        static final int EVENT_BYTE_CAPACITY_PER_TOPIC = 256 * 1024;
        private static final long MAXIMUM_EVENT_DROPS = 64;
        private final OutputStream mOutputStream;
        private final long mWriteStallNanos;
        private final boolean mCloseStreamOnClose;
        private final Runnable mAfterEmptyStatePoll;
        private final Runnable mOnWriterTerminated;
        private final Object mPendingLock = new Object();
        private final AtomicReferenceArray<ArrayBlockingQueue<byte[]>> mEvents =
            new AtomicReferenceArray<>(TOPIC_MAXIMUM + 1);
        private final AtomicReferenceArray<byte[]> mStates = new AtomicReferenceArray<>(TOPIC_MAXIMUM + 1);
        private final AtomicReferenceArray<byte[]> mLatest = new AtomicReferenceArray<>(TOPIC_MAXIMUM + 1);
        private final AtomicBoolean mOutputClosed = new AtomicBoolean();
        private final AtomicBoolean mFailed = new AtomicBoolean();
        private final AtomicBoolean mWriterStarted = new AtomicBoolean();
        private final AtomicBoolean mWriterTerminated = new AtomicBoolean();
        private final AtomicLong mLastProgressNanos = new AtomicLong(System.nanoTime());
        private final AtomicLong mWriteStartedNanos = new AtomicLong();
        private final AtomicLong mEventDrops = new AtomicLong();
        private final AtomicLongArray mTopicEventDrops = new AtomicLongArray(TOPIC_MAXIMUM + 1);
        private final AtomicLongArray mRecentTopicEventDrops = new AtomicLongArray(TOPIC_MAXIMUM + 1);
        private final long[] mPendingEventBytes = new long[TOPIC_MAXIMUM + 1];
        private int mNextStateTopic = TOPIC_CHANNEL_ACTIVITY;
        private int mNextEventTopic = TOPIC_CONTROL;
        private int mNextTopic = TOPIC_CHANNEL_ACTIVITY;
        private boolean mPreferLatest;
        private Thread mWriter;

        MultiplexOutput(OutputStream outputStream)
        {
            this(outputStream, Duration.ofSeconds(8), true, () -> {}, () -> {});
        }

        MultiplexOutput(OutputStream outputStream, Duration writeStall)
        {
            this(outputStream, writeStall, true, () -> {}, () -> {});
        }

        MultiplexOutput(OutputStream outputStream, Duration writeStall, Runnable afterEmptyStatePoll)
        {
            this(outputStream, writeStall, true, afterEmptyStatePoll, () -> {});
        }

        MultiplexOutput(OutputStream outputStream, Duration writeStall, boolean closeStreamOnClose,
                        Runnable afterEmptyStatePoll)
        {
            this(outputStream, writeStall, closeStreamOnClose, afterEmptyStatePoll, () -> {});
        }

        MultiplexOutput(OutputStream outputStream, Duration writeStall, boolean closeStreamOnClose,
                        Runnable afterEmptyStatePoll, Runnable onWriterTerminated)
        {
            mOutputStream = Objects.requireNonNull(outputStream, "Multiplex output stream cannot be null");
            mWriteStallNanos = Objects.requireNonNull(writeStall, "Write stall duration cannot be null").toNanos();
            mCloseStreamOnClose = closeStreamOnClose;
            mAfterEmptyStatePoll = Objects.requireNonNull(afterEmptyStatePoll,
                "Multiplex selection hook cannot be null");
            mOnWriterTerminated = Objects.requireNonNull(onWriterTerminated,
                "Multiplex writer termination callback cannot be null");

            if(mWriteStallNanos <= 0)
            {
                throw new IllegalArgumentException("Write stall duration must be positive");
            }

            for(int topic = TOPIC_CONTROL; topic <= TOPIC_MAXIMUM; topic++)
            {
                mEvents.set(topic, new ArrayBlockingQueue<>(EVENT_CAPACITY_PER_TOPIC));
            }
        }

        void start()
        {
            mWriter = new Thread(this::writeLoop, "stats web multiplex writer");
            mWriter.setDaemon(true);
            mWriter.setPriority(Thread.NORM_PRIORITY - 1);
            mWriterStarted.set(true);

            try
            {
                mWriter.start();
            }
            catch(RuntimeException exception)
            {
                mWriterStarted.set(false);
                writerTerminated();
                throw exception;
            }
        }

        void offerEvent(int topic, byte[] envelope)
        {
            if(mOutputClosed.get() || !validTopic(topic))
            {
                return;
            }

            synchronized(mPendingLock)
            {
                if(mOutputClosed.get())
                {
                    return;
                }

                ArrayBlockingQueue<byte[]> events = mEvents.get(topic);

                if(envelope.length > EVENT_BYTE_CAPACITY_PER_TOPIC)
                {
                    recordEventDrop(topic);
                    return;
                }

                while(!events.isEmpty() && (events.remainingCapacity() == 0 ||
                    mPendingEventBytes[topic] + envelope.length > EVENT_BYTE_CAPACITY_PER_TOPIC))
                {
                    byte[] dropped = events.poll();
                    mPendingEventBytes[topic] -= dropped.length;
                    recordEventDrop(topic);
                }

                if(events.offer(envelope))
                {
                    mPendingEventBytes[topic] += envelope.length;
                }
                else
                {
                    //The queue is mutated only under mPendingLock, but retain a bounded failure path if that changes.
                    recordEventDrop(topic);
                }
            }
        }

        private void recordEventDrop(int topic)
        {
            mEventDrops.incrementAndGet();
            mTopicEventDrops.incrementAndGet(topic);
            mRecentTopicEventDrops.incrementAndGet(topic);
        }

        void offerRecovery(int topic, byte[] envelope)
        {
            if(mOutputClosed.get() || !validTopic(topic))
            {
                return;
            }

            synchronized(mPendingLock)
            {
                if(mOutputClosed.get())
                {
                    return;
                }

                clearEventsLocked(topic);
                offerStateLocked(topic, envelope);
            }
        }

        void clearTopic(int topic)
        {
            if(validTopic(topic))
            {
                synchronized(mPendingLock)
                {
                    clearEventsLocked(topic);
                    mStates.set(topic, null);
                    mLatest.set(topic, null);
                }
            }
        }

        void offerLatest(int topic, byte[] envelope)
        {
            if(!mOutputClosed.get() && topic >= 0 && topic < mLatest.length())
            {
                synchronized(mPendingLock)
                {
                    if(!mOutputClosed.get())
                    {
                        mLatest.set(topic, envelope);
                    }
                }
            }
        }

        void offerState(int topic, byte[] envelope)
        {
            if(!mOutputClosed.get() && topic >= 0 && topic < mStates.length())
            {
                synchronized(mPendingLock)
                {
                    if(!mOutputClosed.get())
                    {
                        offerStateLocked(topic, envelope);
                    }
                }
            }
        }

        private void offerStateLocked(int topic, byte[] envelope)
        {
            //A state frame changes the meaning/layout of dense frames. Discard any prior-layout latest frame and
            //coalesce state independently from lossy metadata so viewport acknowledgement cannot be evicted.
            mLatest.set(topic, null);
            mStates.set(topic, envelope);
        }

        boolean isFailed()
        {
            return mFailed.get();
        }

        boolean isWriterStarted()
        {
            return mWriterStarted.get();
        }

        boolean isWriterTerminated()
        {
            return mWriterTerminated.get();
        }

        long eventDrops()
        {
            return mEventDrops.get();
        }

        long eventDrops(int topic)
        {
            return validTopic(topic) ? mTopicEventDrops.get(topic) : 0;
        }

        long pendingEventBytes(int topic)
        {
            synchronized(mPendingLock)
            {
                return validTopic(topic) ? mPendingEventBytes[topic] : 0;
            }
        }

        boolean isPersistentlySlow()
        {
            long recentDrops = 0;

            for(int topic = TOPIC_CONTROL; topic < mEvents.length(); topic++)
            {
                recentDrops += mRecentTopicEventDrops.get(topic);
            }

            if(recentDrops >= MAXIMUM_EVENT_DROPS)
            {
                return true;
            }

            long started = mWriteStartedNanos.get();
            return started > mLastProgressNanos.get() && System.nanoTime() - started >= mWriteStallNanos;
        }

        private void writeLoop()
        {
            try
            {
                while(!mOutputClosed.get())
                {
                    byte[] envelope = pollPending();

                    if(envelope == null)
                    {
                        Thread.sleep(20);
                        continue;
                    }

                    mWriteStartedNanos.set(System.nanoTime());
                    mOutputStream.write(envelope);
                    mOutputStream.flush();
                    mLastProgressNanos.set(System.nanoTime());
                    mWriteStartedNanos.set(0);

                    clearRecoveredDropWindows();
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            catch(IOException exception)
            {
                if(!mOutputClosed.get())
                {
                    mFailed.set(true);
                }
            }
            catch(RuntimeException exception)
            {
                if(!mOutputClosed.get())
                {
                    mFailed.set(true);
                }
            }
            finally
            {
                mOutputClosed.set(true);
                closeStream();
                writerTerminated();
            }
        }

        private void writerTerminated()
        {
            if(mWriterTerminated.compareAndSet(false, true))
            {
                mOnWriterTerminated.run();
            }
        }

        private byte[] pollPending()
        {
            synchronized(mPendingLock)
            {
                byte[] envelope = pollState();

                if(envelope == null)
                {
                    mAfterEmptyStatePoll.run();
                }

                if(envelope == null && mPreferLatest)
                {
                    envelope = pollLatest();
                }

                if(envelope == null)
                {
                    envelope = pollEvent();
                }

                if(envelope == null)
                {
                    envelope = pollLatest();
                }

                mPreferLatest = !mPreferLatest;
                return envelope;
            }
        }

        private byte[] pollState()
        {
            for(int count = TOPIC_CHANNEL_ACTIVITY; count < mStates.length(); count++)
            {
                int topic = mNextStateTopic++;

                if(mNextStateTopic >= mStates.length())
                {
                    mNextStateTopic = TOPIC_CHANNEL_ACTIVITY;
                }

                byte[] envelope = mStates.getAndSet(topic, null);

                if(envelope != null)
                {
                    return envelope;
                }
            }

            return null;
        }

        private byte[] pollEvent()
        {
            for(int count = TOPIC_CONTROL; count < mEvents.length(); count++)
            {
                int topic = mNextEventTopic++;

                if(mNextEventTopic >= mEvents.length())
                {
                    mNextEventTopic = TOPIC_CONTROL;
                }

                byte[] envelope = mEvents.get(topic).poll();

                if(envelope != null)
                {
                    mPendingEventBytes[topic] -= envelope.length;
                    return envelope;
                }
            }

            return null;
        }

        private byte[] pollLatest()
        {
            for(int count = TOPIC_CHANNEL_ACTIVITY; count < mLatest.length(); count++)
            {
                int topic = mNextTopic++;

                if(mNextTopic >= mLatest.length())
                {
                    mNextTopic = TOPIC_CHANNEL_ACTIVITY;
                }

                if(topic >= mLatest.length())
                {
                    continue;
                }

                byte[] envelope = mLatest.getAndSet(topic, null);

                if(envelope != null)
                {
                    return envelope;
                }
            }

            return null;
        }

        private void clearRecoveredDropWindows()
        {
            synchronized(mPendingLock)
            {
                for(int topic = TOPIC_CONTROL; topic < mEvents.length(); topic++)
                {
                    if(mEvents.get(topic).size() < EVENT_CAPACITY_PER_TOPIC / 4)
                    {
                        mRecentTopicEventDrops.set(topic, 0);
                    }
                }
            }
        }

        private boolean validTopic(int topic)
        {
            return topic >= TOPIC_CONTROL && topic < mEvents.length();
        }

        private void clearEventsLocked(int topic)
        {
            mEvents.get(topic).clear();
            mPendingEventBytes[topic] = 0;
        }

        @Override
        public void close()
        {
            if(!mOutputClosed.compareAndSet(false, true))
            {
                return;
            }

            synchronized(mPendingLock)
            {
                for(int topic = 0; topic < mLatest.length(); topic++)
                {
                    clearEventsLocked(topic);
                    mStates.set(topic, null);
                    mLatest.set(topic, null);
                }
            }

            Thread writer = mWriter;

            if(writer != null && writer != Thread.currentThread())
            {
                writer.interrupt();
            }

            if(mCloseStreamOnClose || !mWriterStarted.get())
            {
                closeStream();
            }
        }

        private void closeStream()
        {
            try
            {
                mOutputStream.close();
            }
            catch(IOException exception)
            {
                //The browser already disconnected.
            }
        }
    }

    /** One browser document's logical subscriptions, all sampled by its single HTTP handler thread. */
    private final class MultiplexClient implements AutoCloseable
    {
        private static final int MAXIMUM_EVENTS_PER_PUMP = 16;
        private static final int MAXIMUM_RECOVERY_DISCARD = 512;
        private final String mClientId;
        private final HttpExchange mExchange;
        private final MultiplexClientLifecycle mLifecycle = new MultiplexClientLifecycle(Thread.currentThread());
        private final AtomicReference<MultiplexConfiguration> mRequested =
            new AtomicReference<>(new MultiplexConfiguration(0, Map.of()));
        private final Map<String,JsonNode> mActiveParameters = new LinkedHashMap<>();
        private final LiveTopicRetryPolicy mTopicRetryPolicy = new LiveTopicRetryPolicy();
        private final long[] mObservedOutputDrops = new long[TOPIC_MAXIMUM + 1];
        private Set<String> mUnauthorizedTopics = Set.of();
        private StatsLiveEventHub.Subscription mChannelActivity;
        private StatsLiveEventHub.Subscription mCalls;
        private Set<Long> mCallScanListIds = Set.of();
        private StatsLiveEventHub.Subscription mDecodeEvents;
        private DecodeMessageViewService.Session mDecodeMessages;
        private ChannelDiagnosticService.Session mChannelDiagnostics;
        private TunerDiagnosticService.Session mTunerDiagnostics;
        private String mDecodeMessageSubscriptionId;
        private String mChannelDiagnosticSubscriptionId;
        private long mDecodeMessageGeneration = -1;
        private long mChannelStateRevision = -1;
        private long mLastMessagePoll;
        private long mLastTunerStatePoll;
        private long mChannelActivityDrops;
        private long mCallDrops;
        private long mDecodeEventDrops;
        private long mDecodeEventIngressDrops;
        private long mDecodeMessageDrops;
        private boolean mMessagePermit;
        private boolean mChannelDiagnosticPermit;
        private boolean mTunerDiagnosticPermit;

        private MultiplexClient(String clientId, HttpExchange exchange)
        {
            mClientId = clientId;
            mExchange = exchange;
        }

        private void configure(MultiplexConfiguration configuration)
        {
            mRequested.updateAndGet(current -> configuration.revision() > current.revision() ? configuration : current);
        }

        private boolean isClosed()
        {
            return mLifecycle.isCloseRequested();
        }

        private void requestClose()
        {
            mLifecycle.requestClose();
        }

        private void awaitOwnerClose(long deadlineNanos)
        {
            mLifecycle.awaitOwnerClose(deadlineNanos);
        }

        private void refreshAuthorization()
        {
            java.util.HashSet<String> denied = new java.util.HashSet<>();

            for(String topic: MULTIPLEX_TOPICS)
            {
                if(!mWebRequestSecurity.isRequestStillAuthorized(mExchange, capabilityForTopic(topic)))
                {
                    denied.add(topic);
                }
            }

            mUnauthorizedTopics = Set.copyOf(denied);
        }

        private boolean pump(MultiplexOutput output) throws IOException, InterruptedException
        {
            boolean wrote = reconcile(output);
            wrote |= pumpEvents(output, TOPIC_CHANNEL_ACTIVITY, mChannelActivity);
            wrote |= pumpEvents(output, TOPIC_CALLS, mCalls);
            wrote |= pumpEvents(output, TOPIC_DECODE_EVENTS, mDecodeEvents);

            long now = System.nanoTime();

            if(mDecodeMessages != null && now - mLastMessagePoll >= TimeUnit.MILLISECONDS.toNanos(100))
            {
                mLastMessagePoll = now;
                DecodeMessageViewService.SourceState sourceState = mDecodeMessages.sourceState();

                if(sourceState.generation() != mDecodeMessageGeneration)
                {
                    mDecodeMessageGeneration = sourceState.generation();
                    writeMultiplexRecoveryJson(output, TOPIC_DECODE_MESSAGES, "source_change",
                        decodeMessageSourceState(sourceState, mDecodeMessageSubscriptionId));
                    wrote = true;
                }

                for(int count = 0; count < MAXIMUM_EVENTS_PER_PUMP; count++)
                {
                    DecodeMessageViewService.MessageView message = mDecodeMessages.poll(0, TimeUnit.NANOSECONDS);

                    if(message == null)
                    {
                        break;
                    }

                    writeMultiplexJson(output, TOPIC_DECODE_MESSAGES, "decode_message", message);
                    wrote = true;
                }
            }

            wrote |= reportStatelessGaps(output);
            wrote |= recoverMetadataGaps(output);

            if(mChannelDiagnostics != null)
            {
                ChannelDiagnosticService.State state = mChannelDiagnostics.refresh();

                if(state.revision() != mChannelStateRevision)
                {
                    writeMultiplexDiagnostic(output, TOPIC_CHANNEL_DIAGNOSTICS,
                        diagnosticState(state.generation(), state.revision(), state,
                            mChannelDiagnosticSubscriptionId));
                    mChannelStateRevision = state.revision();
                    wrote = true;
                }

                DiagnosticStreamFrame frame = mChannelDiagnostics.poll(Duration.ZERO);

                if(frame != null)
                {
                    writeMultiplexDiagnostic(output, TOPIC_CHANNEL_DIAGNOSTICS, frame);
                    wrote = true;
                }
            }

            if(mTunerDiagnostics != null)
            {
                if(now - mLastTunerStatePoll >= TimeUnit.SECONDS.toNanos(1))
                {
                    mLastTunerStatePoll = now;
                    TunerDiagnosticService.State state = mTunerDiagnostics.state();
                    writeMultiplexDiagnostic(output, TOPIC_TUNER_DIAGNOSTICS,
                        diagnosticState(state.generation(), state.revision(), state));
                    wrote = true;
                }

                DiagnosticStreamFrame frame = mTunerDiagnostics.poll(Duration.ZERO);

                if(frame != null)
                {
                    writeMultiplexDiagnostic(output, TOPIC_TUNER_DIAGNOSTICS, frame);
                    wrote = true;
                }
            }

            return wrote;
        }

        private boolean recoverMetadataGaps(MultiplexOutput output) throws IOException, InterruptedException
        {
            boolean wrote = false;

            if(mChannelActivity != null && metadataGap(output, TOPIC_CHANNEL_ACTIVITY,
                mChannelActivity.droppedCount(), mChannelActivityDrops))
            {
                discardSubscription(mChannelActivity);
                long dropBaseline = mChannelActivity.droppedCount();
                byte[] snapshot = mLiveService.encodedSnapshot();
                var recovery = new RecoveryCapture<>(dropBaseline, snapshot);
                mChannelActivityDrops = recovery.dropBaseline();
                writeMultiplexRecoveryJson(output, TOPIC_CHANNEL_ACTIVITY, "snapshot",
                    MULTIPLEX_OBJECT_MAPPER.readTree(recovery.snapshot()));
                observeOutputDrops(output, TOPIC_CHANNEL_ACTIVITY);
                wrote = true;
            }

            if(mCalls != null && metadataGap(output, TOPIC_CALLS, mCalls.droppedCount(), mCallDrops))
            {
                discardSubscription(mCalls);
                var recovery = captureRecovery(mCalls::droppedCount,
                    () -> mWebCallService.snapshot(mCallScanListIds));
                mCallDrops = recovery.dropBaseline();
                writeMultiplexRecoveryJson(output, TOPIC_CALLS, "snapshot",
                    callSnapshot(recovery.snapshot()));
                observeOutputDrops(output, TOPIC_CALLS);
                wrote = true;
            }

            return wrote;
        }

        /** Reports loss for disposable live viewers without replaying or clearing any successfully delivered items. */
        private boolean reportStatelessGaps(MultiplexOutput output) throws IOException
        {
            boolean wrote = false;

            if(mDecodeEvents != null)
            {
                long eventDrops = mDecodeEvents.droppedCount();
                long ingressDrops = mDecodeEventViewService != null ?
                    mDecodeEventViewService.getDroppedObservationCount() : mDecodeEventIngressDrops;
                long outputDrops = output.eventDrops(TOPIC_DECODE_EVENTS);
                long dropped = positiveDelta(eventDrops, mDecodeEventDrops) +
                    positiveDelta(ingressDrops, mDecodeEventIngressDrops) +
                    positiveDelta(outputDrops, mObservedOutputDrops[TOPIC_DECODE_EVENTS]);
                mDecodeEventDrops = eventDrops;
                mDecodeEventIngressDrops = ingressDrops;
                mObservedOutputDrops[TOPIC_DECODE_EVENTS] = outputDrops;

                if(dropped > 0)
                {
                    writeMultiplexJson(output, TOPIC_DECODE_EVENTS, "live_gap", Map.of("dropped", dropped));
                    wrote = true;
                }
            }

            if(mDecodeMessages != null)
            {
                long messageDrops = mDecodeMessages.droppedCount();
                long outputDrops = output.eventDrops(TOPIC_DECODE_MESSAGES);
                long dropped = positiveDelta(messageDrops, mDecodeMessageDrops) +
                    positiveDelta(outputDrops, mObservedOutputDrops[TOPIC_DECODE_MESSAGES]);
                mDecodeMessageDrops = messageDrops;
                mObservedOutputDrops[TOPIC_DECODE_MESSAGES] = outputDrops;

                if(dropped > 0)
                {
                    writeMultiplexJson(output, TOPIC_DECODE_MESSAGES, "live_gap", Map.of("dropped", dropped));
                    wrote = true;
                }
            }

            return wrote;
        }

        private long positiveDelta(long current, long previous)
        {
            return current > previous ? current - previous : 0;
        }

        private boolean metadataGap(MultiplexOutput output, int topic, long sourceDrops, long observedSourceDrops)
        {
            return output.eventDrops(topic) != mObservedOutputDrops[topic] || sourceDrops != observedSourceDrops;
        }

        private void observeOutputDrops(MultiplexOutput output, int topic)
        {
            mObservedOutputDrops[topic] = output.eventDrops(topic);
        }

        private void discardSubscription(StatsLiveEventHub.Subscription subscription) throws InterruptedException
        {
            for(int count = 0; count < MAXIMUM_RECOVERY_DISCARD; count++)
            {
                if(subscription.poll(0, TimeUnit.NANOSECONDS) == null)
                {
                    return;
                }
            }
        }

        private boolean reconcile(MultiplexOutput output) throws IOException
        {
            MultiplexConfiguration requested = mRequested.get();
            boolean wrote = false;
            long now = System.nanoTime();

            for(String topic: MULTIPLEX_TOPICS)
            {
                JsonNode wanted = mUnauthorizedTopics.contains(topic) ? null : requested.subscriptions().get(topic);
                JsonNode active = mActiveParameters.get(topic);

                if(Objects.equals(wanted, active))
                {
                    continue;
                }

                if(wanted != null && active == null && !mTopicRetryPolicy.canAttempt(topic, wanted, now))
                {
                    continue;
                }

                if("tuner_diagnostics".equals(topic) && wanted != null && active != null &&
                    mTunerDiagnostics != null && Objects.equals(wanted.path("target_id"), active.path("target_id")))
                {
                    try
                    {
                        TunerDiagnosticRequest request = tunerDiagnosticRequest(
                            multiplexSubscriptionUri(topic, wanted));
                        mTunerDiagnostics.updateConfiguration(request.viewport(), request.profile());
                        TunerDiagnosticService.State state = mTunerDiagnostics.state();
                        writeMultiplexDiagnostic(output, TOPIC_TUNER_DIAGNOSTICS,
                            diagnosticState(state.generation(), state.revision(), state));
                        mLastTunerStatePoll = System.nanoTime();
                        mActiveParameters.put(topic, wanted);
                        mTopicRetryPolicy.succeeded(topic);
                        wrote = true;
                        continue;
                    }
                    catch(RuntimeException exception)
                    {
                        //The target/session changed concurrently. Reopen through the normal bounded path below.
                    }
                }

                output.clearTopic(topicId(topic));
                closeTopic(topic);
                mActiveParameters.remove(topic);

                if(wanted != null)
                {
                    try
                    {
                        openTopic(topic, wanted, output);
                        mActiveParameters.put(topic, wanted);
                        mTopicRetryPolicy.succeeded(topic);
                    }
                    catch(RuntimeException exception)
                    {
                        closeTopic(topic);
                        mTopicRetryPolicy.failed(topic, wanted, now);
                        mLog.debug("Unable to open multiplex topic [{}] for client [{}]", topic, mClientId,
                            exception);
                        writeMultiplexJson(output, topicId(topic), "error",
                            Map.of("status", 503, "message", "The live subscription is temporarily unavailable"));
                    }
                    catch(IOException exception)
                    {
                        closeTopic(topic);
                        throw exception;
                    }

                    wrote = true;
                }
                else
                {
                    mTopicRetryPolicy.clear(topic);
                }
            }

            return wrote;
        }

        private void openTopic(String topic, JsonNode parameters, MultiplexOutput output) throws IOException
        {
            URI uri = multiplexSubscriptionUri(topic, parameters);

            switch(topic)
            {
                case "channel_activity" -> {
                    mChannelActivity = requiredSubscription(mLiveService.subscribeSystems(), topic);
                    long dropBaseline = mChannelActivity.droppedCount();
                    byte[] snapshot = mLiveService.encodedSnapshot();
                    var recovery = new RecoveryCapture<>(dropBaseline, snapshot);
                    mChannelActivityDrops = recovery.dropBaseline();
                    writeMultiplexRecoveryJson(output, TOPIC_CHANNEL_ACTIVITY, "snapshot",
                        MULTIPLEX_OBJECT_MAPPER.readTree(recovery.snapshot()));
                    observeOutputDrops(output, TOPIC_CHANNEL_ACTIVITY);
                }
                case "calls" -> {
                    mCallScanListIds = selectedScanListIds(uri);
                    mCalls = requiredSubscription(mWebCallService.subscribe(mCallScanListIds), topic);
                    var recovery = captureRecovery(mCalls::droppedCount,
                        () -> mWebCallService.snapshot(mCallScanListIds));
                    mCallDrops = recovery.dropBaseline();
                    writeMultiplexRecoveryJson(output, TOPIC_CALLS, "snapshot",
                        callSnapshot(recovery.snapshot()));
                    observeOutputDrops(output, TOPIC_CALLS);
                }
                case "decode_events" -> {
                    if(mDecodeEventViewService == null)
                    {
                        throw new IllegalStateException("Decode event viewer is unavailable");
                    }

                    DecodeEventRequest request = decodeEventRequest(uri);
                    DecodeEventViewService.Scope scope = request.scope();
                    long ingressDropBaseline = mDecodeEventViewService.getDroppedObservationCount();
                    AtomicLong liveEdge = new AtomicLong(Long.MAX_VALUE);

                    synchronized(mDecodeEventSubscriptionLock)
                    {
                        mDecodeEvents = mDecodeEventHub.subscribe(event ->
                            event.data() instanceof DecodeEventViewService.EventView view &&
                                view.observationEpoch() >= liveEdge.get() && scope.matches(view));

                        if(mDecodeEvents != null)
                        {
                            liveEdge.set(mDecodeEventViewService.advanceLiveEdge());
                            mDecodeEventViewService.addListener(mDecodeEventViewListener);
                        }
                    }

                    requiredSubscription(mDecodeEvents, topic);
                    mDecodeEventDrops = 0;
                    mDecodeEventIngressDrops = ingressDropBaseline;
                    FilterCatalog filterCatalog = DecodeEventViewService.filterCatalog();
                    writeMultiplexRecoveryJson(output, TOPIC_DECODE_EVENTS, "source_change",
                        new DecodeEventSourceState(scope.configurationId(), scope.frequencyHz(), scope.timeslot(),
                            request.subscriptionId(), filterCatalog));
                    observeOutputDrops(output, TOPIC_DECODE_EVENTS);
                }
                case "decode_messages" -> {
                    if(mDecodeMessageViewService == null || !mDecodeMessageClients.tryAcquire())
                    {
                        throw new IllegalStateException("Decode message viewer capacity is in use");
                    }

                    mMessagePermit = true;
                    DecodeMessageRequest request = decodeMessageRequest(uri);
                    mDecodeMessages = mDecodeMessageViewService.openSession(request.scope());
                    mDecodeMessageSubscriptionId = request.subscriptionId();
                    mDecodeMessageDrops = 0;
                    observeOutputDrops(output, TOPIC_DECODE_MESSAGES);
                    DecodeMessageViewService.SourceState sourceState = mDecodeMessages.sourceState();
                    mDecodeMessageGeneration = sourceState.generation();
                    writeMultiplexRecoveryJson(output, TOPIC_DECODE_MESSAGES, "source_change",
                        decodeMessageSourceState(sourceState, mDecodeMessageSubscriptionId));
                }
                case "channel_diagnostics" -> openChannelDiagnostics(uri, output);
                case "tuner_diagnostics" -> openTunerDiagnostics(uri, output);
                default -> throw new IllegalArgumentException("Unknown multiplex topic");
            }
        }

        private void openChannelDiagnostics(URI uri, MultiplexOutput output) throws IOException
        {
            if(mChannelDiagnosticService == null || !mDiagnosticClients.tryAcquire())
            {
                throw new IllegalStateException("Diagnostic viewer capacity is in use");
            }

            mChannelDiagnosticPermit = true;
            ChannelDiagnosticRequest request = channelDiagnosticRequest(uri);
            ChannelDiagnosticService.OpenResult result = mChannelDiagnosticService.tryOpen(request.scope());

            if(result.status() != ChannelDiagnosticService.OpenStatus.OPEN)
            {
                throw new IllegalStateException("Channel diagnostics are unavailable: " + result.status());
            }

            mChannelDiagnostics = result.session();
            mChannelDiagnosticSubscriptionId = request.subscriptionId();
            ChannelDiagnosticService.State state = mChannelDiagnostics.state();
            writeMultiplexDiagnostic(output, TOPIC_CHANNEL_DIAGNOSTICS,
                diagnosticState(state.generation(), state.revision(), state,
                    mChannelDiagnosticSubscriptionId));
            mChannelStateRevision = state.revision();
        }

        private void openTunerDiagnostics(URI uri, MultiplexOutput output) throws IOException
        {
            if(mTunerDiagnosticService == null || !mDiagnosticClients.tryAcquire())
            {
                throw new IllegalStateException("Diagnostic viewer capacity is in use");
            }

            mTunerDiagnosticPermit = true;
            TunerDiagnosticRequest request = tunerDiagnosticRequest(uri);
            TunerDiagnosticService.OpenResult result = mTunerDiagnosticService.tryOpen(request.targetId(),
                request.viewport(), request.profile());

            if(result.status() != TunerDiagnosticService.OpenStatus.OPEN)
            {
                throw new IllegalStateException("Tuner diagnostics are unavailable: " + result.status());
            }

            mTunerDiagnostics = result.session();
            TunerDiagnosticService.State state = mTunerDiagnostics.state();
            writeMultiplexDiagnostic(output, TOPIC_TUNER_DIAGNOSTICS,
                diagnosticState(state.generation(), state.revision(), state));
            mLastTunerStatePoll = System.nanoTime();
        }

        private StatsLiveEventHub.Subscription requiredSubscription(StatsLiveEventHub.Subscription subscription,
                                                                    String topic)
        {
            if(subscription == null)
            {
                throw new IllegalStateException(topic + " viewer capacity is in use");
            }

            return subscription;
        }

        private boolean pumpEvents(MultiplexOutput output, int topic,
                                   StatsLiveEventHub.Subscription subscription)
            throws IOException, InterruptedException
        {
            if(subscription == null)
            {
                return false;
            }

            boolean wrote = false;

            for(int count = 0; count < MAXIMUM_EVENTS_PER_PUMP; count++)
            {
                StatsLiveEventHub.LiveEvent event = subscription.poll(0, TimeUnit.NANOSECONDS);

                if(event == null)
                {
                    break;
                }

                writeMultiplexJson(output, topic, event.name(), event.data());
                wrote = true;
            }

            return wrote;
        }

        private void closeTopic(String topic)
        {
            switch(topic)
            {
                case "channel_activity" -> {
                    mChannelActivity = closeSubscription(mChannelActivity);
                    mChannelActivityDrops = 0;
                }
                case "calls" -> {
                    mCalls = closeSubscription(mCalls);
                    mCallScanListIds = Set.of();
                    mCallDrops = 0;
                }
                case "decode_events" -> closeDecodeEvents();
                case "decode_messages" -> closeDecodeMessages();
                case "channel_diagnostics" -> closeChannelDiagnostics();
                case "tuner_diagnostics" -> closeTunerDiagnostics();
                default -> { }
            }
        }

        private Map<String,Object> callSnapshot(List<Map<String,Object>> calls)
        {
            WebCallConfiguration configuration =
                mUserPreferences.getApplicationPreference().getWebCallConfiguration();
            Map<String,Object> snapshot = new LinkedHashMap<>();
            snapshot.put("calls", calls);
            snapshot.put("scan_list_ids", mCallScanListIds);
            snapshot.put("waiting_calls_per_listener", configuration.waitingCallsPerListener());
            return Map.copyOf(snapshot);
        }

        private StatsLiveEventHub.Subscription closeSubscription(StatsLiveEventHub.Subscription subscription)
        {
            if(subscription != null)
            {
                subscription.close();
            }

            return null;
        }

        private void closeDecodeEvents()
        {
            synchronized(mDecodeEventSubscriptionLock)
            {
                mDecodeEvents = closeSubscription(mDecodeEvents);

                if(mDecodeEventViewService != null && !mDecodeEventHub.hasSubscribers())
                {
                    mDecodeEventViewService.removeListener(mDecodeEventViewListener);
                }

                mDecodeEventDrops = 0;
                mDecodeEventIngressDrops = 0;
            }
        }

        private void closeDecodeMessages()
        {
            if(mDecodeMessages != null)
            {
                mDecodeMessages.close();
                mDecodeMessages = null;
            }

            if(mMessagePermit)
            {
                mMessagePermit = false;
                mDecodeMessageClients.release();
            }

            mDecodeMessageGeneration = -1;
            mDecodeMessageSubscriptionId = null;
            mDecodeMessageDrops = 0;
        }

        private void closeChannelDiagnostics()
        {
            if(mChannelDiagnostics != null)
            {
                mChannelDiagnostics.close();
                mChannelDiagnostics = null;
            }

            if(mChannelDiagnosticPermit)
            {
                mChannelDiagnosticPermit = false;
                mDiagnosticClients.release();
            }

            mChannelStateRevision = -1;
            mChannelDiagnosticSubscriptionId = null;
        }

        private void closeTunerDiagnostics()
        {
            if(mTunerDiagnostics != null)
            {
                mTunerDiagnostics.close();
                mTunerDiagnostics = null;
            }

            if(mTunerDiagnosticPermit)
            {
                mTunerDiagnosticPermit = false;
                mDiagnosticClients.release();
            }

        }

        @Override
        public void close()
        {
            mLifecycle.closeOnOwner(() -> {
                for(String topic: MULTIPLEX_TOPICS)
                {
                    closeTopic(topic);
                }

                mActiveParameters.clear();
                mTopicRetryPolicy.clear();
            });
        }
    }

    /**
     * Keeps external stop requests separate from logical-session cleanup. Only the multiplex handler thread may run
     * the cleanup callback, and it can run it once even when stop/reload races normal handler completion.
     */
    static final class MultiplexClientLifecycle
    {
        private final Thread mOwner;
        private final AtomicBoolean mCloseRequested = new AtomicBoolean();
        private final AtomicBoolean mOwnerClosed = new AtomicBoolean();
        private final CountDownLatch mOwnerCloseComplete = new CountDownLatch(1);

        MultiplexClientLifecycle(Thread owner)
        {
            mOwner = Objects.requireNonNull(owner, "Multiplex owner thread cannot be null");
        }

        boolean isCloseRequested()
        {
            return mCloseRequested.get();
        }

        void requestClose()
        {
            if(mCloseRequested.compareAndSet(false, true) && !mOwnerClosed.get())
            {
                mOwner.interrupt();
            }
        }

        void closeOnOwner(Runnable closeResources)
        {
            if(Thread.currentThread() != mOwner)
            {
                throw new IllegalStateException("Multiplex resources must be closed by their handler thread");
            }

            mCloseRequested.set(true);

            if(!mOwnerClosed.compareAndSet(false, true))
            {
                return;
            }

            try
            {
                closeResources.run();
            }
            finally
            {
                mOwnerCloseComplete.countDown();
            }
        }

        void awaitOwnerClose(long deadlineNanos)
        {
            if(Thread.currentThread() == mOwner || mOwnerCloseComplete.getCount() == 0)
            {
                return;
            }

            long remaining = deadlineNanos - System.nanoTime();

            if(remaining <= 0)
            {
                return;
            }

            try
            {
                mOwnerCloseComplete.await(remaining, TimeUnit.NANOSECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static DiagnosticStreamFrame diagnosticState(long generation, long revision, Object state)
        throws IOException
    {
        return DiagnosticStreamFrame.jsonState(generation, revision,
            ApiHttpResponse.encodePayload(StatsApiV1Payload.present(state)));
    }

    static DiagnosticStreamFrame diagnosticState(long generation, long revision, Object state,
                                                  String subscriptionId) throws IOException
    {
        JsonNode presented = StatsApiV1Payload.present(state);

        if(subscriptionId != null && presented instanceof ObjectNode object)
        {
            object.put("subscription_id", subscriptionId);
        }

        return DiagnosticStreamFrame.jsonState(generation, revision, ApiHttpResponse.encodePayload(presented));
    }

    static WebCapability capabilityForTopic(String topic)
    {
        return switch(topic)
        {
            case "calls" -> WebCapability.WEB_AUDIO_LISTEN;
            case "tuner_diagnostics" -> WebCapability.TUNER_SPECTRUM_VIEW;
            default -> WebCapability.LIVE_VIEW;
        };
    }

    private static int topicId(String topic)
    {
        return switch(topic)
        {
            case "channel_activity" -> TOPIC_CHANNEL_ACTIVITY;
            case "calls" -> TOPIC_CALLS;
            case "decode_events" -> TOPIC_DECODE_EVENTS;
            case "decode_messages" -> TOPIC_DECODE_MESSAGES;
            case "channel_diagnostics" -> TOPIC_CHANNEL_DIAGNOSTICS;
            case "tuner_diagnostics" -> TOPIC_TUNER_DIAGNOSTICS;
            default -> TOPIC_CONTROL;
        };
    }

    private record MultiplexConfiguration(long revision, Map<String,JsonNode> subscriptions)
    {
        private MultiplexConfiguration
        {
            subscriptions = Map.copyOf(subscriptions);
        }
    }

    private record DecodeEventSourceState(String configurationId, Long frequencyHz, Integer timeslot,
                                          String subscriptionId, FilterCatalog filterCatalog)
    {
    }

    private record DecodeEventRequest(DecodeEventViewService.Scope scope, String subscriptionId)
    {
    }

    private static DecodeMessageSourceState decodeMessageSourceState(DecodeMessageViewService.SourceState state,
                                                                      String subscriptionId)
    {
        return new DecodeMessageSourceState(state.generation(), state.bound(), state.configurationId(),
            state.frequencyHz(), subscriptionId, state.filterCatalog());
    }

    private record DecodeMessageRequest(DecodeMessageViewService.Scope scope, String subscriptionId)
    {
    }

    private record DecodeMessageSourceState(long generation, boolean bound, String configurationId,
                                            long frequencyHz, String subscriptionId, FilterCatalog filterCatalog)
    {
    }

    private record ChannelDiagnosticRequest(ChannelDiagnosticService.Scope scope, String subscriptionId)
    {
    }

    record RecoveryCapture<T>(long dropBaseline, T snapshot)
    {
    }

    private record TunerDiagnosticRequest(String targetId, TunerDiagnosticService.Viewport viewport,
                                          TunerDiagnosticService.SpectrumProfile profile)
    {
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

}
