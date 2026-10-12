/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.audio.broadcast.icecast.IcecastHTTPConfiguration;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Header;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioStreamingBroadcasterMetadataContextTest
{
    @TempDir
    Path mDirectory;

    @Test
    void completedWinnerContextReachesUpdaterAndAudioAndClearsForSilence() throws Exception
    {
        IdentifierCollection identifiers = network(0xABCDE, 0x456);
        TestBroadcaster broadcaster = new TestBroadcaster();
        broadcaster.setBroadcastState(BroadcastState.CONNECTED);
        broadcaster.receive(recording(identifiers, completedMetadata(identifiers, "p25:bee00:123")));
        var processor = broadcaster.new RecordingQueueProcessor();

        processor.run();

        assertEquals(List.of("p25:bee00:123"), broadcaster.mMetadataKeys);
        assertSame(identifiers, broadcaster.mMetadataIdentifiers.getFirst());
        assertEquals("p25:bee00:123", broadcaster.servingKey());
        assertTrue(broadcaster.mAudioKeys.stream().allMatch("p25:bee00:123"::equals));

        processor.run();

        assertNull(broadcaster.servingKey());
        assertTrue(broadcaster.mAudioKeys.contains(null), "Silence must not inherit the previous call's system");

        processor.run();

        assertEquals(2, broadcaster.mMetadataKeys.size());
        assertNull(broadcaster.mMetadataKeys.getLast(), "Idle metadata must clear the call context");
        assertNull(broadcaster.mMetadataIdentifiers.getLast());
    }

    @Test
    void legacyRecordingFallsBackToItsOwnNetworkPairAndDisconnectClearsContext() throws Exception
    {
        TestBroadcaster broadcaster = new TestBroadcaster();
        broadcaster.setBroadcastState(BroadcastState.CONNECTED);
        broadcaster.receive(recording(network(0xABCDE, 0x456), CompletedCallBroadcastMetadata.EMPTY));
        broadcaster.new RecordingQueueProcessor().run();

        assertEquals("p25:abcde:456", broadcaster.servingKey());
        assertEquals(List.of("p25:abcde:456"), broadcaster.mMetadataKeys);

        broadcaster.setBroadcastState(BroadcastState.DISCONNECTED);

        assertNull(broadcaster.servingKey());
    }

    @Test
    void unknownCompletedWinnerDoesNotBorrowThePromotedCollectionSystem() throws Exception
    {
        IdentifierCollection promoted = network(0xABCDE, 0x456);
        TestBroadcaster broadcaster = new TestBroadcaster();
        broadcaster.setBroadcastState(BroadcastState.CONNECTED);
        broadcaster.receive(recording(promoted, completedMetadata(promoted, null)));

        broadcaster.new RecordingQueueProcessor().run();

        assertEquals(1, broadcaster.mMetadataKeys.size());
        assertNull(broadcaster.mMetadataKeys.getFirst());
        assertNull(broadcaster.servingKey());
        assertTrue(broadcaster.mAudioKeys.stream().allMatch(key -> key == null));
    }

    @Test
    void completedCallCapturesTheOriginalWinnerNetworkBeforeOnlyPromotedIdentifiersRemain() throws Exception
    {
        IdentifierCollection promoted = network(0xABCDE, 0x456);
        AudioCallSnapshot snapshot = completedMetadata(promoted, null).snapshot();
        CompletedAudioCall oneLeg = new CompletedAudioCall(snapshot, List.of());
        CompletedAudioCall call = new CompletedAudioCall(oneLeg.logicalCallId(), snapshot, List.of(),
            oneLeg.resolvedPolicy(), oneLeg.callLegSummaries(), network(0xBEE00, 0x123));
        TestBroadcaster broadcaster = new TestBroadcaster();
        broadcaster.setBroadcastState(BroadcastState.CONNECTED);
        broadcaster.receive(recording(promoted, CompletedCallBroadcastMetadata.from(call)));

        broadcaster.new RecordingQueueProcessor().run();

        assertEquals(List.of("p25:bee00:123"), broadcaster.mMetadataKeys);
        assertEquals("p25:bee00:123", broadcaster.servingKey());
    }

    @Test
    void contextOverloadKeepsLegacyMetadataUpdaterCompatible()
    {
        List<IdentifierCollection> received = new ArrayList<>();
        IBroadcastMetadataUpdater updater = received::add;
        IdentifierCollection identifiers = network(0xABCDE, 0x456);

        updater.update(identifiers, "p25:bee00:123");

        assertSame(identifiers, received.getFirst());
    }

    @Test
    void observedProjectionRestoresRecordingObservationLostByQualifiedReconstruction() throws Exception
    {
        var observedRadio = APCO25FullyQualifiedRadioIdentifier.createFrom(9_001, 0xABCDE, 0x321, 9_001);
        var retained = APCO25FullyQualifiedRadioIdentifier.withRecordingIdentifier(
            APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(501, 0xABCDE, 0x321, 9_001,
                Role.FROM, ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT), observedRadio);
        var reconstructed = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            501, 0xABCDE, 0x321, 9_001);
        var observedGroup = APCO25Talkgroup.create(77);
        var promotedGroup = APCO25FullyQualifiedTalkgroupIdentifier.createTo(77, 0xABCDE, 0x321, 201);
        var streamAliases = AliasListConfigurationIdentifier.create("Stream aliases");
        IdentifierCollection stream = new IdentifierCollection(List.of(reconstructed, promotedGroup, streamAliases));
        stream.setTimeslot(2);
        IdentifierCollection original = new IdentifierCollection(List.of(retained, observedGroup,
            AliasListConfigurationIdentifier.create("Original aliases")));
        CompletedCallBroadcastMetadata metadata = observedMetadata(stream, original, "p25:abcde:456");

        TestBroadcaster broadcaster = new TestBroadcaster();
        broadcaster.setBroadcastState(BroadcastState.CONNECTED);
        broadcaster.receive(recording(stream, metadata));

        broadcaster.new RecordingQueueProcessor().run();

        IdentifierCollection projected = broadcaster.mMetadataIdentifiers.getFirst();

        assertSame(retained, projected.getFromIdentifier());
        assertSame(observedGroup, projected.getToIdentifier());
        assertSame(streamAliases, projected.getAliasListConfiguration());
        assertEquals(2, projected.getTimeslot());
        assertEquals("ISSI 703710.801.9001", BroadcastIdentifierFormatter.format(
            projected.getFromIdentifier(), metadata.servingSystemKey()));
        assertEquals("9001", BroadcastIdentifierFormatter.format(projected.getFromIdentifier(), "p25:abcde:321"));
        assertEquals("9001", BroadcastIdentifierFormatter.format(projected.getFromIdentifier(), null));
        assertEquals("77", BroadcastIdentifierFormatter.format(projected.getToIdentifier(), metadata.servingSystemKey()));
        assertSame(reconstructed, stream.getFromIdentifier(), "The shared resolved collection must remain intact");
        assertSame(promotedGroup, stream.getToIdentifier());
        assertSame(projected, broadcaster.mAudioIdentifiers.getFirst(),
            "Inline audio metadata must use the same observed view as the out-of-band update");
    }

    @Test
    void projectionCannotBorrowASourceWhenTheWinnerHadNone()
    {
        IdentifierCollection stream = new IdentifierCollection(List.of(APCO25Talkgroup.create(77),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xABCDE, 0x321, 9_001)));
        IdentifierCollection original = new IdentifierCollection(List.of(APCO25Talkgroup.create(77)));

        assertNull(BroadcastIdentifierFormatter.streamIdentifiers(
            observedMetadata(stream, original, "p25:abcde:456"), stream).getFromIdentifier());
        assertNull(BroadcastIdentifierFormatter.streamIdentifiers(
            observedMetadata(stream, null, "p25:abcde:456"), stream).getFromIdentifier());
        assertSame(stream, BroadcastIdentifierFormatter.streamIdentifiers(CompletedCallBroadcastMetadata.EMPTY, stream));
        assertSame(stream, BroadcastIdentifierFormatter.streamIdentifiers(
            completedMetadata(stream, "p25:abcde:456"), stream), "Older metadata has no raw-view contract");
    }

    @Test
    void decomposedPatchDestinationUsesItsObservedMemberAndPreservesTheSelectedRoute()
    {
        var originalPatch = new PatchGroup(APCO25Talkgroup.create(77));
        var firstMember = APCO25Talkgroup.create(201);
        var secondMember = APCO25Talkgroup.create(202);
        originalPatch.addPatchedTalkgroup(firstMember);
        originalPatch.addPatchedTalkgroup(secondMember);
        IdentifierCollection original = new IdentifierCollection(List.of(APCO25PatchGroup.create(originalPatch)));
        IdentifierCollection selected = new IdentifierCollection(List.of(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(202, 0xABCDE, 0x321, 6_002),
            AliasListConfigurationIdentifier.create("Selected member aliases")));

        IdentifierCollection projected = BroadcastIdentifierFormatter.streamIdentifiers(
            observedMetadata(selected, original, "p25:abcde:456"), selected);

        assertSame(secondMember, projected.getToIdentifier());
        assertEquals("202", BroadcastIdentifierFormatter.format(projected.getToIdentifier(), "p25:abcde:456"));
        assertSame(selected.getAliasListConfiguration(), projected.getAliasListConfiguration());
    }

    @Test
    void qualifiedPatchMemberCannotBeReplacedByAnotherHomeWithTheSameLocalNumber()
    {
        var member = APCO25FullyQualifiedTalkgroupIdentifier.createTo(1001, 0xABCDE, 0x321, 2002);
        var selected = new IdentifierCollection(List.of(member));
        for(var primary: List.of(APCO25Talkgroup.create(1001),
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(1001, 0xBEE00, 0x123, 2002)))
        {
            var patch = new PatchGroup(primary);
            patch.addPatchedTalkgroup(member);
            var original = new IdentifierCollection(List.of(APCO25PatchGroup.create(patch)));
            assertSame(member, BroadcastIdentifierFormatter.streamIdentifiers(
                observedMetadata(selected, original, "p25:abcde:456"), selected).getToIdentifier());
        }
    }

    @Test
    void wholePatchProjectionPreservesCurrentMembersAndVersion()
    {
        var originalPatch = new PatchGroup(APCO25Talkgroup.create(77), 3);
        var observedMember = APCO25Talkgroup.create(201);
        originalPatch.addPatchedTalkgroup(observedMember);
        var currentPatch = new PatchGroup(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(77, 0xABCDE, 0x321, 6_001), 5);
        currentPatch.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createTo(201, 0xABCDE, 0x321, 6_002));
        currentPatch.addPatchedTalkgroup(APCO25Talkgroup.create(202));
        IdentifierCollection original = new IdentifierCollection(List.of(APCO25PatchGroup.create(originalPatch)));
        IdentifierCollection stream = new IdentifierCollection(List.of(APCO25PatchGroup.create(currentPatch)));

        var projected = (APCO25PatchGroup)BroadcastIdentifierFormatter.streamIdentifiers(
            observedMetadata(stream, original, "p25:abcde:456"), stream).getToIdentifier();

        assertEquals(5, projected.getValue().getVersion());
        assertEquals("P:77 [201, 202]", BroadcastIdentifierFormatter.format(projected, "p25:abcde:456"));
        assertSame(observedMember, projected.getValue().getPatchedTalkgroupIdentifiers().getFirst());
        assertTrue(currentPatch.getPatchGroup() instanceof APCO25FullyQualifiedTalkgroupIdentifier);
    }

    @Test
    void recordingIdentifierListIsCopiedAndConflictingRawNetworkSuppressesTheServingKey()
    {
        MutableIdentifierCollection original = new MutableIdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(501),
            APCO25Wacn.create(0xABCDE), APCO25System.create(0x321)));
        IdentifierCollection stream = new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(501)));
        CompletedCallBroadcastMetadata metadata = observedMetadata(stream, original, "p25:abcde:456");
        original.remove(Role.FROM);

        assertNull(metadata.servingSystemKey());
        assertEquals(3, metadata.recordingIdentifiers().size());
        assertThrows(UnsupportedOperationException.class, () -> metadata.recordingIdentifiers().clear());
        assertEquals("501", BroadcastIdentifierFormatter.streamIdentifiers(metadata, stream).getFromIdentifier().toString());
    }

    private static CompletedCallBroadcastMetadata observedMetadata(IdentifierCollection stream,
                                                                   IdentifierCollection original, String nativeKey)
    {
        AudioCallSnapshot snapshot = completedMetadata(stream, nativeKey).snapshot();
        CompletedAudioCall oneLeg = new CompletedAudioCall(snapshot, List.of());
        CompletedAudioCall call = new CompletedAudioCall(oneLeg.logicalCallId(), snapshot, List.of(),
            oneLeg.resolvedPolicy(), oneLeg.callLegSummaries(), original);
        return CompletedCallBroadcastMetadata.from(call);
    }

    private AudioRecording recording(IdentifierCollection identifiers, CompletedCallBroadcastMetadata metadata)
        throws Exception
    {
        //Header-only MP3 frames exercise the queue's existing frame splitter without decoding or a network server.
        byte[] header = {(byte)0xFF, (byte)0xE3, 0x18, (byte)0xC0};
        int frameLength = MP3Header.getFrameLength(header, 0);
        byte[] audio = new byte[20 * frameLength];
        for(int offset = 0; offset < audio.length; offset += frameLength)
        {
            System.arraycopy(header, 0, audio, offset, header.length);
        }
        Path path = Files.createTempFile(mDirectory, "queued-", ".mp3");
        Files.write(path, audio);
        return new AudioRecording(path, List.of(), identifiers, System.currentTimeMillis(), 1_440L,
            BroadcastDeliveryEvidence.EMPTY, metadata);
    }

    private static IdentifierCollection network(int wacn, int system)
    {
        return new IdentifierCollection(List.of(APCO25Wacn.create(wacn), APCO25System.create(system)));
    }

    private static CompletedCallBroadcastMetadata completedMetadata(IdentifierCollection identifiers, String nativeKey)
    {
        AudioCallId id = new AudioCallId(1, 1, 0);
        CallLegSource source = new CallLegSource(DecoderType.P25_PHASE1, null, null, null, 0, null,
            TrunkedIdentityDomain.STANDARD, null, true, nativeKey);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(id, null, null, identifiers, Set.of(),
            1_000L, 2_000L, 1, 1, 1_000L, 2_000L, false, true, CallEncryptionState.CLEAR, false,
            null, null, CallLegId.from(id), source, null);
        return new CompletedCallBroadcastMetadata(snapshot, List.of());
    }

    private static final class TestBroadcaster extends AudioStreamingBroadcaster<IcecastHTTPConfiguration>
    {
        private final List<String> mMetadataKeys = new ArrayList<>();
        private final List<IdentifierCollection> mMetadataIdentifiers = new ArrayList<>();
        private final List<String> mAudioKeys = new ArrayList<>();
        private final List<IdentifierCollection> mAudioIdentifiers = new ArrayList<>();

        private TestBroadcaster()
        {
            super(configuration(), InputAudioFormat.SR_8000, MP3Setting.CBR_16);
        }

        private static IcecastHTTPConfiguration configuration()
        {
            IcecastHTTPConfiguration configuration = new IcecastHTTPConfiguration(BroadcastFormat.MP3);
            configuration.setName("Metadata context test");
            configuration.setDelay(0);
            configuration.setMaximumRecordingAge(60_000L);
            return configuration;
        }

        private String servingKey()
        {
            return getInputServingSystemKey();
        }

        @Override
        protected void broadcastAudio(byte[] audio, IdentifierCollection identifiers)
        {
            mAudioKeys.add(getInputServingSystemKey());
            mAudioIdentifiers.add(identifiers);
        }

        @Override
        protected IBroadcastMetadataUpdater getMetadataUpdater()
        {
            return new IBroadcastMetadataUpdater()
            {
                @Override
                public void update(IdentifierCollection identifiers)
                {
                    throw new AssertionError("Streaming must pass the captured serving context");
                }

                @Override
                public void update(IdentifierCollection identifiers, String servingSystemKey)
                {
                    mMetadataIdentifiers.add(identifiers);
                    mMetadataKeys.add(servingSystemKey);
                }
            };
        }

        @Override
        protected void disconnect()
        {
        }
    }
}
