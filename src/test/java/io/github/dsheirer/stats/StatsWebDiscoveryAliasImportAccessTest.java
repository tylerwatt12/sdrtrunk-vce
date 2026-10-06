/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.http.SpectrumDiscoveryHttpController;
import io.github.dsheirer.web.http.SpectrumSearchHttpController;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.http.WebSessionHttpController;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the production permission wrapper with real sessions and a registered, side-effect-free import target. */
class StatsWebDiscoveryAliasImportAccessTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WIZARD = "00000000-0000-0000-0000-000000000001";
    @TempDir Path directory;

    @Test
    void importRoutesKeepAdministratorAndCsrfBoundariesAndExposeTheSnakeCaseContract() throws Exception
    {
        Path database = directory.resolve("access.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService access = new WebAccessService(database);
        char[] adminPassword = "test-discovery-admin-password".toCharArray();
        char[] listenerPassword = "test-discovery-listener-password".toCharArray();
        try
        {
            access.provisionOrResetPrimaryAdmin(adminPassword);
            access.createUser("listener", listenerPassword);
        }
        finally { Arrays.fill(adminPassword, '\0'); Arrays.fill(listenerPassword, '\0'); }
        AtomicInteger reads = new AtomicInteger(), imports = new AtomicInteger();
        var helper = new DiscoveryAliasImportService(new DiscoveryAliasImportService.Operations()
        {
            public DiscoveryAliasImportService.Catalog catalog(int systemId)
            { reads.incrementAndGet(); return new DiscoveryAliasImportService.Catalog("catalog", 9); }
            public String preview(int systemId, long aliasListId, String catalogId) { return "preview"; }
            public DiscoveryAliasImportService.Counts apply(String previewId)
            { imports.incrementAndGet(); return new DiscoveryAliasImportService.Counts(9, 0, 0); }
        });
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, "County aliases", "County P25", new RadioReferenceDiscoveryResolver.Result("matched", "",
            new RadioReferenceDiscoveryResolver.Match(10, 20, "County P25", "North", List.of(), "", ""), "radioreference"));
        helper.retain(WIZARD, batch);
        WebAuthenticationService authentication = new WebAuthenticationService(access);
        WebRequestSecurity security = new WebRequestSecurity(access, authentication);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        new WebSessionHttpController(access, authentication, security).register(server);
        HttpHandler endpoint = exchange -> {
            if(exchange.getRequestURI().getPath().endsWith("/aliases/import"))
            {
                if(!"POST".equals(exchange.getRequestMethod()))
                {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    ApiHttpResponse.sendError(exchange, 405, "method_not_allowed", "Method not allowed");
                    return;
                }
                JsonNode body = MAPPER.readTree(exchange.getRequestBody());
                try { ApiHttpResponse.sendData(exchange, 200, helper.importAliases(WIZARD, body.required("alias_list_id").longValue())); }
                catch(IllegalArgumentException exception) { ApiHttpResponse.sendError(exchange, 400, "invalid_request", exception.getMessage()); }
            }
            else ApiHttpResponse.sendData(exchange, 200, Map.of("alias_import", batch.snapshot()));
        };
        server.createContext(SpectrumDiscoveryHttpController.PATH, security.protectApi(WebCapability.ADMIN_CHANNELS,
            StatsWebServerService.protectDiscoveryAliasImports(security, endpoint)));
        server.createContext(SpectrumSearchHttpController.PATH, security.protectApi(WebCapability.ADMIN_CHANNELS,
            security.protect(WebCapability.ADMIN_TUNERS, StatsWebServerService.protectDiscoveryAliasImports(security, endpoint))));
        server.start();
        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            Login listener = login(client, origin, "listener", "test-discovery-listener-password");
            Login admin = login(client, origin, "admin", "test-discovery-admin-password");
            for(String root: List.of(SpectrumDiscoveryHttpController.PATH, SpectrumSearchHttpController.PATH))
            {
                String path = root + '/' + WIZARD + "/aliases/import";
                assertEquals(401, send(client, request(origin, path).header("Origin", origin.toString())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"alias_list_id\":7}"))).statusCode());
                assertEquals(403, send(client, mutation(origin, path, listener).POST(HttpRequest.BodyPublishers.ofString("{\"alias_list_id\":7}"))).statusCode());
                assertEquals(403, send(client, request(origin, path).header("Cookie", admin.cookie()).header("Origin", origin.toString())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"alias_list_id\":7}"))).statusCode());
                assertEquals(403, send(client, request(origin, path).header("Cookie", admin.cookie()).header("Origin", "http://foreign.invalid")
                    .header(WebRequestSecurity.CSRF_HEADER_NAME, admin.csrf()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"alias_list_id\":7}"))).statusCode());
                assertEquals(405, send(client, request(origin, path).header("Cookie", admin.cookie()).GET()).statusCode());
                assertEquals(400, send(client, mutation(origin, path, admin).POST(HttpRequest.BodyPublishers.ofString("{\"alias_list_id\":8}"))).statusCode());
                assertEquals(0, reads.get());
                assertEquals(0, imports.get());
                var regularRead = send(client, request(origin, root + '/' + WIZARD).header("Cookie", admin.cookie()).GET());
                assertEquals(200, regularRead.statusCode());
                assertTrue(data(regularRead).has("alias_import"));
                assertFalse(data(regularRead).has("aliasImport"));
                assertEquals(200, send(client, mutation(origin, root + '/' + WIZARD + "/save", admin)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            }
            String path = SpectrumSearchHttpController.PATH + '/' + WIZARD + "/aliases/import";
            var imported = send(client, mutation(origin, path, admin).POST(HttpRequest.BodyPublishers.ofString("{\"alias_list_id\":7}")));
            assertEquals(200, imported.statusCode());
            assertEquals("no-store", imported.headers().firstValue("Cache-Control").orElse(null));
            JsonNode result = data(imported);
            assertTrue(result.path("complete").asBoolean());
            JsonNode target = result.path("targets").get(0);
            assertEquals(7, target.path("alias_list_id").asLong());
            assertEquals("County aliases", target.path("alias_list_name").asText());
            assertEquals(10, target.path("system_id").asInt());
            assertEquals("County P25", target.path("system_name").asText());
            assertEquals("imported", target.path("state").asText());
            assertEquals(9, target.path("added").asInt());
            assertEquals(0, target.path("updated").asInt());
            assertEquals(0, target.path("unchanged").asInt());
            assertFalse(target.has("aliasListId"));
            assertFalse(target.has("systemId"));
            assertEquals(1, reads.get());
            assertEquals(1, imports.get());
            assertEquals(200, send(client, mutation(origin, path, admin).POST(HttpRequest.BodyPublishers.ofString("{\"alias_list_id\":7}"))).statusCode());
            assertEquals(1, imports.get());
            for(WebCapability capability: List.of(WebCapability.ADMIN_CHANNELS, WebCapability.ADMIN_TUNERS,
                WebCapability.ADMIN_SETTINGS, WebCapability.ADMIN_ALIASES)) assertFalse(capability.configurable());
        }
        finally { server.stop(0); executor.shutdownNow(); security.close(); helper.clear(); }
    }

    private static Login login(HttpClient client, URI origin, String username, String password) throws Exception
    {
        var response = send(client, request(origin, "/api/v1/auth/login").header("Origin", origin.toString())
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                MAPPER.writeValueAsString(Map.of("username", username, "password", password)))));
        assertEquals(200, response.statusCode(), response.body());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new Login(cookie, data(response).path("csrf_token").asText());
    }
    private static HttpRequest.Builder request(URI origin, String path)
    { return HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5)); }
    private static HttpRequest.Builder mutation(URI origin, String path, Login login)
    { return request(origin, path).header("Origin", origin.toString()).header("Cookie", login.cookie())
        .header(WebRequestSecurity.CSRF_HEADER_NAME, login.csrf()).header("Content-Type", "application/json"); }
    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    { return client.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private static JsonNode data(HttpResponse<String> response) throws Exception
    { return MAPPER.readTree(response.body()).path("data"); }
    private record Login(String cookie, String csrf) { }
}
