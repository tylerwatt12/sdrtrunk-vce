/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Moves learned BSI into explicit carrier/source ownership; normal runtime performs no schema changes. */
final class Format72To73DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "carrier-bsi-v1"; }
    public String description() { return "Retain source-scoped BSI and actual last-heard times"; }
    public int sourceVersion() { return 72; }
    public int targetVersion() { return 73; }

    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "base station identification", DatabaseMigrationEffect.UNKNOWN_COUNT,
            "Preserve learned control callsigns; move BSI to explicit carrier/source records with unknown historical " +
            "last-heard times where exact BSI evidence is unavailable; rename CWID tags and the bundled Alias icon to BSI; " +
            "preserve every call, counter, setting and recording"));
    }

    public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }

    @Override
    public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        migrateSource(connection);
        String history = ConfigurationHistoryDatabase.isPairedConfiguration(connection) ? "activity" : "main";
        long count;
        try(var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT count(*) FROM " + history + ".p25_site_bsi"))
        {
            if(!rows.next()) throw new SQLException("BSI migration could not count retained callsigns");
            count = rows.getLong(1);
        }
        var declared = declaredEffects().getFirst();
        return List.of(new DatabaseMigrationEffect(declared.kind(), declared.subject(), count, declared.detail()));
    }

    public void migrateSource(Connection connection) throws SQLException
    {
        ApplicationMigrationTransaction.requireActive(connection);
        boolean configurationOwner = ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        if(!configurationOwner && !ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("BSI migration requires a recognized database role");

        // An owned pair attaches Activity before BEGIN. A legacy monolith's extracted configuration is already
        // attached by the split step. Both marker writes and the compact site conversion belong to this transaction.
        String schema = configurationOwner ? "activity" : "configuration";
        String role = configurationOwner ? ConfigurationHistorySchema.HISTORY_ROLE :
            ConfigurationHistorySchema.CONFIGURATION_ROLE;
        String fingerprint = SqliteSchemaValidator.fingerprint(connection, schema);
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role, fingerprint, schema);
        if(!"72".equals(ConfigurationHistorySchema.metadata(connection, schema,
            DatabaseFormatCatalog.FORMAT_VERSION_KEY)) ||
            !Objects.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(connection, schema, ConfigurationHistorySchema.PAIR_ID_KEY)))
            throw new SQLException("BSI migration requires its exact matching format-72 companion");

        String history = configurationOwner ? "activity" : "main";
        String config = configurationOwner ? "main" : "configuration";
        for(String table: List.of("p25_site_channel", "p25_site_channel_summary"))
        {
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT 1 FROM " + history + "." + table +
                    " WHERE length(trim(callsign))>0 AND (downlink_hz IS NULL OR downlink_hz<=0 OR length(callsign)>8) LIMIT 1"))
            {
                if(rows.next()) throw new SQLException("Cannot preserve a learned BSI without a valid frequency and eight-character callsign");
            }
        }
        io.github.dsheirer.stats.activity.ReceiverActivitySchema.createBsiTable(connection, history);
        try(var statement = connection.createStatement())
        {
            // Old control callsigns are known, but channel last_seen can be advanced by unrelated evidence.
            // Retain an exact BSI-tag reception time only when the tag supplies it; otherwise leave it unknown.
            statement.executeUpdate("INSERT INTO " + history + ".p25_site_bsi " +
                "SELECT s.channel_id,s.downlink_hz,0,s.callsign,t.last_seen_ms FROM " + history +
                ".p25_site_channel_summary s LEFT JOIN " + history + ".p25_site_channel_tag_summary t " +
                "ON t.channel_id=s.channel_id AND t.channel_key=s.channel_key AND t.tag='CWID' " +
                "WHERE s.downlink_hz>0 AND length(s.callsign) BETWEEN 1 AND 8 " +
                "ORDER BY t.last_seen_ms NULLS FIRST, s.channel_key " +
                "ON CONFLICT(channel_id,frequency_hz,source) DO UPDATE SET callsign=excluded.callsign," +
                "last_heard_ms=excluded.last_heard_ms");
            statement.executeUpdate("INSERT INTO " + history + ".p25_site_bsi " +
                "SELECT channel_id,downlink_hz,0,callsign,NULL FROM " + history + ".p25_site_channel " +
                "WHERE downlink_hz>0 AND length(callsign) BETWEEN 1 AND 8 " +
                "ON CONFLICT(channel_id,frequency_hz,source) DO NOTHING");
            statement.executeUpdate("ALTER TABLE " + history + ".p25_site_channel DROP COLUMN callsign");
            statement.executeUpdate("ALTER TABLE " + history + ".p25_site_channel_summary DROP COLUMN callsign");
            statement.executeUpdate("INSERT INTO " + history + ".p25_site_channel_tag " +
                "SELECT channel_id,channel_key,'BSI',confirmed_at_ms FROM " + history +
                ".p25_site_channel_tag WHERE tag='CWID' ON CONFLICT(channel_id,channel_key,tag) DO UPDATE SET " +
                "confirmed_at_ms=max(confirmed_at_ms,excluded.confirmed_at_ms)");
            statement.executeUpdate("INSERT INTO " + history + ".p25_site_channel_tag_summary " +
                "SELECT channel_id,channel_key,'BSI',first_seen_ms,last_seen_ms,observation_count FROM " + history +
                ".p25_site_channel_tag_summary WHERE tag='CWID' ON CONFLICT(channel_id,channel_key,tag) DO UPDATE SET " +
                "first_seen_ms=min(first_seen_ms,excluded.first_seen_ms),last_seen_ms=max(last_seen_ms,excluded.last_seen_ms)," +
                "observation_count=observation_count+excluded.observation_count");
            for(String table: List.of("p25_site_channel_tag", "p25_site_channel_tag_summary"))
                statement.executeUpdate("DELETE FROM " + history + "." + table + " WHERE tag='CWID'");
            statement.executeUpdate("UPDATE " + config + ".alias SET icon_name='BSI' WHERE icon_name='CWID'");
        }
        try(var update = connection.prepareStatement("UPDATE " + schema +
            ".database_metadata SET value='73',updated_at_ms=? WHERE key=? AND value='72'"))
        {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            if(update.executeUpdate() != 1)
                throw new SQLException("The companion format marker changed during BSI migration");
        }
        if(!configurationOwner)
            ConfigurationHistoryDatabase.validateAdmittedRole(connection, role, fingerprint, schema);
    }
}
