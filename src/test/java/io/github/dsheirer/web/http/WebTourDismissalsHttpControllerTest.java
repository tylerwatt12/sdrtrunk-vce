/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebTourDismissalService;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebTourDismissalsHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "example-tour-password";
    @TempDir Path directory;

    @Test void signedInAccountsMergeTheirOwnHistoryWithoutPreferenceRevisions() throws Exception
    {
        try(var app = new Application(directory.resolve("history.sqlite")))
        {
            assertEquals(401, app.send(app.request().GET()).statusCode());
            Login admin = app.login("admin"), listener = app.login("listener");
            assertEquals(Map.of(), MAPPER.convertValue(app.get(listener).path("dismissed"), Map.class));
            var first = app.put(admin, "{\"version\":1,\"dismissed\":{\"welcome\":1,\"admin\":3}}");
            assertEquals(200, first.statusCode(), first.body());
            assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(first.headers().firstValue("ETag").isEmpty());
            assertEquals(1, data(first).path("version").intValue());
            var merged = app.put(admin, "{\"version\":1,\"dismissed\":{\"admin\":1,\"future-feature\":9}}");
            assertEquals(200, merged.statusCode());
            assertEquals(Map.of("welcome", 1, "admin", 3, "future-feature", 9),
                MAPPER.convertValue(data(merged).path("dismissed"), Map.class));
            assertEquals(200, app.put(listener, "{\"version\":1,\"dismissed\":{\"admin\":2}}").statusCode());
            assertEquals(2, app.get(listener).at("/dismissed/admin").intValue());
            assertEquals(3, app.get(admin).at("/dismissed/admin").intValue());
            assertEquals(3, data(app.put(admin, "{\"version\":1,\"dismissed\":{}}")).path("dismissed").size());
            assertEquals(1, new io.github.dsheirer.web.auth.WebUserPreferencesService(app.database)
                .get(app.access.primaryAdmin().orElseThrow()).revision());
        }
    }

    @Test void rejectsForgedRequestsAmbiguousPathsAndMalformedDocumentsWithoutChangingHistory() throws Exception
    {
        try(var app = new Application(directory.resolve("security.sqlite")))
        {
            Login login = app.login("admin");
            String valid = "{\"version\":1,\"dismissed\":{\"welcome\":1}}";
            assertEquals(403, app.send(app.request().header("Cookie", login.cookie())
                .header("Origin", app.origin.toString()).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(valid))).statusCode());
            assertEquals(403, app.send(app.request().header("Cookie", login.cookie())
                .header("Origin", "https://different.example.invalid").header(WebRequestSecurity.CSRF_HEADER_NAME, login.csrf())
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(valid))).statusCode());
            assertEquals(401, app.send(app.request().header("Cookie", "sdrtrunk_session=invalid").GET()).statusCode());
            for(String suffix: new String[]{"/other", "%2Fother"})
                assertEquals(404, app.send(HttpRequest.newBuilder(app.origin.resolve(WebTourDismissalsHttpController.PATH + suffix))
                    .header("Cookie", login.cookie()).GET()).statusCode());
            assertEquals(400, app.send(HttpRequest.newBuilder(app.origin.resolve(WebTourDismissalsHttpController.PATH + "?user=listener"))
                .header("Cookie", login.cookie()).GET()).statusCode());
            assertEquals(400, app.send(app.request().header("Cookie", login.cookie())
                .method("GET", HttpRequest.BodyPublishers.ofString("unexpected"))).statusCode());
            var unsupported = app.send(app.request().header("Cookie", login.cookie()).POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(405, unsupported.statusCode());
            assertEquals("GET, PUT", unsupported.headers().firstValue("Allow").orElseThrow());
            assertEquals(415, app.send(app.mutation(login, "text/plain").PUT(HttpRequest.BodyPublishers.ofString(valid))).statusCode());
            assertEquals(413, app.put(login, " ".repeat(WebTourDismissalService.MAXIMUM_JSON_BYTES + 1)).statusCode());
            for(String invalid: new String[]{"", "null", "[]", "{}", "{\"version\":1}",
                "{\"version\":2,\"dismissed\":{}}", "{\"version\":1.0,\"dismissed\":{}}",
                "{\"version\":1,\"dismissed\":{},\"user_id\":2}", "{\"version\":1,\"dismissed\":null}",
                "{\"version\":1,\"dismissed\":{\"admin\":0}}", "{\"version\":1,\"dismissed\":{\"admin\":1000001}}",
                "{\"version\":1,\"dismissed\":{\"admin\":1.5}}", "{\"version\":1,\"dismissed\":{\"admin\":\"1\"}}",
                "{\"version\":1,\"dismissed\":{\"admin\":true}}", "{\"version\":1,\"dismissed\":{\"A/b\":1}}",
                "{\"version\":1,\"dismissed\":{\"admin\":1,\"admin\":2}}", valid + valid})
            {
                var response = app.put(login, invalid);
                assertEquals(422, response.statusCode(), response.body());
                assertEquals("invalid_tour_dismissals", MAPPER.readTree(response.body()).at("/error/code").textValue());
            }
            var oversized = MAPPER.createObjectNode().put("version", 1);
            var dismissed = oversized.putObject("dismissed");
            for(int i=0; i<65; i++) dismissed.put("guide-" + i, 1);
            assertEquals(422, app.put(login, oversized.toString()).statusCode());
            assertTrue(app.get(login).path("dismissed").isEmpty());
        }
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    { return MAPPER.readTree(response.body()).path("data"); }
    private record Login(String cookie, String csrf) {}

    private static final class Application implements AutoCloseable
    {
        final Path database;
        final WebAccessService access;
        final WebRequestSecurity security;
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final URI origin;
        Application(Path path) throws Exception
        {
            database = path;
            SdrTrunkDatabaseStartup.createGlobalDatabase(path);
            access = new WebAccessService(path);
            access.provisionOrResetPrimaryAdmin(PASSWORD.toCharArray());
            access.createUser("listener", PASSWORD.toCharArray());
            var authentication = new WebAuthenticationService(access);
            security = new WebRequestSecurity(access, authentication);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(executor);
            new WebSessionHttpController(access, authentication, security).register(server);
            var controller = new WebTourDismissalsHttpController(security, new WebTourDismissalService(path));
            server.createContext(WebTourDismissalsHttpController.PATH, controller::handle);
            server.start();
            origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }
        Login login(String username) throws Exception
        {
            var response = send(HttpRequest.newBuilder(origin.resolve(WebSessionHttpController.LOGIN_PATH))
                .header("Origin", origin.toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(Map.of("username", username, "password", PASSWORD)))));
            assertEquals(200, response.statusCode(), response.body());
            String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            return new Login(cookie, data(response).path("csrf_token").textValue());
        }
        HttpRequest.Builder request()
        { return HttpRequest.newBuilder(origin.resolve(WebTourDismissalsHttpController.PATH)).timeout(Duration.ofSeconds(10)); }
        HttpRequest.Builder mutation(Login login, String contentType)
        { return request().header("Cookie", login.cookie()).header("Origin", origin.toString())
            .header(WebRequestSecurity.CSRF_HEADER_NAME, login.csrf()).header("Content-Type", contentType); }
        HttpResponse<String> put(Login login, String body) throws Exception
        { return send(mutation(login, "application/json").PUT(HttpRequest.BodyPublishers.ofString(body))); }
        JsonNode get(Login login) throws Exception
        { var response = send(request().header("Cookie", login.cookie()).GET()); assertEquals(200, response.statusCode()); return data(response); }
        HttpResponse<String> send(HttpRequest.Builder request) throws Exception
        { return client.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
        @Override public void close() { server.stop(0); security.close(); executor.shutdownNow(); client.close(); }
    }
}
