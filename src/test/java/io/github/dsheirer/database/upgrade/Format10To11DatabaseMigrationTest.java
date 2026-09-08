/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format10To11DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void restoresMissingListsAndOnlyAssignsBlankChannels() throws Exception
    {
        try(Connection connection = fixture())
        {
            addList(connection, "Custom P25", "P25");
            assign(connection, 1, "Custom P25");
            String customChannel = scalar(connection, "SELECT config_json FROM configuration_channel WHERE id=1");
            String settings = settings(connection);
            var effects = new Format10To11DatabaseMigration().validateSource(connection);
            assertEquals(4, effects.stream().filter(effect -> effect.subject().equals("missing factory Alias Lists"))
                .findFirst().orElseThrow().affectedRows());
            assertEquals(1, effects.stream().filter(effect -> effect.subject().equals("unassigned channel Alias Lists"))
                .findFirst().orElseThrow().affectedRows());

            migrate(connection);

            assertEquals("Default Analog|Default DMR|Default NXDN|Default P25", scalar(connection, """
                SELECT group_concat(name, '|') FROM (SELECT name FROM alias_list
                WHERE name LIKE 'Default %' ORDER BY name)
                """));
            assertEquals("Default Analog", scalar(connection,
                "SELECT alias_list_name FROM configuration_channel WHERE id=2"));
            assertEquals("Default Analog", scalar(connection,
                "SELECT json_extract(config_json, '$.aliasListName') FROM configuration_channel WHERE id=2"));
            assertEquals(customChannel, scalar(connection, "SELECT config_json FROM configuration_channel WHERE id=1"));
            assertEquals(settings, settings(connection));
            assertEquals("4", scalar(connection, """
                SELECT count(*) FROM alias_list_unmatched_talkgroup_scan_list_membership m
                JOIN scan_list s ON s.id=m.scan_list_id WHERE s.is_default=1
                """));
            assertEquals("0", scalar(connection,
                "SELECT sum(unmatched_talkgroup_record_enabled) FROM alias_list"));
        }
    }

    @Test
    void restoresMissingDefaultScanListBeforeAddingFactoryRoutes() throws Exception
    {
        try(Connection connection = fixture("missing-default-scan-list"))
        {
            execute(connection, "DELETE FROM scan_list");
            assertEquals(1, new Format10To11DatabaseMigration().validateSource(connection).stream()
                .filter(effect -> effect.subject().equals("Default scan list"))
                .findFirst().orElseThrow().affectedRows());

            migrate(connection);

            assertEquals("1", scalar(connection,
                "SELECT COUNT(*) FROM scan_list WHERE is_default=1 AND published=1"));
            assertEquals("4", scalar(connection, """
                SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership membership
                JOIN scan_list target ON target.id=membership.scan_list_id AND target.is_default=1
                """));
        }
    }

    @Test
    void selectsOneDefaultWhenLegacySelectionFlagsAreMalformed() throws Exception
    {
        try(Connection connection = fixture("malformed-default-scan-lists"))
        {
            execute(connection, "INSERT INTO scan_list(sort_order, name, published, is_default) " +
                "VALUES (1, 'Secondary', 1, 0)");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, "UPDATE scan_list SET is_default=2");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");
            assertEquals(1, new Format10To11DatabaseMigration().validateSource(connection).stream()
                .filter(effect -> effect.subject().equals("Default scan list"))
                .findFirst().orElseThrow().affectedRows());

            migrate(connection);

            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM scan_list WHERE is_default=1"));
            assertEquals("Default", scalar(connection, "SELECT name FROM scan_list WHERE is_default=1"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM scan_list WHERE is_default NOT IN (0, 1)"));
        }
    }

    @Test
    void renamesCaseInsensitiveOldNameInPlaceAndPreservesAliasesAndRouting() throws Exception
    {
        try(Connection connection = fixture())
        {
            addList(connection, "default nbfm", "NBFM");
            String id = scalar(connection, "SELECT id FROM alias_list");
            execute(connection, """
                UPDATE alias_list SET unmatched_talkgroup_record_enabled=1;
                """);
            execute(connection, "INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value,record_enabled) " +
                "VALUES (" + id + ",'Preserve alias','TALKGROUP','NBFM',1,1)");
            execute(connection, "INSERT INTO scan_list(name) VALUES ('Private analog')");
            execute(connection, "INSERT INTO alias_list_unmatched_talkgroup_scan_list_membership " +
                "SELECT " + id + ",id FROM scan_list WHERE name='Private analog'");
            execute(connection, "INSERT INTO alias_list_unmatched_talkgroup_stream(alias_list_id,channel_name) " +
                "VALUES (" + id + ",'Fixture stream')");
            assign(connection, 2, "DEFAULT NBFM");
            String rest = scalar(connection,
                "SELECT json_remove(config_json, '$.aliasListName') FROM configuration_channel WHERE id=2");

            migrate(connection);

            assertEquals(id, scalar(connection, "SELECT id FROM alias_list WHERE name='Default Analog'"));
            assertEquals("1", scalar(connection,
                "SELECT unmatched_talkgroup_record_enabled FROM alias_list WHERE id=" + id));
            assertEquals("Preserve alias", scalar(connection, "SELECT name FROM alias WHERE alias_list_id=" + id));
            assertEquals("Private analog", scalar(connection, "SELECT s.name FROM scan_list s JOIN " +
                "alias_list_unmatched_talkgroup_scan_list_membership m ON m.scan_list_id=s.id WHERE m.alias_list_id=" + id));
            assertEquals("Fixture stream", scalar(connection,
                "SELECT channel_name FROM alias_list_unmatched_talkgroup_stream WHERE alias_list_id=" + id));
            assertEquals("Default Analog", scalar(connection,
                "SELECT alias_list_name FROM configuration_channel WHERE id=2"));
            assertEquals(rest, scalar(connection,
                "SELECT json_remove(config_json, '$.aliasListName') FROM configuration_channel WHERE id=2"));
        }
    }

    @Test
    void usesStoredFactorySpellingWithoutEnablingExistingRoutes() throws Exception
    {
        try(Connection connection = fixture())
        {
            addList(connection, "default analog", "NBFM");
            assign(connection, 2, "   ");
            migrate(connection);
            assertEquals("default analog", scalar(connection,
                "SELECT alias_list_name FROM configuration_channel WHERE id=2"));
            assertEquals("0", scalar(connection, """
                SELECT count(*) FROM alias_list_unmatched_talkgroup_scan_list_membership m
                JOIN alias_list a ON a.id=m.alias_list_id WHERE a.family='NBFM'
                """));
        }
    }

    @Test
    void preservesBothNamesAndExistingRoutingWhenTargetAlreadyExists() throws Exception
    {
        try(Connection connection = fixture())
        {
            addList(connection, "Default NBFM", "NBFM");
            addList(connection, "default analog", "NBFM");
            assign(connection, 2, "Default NBFM");
            String before = scalar(connection, "SELECT config_json FROM configuration_channel WHERE id=2");
            migrate(connection);
            assertEquals(before, scalar(connection, "SELECT config_json FROM configuration_channel WHERE id=2"));
            assertEquals("2", scalar(connection, "SELECT count(*) FROM alias_list WHERE family='NBFM'"));
            assertEquals("0", scalar(connection, """
                SELECT count(*) FROM alias_list_unmatched_talkgroup_scan_list_membership m
                JOIN alias_list a ON a.id=m.alias_list_id WHERE a.family='NBFM'
                """));
        }
    }

    @Test
    void keepsCustomAnalogNamesAndWrongFamilyLegacyName() throws Exception
    {
        try(Connection connection = fixture())
        {
            addList(connection, "County NBFM", "NBFM");
            addList(connection, "Default NBFM", "P25");
            assign(connection, 2, "County NBFM");
            migrate(connection);
            assertEquals("County NBFM", scalar(connection,
                "SELECT alias_list_name FROM configuration_channel WHERE id=2"));
            assertEquals("P25", scalar(connection, "SELECT family FROM alias_list WHERE name='Default NBFM'"));
            assertEquals("NBFM", scalar(connection, "SELECT family FROM alias_list WHERE name='Default Analog'"));
        }
    }

    @Test
    void assignsAllSupportedDecoderMappings() throws Exception
    {
        for(String mapping: new String[]{"AM,Default Analog", "NBFM,Default Analog", "P25_CONVENTIONAL,Default P25",
            "P25_PHASE1,Default P25", "P25_PHASE2,Default P25", "DMR,Default DMR", "NXDN,Default NXDN"})
        {
            String[] values = mapping.split(",");
            assertMapping(values[0], values[1]);
        }
    }

    private void assertMapping(String decoder, String expected) throws Exception
    {
        try(Connection connection = fixture(decoder))
        {
            // Exercise the frozen migration mapping; the catalog validates the saved document independently.
            try(var update = connection.prepareStatement("UPDATE configuration_channel SET decoder_type=? WHERE id=2"))
            {
                update.setString(1, decoder);
                update.executeUpdate();
            }
            new Format10To11DatabaseMigration().migrate(connection);
            assertEquals(expected, scalar(connection, "SELECT alias_list_name FROM configuration_channel WHERE id=2"));
        }
    }

    @Test
    void preservesWrongFamilyFactoryTargetsUnderUniqueNames() throws Exception
    {
        for(String mapping: new String[]{"Default P25,DMR", "Default DMR,P25", "Default NXDN,NBFM", "Default Analog,P25"})
        {
            String[] values = mapping.split(",");
            assertWrongFamilyRepair(values[0], values[1]);
        }
    }

    @Test
    void collisionRepairUsesAUniqueNameAndUpdatesEverySavedNameReference() throws Exception
    {
        try(Connection connection = fixture("collision-references"))
        {
            addList(connection, "Default P25 (DMR)", "DMR");
            addList(connection, "Default P25", "DMR");
            String customId = scalar(connection, "SELECT id FROM alias_list WHERE name='Default P25'");
            execute(connection, "UPDATE alias_list SET unmatched_talkgroup_record_enabled=1 WHERE id=" + customId);
            execute(connection, "INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) VALUES (" +
                customId + ",'Preserved','TALKGROUP','DMR',101)");
            execute(connection, "INSERT INTO alias_list_unmatched_talkgroup_scan_list_membership " +
                "SELECT " + customId + ",id FROM scan_list WHERE is_default=1");
            assign(connection, 1, "Default P25");
            execute(connection, "INSERT INTO receiver_context(context_key,kind_code,protocol_code,alias_list_name," +
                "first_seen_ms,last_seen_ms) VALUES ('collision-context',1,1,'Default P25',1,1)");
            execute(connection, "INSERT INTO p25_site_snapshot(guid,alias_list_name,first_seen_ms,last_seen_ms) " +
                "VALUES ('collision-p25','Default P25',1,1)");
            execute(connection, "INSERT INTO trunked_site_snapshot(guid,snapshot_hash,protocol_code," +
                "alias_list_name,first_seen_ms,last_seen_ms) " +
                "VALUES ('collision-trunked','collision-hash',3,'Default P25',1,1)");

            migrate(connection);

            String replacement = "Default P25 (DMR) 2";
            assertEquals(replacement, scalar(connection, "SELECT name FROM alias_list WHERE id=" + customId));
            assertEquals("1", scalar(connection,
                "SELECT unmatched_talkgroup_record_enabled FROM alias_list WHERE id=" + customId));
            assertEquals("Preserved", scalar(connection, "SELECT name FROM alias WHERE alias_list_id=" + customId));
            assertEquals("1", scalar(connection, "SELECT count(*) FROM " +
                "alias_list_unmatched_talkgroup_scan_list_membership WHERE alias_list_id=" + customId));
            assertEquals(replacement, scalar(connection,
                "SELECT alias_list_name FROM configuration_channel WHERE id=1"));
            assertEquals(replacement, scalar(connection,
                "SELECT json_extract(config_json, '$.aliasListName') FROM configuration_channel WHERE id=1"));
            for(String table: new String[]{"receiver_context", "p25_site_snapshot", "trunked_site_snapshot"})
            {
                assertEquals("0", scalar(connection,
                    "SELECT count(*) FROM " + table + " WHERE alias_list_name='Default P25'"));
                assertTrue(Integer.parseInt(scalar(connection,
                    "SELECT count(*) FROM " + table + " WHERE alias_list_name='" + replacement + "'")) > 0);
            }
        }
    }

    private void assertWrongFamilyRepair(String name, String family) throws Exception
    {
        try(Connection connection = fixture(name))
        {
            addList(connection, name, family);
            String customId = scalar(connection, "SELECT id FROM alias_list WHERE name='" + name + "'");
            var effects = new Format10To11DatabaseMigration().validateSource(connection);
            assertEquals(1, effects.stream().filter(effect ->
                effect.subject().equals("custom Alias Lists using factory names")).findFirst().orElseThrow()
                .affectedRows());

            migrate(connection);

            assertEquals(family, scalar(connection,
                "SELECT family FROM alias_list WHERE id=" + customId));
            assertEquals(name + " (" + family + ")", scalar(connection,
                "SELECT name FROM alias_list WHERE id=" + customId));
            assertEquals(expectedFactoryFamily(name), scalar(connection,
                "SELECT family FROM alias_list WHERE name='" + name + "'"));
        }
    }

    @Test
    void repairsChannelJsonFromTheAliasListProjectionUsedByThePreviousRuntime() throws Exception
    {
        try(Connection connection = fixture())
        {
            addList(connection, "Local Analog", "NBFM");
            execute(connection, "UPDATE configuration_channel SET alias_list_name='Local Analog' WHERE id=2");
            assertEquals(1, new Format10To11DatabaseMigration().validateSource(connection).stream()
                .filter(effect -> effect.subject().equals("saved channel Alias List projections"))
                .findFirst().orElseThrow().affectedRows());

            migrate(connection);

            assertEquals("Local Analog", scalar(connection,
                "SELECT alias_list_name FROM configuration_channel WHERE id=2"));
            assertEquals("Local Analog", scalar(connection,
                "SELECT json_extract(config_json, '$.aliasListName') FROM configuration_channel WHERE id=2"));
        }
    }

    @Test
    void recoversMatchingJsonAliasListWhenRelationalProjectionIsMissing() throws Exception
    {
        try(Connection connection = fixture("json-owned-alias-list"))
        {
            addList(connection, "Local P25", "P25");
            execute(connection, """
                UPDATE configuration_channel
                SET alias_list_name=NULL,
                    config_json=json_set(config_json, '$.aliasListName', 'Local P25')
                WHERE id=1
                """);
            assertEquals(1, new Format10To11DatabaseMigration().validateSource(connection).stream()
                .filter(effect -> effect.subject().equals("saved channel Alias List projections"))
                .findFirst().orElseThrow().affectedRows());

            migrate(connection);

            assertEquals("Local P25", scalar(connection,
                "SELECT alias_list_name FROM configuration_channel WHERE id=1"));
            assertEquals("Local P25", scalar(connection,
                "SELECT json_extract(config_json, '$.aliasListName') FROM configuration_channel WHERE id=1"));
        }
    }

    private static String expectedFactoryFamily(String name)
    {
        return switch(name)
        {
            case "Default P25" -> "P25";
            case "Default DMR" -> "DMR";
            case "Default NXDN" -> "NXDN";
            case "Default Analog" -> "NBFM";
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    void rollbackAndRetryPreserveSourceAndCurrentStartupDoesNotRestoreDeletedLists() throws Exception
    {
        try(Connection connection = fixture())
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.rollback();
            connection.setAutoCommit(true);
            assertEquals(10, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("0", scalar(connection, "SELECT count(*) FROM alias_list"));
            migrate(connection);
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseMigrationChain.migrate(connection).target().version());
            execute(connection, "DELETE FROM alias_list WHERE name='Default DMR'");
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            DatabaseFormatCatalog.requireCurrent(connection);
            assertEquals("0", scalar(connection, "SELECT count(*) FROM alias_list WHERE name='Default DMR'"));
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(mTemporaryFolder.resolve("fixture.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mTemporaryFolder.resolve("fixture.sqlite")))
        {
            assertEquals("0", scalar(connection, "SELECT count(*) FROM alias_list WHERE name='Default DMR'"));
        }
    }

    private Connection fixture() throws Exception
    {
        return fixture("fixture");
    }

    private Connection fixture(String name) throws Exception
    {
        Path database = Format10TestDatabase.create(mTemporaryFolder.resolve(name + ".sqlite"));
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        execute(connection, "PRAGMA foreign_keys=ON");
        // Use the exact populated historical image, then model a profile without factory lists.
        execute(connection, "DELETE FROM alias");
        execute(connection, "DELETE FROM alias_list");
        execute(connection, "UPDATE configuration_channel SET alias_list_name=NULL, " +
            "config_json=json_set(config_json, '$.aliasListName', NULL)");
        return connection;
    }

    private static void migrate(Connection connection) throws Exception
    {
        connection.setAutoCommit(false);
        try
        {
            new Format10To11DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 11);
            connection.commit();
        }
        catch(Exception e)
        {
            connection.rollback();
            throw e;
        }
        finally
        {
            connection.setAutoCommit(true);
        }
        assertEquals(11, DatabaseFormatCatalog.inspect(connection).version());
        assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
        assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
    }

    private static void addList(Connection connection, String name, String family) throws SQLException
    {
        try(var statement = connection.prepareStatement("INSERT INTO alias_list(name,family) VALUES (?,?)"))
        {
            statement.setString(1, name);
            statement.setString(2, family);
            statement.executeUpdate();
        }
    }

    private static void assign(Connection connection, int id, String name) throws SQLException
    {
        try(var statement = connection.prepareStatement("UPDATE configuration_channel SET alias_list_name=?, " +
            "config_json=json_set(config_json, '$.aliasListName', ?) WHERE id=?"))
        {
            statement.setString(1, name);
            statement.setString(2, name);
            statement.setInt(3, id);
            statement.executeUpdate();
        }
    }

    private static String settings(Connection connection) throws SQLException
    {
        return scalar(connection, "SELECT group_concat(value, '|') FROM " +
            "(SELECT key || ':' || settings_json AS value FROM application_settings " +
                "WHERE key NOT IN ('setup_wizard','spectrum_snap_country') ORDER BY key)");
    }

    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException
    {
        try(var statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }
}
