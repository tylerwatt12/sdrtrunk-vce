/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.service.radioreference;

import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.configuration.ConfigurationManager;
import java.time.Clock;
import java.time.Duration;

/** Shares the production directory adapter with HTTP tests that administer channels without JavaFX. */
public final class RadioReferenceImportServiceTestSupport
{
    private RadioReferenceImportServiceTestSupport() {}

    public static RadioReferenceImportService create(RadioReferenceDirectoryService directory,
        ConfigurationManager manager, ChannelAdministrationService channels)
    {
        return new RadioReferenceImportService(new RadioReferenceImportService.DirectoryAdapter(directory), channels,
            AliasAdministrationServiceTestSupport.create(manager), Clock.systemUTC(), Duration.ofMinutes(5));
    }
}
