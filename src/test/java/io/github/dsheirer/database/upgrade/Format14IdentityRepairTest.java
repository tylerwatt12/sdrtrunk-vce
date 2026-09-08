/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format14IdentityRepairTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void defaultsJsonUnsafeFormat14HighWaterToRetainedMaximum() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("unsafe-sequence.sqlite"));
        try(Connection connection = open(database))
        {
            long retainedMaximum = number(connection, "SELECT max(id) FROM alias");
            execute(connection, "UPDATE sqlite_sequence SET seq=" +
                SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM + " WHERE name='alias'");
            connection.setAutoCommit(false);

            List<DatabaseMigrationEffect> effects =
                new Format14To15DatabaseMigration().migrateAndReport(connection);

            assertEquals(1, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "SQLite identity high-water marks").affectedRows());
            assertEquals(retainedMaximum,
                number(connection, "SELECT seq FROM sqlite_sequence WHERE name='alias'"));
            assertEquals(retainedMaximum + 1, insertAliasProbe(connection));
            connection.rollback();
        }
    }

    @Test
    void generatedDefaultNeverUsesTheJsonSafeCeilingWhenSourceSequenceIsExhausted() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("exhausted-default-sequence.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, "DELETE FROM alias_scan_list_membership");
            execute(connection, "DELETE FROM alias_list_unmatched_talkgroup_scan_list_membership");
            execute(connection, "DELETE FROM scan_list");
            execute(connection, "UPDATE sqlite_sequence SET seq=" +
                (SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM - 1) + " WHERE name='scan_list'");
            connection.setAutoCommit(false);

            List<DatabaseMigrationEffect> effects =
                new Format14To15DatabaseMigration().migrateAndReport(connection);

            assertEquals(1, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "SQLite identity high-water marks").affectedRows());
            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM scan_list WHERE is_default=1 AND published=1"));
            long generatedId = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            assertTrue(generatedId > 0 && generatedId < SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM);
            assertEquals(generatedId,
                number(connection, "SELECT seq FROM sqlite_sequence WHERE name='scan_list'"));
            connection.rollback();
        }
    }

    @Test
    void dropsOnlyFormat14AliasAtExhaustedJsonSafeBoundary() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("unsafe-alias-id.sqlite"));
        try(Connection connection = open(database))
        {
            long owningList = number(connection,
                "SELECT id FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1");
            long retainedAliases = number(connection, "SELECT COUNT(*) FROM alias");
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (%d, %d, 'Unsafe format-14 Alias', 'TALKGROUP', 'APCO25', 88102)
                """.formatted(SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM, owningList));
            connection.setAutoCommit(false);

            List<DatabaseMigrationEffect> effects =
                new Format14To15DatabaseMigration().migrateAndReport(connection);

            assertTrue(effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "unusable Alias, Alias List, and scan-list rows").affectedRows() >= 1);
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM alias WHERE name='Unsafe format-14 Alias'"));
            assertEquals(retainedAliases, number(connection, "SELECT COUNT(*) FROM alias"));
            assertTrue(number(connection, "SELECT seq FROM sqlite_sequence WHERE name='alias'") <
                SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM);
            connection.rollback();
        }
    }

    private static long insertAliasProbe(Connection connection) throws Exception
    {
        try(var statement = connection.prepareStatement("""
            INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value)
            VALUES ((SELECT id FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1),
                    'Identity probe', 'TALKGROUP', 'APCO25', 88103)
            """, Statement.RETURN_GENERATED_KEYS))
        {
            assertEquals(1, statement.executeUpdate());
            try(ResultSet keys = statement.getGeneratedKeys())
            {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static DatabaseMigrationEffect effect(List<DatabaseMigrationEffect> effects,
                                                  DatabaseMigrationEffect.Kind kind, String subject)
    {
        return effects.stream().filter(candidate -> candidate.kind() == kind && subject.equals(candidate.subject()))
            .findFirst().orElseThrow();
    }

    private static Connection open(Path path) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private static long number(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
