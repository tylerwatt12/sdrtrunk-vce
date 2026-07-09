import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * External one-off migration for P25 activity history schema v9 to compact v10.
 *
 * This source file is intentionally outside the SDRTrunk application source tree. Run it with Java source-file mode
 * and the SDRTrunk distribution lib directory on the classpath.
 */
public class P25HistoryV9ToV10CompactMigrator
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int CONTEXT_TRUNKED_SITE = 1;
    private static final int CONTEXT_CONVENTIONAL_P25 = 2;
    private static final int PROTOCOL_APCO25 = 1;
    private static final int TARGET_TALKGROUP = 1;
    private static final int TARGET_RADIO = 2;
    private static final int TARGET_PATCH_GROUP = 3;

    private static final String[] ACTIONS = {
        "ACKNOWLEDGE", "ACTIVE", "BUSY", "CALL", "CHECK", "CHECK_ACK", "CONTINUE", "DATA", "DENIAL",
        "EMERGENCY", "GPS", "GRANT", "JOIN", "LOGOUT", "PAGE", "PATCH", "PATCH_CANCEL", "PATCH_CREATE",
        "QUEUED", "REGISTER", "REQUEST", "STATUS", "UNKNOWN"
    };

    private static final String[] EVENT_TYPES = {
        "AFFILIATE", "ANNOUNCEMENT", "ACKNOWLEDGE", "AUTOMATIC_REGISTRATION_SERVICE", "CALL",
        "CALL_ENCRYPTED", "CALL_GROUP", "CALL_GROUP_ENCRYPTED", "CALL_PATCH_GROUP",
        "CALL_PATCH_GROUP_ENCRYPTED", "CALL_ALERT", "CALL_DETECT", "CALL_IN_PROGRESS",
        "CALL_DO_NOT_MONITOR", "CALL_END", "CALL_INTERCONNECT", "CALL_INTERCONNECT_ENCRYPTED",
        "CALL_UNIQUE_ID", "CALL_UNIT_TO_UNIT", "CALL_UNIT_TO_UNIT_ENCRYPTED", "CALL_NO_TUNER",
        "CALL_TIMEOUT", "CELLOCATOR", "COMMAND", "DATA_CALL", "DATA_CALL_ENCRYPTED", "DATA_PACKET",
        "DEREGISTER", "DYNAMIC_REGROUP", "EMERGENCY", "FUNCTION", "GPS", "ICMP_PACKET", "ID_ANI",
        "ID_UNIQUE", "IP_PACKET", "LRRP", "NOTIFICATION", "PAGE", "QUERY", "RADIO_CHECK",
        "RADIO_REGISTRATION_SERVICE", "REGISTER", "REGISTER_ESN", "REQUEST", "RESPONSE", "RESPONSE_PACKET",
        "SDM", "SMS", "STATION_ID", "STATUS", "TEXT_MESSAGE", "UDP_PACKET", "UNKNOWN_PACKET", "XCMP",
        "UNKNOWN"
    };

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" P25HistoryV9ToV10CompactMigrator.java <database>");
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

        long activityRows;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            configure(connection);
            String schemaVersion = schemaVersion(connection);

            if("10".equals(schemaVersion) && hasColumn(connection, "activity_event", "context_id"))
            {
                System.out.println("Database is already at compact P25 activity schema v10.");
                return;
            }

            if(!"9".equals(schemaVersion))
            {
                throw new SQLException("Expected p25_activity_schema_version 9, found [" + schemaVersion +
                    "]. Refusing migration.");
            }

            requireColumn(connection, "radio_context", "guid");
            requireColumn(connection, "activity_event", "guid");
            requireColumn(connection, "activity_event", "action");
            requireColumn(connection, "activity_event", "observed_at_ms");

            String integrity = scalar(connection, "PRAGMA integrity_check");

            if(!"ok".equals(integrity))
            {
                throw new SQLException("Integrity check failed before migration: " + integrity);
            }

            activityRows = countRows(connection, "activity_event");
            checkpoint(connection);
        }

        Path backup = backup(database);
        System.out.println("Backup created: " + backup);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            configure(connection);
            runMigration(connection);
            verify(connection, activityRows);
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            configure(connection);
            vacuum(connection);
            checkpoint(connection);
            optimize(connection);
        }

        System.out.println("Migration complete: P25 activity schema v10 compact event storage.");
    }

    private static Path backup(Path database) throws IOException
    {
        Path backup = database.resolveSibling(database.getFileName() + ".backup-v9-to-v10-" +
            TIMESTAMP.format(LocalDateTime.now()));
        Files.copy(database, backup);
        return backup;
    }

    private static void runMigration(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("BEGIN IMMEDIATE");

            try
            {
                dropOldActivityObjects(statement);
                statement.execute("DROP TABLE IF EXISTS activity_event_v10");
                statement.execute("DROP TABLE IF EXISTS radio_context_v10");
                createRadioContext(statement, "radio_context_v10");
                createActivityEvent(statement, "activity_event_v10");
                copyRadioContexts(statement);
                copyActivityEvents(statement);
                statement.execute("DROP TABLE activity_event");
                statement.execute("ALTER TABLE activity_event_v10 RENAME TO activity_event");
                statement.execute("DROP TABLE radio_context");
                statement.execute("ALTER TABLE radio_context_v10 RENAME TO radio_context");
                createIndexesAndView(statement);
                statement.executeUpdate("""
                    INSERT INTO database_metadata (key, value, updated_at_ms)
                    VALUES ('p25_activity_schema_version', '10', CAST(strftime('%s', 'now') AS INTEGER) * 1000)
                    ON CONFLICT(key) DO UPDATE SET
                        value = excluded.value,
                        updated_at_ms = excluded.updated_at_ms
                    """);
                statement.execute("COMMIT");
            }
            catch(SQLException e)
            {
                rollback(statement);
                throw e;
            }
            finally
            {
                statement.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    private static void dropOldActivityObjects(Statement statement) throws SQLException
    {
        statement.execute("DROP VIEW IF EXISTS activity_event_resolved");
        statement.execute("DROP INDEX IF EXISTS idx_activity_event_guid_time");
        statement.execute("DROP INDEX IF EXISTS idx_activity_event_context_time");
        statement.execute("DROP INDEX IF EXISTS idx_activity_event_target_time");
        statement.execute("DROP INDEX IF EXISTS idx_activity_event_source_time");
        statement.execute("DROP INDEX IF EXISTS idx_activity_event_frequency_time");
        statement.execute("DROP INDEX IF EXISTS idx_activity_event_encryption");
    }

    private static void createRadioContext(Statement statement, String table) throws SQLException
    {
        statement.execute("""
            CREATE TABLE %s (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                guid TEXT NOT NULL UNIQUE,
                kind_code INTEGER NOT NULL,
                protocol_code INTEGER,
                channel_name TEXT,
                alias_list_name TEXT,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                wacn INTEGER,
                system_id INTEGER,
                nac INTEGER,
                rfss INTEGER,
                site INTEGER,
                primary_frequency_hz INTEGER,
                current_control_hz INTEGER
            )
            """.formatted(table));
    }

    private static void createActivityEvent(Statement statement, String table) throws SQLException
    {
        statement.execute("""
            CREATE TABLE %s (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                context_id INTEGER NOT NULL,
                observed_at_ms INTEGER NOT NULL,
                action_code INTEGER NOT NULL,
                event_type_code INTEGER,
                source_radio_id INTEGER,
                target_id INTEGER,
                target_kind_code INTEGER,
                frequency_hz INTEGER,
                lcn_band INTEGER,
                lcn_number INTEGER,
                timeslot INTEGER,
                encrypted INTEGER NOT NULL DEFAULT 0,
                encryption_algorithm_id INTEGER,
                encryption_key_id INTEGER
            )
            """.formatted(table));
    }

    private static void copyRadioContexts(Statement statement) throws SQLException
    {
        statement.execute("""
            INSERT INTO radio_context_v10 (
                guid, kind_code, protocol_code, channel_name, alias_list_name, first_seen_ms, last_seen_ms,
                wacn, system_id, nac, rfss, site, primary_frequency_hz, current_control_hz
            )
            SELECT
                guid,
                CASE kind WHEN 'TRUNKED_SITE' THEN 1 WHEN 'CONVENTIONAL_P25' THEN 2 ELSE 1 END,
                CASE protocol WHEN 'APCO25' THEN 1 ELSE NULL END,
                channel_name,
                alias_list_name,
                first_seen_ms,
                last_seen_ms,
                wacn,
                system_id,
                nac,
                rfss,
                site,
                primary_frequency_hz,
                current_control_hz
            FROM radio_context
            """);
        statement.execute("""
            INSERT INTO radio_context_v10 (
                guid, kind_code, protocol_code, first_seen_ms, last_seen_ms
            )
            SELECT
                a.guid,
                CASE
                    WHEN max(CASE a.channel_kind WHEN 'TRUNKED_SITE' THEN 1 ELSE 0 END) = 1 THEN 1
                    WHEN max(CASE a.channel_kind WHEN 'CONVENTIONAL_P25' THEN 1 ELSE 0 END) = 1 THEN 2
                    ELSE 1
                END,
                CASE WHEN max(CASE a.protocol WHEN 'APCO25' THEN 1 ELSE 0 END) = 1 THEN 1 ELSE NULL END,
                min(a.observed_at_ms),
                max(a.observed_at_ms)
            FROM activity_event a
            LEFT JOIN radio_context_v10 rc ON rc.guid = a.guid
            WHERE rc.guid IS NULL
            GROUP BY a.guid
            """);
    }

    private static void copyActivityEvents(Statement statement) throws SQLException
    {
        statement.execute("""
            INSERT INTO activity_event_v10 (
                id, context_id, observed_at_ms, action_code, event_type_code, source_radio_id, target_id,
                target_kind_code, frequency_hz, lcn_band, lcn_number, timeslot, encrypted,
                encryption_algorithm_id, encryption_key_id
            )
            SELECT
                a.id,
                rc.id,
                a.observed_at_ms,
                %s,
                %s,
                %s,
                %s,
                %s,
                a.frequency_hz,
                %s,
                %s,
                a.timeslot,
                a.encrypted,
                a.encryption_algorithm_id,
                a.encryption_key_id
            FROM activity_event a
            JOIN radio_context_v10 rc ON rc.guid = a.guid
            """.formatted(caseExpression("a.action", ACTIONS, true), caseExpression("a.event_type", EVENT_TYPES, false),
            numericIdentifierExpression("a.source_radio_id"), numericIdentifierExpression("a.target_id"),
            targetKindExpression("a.target_kind"), lcnBandExpression("a.lcn"), lcnNumberExpression("a.lcn")));
    }

    private static void createIndexesAndView(Statement statement) throws SQLException
    {
        statement.execute("CREATE INDEX IF NOT EXISTS idx_activity_event_context_time ON activity_event(context_id, observed_at_ms)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_activity_event_target_time ON activity_event(target_id, observed_at_ms) WHERE target_id IS NOT NULL");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_activity_event_source_time ON activity_event(source_radio_id, observed_at_ms) WHERE source_radio_id IS NOT NULL");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_activity_event_frequency_time ON activity_event(frequency_hz, observed_at_ms) WHERE frequency_hz IS NOT NULL");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_activity_event_encryption ON activity_event(encryption_algorithm_id, encryption_key_id, observed_at_ms) WHERE encrypted = 1");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_site_snapshot_identity ON site_snapshot(wacn, system_id, rfss, site)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_site_channel_guid_frequency ON site_channel(guid, downlink_hz)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_site_neighbor_guid_site ON site_neighbor(guid, system_id, rfss, site)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_site_patch_talkgroup ON site_patch_group_talkgroup(talkgroup_id, guid)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_site_patch_radio ON site_patch_group_radio(radio_id, guid)");
        statement.execute(createResolvedViewSql());
    }

    private static String caseExpression(String expression, String[] values, boolean unknownFallback)
    {
        StringBuilder sb = new StringBuilder("CASE ").append(expression);

        for(int index = 0; index < values.length; index++)
        {
            sb.append(" WHEN '").append(values[index]).append("' THEN ").append(index + 1);
        }

        if(unknownFallback)
        {
            sb.append(" ELSE ").append(values.length);
        }
        else
        {
            sb.append(" ELSE NULL");
        }

        return sb.append(" END").toString();
    }

    private static String numericIdentifierExpression(String expression)
    {
        return """
            CASE
                WHEN %1$s IS NULL OR trim(%1$s) = '' THEN NULL
                WHEN substr(trim(%1$s), 1, 2) = 'P:' AND substr(trim(%1$s), 3, 1) BETWEEN '0' AND '9'
                    THEN CAST(substr(trim(%1$s), 3) AS INTEGER)
                WHEN substr(trim(%1$s), 1, 1) BETWEEN '0' AND '9'
                    THEN CAST(trim(%1$s) AS INTEGER)
                ELSE NULL
            END
            """.formatted(expression);
    }

    private static String targetKindExpression(String expression)
    {
        return """
            CASE %s
                WHEN 'TALKGROUP' THEN 1
                WHEN 'RADIO' THEN 2
                WHEN 'PATCH_GROUP' THEN 3
                ELSE NULL
            END
            """.formatted(expression);
    }

    private static String lcnBandExpression(String expression)
    {
        return """
            CASE
                WHEN %1$s LIKE '%%-%%' AND substr(trim(%1$s), 1, 1) BETWEEN '0' AND '9'
                    THEN CAST(substr(%1$s, 1, instr(%1$s, '-') - 1) AS INTEGER)
                ELSE NULL
            END
            """.formatted(expression);
    }

    private static String lcnNumberExpression(String expression)
    {
        return """
            CASE
                WHEN %1$s LIKE '%%-%%' AND substr(%1$s, instr(%1$s, '-') + 1, 1) BETWEEN '0' AND '9'
                    THEN CAST(substr(%1$s, instr(%1$s, '-') + 1) AS INTEGER)
                ELSE NULL
            END
            """.formatted(expression);
    }

    private static String createResolvedViewSql()
    {
        return """
            CREATE VIEW IF NOT EXISTS activity_event_resolved AS
            SELECT
                a.id,
                rc.guid,
                %s AS channel_kind,
                a.observed_at_ms,
                %s AS protocol,
                %s AS action,
                %s AS event_type,
                a.source_radio_id,
                a.target_id,
                %s AS target_kind,
                a.frequency_hz,
                CASE
                    WHEN a.lcn_band IS NOT NULL AND a.lcn_number IS NOT NULL
                    THEN a.lcn_band || '-' || a.lcn_number
                    ELSE NULL
                END AS lcn,
                a.timeslot,
                a.encrypted,
                a.encryption_algorithm_id,
                a.encryption_key_id,
                a.context_id,
                rc.kind_code AS channel_kind_code,
                rc.protocol_code,
                a.action_code,
                a.event_type_code,
                a.target_kind_code,
                rc.channel_name AS resolved_channel_name,
                rc.alias_list_name AS resolved_alias_list_name,
                rc.wacn AS resolved_wacn,
                rc.system_id AS resolved_system_id,
                rc.nac AS resolved_nac,
                rc.rfss AS resolved_rfss,
                rc.site AS resolved_site,
                rc.current_control_hz AS resolved_current_control_hz
            FROM activity_event a
            LEFT JOIN radio_context rc ON rc.id = a.context_id
            """.formatted(contextKindCase("rc.kind_code"), protocolCase("rc.protocol_code"),
            codeCase("a.action_code", ACTIONS, "UNKNOWN"), codeCase("a.event_type_code", EVENT_TYPES, null),
            targetKindCase("a.target_kind_code"));
    }

    private static String contextKindCase(String expression)
    {
        return "CASE " + expression + " WHEN " + CONTEXT_TRUNKED_SITE + " THEN 'TRUNKED_SITE' WHEN " +
            CONTEXT_CONVENTIONAL_P25 + " THEN 'CONVENTIONAL_P25' ELSE NULL END";
    }

    private static String protocolCase(String expression)
    {
        return "CASE " + expression + " WHEN " + PROTOCOL_APCO25 + " THEN 'APCO25' ELSE NULL END";
    }

    private static String targetKindCase(String expression)
    {
        return "CASE " + expression + " WHEN " + TARGET_TALKGROUP + " THEN 'TALKGROUP' WHEN " +
            TARGET_RADIO + " THEN 'RADIO' WHEN " + TARGET_PATCH_GROUP + " THEN 'PATCH_GROUP' ELSE NULL END";
    }

    private static String codeCase(String expression, String[] values, String fallback)
    {
        StringBuilder sb = new StringBuilder("CASE ").append(expression);

        for(int index = 0; index < values.length; index++)
        {
            sb.append(" WHEN ").append(index + 1).append(" THEN '").append(values[index]).append("'");
        }

        return sb.append(fallback != null ? " ELSE '" + fallback + "' END" : " ELSE NULL END").toString();
    }

    private static void verify(Connection connection, long expectedActivityRows) throws SQLException
    {
        String schemaVersion = schemaVersion(connection);
        String integrity = scalar(connection, "PRAGMA integrity_check");
        long migratedRows = countRows(connection, "activity_event");

        if(!"10".equals(schemaVersion) || !hasColumn(connection, "activity_event", "context_id") ||
            hasColumn(connection, "activity_event", "guid") || hasColumn(connection, "activity_event", "raw_identifiers") ||
            hasColumn(connection, "activity_event", "details") || migratedRows != expectedActivityRows ||
            !"ok".equals(integrity))
        {
            throw new SQLException("Migration verification failed: schema=" + schemaVersion +
                " rows=" + migratedRows + "/" + expectedActivityRows + " integrity=" + integrity);
        }
    }

    private static void configure(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
        }
    }

    private static void rollback(Statement statement)
    {
        try
        {
            statement.execute("ROLLBACK");
        }
        catch(SQLException ignored)
        {
        }
    }

    private static void checkpoint(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private static void vacuum(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("VACUUM");
        }
    }

    private static void optimize(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA optimize");
        }
    }

    private static String schemaVersion(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT value FROM database_metadata WHERE key = 'p25_activity_schema_version'
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            return resultSet.next() ? resultSet.getString(1) : "";
        }
    }

    private static void requireColumn(Connection connection, String table, String column) throws SQLException
    {
        if(!hasColumn(connection, table, column))
        {
            throw new SQLException("Missing expected column [" + table + "." + column + "]");
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM pragma_table_info(?) WHERE name = ?
            """))
        {
            statement.setString(1, table);
            statement.setString(2, column);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static long countRows(Connection connection, String table) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : "";
        }
    }
}
