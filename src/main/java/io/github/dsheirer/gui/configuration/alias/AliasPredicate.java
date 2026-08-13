/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import io.github.dsheirer.alias.Alias;
import java.util.Locale;
import java.util.function.Predicate;

/** Immutable filter installed for each alias-list or search change. */
record AliasPredicate(String aliasListName, String searchText) implements Predicate<Alias>
{
    AliasPredicate
    {
        searchText = searchText != null ? searchText.toLowerCase(Locale.ROOT) : "";
    }

    @Override
    public boolean test(Alias alias)
    {
        return aliasListName != null && aliasListName.equals(alias.getAliasListName()) &&
            (contains(alias.getName()) || contains(alias.getDescription()) || contains(alias.getGroup()));
    }

    private boolean contains(String value)
    {
        return value != null && value.toLowerCase(Locale.ROOT).contains(searchText);
    }
}
