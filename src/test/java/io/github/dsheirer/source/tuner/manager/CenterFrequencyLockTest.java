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
package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import org.junit.jupiter.api.Test;

class CenterFrequencyLockTest
{
    private static final ChannelSpecification CHANNEL_SPECIFICATION =
        new ChannelSpecification(50_000, 12_500, 6_250, 7_000);

    @Test
    void polyphaseManagerDoesNotRetuneLockedController() throws Exception
    {
        TestTunerController controller = new TestTunerController();
        long centerFrequency = controller.getFrequency();
        controller.setCenterFrequencyLocked(true);
        PolyphaseChannelSourceManager manager = new PolyphaseChannelSourceManager(controller);

        assertNull(manager.getSource(new TunerChannel(centerFrequency + 2_000_000, 12_500),
            CHANNEL_SPECIFICATION, "locked-polyphase-test"));
        assertEquals(centerFrequency, controller.getFrequency());
    }
}
