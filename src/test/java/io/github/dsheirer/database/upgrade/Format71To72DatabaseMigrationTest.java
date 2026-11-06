/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format71To72DatabaseMigrationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test void removesOnlyRetiredChoicesAndPreservesActivityAcrossRollbackRetryAndNoOp() throws Exception
    {
        Path database = Format71TestDatabase.create(directory.resolve("prior.sqlite"));
        var activity = ActivityMigrationPreservation.capture(database);
        byte[] original = Files.readAllBytes(database);
        assertThrows(Exception.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(original, Files.readAllBytes(database));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement();
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
            statement.executeUpdate("UPDATE web_user SET preferences_json=json_set(preferences_json," +
                "'$.appearance.theme','dark','$.presentation.show_voice_decode_quality',json('false'))");
            String before;
            long revision, timestamp;
            try(var rows = statement.executeQuery("SELECT preferences_json,preferences_revision,updated_at_ms FROM web_user ORDER BY id LIMIT 1"))
            {
                assertTrue(rows.next()); before = rows.getString(1); revision = rows.getLong(2); timestamp = rows.getLong(3);
            }
            connection.setAutoCommit(false);
            new Format71To72DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 72);
            assertEquals(72, DatabaseFormatCatalog.inspect(connection).version());
            connection.rollback();
            assertEquals(71, DatabaseFormatCatalog.inspect(connection).version());
            try(var rows = statement.executeQuery("SELECT preferences_json FROM web_user ORDER BY id LIMIT 1"))
            {
                assertTrue(rows.next()); assertEquals(before, rows.getString(1));
            }
            new Format71To72DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 72);
            connection.commit();
            try(var rows = statement.executeQuery("SELECT preferences_json,preferences_revision,updated_at_ms FROM web_user ORDER BY id LIMIT 1"))
            {
                assertTrue(rows.next());
                ObjectNode expected = (ObjectNode)JSON.readTree(before);
                expected.put("version", 16);
                ((ObjectNode)expected.get("presentation")).remove(java.util.List.of(
                    "show_control_decode_quality", "show_voice_decode_quality"));
                assertEquals(expected, JSON.readTree(rows.getString(1)));
                WebUserPreferencesCodec.decode(rows.getString(1));
                assertEquals(revision + 1, rows.getLong(2)); assertEquals(timestamp, rows.getLong(3));
            }
        }
        ActivityMigrationPreservation.assertCurrent(database, activity);
        ConfigurationHistoryDatabase.validatePair(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        byte[] current = Files.readAllBytes(database);
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertArrayEquals(current, Files.readAllBytes(database));
        ActivityMigrationPreservation.assertCurrent(database, activity);
    }

    @Test void fullMigratorRollsBackBeforeCommitAndRetriesUnchangedSource() throws Exception
    {
        Path database = Format71TestDatabase.create(directory.resolve("retry.sqlite"));
        byte[] config = Files.readAllBytes(database);
        var activity = ActivityMigrationPreservation.capture(database);
        assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, phase -> {
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
        }));
        assertArrayEquals(config, Files.readAllBytes(database));
        assertEquals(activity, ActivityMigrationPreservation.capture(database));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        ActivityMigrationPreservation.assertCurrent(database, activity);
    }
}
