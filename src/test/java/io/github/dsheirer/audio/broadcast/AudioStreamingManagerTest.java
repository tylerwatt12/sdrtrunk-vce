/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.dsp.oscillator.ScalarRealOscillator;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.message.TimeslotMessage;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.record.AudioCallRecorder;
import io.github.dsheirer.record.RecordFormat;
import io.github.dsheirer.sample.Listener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automated testing for the AudioStreamingManager that includes for testing streaming a patch group audio segment as
 * an individual stream aliased against the patch group, or broken up into the set of patched talkgroups and streamed
 * according to the aliases for each patched talkgroup.
 */
public class AudioStreamingManagerTest
{
    private static final int TALKGROUP_1 = 100;
    private static final int TALKGROUP_2 = 200;
    private static final int TALKGROUP_3 = 300;
    private static final int RADIO_1 = 9999;

    @TempDir
    Path mTemporaryFolder;

    @Test
    public void testPatchGroupStreamingAsPatchGroup()
    {
        int expectedRecordingsCount = 1;

        //We use a countdown latch to count the number of expected audio recordings produced.
        CountDownLatch latch = new CountDownLatch(expectedRecordingsCount);
        CountDownLatch metricLatch = new CountDownLatch(1);
        AtomicInteger streamedMetrics = new AtomicInteger();
        Listener<AudioRecording> listener = audioRecording -> {
            latch.countDown();
        };

        UserPreferences userPreferences = new UserPreferences();
        userPreferences.getCallManagementPreference().setPatchGroupStreamingOption(PatchGroupStreamingOption.PATCH_GROUP);
        AudioStreamingManager manager = new AudioStreamingManager(listener, BroadcastFormat.MP3, userPreferences,
            call -> {
                streamedMetrics.incrementAndGet();
                metricLatch.countDown();
            });
        manager.start();
        manager.receive(getCompletedAudioCall());

        boolean success = false;

        try
        {
            success = latch.await(5, TimeUnit.SECONDS);
            success &= metricLatch.await(5, TimeUnit.SECONDS);
        }
        catch(InterruptedException e)
        {
            throw new RuntimeException(e);
        }

        cleanupStreamingDirectory(userPreferences.getDirectoryPreference().getDirectoryStreaming());

        assertTrue(success, "Stream patch group audio as PATCHED GROUP failed to produce [" +
                latch.getCount() + "/" + expectedRecordingsCount + "] streaming recordings");
        assertEquals(1, streamedMetrics.get());
    }

    @Test
    public void testPatchGroupStreamingAsIndividualGroups()
    {
        int expectedRecordingsCount = 2;

        //We use a countdown latch to count the number of expected audio recordings produced.  In this case, we expect
        //two audio recordings, one for stream B and one for stream C associated with the two patched talkgroups.
        CountDownLatch latch = new CountDownLatch(expectedRecordingsCount);
        CountDownLatch metricLatch = new CountDownLatch(1);
        AtomicInteger streamedMetrics = new AtomicInteger();
        Listener<AudioRecording> listener = audioRecording -> {
            latch.countDown();
        };

        UserPreferences userPreferences = new UserPreferences();
        userPreferences.getCallManagementPreference().setPatchGroupStreamingOption(PatchGroupStreamingOption.TALKGROUPS);
        AudioStreamingManager manager = new AudioStreamingManager(listener, BroadcastFormat.MP3, userPreferences,
            call -> {
                streamedMetrics.incrementAndGet();
                metricLatch.countDown();
            });
        manager.start();
        manager.receive(getCompletedAudioCall());

        boolean success = false;

        try
        {
            success = latch.await(5, TimeUnit.SECONDS);
            success &= metricLatch.await(5, TimeUnit.SECONDS);
        }
        catch(InterruptedException e)
        {
            throw new RuntimeException(e);
        }

        cleanupStreamingDirectory(userPreferences.getDirectoryPreference().getDirectoryStreaming());

        assertTrue(success, "Stream patch group audio as INDIVIDUAL TALKGROUPS failed to produce [" +
                latch.getCount() + "/" + expectedRecordingsCount + "] streaming recordings");
        assertEquals(1, streamedMetrics.get());
    }

