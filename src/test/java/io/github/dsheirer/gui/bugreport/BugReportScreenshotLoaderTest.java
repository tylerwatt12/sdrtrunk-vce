/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BugReportScreenshotLoaderTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void loadsPngScreenshot() throws Exception
    {
        Path screenshot = mTemporaryDirectory.resolve("screen.png");
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "png", screenshot.toFile());

        BufferedImage loaded = BugReportScreenshotLoader.load(screenshot);

        assertEquals(32, loaded.getWidth());
        assertEquals(24, loaded.getHeight());
    }

    @Test
    void rejectsUnsupportedAndOversizedFiles() throws Exception
    {
        Path text = mTemporaryDirectory.resolve("screen.txt");
        Files.writeString(text, "not an image");
        assertThrows(IOException.class, () -> BugReportScreenshotLoader.load(text));

        Path oversized = mTemporaryDirectory.resolve("oversized.png");

        try(SeekableByteChannel channel = Files.newByteChannel(oversized, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE))
        {
            channel.position(BugReportConstants.MAX_SCREENSHOT_SOURCE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }

        assertThrows(IOException.class, () -> BugReportScreenshotLoader.load(oversized));
    }
}
