/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StatsWebMultiplexOutputTest
{
    @Test
    void echoesTheChannelDiagnosticSubscriptionInEachAuthoritativeState() throws Exception
    {
        String subscriptionId = "00000000-0000-0000-0000-000000000003";
        DiagnosticStreamFrame frame = StatsWebServerService.diagnosticState(7, 11, Map.of(
            "configuration_id", "00000000-0000-0000-0000-000000000001",
            "frequency_hz", 851_012_500L,
            "timeslot", 2), subscriptionId);
        String json = new String(frame.encoded(), DiagnosticStreamFrame.HEADER_BYTES,
            frame.encoded().length - DiagnosticStreamFrame.HEADER_BYTES, StandardCharsets.UTF_8);

        assertEquals(DiagnosticStreamFrame.TYPE_STATE, frame.type());
        assertTrue(json.contains("\"subscription_id\":\"" + subscriptionId + "\""));
        assertTrue(json.contains("\"frequency_hz\":851012500"));
    }

    @Test
    void boundsMetadataBacklogAndMarksPersistentOverflow()
    {
        StatsWebServerService.MultiplexOutput output =
            new StatsWebServerService.MultiplexOutput(OutputStream.nullOutputStream());

        try(output)
        {
            for(int index = 0; index < 320; index++)
            {
                output.offerEvent(2, new byte[]{(byte)index});
            }

            assertEquals(256, output.eventDrops());
            assertEquals(256, output.eventDrops(2));
            assertEquals(0, output.eventDrops(3));
            assertTrue(output.isPersistentlySlow());
        }
    }

    @Test
    void detectsAndClosesABlockedNetworkWriter() throws Exception
    {
        BlockingOutputStream blocked = new BlockingOutputStream();
        StatsWebServerService.MultiplexOutput output =
            new StatsWebServerService.MultiplexOutput(blocked, Duration.ofMillis(25));
        output.start();
        output.offerEvent(2, new byte[]{1, 2, 3});
        assertTrue(blocked.mWriteEntered.await(1, TimeUnit.SECONDS));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

        while(!output.isPersistentlySlow() && System.nanoTime() < deadline)
        {
            Thread.sleep(2);
        }

        assertTrue(output.isPersistentlySlow());
        output.close();
        assertTrue(blocked.mClosed.await(1, TimeUnit.SECONDS));
    }

    @Test
    void handlerCloseDoesNotWaitForOrConcurrentlyCloseABlockedTransportWriter() throws Exception
    {
        BlockingOutputStream blocked = new BlockingOutputStream();
        AtomicInteger terminationCallbacks = new AtomicInteger();
        CountDownLatch writerTerminated = new CountDownLatch(1);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(blocked,
            Duration.ofMillis(25), false, () -> {}, () -> {
                terminationCallbacks.incrementAndGet();
                writerTerminated.countDown();
            });
        output.start();
        output.offerEvent(2, new byte[]{1});
        assertTrue(blocked.mWriteEntered.await(1, TimeUnit.SECONDS));

        CountDownLatch handlerReturned = new CountDownLatch(1);
        Thread handler = new Thread(() -> {
            output.close();
            handlerReturned.countDown();
        }, "multiplex handler close test");
        handler.start();

        assertTrue(handlerReturned.await(1, TimeUnit.SECONDS));
        handler.join(1_000);
        assertFalse(handler.isAlive());
        assertEquals(1, blocked.mClosed.getCount(),
            "The writer exclusively owns the real HTTPS response stream while a write is in progress");
        assertEquals(0, terminationCallbacks.get(),
            "Connection capacity remains charged while the transport writer is still retained");
        blocked.close();
        assertTrue(writerTerminated.await(1, TimeUnit.SECONDS));
        assertEquals(1, terminationCallbacks.get());
    }

    @Test
    void viewportStateSurvivesMetadataOverflowAndPrecedesTheNextDenseFrame() throws Exception
    {
        RecordingOutputStream recording = new RecordingOutputStream(3);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(recording);

        for(int index = 0; index < 320; index++)
        {
            output.offerEvent(2, new byte[]{1});
        }

        output.offerLatest(6, new byte[]{2});
        output.offerState(6, new byte[]{3});
        output.offerLatest(6, new byte[]{4});
        output.start();

        assertTrue(recording.mWrites.await(1, TimeUnit.SECONDS));
        output.close();
        assertEquals(List.of((byte)3), bytes(recording.mEnvelopes.getFirst()));
        assertFalse(recording.mEnvelopes.stream().anyMatch(envelope -> envelope.length == 1 && envelope[0] == 2));
        assertTrue(recording.mEnvelopes.stream().anyMatch(envelope -> envelope.length == 1 && envelope[0] == 4));
    }

    @Test
    void emitsChannelLatestValuesAlongsidePriorityDiagnosticState() throws Exception
    {
        RecordingOutputStream recording = new RecordingOutputStream(2);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(recording);
        output.offerLatest(1, new byte[]{1});
        output.offerState(6, new byte[]{6});
        output.start();

        assertTrue(recording.mWrites.await(1, TimeUnit.SECONDS));
        output.close();
        assertEquals(List.of((byte)6), bytes(recording.mEnvelopes.getFirst()));
        assertTrue(recording.mEnvelopes.stream().anyMatch(envelope -> envelope.length == 1 && envelope[0] == 1));
    }

    @Test
    void authoritativeSnapshotDoesNotEraseUnrelatedMetadata() throws Exception
    {
        RecordingOutputStream recording = new RecordingOutputStream(2);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(recording);
        output.offerEvent(3, new byte[]{9});
        output.offerLatest(1, new byte[]{1});
        output.start();

        assertTrue(recording.mWrites.await(1, TimeUnit.SECONDS));
        output.close();
        assertTrue(recording.mEnvelopes.stream().anyMatch(envelope -> envelope[0] == 9));
        assertTrue(recording.mEnvelopes.stream().anyMatch(envelope -> envelope[0] == 1));
    }

    @Test
    void mixedTopicOverflowCannotEvictAnotherTopicsMetadata() throws Exception
    {
        RecordingOutputStream recording = new RecordingOutputStream(65);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(recording);

        for(int index = 0; index < 128; index++)
        {
            output.offerEvent(2, new byte[]{2});
        }

        output.offerEvent(3, new byte[]{3});
        assertEquals(64, output.eventDrops(2));
        assertEquals(0, output.eventDrops(3));
        output.start();

        assertTrue(recording.mWrites.await(1, TimeUnit.SECONDS));
        output.close();
        assertEquals(64, recording.mEnvelopes.stream().filter(envelope -> envelope[0] == 2).count());
        assertEquals(1, recording.mEnvelopes.stream().filter(envelope -> envelope[0] == 3).count());
    }

    @Test
    void byteBudgetEvictsOnlyTheAffectedTopicAndReturnsChargesWhenPolled() throws Exception
    {
        int envelopeBytes = StatsWebServerService.MultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC / 2 + 1;
        byte[] evicted = new byte[envelopeBytes];
        evicted[0] = 2;
        byte[] retained = new byte[envelopeBytes];
        retained[0] = 4;
        byte[] unrelated = new byte[]{3};
        RecordingOutputStream recording = new RecordingOutputStream(2);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(recording);

        output.offerEvent(2, evicted);
        output.offerEvent(3, unrelated);
        output.offerEvent(2, retained);

        assertEquals(1, output.eventDrops(2));
        assertEquals(0, output.eventDrops(3));
        assertEquals(retained.length, output.pendingEventBytes(2));
        assertEquals(unrelated.length, output.pendingEventBytes(3));
        output.start();

        assertTrue(recording.mWrites.await(1, TimeUnit.SECONDS));
        output.close();
        assertEquals(0, output.pendingEventBytes(2));
        assertEquals(0, output.pendingEventBytes(3));
        assertFalse(recording.mEnvelopes.stream().anyMatch(envelope -> envelope[0] == 2));
        assertTrue(recording.mEnvelopes.stream().anyMatch(envelope -> envelope[0] == 4));
        assertTrue(recording.mEnvelopes.stream().anyMatch(envelope -> envelope[0] == 3));
    }

    @Test
    void multiMegabyteMetadataIsDroppedAndTopicRecoveryStillRuns() throws Exception
    {
        RecordingOutputStream recording = new RecordingOutputStream(2);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(recording);
        byte[] previous = new byte[128];
        previous[0] = 2;
        byte[] oversized = new byte[4 * 1024 * 1024];
        oversized[0] = 9;

        output.offerEvent(2, previous);
        output.offerEvent(2, oversized);
        output.offerEvent(3, new byte[]{3});

        assertEquals(1, output.eventDrops(2), "an oversized envelope must signal a gap for this topic");
        assertEquals(0, output.eventDrops(3));
        assertEquals(previous.length, output.pendingEventBytes(2),
            "rejecting an oversized envelope must not evict otherwise valid metadata");
        output.offerRecovery(2, new byte[]{8});
        assertEquals(0, output.pendingEventBytes(2));
        assertEquals(1, output.pendingEventBytes(3));
        output.start();

        assertTrue(recording.mWrites.await(1, TimeUnit.SECONDS));
        output.close();
        assertEquals(List.of((byte)8), bytes(recording.mEnvelopes.getFirst()));
        assertFalse(recording.mEnvelopes.stream().anyMatch(envelope -> envelope.length == oversized.length));
        assertFalse(recording.mEnvelopes.stream().anyMatch(envelope -> envelope[0] == 2));
        assertTrue(recording.mEnvelopes.stream().anyMatch(envelope -> envelope[0] == 3));
        assertEquals(0, output.pendingEventBytes(3));
    }

    @Test
    void recoveryClearsOnlyTheAffectedTopicAndPrecedesRemainingMetadata() throws Exception
    {
        RecordingOutputStream recording = new RecordingOutputStream(2);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(recording);

        for(int index = 0; index < 128; index++)
        {
            output.offerEvent(2, new byte[]{2});
        }

        output.offerEvent(3, new byte[]{3});
        output.offerRecovery(2, new byte[]{8});
        output.start();

        assertTrue(recording.mWrites.await(1, TimeUnit.SECONDS));
        output.close();
        assertEquals(List.of((byte)8), bytes(recording.mEnvelopes.getFirst()));
        assertFalse(recording.mEnvelopes.stream().anyMatch(envelope -> envelope[0] == 2));
        assertTrue(recording.mEnvelopes.stream().anyMatch(envelope -> envelope[0] == 3));
    }

    @Test
    void decoderFilterStateSurvivesAFullTopicQueueAndPrecedesNewGenerationRows() throws Exception
    {
        int decodeMessageTopic = 4;
        RecordingOutputStream recording = new RecordingOutputStream(2);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(recording);

        for(int index = 0; index < 128; index++)
        {
            output.offerEvent(decodeMessageTopic, new byte[]{1});
        }

        output.offerRecovery(decodeMessageTopic, new byte[]{2});
        output.offerEvent(decodeMessageTopic, new byte[]{3});
        output.start();

        assertTrue(recording.mWrites.await(1, TimeUnit.SECONDS));
        output.close();
        assertEquals(List.of((byte)2), bytes(recording.mEnvelopes.get(0)));
        assertEquals(List.of((byte)3), bytes(recording.mEnvelopes.get(1)));
        assertFalse(recording.mEnvelopes.stream().anyMatch(envelope -> envelope[0] == 1));
    }

    @Test
    void recoveryCannotRaceSelectionAndFollowAPostRecoveryDelta() throws Exception
    {
        CountDownLatch emptyStateObserved = new CountDownLatch(1);
        CountDownLatch continueSelection = new CountDownLatch(1);
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        AtomicBoolean interceptFirstSelection = new AtomicBoolean(true);
        RecordingOutputStream recording = new RecordingOutputStream(3);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(recording,
            Duration.ofSeconds(1), () -> {
                if(interceptFirstSelection.compareAndSet(true, false))
                {
                    emptyStateObserved.countDown();

                    try
                    {
                        continueSelection.await();
                    }
                    catch(InterruptedException exception)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        output.offerEvent(2, new byte[]{1});
        output.start();
        assertTrue(emptyStateObserved.await(1, TimeUnit.SECONDS));

        Thread recovery = new Thread(() -> {
            recoveryStarted.countDown();
            output.offerRecovery(2, new byte[]{2});
            output.offerEvent(2, new byte[]{3});
        }, "multiplex recovery ordering test");
        recovery.start();
        assertTrue(recoveryStarted.await(1, TimeUnit.SECONDS));
        continueSelection.countDown();
        recovery.join(1_000);

        assertFalse(recovery.isAlive());
        assertTrue(recording.mWrites.await(1, TimeUnit.SECONDS));
        output.close();
        assertEquals(List.of((byte)1), bytes(recording.mEnvelopes.get(0)));
        assertEquals(List.of((byte)2), bytes(recording.mEnvelopes.get(1)));
        assertEquals(List.of((byte)3), bytes(recording.mEnvelopes.get(2)));
    }

    @Test
    void externalStopRequestsOwnerCleanupWithoutDoubleClosingSessionOrPermit() throws Exception
    {
        AtomicReference<StatsWebServerService.MultiplexClientLifecycle> lifecycle = new AtomicReference<>();
        AtomicInteger sessionCloses = new AtomicInteger();
        Semaphore permits = new Semaphore(0);
        CountDownLatch ownerReady = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            StatsWebServerService.MultiplexClientLifecycle owned =
                new StatsWebServerService.MultiplexClientLifecycle(Thread.currentThread());
            lifecycle.set(owned);
            ownerReady.countDown();

            while(!owned.isCloseRequested())
            {
                try
                {
                    Thread.sleep(1_000);
                }
                catch(InterruptedException exception)
                {
                    //External close requests wake the handler so it can own cleanup.
                }
            }

            Runnable closeResources = () -> {
                sessionCloses.incrementAndGet();
                permits.release();
            };
            owned.closeOnOwner(closeResources);
            owned.closeOnOwner(closeResources);
        }, "multiplex lifecycle owner test");
        owner.start();
        assertTrue(ownerReady.await(1, TimeUnit.SECONDS));

        lifecycle.get().requestClose();
        lifecycle.get().requestClose();
        owner.join(1_000);

        assertFalse(owner.isAlive());
        assertEquals(1, sessionCloses.get());
        assertEquals(1, permits.availablePermits());
    }

    @Test
    void transportUpdatesSameTunerViewportAndExperimentBeforeItsNormalCloseAndReopenPath() throws Exception
    {
        String source = Files.readString(Path.of("src", "main", "java", "io", "github", "dsheirer", "stats",
            "StatsWebServerService.java"));
        int sameTarget = source.indexOf("\"tuner_diagnostics\".equals(topic) && wanted != null && active != null");
        int update = source.indexOf(
            "mTunerDiagnostics.updateConfiguration(request.viewport(), request.profile())", sameTarget);
        int close = source.indexOf("closeTopic(topic);", sameTarget);

        assertTrue(sameTarget >= 0);
        assertTrue(update > sameTarget);
        assertTrue(close > update,
            "Same-target viewport and profile updates must not tear down the producer/session");
        assertTrue(source.contains("writeMultiplexRecoveryJson(output, TOPIC_CHANNEL_ACTIVITY, \"snapshot\""));
        assertTrue(source.contains("metadataGap(output, TOPIC_CHANNEL_ACTIVITY"));
        assertTrue(source.contains("metadataGap(output, TOPIC_CALLS"));
        assertFalse(source.contains("metadataGap(output, TOPIC_DECODE_EVENTS"));
        assertFalse(source.contains("metadataGap(output, TOPIC_DECODE_MESSAGES"));
        assertTrue(source.contains("reportStatelessGaps(output)"));
        assertTrue(source.contains("TOPIC_DECODE_EVENTS, \"live_gap\""));
        assertTrue(source.contains("TOPIC_DECODE_MESSAGES, \"live_gap\""));
        assertTrue(source.contains(
            "writeMultiplexRecoveryJson(output, TOPIC_DECODE_MESSAGES, \"source_change\""));
        assertEquals(2, countOccurrences(source,
            "decodeMessageSourceState(sourceState, mDecodeMessageSubscriptionId)"));
        assertTrue(source.contains("record DecodeMessageSourceState(long generation, boolean bound"));
        assertTrue(source.contains(
            "writeMultiplexRecoveryJson(output, TOPIC_DECODE_EVENTS, \"source_change\""));
        assertTrue(source.contains("new DecodeEventSourceState(scope.configurationId(), scope.frequencyHz(),"));
        assertTrue(source.contains("writeMultiplexJson(output, TOPIC_DECODE_EVENTS, \"filter_catalog\""));
        assertFalse(source.contains("TOPIC_DECODE_EVENTS, \"snapshot\""));
        assertFalse(source.contains("TOPIC_DECODE_MESSAGES, \"snapshot\""));
        assertTrue(source.contains("output.eventDrops(topic) != mObservedOutputDrops[topic]"));
        assertFalse(source.contains("output.eventDrops() !="));
        assertFalse(source.contains("mLastChannelActivitySnapshot"));
    }

    @Test
    void decodeEventSubscriptionInstallsItsLiveEdgeBeforeActivatingProjection() throws Exception
    {
        String source = Files.readString(Path.of("src", "main", "java", "io", "github", "dsheirer", "stats",
            "StatsWebServerService.java"));
        int subscription = source.indexOf("mDecodeEvents = mDecodeEventHub.subscribe(event ->");
        int liveEdge = source.indexOf("liveEdge.set(mDecodeEventViewService.advanceLiveEdge())", subscription);
        int activation = source.indexOf("mDecodeEventViewService.addListener(mDecodeEventViewListener)", liveEdge);

        assertTrue(subscription >= 0);
        assertTrue(source.contains("new AtomicLong(Long.MAX_VALUE)"));
        assertTrue(source.contains("view.observationEpoch() >= liveEdge.get()"));
        assertTrue(liveEdge > subscription);
        assertTrue(activation > liveEdge);
    }

    @Test
    void sourceDropBaselinesPrecedeEveryRecoverySnapshot() throws Exception
    {
        String source = Files.readString(Path.of("src", "main", "java", "io", "github", "dsheirer", "stats",
            "StatsWebServerService.java"));
        assertEquals(2, countOccurrences(source, "var recovery = captureRecovery("),
            "Only stateful topics should construct recovery snapshots");
        assertEquals(2, countOccurrences(source, "new RecoveryCapture<>(dropBaseline, snapshot)"),
            "Channel activity uses the equivalent explicit ordering because snapshot encoding can throw");
        assertFalse(source.contains("Drops = mChannelActivity.droppedCount();"));
        assertFalse(source.contains("Drops = mCalls.droppedCount();"));
        assertTrue(source.contains("mDecodeEventDrops = 0;"));
        assertTrue(source.contains("mDecodeMessageDrops = 0;"));
    }

    @Test
    void aDropDuringSnapshotConstructionForcesAnotherRecoveryPass() throws Exception
    {
        AtomicLong dropped = new AtomicLong(12);
        CountDownLatch snapshotStarted = new CountDownLatch(1);
        CountDownLatch finishSnapshot = new CountDownLatch(1);
        AtomicReference<StatsWebServerService.RecoveryCapture<String>> capture = new AtomicReference<>();
        Thread recovery = new Thread(() -> capture.set(StatsWebServerService.captureRecovery(dropped::get, () -> {
            snapshotStarted.countDown();

            try
            {
                finishSnapshot.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            return "authoritative";
        })), "multiplex slow snapshot test");
        recovery.start();
        assertTrue(snapshotStarted.await(1, TimeUnit.SECONDS));
        dropped.incrementAndGet();
        finishSnapshot.countDown();
        recovery.join(1_000);

        assertFalse(recovery.isAlive());
        assertEquals(12, capture.get().dropBaseline());
        assertEquals(13, dropped.get());
        assertEquals("authoritative", capture.get().snapshot());
    }

    @Test
    void clearingALogicalTopicRemovesItsQueuedStateAndDenseFrame() throws Exception
    {
        RecordingOutputStream recording = new RecordingOutputStream(1);
        StatsWebServerService.MultiplexOutput output = new StatsWebServerService.MultiplexOutput(recording);
        output.offerEvent(2, new byte[]{2});
        output.offerEvent(6, new byte[100]);
        output.offerState(6, new byte[]{6});
        output.offerLatest(6, new byte[]{7});
        assertEquals(100, output.pendingEventBytes(6));
        output.clearTopic(6);
        assertEquals(0, output.pendingEventBytes(6));
        output.start();

        assertTrue(recording.mWrites.await(1, TimeUnit.SECONDS));
        output.close();
        assertEquals(1, recording.mEnvelopes.size());
        assertEquals(List.of((byte)2), bytes(recording.mEnvelopes.getFirst()));
    }

    @Test
    void transportWiresRetryPolicyWithoutActivatingAFailedOpen() throws Exception
    {
        String source = Files.readString(Path.of("src", "main", "java", "io", "github", "dsheirer", "stats",
            "StatsWebServerService.java"));
        int open = source.indexOf("openTopic(topic, wanted, output);");
        int activate = source.indexOf("mActiveParameters.put(topic, wanted);", open);
        int failure = source.indexOf("catch(RuntimeException exception)", open);

        assertTrue(open >= 0);
        assertTrue(activate > open && activate < failure,
            "A logical topic must become active only after its open operation succeeds");
        assertTrue(source.contains("!mTopicRetryPolicy.canAttempt(topic, wanted, now)"));
        assertTrue(source.contains("mTopicRetryPolicy.failed(topic, wanted, now)"));
        assertTrue(source.contains("mTopicRetryPolicy.succeeded(topic)"));
        assertFalse(source.contains("Recording the attempted value avoids a tight open/error loop"));
    }

    private static List<Byte> bytes(byte[] values)
    {
        List<Byte> result = new java.util.ArrayList<>(values.length);

        for(byte value: values)
        {
            result.add(value);
        }

        return result;
    }

    private static int countOccurrences(String source, String value)
    {
        int count = 0;
        int position = 0;

        while((position = source.indexOf(value, position)) >= 0)
        {
            count++;
            position += value.length();
        }

        return count;
    }

    private static final class RecordingOutputStream extends OutputStream
    {
        private final List<byte[]> mEnvelopes = new CopyOnWriteArrayList<>();
        private final CountDownLatch mWrites;

        private RecordingOutputStream(int expectedWrites)
        {
            mWrites = new CountDownLatch(expectedWrites);
        }

        @Override
        public void write(int value)
        {
            write(new byte[]{(byte)value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length)
        {
            mEnvelopes.add(java.util.Arrays.copyOfRange(bytes, offset, offset + length));
            mWrites.countDown();
        }
    }

    private static final class BlockingOutputStream extends OutputStream
    {
        private final CountDownLatch mWriteEntered = new CountDownLatch(1);
        private final CountDownLatch mClosed = new CountDownLatch(1);

        @Override
        public void write(int value) throws IOException
        {
            write(new byte[]{(byte)value});
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException
        {
            mWriteEntered.countDown();
            boolean interrupted = false;

            while(mClosed.getCount() > 0)
            {
                try
                {
                    mClosed.await();
                }
                catch(InterruptedException exception)
                {
                    //Real HTTPS writes are not guaranteed to respond to interruption. Keep this fake blocked until
                    //the transport itself terminates so handler shutdown cannot masquerade as a safe socket abort.
                    interrupted = true;
                }
            }

            if(interrupted)
            {
                Thread.currentThread().interrupt();
            }

            throw new IOException("Closed");
        }

        @Override
        public void close()
        {
            mClosed.countDown();
        }
    }
}
