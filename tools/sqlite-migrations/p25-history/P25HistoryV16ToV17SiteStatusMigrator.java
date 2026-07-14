import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** External one-off migration adding latest P25 site status and channel callsigns in schema v17. */
public class P25HistoryV16ToV17SiteStatusMigrator
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String VERSION_KEY = "p25_activity_schema_version";

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" " +
                "P25HistoryV16ToV17SiteStatusMigrator.java <database>");
            return;
        }

        Class.forName("org.sqlite.JDBC");
        migrate(Path.of(args[0]).toAbsolutePath().normalize());
    }

    private static void migrate(Path database) throws IOException, SQLException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("Database not found: " + database);
        }

        try(Connection connection = open(database))
        {
            String version = schemaVersion(connection);
            if("17".equals(version))
            {
                P25ActivityLogSchema.validate(connection);
                System.out.println("Database is already at P25 activity schema v17.");
                return;
            }
            if(!"16".equals(version))
            {
                throw new SQLException("Expected p25_activity_schema_version 16, found [" + version +
                    "]. Refusing migration.");
            }
            if(!"ok".equals(scalar(connection, "PRAGMA integrity_check")))
            {
                throw new SQLException("Integrity check failed before migration.");
            }
            checkpoint(connection);
        }

        Path backup = database.resolveSibling(database.getFileName() + ".backup-p25-v16-to-v17-" +
            TIMESTAMP.format(LocalDateTime.now()));
        Files.copy(database, backup, StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("Backup created: " + backup);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");
            try
            {
                for(String column: new String[]{"lra INTEGER", "mfid INTEGER", "broadcast_clock_ms INTEGER",
                    "micro_slots INTEGER", "data_service INTEGER", "data_access TEXT",
                    "wuid_lease_minutes INTEGER", "registration_service INTEGER", "tdma INTEGER",
                    "voice_service INTEGER"})
                {
                    statement.executeUpdate("ALTER TABLE p25_site_snapshot ADD COLUMN " + column);
                }
                statement.executeUpdate("ALTER TABLE p25_site_channel ADD COLUMN callsign TEXT");
                SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "17");
                P25ActivityLogSchema.validate(connection);
                statement.execute("COMMIT");
            }
            catch(SQLException e)
            {
                statement.execute("ROLLBACK");
                throw e;
            }

            statement.execute("PRAGMA optimize");
            checkpoint(connection);
        }

        System.out.println("P25 activity schema migration complete: v16 -> v17 latest site status and callsigns.");
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private static String schemaVersion(Connection connection) throws SQLException
    {
        try(var statement = connection.prepareStatement("SELECT value FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, VERSION_KEY);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static void checkpoint(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }
}
