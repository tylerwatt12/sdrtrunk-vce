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
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticService;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.application.WebCertificateMode;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.web.http.WebSessionHttpController;
import io.github.dsheirer.web.http.CallMatchingHttpController;
import io.github.dsheirer.web.http.ApplicationLogHttpController;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import java.net.InetAddress;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
        try(StatsWebServerService service = new StatsWebServerService(preferences))
        {
            assertTrue(service.getRuntimeState().running(), service.getRuntimeState().statusMessage());
            var validate = StatsWebServerService.class.getDeclaredMethod("validateMultiplexSubscription",
                String.class, JsonNode.class);
            validate.setAccessible(true);
            JsonNode first = OBJECT_MAPPER.readTree("""
                {"delta":true,"markers":true,"subscription_id":"00000000-0000-0000-0000-000000000001"}
                """);
            JsonNode second = OBJECT_MAPPER.readTree("""
                {"delta":true,"markers":true,"subscription_id":"00000000-0000-0000-0000-000000000002"}
                """);
            validate.invoke(service, "channel_activity", OBJECT_MAPPER.readTree("{\"delta\":true}"));
            validate.invoke(service, "channel_activity", first);
            for(String invalid: List.of("{\"subscription_id\":\"not-a-uuid\"}",
                "{\"subscription_id\":false}", "{\"subscription_id\":null}", "{\"subscription_id\":\"\"}",
                "{\"delta\":\"true\"}", "{\"unknown\":true}"))
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
            var reconcile = clientType.getDeclaredMethod("reconcile", StatsWebServerService.MultiplexOutput.class);
            reconcile.setAccessible(true);
            var activity = clientType.getDeclaredField("mChannelActivity");
            activity.setAccessible(true);
            var poll = StatsWebServerService.MultiplexOutput.class.getDeclaredMethod("pollPending");
            poll.setAccessible(true);

            // Drive the real reconcile path without a network writer. A fresh browser incarnation must replace
            // the logical activity subscription and queue an authoritative snapshot, regardless of option equality.
            try(AutoCloseable consumer = (AutoCloseable)clientConstructor.newInstance(service,
                    "00000000-0000-0000-0000-000000000003", null);
                StatsWebServerService.MultiplexOutput output =
                    new StatsWebServerService.MultiplexOutput(OutputStream.nullOutputStream()))
            {
                configure.invoke(consumer, configurationConstructor.newInstance(1L, Map.of("channel_activity", first)));
                assertEquals(true, reconcile.invoke(consumer, output));
                StatsLiveEventHub.Subscription original = (StatsLiveEventHub.Subscription)activity.get(consumer);
                assertNotNull(original);
                byte[] initial = (byte[])poll.invoke(output);
                assertNotNull(initial);
                assertEquals("snapshot", OBJECT_MAPPER.readTree(Arrays.copyOfRange(initial,
                    LiveMultiplexFrame.HEADER_BYTES, initial.length)).path("event").textValue());

                configure.invoke(consumer, configurationConstructor.newInstance(2L,
                    Map.of("channel_activity", first.deepCopy())));
                assertEquals(false, reconcile.invoke(consumer, output));
                assertSame(original, activity.get(consumer));
                assertFalse(original.isClosed());
                assertNull(poll.invoke(output), "an unchanged incarnation must not add snapshot bandwidth");

                configure.invoke(consumer, configurationConstructor.newInstance(3L, Map.of("channel_activity", second)));
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
            try(StatsWebServerService validation = StatsWebServerService.forListenerValidation(preferences))
            {
                assertTrue(validation.getRuntimeState().running(), validation.getRuntimeState().statusMessage());
                assertEquals(httpsEnabled, validation.getRuntimeState().https());
                port = validation.getRuntimeState().port();
                serverOrigin = URI.create((httpsEnabled ? "https" : "http") + "://127.0.0.1:" + port + "/");
                client = httpsEnabled ? httpsClient(validation.getTlsMaterialService().validateInstalledMaterial()) :
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
            try(StatsWebServerService runtime = new StatsWebServerService(preferences))
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

        try(StatsWebServerService service = new StatsWebServerService(preferences))
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
            web = new StatsWebServerService(preferences);
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
            web.setLogicalCallDiagnostics(diagnostic, coordinator);
            HttpResponse<String> accepted = client.send(adminRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, accepted.statusCode(), accepted.body());
            JsonNode data = OBJECT_MAPPER.readTree(accepted.body()).path("data");
            assertTrue(data.path("available").booleanValue());
            assertTrue(data.path("duplicates").isArray());

            web.setLogicalCallDiagnostics(null, null);
            assertEquals(503, client.send(adminRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
            web.setLogicalCallDiagnostics(diagnostic, coordinator);
            web.close();
            var serviceField = StatsWebServerService.class.getDeclaredField("mLogicalCallDiagnosticService");
            var coordinatorField = StatsWebServerService.class.getDeclaredField("mAudioCallCoordinator");
            serviceField.setAccessible(true);
            coordinatorField.setAccessible(true);
            assertNull(serviceField.get(web));
            assertNull(coordinatorField.get(web));
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
        ConfigurationManager configurationManager = new ConfigurationManager(preferences, null,
            new AliasModel(), null, null);
        configurationManager.init();
        AliasAdministrationService aliasAdministrationService =
            AliasAdministrationServiceTestSupport.create(configurationManager);
        AliasAdministrationService.MutationResult createdList = aliasAdministrationService.createAliasList(
            "Lifecycle P25", AliasListFamily.P25, aliasAdministrationService.catalog().revision());
        long aliasListId = createdList.aliasListId();
        StatsWebServerService service = null;

        try
        {
            service = new StatsWebServerService(preferences, null, null, aliasAdministrationService);
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
            URI handoffUri = service.createDesktopAdministratorHandoffUri();
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
        ConfigurationManager configurationManager = new ConfigurationManager(preferences, null,
            new AliasModel(), null, null);
        configurationManager.init();
        AliasAdministrationService aliasAdministrationService =
            AliasAdministrationServiceTestSupport.create(configurationManager);
        AliasAdministrationService.MutationResult createdList = aliasAdministrationService.createAliasList(
            "HTTPS Lifecycle P25", AliasListFamily.P25, aliasAdministrationService.catalog().revision());
        long aliasListId = createdList.aliasListId();
        StatsWebServerService service = null;

        try
        {
            service = new StatsWebServerService(preferences, null, null, aliasAdministrationService);
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
            assertFalse(StatsWebServerService.automaticCertificateRequiresRenewal(material,
                material.notAfter().minus(Duration.ofDays(31))));
            assertTrue(StatsWebServerService.automaticCertificateRequiresRenewal(material,
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
            service = new StatsWebServerService(preferences);
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
        private final boolean mAnyIpEnabled;
        private final boolean mHttpsEnabled;
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

        @Override
        public boolean isStatsWebServerEnabled()
        {
            return mEnabled;
        }

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
