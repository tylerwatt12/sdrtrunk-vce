/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.settings.EncryptionKeySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Adds ordinary key configuration without touching retained receiver Activity. */
final class Format75To76DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "database-voice-keys-v1"; }
    public String description() { return "Store voice encryption keys with application settings"; }
    public int sourceVersion() { return 75; }
    public int targetVersion() { return 76; }

    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "configuration and receiver history", 0,
                "Preserve all existing settings, credentials, timestamps, retained events, indexes and recordings"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "voice encryption key configuration", 1,
                "Begin with no voice encryption keys; new keys are ordinary unencrypted application settings"));
    }

    public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }

    public void migrateSource(Connection connection) throws SQLException
    {
        ApplicationMigrationTransaction.requireActive(connection);
        boolean configurationOwner = ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        String configuration = configurationOwner ? "main" : "configuration";
        if(!configurationOwner && !ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Voice key migration requires a recognized database role");
        String companion = configurationOwner ? "activity" : "configuration";
        String role = configurationOwner ? ConfigurationHistorySchema.HISTORY_ROLE :
            ConfigurationHistorySchema.CONFIGURATION_ROLE;
        String fingerprint = SqliteSchemaValidator.fingerprint(connection, companion);
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role, fingerprint, companion);
        if(!"75".equals(ConfigurationHistorySchema.metadata(connection, companion,
            DatabaseFormatCatalog.FORMAT_VERSION_KEY)) ||
            !Objects.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(connection, companion, ConfigurationHistorySchema.PAIR_ID_KEY)))
            throw new SQLException("Voice key migration requires its exact matching format-75 companion");

        // Schema is unchanged. A pre-63 monolith still completing the split chain has configuration attached.
        try(var insert = connection.prepareStatement("INSERT INTO " + configuration +
            ".application_settings(key,settings_json,updated_at_ms) SELECT ?, '[]', ? WHERE NOT EXISTS" +
            "(SELECT 1 FROM " + configuration + ".application_settings WHERE key=?)"))
        {
            insert.setString(1, EncryptionKeySchema.KEY);
            insert.setLong(2, System.currentTimeMillis());
            insert.setString(3, EncryptionKeySchema.KEY);
            insert.executeUpdate();
        }
        try(var update = connection.prepareStatement("UPDATE " + companion +
            ".database_metadata SET value='76',updated_at_ms=? WHERE key=? AND value='75'"))
        {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            if(update.executeUpdate() != 1)
                throw new SQLException("The companion format marker changed during voice key migration");
        }
    }
}
