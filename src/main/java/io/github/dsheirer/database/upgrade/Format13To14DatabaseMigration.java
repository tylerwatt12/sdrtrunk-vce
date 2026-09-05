/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.web.settings.SpectrumSnapSettings;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds an explicit receiver-wide country selection for the code-owned frequency-scope catalog. */
final class Format13To14DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "format-13-to-14"; }
    public String description() { return "Select the United States spectrum frequency-scope catalog"; }
    public int sourceVersion() { return 13; }
    public int targetVersion() { return 14; }
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "spectrum frequency-scope country selection", 1,
            "Select the United States built-in frequency scopes and snap rules for existing profiles"));
    }

    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspect(connection).version() != 13)
        {
            throw new SQLException("Expected format 13");
        }
        try(var query = connection.prepareStatement("SELECT COUNT(*) FROM application_settings WHERE key=?"))
        {
            query.setString(1, SpectrumSnapSettings.KEY);
            try(var rows = query.executeQuery())
            {
                if(rows.next() && rows.getInt(1) != 0)
                {
                    throw new SQLException("Unexpected spectrum-snap country selection in format 13");
                }
            }
        }
        return declaredEffects();
    }

    public void migrate(Connection connection) throws SQLException
    {
        validateSource(connection);
        SpectrumSnapSettings.write(connection, SpectrumSnapSettings.defaults());
    }
}
