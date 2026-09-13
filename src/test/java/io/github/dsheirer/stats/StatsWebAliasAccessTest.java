/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.http.WebSessionHttpController;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebAliasAccessTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String EXPORT_TOKEN = "fedcba9876543210fedcba9876543210";

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void aliasCatalogAndCsvAreFixedAdministratorResources() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES(1, 1, 'Dispatch', 'TALKGROUP', 'APCO25', 56132)
                """);
        }
        WebAccessService accessService = new WebAccessService(database);
        char[] adminPassword = "alias-admin-password".toCharArray();
        char[] userPassword = "alias-user-password".toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(adminPassword);
            accessService.createUser("listener", userPassword, AccessTier.USER);
        }
        finally
        {
            Arrays.fill(adminPassword, '\u0000');
            Arrays.fill(userPassword, '\u0000');
        }

        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
        WebRequestSecurity requestSecurity = new WebRequestSecurity(accessService, authenticationService);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        new WebSessionHttpController(accessService, authenticationService, requestSecurity).register(server);
        StatsWebDatabase statsDatabase = new StatsWebDatabase(new UserPreferences(), database);
        new StatsApiV1Controller(statsDatabase, Map::of, requestSecurity, null).register(server);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();

            assertEquals(401, get(client, origin, StatsApiV1.ALIAS_LISTS, null).statusCode());
            assertEquals(401, get(client, origin, StatsApiV1.ALIASES, null).statusCode());
            assertEquals(401, get(client, origin, StatsApiV1.ALIASES + "/ids", null).statusCode());
            assertEquals(403,
                get(client, origin, StatsApiV1.EXPORTS + "/aliases.csv", null).statusCode());
            HttpResponse<String> deniedTransfer = get(client, origin,
                StatsApiV1.EXPORTS + "/alias-list.csv?list=1&scope=all&export_token=" + EXPORT_TOKEN, null);
            assertEquals(403, deniedTransfer.statusCode());
            assertEquals("DENY", deniedTransfer.headers().firstValue("X-Frame-Options").orElseThrow(),
                "Authorization failures must retain the global clickjacking policy");

            String listener = login(client, origin, "listener", "alias-user-password");
            assertEquals(403, get(client, origin, StatsApiV1.ALIAS_LISTS, listener).statusCode());
            assertEquals(403, get(client, origin, StatsApiV1.ALIASES, listener).statusCode());
            assertEquals(403, get(client, origin, StatsApiV1.ALIASES + "/ids", listener).statusCode());
            assertEquals(403, get(client, origin, StatsApiV1.EXPORTS + "/aliases.csv", listener).statusCode());
            assertEquals(403, get(client, origin,
                StatsApiV1.EXPORTS + "/alias-list.csv?list=1&scope=filtered&export_token=" + EXPORT_TOKEN,
                listener).statusCode());

            String admin = login(client, origin, "admin", "alias-admin-password");
            assertEquals(200, get(client, origin, StatsApiV1.ALIAS_LISTS, admin).statusCode());
            assertEquals(200, get(client, origin, StatsApiV1.ALIASES, admin).statusCode());
            HttpResponse<String> ids = get(client, origin, StatsApiV1.ALIASES + "/ids?list=1", admin);
            assertEquals(200, ids.statusCode());
            assertEquals(1, OBJECT_MAPPER.readTree(ids.body()).at("/data/count").asInt());
            assertEquals(1, OBJECT_MAPPER.readTree(ids.body()).at("/data/alias_ids/0").asInt());
            HttpResponse<String> missingTransfer = get(client, origin,
                StatsApiV1.EXPORTS + "/alias-list.csv?list=999999&scope=all&export_token=" + EXPORT_TOKEN,
                admin);
            assertEquals(404, missingTransfer.statusCode(), missingTransfer.body());
            assertEquals("not_found", OBJECT_MAPPER.readTree(missingTransfer.body()).at("/error/code").asText());
            assertEquals("SAMEORIGIN", missingTransfer.headers().firstValue("X-Frame-Options").orElseThrow());
            assertFalse(missingTransfer.headers().firstValue("Set-Cookie").isPresent());
            assertEquals(200, get(client, origin, StatsApiV1.EXPORTS + "/aliases.csv", admin).statusCode());
            HttpResponse<String> transfer = get(client, origin,
                StatsApiV1.EXPORTS + "/alias-list.csv?list=1&scope=filtered&q=dispatch&export_token=" +
                    EXPORT_TOKEN, admin);
            assertEquals(200, transfer.statusCode(), transfer.body());
            assertEquals("1", transfer.headers().firstValue("X-Export-Row-Count").orElseThrow());
            assertTrue(transfer.body().startsWith("\uFEFFformat_version,alias_list,name,"));
            assertEquals("SAMEORIGIN", transfer.headers().firstValue("X-Frame-Options").orElseThrow());
            assertTrue(transfer.headers().firstValue("Content-Security-Policy").orElseThrow()
                .contains("frame-ancestors 'self'"));
            String readyCookie = transfer.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(readyCookie.contains(WebRequestSecurity.ALIAS_EXPORT_READY_COOKIE_PREFIX + EXPORT_TOKEN + "=1"));
            assertTrue(readyCookie.contains("Max-Age=30"));
            assertTrue(readyCookie.contains("SameSite=Strict"));
            assertFalse(readyCookie.contains("HttpOnly"));
            HttpResponse<String> detail = get(client, origin, StatsApiV1.ALIASES + "/1", admin);
            assertEquals(200, detail.statusCode());
            assertEquals(1, OBJECT_MAPPER.readTree(detail.body()).at("/data/alias_id").asInt());
            assertEquals(true, OBJECT_MAPPER.readTree(detail.body()).at("/data/breakdown").isArray());
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            requestSecurity.close();
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI origin, String path, String cookie)
        throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path)).GET();

        if(cookie != null)
        {
            request.header("Cookie", cookie);
        }

        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String login(HttpClient client, URI origin, String username, String password) throws Exception
    {
        String body = OBJECT_MAPPER.writeValueAsString(Map.of("username", username, "password", password));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/login"))
            .header("Origin", origin.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }
}
