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

/** External one-off migration adding compact P25 talkgroup output summaries in schema v19. */
public class P25HistoryV18ToV19TalkgroupOutputSummaryMigrator
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String VERSION_KEY = "p25_activity_schema_version";

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" " +
                "P25HistoryV18ToV19TalkgroupOutputSummaryMigrator.java <database>");
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

        long outputMetricsStartedAt;

        try(Connection connection = open(database))
        {
            String version = schemaVersion(connection);

            if("19".equals(version))
            {
                P25ActivityLogSchema.validate(connection);
                System.out.println("Database is already at P25 activity schema v19.");
                return;
            }

            if(!"18".equals(version))
            {
                throw new SQLException("Expected p25_activity_schema_version 18, found [" + version +
                    "]. Refusing migration.");
            }

            if(!"ok".equals(scalar(connection, "PRAGMA integrity_check")))
            {
                throw new SQLException("Integrity check failed before migration.");
            }

            outputMetricsStartedAt = schemaUpdatedAt(connection);
            checkpoint(connection);
        }

        if(outputMetricsStartedAt <= 0)
        {
            outputMetricsStartedAt = System.currentTimeMillis();
        }

        Path backup = database.resolveSibling(database.getFileName() + ".backup-p25-v18-to-v19-" +
            TIMESTAMP.format(LocalDateTime.now()));
        Files.copy(database, backup, StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("Backup created: " + backup);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");

            try
            {
                statement.executeUpdate("ALTER TABLE p25_talkgroup_summary " +
                    "ADD COLUMN recorded_count INTEGER NOT NULL DEFAULT 0");
                statement.executeUpdate("ALTER TABLE p25_talkgroup_summary " +
                    "ADD COLUMN streamed_count INTEGER NOT NULL DEFAULT 0");
                statement.executeUpdate("""
                    INSERT INTO p25_talkgroup_summary (
                        system_key, talkgroup_id, first_seen_ms, last_seen_ms, recorded_count, streamed_count
                    )
                    SELECT context.system_key, bucket.talkgroup_id, MIN(bucket.bucket_start_ms),
                        MAX(bucket.bucket_start_ms), SUM(bucket.recorded_count), SUM(bucket.streamed_count)
                    FROM p25_site_talkgroup_bucket bucket
                    JOIN receiver_context context ON context.id = bucket.context_id
                    WHERE context.system_key IS NOT NULL
                        AND (bucket.recorded_count > 0 OR bucket.streamed_count > 0)
                    GROUP BY context.system_key, bucket.talkgroup_id
                    ON CONFLICT(system_key, talkgroup_id) DO UPDATE SET
                        recorded_count = excluded.recorded_count,
                        streamed_count = excluded.streamed_count
                    """);
                SdrTrunkDatabaseStartup.setMetadata(connection,
                    P25ActivityLogSchema.CALL_OUTPUT_METRICS_STARTED_AT_KEY,
                    Long.toString(outputMetricsStartedAt));
                SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "19");
                P25ActivityLogSchema.validate(connection);
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

        System.out.println("P25 activity schema migration complete: v18 -> v19 talkgroup output summaries.");
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

    private static long schemaUpdatedAt(Connection connection) throws SQLException
    {
        try(var statement = connection.prepareStatement("SELECT updated_at_ms FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, VERSION_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getLong(1) : 0;
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
