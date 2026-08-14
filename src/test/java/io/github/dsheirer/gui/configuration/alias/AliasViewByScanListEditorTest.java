/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.configuration.alias;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AliasViewByScanListEditorTest
{
    @Test
    void separatesAvailableAliasListRowsFromAllAssignedRows()
    {
        Alias countyAvailable = alias(101, "County", "Dispatch");
        Alias countyAssigned = alias(102, "County", "Fire");
        Alias cityAssigned = alias(201, "City", "Police");
        Alias unsaved = alias(0, "County", "Unsaved");
        Set<Long> members = Set.of(102L, 201L);

        var available = new AliasViewByScanListEditor.ScanListAliasPredicate("County", members, false);
        assertTrue(available.test(countyAvailable));
        assertFalse(available.test(countyAssigned));
        assertFalse(available.test(cityAssigned));
        assertFalse(available.test(unsaved));

        var assigned = new AliasViewByScanListEditor.ScanListAliasPredicate(null, members, true);
        assertFalse(assigned.test(countyAvailable));
        assertTrue(assigned.test(countyAssigned));
        assertTrue(assigned.test(cityAssigned));
        assertFalse(assigned.test(unsaved));
    }

    private static Alias alias(long id, String aliasListName, String name)
    {
        Alias alias = new Alias(name);
        alias.setId(id);
        alias.setAliasListName(aliasListName);
        return alias;
    }
}
