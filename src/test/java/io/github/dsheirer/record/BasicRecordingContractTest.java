/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.P25ToneIdentifier;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Frozen external recording contract from DSheirer/sdrtrunk a9120ed65e5b9f91cf460544efdab4863d7e4b45. */
class BasicRecordingContractTest
{
    private static final long WRITER_TIME = LocalDateTime.of(2026, 10, 7, 12, 34, 56)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

    @TempDir Path mDirectory;

    @Test
    void plainProtocolsAndNxdnSpecialAddressesRetainTheirUpstreamRepresentation()
    {
        assertEquals("70001", BasicRecordingContract.sourceIdentifier(APCO25RadioIdentifier.createFrom(70001)));
        assertEquals("70001", BasicRecordingContract.sourceIdentifier(DMRRadio.createFrom(70001)));
        assertEquals("7001", BasicRecordingContract.sourceIdentifier(NXDNRadioIdentifier.createFrom(7001)));
        assertEquals("03-0025", BasicRecordingContract.sourceIdentifier(NXDNRadioIdentifier.createTypeDFrom((3 << 11) | 25)));
        assertEquals("0xFFF0 TRUNKING CONTROLLER", BasicRecordingContract.sourceIdentifier(NXDNRadioIdentifier.createFrom(0xFFF0)));
    }

    @Test
    void qualifiedRadioDecimalLocalAddressAndSourceTargetAsymmetryStayFrozen()
    {
        var differing = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xBEE00, 0x687, 70001);
        assertEquals("501(781824.1671.70001)", BasicRecordingContract.sourceIdentifier(differing));
        assertEquals("ROAM 501(781824.1671.70001)", BasicRecordingContract.targetIdentifier(differing));
        assertEquals("501_781824_1671_70001", BasicRecordingContract.filenameIdentifier(differing));
        assertEquals("781824.1671.70001", BasicRecordingContract.sourceIdentifier(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(70001, 0xBEE00, 0x687, 70001)));
        assertEquals("781824.1671.70001", BasicRecordingContract.sourceIdentifier(
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x687, 70001)));
        //The legacy export predicate is numeric inequality, including canonical-only constructor callers.
        assertEquals("501(781824.1671.70001)", BasicRecordingContract.sourceIdentifier(
            APCO25FullyQualifiedRadioIdentifier.createFrom(501, 0xBEE00, 0x687, 70001)));
    }

    @Test
    void qualifiedTalkgroupAndNestedPatchMembersUseTheSameLegacyProjection()
    {
        var primary = APCO25FullyQualifiedTalkgroupIdentifier.createTo(77, 0xBEE00, 0x687, 1201);
        var patch = new PatchGroup(primary);
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(1202));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xBEE00, 0x687, 70001));
        var identifier = APCO25PatchGroup.create(patch);

        assertEquals("77(781824.1671.1201)", BasicRecordingContract.targetIdentifier(primary));
        assertEquals("P:77(781824.1671.1201) [1202] [ROAM 501(781824.1671.70001)]",
            BasicRecordingContract.targetIdentifier(identifier));
        assertEquals("P77_781824_1671_1201 [1202] [501_781824_1671_70001]",
            BasicRecordingContract.filenameIdentifier(identifier));
    }

    @Test
    void basicFilenameTimestampFieldsAndDuplicateSuffixesStayFrozen() throws Exception
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            SystemConfigurationIdentifier.create("SyntheticSystem"), SiteConfigurationIdentifier.create("Site1"),
            ChannelNameConfigurationIdentifier.create("Channel1"), APCO25Talkgroup.create(1201),
            APCO25RadioIdentifier.createFrom(70001)));
        assertEquals("20261007_123456_SyntheticSystem_Site1_Channel1__TO_1201_FROM_70001.mp3",
            BasicRecordingContract.filename(identifiers, RecordFormat.MP3, WRITER_TIME, 1, 1));
        Path first = BasicRecordingContract.nextPath(mDirectory, identifiers, RecordFormat.MP3, WRITER_TIME, 1);
        Files.writeString(first, "existing first recording");
        Path second = BasicRecordingContract.nextPath(mDirectory, identifiers, RecordFormat.MP3, WRITER_TIME, 1);
        assertEquals("20261007_123456_SyntheticSystem_Site1_Channel1__TO_1201_FROM_70001_V2.mp3", second.getFileName().toString());
        Files.writeString(second, "existing second recording");
        assertEquals("20261007_123456_SyntheticSystem_Site1_Channel1__TO_1201_FROM_70001_V3.mp3",
            BasicRecordingContract.nextPath(mDirectory, identifiers, RecordFormat.MP3, WRITER_TIME, 1).getFileName().toString());
        assertEquals("existing first recording", Files.readString(first));
        assertEquals("existing second recording", Files.readString(second));
    }

    @Test
    void filenameSelectionRetainsTalkgroupBeforePatchAndRadioBeforeTone()
    {
        var tone = P25ToneIdentifier.create(new ToneSequence(List.of(new Tone(AmbeTone.DTMF_1))));
        IdentifierCollection identifiers = new IdentifierCollection(List.of(APCO25PatchGroup.create(1203),
            APCO25RadioIdentifier.createTo(70002), tone, APCO25Talkgroup.create(1201),
            APCO25RadioIdentifier.createFrom(70001)));
        assertEquals("20261007_123456__TO_1201_FROM_70001_TONES_DTMF_1.wav",
            BasicRecordingContract.filename(identifiers, RecordFormat.WAVE, WRITER_TIME, 1, 1));
        assertEquals("20261007_123456__TO_P1203.wav", BasicRecordingContract.filename(
            new IdentifierCollection(List.of(APCO25PatchGroup.create(1203))), RecordFormat.WAVE, WRITER_TIME, 1, 1));
    }

    @Test
    void unknownSanitizedAndLongFilenamesRemainImportable()
    {
        assertEquals("20261007_123456_audio_recording_no_metadata_9.wav",
            BasicRecordingContract.filename(null, RecordFormat.WAVE, WRITER_TIME, 9, 1));
        assertEquals("20261007_123456_Sys-Name__TO_1201.mp3", BasicRecordingContract.filename(
            new IdentifierCollection(List.of(SystemConfigurationIdentifier.create("Sys/Name"), APCO25Talkgroup.create(1201))),
            RecordFormat.MP3, WRITER_TIME, 1, 1));
        IdentifierCollection longName = new IdentifierCollection(List.of(SystemConfigurationIdentifier.create("S".repeat(300))));
        String first = BasicRecordingContract.filename(longName, RecordFormat.MP3, WRITER_TIME, 1, 1);
        String tenth = BasicRecordingContract.filename(longName, RecordFormat.MP3, WRITER_TIME, 1, 10);
        assertEquals(252, first.length());
        assertEquals(255, tenth.length());
        assertTrue(tenth.endsWith("_V10.mp3"));
        assertFalse(first.contains("Working_ID"));
    }

    @Test
    void composerUsesUpstreamNameAndManifestVersionRules()
    {
        assertEquals("sdrtrunk", BasicRecordingContract.composer(null, null));
        assertEquals("sdrtrunk v", BasicRecordingContract.composer("", null));
        assertEquals("sdrtrunk v0.6.1", BasicRecordingContract.composer("0.6.1", null));
        assertEquals("sdrtrunk vnightly", BasicRecordingContract.composer("nightly", null));
        assertEquals("sdrtrunk nightly - 2026-10-07T12:34:56.000+0000",
            BasicRecordingContract.composer("nightly", "2026-10-07T12:34:56.000+0000"));
    }
}
