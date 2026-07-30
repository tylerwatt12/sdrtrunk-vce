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
import io.github.dsheirer.audio.call.CompletedAudioCall;
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
import io.github.dsheirer.sample.Listener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        AudioCallSnapshot snapshot = new AudioCallSnapshot(
            new AudioCallId(1L, 1L, TimeslotMessage.TIMESLOT_0),
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
            false,
            false,
            100,
            false);
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
        AudioCallSnapshot snapshot = new AudioCallSnapshot(
            new AudioCallId(2L, 1L, TimeslotMessage.TIMESLOT_0), null, aliasList, identifierCollection,
            frozenRoutes, now, now, 1, 1, now, now, false, true, false, false, 100, false);
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
}
