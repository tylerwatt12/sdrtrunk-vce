/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.alias;

import io.github.dsheirer.configuration.ConfigurationManager;

/** Creates the service without starting the process-wide JavaFX toolkit. */
public final class AliasAdministrationServiceTestSupport
{
    private AliasAdministrationServiceTestSupport()
    {
    }

    public static AliasAdministrationService create(ConfigurationManager configurationManager)
    {
        return new AliasAdministrationService(configurationManager, false);
    }
}
