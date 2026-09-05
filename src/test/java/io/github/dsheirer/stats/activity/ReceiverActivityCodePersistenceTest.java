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

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.module.decode.event.DecodeEventType;
import org.junit.jupiter.api.Test;

class ReceiverActivityCodePersistenceTest
{
    @Test
    void retainedActivityCodesUseExplicitMappings()
    {
        assertEquals(28, ReceiverActivityCodes.eventTypeCode(DecodeEventType.DEREGISTER));
        assertEquals(56, ReceiverActivityCodes.eventTypeCode(DecodeEventType.UNKNOWN));
        assertEquals(57, ReceiverActivityCodes.eventTypeCode(DecodeEventType.DENIAL));
        assertEquals(12, ReceiverActivityRecords.Action.GRANT.code());
        assertEquals(23, ReceiverActivityRecords.Action.UNKNOWN.code());
    }
}
