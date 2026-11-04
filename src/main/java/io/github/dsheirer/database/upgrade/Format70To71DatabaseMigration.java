/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Retires the extra MP3 gain choice without changing unrelated preferences or retained Activity. */
final class Format70To71DatabaseMigration implements DatabaseMigrationStep
{
    private static final String PORTABLE_KEY = "portable_java_preferences_v1";
    private static final String MP3_NODE = "user/io/github/dsheirer/preference/mp3";
    private static final String NORMALIZE_KEY = "audio.mp3.normalize.before.encode";
    // Match the portable preference reader and the earlier audio retirement: the last duplicate value wins.
    private static final ObjectMapper JSON = new ObjectMapper();

    public String id() { return "retired-mp3-normalization-v1"; }
    public String description() { return "Remove retired MP3 normalization"; }
    public int sourceVersion() { return 70; }
    public int targetVersion() { return 71; }
    public List<DatabaseMigrationEffect> declaredEffects() { return effects(DatabaseMigrationEffect.UNKNOWN_COUNT); }
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        return effects(portableUpdate(connection, configurationSchema(connection)).removed());
    }

    public void migrateSource(Connection connection) throws SQLException
    {
        ApplicationMigrationTransaction.requireActive(connection);
        boolean configurationOwner = ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        String configuration = configurationSchema(connection);
        String companion = configurationOwner ? "activity" : "configuration";
        String role = configurationOwner ? ConfigurationHistorySchema.HISTORY_ROLE :
            ConfigurationHistorySchema.CONFIGURATION_ROLE;
        String fingerprint = SqliteSchemaValidator.fingerprint(connection, companion);
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role, fingerprint, companion);
        if(!"70".equals(ConfigurationHistorySchema.metadata(connection, companion,
            DatabaseFormatCatalog.FORMAT_VERSION_KEY)) ||
            !Objects.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(connection, companion, ConfigurationHistorySchema.PAIR_ID_KEY)))
            throw new SQLException("MP3 setting retirement requires its exact matching format-70 companion");

        PortableUpdate portable = portableUpdate(connection, configuration);
        if(portable.removed() > 0)
        {
            try(var update = connection.prepareStatement("UPDATE " + configuration +
                ".application_settings SET settings_json=? WHERE key=?"))
            {
                update.setString(1, portable.json());
                update.setString(2, PORTABLE_KEY);
                if(update.executeUpdate() != 1) throw new SQLException("Retired MP3 preference could not be removed");
            }
        }

        // The same transaction advances the pair's global markers. No Activity row or index is read or rewritten.
        // A legacy monolith's extracted configuration is already attached by the preceding split migration.
        try(var update = connection.prepareStatement("UPDATE " + companion +
            ".database_metadata SET value='71',updated_at_ms=? WHERE key=? AND value='70'"))
        {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            if(update.executeUpdate() != 1)
                throw new SQLException("The companion format marker changed during MP3 setting retirement");
        }
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role, fingerprint, companion);
    }

    /** Existing databases are validated only; startup never silently removes obsolete values. */
    static void validateRetirement(Connection connection) throws SQLException
    {
        if(portableUpdate(connection, "main").removed() != 0)
            throw new SQLException("Retired MP3 normalization remains in the current database; restore a valid database before starting");
    }

    private static String configurationSchema(Connection connection) throws SQLException
    {
        if(ConfigurationHistoryDatabase.isPairedConfiguration(connection)) return "main";
        if(ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY))) return "configuration";
        throw new SQLException("MP3 setting retirement requires a recognized database role");
    }

    private static PortableUpdate portableUpdate(Connection connection, String schema) throws SQLException
    {
        try(var query = connection.prepareStatement("""
            SELECT CASE WHEN typeof(settings_json)='text' AND length(CAST(settings_json AS BLOB))<=4194304
                        THEN settings_json END AS settings_json
            FROM %s.application_settings WHERE key=?
            """.formatted(schema)))
        {
            query.setString(1, PORTABLE_KEY);
            try(var row = query.executeQuery())
            {
                if(!row.next()) return new PortableUpdate(null, 0);
                String source = row.getString(1);
                try
                {
                    if(source != null && JSON.readTree(source) instanceof ObjectNode root &&
                        root.get(MP3_NODE) instanceof ObjectNode mp3 && mp3.has(NORMALIZE_KEY))
                    {
                        mp3.remove(NORMALIZE_KEY);
                        if(mp3.isEmpty()) root.remove(MP3_NODE);
                        return new PortableUpdate(JSON.writeValueAsString(root), 1);
                    }
                }
                catch(java.io.IOException | RuntimeException malformed)
                {
                    // Existing bounded component repair owns unreadable portable settings; unrelated nodes survive.
                }
                return new PortableUpdate(null, 0);
            }
        }
    }

    private static List<DatabaseMigrationEffect> effects(long removed)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "configuration and receiver history", 0,
                "Preserve recording and streaming choices, unrelated preferences and timestamps, credentials, " +
                    "retained observations, allocators, indexes and planner statistics"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "retired MP3 normalization preference", removed,
                "Remove only the former MP3 normalization key from its exact portable preference node"));
    }

    private record PortableUpdate(String json, int removed) {}
}
