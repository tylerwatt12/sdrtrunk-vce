/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.configuration;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.util.List;
import java.util.Objects;

/**
 * Complete Alias, scan-list, channel, and broadcast-stream playlist configuration from one durable database view.
 *
 * <p>The container and its collections are immutable. Configuration objects remain mutable because the desktop
 * models expose JavaFX properties; callers must publish only a snapshot returned by the configuration repository.</p>
 */
public record ConfigurationSnapshot(List<AliasListDefinition> aliasListDefinitions, List<Alias> aliases,
                                    ScanListConfiguration scanListConfiguration, List<Channel> channels,
                                    List<BroadcastConfiguration> broadcastConfigurations)
{
    public ConfigurationSnapshot
    {
        aliasListDefinitions = List.copyOf(Objects.requireNonNull(aliasListDefinitions,
            "Alias-list definitions cannot be null"));
        aliases = List.copyOf(Objects.requireNonNull(aliases, "Aliases cannot be null"));
        scanListConfiguration = Objects.requireNonNull(scanListConfiguration,
            "Scan-list configuration cannot be null");
        channels = List.copyOf(Objects.requireNonNull(channels, "Channels cannot be null"));
        broadcastConfigurations = List.copyOf(Objects.requireNonNull(broadcastConfigurations,
            "Broadcast configurations cannot be null"));
    }

    public AliasConfigurationSnapshot aliasConfiguration()
    {
        return new AliasConfigurationSnapshot(aliasListDefinitions, aliases, scanListConfiguration);
    }
}