    @Test
    void mergedPatchRoutesAreAuthoritativeDeduplicatedAndDeterministic() throws Exception
    {
        List<AudioRecording> recordings = new CopyOnWriteArrayList<>();
        CountDownLatch recordingLatch = new CountDownLatch(2);
        CountDownLatch metricLatch = new CountDownLatch(1);
        UserPreferences userPreferences = new UserPreferences();
        userPreferences.getCallManagementPreference().setPatchGroupStreamingOption(
            PatchGroupStreamingOption.TALKGROUPS);
        AudioStreamingManager manager = new AudioStreamingManager(recording -> {
            recordings.add(recording);
            recordingLatch.countDown();
        }, BroadcastFormat.MP3, userPreferences, _ -> metricLatch.countDown());
        RoutingFixture fixture = getMergedRoutingFixture();

        //This current alias addition happens after the completed call froze its merged route set and must be ignored.
        fixture.firstPatchedAlias().addBroadcastChannel(new BroadcastChannel("Late Addition"));
        manager.start();
        manager.receive(fixture.call());

        try
        {
            assertTrue(recordingLatch.await(5, TimeUnit.SECONDS), "Expected one decomposed and one fallback file");
            assertTrue(metricLatch.await(5, TimeUnit.SECONDS), "Expected the completed call streaming metric");
            manager.stop();

            Map<String, List<AudioRecording>> recordingsByRoute = new HashMap<>();

            for(AudioRecording recording : recordings)
            {
                for(BroadcastChannel broadcastChannel : recording.getBroadcastChannels())
                {
                    recordingsByRoute.computeIfAbsent(broadcastChannel.getChannelName(), _ -> new ArrayList<>())
                        .add(recording);
                }
            }

            assertEquals(Set.of("Route A", "Route B", "Shared"), recordingsByRoute.keySet(),
                "Only the frozen merged routes may be submitted");
            assertEquals(1, recordingsByRoute.get("Route A").size());
            assertEquals(1, recordingsByRoute.get("Route B").size());
            assertEquals(1, recordingsByRoute.get("Shared").size(),
                "A provider present under multiple patched aliases must be claimed once");
            assertEquals(2, recordings.size(),
                "Route A and Shared share one deterministic member; loser-only Route B uses one fallback");

            Identifier routeADestination =
                recordingsByRoute.get("Route A").getFirst().getIdentifierCollection().getToIdentifier();
            Identifier sharedDestination =
                recordingsByRoute.get("Shared").getFirst().getIdentifierCollection().getToIdentifier();
            Identifier routeBFallback =
                recordingsByRoute.get("Route B").getFirst().getIdentifierCollection().getToIdentifier();
            assertTrue(routeADestination instanceof TalkgroupIdentifier);
            assertEquals(TALKGROUP_2, routeADestination.getValue());
            assertEquals(routeADestination, sharedDestination,
                "Stable identifier order must assign Shared to talkgroup 200 even when patch insertion is reversed");
            assertTrue(routeBFallback instanceof PatchGroupIdentifier,
                "A loser-only route unavailable in the winner alias list must retain original patch metadata");
        }
        finally
        {
            manager.stop();
            cleanupStreamingDirectory(userPreferences.getDirectoryPreference().getDirectoryStreaming());
        }
    }

    @Test
    void completedCallHandoffIsBoundedByCallCountAndStopReleasesReservations()
    {
        UserPreferences preferences = new UserPreferences();
        ManualStreamingScheduler scheduler = new ManualStreamingScheduler();
        AudioStreamingManager manager = new AudioStreamingManager(recording -> {}, BroadcastFormat.MP3,
            preferences, null, scheduler, (call, path, userPreferences, identifiers) ->
                AudioCallRecorder.write(call, path, RecordFormat.MP3, userPreferences, identifiers));
        CompletedAudioCall call = getCompletedAudioCall();
        long sourceBytes = 100L * 500L * Float.BYTES;

        try
        {
            manager.start();

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                for(int index = 0; index < AudioStreamingManager.MAXIMUM_QUEUED_CALLS + 2; index++)
                {
                    manager.receive(call);
                }
            }, "A saturated streaming handoff must not wait for its consumer");

            AudioStreamingManager.StreamingQueueStatus status = manager.getQueueStatus();
            assertEquals(AudioStreamingManager.MAXIMUM_QUEUED_CALLS, status.retainedCalls());
            assertEquals(AudioStreamingManager.MAXIMUM_QUEUED_CALLS * sourceBytes,
                status.retainedSourceBytes());
            assertEquals(2, status.droppedCalls());

