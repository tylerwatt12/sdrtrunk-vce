/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.dmr;

import io.github.dsheirer.controller.channel.event.PreloadDataContent;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import java.util.Objects;

/**
 * Request-scoped initial channel grant event for a dynamically allocated DMR traffic channel.
 */
public class DMRChannelGrantPreloadData extends PreloadDataContent<DecodeEvent>
{
    /**
     * Constructs an instance.
     *
     * @param channelGrantEvent initial grant event that the traffic decoder state will maintain
     */
    public DMRChannelGrantPreloadData(DecodeEvent channelGrantEvent)
    {
        super(Objects.requireNonNull(channelGrantEvent, "DMR channel grant event cannot be null"));
    }

    /**
     * Initial channel grant event.
     */
    public DecodeEvent getChannelGrantEvent()
    {
        return getData();
    }
}
