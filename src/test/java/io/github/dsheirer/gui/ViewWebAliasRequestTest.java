/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewWebAliasRequestTest
{
    @Test
    void distinguishesTheCatalogFromOnePersistedAlias()
    {
        ViewWebAliasRequest catalog = new ViewWebAliasRequest();
        assertFalse(catalog.hasAlias());
        assertEquals(0, catalog.getAliasListId());
        assertEquals(0, catalog.getAliasId());

        ViewWebAliasRequest exact = new ViewWebAliasRequest(12, 41);
        assertTrue(exact.hasAlias());
        assertEquals(12, exact.getAliasListId());
        assertEquals(41, exact.getAliasId());

        assertThrows(IllegalArgumentException.class, () -> new ViewWebAliasRequest(0, 41));
        assertThrows(IllegalArgumentException.class, () -> new ViewWebAliasRequest(12, 0));
    }
}
