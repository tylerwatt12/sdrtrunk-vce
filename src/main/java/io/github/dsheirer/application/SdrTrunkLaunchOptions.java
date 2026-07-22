/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application;

import java.util.Objects;

/**
 * Launch options owned by the top-level SDRTrunk application entry point.
 */
public record SdrTrunkLaunchOptions(boolean serverAdminUi)
{
    public static final String SERVER_ADMIN_UI_ARGUMENT = "--server-admin-ui";

    public static SdrTrunkLaunchOptions parse(String[] arguments)
    {
        Objects.requireNonNull(arguments, "Launch arguments cannot be null");
        boolean serverAdminUi = false;

        for(String argument: arguments)
        {
            if(SERVER_ADMIN_UI_ARGUMENT.equals(argument))
            {
                if(serverAdminUi)
                {
                    throw new IllegalArgumentException(SERVER_ADMIN_UI_ARGUMENT + " may be supplied only once");
                }

                serverAdminUi = true;
            }
        }

        return new SdrTrunkLaunchOptions(serverAdminUi);
    }

    /**
     * The normal receiver is always headless.  Only the isolated local node-administration utility may use JavaFX.
     */
    public boolean headlessRuntime()
    {
        return !serverAdminUi;
    }
}
