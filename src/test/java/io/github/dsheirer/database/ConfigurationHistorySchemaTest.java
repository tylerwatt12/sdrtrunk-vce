/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationHistorySchemaTest
{
    @TempDir Path directory;
    @Test void currentRoleDefinitionsAreExact() throws Exception
    {
        try(var configuration=DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("config.sqlite"));
            var history=DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.sqlite")))
        {
            ConfigurationHistorySchema.createConfiguration(configuration);
            ConfigurationHistorySchema.createHistory(history);
            assertAll(
                () -> assertEquals(ConfigurationHistoryDatabase.CONFIGURATION_FINGERPRINT,SqliteSchemaValidator.fingerprint(configuration)),
                () -> assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT,SqliteSchemaValidator.fingerprint(history)));
            try(var query=history.createStatement();var rows=query.executeQuery("PRAGMA foreign_key_check"))
            { assertFalse(rows.next()); }
        }
    }

    @Test void identityAdmissionLeavesRepairableHistoryContentForTheMigrator() throws Exception
    {
        Path configuration = directory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(configuration);
        Path history = ConfigurationHistoryDatabase.companionPath(configuration);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + history);
            var statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='radio_system_metrics_started_at_ms'");
        }
        assertDoesNotThrow(() -> ConfigurationHistoryDatabase.validatePairIdentity(configuration));
        assertThrows(SQLException.class, () -> ConfigurationHistoryDatabase.validatePair(configuration));
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(configuration));
    }

    @Test void identityAdmissionStillRejectsDifferentProfilesAndSchemaDamage() throws Exception
    {
        Path configuration = directory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(configuration);
        Path history = ConfigurationHistoryDatabase.companionPath(configuration);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + history);
            var statement = connection.createStatement())
        {
            String pairId = ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY);
            try(var change = connection.prepareStatement("UPDATE database_metadata SET value=? WHERE key=?"))
            {
                change.setString(1, java.util.UUID.randomUUID().toString());
                change.setString(2, ConfigurationHistorySchema.PAIR_ID_KEY);
                change.executeUpdate();
                assertThrows(SQLException.class, () -> ConfigurationHistoryDatabase.validatePairIdentity(configuration));
                change.setString(1, pairId);
                change.executeUpdate();
            }
            statement.execute("CREATE TABLE unexpected_history_object(id INTEGER)");
        }
        assertThrows(SQLException.class, () -> ConfigurationHistoryDatabase.validatePairIdentity(configuration));
    }
}
