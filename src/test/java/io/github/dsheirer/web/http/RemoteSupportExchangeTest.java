/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RemoteSupportExchangeTest
{
    @Test void injectsPrivateSessionPreservesOriginAndNeverForwardsItsCookie() throws Exception
    {
        HttpServer server = HttpServer.create();
        var context = server.createContext("/", exchange -> {});
        List<Map<String,?>> messages = new CopyOnWriteArrayList<>();
        RemoteSupportExchange exchange = new RemoteSupportExchange("1", "POST", URI.create("/api/test"),
            Map.of("Cookie", List.of("sdrtrunk_web_session=attacker"), "Host", List.of("attacker.invalid"),
                "Origin", List.of("https://vce-example.radioresolve.com")), "vce-example.radioresolve.com",
            "private-session", context, messages::add, () -> true);
        assertEquals("sdrtrunk_web_session=private-session", exchange.getRequestHeaders().getFirst("Cookie"));
        assertTrue(WebRequestSecurity.hasSameOrigin(exchange));
        exchange.getResponseHeaders().add("Set-Cookie", "sdrtrunk_web_session=private-session; Secure");
        String marker = WebRequestSecurity.ALIAS_EXPORT_READY_COOKIE_PREFIX + "a".repeat(32) + "=1; Secure";
        exchange.getResponseHeaders().add("Set-Cookie", marker);
        exchange.getResponseHeaders().add("Set-Cookie", "unrelated=secret; Secure");
        exchange.sendResponseHeaders(200, -1);
        assertFalse(messages.toString().contains("private-session"));
        assertTrue(messages.toString().contains(marker));
        assertFalse(messages.toString().contains("unrelated"));
        exchange.close();
        server.stop(0);
    }

    @Test void responseDateUsesTheReceiverClockAndIsForwardedToTheBrowser() throws Exception
    {
        HttpServer server = HttpServer.create();
        try
        {
            var context = server.createContext("/", exchange -> {});
            List<Map<String,?>> messages = new CopyOnWriteArrayList<>();
            RemoteSupportExchange exchange = new RemoteSupportExchange("1", "GET", URI.create("/api/test"), Map.of(),
                "vce-example.radioresolve.com", "private", context, messages::add, () -> true);
            exchange.getResponseHeaders().set("Date", "Thu, 1 Jan 1970 00:00:00 GMT");
            Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            exchange.sendResponseHeaders(200, -1);
            Instant after = Instant.now();
            Map<?,?> headers = (Map<?,?>)messages.getFirst().get("headers");
            @SuppressWarnings("unchecked")
            List<String> dates = (List<String>)headers.entrySet().stream()
                .filter(entry -> "Date".equalsIgnoreCase(String.valueOf(entry.getKey())))
                .findFirst().orElseThrow().getValue();
            assertEquals(1, dates.size());
            Instant date = ZonedDateTime.parse(dates.getFirst(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            assertFalse(date.isBefore(before), "The transport must replace stale handler metadata with receiver time");
            assertFalse(date.isAfter(after));
            exchange.close();
        }
        finally { server.stop(0); }
    }

    @Test void acknowledgesConsumedUploadAndResponseWaitsForAcknowledgement() throws Exception
    {
        HttpServer server = HttpServer.create();
        var context = server.createContext("/", exchange -> {});
        List<Map<String,?>> messages = new CopyOnWriteArrayList<>();
        RemoteSupportExchange exchange = new RemoteSupportExchange("7", "POST", URI.create("/"), Map.of(),
            "vce-example.radioresolve.com", "private", context, messages::add, () -> true);
        assertTrue(exchange.offer(new byte[]{1, 2, 3}));
        exchange.endRequest();
        assertEquals(1, exchange.getRequestBody().read());
        assertTrue(messages.isEmpty());
        assertArrayEquals(new byte[]{2, 3}, exchange.getRequestBody().readAllBytes());
        assertEquals("ack", messages.getFirst().get("type"));
        exchange.sendResponseHeaders(200, 3);
        try(var executor = Executors.newSingleThreadExecutor())
        {
            var writing = executor.submit(() -> {
                exchange.getResponseBody().write(new byte[]{4, 5, 6});
                exchange.getResponseBody().flush();
                return true;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while(messages.stream().noneMatch(value -> value.get("type").equals("data")) && System.nanoTime() < deadline)
                Thread.onSpinWait();
            assertFalse(writing.isDone(), "the response must wait for receiver acknowledgement");
            exchange.acknowledge();
            assertTrue(writing.get(2, TimeUnit.SECONDS));
        }
        exchange.close();
        server.stop(0);
    }

    @Test void cancellationWakesWaitingResponseAndRejectsFurtherWrites() throws Exception
    {
        HttpServer server = HttpServer.create();
        var context = server.createContext("/", exchange -> {});
        AtomicBoolean sent = new AtomicBoolean();
        RemoteSupportExchange exchange = new RemoteSupportExchange("1", "GET", URI.create("/"), Map.of(),
            "vce-example.radioresolve.com", "private", context,
            message -> { if(message.get("type").equals("data")) sent.set(true); }, () -> true);
        exchange.sendResponseHeaders(200, 1);
        try(var executor = Executors.newSingleThreadExecutor())
        {
            var writing = executor.submit(() -> assertThrows(java.io.IOException.class, () -> {
                exchange.getResponseBody().write(42); exchange.getResponseBody().flush();
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while(!sent.get() && System.nanoTime() < deadline) Thread.onSpinWait();
            exchange.cancel();
            writing.get(2, TimeUnit.SECONDS);
        }
        server.stop(0);
    }

    @Test void failedWriteIsNeverRetriedWhenBufferedResponseCloses() throws Exception
    {
        HttpServer server = HttpServer.create();
        var context = server.createContext("/", exchange -> {});
        java.util.concurrent.atomic.AtomicInteger chunks = new java.util.concurrent.atomic.AtomicInteger();
        RemoteSupportExchange exchange = new RemoteSupportExchange("1", "GET", URI.create("/"), Map.of(),
            "vce-example.radioresolve.com", "private", context, message -> {
                if(message.get("type").equals("data"))
                { chunks.incrementAndGet(); throw new java.io.IOException("simulated failed send"); }
            }, () -> true);
        exchange.sendResponseHeaders(200, 1);
        exchange.getResponseBody().write(42);
        assertThrows(java.io.IOException.class, () -> exchange.getResponseBody().flush());
        exchange.close();
        assertEquals(1, chunks.get(), "closing a failed buffer must not resend its unacknowledged data");
        server.stop(0);
    }
}
