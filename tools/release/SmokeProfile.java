/* Copyright (C) 2026. Distributed under the GNU General Public License, version 3 or later. */
import io.github.dsheirer.database.SdrTrunkDatabaseBootstrap;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.upgrade.DatabaseFormatCatalog;
import io.github.dsheirer.database.upgrade.SqliteDatabaseSnapshot;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Creates an isolated, hardware-free profile before the packaged launcher is tested. */
public class SmokeProfile
{
    public static void main(String[] args) throws Exception
    {
        if(args.length == 3 && "--snapshot".equals(args[0]))
        {
            // The bundled snapshot owns companion naming, exact pair admission and SQLite-aware copies.
            SqliteDatabaseSnapshot.create(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if(args.length == 2 && "--validate".equals(args[0]))
        {
            ConfigurationHistoryDatabase.validatePair(Path.of(args[1]));
            return;
        }
        SdrTrunkDatabaseBootstrap.run(new String[]{"--fresh", "--admin-password-file", args[0]});
        Path database = SdrTrunkDatabasePath.getDatabasePath();
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        ConfigurationHistoryDatabase.validatePair(database);
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
        int targetFormat = DatabaseFormatCatalog.current().version();
        Files.writeString(Path.of(args[2]), Integer.toString(targetFormat));
        System.out.println("Isolated profile ready; database format " + targetFormat);
    }
}
