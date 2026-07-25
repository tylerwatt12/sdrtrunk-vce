import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

/** External one-off migration that adds the optional Alias description field. */
public class AliasV2ToV3DescriptionMigrator
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String VERSION_KEY = "alias_schema_version";

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" " +
                "AliasV2ToV3DescriptionMigrator.java <database>");
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

        int aliasCount;

        try(Connection connection = open(database))
        {
            String version = schemaVersion(connection);

            if("3".equals(version))
            {
                SdrTrunkDatabaseSchema.validate(connection);
                System.out.println("Database is already at Alias schema v3.");
                return;
            }

            if(!"2".equals(version))
            {
                throw new SQLException("Expected alias_schema_version 2, found [" + version +
                    "]. Refusing migration.");
            }

            Set<String> columns = columns(connection, "alias");
            if(columns.contains("description"))
            {
                throw new SQLException("Alias description column already exists while schema metadata is v2. " +
                    "Refusing a partial or previously modified schema.");
            }

            if(!"ok".equals(scalar(connection, "PRAGMA integrity_check")))
            {
                throw new SQLException("Integrity check failed before migration.");
            }

            aliasCount = count(connection, "alias");
            checkpoint(connection);
        }

        Path backup = database.resolveSibling(database.getFileName() + ".backup-alias-v2-to-v3-" +
            TIMESTAMP.format(LocalDateTime.now()));
        Files.copy(database, backup, StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("Backup created: " + backup);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");

            try
            {
                statement.executeUpdate("ALTER TABLE alias ADD COLUMN description TEXT");
                SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "3");
                SdrTrunkDatabaseSchema.validate(connection);

                if(aliasCount != count(connection, "alias"))
                {
                    throw new SQLException("Alias row count changed during migration.");
                }

                statement.execute("COMMIT");
            }
            catch(SQLException exception)
            {
                statement.execute("ROLLBACK");
                throw exception;
            }

            if(!"ok".equals(scalar(connection, "PRAGMA quick_check")))
            {
                throw new SQLException("Quick check failed after migration. Restore the timestamped backup.");
            }

            statement.execute("PRAGMA optimize");
            checkpoint(connection);
        }

        System.out.println("Alias schema migration complete: v2 -> v3 description storage.");
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
        try(PreparedStatement statement =
                connection.prepareStatement("SELECT value FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, VERSION_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException
    {
        Set<String> columns = new HashSet<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                columns.add(resultSet.getString("name"));
            }
        }

        return columns;
    }

    private static int count(Connection connection, String table) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            if(resultSet.next())
            {
                return resultSet.getInt(1);
            }
        }

        throw new SQLException("Unable to count rows in table [" + table + "]");
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
