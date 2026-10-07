/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Migration-only normalization of serving-local P25 owners; the caller owns the migration transaction. */
final class P25HomeRadioIdentityConsolidation
{
    private static final String SUMMARY = "radio_system_identity_summary";
    private static final List<String> RADIO_CHILDREN = List.of("trunked_radio_group_summary",
        "trunked_radio_affiliation", "trunked_radio_channel_presence", "trunked_radio_channel_presence_clear");
    private static final List<String> IDENTITY_BUCKETS = List.of("trunked_logical_call_identity_bucket",
        "p25_site_call_identity_bucket");

    private P25HomeRadioIdentityConsolidation()
    {
    }

    static ReceiverActivitySchema.HomeRadioConsolidation consolidate(Connection connection) throws SQLException
    {
        List<LocalRadio> localRadios = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT identity.id, identity.radio_system_id, identity.identity_id, system.p25_wacn, system.p25_system_id
            FROM radio_system_identity_summary identity
            JOIN radio_system system ON system.id=identity.radio_system_id
            WHERE identity.identity_kind_code=2 AND identity.home_wacn=-1 AND identity.home_system_id=-1
              AND identity.p25_subscriber_identity_id IS NULL AND system.protocol_code=1
              AND system.p25_wacn IS NOT NULL AND system.p25_system_id IS NOT NULL
            ORDER BY identity.id
            """))
        {
            while(rows.next())
            {
                localRadios.add(new LocalRadio(rows.getLong(1), rows.getLong(2), rows.getInt(3),
                    rows.getInt(4), rows.getInt(5)));
            }
        }
        if(localRadios.isEmpty())
        {
            return new ReceiverActivitySchema.HomeRadioConsolidation(0, 0, 0);
        }

        //Scan each retained evidence projection once, rather than rescanning call history for every local radio.
        Set<LocalAddress> ambiguous = ambiguousLocalAddresses(connection);
        long promoted = 0;
        long merged = 0;
        long retained = 0;
        try(PreparedStatement counterpart = connection.prepareStatement("""
            SELECT id FROM radio_system_identity_summary
            WHERE radio_system_id=? AND identity_kind_code=2 AND home_wacn=? AND home_system_id=? AND identity_id=?
            """); PreparedStatement promote = connection.prepareStatement("""
            UPDATE radio_system_identity_summary SET home_wacn=?, home_system_id=? WHERE id=?
            """))
        {
            for(LocalRadio radio: localRadios)
            {
                if(ambiguous.contains(new LocalAddress(radio.systemId(), radio.radioId())))
                {
                    retained++;
                    continue;
                }
                counterpart.setLong(1, radio.systemId());
                counterpart.setInt(2, radio.wacn());
                counterpart.setInt(3, radio.system());
                counterpart.setInt(4, radio.radioId());
                Long survivor = null;
                try(ResultSet rows = counterpart.executeQuery())
                {
                    if(rows.next())
                    {
                        survivor = rows.getLong(1);
                    }
                }
                if(survivor == null)
                {
                    promote.setInt(1, radio.wacn());
                    promote.setInt(2, radio.system());
                    promote.setLong(3, radio.summaryId());
                    promote.executeUpdate();
                    promoted++;
                }
                else
                {
                    merge(connection, radio, survivor);
                    merged++;
                }
            }
        }
        return new ReceiverActivitySchema.HomeRadioConsolidation(promoted, merged, retained);
    }

    /** An observed address used by a different permanent subscriber cannot identify an entire old local lifetime. */
    private static Set<LocalAddress> ambiguousLocalAddresses(Connection connection) throws SQLException
    {
        Set<LocalAddress> addresses = new HashSet<>();
        collect(connection, addresses, """
            SELECT DISTINCT assignment.radio_system_id, assignment.working_id
            FROM p25_wuid_assignment_observation_summary assignment
            JOIN p25_subscriber_identity subscriber ON subscriber.id=assignment.p25_subscriber_identity_id
            JOIN radio_system system ON system.id=assignment.radio_system_id
            JOIN radio_system_identity_summary local ON local.radio_system_id=assignment.radio_system_id
                AND local.identity_kind_code=2 AND local.home_wacn=-1 AND local.home_system_id=-1
                AND local.identity_id=assignment.working_id AND local.p25_subscriber_identity_id IS NULL
            WHERE subscriber.home_wacn<>system.p25_wacn OR subscriber.home_system_id<>system.p25_system_id
               OR subscriber.subscriber_id<>assignment.working_id
            """);
        collectEvidence(connection, addresses, "receiver_activity_event", "source_identity_summary_id",
            "coalesce(evidence.source_observed_working_id, evidence.source_observed_local_id)");
        collectEvidence(connection, addresses, "receiver_activity_event", "target_identity_summary_id",
            "coalesce(evidence.target_observed_working_id, evidence.target_observed_local_id)");
        collectEvidence(connection, addresses, "trunked_radio_affiliation", "radio_identity_id",
            "coalesce(evidence.radio_observed_working_id, evidence.radio_observed_local_id)");
        for(String table: List.of("trunked_radio_channel_presence", "trunked_radio_channel_presence_clear",
            "p25_site_call_identity_bucket"))
        {
            collectEvidence(connection, addresses, table,
                table.equals("p25_site_call_identity_bucket") ? "identity_summary_id" : "radio_identity_id",
                "coalesce(evidence.observed_working_id, evidence.observed_local_id)");
        }
        return addresses;
    }

    private static void collectEvidence(Connection connection, Set<LocalAddress> addresses, String table,
                                        String ownerColumn, String address) throws SQLException
    {
        //Keep the bounded directory before retained history so the existing owner indexes seek qualified evidence.
        collect(connection, addresses, "SELECT DISTINCT evidence.radio_system_id, " + address + " FROM " +
            SUMMARY + " owner CROSS JOIN " + table + " evidence ON owner.id=evidence." + ownerColumn +
            " JOIN radio_system system ON system.id=evidence.radio_system_id" +
            " JOIN " + SUMMARY + " local ON local.radio_system_id=evidence.radio_system_id" +
            " AND local.identity_kind_code=2 AND local.home_wacn=-1 AND local.home_system_id=-1" +
            " AND local.identity_id=" + address + " AND local.p25_subscriber_identity_id IS NULL" +
            " WHERE owner.identity_kind_code=2 AND owner.home_wacn>=0 AND " + address + " IS NOT NULL" +
            " AND (owner.home_wacn<>system.p25_wacn OR owner.home_system_id<>system.p25_system_id" +
            " OR owner.identity_id<>" + address + ")");
    }

    private static void collect(Connection connection, Set<LocalAddress> addresses, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            while(rows.next())
            {
                addresses.add(new LocalAddress(rows.getLong(1), rows.getInt(2)));
            }
        }
    }

    private static void merge(Connection connection, LocalRadio radio, long survivor) throws SQLException
    {
        for(String column: List.of("source_identity_summary_id", "target_identity_summary_id"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "UPDATE receiver_activity_event SET " + column + "=? WHERE " + column + "=?"))
            {
                statement.setLong(1, survivor);
                statement.setLong(2, radio.summaryId());
                statement.executeUpdate();
            }
        }
        for(String table: RADIO_CHILDREN)
        {
            mergeRows(connection, table, "radio_identity_id", radio.summaryId(), survivor);
        }
        for(String table: IDENTITY_BUCKETS)
        {
            mergeRows(connection, table, "identity_summary_id", radio.summaryId(), survivor);
        }
        //The surviving clear watermark also applies to confirmations formerly owned by the other row.
        for(String table: List.of("trunked_radio_affiliation", "trunked_radio_channel_presence"))
        {
            try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table +
                " WHERE radio_identity_id=? AND EXISTS (SELECT 1 FROM trunked_radio_channel_presence_clear clear" +
                " WHERE clear.radio_system_id=" + table + ".radio_system_id" +
                " AND clear.radio_identity_id=" + table + ".radio_identity_id" +
                " AND clear.channel_id=" + table + ".channel_id AND clear.cleared_at_ms>=" +
                    table + ".confirmed_at_ms)"))
            {
                statement.setLong(1, survivor);
                statement.executeUpdate();
            }
        }

        //Normalize the losing key only in the SELECT; its persistent key cannot change until the collision is folded.
        mergeRows(connection, SUMMARY, "id", radio.summaryId(), survivor);
    }

    /** Fold accepted counter credits once; independent site observations stay in their original site/channel keys. */
    private static void mergeRows(Connection connection, String table, String ownerColumn, long losingId,
                                   long survivingId) throws SQLException
    {
        List<Column> columns = new ArrayList<>();
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(rows.next())
            {
                columns.add(new Column(rows.getString("name"), rows.getInt("pk")));
            }
        }
        String names = columns.stream().map(Column::name).collect(Collectors.joining(", "));
        String select = columns.stream().map(column -> column.name().equals(ownerColumn) ? "?" :
            table.equals(SUMMARY) && Set.of("home_wacn", "home_system_id").contains(column.name()) ?
                "(SELECT " + column.name() + " FROM " + SUMMARY + " WHERE id=" + survivingId + ")" : column.name())
            .collect(Collectors.joining(", "));
        String keys = columns.stream().filter(column -> column.primaryKeyOrder() > 0)
            .sorted(Comparator.comparingInt(Column::primaryKeyOrder)).map(Column::name)
            .collect(Collectors.joining(", "));
        String timestamp = columns.stream().map(Column::name)
            .filter(name -> Set.of("last_seen_ms", "confirmed_at_ms", "cleared_at_ms", "last_observed_at_ms")
                .contains(name)).findFirst().orElse(null);
        String updates = columns.stream().filter(column -> column.primaryKeyOrder() == 0)
            .filter(column -> !Set.of("radio_system_id", "identity_kind_code", "home_wacn", "home_system_id",
                "identity_id", "radio_kind_code", "group_kind_code", "talkgroup_kind_code")
                .contains(column.name()))
            .map(column -> column.name() + "=" + mergedValue(table, column.name(), timestamp))
            .collect(Collectors.joining(", "));
        try(PreparedStatement statement = connection.prepareStatement("INSERT INTO " + table + " (" + names +
            ") SELECT " + select + " FROM " + table + " WHERE " + ownerColumn +
            "=? ON CONFLICT(" + keys + ") DO UPDATE SET " + updates))
        {
            statement.setLong(1, survivingId);
            statement.setLong(2, losingId);
            statement.executeUpdate();
        }
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table +
            " WHERE " + ownerColumn + "=?"))
        {
            statement.setLong(1, losingId);
            statement.executeUpdate();
        }
    }

    private static String mergedValue(String table, String column, String timestamp)
    {
        String current = table + "." + column;
        String incoming = "excluded." + column;
        if(column.endsWith("_count"))
        {
            return current + "+" + incoming;
        }
        if(column.equals("first_seen_ms"))
        {
            return "min(" + current + "," + incoming + ")";
        }
        if(column.equals(timestamp))
        {
            return "max(" + current + "," + incoming + ")";
        }
        if(column.equals("p25_subscriber_identity_id"))
        {
            return "coalesce(" + current + "," + incoming + ")";
        }
        if(column.equals("last_talker_alias") || column.equals("last_talker_alias_seen_ms"))
        {
            return "CASE WHEN coalesce(excluded.last_talker_alias_seen_ms,0)>" +
                "coalesce(" + table + ".last_talker_alias_seen_ms,0) THEN " + incoming +
                " ELSE " + current + " END";
        }
        if(table.equals(SUMMARY) || table.equals("trunked_radio_group_summary"))
        {
            return "CASE WHEN " + incoming + " IS NOT NULL AND (" + current + " IS NULL OR excluded." +
                timestamp + ">=" + table + "." + timestamp + ") THEN " + incoming + " ELSE " + current + " END";
        }
        return "CASE WHEN excluded." + timestamp + ">" + table + "." + timestamp + " THEN " +
            incoming + " ELSE " + current + " END";
    }

    private record LocalAddress(long systemId, int radioId)
    {
    }

    private record LocalRadio(long summaryId, long systemId, int radioId, int wacn, int system)
    {
    }

    private record Column(String name, int primaryKeyOrder)
    {
    }
}
