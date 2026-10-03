/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.stats.SpectrumSearchService;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Invalid input must fail before tuner ownership, probing, or configuration mutation. */
class SpectrumSearchHttpControllerTest
{
    private static final String PATH = SpectrumSearchHttpController.PATH;
    private static final String JOB = PATH + "/00000000-0000-0000-0000-000000000001";
    private static final String OPEN = "{\"tuner_id\":\"receiver\",\"browse_lease_id\":\"lease\",\"ranges\":[{\"minimum_hz\":769000000,\"maximum_hz\":775000000}]}";

    @Test
    void methodsBodiesQueriesAndForeignRoutesFailBeforeBackendAccess() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            assertEquals(405, fixture.send(PATH, "GET", null).statusCode());
            assertEquals(405, fixture.send(PATH + "/catalog", "POST", null).statusCode());
            assertEquals(400, fixture.send(PATH + "/catalog", "GET", "{}").statusCode());
            assertEquals(400, fixture.send(JOB, "DELETE", "{}").statusCode());
            assertEquals(400, fixture.send(JOB + "?unknown=1", "GET", null).statusCode());
            assertEquals(405, fixture.send(JOB + "/save", "GET", null).statusCode());
            assertEquals(405, fixture.send(JOB + "/start", "GET", null).statusCode());
            assertEquals(404, fixture.send(PATH + "extra", "GET", null).statusCode());
            assertEquals(404, fixture.send(JOB + "/other", "POST", null).statusCode());
        }
    }

    @Test
    void rangeAndBodyBoundsRejectAmbiguousOrUnboundedRequests() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            String[] invalid = {
                "{}", "[]", OPEN + " {}", OPEN.replace("\"receiver\"", "null"),
                OPEN.replace("769000000", "769000000.0"), OPEN.replace("769000000", "0"),
                OPEN.replace("769000000", "9007199254740992"), OPEN.replace("775000000", "769000000"),
                OPEN.replace("775000000", "1000000000"), OPEN.replace("775000000", "7000000000"),
                OPEN.replace("\"maximum_hz\":775000000", "\"maximum_hz\":775000000,\"modulation\":\"CQPSK\""),
                OPEN.replace("\"ranges\":[", "\"ranges\":{\"nested\":"),
                OPEN.replace("769000000", "true"), OPEN.replace("\"receiver\"", "\"" + "x".repeat(81) + "\""),
                OPEN.replace("\"tuner_id\":\"receiver\"", "\"tuner_id\":\"receiver\",\"tuner_id\":\"other\""),
                OPEN.substring(0, OPEN.length()-1) + ",\"dwell_ms\":749}",
                OPEN.substring(0, OPEN.length()-1) + ",\"dwell_ms\":5001}",
                OPEN.substring(0, OPEN.length()-1) + ",\"protocol\":\"p25\"}"
            };
            for(String body: invalid) assertEquals(400, fixture.send(PATH, "POST", body).statusCode(), body);
            assertEquals(415, fixture.send(PATH, "POST", OPEN, "text/plain").statusCode());
            String range = "{\"minimum_hz\":769000000,\"maximum_hz\":770000000}";
            assertEquals(400, fixture.send(PATH, "POST", "{\"ranges\":[" + String.join(",", java.util.Collections.nCopies(9, range)) + "]}").statusCode());
            // An oversized upload can close its connection; do not reuse that connection for another POST.
            assertEquals(413, fixture.send(PATH, "POST", "{\"tuner_id\":\"" + "x".repeat(100000) + "\"}").statusCode());
        }
    }

    @Test
    void saveAcceptsOnlyBoundedEditsAndExplicitStartupFlags() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            String valid = "{\"revision\":1,\"candidates\":[{\"candidate_id\":\"candidate\",\"name\":\"Site\",\"auto_start\":false}]," +
                "\"alias_groups\":[{\"group_id\":\"group\",\"alias_list_id\":0,\"new_alias_list_name\":\"Test network\"}]}";
            String[] invalid = {
                valid.replace("\"revision\":1", "\"revision\":1.0"),
                valid.replace("\"revision\":1", "\"revision\":-1"),
                valid.replace("\"auto_start\":false", "\"auto_start\":\"false\""),
                valid.replace(",\"auto_start\":false", ""),
                valid.replace("\"candidate_id\":\"candidate\"", "\"candidate_id\":null"),
                valid.replace("\"candidate_id\":\"candidate\"", "\"candidate_id\":\"candidate\",\"identity\":{\"wacn\":1}"),
                valid.replace("\"alias_list_id\":0", "\"alias_list_id\":-1"),
                valid.replace("\"Test network\"", "\"" + "x".repeat(26) + "\""),
                valid.replace("\"Site\"", "\"" + "x".repeat(257) + "\""),
                valid.replace("\"group_id\":\"group\"", "\"group_id\":\"group\",\"unknown\":true")
            };
            for(String body: invalid) assertEquals(400, fixture.send(JOB + "/save", "POST", body).statusCode(), body);
            // A valid edit reaches the intentional null backend sentinel, which returns the generic availability error.
            assertEquals(503, fixture.send(JOB + "/save", "POST", valid).statusCode());
        }
    }

    @Test
    void optionalRadioReferenceScopeIsStrictAndCannotSupplyDirectoryEvidence() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            for(String scope: new String[]{"0", "-1", "1.5", "\"39\"", "true", "2147483648", "{}"})
                assertEquals(400, fixture.send(PATH, "POST", OPEN.substring(0, OPEN.length()-1) +
                    ",\"radioreference_state_id\":" + scope + "}").statusCode());
            for(String scope: new String[]{"null", "39"})
                assertEquals(503, fixture.send(PATH, "POST", OPEN.substring(0, OPEN.length()-1) +
                    ",\"radioreference_state_id\":" + scope + "}").statusCode());
            assertEquals(400, fixture.send(PATH, "POST", OPEN.substring(0, OPEN.length()-1) +
                ",\"radio_reference\":{\"state\":\"matched\"}}").statusCode());
        }
    }

    @Test
    void startRejectsMissingUnboundedOrNonTextResultIds() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            String[] invalid = {"{}", "{\"candidate_ids\":[]}", "{\"candidate_ids\":[null]}",
                "{\"candidate_ids\":[1]}", "{\"candidate_ids\":[\"candidate\"],\"first_candidate_id\":true}",
                "{\"candidate_ids\":[\"candidate\"],\"frequency_hz\":770000000}",
                "{\"candidate_ids\":[" + String.join(",", java.util.Collections.nCopies(33, "\"candidate\"")) + "]}"};
            for(String body: invalid) assertEquals(400, fixture.send(JOB + "/start", "POST", body).statusCode(), body);
        }
    }

    @Test
    void backendFailuresRemainGenericAndHaveSecurityHeaders() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            var response = fixture.send(PATH, "POST", OPEN);
            assertEquals(503, response.statusCode());
            assertEquals("search_unavailable", new ObjectMapper().readTree(response.body()).at("/error/code").textValue());
            assertFalse(response.body().contains("NullPointerException"));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        }
    }

    @Test
    void expiredOrForeignJobHasATerminalExpiryResponse() throws Exception
    {
        // No hardware dependency is touched when a request names a job that this service does not own.
        try(SpectrumSearchService service = new SpectrumSearchService(null, null, null, null, null);
            Fixture fixture = new Fixture(service))
        {
            var response = fixture.send(JOB, "GET", null);
            assertEquals(410, response.statusCode());
            assertEquals("search_expired", new ObjectMapper().readTree(response.body()).at("/error/code").textValue());
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final HttpServer server;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        final URI base;
        Fixture() throws Exception { this(null); }
        Fixture(SpectrumSearchService service) throws Exception
        {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(PATH, new SpectrumSearchHttpController(service)::handle);
            server.start();
            base = URI.create("http://localhost:" + server.getAddress().getPort());
        }
        HttpResponse<String> send(String path, String method, String body) throws Exception
        { return send(path, method, body, "application/json"); }
        HttpResponse<String> send(String path, String method, String body, String contentType) throws Exception
        {
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3));
            if(body != null) request.header("Content-Type", contentType);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        public void close() { server.stop(0); client.close(); }
    }
}
