/* Copyright (C) 2026 Dennis Sheirer; SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static io.github.dsheirer.stats.StatsDirectListenTest.call;
import static io.github.dsheirer.stats.StatsDirectListenTest.directQuery;
import static io.github.dsheirer.stats.StatsDirectListenTest.group;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebCapability;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises permission checks on the actual feed and cached-audio HTTP routes. */
class StatsWebDirectListenAccessTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "direct-listen-fixture-password";
    @TempDir Path mTemporaryDirectory;
    private StatsWebServerService mHttp;
    private StatsWebCallService mCalls;
    private WebAccessService mAccess;
    private HttpClient mClient;
    private URI mOrigin;
    private String mPreviousAssetOverride;

    @BeforeEach
    void startServer() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path assets = Files.createDirectories(mTemporaryDirectory.resolve("assets"));
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>Direct Listen fixture</title>");
        mPreviousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        mHttp = StatsWebTestRuntime.start(new TestPreferences(dataRoot));
        assertTrue(mHttp.getRuntimeState().running(), mHttp.getRuntimeState().statusMessage());
        mHttp.provisionOrResetPrimaryAdmin(PASSWORD.toCharArray());
        var accessField = StatsWebServerService.class.getDeclaredField("mWebAccessService");
        accessField.setAccessible(true);
        mAccess = (WebAccessService)accessField.get(mHttp);
        mAccess.createUser("listener", PASSWORD.toCharArray());
        ReceiverWebServices services = StatsWebTestRuntime.services(mHttp);
        services.mScanListModel.replaceConfiguration(new ScanListConfiguration(List.of(
            new ScanList(1, 0, "Default", null, true, true),
            new ScanList(2, 1, "Dispatch", null, true, false)), Map.of(101L, Set.of(2L)), Map.of()));
        mCalls = services.mWebCallService;
        mOrigin = URI.create("http://127.0.0.1:" + mHttp.getRuntimeState().port());
        mClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void stopServer()
    {
        if(mHttp != null) mHttp.close();
        if(mPreviousAssetOverride == null) System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        else System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, mPreviousAssetOverride);
    }

    @Test
    void requiresBothPermissionsAndProtectsDirectOnlyAudioFromOrdinaryListeners() throws Exception
    {
        String listener = login("listener"), admin = login("admin");
        String directFeed = StatsApiV1.CALLS_FEED + "?" + directQuery(group(4400));
        assertEquals(403, get(directFeed, null).statusCode());
        assertEquals(403, get(directFeed, listener).statusCode());
        assertEquals(200, get(directFeed, admin).statusCode());
        assertEquals(200, get(StatsApiV1.CALLS_FEED + "?scan_list_id=2", listener).statusCode());

        mAccess.setCapabilityTier(WebCapability.DIRECT_LISTEN, AccessTier.PUBLIC);
        mAccess.setCapabilityTier(WebCapability.WEB_AUDIO_LISTEN, AccessTier.ADMIN);
        assertEquals(403, get(directFeed, null).statusCode(), "Direct Listen alone must not grant audio access");
        assertEquals(403, get(directFeed, listener).statusCode());
        mAccess.setCapabilityTier(WebCapability.WEB_AUDIO_LISTEN, AccessTier.PUBLIC);
        assertEquals(200, get(directFeed, null).statusCode());

        long generation = mCalls.tryAcquireFeed();
        try
        {
            assertTrue(mCalls.refreshDirectInterests(Set.of(group(4400))));
            mCalls.receive(call(APCO25Talkgroup.create(4400), Set.of(), false, 0));
            await(() -> count("published_calls") >= 1);
            mCalls.receive(call(APCO25Talkgroup.create(4402), Set.of(101L), false, 0));
            await(() -> count("published_calls") >= 2);
            var direct = mCalls.feed(Set.of(), Set.of(group(4400)), 0L, 0, TimeUnit.NANOSECONDS, generation);
            var published = mCalls.feed(Set.of(2L), 0L, 0, TimeUnit.NANOSECONDS, generation);
            String directAudio = String.valueOf(direct.calls().getFirst().get("audio_url"));
            String publishedAudio = String.valueOf(published.calls().getFirst().get("audio_url"));

            mAccess.setCapabilityTier(WebCapability.DIRECT_LISTEN, AccessTier.ADMIN);
            assertEquals(403, get(directAudio, listener).statusCode());
            assertEquals(403, get(directAudio, null).statusCode());
            HttpResponse<String> allowed = get(directAudio, admin);
            assertEquals(200, allowed.statusCode());
            assertEquals("audio/wav", allowed.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-store, no-transform", allowed.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(200, get(publishedAudio, listener).statusCode());
            assertEquals(200, get(publishedAudio, null).statusCode());

            mAccess.setCapabilityTier(WebCapability.DIRECT_LISTEN, AccessTier.USER);
            assertEquals(200, get(directAudio, listener).statusCode());
            assertEquals(403, get(directAudio, null).statusCode());
            assertEquals(200, get(directFeed, listener).statusCode());
            mAccess.setCapabilityTier(WebCapability.WEB_AUDIO_LISTEN, AccessTier.ADMIN);
            assertEquals(403, get(directAudio, listener).statusCode());
            assertEquals(403, get(publishedAudio, listener).statusCode());
        }
        finally
        {
            mCalls.releaseFeed(generation);
        }
    }

    @Test
    void rechecksEachPermissionAfterAWaitingDirectFeedWakes() throws Exception
    {
        String listener = login("listener");
        mAccess.setCapabilityTier(WebCapability.DIRECT_LISTEN, AccessTier.USER);
        for(WebCapability revoked: List.of(WebCapability.DIRECT_LISTEN, WebCapability.WEB_AUDIO_LISTEN))
        {
            mAccess.setCapabilityTier(WebCapability.DIRECT_LISTEN, AccessTier.USER);
            mAccess.setCapabilityTier(WebCapability.WEB_AUDIO_LISTEN, AccessTier.PUBLIC);
            String edge = JSON.readTree(get(StatsApiV1.CALLS_FEED + "?" + directQuery(group(4400)), listener).body())
                .at("/data/cursor").asText();
            String path = StatsApiV1.CALLS_FEED + "?" + directQuery(group(4400)) + "&cursor=" + edge;
            var waiting = mClient.sendAsync(request(path, listener), HttpResponse.BodyHandlers.ofString());
            await(() -> count("active_feeds") == 1);
            mAccess.setCapabilityTier(revoked, AccessTier.ADMIN);
            mCalls.receive(call(APCO25Talkgroup.create(4400), Set.of(), false, 0));
            HttpResponse<String> response = waiting.get(4, TimeUnit.SECONDS);
            assertEquals(403, response.statusCode(), revoked.id());
            assertFalse(JSON.readTree(response.body()).has("data"), response.body());
            assertFalse(response.body().contains("audio_url"));
            await(() -> count("active_feeds") == 0);
        }
    }

    private long count(String key)
    {
        return ((Number)mCalls.observerStatus().get(key)).longValue();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "The call feed did not reach the expected state");
    }

    private HttpRequest request(String path, String cookie)
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(mOrigin.resolve(path)).timeout(Duration.ofSeconds(6)).GET();
        if(cookie != null) request.header("Cookie", cookie);
        return request.build();
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception
    {
        return mClient.send(request(path, cookie), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String username) throws Exception
    {
        HttpResponse<String> response = mClient.send(HttpRequest.newBuilder(mOrigin.resolve("/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(3)).header("Origin", mOrigin.toString())
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                JSON.writeValueAsString(Map.of("username", username, "password", PASSWORD)))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static final class TestPreferences extends UserPreferences
    {
        private final ApplicationPreference mApplication = new ApplicationPreference(ignored -> {})
        {
            @Override public boolean isStatsWebServerEnabled() { return true; }
            @Override public int getStatsWebServerPort() { return 0; }
            @Override public boolean isStatsWebServerAnyIpEnabled() { return false; }
            @Override public boolean isStatsWebServerHttpsEnabled() { return false; }
        };
        private final DirectoryPreference mDirectory;
        private TestPreferences(Path root)
        {
            mDirectory = new DirectoryPreference(ignored -> {})
            {
                @Override public Path getDirectoryApplicationRoot() { return root; }
            };
        }
        @Override public ApplicationPreference getApplicationPreference() { return mApplication; }
        @Override public DirectoryPreference getDirectoryPreference() { return mDirectory; }
    }
}
