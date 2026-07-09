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
 * External one-off migration for P25 activity history schema v8 to v9.
 *
 * This source file is intentionally outside the SDRTrunk application source tree. Run it with Java source-file mode
 * and the SDRTrunk distribution lib directory on the classpath.
 */
public class P25HistoryV8ToV9Migrator
{
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static void main(String[] args) throws Exception
    {
        if(args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0]))
        {
            System.out.println("Usage: java -cp \"<sdrtrunk-app>/lib/*\" P25HistoryV8ToV9Migrator.java <database>");
            return;
        }

        Class.forName("org.sqlite.JDBC");
        Path database = Path.of(args[0]).toAbsolutePath().normalize();
        migrate(database);
    }

    private static void migrate(Path database) throws IOException, SQLException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("Database not found: " + database);
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            String schemaVersion = schemaVersion(connection);
            int nacColumns = countNacColumns(connection);

            if("9".equals(schemaVersion) && nacColumns == 0)
            {
                System.out.println("Database is already at P25 activity schema v9 with no site_neighbor.nac column.");
                return;
            }

            if(!"8".equals(schemaVersion))
            {
                throw new SQLException("Expected p25_activity_schema_version 8, found [" + schemaVersion +
                    "]. Refusing migration.");
            }

            if(nacColumns != 1)
            {
                throw new SQLException("Expected exactly one site_neighbor.nac column, found [" + nacColumns +
                    "]. Refusing migration.");
            }

            String integrity = scalar(connection, "PRAGMA integrity_check");

            if(!"ok".equals(integrity))
            {
                throw new SQLException("Integrity check failed before migration: " + integrity);
            }

            checkpoint(connection);
        }

        Path backup = backup(database);
        System.out.println("Backup created: " + backup);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            runMigration(connection);
            verify(connection);
        }

        System.out.println("Migration complete: P25 activity schema v9, site_neighbor.nac removed.");
    }

    private static Path backup(Path database) throws IOException
    {
        Path backup = database.resolveSibling(database.getFileName() + ".backup-v8-to-v9-" +
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
                statement.execute("DROP TABLE IF EXISTS site_neighbor_v9");
                statement.execute("""
                    CREATE TABLE site_neighbor_v9 (
                        guid TEXT NOT NULL,
                        neighbor_key TEXT NOT NULL,
                        system_id INTEGER,
                        rfss INTEGER,
                        site INTEGER,
                        lra INTEGER,
                        channel_descriptor TEXT,
                        downlink_hz INTEGER,
                        uplink_hz INTEGER,
                        status TEXT,
                        first_seen_ms INTEGER NOT NULL,
                        last_seen_ms INTEGER NOT NULL,
                        seen_count INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY(guid, neighbor_key)
                    )
                    """);
                statement.execute("""
                    INSERT INTO site_neighbor_v9 (
                        guid, neighbor_key, system_id, rfss, site, lra, channel_descriptor, downlink_hz,
                        uplink_hz, status, first_seen_ms, last_seen_ms, seen_count
                    )
                    SELECT
                        guid, neighbor_key, system_id, rfss, site, lra, channel_descriptor, downlink_hz,
                        uplink_hz, status, first_seen_ms, last_seen_ms, seen_count
                    FROM site_neighbor
                    """);
                statement.execute("DROP TABLE site_neighbor");
                statement.execute("ALTER TABLE site_neighbor_v9 RENAME TO site_neighbor");
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_site_neighbor_guid_site
                    ON site_neighbor(guid, system_id, rfss, site)
                    """);
                statement.executeUpdate("""
                    INSERT INTO database_metadata (key, value, updated_at_ms)
                    VALUES ('p25_activity_schema_version', '9', CAST(strftime('%s', 'now') AS INTEGER) * 1000)
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

    private static void verify(Connection connection) throws SQLException
    {
        String schemaVersion = schemaVersion(connection);
        int nacColumns = countNacColumns(connection);
        String integrity = scalar(connection, "PRAGMA integrity_check");

        if(!"9".equals(schemaVersion) || nacColumns != 0 || !"ok".equals(integrity))
        {
            throw new SQLException("Migration verification failed: schema=" + schemaVersion +
                " site_neighbor.nac_columns=" + nacColumns + " integrity=" + integrity);
        }
    }

    private static void checkpoint(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
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

    private static int countNacColumns(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT COUNT(*) FROM pragma_table_info('site_neighbor') WHERE name = 'nac'
                """))
        {
            return resultSet.next() ? resultSet.getInt(1) : 0;
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
