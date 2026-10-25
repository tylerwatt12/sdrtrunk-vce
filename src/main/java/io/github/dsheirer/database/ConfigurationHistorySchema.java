/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import io.github.dsheirer.alerts.TopicAlertSchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Authoritative format-63 storage ownership. Frozen monolithic creators remain available to legacy fixtures. */
public final class ConfigurationHistorySchema
{
    private static final String ACTIVITY_IDENTITY_SQL = "activity_identity TEXT NOT NULL DEFAULT " +
        "(lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || " +
        "hex(randomblob(2)) || '-' || hex(randomblob(6)))) CHECK(typeof(activity_identity)='text' AND " +
        "length(activity_identity)=36 AND activity_identity=lower(activity_identity) AND " +
        "length(replace(activity_identity,'-',''))=32 AND replace(activity_identity,'-','') NOT GLOB '*[^0-9a-f]*')";
    public static final String PAIR_ID_KEY = "configuration_history_pair_id";
    public static final String ROLE_KEY = "database_role";
    public static final String CONFIGURATION_ROLE = "configuration";
    public static final String HISTORY_ROLE = "activity";
    public static final Set<String> CONFIGURATION_TABLES = Set.of("database_metadata", "alias_list", "alias",
        "alias_p25_subscriber_identity", "scan_list", "alias_scan_list_membership",
        "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_new_alias_scan_list_membership",
        "alias_broadcast_channel", "alias_list_unmatched_talkgroup_stream", "alias_list_new_alias_stream",
        "configuration_channel", "configuration_broadcast_stream", "application_settings", "application_icons",
        "web_user", "web_user_tour_dismissal", "web_access_policy", "p25_subscriber_identity",
        "topic_alert_topic", "topic_alert_rule", "topic_alert_device", "topic_alert_membership",
        "topic_alert_enrollment", "topic_alert_enrollment_topic", "topic_alert_message", "topic_alert_delivery");

    private ConfigurationHistorySchema()
    {
    }

