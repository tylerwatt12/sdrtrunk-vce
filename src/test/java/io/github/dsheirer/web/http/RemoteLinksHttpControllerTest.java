/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.remote.RemoteLinkAdministrationService;
import io.github.dsheirer.remote.RemoteOriginLookup;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemoteLinksHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void snapshotIsExplicitlyCredentialFreeAndUsesStableWebNames() throws Exception
    {
        FakeService service = new FakeService();
        try(TestServer server = new TestServer(service))
        {
            HttpResponse<String> response = server.send(server.request("").GET());
            assertEquals(200, response.statusCode());
            JsonNode data = data(response);
            assertEquals(7L, data.path("revision").longValue());
            assertEquals("10.8.0.2", data.at("/sender_connection/destination_host").textValue());
            assertEquals("feed-1", data.at("/senders/0/feeds/0/feed_id").textValue());
            assertEquals("Metro", data.at("/senders/0/feeds/0/system_name").textValue());
            assertEquals("North Site", data.at("/senders/0/feeds/0/site_name").textValue());
            assertEquals("DEGRADED", data.at("/listener/dependencies/0/state").textValue());
            assertFalse(response.body().contains("shared-secret"));
            assertFalse(response.body().contains("secret\""));
        }
    }

    @Test
    void secretBearingWebDtosRedactTheirStringRepresentations()
    {
        RemoteLinkAdministrationService.SenderConnectionUpdate update =
            new RemoteLinkAdministrationService.SenderConnectionUpdate(true, "vpn-host", 35_300, "sender-a",
                "private-update-secret", List.of("channel-a"));
        RemoteLinkAdministrationService.CreateSenderResult result =
            new RemoteLinkAdministrationService.CreateSenderResult(8L, "sender-a", "Hilltop",
                "private-result-secret");

        assertTrue(update.toString().contains("secret=<redacted>"));
        assertFalse(update.toString().contains("private-update-secret"));
        assertTrue(result.toString().contains("secret=<redacted>"));
        assertFalse(result.toString().contains("private-result-secret"));
    }

    @Test
    void outboundCredentialIsWriteOnlyAndExportSelectionIsBounded() throws Exception
    {
        FakeService service = new FakeService();
        try(TestServer server = new TestServer(service))
        {
            String update = """
                {"revision":7,"enabled":true,"destination_host":"vpn-host","destination_port":35300,
                 "sender_id":"sender-a","secret":"shared-secret",
                 "exported_channel_configuration_ids":["channel-a","channel-b"]}
                """;
            HttpResponse<String> response = server.send(server.jsonRequest("/sender-connection")
                .PUT(HttpRequest.BodyPublishers.ofString(update)));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("shared-secret", service.mSenderUpdate.secret());
            assertEquals(List.of("channel-a", "channel-b"),
                service.mSenderUpdate.exportedChannelConfigurationIds());
            assertFalse(response.body().contains("shared-secret"));

            String duplicate = update.replace("[\"channel-a\",\"channel-b\"]",
                "[\"channel-a\",\"channel-a\"]");
            assertEquals(400, server.send(server.jsonRequest("/sender-connection")
                .PUT(HttpRequest.BodyPublishers.ofString(duplicate))).statusCode());
        }
    }

    @Test
    void rejectsValuesLargerThanThePersistedSettingsCanStore() throws Exception
    {
        FakeService service = new FakeService();
        try(TestServer server = new TestServer(service))
        {
            String boundaryConnection = MAPPER.writeValueAsString(Map.of(
                "revision", 7, "enabled", true, "destination_host", "h".repeat(255),
                "destination_port", 35_300, "sender_id", "i".repeat(36), "secret", "s".repeat(256),
                "exported_channel_configuration_ids", List.of("channel-a")));
            assertEquals(200, server.send(server.jsonRequest("/sender-connection")
                .PUT(HttpRequest.BodyPublishers.ofString(boundaryConnection))).statusCode());

            String longHost = boundaryConnection.replace("h".repeat(255), "h".repeat(256));
            assertEquals(400, server.send(server.jsonRequest("/sender-connection")
                .PUT(HttpRequest.BodyPublishers.ofString(longHost))).statusCode());
            String longSenderId = boundaryConnection.replace("i".repeat(36), "i".repeat(37));
            assertEquals(400, server.send(server.jsonRequest("/sender-connection")
                .PUT(HttpRequest.BodyPublishers.ofString(longSenderId))).statusCode());
            String longSecret = boundaryConnection.replace("s".repeat(256), "s".repeat(257));
            assertEquals(400, server.send(server.jsonRequest("/sender-connection")
                .PUT(HttpRequest.BodyPublishers.ofString(longSecret))).statusCode());

            String boundaryName = MAPPER.writeValueAsString(Map.of(
                "revision", 7, "display_name", "n".repeat(120)));
            assertEquals(201, server.send(server.jsonRequest("/senders")
                .POST(HttpRequest.BodyPublishers.ofString(boundaryName))).statusCode());
            String longName = boundaryName.replace("n".repeat(120), "n".repeat(121));
            assertEquals(400, server.send(server.jsonRequest("/senders")
                .POST(HttpRequest.BodyPublishers.ofString(longName))).statusCode());
        }
    }

    @Test
    void hostIssuesTheSecretOnceAndMutationsRequireStrictBodies() throws Exception
    {
        FakeService service = new FakeService();
        try(TestServer server = new TestServer(service))
        {
            HttpResponse<String> created = server.send(server.jsonRequest("/senders")
                .POST(HttpRequest.BodyPublishers.ofString("{\"revision\":7,\"display_name\":\"Hilltop\"}")));
            assertEquals(201, created.statusCode(), created.body());
            assertEquals("shared-secret", data(created).path("secret").textValue());
            assertEquals("Hilltop", service.mCreateRequest.displayName());

            assertEquals(400, server.send(server.jsonRequest("/listener")
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"revision\":7,\"enabled\":true,\"bind_address\":\"0.0.0.0\",\"port\":35300,\"extra\":1}")))
                .statusCode());
            assertEquals(400, server.send(server.request("?unexpected=true").GET()).statusCode());
            HttpResponse<String> wrongMethod = server.send(server.request("/listener").GET());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("PUT", wrongMethod.headers().firstValue("Allow").orElseThrow());
        }
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    {
        JsonNode document = MAPPER.readTree(response.body());
        return document.path("data");
    }

    private static final class TestServer implements AutoCloseable
    {
        private final HttpServer mServer;
        private final URI mOrigin;
        private final HttpClient mClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        private TestServer(RemoteLinkAdministrationService service) throws Exception
        {
            mServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            RemoteLinksHttpController controller = new RemoteLinksHttpController(service);
            mServer.createContext(RemoteLinksHttpController.PATH, controller::handle);
            mServer.start();
            mOrigin = URI.create("http://127.0.0.1:" + mServer.getAddress().getPort());
        }

        private HttpRequest.Builder request(String suffix)
        {
            return HttpRequest.newBuilder(mOrigin.resolve(RemoteLinksHttpController.PATH + suffix))
                .timeout(Duration.ofSeconds(10));
        }

        private HttpRequest.Builder jsonRequest(String suffix)
        {
            return request(suffix).header("Content-Type", "application/json");
        }

        private HttpResponse<String> send(HttpRequest.Builder request) throws Exception
        {
            return mClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close()
        {
            mServer.stop(0);
        }
    }

    private static final class FakeService implements RemoteLinkAdministrationService
    {
        private SenderConnectionUpdate mSenderUpdate;
        private CreateSenderRequest mCreateRequest;

        @Override
        public RemoteLinkSnapshot snapshot()
        {
            ListenerSnapshot listener = new ListenerSnapshot(true, "0.0.0.0", 35_300,
                ListenerState.LISTENING, null,
                List.of(new DependencySnapshot("jmbe", "P25 voice", DependencyState.DEGRADED,
                    "Voice synthesis is unavailable")));
            SenderConnectionSnapshot connection = new SenderConnectionSnapshot(true, "10.8.0.2", 35_300,
                "sender-local", true, SenderConnectionState.CONNECTED, 1_000L, null, List.of("channel-a"));
            FeedSnapshot feed = new FeedSnapshot("feed-1", "North", "North simulcast", "P25_PHASE_1",
                "Metro", "North Site", 0xbee00, 0x123, 1, 2, 851_012_500L, FeedState.CONNECTED, true, true,
                "remote-channel-1", 2L, 1_100L, 24L, 0L, 0L, null);
            SenderSnapshot sender = new SenderSnapshot("sender-1", "Hilltop", SenderState.CONNECTED, true,
                500L, 1_100L, false, 2L, null, List.of(feed));
            return new RemoteLinkSnapshot(7L, listener, connection,
                List.of(new ExportChannelOption("channel-a", "Downtown", "Metro", "Central", "P25_PHASE_1")),
                List.of(new AliasListOption(2L, "Metro aliases")), List.of(sender));
        }

        @Override
        public RemoteLinkSnapshot updateListener(long expectedRevision, ListenerUpdate request)
        {
            return snapshot();
        }

        @Override
        public RemoteLinkSnapshot updateSenderConnection(long expectedRevision, SenderConnectionUpdate request)
        {
            mSenderUpdate = request;
            return snapshot();
        }

        @Override
        public CreateSenderResult createSender(long expectedRevision, CreateSenderRequest request)
        {
            mCreateRequest = request;
            return new CreateSenderResult(8L, "sender-created", request.displayName(), "shared-secret");
        }

        @Override
        public RemoteLinkSnapshot updateSender(long expectedRevision, String senderId, UpdateSenderRequest request)
        {
            return snapshot();
        }

        @Override
        public RemoteLinkSnapshot revokeSender(long expectedRevision, String senderId)
        {
            return snapshot();
        }

        @Override
        public RemoteLinkSnapshot adoptFeed(long expectedRevision, String senderId, String feedId,
                                            AdoptFeedRequest request)
        {
            return snapshot();
        }

        @Override
        public RemoteLinkSnapshot updateFeed(long expectedRevision, String senderId, String feedId,
                                             UpdateFeedRequest request)
        {
            return snapshot();
        }

        @Override
        public RemoteLinkSnapshot forgetFeed(long expectedRevision, String senderId, String feedId)
        {
            return snapshot();
        }

        @Override
        public RemoteOriginLookup.OriginSnapshot originSnapshot()
        {
            return RemoteOriginLookup.OriginSnapshot.EMPTY;
        }
    }
}
