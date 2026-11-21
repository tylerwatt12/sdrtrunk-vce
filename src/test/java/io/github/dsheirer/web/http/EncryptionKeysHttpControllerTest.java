/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModuleManager;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionKeyResolver;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.EncryptionKeySchema;
import io.github.dsheirer.preference.encryption.EncryptionKeyPreference;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAccessSession;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EncryptionKeysHttpControllerTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test
    void applicationWiringUsesTheLivePreferenceAndAdministratorProtection() throws Exception
    {
        String source = Files.readString(Path.of("src/main/java/io/github/dsheirer/stats/StatsWebServerService.java"));
        assertTrue(source.matches("(?s).*new EncryptionKeysHttpController\\(\\s*" +
            "mUserPreferences.getEncryptionKeyPreference\\(\\),\\s*" +
            "mUserPreferences.getVoiceDecryptionModulePreference\\(\\).getModuleManager\\(\\)\\).*"));
        assertTrue(source.matches("(?s).*server.createContext\\(EncryptionKeysHttpController.PATH,\\s*" +
            "mWebRequestSecurity.protectApi\\(\\s*WebCapability.ADMIN_SETTINGS,\\s*" +
            "encryptionKeysController::handle\\)\\).*"));
    }

    @Test
    void protectsEveryReadAndMutationWithAdministratorSessionSameOriginAndCsrf() throws Exception
    {
        try(Fixture fixture = new Fixture(directory))
        {
            assertEquals(401, fixture.send("GET", "", null, null, null, null).statusCode());
            assertEquals(403, fixture.send("GET", "", fixture.user, null, null, null).statusCode());
            var initial = fixture.get();
            String revision = initial.headers().firstValue("ETag").orElseThrow();
            assertEquals("no-store", initial.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals('"' + JSON.readTree(initial.body()).path("revision").textValue() + '"', revision);
            for(String method: List.of("POST", "PUT", "DELETE"))
            {
                String suffix = method.equals("POST") ? "" : "/unused";
                String body = method.equals("DELETE") ? null : key("APCO25", 170, 7, "0102030405").toString();
                assertEquals(403, fixture.send(method, suffix, fixture.admin, body, revision, null).statusCode());
                assertEquals(403, fixture.send(method, suffix, fixture.admin, body, revision, "invalid").statusCode());
                assertEquals(403, fixture.send(method, suffix, fixture.user, body, revision, fixture.user.csrfToken())
                    .statusCode());
                var crossOrigin = fixture.request(method, suffix, fixture.admin, body, revision,
                    fixture.admin.csrfToken(), "https://unrelated.invalid");
                assertEquals(403, fixture.client.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            assertFalse(fixture.preference.hasKeys());
        }
    }

    @Test
    void managesEveryIncludedAlgorithmAsPlainConfigurationAndUpdatesTheLiveResolver() throws Exception
    {
        try(Fixture fixture = new Fixture(directory))
        {
            var response = fixture.get();
            JsonNode document = JSON.readTree(response.body());
            assertEquals(3, document.path("protocols").size());
            int algorithms = 0;
            int keyId = 0;
            for(JsonNode protocol: document.path("protocols"))
            {
                assertEquals(3, protocol.path("algorithms").size());
                for(JsonNode algorithm: protocol.path("algorithms"))
                {
                    algorithms++;
                    String protocolId = protocol.path("id").textValue();
                    int algorithmId = algorithm.path("id").intValue();
                    int minimum = algorithm.path("minimum_key_bytes").intValue();
                    String hex = "01".repeat(minimum);
                    ObjectNode input = key(protocolId, algorithmId, keyId++, hex);
                    response = fixture.mutate("POST", "", input, response);
                    assertEquals(201, response.statusCode());
                    VoiceEncryptionProtocol namespace = VoiceEncryptionProtocol.valueOf(protocolId);
                    assertEquals(hex, fixture.resolver.resolve(new VoiceEncryptionContext(namespace, algorithmId,
                        keyId - 1, null, 0, null)).orElseThrow().getKeyHex());
                }
            }
            assertEquals(9, algorithms);
            var reopened = new EncryptionKeyPreference(ignored -> {}, new ApplicationSettingsStore(fixture.database));
            assertEquals(9, reopened.getKeys().size());
            assertEquals(JSON.readTree(response.body()).path("revision").textValue(), reopened.snapshot().revision());
            String stored = new ApplicationSettingsStore(fixture.database).loadJson(EncryptionKeySchema.KEY,
                EncryptionKeySchema.MAXIMUM_BYTES).orElseThrow();
            assertTrue(stored.contains("\"key_hex\":\"01\""), "Short ADP keys remain valid ordinary configuration");

            JsonNode first = JSON.readTree(response.body()).path("keys").get(0);
            String suffix = "/" + first.path("id").textValue();
            ObjectNode edit = key(first.path("protocol").textValue(), first.path("algorithm_id").intValue(),
                first.path("key_id").intValue(), first.path("key_hex").textValue());
            edit.put("enabled", false);
            edit.put("label", "Disabled key");
            response = fixture.mutate("PUT", suffix, edit, response);
            assertEquals(200, response.statusCode());
            assertTrue(fixture.preference.getKeys().stream().anyMatch(saved -> !saved.isEnabled()));
            response = fixture.mutate("DELETE", suffix, null, response);
            assertEquals(200, response.statusCode());
            assertEquals(8, JSON.readTree(response.body()).path("keys").size());
            assertEquals(404, fixture.mutate("DELETE", suffix, null, response).statusCode());
        }
    }

    @Test
    void rejectsMalformedInputsAndStaleConcurrentWritesWithoutChangingCommittedKeys() throws Exception
    {
        try(Fixture fixture = new Fixture(directory))
        {
            var initial = fixture.get();
            String revision = initial.headers().firstValue("ETag").orElseThrow();
            ObjectNode valid = key("APCO25", 170, 65535, "01 23:45-67_89");
            assertEquals(428, fixture.send("POST", "", fixture.admin, valid.toString(), null,
                fixture.admin.csrfToken()).statusCode());
            assertEquals(400, fixture.send("POST", "", fixture.admin, valid.toString(), "bad",
                fixture.admin.csrfToken()).statusCode());
            assertEquals(404, fixture.mutate("PUT", "/missing", valid, initial).statusCode());
            for(ObjectNode bad: List.of(valid.deepCopy().put("protocol", "DMR"),
                valid.deepCopy().put("algorithm_id", 129), valid.deepCopy().put("key_id", -1),
                valid.deepCopy().put("key_id", 65536), valid.deepCopy().put("key_id", 1.5),
                valid.deepCopy().put("key_hex", "not hexadecimal"), valid.deepCopy().put("key_hex", "01".repeat(6)),
                valid.deepCopy().put("enabled", "true"), valid.deepCopy().put("scope", "x".repeat(4097))))
            {
                assertEquals(422, fixture.mutate("POST", "", bad, initial).statusCode());
            }
            assertEquals(400, fixture.mutate("POST", "", valid.deepCopy().put("id", "client-selected"), initial)
                .statusCode());
            assertEquals(400, fixture.send("POST", "", fixture.admin, "{\"key_hex\":\"secret\",\"key_hex\":\"duplicate\"}",
                revision, fixture.admin.csrfToken()).statusCode());
            assertEquals(400, fixture.send("POST", "", fixture.admin, valid + " {}", revision,
                fixture.admin.csrfToken()).statusCode());
            assertEquals(400, fixture.send("GET", "?query=1", fixture.admin, null, null, null).statusCode());
            assertEquals(400, fixture.send("GET", "", fixture.admin, "{}", null, null).statusCode());
            assertEquals(405, fixture.send("GET", "/missing", fixture.admin, null, null, null).statusCode());
            assertEquals(404, fixture.send("GET", "/missing/extra", fixture.admin, null, null, null).statusCode());
            assertEquals(415, fixture.client.send(HttpRequest.newBuilder(fixture.origin.resolve(EncryptionKeysHttpController.PATH))
                .header("Cookie", cookie(fixture.admin)).header("Origin", fixture.origin.toString())
                .header("X-CSRF-Token", fixture.admin.csrfToken()).header("If-Match", revision)
                .POST(HttpRequest.BodyPublishers.ofString(valid.toString())).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode());
            var oversized = fixture.send("POST", "", fixture.admin, " ".repeat(100_000), revision,
                fixture.admin.csrfToken());
            assertEquals(413, oversized.statusCode());
            assertEquals("close", oversized.headers().firstValue("Connection").orElseThrow());
            assertFalse(fixture.preference.hasKeys());

            var first = fixture.client.sendAsync(fixture.request("POST", "", fixture.admin, valid.toString(), revision,
                fixture.admin.csrfToken(), fixture.origin.toString()), HttpResponse.BodyHandlers.ofString());
            var second = fixture.client.sendAsync(fixture.request("POST", "", fixture.admin, valid.toString(), revision,
                fixture.admin.csrfToken(), fixture.origin.toString()), HttpResponse.BodyHandlers.ofString());
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(201, 409), results.stream().map(HttpResponse::statusCode).sorted().toList());
            assertEquals(1, fixture.preference.getKeys().size());
            assertEquals("0123456789", fixture.resolver.resolve(new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25,
                170, 65535, null, 0, null)).orElseThrow().getKeyHex());
            var stale = fixture.mutate("POST", "", valid, initial);
            assertEquals(409, stale.statusCode());
            assertEquals(1, JSON.readTree(stale.body()).path("keys").size());
            assertNotEquals(revision, stale.headers().firstValue("ETag").orElseThrow());
        }
    }

    @Test
    void encodedOpaqueIdentifiersAndFailedSavesPreserveExistingKeysAndSafeResponses() throws Exception
    {
        try(Fixture fixture = new Fixture(directory))
        {
            VoiceEncryptionKey existing = new VoiceEncryptionKey("key/with + spaces");
            existing.setProtocol(VoiceEncryptionProtocol.NXDN);
            existing.setAlgorithmId(1);
            existing.setKeyId(1);
            existing.setKeyHex("1234");
            fixture.preference.addKey(existing);
            var initial = fixture.get();
            ObjectNode edit = key("NXDN", 1, 1, "ABCD");
            String suffix = "/" + URLEncoder.encode(existing.getId(), StandardCharsets.UTF_8).replace("+", "%20");
            fixture.failWrites.set(true);
            var failed = fixture.mutate("PUT", suffix, edit, initial);
            assertEquals(500, failed.statusCode());
            assertFalse(failed.body().contains("ABCD"));
            assertFalse(failed.body().contains("private failure details"));
            assertEquals(initial.body(), fixture.get().body());
            assertEquals("1234", fixture.resolver.resolve(new VoiceEncryptionContext(VoiceEncryptionProtocol.NXDN,
                1, 1, null, 0, null)).orElseThrow().getKeyHex());
            assertEquals("1234", new EncryptionKeyPreference(ignored -> {}, new ApplicationSettingsStore(fixture.database))
                .getKeys().getFirst().getKeyHex());
            fixture.failWrites.set(false);
            var updated = fixture.mutate("PUT", suffix, edit, initial);
            assertEquals(200, updated.statusCode());
            assertEquals(existing.getId(), JSON.readTree(updated.body()).path("keys").get(0).path("id").textValue());
            assertEquals(200, fixture.mutate("DELETE", suffix, null, updated).statusCode());
            assertFalse(fixture.preference.hasKeys());
        }
    }

    private static ObjectNode key(String protocol, int algorithm, int keyId, String hex)
    {
        ObjectNode value = JSON.createObjectNode();
        value.put("label", "Test key");
        value.put("enabled", true);
        value.put("protocol", protocol);
        value.put("algorithm_id", algorithm);
        value.put("key_id", keyId);
        value.put("key_hex", hex);
        value.putNull("scope");
        return value;
    }

    private static String cookie(WebAccessSession session)
    {
        return WebRequestSecurity.SESSION_COOKIE_NAME + "=" + session.sessionId();
    }

    private static final class Fixture implements AutoCloseable
    {
        final Path database;
        final AtomicBoolean failWrites = new AtomicBoolean();
        final EncryptionKeyPreference preference;
        final VoiceEncryptionKeyResolver resolver;
        final WebAuthenticationService authentication;
        final WebAccessSession admin;
        final WebAccessSession user;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer server;
        final URI origin;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        Fixture(Path directory) throws Exception
        {
            database = directory.resolve("sdrtrunk.sqlite");
            SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            preference = new EncryptionKeyPreference(ignored -> {}, new ApplicationSettingsStore(database)
            {
                @Override public void save(String name, Object value) throws IOException, SQLException
                {
                    if(failWrites.get()) throw new SQLException("private failure details ABCD");
                    super.save(name, value);
                }
            });
            resolver = new VoiceEncryptionKeyResolver(preference);
            WebAccessService access = new WebAccessService(database);
            access.provisionOrResetPrimaryAdmin("test administrator password".toCharArray());
            access.createUser("listener", "test listener password".toCharArray());
            authentication = new WebAuthenticationService(access);
            admin = authentication.login("admin", "test administrator password".toCharArray(), "admin")
                .get(10, TimeUnit.SECONDS).session().orElseThrow();
            user = authentication.login("listener", "test listener password".toCharArray(), "user")
                .get(10, TimeUnit.SECONDS).session().orElseThrow();
            WebRequestSecurity security = new WebRequestSecurity(access, authentication);
            EncryptionKeysHttpController controller = new EncryptionKeysHttpController(preference,
                new VoiceDecryptionModuleManager());
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext(EncryptionKeysHttpController.PATH,
                security.protectApi(WebCapability.ADMIN_SETTINGS, controller::handle));
            server.start();
            origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        HttpResponse<String> get() throws Exception { return send("GET", "", admin, null, null, null); }

        HttpResponse<String> mutate(String method, String suffix, JsonNode body, HttpResponse<String> previous)
            throws Exception
        {
            return send(method, suffix, admin, body == null ? null : body.toString(),
                previous.headers().firstValue("ETag").orElseThrow(), admin.csrfToken());
        }

        HttpResponse<String> send(String method, String suffix, WebAccessSession session, String body,
                                  String revision, String csrf) throws Exception
        {
            return client.send(request(method, suffix, session, body, revision, csrf, origin.toString()),
                HttpResponse.BodyHandlers.ofString());
        }

        HttpRequest request(String method, String suffix, WebAccessSession session, String body,
                            String revision, String csrf, String requestOrigin)
        {
            var request = HttpRequest.newBuilder(origin.resolve(EncryptionKeysHttpController.PATH + suffix))
                .timeout(Duration.ofSeconds(10)).header("Origin", requestOrigin);
            if(session != null) request.header("Cookie", cookie(session));
            if(csrf != null) request.header("X-CSRF-Token", csrf);
            if(revision != null) request.header("If-Match", revision);
            if(body != null) request.header("Content-Type", "application/json");
            return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(body)).build();
        }

        @Override public void close()
        {
            server.stop(0);
            executor.close();
            authentication.close();
        }
    }
}
