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

package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.stats.activity.DmrActivitySchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

class SdrTrunkDatabaseStartupTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void writeTransactionIsImmediateAndPreservesConnectionSafetySettings() throws Exception
    {
        Path database = mTemporaryFolder.resolve("immediate-write.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = SdrTrunkDatabase.openWriteTransaction(database);
            Statement statement = connection.createStatement())
        {
            SQLiteConnection sqlite = (SQLiteConnection)connection;
            assertFalse(connection.getAutoCommit());
            assertEquals(SQLiteConfig.TransactionMode.IMMEDIATE, sqlite.getCurrentTransactionMode());
            assertEquals(Integer.toString(SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS),
                scalar(statement, "PRAGMA busy_timeout"));
            assertEquals("1", scalar(statement, "PRAGMA foreign_keys"));
            connection.rollback();
        }
    }

    @Test
    void completedImmediateWriteDoesNotReserveTheWriterAgain() throws Exception
    {
        Path database = mTemporaryFolder.resolve("completed-immediate-write.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try(Connection first = SdrTrunkDatabase.openWriteTransaction(database);
            Statement firstStatement = first.createStatement())
        {
            firstStatement.executeUpdate("""
                UPDATE database_metadata
                SET updated_at_ms = updated_at_ms + 1
                WHERE key = 'database_format_version'
                """);
            SdrTrunkDatabase.commitWriteTransaction(first);
            assertTrue(first.getAutoCommit());

            Future<?> competingWriter = executor.submit(() ->
            {
                try(Connection second = SdrTrunkDatabase.open(database);
                    Statement secondStatement = second.createStatement())
                {
                    secondStatement.execute("PRAGMA busy_timeout=0");
                    secondStatement.executeUpdate("""
                        UPDATE database_metadata
                        SET updated_at_ms = updated_at_ms + 1
                        WHERE key = 'database_format_version'
                        """);
                }
                return null;
            });

            competingWriter.get(2, TimeUnit.SECONDS);
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    @Test
    void freshSchemaOmitsRetiredNamedChannelMaps() throws Exception
    {
        Path database = mTemporaryFolder.resolve("fresh-schema.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(connection);
            SdrTrunkDatabaseSchema.validate(connection);
            assertEquals("0", scalar(statement, """
                SELECT COUNT(*) FROM sqlite_master
                WHERE name IN ('configuration_channel_map', 'idx_configuration_channel_map_sort')
                """));
            assertEquals("2", scalar(statement, """
                SELECT sum(count) FROM (
                    SELECT COUNT(*) AS count
                    FROM pragma_table_info('alias_talkgroup')
                    WHERE name = 'alias_list_id'
                    UNION ALL
                    SELECT COUNT(*) AS count
                    FROM pragma_table_info('alias_radio')
                    WHERE name = 'alias_list_id'
                )
                """));
            assertEquals("0", scalar(statement, """
                SELECT COUNT(*) FROM (
                    SELECT name FROM pragma_table_info('alias_talkgroup')
                    UNION ALL
                    SELECT name FROM pragma_table_info('alias_radio')
                )
                WHERE name = 'alias_list_name'
                """));
            assertEquals("0", scalar(statement, """
                SELECT COUNT(*) FROM database_metadata
                WHERE key IN ('alias_schema_version', 'configuration_schema_version',
                    'settings_schema_version', 'icon_schema_version')
                """));
        }
    }

    @Test
    void currentAdministrativeSchemaRejectsAmbiguousScalarAndPayloadRepresentations() throws Exception
    {
        Path database = mTemporaryFolder.resolve("strict-current-schema.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            statement.executeUpdate("INSERT INTO alias_list(id, name, family) VALUES (1, 'Dispatch', 'P25')");

            for(String sql: List.of(
                "INSERT INTO database_metadata(key,value,updated_at_ms) VALUES ('', '1', 1)",
                "INSERT INTO database_metadata(key,value,updated_at_ms) VALUES ('bad-time', '1', 0)",
                "INSERT INTO database_metadata(key,value,updated_at_ms) VALUES ('bad-value', x'01', 1)",
                "INSERT INTO application_settings(key,settings_json,updated_at_ms) VALUES ('bad-json', '{', 1)",
                "INSERT INTO application_settings(key,settings_json,updated_at_ms) VALUES ('bad-time', '{}', 0)",
                "INSERT INTO application_icons(key,icons_json,updated_at_ms) VALUES ('icons', '[]', 1)",
                "INSERT INTO alias_list(id,name,family) VALUES (-1,'Bad ID','P25')",
                "INSERT INTO alias_list(name,family) VALUES ('','P25')",
                "INSERT INTO alias_list(name,family) VALUES ('This Alias List Name Is Too Long','P25')",
                "INSERT INTO alias_list(name,family,unmatched_talkgroup_record_enabled) VALUES ('Bad flag','P25',0.5)",
                "INSERT INTO scan_list(sort_order,name,published,is_default) VALUES (0.5,'Bad order',1,0)",
                "INSERT INTO scan_list(sort_order,name,published,is_default) VALUES (1,'Bad flag',0.5,0)",
                "INSERT INTO alias(alias_list_id,name,color,record_enabled,matcher_type,protocol,value) " +
                    "VALUES (1,'Bad color',0.5,0,'TALKGROUP','APCO25',91)",
                "INSERT INTO alias(alias_list_id,name,color,record_enabled,matcher_type,protocol,value) " +
                    "VALUES (1,'Bad record',0,2,'TALKGROUP','APCO25',91)",
                "INSERT INTO alias(alias_list_id,name,color,record_enabled,matcher_type,protocol,value) " +
                    "VALUES (1,'',0,0,'TALKGROUP','APCO25',91)",
                "INSERT INTO alias(alias_list_id,name,color,record_enabled,matcher_type,protocol,value) " +
                    "VALUES (1,'Bad protocol',0,0,'TALKGROUP','UNKNOWN',91)",
                "INSERT INTO alias(alias_list_id,name,color,record_enabled,matcher_type,protocol,value) " +
                    "VALUES (1,'Bad identifier',0,0,'TALKGROUP','APCO25',-1)",
                "INSERT INTO alias(alias_list_id,name,color,record_enabled,matcher_type,numeric_value) " +
                    "VALUES (1,'Bad status',0,0,'STATUS',256)",
                "INSERT INTO alias(alias_list_id,name,color,stream_as_talkgroup,record_enabled," +
                    "matcher_type,protocol,value) VALUES (1,'Bad stream',0,0,0,'TALKGROUP','APCO25',91)",
                "INSERT INTO alias(alias_list_id,name,color,record_enabled,matcher_type,protocol,value,text_value) " +
                    "VALUES (1,'Mixed payload',0,0,'TALKGROUP','APCO25',91,'unused')",
                "INSERT INTO alias(alias_list_id,name,color,record_enabled,matcher_type,protocol,min_value,max_value) " +
                    "VALUES (1,'Empty range',0,0,'TALKGROUP_RANGE','APCO25',91,91)",
                "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,config_json) " +
                    "VALUES ('11111111-1111-4111-8111-111111111111','CONVENTIONAL',-1,'{}')",
                "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,config_json) " +
                    "VALUES ('11111111-1111-4111-8111-111111111111','CONVENTIONAL',0.5,'{}')",
                "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,decoder_type,config_json) " +
                    "VALUES ('11111111-1111-4111-8111-111111111111','CONVENTIONAL',0,'  ','{}')",
                "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,decoder_type,config_json) " +
                    "VALUES ('11111111-1111-4111-8111-111111111111','CONVENTIONAL',0,'MPT1327','{}')",
                "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,decoder_type,config_json) " +
                    "VALUES ('11111111-1111-4111-8111-111111111111','CONVENTIONAL',0,'DMR','{}')",
                "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,decoder_type,config_json) " +
                    "VALUES ('11111111-1111-4111-8111-111111111111','CONVENTIONAL',0,'NXDN'," +
                    "'{\"decodeConfiguration\":{\"channelMode\":\"UNKNOWN\"}}')",
                "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,primary_frequency_hz," +
                    "config_json) VALUES ('11111111-1111-4111-8111-111111111111','CONVENTIONAL',0,0,'{}')",
                "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,primary_frequency_hz," +
                    "config_json) VALUES ('11111111-1111-4111-8111-111111111111','CONVENTIONAL',0,121.5,'{}')",
                "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,config_json) " +
                    "VALUES ('11111111-1111-4111-8111-111111111111','CONVENTIONAL',0,'[]')",
                "INSERT INTO configuration_broadcast_stream(configuration_id,sort_order,config_json) " +
                    "VALUES ('22222222-2222-4222-8222-222222222222',0.5,'{}')",
                "INSERT INTO configuration_broadcast_stream(configuration_id,sort_order,config_json) " +
                    "VALUES ('22222222-2222-4222-8222-222222222222',0,'[]')",
                "INSERT INTO web_user(username,tier,primary_admin,credential_version,password_algorithm," +
                    "password_iterations,password_derived_key_bits,password_salt,password_hash," +
                    "password_changed_at_ms,auth_revision,preferences_json,preferences_revision," +
                    "created_at_ms,updated_at_ms) VALUES ('operator','USER',0.5,1," +
                    "'PBKDF2WithHmacSHA256',600000,256,zeroblob(16),zeroblob(32),1,1,'{}',1,1,1)",
                "INSERT INTO web_user(username,tier,primary_admin,credential_version,password_algorithm," +
                    "password_iterations,password_derived_key_bits,password_salt,password_hash," +
                    "password_changed_at_ms,auth_revision,preferences_json,preferences_revision," +
                    "created_at_ms,updated_at_ms) VALUES ('operator','USER',0,1," +
                    "'PBKDF2WithHmacSHA256',600000,256,zeroblob(16),zeroblob(32),1,1,'[]',1,1,1)",
                "INSERT INTO web_user(username,tier,primary_admin,credential_version,password_algorithm," +
                    "password_iterations,password_derived_key_bits,password_salt,password_hash," +
                    "password_changed_at_ms,auth_revision,preferences_json,preferences_revision," +
                    "created_at_ms,updated_at_ms) VALUES ('bad user','USER',0,1," +
                    "'PBKDF2WithHmacSHA256',600000,256,zeroblob(16),zeroblob(32),1,1,'{}',1,1,1)",
                "INSERT INTO web_access_policy(capability_id,required_tier,updated_at_ms) " +
                    "VALUES ('receiver-control','ADMIN',0.5)"))
            {
                assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(sql), sql);
            }

            statement.executeUpdate("""
                INSERT INTO alias(alias_list_id,name,color,stream_as_talkgroup,record_enabled,
                    matcher_type,protocol,min_value,max_value)
                VALUES (1,'Valid range',-1,91,1,'TALKGROUP_RANGE','APCO25',1,65535)
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,decoder_type,
                    primary_frequency_hz,config_json)
                VALUES ('11111111-1111-4111-8111-111111111111','CONVENTIONAL',0,'AM',121900000,'{}')
                """);
        }
    }

    @Test
    void configurationRowsRejectDuplicateJsonAuthorityAndNoncanonicalRadioResolveIds() throws Exception
    {
        Path database = mTemporaryFolder.resolve("configuration-authority.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(connection);
            String base = """
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, decoder_type, config_json, radioresolve_id
                ) VALUES ('11111111-1111-4111-8111-111111111111', 'TRUNKED', 0, 'P25_PHASE1', %s, %s)
                """;
            assertThrows(java.sql.SQLException.class,
                () -> statement.executeUpdate(base.formatted("'{\"name\":\"duplicate\"}'", "NULL")));
            assertThrows(java.sql.SQLException.class,
                () -> statement.executeUpdate(base.formatted("'{}'", "''")));
            statement.executeUpdate(base.formatted("'{}'", "NULL"));

            for(String property: List.of("configurationId", "system", "site", "name", "aliasListId",
                "aliasListName", "radioResolveId", "radresGuid", "radres_guid", "autoStart", "enabled",
                "autoStartOrder", "order", "channelType"))
            {
                assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                    UPDATE configuration_channel
                    SET config_json=json_set(config_json, '$.%s', 'duplicate')
                    """.formatted(property)), property);
            }

            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO configuration_broadcast_stream(configuration_id, sort_order, config_json)
                VALUES ('22222222-2222-4222-8222-222222222222', 0,
                    '{"configurationId":"33333333-3333-4333-8333-333333333333"}')
                """));
            statement.executeUpdate("""
                INSERT INTO configuration_broadcast_stream(configuration_id, sort_order, config_json)
                VALUES ('22222222-2222-4222-8222-222222222222', 0, '{}')
                """);
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                UPDATE configuration_broadcast_stream
                SET config_json=json_set(config_json, '$.configurationId',
                    '33333333-3333-4333-8333-333333333333')
                """));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                UPDATE configuration_broadcast_stream
                SET config_json=json_set(config_json, '$.aliasListName', 'Duplicate display name')
                """));
        }
    }

    @Test
    void createsAndValidatesCurrentDmrSchemaWithoutASecondVersionMarker() throws Exception
    {
        Path database = mTemporaryFolder.resolve("new-global.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        assertEquals("wal", journalMode(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT value FROM database_metadata WHERE key = 'dmr_activity_schema_version'
                """))
        {
            assertFalse(resultSet.next());
            DmrActivitySchema.validate(connection);
        }
    }

    @Test
    void createsAndValidatesUnmatchedTalkgroupScanListMembershipSchema() throws Exception
    {
        Path database = mTemporaryFolder.resolve("unmatched-scan-lists.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            try(ResultSet resultSet = statement.executeQuery("""
                SELECT sql FROM sqlite_master
                WHERE type = 'table'
                  AND name = 'alias_list_unmatched_talkgroup_scan_list_membership'
                """))
            {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getString("sql").contains("PRIMARY KEY(alias_list_id, scan_list_id)"));
                assertTrue(resultSet.getString("sql").contains("WITHOUT ROWID"));
            }

            try(ResultSet resultSet = statement.executeQuery("""
                SELECT sql FROM sqlite_master
                WHERE type = 'index'
                  AND name = 'idx_alias_list_unmatched_talkgroup_scan_list_by_list'
                """))
            {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getString("sql").contains("scan_list_id, alias_list_id"));
            }
        }

        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    @Test
    void defaultAliasListSeedReusesMatchingFamilyAndRoutesEveryFamily() throws Exception
    {
        Path database = mTemporaryFolder.resolve("default-alias-lists.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(connection);
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family)
                VALUES (25, 'Default P25', 'P25')
                """);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);

            assertEquals("25:P25|26:DMR|27:NXDN|28:NBFM", scalar(statement, """
                SELECT group_concat(value, '|')
                FROM (
                    SELECT id || ':' || family AS value
                    FROM alias_list
                    WHERE name IN ('Default P25', 'Default DMR', 'Default NXDN', 'Default Analog')
                    ORDER BY id
                )
                """));
            assertEquals("Default Analog|Default DMR|Default NXDN|Default P25", scalar(statement, """
                SELECT group_concat(name, '|')
                FROM (
                    SELECT alias_list.name
                    FROM alias_list
                    JOIN alias_list_unmatched_talkgroup_scan_list_membership AS membership
                      ON membership.alias_list_id = alias_list.id
                    JOIN scan_list ON scan_list.id = membership.scan_list_id
                    WHERE scan_list.is_default = 1
                    ORDER BY alias_list.name
                )
                """));
        }
    }

    @Test
    void defaultAliasListSeedRejectsWrongFamilyNameCollisionBeforeChangingRows() throws Exception
    {
        Path database = mTemporaryFolder.resolve("wrong-family-default.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(connection);
            statement.executeUpdate("""
                INSERT INTO alias_list(name, family)
                VALUES ('Default P25', 'DMR')
                """);

            java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
                () -> SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection));
            assertTrue(failure.getMessage().contains("Default P25"));
            assertTrue(failure.getMessage().contains("expected [P25]"));
            assertEquals("1", scalar(statement, "SELECT COUNT(*) FROM alias_list"));
            assertEquals("0", scalar(statement,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership"));
        }
    }

    @Test
    void rejectsIncompleteExistingSchemaWithoutRepairingIt() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);
        }

        assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'p25_system'
                """))
        {
            assertFalse(resultSet.next());
        }
    }

    @Test
    void rejectsMissingDmrSchemaWithoutRepairingIt() throws Exception
    {
        Path database = mTemporaryFolder.resolve("missing-dmr.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.TALKGROUP_RETENTION_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.RADIO_RETENTION_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.TALKGROUP_RECEIVER_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.RADIO_RECEIVER_INDEX);
            statement.executeUpdate("DROP TABLE " + DmrActivitySchema.TALKGROUP_TABLE);
            statement.executeUpdate("DROP TABLE " + DmrActivitySchema.RADIO_TABLE);
        }

        assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT 1 FROM sqlite_master WHERE type = 'table'
                    AND name = 'dmr_conventional_talkgroup_summary'
                """))
        {
            assertFalse(resultSet.next());
        }
    }

    @Test
    void rejectsChangedAliasTableDefinitionWithoutRepairingIt() throws Exception
    {
        Path database = mTemporaryFolder.resolve("changed-alias-definition.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA writable_schema=ON");
            statement.executeUpdate("""
                UPDATE sqlite_master
                SET sql = replace(sql, 'alias_list_id INTEGER', 'alias_list_id TEXT')
                WHERE type = 'table' AND name = 'alias'
                """);
            statement.execute("PRAGMA writable_schema=OFF");
        }

        assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'alias'
                """))
        {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getString(1).contains("alias_list_id TEXT"));
        }
    }

    @Test
    void rejectsCurrentTupleWithWebfirstRecordingTable() throws Exception
    {
        Path database = mTemporaryFolder.resolve("webfirst-table.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE recorded_call(id INTEGER PRIMARY KEY)");
        }

        java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        assertTrue(failure.getMessage().contains("webfirst managed-recording"));
    }

    @Test
    void rejectsWebfirstFootprintWithoutChangingDatabaseOrJournalMode() throws Exception
    {
        Path database = mTemporaryFolder.resolve("webfirst-read-only-rejection.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.executeUpdate("CREATE TABLE recorded_call(id INTEGER PRIMARY KEY)");
        }
        assertEquals("delete", journalMode(database));
        byte[] before = Files.readAllBytes(database);
        FileTime modifiedBefore = Files.getLastModifiedTime(database);
        List<String> siblingsBefore;
        try(var files = Files.list(database.getParent()))
        {
            siblingsBefore = files.map(path -> path.getFileName().toString()).sorted().toList();
        }

        java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        assertTrue(failure.getMessage().contains("webfirst managed-recording"));
        assertEquals("delete", journalMode(database));
        assertArrayEquals(before, Files.readAllBytes(database));
        assertEquals(modifiedBefore, Files.getLastModifiedTime(database));
        try(var files = Files.list(database.getParent()))
        {
            assertEquals(siblingsBefore,
                files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void rejectsCurrentTupleWithWebfirstRecordingMetadata() throws Exception
    {
        Path database = mTemporaryFolder.resolve("webfirst-metadata.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO database_metadata(key, value, updated_at_ms)
                VALUES ('recorded_call_catalog_schema_version', '3', 1)
                """);
        }

        java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        assertTrue(failure.getMessage().contains("recorded_call_catalog_schema_version"));
    }

    @Test
    void similarlyNamedNonWebfirstMetadataIsAccepted() throws Exception
    {
        Path database = mTemporaryFolder.resolve("non-webfirst-names.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO database_metadata(key, value, updated_at_ms)
                VALUES ('recordedXcall_catalog_schema_version', 'not-webfirst', 1)
                """);
        }

        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        assertEquals("wal", journalMode(database));
    }

    @Test
    void rejectsExtraTriggerWithoutChangingJournalMode() throws Exception
    {
        Path database = mTemporaryFolder.resolve("extra-trigger.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.executeUpdate("""
                CREATE TRIGGER unexpected_alias_trigger
                AFTER INSERT ON alias
                BEGIN
                    SELECT 1;
                END
                """);
        }

        assertEquals("delete", journalMode(database));
        java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        assertTrue(failure.getMessage().contains("Unrecognized SQLite database schema fingerprint"));
        assertEquals("delete", journalMode(database));
    }

    @Test
    void rejectsSameNamedIndexWithWrongDefinition() throws Exception
    {
        Path database = mTemporaryFolder.resolve("wrong-index-definition.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_configuration_channel_sort");
            statement.executeUpdate("""
                CREATE INDEX idx_configuration_channel_sort
                ON configuration_channel(id)
                """);
        }

        java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        assertTrue(failure.getMessage().contains("Unrecognized SQLite database schema fingerprint"));
    }

    private static String journalMode(Path database) throws Exception
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database,
            config.toProperties()); Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode"))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static String scalar(Statement statement, String sql) throws Exception
    {
        try(ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
