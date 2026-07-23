/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.record.Record;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteGuidConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedCallRecordingTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void createsTheFixedSafeVersionedHierarchyAndNeverOverwritesACall() throws Exception
    {
        long completedAt = Instant.parse("2026-07-23T18:30:00.123Z").toEpochMilli();
        CompletedAudioCall call = call(completedAt);
        Path first;

        try(ManagedCallRecording recording =
                ManagedCallRecording.prepare(mTemporaryFolder, call, RecordFormat.WAVE))
        {
            Files.write(recording.stagingPath(), new byte[] {1, 2, 3});
            first = recording.commit();
            String relative = recording.relativePath().toString().replace('\\', '/');
            assertTrue(relative.startsWith("calls/v1/2026/07/23/"));
            assertTrue(relative.contains("/_con~"));
            assertTrue(relative.contains("/downtown-north~"));
            assertTrue(relative.contains("/control-one~"));
            assertTrue(relative.contains("/56138~"));
            assertTrue(relative.endsWith(".wav"));
            assertFalse(recording.destinationTalkgroupRecordEnabled());
        }

        assertThrows(IOException.class,
            () -> ManagedCallRecording.prepare(mTemporaryFolder, call, RecordFormat.WAVE));
        assertTrue(Files.exists(first));
    }

    @Test
    void atomicallyReservesTheOnlyCanonicalNameAndNeverOverwritesIt() throws Exception
    {
        CompletedAudioCall call = call(System.currentTimeMillis());

        try(ManagedCallRecording recording =
                ManagedCallRecording.prepare(mTemporaryFolder, call, RecordFormat.WAVE))
        {
            assertThrows(IOException.class,
                () -> ManagedCallRecording.prepare(mTemporaryFolder, call, RecordFormat.WAVE));
            Files.write(recording.stagingPath(), new byte[] {1, 2, 3});
            Files.writeString(recording.finalPath(), "sentinel", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            assertThrows(IOException.class, recording::commit);
            assertEquals("sentinel", Files.readString(recording.finalPath()));
        }
    }

    @Test
    void capturesAliasNameRecordDecisionAndStableIdsAtHandoff() throws Exception
    {
        long completedAt = System.currentTimeMillis();
        Alias alias = new Alias("Original Dispatch");
        alias.addAliasID(new Talkgroup(Protocol.APCO25, 56138));
        alias.addAliasID(new Record());
        AliasList aliasList = new AliasList("test");
        aliasList.addAlias(alias);
        CompletedAudioCall call = call(completedAt, aliasList, APCO25Talkgroup.create(56138));
        ManagedCallRecording.CallPathMetadata captured =
            ManagedCallRecording.CallPathMetadata.capture(call);

        alias.setName("Changed Later");
        alias.setRecordable(false);

        assertEquals("Original Dispatch", captured.destinationAlias());
        assertTrue(captured.destinationTalkgroupRecordEnabled());
        assertEquals("CON", captured.systemIdentity());
        assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", captured.siteIdentity());
        assertEquals("11111111-2222-4333-8444-555555555555", captured.channelIdentity());
    }

    @Test
    void patchGroupUsesARecordEnabledConstituentButNeverARadioOnlyAlias()
    {
        Alias constituent = new Alias("Patched Fire");
        constituent.addAliasID(new Talkgroup(Protocol.APCO25, 56138));
        constituent.addAliasID(new Record());
        AliasList aliasList = new AliasList("test");
        aliasList.addAlias(constituent);
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(900));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(56138));
        CompletedAudioCall call = call(System.currentTimeMillis(), aliasList,
            APCO25PatchGroup.create(patchGroup));
        ManagedCallRecording.CallPathMetadata captured =
            ManagedCallRecording.CallPathMetadata.capture(call);

        assertEquals("900", captured.destinationValue());
        assertEquals("Patched Fire", captured.destinationAlias());
        assertTrue(captured.destinationTalkgroupRecordEnabled());

        Alias radio = new Alias("Patched Radio");
        radio.addAliasID(new Radio(Protocol.APCO25, 123));
        radio.addAliasID(new Record());
        AliasList radioOnly = new AliasList("radio-only");
        radioOnly.addAlias(radio);
        PatchGroup radioPatch = new PatchGroup(APCO25Talkgroup.create(901));
        radioPatch.addPatchedRadio(APCO25RadioIdentifier.createTo(123));
        ManagedCallRecording.CallPathMetadata radioCaptured = ManagedCallRecording.CallPathMetadata.capture(
            call(System.currentTimeMillis(), radioOnly, APCO25PatchGroup.create(radioPatch)));
        assertFalse(radioCaptured.destinationTalkgroupRecordEnabled());
    }

    @Test
    void exactAndRangeMatchersRespectP25DmrAndNxdnProtocols()
    {
        assertRecordMatch(new Talkgroup(Protocol.APCO25, 100), APCO25Talkgroup.create(100), true);
        assertRecordMatch(new TalkgroupRange(Protocol.DMR, 100, 200), DMRTalkgroup.create(150), true);
        assertRecordMatch(new TalkgroupRange(Protocol.NXDN, 100, 200),
            NXDNTalkgroupIdentifier.createTo(150), true);
        assertRecordMatch(new Talkgroup(Protocol.DMR, 150), NXDNTalkgroupIdentifier.createTo(150), false);
    }

    @Test
    void fullyQualifiedTalkgroupsWithTheSameLocalValueKeepDistinctMatcherIdentities()
    {
        CompletedAudioCall first = call(System.currentTimeMillis(), new AliasList("test"),
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(100, 1, 2, 300));
        CompletedAudioCall second = call(System.currentTimeMillis(), new AliasList("test"),
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(100, 9, 8, 300));

        String firstIdentity = ManagedCallRecording.CallPathMetadata.capture(first).destinationIdentity();
        String secondIdentity = ManagedCallRecording.CallPathMetadata.capture(second).destinationIdentity();
        assertNotEquals(firstIdentity, secondIdentity);
    }

    @Test
    void removesAnUncommittedStagingFile() throws Exception
    {
        Path staging;

        try(ManagedCallRecording recording =
                ManagedCallRecording.prepare(mTemporaryFolder, call(System.currentTimeMillis()), RecordFormat.WAVE))
        {
            staging = recording.stagingPath();
            Files.write(staging, new byte[] {1});
            assertTrue(Files.exists(staging));
        }

        assertFalse(Files.exists(staging));
    }

    @Test
    void rejectsASymbolicLinkInsideTheManagedTree() throws Exception
    {
        Path outside = Files.createDirectory(mTemporaryFolder.resolve("outside"));

        try
        {
            Files.createSymbolicLink(mTemporaryFolder.resolve("calls"), outside);
        }
        catch(UnsupportedOperationException | IOException | SecurityException exception)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable for this filesystem");
            return;
        }

        assertThrows(IOException.class,
            () -> ManagedCallRecording.prepare(mTemporaryFolder, call(System.currentTimeMillis()), RecordFormat.WAVE));
    }

    private static CompletedAudioCall call(long completedAt)
    {
        return call(completedAt, new AliasList("test"), APCO25Talkgroup.create(56138));
    }

    private static void assertRecordMatch(AliasID matcher, io.github.dsheirer.identifier.Identifier<?> destination,
                                          boolean expected)
    {
        Alias alias = new Alias("Matcher");
        alias.addAliasID(matcher);
        alias.addAliasID(new Record());
        AliasList aliasList = new AliasList("test");
        aliasList.addAlias(alias);
        ManagedCallRecording.CallPathMetadata captured = ManagedCallRecording.CallPathMetadata.capture(
            call(System.currentTimeMillis(), aliasList, destination));
        assertEquals(expected, captured.destinationTalkgroupRecordEnabled());
    }

    private static CompletedAudioCall call(long completedAt, AliasList aliasList,
                                           io.github.dsheirer.identifier.Identifier<?> destination)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(SystemConfigurationIdentifier.create("CON"));
        identifiers.update(SiteConfigurationIdentifier.create("Downtown / North"));
        identifiers.update(SiteGuidConfigurationIdentifier.create("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));
        identifiers.update(ChannelNameConfigurationIdentifier.create("Control: One."));
        identifiers.update(ChannelConfigurationIdentifier.create("11111111-2222-4333-8444-555555555555"));
        identifiers.update(destination);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(10, 20, 1), null,
            aliasList, identifiers, Set.of(), completedAt - 100, completedAt, 1, 1,
            completedAt - 100, completedAt, false, true, false, true, 100, false);
        return new CompletedAudioCall(snapshot, List.of(new float[800]));
    }
}
