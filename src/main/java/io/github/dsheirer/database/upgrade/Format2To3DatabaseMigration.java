/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Immutable exact global-format 2 to 3 migration. */
final class Format2To3DatabaseMigration implements DatabaseMigrationStep
{
    private static final List<DefaultAliasList> DEFAULT_ALIAS_LISTS = List.of(
        new DefaultAliasList("Default P25", "P25"),
        new DefaultAliasList("Default DMR", "DMR"),
        new DefaultAliasList("Default NXDN", "NXDN"),
        new DefaultAliasList("Default NBFM", "NBFM"));

    /** Pinned mappings for the decoder enum values persisted by format 2. */
    private static final List<DecoderAliasList> DECODER_ALIAS_LISTS = List.of(
        new DecoderAliasList("P25_CONVENTIONAL", "Default P25"),
        new DecoderAliasList("P25_PHASE1", "Default P25"),
        new DecoderAliasList("P25_PHASE2", "Default P25"),
        new DecoderAliasList("DMR", "Default DMR"),
        new DecoderAliasList("NXDN", "Default NXDN"),
        new DecoderAliasList("AM", "Default NBFM"),
        new DecoderAliasList("NBFM", "Default NBFM"));

    @Override
    public String id()
    {
        return "format-2-to-3";
    }

    @Override
    public String description()
    {
        return "Add complete P25 site projections and current default Alias List routing";
    }

    @Override
    public int sourceVersion()
    {
        return 2;
    }