            manager.stop();
            status = manager.getQueueStatus();
            assertEquals(0, status.retainedCalls());
            assertEquals(0, status.retainedSourceBytes());
            assertEquals(AudioStreamingManager.MAXIMUM_QUEUED_CALLS + 2L, status.droppedCalls());
            assertFalse(status.acceptingCalls());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void emptyAudioCallsNeverEnterStreamingQueue()
    {
        UserPreferences preferences = new UserPreferences();
        ManualStreamingScheduler scheduler = new ManualStreamingScheduler();
        AudioStreamingManager manager = new AudioStreamingManager(recording -> {}, BroadcastFormat.MP3,
            preferences, null, scheduler, (call, path, userPreferences, identifiers) -> {});
        CompletedAudioCall template = getCompletedAudioCall();
        AudioCallSnapshot snapshot = template.snapshot();
        AudioCallSnapshot noBroadcastRoute = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(),
            snapshot.aliasList(), snapshot.identifierCollection(), Set.of(), snapshot.startTimestamp(),
            snapshot.lastActivityTimestamp(), snapshot.burstCount(), snapshot.burstGeneration(),
            snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(), snapshot.burstActive(),
            snapshot.complete(), snapshot.encryptionState(), snapshot.recordAudio(),
            snapshot.recordingMetadata(), snapshot.voiceCallQuality(), snapshot.callLegId(),
            snapshot.callLegSource(), snapshot.callEncryptionEvidence());

        try
        {
            manager.receive(new CompletedAudioCall(noBroadcastRoute, List.of()));
            assertEquals(0, manager.getQueueStatus().droppedCalls());

            manager.receive(withAudioBuffers(List.of()));
            assertEquals(0, manager.getQueueStatus().droppedCalls());
            assertEquals(0, manager.getQueueStatus().retainedCalls());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void completedCallHandoffIsBoundedByRetainedSourceBytes()
    {
        UserPreferences preferences = new UserPreferences();
        ManualStreamingScheduler scheduler = new ManualStreamingScheduler();
        AudioStreamingManager manager = new AudioStreamingManager(recording -> {}, BroadcastFormat.MP3,
            preferences, null, scheduler, (call, path, userPreferences, identifiers) ->
                AudioCallRecorder.write(call, path, RecordFormat.MP3, userPreferences, identifiers));
        float[] sharedEightMiBBuffer = new float[2 * 1024 * 1024];
        CompletedAudioCall call = withAudioBuffers(List.of(sharedEightMiBBuffer));

        try
        {
            manager.start();

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                for(int index = 0; index < 40; index++)
                {
                    manager.receive(call);
                }
            }, "The retained-audio limit must reject without waiting");

            AudioStreamingManager.StreamingQueueStatus status = manager.getQueueStatus();
            assertEquals(32, status.retainedCalls());
            assertEquals(AudioStreamingManager.MAXIMUM_QUEUED_SOURCE_BYTES,
                status.retainedSourceBytes());
            assertEquals(8, status.droppedCalls());

            manager.stop();
            assertEquals(0, manager.getQueueStatus().retainedCalls());
            assertEquals(0, manager.getQueueStatus().retainedSourceBytes());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void successfulAndFailedDeliveriesReleaseReservations() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryStreaming();
        ManualStreamingScheduler scheduler = new ManualStreamingScheduler();
        AtomicInteger delivered = new AtomicInteger();
        AudioStreamingManager successful = new AudioStreamingManager(recording -> delivered.incrementAndGet(),
            BroadcastFormat.MP3, preferences, null, scheduler,
            (call, path, userPreferences, identifiers) ->
                Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW));
        AudioStreamingManager failed = new AudioStreamingManager(recording -> {}, BroadcastFormat.MP3,
            preferences, null, scheduler, (call, path, userPreferences, identifiers) -> {
                throw new IOException("expected test failure");
            });

