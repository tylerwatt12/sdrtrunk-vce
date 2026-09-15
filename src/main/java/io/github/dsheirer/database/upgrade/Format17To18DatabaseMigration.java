/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Allows the shared detailed-activity site field to retain valid DMR and NXDN site identifiers. */
final class Format17To18DatabaseMigration implements DatabaseMigrationStep
{
    private static final String EVENT_COLUMNS = "id, channel_id, radio_system_id, observed_at_ms, action_code, " +
        "event_type_code, source_observed_local_id, target_observed_local_id, target_kind_code, " +
        "source_identity_summary_id, source_identity_kind_code, target_identity_summary_id, frequency_hz, " +
        "lcn_band, lcn_number, timeslot, encrypted, encryption_algorithm_id, encryption_key_id, observed_nac, " +
        "observed_rfss, observed_site";

    @Override public String id() { return "format-17-to-18"; }
    @Override public String description() { return "Retain full DMR and NXDN site identifiers in activity history"; }
    @Override public int sourceVersion() { return 17; }
    @Override public int targetVersion() { return 18; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM receiver_activity_event"))
        {
            return effects(rows.next() ? rows.getLong(1) : 0);
        }
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSource(connection);
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP VIEW receiver_activity_event_resolved");
            ReceiverActivitySchema.createFormat18ReceiverActivityEventMigrationTable(connection);
            statement.executeUpdate("INSERT INTO receiver_activity_event_format18 (" + EVENT_COLUMNS + ") " +
                "SELECT " + EVENT_COLUMNS + " FROM receiver_activity_event");
            statement.executeUpdate("DROP TABLE receiver_activity_event");
            statement.executeUpdate(
                "ALTER TABLE receiver_activity_event_format18 RENAME TO receiver_activity_event");
            ReceiverActivitySchema.createCurrentIndexesAndViews(connection);
        }
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 17)
        {
            throw new SQLException("Expected format 17");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long activityEvents)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "detailed receiver activity history", activityEvents,
            "Keep existing history while accepting the full valid DMR and NXDN site range"));
    }
}
