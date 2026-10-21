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

import static io.github.dsheirer.stats.LiveMultiplexFrame.*;

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
import io.github.dsheirer.stats.WebListener.RequestedConfiguration;
import io.github.dsheirer.stats.WebListener.PreparedConfiguration;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.web.http.StreamingAdminHttpController;
import io.github.dsheirer.web.http.TopicAlertsHttpController;
import io.github.dsheirer.web.http.PagingToneHttpController;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.filter.FilterCatalog;
import io.github.dsheirer.message.DecodeMessageViewService;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.application.WebCertificateMode;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListModel;
import io.github.dsheirer.source.tuner.recording.RecordingTunerFileCatalog;
import io.github.dsheirer.stats.activity.ReceiverActivityStatus;
import io.github.dsheirer.support.WebDiagnosticLogService;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.TlsMaterialException;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.AliasAdminHttpController;
import io.github.dsheirer.web.http.ChannelAdminHttpController;
import io.github.dsheirer.web.http.SpectrumDiscoveryHttpController;
import io.github.dsheirer.web.http.SpectrumSearchHttpController;
import io.github.dsheirer.web.http.CallMatchingHttpController;
import io.github.dsheirer.web.http.TunerAllocationHttpController;
import io.github.dsheirer.web.http.MapSnapshotHttpController;
import io.github.dsheirer.web.http.OperationalPreferencesHttpController;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.http.ApiRequestDecoder;
import io.github.dsheirer.web.http.EmbeddedHttpServerPolicy;
import io.github.dsheirer.web.http.RadioReferenceHttpController;
import io.github.dsheirer.web.http.RemoteLinksHttpController;
import io.github.dsheirer.web.http.RecordingTunerHttpController;
import io.github.dsheirer.web.http.WebAccessPolicyHttpController;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.http.WebSessionHttpController;
import io.github.dsheirer.web.http.WebReceiverSettingsHttpController;
import io.github.dsheirer.web.http.P25BandplanOverrideHttpController;
import io.github.dsheirer.web.http.SpectrumSnapPresetHttpController;
import io.github.dsheirer.web.http.SupportReportHttpController;
import io.github.dsheirer.web.http.ApplicationLogHttpController;
import io.github.dsheirer.web.http.WebDiagnosticLogHttpController;
import io.github.dsheirer.web.http.WebDiagnosticHttpFilter;
import io.github.dsheirer.web.http.TunerAdminHttpController;
import io.github.dsheirer.web.http.WebUserAdminHttpController;
import io.github.dsheirer.web.http.WebUserPreferencesHttpController;
import io.github.dsheirer.web.http.StaticWebAssetResponse;
import io.github.dsheirer.web.auth.WebUserPreferencesService;
import io.github.dsheirer.web.settings.OperationalPreferencesService;
import io.github.dsheirer.web.network.WebCertificateIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded stats web server. Static assets are served only from an external filesystem folder.
 */
