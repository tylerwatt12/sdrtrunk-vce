/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Retains accepted P25 assignment timing without treating historical observations as current assignments. */
final class Format46To47DatabaseMigration implements DatabaseMigrationStep
{
    private static final String SITE_COLUMNS = "channel_id, snapshot_hash, first_seen_ms, last_seen_ms, observation_count, " +
        "protocol, nac, rfss, site, lra, mfid, broadcast_clock_ms, micro_slots, data_service, data_access, " +
        "wuid_lease_minutes, registration_service, tdma, voice_service, primary_frequency_hz, current_control_hz, " +
        "active_rfss_network_connection";
    @Override public String id() { return "format-46-to-47"; }
    @Override public String description() { return "Keep P25 assignment timing with association history"; }
    @Override public int sourceVersion() { return 46; }
    @Override public int targetVersion() { return 47; }

    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    private static List<DatabaseMigrationEffect> effects(long normalizedSites)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "P25 association history and all other saved data", 0,
            "Preserve every existing pair, counter, source and relationship; previously unrecorded lifetime and expiry remain unknown"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "P25 site advertised lifetime constraint", 1,
                "Accept finite advertised lifetimes of 240 through 7860 minutes and explicit no expiry"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "legacy P25 no-expiry observations", normalizedSites,
                "Normalize only the former raw-255 value 7890 to explicit no-expiry zero and invalidate its obsolete snapshot hash"));
    }

    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        return effects(scalar(connection, "SELECT count(*) FROM p25_site_snapshot WHERE wuid_lease_minutes=7890"));
    }

    @Override public void migrateSource(Connection connection) throws SQLException
    {
        if(scalar(connection, "PRAGMA foreign_keys") != 0)
            throw new SQLException("P25 site migration requires the Application Migrator foreign-key boundary");
        ReceiverActivitySchema.addFormat47P25WuidLeaseHistory(connection);
        rebuildSiteSnapshot(connection);
    }

    private static void rebuildSiteSnapshot(Connection connection) throws SQLException
    {
        long count = scalar(connection, "SELECT count(*) FROM p25_site_snapshot");
        long legacyAlterTable = scalar(connection, "PRAGMA legacy_alter_table");
        try(Statement statement = connection.createStatement())
        {
            // The offline migrator preserves child foreign-key targets while replacing their parent.
            statement.execute("PRAGMA legacy_alter_table=ON");
            try
            {
                statement.executeUpdate("ALTER TABLE p25_site_snapshot RENAME TO format46_p25_site_snapshot");
                ReceiverActivitySchema.createFormat47P25SiteSnapshotTable(connection);
                String projection = SITE_COLUMNS.replace("snapshot_hash", "CASE WHEN wuid_lease_minutes=7890 " +
                    "THEN NULL ELSE snapshot_hash END").replace(
                    ", wuid_lease_minutes,", ", CASE WHEN wuid_lease_minutes=7890 THEN 0 ELSE wuid_lease_minutes END,");
                statement.executeUpdate("INSERT INTO p25_site_snapshot(" + SITE_COLUMNS + ") SELECT " + projection +
                    " FROM format46_p25_site_snapshot ORDER BY channel_id");
                if(scalar(connection, "SELECT count(*) FROM p25_site_snapshot") != count)
                    throw new SQLException("P25 site migration changed the retained row count");
                statement.executeUpdate("DROP TABLE format46_p25_site_snapshot");
                statement.executeUpdate("CREATE INDEX idx_p25_site_snapshot_identity " +
                    "ON p25_site_snapshot(rfss, site, channel_id)");
                statement.executeUpdate("CREATE INDEX idx_p25_site_snapshot_retention " +
                    "ON p25_site_snapshot(last_seen_ms, channel_id)");
            }
            finally
            {
                statement.execute("PRAGMA legacy_alter_table=" + legacyAlterTable);
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            if(!rows.next()) throw new SQLException("P25 site migration count returned no result");
            return rows.getLong(1);
        }
    }
}
