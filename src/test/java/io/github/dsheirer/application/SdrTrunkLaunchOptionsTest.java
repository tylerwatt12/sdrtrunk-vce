/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SdrTrunkLaunchOptionsTest
{
    @Test
    void defaultsToNormalRuntime()
    {
        SdrTrunkLaunchOptions options = SdrTrunkLaunchOptions.parse(new String[0]);
        assertFalse(options.serverAdminUi());
        assertTrue(options.headlessRuntime());
    }

    @Test
    void recognizesServerAdminUiAmongDatabaseBootstrapArguments()
    {
        SdrTrunkLaunchOptions options = SdrTrunkLaunchOptions.parse(new String[] {
            "--fresh", SdrTrunkLaunchOptions.SERVER_ADMIN_UI_ARGUMENT
        });
        assertTrue(options.serverAdminUi());
        assertFalse(options.headlessRuntime());
    }

    @Test
    void rejectsDuplicateServerAdminUiArgument()
    {
        assertThrows(IllegalArgumentException.class, () -> SdrTrunkLaunchOptions.parse(new String[] {
            SdrTrunkLaunchOptions.SERVER_ADMIN_UI_ARGUMENT,
            SdrTrunkLaunchOptions.SERVER_ADMIN_UI_ARGUMENT
        }));
    }
}