public class StatsWebServerService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(StatsWebServerService.class);
    private static final long AUTOMATIC_CERTIFICATE_INITIAL_CHECK_MINUTES = 1;
    private static final long AUTOMATIC_CERTIFICATE_MAINTENANCE_MINUTES = TimeUnit.HOURS.toMinutes(12);
    private static final int REQUEST_MAXIMUM_THREADS = 96;
    private static final int REQUEST_QUEUE_CAPACITY = 256;
    private static final int MAXIMUM_WEB_INDEX_BYTES = 64 * 1024;
    private static final int MAXIMUM_MULTIPLEX_CLIENTS = 32;
    private static final int MAXIMUM_MULTIPLEX_CONTROL_BYTES = 32 * 1024;
    private static final int MAXIMUM_MULTIPLEX_PARAMETER_VALUES = 64;
    private static final ObjectMapper MULTIPLEX_OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final Set<String> MULTIPLEX_TOPICS = Set.of("channel_activity", "decode_events",
        "decode_messages", "channel_diagnostics", "tuner_diagnostics", "frequency_audio", "saved_activity");
    static final Set<WebCapability> MULTIPLEX_CAPABILITIES = Set.of(WebCapability.LIVE_VIEW,
        WebCapability.TUNER_SPECTRUM_VIEW, WebCapability.WEB_AUDIO_LISTEN, WebCapability.RADIO_VIEW,
        WebCapability.DASHBOARD_VIEW);

    private final UserPreferences mUserPreferences;
    private final WebDiagnosticLogService mWebDiagnosticLogService;
    private final AtomicLong mDiagnosticConnectionIds = new AtomicLong();
    private final StatsLiveEventHub mDecodeEventHub = new StatsLiveEventHub(32, 256);
    private final Object mDecodeEventSubscriptionLock = new Object();
    private final Listener<DecodeEventViewService.EventView> mDecodeEventViewListener =
        event -> mDecodeEventHub.publish("decode_event", event);
    private final StaticWebAssetResponse mStaticWebAssetResponse = new StaticWebAssetResponse();
    private final Semaphore mDecodeMessageClients = new Semaphore(16);
    private final Semaphore mDiagnosticClients = new Semaphore(32);
    private final Semaphore mMultiplexClientPermits = new Semaphore(MAXIMUM_MULTIPLEX_CLIENTS);
    private final Map<String,MultiplexClient> mMultiplexClients = new ConcurrentHashMap<>();
    private final AtomicLong mMultiplexRejectedClients = new AtomicLong();
    private final AtomicLong mMultiplexSlowDisconnects = new AtomicLong();
    private final AtomicLong mMultiplexEventDrops = new AtomicLong();
    private final Path mWebAccessDatabasePath;
    private final WebTlsMaterialService mTlsMaterialService;
    private final ScheduledExecutorService mTlsMaintenanceExecutor;
    private volatile WebListener mListener;
    private volatile WebServerRuntimeState mRuntimeState = stoppedState("Web server is disabled.");
    private WebAccessService mWebAccessService;
    private WebAuthenticationService mWebAuthenticationService;
    private volatile WebRequestSecurity mWebRequestSecurity;
    private boolean mClosed;

    private final ReceiverWebServices mServices;
    private boolean mStarted;

    public StatsWebServerService(UserPreferences preferences, ReceiverWebServices services)
    {
        EmbeddedHttpServerPolicy.configureBeforeServerInitialization();
        mUserPreferences = Objects.requireNonNull(preferences);
        mServices = Objects.requireNonNull(services);
        mWebAccessDatabasePath = SdrTrunkDatabasePath.getDatabasePath(preferences);
        mTlsMaterialService = new WebTlsMaterialService(
            preferences.getDirectoryPreference().getDirectoryApplicationRoot());
        mTlsMaintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stats web TLS maintenance");
            thread.setDaemon(true);
            return thread;
        });
        mWebDiagnosticLogService = new WebDiagnosticLogService(
            () -> preferences.getDirectoryPreference().getDirectoryApplicationLog(),
            preferences.getApplicationPreference());
        services.mReceiverHealthService.setWebDiagnosticLogService(mWebDiagnosticLogService);
        services.mReceiverHealthService.setWebStatusSupplier(this::receiverHealthObserverStatus);
    }

    public synchronized void start()
    {
        ensureOpen();
        if(mStarted) return;
        mStarted = true;
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
            setRuntimeState(stoppedState("Web server is disabled."));
            return mRuntimeState;
        }

        boolean anyIpEnabled = preference.isStatsWebServerAnyIpEnabled();
        boolean httpsEnabled = anyIpEnabled || preference.isStatsWebServerHttpsEnabled();
        RequestedConfiguration requested = new RequestedConfiguration(StatsWebPath.getAssetsPath(),
            preference.getStatsWebServerPort(), anyIpEnabled, httpsEnabled);
        WebListener previous = mListener;

        if(!forceReload && previous != null && previous.configuration().requested().equals(requested))
        {
            return mRuntimeState;
        }

        PreparedConfiguration prepared;

        try
        {
            ensureAuthenticationServices();
            prepared = WebListener.prepare(mUserPreferences, mTlsMaterialService, requested);
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
            WebListener replacement = startListener(prepared);
            mListener = replacement;
            setRuntimeState(runningState(replacement, "Web server is running."));

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
            WebListener restored = null;

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
                WebListener.requiresRenewal(material, Instant.now()))
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

    private WebListener startListener(PreparedConfiguration configuration) throws IOException
    {
        ExecutorService executor = new ThreadPoolExecutor(REQUEST_MAXIMUM_THREADS, REQUEST_MAXIMUM_THREADS,
            60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(REQUEST_QUEUE_CAPACITY),
            new NamingThreadFactory("stats web server"), (task, pool) -> {
                mWebDiagnosticLogService.event("request_queue_full", Map.of());
                throw new RejectedExecutionException("Web request capacity is in use");
            });
        String webClientRevision = readWebClientRevision(configuration.requested().assetRoot());
        WebListener listener = WebListener.open(configuration, executor, server ->
            registerContexts(WebDiagnosticHttpFilter.instrumentContexts(server, mWebDiagnosticLogService),
                configuration.requested().assetRoot(), webClientRevision));
        mWebDiagnosticLogService.event("listener_started", Map.of());
        return listener;
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
        CallRelationsService callRelations = new CallRelationsService(mServices.mDatabase, () -> mServices.mManagedRecordingCatalog,
            mServices.mTopicAlertService, new ManagedRecordingLabels(mUserPreferences));
        CallRelationsHttpController relatedCalls = new CallRelationsHttpController(callRelations, mWebRequestSecurity);
        server.createContext(CallRelationsHttpController.PATH, relatedCalls::handle);
        if(mServices.mTopicAlertService != null)
        {
            TopicAlertsHttpController alerts = new TopicAlertsHttpController(mServices.mTopicAlertService,
                mServices.mPushDeliveryService, mServices::topicAlertOptions, mServices::reconcileTopicAlertObservation, mWebAccessService,
                mWebRequestSecurity, callRelations::resolve);
            server.createContext(TopicAlertsHttpController.ADMIN_PATH, mWebRequestSecurity.protectApi(
                WebCapability.ADMIN_SETTINGS, alerts::handleAdmin));
            server.createContext(TopicAlertsHttpController.PUBLIC_PATH, alerts::handlePublic);
        }
        new WebSessionHttpController(mWebAccessService, mWebAuthenticationService, mWebRequestSecurity)
            .register(server);
        WebUserAdminHttpController users =
            new WebUserAdminHttpController(mWebAccessService, mWebAuthenticationService);
        server.createContext(WebUserAdminHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_USERS, users::handle));
        WebAccessPolicyHttpController access = new WebAccessPolicyHttpController(mWebAccessService);
        server.createContext(WebAccessPolicyHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_ACCESS, access::handle));

        AliasAdminHttpController aliasController = new AliasAdminHttpController(mServices.mAliasAdministrationService,
            mServices.mDatabase::invalidateAliasActivitySnapshots, aliasListId ->
            {
                AliasTransferExport.Prepared prepared = mServices.mDatabase.aliasTransferExport(aliasListId);
                return new AliasAdminHttpController.AliasListExport(prepared.path(), prepared.fileName(),
                    prepared.rowCount(), prepared.byteCount(), prepared::close);
            });
        HttpHandler protectedAliases = mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_ALIASES, aliasController::handle);
        server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, protectedAliases);
        server.createContext(AliasAdminHttpController.ALIASES_PATH, protectedAliases);
        server.createContext(AliasAdminHttpController.SCAN_LISTS_PATH, protectedAliases);


        StreamingAdminHttpController streamingController = new StreamingAdminHttpController(mServices.mStreamingAdministrationService);
        server.createContext(StreamingAdminHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_STREAMING, streamingController::handle));

        if(mServices.mRemoteLinkAdministrationService != null)
        {
            RemoteLinksHttpController remoteLinksController =
                new RemoteLinksHttpController(mServices.mRemoteLinkAdministrationService);
            server.createContext(RemoteLinksHttpController.PATH, mWebRequestSecurity.protectApi(
                WebCapability.ADMIN_SETTINGS, remoteLinksController::handle));
        }
        ChannelAdminHttpController channelController =
            new ChannelAdminHttpController(mServices.mChannelAdministrationService, () -> mUserPreferences
                .getVoiceDecryptionModulePreference().getModuleManager().isLoaded());
        server.createContext(ChannelAdminHttpController.READ_PATH, mWebRequestSecurity.protectApi(
            WebCapability.RADIO_VIEW, channelController::handleCatalog));
        server.createContext(ChannelAdminHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_CHANNELS, channelController::handle));
        if(mServices.mSpectrumDiscoveryService != null)
            server.createContext(SpectrumDiscoveryHttpController.PATH, mWebRequestSecurity.protectApi(
                WebCapability.ADMIN_CHANNELS, protectDiscoveryAliasImports(new SpectrumDiscoveryHttpController(
                    mServices.mSpectrumDiscoveryService, mServices.mDatabase::enrichSystemNames)::handle)));
        if(mServices.mSpectrumSearchService != null)
            server.createContext(SpectrumSearchHttpController.PATH, mWebRequestSecurity.protectApi(
                WebCapability.ADMIN_CHANNELS, mWebRequestSecurity.protect(WebCapability.ADMIN_TUNERS,
                    protectDiscoveryAliasImports(new SpectrumSearchHttpController(mServices.mSpectrumSearchService,
                        mServices.mDatabase::enrichSystemNames)::handle))));


        RadioReferenceHttpController radioReferenceController = new RadioReferenceHttpController(
            mServices.mRadioReferenceDirectoryService, mUserPreferences.getRadioReferencePreference(),
            mServices.mRadioReferenceImportService);
        server.createContext(RadioReferenceHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, radioReferenceController::handle));

        WebReceiverSettingsHttpController receiverSettingsController =
            new WebReceiverSettingsHttpController(mServices.mWebReceiverSettingsService);
        server.createContext(WebReceiverSettingsHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, receiverSettingsController::handle));

        OperationalPreferencesHttpController operationalPreferencesController =
            new OperationalPreferencesHttpController(new OperationalPreferencesService(mUserPreferences));
        server.createContext(OperationalPreferencesHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, operationalPreferencesController::handle));

        PagingToneHttpController pagingToneController = new PagingToneHttpController(mServices.mPagingToneSettingsService);
        server.createContext(PagingToneHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, pagingToneController::handle));

        SpectrumSnapPresetHttpController spectrumSnapController =
            new SpectrumSnapPresetHttpController(mServices.mSpectrumSnapSettingsService);
        server.createContext(SpectrumSnapPresetHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.TUNER_SPECTRUM_VIEW, spectrumSnapController::handleRead));
        server.createContext(SpectrumSnapPresetHttpController.ADMIN_PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, spectrumSnapController::handleAdmin));

        P25BandplanOverrideHttpController bandplanOverrideController =
            new P25BandplanOverrideHttpController(mUserPreferences.getP25BandplanOverrideRegistry(),
                mServices.mDatabase::namedP25Overrides);
        server.createContext(P25BandplanOverrideHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, bandplanOverrideController::handle));

        SupportReportHttpController supportReportController =
            new SupportReportHttpController(mServices.mSupportBundleService);
        server.createContext(SupportReportHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, supportReportController::handle));

        ApplicationLogHttpController applicationLogController =
            new ApplicationLogHttpController(mServices.mApplicationLogService);
        server.createContext(ApplicationLogHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, applicationLogController::handle));

        WebDiagnosticLogHttpController webDiagnosticLogController =
            new WebDiagnosticLogHttpController(mWebDiagnosticLogService);
        server.createContext(WebDiagnosticLogHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, webDiagnosticLogController::handle));

        WebUserPreferencesHttpController userPreferencesController = new WebUserPreferencesHttpController(
            mWebRequestSecurity, new WebUserPreferencesService(mWebAccessDatabasePath));
        server.createContext(WebUserPreferencesHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.USER_SETTINGS, userPreferencesController::handle));

        CallMatchingHttpController callMatchingController = new CallMatchingHttpController(
            () -> mServices.mLogicalCallDiagnosticService, () -> mServices.mAudioCallCoordinator, mServices.mDatabase::enrichSystemNames);
        server.createContext(CallMatchingHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, callMatchingController::handle));

        TunerAllocationHttpController tunerAllocationController = new TunerAllocationHttpController(
            mServices.mTunerAllocationMonitorService::snapshot);
        server.createContext(TunerAllocationHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_SETTINGS, tunerAllocationController::handle));

        MapSnapshotHttpController mapController = new MapSnapshotHttpController(() -> mServices.mMapSnapshotService,
            mServices.mDatabase::enrichSystemNames);
        server.createContext(MapSnapshotHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.WEB_AUDIO_LISTEN, mapController::handle));

        ManagedRecordingsHttpController managedRecordings = new ManagedRecordingsHttpController(
            () -> mServices.mManagedRecordingCatalog, mUserPreferences, mWebRequestSecurity,
            mServices.mManagedRecordingMaintenance, () -> mServices.mManagedRecordingTranscriptionService);
        server.createContext(ManagedRecordingsHttpController.BROWSE_PATH, mWebRequestSecurity.protectApi(
            WebCapability.RECORDINGS_VIEW, managedRecordings::handleBrowse));
        server.createContext(ManagedRecordingsHttpController.ADMIN_PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_RECORDINGS, managedRecordings::handleAdmin));

        TunerAdminHttpController tunerController = new TunerAdminHttpController(
            mServices.mTunerAdministrationService, mServices.mTunerSettingsService, mServices.mTunerManager,
            mServices.mChannelProcessingManager != null ?
                mServices.mChannelProcessingManager::getActiveSourceFrequencies : List::of);
        server.createContext(TunerAdminHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_TUNERS, tunerController::handle));
        RecordingTunerHttpController recordingController = new RecordingTunerHttpController(
            new RecordingTunerFileCatalog(RecordingTunerFileCatalog.defaultDirectory()),
            mServices.mTunerManager::addRecordingTuner);
        server.createContext(RecordingTunerHttpController.PATH, mWebRequestSecurity.protectApi(
            WebCapability.ADMIN_TUNERS, recordingController::handle));


        new StatsApiV1Controller(mServices.mDatabase, this::status, mWebRequestSecurity, mServices.mTunerDiagnosticService,
            mServices.mReceiverHealthService::snapshot, mServices.mP25AssignmentService,
            mUserPreferences.getNowPlayingPreference()::getTrafficGrantAgeOutMilliseconds,
            mServices.mRemoteLinkAdministrationService,
            new ActivityRecordingAvailability(mServices.mDatabase, () -> mServices.mManagedRecordingCatalog))
            .register(server);
        RetainedStatisticsAdminHttpController retainedStatistics = new RetainedStatisticsAdminHttpController(
            mServices.mRetainedStatisticsCatalog, mServices.mRetainedStatisticsJobs);
        server.createContext(RetainedStatisticsAdminHttpController.PATH,
            mWebRequestSecurity.protectApi(WebCapability.ADMIN_SETTINGS, retainedStatistics::handle));
        server.createContext(StatsApiV1.LIVE_MULTIPLEX,
            mWebRequestSecurity.protectAny(MULTIPLEX_CAPABILITIES, this::handleLiveMultiplex));
        server.createContext(StatsApiV1.LIVE_MULTIPLEX_CONTROL,
            mWebRequestSecurity.protectAnyViewerControl(MULTIPLEX_CAPABILITIES,
                this::handleLiveMultiplexControl));
        createProtectedContext(server, StatsApiV1.SCAN_LISTS, WebCapability.WEB_AUDIO_LISTEN,
            this::handleScanLists);
        createProtectedContext(server, StatsApiV1.CALLS_FEED, WebCapability.WEB_AUDIO_LISTEN,
            this::handleWebCallFeed);
        createProtectedContext(server, StatsApiV1.CALLS + "/", WebCapability.WEB_AUDIO_LISTEN,
            this::handleWebCallAudio);
        server.createContext(StatsApiV1.ROOT, StatsWebServerService::handleApiNotFound);
        server.createContext("/api", StatsWebServerService::handleApiNotFound);
        server.createContext("/live", StatsWebServerService::handleApiNotFound);
        server.createContext("/", exchange -> handleStatic(exchange, assetRoot, webClientRevision));
    }

    private HttpHandler protectDiscoveryAliasImports(HttpHandler handler)
    { return protectDiscoveryAliasImports(mWebRequestSecurity, handler); }

    static HttpHandler protectDiscoveryAliasImports(WebRequestSecurity security, HttpHandler handler)
    {
        HttpHandler imports = security.protect(WebCapability.ADMIN_SETTINGS,
            security.protect(WebCapability.ADMIN_ALIASES, handler));
        return exchange -> {
            if(exchange.getRequestURI().getPath().endsWith("/aliases/import")) imports.handle(exchange);
            else handler.handle(exchange);
        };
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

    private void stopRuntimeServices()
    {
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

        synchronized(mDecodeEventSubscriptionLock)
        {
            mServices.mDecodeEventViewService.removeListener(mDecodeEventViewListener);
        }



        mServices.mChannelDiagnosticService.closeActiveSession();


        mServices.mFrequencyListenService.close();
        mServices.mTunerDiagnosticService.closeActiveSessions();



    }

    private void stopActiveListener()
    {
        mServices.mSpectrumSearchService.closeActiveSession();
        mServices.mSpectrumDiscoveryService.closeActiveSession();
        WebListener listener = mListener;
        mListener = null;

        if(listener != null)
        {
            stopListener(listener);
            mLog.info("Stats web server stopped");
        }

        stopRuntimeServices();
    }

    private void stopListener(WebListener listener)
    {
        listener.close();
        mWebDiagnosticLogService.event("listener_stopped", Map.of());
    }

    private static boolean bindingsConflict(WebListener previous, RequestedConfiguration requested)
    {
        int requestedPort = requested.port();
        return requestedPort != 0 && previous.configuration().requested().port() == requestedPort;
    }

    private WebServerRuntimeState recordReloadFailure(WebListener active, RequestedConfiguration requested,
                                                       Exception exception, boolean restorationAttempted)
    {
        mLog.warn("Unable to start or reload stats web server on requested port [{}]", requested.port(), exception);
        mWebDiagnosticLogService.event("listener_failed", Map.of());

        if(active != null)
        {
            setRuntimeState(runningState(active,
                "Web server reload failed; the previous listener remains active."));
        }
        else if(restorationAttempted)
        {
            setRuntimeState(stoppedState(
                "Web server reload failed and the previous listener could not be restored; check the application log."));
        }
        else
        {
            setRuntimeState(stoppedState("Web server failed to start; check the application log."));
        }

        return mRuntimeState;
    }

    private void setRuntimeState(WebServerRuntimeState state)
    {
        WebServerRuntimeState previous = mRuntimeState;
        mRuntimeState = state;

        if(!state.equals(previous))
        {
            MyEventBus.getGlobalEventBus().post(state);
        }
    }

    private static WebServerRuntimeState runningState(WebListener listener, String statusMessage)
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
        WebListener listener = mListener;
        Map<String,Object> server = new LinkedHashMap<>();
        server.put("enabled", runtimeState.running());
        server.put("port", runtimeState.port());
        server.put("https", runtimeState.https());
        server.put("access_mode", runtimeState.anyIpEnabled() ? "any_ip" : "local_only");
        server.put("certificate_fingerprint", runtimeState.certificateFingerprint());
        server.put("status_message", runtimeState.statusMessage());
        server.put("assets_available", listener != null &&
            Files.isRegularFile(listener.configuration().requested().assetRoot().resolve("index.html")));
        LinkedHashMap<String,Object> transport = new LinkedHashMap<>();
        transport.put("stream", StatsApiV1.LIVE_MULTIPLEX);
        transport.put("control", StatsApiV1.LIVE_MULTIPLEX_CONTROL);
        transport.put("maximum_clients", MAXIMUM_MULTIPLEX_CLIENTS);
        transport.put("active_clients", mMultiplexClients.size());
        transport.put("rejected_clients", mMultiplexRejectedClients.get());
        transport.put("slow_disconnects", mMultiplexSlowDisconnects.get());
        transport.put("event_drops", mMultiplexEventDrops.get());
        long activeDrops = 0;
        long writtenBytes = 0;
        long writtenFrames = 0;
        long pendingBytes = 0;
        for(MultiplexClient client: mMultiplexClients.values())
        {
            LiveMultiplexOutput output = client.mOutput;
            if(output != null)
            {
                activeDrops += output.eventDrops();
                writtenBytes += output.writtenBytes();
                writtenFrames += output.writtenFrames();
                for(int topic = 0; topic <= TOPIC_MAXIMUM; topic++) pendingBytes += output.pendingEventBytes(topic);
            }
        }
        transport.put("active_event_drops", activeDrops);
        transport.put("active_written_bytes", writtenBytes);
        transport.put("active_written_frames", writtenFrames);
        transport.put("pending_event_bytes", pendingBytes);
        transport.put("shared_frame_encodes", LiveMultiplexFrame.encodingCount());
        transport.put("shared_frame_compressions", LiveMultiplexFrame.compressionCount());
        transport.put("activity_projection_drops", mServices.mLiveService.droppedProjectionEvents());
        transport.put("activity_projection_failures", mServices.mLiveService.projectionFailures());
        transport.put("activity_ingress_drops", mServices.mLiveService.droppedActivityIngressEvents());
        server.put("live_transport", Map.copyOf(transport));
        status.put("server", server);
        status.put("database", mServices.mDatabase.status());
        status.put("stats_logging", statsLoggingStatusResponse());
        return status;
    }

    /**
     * Internal-only observer measurements for the administrator health sampler.  These are intentionally not added to
     * the public status endpoint.
     */
    private Map<String,Object> receiverHealthObserverStatus()
    {
        mServices.mWebCallService.maintain();
        Map<String,Object> transport = Map.of(
            "active_clients", mMultiplexClients.size(),
            "rejected_clients", mMultiplexRejectedClients.get(),
            "slow_disconnects", mMultiplexSlowDisconnects.get(),
            "event_drops", mMultiplexEventDrops.get());
        return Map.of(
            "server", Map.of("live_transport", transport),
            "web_player", mServices.mWebCallService.observerStatus(),
            "diagnostics", Map.of(
            "channel_sessions", mServices.mChannelDiagnosticService.activeSessionCount(),
            "channel_producers", mServices.mChannelDiagnosticService.activeProducerCount(),
            "tuner_sessions", mServices.mTunerDiagnosticService.activeSessionCount(),
            "tuner_producers", mServices.mTunerDiagnosticService.activeProducerCount()));
    }

    private Map<String,Object> statsLoggingStatusResponse()
    {
        ReceiverActivityStatus current = statsLoggingStatus();
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("summary_configured", current.summaryConfigured());
        response.put("detailed_history_configured", current.detailedHistoryConfigured());
        response.put("summary_active", current.summaryActive());
        response.put("detailed_history_active", current.detailedHistoryActive());
        response.put("retention_days", current.retentionDays());
        response.put("state", current.state());
        response.put("last_successful_write_ms", current.lastSuccessfulWriteMs());
        response.put("records_written", current.recordsWritten());
        response.put("records_dropped", current.recordsDropped());
        var drops = current.dropDiagnostics();
        response.put("drop_causes", Map.of("writer_queue_overflow", drops.writerQueueOverflow(),
            "constraint_rejection", drops.constraintRejection(),
            "observation_queue_overflow", drops.observationQueueOverflow(), "unclassified", drops.unclassified()));
        response.put("writer_dropped_record_categories", drops.writerRecordCategories());
        response.put("last_drop_ms", Map.of("writer_queue_overflow", drops.lastWriterQueueOverflowMs(),
            "constraint_rejection", drops.lastConstraintRejectionMs(),
            "observation_queue_overflow", drops.lastObservationQueueOverflowMs()));
        response.put("last_error", current.lastError() == null || current.lastError().isBlank() ? "" :
            "Statistics logging failed; check the application log.");
        return response;
    }

    private ReceiverActivityStatus statsLoggingStatus()
    {
        return mServices.mActivityLogService.getStatus();
    }

    /**
     * Current state for desktop controls that open embedded-web pages.
     */
    public synchronized StatsWebNavigationState getNavigationState()
    {
        ReceiverActivityStatus loggingStatus = statsLoggingStatus();
        WebServerRuntimeState runtimeState = mRuntimeState;
        return new StatsWebNavigationState(runtimeState.running(), runtimeState.port(), runtimeState.https(),
            loggingStatus.summaryActive());
    }

    /** Arms the same local sign-in with a fixed destination selected by the application. */
    public synchronized URI createDesktopAdministratorHandoffUri(
        WebAuthenticationService.DesktopHandoffDestination destination)
    {
        StatsWebNavigationState navigation = getNavigationState();

        if(!navigation.running() || mWebAuthenticationService == null ||
            !mWebAuthenticationService.armDesktopAdministratorHandoff(destination))
        {
            return null;
        }

        return navigation.baseUri().resolve(WebSessionHttpController.DESKTOP_HANDOFF_PATH);
    }

    public synchronized void cancelDesktopAdministratorHandoff()
    {
        if(mWebAuthenticationService != null)
        {
            mWebAuthenticationService.cancelDesktopAdministratorHandoff();
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
        boolean gzip;

        try
        {
            StatsRequest request = StatsRequest.from(exchange.getRequestURI());
            clientId = UUID.fromString(request.requiredText("client_id")).toString();
            String compression = request.text("compression");
            if(compression != null && !"gzip".equals(compression))
            {
                throw new IllegalArgumentException("compression is invalid");
            }
            gzip = "gzip".equals(compression);
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
                mWebDiagnosticLogService.event("live_disconnected", Map.of("client_id", client.mDiagnosticId));
            }
        };
        LiveMultiplexOutput output = null;

        try(client)
        {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/vnd.sdrtrunk.live+binary");
            headers.set("Cache-Control", "no-store, no-transform");
            headers.set("Connection", "keep-alive");
            headers.set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);
            mWebDiagnosticLogService.event("live_connected", Map.of("client_id", client.mDiagnosticId));
            HttpServer server = exchange.getHttpContext().getServer();
            LiveMultiplexOutput connectedOutput = new LiveMultiplexOutput(exchange.getResponseBody(), Duration.ofSeconds(8),
                false, () -> {}, releaseConnection, gzip);
            output = connectedOutput;
            client.mOutput = connectedOutput;

            try(connectedOutput)
            {
                connectedOutput.start();
                writeMultiplexJson(connectedOutput, TOPIC_CONTROL, "ready", Map.of("client_id", clientId));
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
                        mWebDiagnosticLogService.event("live_slow_disconnect", Map.of("client_id", client.mDiagnosticId));
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

            Map<String,LiveSubscription> requested = new LinkedHashMap<>();
            java.util.Iterator<String> names = subscriptions.fieldNames();

            while(names.hasNext())
            {
                String name = names.next();
                JsonNode parameters = subscriptions.get(name);

                if(!MULTIPLEX_TOPICS.contains(name) || parameters == null || !parameters.isObject())
                {
                    throw new IllegalArgumentException("The viewer subscription is invalid");
                }

                requested.put(name, parseMultiplexSubscription(name, parameters));
            }

            MultiplexClient client = mMultiplexClients.get(clientId);

            if(client == null || client.isClosed())
            {
                sendApiError(exchange, 404, "client_not_found", "The live browser connection is not available");
                return;
            }

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

    static LiveSubscription parseMultiplexSubscription(String name, JsonNode parameters)
    {
        StatsRequest request = multiplexParameters(parameters);
        return switch(name)
        {
            case "channel_activity" -> {
                if(parameters.size() > 3) throw new StatsApiException(400, "Channel activity options are invalid");
                parameters.fields().forEachRemaining(field -> {
                    if("subscription_id".equals(field.getKey()))
                        UUID.fromString(requiredMultiplexText(parameters, "subscription_id"));
                    else if(!Set.of("delta", "markers").contains(field.getKey()) || !field.getValue().isBoolean())
                        throw new StatsApiException(400, "Channel activity options are invalid");
                });
                yield new ActivitySubscription(parameters.path("delta").asBoolean(false),
                    parameters.path("markers").asBoolean(false), parameters.has("subscription_id") ?
                    requiredMultiplexText(parameters, "subscription_id") : null);
            }
            case "decode_events" -> decodeEventRequest(request, false);
            case "decode_messages" -> decodeMessageRequest(request);
            case "channel_diagnostics" -> channelDiagnosticRequest(request);
            case "tuner_diagnostics" -> tunerDiagnosticRequest(request);
            case "frequency_audio" -> frequencyListenRequest(request);
            case "saved_activity" -> {
                if(parameters.size() != 1 || !parameters.has("subscription_id"))
                    throw new StatsApiException(400, "Saved activity options are invalid");
                String id = requiredMultiplexText(parameters, "subscription_id");
                UUID.fromString(id);
                yield new SavedActivitySubscription(id);
            }
            default -> throw new StatsApiException(400, "Unknown live subscription");
        };
    }

    /** Applies the existing scalar/count/length bounds without fabricating and reparsing a URL. */
    private static StatsRequest multiplexParameters(JsonNode parameters)
    {
        if(parameters == null || !parameters.isObject() || parameters.size() > 16)
            throw new StatsApiException(400, "Too many live subscription parameters");
        Map<String,String> values = new LinkedHashMap<>();
        int count = 0;
        int encodedLength = 0;
        var fields = parameters.fields();
        while(fields.hasNext())
        {
            var field = fields.next();
            String name = field.getKey();
            JsonNode node = field.getValue();
            if(name.isBlank() || name.length() > StatsRequest.MAX_PARAMETER_NAME_LENGTH)
                throw new StatsApiException(400, "Live subscription parameter name is invalid");
            Iterable<JsonNode> items = node.isArray() ? node : List.of(node);
            for(JsonNode item: items)
            {
                if(item == null || (!item.isTextual() && !item.isNumber() && !item.isBoolean()) ||
                    ++count > MAXIMUM_MULTIPLEX_PARAMETER_VALUES)
                    throw new StatsApiException(400, "Live subscription parameters must contain bounded scalar values");
                String value = item.asText();
                if(value.length() > StatsRequest.MAX_PARAMETER_VALUE_LENGTH ||
                    values.putIfAbsent(name, value) != null || values.size() > StatsRequest.MAX_PARAMETER_COUNT)
                    throw new StatsApiException(400, "Live subscription parameters are invalid");
                encodedLength += (encodedLength > 0 ? 1 : 0) +
                    URLEncoder.encode(name, StandardCharsets.UTF_8).length() + 1 +
                    URLEncoder.encode(value, StandardCharsets.UTF_8).length();
                if(encodedLength > StatsRequest.MAX_QUERY_LENGTH)
                    throw new StatsApiException(400, "Live subscription parameters are too long");
            }
        }
        return new StatsRequest(values);
    }

    sealed interface LiveSubscription permits ActivitySubscription, SavedActivitySubscription,
        DecodeEventRequest, DecodeMessageRequest, ChannelDiagnosticRequest, TunerDiagnosticRequest,
        FrequencyListenRequest { }
    private record ActivitySubscription(boolean delta, boolean markers, String subscriptionId)
        implements LiveSubscription { }
    private record SavedActivitySubscription(String subscriptionId) implements LiveSubscription { }

    private static TunerDiagnosticRequest tunerDiagnosticRequest(StatsRequest request)
    {
        String targetId = request.requiredText("target_id");
        Long viewportStart = request.optionalLong("viewport_start_hz");
        Long viewportEnd = request.optionalLong("viewport_end_hz");
        String profileId = request.text("profile");
        boolean compactStrength = compactStrength(request.text("strength_encoding"));

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
        return new TunerDiagnosticRequest(targetId, viewport, profile, compactStrength);
    }

    private static FrequencyListenRequest frequencyListenRequest(StatsRequest request)
    {
        String targetId = request.requiredText("target_id");
        Long frequencyHz = request.optionalLong("frequency_hz");
        Long bandwidthHz = request.optionalLong("bandwidth_hz");
        request.requireFullyConsumed();

        if(frequencyHz == null || frequencyHz <= 0 || bandwidthHz == null ||
            (bandwidthHz != 6250 && bandwidthHz != 12500 && bandwidthHz != 20000 && bandwidthHz != 25000))
        {
            throw new StatsApiException(400, "Frequency or NBFM bandwidth is invalid");
        }

        return new FrequencyListenRequest(targetId, frequencyHz, bandwidthHz.intValue());
    }

    private record FrequencyListenRequest(String targetId, long frequencyHz, int bandwidthHz) implements LiveSubscription
    {
    }

    private static void writeMultiplexJson(LiveMultiplexOutput output, int topic, String event, Object data)
        throws IOException
    {
        output.offerEvent(topic, LiveMultiplexFrame.json(topic, event, data).bytes(output.gzipEnabled()));
    }

    private static void writeMultiplexRecoveryJson(LiveMultiplexOutput output, int topic, String event, Object data)
        throws IOException
    {
        output.offerRecovery(topic, LiveMultiplexFrame.json(topic, event, data).bytes(output.gzipEnabled()));
    }

    /** Samples a bounded message batch and admits each row only behind its matching source-change boundary. */
    static DecodeMessagePumpResult pumpDecodeMessages(LiveMultiplexOutput output,
                                                       Supplier<DecodeMessageViewService.SourceState> source,
                                                       DecodeMessagePoller messages,
                                                       Predicate<DecodeMessageViewService.MessageView> admission,
                                                       int maximumMessages,
                                                       long currentGeneration, String subscriptionId)
        throws IOException, InterruptedException
    {
        boolean wrote = false;
        DecodeMessageViewService.SourceState state = source.get();

        if(state.generation() != currentGeneration)
        {
            currentGeneration = state.generation();
            writeMultiplexRecoveryJson(output, TOPIC_DECODE_MESSAGES, "source_change",
                decodeMessageSourceState(state, subscriptionId));
            wrote = true;
        }

        for(int count = 0; count < maximumMessages; count++)
        {
            DecodeMessageViewService.MessageView message = messages.poll();

            if(message == null)
            {
                break;
            }

            // A source can stop, retune, or join the aggregate while poll() is returning a previously admitted row.
            state = source.get();

            if(state.generation() != currentGeneration)
            {
                currentGeneration = state.generation();
                writeMultiplexRecoveryJson(output, TOPIC_DECODE_MESSAGES, "source_change",
                    decodeMessageSourceState(state, subscriptionId));
                wrote = true;
            }

            if(message.sourceGeneration() == state.generation() && state.bound() && admission.test(message))
            {
                writeMultiplexJson(output, TOPIC_DECODE_MESSAGES, "decode_message", message);
                wrote = true;
            }
        }

        return new DecodeMessagePumpResult(currentGeneration, wrote);
    }

    @FunctionalInterface
    interface DecodeMessagePoller
    {
        DecodeMessageViewService.MessageView poll() throws InterruptedException;
    }

    record DecodeMessagePumpResult(long generation, boolean wrote)
    {
    }

    private static void writeMultiplexDiagnostic(LiveMultiplexOutput output, int topic, DiagnosticStreamFrame frame)
        throws IOException
    {
        byte[] encoded = frame.multiplexFrame(topic).bytes(output.gzipEnabled());

        if(frame.type() == DiagnosticStreamFrame.TYPE_STATE)
        {
            output.offerState(topic, encoded);
        }
        else
        {
            output.offerLatest(topic, frame.type(), encoded);
        }
    }

    private static DecodeEventRequest decodeEventRequest(StatsRequest request, boolean diagnostics)
    {
        if(conventionalDecodeScope(request))
        {
            if(diagnostics)
            {
                throw new StatsApiException(400, "invalid_parameter", "scope is not supported for channel diagnostics",
                    "scope");
            }

            request.requireOnly("scope", "subscription_id");
            String subscriptionId = decodeSubscriptionId(request);
            request.requireFullyConsumed();
            return new DecodeEventRequest(DecodeEventViewService.Scope.conventional(), subscriptionId, false);
        }

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
        String subscriptionId = decodeSubscriptionId(request);
        boolean compactStrength = diagnostics && compactStrength(request.text("strength_encoding"));

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

        request.requireFullyConsumed();
        return new DecodeEventRequest(new DecodeEventViewService.Scope(configurationId, frequency, timeslot),
            subscriptionId, compactStrength);
    }


    private static DecodeMessageRequest decodeMessageRequest(StatsRequest request)
    {
        if(conventionalDecodeScope(request))
        {
            request.requireOnly("scope", "subscription_id");
            String subscriptionId = decodeSubscriptionId(request);
            request.requireFullyConsumed();
            return new DecodeMessageRequest(DecodeMessageViewService.Scope.conventional(), subscriptionId);
        }

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
        String subscriptionId = decodeSubscriptionId(request);

        if(frequency == null)
        {
            throw new StatsApiException(400, "frequency_hz is required");
        }

        if(frequency <= 0)
        {
            throw new StatsApiException(400, "invalid_parameter", "frequency_hz must be positive",
                "frequency_hz");
        }

        request.requireFullyConsumed();
        return new DecodeMessageRequest(new DecodeMessageViewService.Scope(configurationId, frequency),
            subscriptionId);
    }

    private static boolean conventionalDecodeScope(StatsRequest request)
    {
        String scope = request.text("scope");

        if(scope == null)
        {
            return false;
        }

        if(!"conventional".equals(scope))
        {
            throw new StatsApiException(400, "invalid_parameter", "scope must be conventional", "scope");
        }

        return true;
    }

    private static String decodeSubscriptionId(StatsRequest request)
    {
        String subscriptionId = request.text("subscription_id");

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

        return subscriptionId;
    }

    private static ChannelDiagnosticRequest channelDiagnosticRequest(StatsRequest request)
    {
        DecodeEventRequest selected = decodeEventRequest(request, true);

        if(selected.scope().frequencyHz() == null)
        {
            throw new StatsApiException(400, "frequency_hz is required");
        }

        return new ChannelDiagnosticRequest(new ChannelDiagnosticService.Scope(selected.scope().configurationId(),
            selected.scope().frequencyHz(), selected.scope().timeslot()), selected.subscriptionId(),
            selected.compactStrength());
    }

    private static boolean compactStrength(String value)
    {
        if(value == null || "legacy".equals(value)) return false;
        if("packed6".equals(value)) return true;
        throw new StatsApiException(400, "strength_encoding is invalid");
    }

    private void handleScanLists(HttpExchange exchange) throws IOException
    {
        if(!requireMethod(exchange, "GET") || !requireNoQuery(exchange))
        {
            return;
        }

        if(hasExactPath(exchange.getRequestURI(), StatsApiV1.SCAN_LISTS))
        {
            sendScanLists(exchange);
            return;
        }

        Long coverageId = scanListCoverageId(exchange.getRequestURI());

        if(coverageId == null)
        {
            sendApiError(exchange, 404, "not_found", "Resource not found");
            return;
        }

        try
        {
            AliasAdministrationService.ScanListCoverage coverage =
                mServices.mAliasAdministrationService.scanListCoverage(coverageId);

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
        List<Map<String,Object>> rows = mServices.mScanListModel.configuration().scanLists().stream()
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
        ApiHttpResponse.sendData(exchange, 200, Map.of("scan_lists", rows,
            "maximum_selected_scan_lists", StatsWebCallService.MAXIMUM_SELECTED_SCAN_LISTS));
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

    private CallFeedRequest callFeedRequest(URI uri)
    {
        return parseCallFeedRequest(uri, mServices.mScanListModel, StatsWebCallService.MAXIMUM_SELECTED_SCAN_LISTS);
    }

    static CallFeedRequest parseCallFeedRequest(URI uri, ScanListModel scanListModel, int maximum)
    {
        if(scanListModel == null)
        {
            throw new StatsApiException(503, "service_unavailable", "Scan lists are unavailable");
        }

        String query = uri != null ? uri.getRawQuery() : null;
        Set<Long> selected = new LinkedHashSet<>();
        Long cursor = null;

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

                if("cursor".equals(name))
                {
                    if(cursor != null || !value.matches("0|[1-9][0-9]*"))
                    {
                        throw new StatsApiException(400, "invalid_parameter",
                            "cursor must be one non-negative decimal integer", "cursor");
                    }

                    try
                    {
                        cursor = Long.parseLong(value);
                    }
                    catch(NumberFormatException exception)
                    {
                        throw new StatsApiException(400, "invalid_parameter",
                            "cursor must be a non-negative decimal integer", "cursor");
                    }

                    continue;
                }

                if(!"scan_list_id".equals(name) || !value.matches("[1-9][0-9]*"))
                {
                    throw new StatsApiException(400, "invalid_parameter",
                        "Only scan_list_id and cursor parameters are allowed",
                        "scan_list_id");
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

        return new CallFeedRequest(Set.copyOf(selected), cursor);
    }

    record CallFeedRequest(Set<Long> scanListIds, Long cursor)
    {
    }

    private void handleWebCallFeed(HttpExchange exchange) throws IOException
    {
        if(!requireExactTextPath(exchange, StatsApiV1.CALLS_FEED) || !requireMethod(exchange, "GET"))
        {
            return;
        }

        CallFeedRequest request;

        try
        {
            request = callFeedRequest(exchange.getRequestURI());
        }
        catch(StatsApiException exception)
        {
            sendApiException(exchange, exception);
            return;
        }

        long feedGeneration = mServices.mWebCallService.tryAcquireFeed();

        if(feedGeneration == StatsWebCallService.NO_FEED_GENERATION)
        {
            if(mServices.mWebCallService.isRunning())
            {
                sendApiError(exchange, 429, "too_many_call_feeds", "Too many call-feed requests are active");
            }
            else
            {
                sendApiError(exchange, 503, "service_unavailable", "The call feed is unavailable");
            }
            return;
        }

        try
        {
            StatsWebCallService.FeedResult result = mServices.mWebCallService.feed(request.scanListIds(), request.cursor(),
                StatsWebCallService.FEED_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS, feedGeneration);
            ApiHttpResponse.sendData(exchange, 200, mServices.mDatabase.enrichSystemNames(callFeedResponse(result)));
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            sendApiError(exchange, 503, "service_unavailable", "The call feed was interrupted");
        }
        finally
        {
            mServices.mWebCallService.releaseFeed(feedGeneration);
        }
    }

    static Map<String,Object> callFeedResponse(StatsWebCallService.FeedResult result)
    {
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("cursor", result.cursor());
        response.put("reset", result.reset());
        response.put("calls", result.calls());
        return response;
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

        if(!mServices.mWebCallService.tryAcquireAudioResponse())
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
            mServices.mWebCallService.releaseAudioResponse();
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

        StatsWebCallService.CachedCall call = mServices.mWebCallService.get(id);

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
            mStaticWebAssetResponse.invalidate(file);
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

        if(file.equals(root.resolve("index.html").normalize()))
        {
            mStaticWebAssetResponse.sendIndex(exchange, file, contentType(file), webClientRevision);
        }
        else
        {
            mStaticWebAssetResponse.send(exchange, file, contentType(file), webClientRevision);
        }
    }

    private void sendMissingAssetsPage(HttpExchange exchange, Path root) throws IOException
    {
        sendHtml(exchange, 200, """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>VCE</title>
              <style>body{font-family:Arial,sans-serif;margin:2rem;line-height:1.4}</style>
            </head>
            <body>
              <h1>VCE</h1>
              <p>No web assets were found in:</p>
              <pre>%s</pre>
            </body>
            </html>
            """.formatted(root));
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

    static String contentType(Path file)
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
        else if(name.endsWith(".webmanifest"))
        {
            return "application/manifest+json";
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
        if(mClosed) return;
        mClosed = true;
        mTlsMaintenanceExecutor.shutdownNow();
        if(mStarted) MyEventBus.getGlobalEventBus().unregister(this);
        stopActiveListener();
        mWebDiagnosticLogService.close();
        mDecodeEventHub.close();
        setRuntimeState(stoppedState("Web server is stopped."));
        if(mWebRequestSecurity != null)
        {
            mWebRequestSecurity.close();
            mWebRequestSecurity = null;
            mWebAuthenticationService = null;
            mWebAccessService = null;
        }
    }

    private final class MultiplexClient implements AutoCloseable
    {
        private static final int MAXIMUM_EVENTS_PER_PUMP = 16;
        private static final int MAXIMUM_AGGREGATE_MESSAGES_PER_PUMP = 64;
        private static final int MAXIMUM_RECOVERY_DISCARD = 512;
        private final String mClientId;
        private final long mDiagnosticId = mDiagnosticConnectionIds.incrementAndGet();
        private volatile LiveMultiplexOutput mOutput;
        private final HttpExchange mExchange;
        private final MultiplexClientLifecycle mLifecycle = new MultiplexClientLifecycle(Thread.currentThread());
        private final AtomicReference<MultiplexConfiguration> mRequested =
            new AtomicReference<>(new MultiplexConfiguration(0, Map.of()));
        private final Map<String,LiveSubscription> mActiveParameters = new LinkedHashMap<>();
        private final LiveTopicRetryPolicy mTopicRetryPolicy = new LiveTopicRetryPolicy();
        private final long[] mObservedOutputDrops = new long[TOPIC_MAXIMUM + 1];
        private Set<String> mUnauthorizedTopics = Set.of();
        private StatsLiveEventHub.Subscription mChannelActivity;
        private StatsLiveEventHub.Subscription mDecodeEvents;
        private StatsLiveEventHub.Subscription mSavedActivity;
        private String mSavedActivitySubscriptionId;
        private long mSavedActivityStateRevision = -1;
        private long mSavedActivityDrops;
        private DecodeEventViewService.Scope mDecodeEventScope;
        private DecodeMessageViewService.Session mDecodeMessages;
        private ChannelDiagnosticService.Session mChannelDiagnostics;
        private TunerDiagnosticService.Session mTunerDiagnostics;
        private FrequencyListenService.Session mFrequencyAudio;
        private String mDecodeMessageSubscriptionId;
        private String mChannelDiagnosticSubscriptionId;
        private long mDecodeMessageGeneration = -1;
        private long mChannelStateRevision = -1;
        private long mLastMessagePoll;
        private long mLastTunerStatePoll;
        private long mChannelActivityDrops;
        private long mChannelActivityIngressDrops;
        private long mChannelActivityBaselineRevision;
        private boolean mChannelActivityMarkers;
        private final Set<String> mChannelActivityTableBaselines = new LinkedHashSet<>();
        private long mDecodeEventDrops;
        private long mDecodeEventIngressDrops;
        private long mDecodeMessageDrops;
        private boolean mMessagePermit;
        private boolean mChannelDiagnosticPermit;
        private boolean mTunerDiagnosticPermit;
        private volatile boolean mChannelAudioAllowed;

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
                if(capabilitiesForTopic(topic).stream().noneMatch(capability ->
                    mWebRequestSecurity.isRequestStillAuthorized(mExchange, capability)))
                {
                    denied.add(topic);
                }
                else if("frequency_audio".equals(topic) && !mWebRequestSecurity.isRequestStillAuthorized(mExchange,
                    WebCapability.TUNER_SPECTRUM_VIEW))
                {
                    denied.add(topic);
                }
            }

            mUnauthorizedTopics = Set.copyOf(denied);
            mChannelAudioAllowed = mWebRequestSecurity.isRequestStillAuthorized(mExchange,
                WebCapability.WEB_AUDIO_LISTEN);
        }

        private boolean pump(LiveMultiplexOutput output) throws IOException, InterruptedException
        {
            boolean wrote = reconcile(output);
            wrote |= pumpSavedActivityState(output);
            wrote |= pumpEvents(output, TOPIC_SAVED_ACTIVITY, mSavedActivity);
            wrote |= pumpEvents(output, TOPIC_CHANNEL_ACTIVITY, mChannelActivity);
            wrote |= pumpEvents(output, TOPIC_DECODE_EVENTS, mDecodeEvents);

            long now = System.nanoTime();

            if(mDecodeMessages != null && now - mLastMessagePoll >= TimeUnit.MILLISECONDS.toNanos(
                mDecodeMessages.getScope().aggregate() ? 20 : 100))
            {
                mLastMessagePoll = now;
                DecodeMessageViewService.SourceState sourceState = mDecodeMessages.sourceState();
                int maximumMessages = sourceState.aggregate() ? MAXIMUM_AGGREGATE_MESSAGES_PER_PUMP :
                    MAXIMUM_EVENTS_PER_PUMP;
                DecodeMessagePumpResult result = pumpDecodeMessages(output, mDecodeMessages::sourceState,
                    () -> mDecodeMessages.poll(0, TimeUnit.NANOSECONDS), mDecodeMessages::isCurrent, maximumMessages,
                    mDecodeMessageGeneration, mDecodeMessageSubscriptionId);
                mDecodeMessageGeneration = result.generation();
                wrote |= result.wrote();
            }

            wrote |= reportStatelessGaps(output);
            wrote |= recoverStatefulGaps(output);

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
                    if(frame.type() != DiagnosticStreamFrame.TYPE_AUDIO_PCM16 || mChannelAudioAllowed)
                    {
                        writeMultiplexDiagnostic(output, TOPIC_CHANNEL_DIAGNOSTICS, frame);
                        wrote = true;
                    }
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

            if(mFrequencyAudio != null)
            {
                DiagnosticStreamFrame frame = mFrequencyAudio.poll(Duration.ZERO);

                if(frame != null)
                {
                    writeMultiplexDiagnostic(output, TOPIC_FREQUENCY_AUDIO, frame);
                    wrote = true;
                }
            }

            return wrote;
        }

        private boolean recoverStatefulGaps(LiveMultiplexOutput output) throws IOException, InterruptedException
        {
            boolean wrote = false;
            long activityDrops = mChannelActivity != null ? mChannelActivity.droppedCount() : 0;
            long ingressDrops = mServices.mLiveService.droppedActivityIngressEvents();
            long outputDrops = output.eventDrops(TOPIC_CHANNEL_ACTIVITY);

            if(mChannelActivity != null && (metadataGap(output, TOPIC_CHANNEL_ACTIVITY,
                activityDrops, mChannelActivityDrops) || ingressDrops != mChannelActivityIngressDrops))
            {
                long dropped = positiveDelta(activityDrops, mChannelActivityDrops) +
                    positiveDelta(ingressDrops, mChannelActivityIngressDrops) +
                    positiveDelta(outputDrops, mObservedOutputDrops[TOPIC_CHANNEL_ACTIVITY]);
                discardSubscription(mChannelActivity);
                StatsLiveService.SnapshotWire snapshot = mServices.mLiveService.snapshotWire(mChannelActivityMarkers);
                var recovery = new RecoveryCapture<>(activityDrops, snapshot);
                mChannelActivityDrops = recovery.dropBaseline();
                mChannelActivityIngressDrops = ingressDrops;
                mObservedOutputDrops[TOPIC_CHANNEL_ACTIVITY] = outputDrops;
                output.offerRecoverySequence(TOPIC_CHANNEL_ACTIVITY,
                    LiveMultiplexFrame.json(TOPIC_CHANNEL_ACTIVITY, "live_gap",
                        Map.of("dropped", dropped > 0 ? dropped : 1)).bytes(output.gzipEnabled()),
                    recovery.snapshot().frame().bytes(output.gzipEnabled()));
                mChannelActivityBaselineRevision = snapshot.revision();
                mChannelActivityTableBaselines.clear();
                wrote = true;
            }

            return wrote;
        }

        /** Reports loss for disposable live viewers without replaying or clearing any successfully delivered items. */
        private boolean reportStatelessGaps(LiveMultiplexOutput output) throws IOException
        {
            boolean wrote = false;

            if(mSavedActivity != null)
            {
                long queueDrops = mSavedActivity.droppedCount();
                long outputDrops = output.eventDrops(TOPIC_SAVED_ACTIVITY);
                long dropped = positiveDelta(queueDrops, mSavedActivityDrops) +
                    positiveDelta(outputDrops, mObservedOutputDrops[TOPIC_SAVED_ACTIVITY]);
                mSavedActivityDrops = queueDrops;
                mObservedOutputDrops[TOPIC_SAVED_ACTIVITY] = outputDrops;
                if(dropped > 0)
                {
                    SavedActivityLiveService.State state = mServices.mSavedActivityLiveService.state();
                    recoverSavedActivityGap(output, state, mSavedActivitySubscriptionId, dropped);
                    if(state.initialized()) mSavedActivityStateRevision = state.revision();
                    wrote = true;
                }
            }

            if(mDecodeEvents != null)
            {
                long eventDrops = mDecodeEvents.droppedCount();
                long ingressDrops = mServices.mDecodeEventViewService != null ?
                    mServices.mDecodeEventViewService.getDroppedObservationCount() : mDecodeEventIngressDrops;
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

        private boolean metadataGap(LiveMultiplexOutput output, int topic, long sourceDrops, long observedSourceDrops)
        {
            return output.eventDrops(topic) != mObservedOutputDrops[topic] || sourceDrops != observedSourceDrops;
        }

        private void observeOutputDrops(LiveMultiplexOutput output, int topic)
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

        private boolean reconcile(LiveMultiplexOutput output) throws IOException
        {
            MultiplexConfiguration requested = mRequested.get();
            boolean wrote = false;
            long now = System.nanoTime();

            for(String topic: MULTIPLEX_TOPICS)
            {
                LiveSubscription wanted = mUnauthorizedTopics.contains(topic) ? null : requested.subscriptions().get(topic);
                LiveSubscription active = mActiveParameters.get(topic);

                if(Objects.equals(wanted, active))
                {
                    continue;
                }

                if(wanted != null && active == null && !mTopicRetryPolicy.canAttempt(topic, wanted, now))
                {
                    continue;
                }

                if(wanted instanceof TunerDiagnosticRequest request &&
                    active instanceof TunerDiagnosticRequest previous && mTunerDiagnostics != null &&
                    Objects.equals(request.targetId(), previous.targetId()) &&
                    request.compactStrength() == previous.compactStrength())
                {
                    try
                    {
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
                        mWebDiagnosticLogService.event("stream_topic_open", Map.of("client_id", mDiagnosticId,
                            "topic", topic));
                        mTopicRetryPolicy.succeeded(topic);
                    }
                    catch(RuntimeException exception)
                    {
                        closeTopic(topic);
                        mTopicRetryPolicy.failed(topic, wanted, now);
                        mWebDiagnosticLogService.event("stream_topic_failed", Map.of("client_id", mDiagnosticId,
                            "topic", topic));
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

        private void openTopic(String topic, LiveSubscription parameters, LiveMultiplexOutput output) throws IOException
        {
            switch(topic)
            {
                case "channel_activity" -> {
                    long ingressDropBaseline = mServices.mLiveService.droppedActivityIngressEvents();
                    ActivitySubscription activity = (ActivitySubscription)parameters;
                    mChannelActivityMarkers = activity.markers();
                    mChannelActivity = requiredSubscription(mServices.mLiveService.subscribeChannelActivity(
                        activity.delta(), mChannelActivityMarkers), topic);
                    long dropBaseline = mChannelActivity.droppedCount();
                    StatsLiveService.SnapshotWire snapshot = mServices.mLiveService.snapshotWire(mChannelActivityMarkers);
                    var recovery = new RecoveryCapture<>(dropBaseline, snapshot);
                    mChannelActivityDrops = recovery.dropBaseline();
                    mChannelActivityIngressDrops = ingressDropBaseline;
                    output.offerRecovery(TOPIC_CHANNEL_ACTIVITY, recovery.snapshot().frame().bytes(output.gzipEnabled()));
                    mChannelActivityBaselineRevision = snapshot.revision();
                    mChannelActivityTableBaselines.clear();
                    observeOutputDrops(output, TOPIC_CHANNEL_ACTIVITY);
                }
                case "decode_events" -> {
                    DecodeEventRequest request = (DecodeEventRequest)parameters;
                    DecodeEventViewService.Scope scope = request.scope();
                    mDecodeEventScope = scope;
                    long ingressDropBaseline = mServices.mDecodeEventViewService.getDroppedObservationCount();
                    AtomicLong liveEdge = new AtomicLong(Long.MAX_VALUE);

                    synchronized(mDecodeEventSubscriptionLock)
                    {
                        mDecodeEvents = mDecodeEventHub.subscribe(event ->
                            event.data() instanceof DecodeEventViewService.EventView view &&
                                view.observationEpoch() >= liveEdge.get() &&
                                mServices.mDecodeEventViewService.isCurrent(view) &&
                                scope.matches(view, mServices.mConventionalLiveSources));

                        if(mDecodeEvents != null)
                        {
                            mServices.mDecodeEventViewService.advanceLiveEdge(liveEdge);
                            mServices.mDecodeEventViewService.addListener(mDecodeEventViewListener);
                        }
                    }

                    requiredSubscription(mDecodeEvents, topic);
                    mDecodeEventDrops = 0;
                    mDecodeEventIngressDrops = ingressDropBaseline;
                    writeMultiplexRecoveryJson(output, TOPIC_DECODE_EVENTS, "source_change",
                        decodeEventSourceState(scope, request.subscriptionId()));
                    observeOutputDrops(output, TOPIC_DECODE_EVENTS);
                }
                case "decode_messages" -> {
                    if(!mDecodeMessageClients.tryAcquire())
                    {
                        throw new IllegalStateException("Decode message viewer capacity is in use");
                    }

                    mMessagePermit = true;
                    DecodeMessageRequest request = (DecodeMessageRequest)parameters;
                    mDecodeMessages = mServices.mDecodeMessageViewService.openSession(request.scope());
                    mDecodeMessageSubscriptionId = request.subscriptionId();
                    mDecodeMessageDrops = 0;
                    observeOutputDrops(output, TOPIC_DECODE_MESSAGES);
                    DecodeMessageViewService.SourceState sourceState = mDecodeMessages.sourceState();
                    mDecodeMessageGeneration = sourceState.generation();
                    writeMultiplexRecoveryJson(output, TOPIC_DECODE_MESSAGES, "source_change",
                        decodeMessageSourceState(sourceState, mDecodeMessageSubscriptionId));
                }
                case "channel_diagnostics" -> openChannelDiagnostics((ChannelDiagnosticRequest)parameters, output);
                case "tuner_diagnostics" -> openTunerDiagnostics((TunerDiagnosticRequest)parameters, output);
                case "frequency_audio" -> openFrequencyAudio((FrequencyListenRequest)parameters);
                case "saved_activity" -> {
                    mSavedActivitySubscriptionId = ((SavedActivitySubscription)parameters).subscriptionId();
                    mSavedActivity = requiredSubscription(mServices.mSavedActivityLiveService.subscribe(), topic);
                    mSavedActivityDrops = mSavedActivity.droppedCount();
                    observeOutputDrops(output, TOPIC_SAVED_ACTIVITY);
                    pumpSavedActivityState(output);
                }
                default -> throw new IllegalArgumentException("Unknown multiplex topic");
            }
        }

        private boolean pumpSavedActivityState(LiveMultiplexOutput output) throws IOException
        {
            if(mSavedActivity == null) return false;
            SavedActivityLiveService.State state = mServices.mSavedActivityLiveService.state();
            if(!state.initialized() || state.revision() == mSavedActivityStateRevision) return false;
            writeMultiplexRecoveryJson(output, TOPIC_SAVED_ACTIVITY, "source_change",
                state.payload(mSavedActivitySubscriptionId));
            mSavedActivityStateRevision = state.revision();
            return true;
        }

        private void openChannelDiagnostics(ChannelDiagnosticRequest request, LiveMultiplexOutput output) throws IOException
        {
            if(!mDiagnosticClients.tryAcquire())
            {
                throw new IllegalStateException("Diagnostic viewer capacity is in use");
            }

            mChannelDiagnosticPermit = true;
            ChannelDiagnosticService.OpenResult result = mServices.mChannelDiagnosticService.tryOpen(request.scope(),
                request.compactStrength());

            if(result.status() != ChannelDiagnosticService.OpenStatus.OPEN)
            {
                throw new IllegalStateException("Channel diagnostics are unavailable: " + result.status());
            }

            mChannelDiagnostics = result.session();
            mChannelAudioAllowed = mWebRequestSecurity.isRequestStillAuthorized(mExchange,
                WebCapability.WEB_AUDIO_LISTEN);
            mChannelDiagnosticSubscriptionId = request.subscriptionId();
            ChannelDiagnosticService.State state = mChannelDiagnostics.state();
            writeMultiplexDiagnostic(output, TOPIC_CHANNEL_DIAGNOSTICS,
                diagnosticState(state.generation(), state.revision(), state,
                    mChannelDiagnosticSubscriptionId));
            mChannelStateRevision = state.revision();
        }

        private void openTunerDiagnostics(TunerDiagnosticRequest request, LiveMultiplexOutput output) throws IOException
        {
            if(!mDiagnosticClients.tryAcquire())
            {
                throw new IllegalStateException("Diagnostic viewer capacity is in use");
            }

            mTunerDiagnosticPermit = true;
            TunerDiagnosticService.OpenResult result = mServices.mTunerDiagnosticService.tryOpen(request.targetId(),
                request.viewport(), request.profile(), request.compactStrength());

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

        private void openFrequencyAudio(FrequencyListenRequest request)
        {
            if(!mWebRequestSecurity.isRequestStillAuthorized(mExchange, WebCapability.WEB_AUDIO_LISTEN) ||
                !mWebRequestSecurity.isRequestStillAuthorized(mExchange, WebCapability.TUNER_SPECTRUM_VIEW))
            {
                throw new IllegalStateException("Frequency listening is unavailable");
            }

            mFrequencyAudio = mServices.mFrequencyListenService.open(request.targetId(), request.frequencyHz(),
                request.bandwidthHz());
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

        private boolean pumpEvents(LiveMultiplexOutput output, int topic,
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

                if(topic == TOPIC_DECODE_EVENTS && mDecodeEventScope != null &&
                    event.data() instanceof DecodeEventViewService.EventView view &&
                    (!mServices.mDecodeEventViewService.isCurrent(view) ||
                        !mDecodeEventScope.matches(view, mServices.mConventionalLiveSources)))
                {
                    // Membership can change while this browser's bounded queue is waiting to be sampled.
                    continue;
                }

                if(topic == TOPIC_CHANNEL_ACTIVITY && event.data() instanceof Map<?,?> data)
                {
                    if("activity_resync".equals(event.name()))
                    {
                        Map<?,?> snapshot = (Map<?,?>)data.get("snapshot");
                        long revision = ((Number)snapshot.get("revision")).longValue();
                        if(revision < mChannelActivityBaselineRevision) continue;
                        output.offerRecovery(topic, event.frame(topic).bytes(output.gzipEnabled()));
                        mChannelActivityBaselineRevision = revision;
                        mChannelActivityTableBaselines.clear();
                        // Any earlier output eviction is covered by this authoritative replacement already.
                        observeOutputDrops(output, topic);
                    }
                    else
                    {
                        long revision = ((Number)data.get("revision")).longValue();
                        if(revision <= mChannelActivityBaselineRevision) continue;
                        String tableId = String.valueOf(data.get("table_id"));
                        StatsLiveEventHub.LiveEvent selected = event;
                        if(event.baseline() != null && mChannelActivityTableBaselines.add(tableId))
                        {
                            selected = event.baseline();
                            if(mChannelActivityTableBaselines.size() > StatsLiveService.MAXIMUM_LIVE_TABLES)
                            {
                                mChannelActivityTableBaselines.remove(mChannelActivityTableBaselines.iterator().next());
                            }
                        }
                        if("remove".equals(data.get("operation"))) mChannelActivityTableBaselines.remove(tableId);
                        output.offerEvent(topic, selected.frame(topic).bytes(output.gzipEnabled()));
                        mChannelActivityBaselineRevision = revision;
                    }
                }
                else
                {
                    output.offerEvent(topic, event.frame(topic).bytes(output.gzipEnabled()));
                }
                wrote = true;
            }

            return wrote;
        }

        private void closeTopic(String topic)
        {
            if(mActiveParameters.containsKey(topic))
                mWebDiagnosticLogService.event("stream_topic_close", Map.of("client_id", mDiagnosticId,
                    "topic", topic));
            switch(topic)
            {
                case "channel_activity" -> {
                    mChannelActivity = closeSubscription(mChannelActivity);
                    mChannelActivityDrops = 0;
                    mChannelActivityIngressDrops = 0;
                    mChannelActivityBaselineRevision = 0;
                    mChannelActivityMarkers = false;
                    mChannelActivityTableBaselines.clear();
                }
                case "decode_events" -> closeDecodeEvents();
                case "decode_messages" -> closeDecodeMessages();
                case "channel_diagnostics" -> closeChannelDiagnostics();
                case "tuner_diagnostics" -> closeTunerDiagnostics();
                case "frequency_audio" -> closeFrequencyAudio();
                case "saved_activity" -> {
                    mSavedActivity = closeSubscription(mSavedActivity);
                    mSavedActivitySubscriptionId = null;
                    mSavedActivityStateRevision = -1;
                    mSavedActivityDrops = 0;
                }
                default -> { }
            }
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
                mDecodeEventScope = null;

                if(!mDecodeEventHub.hasSubscribers())
                {
                    mServices.mDecodeEventViewService.removeListener(mDecodeEventViewListener);
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
            mChannelAudioAllowed = false;
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

        private void closeFrequencyAudio()
        {
            if(mFrequencyAudio != null)
            {
                mFrequencyAudio.close();
                mFrequencyAudio = null;
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
            case "tuner_diagnostics" -> WebCapability.TUNER_SPECTRUM_VIEW;
            case "frequency_audio" -> WebCapability.WEB_AUDIO_LISTEN;
            case "saved_activity" -> WebCapability.RADIO_VIEW;
            default -> WebCapability.LIVE_VIEW;
        };
    }

    static Set<WebCapability> capabilitiesForTopic(String topic)
    {
        return "saved_activity".equals(topic) ? Set.of(WebCapability.RADIO_VIEW, WebCapability.DASHBOARD_VIEW) :
            Set.of(capabilityForTopic(topic));
    }

    static void recoverSavedActivityGap(LiveMultiplexOutput output, SavedActivityLiveService.State state,
                                       String subscriptionId, long dropped) throws IOException
    {
        byte[] gap = LiveMultiplexFrame.json(TOPIC_SAVED_ACTIVITY, "live_gap", Map.of("dropped", dropped))
            .bytes(output.gzipEnabled());
        if(state.initialized())
        {
            // A gap can replace an unwritten initial/settings state. Preserve its authoritative timing and
            // subscription boundary after the gap, before any later append, so the browser can finish recovery.
            output.offerRecoverySequence(TOPIC_SAVED_ACTIVITY, gap,
                LiveMultiplexFrame.json(TOPIC_SAVED_ACTIVITY, "source_change", state.payload(subscriptionId))
                    .bytes(output.gzipEnabled()));
        }
        else output.offerRecovery(TOPIC_SAVED_ACTIVITY, gap);
    }

    private static int topicId(String topic)
    {
        return switch(topic)
        {
            case "channel_activity" -> TOPIC_CHANNEL_ACTIVITY;
            case "decode_events" -> TOPIC_DECODE_EVENTS;
            case "decode_messages" -> TOPIC_DECODE_MESSAGES;
            case "channel_diagnostics" -> TOPIC_CHANNEL_DIAGNOSTICS;
            case "tuner_diagnostics" -> TOPIC_TUNER_DIAGNOSTICS;
            case "frequency_audio" -> TOPIC_FREQUENCY_AUDIO;
            case "saved_activity" -> TOPIC_SAVED_ACTIVITY;
            default -> TOPIC_CONTROL;
        };
    }

    private record MultiplexConfiguration(long revision, Map<String,LiveSubscription> subscriptions)
    {
        private MultiplexConfiguration
        {
            subscriptions = Map.copyOf(subscriptions);
        }
    }

    private record DecodeEventSourceState(String configurationId, Long frequencyHz, Integer timeslot,
                                          String subscriptionId, FilterCatalog filterCatalog, String scope)
    {
    }

    static DecodeEventSourceState decodeEventSourceState(DecodeEventViewService.Scope scope,
                                                        String subscriptionId)
    {
        return new DecodeEventSourceState(scope.configurationId(), scope.frequencyHz(), scope.timeslot(),
            subscriptionId, DecodeEventViewService.filterCatalog(), scope.mode().name());
    }

    record DecodeEventRequest(DecodeEventViewService.Scope scope, String subscriptionId,
                                      boolean compactStrength) implements LiveSubscription
    {
    }

    static DecodeMessageSourceState decodeMessageSourceState(DecodeMessageViewService.SourceState state,
                                                             String subscriptionId)
    {
        return new DecodeMessageSourceState(state.generation(), state.bound(), state.configurationId(),
            state.aggregate() ? null : state.frequencyHz(), subscriptionId, state.filterCatalog(),
            state.aggregate() ? "CONVENTIONAL" : "CHANNEL");
    }

    record DecodeMessageRequest(DecodeMessageViewService.Scope scope, String subscriptionId) implements LiveSubscription
    {
    }

    private record DecodeMessageSourceState(long generation, boolean bound, String configurationId,
                                            Long frequencyHz, String subscriptionId, FilterCatalog filterCatalog,
                                            String scope)
    {
    }

    record ChannelDiagnosticRequest(ChannelDiagnosticService.Scope scope, String subscriptionId,
                                             boolean compactStrength) implements LiveSubscription
    {
    }

    record RecoveryCapture<T>(long dropBaseline, T snapshot)
    {
    }

    private record TunerDiagnosticRequest(String targetId, TunerDiagnosticService.Viewport viewport,
                                          TunerDiagnosticService.SpectrumProfile profile, boolean compactStrength) implements LiveSubscription
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

}
