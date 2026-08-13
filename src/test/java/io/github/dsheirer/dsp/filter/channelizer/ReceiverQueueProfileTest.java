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
package io.github.dsheirer.dsp.filter.channelizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiverQueueProfileTest
{
    @Test
    void parsesTheTwoExplicitLaunchValues()
    {
        assertEquals(ReceiverQueueProfile.PROTECTED, ReceiverQueueProfile.parse("protected"));
        assertEquals(ReceiverQueueProfile.RETAIN_ALL, ReceiverQueueProfile.parse(" RETAIN-ALL "));
    }

    @Test
    void invalidExplicitValueFailsFast()
    {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> ReceiverQueueProfile.parse("mainline"));
        assertTrue(exception.getMessage().contains(ReceiverQueueProfile.PROPERTY_NAME));
    }

    @Test
    void profileLimitsKeepZeroAsTheUnboundedSentinel()
    {
        assertEquals(100, ReceiverQueueProfile.PROTECTED.getNativeBufferMaximumQueueDurationMillis());
        assertEquals(8, ReceiverQueueProfile.PROTECTED.getIfftQueueCapacity());
        assertEquals(8, ReceiverQueueProfile.PROTECTED.getChannelOutputQueueCapacity());

        assertEquals(0, ReceiverQueueProfile.RETAIN_ALL.getNativeBufferMaximumQueueDurationMillis());
        assertEquals(0, ReceiverQueueProfile.RETAIN_ALL.getIfftQueueCapacity());
        assertEquals(0, ReceiverQueueProfile.RETAIN_ALL.getChannelOutputQueueCapacity());
        assertTrue(ReceiverQueueProfile.RETAIN_ALL.isRetainAll());
    }
}
