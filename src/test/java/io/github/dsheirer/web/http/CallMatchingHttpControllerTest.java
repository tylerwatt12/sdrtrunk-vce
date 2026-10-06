/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDecisionOutcome;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticCallIdentity;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticConfiguration;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticDecision;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticEvidence;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticLeg;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticOutputPolicy;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticService;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticWinner;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallMergeProof;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallSeparationReason;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallWinnerCriterion;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CallMatchingHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void cursorPollingSendsOnlyNewSummariesAndDetailsRemainAvailableOnDemand() throws Exception
    {
        LogicalCallDiagnosticService service = diagnosticService();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(null, null, null, null, service);
        try
        {
            assertTrue(service.offer(sensitiveDecision(1)));
            CallMatchingHttpController controller = new CallMatchingHttpController(() -> service, () -> coordinator);
            TestExchange initial = new TestExchange(CallMatchingHttpController.PATH + "?summary=true", "GET");
            controller.handle(initial);
            JsonNode first = MAPPER.readTree(initial.body()).path("data");
            assertEquals(1, first.path("cursor").longValue());
            assertFalse(first.path("incremental").booleanValue());
            assertEquals(32, first.at("/duplicates/0/copy_count").intValue());
            assertEquals(1, first.at("/duplicates/0/legs").size());
            assertFalse(first.at("/duplicates/0/details_available").booleanValue());
            assertFalse(first.at("/duplicates/0/legs/0").has("expected_frame_count"));
            String session = first.path("session_id").textValue();
            String cursorQuery = "?summary=true&after=1&session_id=" + session;
            TestExchange unchanged = new TestExchange(CallMatchingHttpController.PATH + cursorQuery, "GET");
            controller.handle(unchanged);
            JsonNode same = MAPPER.readTree(unchanged.body()).path("data");
            assertTrue(same.path("incremental").booleanValue());
            assertEquals(0, same.path("duplicates").size());
            assertEquals(1, same.at("/history/visible_count").intValue());
            assertTrue(same.has("resolver"));
            assertTrue(service.offer(sensitiveDecision(2)));
            TestExchange appended = new TestExchange(CallMatchingHttpController.PATH + cursorQuery, "GET");
            controller.handle(appended);
            JsonNode delta = MAPPER.readTree(appended.body()).path("data");
            assertTrue(delta.path("incremental").booleanValue());
            assertEquals(1, delta.path("duplicates").size());
            assertEquals(2, delta.at("/duplicates/0/decision_sequence").longValue());
            TestExchange detail = new TestExchange(CallMatchingHttpController.PATH +
                "?decision_sequence=1&session_id=" + session, "GET");
            controller.handle(detail);
            assertEquals(200, detail.getResponseCode());
            JsonNode selected = MAPPER.readTree(detail.body()).path("data");
            assertEquals(32, selected.path("legs").size());
            assertTrue(selected.has("evidence"));
            assertFalse(detail.body().contains("topsecret"));
            TestExchange full = new TestExchange(CallMatchingHttpController.PATH, "GET");
            controller.handle(full);
            assertTrue(full.body().length() > initial.body().length() * 8,
                "Summary polling must not transfer all copy metrics.");
            TestExchange replacement = new TestExchange(CallMatchingHttpController.PATH +
                "?summary=true&after=1&session_id=00000000-0000-0000-0000-000000000000", "GET");
            controller.handle(replacement);
            assertFalse(MAPPER.readTree(replacement.body()).at("/data/incremental").booleanValue());
            TestExchange expired = new TestExchange(CallMatchingHttpController.PATH +
                "?decision_sequence=999&session_id=" + session, "GET");
            controller.handle(expired);
            assertEquals(404, expired.getResponseCode());
        }
        finally
        {
            coordinator.disposeAndAwait(2, TimeUnit.SECONDS);
            service.close();
        }
    }

    @Test
    void returnsNewestConfirmedDuplicatesOnlyWithBoundedCopiesAndSafeLabels() throws Exception
    {
        LogicalCallDiagnosticService service = diagnosticService();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(null, null, null, null, service);

        try
        {
            for(int sequence = 1; sequence <= 300; sequence++)
            {
                assertTrue(service.offer(decision(sequence,
                    sequence % 2 == 0 ? LogicalCallDecisionOutcome.MERGED :
                        LogicalCallDecisionOutcome.INDEPENDENT)));
            }
            assertTrue(service.offer(sensitiveDecision(301)));

            TestExchange exchange = new TestExchange(CallMatchingHttpController.PATH, "GET");
            new CallMatchingHttpController(() -> service, () -> coordinator).handle(exchange);
            assertEquals(200, exchange.getResponseCode());
            assertEquals("no-store", exchange.getResponseHeaders().getFirst("Cache-Control"));
            JsonNode data = MAPPER.readTree(exchange.body()).path("data");
            assertTrue(data.path("available").booleanValue());
            assertEquals(0, data.at("/history/duplicates_evicted").longValue());
            assertEquals(151, data.at("/history/duplicates_retained").intValue());
            assertEquals(100, data.at("/history/limit").intValue());
            assertEquals(100, data.at("/history/visible_count").intValue());
            assertEquals(256, data.at("/history/retention_capacity").intValue());
            assertEquals(151, data.at("/diagnostic_status/decisions_observed").longValue());
            assertTrue(data.at("/queue/total_ingress_capacity").intValue() > 0);
            assertNotNull(data.at("/resolver/health_state").textValue());
            assertFalse(data.path("diagnostic_status").has("file_health_state"));
            assertFalse(data.path("diagnostic_status").has("queued_records"));
            assertFalse(data.path("diagnostic_status").has("output_confirmations_observed"));

            JsonNode duplicates = data.path("duplicates");
            assertEquals(100, duplicates.size());
            assertEquals(301, duplicates.get(0).path("decision_sequence").longValue());
            assertEquals(300, duplicates.get(1).path("decision_sequence").longValue());
            assertEquals(104, duplicates.get(99).path("decision_sequence").longValue());
            duplicates.forEach(item -> assertEquals("MERGED", item.path("outcome").textValue()));

            JsonNode selected = duplicates.get(0);
            assertEquals(CallMatchingHttpController.MAXIMUM_VISIBLE_COPIES, selected.path("legs").size());
            assertEquals(1, selected.at("/winner/selected_copy_index").intValue());
            assertEquals(2, selected.at("/winner/runner_up_copy_index").intValue());
            assertTrue(selected.at("/legs/0/selected").booleanValue());
            assertEquals(1, selected.at("/legs/0/copy_index").intValue());
            assertEquals(2, selected.at("/legs/1/copy_index").intValue());
            assertEquals(500, selected.at("/legs/1/overlap/overlap_milliseconds").longValue());
            assertEquals("No measurable difference", selected.at("/winner/winner_value/display").textValue());
            assertEquals("[path hidden]", selected.at("/legs/0/channel_name").textValue());
            assertEquals("[path hidden]", selected.at("/legs/1/channel_name").textValue());
            assertEquals("[path hidden]", selected.at("/call_identity/destination_alias").textValue());
            assertEquals("[path hidden]", selected.at("/call_identity/alias_list_name").textValue());
            assertEquals("[endpoint hidden]", selected.at("/call_identity/destination_value").textValue());
            assertEquals("[secret hidden]", selected.at("/call_identity/source_alias").textValue());
            assertEquals("[secret hidden]", selected.at("/call_identity/source_value").textValue());
            assertEquals(1, selected.path("decision_reasons").size());
            assertEquals(1, selected.at("/evidence/confirmed_duplicate_pair_count").longValue());
            assertEquals(1, selected.at("/evidence/merge_proof_counts/shared_voice_content").longValue());
            assertEquals(1, selected.at("/output_policy/stream_routing_key_count").intValue());
            assertFalse(selected.has("logical_call_id"));
            assertFalse(selected.at("/legs/0").has("leg_id"));
            assertFalse(selected.at("/legs/0").has("channel_configuration_id"));
            assertFalse(selected.at("/legs/0").has("radio_resolve_id"));
            assertFalse(selected.at("/output_policy").has("stream_routing_keys"));
            assertNoPathField(data);
            assertFalse(exchange.body().contains("/Users/owner"));
            assertFalse(exchange.body().contains("/tmp/file"));
            assertFalse(exchange.body().contains("C:\\\\Users"));
            assertFalse(exchange.body().contains("topsecret"));
            assertFalse(exchange.body().contains("192.168.1.1"));
            assertFalse(exchange.body().contains("physical-leg"));

            service.close();
            TestExchange closedHistory = new TestExchange(CallMatchingHttpController.PATH, "GET");
            new CallMatchingHttpController(() -> service, () -> coordinator).handle(closedHistory);
            JsonNode closedData = MAPPER.readTree(closedHistory.body()).path("data");
            assertFalse(closedData.at("/diagnostic_status/accepting").booleanValue());
            assertEquals("WARNING", closedData.at("/resolver/health_state").textValue());
        }
        finally
        {
            coordinator.disposeAndAwait(2, TimeUnit.SECONDS);
            service.close();
        }
    }

    @Test
    void copyDetailsDistinguishSharedConfigurationsWithoutExposingTheirIdentifiers() throws Exception
    {
        String sharedConfiguration = "970eecf2-652b-4968-9172-d8c9a61a9d07";
        String otherConfiguration = "642adcb4-5c0a-4df3-adc7-4a6de5805a80";
        List<LogicalCallDiagnosticLeg> legs = List.of(
            leg(0, "Same visible name", false, sharedConfiguration, 851_012_500L, 2),
            leg(1, "Same visible name", true, sharedConfiguration, 851_012_500L, 2),
            leg(2, "Same visible name", false, otherConfiguration, 0L, 0));
        LogicalCallDiagnosticWinner winner = new LogicalCallDiagnosticWinner("physical-leg-1",
            "physical-leg-0", LogicalCallWinnerCriterion.CALL_LEG_ID,
            new LogicalCallDiagnosticWinner.CriterionValue("physical-leg-1", null, null),
            new LogicalCallDiagnosticWinner.CriterionValue("physical-leg-0", null, null));
        LogicalCallDiagnosticEvidence evidence = new LogicalCallDiagnosticEvidence(3, 0, 0,
            Map.of(LogicalCallMergeProof.SHARED_VOICE_CONTENT, 3L), Map.of());
        LogicalCallDiagnosticDecision decision = new LogicalCallDiagnosticDecision(1, 2_200,
            new LogicalCallId(77, 1), LogicalCallDecisionOutcome.MERGED, null, null, winner, legs,
            evidence, List.of());
        LogicalCallDiagnosticService service = diagnosticService();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(null, null, null, null, service);

        try
        {
            assertTrue(service.offer(decision));
            TestExchange exchange = new TestExchange(CallMatchingHttpController.PATH, "GET");
            new CallMatchingHttpController(() -> service, () -> coordinator).handle(exchange);
            JsonNode duplicate = MAPPER.readTree(exchange.body()).at("/data/duplicates/0");
            assertEquals(3, duplicate.path("legs").size());
            assertEquals(3, duplicate.at("/evidence/confirmed_duplicate_pair_count").longValue());
            assertEquals(1, duplicate.at("/winner/selected_copy_index").intValue());
            assertEquals(2, duplicate.at("/winner/runner_up_copy_index").intValue());
            assertEquals(1, duplicate.at("/legs/0/configuration_ref").intValue());
            assertEquals(1, duplicate.at("/legs/1/configuration_ref").intValue());
            assertEquals(2, duplicate.at("/legs/2/configuration_ref").intValue());
            assertEquals(851_012_500L, duplicate.at("/legs/0/frequency_hz").longValue());
            assertEquals(2, duplicate.at("/legs/0/timeslot").intValue());
            assertTrue(duplicate.at("/legs/2/frequency_hz").isNull());
            assertTrue(duplicate.at("/legs/2/timeslot").isNull());
            assertFalse(exchange.body().contains(sharedConfiguration));
            assertFalse(exchange.body().contains(otherConfiguration));
            assertFalse(exchange.body().contains("physical-leg"));
            duplicate.path("legs").forEach(copy -> assertFalse(copy.has("channel_configuration_id")));
        }
        finally
        {
            coordinator.disposeAndAwait(2, TimeUnit.SECONDS);
            service.close();
        }
    }

    @Test
    void reportsRetainedHistoryAndDisplayedHistoryAsSeparateScopes() throws Exception
    {
        LogicalCallDiagnosticService service =
            new LogicalCallDiagnosticService(new LogicalCallDiagnosticConfiguration(128));
        AudioCallCoordinator coordinator = new AudioCallCoordinator(null, null, null, null, service);

        try
        {
            for(int sequence = 1; sequence <= 200; sequence++)
            {
                assertTrue(service.offer(decision(sequence, LogicalCallDecisionOutcome.MERGED)));
            }

            TestExchange exchange = new TestExchange(CallMatchingHttpController.PATH, "GET");
            new CallMatchingHttpController(() -> service, () -> coordinator).handle(exchange);
            JsonNode data = MAPPER.readTree(exchange.body()).path("data");
            assertEquals(128, data.at("/history/retention_capacity").intValue());
            assertEquals(128, data.at("/history/duplicates_retained").intValue());
            assertEquals(72, data.at("/history/duplicates_evicted").longValue());
            assertEquals(100, data.at("/history/limit").intValue());
            assertEquals(100, data.at("/history/visible_count").intValue());
            assertEquals(data.path("duplicates").size(), data.at("/history/visible_count").intValue());
            assertEquals(200, data.at("/duplicates/0/decision_sequence").longValue());
            assertEquals(101, data.at("/duplicates/99/decision_sequence").longValue());
        }
        finally
        {
            coordinator.disposeAndAwait(2, TimeUnit.SECONDS);
            service.close();
        }
    }

    @Test
    void rejectsUnsupportedRequestsAndDetachedSources() throws Exception
    {
        CallMatchingHttpController controller = new CallMatchingHttpController(() -> null, () -> null);
        TestExchange unavailable = new TestExchange(CallMatchingHttpController.PATH, "GET");
        controller.handle(unavailable);
        assertEquals(503, unavailable.getResponseCode());
        assertEquals("call_matching_unavailable",
            MAPPER.readTree(unavailable.body()).at("/error/code").textValue());

        TestExchange query = new TestExchange(CallMatchingHttpController.PATH + "?all=true", "GET");
        controller.handle(query);
        assertEquals(400, query.getResponseCode());
        for(String invalid: List.of("summary=false", "after=1", "summary=true&after=-1&session_id=x",
            "summary=true&summary=true", "decision_sequence=1", "summary=true&after=9007199254740992"))
        {
            TestExchange rejected = new TestExchange(CallMatchingHttpController.PATH + "?" + invalid, "GET");
            controller.handle(rejected);
            assertEquals(400, rejected.getResponseCode(), invalid);
        }

        TestExchange method = new TestExchange(CallMatchingHttpController.PATH, "POST");
        controller.handle(method);
        assertEquals(405, method.getResponseCode());
        assertEquals("GET", method.getResponseHeaders().getFirst("Allow"));

        TestExchange body = new TestExchange(CallMatchingHttpController.PATH, "GET");
        body.getRequestHeaders().set("Content-Length", "1");
        controller.handle(body);
        assertEquals(400, body.getResponseCode());

        TestExchange suffix = new TestExchange(CallMatchingHttpController.PATH + "/all", "GET");
        controller.handle(suffix);
        assertEquals(404, suffix.getResponseCode());
    }

    @Test
    void blockedBrowserWriterDoesNotBlockDiagnosticOffersOrCoordinatorSnapshotReads() throws Exception
    {
        LogicalCallDiagnosticService service = diagnosticService();
        AudioCallCoordinator coordinator = new AudioCallCoordinator(null, null, null, null, service);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        BlockingOutputStream output = new BlockingOutputStream();

        try
        {
            assertTrue(service.offer(decision(1, LogicalCallDecisionOutcome.MERGED)));
            TestExchange exchange = new TestExchange(CallMatchingHttpController.PATH, "GET", output);
            CallMatchingHttpController controller = new CallMatchingHttpController(() -> service, () -> coordinator);
            Future<?> response = executor.submit(() -> {
                controller.handle(exchange);
                return null;
            });
            assertTrue(output.mWriting.await(5, TimeUnit.SECONDS), "HTTP writer never reached its blocked output");

            Future<?> producer = executor.submit(() -> {
                for(int sequence = 2; sequence <= 2_001; sequence++)
                {
                    service.offer(decision(sequence, LogicalCallDecisionOutcome.MERGED));
                }
                assertNotNull(coordinator.getDiagnosticSnapshot());
                assertNotNull(coordinator.getQueueStatus());
                return null;
            });
            producer.get(2, TimeUnit.SECONDS);
            assertFalse(response.isDone(), "The client response must still be blocked");
            output.mRelease.countDown();
            response.get(5, TimeUnit.SECONDS);
            assertEquals(200, exchange.getResponseCode());
        }
        finally
        {
            output.mRelease.countDown();
            executor.shutdownNow();
            coordinator.disposeAndAwait(2, TimeUnit.SECONDS);
            service.close();
        }
    }

    private LogicalCallDiagnosticService diagnosticService()
    {
        return new LogicalCallDiagnosticService(new LogicalCallDiagnosticConfiguration(256));
    }

    private static LogicalCallDiagnosticDecision decision(int sequence, LogicalCallDecisionOutcome outcome)
    {
        return new LogicalCallDiagnosticDecision(sequence, 1_000L + sequence,
            new LogicalCallId(77, sequence), outcome, null, null, null, List.of(),
            LogicalCallDiagnosticEvidence.EMPTY, List.of());
    }

    private static LogicalCallDiagnosticDecision sensitiveDecision(int sequence)
    {
        List<LogicalCallDiagnosticLeg> legs = new ArrayList<>();
        for(int index = 0; index < 40; index++)
        {
            String channel = index == 39 ? "channel=/Users/owner/logs" :
                index == 20 ? "<C:\\Users\\owner>" : "Receiver " + index;
            legs.add(leg(index, channel, index == 39));
        }
        LogicalCallDiagnosticCallIdentity identity = new LogicalCallDiagnosticCallIdentity(
            sequence, "P25", "P25 Phase 2", 1_000, 2_000, 2_100, 100,
            "http://192.168.1.1/private", "foo(/tmp/file)", "https://name:pwd@example.com/",
            "token=very-secret", null, 1, 2,
            91, "note;/Users/owner/logs", 2);
        LogicalCallDiagnosticWinner winner = new LogicalCallDiagnosticWinner("physical-leg-39",
            "physical-leg-20", LogicalCallWinnerCriterion.CHANNEL_CONFIGURATION_ID,
            new LogicalCallDiagnosticWinner.CriterionValue("configuration-id-39", null, null),
            new LogicalCallDiagnosticWinner.CriterionValue("configuration-id-20", null, null));
        LogicalCallDiagnosticEvidence evidence = new LogicalCallDiagnosticEvidence(1, 0, 0,
            Map.of(LogicalCallMergeProof.SHARED_VOICE_CONTENT, 1L), Map.of());
        List<LogicalCallSeparationReason> repeatedReasons = new ArrayList<>();
        for(int index = 0; index < 1_000; index++)
        {
            repeatedReasons.add(LogicalCallSeparationReason.INSUFFICIENT_TIME_OVERLAP);
        }
        return new LogicalCallDiagnosticDecision(sequence, 2_200, new LogicalCallId(77, sequence),
            LogicalCallDecisionOutcome.MERGED, identity,
            new LogicalCallDiagnosticOutputPolicy(true, List.of("api_key=topsecret"), 1, true),
            winner, legs, evidence, repeatedReasons);
    }

    private static LogicalCallDiagnosticLeg leg(int index, String channelName, boolean selected)
    {
        return leg(index, channelName, selected, "configuration-id-" + index, null, null);
    }

    private static LogicalCallDiagnosticLeg leg(int index, String channelName, boolean selected,
                                                String configurationId, Long frequencyHz, Integer timeslot)
    {
        return new LogicalCallDiagnosticLeg("physical-leg-" + index, "P25", configurationId,
            channelName, "radio-resolve-internal-id", 91, 1, 2, 3, 4,
            index == 20 ? 1_500 : 1_000, index == 20 ? 2_500 : 2_000, 1_000,
            50, 50, 48, 47, 1, 1, 1, 2, 100, 96.0, 4.0, 2.0, 2.0,
            8_000, false, false, selected, frequencyHz, timeslot);
    }

    private static void assertNoPathField(JsonNode node)
    {
        if(node.isObject())
        {
            node.fieldNames().forEachRemaining(name -> {
                assertFalse(name.contains("path"), "Unexpected path field: " + name);
                assertFalse(name.contains("directory"), "Unexpected directory field: " + name);
            });
            node.elements().forEachRemaining(CallMatchingHttpControllerTest::assertNoPathField);
        }
        else if(node.isArray())
        {
            node.forEach(CallMatchingHttpControllerTest::assertNoPathField);
        }
    }

    private static class TestExchange extends HttpExchange
    {
        private final Headers mRequestHeaders = new Headers();
        private final Headers mResponseHeaders = new Headers();
        private final Map<String,Object> mAttributes = new HashMap<>();
        private final URI mUri;
        private final String mMethod;
        private final OutputStream mOutput;
        private int mResponseCode = -1;

        private TestExchange(String path, String method)
        {
            this(path, method, new ByteArrayOutputStream());
        }

        private TestExchange(String path, String method, OutputStream output)
        {
            mUri = URI.create(path);
            mMethod = method;
            mOutput = output;
        }

        private String body()
        {
            return mOutput instanceof ByteArrayOutputStream bytes ?
                bytes.toString(StandardCharsets.UTF_8) : "";
        }

        @Override public Headers getRequestHeaders() { return mRequestHeaders; }
        @Override public Headers getResponseHeaders() { return mResponseHeaders; }
        @Override public URI getRequestURI() { return mUri; }
        @Override public String getRequestMethod() { return mMethod; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return mOutput; }
        @Override public void sendResponseHeaders(int code, long length) { mResponseCode = code; }
        @Override public InetSocketAddress getRemoteAddress()
        {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
        }
        @Override public int getResponseCode() { return mResponseCode; }
        @Override public InetSocketAddress getLocalAddress()
        {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080);
        }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return mAttributes.get(name); }
        @Override public void setAttribute(String name, Object value) { mAttributes.put(name, value); }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }

    private static final class BlockingOutputStream extends OutputStream
    {
        private final CountDownLatch mWriting = new CountDownLatch(1);
        private final CountDownLatch mRelease = new CountDownLatch(1);

        @Override
        public void write(int value) throws IOException
        {
            block();
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException
        {
            block();
        }

        private void block() throws IOException
        {
            mWriting.countDown();
            try
            {
                if(!mRelease.await(10, TimeUnit.SECONDS))
                {
                    throw new IOException("Timed out waiting for browser response release");
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Browser response interrupted", exception);
            }
        }
    }
}
