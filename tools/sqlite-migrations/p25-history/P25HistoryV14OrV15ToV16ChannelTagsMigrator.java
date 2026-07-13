import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
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
import java.util.stream.Collectors;

/**
 * External one-off migration from P25 history v14 or v15 to non-exclusive channel tags in v16.
 */
public class P25HistoryV14OrV15ToV16ChannelTagsMigrator
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String VERSION_KEY = "p25_activity_schema_version";

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" " +
                "P25HistoryV14OrV15ToV16ChannelTagsMigrator.java <database>");
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

        String sourceVersion;

        try(Connection connection = open(database))
        {
            sourceVersion = schemaVersion(connection);

            if("16".equals(sourceVersion))
            {
                P25ActivityLogSchema.validate(connection);
                System.out.println("Database is already at P25 activity schema v16.");
                return;
            }

            if(!"14".equals(sourceVersion) && !"15".equals(sourceVersion))
            {
                throw new SQLException("Expected p25_activity_schema_version 14 or 15, found [" + sourceVersion +
                    "]. Refusing migration.");
            }

            String integrity = scalar(connection, "PRAGMA integrity_check");

            if(!"ok".equals(integrity))
            {
                throw new SQLException("Integrity check failed before migration: " + integrity);
            }

            checkpoint(connection);
        }

        Path backup = database.resolveSibling(database.getFileName() + ".backup-p25-v" + sourceVersion +
            "-to-v16-" + TIMESTAMP.format(LocalDateTime.now()));
        Files.copy(database, backup, StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("Backup created: " + backup);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");

            try
            {
                if("14".equals(sourceVersion))
                {
                    createControlQuality(statement);
                }

                createChannelTables(statement);
                copyChannelFacts(statement);
                copyChannelEvidence(statement);
                copyDetailedDataGrants(statement);
                replaceChannelTables(statement);
                SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "16");
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

        System.out.println("P25 activity schema migration complete: v" + sourceVersion +
            " -> v16 non-exclusive channel tags.");
    }

    private static void createControlQuality(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE p25_control_channel_quality (
                guid TEXT NOT NULL,
                frequency_hz INTEGER NOT NULL,
                bucket_start_ms INTEGER NOT NULL,
                observed_at_ms INTEGER NOT NULL,
                signal_dbfs REAL,
                average_signal_dbfs REAL,
                minimum_signal_dbfs REAL,
                maximum_signal_dbfs REAL,
                decode_health_pct REAL,
                valid_frames INTEGER NOT NULL DEFAULT 0,
                invalid_frames INTEGER NOT NULL DEFAULT 0,
                corrected_bits INTEGER NOT NULL DEFAULT 0,
                sync_loss_bits INTEGER NOT NULL DEFAULT 0,
                dropped_bits INTEGER NOT NULL DEFAULT 0,
                last_valid_decode_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (guid, frequency_hz, bucket_start_ms)
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_p25_control_quality_guid_time
            ON p25_control_channel_quality(guid, observed_at_ms DESC)
            """);
    }

    private static void createChannelTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE p25_site_channel_v16 (
                guid TEXT NOT NULL,
                channel_key TEXT NOT NULL,
                descriptor TEXT,
                downlink_hz INTEGER,
                uplink_hz INTEGER,
                tdma INTEGER,
                timeslots INTEGER,
                confirmed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(guid, channel_key)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE p25_site_channel_summary_v16 (
                guid TEXT NOT NULL,
                channel_key TEXT NOT NULL,
                descriptor TEXT,
                downlink_hz INTEGER,
                uplink_hz INTEGER,
                tdma INTEGER,
                timeslots INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                observation_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, channel_key)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE p25_site_channel_tag (
                guid TEXT NOT NULL,
                channel_key TEXT NOT NULL,
                tag TEXT NOT NULL,
                confirmed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(guid, channel_key, tag)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE p25_site_channel_tag_summary (
                guid TEXT NOT NULL,
                channel_key TEXT NOT NULL,
                tag TEXT NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                observation_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(guid, channel_key, tag)
            )
            """);
    }

    private static void copyChannelFacts(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            INSERT INTO p25_site_channel_v16
                (guid, channel_key, descriptor, downlink_hz, uplink_hz, tdma, timeslots, confirmed_at_ms)
            SELECT guid, channel_key, descriptor, downlink_hz, uplink_hz, tdma, timeslots, confirmed_at_ms
            FROM p25_site_channel
            """);
        statement.executeUpdate("""
            INSERT INTO p25_site_channel_summary_v16
                (guid, channel_key, descriptor, downlink_hz, uplink_hz, tdma, timeslots,
                 first_seen_ms, last_seen_ms, observation_count)
            SELECT guid, channel_key, descriptor, downlink_hz, uplink_hz, tdma, timeslots,
                first_seen_ms, last_seen_ms, observation_count
            FROM p25_site_channel_summary
            """);
    }

    private static void copyChannelEvidence(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            INSERT INTO p25_site_channel_tag (guid, channel_key, tag, confirmed_at_ms)
            SELECT guid, channel_key,
                CASE role
                    WHEN 'current_control' THEN 'CURRENT_CONTROL'
                    WHEN 'primary_control' THEN 'CURRENT_CONTROL'
                    WHEN 'secondary_control' THEN 'ALTERNATE_CONTROL'
                    WHEN 'alternate_control' THEN 'ALTERNATE_CONTROL'
                    WHEN 'fdma_data' THEN 'DATA_ANNOUNCED'
                    WHEN 'tdma_data' THEN 'DATA_ANNOUNCED'
                END,
                confirmed_at_ms
            FROM p25_site_channel
            WHERE role IN ('current_control', 'primary_control', 'secondary_control', 'alternate_control',
                           'fdma_data', 'tdma_data')
            """);
        copyCounterTag(statement, "CONTROL", "primary_control_observations");
        copyCounterTag(statement, "ALTERNATE_CONTROL", "alternate_control_observations");
        copyCounterTag(statement, "VOICE", "traffic_observations");
        statement.executeUpdate("""
            INSERT INTO p25_site_channel_tag_summary
                (guid, channel_key, tag, first_seen_ms, last_seen_ms, observation_count)
            SELECT guid, channel_key, 'DATA_ANNOUNCED', first_seen_ms, last_seen_ms, 1
            FROM p25_site_channel_summary
            WHERE role IN ('fdma_data', 'tdma_data')
            """);
    }

    private static void copyCounterTag(Statement statement, String tag, String counter) throws SQLException
    {
        statement.executeUpdate("""
            INSERT INTO p25_site_channel_tag_summary
                (guid, channel_key, tag, first_seen_ms, last_seen_ms, observation_count)
            SELECT guid, channel_key, '%s', first_seen_ms, last_seen_ms, %s
            FROM p25_site_channel_summary
            WHERE %s > 0
            """.formatted(tag, counter, counter));
    }

    private static void copyDetailedDataGrants(Statement statement) throws SQLException
    {
        String dataEventCodes = DecodeEventType.DATA_CALLS.stream()
            .map(event -> Integer.toString(event.ordinal() + 1)).collect(Collectors.joining(","));
        String groupedDataGrants = """
            SELECT context.guid AS guid,
                event.lcn_band || '-' || event.lcn_number AS channel_key,
                event.lcn_band || '-' || event.lcn_number AS descriptor,
                max(event.frequency_hz) AS downlink_hz,
                min(event.observed_at_ms) AS first_seen_ms,
                max(event.observed_at_ms) AS last_seen_ms,
                count(*) AS observation_count
            FROM p25_activity_event event
            JOIN receiver_context context ON context.id = event.context_id
            WHERE context.guid IS NOT NULL AND event.action_code = 12
                AND event.event_type_code IN (%s)
                AND event.lcn_band IS NOT NULL AND event.lcn_number IS NOT NULL
                AND event.frequency_hz IS NOT NULL AND event.frequency_hz > 0
            GROUP BY context.guid, event.lcn_band, event.lcn_number
            """.formatted(dataEventCodes);
        statement.executeUpdate("""
            INSERT INTO p25_site_channel_summary_v16
                (guid, channel_key, descriptor, downlink_hz, tdma, timeslots,
                 first_seen_ms, last_seen_ms, observation_count)
            SELECT guid, channel_key, descriptor, downlink_hz, 0, 1,
                first_seen_ms, last_seen_ms, observation_count
            FROM (%s)
            WHERE true
            ON CONFLICT(guid, channel_key) DO UPDATE SET
                downlink_hz = coalesce(excluded.downlink_hz, p25_site_channel_summary_v16.downlink_hz),
                first_seen_ms = min(p25_site_channel_summary_v16.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(p25_site_channel_summary_v16.last_seen_ms, excluded.last_seen_ms),
                observation_count = p25_site_channel_summary_v16.observation_count + excluded.observation_count
            """.formatted(groupedDataGrants));
        statement.executeUpdate("""
            INSERT INTO p25_site_channel_tag_summary
                (guid, channel_key, tag, first_seen_ms, last_seen_ms, observation_count)
            SELECT guid, channel_key, 'DATA', first_seen_ms, last_seen_ms, observation_count
            FROM (%s)
            WHERE true
            ON CONFLICT(guid, channel_key, tag) DO UPDATE SET
                first_seen_ms = min(p25_site_channel_tag_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(p25_site_channel_tag_summary.last_seen_ms, excluded.last_seen_ms),
                observation_count = p25_site_channel_tag_summary.observation_count + excluded.observation_count
            """.formatted(groupedDataGrants));
    }

    private static void replaceChannelTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("DROP TABLE p25_site_channel");
        statement.executeUpdate("DROP TABLE p25_site_channel_summary");
        statement.executeUpdate("ALTER TABLE p25_site_channel_v16 RENAME TO p25_site_channel");
        statement.executeUpdate("ALTER TABLE p25_site_channel_summary_v16 RENAME TO p25_site_channel_summary");
        statement.executeUpdate("""
            CREATE INDEX idx_p25_site_channel_guid_frequency
            ON p25_site_channel(guid, downlink_hz)
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_p25_site_channel_summary_guid_frequency
            ON p25_site_channel_summary(guid, downlink_hz, last_seen_ms DESC)
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_p25_site_channel_tag_summary_guid_tag
            ON p25_site_channel_tag_summary(guid, tag, last_seen_ms DESC)
            """);
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);

        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }

        return connection;
    }

    private static String schemaVersion(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT value FROM database_metadata WHERE key = ?"))
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
