/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.web.settings.SpectrumSnapSettings;
import java.sql.Connection;
import java.sql.ResultSet;
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
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        SettingInspection inspection = inspectSetting(connection);
        return effects(inspection.preserved() ? 0 : 1, inspection.preserved() ? 1 : 0);
    }

    public void migrate(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        SettingInspection inspection = inspectSetting(connection);
        if(!inspection.preserved())
        {
            SpectrumSnapSettings.write(connection, SpectrumSnapSettings.defaults());
        }
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 13)
        {
            throw new SQLException("Expected format 13");
        }
    }

    private static SettingInspection inspectSetting(Connection connection) throws SQLException
    {
        try(var query = connection.prepareStatement("""
            SELECT CASE WHEN typeof(settings_json)='text'
                              AND length(CAST(settings_json AS BLOB)) <= 256
                        THEN settings_json END AS settings_json
            FROM application_settings WHERE key=?
            """))
        {
            query.setString(1, SpectrumSnapSettings.KEY);
            try(ResultSet rows = query.executeQuery())
            {
                if(rows.next())
                {
                    try
                    {
                        SpectrumSnapSettings.decode(rows.getString(1));
                        return new SettingInspection(true);
                    }
                    catch(SQLException ignored)
                    {
                        //A damaged or premature setting is safe to replace with the built-in default.
                    }
                }
            }
        }

        return new SettingInspection(false);
    }

    private static List<DatabaseMigrationEffect> effects(long defaulted, long preserved)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "spectrum frequency-scope country selection", defaulted,
                "Select the United States built-in frequency scopes and snap rules when the old selection is " +
                    "missing or unusable"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "existing spectrum frequency-scope country selection", preserved,
                "Keep an already usable selection without rewriting it"));
    }

    private record SettingInspection(boolean preserved) {}
}
