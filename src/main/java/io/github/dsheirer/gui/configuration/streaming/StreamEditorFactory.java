/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.gui.configuration.streaming;

import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.configuration.ConfigurationManager;

/**
 * Factory for creating broadcast configuration editors
 */
public class StreamEditorFactory
{
    private StreamEditorFactory()
    {
    }

    /**
     * Creates a new editor for the specified broadcast server type
     * @param broadcastServerType to edit
     * @return editor or the default unknown editor
     */
    public static AbstractBroadcastEditor<?> getEditor(BroadcastServerType broadcastServerType, ConfigurationManager configurationManager)
    {
        switch(broadcastServerType)
        {
            case BROADCASTIFY:
                return new BroadcastifyStreamEditor(configurationManager);
            case RDIOSCANNER_CALL:
                return new RdioScannerEditor(configurationManager);
            case OPENMHZ:
                return new OpenMHzEditor(configurationManager);
            case RADIORESOLVE:
                return new RadioResolveEditor(configurationManager);
            case BROADCASTIFY_CALL:
                return new BroadcastifyCallEditor(configurationManager);
            case ICECAST_HTTP:
                return new IcecastHTTPStreamEditor(configurationManager);
            case ICECAST_TCP:
                return new IcecastTCPStreamEditor(configurationManager);
            case SHOUTCAST_V1:
                return new ShoutcastV1StreamEditor(configurationManager);
            default:
                return new UnknownStreamEditor(configurationManager);
        }
    }
}
