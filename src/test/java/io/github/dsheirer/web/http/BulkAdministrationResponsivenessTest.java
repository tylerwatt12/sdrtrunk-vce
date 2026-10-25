/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.configuration.ConfigurationSnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.net.InetAddress;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BulkAdministrationResponsivenessTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void largeAliasAndMaximumChannelBatchesLeaveHttpCatalogResponsiveWithHistoryWriterHeld(@TempDir Path data)
        throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(data);
        SdrTrunkTestDatabase.create(database);
        ConfigurationRepository repository = new ConfigurationRepository(database);
        var initial = repository.load();
        var list = initial.aliasListDefinitions().stream().filter(item -> item.getFamily() == AliasListFamily.NBFM)
            .findFirst().orElseThrow();
        List<Alias> aliases = new ArrayList<>();
        for(int index = 1; index <= 2000; index++)
        {
            Alias alias = new Alias("Bulk Alias " + index);
            alias.setAliasListDefinition(list);
            alias.setMatchIdentifier(new Talkgroup(Protocol.NBFM, index));
            aliases.add(alias);
        }
        List<Channel> channels = new ArrayList<>();
        for(int index = 0; index < ChannelAdministrationService.MAXIMUM_BULK_CHANNELS; index++)
        {
            Channel channel = new Channel("Bulk channel " + index);
            channel.setDecodeConfiguration(new DecodeConfigNBFM());
            channel.setAliasListDefinition(list);
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(450_000_000L + index * 12_500L);
            channel.setSourceConfiguration(source);
            channels.add(channel);
        }
        repository.replace(new ConfigurationSnapshot(initial.aliasListDefinitions(), aliases, initial.scanListConfiguration(),
            channels, List.of()));
        DirectoryPreference directory = new DirectoryPreference(ignored -> {})
        { @Override public Path getDirectoryApplicationRoot() { return data; } };
        UserPreferences preferences = new UserPreferences()
        { @Override public DirectoryPreference getDirectoryPreference() { return directory; } };
        ConfigurationManager manager = new ConfigurationManager(preferences, null, new AliasModel(), null);
        var executor = Executors.newFixedThreadPool(4);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        try
        {
            manager.init();
            var channelService = ChannelAdministrationServiceTestSupport.create(manager);
            var aliasService = AliasAdministrationServiceTestSupport.create(manager);
            var channelController = new ChannelAdminHttpController(channelService);
            var aliasController = new AliasAdminHttpController(aliasService);
            server.setExecutor(executor);
            server.createContext(ChannelAdminHttpController.PATH, channelController::handle);
            server.createContext(ChannelAdminHttpController.READ_PATH, channelController::handleCatalog);
            server.createContext(AliasAdminHttpController.ALIASES_PATH, aliasController::handle);
            server.start();
            URI origin = URI.create("http://localhost:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            try(var history = SdrTrunkDatabase.openHistory(database))
            {
                history.createStatement().execute("BEGIN IMMEDIATE");
                try
                {
                    var aliasIds = repository.loadAliasConfiguration().aliases().stream().map(Alias::getId).toList();
                    var edited = client.sendAsync(post(origin.resolve(AliasAdminHttpController.BULK_PATH), Map.of(
                        "revision", manager.getAliasConfigurationRevision(), "alias_ids", aliasIds, "color", 0x123456)),
                        HttpResponse.BodyHandlers.ofString());
                    //Use the actual HTTP catalog concurrently with the large write, not a stub status endpoint.
                    List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> readers = new ArrayList<>();
                    for(int request = 0; request < 20; request++) readers.add(client.sendAsync(
                        HttpRequest.newBuilder(origin.resolve(ChannelAdminHttpController.READ_PATH))
                            .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString()));
                    for(var reader: readers)
                    {
                        var response = reader.get(4, TimeUnit.SECONDS);
                        assertEquals(200, response.statusCode(), response.body());
                        assertEquals(100, JSON.readTree(response.body()).at("/data/channels").size());
                    }
                    var response = edited.get(5, TimeUnit.SECONDS);
                    assertEquals(200, response.statusCode(), response.body());
                    assertTrue(repository.loadAliasConfiguration().aliases().stream()
                        .allMatch(alias -> alias.getColor() == 0x123456));

                    var removed = client.send(post(origin.resolve(ChannelAdminHttpController.ACTIONS_PATH), Map.of(
                        "revision", channelService.currentRevision(), "action", "DELETE", "configuration_ids",
                        channels.stream().map(Channel::getConfigurationId).toList())), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, removed.statusCode(), removed.body());
                    assertTrue(channelService.currentCatalog().channels().isEmpty());
                    assertEquals(200, client.send(HttpRequest.newBuilder(origin.resolve(ChannelAdminHttpController.READ_PATH))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                }
                finally { history.createStatement().execute("ROLLBACK"); }
            }
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            manager.quiesceAdministration();
            manager.getChannelProcessingManager().close();
            manager.getBroadcastModel().close();
            manager.close();
            manager.getRadioReferenceDirectoryService().close();
        }
    }

    private static HttpRequest post(URI uri, Object body) throws Exception
    {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }
}
