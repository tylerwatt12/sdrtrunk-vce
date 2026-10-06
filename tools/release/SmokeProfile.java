/* Copyright (C) 2026. Distributed under the GNU General Public License, version 3 or later. */
import io.github.dsheirer.database.SdrTrunkDatabaseBootstrap;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.upgrade.DatabaseFormatCatalog;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Creates an isolated, hardware-free profile before the packaged launcher is tested. */
public class SmokeProfile
{
    public static void main(String[] args) throws Exception
    {
        SdrTrunkDatabaseBootstrap.run(new String[]{"--fresh", "--admin-password-file", args[0]});
        Path database = SdrTrunkDatabasePath.getDatabasePath();
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            if(DatabaseFormatCatalog.inspect(connection).version() != DatabaseFormatCatalog.CURRENT_VERSION)
                throw new IllegalStateException("Fresh profile has the wrong database format");
        }
        SqlitePreferencesFactory.install(database);
        try
        {
            var preferences = new UserPreferences().getApplicationPreference();
            preferences.setStatsWebServerEnabled(true);
            preferences.setStatsWebServerNetworkAccessEnabled(false);
            preferences.setStatsWebServerHttpsEnabled(false);
            preferences.setStatsWebServerPort(Integer.parseInt(args[1]));
        }
        finally
        {
            SqlitePreferencesFactory.shutdown();
        }
        System.out.println("Isolated profile ready; database format " + DatabaseFormatCatalog.CURRENT_VERSION);
    }
}
