/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.portable.PortableFileIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("platform-filesystem")
class ApplicationDatabaseMigratorActivityJournalTest
{
    @TempDir Path temporary;

    @Test void normal68To70MigrationKeepsBothJournalsAndPreservesActivity() throws Exception
    {
        Path source = root("old"), target = root("new");
        Path database = Format68TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(source));
        Path activity = ActivityDatabasePath.forConfiguration(database);
        String identity = PortableFileIdentity.read(activity);
        Map<String,List<String>> before;
        String expectedEncryption;
        try(Connection connection = open(activity))
        {
            before = Format68To69DatabaseMigrationTest.snapshot(connection);
            expectedEncryption = scalar(connection, "SELECT group_concat(id || ':' || (CASE WHEN encrypted=1 " +
                "OR event_type_code IN (6,8,10,17,20,26) THEN 2 ELSE 0 END),',') FROM " +
                "(SELECT * FROM receiver_activity_event ORDER BY id)");
        }
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            var transfer = PortableProfileTransfer.prepare(source, target, lock);
            transfer.move(); transfer.beginActivation();
            Path moved = SdrTrunkDatabasePath.getDatabasePath(target);
            Path movedActivity = ActivityDatabasePath.forConfiguration(moved);
            AtomicBoolean observed = new AtomicBoolean();
            ApplicationDatabaseMigrator.migrateTransferred(moved, source, target, lock, false, line -> {
                if(line.equals("Validating the updated schema and configuration"))
                {
                    observed.set(true);
                    assertTrue(Files.exists(Path.of(moved + "-journal")));
                    assertTrue(Files.exists(Path.of(movedActivity + "-journal")));
                }
            });
            assertTrue(observed.get());
            assertEquals(identity, PortableFileIdentity.read(movedActivity));
            try(Connection connection = open(movedActivity))
            {
                assertEquals(before, Format68To69DatabaseMigrationTest.snapshot(connection));
                assertEquals(expectedEncryption, scalar(connection, "SELECT group_concat(id || ':' || " +
                    "encryption_state,',') FROM (SELECT * FROM receiver_activity_event ORDER BY id)"));
                assertEquals("delete", scalar(connection, "PRAGMA journal_mode"));
                assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            }
            ConfigurationHistoryDatabase.validatePair(moved);
            SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(moved);
            assertNull(PortableProfileTransfer.resume(target, lock).metadata("activity_journal_disabled_attempt"));
            transfer.markCommitted(); transfer.complete();
            assertFalse(VceUpgradeService.isPending(target));
        }
    }

    @Test void unsupportedSourcesAreRefusedBeforeTheOneAttemptLatchOrAnyDatabaseChange() throws Exception
    {
        for(int version: List.of(63, 68, 69, 70))
        {
            Path source = root("unsupported-old-" + version), target = root("unsupported-new-" + version);
            Path database = SdrTrunkDatabasePath.getDatabasePath(source);
            switch(version)
            {
                case 63 -> Format63TestDatabase.create(database);
                case 68 -> Format68TestDatabase.create(database);
                case 69 -> Format69TestDatabase.create(database);
                default -> Format70TestDatabase.create(database);
            }
            try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
            {
                var transfer = PortableProfileTransfer.prepare(source, target, lock);
                transfer.move(); transfer.beginActivation();
                Path moved = SdrTrunkDatabasePath.getDatabasePath(target);
                byte[] configuration = Files.readAllBytes(moved);
                byte[] activity = Files.readAllBytes(ActivityDatabasePath.forConfiguration(moved));
                assertThrows(SQLException.class,
                    () -> ApplicationDatabaseMigrator.migrateTransferred(moved, source, target, lock, true));
                assertNull(PortableProfileTransfer.resume(target, lock).metadata("activity_journal_disabled_attempt"));
                assertArrayEquals(configuration, Files.readAllBytes(moved));
                assertArrayEquals(activity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(moved)));
            }
        }
    }

    private Path root(String name) throws IOException
    { return Files.createDirectories(temporary.resolve(name)).resolve("data").toAbsolutePath().normalize(); }
    private static Connection open(Path database) throws SQLException
    { return DriverManager.getConnection("jdbc:sqlite:" + database); }
    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(var query = connection.createStatement(); var rows = query.executeQuery(sql))
        { assertTrue(rows.next()); return rows.getString(1); }
    }
}
