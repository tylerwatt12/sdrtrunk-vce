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

package io.github.dsheirer.controller.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChannelModelTest
{
    @Test
    public void standardChannelGetsSiteGuidWhenAdded()
    {
        Channel channel = new Channel("Test");

        assertFalse(channel.hasRadresGuid());

        ChannelModel model = new ChannelModel();
        model.addChannel(channel);

        assertTrue(channel.hasRadresGuid());
    }

    @Test
    public void copiedChannelGetsNewSiteGuidWhenAdded()
    {
        Channel original = new Channel("Test");
        ChannelModel model = new ChannelModel();
        model.addChannel(original);

        Channel copy = original.copyOf();

        assertFalse(copy.hasRadresGuid());

        model.addChannel(copy);

        assertTrue(copy.hasRadresGuid());
        assertNotEquals(original.getRadresGuid(), copy.getRadresGuid());
    }
}
