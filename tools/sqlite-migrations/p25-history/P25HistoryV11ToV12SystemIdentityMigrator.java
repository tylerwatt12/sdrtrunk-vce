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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * External one-off migration from the site-scoped P25 activity schema v11 to the system/site split in v12.
 */
public class P25HistoryV11ToV12SystemIdentityMigrator
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String VERSION_KEY = "p25_activity_schema_version";

    private static final String[] REPLACED_INDEXES = {
        "idx_receiver_context_guid",
        "idx_p25_talkgroup_bucket_time",
        "idx_p25_talkgroup_bucket_talkgroup_time",
        "idx_p25_radio_bucket_time",
        "idx_p25_frequency_bucket_time",
        "idx_p25_site_snapshot_identity"
    };

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" " +
                "P25HistoryV11ToV12SystemIdentityMigrator.java <database>");
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

            if("12".equals(version) && tableExists(connection, "p25_system"))
            {
                P25ActivityLogSchema.validate(connection);
                System.out.println("Database is already at P25 activity schema v12.");
                return;
            }

            if(!"11".equals(version))
            {
                throw new SQLException("Expected p25_activity_schema_version 11, found [" + version +
                    "]. Refusing migration.");
            }

            String integrity = scalar(connection, "PRAGMA integrity_check");

            if(!"ok".equals(integrity))
            {
                throw new SQLException("Integrity check failed before migration: " + integrity);
            }

            checkpoint(connection);
        }

        Path backup = database.resolveSibling(database.getFileName() + ".backup-p25-v11-to-v12-" +
            TIMESTAMP.format(LocalDateTime.now()));
        Files.copy(database, backup, StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("Backup created: " + backup);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.execute("BEGIN IMMEDIATE");

            try
            {
                prepareV11Tables(statement);
                P25ActivityLogSchema.create(connection);
                populateSystems(statement);
                populateContextSystemMap(statement);
                copyReceiverContexts(statement);
                copySiteSnapshots(statement);
                aggregateTalkgroups(connection);
                aggregateRadios(connection);
                seedRadioTalkgroupRelationships(connection);
                dropV11Tables(statement);
                P25ActivityLogSchema.validate(connection);
                statement.execute("COMMIT");
            }
            catch(SQLException e)
            {
                statement.execute("ROLLBACK");
                throw e;
            }
            finally
            {
                statement.execute("PRAGMA foreign_keys=ON");
            }

            if(!"12".equals(schemaVersion(connection)))
            {
                throw new SQLException("P25 activity schema v12 validation failed");
            }

            statement.execute("PRAGMA optimize");
            checkpoint(connection);
        }

        System.out.println("P25 activity schema migration complete: v11 -> v12 system-owned radios and talkgroups.");
    }

    private static void prepareV11Tables(Statement statement) throws SQLException
    {
        statement.execute("DROP VIEW IF EXISTS p25_activity_event_resolved");

        for(String index: REPLACED_INDEXES)
        {
            statement.execute("DROP INDEX IF EXISTS " + index);
        }

        rename(statement, "receiver_context", "receiver_context_v11");
        rename(statement, "p25_talkgroup_summary", "p25_talkgroup_summary_v11");
        rename(statement, "p25_radio_summary", "p25_radio_summary_v11");
        rename(statement, "p25_site_snapshot", "p25_site_snapshot_v11");
        rename(statement, "p25_frequency_summary", "p25_site_frequency_summary");
        rename(statement, "p25_talkgroup_activity_bucket", "p25_site_talkgroup_bucket");
        rename(statement, "p25_radio_activity_bucket", "p25_site_radio_bucket");
        rename(statement, "p25_frequency_activity_bucket", "p25_site_frequency_bucket");
    }

    private static void rename(Statement statement, String source, String destination) throws SQLException
    {
        statement.execute("ALTER TABLE " + source + " RENAME TO " + destination);
    }

    private static void populateSystems(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            INSERT INTO p25_system (wacn, system_id, first_seen_ms, last_seen_ms)
            SELECT wacn, system_id, min(first_seen_ms), max(last_seen_ms)
            FROM (
                SELECT wacn, system_id, first_seen_ms, last_seen_ms
                FROM receiver_context_v11
                WHERE wacn IS NOT NULL AND system_id IS NOT NULL
                UNION ALL
                SELECT wacn, system_id, first_seen_ms, last_seen_ms
                FROM p25_site_snapshot_v11
                WHERE wacn IS NOT NULL AND system_id IS NOT NULL
            )
            GROUP BY wacn, system_id
            """);
    }

    private static void populateContextSystemMap(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TEMP TABLE v11_context_system_map (
                context_id INTEGER PRIMARY KEY,
                system_key INTEGER
            )
            """);
        statement.executeUpdate("""
            INSERT INTO v11_context_system_map (context_id, system_key)
            SELECT old.id, system.system_key
            FROM receiver_context_v11 old
            LEFT JOIN p25_site_snapshot_v11 site ON site.guid = old.guid
            LEFT JOIN p25_system system
              ON system.wacn = coalesce(old.wacn, site.wacn)
             AND system.system_id = coalesce(old.system_id, site.system_id)
            """);
    }

    private static void copyReceiverContexts(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            INSERT INTO receiver_context (
                id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name, decoder,
                first_seen_ms, last_seen_ms, system_key, nac, rfss, site, primary_frequency_hz,
                current_control_hz
            )
            SELECT
                old.id, old.context_key, old.guid, old.kind_code, old.protocol_code, old.channel_name,
                old.alias_list_name, old.decoder, old.first_seen_ms, old.last_seen_ms, map.system_key,
                old.nac, old.rfss, old.site, old.primary_frequency_hz, old.current_control_hz
            FROM receiver_context_v11 old
            LEFT JOIN v11_context_system_map map ON map.context_id = old.id
            """);
    }

    private static void copySiteSnapshots(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            INSERT INTO p25_site_snapshot (
                guid, snapshot_hash, first_seen_ms, last_seen_ms, seen_count, protocol, channel_name,
                alias_list_name, decoder, system_key, nac, rfss, site, primary_frequency_hz,
                current_control_hz
            )
            SELECT
                old.guid, old.snapshot_hash, old.first_seen_ms, old.last_seen_ms, old.seen_count,
                old.protocol, old.channel_name, old.alias_list_name, old.decoder, system.system_key,
                old.nac, old.rfss, old.site, old.primary_frequency_hz, old.current_control_hz
            FROM p25_site_snapshot_v11 old
            LEFT JOIN p25_system system
              ON system.wacn = old.wacn AND system.system_id = old.system_id
            """);
    }

    private static void aggregateTalkgroups(Connection connection) throws SQLException
    {
        List<String> actions = actionColumns(connection, "p25_talkgroup_summary_v11");
        String actionNames = String.join(", ", actions);
        String actionSums = actions.stream().map(column -> "sum(old." + column + ")")
            .collect(Collectors.joining(", "));
        String optionalColumns = actions.isEmpty() ? "" : ", " + actionNames;
        String optionalValues = actions.isEmpty() ? "" : ", " + actionSums;

        executeUpdate(connection, """
            INSERT INTO p25_talkgroup_summary (
                system_key, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, hits%s,
                encrypted_count
            )
            SELECT
                map.system_key, old.talkgroup_id, max(old.target_kind_code), min(old.first_seen_ms),
                max(old.last_seen_ms), sum(old.hits)%s, sum(old.encrypted_count)
            FROM p25_talkgroup_summary_v11 old
            JOIN v11_context_system_map map ON map.context_id = old.context_id
            WHERE map.system_key IS NOT NULL
            GROUP BY map.system_key, old.talkgroup_id
            """.formatted(optionalColumns, optionalValues));

        copyLatestTalkgroupValue(connection, "last_source_radio_id");
        copyLatestTalkgroupValue(connection, "last_encryption_algorithm_id");
        copyLatestTalkgroupValue(connection, "last_encryption_key_id");
    }

    private static void copyLatestTalkgroupValue(Connection connection, String column) throws SQLException
    {
        executeUpdate(connection, """
            UPDATE p25_talkgroup_summary AS destination
            SET %1$s = (
                SELECT old.%1$s
                FROM p25_talkgroup_summary_v11 old
                JOIN v11_context_system_map map ON map.context_id = old.context_id
                WHERE map.system_key = destination.system_key
                  AND old.talkgroup_id = destination.talkgroup_id
                  AND old.%1$s IS NOT NULL
                ORDER BY old.last_seen_ms DESC, old.context_id DESC
                LIMIT 1
            )
            """.formatted(column));
    }

    private static void aggregateRadios(Connection connection) throws SQLException
    {
        List<String> actions = actionColumns(connection, "p25_radio_summary_v11");
        String actionNames = String.join(", ", actions);
        String actionSums = actions.stream().map(column -> "sum(old." + column + ")")
            .collect(Collectors.joining(", "));
        String optionalColumns = actions.isEmpty() ? "" : ", " + actionNames;
        String optionalValues = actions.isEmpty() ? "" : ", " + actionSums;

        executeUpdate(connection, """
            INSERT INTO p25_radio_summary (
                system_key, radio_id, first_seen_ms, last_seen_ms, hits%s, encrypted_count
            )
            SELECT
                map.system_key, old.radio_id, min(old.first_seen_ms), max(old.last_seen_ms),
                sum(old.hits)%s, sum(old.encrypted_count)
            FROM p25_radio_summary_v11 old
            JOIN v11_context_system_map map ON map.context_id = old.context_id
            WHERE map.system_key IS NOT NULL
            GROUP BY map.system_key, old.radio_id
            """.formatted(optionalColumns, optionalValues));

        copyLatestRadioValue(connection, "last_talkgroup_id");
        copyLatestRadioValue(connection, "last_encryption_algorithm_id");
        copyLatestRadioValue(connection, "last_encryption_key_id");
        executeUpdate(connection, """
            UPDATE p25_radio_summary AS destination
            SET
                last_talker_alias = (
                    SELECT old.last_talker_alias
                    FROM p25_radio_summary_v11 old
                    JOIN v11_context_system_map map ON map.context_id = old.context_id
                    WHERE map.system_key = destination.system_key
                      AND old.radio_id = destination.radio_id
                      AND old.last_talker_alias IS NOT NULL
                      AND trim(old.last_talker_alias) <> ''
                    ORDER BY old.last_seen_ms DESC, old.context_id DESC
                    LIMIT 1
                ),
                last_talker_alias_seen_ms = (
                    SELECT old.last_seen_ms
                    FROM p25_radio_summary_v11 old
                    JOIN v11_context_system_map map ON map.context_id = old.context_id
                    WHERE map.system_key = destination.system_key
                      AND old.radio_id = destination.radio_id
                      AND old.last_talker_alias IS NOT NULL
                      AND trim(old.last_talker_alias) <> ''
                    ORDER BY old.last_seen_ms DESC, old.context_id DESC
                    LIMIT 1
                )
            """);
    }

    private static void copyLatestRadioValue(Connection connection, String column) throws SQLException
    {
        executeUpdate(connection, """
            UPDATE p25_radio_summary AS destination
            SET %1$s = (
                SELECT old.%1$s
                FROM p25_radio_summary_v11 old
                JOIN v11_context_system_map map ON map.context_id = old.context_id
                WHERE map.system_key = destination.system_key
                  AND old.radio_id = destination.radio_id
                  AND old.%1$s IS NOT NULL
                ORDER BY old.last_seen_ms DESC, old.context_id DESC
                LIMIT 1
            )
            """.formatted(column));
    }

    private static void seedRadioTalkgroupRelationships(Connection connection) throws SQLException
    {
        List<String> actions = actionColumns(connection, "p25_radio_talkgroup_summary");
        List<String> actionExpressions = new ArrayList<>();

        for(int x = 0; x < actions.size(); x++)
        {
            actionExpressions.add("sum(CASE WHEN event.action_code = " + (x + 1) + " THEN 1 ELSE 0 END)");
        }

        String optionalColumns = actions.isEmpty() ? "" : ", " + String.join(", ", actions);
        String optionalValues = actions.isEmpty() ? "" : ", " + String.join(", ", actionExpressions);

        executeUpdate(connection, """
            INSERT INTO p25_radio_talkgroup_summary (
                system_key, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, hits%s,
                encrypted_count
            )
            SELECT
                map.system_key, event.source_radio_id, event.target_id, max(event.target_kind_code),
                min(event.observed_at_ms), max(event.observed_at_ms), count(*)%s, sum(event.encrypted)
            FROM p25_activity_event event
            JOIN v11_context_system_map map ON map.context_id = event.context_id
            WHERE map.system_key IS NOT NULL
              AND event.source_radio_id IS NOT NULL
              AND event.target_id IS NOT NULL
              AND event.target_kind_code IN (1, 3)
            GROUP BY map.system_key, event.source_radio_id, event.target_id
            """.formatted(optionalColumns, optionalValues));
    }

    private static List<String> actionColumns(Connection connection, String table) throws SQLException
    {
        List<String> columns = new ArrayList<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                String name = resultSet.getString("name");

                if(name.endsWith("_count") && !"encrypted_count".equals(name))
                {
                    columns.add(name);
                }
            }
        }

        return columns;
    }

    private static void dropV11Tables(Statement statement) throws SQLException
    {
        statement.execute("DROP TABLE receiver_context_v11");
        statement.execute("DROP TABLE p25_talkgroup_summary_v11");
        statement.execute("DROP TABLE p25_radio_summary_v11");
        statement.execute("DROP TABLE p25_site_snapshot_v11");
        statement.execute("DROP TABLE IF EXISTS p25_site_talker_alias");
        statement.execute("DROP TABLE v11_context_system_map");
    }

    private static void executeUpdate(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);

        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }

        return connection;
    }

    private static String schemaVersion(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT value FROM database_metadata WHERE key = ?
            """))
        {
            statement.setString(1, VERSION_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static boolean tableExists(Connection connection, String name) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
            """))
        {
            statement.setString(1, name);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
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