    @Override
    public int targetVersion()
    {
        return 3;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        long unknown = DatabaseMigrationEffect.UNKNOWN_COUNT;
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE, "P25 activity", unknown,
                "Preserve site snapshots and channel summaries while adding nullable projections"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "P25 channel callsigns", unknown,
                "Recover current callsigns into matching historical summaries"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "factory Alias Lists", unknown,
                "Seed only missing canonical family lists and compatible unassigned channel routing"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "factory unmatched-talkgroup routing", unknown,
                "Route each newly created factory Alias List to the Default scan list"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "unassigned channel Alias Lists", unknown,
                "Assign compatible factory lists where format 2 retained no Alias List"));
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        validateDefaultAliasListState(connection);
        requireZero(connection, "SELECT COUNT(*) FROM configuration_channel WHERE json_valid(config_json) = 0",
            "configuration channels with invalid JSON that cannot be updated safely");

        long snapshots = scalarLong(connection, "SELECT COUNT(*) FROM p25_site_snapshot");
        long summaries = scalarLong(connection, "SELECT COUNT(*) FROM p25_site_channel_summary");
        long callsigns = scalarLong(connection, """
            SELECT COUNT(*)
            FROM p25_site_channel_summary AS summary
            WHERE EXISTS (
                SELECT 1 FROM p25_site_channel AS current
                WHERE current.guid = summary.guid
                  AND current.channel_key = summary.channel_key
                  AND current.callsign IS NOT NULL
            )
            """);
        long defaultLists = missingDefaultAliasListCount(connection);
        long defaultMemberships = missingDefaultAliasListMembershipCount(connection);
        long unassignedChannels = scalarLong(connection, """
            SELECT COUNT(*)
            FROM configuration_channel
            WHERE (alias_list_name IS NULL OR trim(alias_list_name) = '')
              AND decoder_type IN (
                  'P25_CONVENTIONAL', 'P25_PHASE1', 'P25_PHASE2', 'DMR', 'NXDN', 'AM', 'NBFM'
              )
            """);

        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE, "P25 activity", snapshots + summaries,
                "Preserve every existing site snapshot and channel summary while adding nullable projections"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "P25 channel callsigns", callsigns,
                "Recover the current callsign into matching historical channel summaries"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "factory Alias Lists", defaultLists,
                "Create only missing canonical family lists and route unmatched talkgroups to Default"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "factory unmatched-talkgroup routing", defaultMemberships,
                "Route each newly created factory Alias List to the Default scan list"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "unassigned channel Alias Lists",
                unassignedChannels, "Assign compatible factory lists where format 2 retained no Alias List"));
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);

        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(
                "ALTER TABLE p25_site_snapshot ADD COLUMN active_rfss_network_connection INTEGER");
            statement.executeUpdate("ALTER TABLE p25_site_snapshot ADD COLUMN system_id INTEGER");
            statement.executeUpdate("ALTER TABLE p25_site_channel_summary ADD COLUMN callsign TEXT");
            statement.executeUpdate("""
                UPDATE p25_site_channel_summary AS summary
                SET callsign = (
                    SELECT current.callsign
                    FROM p25_site_channel AS current
                    WHERE current.guid = summary.guid
                      AND current.channel_key = summary.channel_key
                )
                WHERE EXISTS (
                    SELECT 1
                    FROM p25_site_channel AS current
                    WHERE current.guid = summary.guid
                      AND current.channel_key = summary.channel_key
                      AND current.callsign IS NOT NULL
                )
                """);
            statement.executeUpdate("DROP VIEW p25_activity_event_resolved");
            statement.executeUpdate(resolvedActivityViewSql());
        }

        seedDefaultAliasLists(connection);
        assignDefaultAliasListsToUnassignedChannels(connection);
        setMetadata(connection, "p25_activity_schema_version", "27");
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);

        if(detected.version() != 2)
        {
            throw new SQLException("Migration step format-2-to-3 requires exact source format 2; found " +
                detected.version() + " [" + detected.id() + "]");
        }
    }

    static void validateDefaultAliasListState(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT family
            FROM alias_list
            WHERE name = ? COLLATE NOCASE
            """))
        {
            for(DefaultAliasList defaultList: DEFAULT_ALIAS_LISTS)
            {
                statement.setString(1, defaultList.name());

                try(ResultSet resultSet = statement.executeQuery())
                {
                    if(!resultSet.next())
                    {
                        continue;
                    }

                    String persistedFamily = resultSet.getString("family");

                    if(!defaultList.family().equals(persistedFamily))
                    {
                        throw new SQLException("Refusing migration: canonical Alias List name [" +
                            defaultList.name() + "] belongs to family [" + persistedFamily +
                            "]; expected [" + defaultList.family() + "]");
                    }

                }
            }
        }
    }

    private static long missingDefaultAliasListCount(Connection connection) throws SQLException
    {
        long count = 0;

        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM alias_list WHERE name = ? COLLATE NOCASE"))
        {
            for(DefaultAliasList defaultList: DEFAULT_ALIAS_LISTS)
            {
                statement.setString(1, defaultList.name());

                try(ResultSet resultSet = statement.executeQuery())
                {
                    if(resultSet.next() && resultSet.getLong(1) == 0)
                    {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    private static long missingDefaultAliasListMembershipCount(Connection connection) throws SQLException
    {
        //Only a list created by this step receives new unmatched behavior. An existing same-family name remains
        //administrator-owned and keeps its prior routing unchanged.
        return missingDefaultAliasListCount(connection);
    }

    private static void seedDefaultAliasLists(Connection connection) throws SQLException
    {
        validateDefaultAliasListState(connection);
        long defaultScanListId = defaultScanListId(connection);

        try(PreparedStatement lookup = connection.prepareStatement("""
                SELECT id FROM alias_list WHERE name = ? COLLATE NOCASE
                """);
            PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO alias_list (name, family, unmatched_talkgroup_record_enabled)
                VALUES (?, ?, 0)
                RETURNING id
                """);
            PreparedStatement membership = connection.prepareStatement("""
                INSERT INTO alias_list_unmatched_talkgroup_scan_list_membership (
                    alias_list_id, scan_list_id
                ) VALUES (?, ?)
                """))
        {
            for(DefaultAliasList defaultList: DEFAULT_ALIAS_LISTS)
            {
                lookup.setString(1, defaultList.name());

                try(ResultSet resultSet = lookup.executeQuery())
                {
                    if(resultSet.next())
                    {
                        continue;
                    }
                }

                insert.setString(1, defaultList.name());
                insert.setString(2, defaultList.family());

                try(ResultSet inserted = insert.executeQuery())
                {
                    if(!inserted.next())
                    {
                        throw new SQLException("Unable to create factory Alias List [" + defaultList.name() + "]");
                    }

                    membership.setLong(1, inserted.getLong(1));
                    membership.setLong(2, defaultScanListId);
                    membership.executeUpdate();
                }
            }
        }
    }

    private static void assignDefaultAliasListsToUnassignedChannels(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE configuration_channel
            SET alias_list_name = ?,
                config_json = json_set(config_json, '$.aliasListName', ?)
            WHERE (alias_list_name IS NULL OR trim(alias_list_name) = '')
              AND decoder_type = ?
            """))
        {
            for(DecoderAliasList mapping: DECODER_ALIAS_LISTS)
            {
                String persistedName = persistedAliasListName(connection, mapping.aliasListName());
                statement.setString(1, persistedName);
                statement.setString(2, persistedName);
                statement.setString(3, mapping.decoderType());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static String persistedAliasListName(Connection connection, String canonicalName) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT name FROM alias_list WHERE name = ? COLLATE NOCASE"))
        {
            statement.setString(1, canonicalName);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getString(1);
                }
            }
        }

        throw new SQLException("Unable to resolve factory Alias List [" + canonicalName + "]");
    }

    private static long defaultScanListId(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT id FROM scan_list WHERE is_default = 1"))
        {
            if(!resultSet.next())
            {
                throw new SQLException("Format 2 requires one Default scan list");
            }

            long id = resultSet.getLong(1);

            if(resultSet.next())
            {
                throw new SQLException("Format 2 contains more than one Default scan list");
            }

            return id;
        }
    }

    private static void setMetadata(Connection connection, String key, String value) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata (key, value, updated_at_ms)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at_ms=excluded.updated_at_ms
            """))
        {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    /** Exact format-3 view with every persisted numeric mapping pinned independently of mutable enums. */
    private static String resolvedActivityViewSql()
    {
        String channelKind = "CASE rc.kind_code WHEN 1 THEN 'TRUNKED_SITE' " +
            "WHEN 2 THEN 'CONVENTIONAL_P25' WHEN 3 THEN 'CONVENTIONAL_DMR' " +
            "WHEN 4 THEN 'CONVENTIONAL_NXDN' WHEN 10 THEN 'CONVENTIONAL_ANALOG' ELSE NULL END";
        String protocol = "CASE rc.protocol_code WHEN 1 THEN 'APCO25' WHEN 2 THEN 'APCO25_PHASE2' " +
            "WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' WHEN 10 THEN 'NBFM' ELSE 'UNKNOWN' END";
        String action = pinnedCase("a.action_code", List.of(
            "ACKNOWLEDGE", "ACTIVE", "BUSY", "CALL", "CHECK", "CHECK_ACK", "CONTINUE", "DATA",
            "DENIAL", "EMERGENCY", "GPS", "GRANT", "JOIN", "LOGOUT", "PAGE", "PATCH", "PATCH_CANCEL",
            "PATCH_CREATE", "QUEUED", "REGISTER", "REQUEST", "STATUS", "UNKNOWN"), "UNKNOWN");
        String eventType = pinnedCase("a.event_type_code", List.of(
            "AFFILIATE", "ANNOUNCEMENT", "ACKNOWLEDGE", "AUTOMATIC_REGISTRATION_SERVICE", "CALL",
            "CALL_ENCRYPTED", "CALL_GROUP", "CALL_GROUP_ENCRYPTED", "CALL_PATCH_GROUP",
            "CALL_PATCH_GROUP_ENCRYPTED", "CALL_ALERT", "CALL_DETECT", "CALL_IN_PROGRESS",
            "CALL_DO_NOT_MONITOR", "CALL_END", "CALL_INTERCONNECT", "CALL_INTERCONNECT_ENCRYPTED",
            "CALL_UNIQUE_ID", "CALL_UNIT_TO_UNIT", "CALL_UNIT_TO_UNIT_ENCRYPTED", "CALL_NO_TUNER",
            "CALL_TIMEOUT", "CELLOCATOR", "COMMAND", "DATA_CALL", "DATA_CALL_ENCRYPTED", "DATA_PACKET",
            "DEREGISTER", "DYNAMIC_REGROUP", "EMERGENCY", "FUNCTION", "GPS", "ICMP_PACKET", "ID_ANI",
            "ID_UNIQUE", "IP_PACKET", "LRRP", "NOTIFICATION", "PAGE", "QUERY", "RADIO_CHECK",
            "RADIO_REGISTRATION_SERVICE", "REGISTER", "REGISTER_ESN", "REQUEST", "RESPONSE",
            "RESPONSE_PACKET", "SDM", "SMS", "STATION_ID", "STATUS", "TEXT_MESSAGE", "UDP_PACKET",
            "UNKNOWN_PACKET", "XCMP", "UNKNOWN", "DENIAL"), null);
        String targetKind = "CASE a.target_kind_code WHEN 1 THEN 'TALKGROUP' WHEN 2 THEN 'RADIO' " +
            "WHEN 3 THEN 'PATCH_GROUP' ELSE NULL END";

        return """
            CREATE VIEW IF NOT EXISTS p25_activity_event_resolved AS
            SELECT
                a.id,
                rc.context_key,
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
                rc.decoder AS resolved_decoder,
                rc.system_key AS resolved_system_key,
                ps.wacn AS resolved_wacn,
                coalesce(ps.system_id, p25.system_id) AS resolved_system_id,
                rc.nac AS resolved_nac,
                rc.rfss AS resolved_rfss,
                rc.site AS resolved_site,
                rc.current_control_hz AS resolved_current_control_hz
            FROM p25_activity_event a
            LEFT JOIN receiver_context rc ON rc.id = a.context_id
            LEFT JOIN p25_system ps ON ps.system_key = rc.system_key
            LEFT JOIN p25_site_snapshot p25
              ON p25.guid = rc.guid AND rc.kind_code = 1 AND rc.protocol_code IN (1, 2)
            """.formatted(channelKind, protocol, action, eventType, targetKind);
    }

    private static String pinnedCase(String expression, List<String> values, String fallback)
    {
        StringBuilder builder = new StringBuilder("CASE ").append(expression);

        for(int x = 0; x < values.size(); x++)
        {
            builder.append(" WHEN ").append(x + 1).append(" THEN '").append(values.get(x)).append("'");
        }

        return builder.append(" ELSE ").append(fallback != null ? "'" + fallback + "'" : "NULL")
            .append(" END").toString();
    }

    private static void requireZero(Connection connection, String sql, String description) throws SQLException
    {
        long count = scalarLong(connection, sql);

        if(count != 0)
        {
            throw new SQLException("Refusing migration: found " + count + " " + description);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private record DefaultAliasList(String name, String family)
    {
    }

    private record DecoderAliasList(String decoderType, String aliasListName)
    {
    }
}
