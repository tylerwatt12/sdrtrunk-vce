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
package io.github.dsheirer.source.wave;

import io.github.dsheirer.source.IFrameLocationListener;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComplexWaveSourceLargeRecordingTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void frameLocationRemainsPositiveAfterTwoGigabytesAndResetsForReplay() throws Exception
    {
        Path recording = mTemporaryDirectory.resolve("large-recording-boundary.wav");
        createWave(recording);
        List<Integer> locations = new ArrayList<>();
        try(ComplexWaveSource source = new ComplexWaveSource(recording.toFile()))
        {
            source.setListener(new IFrameLocationListener()
            {
                @Override
                public void frameLocationUpdated(int location)
                {
                    locations.add(location);
                }

                @Override
                public void frameLocationReset()
                {
                }
            });
            source.open();

            // Simulate reaching the int byte-offset boundary without reading 2 GB during the test.
            Field counter = ComplexWaveSource.class.getDeclaredField("mFrameCounter");
            counter.setAccessible(true);
            counter.setLong(source, Integer.MAX_VALUE - 3L);
            source.next(4, false);
            assertEquals(536_870_915, locations.getLast());
            assertEquals(2_147_483_660L, counter.getLong(source));

            assertThrows(IOException.class, () -> source.next(4, false));
            assertEquals(536_870_915, locations.getLast(), "EOF must not move the location backwards");

            source.reset();
            assertEquals(0, locations.getLast());
            source.next(4, false);
            assertEquals(4, locations.getLast());
        }
    }

    private static void createWave(Path path) throws IOException
    {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(52);
        header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short)1);
        header.putShort((short)2);
        header.putInt(2_400_000);
        header.putInt(9_600_000);
        header.putShort((short)4);
        header.putShort((short)16);
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16);
        header.flip();
        try(FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        {
            while(header.hasRemaining())
            {
                channel.write(header);
            }
            channel.write(ByteBuffer.allocate(16));
        }
    }
}
