/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Loads a bounded PNG or JPEG screenshot. Images are later re-encoded as PNG so source metadata is not retained.
 */
final class BugReportScreenshotLoader
{
    private BugReportScreenshotLoader()
    {
    }

    static BufferedImage load(Path path) throws IOException
    {
        if(path == null || !Files.isRegularFile(path))
        {
            throw new IOException("The selected screenshot is not a regular file.");
        }

        long sourceBytes = Files.size(path);

        if(sourceBytes <= 0 || sourceBytes > BugReportConstants.MAX_SCREENSHOT_SOURCE_BYTES)
        {
            throw new IOException("Screenshots must be no larger than 15 MB each.");
        }

        try(ImageInputStream input = ImageIO.createImageInputStream(path.toFile()))
        {
            if(input == null)
            {
                throw new IOException("The selected screenshot could not be read.");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);

            if(!readers.hasNext())
            {
                throw new IOException("The selected file is not a supported PNG or JPEG image.");
            }

            ImageReader reader = readers.next();

            try
            {
                String format = reader.getFormatName();

                if(!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg") &&
                    !format.equalsIgnoreCase("jpg"))
                {
                    throw new IOException("Only PNG and JPEG screenshots are supported.");
                }

                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long)width * height;

                if(width <= 0 || height <= 0 || width > BugReportConstants.MAX_SCREENSHOT_DIMENSION ||
                    height > BugReportConstants.MAX_SCREENSHOT_DIMENSION ||
                    pixels > BugReportConstants.MAX_SCREENSHOT_PIXELS)
                {
                    throw new IOException("The screenshot dimensions are too large.");
                }

                BufferedImage image = reader.read(0);

                if(image == null)
                {
                    throw new IOException("The selected screenshot could not be decoded.");
                }

                return image;
            }
            finally
            {
                reader.dispose();
            }
        }
    }
}
