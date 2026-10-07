/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.message.DecodeMessageViewService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class StatsDecodeMessagePumpTest
{
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000005";

    @Test
    void publishesNewGenerationSourceChangeBeforeMessagesWhenTheSourceChangesDuringPoll() throws Exception
    {
        var state = new AtomicReference<>(state(1, true));
        AtomicInteger polls = new AtomicInteger();
        CapturingOutput stream = new CapturingOutput();

        try(var output = new StatsWebServerService.MultiplexOutput(stream))
        {
            var result = StatsWebServerService.pumpDecodeMessages(output, state::get, () -> {
                if(polls.getAndIncrement() == 0)
                {
                    state.set(state(2, true));
                    return message("new", 2);
                }
                return null;
            }, message -> true, 64, 1, SUBSCRIPTION);
            assertEquals(2, result.generation());
            assertTrue(result.wrote());
            output.start();
            await(() -> stream.frames.size() == 2);

            assertEquals(List.of("source_change", "decode_message"), stream.frames.stream()
                .map(frame -> frame.path("event").textValue()).toList());
            assertEquals(2, stream.frames.getFirst().path("data").path("generation").longValue());
            assertEquals(SUBSCRIPTION, stream.frames.getFirst().path("data").path("subscription_id").textValue());
            assertEquals("CONVENTIONAL", stream.frames.getFirst().path("data").path("scope").textValue());
            assertEquals("new", stream.frames.getLast().path("data").path("message_id").textValue());
            assertFalse(stream.frames.getLast().path("data").has("source_generation"));
        }
    }

    @Test
    void discardsOldPolledRowsAndClearsPendingOldGenerationOutputBeforeRebinding() throws Exception
    {
        var state = new AtomicReference<>(state(1, true));
        AtomicInteger polls = new AtomicInteger();
        CapturingOutput stream = new CapturingOutput();

        try(var output = new StatsWebServerService.MultiplexOutput(stream))
        {
            StatsWebServerService.pumpDecodeMessages(output, state::get,
                () -> polls.getAndIncrement() == 0 ? message("queued-old", 1) : null, message -> true, 16, 1,
                SUBSCRIPTION);
            var result = StatsWebServerService.pumpDecodeMessages(output, state::get, () -> {
                if(polls.getAndIncrement() == 2)
                {
                    state.set(state(2, false));
                    return message("polled-old", 1);
                }
                return null;
            }, message -> true, 64, 1, SUBSCRIPTION);
            assertEquals(2, result.generation());
            output.start();
            await(() -> stream.frames.size() == 1);
            assertEquals("source_change", stream.frames.getFirst().path("event").textValue());
            assertFalse(stream.frames.getFirst().path("data").path("bound").booleanValue());
        }
    }

    @Test
    void limitsEveryPumpAndLeavesOtherMultiplexTopicsAvailable() throws Exception
    {
        AtomicInteger polls = new AtomicInteger();
        CapturingOutput stream = new CapturingOutput();
        try(var output = new StatsWebServerService.MultiplexOutput(stream))
        {
            var result = StatsWebServerService.pumpDecodeMessages(output, () -> state(3, true),
                () -> message("message-" + polls.incrementAndGet(), 3), message -> true, 64, 3, SUBSCRIPTION);
            assertTrue(result.wrote());
            assertEquals(64, polls.get());
            assertEquals(0, output.eventDrops());
            output.offerEvent(1, LiveMultiplexFrame.json(1, "activity", java.util.Map.of("revision", 9)).bytes(false));
            output.start();
            await(() -> stream.frames.size() == 65);
            assertEquals(64, stream.frames.stream().filter(frame ->
                "decode_message".equals(frame.path("event").textValue())).count());
            assertTrue(stream.frames.stream().anyMatch(frame -> "activity".equals(frame.path("event").textValue())));
        }
    }

    @Test
    void rechecksCurrentMembershipAfterPollEvenWhenThePublishedSourceGenerationHasNotChanged() throws Exception
    {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger admissions = new AtomicInteger();
        CapturingOutput stream = new CapturingOutput();
        try(var output = new StatsWebServerService.MultiplexOutput(stream))
        {
            var result = StatsWebServerService.pumpDecodeMessages(output, () -> state(3, true),
                () -> polls.getAndIncrement() == 0 ? message("removed-member", 3) : null,
                message -> { admissions.incrementAndGet(); return false; }, 64, 3, SUBSCRIPTION);
            assertEquals(3, result.generation());
            assertEquals(1, admissions.get());
            assertFalse(result.wrote());
            output.start();
            output.offerEvent(1, LiveMultiplexFrame.json(1, "activity", java.util.Map.of("revision", 9)).bytes(false));
            await(() -> stream.frames.size() == 1);
            assertEquals("activity", stream.frames.getFirst().path("event").textValue());
        }
    }

    private static DecodeMessageViewService.SourceState state(long generation, boolean bound)
    {
        return new DecodeMessageViewService.SourceState(generation, bound, null, 0, null, true);
    }

    private static DecodeMessageViewService.MessageView message(String id, long generation)
    {
        return new DecodeMessageViewService.MessageView(id, 1, "DMR", 1, true, "", "", "message",
            "00000000-0000-0000-0000-000000000001", "Fire", 451_012_500, generation);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(2);
        }
        assertTrue(condition.getAsBoolean(), "Multiplex writer did not publish the expected frames");
    }

    private static class CapturingOutput extends OutputStream
    {
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();

        @Override
        public void write(int value) throws IOException
        {
            throw new IOException("Expected complete multiplex frames");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException
        {
            frames.add(new ObjectMapper().readTree(new String(bytes, offset + LiveMultiplexFrame.HEADER_BYTES,
                length - LiveMultiplexFrame.HEADER_BYTES, StandardCharsets.UTF_8)));
        }
    }
}
