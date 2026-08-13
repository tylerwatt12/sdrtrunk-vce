/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DebugHarnessConfigurationTest
{
    @Test
    void validatesRestartScopedSettings()
    {
        assertEquals(DebugHarnessConfiguration.DEFAULT_PORT, DebugHarnessConfiguration.parsePort(null));
        assertEquals(18091, DebugHarnessConfiguration.parsePort(" 18091 "));
        assertThrows(IllegalArgumentException.class, () -> DebugHarnessConfiguration.parsePort("not-a-port"));
        assertThrows(IllegalArgumentException.class,
            () -> new DebugHarnessConfiguration(false, true, DebugHarnessConfiguration.DEFAULT_PORT));
        assertThrows(IllegalArgumentException.class, () -> new DebugHarnessConfiguration(true, false, 80));
    }
}
