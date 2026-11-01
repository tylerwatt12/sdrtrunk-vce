/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/** Replaces Activity's binary confirmation flag with one explicit call-encryption state. */
final class Format68To69DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "activity-encryption-state-v1"; }
    public String description() { return "Distinguish clear, encrypted and unknown Activity"; }
    public int sourceVersion() { return 68; }
    public int targetVersion() { return 69; }
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }
    private static List<DatabaseMigrationEffect> effects(long count)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "Activity encryption classification", count,
            "Preserve every event and link; retain confirmed or explicitly signaled encryption, leave ambiguous " +
                "historical calls unknown, and rebuild the existing event indexes without changing counters or recordings"));
    }
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    public void migrateSource(Connection connection) throws SQLException { convert(connection); }
    public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        return effects(convert(connection));
    }
    private long convert(Connection connection) throws SQLException
    {
        if(ConfigurationHistoryDatabase.isPairedConfiguration(connection))
        {
            requireAttachedHistory(connection);
            long converted = ReceiverActivitySchema.migrateFormat68EncryptionState(connection, "activity");
            stampHistory(connection, "activity");
            return converted;
        }
        if(!ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Activity encryption migration requires a recognized database role");
        long converted = ReceiverActivitySchema.migrateFormat68EncryptionState(connection, "main");
        // An older monolith is still owned by the split receipt. Its durable extracted configuration advances
        // alongside the source transaction and is activated only after the complete chain commits successfully.
        var stage = ActivityDatabasePath.configurationStage(Format62To63DatabaseMigration.databasePath(connection));
        if(!java.nio.file.Files.isRegularFile(stage) || java.nio.file.Files.isSymbolicLink(stage))
            throw new SQLException("The configuration extraction for Activity encryption is missing");
        try(Connection configuration = DriverManager.getConnection("jdbc:sqlite:" + stage))
        {
            configuration.setAutoCommit(false);
            if(DatabaseFormatCatalog.inspect(configuration).version() != sourceVersion())
                throw new SQLException("Activity encryption migration requires exact format-68 configuration");
            DatabaseFormatCatalog.stamp(configuration, targetVersion());
            DatabaseFormatCatalog.requireCurrent(configuration);
            configuration.commit();
        }
        return converted;
    }

    private static void requireAttachedHistory(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var databases = statement.executeQuery("PRAGMA database_list"))
        {
            while(databases.next()) if("activity".equals(databases.getString("name"))) return;
        }
        throw new SQLException("Activity encryption migration requires its admitted companion in the same transaction");
    }

    private static void stampHistory(Connection connection, String schema) throws SQLException
    {
        String actual = SqliteSchemaValidator.fingerprint(connection, schema);
        if(!ConfigurationHistoryDatabase.HISTORY_FINGERPRINT.equals(actual))
            throw new SQLException("Activity encryption migration did not reach the exact target schema: " + actual);
        try(var update = connection.prepareStatement("UPDATE " + schema +
            ".database_metadata SET value=?,updated_at_ms=? WHERE key=? AND CAST(value AS INTEGER) BETWEEN 63 AND 68"))
        {
            update.setString(1, "69");
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            if(update.executeUpdate() != 1) throw new SQLException("Activity format marker changed during migration");
        }
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, ConfigurationHistorySchema.HISTORY_ROLE,
            actual, schema);
    }
}
