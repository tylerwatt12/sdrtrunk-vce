/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.stats.activity.ReceiverActivityMaintenance;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real HTTP/command boundary with deterministic stand-ins for hardware and maintenance. */
class ChannelBulkCommandResponsivenessTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void maximumLifecycleBatchesAllowBrowsingRejectDuplicatesAndReportPartialFailure(@TempDir Path data)
        throws Exception
    {
        try(Fixture fixture = new Fixture(data); var history = SdrTrunkDatabase.openHistory(fixture.database))
        {
            history.createStatement().execute("BEGIN IMMEDIATE");
            try
            {
                fixture.processing.failedStart = fixture.ids.get(50);
                for(String action: List.of("START", "STOP"))
                {
                    fixture.processing.arm();
                    var batch = fixture.client.sendAsync(fixture.action(action, fixture.ids),
                        HttpResponse.BodyHandlers.ofString());
                    try
                    {
                        assertTrue(fixture.processing.entered.await(2, TimeUnit.SECONDS));
                        fixture.assertBrowsing();
                        var duplicate = fixture.send(fixture.action(action, fixture.ids));
                        assertEquals(429, duplicate.statusCode(), duplicate.body());
                        assertFalse(batch.isDone(), "The controlled hardware dependency must still be held");
                    }
                    finally { fixture.processing.release.countDown(); }
                    var response = batch.get(3, TimeUnit.SECONDS);
                    assertEquals(200, response.statusCode(), response.body());
                    JsonNode results = JSON.readTree(response.body()).at("/data/results");
                    assertEquals(ChannelAdministrationService.MAXIMUM_BULK_CHANNELS, results.size());
                    int failed = 0;
                    for(JsonNode result: results) if(!result.path("success").asBoolean()) failed++;
                    assertEquals("START".equals(action) ? 1 : 0, failed);
                }
                assertEquals(100, fixture.processing.starts.get());
                assertEquals(99, fixture.processing.stops.get());
                assertTrue(fixture.processing.running.isEmpty());

                //The other maximum-size channel mutations share admission and must not reserve history's writer.
                assertEquals(200, fixture.send(fixture.action("ENABLE_AUTO_START", fixture.ids)).statusCode());
                assertEquals(200, fixture.send(fixture.action("DISABLE_AUTO_START", fixture.ids)).statusCode());
                assertEquals(200, fixture.send(fixture.action("CLONE", fixture.ids)).statusCode());
                assertEquals(200, fixture.channels.currentCatalog().channels().size());
                var tooMany = new ArrayList<>(fixture.ids);
                tooMany.add(fixture.channels.currentCatalog().channels().getLast().configurationId());
                assertEquals(400, fixture.send(fixture.action("START", tooMany)).statusCode());
                assertEquals(100, fixture.processing.starts.get(), "Oversized batches must not reach hardware");
            }
            finally { history.createStatement().execute("ROLLBACK"); }
        }
    }

    @Test
    void waitingForStatisticsMaintenanceLeavesBrowsingAvailableAndReleasesAdmissionAfterFailure(@TempDir Path data)
        throws Exception
    {
        try(Fixture fixture = new Fixture(data))
        {
            String id = fixture.ids.getFirst();
            for(boolean fail: List.of(true, false))
            {
                var clear = fixture.client.sendAsync(HttpRequest.newBuilder(fixture.origin.resolve(
                    ChannelAdminHttpController.PATH + "/" + id + "/statistics/clear"))
                    .timeout(Duration.ofSeconds(4)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
                StatsDatabaseMaintenanceRequest request = fixture.maintenance.poll(2, TimeUnit.SECONDS);
                assertNotNull(request);
                try
                {
                    fixture.assertBrowsing();
                    assertEquals(429, fixture.send(fixture.action("CLONE", List.of(id))).statusCode());
                    assertFalse(clear.isDone(), "The actual maintenance response must still be pending");
                }
                finally
                {
                    if(fail) request.result().completeExceptionally(new IllegalStateException("Controlled maintenance failure"));
                    else request.result().complete(new ReceiverActivityMaintenance.Result(request.operation(),
                        123, "ok", 0, 0, 0, 0));
                }
                var response = clear.get(3, TimeUnit.SECONDS);
                assertEquals(fail ? 503 : 200, response.statusCode(), response.body());
                assertEquals(200, fixture.send(fixture.action("CLONE", List.of(id))).statusCode(),
                    "Both success and failure must release channel command admission");
            }
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        private final Path database;
        private final BlockingQueue<StatsDatabaseMaintenanceRequest> maintenance = new LinkedBlockingQueue<>();
        private final FakeProcessing processing;
        private final ConfigurationManager manager;
        private final ChannelProcessingManager originalProcessing;
        private final ChannelAdministrationService channels;
        private final List<String> ids;
        private final WebRequestExecutor executor = new WebRequestExecutor(64, () -> fail("Unexpected saturation"));
        private final HttpServer server;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        private final URI origin;

        private Fixture(Path data) throws Exception
        {
            database = SdrTrunkDatabasePath.getDatabasePath(data);
            SdrTrunkTestDatabase.create(database);
            DirectoryPreference directory = new DirectoryPreference(ignored -> {})
            { @Override public Path getDirectoryApplicationRoot() { return data; } };
            UserPreferences preferences = new UserPreferences()
            { @Override public DirectoryPreference getDirectoryPreference() { return directory; } };
            AliasModel aliases = new AliasModel();
            processing = new FakeProcessing(aliases, preferences);
            manager = new ConfigurationManager(preferences, null, aliases, null, maintenance::add)
            { @Override public ChannelProcessingManager getChannelProcessingManager() { return processing; } };
            var original = ConfigurationManager.class.getDeclaredField("mChannelProcessingManager");
            original.setAccessible(true);
            originalProcessing = (ChannelProcessingManager)original.get(manager);
            ConfigurationRepository repository = new ConfigurationRepository(database);
            var list = repository.load().aliasListDefinitions().stream()
                .filter(value -> value.getFamily() == AliasListFamily.NBFM).findFirst().orElseThrow();
            List<Channel> seeded = new ArrayList<>();
            for(int index = 0; index < ChannelAdministrationService.MAXIMUM_BULK_CHANNELS; index++)
            {
                Channel channel = new Channel("Disposable channel " + index);
                channel.setDecodeConfiguration(new DecodeConfigNBFM());
                channel.setAliasListDefinition(list);
                SourceConfigTuner source = new SourceConfigTuner();
                source.setFrequency(450_000_000L + index * 12_500L);
                channel.setSourceConfiguration(source);
                seeded.add(channel);
            }
            repository.commitChannelConfiguration(new ChannelConfigurationSnapshot(seeded));
            manager.init();
            channels = ChannelAdministrationServiceTestSupport.create(manager);
            ids = seeded.stream().map(Channel::getConfigurationId).toList();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            ChannelAdminHttpController controller = new ChannelAdminHttpController(channels);
            server.createContext(ChannelAdminHttpController.PATH, controller::handle);
            server.createContext(ChannelAdminHttpController.READ_PATH, controller::handleCatalog);
            server.start();
            origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private HttpRequest action(String action, List<String> selected) throws Exception
        {
            return post(ChannelAdminHttpController.ACTIONS_PATH, Map.of("action", action,
                "configuration_ids", selected, "revision", channels.currentRevision()));
        }

        private HttpRequest post(String path, Object body) throws Exception
        {
            return HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(4))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        }

        private HttpResponse<String> send(HttpRequest request) throws Exception
        { return client.send(request, HttpResponse.BodyHandlers.ofString()); }

        private void assertBrowsing() throws Exception
        {
            List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> reads = new ArrayList<>();
            for(int index = 0; index < 20; index++) reads.add(client.sendAsync(HttpRequest.newBuilder(
                origin.resolve(ChannelAdminHttpController.READ_PATH)).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            for(var read: reads)
            {
                var response = read.get(3, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(channels.currentCatalog().channels().size(),
                    JSON.readTree(response.body()).at("/data/channels").size());
            }
        }

        @Override public void close() throws Exception
        {
            processing.release.countDown();
            server.stop(0);
            executor.shutdownNow();
            client.close();
            manager.quiesceAdministration();
            processing.close();
            processing.marker.dispose();
            originalProcessing.close();
            manager.getBroadcastModel().close();
            manager.close();
            manager.getRadioReferenceDirectoryService().close();
        }
    }

    /** Only test state changes: no decoder, source, tuner or receiver is started. */
    private static final class FakeProcessing extends ChannelProcessingManager
    {
        private final Set<String> running = ConcurrentHashMap.newKeySet();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();
        private final ProcessingChain marker;
        private volatile String failedStart;
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(0);

        private FakeProcessing(AliasModel aliases, UserPreferences preferences)
        { super(null, null, aliases, preferences); marker = new ProcessingChain(new Channel("Test marker"), aliases); }
        private void arm() { entered = new CountDownLatch(1); release = new CountDownLatch(1); }
        private void awaitDependency() throws ChannelException
        {
            entered.countDown();
            try
            {
                if(!release.await(5, TimeUnit.SECONDS)) throw new ChannelException("Test dependency timed out");
            }
            catch(InterruptedException failure)
            { Thread.currentThread().interrupt(); throw new ChannelException("Test interrupted"); }
        }
        @Override public void start(Channel channel) throws ChannelException
        {
            starts.incrementAndGet();
            awaitDependency();
            if(channel.getConfigurationId().equals(failedStart)) throw new ChannelException("Controlled start failure");
            running.add(channel.getConfigurationId());
        }
        @Override public void stop(Channel channel) throws ChannelException
        { stops.incrementAndGet(); awaitDependency(); running.remove(channel.getConfigurationId()); }
        @Override public List<ProcessingChain> getProcessingChainsByConfiguration(String id, Long frequency)
        { return running.contains(id) ? List.of(marker) : List.of(); }
    }
}
