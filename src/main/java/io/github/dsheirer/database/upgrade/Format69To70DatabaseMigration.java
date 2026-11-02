/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Prospective voice-call collection semantics; historical observations cannot be associated exactly. */
final class Format69To70DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "call-owned-voice-activity-v1"; }
    public String description() { return "Keep new voice-call updates on their original Activity entry"; }
    public int sourceVersion() { return 69; }
    public int targetVersion() { return 70; }

    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "existing Activity and statistics", 0,
            "Preserve every event, identity relationship, call connection, counter, setting and recording; " +
                "do not guess connections between historical calls and active-channel entries"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "new voice-call Activity", 1,
                "Keep updates on the exact original call while retaining independent signaling and data activity"));
    }

    public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }

    public void migrateSource(Connection connection) throws SQLException
    {
        ApplicationMigrationTransaction.requireActive(connection);
        boolean configurationOwner = ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        if(!configurationOwner && !ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Voice-call Activity migration requires a recognized database role");

        // An owned pair attaches Activity before BEGIN. A legacy monolith's extracted configuration is already
        // attached by the split step. Both marker writes belong to this transaction; no history is inspected.
        String schema = configurationOwner ? "activity" : "configuration";
        String role = configurationOwner ? ConfigurationHistorySchema.HISTORY_ROLE :
            ConfigurationHistorySchema.CONFIGURATION_ROLE;
        String fingerprint = SqliteSchemaValidator.fingerprint(connection, schema);
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role, fingerprint, schema);
        if(!"69".equals(ConfigurationHistorySchema.metadata(connection, schema,
            DatabaseFormatCatalog.FORMAT_VERSION_KEY)) ||
            !Objects.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(connection, schema, ConfigurationHistorySchema.PAIR_ID_KEY)))
            throw new SQLException("Voice-call Activity migration requires its exact matching format-69 companion");

        try(var update = connection.prepareStatement("UPDATE " + schema +
            ".database_metadata SET value='70',updated_at_ms=? WHERE key=? AND value='69'"))
        {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            if(update.executeUpdate() != 1)
                throw new SQLException("The companion format marker changed during voice-call Activity migration");
        }
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role, fingerprint, schema);
    }
}
