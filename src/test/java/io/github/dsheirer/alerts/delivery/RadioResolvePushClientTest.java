package io.github.dsheirer.alerts.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioResolvePushClientTest
{
    @Test void registrationUsesPersistedIdentityAndCredentialWithoutBearerOnPublicRegistration() throws Exception
    {
        var request = new AtomicReference<Map>(); var authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/senders", exchange -> {
            request.set(new ObjectMapper().readValue(exchange.getRequestBody(), Map.class));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"sender_id\":\"sender-fixture\",\"status\":\"pending\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try
        {
            var client = client(server); String credential = PublicAccessVerifier.token();
            assertEquals("pending", client.register("install-fixture", "VCE installation", credential).path("status").asText());
            assertEquals("install-fixture", request.get().get("installation_id"));
            assertEquals(credential, request.get().get("credential")); assertNull(authorization.get());
        }
        finally { server.stop(0); }
    }

    @Test void directProofIsOwnedByServerAndBoundToExactNormalizedUrl() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/public-test", exchange -> {
            var request = new ObjectMapper().readTree(exchange.getRequestBody());
            byte[] response = new ObjectMapper().writeValueAsBytes(Map.of("public_access", true, "checked_url", request.path("url").asText()));
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try
        {
            var verifier = new PublicAccessVerifier(client(server));
            var result = verifier.test("https://VCE.example:443/");
            String token = (String)result.get("test_token");
            assertTrue(verifier.passed("https://vce.example", token));
            assertFalse(verifier.passed("https://other.example", token));
            assertFalse(verifier.passed("https://vce.example", PublicAccessVerifier.token()));
            assertFalse(verifier.hasChallenge(token));
            for(String url: new String[]{"http://vce.example", "https://127.0.0.1", "https://vce.example/private", "https://user@vce.example", "https://vce.example?x=1"})
                assertThrows(IllegalArgumentException.class, () -> PublicAccessVerifier.normalizeUrl(url));
        }
        finally { server.stop(0); }
    }

    @Test void gatewayRefusesRedirectsAndOversizedBodiesAndDoesNotExposeResponseText() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new java.util.concurrent.atomic.AtomicInteger(302);
        server.createContext("/senders/me", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1/private");
            byte[] response = ("{\"private\":\"" + "sensitive-provider-response".repeat(43000) + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), response.length);
            try { exchange.getResponseBody().write(response); } catch(java.io.IOException ignored) { }
            exchange.close();
        }); server.start();
        try
        {
            var client = client(server);
            var exception = assertThrows(java.io.IOException.class, () -> client.status(PublicAccessVerifier.token()));
            assertFalse(exception.getMessage().contains("sensitive-provider"));
            status.set(200);
            assertThrows(java.io.IOException.class, () -> client.status(PublicAccessVerifier.token()));
        }
        finally { server.stop(0); }
    }

    @Test void acceptsFullBoundedUpdatePageIncludingUnicodeModerationReasons() throws Exception
    {
        var rows = java.util.stream.IntStream.rangeClosed(1, 200).mapToObj(cursor -> Map.of(
            "cursor", Integer.toString(cursor), "type", "sender_status", "status", "rejected",
            "moderation_reason", "理由".repeat(250))).toList();
        byte[] response = new ObjectMapper().writeValueAsBytes(Map.of("cursor", "200", "updates", rows, "has_more", true));
        assertTrue(response.length > 65_536);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/updates", exchange -> {
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try(var client = client(server))
        {
            var result = client.updates(PublicAccessVerifier.token(), "0");
            assertEquals("200", result.path("cursor").asText());
            assertEquals(200, result.path("updates").size());
        }
        finally { server.stop(0); }
    }

    @Test void unavailablePublicCheckerDoesNotClaimTheVceAddressFailed() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var remoteStatus = new java.util.concurrent.atomic.AtomicInteger(404);
        server.createContext("/public-test", exchange -> { exchange.sendResponseHeaders(remoteStatus.get(), -1); exchange.close(); }); server.start();
        try(var gateway = client(server))
        {
            var verifier = new PublicAccessVerifier(gateway);
            var result = verifier.test("https://vce.example");
            @SuppressWarnings("unchecked") var status = (Map<String,Object>)result.get("public_access");
            assertEquals("unavailable", status.get("status")); assertEquals("", result.get("test_token"));
            assertTrue(((String)status.get("message")).contains("has not checked"));
            remoteStatus.set(400);
            @SuppressWarnings("unchecked") var invalidAddress = (Map<String,Object>)verifier.test("https://vce.example").get("public_access");
            assertEquals("failed", invalidAddress.get("status"));
            assertTrue(((String)invalidAddress.get("message")).contains("hostname"));
            assertThrows(IllegalArgumentException.class, () -> verifier.test("http://vce.example"));
        }
        finally { server.stop(0); }
    }

    private static RadioResolvePushClient client(HttpServer server)
    {
        return new RadioResolvePushClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }
}
