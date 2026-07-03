/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.icon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.icon.IconSet;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IconDatabaseStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void roundTripsIconSet() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        IconDatabaseStore store = new IconDatabaseStore(database);
        assertFalse(store.isInitialized());

        IconSet iconSet = new IconSet();
        iconSet.setDefaultIcon("No Icon");
        iconSet.setIcons(List.of(
            new Icon("No Icon", "images/no_icon.png"),
            new Icon("Custom", "/tmp/custom.png")
        ));

        store.replaceIcons(iconSet);
        assertTrue(store.isInitialized());

        IconSet loaded = store.loadIcons();
        assertEquals("No Icon", loaded.getDefaultIcon());
        assertEquals(2, loaded.getIcons().size());
        assertEquals("No Icon", loaded.getIcons().get(0).getName());
        assertEquals("images/no_icon.png", loaded.getIcons().get(0).getPath());
        assertEquals("Custom", loaded.getIcons().get(1).getName());
        assertEquals("/tmp/custom.png", loaded.getIcons().get(1).getPath());
    }
}
