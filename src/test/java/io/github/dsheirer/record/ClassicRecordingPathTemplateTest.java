/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ClassicRecordingPathTemplateTest
{
    @TempDir Path mTemp;

    @Test
    void starterRetainsTheStandardFilenameIncludingItsMissingMetadataFallback()
    {
        var template = ClassicRecordingPathTemplate.compile(ClassicRecordingPathTemplate.STARTER);
        long timestamp = 1_850_000_000_000L;
        for(RecordFormat format: RecordFormat.values())
        {
            var path = template.resolve(null, null, format, timestamp, 7);
            assertTrue(path.folders().isEmpty());
            assertEquals(BasicRecordingContract.filename(null, format, timestamp, 7, 1), path.filename(""));
        }
        assertEquals("20261109_143522_County_North_Control__TO_234_FROM_4534.mp3", template.preview(RecordFormat.MP3));
    }

    @Test
    void namesStayInOneComponentAndMissingAliasesUseTheTalkgroupId() throws IOException
    {
        var identifiers = new IdentifierCollection(List.of(APCO25Talkgroup.create(234)));
        var aliases = new AliasList(new AliasListDefinition("Synthetic", AliasListFamily.P25));
        Alias alias = new Alias("Fire/Dispatch");
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 234));
        aliases.addAlias(alias);
        var metadata = AudioCallRecordingMetadata.captureAtSnapshot(aliases, identifiers);
        var template = ClassicRecordingPathTemplate.compile("Groups/{talkgroupName}/{talkgroupId}");
        var named = template.resolve(identifiers, metadata, RecordFormat.WAVE, 0, 1);
        assertEquals(mTemp.toRealPath().resolve("Groups/Fire-Dispatch"), named.createDirectoryUnder(mTemp));
        assertEquals("234.wav", named.filename(""));
        var unnamed = template.resolve(identifiers, null, RecordFormat.MP3, 0, 1);
        assertEquals(List.of("Groups", "234"), unnamed.folders());
        var wrongGroup = template.resolve(new IdentifierCollection(List.of(APCO25Talkgroup.create(333))), metadata,
            RecordFormat.MP3, 0, 1);
        assertEquals(List.of("Groups", "333"), wrongGroup.folders());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void refusesAnExistingSymlinkToOutsideTheClassicDirectory() throws IOException
    {
        Path root = Files.createDirectory(mTemp.resolve("recordings"));
        Path outside = Files.createDirectory(mTemp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("link"), outside);
        var path = ClassicRecordingPathTemplate.compile("link/folder/file")
            .resolve(null, null, RecordFormat.MP3, 0, 1);
        assertThrows(IOException.class, () -> path.createDirectoryUnder(root));
        assertFalse(Files.exists(outside.resolve("folder")));
    }

    @Test
    void rejectsUnsafeTemplatesAndRefusesToWriteThroughAFileInTheFolderPath() throws IOException
    {
        for(String invalid: List.of("/absolute/file", "C:/absolute/file", "x/../file", "x//file", "x/", "{missing}",
            "{date", "x\\file"))
        {
            assertThrows(IllegalArgumentException.class, () -> ClassicRecordingPathTemplate.compile(invalid), invalid);
        }
        Files.writeString(mTemp.resolve("existing"), "preserve");
        var path = ClassicRecordingPathTemplate.compile("existing/folder/file")
            .resolve(null, null, RecordFormat.MP3, 0, 1);
        assertThrows(IOException.class, () -> path.createDirectoryUnder(mTemp));
        assertEquals("preserve", Files.readString(mTemp.resolve("existing")));
        assertFalse(Files.exists(mTemp.resolve("existing/folder")));
    }
}
