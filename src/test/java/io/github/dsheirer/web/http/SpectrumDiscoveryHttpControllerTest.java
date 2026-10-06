/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Invalid requests must be rejected before acquiring a tuner, starting a probe, or mutating configuration. */
class SpectrumDiscoveryHttpControllerTest
{
    private static final String PATH = SpectrumDiscoveryHttpController.PATH;
    private static final String SESSION = PATH + "/00000000-0000-0000-0000-000000000001";
    private static final String OPEN = "{\"tuner_id\":\"target\",\"frequency_hz\":770500000,\"protocol_id\":\"p25-phase1\"}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rejectsWrongMethodsBodiesAndForeignPathsBeforeBackendAccess() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            HttpResponse<String> root = fixture.send(PATH, "GET", null);
            assertEquals(405, root.statusCode());
            assertEquals("POST", root.headers().firstValue("Allow").orElseThrow());
            HttpResponse<String> eligibility = fixture.send(PATH + "/eligibility", "POST", null);
            assertEquals(405, eligibility.statusCode());
            assertEquals("GET", eligibility.headers().firstValue("Allow").orElseThrow());
            assertEquals(400, fixture.send(SESSION, "DELETE", "{}").statusCode());
            assertEquals(400, fixture.send(SESSION + "/start", "POST", "{}").statusCode());
            assertEquals(405, fixture.send(SESSION + "/start", "GET", null).statusCode());
            assertEquals(404, fixture.send(SESSION + "/unrecognized", "POST", null).statusCode());
            assertEquals(404, fixture.send(PATH + "extra", "GET", null).statusCode());
        }
    }

    @Test
    void rejectsDuplicateUnknownMissingAndNonIntegralOpenFields() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            String[] bodies = {
                "{}", "[]", OPEN + " {}", OPEN.replace("\"target\"", "null"),
                OPEN.replace("770500000", "770500000.0"), OPEN.replace("770500000", "-1"),
                OPEN.replace("770500000", "9007199254740992"),
                OPEN.replace("\"protocol_id\":\"p25-phase1\"", "\"protocol_id\":true"),
                OPEN.replace("}", ",\"protocol_id\":\"nbfm\"}"),
                OPEN.replace("}", ",\"unknown\":true}")
            };
            for(String body: bodies)
                assertEquals(400, fixture.send(PATH, "POST", body).statusCode(), body);
            assertEquals(415, fixture.send(PATH, "POST", OPEN, "text/plain").statusCode());
            assertEquals(413, fixture.send(PATH, "POST", "{\"tuner_id\":\"" + "x".repeat(100000) + "\"}")
                .statusCode());
        }
    }

    @Test
    void aliasImportAcceptsOnlyAPositiveListIdAndNeverBrowserProvidedSystemIdentity() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            String path = SESSION + "/aliases/import";
            assertEquals(405, fixture.send(path, "GET", null).statusCode());
            for(String body: new String[]{"{}", "[]", "{\"alias_list_id\":0}", "{\"alias_list_id\":-1}",
                "{\"alias_list_id\":1.5}", "{\"alias_list_id\":true}", "{\"alias_list_id\":9007199254740992}",
                "{\"alias_list_id\":1,\"system_id\":10}", "{\"alias_list_id\":1,\"alias_list_id\":2}"})
                assertEquals(400, fixture.send(path, "POST", body).statusCode(), body);
            assertEquals(400, fixture.send(path + "?system_id=10", "POST", "{\"alias_list_id\":1}").statusCode());
            assertEquals(503, fixture.send(path, "POST", "{\"alias_list_id\":1}").statusCode());
        }
    }

    @Test
    void rejectsAmbiguousEligibilityQueriesAndSessionQueryParameters() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            String[] queries = {
                "", "?tuner_id=target", "?frequency_hz=770500000", "?tuner_id=target&frequency_hz=0",
                "?tuner_id=target&frequency_hz=770500000.0",
                "?tuner_id=target&frequency_hz=9007199254740992",
                "?tuner_id=target&tuner_id=other&frequency_hz=770500000",
                "?tuner_id=target&frequency_hz=770500000&unknown=1",
                "?tuner_id=target&frequency_hz=770500000&"
            };
            for(String query: queries)
                assertEquals(400, fixture.send(PATH + "/eligibility" + query, "GET", null).statusCode(), query);
            assertEquals(400, fixture.send(SESSION + "?x=1", "GET", null).statusCode());
            assertEquals(400, fixture.send(PATH + "?x=1", "POST", OPEN).statusCode());
        }
    }

    @Test
    void optionalRadioReferenceScopeIsStrictForEveryDiscoveryProtocol() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            for(String scope: new String[]{"0", "-1", "1.5", "\"39\"", "true", "2147483648", "[]"})
                assertEquals(400, fixture.send(PATH, "POST", OPEN.substring(0, OPEN.length()-1) +
                    ",\"radioreference_state_id\":" + scope + "}").statusCode());
            for(String protocol: new String[]{"p25-phase1", "dmr", "nxdn", "am", "nbfm"})
                for(String scope: new String[]{"null", "39"})
                    assertEquals(503, fixture.send(PATH, "POST", OPEN.replace("p25-phase1", protocol)
                        .replace("}", ",\"radioreference_state_id\":" + scope + "}")).statusCode());
            assertEquals(400, fixture.send(PATH, "POST", OPEN.substring(0, OPEN.length()-1) +
                ",\"trunked_evidence\":{\"verified\":true}}").statusCode());
        }
    }

    @Test
    void rejectsMalformedSaveSettingsAndRevisionBeforeMutation() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            String valid = "{\"name\":\"Test site\",\"alias_list_id\":0,\"new_alias_list_name\":\"Test list\"," +
                "\"revision\":1,\"settings\":{\"modulation\":\"C4FM\"}}";
            String[] bodies = {
                valid.replace("\"alias_list_id\":0", "\"alias_list_id\":-1"),
                valid.replace("\"revision\":1", "\"revision\":1.0"),
                valid.replace("\"revision\":1", "\"revision\":9007199254740992"),
                valid.replace("\"C4FM\"", "[\"C4FM\"]"),
                valid.replace("\"C4FM\"", "{\"nested\":true}"),
                valid.replace("\"settings\":{\"modulation\":\"C4FM\"}", "\"settings\":[]"),
                valid.replace("\"Test list\"", "\"" + "x".repeat(129) + "\""),
                valid.replace("\"Test site\"", "\"\""),
                valid.replace("}", ",\"unknown\":true}")
            };
            for(String body: bodies)
                assertEquals(400, fixture.send(SESSION + "/save", "POST", body).statusCode(), body);
            assertEquals(503, fixture.send(SESSION + "/save", "POST",
                valid.replace("\"Test list\"", "\"" + "x".repeat(128) + "\"")).statusCode());
        }
    }

    @Test
    void backendFailuresUseGenericErrorsAndSecurityHeaders() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            HttpResponse<String> response = fixture.send(PATH, "POST", OPEN);
            assertEquals(503, response.statusCode());
            assertEquals("discovery_unavailable", MAPPER.readTree(response.body()).at("/error/code").textValue());
            assertFalse(response.body().contains("NullPointerException"));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        private final HttpServer server;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        private final URI base;

        Fixture() throws Exception
        {
            //A null service is an intentional sentinel: valid input reaches it and returns a generic 503;
            //invalid input must receive its validation response before any backend access.
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(PATH, new SpectrumDiscoveryHttpController(null)::handle);
            server.start();
            base = URI.create("http://localhost:" + server.getAddress().getPort());
        }

        HttpResponse<String> send(String path, String method, String body) throws Exception
        {
            return send(path, method, body, "application/json");
        }

        HttpResponse<String> send(String path, String method, String body, String contentType) throws Exception
        {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3));
            if(body != null) request.header("Content-Type", contentType);
            request.method(method, body != null ? HttpRequest.BodyPublishers.ofString(body) :
                HttpRequest.BodyPublishers.noBody());
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        public void close() { server.stop(0); client.close(); }
    }
}
