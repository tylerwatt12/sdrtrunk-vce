/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.function.Predicate;
import javafx.collections.transformation.FilteredList;
import org.junit.jupiter.api.Test;

/** Exercises the observable filter lifecycle used by the desktop Alias editor. */
class AliasConfigurationEditorFilterTest
{
    @Test
    void freshPredicatesFollowListSearchAndModelChanges()
    {
        AliasListDefinition county = definition(11, "County");
        AliasListDefinition city = definition(12, "City");
        AliasListDefinition empty = definition(13, "Empty");
        Alias countyDispatch = alias(101, county, "County Dispatch", 1001);
        Alias countyFire = alias(102, county, "County Fire", 1002);
        Alias cityDispatch = alias(201, city, "City Dispatch", 2001);
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(county, city, empty));
        model.addAliases(List.of(countyDispatch, countyFire, cityDispatch));
        FilteredList<Alias> visible = new FilteredList<>(model.aliasList());

        Predicate<Alias> countyFilter = new AliasPredicate("County", "");
        visible.setPredicate(countyFilter);
        assertEquals(List.of(101L, 102L), ids(visible));

        Predicate<Alias> cityFilter = new AliasPredicate("City", "dispatch");
        assertNotSame(countyFilter, cityFilter);
        visible.setPredicate(cityFilter);
        assertEquals(List.of(201L), ids(visible));

        visible.setPredicate(new AliasPredicate("Empty", ""));
        assertEquals(List.of(), ids(visible));

        visible.setPredicate(new AliasPredicate("County", "fire"));
        assertEquals(List.of(102L), ids(visible));

        Alias countyFireTwo = alias(103, county, "County Fireground", 1003);
        model.addAlias(countyFireTwo);
        assertEquals(List.of(102L, 103L), ids(visible));

        visible.setPredicate(new AliasPredicate("City", ""));
        assertEquals(List.of(201L), ids(visible));
        visible.setPredicate(new AliasPredicate("County", "dispatch"));
        assertEquals(List.of(101L), ids(visible));
    }

    @Test
    void familyLabelChangesPresentationWithoutChangingStoredIdentity()
    {
        assertEquals("Conventional Analog (AM/NBFM)", AliasListFamily.NBFM.toString());
        assertEquals("NBFM", AliasListFamily.NBFM.name());
    }

    private static AliasListDefinition definition(long id, String name)
    {
        AliasListDefinition definition = new AliasListDefinition(name, AliasListFamily.NBFM);
        definition.setId(id);
        return definition;
    }

    private static Alias alias(long id, AliasListDefinition definition, String name, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setId(id);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.NBFM, talkgroup));
        return alias;
    }

    private static List<Long> ids(List<Alias> aliases)
    {
        return aliases.stream().map(Alias::getId).toList();
    }
}
