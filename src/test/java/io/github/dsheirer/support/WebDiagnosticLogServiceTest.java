/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.support;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.web.http.WebDiagnosticHttpFilter;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebDiagnosticLogServiceTest
{
    @TempDir Path mDirectory;

    @Test
    void startsOffAndDoesNotCreateAFileUntilEnabled() throws Exception
    {
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value))
        {
            assertFalse(service.isEnabled());
            assertEquals(0, service.requestStarted("GET", "/api/v1/channels"));
            service.event("listener_started", Map.of("port", 8090));
            Thread.sleep(120);
            assertFalse(Files.exists(mDirectory.resolve(WebDiagnosticLogService.FILE_NAME)));
            service.setEnabled(true);
            await(() -> service.status().active());
            assertTrue(prefs.value.isWebDiagnosticLoggingEnabled());
            service.setEnabled(false);
            assertFalse(service.status().active());
            assertTrue(service.status().canDownload());
            assertFalse(prefs.value.isWebDiagnosticLoggingEnabled());
        }
    }

    @Test
    void firstEnableDuringMaintenancePreservesAlreadyAdmittedNewCaptureRecords() throws Exception
    {
        CountDownLatch maintaining = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> {
                if(first.compareAndSet(true, false))
                {
                    maintaining.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                }
                return mDirectory;
            }, prefs.value))
        {
            assertTrue(maintaining.await(2, TimeUnit.SECONDS));
            service.setEnabled(true);
            service.event("listener_started", Map.of("operation", "first_capture"));
            long id = service.requestStarted("GET", "/api/v1/channels");
            service.requestFinished(id, "GET", "/api/v1/channels", 1_000_000, 200, false);
            release.countDown();
            await(() -> service.status().writtenRecords() == 4 && service.status().queueDepth() == 0);
            assertEquals(0, service.status().droppedRecords());
            assertTrue(read().contains("first_capture"));
            assertTrue(read().contains("request_started"));
            assertTrue(read().contains("request_finished"));
        }
        finally { release.countDown(); }
    }

    @Test
    void parallelHttpRequestsBelowCapacityRetainEveryDiagnosticRecord() throws Exception
    {
        int requests = 200;
        CountDownLatch projecting = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch requestsEntered = new CountDownLatch(requests);
        CountDownLatch releaseRequests = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), requests);
        try(TestPreferences prefs = new TestPreferences();
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value,
                System::currentTimeMillis, WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {
                    projecting.countDown();
                    try { releaseWriter.await(15, TimeUnit.SECONDS); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                }))
        {
            service.setEnabled(true);
            service.event("listener_started", Map.of());
            assertTrue(projecting.await(2, TimeUnit.SECONDS));
            server.setExecutor(executor);
            WebDiagnosticHttpFilter.instrumentContexts(server, service,
                Filter.beforeHandler("Align concurrent HTTP requests", exchange -> {
                    requestsEntered.countDown();
                    try
                    {
                        if(!releaseRequests.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("Parallel requests did not start");
                    }
                    catch(InterruptedException exception)
                    {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                })).createContext("/fixture", exchange -> {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
            server.start();
            try(HttpClient client = HttpClient.newHttpClient())
            {
                URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/fixture");
                List<CompletableFuture<HttpResponse<Void>>> responses = new ArrayList<>();
                for(int index = 0; index < requests; index++)
                    responses.add(client.sendAsync(HttpRequest.newBuilder(target).build(),
                        HttpResponse.BodyHandlers.discarding()));
                assertTrue(requestsEntered.await(10, TimeUnit.SECONDS));
                releaseRequests.countDown();
                CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
                for(var response: responses) assertEquals(204, response.join().statusCode());
                await(() -> service.status().queueDepth() == requests * 2);
                assertEquals(requests * 2, service.status().queueDepth());
                assertEquals(0, service.status().droppedRecords(),
                    "Concurrent requests must not lose records while capacity is available");
                releaseWriter.countDown();
                await(() -> read().lines().filter(line -> line.contains(" - request_finished ")).count() == requests);
                assertEquals(requests,
                    read().lines().filter(line -> line.contains(" - request_started ")).count());
                assertFalse(read().contains("diagnostic_gap"));
            }
            finally
            {
                releaseWriter.countDown();
                releaseRequests.countDown();
                server.stop(0);
            }
        }
        finally
        {
            releaseWriter.countDown();
            releaseRequests.countDown();
            server.stop(0);
        }
    }

    @Test
    void blockedWriterNeverProjectsOrWaitsOnProducerAndReportsOverflow() throws Exception
    {
        CountDownLatch projecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value,
                System::currentTimeMillis, WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {
                    projectionThread.set(Thread.currentThread());
                    projecting.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                }))
        {
            service.setEnabled(true);
            service.event("listener_started", Map.of("port", 8090));
            assertTrue(projecting.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            for(int index = 0; index < WebDiagnosticLogService.QUEUE_CAPACITY * 3; index++)
                service.event("live_connected", Map.of("client_id", index));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
            assertNotSame(Thread.currentThread(), projectionThread.get());
            assertEquals(Thread.MIN_PRIORITY, projectionThread.get().getPriority());
            assertEquals(WebDiagnosticLogService.QUEUE_CAPACITY, service.status().queueDepth());
            assertEquals(WebDiagnosticLogService.QUEUE_CAPACITY * 2, service.status().droppedRecords());
            release.countDown();
            await(() -> service.status().queueDepth() == 0);
            await(() -> read().contains("diagnostic_gap"));
            assertTrue(read().contains("\"dropped_records\":1024"));
            assertTrue(read().contains("WARN web.diagnostic - diagnostic_gap"));
        }
        finally { release.countDown(); }
    }

    @Test
    void saturatedRequestQueueRetainsLatestCompactHealthWithoutSharingItsTextBudget() throws Exception
    {
        CountDownLatch projecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value, clock::get,
                WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {
                    projecting.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                }))
        {
            service.setEnabled(true);
            service.event("listener_started", Map.of());
            assertTrue(projecting.await(2, TimeUnit.SECONDS));
            for(int index = 0; index < WebDiagnosticLogService.QUEUE_CAPACITY; index++)
                service.requestStarted("GET", "/api/v1/channels");
            for(int sample = 1; sample <= 3; sample++)
            {
                clock.addAndGet(1_000);
                service.recordHealth(Map.of("generated_at_ms", clock.get(), "active", List.of(Map.of(
                        "occurrence_id", 71, "severity", "warning", "code", "usb",
                        "title", "long alert ".repeat(400), "observed", "long observation ".repeat(400))),
                    "measurements", List.of(Map.of("detail", "large measurement ".repeat(4_000)))),
                    Map.of("capture_generation", service.captureGeneration(), "sample_sequence", sample,
                        "host", Map.of("cpu_percent", 13.5, "heap_used_bytes", 123456789L,
                            "gc_collection_time_ms", 7)));
            }
            assertEquals(WebDiagnosticLogService.QUEUE_CAPACITY + 1, service.status().queueDepth());
            assertEquals(2, service.status().droppedRecords());
            release.countDown();
            await(() -> service.processedHealthSamples() == 1);
            await(() -> read().contains("\"sample_sequence\":3"));
            assertTrue(read().contains("\"cpu_percent\":13.5"));
            assertTrue(read().contains("\"heap_used_bytes\":123456789"));
            assertTrue(read().contains("\"gc_collection_time_ms\":7"));
            assertFalse(read().contains("large measurement"));
            assertTrue(read().contains("projection_truncated"));
            assertFalse(read().contains("\"sample_sequence\":1"));
            clock.addAndGet(5_000);
            service.event("listener_started", Map.of());
            await(() -> read().contains("\"coalesced_health_samples\":2"));
        }
        finally { release.countDown(); }
    }

    @Test
    void oldSamplerCaptureCannotEnterNewHealthSlot() throws Exception
    {
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value))
        {
            service.setEnabled(true);
            long previous = service.captureGeneration();
            service.setEnabled(false);
            service.setEnabled(true);
            assertNotEquals(previous, service.captureGeneration());
            service.recordHealth(Map.of("active", List.of()), Map.of("capture_generation", previous, "old_sample", 1));
            service.recordHealth(Map.of("active", List.of()),
                Map.of("capture_generation", service.captureGeneration(), "fresh_sample", 1));
            await(() -> read().contains("fresh_sample"));
            assertFalse(read().contains("old_sample"));
        }
    }

    @Test
    void pausedOldHealthProducerCannotReplaceAnAdmittedNewCapture() throws Exception
    {
        CountDownLatch readingCapture = new CountDownLatch(1);
        CountDownLatch releaseCapture = new CountDownLatch(1);
        CountDownLatch projecting = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value,
                System::currentTimeMillis, WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {
                    projecting.countDown();
                    try { releaseWriter.await(3, TimeUnit.SECONDS); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                }))
        {
            service.setEnabled(true);
            service.event("listener_started", Map.of());
            assertTrue(projecting.await(2, TimeUnit.SECONDS));
            long previous = service.captureGeneration();
            Map<String,Object> oldCore = new java.util.AbstractMap<>()
            {
                @Override public Object get(Object key)
                {
                    readingCapture.countDown();
                    try { releaseCapture.await(3, TimeUnit.SECONDS); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                    return previous;
                }
                @Override public java.util.Set<Entry<String,Object>> entrySet() { return Map.<String,Object>of().entrySet(); }
            };
            Thread oldProducer = new Thread(() -> service.recordHealth(Map.of("active", List.of()), oldCore));
            oldProducer.start();
            assertTrue(readingCapture.await(2, TimeUnit.SECONDS));
            service.setEnabled(false);
            service.setEnabled(true);
            service.recordHealth(Map.of("active", List.of()),
                Map.of("capture_generation", service.captureGeneration(), "fresh_sample", 1));
            releaseCapture.countDown();
            oldProducer.join(1_000);
            assertFalse(oldProducer.isAlive());
            releaseWriter.countDown();
            await(() -> read().contains("fresh_sample"));
        }
        finally { releaseCapture.countDown(); releaseWriter.countDown(); }
    }

    @Test
    void viewerTrafficReachesPeriodicLogSummariesWithoutIndividualRequestRecords() throws Exception
    {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value, clock::get,
                WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {}))
        {
            service.setEnabled(true);
            await(() -> service.status().active());
            var metrics = service.viewerMetrics();
            var download = metrics.begin("web_log_download", service.captureGeneration());
            clock.addAndGet(5_000);
            service.event("listener_started", Map.of("operation", "first_summary"));
            await(() -> read().contains(" - viewer_activity "));
            metrics.finish(download, 6_000_000, 503, true, 1_000);
            clock.addAndGet(5_000);
            service.event("listener_started", Map.of("operation", "second_summary"));
            await(() -> read().lines().filter(line -> line.contains(" - viewer_activity ")).count() == 2);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var lines = read().lines().filter(line -> line.contains(" - viewer_activity ")).toList();
            var active = json.readTree(lines.getFirst().substring(lines.getFirst().indexOf('{')));
            var completed = json.readTree(lines.getLast().substring(lines.getLast().indexOf('{')));
            assertEquals(1, active.get("active").asInt());
            assertEquals(0, completed.get("active").asInt());
            assertEquals(1, completed.get("completed").asInt());
            assertEquals(1, completed.get("errors").asInt());
            assertEquals(1_000, completed.at("/operations/web_log_download/declared_response_bytes").asInt());
            clock.addAndGet(5_000);
            service.event("listener_started", Map.of("operation", "idle_summary"));
            await(() -> read().contains("idle_summary"));
            assertEquals(2, read().lines().filter(line -> line.contains(" - viewer_activity ")).count());
            assertFalse(read().contains(" - request_started "));
            assertFalse(read().contains(" - request_finished "));
        }
    }

    @Test
    void ordinaryWritesUseCachedSizesAndPeriodicSummariesMeasureMaintenance() throws Exception
    {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        AtomicLong directoryReads = new AtomicLong();
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> {
                directoryReads.incrementAndGet();
                return mDirectory;
            }, prefs.value, clock::get, WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {}))
        {
            service.setEnabled(true);
            await(() -> service.status().active());
            for(int index = 0; index < 100; index++) service.event("live_connected", Map.of("client_id", index));
            await(() -> service.status().writtenRecords() >= 101);
            clock.addAndGet(5_000);
            service.event("listener_started", Map.of());
            await(() -> read().contains("logging_overhead"));
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            String line = read().lines().filter(value -> value.contains(" - logging_overhead ")).findFirst().orElseThrow();
            var fields = json.readTree(line.substring(line.indexOf("{", line.indexOf(" - logging_overhead "))));
            assertTrue(fields.get("maintenance_scans").asLong() <= 3, line);
            assertTrue(fields.get("bytes_written").asLong() > 0);
            assertTrue(fields.get("projected_records").asLong() >= 100);
            assertTrue(fields.has("recorded_at_ms"));
            assertTrue(fields.has("observed_at_ms"));
            assertTrue(fields.get("writer_ms").asDouble() >= 0);
            assertTrue(directoryReads.get() >= 100, "Configured directory changes must still be checked on writes");
        }
    }

    @Test
    void captureChangesDiscardStaleProjectionAndLateRequestCompletions() throws Exception
    {
        CountDownLatch projecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value,
                System::currentTimeMillis, WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {
                    projecting.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                }))
        {
            service.setEnabled(true);
            long request = service.requestStarted("GET", "/api/v1/channels");
            assertTrue(projecting.await(2, TimeUnit.SECONDS));
            service.setEnabled(false);
            service.setEnabled(true);
            service.requestFinished(request, "GET", "/api/v1/channels", 100, 200, false);
            release.countDown();
            service.event("listener_started", Map.of("operation", "new_capture"));
            await(() -> read().contains("new_capture"));
            assertFalse(read().contains("request_finished"));
            assertFalse(read().contains("request_started"));
        }
        finally { release.countDown(); }
    }

    @Test
    void projectsOnlyKnownValuesAndRemovesSecretsBeforeDiskAndDownload() throws Exception
    {
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value))
        {
            service.setEnabled(true);
            service.event("stream_topic_open", Map.of("topic", "spectrum", "client_id", 17, "password", "secret-one",
                "headers", Map.of("Authorization", "Bearer secret-two"), "operation", new Object() {
                    @Override public String toString() { throw new AssertionError("Projected arbitrary object"); }
                }));
            long now = System.currentTimeMillis();
            service.recordHealth(Map.of("generated_at_ms", now, "active", List.of(), "computer", Map.of("hostname", "private-host"),
                "measurements", List.of(Map.of("id", "usb", "rows", List.of(Map.of("label", "USB errors", "value", 4,
                    "detail", "password=secret-three https://private.example/stream?token=secret-four /Users/private-owner/log"))))));
            long id = service.requestStarted("GET", "/api/v1/channels?token=secret-five");
            service.requestFinished(id, "GET", "/api/v1/channels?token=secret-five", 1_000_000, 500, true);
            await(() -> read().contains("request_finished"));
            String text = read();
            for(String secret: List.of("secret-one", "secret-two", "secret-three", "secret-four", "secret-five", "private-host", "private-owner"))
                assertFalse(text.contains(secret), secret);
            assertTrue(text.contains("USB errors"));
            assertTrue(text.contains("\"route\":\"other\""));
            assertTrue(text.contains("ERROR web.diagnostic - request_finished"));
            try(WebDiagnosticLogService.Download download = service.download();
                ZipInputStream zip = new ZipInputStream(Files.newInputStream(download.path())))
            {
                assertEquals(WebDiagnosticLogService.FILE_NAME, zip.getNextEntry().getName());
                assertEquals(text, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                assertNull(zip.getNextEntry());
            }
        }
    }

    @Test
    void retainsOneMinuteBeforeAlertAndThirtySecondsAfterWithoutRepeatingAnOngoingAlert() throws Exception
    {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        long start = clock.get();
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value, clock::get,
                WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {}))
        {
            service.setEnabled(true);
            for(int second = 0; second < 65; second++)
                sample(service, clock, start + second * 1_000L, List.of());
            Map<String,Object> warning = Map.of("occurrence_id", 1, "code", "native_ingress_loss", "severity", "warning", "scope", "Tuner 1");
            for(int second = 65; second < 70; second++)
                sample(service, clock, start + second * 1_000L, List.of(warning));
            Map<String,Object> critical = Map.of("occurrence_id", 1, "code", "native_ingress_loss", "severity", "critical", "scope", "Tuner 1");
            sample(service, clock, start + 70_000L, List.of(critical));
            for(int second = 71; second <= 103; second++)
                sample(service, clock, start + second * 1_000L, List.of());
            await(() -> service.status().queueDepth() == 0);
            await(() -> read().contains("\"change\":\"resolved\""));
            String text = read();
            assertEquals(3, text.lines().filter(line -> line.contains(" - receiver_alert ")).count());
            assertTrue(text.contains("\"change\":\"opened\""));
            assertTrue(text.contains("\"change\":\"severity_changed\""));
            assertTrue(text.contains("WARN web.diagnostic - receiver_alert"));
            assertTrue(text.contains("ERROR web.diagnostic - receiver_alert"));
            assertTrue(text.lines().anyMatch(line -> line.contains(" - receiver_context ") && line.contains("\"generated_at_ms\":" + (start + 6_000L))));
            assertTrue(text.lines().anyMatch(line -> line.contains(" - receiver_context ") && line.contains("\"generated_at_ms\":" + (start + 101_000L))));
            assertFalse(text.lines().anyMatch(line -> line.contains(" - receiver_context ") && line.contains("\"generated_at_ms\":" + (start + 103_000L))));
        }
    }

    @Test
    void incompleteActiveProjectionNeverInventsResolutionButLargeMeasurementsAllowRealResolution() throws Exception
    {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        long start = clock.get();
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value, clock::get,
                WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {}))
        {
            service.setEnabled(true);
            sample(service, clock, start, List.of(Map.of("occurrence_id", 139, "severity", "warning", "code", "usb", "scope", "Tuner")));
            await(() -> read().contains("\"occurrence_id\":139"));
            List<Map<String,Object>> many = new ArrayList<>();
            for(int id = 1; id <= 140; id++)
                many.add(Map.of("occurrence_id", id, "severity", "warning", "code", "usb", "scope", "Tuner"));
            sample(service, clock, start + 1_000, many);
            await(() -> read().contains("active_projection_incomplete"));
            assertFalse(read().contains("\"change\":\"resolved\""));
            List<Map<String,Object>> largeRows = new ArrayList<>();
            for(int row = 0; row < 140; row++) largeRows.add(Map.of("label", "Long measurement", "detail", "x".repeat(1_024)));
            clock.set(start + 2_000);
            service.recordHealth(Map.of("generated_at_ms", clock.get(), "active", List.of(),
                "measurements", List.of(Map.of("id", "large", "rows", largeRows))));
            await(() -> read().contains("\"change\":\"resolved\""));
            assertTrue(read().contains("projection_truncated"));
            assertTrue(read().lines().anyMatch(line -> line.contains("\"change\":\"resolved\"") && line.contains("\"occurrence_id\":139")));
        }
    }

    @Test
    void appliesAgeAndTotalSizeBoundsWithoutTouchingOtherLogsOrSymlinkTargets() throws Exception
    {
        long now = System.currentTimeMillis();
        Path expired = mDirectory.resolve("20261001_120000_000_1_sdrtrunk_web.log");
        Files.writeString(expired, "old\n");
        Files.setLastModifiedTime(expired, FileTime.fromMillis(now - 8 * 86_400_000L));
        Path unrelated = mDirectory.resolve("sdrtrunk_app.log");
        Files.writeString(unrelated, "keep me\n");
        Path target = mDirectory.resolve("unrelated-private.log");
        Files.writeString(target, "private contents\n");
        Files.createSymbolicLink(mDirectory.resolve("20261002_120000_000_1_sdrtrunk_web.log"), target);
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value,
                System::currentTimeMillis, 4_096, 1_024, () -> {}))
        {
            service.setEnabled(true);
            for(int index = 0; index < 40; index++)
                service.event("live_connected", Map.of("client_id", index, "operation", "x".repeat(100)));
            await(() -> service.status().queueDepth() == 0 && service.status().writtenRecords() >= 41);
            service.setEnabled(false);
            assertFalse(Files.exists(expired));
            try(var paths = Files.list(mDirectory))
            {
                long total = paths.filter(path -> path.getFileName().toString().endsWith("sdrtrunk_web.log"))
                    .filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(path -> { try { return Files.size(path); } catch(Exception exception) { throw new RuntimeException(exception); } }).sum();
                assertTrue(total <= 4_096, "Total " + total);
            }
            assertEquals("keep me\n", Files.readString(unrelated));
            assertEquals("private contents\n", Files.readString(target));
            assertTrue(service.snapshot("previous", null, null).available());
            try(WebDiagnosticLogService.Download download = service.download();
                ZipInputStream zip = new ZipInputStream(Files.newInputStream(download.path())))
            {
                while(zip.getNextEntry() != null)
                    assertFalse(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("private contents"));
            }
        }
    }

    @Test
    void periodicMaintenanceReopensDeletedAndReplacedCurrentFiles() throws Exception
    {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        Path current = mDirectory.resolve(WebDiagnosticLogService.FILE_NAME);
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value, clock::get,
                WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {}))
        {
            service.setEnabled(true);
            service.event("listener_started", Map.of("operation", "before_delete"));
            await(() -> read().contains("before_delete"));
            Files.delete(current);
            clock.addAndGet(61_000);
            service.event("listener_started", Map.of("operation", "after_delete"));
            await(() -> read().contains("after_delete"));
            Files.move(current, mDirectory.resolve("retired-current"));
            Files.writeString(current, "Replacement\n");
            clock.addAndGet(61_000);
            service.event("listener_started", Map.of("operation", "after_replace"));
            await(() -> read().contains("after_replace"));
            assertFalse(Files.readString(mDirectory.resolve("retired-current")).contains("after_replace"));
        }
    }

    @Test
    void changingTheConfiguredDirectoryBypassesCachedWriterState() throws Exception
    {
        Path second = mDirectory.resolve("second");
        AtomicReference<Path> directory = new AtomicReference<>(mDirectory);
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(directory::get, prefs.value))
        {
            service.setEnabled(true);
            service.event("listener_started", Map.of("operation", "first_folder"));
            await(() -> read().contains("first_folder"));
            directory.set(second);
            service.event("listener_started", Map.of("operation", "second_folder"));
            await(() -> {
                try { return Files.readString(second.resolve(WebDiagnosticLogService.FILE_NAME)).contains("second_folder"); }
                catch(Exception exception) { return false; }
            });
            assertFalse(read().contains("second_folder"));
        }
    }

    @Test
    void downloadBoundsSanitizedExpansionAndKeepsTemporaryFilesPrivateWithoutLeakingLeases() throws Exception
    {
        Path current = mDirectory.resolve(WebDiagnosticLogService.FILE_NAME);
        Files.writeString(current, "1.2.3.4 ".repeat(100) + "\n");
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value,
                System::currentTimeMillis, 1_024, 1_024, () -> {}))
        {
            assertThrows(java.io.IOException.class, service::download);
            Files.writeString(current, "Safe saved diagnostic\n");
            try(WebDiagnosticLogService.Download first = service.download();
                WebDiagnosticLogService.Download second = service.download())
            {
                assertThrows(ApplicationLogService.BusyException.class, service::download);
                if(Files.getFileStore(first.path()).supportsFileAttributeView("posix"))
                    assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(first.path()));
                try(ZipInputStream zip = new ZipInputStream(Files.newInputStream(first.path())))
                {
                    assertNotNull(zip.getNextEntry());
                    assertTrue(zip.readAllBytes().length <= 1_024);
                }
            }
        }
    }

    @Test
    void unsafeCurrentFileReportsSafeWriteFailureAndDownloadsHaveIndependentBoundedLeases() throws Exception
    {
        Path target = mDirectory.resolve("private-target");
        Files.writeString(target, "Authorization: Bearer private-token\n");
        Files.createSymbolicLink(mDirectory.resolve(WebDiagnosticLogService.FILE_NAME), target);
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value))
        {
            service.setEnabled(true);
            await(() -> service.status().lastError() != null);
            assertFalse(service.status().active());
            assertFalse(service.status().lastError().contains(mDirectory.toString()));
            assertEquals("Authorization: Bearer private-token\n", Files.readString(target));
            assertThrows(java.io.IOException.class, service::download);
        }
        Files.delete(mDirectory.resolve(WebDiagnosticLogService.FILE_NAME));
        Files.writeString(mDirectory.resolve(WebDiagnosticLogService.FILE_NAME), "Authorization: Bearer download-secret\n");
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value);
            WebDiagnosticLogService.Download first = service.download();
            WebDiagnosticLogService.Download second = service.download())
        {
            assertTrue(service.snapshot("current", null, null).available());
            assertThrows(ApplicationLogService.BusyException.class, service::download);
            try(ZipInputStream zip = new ZipInputStream(Files.newInputStream(first.path())))
            {
                assertNotNull(zip.getNextEntry());
                assertFalse(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("download-secret"));
            }
            Path firstPath = first.path();
            first.close();
            assertFalse(Files.exists(firstPath));
            first.close(); // Idempotent close must not create an extra admission permit.
            try(WebDiagnosticLogService.Download third = service.download())
            { assertTrue(Files.exists(third.path())); }
        }
    }

    @Test
    void recentAppendsDoNotExtendTheAgeOfOldDailyFiles() throws Exception
    {
        long future = System.currentTimeMillis() + 8 * 86_400_000L;
        Path archive = mDirectory.resolve("20261014_120000_000_0000000001_sdrtrunk_web.log");
        Files.writeString(archive, "An older daily log\n");
        Files.setLastModifiedTime(archive, FileTime.fromMillis(future - 1_000));
        try(TestPreferences prefs = new TestPreferences();
            WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, prefs.value,
                () -> future, WebDiagnosticLogService.MAXIMUM_BYTES, 10_000_000, () -> {}))
        {
            await(() -> !Files.exists(archive));
        }
    }

    private static void sample(WebDiagnosticLogService service, AtomicLong clock, long time, List<Map<String,Object>> alerts) throws Exception
    {
        long before = service.processedHealthSamples();
        clock.set(time);
        service.recordHealth(Map.of("generated_at_ms", time, "active", alerts,
            "measurements", List.of(Map.of("id", "computer", "rows", List.of(Map.of("label", "VCE processor use", "value", 20))))));
        await(() -> service.processedHealthSamples() > before);
    }

    private String read()
    {
        try { return Files.readString(mDirectory.resolve(WebDiagnosticLogService.FILE_NAME)); }
        catch(Exception exception) { return ""; }
    }

    private static void await(BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Diagnostic worker did not complete");
    }

    private static final class TestPreferences implements AutoCloseable
    {
        private final Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/web-diagnostics-" + UUID.randomUUID());
        private final ApplicationPreference value = new ApplicationPreference(ignored -> {});
        private TestPreferences() throws Exception
        {
            Field field = ApplicationPreference.class.getDeclaredField("mPreferences");
            field.setAccessible(true);
            field.set(value, node);
        }
        @Override public void close() throws Exception
        {
            Preferences parent = node.parent();
            node.removeNode();
            parent.flush();
        }
    }
}
