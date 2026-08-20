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

package io.github.dsheirer.alias;

import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Complete Alias, Alias List, and scan-list state for one database transaction. */
public record AliasConfigurationSnapshot(List<AliasListDefinition> definitions, List<Alias> aliases,
                                         ScanListConfiguration scanLists)
{
    public AliasConfigurationSnapshot
    {
        definitions = List.copyOf(Objects.requireNonNull(definitions, "Alias-list definitions cannot be null"));
        aliases = List.copyOf(Objects.requireNonNull(aliases, "Aliases cannot be null"));
        scanLists = Objects.requireNonNull(scanLists, "Scan-list configuration cannot be null");
    }

    /** Copies every identity-bearing object so a failed command cannot change the active runtime model. */
    public static AliasConfigurationSnapshot detachedCopyOf(Collection<AliasListDefinition> definitions,
                                                             Collection<Alias> aliases,
                                                             ScanListConfiguration scanLists)
    {
        List<AliasListDefinition> definitionCopies = new ArrayList<>();
        for(AliasListDefinition definition: Objects.requireNonNull(definitions,
            "Alias-list definitions cannot be null"))
        {
            AliasListDefinition copy = new AliasListDefinition(definition.getName(), definition.getFamily(),
                definition.getUnmatchedTalkgroupPolicy());
            copy.setId(definition.getId());
            definitionCopies.add(copy);
        }

        List<Alias> aliasCopies = new ArrayList<>();
        for(Alias alias: Objects.requireNonNull(aliases, "Aliases cannot be null"))
        {
            Alias copy = AliasFactory.copyOf(alias);
            copy.setId(alias.getId());
            aliasCopies.add(copy);
        }

        List<ScanList> scanListCopies = scanLists.scanLists().stream()
            .map(scanList -> new ScanList(scanList.getId(), scanList.getSortOrder(), scanList.getName(),
                scanList.getDescription(), scanList.isPublished(), scanList.isDefault()))
            .toList();
        ScanListConfiguration scanListCopy = new ScanListConfiguration(scanListCopies,
            scanLists.aliasMemberships(), scanLists.unmatchedAliasListMemberships());
        return new AliasConfigurationSnapshot(definitionCopies, aliasCopies, scanListCopy);
    }

    public static AliasConfigurationSnapshot detachedCopyOf(AliasConfigurationSnapshot snapshot)
    {
        Objects.requireNonNull(snapshot, "Alias configuration cannot be null");
        return detachedCopyOf(snapshot.definitions(), snapshot.aliases(), snapshot.scanLists());
    }
}
