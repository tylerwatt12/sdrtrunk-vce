import io.github.dsheirer.radioresolve.activitylog.P25ActivityLogSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * External one-off reset for the SDRTrunk Stats Server schema.
 *
 * This intentionally drops only activity/history tables, views, and indexes, then recreates the current v11 schema.
 * It does not touch SDRTrunk configuration, channels, aliases, streams, preferences, or vault data.
 */
public class P25HistoryResetToV11StatsSchema
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final String[] VIEWS = {
        "activity_event_resolved",
        "p25_activity_event_resolved"
    };

    private static final String[] INDEXES = {
        "idx_activity_event_guid_time",
        "idx_activity_event_context_time",
        "idx_activity_event_target_time",
        "idx_activity_event_source_time",
        "idx_activity_event_frequency_time",
        "idx_activity_event_encryption",
        "idx_site_snapshot_identity",
        "idx_site_channel_guid_frequency",
        "idx_site_neighbor_guid_site",
        "idx_site_patch_talkgroup",
        "idx_site_patch_radio",
        "idx_receiver_context_guid",
        "idx_p25_activity_event_context_time",
        "idx_p25_activity_event_target_time",
        "idx_p25_activity_event_source_time",
        "idx_p25_activity_event_frequency_time",
        "idx_p25_activity_event_encryption",
        "idx_p25_talkgroup_bucket_time",
        "idx_p25_talkgroup_bucket_talkgroup_time",
        "idx_p25_radio_bucket_time",
        "idx_p25_frequency_bucket_time",
        "idx_conventional_bucket_time",
        "idx_p25_site_snapshot_identity",
        "idx_p25_site_channel_guid_frequency",
        "idx_p25_site_neighbor_guid_site",
        "idx_p25_site_patch_talkgroup",
        "idx_p25_site_patch_radio"
    };

    private static final String[] TABLES = {
        "activity_event",
        "talkgroup_summary",
        "radio_user_summary",
        "frequency_summary",
        "radio_context",
        "site_talker_alias",
        "site_patch_group_radio",
        "site_patch_group_talkgroup",
        "site_patch_group",
        "site_neighbor",
        "site_frequency_band",
        "site_channel",
        "site_snapshot",
        "receiver_context",
        "p25_activity_event",
        "p25_talkgroup_activity_bucket",
        "p25_radio_activity_bucket",
        "p25_frequency_activity_bucket",
        "p25_talkgroup_summary",
        "p25_radio_summary",
        "p25_frequency_summary",
        "conventional_activity_bucket",
        "conventional_activity_summary",
        "p25_site_talker_alias",
        "p25_site_patch_group_radio",
        "p25_site_patch_group_talkgroup",
        "p25_site_patch_group",
        "p25_site_neighbor",
        "p25_site_frequency_band",
        "p25_site_channel",
        "p25_site_snapshot",
        "logger_status"
    };

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" P25HistoryResetToV11StatsSchema.java <database>");
            return;
        }

        Class.forName("org.sqlite.JDBC");
        reset(Path.of(args[0]).toAbsolutePath().normalize());
    }

    private static void reset(Path database) throws IOException, SQLException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("Database not found: " + database);
        }

        Path backup = backup(database);
        System.out.println("Backup created: " + backup);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            configure(connection);
            dropStatsObjects(connection);
            P25ActivityLogSchema.create(connection);
            P25ActivityLogSchema.validate(connection);
            vacuum(connection);
            checkpoint(connection);
            optimize(connection);
        }

        System.out.println("Stats Server schema reset complete: v11 summary-first activity logging.");
    }

    private static Path backup(Path database) throws IOException
    {
        Path backup = database.resolveSibling(database.getFileName() + ".backup-stats-v11-reset-" +
            TIMESTAMP.format(LocalDateTime.now()));
        Files.copy(database, backup);
        return backup;
    }

    private static void dropStatsObjects(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("BEGIN IMMEDIATE");

            try
            {
                for(String view: VIEWS)
                {
                    statement.execute("DROP VIEW IF EXISTS " + view);
                }

                for(String index: INDEXES)
                {
                    statement.execute("DROP INDEX IF EXISTS " + index);
                }

                for(String table: TABLES)
                {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }

                statement.executeUpdate("""
                    DELETE FROM database_metadata
                    WHERE key = 'p25_activity_schema_version'
                    """);
                statement.execute("COMMIT");
            }
            catch(SQLException e)
            {
                statement.execute("ROLLBACK");
                throw e;
            }
            finally
            {
                statement.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    private static void configure(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    private static void vacuum(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("VACUUM");
        }
    }

    private static void checkpoint(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private static void optimize(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA optimize");
        }
    }
}
