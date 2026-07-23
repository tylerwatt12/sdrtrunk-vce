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

/** External one-off migration adding scoped foreign-system frequency bands in schema v20. */
public class P25HistoryV19ToV20ForeignSystemBandsMigrator
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String VERSION_KEY = "p25_activity_schema_version";

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" " +
                "P25HistoryV19ToV20ForeignSystemBandsMigrator.java <database>");
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

            if("20".equals(version))
            {
                P25ActivityLogSchema.validateV20ForUpgrade(connection);
                System.out.println("Database is already at P25 activity schema v20.");
                return;
            }

            if(!"19".equals(version))
            {
                throw new SQLException("Expected p25_activity_schema_version 19, found [" + version +
                    "]. Refusing migration.");
            }

            if(!"ok".equals(scalar(connection, "PRAGMA integrity_check")))
            {
                throw new SQLException("Integrity check failed before migration.");
            }

            checkpoint(connection);
        }

        Path backup = database.resolveSibling(database.getFileName() + ".backup-p25-v19-to-v20-" +
            TIMESTAMP.format(LocalDateTime.now()));
        Files.copy(database, backup, StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("Backup created: " + backup);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");

            try
            {
                P25ActivityLogSchema.createForeignSystemBandTables(statement);
                SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "20");
                P25ActivityLogSchema.validateV20ForUpgrade(connection);
                statement.execute("COMMIT");
            }
            catch(SQLException e)
            {
                statement.execute("ROLLBACK");
                throw e;
            }

            if(!"ok".equals(scalar(connection, "PRAGMA quick_check")))
            {
                throw new SQLException("Quick check failed after migration.");
            }

            statement.execute("PRAGMA optimize");
            checkpoint(connection);
        }

        System.out.println("P25 activity schema migration complete: v19 -> v20 foreign-system bands.");
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
