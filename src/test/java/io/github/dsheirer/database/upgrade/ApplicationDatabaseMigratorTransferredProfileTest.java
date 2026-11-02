/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.gui.setup.SetupProgress;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.portable.PortableFileIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("platform-filesystem")
class ApplicationDatabaseMigratorTransferredProfileTest
{
    @TempDir Path temporary;

    @Test
    void movesWholeProfileWithoutBackupOrResettingCompletedSetup() throws Exception
    {
        for(int version: List.of(63, 69))
        {
            Path source = root("old-" + version), target = root("new-" + version);
            Path database = SdrTrunkDatabasePath.getDatabasePath(source);
            if(version == 63) Format63TestDatabase.create(database);
            else Format69TestDatabase.create(database);
            new SetupProgress(true, false).save(database);
            Path audio = source.resolve("recordings-managed/saved.wav");
            Files.createDirectories(audio.getParent());
            Files.writeString(audio, "retained audio");
            String audioIdentity = PortableFileIdentity.read(audio);
            String historyIdentity = PortableFileIdentity.read(ActivityDatabasePath.forConfiguration(database));
            String events = scalar(ActivityDatabasePath.forConfiguration(database), "SELECT count(*) FROM receiver_activity_event");
            String setup = scalar(database, "SELECT settings_json FROM application_settings WHERE key='setup_wizard'");
            try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
            {
                var transfer = PortableProfileTransfer.prepare(source, target, lock);
                transfer.move();
                transfer.beginActivation();
                Path moved = SdrTrunkDatabasePath.getDatabasePath(target);
                ApplicationDatabaseMigrator.migrateTransferred(moved, source, target, lock);
                ConfigurationHistoryDatabase.validatePair(moved);
                SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(moved);
                assertFalse(Files.exists(source));
                assertEquals(target, lock.getDataRoot());
                assertThrows(IOException.class, () -> PortableDataRootLock.acquire(target));
                assertEquals(setup, scalar(moved, "SELECT settings_json FROM application_settings WHERE key='setup_wizard'"));
                assertTrue(SetupProgress.read(moved).isComplete());
                assertEquals(events, scalar(ActivityDatabasePath.forConfiguration(moved), "SELECT count(*) FROM receiver_activity_event"));
                assertEquals(historyIdentity, PortableFileIdentity.read(ActivityDatabasePath.forConfiguration(moved)));
                assertEquals(audioIdentity, PortableFileIdentity.read(target.resolve("recordings-managed/saved.wav")));
                assertEquals("retained audio", Files.readString(target.resolve("recordings-managed/saved.wav")));
                assertFalse(Files.exists(target.resolve("database/backups")));
                transfer.markCommitted();
                transfer.complete();
                assertFalse(VceUpgradeService.isPending(target));
            }
        }
    }

    @Test
    void requiresActivationReceiptAndNeverRestoresFailedActivationUnderOldBuild() throws Exception
    {
        Path source = root("failed"), target = root("new-failed");
        Format69TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(source));
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            var transfer = PortableProfileTransfer.prepare(source, target, lock);
            transfer.move();
            Path database = SdrTrunkDatabasePath.getDatabasePath(target);
            byte[] before = Files.readAllBytes(database);
            assertThrows(IOException.class, () -> ApplicationDatabaseMigrator.migrateTransferred(database, source, target, lock));
            assertArrayEquals(before, Files.readAllBytes(database));
            transfer.beginActivation();
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement())
            {
                statement.execute("CREATE TABLE unexpected_schema(id INTEGER)");
            }
            assertThrows(SQLException.class, () -> ApplicationDatabaseMigrator.migrateTransferred(database, source, target, lock));
            assertFalse(Files.exists(source));
            assertTrue(Files.isRegularFile(database));
            assertEquals(PortableProfileTransfer.Phase.ACTIVATING, PortableProfileTransfer.inspect(target).phase());
            assertTrue(VceUpgradeService.isPending(target));
            assertThrows(IOException.class, transfer::restoreBeforeActivation);
            assertFalse(Files.exists(target.resolve("database/backups")));
        }
    }

    private Path root(String name) throws IOException
    {
        return Files.createDirectories(temporary.resolve(name)).resolve("data").toAbsolutePath().normalize();
    }

    private static String scalar(Path database, String sql) throws SQLException
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var query = connection.createStatement(); var rows = query.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
