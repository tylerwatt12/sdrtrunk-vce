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

/** External one-off migration adding recorded and streamed hourly counters in schema v18. */
public class P25HistoryV17ToV18CallOutputMetricsMigrator
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String VERSION_KEY = "p25_activity_schema_version";

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" " +
                "P25HistoryV17ToV18CallOutputMetricsMigrator.java <database>");
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

            if("18".equals(version))
            {
                P25ActivityLogSchema.validate(connection);
                System.out.println("Database is already at P25 activity schema v18.");
                return;
            }

            if(!"17".equals(version))
            {
                throw new SQLException("Expected p25_activity_schema_version 17, found [" + version +
                    "]. Refusing migration.");
            }

            if(!"ok".equals(scalar(connection, "PRAGMA integrity_check")))
            {
                throw new SQLException("Integrity check failed before migration.");
            }

            checkpoint(connection);
        }

        Path backup = database.resolveSibling(database.getFileName() + ".backup-p25-v17-to-v18-" +
            TIMESTAMP.format(LocalDateTime.now()));
        Files.copy(database, backup, StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("Backup created: " + backup);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");

            try
            {
                for(String table: new String[]{"p25_site_talkgroup_bucket", "p25_site_activity_bucket"})
                {
                    statement.executeUpdate("ALTER TABLE " + table +
                        " ADD COLUMN recorded_count INTEGER NOT NULL DEFAULT 0");
                    statement.executeUpdate("ALTER TABLE " + table +
                        " ADD COLUMN streamed_count INTEGER NOT NULL DEFAULT 0");
                }

                SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "18");
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

        System.out.println("P25 activity schema migration complete: v17 -> v18 call output metrics.");
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
