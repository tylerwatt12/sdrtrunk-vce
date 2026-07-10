import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * External one-off correction that makes every Stats Server hits column equal its grant counter.
 */
public class P25HistoryNormalizeGrantHits
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> TABLES = List.of(
        "p25_talkgroup_summary",
        "p25_radio_summary",
        "p25_radio_talkgroup_summary",
        "p25_site_frequency_summary",
        "p25_site_talkgroup_bucket",
        "p25_site_radio_bucket",
        "p25_site_frequency_bucket",
        "conventional_activity_summary",
        "conventional_activity_bucket"
    );

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" P25HistoryNormalizeGrantHits.java <database>");
            return;
        }

        Class.forName("org.sqlite.JDBC");
        normalize(Path.of(args[0]).toAbsolutePath().normalize());
    }

    private static void normalize(Path database) throws IOException, SQLException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("Database not found: " + database);
        }

        checkpoint(database);
        Path backup = database.resolveSibling(database.getFileName() + ".backup-grant-hits-" +
            TIMESTAMP.format(LocalDateTime.now()));
        Files.copy(database, backup);
        System.out.println("Backup created: " + backup);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("BEGIN IMMEDIATE");

            try
            {
                int updated = 0;

                for(String table: TABLES)
                {
                    updated += statement.executeUpdate("UPDATE " + table + " SET hits = grant_count " +
                        "WHERE hits <> grant_count");
                }

                validate(statement);
                statement.execute("COMMIT");
                System.out.println("Grant-only hit normalization complete. Rows corrected: " + updated);
            }
            catch(SQLException e)
            {
                statement.execute("ROLLBACK");
                throw e;
            }

            statement.execute("PRAGMA optimize");
        }

        checkpoint(database);
    }

    private static void validate(Statement statement) throws SQLException
    {
        for(String table: TABLES)
        {
            try(ResultSet resultSet = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE hits <> grant_count"))
            {
                if(resultSet.next() && resultSet.getLong(1) != 0)
                {
                    throw new SQLException("Grant-hit validation failed for table: " + table);
                }
            }
        }
    }

    private static void checkpoint(Path database) throws SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }
}
