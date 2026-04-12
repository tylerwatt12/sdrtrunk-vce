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
package io.github.dsheirer.controller.channel.event;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.ModuleEventBusMessage;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Request for ChannelProcessingManager to post a module event onto the processing-chain event bus for each specified
 * active channel.
 */
public class PostChannelModuleEventRequest implements ModuleEventBusMessage
{
    private final List<Channel> mChannels;
    private final ModuleEventBusMessage mEvent;

    public PostChannelModuleEventRequest(Collection<Channel> channels, ModuleEventBusMessage event)
    {
        mChannels = channels != null ? List.copyOf(channels) : Collections.emptyList();
        mEvent = event;
    }

    public List<Channel> getChannels()
    {
        return mChannels;
    }

    public ModuleEventBusMessage getEvent()
    {
        return mEvent;
    }

    public boolean hasChannels()
    {
        return !mChannels.isEmpty();
    }

    public boolean hasEvent()
    {
        return mEvent != null;
    }
}
