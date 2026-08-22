/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.service.radioreference;

import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.configuration.ConfigurationManager;

/** Creates the importer without starting the process-wide JavaFX toolkit. */
public final class RadioReferenceImportServiceTestSupport
{
    private RadioReferenceImportServiceTestSupport()
    {
    }

    public static RadioReferenceImportService create(RadioReferenceDirectoryService directory,
                                                      ConfigurationManager configurationManager,
                                                      AliasAdministrationService aliasAdministrationService)
    {
        return new RadioReferenceImportService(directory, configurationManager, aliasAdministrationService, false);
    }
}
