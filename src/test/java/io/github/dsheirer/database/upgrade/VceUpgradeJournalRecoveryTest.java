/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.portable.PortableDataRootLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class VceUpgradeJournalRecoveryTest
{
    @TempDir Path temporary;

    @Test
    void resumesAfterItsOwnCheckpointBeforeSavingTheTransferredOriginalDatabase() throws Exception
    {
        Path source = Files.createDirectories(temporary.resolve("previous/data"));
        Path target = Files.createDirectories(temporary.resolve("next")).resolve("data");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(source);
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(target);
        Format64TestDatabase.create(sourceDatabase);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + sourceDatabase);
            var statement = connection.createStatement())
        {
            try(var rows = statement.executeQuery("PRAGMA journal_mode=WAL"))
            { assertTrue(rows.next()); assertEquals("wal", rows.getString(1)); }
            statement.executeUpdate("UPDATE alias SET name='Saved before upgrade' WHERE id=(SELECT min(id) FROM alias)");
        }

        assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
            MigrationContent.CONFIGURATION_ONLY, true, null, message -> {
                if(message.equals("Installing the prepared settings with empty Activity"))
                {
                    // Reproduce the durable filesystem state of finishSqlite() completing before its next
                    // intent phase is saved. No settings are changed: only SQLite's own journal housekeeping.
                    try(var connection = DriverManager.getConnection("jdbc:sqlite:" + targetDatabase);
                        var statement = connection.createStatement())
                    {
                        statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                        try(var rows = statement.executeQuery("PRAGMA journal_mode=DELETE"))
                        { assertTrue(rows.next()); assertEquals("delete", rows.getString(1)); }
                    }
                    catch(Exception failure) { throw new AssertionError(failure); }
                    throw new SimulatedCrash();
                }
            }));
        assertFalse(Files.exists(source));
        assertTrue(VceUpgradeService.isPending(target));
        assertEquals(PortableProfileTransfer.Phase.ACTIVATING, PortableProfileTransfer.inspect(target).phase());

        var result = VceUpgradeService.resume(target, null, null);

        try(PortableDataRootLock ignored = result.lock())
        {
            ConfigurationHistoryDatabase.validatePair(targetDatabase);
            SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(targetDatabase);
            assertFalse(VceUpgradeService.isPending(target));
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + targetDatabase);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT name FROM alias WHERE id=(SELECT min(id) FROM alias)"))
            { assertTrue(rows.next()); assertEquals("Saved before upgrade", rows.getString(1)); }
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + ActivityDatabasePath.forConfiguration(targetDatabase));
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM receiver_activity_event"))
            { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
        }
    }

    private static final class SimulatedCrash extends Error {}
}
