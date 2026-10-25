package io.github.dsheirer.stats.activity;

import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.StatsHistoryContext;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Explicit current-runtime fixtures; frozen migration creators and fixtures remain unchanged. */
final class ActivityTestDatabase
{
    private ActivityTestDatabase() {}

    /** Fixture setup may write both stores; production statistics connections attach configuration read-only. */
    static Connection openPair(Path configuration) throws Exception
    {
        Connection connection=DriverManager.getConnection("jdbc:sqlite:"+SdrTrunkDatabase.historyPath(configuration));
        try(var attach=connection.prepareStatement("ATTACH DATABASE ? AS configuration"))
        { attach.setString(1,configuration.toString());attach.execute(); }
        StatsHistoryContext.installSubscriberContext(connection);
        ReceiverActivitySchema.installConfigurationReadView(connection);
        return connection;
    }

    /** Compact in-memory SQL tests can retain their configuration tables while using current historical owners. */
    static void currentOwners(Connection connection) throws Exception
    {
        try(Statement statement=connection.createStatement()) { statement.execute("PRAGMA foreign_keys=OFF"); }
        ConfigurationHistorySchema.separateHistoryOwners(connection);
        try(Statement statement=connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        StatsHistoryContext.installSubscriberContext(connection);
        ReceiverActivitySchema.installConfigurationReadView(connection);
    }
    static void validateCurrent(Connection connection) throws Exception
    {
        SqliteSchemaValidator.validateDefinitions(connection, currentHistoryDefinitions());
        ActivityMessageCatalog.validate(connection);
        RadioSystemSchema.validateRadioSystemKeys(connection);
    }

    private static java.util.List<SqliteSchemaValidator.Definition> sHistoryDefinitions;

    private static synchronized java.util.List<SqliteSchemaValidator.Definition> currentHistoryDefinitions()
        throws Exception
    {
        if(sHistoryDefinitions == null)
        {
            java.util.List<SqliteSchemaValidator.Definition> definitions = new java.util.ArrayList<>();
            try(Connection reference = DriverManager.getConnection("jdbc:sqlite::memory:"))
            {
                ConfigurationHistorySchema.createHistory(reference);
                try(Statement query = reference.createStatement(); var rows = query.executeQuery(
                    "SELECT type,name,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' ORDER BY type,name"))
                {
                    while(rows.next()) definitions.add(new SqliteSchemaValidator.Definition(
                        rows.getString(1), rows.getString(2), rows.getString(3)));
                }
            }
            sHistoryDefinitions = java.util.List.copyOf(definitions);
        }
        return sHistoryDefinitions;
    }

}
