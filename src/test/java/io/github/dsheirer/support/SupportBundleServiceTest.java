/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupportBundleServiceTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void cancelledJobCapacityCanBeReusedWhileTheOldGeneratorStillOwnsItsCleanup() throws Exception
    {
        CountDownLatch healthStarted = new CountDownLatch(1);
        CountDownLatch releaseHealth = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try(SupportBundleService service = new SupportBundleService(mTemporaryDirectory.resolve("unused.sqlite"),
            mTemporaryDirectory.resolve("logs"), () -> {
                if(calls.incrementAndGet() == 1)
                {
                    healthStarted.countDown();
                    while(releaseHealth.getCount() > 0)
                    {
                        try { releaseHealth.await(); }
                        catch(InterruptedException ignored) { } // Deliberately hold cancellation cleanup in flight.
                    }
                }
                return Map.of();
            }))
        {
            String active = service.generate(smallRequest());
            assertTrue(healthStarted.await(5, TimeUnit.SECONDS));
            for(int index = 0; index < 7; index++)
            {
                String id = service.generate(smallRequest());
                service.cancel(id);
                assertEquals("cancelled", service.status(id).state());
            }
            service.cancel(active);
            assertEquals("cancelled", service.status(active).state());
            String next = service.generate(smallRequest());
            assertEquals("queued", service.status(next).state());
            // Reusing cancelled status slots must also remove cancelled queued FutureTasks. A held worker
            // must not accumulate them or exhaust the task queue after another eight cancellations.
            for(int index = 0; index < 16; index++)
            {
                service.cancel(next);
                next = service.generate(smallRequest());
                assertEquals("queued", service.status(next).state());
            }
            releaseHealth.countDown();
            assertEquals("ready", awaitReady(service, next).state());
            assertEquals(2, calls.get(), "Cancelled queued jobs never start");
        }
        finally { releaseHealth.countDown(); }
    }

    @Test
    void failedJobCapacityCanBeReusedWithoutDiscardingRecentFailureStatusesEarly() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        try(SupportBundleService service = new SupportBundleService(mTemporaryDirectory.resolve("unused.sqlite"),
            mTemporaryDirectory.resolve("logs"), () -> {
                if(calls.incrementAndGet() <= 8) throw new IllegalStateException("Synthetic status failure");
                return Map.of();
            }))
        {
            var failures = new ArrayList<String>();
            for(int index = 0; index < 8; index++)
            {
                String id = service.generate(smallRequest());
                assertEquals("failed", awaitReady(service, id).state());
                failures.add(id);
            }
            failures.forEach(id -> assertEquals("failed", service.status(id).state()));
            assertEquals("ready", awaitReady(service, service.generate(smallRequest())).state());
        }
    }

    @Test
    void uploadedJobCapacityCanBeReusedAfterTheArchiveWasRemoved() throws Exception
    {
        HttpServer upload = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upload.createContext("/upload", exchange -> {
            try(exchange)
            {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        upload.start();
        try(SupportBundleService service = new SupportBundleService(mTemporaryDirectory.resolve("unused.sqlite"),
            mTemporaryDirectory.resolve("logs"), Map::of,
            URI.create("http://127.0.0.1:" + upload.getAddress().getPort() + "/upload"), HttpClient.newHttpClient()))
        {
            var uploaded = new ArrayList<String>();
            for(int index = 0; index < 8; index++)
            {
                String id = service.generate(smallRequest());
                assertEquals("ready", awaitReady(service, id).state());
                Path output = service.download(id).path();
                service.submit(id);
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while("uploading".equals(service.status(id).state()) && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals("uploaded", service.status(id).state());
                assertFalse(Files.exists(output));
                uploaded.add(id);
            }
            uploaded.forEach(id -> assertEquals("uploaded", service.status(id).state()));
            assertEquals("ready", awaitReady(service, service.generate(smallRequest())).state());
        }
        finally { upload.stop(0); }
    }

    @Test
    void capacityEvictionPreservesReadyDownloadsAndActiveGeneration() throws Exception
    {
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try(SupportBundleService service = new SupportBundleService(mTemporaryDirectory.resolve("unused.sqlite"),
            mTemporaryDirectory.resolve("logs"), () -> {
                if(calls.incrementAndGet() == 8)
                {
                    activeStarted.countDown();
                    try { releaseActive.await(); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                }
                return Map.of();
            }))
        {
            var ready = new ArrayList<String>();
            for(int index = 0; index < 7; index++)
            {
                String id = service.generate(smallRequest());
                assertEquals("ready", awaitReady(service, id).state());
                ready.add(id);
            }
            String active = service.generate(smallRequest());
            assertTrue(activeStarted.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> service.generate(smallRequest()));
            for(String id: ready) assertTrue(Files.isRegularFile(service.download(id).path()));
            assertEquals("generating", service.status(active).state());
            service.cancel(ready.getFirst());
            String next = service.generate(smallRequest());
            assertEquals("queued", service.status(next).state());
            assertThrows(IllegalArgumentException.class, () -> service.status(ready.getFirst()));
            for(String id: ready.subList(1, ready.size())) assertTrue(Files.isRegularFile(service.download(id).path()));
            assertEquals("generating", service.status(active).state());
            releaseActive.countDown();
            assertEquals("ready", awaitReady(service, active).state());
            assertEquals("ready", awaitReady(service, next).state());
        }
        finally { releaseActive.countDown(); }
    }

    @Test
    void cancellingAStalledUploadReleasesTheWorkerForTheNextReport() throws Exception
    {
        CountDownLatch uploadStarted = new CountDownLatch(1);
        CountDownLatch releaseUpload = new CountDownLatch(1);
        HttpServer upload = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var uploadExecutor = Executors.newCachedThreadPool();
        upload.setExecutor(uploadExecutor);
        upload.createContext("/upload", exchange -> {
            try(exchange)
            {
                exchange.getRequestBody().readAllBytes();
                uploadStarted.countDown();
                try { releaseUpload.await(10, TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                exchange.sendResponseHeaders(200, -1);
            }
        });
        upload.start();
        URI destination = URI.create("http://127.0.0.1:" + upload.getAddress().getPort() + "/upload");
        try(SupportBundleService service = new SupportBundleService(mTemporaryDirectory.resolve("unused.sqlite"),
            mTemporaryDirectory.resolve("logs"), Map::of, destination, HttpClient.newHttpClient()))
        {
            String first = service.generate(smallRequest());
            assertEquals("ready", awaitReady(service, first).state());
            Path output = service.download(first).path();
            service.submit(first);
            assertTrue(uploadStarted.await(5, TimeUnit.SECONDS));
            assertEquals("uploading", service.status(first).state());
            String next = service.generate(smallRequest());
            assertEquals("queued", service.status(next).state());
            service.cancel(first);
            assertEquals("cancelled", service.status(first).state());
            // The upload server still has not answered. Cancellation must release the sole support worker.
            assertEquals("ready", awaitReady(service, next).state());
            assertEquals(1L, releaseUpload.getCount());
            assertFalse(Files.exists(output));
            assertThrows(IllegalStateException.class, () -> service.download(first));
            assertThrows(IllegalStateException.class, () -> service.submit(first));
            assertEquals("cancelled", service.status(first).state(), "A late upload failure cannot restore Ready");
        }
        finally
        {
            releaseUpload.countDown();
            upload.stop(0);
            uploadExecutor.shutdownNow();
        }
    }

    @Test
    void queuedCancellationAndCloseDoNotStartCancelledWorkOrResurrectItsStatus() throws Exception
    {
        CountDownLatch healthStarted = new CountDownLatch(1);
        CountDownLatch healthInterrupted = new CountDownLatch(1);
        AtomicInteger healthCalls = new AtomicInteger();
        SupportBundleService service = new SupportBundleService(mTemporaryDirectory.resolve("unused.sqlite"),
            mTemporaryDirectory.resolve("logs"), () -> {
                healthCalls.incrementAndGet();
                healthStarted.countDown();
                try { new CountDownLatch(1).await(); }
                catch(InterruptedException exception) { healthInterrupted.countDown(); }
                return Map.of();
            });
        try
        {
            String active = service.generate(smallRequest());
            assertTrue(healthStarted.await(5, TimeUnit.SECONDS));
            String queued = service.generate(smallRequest());
            assertEquals("queued", service.status(queued).state());
            service.cancel(queued);
            assertEquals("cancelled", service.status(queued).state());
            assertThrows(IllegalStateException.class, () -> service.download(queued));
            service.close();
            assertTrue(healthInterrupted.await(5, TimeUnit.SECONDS));
            assertEquals("cancelled", service.status(active).state());
            assertEquals("cancelled", service.status(queued).state());
            assertEquals(1, healthCalls.get(), "Cancelled queued reports must never run their data suppliers");
            assertThrows(IllegalStateException.class, () -> service.generate(smallRequest()));
            assertThrows(IllegalStateException.class, () -> service.submit(active));
        }
        finally { service.close(); }
    }

    @Test
    void cancellingGenerationLetsTheNextReportProceedWithoutResurrectingTheCancelledReports() throws Exception
    {
        CountDownLatch healthStarted = new CountDownLatch(1);
        AtomicInteger healthCalls = new AtomicInteger();
        try(SupportBundleService service = new SupportBundleService(mTemporaryDirectory.resolve("unused.sqlite"),
            mTemporaryDirectory.resolve("logs"), () -> {
                if(healthCalls.incrementAndGet() == 1)
                {
                    healthStarted.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch(InterruptedException ignored) { }
                }
                return Map.of();
            }))
        {
            String active = service.generate(smallRequest());
            assertTrue(healthStarted.await(5, TimeUnit.SECONDS));
            String cancelled = service.generate(smallRequest());
            service.cancel(cancelled);
            String next = service.generate(smallRequest());
            service.cancel(active);
            assertEquals("ready", awaitReady(service, next).state());
            assertEquals("cancelled", service.status(active).state());
            assertEquals("cancelled", service.status(cancelled).state());
            assertEquals(2, healthCalls.get());
        }
    }

    @Test
    void concurrentDownloadAdmissionAndCancellationNeverPublishANullPath() throws Exception
    {
        try(SupportBundleService service = new SupportBundleService(mTemporaryDirectory.resolve("unused.sqlite"),
            mTemporaryDirectory.resolve("logs"), Map::of);
            var requests = Executors.newVirtualThreadPerTaskExecutor())
        {
            for(int attempt = 0; attempt < 4; attempt++)
            {
                String id = service.generate(smallRequest());
                assertEquals("ready", awaitReady(service, id).state());
                Path output = service.download(id).path();
                CountDownLatch start = new CountDownLatch(1);
                var downloads = java.util.stream.IntStream.range(0, 64).mapToObj(index ->
                    CompletableFuture.runAsync(() -> {
                        try
                        {
                            start.await();
                            // Once admitted, an explicit cancel may remove the file before a client opens it.
                            // Admission itself must return complete metadata or the normal not-ready error.
                            var download = service.download(id);
                            assertNotNull(download.path());
                            assertNotNull(download.fileName());
                            assertTrue(download.bytes() > 0);
                        }
                        catch(IllegalStateException expected) { }
                        catch(InterruptedException exception) { throw new AssertionError(exception); }
                    }, requests)).toList();
                var cancellation = CompletableFuture.runAsync(() -> {
                    try { start.await(); }
                    catch(InterruptedException exception) { throw new AssertionError(exception); }
                    service.cancel(id);
                }, requests);
                start.countDown();
                CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
                cancellation.get(3, TimeUnit.SECONDS);
                assertEquals("cancelled", service.status(id).state());
                assertFalse(Files.exists(output));
                assertThrows(IllegalStateException.class, () -> service.download(id));
            }
        }
    }

    @Test
    void rejectedUploadRemainsDownloadableAndDoesNotBlockFollowingReports() throws Exception
    {
        HttpServer upload = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upload.createContext("/upload", exchange -> {
            try(exchange)
            {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(503, -1);
            }
        });
        upload.start();
        try(SupportBundleService service = new SupportBundleService(mTemporaryDirectory.resolve("unused.sqlite"),
            mTemporaryDirectory.resolve("logs"), Map::of,
            URI.create("http://127.0.0.1:" + upload.getAddress().getPort() + "/upload"), HttpClient.newHttpClient()))
        {
            String first = service.generate(smallRequest());
            assertEquals("ready", awaitReady(service, first).state());
            service.submit(first);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while("uploading".equals(service.status(first).state()) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("ready", service.status(first).state());
            assertTrue(service.status(first).message().contains("Try again"));
            assertTrue(Files.isRegularFile(service.download(first).path()));
            assertEquals("ready", awaitReady(service, service.generate(smallRequest())).state());
        }
        finally { upload.stop(0); }
    }

    private static SupportBundleService.Request smallRequest()
    {
        return new SupportBundleService.Request("Status issue", "operator@example.com", "Other", "Something else",
            "Check the receiver status.", "Open Receiver status.", EnumSet.of(SupportBundleService.Section.HEALTH));
    }

    @Test
    void createsCuratedBundleWithoutDatabaseOrSecrets() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.db");
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE database_metadata(key TEXT, value TEXT, updated_at_ms INTEGER)");
            statement.execute("INSERT INTO database_metadata VALUES('database_format_version','21',1)");
            statement.execute("CREATE TABLE alias_list(id INTEGER, name TEXT)");
            statement.execute("INSERT INTO alias_list VALUES(1,'Default NXDN')");
            statement.execute("CREATE TABLE receiver_activity_event(id INTEGER, timestamp_ms INTEGER, details TEXT, api_key TEXT)");
            statement.execute("INSERT INTO receiver_activity_event VALUES(1," + System.currentTimeMillis() +
                ",'recent call','never-include-history-key')");
            statement.execute("CREATE TABLE web_user(id INTEGER, username TEXT, password_verifier TEXT)");
            statement.execute("INSERT INTO web_user VALUES(1,'admin','never-include-this-verifier')");
            statement.execute("CREATE TABLE future_feature(id INTEGER, value TEXT)");
            statement.execute("INSERT INTO future_feature VALUES(1,'safe omission')");
        }
        Path logs = Files.createDirectories(mTemporaryDirectory.resolve("logs"));
        Files.writeString(logs.resolve("sdrtrunk_app.log"),
            "API key=never-include-this-key\nPath /Users/alice/private/data\n", StandardCharsets.UTF_8);
        SupportBundleService.Request request = new SupportBundleService.Request("NXDN recording", "a@example.com",
            "Audio and recordings", "Calls are not recording", "Audio plays but no recording is saved.",
            "Start the receiver and wait for a call.", EnumSet.of(SupportBundleService.Section.APPLICATION,
            SupportBundleService.Section.HEALTH, SupportBundleService.Section.SETUP,
            SupportBundleService.Section.ALIASES, SupportBundleService.Section.LOGS,
            SupportBundleService.Section.RECENT_ACTIVITY));

        try(SupportBundleService service = new SupportBundleService(database, logs,
            () -> Map.of("generated_at_ms", 1L, "summary", Map.of("active_count", 0))))
        {
            String id = service.generate(request);
            SupportBundleService.Status status = awaitReady(service, id);
            assertEquals("ready", status.state());
            assertTrue(status.bytes() > 0);
            SupportBundleService.PreparedDownload download = service.download(id);
            Map<String,String> entries = unzip(download.path());
            assertTrue(entries.containsKey("report.json"));
            assertTrue(entries.containsKey("application-and-computer.json"));
            var mapper = new ObjectMapper();
            var computer = mapper.readTree(entries.get("report.json")).get("computer_and_version");
            var application = mapper.readTree(entries.get("application-and-computer.json"));
            assertTrue(computer.has("cpu_model"));
            assertTrue(computer.has("installed_memory_bytes"));
            assertTrue(computer.has("jvm_memory_limit_bytes"));
            computer.fields().forEachRemaining(entry -> assertEquals(entry.getValue(), application.get(entry.getKey())));
            assertFalse(application.has("memory_available_bytes"), "Heap limit must not be labeled available RAM");
            assertTrue(entries.containsKey("receiver-setup/database-metadata.csv"));
            assertTrue(entries.containsKey("aliases-and-listening/alias-list.csv"));
            assertTrue(entries.containsKey("recent-activity-history/receiver-activity-event.csv"));
            assertFalse(entries.keySet().stream().anyMatch(name -> name.contains("web-user")));
            String all = String.join("\n", entries.values());
            assertFalse(all.contains("never-include-this-verifier"));
            assertFalse(all.contains("never-include-this-key"));
            assertFalse(all.contains("never-include-history-key"));
            assertTrue(entries.get("recent-activity-history/receiver-activity-event.csv").contains("[removed]"));
            assertFalse(all.contains("/Users/alice"));
            assertTrue(all.contains("future_feature"));
            assertTrue(all.contains("Not part of a support-bundle section"));

            service.cancel(id);
            assertFalse(Files.exists(download.path()));
            assertEquals("cancelled", service.status(id).state());
        }
    }

    @Test
    void baseComputerDetailsAreIncludedWithoutOptionalApplicationMeasurements() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("unused.sqlite");
        Path logs = Files.createDirectories(mTemporaryDirectory.resolve("base-logs"));
        SupportBundleService.Request request = new SupportBundleService.Request("Status issue", "a@example.com",
            "Other", "Something else", "Check the status.", "Open Receiver status.",
            EnumSet.of(SupportBundleService.Section.HEALTH));
        try(SupportBundleService service = new SupportBundleService(database, logs, Map::of))
        {
            String id = service.generate(request);
            assertEquals("ready", awaitReady(service, id).state());
            Map<String,String> entries = unzip(service.download(id).path());
            assertFalse(entries.containsKey("application-and-computer.json"));
            var computer = new ObjectMapper().readTree(entries.get("report.json")).get("computer_and_version");
            assertTrue(computer.has("version"));
            assertTrue(computer.has("build_timestamp"));
            assertTrue(computer.has("application_uptime_ms"));
            assertFalse(computer.has("hostname"));
            assertFalse(computer.has("user_name"));
            assertFalse(computer.has("database_size_bytes"));
        }
    }

    @Test
    void validatesRequiredReportDetails()
    {
        assertThrows(IllegalArgumentException.class, () -> new SupportBundleService.Request("", "bad",
            "Other", "Other", "Description", "Steps", EnumSet.of(SupportBundleService.Section.APPLICATION)));
        assertThrows(IllegalArgumentException.class, () -> new SupportBundleService.Request("Title", "bad",
            "Other", "Other", "Description", "Steps", EnumSet.of(SupportBundleService.Section.APPLICATION)));
        SupportBundleService.Request normalized = new SupportBundleService.Request("Title", "a@example.com",
            "Other", "Other", "Description", "Steps", EnumSet.of(SupportBundleService.Section.RECENT_ACTIVITY,
            SupportBundleService.Section.FULL_ACTIVITY));
        assertTrue(normalized.sections().contains(SupportBundleService.Section.FULL_ACTIVITY));
        assertFalse(normalized.sections().contains(SupportBundleService.Section.RECENT_ACTIVITY));
    }

    @Test
    void currentAndFutureDatabaseTablesCannotBreakBundleCreation() throws Exception
    {
        Path database = SdrTrunkTestDatabase.create(mTemporaryDirectory.resolve("current.sqlite"));
        Path logs = Files.createDirectories(mTemporaryDirectory.resolve("current-logs"));
        SupportBundleService.Request request = new SupportBundleService.Request("Current schema", "a@example.com",
            "Other", "Something else", "Check every current data area.", "Generate a support bundle.",
            EnumSet.allOf(SupportBundleService.Section.class));
        try(SupportBundleService service = new SupportBundleService(database, logs, Map::of))
        {
            String id = service.generate(request);
            SupportBundleService.Status status = awaitReady(service, id);
            assertEquals("ready", status.state(), status.message());
            Map<String,String> entries = unzip(service.download(id).path());
            assertTrue(entries.containsKey("bundle-summary.json"));
            assertFalse(entries.keySet().stream().anyMatch(name -> name.contains("web-user") ||
                name.contains("application-settings") || name.contains("configuration-broadcast-stream")));
            assertTrue(entries.get("bundle-summary.json").contains("private_data_never_included"));
        }
    }

    @Test
    void pairedHistoryIsOptInUsesTheRecentWindowAndKeepsItsSourceAndPrivacyBoundaries() throws Exception
    {
        Path database = SdrTrunkTestDatabase.create(mTemporaryDirectory.resolve("paired.sqlite"));
        Path activity = ActivityDatabasePath.forConfiguration(database);
        long recent = System.currentTimeMillis();
        long old = recent - Duration.ofHours(3).toMillis();
        try(var configuration = SdrTrunkDatabase.open(database); var sql = configuration.createStatement())
        {
            sql.executeUpdate("INSERT INTO application_settings(key,settings_json,updated_at_ms) " +
                "VALUES('private_support_fixture','{\"api_key\":\"paired-credential-secret\"}',1)");
            sql.executeUpdate("INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(1,16,1,123)");
        }
        try(var history = SdrTrunkDatabase.openHistory(database); var sql = history.createStatement())
        {
            sql.executeUpdate("INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms) " +
                "VALUES(1,'123e4567-e89b-42d3-a456-426614174001'," + old + "," + recent + ")");
            sql.executeUpdate("INSERT INTO receiver_activity_event(id,channel_id,observed_at_ms,action_code) " +
                "VALUES(1,1," + recent + ",1),(2,1," + old + ",1)");
            sql.executeUpdate("INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(1,17,2,456)");
        }
        byte[] configurationBefore = Files.readAllBytes(database);
        byte[] historyBefore = Files.readAllBytes(activity);
        try(SupportBundleService service = new SupportBundleService(database, mTemporaryDirectory.resolve("logs"), Map::of))
        {
            Map<String,String> withoutHistory = bundle(service,
                EnumSet.of(SupportBundleService.Section.SETUP, SupportBundleService.Section.ALIASES));
            assertFalse(withoutHistory.keySet().stream().anyMatch(name -> name.contains("activity-history/")));
            Map<String,String> recentHistory = bundle(service,
                EnumSet.of(SupportBundleService.Section.RECENT_ACTIVITY, SupportBundleService.Section.SETUP));
            String recentRows = recentHistory.get("recent-activity-history/activity-receiver-activity-event.csv");
            assertTrue(recentRows.contains(Long.toString(recent)));
            assertFalse(recentRows.contains(Long.toString(old)));
            assertEquals(1L, new ObjectMapper().readTree(recentHistory.get("bundle-summary.json"))
                .path("exported_rows").path("activity.receiver_activity_event").longValue());
            Map<String,String> fullHistory = bundle(service, EnumSet.of(SupportBundleService.Section.FULL_ACTIVITY));
            assertTrue(fullHistory.get("full-activity-history/activity-receiver-activity-event.csv").contains(Long.toString(old)));
            assertTrue(fullHistory.containsKey("full-activity-history/p25-subscriber-identity.csv"));
            assertTrue(fullHistory.containsKey("full-activity-history/activity-p25-subscriber-identity.csv"));
            assertFalse(fullHistory.keySet().stream().anyMatch(name -> name.contains("activity-database-metadata") ||
                name.endsWith(".sqlite") || name.contains("web-user") || name.contains("application-settings")));
            assertFalse(String.join("\n", recentHistory.values()).contains("paired-credential-secret"));
            assertFalse(String.join("\n", fullHistory.values()).contains("paired-credential-secret"));
            assertEquals(2L, new ObjectMapper().readTree(fullHistory.get("bundle-summary.json"))
                .path("exported_rows").path("activity.receiver_activity_event").longValue());
        }
        assertArrayEquals(configurationBefore, Files.readAllBytes(database), "Configuration is never changed by export");
        assertArrayEquals(historyBefore, Files.readAllBytes(activity), "History is never changed by export");
    }

    private static Map<String,String> bundle(SupportBundleService service, EnumSet<SupportBundleService.Section> sections)
        throws Exception
    {
        String id = service.generate(new SupportBundleService.Request("Paired history", "a@example.com", "Other",
            "Something else", "Inspect the selected data.", "Generate a support bundle.", sections));
        SupportBundleService.Status status = awaitReady(service, id);
        assertEquals("ready", status.state(), status.message());
        return unzip(service.download(id).path());
    }

    private static SupportBundleService.Status awaitReady(SupportBundleService service, String id) throws Exception
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while(System.nanoTime() < deadline)
        {
            SupportBundleService.Status status = service.status(id);
            if(!"queued".equals(status.state()) && !"generating".equals(status.state())) return status;
            Thread.sleep(20);
        }
        throw new AssertionError("Support bundle did not finish");
    }

    private static Map<String,String> unzip(Path archive) throws Exception
    {
        Map<String,String> entries = new HashMap<>();
        try(ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive)))
        {
            ZipEntry entry;
            while((entry = zip.getNextEntry()) != null)
            {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                zip.transferTo(bytes);
                entries.put(entry.getName(), bytes.toString(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
