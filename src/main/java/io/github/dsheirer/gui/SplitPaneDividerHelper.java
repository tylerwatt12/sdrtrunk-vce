/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

import com.jidesoft.swing.JideSplitPane;
import javax.swing.JSplitPane;

/**
 * Restores split pane divider locations only after layout has produced enough
 * room for both sides of the split.
 */
public class SplitPaneDividerHelper
{
    private SplitPaneDividerHelper()
    {
    }

    public static boolean restore(JideSplitPane splitPane, int dividerIndex, int location, int minimumPaneSize,
                                  boolean vertical)
    {
        if(!isReady(splitPane, dividerIndex, minimumPaneSize, vertical))
        {
            return false;
        }

        splitPane.setDividerLocation(dividerIndex, clamp(location, size(splitPane, vertical), minimumPaneSize));
        return true;
    }

    public static boolean restore(JSplitPane splitPane, int location, int minimumPaneSize)
    {
        if(!isReady(splitPane, minimumPaneSize))
        {
            return false;
        }

        splitPane.setDividerLocation(clamp(location, size(splitPane), minimumPaneSize));
        return true;
    }

    public static int getDividerLocationOrDefault(JideSplitPane splitPane, int dividerIndex, int defaultLocation,
                                                  int minimumPaneSize, boolean vertical)
    {
        if(isReady(splitPane, dividerIndex, minimumPaneSize, vertical))
        {
            int location = splitPane.getDividerLocation(dividerIndex);
            int size = size(splitPane, vertical);

            if(location >= minimumPaneSize && location <= size - minimumPaneSize)
            {
                return location;
            }
        }

        return defaultLocation;
    }

    public static int getDividerLocationOrDefault(JSplitPane splitPane, int defaultLocation, int minimumPaneSize)
    {
        if(isReady(splitPane, minimumPaneSize))
        {
            int location = splitPane.getDividerLocation();
            int size = size(splitPane);

            if(location >= minimumPaneSize && location <= size - minimumPaneSize)
            {
                return location;
            }
        }

        return defaultLocation;
    }

    private static boolean isReady(JideSplitPane splitPane, int dividerIndex, int minimumPaneSize, boolean vertical)
    {
        return splitPane != null && splitPane.getPaneCount() > dividerIndex + 1 &&
            size(splitPane, vertical) >= minimumPaneSize * 2;
    }

    private static boolean isReady(JSplitPane splitPane, int minimumPaneSize)
    {
        return splitPane != null && splitPane.getLeftComponent() != null && splitPane.getRightComponent() != null &&
            size(splitPane) >= minimumPaneSize * 2;
    }

    private static int size(JideSplitPane splitPane, boolean vertical)
    {
        return vertical ? splitPane.getHeight() : splitPane.getWidth();
    }

    private static int size(JSplitPane splitPane)
    {
        return splitPane.getOrientation() == JSplitPane.VERTICAL_SPLIT ? splitPane.getHeight() : splitPane.getWidth();
    }

    private static int clamp(int location, int size, int minimumPaneSize)
    {
        int maximum = Math.max(minimumPaneSize, size - minimumPaneSize);
        return Math.min(Math.max(location, minimumPaneSize), maximum);
    }
}
