/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.icon.IconSet;
import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyIconSetCompatibilityTest
{
    @TempDir
    Path mTemporaryFolder;

    private static final String LEGACY_ICONS = """
        {"defaultIcon":"Old custom","icons":[
          {"name":"No Icon","path":"images/no_icon.png","defaultIcon":false,"standardIcon":true},
          {"name":"Old custom","path":"/legacy/icons/custom.png","defaultIcon":true,"standardIcon":false}
        ]}
        """;

    @Test
    void readsLegacyMetadataWithoutJavaFxPropertiesOrCustomManagement() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        IconSet icons = mapper.readValue(LEGACY_ICONS, IconSet.class);
        assertEquals("Old custom", icons.getDefaultIcon());
        assertEquals(2, icons.getIcons().size());
        assertEquals("No Icon", icons.getIcons().getFirst().getName());
        assertEquals("images/no_icon.png", icons.getIcons().getFirst().getPath());
        assertEquals("Old custom", icons.getIcons().getLast().getName());
        assertEquals("/legacy/icons/custom.png", icons.getIcons().getLast().getPath());
        var serialized = mapper.valueToTree(icons);
        assertFalse(serialized.path("icons").get(0).has("defaultIcon"));
        assertFalse(serialized.path("icons").get(0).has("standardIcon"));
    }

    @Test
    void currentDatabaseRepairRetainsTheOriginalLegacyDocumentAndTimestamp() throws Exception
    {
        Path database = mTemporaryFolder.resolve("legacy-icons.sqlite");
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            try(var statement = connection.prepareStatement("""
                INSERT INTO application_icons(key, icons_json, updated_at_ms) VALUES ('default', ?, 1234)
                """))
            {
                statement.setString(1, LEGACY_ICONS);
                statement.executeUpdate();
            }
            CurrentDatabaseAdministrativeRepair.repair(connection);
            try(var statement = connection.prepareStatement(
                    "SELECT icons_json, updated_at_ms FROM application_icons WHERE key='default'");
                var rows = statement.executeQuery())
            {
                assertTrue(rows.next());
                assertEquals(LEGACY_ICONS, rows.getString(1));
                assertEquals(1234, rows.getLong(2));
            }
        }
    }
}
