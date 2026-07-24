/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.spectrum;

import io.github.dsheirer.settings.ColorSetting;
import io.github.dsheirer.settings.ColorSetting.ColorSettingName;
import io.github.dsheirer.settings.Setting;
import io.github.dsheirer.settings.SettingChangeListener;
import io.github.dsheirer.settings.SettingsManager;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.text.DecimalFormat;
import java.util.Arrays;
import javax.swing.JPanel;
import org.apache.commons.math3.util.FastMath;

public class WaterfallPanel extends JPanel implements DFTResultsListener,
    Pausable,
    SettingChangeListener
{
    private static final long serialVersionUID = 1L;

    private static DecimalFormat CURSOR_FORMAT = new DecimalFormat("0.00000");
    private static final String PAUSED = "PAUSED - Right Click to Unpause";
    private static final String DISABLED = "DISABLED - Right Click to Select a Tuner";

    private byte[] mPixels;
    private byte[] mPausedPixels;
    private int mDFTSize = 4096;
    private int mImageHeight = 700;
    private transient IndexColorModel mColorModel = WaterfallColorModel.getDefaultColorModel();
    private Color mColorSpectrumCursor;
    private transient BufferedImage mWaterfallImage;
    private boolean mWaterfallImageDirty = true;

    private Point mCursorLocation = new Point(0, 0);
    private boolean mCursorVisible = false;
    private long mCursorFrequency = 0;
    private boolean mPaused = false;
    private boolean mDisabled = true;
    private int mZoom = 0;
    private int mDFTZoomWindowOffset = 0;
    private int mNewestPixelRow = 0;
    private int mPausedNewestPixelRow = 0;

    private SettingsManager mSettingsManager;

    /**
     * Displays a scrolling window of multiple DFT frequency bin outputs over
     * time.  Maps DFT frequency bin decibel values into a 256 bucket color map
     * for display.
     *
     * @param settingsManager
     */
    public WaterfallPanel(SettingsManager settingsManager)
    {
        super();
        mSettingsManager = settingsManager;
        mSettingsManager.addListener(this);
        mColorSpectrumCursor = getColor(ColorSettingName.SPECTRUM_CURSOR);
        reset();
    }

    /**
     * Prepares this instance for disposal
     */
    public void dispose()
    {
        if(mSettingsManager != null)
        {
            mSettingsManager.removeListener(this);
        }

        mSettingsManager = null;

        if(mWaterfallImage != null)
        {
            mWaterfallImage.flush();
            mWaterfallImage = null;
        }

        mPixels = null;
        mPausedPixels = null;
    }

    /**
     * Resets the full-resolution history and display image when the DFT point size changes.
     */
    private void reset()
    {
        mPixels = new byte[mDFTSize * mImageHeight];
        mNewestPixelRow = 0;
        mWaterfallImageDirty = true;

        if(mPaused)
        {
            mPausedPixels = mPixels.clone();
            mPausedNewestPixelRow = mNewestPixelRow;
        }

        if(mWaterfallImage != null)
        {
            mWaterfallImage.flush();
            mWaterfallImage = null;
        }

        repaint();
    }

    /**
     * Pausable interface - pauses updates to the waterfall
     */
    public void setPaused(boolean paused)
    {
        if(paused && !mPaused)
        {
            mPausedPixels = mPixels.clone();
            mPausedNewestPixelRow = mNewestPixelRow;
        }

        mPaused = paused;
        mWaterfallImageDirty = true;

        repaint();
    }

    /**
     * Returns current pause state
     *
     * @return true if paused, false otherwise
     */
    public boolean isPaused()
    {
        return mPaused;
    }

    /**
     * Indicates if the waterfall is currently disabled.
     */
    public boolean isDisabled()
    {
        return mDisabled;
    }

    /**
     * Sets the current zoom level (2^zoom)
     *
     * 0 	No Zoom
     * 1	2x Zoom
     * 2	4x Zoom
     * 3	8x Zoom
     * 4	16x Zoom
     * 5	32x Zoom
     * 6	64x Zoom
     *
     * @param zoom level, 0 - 6.
     */
    public void setZoom(int zoom)
    {
        mZoom = zoom;
        mWaterfallImageDirty = true;
        repaint();
    }

    /**
     * Multiplier for the current zoom level
     */
    private int getZoomMultiplier()
    {
        return (int) FastMath.pow(2.0, mZoom);
    }

    /**
     * Sets the zoom window offset from zero
     *
     * @param offset in DFT bins
     */
    public void setZoomWindowOffset(int offset)
    {
        mDFTZoomWindowOffset = offset;
        mWaterfallImageDirty = true;
        repaint();
    }

    /**
     * Fetches a named color setting from the settings manager.  If the setting
     * doesn't exist, creates the setting using the defaultColor
     */
    private Color getColor(ColorSettingName name)
    {
        ColorSetting setting = mSettingsManager.getColorSetting(name);

        return setting.getColor();
    }

    /**
     * Monitors for setting changes.  Colors can be changed by external actions
     * and will automatically update in this class
     */
    @Override
    public void settingChanged(Setting setting)
    {
        if(setting instanceof ColorSetting colorSetting &&
            colorSetting.getColorSettingName() == ColorSettingName.SPECTRUM_CURSOR)
        {
            mColorSpectrumCursor = colorSetting.getColor();
        }
    }

    @Override
    public void settingDeleted(Setting setting)
    { /* Not implemented */ }

    /**
     * Sets the display location of the cursor.  Cursor location monitoring is
     * handled external to this class.
     *
     * @param point
     */
    public void setCursorLocation(Point point)
    {
        mCursorLocation = point;
        repaint();
    }

    /**
     * Sets the current cursor display frequency.  Cursor location frequency
     * monitoring is handled external to this class.
     *
     * @param frequency
     */
    public void setCursorFrequency(long frequency)
    {
        mCursorFrequency = frequency;
    }

    /**
     * Toggles the visibility of the cursor
     *
     * @param visible
     */
    public void setCursorVisible(boolean visible)
    {
        mCursorVisible = visible;
        repaint();
    }

    /**
     * Renders the screen at each refresh
     */
    @Override
    public void paintComponent(Graphics g)
    {
        super.paintComponent(g);

        prepareWaterfallImage();

        if(mWaterfallImage != null)
        {
            int newestRow = mPaused ? mPausedNewestPixelRow : mNewestPixelRow;
            int topRowCount = mImageHeight - newestRow;

            g.drawImage(mWaterfallImage, 0, 0, getWidth(), topRowCount, 0, newestRow, mWaterfallImage.getWidth(),
                mImageHeight, this);

            if(newestRow > 0)
            {
                g.drawImage(mWaterfallImage, 0, topRowCount, getWidth(), mImageHeight, 0, 0,
                    mWaterfallImage.getWidth(), newestRow, this);
            }
        }

        Graphics2D graphics = (Graphics2D)g;
        graphics.setColor(mColorSpectrumCursor);

        if(mCursorVisible)
        {
            graphics.draw(new Line2D.Float(mCursorLocation.x, 0, mCursorLocation.x, (float)(getSize().getHeight())));
            String frequency = CURSOR_FORMAT.format(mCursorFrequency / 1000000.0D);
            graphics.drawString(frequency, mCursorLocation.x + 5, mCursorLocation.y);
        }

        if(mDisabled)
        {
            graphics.drawString(DISABLED, 20, 20);
        }
        else if(mPaused)
        {
            graphics.drawString(PAUSED, 20, 20);
        }

        paintZoomIndicator(graphics);
        graphics.dispose();
    }

    private void prepareWaterfallImage()
    {
        int imageWidth = getWidth();

        if(imageWidth <= 0)
        {
            return;
        }

        if(mWaterfallImage == null || mWaterfallImage.getWidth() != imageWidth)
        {
            if(mWaterfallImage != null)
            {
                mWaterfallImage.flush();
            }

            mWaterfallImage = new BufferedImage(imageWidth, mImageHeight, BufferedImage.TYPE_BYTE_INDEXED, mColorModel);
            mWaterfallImageDirty = true;
        }

        if(mWaterfallImageDirty)
        {
            byte[] sourcePixels = mPaused ? mPausedPixels : mPixels;
            byte[] displayPixels = new byte[imageWidth * mImageHeight];

            for(int row = 0; row < mImageHeight; row++)
            {
                renderDisplayRow(sourcePixels, row * mDFTSize, mDFTSize, getZoomMultiplier(),
                    mDFTZoomWindowOffset, displayPixels, row * imageWidth, imageWidth);
            }

            mWaterfallImage.getRaster().setDataElements(0, 0, imageWidth, mImageHeight, displayPixels);
            mWaterfallImageDirty = false;
        }
    }

    /**
     * Maps a full-resolution history row into the visible display width.  When multiple FFT bins map to the same
     * display pixel, retains the strongest bin so that narrow signals remain visible.
     */
    static void renderDisplayRow(byte[] source, int sourceOffset, int sourceWidth, int zoomMultiplier,
                                 int zoomOffset, byte[] destination, int destinationOffset, int destinationWidth)
    {
        int visibleBinCount = Math.max(1, sourceWidth / Math.max(1, zoomMultiplier));
        int firstVisibleBin = Math.max(0, Math.min(zoomOffset, sourceWidth - visibleBinCount));

        for(int x = 0; x < destinationWidth; x++)
        {
            int firstBin = firstVisibleBin + (int)((long)x * visibleBinCount / destinationWidth);
            int nextBin = firstVisibleBin + (int)((long)(x + 1) * visibleBinCount / destinationWidth);

            if(nextBin <= firstBin)
            {
                nextBin = firstBin + 1;
            }

            int maximum = 0;

            for(int bin = firstBin; bin < nextBin && bin < sourceWidth; bin++)
            {
                maximum = Math.max(maximum, Byte.toUnsignedInt(source[sourceOffset + bin]));
            }

            destination[destinationOffset + x] = (byte)maximum;
        }
    }

    /**
     * When zoom level is greater than zero, paints a small indicator at the
     * bottom center of the screen showing the location of the zoom window
     * within the overall DFT results window
     */
    private void paintZoomIndicator(Graphics2D graphics)
    {
        if(mZoom != 0)
        {
            int width = getWidth() / 4;
            int x = (getWidth() / 2) - (width / 2);

            //Draw the outer window
            graphics.drawRect(x, getHeight() - 12, width, 10);
            int zoomWidth = width / getZoomMultiplier();
            int windowOffset = 0;

            if(mDFTZoomWindowOffset != 0)
            {
                windowOffset = (int)(((double)mDFTZoomWindowOffset / (double)mDFTSize) * width);
            }

            //Draw the zoom window
            graphics.fillRect(x + windowOffset, getHeight() - 12, zoomWidth, 10);

            //Draw the zoom text
            graphics.drawString("Zoom: " + getZoomMultiplier() + "x", x + width + 3, getHeight() - 2);
        }
    }

    /**
     * Implements the DFT results listener interface method.  This is the primary method for receiving new frequency bin results.
     */
    @Override
    public void receive(float[] update)
    {
        mDisabled = false;

        byte[] newPixels = new byte[update.length];

        /**
         * Find the average value and scale the display to it
         */
        double sum = 0.0d;

        for(int x = 0; x < update.length - 1; x++)
        {
            sum += update[x];
        }

        float average = (float)(sum / update.length - 1);
        float scale = 256.0f / average;

        for(int x = 0; x < update.length - 1; x++)
        {
            float value = (average - update[x]) * scale;

            if(value < 0)
            {
                newPixels[x] = 0;
            }
            else if(value > 255)
            {
                newPixels[x] = (byte)255;
            }
            else
            {
                newPixels[x] = (byte)value;
            }
        }

        //Task the swing event thread to add the new pixels to the pixel array and update the display
        EventQueue.invokeLater(() -> {
            if(mPixels != null)
            {
                //If our FFT size changes, reset our pixel map and image source
                if(mDFTSize != newPixels.length)
                {
                    mDFTSize = newPixels.length;
                    reset();
                }

                //Use a ring buffer so that only the newest scanline has to be copied and uploaded to Java2D.
                mNewestPixelRow = mNewestPixelRow == 0 ? mImageHeight - 1 : mNewestPixelRow - 1;
                System.arraycopy(newPixels, 0, mPixels, mNewestPixelRow * mDFTSize, newPixels.length);

                if(!mPaused)
                {
                    if(mWaterfallImage != null && !mWaterfallImageDirty)
                    {
                        byte[] displayRow = new byte[mWaterfallImage.getWidth()];
                        renderDisplayRow(mPixels, mNewestPixelRow * mDFTSize, mDFTSize, getZoomMultiplier(),
                            mDFTZoomWindowOffset, displayRow, 0, displayRow.length);
                        mWaterfallImage.getRaster().setDataElements(0, mNewestPixelRow, displayRow.length, 1,
                            displayRow);
                    }
                    else
                    {
                        mWaterfallImageDirty = true;
                    }
                }

                repaint();
            }
        });
    }

    public void clearWaterfall()
    {
        Arrays.fill(mPixels, (byte)0);
        mNewestPixelRow = 0;

        if(mPaused)
        {
            Arrays.fill(mPausedPixels, (byte)0);
            mPausedNewestPixelRow = 0;
        }

        mDisabled = true;
        mWaterfallImageDirty = true;
        repaint();
    }
}
