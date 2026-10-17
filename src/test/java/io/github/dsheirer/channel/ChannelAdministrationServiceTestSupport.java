/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.channel;

import io.github.dsheirer.configuration.ConfigurationManager;
import java.util.function.Function;

/** Creates channel administration without starting the process-wide JavaFX toolkit. */
public final class ChannelAdministrationServiceTestSupport
{
    private ChannelAdministrationServiceTestSupport()
    {
    }

    public static ChannelAdministrationService create(ConfigurationManager manager)
    {
        return create(manager, key -> null);
    }

    public static ChannelAdministrationService create(ConfigurationManager manager, Function<String,String> systemNames)
    {
        return new ChannelAdministrationService(manager, new ChannelProtocolRegistry(), false, systemNames);
    }
}
