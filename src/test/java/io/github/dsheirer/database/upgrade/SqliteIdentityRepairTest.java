/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.configuration.ConfigurationIdentityAllocator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteIdentityRepairTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void normalizesDuplicateMalformedAndExhaustedCurrentAllocatorRows() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-sequence-damage.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long retainedMaximum;

        try(Connection connection = open(database))
        {
            retainedMaximum = number(connection, "SELECT coalesce(max(id), 0) FROM alias");
            execute(connection, "DELETE FROM sqlite_sequence WHERE name='alias'");
            execute(connection, "INSERT INTO sqlite_sequence(name, seq) VALUES " +
                "('alias', NULL), ('alias', -1), ('alias', " + Long.MAX_VALUE + ")");

            SqliteIdentityRepair.Inspection inspection = SqliteIdentityRepair.inspect(connection);
            assertEquals(3, inspection.repairedSequenceRows());
            assertEquals(0, inspection.droppedConfigurationRows());

            SqliteIdentityRepair.Inspection repaired = SqliteIdentityRepair.repair(connection);
            assertEquals(3, repaired.repairedSequenceRows());
            assertEquals(retainedMaximum > 0 ? 1 : 0, number(connection,
                "SELECT COUNT(*) FROM sqlite_sequence WHERE name='alias'"));
            assertEquals(retainedMaximum, number(connection,
                "SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name='alias'), 0)"));
        }

        assertEquals(retainedMaximum + 1,
            new ConfigurationIdentityAllocator(database).nextAliasIds(List.of(), 1).getFirst());
    }

    @Test
    void currentApplicationMigrationDoesNotTreatExhaustedSequenceAsANoOp() throws Exception
    {
        Path database = stagedDatabase("current-exhausted-sequence");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            execute(connection, "DELETE FROM sqlite_sequence WHERE name='alias'");
            execute(connection, "INSERT INTO sqlite_sequence(name, seq) VALUES ('alias', " +
                Long.MAX_VALUE + ")");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("COMPLETED STEP: 15 -> 15 [repair-sqlite-identities]"),
            result::output);
        assertTrue(result.output().contains("DEFAULT SQLite identity high-water marks: 1 row(s)"),
            result::output);
        assertEquals(1L, new ConfigurationIdentityAllocator(database).nextAliasIds(List.of(), 1).getFirst());
    }

    @Test
    void runtimeAllocatorNeverReturnsTheJsonSafeCeiling() throws Exception
    {
        Path database = mTemporaryFolder.resolve("allocator-ceiling.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            execute(connection, "DELETE FROM sqlite_sequence WHERE name='alias'");
            execute(connection, "INSERT INTO sqlite_sequence(name, seq) VALUES ('alias', " +
                (SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM - 1) + ")");
        }

        assertThrows(IllegalStateException.class,
            () -> new ConfigurationIdentityAllocator(database).nextAliasIds(List.of(), 1));
    }

    @Test
    void preservesRetainedJsonSafeRowAtTheLastAllocatableIdentity() throws Exception
    {
        Path database = mTemporaryFolder.resolve("retained-last-identity.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long retainedId = SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM - 1;
        try(Connection connection = open(database))
        {
            long owningList = number(connection, "SELECT id FROM alias_list ORDER BY id LIMIT 1");
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (%d, %d, 'Last JSON-safe Alias', 'TALKGROUP', 'APCO25', 8103)
                """.formatted(retainedId, owningList));

            SqliteIdentityRepair.Inspection inspection = SqliteIdentityRepair.inspect(connection);
            assertEquals(0, inspection.droppedConfigurationRows());
            assertEquals(0, inspection.repairedSequenceRows());
            SqliteIdentityRepair.repair(connection);

            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias WHERE id=" + retainedId));
            assertEquals(retainedId, number(connection,
                "SELECT seq FROM sqlite_sequence WHERE name='alias'"));
        }
    }

    @Test
    void isolatesOnlyConfigurationRowsAtTheExhaustedJsonSafeBoundary() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-unsafe-identity.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long owningList;

        try(Connection connection = open(database))
        {
            owningList = number(connection, "SELECT id FROM alias_list ORDER BY id LIMIT 1");
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (%d, %d, 'Unallocatable identity', 'TALKGROUP', 'APCO25', 8101)
                """.formatted(SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM, owningList));

            SqliteIdentityRepair.Inspection inspection = SqliteIdentityRepair.inspect(connection);
            assertEquals(1, inspection.droppedConfigurationRows());
            assertTrue(inspection.repairedSequenceRows() > 0);

            SqliteIdentityRepair.repair(connection);
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM alias WHERE id=" + SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM));
            assertTrue(number(connection,
                "SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name='alias'), 0)") <
                SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM);
            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM alias_list WHERE id=" + owningList));
        }
    }

    @Test
    void currentComponentRepairClassifiesJsonUnsafeAliasAsOneBadItem() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-component-unsafe-identity.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            long owningList = number(connection, "SELECT id FROM alias_list ORDER BY id LIMIT 1");
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (%d, %d, 'Unsafe current Alias', 'TALKGROUP', 'APCO25', 8102)
                """.formatted(SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM, owningList));

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(1, inspection.droppedAliases());

            CurrentDatabaseBestEffortRepair.repair(connection);
            SqliteIdentityRepair.repair(connection);
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM alias WHERE name='Unsafe current Alias'"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias_list WHERE id=" + owningList));
        }
    }

    @Test
    void fullFormatOneMigrationToleratesDuplicateAndNullLegacySequences() throws Exception
    {
        Path database = Format1TestDatabase.create(stagedDatabase("format-one-sequences"));
        try(Connection connection = open(database))
        {
            execute(connection, "DELETE FROM sqlite_sequence WHERE name='alias'");
            execute(connection, "INSERT INTO sqlite_sequence(name, seq) VALUES " +
                "('alias', NULL), ('alias', 500), ('alias', " + Long.MAX_VALUE + ")");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("[repair-sqlite-identities]"), result::output);
        assertFalse(result.output().contains(Long.toString(Long.MAX_VALUE)), result::output);
        try(Connection connection = open(database))
        {
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                number(connection, "SELECT value FROM database_metadata WHERE key='database_format_version'"));
            assertTrue(number(connection,
                "SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name='alias'), 0)") <
                SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM);
        }
    }

    @Test
    void formatOneStepDefensivelyCollapsesDuplicateSequenceRowsOnItsOwn() throws Exception
    {
        Path database = Format1TestDatabase.create(mTemporaryFolder.resolve("direct-format-one.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, "DELETE FROM sqlite_sequence WHERE name='alias'");
            execute(connection, "INSERT INTO sqlite_sequence(name, seq) VALUES " +
                "('alias', NULL), ('alias', 500), ('alias', " + Long.MAX_VALUE + ")");
            execute(connection, "PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);

            new Format1To2DatabaseMigration().migrate(connection);

            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM sqlite_sequence WHERE name='alias'"));
            assertEquals(500, number(connection, "SELECT seq FROM sqlite_sequence WHERE name='alias'"));
            connection.rollback();
        }
    }

    @Test
    void fullLegacyMigrationRepairsExhaustedSequenceBeforeFactorySeedInsert() throws Exception
    {
        Path database = Format2TestDatabase.create(stagedDatabase("format-two-exhausted"));
        try(Connection connection = open(database))
        {
            execute(connection, "UPDATE sqlite_sequence SET seq=" + Long.MAX_VALUE + " WHERE name='alias_list'");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("DEFAULT SQLite identity high-water marks"), result::output);
        try(Connection connection = open(database))
        {
            assertEquals(4, number(connection,
                "SELECT COUNT(*) FROM (SELECT family FROM alias_list " +
                    "WHERE family IN ('P25', 'DMR', 'NXDN', 'NBFM') GROUP BY family)"));
            assertTrue(number(connection, "SELECT seq FROM sqlite_sequence WHERE name='alias_list'") <
                SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM);
        }
    }

    private Path stagedDatabase(String label)
    {
        return mTemporaryFolder.resolve(".sdrtrunk.sqlite.migration-" + UUID.nameUUIDFromBytes(
            label.getBytes(StandardCharsets.UTF_8)));
    }

    private static CommandResult run(Path database)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode;
        try(PrintStream standardOutput = new PrintStream(output, true, StandardCharsets.UTF_8);
            PrintStream standardError = new PrintStream(error, true, StandardCharsets.UTF_8))
        {
            exitCode = ApplicationDatabaseMigrator.run(new String[] {database.toString()}, standardOutput,
                standardError);
        }
        return new CommandResult(exitCode, output.toString(StandardCharsets.UTF_8),
            error.toString(StandardCharsets.UTF_8));
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
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

    private record CommandResult(int exitCode, String output, String error)
    {
    }
}
