/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingPathTest
{
    private static final Path VALID_RECORDING = Path.of("calls", "v1", "2026", "07", "23",
        "metro~0123456789ab", "_conventional", "control~abcdef012345",
        "56138-dispatch~111111111111", "20260723T183000.123Z-a-k-1.wav");

    @TempDir
    Path mTemporaryFolder;

    @Test
    void parsesOnlyTheExactVersionedLayout()
    {
        ManagedRecordingPath parsed = ManagedRecordingPath.parse(VALID_RECORDING).orElseThrow();

        assertEquals(VALID_RECORDING, parsed.relativePath());
        assertEquals(VALID_RECORDING.getParent(), parsed.relativeDirectory());
        assertEquals(LocalDate.of(2026, 7, 23), parsed.date());
        assertEquals(Instant.parse("2026-07-23T18:30:00.123Z"), parsed.completedAt());
        assertEquals("metro~0123456789ab", parsed.systemSegment());
        assertEquals("_conventional", parsed.siteSegment());
        assertEquals("control~abcdef012345", parsed.channelSegment());
        assertEquals("56138-dispatch~111111111111", parsed.talkgroupSegment());
        assertEquals("a-k-1", parsed.callIdentity());
        assertEquals(RecordFormat.WAVE, parsed.format());

        Path unknownSite = replace(VALID_RECORDING, 6, "_unknown");
        assertTrue(ManagedRecordingPath.parse(unknownSite).isPresent());
        Path unknownCall = replace(VALID_RECORDING, 9,
            "20260723T183000.123Z-unknown-0123456789ab.mp3");
        assertEquals(RecordFormat.MP3, ManagedRecordingPath.parse(unknownCall).orElseThrow().format());
    }

    @Test
    void rejectsPathsThatTheVersionOneWriterCannotOwn()
    {
        List<Path> invalid = List.of(
            Path.of("calls", "v2").resolve(VALID_RECORDING.subpath(2, VALID_RECORDING.getNameCount())),
            VALID_RECORDING.subpath(0, VALID_RECORDING.getNameCount() - 1),
            VALID_RECORDING.resolve("extra"),
            replace(VALID_RECORDING, 3, "13"),
            replace(VALID_RECORDING, 4, "32"),
            replace(replace(VALID_RECORDING, 3, "02"), 4, "30"),
            replace(VALID_RECORDING, 5, "Metro~0123456789ab"),
            replace(VALID_RECORDING, 5, "metro~0123456789AB"),
            replace(VALID_RECORDING, 6, "conventional"),
            replace(VALID_RECORDING, 9, "20260724T183000.123Z-a-k-1.wav"),
            replace(VALID_RECORDING, 9, "20260723T183000.123Z-01-k-1.wav"),
            replace(VALID_RECORDING, 9, "20260723T183000.123Z-a-k-1.WAV"),
            replace(VALID_RECORDING, 9, "20260723T183000.123Z-a-k-1.flac"),
            Path.of("calls", "v1", "2026", "07", "23", "..", "23",
                "metro~0123456789ab", "_conventional", "control~abcdef012345",
                "56138-dispatch~111111111111", "20260723T183000.123Z-a-k-1.wav"));

        for(Path path : invalid)
        {
            assertFalse(ManagedRecordingPath.parse(path).isPresent(), path.toString());
        }

        assertFalse(ManagedRecordingPath.parse(VALID_RECORDING.toAbsolutePath()).isPresent());
        assertFalse(ManagedRecordingPath.parse(null).isPresent());
    }

    @Test
    void inspectsOnlyANonEmptyRealFileWithoutFollowingLinks() throws Exception
    {
        Path recording = mTemporaryFolder.resolve(VALID_RECORDING);
        Files.createDirectories(recording.getParent());
        Files.write(recording, new byte[] {1, 2, 3});

        assertTrue(ManagedRecordingPath.inspect(mTemporaryFolder, recording).isPresent());

        Files.write(recording, new byte[0]);
        assertFalse(ManagedRecordingPath.inspect(mTemporaryFolder, recording).isPresent());
        Files.write(recording, new byte[] {1});

        Path outside = Files.write(mTemporaryFolder.resolve("outside.wav"), new byte[] {1});
        Path linkedRelative = replace(VALID_RECORDING, 9, "20260723T183000.123Z-a-k-2.wav");
        Path link = mTemporaryFolder.resolve(linkedRelative);

        try
        {
            Files.createSymbolicLink(link, outside);
        }
        catch(UnsupportedOperationException | IOException | SecurityException exception)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable for this filesystem");
            return;
        }

        assertFalse(ManagedRecordingPath.inspect(mTemporaryFolder, link.toAbsolutePath()).isPresent());
    }

    @Test
    void acceptsTheSameRootThroughAPlatformDirectoryAlias() throws Exception
    {
        Path realRoot = Files.createDirectory(mTemporaryFolder.resolve("real"));
        Path aliasRoot = mTemporaryFolder.resolve("alias");

        try
        {
            Files.createSymbolicLink(aliasRoot, realRoot);
        }
        catch(UnsupportedOperationException | IOException | SecurityException exception)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable for this filesystem");
            return;
        }

        Path recording = realRoot.resolve(VALID_RECORDING);
        Files.createDirectories(recording.getParent());
        Files.write(recording, new byte[] {1});
        assertTrue(ManagedRecordingPath.inspect(aliasRoot, recording).isPresent());
    }

    @Test
    void recognizesOnlyExactWriterReservationsAndWorkNames()
    {
        Path reservation = VALID_RECORDING.getParent().resolve('.' +
            VALID_RECORDING.getFileName().toString() + ".reserve");

        assertEquals(VALID_RECORDING,
            ManagedRecordingPath.parseReservation(reservation).orElseThrow().relativePath());
        assertFalse(ManagedRecordingPath.parseReservation(
            reservation.resolveSibling(reservation.getFileName() + ".old")).isPresent());
        assertFalse(ManagedRecordingPath.parseReservation(VALID_RECORDING).isPresent());

        assertTrue(ManagedRecordingPath.isWorkDirectoryName(
            ".recording-12345678-1234-4abc-8def-123456789abc.work"));
        assertFalse(ManagedRecordingPath.isWorkDirectoryName(
            ".recording-12345678-1234-0abc-8def-123456789abc.work"));
        assertFalse(ManagedRecordingPath.isWorkDirectoryName(".recording-anything.work"));
        assertTrue(ManagedRecordingPath.isStagingFileName("audio.tmp"));
        assertFalse(ManagedRecordingPath.isStagingFileName("audio.tmp.old"));
    }

    private static Path replace(Path path, int index, String replacement)
    {
        Path result = Path.of(path.getName(0).toString());

        for(int current = 1; current < path.getNameCount(); current++)
        {
            result = result.resolve(current == index ? replacement : path.getName(current).toString());
        }

        return result;
    }
}
