/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format22To23DatabaseMigrationTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir
    Path mTemporaryFolder;

    @Test
    void addsExpandedRowGroupStateAndPreservesExistingPreferences() throws Exception
    {
        Path database = Format22TestDatabase.create(mTemporaryFolder.resolve("format-22.sqlite"));
        try(Connection connection = open(database); var statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE web_user SET preferences_json=json_set(preferences_json, '$.tables.channels', json(
                  '{"schema":["name"],"column_order":["name"],"column_widths":{"name":240},"hidden_columns":[]}'))
                WHERE id=3
                """);
            assertEquals(22, DatabaseFormatCatalog.inspect(connection).version());
            String theme = scalar(connection,
                "SELECT json_extract(preferences_json, '$.appearance.theme') FROM web_user WHERE id=3");
            long revision = Long.parseLong(scalar(connection,
                "SELECT preferences_revision FROM web_user WHERE id=3"));

            List<DatabaseMigrationEffect> effects = migrate(connection);

            assertEquals(3, effects.getFirst().affectedRows());
            assertEquals(theme, scalar(connection,
                "SELECT json_extract(preferences_json, '$.appearance.theme') FROM web_user WHERE id=3"));
            assertEquals(Long.toString(revision + 1), scalar(connection,
                "SELECT preferences_revision FROM web_user WHERE id=3"));
            assertEquals("7:[]", scalar(connection, """
                SELECT json_extract(preferences_json, '$.version') || ':' ||
                       json_extract(preferences_json, '$.tables.channels.collapsed_groups')
                FROM web_user WHERE id=3
                """));
            assertEquals(23, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void rollbackRestoresVersionSixAndAllowsRetry() throws Exception
    {
        Path database = Format22TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));
        try(Connection connection = open(database))
        {
            String before = scalar(connection, "SELECT preferences_json FROM web_user WHERE id=3");
            connection.setAutoCommit(false);
            try
            {
                new Format22To23DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 23);
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }
            assertEquals(22, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, scalar(connection, "SELECT preferences_json FROM web_user WHERE id=3"));
            migrate(connection);
            assertEquals(23, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test
    void defaultsOnlyMalformedOrRevisionExhaustedPreferences() throws Exception
    {
        for(String change: List.of(
            "preferences_json=json_remove(preferences_json, '$.playback.target_grouping')",
            "preferences_revision=9223372036854775805",
            "preferences_revision=9223372036854775806"))
        {
            Path database = Format22TestDatabase.create(mTemporaryFolder.resolve(
                "invalid-" + Math.abs(change.hashCode()) + ".sqlite"));
            try(Connection connection = open(database); var statement = connection.createStatement())
            {
                String sibling = scalar(connection, "SELECT preferences_json FROM web_user WHERE id=2");
                statement.executeUpdate("UPDATE web_user SET " + change + " WHERE id=3");
                long sourceRevision = Long.parseLong(scalar(connection,
                    "SELECT preferences_revision FROM web_user WHERE id=3"));
                long expectedRevision = sourceRevision > 0 && sourceRevision < Long.MAX_VALUE - 2 ?
                    sourceRevision + 1 : 1;
                List<DatabaseMigrationEffect> effects = migrate(connection);
                assertEquals(1, effects.stream()
                    .filter(effect -> effect.subject().equals("unusable per-user browser preferences"))
                    .findFirst().orElseThrow().affectedRows());
                assertEquals(Format22WebUserPreferencesCodec.migrateToFormat23(sibling),
                    scalar(connection, "SELECT preferences_json FROM web_user WHERE id=2"));
                assertEquals(Format22WebUserPreferencesCodec.defaults(), scalar(connection,
                    "SELECT preferences_json FROM web_user WHERE id=3"));
                assertEquals(Long.toString(expectedRevision), scalar(connection,
                    "SELECT preferences_revision FROM web_user WHERE id=3"));
            }
        }
    }

    @Test
    void defaultsAPreferenceWithNonIntegerRevisionStorage() throws Exception
    {
        Path database = Format22TestDatabase.create(mTemporaryFolder.resolve("invalid-revision-storage.sqlite"));
        try(Connection connection = open(database); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            try
            {
                statement.executeUpdate("UPDATE web_user SET preferences_revision='invalid' WHERE id=3");
            }
            finally
            {
                statement.execute("PRAGMA ignore_check_constraints=OFF");
            }

            List<DatabaseMigrationEffect> effects = migrate(connection);
            assertEquals(1, effects.stream()
                .filter(effect -> effect.subject().equals("unusable per-user browser preferences"))
                .findFirst().orElseThrow().affectedRows());
            assertEquals(Format22WebUserPreferencesCodec.defaults(), scalar(connection,
                "SELECT preferences_json FROM web_user WHERE id=3"));
            assertEquals("1", scalar(connection,
                "SELECT preferences_revision FROM web_user WHERE id=3"));
        }
    }

    @Test
    void defaultsOnlyAnOversizedPreferenceDocument() throws Exception
    {
        Path database = Format22TestDatabase.create(mTemporaryFolder.resolve("oversized.sqlite"));
        try(Connection connection = open(database); var statement = connection.createStatement())
        {
            String sibling = scalar(connection, "SELECT preferences_json FROM web_user WHERE id=2");
            statement.execute("PRAGMA ignore_check_constraints=ON");
            try
            {
                statement.executeUpdate("""
                    UPDATE web_user
                    SET preferences_json='{"padding":"' || hex(zeroblob(65537)) || '"}'
                    WHERE id=3
                    """);
            }
            finally
            {
                statement.execute("PRAGMA ignore_check_constraints=OFF");
            }
            List<DatabaseMigrationEffect> effects = migrate(connection);
            assertEquals(1, effects.stream()
                .filter(effect -> effect.subject().equals("unusable per-user browser preferences"))
                .findFirst().orElseThrow().affectedRows());
            assertEquals(Format22WebUserPreferencesCodec.migrateToFormat23(sibling),
                scalar(connection, "SELECT preferences_json FROM web_user WHERE id=2"));
            assertEquals("7", scalar(connection,
                "SELECT json_extract(preferences_json, '$.version') FROM web_user WHERE id=3"));
        }
    }

    @Test
    void frozenCodecRejectsMixedAndAlreadyMigratedDocuments() throws Exception
    {
        String versionSix = Format22WebUserPreferencesCodec.format6Defaults();
        Format22WebUserPreferencesCodec.validate(versionSix);
        ObjectNode withTable = (ObjectNode)MAPPER.readTree(versionSix);
        ObjectNode layout = withTable.withObject("tables").putObject("channels");
        layout.putArray("schema").add("name");
        layout.putArray("column_order").add("name");
        layout.putObject("column_widths").put("name", 240);
        layout.putArray("hidden_columns");
        String versionSeven = Format22WebUserPreferencesCodec.migrateToFormat23(withTable.toString());
        JsonNode migrated = MAPPER.readTree(versionSeven);
        assertEquals(7, migrated.path("version").asInt());
        Format23WebUserPreferencesCodec.validate(versionSeven);
        assertThrows(IOException.class, () -> Format22WebUserPreferencesCodec.validate(versionSeven));
        assertThrows(IOException.class, () -> Format23WebUserPreferencesCodec.validate(
            versionSeven.replace(",\"collapsed_groups\":[]", "")));
        assertThrows(IOException.class, () -> Format23WebUserPreferencesCodec.validate(
            versionSeven.replace("\"collapsed_groups\":[]",
                "\"collapsed_groups\":[\"p25\",\"p25\"]")));
        assertThrows(IOException.class, () -> Format22WebUserPreferencesCodec.validate(
            versionSix.replace("\"target_grouping\":true",
                "\"target_grouping\":true,\"conversation_grouping\":true")));
    }

    private static List<DatabaseMigrationEffect> migrate(Connection connection) throws Exception
    {
        connection.setAutoCommit(false);
        try
        {
            Format22To23DatabaseMigration migration = new Format22To23DatabaseMigration();
            List<DatabaseMigrationEffect> effects = migration.validateSource(connection);
            migration.migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 23);
            connection.commit();
            return effects;
        }
        catch(Exception exception)
        {
            connection.rollback();
            throw exception;
        }
        finally
        {
            connection.setAutoCommit(true);
        }
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
