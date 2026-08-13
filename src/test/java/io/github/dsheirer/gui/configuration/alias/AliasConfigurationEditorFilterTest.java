/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import org.junit.jupiter.api.Test;

/** Exercises the observable filter lifecycle used by the desktop alias editor. */
class AliasConfigurationEditorFilterTest
{
    @Test
    void freshPredicatesFollowListAndSearchChanges()
    {
        Alias countyDispatch = alias("County", "County Dispatch");
        Alias countyFire = alias("County", "County Fire");
        Alias cityDispatch = alias("City", "City Dispatch");
        FilteredList<Alias> visible = new FilteredList<>(
            FXCollections.observableArrayList(countyDispatch, countyFire, cityDispatch));

        visible.setPredicate(new AliasPredicate("County", ""));
        assertEquals(List.of(countyDispatch, countyFire), List.copyOf(visible));

        visible.setPredicate(new AliasPredicate("City", "dispatch"));
        assertEquals(List.of(cityDispatch), List.copyOf(visible));

        visible.setPredicate(new AliasPredicate("County", "fire"));
        assertEquals(List.of(countyFire), List.copyOf(visible));

        visible.setPredicate(new AliasPredicate(null, ""));
        assertEquals(List.of(), List.copyOf(visible));
    }

    private static Alias alias(String listName, String name)
    {
        Alias alias = new Alias(name);
        alias.setAliasListDefinition(new AliasListDefinition(listName, AliasListFamily.P25));
        return alias;
    }
}
