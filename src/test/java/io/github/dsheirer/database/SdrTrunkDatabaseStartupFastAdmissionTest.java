/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdrTrunkDatabaseStartupFastAdmissionTest
{
    @TempDir Path temporary;

    @Test
    void ordinaryStartupDefersDerivedIdentityDamageWhileExplicitValidationStillRejectsIt() throws Exception
    {
        Path database = SdrTrunkTestDatabase.create(temporary.resolve("derived.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,
                    first_seen_ms,last_seen_ms) VALUES(1,'p25:00001:001',1,1,1,1000,1000)
                """);
            //The table permits positive IDs; the full protocol-semantic check rejects this P25 directory ID.
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(radio_system_id,identity_kind_code,
                    home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms)
                VALUES(1,2,1,1,10000000,1000,1000)
                """);
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT identity_id FROM radio_system_identity_summary"))
        {
            rows.next();
            assertEquals(10000000, rows.getInt(1));
        }
    }

    @Test
    void rejectsAnIncorrectSameNamedIndexBeforeOpeningWritableOrChangingJournalMode() throws Exception
    {
        Path database = SdrTrunkTestDatabase.create(temporary.resolve("wrong-index.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("DROP INDEX idx_configuration_channel_sort");
            statement.execute("CREATE INDEX idx_configuration_channel_sort ON configuration_channel(id)");
        }
        byte[] before = Files.readAllBytes(database);
        assertThrows(SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void rejectsMissingRequiredSetupStateWithoutRepairingIt() throws Exception
    {
        Path database = SdrTrunkTestDatabase.create(temporary.resolve("missing-setup.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.executeUpdate("DELETE FROM application_settings WHERE key='setup_wizard'");
        }
        byte[] before = Files.readAllBytes(database);
        assertThrows(SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(before, Files.readAllBytes(database));
    }
}
