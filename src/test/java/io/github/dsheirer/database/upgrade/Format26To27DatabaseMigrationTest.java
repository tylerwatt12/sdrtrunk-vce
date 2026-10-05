/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format26To27DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void convertsOnlyOrdinaryAdministratorsAndPreservesAccountState() throws Exception
    {
        Path database = Format26TestDatabase.create(mTemporaryFolder.resolve("format-26.sqlite"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            long elevatedSequence = number(statement, "SELECT max(id) + 50 FROM web_user");
            assertEquals(1, statement.executeUpdate(
                "UPDATE sqlite_sequence SET seq=" + elevatedSequence + " WHERE name='web_user'"));
            Map<String,AccountSnapshot> before = accounts(statement);
            Map<String,byte[]> verifierDigestsBefore = verifierDigests(statement);
            assertEquals("ADMIN", before.get("admin").tier());
            assertEquals("USER", before.get("listener").tier());
            assertEquals("ADMIN", before.get("operator").tier());

            List<DatabaseMigrationEffect> validated = new Format26To27DatabaseMigration().validateSource(connection);
            assertEffect(validated, DatabaseMigrationEffect.Kind.TRANSFORM,
                "ordinary administrator accounts", 1);
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEquals("format-26-to-27", preflight.steps().getFirst().id());
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.TRANSFORM,
                "ordinary administrator accounts", DatabaseMigrationEffect.UNKNOWN_COUNT);

            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);
            DatabaseMigrationChain.MigrationReport report;
            try
            {
                report = DatabaseMigrationChain.migrate(connection);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
                statement.execute("PRAGMA foreign_keys=ON");
            }

            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 26, report.steps().size());
            assertEquals("format-26-to-27", report.steps().getFirst().id());
            assertEquals("format-40-to-41", report.steps().getLast().id());
            assertEffect(report.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.TRANSFORM,
                "ordinary administrator accounts", 1);
            Map<String,AccountSnapshot> after = accounts(statement);
            Map<String,byte[]> verifierDigestsAfter = verifierDigests(statement);
            assertEquals(before.keySet(), after.keySet());
            for(Map.Entry<String,AccountSnapshot> entry: before.entrySet())
            {
                AccountSnapshot expected = entry.getValue();
                AccountSnapshot actual = after.get(entry.getKey());
                assertEquals(expected.withMigratedPreferences("admin".equals(entry.getKey()) ? "ADMIN" : "USER",
                    actual.updatedAtMs()), actual);
                assertTrue(actual.updatedAtMs() >= expected.updatedAtMs());
                assertTrue(MessageDigest.isEqual(verifierDigestsBefore.get(entry.getKey()),
                    verifierDigestsAfter.get(entry.getKey())),
                    () -> "Password verifier changed for " + entry.getKey());
            }
            assertEquals(elevatedSequence,
                number(statement, "SELECT seq FROM sqlite_sequence WHERE name='web_user'"));
            assertEquals(0, number(statement,
                "SELECT COUNT(*) FROM web_user WHERE primary_admin=0 AND tier<>'USER'"));
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE web_user SET tier='ADMIN' WHERE username='listener'
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO web_user (
                    username, tier, primary_admin, credential_version, password_algorithm, password_iterations,
                    password_derived_key_bits, password_salt, password_hash, password_changed_at_ms, auth_revision,
                    preferences_json, preferences_revision, created_at_ms, updated_at_ms
                )
                SELECT 'second-admin', 'ADMIN', 0, credential_version, password_algorithm, password_iterations,
                       password_derived_key_bits, password_salt, password_hash, password_changed_at_ms, auth_revision,
                       preferences_json, preferences_revision, created_at_ms, updated_at_ms
                FROM web_user WHERE username='admin'
                """));
        }
    }

    @Test
    void rollbackRestoresFormat26AndTheSameSourceCanBeRetried() throws Exception
    {
        Path database = Format26TestDatabase.create(mTemporaryFolder.resolve("retry.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            String sourceFingerprint = SqliteSchemaValidator.fingerprint(connection);
            Map<String,AccountSnapshot> sourceAccounts = accounts(statement);

            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);
            try
            {
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                    DatabaseMigrationChain.migrate(connection).target().version());
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(26, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(sourceFingerprint, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(sourceAccounts, accounts(statement));

            connection.setAutoCommit(false);
            try
            {
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                    DatabaseMigrationChain.migrate(connection).target().version());
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
                statement.execute("PRAGMA foreign_keys=ON");
            }
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals("USER", text(statement,
                "SELECT tier FROM web_user WHERE username='operator'"));
        }
    }

    @Test
    void currentFormatStartupDoesNotRewriteAccounts() throws Exception
    {
        Path database = Format41TestDatabase.create(mTemporaryFolder.resolve("current.sqlite"));
        Map<String,AccountSnapshot> accountsBefore;
        Map<String,byte[]> verifierDigestsBefore;
        long sequenceBefore;
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            accountsBefore = accounts(statement);
            verifierDigestsBefore = verifierDigests(statement);
            sequenceBefore = number(statement, "SELECT seq FROM sqlite_sequence WHERE name='web_user'");
        }

        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertEquals(accountsBefore, accounts(statement));
            Map<String,byte[]> verifierDigestsAfter = verifierDigests(statement);
            for(String username: verifierDigestsBefore.keySet())
            {
                assertTrue(MessageDigest.isEqual(verifierDigestsBefore.get(username),
                    verifierDigestsAfter.get(username)), () -> "Startup changed password verifier for " + username);
            }
            assertEquals(sequenceBefore, number(statement,
                "SELECT seq FROM sqlite_sequence WHERE name='web_user'"));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    private static Map<String,AccountSnapshot> accounts(Statement statement) throws Exception
    {
        Map<String,AccountSnapshot> accounts = new LinkedHashMap<>();
        try(ResultSet rows = statement.executeQuery("""
            SELECT id, username, tier, primary_admin, credential_version, password_algorithm,
                   password_iterations, password_derived_key_bits, password_salt, password_hash,
                   password_changed_at_ms, auth_revision, preferences_json, preferences_revision,
                   created_at_ms, updated_at_ms
            FROM web_user ORDER BY id
            """))
        {
            while(rows.next())
            {
                AccountSnapshot account = new AccountSnapshot(rows.getLong("id"), rows.getString("username"),
                    rows.getString("tier"), rows.getInt("primary_admin"), rows.getInt("credential_version"),
                    rows.getString("password_algorithm"), rows.getInt("password_iterations"),
                    rows.getInt("password_derived_key_bits"), rows.getLong("password_changed_at_ms"),
                    rows.getLong("auth_revision"),
                    rows.getString("preferences_json"), rows.getLong("preferences_revision"),
                    rows.getLong("created_at_ms"), rows.getLong("updated_at_ms"));
                accounts.put(account.username(), account);
            }
        }
        return Map.copyOf(accounts);
    }

    private static Map<String,byte[]> verifierDigests(Statement statement) throws Exception
    {
        Map<String,byte[]> digests = new LinkedHashMap<>();
        try(ResultSet rows = statement.executeQuery("""
            SELECT username, password_salt, password_hash FROM web_user ORDER BY id
            """))
        {
            while(rows.next())
            {
                digests.put(rows.getString("username"), MessageDigest.getInstance("SHA-256").digest(concatenate(
                    rows.getBytes("password_salt"), rows.getBytes("password_hash"))));
            }
        }
        return Map.copyOf(digests);
    }

    private static byte[] concatenate(byte[] first, byte[] second)
    {
        byte[] joined = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static void assertEffect(List<DatabaseMigrationEffect> effects, DatabaseMigrationEffect.Kind kind,
                                     String subject, long rows)
    {
        assertTrue(effects.stream().anyMatch(effect -> effect.kind() == kind && effect.subject().equals(subject) &&
            effect.affectedRows() == rows), () -> "Missing migration effect " + kind + " " + subject + " " + rows);
    }

    private static long number(Statement statement, String sql) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static String text(Statement statement, String sql) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static Connection open(Path database) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private record AccountSnapshot(long id, String username, String tier, int primaryAdmin,
                                   int credentialVersion, String passwordAlgorithm, int passwordIterations,
                                   int passwordDerivedKeyBits, long passwordChangedAtMs, long authRevision,
                                   String preferencesJson, long preferencesRevision,
                                   long createdAtMs, long updatedAtMs)
    {
        private AccountSnapshot withMigratedPreferences(String replacement, long migratedUpdatedAtMs)
            throws java.io.IOException
        {
            return new AccountSnapshot(id, username, replacement, primaryAdmin, credentialVersion,
                passwordAlgorithm, passwordIterations, passwordDerivedKeyBits, passwordChangedAtMs, authRevision,
                Format36WebUserPreferencesCodec.migrateFromFormat35(Format35WebUserPreferencesCodec.migrateFromFormat34(
                    Format23WebUserPreferencesCodec.migrateToFormat31(preferencesJson))), preferencesRevision + 3,
                createdAtMs, migratedUpdatedAtMs);
        }
    }
}
