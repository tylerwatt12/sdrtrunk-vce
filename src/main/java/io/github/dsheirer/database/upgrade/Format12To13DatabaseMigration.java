package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.gui.setup.SetupProgress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds bounded setup progress without resetting any administrator-owned preferences. */
final class Format12To13DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "format-12-to-13"; }
    public String description() { return "Add resumable setup wizard state"; }
    public int sourceVersion() { return 12; }
    public int targetVersion() { return 13; }
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "setup wizard progress", 1,
            "Mark existing profiles as previously configured; preserve all settings and recheck actual readiness at launch"));
    }
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspect(connection).version() != 12) throw new SQLException("Expected format 12");
        try(var query = connection.prepareStatement("SELECT COUNT(*) FROM application_settings WHERE key=?"))
        {
            query.setString(1, SetupProgress.KEY);
            try(var rows = query.executeQuery())
            {
                if(rows.next() && rows.getInt(1) != 0) throw new SQLException("Unexpected setup progress in format 12");
            }
        }
        return declaredEffects();
    }
    public void migrate(Connection connection) throws SQLException
    {
        validateSource(connection);
        SetupProgress.write(connection, new SetupProgress(true, false));
    }
}
