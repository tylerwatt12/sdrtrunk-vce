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

/** Allows conventional NXDN Null Group calls to retain talkgroup identity zero in compact statistics. */
final class Format21To22DatabaseMigration implements DatabaseMigrationStep
{
    private static final String COLUMNS = "channel_id, bucket_start_ms, identity_role_code, identity_kind_code, " +
        "identity_id, call_count, encrypted_count, recorded_count, streamed_count";

    @Override public String id() { return "format-21-to-22"; }
    @Override public String description() { return "Track NXDN Null Group calls as a distinct destination"; }
    @Override public int sourceVersion() { return 21; }
    @Override public int targetVersion() { return 22; }

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
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM conventional_call_identity_bucket"))
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
            ReceiverActivitySchema.createFormat22ConventionalCallIdentityMigrationTable(connection);
            statement.executeUpdate("INSERT INTO conventional_call_identity_bucket_format22 (" + COLUMNS + ") " +
                "SELECT " + COLUMNS + " FROM conventional_call_identity_bucket");
            statement.executeUpdate("DROP TABLE conventional_call_identity_bucket");
            statement.executeUpdate("ALTER TABLE conventional_call_identity_bucket_format22 " +
                "RENAME TO conventional_call_identity_bucket");
            ReceiverActivitySchema.createCurrentIndexesAndViews(connection);
        }
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 21)
        {
            throw new SQLException("Expected format 21");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long rows)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "conventional call identity statistics", rows,
            "Keep every retained identity counter while allowing future NXDN Null Group totals"));
    }
}