    /** A fresh tiny configuration store contains no receiver-activity descendants. */
    public static void createConfiguration(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseSchema.create(connection);
        addAliasActivityIdentity(connection);
        removeLegacyConfigurationReset(connection);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("DROP TABLE alias_activity_summary");
            statement.execute("DROP TABLE alias_list_analog_activity_summary");
            statement.execute("DROP TABLE receiver_health_incident");
            statement.execute("""
                CREATE TABLE p25_subscriber_identity (
                    id INTEGER PRIMARY KEY CHECK(typeof(id) = 'integer' AND id > 0),
                    home_wacn INTEGER NOT NULL CHECK(typeof(home_wacn) = 'integer' AND home_wacn BETWEEN 0 AND 1048575),
                    home_system_id INTEGER NOT NULL CHECK(typeof(home_system_id) = 'integer' AND home_system_id BETWEEN 0 AND 4095),
                    subscriber_id INTEGER NOT NULL CHECK(typeof(subscriber_id) = 'integer' AND subscriber_id BETWEEN 1 AND 16777212),
                    UNIQUE(home_wacn, home_system_id, subscriber_id)
                )
                """);
        }
        TopicAlertSchema.create(connection);
    }

    /** Fresh creation uses the same receiver schema and exact owner transformation as the adjacent migration. */
    public static void createHistory(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseSchema.create(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        separateHistoryOwners(connection);
        removeConfiguration(connection);
    }

    /** Rebuilds only compact owner/counter tables; detailed events, indexes and historical allocators stay in place. */
    public static void separateHistoryOwners(Connection connection) throws SQLException
    {
        addAliasActivityIdentity(connection);
        rebuild(connection, "receiver_channel", sql -> sql.replace(
            "REFERENCES configuration_channel(configuration_id) ON DELETE CASCADE", "")
            .replace("    CHECK(radio_system_id IS NULL OR radio_system_assigned_at_ms IS NOT NULL),", """
                name TEXT,
                system_name TEXT,
                site_name TEXT,
                decoder_type TEXT,
                channel_kind TEXT,
                address_domain_code INTEGER NOT NULL DEFAULT 0 CHECK(address_domain_code IN (0,1,2)),
                alias_list_id INTEGER,
                alias_list_name TEXT NOT NULL DEFAULT '',
                primary_frequency_hz INTEGER,
                assigned_talkgroup INTEGER,
                CHECK(radio_system_id IS NULL OR radio_system_assigned_at_ms IS NOT NULL),
                """.stripIndent()), """
            UPDATE receiver_channel SET
                name=c.name, system_name=c.system_name, site_name=c.site_name, decoder_type=c.decoder_type,
                channel_kind=c.channel_kind, address_domain_code=c.address_domain_code,
                alias_list_id=c.alias_list_id, alias_list_name=coalesce(l.name,''),
                primary_frequency_hz=c.primary_frequency_hz,
                assigned_talkgroup=CASE WHEN c.decoder_type IN ('AM','NBFM')
                    THEN coalesce(json_extract(c.config_json,'$.decodeConfiguration.talkgroup'),1) END
            FROM configuration_channel c LEFT JOIN alias_list l ON l.id=c.alias_list_id
            WHERE receiver_channel.configuration_id=c.configuration_id
            """);
        rebuild(connection, "radio_system", sql -> sql.replace(
            "REFERENCES configuration_channel(configuration_id) ON DELETE CASCADE", ""), null);
        rebuild(connection, "alias_activity_summary", sql -> sql
            .replace("REFERENCES alias_list(id) ON DELETE CASCADE", "")
            .replace("    FOREIGN KEY(alias_id) REFERENCES alias(id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,", "")
            .replace("    CHECK((first_evidence_ms", "    " + ACTIVITY_IDENTITY_SQL + ",\n" +
                "    alias_name TEXT NOT NULL DEFAULT '',\n" +
                "    alias_list_name TEXT NOT NULL DEFAULT '',\n    CHECK((first_evidence_ms"), """
            UPDATE alias_activity_summary SET activity_identity=a.activity_identity, alias_name=a.name, alias_list_name=coalesce(l.name,'')
            FROM alias a LEFT JOIN alias_list l ON l.id=a.alias_list_id WHERE alias_activity_summary.alias_id=a.id
            """);
        rebuild(connection, "alias_list_analog_activity_summary", sql -> sql
            .replace("REFERENCES alias_list(id) ON DELETE CASCADE", "")
            .replace("REFERENCES configuration_channel(configuration_id)\n        ON DELETE CASCADE", "")
            .replace("REFERENCES configuration_channel(configuration_id)\n    ON DELETE CASCADE", "")
            .replaceAll("REFERENCES configuration_channel\\(configuration_id\\)\\s+ON DELETE CASCADE", "")
            .replace("    PRIMARY KEY(alias_list_id", "    alias_list_name TEXT NOT NULL DEFAULT '',\n    PRIMARY KEY(alias_list_id"), """
            UPDATE alias_list_analog_activity_summary SET alias_list_name=l.name
            FROM alias_list l WHERE alias_list_analog_activity_summary.alias_list_id=l.id
            """);
        // A persistent SQLite view cannot reference another attached file. Historical display context belongs
        // to receiver_channel, so the resolved event view remains complete after configuration is removed.
        String resolved = objectSql(connection, "view", "receiver_activity_event_resolved");
        try(Statement statement = connection.createStatement())
        {
            statement.execute("""
                CREATE TABLE receiver_channel_statistics_reset (
                    configuration_id TEXT NOT NULL PRIMARY KEY CHECK(typeof(configuration_id)='text'
                        AND length(configuration_id)=36 AND configuration_id=lower(configuration_id)
                        AND substr(configuration_id,9,1)='-' AND substr(configuration_id,14,1)='-'
                        AND substr(configuration_id,19,1)='-' AND substr(configuration_id,24,1)='-'
                        AND length(replace(configuration_id,'-',''))=32
                        AND replace(configuration_id,'-','') NOT GLOB '*[^0-9a-f]*'),
                    reset_at_ms INTEGER NOT NULL CHECK(typeof(reset_at_ms)='integer' AND reset_at_ms>0)
                ) WITHOUT ROWID
                """);
            statement.execute("""
                INSERT INTO receiver_channel_statistics_reset(configuration_id,reset_at_ms)
                SELECT configuration_id,analog_activity_reset_ms FROM configuration_channel
                WHERE analog_activity_reset_ms IS NOT NULL
                """);
            statement.execute("CREATE INDEX idx_receiver_channel_statistics_reset_retention ON " +
                "receiver_channel_statistics_reset(reset_at_ms,configuration_id)");
            statement.execute("DROP VIEW receiver_activity_event_resolved");
            statement.execute(resolved
                .replace("configured_alias.name AS resolved_alias_list_name", "rc.alias_list_name AS resolved_alias_list_name")
                .replace("configured.", "rc.")
                .replaceAll("(?m)^.*JOIN configuration_channel configured ON.*$", "")
                .replaceAll("(?m)^.*JOIN alias_list configured_alias ON.*$", ""));
            statement.execute("CREATE INDEX idx_trunked_radio_affiliation_channel ON trunked_radio_affiliation(channel_id)");
            statement.execute("CREATE INDEX idx_trunked_radio_presence_clear_channel ON trunked_radio_channel_presence_clear(channel_id)");
            statement.execute("CREATE INDEX idx_p25_wuid_assignment_observation_channel ON " +
                "p25_wuid_assignment_observation_summary(last_channel_id) WHERE last_channel_id IS NOT NULL");
            statement.execute("CREATE INDEX idx_receiver_channel_retention ON receiver_channel(last_seen_ms,id)");
            statement.execute("CREATE INDEX idx_alias_activity_retention ON " +
                "alias_activity_summary(coalesce(last_evidence_ms,updated_at_ms),alias_id)");
            statement.execute("CREATE INDEX idx_conventional_summary_retention ON " +
                "conventional_activity_summary(last_seen_ms,channel_id,frequency_hz,timeslot)");
            statement.execute("CREATE INDEX idx_alias_analog_retention ON " +
                "alias_list_analog_activity_summary(last_seen_ms,alias_list_id,channel_configuration_id,protocol_code,assigned_talkgroup)");
        }
    }

    public static void addAliasActivityIdentity(Connection connection) throws SQLException
    {
        try(Statement query = connection.createStatement();
            ResultSet columns = query.executeQuery("PRAGMA table_info(alias)"))
        {
            while(columns.next())
            {
                if("activity_identity".equals(columns.getString("name")))
                {
                    return;
                }
            }
        }
        rebuild(connection, "alias", sql -> sql.replace("    CHECK(\n", "    " + ACTIVITY_IDENTITY_SQL + ",\n" +
            "    activity_reset_ms INTEGER CHECK(activity_reset_ms IS NULL OR " +
            "(typeof(activity_reset_ms)='integer' AND activity_reset_ms>0)),\n    CHECK(\n"), """
            UPDATE alias SET activity_reset_ms=summary.last_reset_ms
            FROM alias_activity_summary summary WHERE summary.alias_id=alias.id
            """);
    }

    private static void removeLegacyConfigurationReset(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            // Only fresh format-63 configuration is using this helper. The prior marker is preserved separately
            // from the source monolith before any configuration table is removed during migration.
            statement.execute("ALTER TABLE configuration_channel DROP COLUMN analog_activity_reset_ms");
        }
    }

    public static void removeConfiguration(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("DROP VIEW IF EXISTS alias_talkgroup");
            statement.execute("DROP VIEW IF EXISTS alias_radio");
            for(String table : CONFIGURATION_TABLES)
            {
                if(!table.equals("database_metadata") && !table.equals("p25_subscriber_identity"))
                {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
            }
            statement.executeUpdate("DELETE FROM database_metadata WHERE key IN ('initial_admin_setup','icon_config_initialized')");
        }
    }

    public static void setIdentity(Connection connection, String role, String pairId) throws SQLException
    {
        SdrTrunkDatabaseStartup.setMetadata(connection, ROLE_KEY, role);
        SdrTrunkDatabaseStartup.setMetadata(connection, PAIR_ID_KEY, pairId);
    }

    public static String metadata(Connection connection, String key) throws SQLException
    {
        return metadata(connection, "main", key);
    }

    /** Reads one role's metadata while the explicit migration owns both databases on one connection. */
    public static String metadata(Connection connection, String schema, String key) throws SQLException
    {
        if(!java.util.Set.of("main", "activity").contains(schema)) throw new SQLException("Unknown database role schema");
        try(PreparedStatement statement = connection.prepareStatement("SELECT value,typeof(value)," +
            "typeof(updated_at_ms),updated_at_ms FROM " + schema + ".database_metadata WHERE key=?"))
        {
            statement.setString(1, key);
            try(ResultSet rows = statement.executeQuery())
            {
                if(!rows.next())
                {
                    return null;
                }
                if(!"text".equals(rows.getString(2)) || !"integer".equals(rows.getString(3)) || rows.getLong(4) <= 0)
                {
                    throw new SQLException("Database identity metadata has invalid storage");
                }
                return rows.getString(1);
            }
        }
    }

    /** Copies complete configuration rows and configuration allocator state through separate connections. */
    public static void copyConfiguration(Connection source, Connection target) throws SQLException
    {
        try(Statement statement = target.createStatement())
        {
            for(String table: CONFIGURATION_TABLES)
            {
                statement.executeUpdate("DELETE FROM " + table);
            }
        }
        for(String table : CONFIGURATION_TABLES)
        {
            List<String> columns = new ArrayList<>();
            try(Statement query = target.createStatement();
                ResultSet rows = query.executeQuery("PRAGMA table_info(" + table + ")"))
            {
                while(rows.next())
                {
                    columns.add(rows.getString("name"));
                }
            }
            String names = String.join(",", columns);
            String filter = table.equals("database_metadata") ? " WHERE key NOT IN (" +
                "'conventional_call_output_metrics_started_at_ms','trunked_logical_call_metrics_started_at_ms'," +
                "'radio_system_metrics_started_at_ms')" : table.equals("p25_subscriber_identity") ?
                " WHERE id IN (SELECT p25_subscriber_identity_id FROM alias_p25_subscriber_identity)" : "";
            try(Statement select = source.createStatement();
                ResultSet rows = select.executeQuery("SELECT " + names + " FROM " + table + filter))
            {
                int count = rows.getMetaData().getColumnCount();
                String placeholders = String.join(",", java.util.Collections.nCopies(count, "?"));
                try(PreparedStatement insert = target.prepareStatement("INSERT INTO " + table + "(" + names +
                    ") VALUES(" + placeholders + ")"))
                {
                    while(rows.next())
                    {
                        for(int column = 1; column <= count; column++)
                        {
                            insert.setObject(column, rows.getObject(column));
                        }
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
        }
        try(Statement delete = target.createStatement())
        {
            delete.executeUpdate("DELETE FROM sqlite_sequence");
        }
        try(Statement select = source.createStatement();
            ResultSet rows = select.executeQuery("SELECT name,seq FROM sqlite_sequence");
            PreparedStatement insert = target.prepareStatement("INSERT INTO sqlite_sequence(name,seq) VALUES(?,?)"))
        {
            while(rows.next())
            {
                if(CONFIGURATION_TABLES.contains(rows.getString(1)))
                {
                    insert.setString(1, rows.getString(1));
                    insert.setLong(2, rows.getLong(2));
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private static void rebuild(Connection connection, String table, UnaryOperator<String> transform, String populate)
        throws SQLException
    {
        String original = objectSql(connection, "table", table);
        List<String> indexes = new ArrayList<>();
        try(PreparedStatement query = connection.prepareStatement("""
            SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL
            """))
        {
            query.setString(1, table);
            try(ResultSet rows = query.executeQuery())
            {
                while(rows.next())
                {
                    indexes.add(rows.getString(1));
                }
            }
        }
        List<String> columns = new ArrayList<>();
        try(Statement query = connection.createStatement();
            ResultSet rows = query.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(rows.next())
            {
                columns.add(rows.getString("name"));
            }
        }
        long sequence = -1;
        try(PreparedStatement query = connection.prepareStatement("SELECT seq FROM sqlite_sequence WHERE name=?"))
        {
            query.setString(1, table);
            try(ResultSet rows = query.executeQuery())
            {
                if(rows.next())
                {
                    sequence = rows.getLong(1);
                }
            }
        }
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA legacy_alter_table=ON");
            statement.execute("ALTER TABLE " + table + " RENAME TO " + table + "_format62");
            statement.execute(transform.apply(original));
            String names = String.join(",", columns);
            statement.execute("INSERT INTO " + table + "(" + names + ") SELECT " + names + " FROM " + table + "_format62");
            statement.execute("DROP TABLE " + table + "_format62");
            for(String index: indexes)
            {
                statement.execute(index);
            }
            if(sequence >= 0)
            {
                statement.execute("UPDATE sqlite_sequence SET seq=" + sequence + " WHERE name='" + table + "'");
            }
            else
            {
                statement.execute("DELETE FROM sqlite_sequence WHERE name='" + table + "'");
            }
            if(populate != null)
            {
                statement.executeUpdate(populate);
            }
            statement.execute("PRAGMA legacy_alter_table=OFF");
        }
    }

    private static String objectSql(Connection connection, String type, String name) throws SQLException
    {
        try(PreparedStatement query = connection.prepareStatement("SELECT sql FROM sqlite_master WHERE type=? AND name=?"))
        {
            query.setString(1, type);
            query.setString(2, name);
            try(ResultSet rows = query.executeQuery())
            {
                if(rows.next())
                {
                    return rows.getString(1);
                }
            }
        }
        throw new SQLException("Missing " + type + " " + name);
    }
}
