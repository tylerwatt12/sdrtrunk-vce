/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.receiver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ReceiverLaunchModeTest
{
    @Test
    void desktopIsOptionalEvenWhenGraphicalDesktopIsAvailable()
    {
        assertEquals(ReceiverLaunchMode.DESKTOP, ReceiverLaunchMode.select(new String[0], true));
        assertEquals(ReceiverLaunchMode.SERVER, ReceiverLaunchMode.select(new String[]{"--server"}, true));
        assertEquals(ReceiverLaunchMode.SERVER, ReceiverLaunchMode.select(new String[0], false));
        assertEquals(ReceiverLaunchMode.SERVER, ReceiverLaunchMode.select(new String[]{"--server", "--upgrade-current"}, true));
    }

    @Test
    void graphicalSetupRemainsDesktopOnly()
    {
        assertEquals(ReceiverLaunchMode.DESKTOP, ReceiverLaunchMode.select(new String[]{"--setup-wizard"}, true));
        assertThrows(IllegalArgumentException.class,
            () -> ReceiverLaunchMode.select(new String[]{"--server", "--setup-wizard"}, true));
        assertThrows(IllegalArgumentException.class,
            () -> ReceiverLaunchMode.select(new String[]{"--setup-wizard"}, false));
    }
}
