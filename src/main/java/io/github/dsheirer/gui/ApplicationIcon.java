/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.gui;

import java.awt.GraphicsEnvironment;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and applies the application icon for Swing, JavaFX, and supported desktop taskbars.
 */
public final class ApplicationIcon
{
    private static final Logger mLog = LoggerFactory.getLogger(ApplicationIcon.class);
    private static final String ICON_RESOURCE = "/images/app/sdr-trunk-icon.png";
    private static BufferedImage sBufferedImage;
    private static Image sFxImage;

    private ApplicationIcon()
    {
    }

    public static void apply(JFrame frame)
    {
        BufferedImage image = getBufferedImage();

        if(frame != null && image != null)
        {
            frame.setIconImage(image);
        }
    }

    public static void apply(Stage stage)
    {
        Image image = getFxImage();

        if(stage != null && image != null && !stage.getIcons().contains(image))
        {
            stage.getIcons().add(image);
        }
    }

    public static void applyTaskbarIcon()
    {
        if(GraphicsEnvironment.isHeadless())
        {
            return;
        }

        BufferedImage image = getBufferedImage();

        if(image == null || !Taskbar.isTaskbarSupported())
        {
            return;
        }

        try
        {
            Taskbar taskbar = Taskbar.getTaskbar();

            if(taskbar.isSupported(Taskbar.Feature.ICON_IMAGE))
            {
                taskbar.setIconImage(image);
            }
        }
        catch(UnsupportedOperationException | SecurityException e)
        {
            mLog.debug("Unable to set desktop taskbar icon", e);
        }
    }

    private static BufferedImage getBufferedImage()
    {
        if(sBufferedImage == null)
        {
            try
            {
                URL resource = ApplicationIcon.class.getResource(ICON_RESOURCE);

                if(resource == null)
                {
                    mLog.warn("Application icon resource not found: {}", ICON_RESOURCE);
                    return null;
                }

                sBufferedImage = ImageIO.read(resource);
            }
            catch(IOException ioe)
            {
                mLog.error("Unable to load application icon resource: {}", ICON_RESOURCE, ioe);
            }
        }

        return sBufferedImage;
    }

    private static Image getFxImage()
    {
        if(sFxImage == null)
        {
            URL resource = ApplicationIcon.class.getResource(ICON_RESOURCE);

            if(resource == null)
            {
                mLog.warn("Application icon resource not found: {}", ICON_RESOURCE);
                return null;
            }

            sFxImage = new Image(resource.toExternalForm());
        }

        return sFxImage;
    }
}
