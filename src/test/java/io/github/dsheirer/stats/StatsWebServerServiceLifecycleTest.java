/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticService;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.application.WebCertificateMode;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.support.WebDiagnosticLogService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.http.WebSessionHttpController;
import io.github.dsheirer.web.http.CallMatchingHttpController;
import io.github.dsheirer.web.http.ApplicationLogHttpController;
import io.github.dsheirer.web.http.WebDiagnosticLogHttpController;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import java.net.InetAddress;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebServerServiceLifecycleTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void failedFeatureConstructionStopsItsAlreadyCreatedObserver() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("failed-feature-construction");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        DirectoryPreference directories = new DirectoryPreference(ignored -> {})
        {
            @Override public Path getDirectoryApplicationRoot() { return dataRoot; }
            @Override public Path getDirectoryApplicationLog()
            { throw new IllegalStateException("Injected late feature construction failure"); }
        };
        UserPreferences preferences = new TestUserPreferences(
            new TestApplicationPreference(0, false, false, true), directories);
        var tuners = new io.github.dsheirer.source.tuner.manager.TunerManager(preferences);
        var configuration = new ConfigurationManager(preferences, tuners, new AliasModel(), null);
        configuration.init();
        var activity = new io.github.dsheirer.stats.activity.ReceiverActivityService(preferences);
        var events = new io.github.dsheirer.module.decode.event.DecodeEventViewService(
            configuration.getChannelProcessingManager(), configuration.getAliasModel());
        var before = Thread.getAllStackTraces().keySet();
        try
        {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ReceiverWebServices(preferences, configuration, activity, events, null));
            assertTrue(failure.getMessage().contains("Injected late feature construction failure"));
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread ->
                !before.contains(thread) && thread.isAlive() &&
                    thread.getName().startsWith("sdrtrunk decode message views")),
                "Failed construction must close the already-started message observer");
        }
        finally
        {
            configuration.quiesceAdministration();
            configuration.getChannelProcessingManager().close();
            events.close();
            activity.dispose();
            configuration.getBroadcastModel().close();
            configuration.close();
            configuration.getRadioReferenceDirectoryService().close();
            tuners.stop();
        }
    }

    @Test
    void publishesActualListenerStateAfterAccessChangesFailuresAndShutdown() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("runtime-notification-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path assets = Files.createDirectories(mTemporaryDirectory.resolve("runtime-notification-assets"));
        String previousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestApplicationPreference application = new TestApplicationPreference(0, false, false, true);
        TestUserPreferences preferences = new TestUserPreferences(application, new TestDirectoryPreference(dataRoot));

        try(StatsWebServerService service = StatsWebTestRuntime.start(preferences))
        {
            assertTrue(service.getRuntimeState().running(), service.getRuntimeState().statusMessage());
            RuntimeStateObserver observer = new RuntimeStateObserver(service);
            MyEventBus.getGlobalEventBus().register(observer);

            try
            {
                application.setNetworkAccessEnabled(true);
                service.preferenceUpdated(PreferenceType.APPLICATION);
                WebServerRuntimeState network = service.getRuntimeState();
                assertTrue(network.running(), network.statusMessage());
                assertTrue(network.anyIpEnabled());
                assertTrue(network.https());
                observer.assertLatest(1, network);

                service.preferenceUpdated(PreferenceType.APPLICATION);
                observer.assertLatest(1, network);

                application.setNetworkAccessEnabled(false);
                service.preferenceUpdated(PreferenceType.APPLICATION);
                WebServerRuntimeState local = service.getRuntimeState();
                assertTrue(local.running(), local.statusMessage());
                assertFalse(local.anyIpEnabled());
                observer.assertLatest(2, local);

                try(ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
                {
                    application.setPort(occupied.getLocalPort());
                    service.preferenceUpdated(PreferenceType.APPLICATION);
                    WebServerRuntimeState failed = service.getRuntimeState();
                    assertTrue(failed.running(), failed.statusMessage());
                    assertEquals(local.port(), failed.port());
                    assertEquals(local.anyIpEnabled(), failed.anyIpEnabled());
                    assertEquals(local.https(), failed.https());
                    assertEquals(local.certificateFingerprint(), failed.certificateFingerprint());
                    assertTrue(failed.statusMessage().contains("previous listener remains active"));
                    observer.assertLatest(3, failed);
                }

                application.setEnabled(false);
                service.preferenceUpdated(PreferenceType.APPLICATION);
                WebServerRuntimeState disabled = service.getRuntimeState();
                assertFalse(disabled.running());
                observer.assertLatest(4, disabled);
                service.preferenceUpdated(PreferenceType.APPLICATION);
                observer.assertLatest(4, disabled);

                application.setEnabled(true);
                application.setPort(0);
                service.preferenceUpdated(PreferenceType.APPLICATION);
                assertTrue(service.getRuntimeState().running(), service.getRuntimeState().statusMessage());
                observer.assertLatest(5, service.getRuntimeState());

                service.close();
                assertFalse(service.getRuntimeState().running());
                observer.assertLatest(6, service.getRuntimeState());
            }
            finally
            {
                MyEventBus.getGlobalEventBus().unregister(observer);
            }
        }
        finally
        {
            if(previousAssetOverride == null)
            {
                System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
            }
            else
            {
                System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, previousAssetOverride);
            }
        }
    }

    @Test
    void simultaneousTwoHundredMultiplexClosesRetainEveryDiagnosticRecord() throws Exception
    {
        int viewers = 200;
        Path dataRoot = mTemporaryDirectory.resolve("multiplex-close-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path assets = Files.createDirectories(mTemporaryDirectory.resolve("multiplex-close-assets"));
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>test</title>");
        String previousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestUserPreferences preferences = new TestUserPreferences(
            new TestApplicationPreference(0, false, false, true), new TestDirectoryPreference(dataRoot));
        CountDownLatch projecting = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicBoolean pauseWriter = new AtomicBoolean();
        List<CompletableFuture<HttpResponse<InputStream>>> streams = new ArrayList<>();

        try(StatsWebServerService service = StatsWebTestRuntime.start(preferences);
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
            var readers = Executors.newVirtualThreadPerTaskExecutor())
        {
            try
            {
                // This fixture stalls only HTTP diagnostics. Latest-health coalescing has separate saturation coverage.
                StatsWebTestRuntime.services(service).mReceiverHealthService.setWebDiagnosticLogService(null);
                var logField = StatsWebServerService.class.getDeclaredField("mWebDiagnosticLogService");
                logField.setAccessible(true);
                WebDiagnosticLogService diagnostics = (WebDiagnosticLogService)logField.get(service);
                var projectionHook = WebDiagnosticLogService.class.getDeclaredField("mBeforeProjection");
                projectionHook.setAccessible(true);
                projectionHook.set(diagnostics, (Runnable)() -> {
                    if(pauseWriter.get())
                    {
                        projecting.countDown();
                        try { releaseWriter.await(); }
                        catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                    }
                });
                URI origin = origin(service.getRuntimeState().port());
                char[] password = "multiplex-close-lifecycle-password".toCharArray();
                try { service.provisionOrResetPrimaryAdmin(password); }
                finally { Arrays.fill(password, '\u0000'); }
                String cookie = login(client, origin, "multiplex-close-lifecycle-password");
                HttpResponse<String> session = client.send(HttpRequest.newBuilder(
                        origin.resolve(WebSessionHttpController.SESSION_PATH)).header("Cookie", cookie).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, session.statusCode());
                String csrf = OBJECT_MAPPER.readTree(session.body()).at("/data/csrf_token").textValue();
                diagnostics.setEnabled(true);
                Path log = preferences.getDirectoryPreference().getDirectoryApplicationLog()
                    .resolve(WebDiagnosticLogService.FILE_NAME);
                List<String> ids = new ArrayList<>();
                for(int index = 0; index < viewers; index++)
                {
                    String id = UUID.randomUUID().toString();
                    ids.add(id);
                    streams.add(client.sendAsync(HttpRequest.newBuilder(origin.resolve(
                            StatsApiV1.LIVE_MULTIPLEX + "?client_id=" + id)).header("Cookie", cookie).build(),
                        HttpResponse.BodyHandlers.ofInputStream()));
                }
                CompletableFuture.allOf(streams.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
                List<DataInputStream> bodies = new ArrayList<>();
                for(var stream: streams)
                {
                    assertEquals(200, stream.join().statusCode());
                    bodies.add(new DataInputStream(stream.join().body()));
                }
                List<java.util.concurrent.Future<?>> ready = new ArrayList<>();
                for(DataInputStream body: bodies)
                    ready.add(readers.submit(() -> { assertMultiplexEvent(body, "ready"); return null; }));
                for(var result: ready) result.get(10, TimeUnit.SECONDS);

                List<CompletableFuture<HttpResponse<Void>>> controls = new ArrayList<>();
                for(String id: ids)
                    controls.add(client.sendAsync(multiplexControl(origin, cookie, csrf, id, 1,
                        "{\"channel_activity\":{}}"), HttpResponse.BodyHandlers.discarding()));
                CompletableFuture.allOf(controls.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
                for(var control: controls) assertEquals(200, control.join().statusCode());
                ready.clear();
                for(DataInputStream body: bodies)
                    ready.add(readers.submit(() -> { assertMultiplexEvent(body, "snapshot"); return null; }));
                for(var result: ready) result.get(10, TimeUnit.SECONDS);
                awaitCondition(() -> diagnostics.status().queueDepth() == 0);
                awaitCondition(() -> {
                    try { return Files.readString(log).lines().filter(line ->
                        line.contains(" - request_finished ")).count() == viewers; }
                    catch(java.io.IOException exception) { return false; }
                });
                assertEquals(0, diagnostics.status().droppedRecords());

                pauseWriter.set(true);
                diagnostics.event("listener_started", Map.of("operation", "shutdown_test_barrier"));
                assertTrue(projecting.await(3, TimeUnit.SECONDS));
                controls.clear();
                // Exercise the actual browser unsubscribe handshake, stream owner cleanup, HTTP filter and writer.
                for(String id: ids)
                    controls.add(client.sendAsync(multiplexControl(origin, cookie, csrf, id, 2, "{}"),
                        HttpResponse.BodyHandlers.discarding()));
                ready.clear();
                for(DataInputStream body: bodies)
                    ready.add(readers.submit(() -> {
                        // The unsubscribe interrupts the stream owner; its final chunk may be closed abruptly.
                        try { body.transferTo(OutputStream.nullOutputStream()); }
                        catch(java.io.IOException expectedOnClose) { }
                        return null;
                    }));
                CompletableFuture.allOf(controls.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
                for(var control: controls) assertEquals(200, control.join().statusCode());
                for(var result: ready) result.get(10, TimeUnit.SECONDS);
                // Per viewer: control start/finish, topic close, disconnect and long GET finish.
                awaitCondition(() -> diagnostics.status().queueDepth() == viewers * 5);
                assertEquals(0, diagnostics.status().droppedRecords());
                releaseWriter.countDown();
                awaitCondition(() -> diagnostics.status().queueDepth() == 0);
                awaitCondition(() -> {
                    try { return Files.readString(log).lines().filter(line ->
                        line.contains(" - request_finished ")).count() == viewers * 3L; }
                    catch(java.io.IOException exception) { return false; }
                });
                String written = Files.readString(log);
                assertEquals(viewers, written.lines().filter(line -> line.contains(" - stream_topic_close ")).count());
                assertEquals(viewers, written.lines().filter(line -> line.contains(" - live_disconnected ")).count());
                assertEquals(viewers * 3L, written.lines().filter(line -> line.contains(" - request_started ")).count());
                assertFalse(written.contains("diagnostic_gap"));
                // A fresh client must establish a new TCP connection after every stream has closed.
                try(HttpClient probe = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
                {
                    HttpResponse<String> afterClose = probe.send(HttpRequest.newBuilder(
                            origin.resolve(WebSessionHttpController.SESSION_PATH)).timeout(Duration.ofSeconds(5))
                        .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, afterClose.statusCode(), afterClose.body());
                    assertFalse(OBJECT_MAPPER.readTree(afterClose.body()).path("data").isMissingNode());
                }
                assertTrue(service.getRuntimeState().running());
                assertEquals(0, diagnostics.status().droppedRecords());
            }
            finally
            {
                releaseWriter.countDown();
                client.shutdownNow();
                for(var stream: streams)
                {
                    if(stream.isDone() && !stream.isCompletedExceptionally()) stream.join().body().close();
                }
            }
        }
        finally
        {
            releaseWriter.countDown();
            if(previousAssetOverride == null) System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
            else System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, previousAssetOverride);
        }
    }

    private static HttpRequest multiplexControl(URI origin, String cookie, String csrf, String id, int revision,
                                                String subscriptions)
    {
        return HttpRequest.newBuilder(origin.resolve(StatsApiV1.LIVE_MULTIPLEX_CONTROL))
            .timeout(Duration.ofSeconds(10)).header("Cookie", cookie).header("Origin", origin.toString())
            .header(WebRequestSecurity.CSRF_HEADER_NAME, csrf).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"client_id\":\"" + id + "\",\"revision\":" + revision +
                ",\"subscriptions\":" + subscriptions + "}")).build();
    }

    private static void assertMultiplexEvent(DataInputStream input, String event) throws Exception
    {
        assertEquals(0x534C4D58, input.readInt());
        assertEquals(2, input.readUnsignedByte());
        assertEquals(1, input.readUnsignedByte());
        input.readUnsignedShort();
        int bytes = input.readInt();
        assertTrue(bytes > 0 && bytes <= 1_048_576);
        assertEquals(0, input.readInt());
        assertEquals(event, OBJECT_MAPPER.readTree(input.readNBytes(bytes)).path("event").textValue());
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not become true within ten seconds");
    }

    @Test
    void supportsP25LiveSubscriptionsAndRejectsRetiredSavedHistory() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("history-snapshot-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path assets = Files.createDirectories(mTemporaryDirectory.resolve("history-snapshot-assets"));
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>test</title>");
        String previousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestUserPreferences preferences = new TestUserPreferences(
            new TestApplicationPreference(0, false, false, true), new TestDirectoryPreference(dataRoot));
        try(StatsWebServerService service = StatsWebTestRuntime.start(preferences);
            HttpClient client = HttpClient.newHttpClient())
        {
            URI origin = origin(service.getRuntimeState().port());
            char[] password = "history-snapshot-password".toCharArray();
            try { service.provisionOrResetPrimaryAdmin(password); }
            finally { Arrays.fill(password, '\u0000'); }
            String cookie = login(client, origin, "history-snapshot-password");
            HttpResponse<String> session = client.send(HttpRequest.newBuilder(
                    origin.resolve(WebSessionHttpController.SESSION_PATH)).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, session.statusCode());
            String csrf = OBJECT_MAPPER.readTree(session.body()).at("/data/csrf_token").textValue();
            String clientId = UUID.randomUUID().toString();
            HttpResponse<String> retired = client.send(multiplexControl(origin, cookie, csrf, clientId, 1,
                "{\"saved_activity\":{\"subscription_id\":\"" + UUID.randomUUID() + "\"}}"),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, retired.statusCode(), retired.body());

            HttpResponse<String> p25 = client.send(multiplexControl(origin, cookie, csrf, clientId, 2,
                "{\"p25_activity\":{\"subscription_id\":\"" + UUID.randomUUID() + "\"}}"),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(404, p25.statusCode(), p25.body());
            assertEquals("client_not_found", OBJECT_MAPPER.readTree(p25.body()).at("/error/code").textValue(),
                "The P25 live topic passes typed validation and requires an actual live connection");

            HttpResponse<String> supported = client.send(multiplexControl(origin, cookie, csrf, clientId, 3,
                "{\"channel_activity\":{}}"), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, supported.statusCode(), supported.body());
            assertEquals("client_not_found", OBJECT_MAPPER.readTree(supported.body()).at("/error/code").textValue(),
                "A supported topic passes validation and requires an actual live connection");
        }
        finally
        {
            if(previousAssetOverride == null) System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
            else System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, previousAssetOverride);
        }
    }

    @Test
    void activitySubscriptionIncarnationsRefreshBaselinesWithoutReopeningUnchangedConsumers() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("activity-incarnation-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path assets = Files.createDirectories(mTemporaryDirectory.resolve("activity-incarnation-assets"));
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>test</title>");
        String previousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestUserPreferences preferences = new TestUserPreferences(
            new TestApplicationPreference(0, false, false, true), new TestDirectoryPreference(dataRoot));
        try(StatsWebServerService service = StatsWebTestRuntime.start(preferences))
        {
            assertTrue(service.getRuntimeState().running(), service.getRuntimeState().statusMessage());
            var validate = StatsWebServerService.class.getDeclaredMethod("parseMultiplexSubscription",
                String.class, JsonNode.class);
            validate.setAccessible(true);
            JsonNode first = OBJECT_MAPPER.readTree("""
                {"markers":true,"subscription_id":"00000000-0000-0000-0000-000000000001"}
                """);
            JsonNode second = OBJECT_MAPPER.readTree("""
                {"markers":true,"subscription_id":"00000000-0000-0000-0000-000000000002"}
                """);
            validate.invoke(service, "channel_activity", OBJECT_MAPPER.readTree("{\"markers\":true}"));
            validate.invoke(service, "channel_activity", first);
            for(String invalid: List.of("{\"subscription_id\":\"not-a-uuid\"}",
                "{\"subscription_id\":false}", "{\"subscription_id\":null}", "{\"subscription_id\":\"\"}",
                "{\"delta\":true}", "{\"markers\":\"true\"}", "{\"unknown\":true}"))
            {
                InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> validate.invoke(service, "channel_activity", OBJECT_MAPPER.readTree(invalid)));
                assertTrue(failure.getCause() instanceof IllegalArgumentException ||
                    failure.getCause() instanceof StatsApiException);
            }

            Class<?> clientType = Class.forName(StatsWebServerService.class.getName() + "$MultiplexClient");
            var clientConstructor = clientType.getDeclaredConstructor(StatsWebServerService.class,
                String.class, com.sun.net.httpserver.HttpExchange.class);
            clientConstructor.setAccessible(true);
            Class<?> configurationType = Class.forName(StatsWebServerService.class.getName() + "$MultiplexConfiguration");
            var configurationConstructor = configurationType.getDeclaredConstructor(long.class, Map.class);
            configurationConstructor.setAccessible(true);
            var configure = clientType.getDeclaredMethod("configure", configurationType);
            configure.setAccessible(true);
            var reconcile = clientType.getDeclaredMethod("reconcile", LiveMultiplexOutput.class);
            reconcile.setAccessible(true);
            var activity = clientType.getDeclaredField("mChannelActivity");
            activity.setAccessible(true);
            var poll = LiveMultiplexOutput.class.getDeclaredMethod("pollPending");
            poll.setAccessible(true);

            // Drive the real reconcile path without a network writer. A fresh browser incarnation must replace
            // the logical activity subscription and queue an authoritative snapshot, regardless of option equality.
            try(AutoCloseable consumer = (AutoCloseable)clientConstructor.newInstance(service,
                    "00000000-0000-0000-0000-000000000003", null);
                LiveMultiplexOutput output =
                    new LiveMultiplexOutput(OutputStream.nullOutputStream()))
            {
                configure.invoke(consumer, configurationConstructor.newInstance(1L, Map.of("channel_activity", StatsWebServerService.parseMultiplexSubscription("channel_activity", first))));
                assertEquals(true, reconcile.invoke(consumer, output));
                StatsLiveEventHub.Subscription original = (StatsLiveEventHub.Subscription)activity.get(consumer);
                assertNotNull(original);
                byte[] initial = (byte[])poll.invoke(output);
                assertNotNull(initial);
                assertEquals("snapshot", OBJECT_MAPPER.readTree(Arrays.copyOfRange(initial,
                    LiveMultiplexFrame.HEADER_BYTES, initial.length)).path("event").textValue());

                configure.invoke(consumer, configurationConstructor.newInstance(2L,
                    Map.of("channel_activity", StatsWebServerService.parseMultiplexSubscription("channel_activity", first.deepCopy()))));
                assertEquals(false, reconcile.invoke(consumer, output));
                assertSame(original, activity.get(consumer));
                assertFalse(original.isClosed());
                assertNull(poll.invoke(output), "an unchanged incarnation must not add snapshot bandwidth");

                configure.invoke(consumer, configurationConstructor.newInstance(3L, Map.of("channel_activity", StatsWebServerService.parseMultiplexSubscription("channel_activity", second))));
                assertEquals(true, reconcile.invoke(consumer, output));
                assertTrue(original.isClosed());
                assertNotSame(original, activity.get(consumer));
                byte[] replacement = (byte[])poll.invoke(output);
                assertNotNull(replacement);
                assertEquals("snapshot", OBJECT_MAPPER.readTree(Arrays.copyOfRange(replacement,
                    LiveMultiplexFrame.HEADER_BYTES, replacement.length)).path("event").textValue());
            }
        }
        finally
        {
            if(previousAssetOverride == null) System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
            else System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, previousAssetOverride);
        }
    }

    @Test
    void servesWebBrandingAssetsWithBrowserMediaTypes()
    {
        assertEquals("image/svg+xml", StatsWebServerService.contentType(Path.of("vce-favicon.svg")));
        assertEquals("image/png", StatsWebServerService.contentType(Path.of("vce-icon-32.png")));
        assertEquals("application/manifest+json", StatsWebServerService.contentType(Path.of("site.webmanifest")));
    }

    @Test
    void listenerValidationDoesNotServeReceiverStatusAndReleasesPortForRuntime() throws Exception
    {
        assertListenerValidationDoesNotServeReceiverStatusAndReleasesPortForRuntime(false);
    }

    @Test
    void httpsListenerValidationDoesNotServeReceiverStatusAndReleasesPortForRuntime() throws Exception
    {
        assertListenerValidationDoesNotServeReceiverStatusAndReleasesPortForRuntime(true);
    }

    private void assertListenerValidationDoesNotServeReceiverStatusAndReleasesPortForRuntime(boolean httpsEnabled)
        throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("validation-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path assets = mTemporaryDirectory.resolve("validation-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>receiver</title>");
        String previousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestApplicationPreference applicationPreference = new TestApplicationPreference(0, false, httpsEnabled, true);
        TestUserPreferences preferences = new TestUserPreferences(applicationPreference,
            new TestDirectoryPreference(dataRoot));
        HttpClient client;

        try
        {
            int port;
            URI serverOrigin;
            try(WebListener validation = WebListener.validation(preferences))
            {
                assertTrue(validation.runtimeState("Web listener verified.").running(), validation.runtimeState("Web listener verified.").statusMessage());
                assertEquals(httpsEnabled, validation.runtimeState("Web listener verified.").https());
                port = validation.runtimeState("Web listener verified.").port();
                serverOrigin = URI.create((httpsEnabled ? "https" : "http") + "://127.0.0.1:" + port + "/");
                client = httpsEnabled ? httpsClient(new WebTlsMaterialService(dataRoot).validateInstalledMaterial()) :
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                for(String path: List.of("/", "/api/v1/status", WebSessionHttpController.SESSION_PATH,
                    "/assets/app.js"))
                {
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder(serverOrigin.resolve(path))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(503, response.statusCode(), path + ": " + response.body());
                    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                    assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
                    assertFalse(response.headers().firstValue("Set-Cookie").isPresent());
                    assertFalse(response.body().contains("stats_logging"));
                    if(path.startsWith("/api"))
                    {
                        assertEquals("receiver_starting",
                            OBJECT_MAPPER.readTree(response.body()).at("/error/code").textValue());
                    }
                }
                HttpResponse<String> mutation = client.send(HttpRequest.newBuilder(
                    serverOrigin.resolve(WebSessionHttpController.LOGIN_PATH))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(503, mutation.statusCode());
                assertFalse(mutation.headers().firstValue("Set-Cookie").isPresent());
                HttpResponse<String> head = client.send(HttpRequest.newBuilder(serverOrigin)
                    .timeout(Duration.ofSeconds(5)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(503, head.statusCode());
                assertEquals("", head.body());
            }

            applicationPreference.setPort(port);
            try(StatsWebServerService runtime = StatsWebTestRuntime.start(preferences))
            {
                assertTrue(runtime.getRuntimeState().running(), runtime.getRuntimeState().statusMessage());
                assertEquals(port, runtime.getRuntimeState().port());
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(serverOrigin).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(response.body().contains("receiver"));
            }
        }
        finally
        {
            if(previousAssetOverride == null)
            {
                System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
            }
            else
            {
                System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, previousAssetOverride);
            }
        }
    }

    @Test
    void applicationLogRouteIsRegisteredAndSurvivesListenerReload() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("log-viewer-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path logDirectory = Files.createDirectories(dataRoot.resolve("logs"));
        Files.writeString(logDirectory.resolve("sdrtrunk_app.log"),
            "20261002 101500.125 [main] INFO example.Application - Saved log route works\n");
        Path assets = Files.createDirectories(mTemporaryDirectory.resolve("log-viewer-assets"));
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>test</title>");
        String previousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        DirectoryPreference directoryPreference = new DirectoryPreference(preferenceType -> {})
        {
            @Override
            public Path getDirectoryApplicationRoot()
            {
                return dataRoot;
            }

            @Override
            public Path getDirectoryApplicationLog()
            {
                return logDirectory;
            }
        };
        TestUserPreferences preferences = new TestUserPreferences(
            new TestApplicationPreference(0, false, false, true), directoryPreference);

        try(StatsWebServerService service = StatsWebTestRuntime.start(preferences))
        {
            URI origin = origin(service.getRuntimeState().port());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            URI endpoint = origin.resolve(ApplicationLogHttpController.PATH);
            assertEquals(401, client.send(HttpRequest.newBuilder(endpoint).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
            char[] password = "application-log-lifecycle-password".toCharArray();
            try
            {
                service.provisionOrResetPrimaryAdmin(password);
            }
            finally
            {
                Arrays.fill(password, '\u0000');
            }
            String cookie = login(client, origin, "application-log-lifecycle-password");
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Cookie", cookie).GET().build();
            HttpResponse<String> initial = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, initial.statusCode(), initial.body());
            assertEquals("Saved log route works",
                OBJECT_MAPPER.readTree(initial.body()).at("/data/entries/0/message").asText());
            URI diagnosticEndpoint = origin.resolve(WebDiagnosticLogHttpController.PATH);
            assertEquals(401, client.send(HttpRequest.newBuilder(diagnosticEndpoint).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
            HttpResponse<String> status = client.send(HttpRequest.newBuilder(diagnosticEndpoint)
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, status.statusCode(), status.body());
            assertFalse(OBJECT_MAPPER.readTree(status.body()).at("/data/enabled").asBoolean());
            HttpResponse<String> session = client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/session"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            String csrf = OBJECT_MAPPER.readTree(session.body()).at("/data/csrf_token").asText();
            WebServerRuntimeState beforeLogging = service.getRuntimeState();
            HttpResponse<String> enabled = client.send(HttpRequest.newBuilder(diagnosticEndpoint)
                .timeout(Duration.ofSeconds(5)).header("Cookie", cookie).header("Origin", origin.toString())
                .header(WebRequestSecurity.CSRF_HEADER_NAME, csrf).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":true}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, enabled.statusCode(), enabled.body());
            assertTrue(OBJECT_MAPPER.readTree(enabled.body()).at("/data/enabled").asBoolean());
            assertEquals(beforeLogging, service.getRuntimeState(), "Logging must not recycle the listener");
            client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/status"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.discarding());
            long logDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            Path webLog = logDirectory.resolve("sdrtrunk_web.log");
            while((!Files.exists(webLog) || !Files.readString(webLog).contains("/api/v1/status")) &&
                System.nanoTime() < logDeadline) Thread.sleep(10);
            assertTrue(Files.readString(webLog).contains("/api/v1/status"), "Registered API contexts must be observed");
            assertFalse(Files.readString(webLog).contains(csrf));
            assertFalse(Files.readString(webLog).contains("application-log-lifecycle-password"));
            WebServerRuntimeState reloadedState = service.reloadActiveListener();
            assertTrue(reloadedState.running());
            HttpRequest reloadedRequest = HttpRequest.newBuilder(
                origin(reloadedState.port()).resolve(ApplicationLogHttpController.PATH))
                .timeout(Duration.ofSeconds(5)).header("Cookie", cookie).GET().build();
            HttpResponse<String> reloaded = client.send(reloadedRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reloaded.statusCode(), reloaded.body());
            assertEquals("no-store", reloaded.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("Saved log route works",
                OBJECT_MAPPER.readTree(reloaded.body()).at("/data/entries/0/message").asText());
        }
        finally
        {
            if(previousAssetOverride == null)
            {
                System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
            }
            else
            {
                System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, previousAssetOverride);
            }
        }
    }

    @Test
    void callMatchingRouteEnforcesAdminAndClearsLateBoundSourcesOnClose() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("call-matching-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path assets = mTemporaryDirectory.resolve("call-matching-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>test</title>");
        String previousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestUserPreferences preferences = new TestUserPreferences(
            new TestApplicationPreference(0, false, false, true),
            new TestDirectoryPreference(dataRoot));
        StatsWebServerService web = null;
        LogicalCallDiagnosticService diagnostic = null;
        AudioCallCoordinator coordinator = null;

        try
        {
            web = StatsWebTestRuntime.start(preferences);
            assertTrue(web.getRuntimeState().running(), web.getRuntimeState().statusMessage());
            URI endpoint = origin(web.getRuntimeState().port()).resolve(CallMatchingHttpController.PATH);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertEquals(401, client.send(HttpRequest.newBuilder(endpoint).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

            char[] adminPassword = "call-matching-admin-password".toCharArray();
            try
            {
                web.provisionOrResetPrimaryAdmin(adminPassword);
            }
            finally
            {
                Arrays.fill(adminPassword, '\u0000');
            }
            String adminCookie = login(client, origin(web.getRuntimeState().port()),
                "call-matching-admin-password");
            HttpRequest adminRequest = HttpRequest.newBuilder(endpoint).header("Cookie", adminCookie).GET().build();
            HttpResponse<String> unavailable = client.send(adminRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, unavailable.statusCode(), unavailable.body());

            URI serverOrigin = origin(web.getRuntimeState().port());
            HttpResponse<String> adminSession = client.send(HttpRequest.newBuilder(
                serverOrigin.resolve(WebSessionHttpController.SESSION_PATH))
                .header("Cookie", adminCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            String csrf = OBJECT_MAPPER.readTree(adminSession.body()).at("/data/csrf_token").textValue();
            HttpResponse<String> createdUser = client.send(HttpRequest.newBuilder(
                serverOrigin.resolve("/api/v1/admin/users"))
                .header("Cookie", adminCookie)
                .header("Origin", serverOrigin.toString())
                .header(WebRequestSecurity.CSRF_HEADER_NAME, csrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"listener\",\"password\":\"call-matching-user-password\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, createdUser.statusCode(), createdUser.body());
            String userCookie = login(client, origin(web.getRuntimeState().port()), "listener",
                "call-matching-user-password");
            HttpResponse<String> denied = client.send(HttpRequest.newBuilder(endpoint)
                .header("Cookie", userCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode(), denied.body());

            diagnostic = new LogicalCallDiagnosticService();
            coordinator = new AudioCallCoordinator(null, null, null, null, diagnostic);
            StatsWebTestRuntime.services(web).setLogicalCallDiagnostics(diagnostic, coordinator);
            HttpResponse<String> accepted = client.send(adminRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, accepted.statusCode(), accepted.body());
            JsonNode data = OBJECT_MAPPER.readTree(accepted.body()).path("data");
            assertTrue(data.path("available").booleanValue());
            assertTrue(data.path("duplicates").isArray());

            StatsWebTestRuntime.services(web).setLogicalCallDiagnostics(null, null);
            assertEquals(503, client.send(adminRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
            StatsWebTestRuntime.services(web).setLogicalCallDiagnostics(diagnostic, coordinator);
            web.close();
            var serviceField = ReceiverWebServices.class.getDeclaredField("mLogicalCallDiagnosticService");
            var coordinatorField = ReceiverWebServices.class.getDeclaredField("mAudioCallCoordinator");
            serviceField.setAccessible(true);
            coordinatorField.setAccessible(true);
            assertNull(serviceField.get(StatsWebTestRuntime.services(web)));
            assertNull(coordinatorField.get(StatsWebTestRuntime.services(web)));
        }
        finally
        {
            if(web != null)
            {
                web.close();
            }
            if(coordinator != null)
            {
                coordinator.disposeAndAwait(2, TimeUnit.SECONDS);
            }
            if(diagnostic != null)
            {
                diagnostic.close();
            }
            if(previousAssetOverride == null)
            {
                System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
            }
            else
            {
                System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, previousAssetOverride);
            }
        }
    }

    @Test
    void preservesSessionsAcrossRebindRetainsWorkingListenerOnFailureAndRevokesPrimaryReset() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path assets = mTemporaryDirectory.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>test</title>");
        String previousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestApplicationPreference applicationPreference = new TestApplicationPreference(0, false, false, true);
        TestUserPreferences preferences = new TestUserPreferences(applicationPreference,
            new TestDirectoryPreference(dataRoot));
        ConfigurationManager configurationManager = new ConfigurationManager(preferences, new io.github.dsheirer.source.tuner.manager.TunerManager(preferences),
            new AliasModel(), null);
        configurationManager.init();
        AliasAdministrationService aliasAdministrationService =
            AliasAdministrationServiceTestSupport.create(configurationManager);
        AliasAdministrationService.MutationResult createdList = aliasAdministrationService.createAliasList(
            "Lifecycle P25", AliasListFamily.P25);
        long aliasListId = createdList.aliasListId();
        StatsWebServerService service = null;

        try
        {
            service = StatsWebTestRuntime.start(preferences, configurationManager);
            WebServerRuntimeState initial = service.getRuntimeState();
            assertTrue(initial.running());
            assertTrue(initial.port() > 0);
            assertFalse(initial.anyIpEnabled());
            assertFalse(initial.https());
            assertNull(initial.certificateFingerprint());

            char[] initialPassword = "primary-admin-password".toCharArray();

            try
            {
                assertEquals(1, service.provisionOrResetPrimaryAdmin(initialPassword).authRevision());
            }
            finally
            {
                Arrays.fill(initialPassword, '\u0000');
            }

            assertTrue(service.isPrimaryAdminConfigured());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            URI initialOrigin = origin(initial.port());
            URI handoffUri = service.createDesktopAdministratorHandoffUri(
                WebAuthenticationService.DesktopHandoffDestination.DASHBOARD);
            assertEquals(initialOrigin.resolve(WebSessionHttpController.DESKTOP_HANDOFF_PATH), handoffUri);
            assertNull(handoffUri.getQuery());
            assertNull(handoffUri.getFragment());
            HttpResponse<String> handoff = client.send(HttpRequest.newBuilder(handoffUri)
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, handoff.statusCode());
            assertEquals("/", handoff.headers().firstValue("Location").orElseThrow());
            String setCookie = handoff.headers().firstValue("Set-Cookie").orElseThrow();
            String cookie = setCookie.substring(0, setCookie.indexOf(';'));
            assertAuthenticated(client, initialOrigin, cookie, true);
            assertAliasRoutes(client, initialOrigin, cookie, aliasListId);

            URI setupHandoffUri = service.createDesktopAdministratorHandoffUri(
                WebAuthenticationService.DesktopHandoffDestination.CHANNEL_SETUP);
            assertEquals(handoffUri, setupHandoffUri,
                "the setup destination must be held on the server rather than exposed in the handoff URL");
            HttpResponse<String> setupHandoff = client.send(HttpRequest.newBuilder(setupHandoffUri)
                .timeout(Duration.ofSeconds(10)).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(303, setupHandoff.statusCode());
            assertEquals("/?view=channel-setup", setupHandoff.headers().firstValue("Location").orElseThrow());
            setCookie = setupHandoff.headers().firstValue("Set-Cookie").orElseThrow();
            cookie = setCookie.substring(0, setCookie.indexOf(';'));
            assertAuthenticated(client, initialOrigin, cookie, true);
            assertAliasRoutes(client, initialOrigin, cookie, aliasListId);

            int replacementPort = availableLoopbackPort();
            applicationPreference.setPort(replacementPort);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            WebServerRuntimeState rebound = service.getRuntimeState();
            assertTrue(rebound.running());
            assertEquals(replacementPort, rebound.port());
            assertAuthenticated(client, origin(replacementPort), cookie, true);
            assertAliasRoutes(client, origin(replacementPort), cookie, aliasListId);

            WebServerRuntimeState recycled = service.reloadActiveListener();
            assertTrue(recycled.running());
            assertEquals(replacementPort, recycled.port());
            assertAuthenticated(client, origin(replacementPort), cookie, true);
            assertAliasRoutes(client, origin(replacementPort), cookie, aliasListId);

            try(ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
            {
                applicationPreference.setPort(occupied.getLocalPort());
                service.preferenceUpdated(PreferenceType.APPLICATION);
                WebServerRuntimeState retained = service.getRuntimeState();
                assertTrue(retained.running());
                assertEquals(replacementPort, retained.port());
                assertTrue(retained.statusMessage().contains("previous listener remains active"));
                assertFalse(retained.statusMessage().contains("\n"));
                assertAuthenticated(client, origin(replacementPort), cookie, true);
                assertAliasRoutes(client, origin(replacementPort), cookie, aliasListId);
            }

            applicationPreference.setEnabled(false);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            assertFalse(service.getRuntimeState().running());
            applicationPreference.setPort(replacementPort);
            applicationPreference.setEnabled(true);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            assertTrue(service.getRuntimeState().running());
            assertAuthenticated(client, origin(replacementPort), cookie, false);

            cookie = login(client, origin(replacementPort), "primary-admin-password");
            assertAuthenticated(client, origin(replacementPort), cookie, true);

            char[] replacementPassword = "replacement-admin-password".toCharArray();

            try
            {
                assertEquals(2, service.provisionOrResetPrimaryAdmin(replacementPassword).authRevision());
            }
            finally
            {
                Arrays.fill(replacementPassword, '\u0000');
            }

            assertAuthenticated(client, origin(replacementPort), cookie, false);
        }
        finally
        {
            if(service != null)
            {
                service.close();
            }

            if(previousAssetOverride == null)
            {
                System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
            }
            else
            {
                System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, previousAssetOverride);
            }
        }
    }

    @Test
    void automaticallyUsesHttpsAndGeneratesCertificateForNetworkAccess() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("automatic-https-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path assets = mTemporaryDirectory.resolve("automatic-https-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>test</title>");
        String previousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestApplicationPreference applicationPreference = new TestApplicationPreference(0, true, false, true);
        TestUserPreferences preferences = new TestUserPreferences(applicationPreference,
            new TestDirectoryPreference(dataRoot));
        ConfigurationManager configurationManager = new ConfigurationManager(preferences, new io.github.dsheirer.source.tuner.manager.TunerManager(preferences),
            new AliasModel(), null);
        configurationManager.init();
        AliasAdministrationService aliasAdministrationService =
            AliasAdministrationServiceTestSupport.create(configurationManager);
        AliasAdministrationService.MutationResult createdList = aliasAdministrationService.createAliasList(
            "HTTPS Lifecycle P25", AliasListFamily.P25);
        long aliasListId = createdList.aliasListId();
        StatsWebServerService service = null;

        try
        {
            service = StatsWebTestRuntime.start(preferences, configurationManager);
            WebServerRuntimeState state = service.getRuntimeState();
            assertTrue(state.running(), state.statusMessage());
            assertTrue(state.anyIpEnabled());
            assertTrue(state.https(), "Network access must ignore a stored plain-HTTP preference");
            assertTrue(state.certificateFingerprint() != null && !state.certificateFingerprint().isBlank());
            WebTlsMaterialService tls = service.getTlsMaterialService();
            TlsMaterial material = tls.validateInstalledMaterial();
            assertEquals(state.certificateFingerprint(), material.leafSha256Fingerprint());
            assertTrue(material.coversHost("localhost"));
            assertTrue(material.coversHost("127.0.0.1"));
            assertHttpsIndex(material, state.port());
            assertFalse(WebListener.requiresRenewal(material,
                material.notAfter().minus(Duration.ofDays(31))));
            assertTrue(WebListener.requiresRenewal(material,
                material.notAfter().minus(Duration.ofDays(30))));

            WebTlsMaterialService customSource = new WebTlsMaterialService(
                mTemporaryDirectory.resolve("custom-certificate-source"));
            TlsMaterial replacement = customSource.generateSelfSigned("replacement.receiver.test",
                List.of("replacement.receiver.test", "127.0.0.1"));
            HttpClient client = httpsClient(material, replacement);
            URI origin = httpsOrigin(state.port());
            char[] password = "automatic-https-admin-password".toCharArray();

            try
            {
                service.provisionOrResetPrimaryAdmin(password);
            }
            finally
            {
                Arrays.fill(password, '\u0000');
            }

            String cookie = login(client, origin, "automatic-https-admin-password");
            assertAliasRoutes(client, origin, cookie, aliasListId);
            StatsWebServerService.TlsActivation activation =
                service.installAndActivateCustomCertificate(replacement);
            WebServerRuntimeState reloaded = activation.runtimeState();
            assertTrue(reloaded.running(), reloaded.statusMessage());
            assertTrue(reloaded.https());
            assertEquals(replacement.leafSha256Fingerprint(), reloaded.certificateFingerprint());
            assertEquals(WebCertificateMode.CUSTOM, applicationPreference.getStatsWebServerCertificateMode());
            assertHttpsIndex(replacement, reloaded.port());
            assertAuthenticated(client, httpsOrigin(reloaded.port()), cookie, true);
            assertAliasRoutes(client, httpsOrigin(reloaded.port()), cookie, aliasListId);
        }
        finally
        {
            if(service != null)
            {
                service.close();
            }

            if(previousAssetOverride == null)
            {
                System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
            }
            else
            {
                System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, previousAssetOverride);
            }
        }
    }

    @Test
    void preservesExistingCertificateWhenOlderProfileHasNoCertificateMode() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("existing-certificate-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebTlsMaterialService tls = new WebTlsMaterialService(dataRoot);
        TlsMaterial existing = tls.generateSelfSigned("custom.example", List.of("custom.example"));
        String existingFingerprint = existing.leafSha256Fingerprint();
        Path assets = mTemporaryDirectory.resolve("existing-certificate-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("index.html"), "<!doctype html><title>test</title>");
        String previousAssetOverride = System.getProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
        System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, assets.toString());
        TestApplicationPreference applicationPreference = new TestApplicationPreference(0, true, false, false);
        TestUserPreferences preferences = new TestUserPreferences(applicationPreference,
            new TestDirectoryPreference(dataRoot));
        StatsWebServerService service = null;

        try
        {
            service = StatsWebTestRuntime.start(preferences);
            WebServerRuntimeState state = service.getRuntimeState();
            assertTrue(state.running(), state.statusMessage());
            assertTrue(state.https());
            assertEquals(existingFingerprint, state.certificateFingerprint());
            assertEquals(WebCertificateMode.CUSTOM, applicationPreference.getStatsWebServerCertificateMode());
        }
        finally
        {
            if(service != null)
            {
                service.close();
            }

            if(previousAssetOverride == null)
            {
                System.clearProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY);
            }
            else
            {
                System.setProperty(StatsWebPath.ROOT_OVERRIDE_PROPERTY, previousAssetOverride);
            }
        }
    }

    private static void assertAuthenticated(HttpClient client, URI origin, String cookie, boolean expected)
        throws Exception
    {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(origin.resolve(
                WebSessionHttpController.SESSION_PATH))
            .timeout(Duration.ofSeconds(10))
            .header("Cookie", cookie)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = OBJECT_MAPPER.readTree(response.body());
        assertEquals(expected, body.at("/data/authenticated").booleanValue());
    }

    private static void assertAliasRoutes(HttpClient client, URI origin, String cookie, long aliasListId)
        throws Exception
    {
        HttpResponse<String> catalog = client.send(HttpRequest.newBuilder(origin.resolve(
                "/api/v1/admin/alias-lists"))
            .timeout(Duration.ofSeconds(10))
            .header("Cookie", cookie)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, catalog.statusCode(), catalog.body());
        JsonNode aliasLists = OBJECT_MAPPER.readTree(catalog.body()).at("/data/alias_lists");
        boolean found = false;
        for(JsonNode aliasList: aliasLists)
        {
            found |= aliasList.path("alias_list_id").longValue() == aliasListId;
        }
        assertTrue(found, "Alias-list catalog did not contain ID [" + aliasListId + "]: " + catalog.body());

        HttpResponse<String> observed = client.send(HttpRequest.newBuilder(origin.resolve(
                "/api/v1/alias-lists/" + aliasListId + "/observed-group-identities"))
            .timeout(Duration.ofSeconds(10))
            .header("Cookie", cookie)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, observed.statusCode(), observed.body());
        assertTrue(OBJECT_MAPPER.readTree(observed.body()).get("data").isArray());
    }

    private static String login(HttpClient client, URI origin, String password) throws Exception
    {
        return login(client, origin, "admin", password);
    }

    private static String login(HttpClient client, URI origin, String username, String password) throws Exception
    {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(origin.resolve(
                WebSessionHttpController.LOGIN_PATH))
            .timeout(Duration.ofSeconds(30))
            .header("Origin", origin.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static URI origin(int port)
    {
        return URI.create("http://127.0.0.1:" + port);
    }

    private static URI httpsOrigin(int port)
    {
        return URI.create("https://127.0.0.1:" + port);
    }

    private static void assertHttpsIndex(TlsMaterial material, int port) throws Exception
    {
        HttpClient client = httpsClient(material);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(httpsOrigin(port).resolve("/"))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("<title>test</title>"), response.body());
    }

    private static HttpClient httpsClient(TlsMaterial... materials) throws Exception
    {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);

        for(int x = 0; x < materials.length; x++)
        {
            trustStore.setCertificateEntry("web-listener-" + x, materials[x].leafCertificate());
        }

        TrustManagerFactory trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .sslContext(sslContext).build();
    }

    private static int availableLoopbackPort() throws Exception
    {
        try(ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
        {
            return socket.getLocalPort();
        }
    }

    private static final class RuntimeStateObserver
    {
        private final StatsWebServerService mService;
        private final List<WebServerRuntimeState> mNotifications = new ArrayList<>();
        private final List<WebServerRuntimeState> mReadbacks = new ArrayList<>();

        private RuntimeStateObserver(StatsWebServerService service)
        {
            mService = service;
        }

        @Subscribe
        public void stateChanged(WebServerRuntimeState state)
        {
            mNotifications.add(state);
            mReadbacks.add(mService.getRuntimeState());
        }

        private void assertLatest(int count, WebServerRuntimeState expected)
        {
            assertEquals(count, mNotifications.size());
            assertSame(expected, mNotifications.getLast());
            for(int index = 0; index < count; index++)
            {
                assertSame(mNotifications.get(index), mReadbacks.get(index),
                    "Notifications must follow runtime-state publication");
            }
        }
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final ApplicationPreference mApplicationPreference;
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(ApplicationPreference applicationPreference,
                                    DirectoryPreference directoryPreference)
        {
            mApplicationPreference = applicationPreference;
            mDirectoryPreference = directoryPreference;
        }

        @Override
        public ApplicationPreference getApplicationPreference()
        {
            return mApplicationPreference;
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }

    private static final class TestApplicationPreference extends ApplicationPreference
    {
        private volatile int mPort;
        private volatile boolean mEnabled = true;
        private volatile boolean mWebDiagnosticLoggingEnabled;
        private boolean mAnyIpEnabled;
        private boolean mHttpsEnabled;
        private boolean mCertificateModeConfigured;
        private WebCertificateMode mCertificateMode = WebCertificateMode.AUTOMATIC;

        private TestApplicationPreference(int port, boolean anyIpEnabled, boolean httpsEnabled,
                                          boolean certificateModeConfigured)
        {
            super(preferenceType -> {});
            mPort = port;
            mAnyIpEnabled = anyIpEnabled;
            mHttpsEnabled = httpsEnabled;
            mCertificateModeConfigured = certificateModeConfigured;
        }

        private void setPort(int port)
        {
            mPort = port;
        }

        private void setEnabled(boolean enabled)
        {
            mEnabled = enabled;
        }

        private void setNetworkAccessEnabled(boolean enabled)
        {
            mAnyIpEnabled = enabled;
            if(enabled)
            {
                mHttpsEnabled = true;
            }
        }

        @Override
        public boolean isStatsWebServerEnabled()
        {
            return mEnabled;
        }

        @Override public boolean isWebDiagnosticLoggingEnabled() { return mWebDiagnosticLoggingEnabled; }
        @Override public void setWebDiagnosticLoggingEnabled(boolean enabled) { mWebDiagnosticLoggingEnabled = enabled; }

        @Override
        public int getStatsWebServerPort()
        {
            return mPort;
        }

        @Override
        public boolean isStatsWebServerAnyIpEnabled()
        {
            return mAnyIpEnabled;
        }

        @Override
        public boolean isStatsWebServerHttpsEnabled()
        {
            return mHttpsEnabled;
        }

        @Override
        public boolean isStatsWebServerCertificateModeConfigured()
        {
            return mCertificateModeConfigured;
        }

        @Override
        public WebCertificateMode getStatsWebServerCertificateMode()
        {
            return mCertificateMode;
        }

        @Override
        public void setStatsWebServerCertificateMode(WebCertificateMode mode)
        {
            mCertificateMode = mode;
            mCertificateModeConfigured = true;
        }

        @Override
        public void initializeStatsWebServerCertificateMode(WebCertificateMode mode)
        {
            if(!mCertificateModeConfigured)
            {
                mCertificateMode = mode;
                mCertificateModeConfigured = true;
            }
        }
    }

    private static final class TestDirectoryPreference extends DirectoryPreference
    {
        private final Path mDataRoot;

        private TestDirectoryPreference(Path dataRoot)
        {
            super(preferenceType -> {});
            mDataRoot = dataRoot;
        }

        @Override
        public Path getDirectoryApplicationRoot()
        {
            return mDataRoot;
        }
    }
}
