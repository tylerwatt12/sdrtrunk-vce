/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner.recording;

import io.github.dsheirer.source.wave.ComplexWaveSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingTunerFileCatalogTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void rescansSparseFourGigabyteFileUsingOnlyItsHeader() throws Exception
    {
        Path folder = mTemporaryDirectory.resolve("recording_tuners");
        Files.createDirectory(folder);
        Path recording = folder.resolve("capture_851012500_baseband_20260927_120000.wav");
        createWave(recording, 4_000_000_000L, 2_400_000);
        RecordingTunerFileCatalog catalog = new RecordingTunerFileCatalog(folder);

        assertTrue(catalog.snapshot().entries().isEmpty());
        RecordingTunerFileCatalog.ScanResult result = catalog.rescan();
        assertEquals(1, result.entries().size());
        RecordingTunerFileCatalog.Entry entry = result.entries().getFirst();
        assertEquals(4_000_000_000L, entry.sizeBytes());
        assertEquals(2_400_000, entry.sampleRateHz());
        assertEquals(851_012_500L, entry.suggestedCenterFrequencyHz());
        assertFalse(entry.id().contains(recording.getFileName().toString()));
        assertEquals(recording, catalog.resolve(entry.id()));
        assertTrue(ComplexWaveSource.supports(recording.toFile()), "Existing playback must accept indexed files");
        assertEquals(entry.id(), catalog.rescan().entries().getFirst().id());
    }

    @Test
    void ignoresNestedFilesAndRejectsSymlinksAndUnsupportedWaveHeaders() throws Exception
    {
        Path folder = mTemporaryDirectory.resolve("recording_tuners");
        Files.createDirectory(folder);
        Path outside = mTemporaryDirectory.resolve("outside.wav");
        createWave(outside, 48, 2_400_000);
        Files.createSymbolicLink(folder.resolve("linked.wav"), outside);
        Path nested = folder.resolve("nested");
        Files.createDirectory(nested);
        createWave(nested.resolve("nested.wav"), 48, 2_400_000);
        Path invalid = folder.resolve("invalid.wav");
        createWave(invalid, 48, 2_400_000);
        try(FileChannel channel = FileChannel.open(invalid, StandardOpenOption.WRITE))
        {
            channel.write(ByteBuffer.wrap(new byte[]{1}), 22); // Mono instead of stereo I/Q.
        }

        RecordingTunerFileCatalog.ScanResult result = new RecordingTunerFileCatalog(folder).rescan();
        assertTrue(result.entries().isEmpty());
        assertEquals(2, result.rejectedCount());
    }

    @Test
    void requiresRescanWhenASelectedFileChangesOrIsReplacedBySymlink() throws Exception
    {
        Path folder = mTemporaryDirectory.resolve("recording_tuners");
        Files.createDirectory(folder);
        Path recording = folder.resolve("first.wav");
        createWave(recording, 48, 2_400_000);
        RecordingTunerFileCatalog catalog = new RecordingTunerFileCatalog(folder);
        String id = catalog.rescan().entries().getFirst().id();

        createWave(recording, 52, 2_400_000);
        assertThrows(IOException.class, () -> catalog.resolve(id));
        String replacementId = catalog.rescan().entries().getFirst().id();
        assertNotEquals(id, replacementId);

        Files.delete(recording);
        Path outside = mTemporaryDirectory.resolve("outside.wav");
        createWave(outside, 52, 2_400_000);
        Files.createSymbolicLink(recording, outside);
        assertThrows(IOException.class, () -> catalog.resolve(replacementId));
    }

    @Test
    void refusesSymbolicLinkDirectoryAndUnknownFileIds() throws Exception
    {
        Path actual = mTemporaryDirectory.resolve("actual");
        Files.createDirectory(actual);
        Path alias = mTemporaryDirectory.resolve("recording_tuners");
        Files.createSymbolicLink(alias, actual);
        assertThrows(IOException.class, () -> new RecordingTunerFileCatalog(alias).rescan());

        RecordingTunerFileCatalog catalog = new RecordingTunerFileCatalog(actual);
        assertThrows(IllegalArgumentException.class, () -> catalog.resolve("../outside.wav"));
    }

    @Test
    void boundsTheInMemoryIndexWhenTheDirectoryContainsManyRecordings() throws Exception
    {
        Path folder = mTemporaryDirectory.resolve("recording_tuners");
        Files.createDirectory(folder);
        for(int index = 0; index < RecordingTunerFileCatalog.MAXIMUM_FILES + 2; index++)
        {
            createWave(folder.resolve("recording-" + index + ".wav"), 48, 2_400_000);
        }

        RecordingTunerFileCatalog.ScanResult result = new RecordingTunerFileCatalog(folder).rescan();
        assertEquals(RecordingTunerFileCatalog.MAXIMUM_FILES, result.entries().size());
        assertTrue(result.truncated());
    }

    private static void createWave(Path path, long fileLength, int sampleRate) throws IOException
    {
        if(fileLength < 48 || (fileLength - 44) % 4 != 0)
        {
            throw new IllegalArgumentException("Test WAV length must contain whole I/Q frames");
        }
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt((int)(fileLength - 8));
        header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short)1);
        header.putShort((short)2);
        header.putInt(sampleRate);
        header.putInt(sampleRate * 4);
        header.putShort((short)4);
        header.putShort((short)16);
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt((int)(fileLength - 44));
        header.flip();
        try(FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE))
        {
            while(header.hasRemaining())
            {
                channel.write(header);
            }
            channel.write(ByteBuffer.wrap(new byte[]{0}), fileLength - 1);
        }
    }
}
