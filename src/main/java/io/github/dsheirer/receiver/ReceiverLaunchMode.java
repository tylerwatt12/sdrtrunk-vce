/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.receiver;

import java.util.Arrays;

/** Selects the optional desktop shell without changing receiver behavior. */
public enum ReceiverLaunchMode
{
    DESKTOP, SERVER;

    public static ReceiverLaunchMode select(String[] arguments, boolean desktopAvailable)
    {
        boolean server = !desktopAvailable || Arrays.asList(arguments).contains("--server");
        if(server && Arrays.asList(arguments).contains("--setup-wizard"))
        {
            throw new IllegalArgumentException("--setup-wizard requires desktop mode; omit --server.");
        }
        return server ? SERVER : DESKTOP;
    }
}
