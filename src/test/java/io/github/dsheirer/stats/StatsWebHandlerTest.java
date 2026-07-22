/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.configuration.channel.ChannelConfigurationOperations;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.AutoStartRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.BulkRuntimeRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelDeleteRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelListRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelWriteRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.NbfmRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.RevisionRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.RuntimeRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.TimeoutRequest;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.manager.TunerSettingsOperations;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService.EnabledRequest;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService.UpdateRequest;
import io.github.dsheirer.web.WebApplicationService;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.FeatureAccessMode;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.WebFeature;
import io.github.dsheirer.web.access.WebRequestSubjectResolver;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebHandlerTest
{
    private static final String TUNER_ID = "TNR_0123456789ABCDEF0123456789AB";
    private static final String CHANNEL_ID = "CHN_0123456789ABCDEF0123456789AB";
    @TempDir
    Path mTemporaryFolder;
    private StatsLiveService mLiveService;
    private StatsWebCallService mWebCallService;
    private StatsWebHandler mHandler;
    private WebApplicationService mWebApplicationService;
    private HttpClient mHttpClient;
    private InMemoryFeatureAccessPolicy mAccessPolicy;
    private AtomicReference<AuthorizationSubject> mSubject;
    private AtomicBoolean mSessionValid;
    private AtomicBoolean mMutationAuthorized;
    private AtomicReference<UpdateRequest> mTunerUpdate;
    private AtomicReference<EnabledRequest> mEnabledUpdate;
    private AtomicInteger mTunerMutationCalls;
    private AtomicReference<ChannelListRequest> mChannelListRequest;
    private AtomicReference<ChannelWriteRequest> mChannelWriteRequest;
    private AtomicInteger mChannelMutationCalls;

    @BeforeEach
    void setUp() throws Exception
    {
        Path databasePath = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Path assets = mTemporaryFolder.resolve("assets");
        Files.createDirectories(assets.resolve("assets"));
        Files.writeString(assets.resolve("index.html"), "<h1>Stats</h1>", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("assets/app.js"), "window.statsReady = true;", StandardCharsets.UTF_8);
        SdrTrunkDatabaseStartup.createGlobalDatabase(databasePath);

        StatsWebDatabase database = new StatsWebDatabase(new UserPreferences(), databasePath);
        mLiveService = new StatsLiveService(database, null);
        mWebCallService = new StatsWebCallService();
        mLiveService.start();
        mWebCallService.start();
        mAccessPolicy = InMemoryFeatureAccessPolicy.currentProfileDefaults();
        mSubject = new AtomicReference<>(AuthorizationSubject.ANONYMOUS);
        mSessionValid = new AtomicBoolean(true);
        mMutationAuthorized = new AtomicBoolean(false);
        mTunerUpdate = new AtomicReference<>();
        mEnabledUpdate = new AtomicReference<>();
        mTunerMutationCalls = new AtomicInteger();
        mChannelListRequest = new AtomicReference<>();
        mChannelWriteRequest = new AtomicReference<>();
        mChannelMutationCalls = new AtomicInteger();
        WebRequestSubjectResolver subjectResolver = new WebRequestSubjectResolver()
        {
            @Override
            public AuthorizationSubject resolve(Request request)
            {
                return mSubject.get();
            }

            @Override
            public WebAuthorization resolveAuthorization(Request request)
            {
                AuthorizationSubject subject = mSubject.get();
                return subject.isAuthenticatedAdmin() ? new WebAuthorization(subject, mSessionValid::get) :
                    WebAuthorization.permanent(subject);
            }
        };
        TunerSettingsOperations tunerSettings = new TunerSettingsOperations()
        {
            @Override
            public CompletableFuture<Map<String,Object>> settings(String tunerId)
            {
                return CompletableFuture.completedFuture(Map.of("id", tunerId, "revision", 41,
                    "enabled", true, "device", Map.of("type", "AIRSPY")));
            }

            @Override
            public CompletableFuture<Map<String,Object>> update(String tunerId, UpdateRequest request,
                                                                  BooleanSupplier sessionIsValid)
            {
                mTunerMutationCalls.incrementAndGet();
                mTunerUpdate.set(request);
                return CompletableFuture.completedFuture(Map.of("id", tunerId, "revision", 42,
                    "sessionValid", sessionIsValid.getAsBoolean()));
            }

            @Override
            public CompletableFuture<Map<String,Object>> setEnabled(String tunerId, EnabledRequest request,
                                                                      BooleanSupplier sessionIsValid)
            {
                mTunerMutationCalls.incrementAndGet();
                mEnabledUpdate.set(request);
                return CompletableFuture.completedFuture(Map.of("id", tunerId, "revision", 43,
                    "sessionValid", sessionIsValid.getAsBoolean()));
            }
        };
        ChannelConfigurationOperations channelSettings = new ChannelConfigurationOperations()
        {
            @Override
            public CompletableFuture<Map<String,Object>> list(ChannelListRequest request)
            {
                mChannelListRequest.set(request);
                return CompletableFuture.completedFuture(Map.of("generation", "test", "items", List.of(),
                    "total", 0, "queueRevision", "QUE_TEST"));
            }

            @Override
            public CompletableFuture<Map<String,Object>> template(String protocol)
            {
                return CompletableFuture.completedFuture(Map.of("template", true, "protocol", protocol));
            }

            @Override
            public CompletableFuture<Map<String,Object>> detail(String channelId)
            {
                return CompletableFuture.completedFuture(Map.of("id", channelId, "revision", "REV_TEST"));
            }

            @Override
            public CompletableFuture<Map<String,Object>> export(String channelId)
            {
                return CompletableFuture.completedFuture(Map.of("fileName", "channel.json"));
            }

            @Override
            public CompletableFuture<Map<String,Object>> create(ChannelWriteRequest request,
                                                                  BooleanSupplier sessionIsValid)
            {
                mChannelMutationCalls.incrementAndGet();
                mChannelWriteRequest.set(request);
                return CompletableFuture.completedFuture(Map.of("id", CHANNEL_ID, "created", true,
                    "sessionValid", sessionIsValid.getAsBoolean()));
            }

            @Override
            public CompletableFuture<Map<String,Object>> update(String channelId, ChannelWriteRequest request,
                                                                  BooleanSupplier sessionIsValid)
            {
                mChannelMutationCalls.incrementAndGet();
                mChannelWriteRequest.set(request);
                return CompletableFuture.completedFuture(Map.of("id", channelId, "updated", true));
            }

            @Override
            public CompletableFuture<Map<String,Object>> cloneChannel(String channelId, RevisionRequest request,
                                                                        BooleanSupplier sessionIsValid)
            {
                mChannelMutationCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Map.of("id", CHANNEL_ID, "cloned", true));
            }

            @Override
            public CompletableFuture<Map<String,Object>> delete(String channelId, ChannelDeleteRequest request,
                                                                  BooleanSupplier sessionIsValid)
            {
                mChannelMutationCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Map.of("id", channelId, "deleted", request.confirm()));
            }

            @Override
            public CompletableFuture<Map<String,Object>> autoStart(String channelId, AutoStartRequest request,
                                                                     BooleanSupplier sessionIsValid)
            {
                mChannelMutationCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Map.of("id", channelId, "action", request.action().name()));
            }

            @Override
            public CompletableFuture<Map<String,Object>> runtime(String channelId, RuntimeRequest request,
                                                                   BooleanSupplier sessionIsValid)
            {
                mChannelMutationCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Map.of("id", channelId, "action", request.action().name()));
            }

            @Override
            public CompletableFuture<Map<String,Object>> bulkRuntime(BulkRuntimeRequest request,
                                                                       BooleanSupplier sessionIsValid)
            {
                mChannelMutationCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Map.of("selected", request.channels().size()));
            }

            @Override
            public CompletableFuture<Map<String,Object>> setAutoStartTimeout(TimeoutRequest request,
                                                                               BooleanSupplier sessionIsValid)
            {
                mChannelMutationCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Map.of("autoStartTimeoutSeconds", request.seconds()));
            }
        };
        mHandler = new StatsWebHandler(assets, database, mLiveService, mWebCallService,
            () -> Map.of("server", Map.of("enabled", true)), null, mAccessPolicy, subjectResolver,
            io.github.dsheirer.web.access.RemoteAddressAdmissionPolicy.allowAll(),
            () -> Map.of("revision", 7,
                "tuners", List.of(Map.of("id", "AIRSPY-TEST", "displayName", "Airspy")),
                "spectrum", Map.of("exclusive", true, "busy", false)), tunerSettings, channelSettings,
            request -> new io.github.dsheirer.web.auth.WebAdminAuthenticationHandler.MutationAuthorization(
                mMutationAuthorized.get(), mSessionValid::get));
        mWebApplicationService = new WebApplicationService(
            WebApplicationService.Configuration.ephemeralLoopback(), mHandler, container -> {});
        mWebApplicationService.start();
        mHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void appliesTheSamePublicAdminPolicyToExistingHttpRoutes() throws Exception
    {
        URI root = mWebApplicationService.getBaseUri();
        HttpResponse<String> publicStatus = mHttpClient.send(HttpRequest.newBuilder(root.resolve("api/status")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, publicStatus.statusCode());

        mAccessPolicy.setMode(WebFeature.STATUS_STATISTICS, FeatureAccessMode.ADMIN_ONLY);
        HttpResponse<String> lockedStatus = mHttpClient.send(HttpRequest.newBuilder(root.resolve("api/status")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, lockedStatus.statusCode());
        assertTrue(lockedStatus.headers().firstValue("WWW-Authenticate").isPresent());

        HttpResponse<String> publicShell = mHttpClient.send(HttpRequest.newBuilder(root.resolve("index.html")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, publicShell.statusCode());

        mSubject.set(AuthorizationSubject.AUTHENTICATED_ADMIN);
        HttpResponse<String> adminStatus = mHttpClient.send(HttpRequest.newBuilder(root.resolve("api/status")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, adminStatus.statusCode());
    }

    @Test
    void keepsTunerInventoryPermanentlyAdministratorOnly() throws Exception
    {
        HttpResponse<String> anonymous = get("api/v1/tuners");
        assertEquals(401, anonymous.statusCode());
        assertTrue(anonymous.headers().firstValue("WWW-Authenticate").isPresent());

        mSubject.set(AuthorizationSubject.AUTHENTICATED_ADMIN);
        HttpResponse<String> administrator = get("api/v1/tuners");
        assertEquals(200, administrator.statusCode());
        assertTrue(administrator.body().contains("\"revision\":7"));
        assertTrue(administrator.body().contains("\"id\":\"AIRSPY-TEST\""));

        mSessionValid.set(false);
        assertEquals(401, get("api/v1/tuners").statusCode());
    }

    @Test
    void protectsAndStrictlyParsesTunerSettingsRoutes() throws Exception
    {
        String settingsPath = "api/v1/tuners/" + TUNER_ID + "/settings";
        String enabledPath = "api/v1/tuners/" + TUNER_ID + "/enabled";
        assertEquals(401, get(settingsPath).statusCode());
        mSubject.set(AuthorizationSubject.AUTHENTICATED_ADMIN);
        HttpResponse<String> settings = get(settingsPath);
        assertEquals(200, settings.statusCode());
        assertTrue(settings.body().contains("\"revision\":41"));

        String update = """
            {
              "revision":41,
              "frequencyCorrectionPpm":0.0,
              "autoPpm":true,
              "centerFrequencyHz":101100000,
              "minimumFrequencyHz":24000000,
              "maximumFrequencyHz":1800000000,
              "centerFrequencyFixed":false,
              "deviceType":"AIRSPY",
              "sampleRateHz":10000000,
              "airspyGainMode":"LINEARITY",
              "airspyGain":14,
              "airspyIfGain":9,
              "airspyMixerGain":9,
              "airspyLnaGain":7,
              "airspyMixerAgc":false,
              "airspyLnaAgc":false,
              "rtlBiasT":null,
              "rtlMasterGain":null,
              "rtlMixerGain":null,
              "rtlLnaGain":null,
              "rtlVgaGain":null
            }
            """;

        assertEquals(403, put(settingsPath, "application/json", update).statusCode());
        assertEquals(0, mTunerMutationCalls.get());
        mMutationAuthorized.set(true);
        HttpResponse<String> saved = put(settingsPath, "application/json; charset=utf-8", update);
        assertEquals(200, saved.statusCode());
        assertTrue(saved.body().contains("\"sessionValid\":true"));
        assertEquals("AIRSPY", mTunerUpdate.get().deviceType());
        assertEquals(101_100_000L, mTunerUpdate.get().centerFrequencyHz());
        assertEquals(14, mTunerUpdate.get().airspyGain());

        int calls = mTunerMutationCalls.get();
        String duplicate = update.replace("\"revision\":41,", "\"revision\":41,\"revision\":41,");
        assertEquals(400, put(settingsPath, "application/json", duplicate).statusCode());
        assertEquals(calls, mTunerMutationCalls.get());
        String unknown = update.replace("\"revision\":41,", "\"revision\":41,\"unexpected\":true,");
        assertEquals(400, put(settingsPath, "application/json", unknown).statusCode());
        assertEquals(calls, mTunerMutationCalls.get());
        assertEquals(415, put(settingsPath, "text/plain", update).statusCode());

        HttpResponse<String> enabled = put(enabledPath, "application/json",
            "{\"revision\":42,\"enabled\":false,\"confirmActiveStop\":true}");
        assertEquals(200, enabled.statusCode());
        assertEquals(Boolean.FALSE, mEnabledUpdate.get().enabled());
        assertEquals(405, get(enabledPath).statusCode());
        assertEquals(404, get("api/v1/tuners/" + TUNER_ID + "/unknown").statusCode());
    }

    @Test
    void keepsChannelSettingsAdministratorOnlyAndStrictlyBounded() throws Exception
    {
        String base = "api/v1/configuration/channels";
        assertEquals(401, get(base).statusCode());
        assertEquals(401, get(base + "/templates/NBFM").statusCode());

        mSubject.set(AuthorizationSubject.AUTHENTICATED_ADMIN);
        HttpResponse<String> list = get(base + "?q=North&sort=name&direction=descending&offset=2&limit=20");
        assertEquals(200, list.statusCode());
        assertEquals("North", mChannelListRequest.get().query());
        assertEquals("name", mChannelListRequest.get().sort());
        assertEquals("descending", mChannelListRequest.get().direction());
        assertEquals(2, mChannelListRequest.get().offset());
        assertEquals(20, mChannelListRequest.get().limit());
        assertEquals(200, get(base + "/templates/NBFM").statusCode());
        assertEquals(200, get(base + "/" + CHANNEL_ID).statusCode());

        String body = channelWriteJson();
        assertEquals(403, sendJson("POST", base, "application/json", body).statusCode());
        assertEquals(0, mChannelMutationCalls.get());
        mMutationAuthorized.set(true);
        HttpResponse<String> created = sendJson("POST", base, "application/json; charset=utf-8", body);
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"created\":true"));
        assertTrue(mChannelWriteRequest.get().decoder() instanceof NbfmRequest);
        assertEquals(Boolean.FALSE, mChannelWriteRequest.get().confirmGuidChange());
        assertEquals(1, mChannelMutationCalls.get());

        int calls = mChannelMutationCalls.get();
        assertEquals(400, sendJson("POST", base, "application/json",
            body.replace("\"name\":\"Dispatch\",", "\"name\":\"Dispatch\",\"name\":\"Duplicate\","))
            .statusCode());
        assertEquals(calls, mChannelMutationCalls.get());
        assertEquals(400, sendJson("POST", base, "application/json",
            body.replace("\"system\":", "\"unexpected\":true,\"system\":" )).statusCode());
        assertEquals(calls, mChannelMutationCalls.get());
        assertEquals(415, sendJson("POST", base, "text/plain", body).statusCode());

        assertEquals(200, sendJson("PUT", base + "/" + CHANNEL_ID, "application/json", body).statusCode());
        assertEquals(200, sendJson("PUT", base + "/" + CHANNEL_ID + "/runtime", "application/json",
            "{\"revision\":\"REV_TEST\",\"action\":\"START\"}").statusCode());
        assertEquals(200, sendJson("DELETE", base + "/" + CHANNEL_ID, "application/json",
            "{\"revision\":\"REV_TEST\",\"confirm\":true}").statusCode());
        assertEquals(405, get(base + "/runtime").statusCode());
        assertEquals(404, get(base + "/" + CHANNEL_ID + "/unknown").statusCode());

        mSessionValid.set(false);
        assertEquals(401, get(base).statusCode());
    }

    @Test
    void gatesSelectedContextEventsAndMessagesBeforeResolvingRuntimeState() throws Exception
    {
        String context = "api/v1/contexts/site-00000000-0000-0000-0000-000000000000/";
        assertEquals(401, get(context + "events").statusCode());
        assertEquals(401, get(context + "messages/stream").statusCode());

        mSubject.set(AuthorizationSubject.AUTHENTICATED_ADMIN);
        assertEquals(503, get(context + "events").statusCode());
        assertEquals(503, get(context + "messages").statusCode());
    }

    @AfterEach
    void tearDown()
    {
        if(mWebApplicationService != null)
        {
            mWebApplicationService.close();
        }

        if(mLiveService != null)
        {
            mLiveService.close();
        }

        if(mWebCallService != null)
        {
            mWebCallService.close();
        }
    }

    @Test
    void servesExistingStatusStaticAndAudioRoutes() throws Exception
    {
        HttpResponse<String> status = get("api/status");
        assertEquals(200, status.statusCode());
        assertTrue(status.body().contains("\"enabled\":true"));
        assertEquals("application/json; charset=utf-8", status.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("nosniff", status.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", status.headers().firstValue("X-Frame-Options").orElse(null));
        assertTrue(status.headers().firstValue("Content-Security-Policy").orElse("")
            .contains("frame-ancestors 'none'"));
        assertEquals(200, get("api/systems").statusCode());

        HttpResponse<String> index = get("");
        assertEquals(200, index.statusCode());
        assertEquals("<h1>Stats</h1>", index.body());
        assertEquals("no-cache", index.headers().firstValue("Cache-Control").orElseThrow());

        URI scriptUri = mWebApplicationService.getBaseUri().resolve("assets/app.js");
        HttpResponse<String> head = mHttpClient.send(HttpRequest.newBuilder(scriptUri).method("HEAD",
            HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(Files.size(mTemporaryFolder.resolve("assets/assets/app.js")),
            head.headers().firstValueAsLong("Content-Length").orElseThrow());

        try(StatsLiveEventHub.Subscription subscription = mWebCallService.subscribe())
        {
            mWebCallService.receive(call());
            StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            @SuppressWarnings("unchecked")
            String callId = String.valueOf(((Map<String,Object>)event.data()).get("call_id"));
            HttpResponse<byte[]> audio = mHttpClient.send(HttpRequest.newBuilder(mWebApplicationService.getBaseUri()
                .resolve("api/web-player/calls/" + callId + "/audio")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, audio.statusCode());
            assertEquals("audio/wav", audio.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("RIFF", new String(audio.body(), 0, 4, StandardCharsets.US_ASCII));
        }

        HttpResponse<String> wrongMethod = mHttpClient.send(HttpRequest.newBuilder(
            mWebApplicationService.getBaseUri().resolve("api/status")).POST(
            HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, wrongMethod.statusCode());
    }

    @Test
    void keepsStaticFilesInsideConfiguredRoot() throws Exception
    {
        Files.writeString(mTemporaryFolder.resolve("secret.txt"), "must-not-leak", StandardCharsets.UTF_8);
        HttpResponse<String> response = get("%2e%2e/secret.txt");
        assertTrue(response.statusCode() == 400 || response.statusCode() == 403 || response.statusCode() == 404);
        assertFalse(response.body().contains("must-not-leak"));
    }

    @Test
    void sseViewersDoNotReserveJettyPlatformThreads() throws Exception
    {
        List<InputStream> streams = new ArrayList<>();

        try
        {
            for(int x = 0; x < 10; x++)
            {
                HttpResponse<InputStream> response = mHttpClient.send(HttpRequest.newBuilder(
                    mWebApplicationService.getBaseUri().resolve("live/systems")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, response.statusCode());
                assertEquals("event: snapshot\n", new String(response.body().readNBytes(16), StandardCharsets.UTF_8));
                streams.add(response.body());
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

            while(mHandler.activeStreamCount() != 10 && System.nanoTime() < deadline)
            {
                Thread.onSpinWait();
            }

            assertEquals(10, mHandler.activeStreamCount());
            assertTrue(mWebApplicationService.getThreadPoolSnapshot().busyThreads() < 10);
            assertEquals(200, get("api/status").statusCode());
        }
        finally
        {
            for(InputStream stream: streams)
            {
                stream.close();
            }
        }

        mWebApplicationService.close();
        assertEquals(0, mHandler.activeStreamCount());
    }

    @Test
    void closesSsePromptlyWhenItsAdminSessionIsInvalidated() throws Exception
    {
        mAccessPolicy.setMode(WebFeature.STATUS_STATISTICS, FeatureAccessMode.ADMIN_ONLY);
        mSubject.set(AuthorizationSubject.AUTHENTICATED_ADMIN);

        try(InputStream stream = openSse("live/systems"))
        {
            assertTrue(readSseEvent(stream).startsWith("event: snapshot\n"));
            mSessionValid.set(false);
            assertStreamClosesWithinOneSecond(stream);
        }
    }

    @Test
    void closesAnonymousSsePromptlyWhenFeatureBecomesAdminOnly() throws Exception
    {
        try(InputStream stream = openSse("live/web-calls"))
        {
            assertTrue(readSseEvent(stream).startsWith("event: ready\n"));
            mAccessPolicy.setMode(WebFeature.CALL_AUDIO, FeatureAccessMode.ADMIN_ONLY);
            assertStreamClosesWithinOneSecond(stream);
        }
    }

    private HttpResponse<String> get(String relativePath) throws Exception
    {
        return mHttpClient.send(HttpRequest.newBuilder(mWebApplicationService.getBaseUri().resolve(relativePath))
            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String relativePath, String contentType, String body) throws Exception
    {
        URI baseUri = mWebApplicationService.getBaseUri();
        String origin = baseUri.getScheme() + "://" + baseUri.getAuthority();
        return mHttpClient.send(HttpRequest.newBuilder(baseUri.resolve(relativePath))
            .timeout(Duration.ofSeconds(5)).header("Origin", origin)
            .header("Content-Type", contentType).header("X-CSRF-Token", "test-token")
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(String method, String relativePath, String contentType, String body)
        throws Exception
    {
        URI baseUri = mWebApplicationService.getBaseUri();
        String origin = baseUri.getScheme() + "://" + baseUri.getAuthority();
        return mHttpClient.send(HttpRequest.newBuilder(baseUri.resolve(relativePath))
            .timeout(Duration.ofSeconds(5)).header("Origin", origin)
            .header("Content-Type", contentType).header("X-CSRF-Token", "test-token")
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String channelWriteJson()
    {
        return """
            {
              "revision":null,
              "applyPolicy":"APPLY",
              "system":"County",
              "site":"North",
              "name":"Dispatch",
              "guid":"9c3f805d-78f4-4c3b-bc62-f07e9c5acabc",
              "confirmGuidChange":false,
              "aliasList":"Default",
              "source":{
                "kind":"SINGLE",
                "frequenciesHz":[155100000],
                "preferredTuner":"",
                "rotationDelayMs":null
              },
              "decoder":{
                "type":"NBFM",
                "bandwidth":"BW_12_5",
                "talkgroup":1,
                "deemphasis":"NONE",
                "outputGain":1.0,
                "highPassEnabled":true,
                "lowPassEnabled":false,
                "lowPassCutoffHz":3400,
                "voiceEnhancePercent":30.0,
                "squelchTrimEnabled":false,
                "tailTrimMs":180,
                "headTrimMs":0,
                "bassBoostDb":0.0
              },
              "auxiliaries":[],
              "logging":[],
              "recording":[]
            }
            """;
    }

    private InputStream openSse(String relativePath) throws Exception
    {
        HttpResponse<InputStream> response = mHttpClient.send(HttpRequest.newBuilder(
            mWebApplicationService.getBaseUri().resolve(relativePath)).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private static String readSseEvent(InputStream stream) throws Exception
    {
        byte[] event = new byte[64 * 1024];
        int length = 0;

        while(length < event.length)
        {
            int value = stream.read();

            if(value < 0)
            {
                break;
            }

            event[length++] = (byte)value;

            if(length >= 2 && event[length - 2] == '\n' && event[length - 1] == '\n')
            {
                return new String(event, 0, length, StandardCharsets.UTF_8);
            }
        }

        throw new AssertionError("SSE initial event did not terminate within the bounded test buffer");
    }

    private static void assertStreamClosesWithinOneSecond(InputStream stream) throws Exception
    {
        CompletableFuture<Integer> nextByte = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().name("stats SSE revocation test").start(() -> {
            try
            {
                nextByte.complete(stream.read());
            }
            catch(Exception exception)
            {
                nextByte.completeExceptionally(exception);
            }
        });

        try
        {
            assertEquals(-1, nextByte.get(1, TimeUnit.SECONDS));
        }
        finally
        {
            if(!nextByte.isDone())
            {
                stream.close();
                reader.interrupt();
            }
        }
    }

    private static CompletedAudioCall call()
    {
        List<Identifier> identifiers = List.of(SystemConfigurationIdentifier.create("Test System"),
            FrequencyConfigurationIdentifier.create(854_187_500L), APCO25Talkgroup.create(4400),
            APCO25RadioIdentifier.createFrom(9001));
        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(1, 2, 0), null, null,
            new IdentifierCollection(identifiers), Set.of(), 1_000L, 1_100L, 1, 1L, 1_000L, 1_100L,
            false, true, false, true, 50, false);
        float[] audio = new float[800];

        for(int index = 0; index < audio.length; index++)
        {
            audio[index] = (float)(Math.sin(2.0 * Math.PI * 440.0 * index / 8000.0) * 0.2);
        }

        return new CompletedAudioCall(snapshot, List.of(audio));
    }
}
