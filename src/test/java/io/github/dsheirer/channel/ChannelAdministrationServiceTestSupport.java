/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.channel;

import io.github.dsheirer.configuration.ConfigurationManager;

/** Creates channel administration without starting the process-wide JavaFX toolkit. */
public final class ChannelAdministrationServiceTestSupport
{
    private ChannelAdministrationServiceTestSupport()
    {
    }

    public static ChannelAdministrationService create(ConfigurationManager manager)
    {
        return new ChannelAdministrationService(manager, new ChannelProtocolRegistry(), false);
    }
}
