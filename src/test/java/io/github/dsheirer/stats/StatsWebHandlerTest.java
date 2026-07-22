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
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
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
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebHandlerTest
{
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
        mHandler = new StatsWebHandler(assets, database, mLiveService, mWebCallService,
            () -> Map.of("server", Map.of("enabled", true)), mAccessPolicy, subjectResolver);
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