        try
        {
            preferences.getDirectoryPreference().setDirectoryStreaming(mTemporaryFolder);
            successful.start();
            successful.receive(getCompletedAudioCall());
            successful.new AudioSegmentProcessor().run();
            assertEquals(1, delivered.get());
            assertEquals(0, successful.getQueueStatus().retainedCalls());
            assertEquals(0, successful.getQueueStatus().retainedSourceBytes());
            assertEquals(0, successful.getQueueStatus().failedCalls());

            failed.start();
            failed.receive(getCompletedAudioCall());
            failed.new AudioSegmentProcessor().run();
            assertEquals(0, failed.getQueueStatus().retainedCalls());
            assertEquals(0, failed.getQueueStatus().retainedSourceBytes());
            assertEquals(1, failed.getQueueStatus().failedCalls());
        }
        finally
        {
            successful.stop();
            failed.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryStreaming(originalDirectory);
        }
    }

    @Test
    void stopWaitsForInFlightWriterAndLeavesNoReservation() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryStreaming();
        ManualStreamingScheduler scheduler = new ManualStreamingScheduler();
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AudioStreamingManager manager = new AudioStreamingManager(recording -> {}, BroadcastFormat.MP3,
            preferences, null, scheduler, (call, path, userPreferences, identifiers) -> {
                if(writes.getAndIncrement() == 0)
                {
                    writerEntered.countDown();

                    try
                    {
                        if(!releaseWriter.await(2, TimeUnit.SECONDS))
                        {
                            throw new IOException("Timed out waiting to release the streaming writer");
                        }
                    }
                    catch(InterruptedException exception)
                    {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted test writer", exception);
                    }
                }

                Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW);
            });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try
        {
            preferences.getDirectoryPreference().setDirectoryStreaming(mTemporaryFolder);
            manager.start();
            manager.receive(getCompletedAudioCall());
            manager.receive(getCompletedAudioCall());
            Future<?> processor = executor.submit(manager.new AudioSegmentProcessor());
            assertTrue(writerEntered.await(1, TimeUnit.SECONDS));
            Future<?> stopping = executor.submit(manager::stop);
            assertTrue(awaitWaitingDrain(manager, 1, TimeUnit.SECONDS));
            assertFalse(stopping.isDone(), "Stop must wait for an in-flight streaming writer");
            releaseWriter.countDown();
            processor.get(2, TimeUnit.SECONDS);
            stopping.get(2, TimeUnit.SECONDS);

            assertEquals(2, writes.get());
            assertEquals(0, manager.getQueueStatus().retainedCalls());
            assertEquals(0, manager.getQueueStatus().retainedSourceBytes());
            assertFalse(manager.getQueueStatus().acceptingCalls());
        }
        finally
        {
            releaseWriter.countDown();
            manager.stop();
            executor.shutdownNow();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryStreaming(originalDirectory);
        }
    }

    /**
     * Cleanup any generated streaming recordings.
     * @param streamingDirectory
     */
    private void cleanupStreamingDirectory(Path streamingDirectory)
    {
        if(Files.exists(streamingDirectory))
        {
            try(Stream<Path> fileStream = Files.list(streamingDirectory))
            {
                fileStream.forEach(path -> {
                    try
                    {
                        Files.delete(path);
                    }
                    catch(IOException e)
                    {
                        e.printStackTrace();
                    }
                });
            }
            catch(IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static CompletedAudioCall withAudioBuffers(List<float[]> audioBuffers)
    {
        CompletedAudioCall template = getCompletedAudioCall();
        return new CompletedAudioCall(template.snapshot(), audioBuffers);
    }

    private static boolean awaitWaitingDrain(AudioStreamingManager manager, long timeout, TimeUnit unit)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        while(System.nanoTime() < deadline)
        {
            if(manager.getQueueStatus().waitingDrains() > 0)
            {
                return true;
            }

            Thread.sleep(10);
        }

        return manager.getQueueStatus().waitingDrains() > 0;
    }

    private static CompletedAudioCall getCompletedAudioCall()
    {
        AliasList aliasList = getAliasList();
        List<float[]> audioBuffers = new ArrayList<>();
        ScalarRealOscillator oscillator = new ScalarRealOscillator(1000, 8000);
        for(int x = 0; x < 100; x++)
        {
            audioBuffers.add(oscillator.generate(500));
        }

        MutableIdentifierCollection identifierCollection = new MutableIdentifierCollection();
        identifierCollection.setTimeslot(TimeslotMessage.TIMESLOT_0);
        identifierCollection.update(getPatchGroup());
        identifierCollection.update(getRadio());

        Set<BroadcastChannel> broadcastChannels = new HashSet<>();
        broadcastChannels.add(new BroadcastChannel("Stream B"));
        broadcastChannels.add(new BroadcastChannel("Stream C"));

        long now = System.currentTimeMillis();
        AudioCallId callId = new AudioCallId(1L, 1L, TimeslotMessage.TIMESLOT_0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(
            callId,
            null,
            aliasList,
            identifierCollection,
            broadcastChannels,
            now,
            now,
            1,
            1,
            now,
            now,
            false,
            true,
            CallEncryptionState.CLEAR,
            false,
            null,
            VoiceCallQuality.EMPTY,
            CallLegId.from(callId),
            null,
            null);
        return new CompletedAudioCall(snapshot, audioBuffers);
    }

    private static RoutingFixture getMergedRoutingFixture()
    {
        AliasList aliasList = p25AliasList("merged-routing-test");
        Alias firstPatchedAlias = new Alias("talkgroup 200");
        firstPatchedAlias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, TALKGROUP_2));
        firstPatchedAlias.addBroadcastChannel(new BroadcastChannel("Route A"));
        firstPatchedAlias.addBroadcastChannel(new BroadcastChannel("Shared"));
        aliasList.addAlias(firstPatchedAlias);

        Alias secondPatchedAlias = new Alias("talkgroup 300");
        secondPatchedAlias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, TALKGROUP_3));
        secondPatchedAlias.addBroadcastChannel(new BroadcastChannel("Shared"));
        aliasList.addAlias(secondPatchedAlias);

        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(TALKGROUP_1));
        //Reverse insertion proves that provider claiming does not depend on decoder update arrival order.
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(TALKGROUP_3));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(TALKGROUP_2));
        MutableIdentifierCollection identifierCollection = new MutableIdentifierCollection();
        identifierCollection.setTimeslot(TimeslotMessage.TIMESLOT_0);
        identifierCollection.update(APCO25PatchGroup.create(patchGroup));
        identifierCollection.update(getRadio());
        Set<BroadcastChannel> frozenRoutes = Set.of(new BroadcastChannel("Route A"),
            new BroadcastChannel("Route B"), new BroadcastChannel("Shared"));
        List<float[]> audioBuffers = new ArrayList<>();
        ScalarRealOscillator oscillator = new ScalarRealOscillator(1000, 8000);

        for(int x = 0; x < 100; x++)
        {
            audioBuffers.add(oscillator.generate(500));
        }

        long now = System.currentTimeMillis();
        AudioCallId callId = new AudioCallId(2L, 1L, TimeslotMessage.TIMESLOT_0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(
            callId, null, aliasList, identifierCollection,
            frozenRoutes, now, now, 1, 1, now, now, false, true, CallEncryptionState.CLEAR, false,
            null, VoiceCallQuality.EMPTY, CallLegId.from(callId), null, null);
        return new RoutingFixture(new CompletedAudioCall(snapshot, audioBuffers), firstPatchedAlias);
    }

    private static AliasList getAliasList()
    {
        AliasList aliasList = p25AliasList("test");

        Alias patchAlias = new Alias("patch");
        patchAlias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        patchAlias.addBroadcastChannel(new BroadcastChannel("Stream A"));
        aliasList.addAlias(patchAlias);

        Alias talkgroupAlias1 = new Alias("talkgroup1");
        talkgroupAlias1.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 200));
        talkgroupAlias1.addBroadcastChannel(new BroadcastChannel("Stream B"));
        aliasList.addAlias(talkgroupAlias1);

        Alias talkgroupAlias2 = new Alias("talkgroup2");
        talkgroupAlias2.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 300));
        talkgroupAlias2.addBroadcastChannel(new BroadcastChannel("Stream C"));
        aliasList.addAlias(talkgroupAlias2);

        return aliasList;
    }

    private static AliasList p25AliasList(String name)
    {
        return new AliasList(new AliasListDefinition(name, AliasListFamily.P25));
    }

    /**
     * Creates a patch group
     * @return p25 patch group
     */
    private static APCO25PatchGroup getPatchGroup()
    {
        TalkgroupIdentifier talkgroup1 = APCO25Talkgroup.create(TALKGROUP_1);
        TalkgroupIdentifier talkgroup2 = APCO25Talkgroup.create(TALKGROUP_2);
        TalkgroupIdentifier talkgroup3 = APCO25Talkgroup.create(TALKGROUP_3);

        PatchGroup pg = new PatchGroup(talkgroup1);
        pg.addPatchedTalkgroup(talkgroup2);
        pg.addPatchedTalkgroup(talkgroup3);
        return APCO25PatchGroup.create(pg);
    }

    /**
     * Creates a source radio identifier.
     * @return radio
     */
    private static RadioIdentifier getRadio()
    {
        return APCO25RadioIdentifier.createFrom(RADIO_1);
    }

    private record RoutingFixture(CompletedAudioCall call, Alias firstPatchedAlias)
    {
    }

    private static class ManualStreamingScheduler extends ScheduledThreadPoolExecutor
    {
        private ManualStreamingScheduler()
        {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                       TimeUnit unit)
        {
            return super.scheduleAtFixedRate(command, 1, 1, TimeUnit.DAYS);
        }
    }
}
