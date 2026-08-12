/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.WebAccessHttpController;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebTunerSpectrumAccessTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void protectsTunerDiscoveryAndBinaryTopicWithTunerSpectrumCapability() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] password = "tuner-spectrum-admin-password".toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(password);
        }
        finally
        {
            Arrays.fill(password, '\u0000');
        }

        WebAccessHttpController accessController = new WebAccessHttpController(accessService);
        TunerDiagnosticService tunerDiagnostics = new TunerDiagnosticService(List::of, (target, consumer) -> null);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        accessController.register(server);
        new StatsApiV1Controller(null, Map::of, accessController, tunerDiagnostics).register(server);
        server.start();

        try
        {
            URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() +
                StatsApiV1.TUNER_DIAGNOSTICS);
            HttpClient client = HttpClient.newHttpClient();
            assertEquals(401, client.send(HttpRequest.newBuilder(target).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());

            accessService.setCapabilityTier(WebCapability.TUNER_SPECTRUM_VIEW, AccessTier.PUBLIC);
            assertEquals(200, client.send(HttpRequest.newBuilder(target).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());

            assertTrue(StatsWebServerService.MULTIPLEX_CAPABILITIES.contains(
                WebCapability.TUNER_SPECTRUM_VIEW));
            assertEquals(WebCapability.TUNER_SPECTRUM_VIEW,
                StatsWebServerService.capabilityForTopic("tuner_diagnostics"));
            assertEquals(WebCapability.LIVE_VIEW,
                StatsWebServerService.capabilityForTopic("channel_activity"));
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            tunerDiagnostics.close();
        }
    }
}
