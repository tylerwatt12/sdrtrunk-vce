/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.gui.setup.SetupProgress;
import io.github.dsheirer.gui.setup.SetupStep;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Format28To29DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void preservesBothExistingRecordingModesAndWizardStates() throws Exception
    {
        for(String mode: new String[]{"CLASSIC", "MANAGED"})
        {
            Path database = Format28TestDatabase.create(directory.resolve(mode + ".sqlite"));
            try(Connection connection = open(database); Statement statement = connection.createStatement())
            {
                String portable = "{\"user/io/github/dsheirer/preference/record\":{\"audio.record.mode\":\"" +
                    mode + "\",\"unrelated\":\"keep\"}}";
                try(var update = connection.prepareStatement("""
                    INSERT INTO application_settings(key,settings_json,updated_at_ms) VALUES('portable_java_preferences_v1',?,1)
                    ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json,updated_at_ms=1
                    """))
                {
                    update.setString(1, portable);
                    update.executeUpdate();
                }
                SetupProgress legacy = SetupProgress.readLegacy(connection);
                legacy.set(SetupStep.ACTIVITY, SetupProgress.State.DEFERRED);
                SetupProgress.writeLegacy(connection, legacy);
                assertEquals(28, DatabaseFormatCatalog.inspect(connection).version());

                DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, report.target().version());
                assertEquals("format-28-to-29", report.steps().getFirst().id());
                assertEquals("format-43-to-44", report.steps().getLast().id());
                assertEquals(portable, scalar(statement,
                    "SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'"));
                SetupProgress upgraded = SetupProgress.read(connection);
                assertEquals(SetupProgress.State.DEFERRED, upgraded.get(SetupStep.ACTIVITY));
                assertEquals(SetupProgress.State.CARRIED_OVER, upgraded.get(SetupStep.RECORDINGS));
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                    DatabaseFormatCatalog.requireCurrent(connection).version());
            }
        }
    }

    @Test void malformedLegacyProgressIsCountedAndReplacedForReview() throws Exception
    {
        Path database = Format28TestDatabase.create(directory.resolve("malformed.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE application_settings SET settings_json='{}' WHERE key='setup_wizard'");
            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
            assertTrue(report.steps().getFirst().effects().stream().anyMatch(effect ->
                effect.subject().equals("unusable legacy setup progress") && effect.affectedRows() == 1));
            SetupProgress progress = SetupProgress.read(connection);
            assertTrue(progress.isImported());
            assertFalse(progress.isComplete());
            assertEquals(SetupProgress.State.CARRIED_OVER, progress.get(SetupStep.RECORDINGS));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test void rollbackLeavesPriorFormatUnchangedAndRetryWorks() throws Exception
    {
        Path database = Format28TestDatabase.create(directory.resolve("retry.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            String before = scalar(statement, "SELECT settings_json FROM application_settings WHERE key='setup_wizard'");
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
            assertEquals(before, scalar(statement, "SELECT settings_json FROM application_settings WHERE key='setup_wizard'"));
            assertEquals(28, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseMigrationChain.migrate(connection).target().version());
        }
    }

    @Test void directSourceRepairsIndependentAdministrativeDamage() throws Exception
    {
        Path database = Format28TestDatabase.create(directory.resolve("admin-repair.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM application_settings WHERE key='spectrum_snap_country'");
            assertTrue(new Format28To29DatabaseMigration().validateSource(connection).stream().anyMatch(effect ->
                effect.subject().equals("unusable spectrum-snap settings") && effect.affectedRows() == 1));
            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
            assertTrue(report.steps().getFirst().effects().stream().anyMatch(effect ->
                effect.subject().equals("unusable spectrum-snap settings") && effect.affectedRows() == 1));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    private static Connection open(Path path) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    private static String scalar(Statement statement, String sql) throws Exception
    {
        try(var row = statement.executeQuery(sql))
        {
            assertTrue(row.next());
            return row.getString(1);
        }
    }
}
