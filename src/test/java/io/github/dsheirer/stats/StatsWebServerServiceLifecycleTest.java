/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.application.WebCertificateMode;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.web.http.WebAccessHttpController;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import java.net.InetAddress;
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
        StatsWebServerService service = null;

        try
        {
            service = new StatsWebServerService(preferences);
            WebServerRuntimeState initial = service.getRuntimeState();
            assertTrue(initial.running());
            assertTrue(initial.port() > 0);
            assertFalse(initial.anyIpEnabled());
            assertFalse(initial.https());
            assertNull(initial.certificateFingerprint());

            char[] initialPassword = "primary-admin-password".toCharArray();

            try
            {
                assertEquals(1, service.provisionOrResetPrimaryAdmin(initialPassword).credentialVersion());
            }
            finally
            {
                Arrays.fill(initialPassword, '\u0000');
            }

            assertTrue(service.isPrimaryAdminConfigured());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            URI initialOrigin = origin(initial.port());
            String cookie = login(client, initialOrigin, "primary-admin-password");
            assertAuthenticated(client, initialOrigin, cookie, true);

            int replacementPort = availableLoopbackPort();
            applicationPreference.setPort(replacementPort);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            WebServerRuntimeState rebound = service.getRuntimeState();
            assertTrue(rebound.running());
            assertEquals(replacementPort, rebound.port());
            assertAuthenticated(client, origin(replacementPort), cookie, true);

            WebServerRuntimeState recycled = service.reloadActiveListener();
            assertTrue(recycled.running());
            assertEquals(replacementPort, recycled.port());
            assertAuthenticated(client, origin(replacementPort), cookie, true);

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
                assertEquals(2, service.provisionOrResetPrimaryAdmin(replacementPassword).credentialVersion());
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
        StatsWebServerService service = null;

        try
        {
            service = new StatsWebServerService(preferences);
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
            StatsWebServerService.TlsActivation activation =
                service.installAndActivateCustomCertificate(replacement);
            WebServerRuntimeState reloaded = activation.runtimeState();
            assertTrue(reloaded.running(), reloaded.statusMessage());
            assertTrue(reloaded.https());
            assertEquals(replacement.leafSha256Fingerprint(), reloaded.certificateFingerprint());
            assertEquals(WebCertificateMode.CUSTOM, applicationPreference.getStatsWebServerCertificateMode());
            assertHttpsIndex(replacement, reloaded.port());
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
                WebAccessHttpController.SESSION_PATH))
            .timeout(Duration.ofSeconds(10))
            .header("Cookie", cookie)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = OBJECT_MAPPER.readTree(response.body());
        assertEquals(expected, body.get("authenticated").booleanValue());
    }

    private static String login(HttpClient client, URI origin, String password) throws Exception
    {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(origin.resolve(
                WebAccessHttpController.LOGIN_PATH))
            .timeout(Duration.ofSeconds(30))
            .header("Origin", origin.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"admin\",\"password\":\"" + password + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static URI origin(int port)
    {
        return URI.create("http://127.0.0.1:" + port);
    }

    private static void assertHttpsIndex(TlsMaterial material, int port) throws Exception
    {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("web-listener", material.leafCertificate());
        TrustManagerFactory trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .sslContext(sslContext).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("https://127.0.0.1:" + port + "/"))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("<title>test</title>"), response.body());
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
