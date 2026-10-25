/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.*;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.StreamingAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.configuration.ConfigurationSnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.*;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real command adapters and persistence while independent activity writes cannot finish. */
class ConfigurationBulkPathResponsivenessTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void maximumExplicitAndWholeListMembershipBatchesDoNotWaitForActivity(@TempDir Path data) throws Exception
    {
        try(Fixture fixture = new Fixture(data, AliasAdministrationService.MAX_BULK_ALIASES + 1);
            var history = SdrTrunkDatabase.openHistory(fixture.database))
        {
            long scan = fixture.manager.getScanListModel().defaultScanList().getId();
            List<Long> selected = fixture.aliasIds.subList(0, AliasAdministrationService.MAX_BULK_ALIASES);
            String path = AliasAdminHttpController.SCAN_LISTS_PATH + "/" + scan + "/members";
            history.createStatement().execute("BEGIN IMMEDIATE");
            try
            {
                String baseline = fixture.aliases.getScanList(scan).editRevision();
                var write = fixture.sendAsync("PUT", path, Map.of("edit_revision", baseline,
                    "operation", "add", "alias_ids", selected));
                fixture.assertCatalogResponsive();
                var added = write.get(8, TimeUnit.SECONDS);
                assertEquals(200, added.statusCode(), added.body());
                assertEquals(selected.size(), fixture.aliases.getScanList(scan).aliasIds().size());
                assertFalse(fixture.aliases.getScanList(scan).aliasIds().contains(fixture.aliasIds.getLast()),
                    "an explicit batch must preserve owners outside the selection");

                var removed = fixture.sendAsync("PUT", path, Map.of("edit_revision",
                    fixture.aliases.getScanList(scan).editRevision(), "operation", "remove", "alias_scope",
                    Map.of("alias_list_id", fixture.listId)));
                fixture.assertCatalogResponsive();
                assertEquals(200, removed.get(8, TimeUnit.SECONDS).statusCode());
                assertTrue(fixture.aliases.getScanList(scan).aliasIds().isEmpty());
                var scoped = fixture.sendAsync("PUT", path, Map.of("edit_revision",
                    fixture.aliases.getScanList(scan).editRevision(), "operation", "add", "alias_scope",
                    Map.of("alias_list_id", fixture.listId)));
                fixture.assertCatalogResponsive();
                assertEquals(200, scoped.get(8, TimeUnit.SECONDS).statusCode());
                assertEquals(fixture.aliasIds.size(), fixture.aliases.getScanList(scan).aliasIds().size(),
                    "whole-list commands include owners beyond the explicit 10,000 selection limit");

                var oversized = fixture.send("PUT", path, Map.of("edit_revision",
                    fixture.aliases.getScanList(scan).editRevision(), "operation", "remove",
                    "alias_ids", fixture.aliasIds));
                assertEquals(400, oversized.statusCode(), oversized.body());
                assertEquals(fixture.aliasIds.size(), fixture.aliases.getScanList(scan).aliasIds().size());
            }
            finally { history.createStatement().execute("ROLLBACK"); }
        }
    }

    @Test
    void largeImportPreviewAndApplyStayIndependentOfActivityAndReturnBoundedPreview(@TempDir Path data)
        throws Exception
    {
        try(Fixture fixture = new Fixture(data, 0);
            var history = SdrTrunkDatabase.openHistory(fixture.database))
        {
            long p25 = fixture.manager.getAliasModel().aliasListDefinitions().stream()
                .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow().getId();
            String path = AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + p25 + "/transfer";
            StringBuilder csv = new StringBuilder("Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\r\n");
            for(int index = 1; index <= 10_000; index++)
                csv.append(index).append(",").append(Integer.toHexString(index)).append(",Imported ")
                    .append(index).append(",D,Imported dispatch,Law Dispatch,County\r\n");
            history.createStatement().execute("BEGIN IMMEDIATE");
            try
            {
                Map<String,Object> request = new LinkedHashMap<>(Map.of("action", "preview", "format",
                    "RADIOREFERENCE", "mode", "UPDATE_ADD"));
                var preview = fixture.sendCsv(path, request, csv.toString());
                fixture.assertCatalogResponsive();
                var response = preview.get(8, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode result = JSON.readTree(response.body()).path("data");
                assertEquals(10_000, result.path("total").asInt());
                assertEquals(100, result.path("rows").size(), "preview responses must remain bounded");
                assertEquals(10_000, result.at("/counts/added").asInt());
                request.put("action", "apply");
                request.put("revision", result.path("revision").asLong());
                request.put("digest", result.path("digest").asText());
                var apply = fixture.sendCsv(path, request, csv.toString());
                fixture.assertCatalogResponsive();
                var saved = apply.get(12, TimeUnit.SECONDS);
                assertEquals(200, saved.statusCode(), saved.body());
                assertEquals(10_000, fixture.repository.loadAliasConfiguration().aliases().size());
                // A reviewed request cannot accidentally be applied twice after the revision changes.
                assertEquals(409, fixture.sendCsv(path, request, csv.toString()).get(8, TimeUnit.SECONDS).statusCode());
                assertEquals(10_000, fixture.repository.loadAliasConfiguration().aliases().size());
            }
            finally { history.createStatement().execute("ROLLBACK"); }
        }
    }

    @Test
    void csvUploadPreservesLargeUnicodeOptionsAndRejectsMalformedMetadataBeforeMutation(@TempDir Path data)
        throws Exception
    {
        try(Fixture fixture = new Fixture(data, 0))
        {
            long p25 = fixture.manager.getAliasModel().aliasListDefinitions().stream()
                .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow().getId();
            String path = AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + p25 + "/transfer";
            String csv = "Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\r\n42,2a,Départ, D,North,Law,County\r\n"
                .replace(", D,", ",D,");
            List<String> names = new ArrayList<>();
            for(int index = 0; index < 500; index++) names.add("é".repeat(250) + index);
            Map<String,Object> metadata = Map.of("action", "preview", "format", "RADIOREFERENCE", "mode", "UPDATE_ADD",
                "defaults", Map.of("scan_lists", names, "streams", names));
            var preview = fixture.sendCsv(path, metadata, csv).get(3, TimeUnit.SECONDS);
            assertEquals(200, preview.statusCode(), preview.body());
            assertTrue(preview.body().contains(names.getFirst()), "UTF-8 options must be read without header-size limits");
            assertTrue(JSON.writeValueAsBytes(metadata).length > 64 * 1024);
            for(String invalid: List.of("{", "null", "{\"action\":\"preview\",\"unexpected\":1}",
                "{\"action\":\"preview\",\"action\":\"apply\"}"))
            {
                var response = fixture.sendMultipart(path, invalid, csv).get(3, TimeUnit.SECONDS);
                assertEquals(400, response.statusCode(), response.body());
            }
            var tooLarge = fixture.sendMultipart(path, "x".repeat(AliasTransferUpload.MAXIMUM_OPTIONS_BYTES + 1), csv)
                .get(3, TimeUnit.SECONDS);
            assertEquals(413, tooLarge.statusCode(), tooLarge.body());
            assertTrue(fixture.repository.loadAliasConfiguration().aliases().isEmpty());
        }
    }

    @Test
    void multipartAndJsonRetainActionableCsvValidationAndRejectMalformedUtf8(@TempDir Path data) throws Exception
    {
        try(Fixture fixture = new Fixture(data, 0))
        {
            long p25 = fixture.manager.getAliasModel().aliasListDefinitions().stream()
                .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow().getId();
            String path = AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + p25 + "/transfer";
            Map<String,Object> metadata = Map.of("action", "preview", "format", "RADIOREFERENCE", "mode", "UPDATE_ADD");
            for(String csv: List.of("wrong,header\r\n42,Dispatch\r\n",
                "Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\r\ninvalid,1,Dispatch,D,Name,Tag,Group\r\n"))
            {
                Map<String,Object> legacy = new LinkedHashMap<>(metadata);
                legacy.put("csv", csv);
                var json = fixture.send("POST", path, legacy);
                var multipart = fixture.sendCsv(path, metadata, csv).get(3, TimeUnit.SECONDS);
                assertEquals(400, json.statusCode(), json.body());
                assertEquals(400, multipart.statusCode(), multipart.body());
                assertEquals(JSON.readTree(json.body()).at("/error/message"),
                    JSON.readTree(multipart.body()).at("/error/message"));
            }
            byte[] invalidUtf8 = new byte[]{(byte)0xC3, (byte)0x28};
            var malformed = fixture.sendMultipartBytes(path, JSON.writeValueAsString(metadata), invalidUtf8)
                .get(3, TimeUnit.SECONDS);
            assertEquals(400, malformed.statusCode(), malformed.body());
            assertTrue(fixture.repository.loadAliasConfiguration().aliases().isEmpty());
        }
    }

    @Test
    void oversizedImportRowCountIsRefusedIdenticallyByJsonAndMultipartWithoutSaving(@TempDir Path data)
        throws Exception
    {
        try(Fixture fixture = new Fixture(data, 0))
        {
            long p25 = fixture.manager.getAliasModel().aliasListDefinitions().stream()
                .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow().getId();
            String path = AliasAdminHttpController.ALIAS_LISTS_PATH + "/" + p25 + "/transfer";
            String csv = "Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\r\n" +
                "1,1,Dispatch,D,,,\r\n".repeat(AliasTransferCsv.MAX_IMPORT_ROWS + 1);
            Map<String,Object> metadata = Map.of("action", "preview", "format", "RADIOREFERENCE", "mode", "UPDATE_ADD");
            Map<String,Object> legacy = new LinkedHashMap<>(metadata); legacy.put("csv", csv);
            var json = fixture.sendAsync("POST", path, legacy);
            fixture.assertCatalogResponsive();
            var jsonResult = json.get(5, TimeUnit.SECONDS);
            var multipart = fixture.sendCsv(path, metadata, csv);
            fixture.assertCatalogResponsive();
            var uploadResult = multipart.get(5, TimeUnit.SECONDS);
            assertEquals(400, jsonResult.statusCode(), jsonResult.body());
            assertEquals(400, uploadResult.statusCode(), uploadResult.body());
            String expected = "CSV must contain at most 100,000 aliases. Split it into smaller files and try again";
            assertEquals(expected, JSON.readTree(jsonResult.body()).at("/error/message").asText());
            assertEquals(expected, JSON.readTree(uploadResult.body()).at("/error/message").asText());
            assertTrue(fixture.repository.loadAliasConfiguration().aliases().isEmpty());
        }
    }

    @Test
    void channelOptionsWaitForCompleteAliasPublicationWithoutWaitingForTheOwner(@TempDir Path data) throws Exception
    {
        try(Fixture fixture = new Fixture(data, 0))
        {
            var before = fixture.manager.getChannelAdministrationService().options();
            CountDownLatch replaced = new CountDownLatch(1), release = new CountDownLatch(1);
            var command = fixture.manager.submitConfigurationTask(() -> {
                fixture.aliases.createAliasList("Pending channel choice", AliasListFamily.P25);
                replaced.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            });
            try
            {
                assertTrue(replaced.await(2, TimeUnit.SECONDS));
                var response = fixture.send("GET", ChannelAdminHttpController.PATH + "/options", null);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode options = JSON.readTree(response.body()).path("data");
                assertEquals(before.revision(), options.path("revision").asLong());
                assertFalse(response.body().contains("Pending channel choice"));
                fixture.assertCatalogResponsive();
            }
            finally { release.countDown(); command.get(3, TimeUnit.SECONDS); }
            var after = fixture.send("GET", ChannelAdminHttpController.PATH + "/options", null);
            assertTrue(after.body().contains("Pending channel choice"), after.body());
            assertEquals(fixture.manager.getChannelAdministrationService().currentRevision(),
                JSON.readTree(after.body()).at("/data/revision").asLong());
        }
    }

    @Test
    void largeReplacementAndBulkDeletionKeepMembershipsAtomicAndRollBackStorageFailure(@TempDir Path data)
        throws Exception
    {
        try(Fixture fixture = new Fixture(data, 0))
        {
            var p25 = fixture.manager.getAliasModel().aliasListDefinitions().stream()
                .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow();
            long scan = fixture.manager.getScanListModel().defaultScanList().getId();
            List<AliasImportService.Input> inputs = new ArrayList<>();
            for(int index = 1; index <= 10_000; index++)
            {
                Alias alias = new Alias("Replacement " + index);
                alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, index));
                inputs.add(new AliasImportService.Input(alias, true, true, false, null, null, null));
            }
            AliasImportService importer = new AliasImportService(fixture.aliases);
            importer.apply(importer.preview(p25.getId(), AliasImportService.Mode.UPDATE_ADD, inputs, null));
            assertEquals(10_000, fixture.aliases.getScanList(scan).aliasIds().size());
            long before = fixture.manager.getAliasConfigurationRevision();
            try(var connection = SdrTrunkDatabase.open(fixture.database); var statement = connection.createStatement())
            {
                statement.execute("CREATE TRIGGER refuse_bulk_alias_delete BEFORE DELETE ON alias BEGIN SELECT RAISE(ABORT, 'test failure'); END");
            }
            var replacement = importer.preview(p25.getId(), AliasImportService.Mode.REPLACE, inputs.subList(0, 1), null);
            assertThrows(AliasAdministrationService.PersistenceException.class, () -> importer.apply(replacement));
            assertEquals(before, fixture.manager.getAliasConfigurationRevision());
            assertEquals(10_000, fixture.repository.loadAliasConfiguration().aliases().size());
            assertEquals(10_000, fixture.aliases.getScanList(scan).aliasIds().size());
            try(var connection = SdrTrunkDatabase.open(fixture.database); var statement = connection.createStatement())
            { statement.execute("DROP TRIGGER refuse_bulk_alias_delete"); }
            importer.apply(replacement);
            assertEquals(1, fixture.repository.loadAliasConfiguration().aliases().size());
            var remaining = fixture.aliases.getScanList(scan).aliasIds();
            assertEquals(1, remaining.size());
            long remainingId = remaining.iterator().next();
            fixture.aliases.deleteAlias(remainingId, fixture.aliases.getAlias(remainingId).editRevision());
            assertTrue(fixture.repository.loadAliasConfiguration().aliases().isEmpty());
            assertTrue(fixture.aliases.getScanList(scan).aliasIds().isEmpty());

            importer.apply(importer.preview(p25.getId(), AliasImportService.Mode.UPDATE_ADD, inputs, null));
            assertEquals(10_000, fixture.aliases.getScanList(scan).aliasIds().size());
            try(var history = SdrTrunkDatabase.openHistory(fixture.database))
            {
                history.createStatement().execute("BEGIN IMMEDIATE");
                try
                {
                    var deleted = fixture.sendAsync("DELETE", AliasAdminHttpController.ALIAS_LISTS_PATH + "/" +
                        p25.getId(), Map.of("revision", fixture.manager.getAliasConfigurationRevision(), "confirmed", true));
                    fixture.assertCatalogResponsive();
                    var response = deleted.get(5, TimeUnit.SECONDS);
                    assertEquals(200, response.statusCode(), response.body());
                    assertTrue(fixture.repository.loadAliasConfiguration().aliases().isEmpty());
                    assertTrue(fixture.aliases.getScanList(scan).aliasIds().isEmpty());
                }
                finally { history.createStatement().execute("ROLLBACK"); }
            }
        }
    }

    @Test
    void maximumStreamAssignmentHasSingleFlightAdmissionAndCatalogNavigationWhileOwnerIsHeld(@TempDir Path data)
        throws Exception
    {
        try(Fixture fixture = new Fixture(data, 501);
            var history = SdrTrunkDatabase.openHistory(fixture.database))
        {
            var created = fixture.streams.save(null, "BROADCASTIFY_CALL", Map.of("name", "Bulk destination"), null);
            String id = created.configurationId();
            String path = StreamingAdminHttpController.PATH + "/" + id + "/aliases";
            String revision = fixture.streams.aliases(id, "", false, 0, 50).revision();
            history.createStatement().execute("BEGIN IMMEDIATE");
            try(OwnerGate gate = fixture.holdOwner())
            {
                CountDownLatch admitted = fixture.observeNextConfigurationCall();
                var write = fixture.sendAsync("POST", path, Map.of("revision", revision,
                    "add", fixture.aliasIds.subList(0, 500), "remove", List.of()));
                // The single-flight busy response proves the real first command has been admitted and is waiting.
                assertTrue(admitted.await(2, TimeUnit.SECONDS));
                assertEquals(429, fixture.send("GET", StreamingAdminHttpController.PATH, null).statusCode());
                fixture.assertCatalogResponsive();
                assertFalse(write.isDone(), "the write should still be waiting on the deliberately held owner");
                gate.release();
                var saved = write.get(8, TimeUnit.SECONDS);
                assertEquals(200, saved.statusCode(), saved.body());
                assertEquals(500, fixture.streams.aliases(id, "", true, 0, 100).total());
                assertFalse(fixture.manager.getAliasModel().getAliases().stream()
                    .filter(alias -> alias.getId() == fixture.aliasIds.getLast()).findFirst().orElseThrow()
                    .hasBroadcastConfiguration(id));
                assertEquals(400, fixture.send("POST", path, Map.of("revision",
                    fixture.streams.aliases(id, "", false, 0, 50).revision(), "add", fixture.aliasIds,
                    "remove", List.of())).statusCode());
            }
            finally { history.createStatement().execute("ROLLBACK"); }
        }
    }

    @Test
    void saturatedConfigurationQueueRejectsMembershipAndStreamWritesWithoutLaterMutation(@TempDir Path data)
        throws Exception
    {
        try(Fixture fixture = new Fixture(data, 1))
        {
            long scan = fixture.manager.getScanListModel().defaultScanList().getId();
            String baseline = fixture.aliases.getScanList(scan).editRevision();
            String id = fixture.streams.save(null, "BROADCASTIFY_CALL", Map.of("name", "Busy destination"), null)
                .configurationId();
            String streamRevision = fixture.streams.aliases(id, "", false, 0, 50).revision();
            List<CompletableFuture<Void>> queued = new ArrayList<>();
            try(OwnerGate gate = fixture.holdOwner())
            {
                for(int index = 0; index < 256; index++) queued.add(fixture.manager.submitConfigurationTask(() -> {}));
                assertTrue(queued.stream().noneMatch(CompletableFuture::isDone));
                var membership = fixture.send("PUT", AliasAdminHttpController.SCAN_LISTS_PATH + "/" + scan + "/members",
                    Map.of("edit_revision", baseline, "operation", "add", "alias_ids", fixture.aliasIds));
                assertEquals(429, membership.statusCode(), membership.body());
                assertEquals("1", membership.headers().firstValue("Retry-After").orElseThrow());
                var stream = fixture.send("POST", StreamingAdminHttpController.PATH + "/" + id + "/aliases",
                    Map.of("revision", streamRevision, "add", fixture.aliasIds, "remove", List.of()));
                assertEquals(429, stream.statusCode(), stream.body());
                fixture.assertCatalogResponsive();
                gate.release();
            }
            CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
            assertTrue(fixture.aliases.getScanList(scan).aliasIds().isEmpty(), "rejected work must not run later");
            assertEquals(0, fixture.streams.aliases(id, "", true, 0, 50).total());
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final Path database;
        final ConfigurationRepository repository;
        final ConfigurationManager manager;
        final java.util.concurrent.atomic.AtomicReference<CountDownLatch> observedCall =
            new java.util.concurrent.atomic.AtomicReference<>();
        final AliasAdministrationService aliases;
        final StreamingAdministrationService streams;
        final long listId;
        final List<Long> aliasIds;
        final HttpServer server;
        final ExecutorService workers = Executors.newFixedThreadPool(6);
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final URI origin;

        Fixture(Path data, int aliasCount) throws Exception
        {
            database = SdrTrunkDatabasePath.getDatabasePath(data);
            SdrTrunkTestDatabase.create(database);
            repository = new ConfigurationRepository(database);
            var initial = repository.load();
            var list = initial.aliasListDefinitions().stream().filter(item -> item.getFamily() == AliasListFamily.NBFM)
                .findFirst().orElseThrow();
            listId = list.getId();
            List<Alias> seeded = new ArrayList<>();
            for(int index = 1; index <= aliasCount; index++)
            {
                Alias alias = new Alias("Scoped bulk alias " + index);
                alias.setAliasListDefinition(list);
                alias.setMatchIdentifier(new Talkgroup(Protocol.NBFM, index));
                seeded.add(alias);
            }
            Channel channel = new Channel("Unrelated navigation channel");
            channel.setDecodeConfiguration(new DecodeConfigNBFM());
            channel.setAliasListDefinition(list);
            SourceConfigTuner source = new SourceConfigTuner(); source.setFrequency(450_000_000L);
            channel.setSourceConfiguration(source);
            repository.replace(new ConfigurationSnapshot(initial.aliasListDefinitions(), seeded,
                initial.scanListConfiguration(), List.of(channel), List.of()));
            DirectoryPreference directory = new DirectoryPreference(ignored -> {})
            { @Override public Path getDirectoryApplicationRoot() { return data; } };
            UserPreferences preferences = new UserPreferences()
            { @Override public DirectoryPreference getDirectoryPreference() { return directory; } };
            manager = new ConfigurationManager(preferences, null, new AliasModel(), null)
            {
                @Override public <T> T executeConfigurationTask(java.util.function.Supplier<T> operation)
                {
                    CountDownLatch observer = observedCall.getAndSet(null);
                    if(observer != null) observer.countDown();
                    return super.executeConfigurationTask(operation);
                }
            };
            manager.init();
            aliases = AliasAdministrationServiceTestSupport.create(manager);
            streams = new StreamingAdministrationService(manager);
            aliasIds = repository.loadAliasConfiguration().aliases().stream().map(Alias::getId).toList();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            var aliasController = new AliasAdminHttpController(aliases);
            var channelController = new ChannelAdminHttpController(ChannelAdministrationServiceTestSupport.create(manager));
            server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, aliasController::handle);
            server.createContext(AliasAdminHttpController.SCAN_LISTS_PATH, aliasController::handle);
            server.createContext(ChannelAdminHttpController.READ_PATH, channelController::handleCatalog);
            server.createContext(ChannelAdminHttpController.PATH, channelController::handle);
            server.createContext(StreamingAdminHttpController.PATH, new StreamingAdminHttpController(streams)::handle);
            server.start();
            origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        HttpResponse<String> send(String method, String path, Object body) throws Exception
        { return sendAsync(method, path, body).get(3, TimeUnit.SECONDS); }

        CompletableFuture<HttpResponse<String>> sendAsync(String method, String path, Object body) throws Exception
        {
            var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(15));
            if(body != null) request.header("Content-Type", "application/json");
            return client.sendAsync(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        }

        CompletableFuture<HttpResponse<String>> sendCsv(String path, Object metadata, String csv) throws Exception
        {
            return sendMultipart(path, JSON.writeValueAsString(metadata), csv);
        }

        CompletableFuture<HttpResponse<String>> sendMultipart(String path, String metadata, String csv)
            throws Exception
        { return sendMultipartBytes(path, metadata, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

        CompletableFuture<HttpResponse<String>> sendMultipartBytes(String path, String metadata, byte[] csv)
            throws Exception
        {
            String boundary = "fixture-" + UUID.randomUUID();
            String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"options\"\r\n\r\n" +
                metadata + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"aliases.csv\"\r\n" +
                "Content-Type: text/csv\r\n\r\n";
            byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=\"" + boundary + "\"");
            return client.sendAsync(request.POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(prefixBytes, csv, suffix))).build(),
                HttpResponse.BodyHandlers.ofString());
        }

        void assertCatalogResponsive() throws Exception
        {
            List<CompletableFuture<HttpResponse<String>>> reads = new ArrayList<>();
            for(int index = 0; index < 8; index++) reads.add(sendAsync("GET", ChannelAdminHttpController.READ_PATH, null));
            for(var read: reads)
            {
                var response = read.get(2, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(), response.body());
                assertEquals("Unrelated navigation channel", JSON.readTree(response.body()).at("/data/channels/0/name").asText());
            }
        }

        CountDownLatch observeNextConfigurationCall()
        {
            CountDownLatch observer = new CountDownLatch(1);
            observedCall.set(observer);
            return observer;
        }

        OwnerGate holdOwner() throws Exception { return new OwnerGate(manager); }

        @Override public void close()
        {
            server.stop(0); workers.shutdownNow();
            manager.quiesceAdministration();
            manager.getChannelProcessingManager().close(); manager.getBroadcastModel().close();
            manager.close(); manager.getRadioReferenceDirectoryService().close();
            client.close();
        }
    }

    private static final class OwnerGate implements AutoCloseable
    {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CompletableFuture<Void> held;
        OwnerGate(ConfigurationManager manager) throws Exception
        {
            CountDownLatch started = new CountDownLatch(1);
            held = manager.submitConfigurationTask(() -> {
                started.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
        }
        void release() { release.countDown(); }
        @Override public void close() throws Exception { release(); held.get(3, TimeUnit.SECONDS); }
    }
}
