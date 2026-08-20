/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import io.github.dsheirer.alias.Alias;
import java.util.Locale;
import java.util.function.Predicate;

/** Immutable filter installed for each Alias list or search change. */
record AliasPredicate(long aliasListId, String searchText) implements Predicate<Alias>
{
    AliasPredicate
    {
        searchText = searchText != null ? searchText.toLowerCase(Locale.ROOT) : "";
    }

    @Override
    public boolean test(Alias alias)
    {
        return aliasListId > Alias.UNASSIGNED_ALIAS_LIST_ID && alias != null &&
            aliasListId == alias.getAliasListId() &&
            (contains(alias.getName()) || contains(alias.getDescription()) || contains(alias.getGroup()));
    }

    private boolean contains(String value)
    {
        return value != null && value.toLowerCase(Locale.ROOT).contains(searchText);
    }
}
