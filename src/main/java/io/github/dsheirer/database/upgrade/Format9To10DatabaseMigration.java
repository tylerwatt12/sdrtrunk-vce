/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Introduces optional P25 bandplan override configuration without changing existing administrator data. */
final class Format9To10DatabaseMigration implements DatabaseMigrationStep
{
    @Override
    public String id()
    {
        return "format-9-to-10";
    }

    @Override
    public String description()
    {
        return "Add optional P25 bandplan override configuration";
    }

    @Override
    public int sourceVersion()
    {
        return 9;
    }

    @Override
    public int targetVersion()
    {
        return 10;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        return effects(configurationRowCount(connection));
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
    }

    private static List<DatabaseMigrationEffect> effects(long configurationRows)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "saved channels and application settings", configurationRows,
                "Preserve every saved channel and application setting without rewriting its JSON"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "P25 bandplan overrides", 0,
                "Keep override profiles absent until an administrator creates one and treat the absent saved-channel " +
                    "opt-in setting as disabled"));
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
        if(detected.version() != 9)
        {
            throw new SQLException("Migration step format-9-to-10 requires exact source format 9; found " +
                detected.version() + " [" + detected.id() + "]");
        }
    }

    private static long configurationRowCount(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
            SELECT (SELECT COUNT(*) FROM configuration_channel) +
                   (SELECT COUNT(*) FROM application_settings)
            """))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }
}
