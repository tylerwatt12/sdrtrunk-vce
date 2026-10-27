/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.portable.PortableFileIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
@Tag("platform-filesystem")
class Format62To63CrashRecoveryTest
{
    @TempDir Path temporary;

    @Test
    void resumesAfterProcessExitLeavesReceiptExtractionAndHotSourceJournal() throws Exception
    {
        Path database = Format62TestDatabase.create(temporary.resolve("sdrtrunk.sqlite"));
        long events = number(database, "SELECT count(*) FROM receiver_activity_event");
        long channels = number(database, "SELECT count(*) FROM configuration_channel");
        Path proof = temporary.resolve("crashed-before-commit");
        Path log = temporary.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--add-modules", "jdk.incubator.vector", "-cp", System.getProperty("java.class.path"),
            "--enable-native-access=ALL-UNNAMED",
            BeforeCommitCrash.class.getName(), database.toString(), proof.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try
        {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Child did not reach its crash point");
            assertEquals(0, child.exitValue(), () -> read(log));
            assertEquals("active-transaction", Files.readString(proof));
            assertTrue(Files.isRegularFile(ActivityDatabasePath.splitReceipt(database)));
            assertTrue(Files.isRegularFile(ActivityDatabasePath.configurationStage(database)));
            assertTrue(Files.size(Path.of(database + "-journal")) > 512);

            Path receipt = ActivityDatabasePath.splitReceipt(database);
            byte[] receiptBytes = Files.readAllBytes(receipt);
            byte[] sourceBytes = Files.readAllBytes(database);
            byte[] journalBytes = Files.readAllBytes(Path.of(database + "-journal"));
            Properties missingIdentity = new Properties();
            try(var input = Files.newInputStream(receipt)) { missingIdentity.load(input); }
            assertNotNull(missingIdentity.remove("source_file_key"));
            try(var output = Files.newOutputStream(receipt)) { missingIdentity.store(output, "Old receipt without identity"); }
            var missing = assertThrows(java.io.IOException.class,
                () -> ApplicationDatabaseMigrator.recoverSplitTransition(database));
            assertTrue(missing.getMessage().contains("source file identity"));
            assertArrayEquals(sourceBytes, Files.readAllBytes(database));
            assertArrayEquals(journalBytes, Files.readAllBytes(Path.of(database + "-journal")));
            Files.write(receipt, receiptBytes);

            {
                String sourceIdentity = PortableFileIdentity.read(database);
                assertNotNull(sourceIdentity);
                Path original = temporary.resolve("preserved-original.sqlite");
                Path replacement = temporary.resolve("substitute.sqlite");
                Files.copy(database, replacement);
                byte[] replacedBytes = Files.readAllBytes(replacement);
                assertNotEquals(sourceIdentity, PortableFileIdentity.read(replacement));
                Files.move(database, original);
                Files.move(replacement, database);
                var refusal = assertThrows(java.io.IOException.class,
                    () -> ApplicationDatabaseMigrator.recoverSplitTransition(database));
                assertTrue(refusal.getMessage().contains("source file was replaced"));
                assertArrayEquals(replacedBytes, Files.readAllBytes(database));
                assertArrayEquals(journalBytes, Files.readAllBytes(Path.of(database + "-journal")));
                Files.delete(database);
                Files.move(original, database);
            }

            ApplicationDatabaseMigrator.migrateInPlace(database, null);

            ConfigurationHistoryDatabase.validatePair(database);
            assertEquals(events, number(ActivityDatabasePath.forConfiguration(database),
                "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(channels, number(database, "SELECT count(*) FROM configuration_channel"));
            assertFalse(Files.exists(ActivityDatabasePath.splitReceipt(database)));
            assertFalse(Files.exists(ActivityDatabasePath.configurationStage(database)));
        }
        finally
        {
            if(child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    public static class BeforeCommitCrash
    {
        private static Connection sourceConnection;

        public static void main(String[] arguments) throws Exception
        {
            Path database = Path.of(arguments[0]);
            Class.forName("org.sqlite.JDBC");
            for(Driver driver: Collections.list(DriverManager.getDrivers()))
                if(driver instanceof org.sqlite.JDBC) DriverManager.deregisterDriver(driver);
            DriverManager.registerDriver(new SmallCacheDriver(database));
            ApplicationDatabaseMigrator.migrateInPlace(database, phase -> {
                if(phase.equals("Validating the updated schema and configuration"))
                {
                    try
                    {
                        ApplicationMigrationTransaction.requireActive(sourceConnection);
                        Files.writeString(Path.of(arguments[1]), "active-transaction");
                    }
                    catch(Exception failure) { throw new IllegalStateException(failure); }
                    Runtime.getRuntime().halt(0);
                }
            });
            throw new IllegalStateException("Migration never reached the crash point");
        }

        /** Return the real SQLite connection so the migrator's transaction ownership proof stays unchanged. */
        private static class SmallCacheDriver implements Driver
        {
            private final org.sqlite.JDBC delegate = new org.sqlite.JDBC();
            private final Path source;
            private SmallCacheDriver(Path database) { source = database; }
            @Override public Connection connect(String url, Properties properties) throws SQLException
            {
                Connection connection = delegate.connect(url, properties);
                if(connection != null && url.equals("jdbc:sqlite:" + source))
                {
                    sourceConnection = connection;
                    try(var statement = connection.createStatement())
                    {
                        statement.execute("PRAGMA journal_mode=DELETE");
                        statement.execute("PRAGMA synchronous=FULL");
                        statement.execute("PRAGMA cache_size=1");
                    }
                }
                return connection;
            }
            @Override public boolean acceptsURL(String url) throws SQLException { return delegate.acceptsURL(url); }
            @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) throws SQLException
                { return delegate.getPropertyInfo(url, properties); }
            @Override public int getMajorVersion() { return delegate.getMajorVersion(); }
            @Override public int getMinorVersion() { return delegate.getMinorVersion(); }
            @Override public boolean jdbcCompliant() { return delegate.jdbcCompliant(); }
            @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        }
    }

    private static long number(Path database, String sql) throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        { assertTrue(rows.next()); return rows.getLong(1); }
    }

    private static String read(Path path)
    {
        try { return Files.readString(path); }
        catch(Exception failure) { return failure.getClass().getSimpleName(); }
    }
}
