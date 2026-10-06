/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format43To44DatabaseMigrationTest
{
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesEveryPopulatedTableRelationshipAndAllocatorWhileWideningOnlyTheNameConstraint() throws Exception
    {
        Path database = Format43TestDatabase.create(mTemporaryFolder.resolve("populated43.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertEquals(43, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("7ea75507b7840b9f7f42a05f7d70062360e8cc74cd014565c80b426b6d9f6d0b",
                SqliteSchemaValidator.fingerprint(connection));
            statement.executeUpdate("UPDATE sqlite_sequence SET seq=seq+10000 WHERE name='alias_list'");
            statement.executeUpdate("UPDATE alias_list SET unmatched_talkgroup_record_enabled=1, new_alias_record_enabled=1 " +
                "WHERE id=(SELECT min(alias_list_id) FROM alias)");
            statement.execute("ANALYZE alias_list");
            Map<String,TableContents> before = tableContents(connection);
            Map<String,String> schema = schemaDefinitions(connection);
            assertTrue(before.get("alias").rows() > 0);
            assertTrue(before.get("configuration_channel").rows() > 0);
            assertTrue(before.get("alias_list_unmatched_talkgroup_scan_list_membership").rows() > 0);
            assertTrue(before.get("alias_list_new_alias_scan_list_membership").rows() > 0);
            assertTrue(before.get("sqlite_stat1").rows() > 0);
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(1, report.steps().size());
            assertEquals("format-43-to-44", report.steps().getFirst().id());
            assertEquals(1, report.steps().getFirst().effects().getFirst().affectedRows());
            assertEquals(before, tableContents(connection), "Application rows or allocator changed");
            Map<String,String> after = schemaDefinitions(connection);
            assertEquals(schema.keySet(), after.keySet());
            schema.forEach((object, sql) -> assertEquals(object.equals("table:alias_list") ?
                sql.replace("BETWEEN 1 AND 25", "BETWEEN 1 AND 128") : sql, after.get(object), object));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(44, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    @Test
    void preservesTheAllocatorPresenceAndZeroValueForAnEmptyAliasCatalog() throws Exception
    {
        for(boolean sequencePresent: List.of(false, true))
        {
            Path database = Format43TestDatabase.create(mTemporaryFolder.resolve("empty43-" + sequencePresent + ".sqlite"));
            try(Connection connection = open(database); Statement statement = connection.createStatement())
            {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.executeUpdate("DELETE FROM configuration_channel");
                statement.executeUpdate("DELETE FROM alias_activity_summary");
                statement.executeUpdate("DELETE FROM alias");
                statement.executeUpdate("DELETE FROM alias_list");
                statement.executeUpdate("DELETE FROM sqlite_sequence WHERE name='alias_list'");
                if(sequencePresent)
                {
                    statement.executeUpdate("INSERT INTO sqlite_sequence(name,seq) VALUES ('alias_list',0)");
                }
                assertEquals(43, DatabaseFormatCatalog.inspect(connection).version());
                Map<String,TableContents> before = tableContents(connection);
                statement.execute("PRAGMA foreign_keys=OFF");
                connection.setAutoCommit(false);
                DatabaseMigrationChain.migrate(connection);
                connection.commit();
                assertEquals(before, tableContents(connection));
                assertEquals(sequencePresent ? "1" : "0", scalar(connection,
                    "SELECT count(*) FROM sqlite_sequence WHERE name='alias_list'"));
                if(sequencePresent)
                {
                    assertEquals("0", scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='alias_list'"));
                }
                assertEquals(44, DatabaseFormatCatalog.requireCurrent(connection).version());
            }
        }
    }

    @Test
    void accepts128CharacterNamesAndPunctuationButRetainsBlankAndCaseInsensitiveDuplicateChecks() throws Exception
    {
        Path database = Format44TestDatabase.create(mTemporaryFolder.resolve("names44.sqlite"));
        try(Connection connection = open(database); PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO alias_list(name,family) VALUES (?,'P25')"))
        {
            String prefix = "County: North / South \\\" & ";
            String name = prefix + "A".repeat(128 - prefix.length());
            assertEquals(128, name.length());
            insert.setString(1, name);
            assertEquals(1, insert.executeUpdate());
            insert.setString(1, name.toLowerCase(java.util.Locale.ROOT));
            assertThrows(SQLException.class, insert::executeUpdate);
            insert.setString(1, "A".repeat(129));
            assertThrows(SQLException.class, insert::executeUpdate);
            insert.setString(1, "   ");
            assertThrows(SQLException.class, insert::executeUpdate);
            assertFalse(CurrentDatabaseBestEffortRepair.inspect(connection).requiresRepair(),
                "Current-format repair would alter a supported longer name");
            assertEquals(name, scalar(connection, "SELECT name FROM alias_list ORDER BY id DESC LIMIT 1"));
            assertEquals(44, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void interruptionAfterCopyRollsBackToExact43AndTheUnchangedSourceCanBeRetried() throws Exception
    {
        Path source = Format43TestDatabase.create(mTemporaryFolder.resolve("source43.sqlite"));
        byte[] sourceBytes = Files.readAllBytes(source);
        Path candidate = mTemporaryFolder.resolve("candidate.sqlite");
        Files.copy(source, candidate);
        try(Connection connection = open(candidate))
        {
            Map<String,TableContents> contents = tableContents(connection);
            Map<String,String> schema = schemaDefinitions(connection);
            connection.setAutoCommit(false);
            SQLException failure = assertThrows(SQLException.class,
                () -> new Format43To44DatabaseMigration().migrate(failBeforeDrop(connection)));
            assertEquals("Injected failure after Alias List copy", failure.getMessage());
            connection.rollback();
            assertEquals(43, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(contents, tableContents(connection));
            assertEquals(schema, schemaDefinitions(connection));
            assertEquals("0", scalar(connection, "PRAGMA legacy_alter_table"));
            assertEquals("0", scalar(connection, "PRAGMA ignore_check_constraints"));
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(contents, tableContents(connection));
            assertEquals(44, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
        assertArrayEquals(sourceBytes, Files.readAllBytes(source), "Selected source changed during the retry");
        Path retry = mTemporaryFolder.resolve("retry.sqlite");
        Files.copy(source, retry);
        try(Connection connection = open(retry))
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test
    void runtimeRefuses43WithoutMutationAndCurrentMigrationIsANoOp() throws Exception
    {
        Path old = Format43TestDatabase.create(mTemporaryFolder.resolve("old43.sqlite"));
        byte[] before = Files.readAllBytes(old);
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(old));
        assertArrayEquals(before, Files.readAllBytes(old));
        Path current = Format44TestDatabase.create(mTemporaryFolder.resolve("current44.sqlite"));
        before = Files.readAllBytes(current);
        try(Connection connection = open(current))
        {
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
        }
        assertArrayEquals(before, Files.readAllBytes(current));
    }

    @Test
    void refusesWrongSourcesAndForeignKeyEnabledRebuildBeforeMutation() throws Exception
    {
        for(boolean foreignKeys: List.of(false, true))
        {
            Path database = foreignKeys ? Format43TestDatabase.create(mTemporaryFolder.resolve("fk43.sqlite")) :
                Format42TestDatabase.create(mTemporaryFolder.resolve("wrong42.sqlite"));
            try(Connection connection = open(database); Statement statement = connection.createStatement())
            {
                Map<String,TableContents> contents = tableContents(connection);
                Map<String,String> schema = schemaDefinitions(connection);
                if(foreignKeys) statement.execute("PRAGMA foreign_keys=ON");
                assertThrows(SQLException.class, () -> new Format43To44DatabaseMigration().migrate(connection));
                assertEquals(contents, tableContents(connection));
                assertEquals(schema, schemaDefinitions(connection));
            }
        }
    }

    @Test
    void reportsBoundedRecoveryForADamaged43NameWithoutLosingItsIdentityOrAliases() throws Exception
    {
        Path database = Format43TestDatabase.create(mTemporaryFolder.resolve("recover43.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            String id = scalar(connection, "SELECT min(alias_list_id) FROM alias");
            String aliasCount = scalar(connection, "SELECT count(*) FROM alias WHERE alias_list_id=" + id);
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE alias_list SET name=' ' WHERE id=" + id);
            statement.execute("PRAGMA ignore_check_constraints=OFF");
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals("Recovered Alias List " + id,
                scalar(connection, "SELECT name FROM alias_list WHERE id=" + id));
            assertEquals(aliasCount, scalar(connection, "SELECT count(*) FROM alias WHERE alias_list_id=" + id));
            DatabaseMigrationEffect nameRepair = report.steps().getFirst().effects().stream()
                .filter(effect -> effect.subject().equals("Alias List names separated after normalization"))
                .findFirst().orElseThrow();
            assertEquals(DatabaseMigrationEffect.Kind.DEFAULT, nameRepair.kind());
            assertEquals(1, nameRepair.affectedRows());
            assertEquals(44, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    private static Connection failBeforeDrop(Connection target)
    {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, arguments) -> {
                try
                {
                    Object value = method.invoke(target, arguments);
                    if(!method.getName().equals("createStatement")) return value;
                    Statement statement = (Statement)value;
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                        (statementProxy, operation, values) -> {
                            if(operation.getName().equals("executeUpdate") && values != null &&
                                "DROP TABLE format43_alias_list".equals(values[0]))
                                throw new SQLException("Injected failure after Alias List copy");
                            try { return operation.invoke(statement, values); }
                            catch(InvocationTargetException error) { throw error.getCause(); }
                        });
                }
                catch(InvocationTargetException error) { throw error.getCause(); }
            });
    }

    private static Map<String,TableContents> tableContents(Connection connection) throws Exception
    {
        List<String> tables = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        {
            while(rows.next()) tables.add(rows.getString(1));
        }
        Map<String,TableContents> contents = new LinkedHashMap<>();
        for(String table: tables)
        {
            String escaped = table.replace("\"", "\"\"");
            int columns;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT * FROM \"" + escaped + "\" LIMIT 0"))
            {
                columns = rows.getMetaData().getColumnCount();
            }
            String ordering = java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(","));
            String filter = table.equals("database_metadata") ? " WHERE key<>'database_format_version'" : "";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT * FROM \"" + escaped + "\"" + filter + " ORDER BY " + ordering))
            {
                while(rows.next())
                {
                    count++;
                    for(int column = 1; column <= columns; column++)
                    {
                        byte[] value = rows.getBytes(column);
                        digest.update((byte)(value == null ? 0 : 1));
                        if(value != null)
                        {
                            digest.update(Integer.toString(value.length).getBytes(StandardCharsets.UTF_8));
                            digest.update((byte)0);
                            digest.update(value);
                        }
                    }
                }
            }
            contents.put(table, new TableContents(count, HexFormat.of().formatHex(digest.digest())));
        }
        return contents;
    }

    private static Map<String,String> schemaDefinitions(Connection connection) throws SQLException
    {
        Map<String,String> definitions = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT type,name,sql FROM sqlite_schema WHERE sql IS NOT NULL ORDER BY type,name"))
        {
            while(rows.next()) definitions.put(rows.getString(1) + ":" + rows.getString(2),
                rows.getString(3).replaceAll("\\s+", " ").trim().replace("IF NOT EXISTS ", "")
                    .replaceAll("\\s*,\\s*", ","));
        }
        return definitions;
    }

    private static Connection open(Path path) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private record TableContents(long rows, String digest) {}
}
