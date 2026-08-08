/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsAliasResolverTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void classifiesObservedTalkgroupsWithinOnlyTheSelectedAliasListAndInvalidatesImmediately() throws Exception
    {
        Path database = mTemporaryFolder.resolve("observed-talkgroups.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (1, 'Selected', 'P25'), (2, 'Other', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, matcher_type, protocol, value, min_value, max_value,
                    wacn, p25_system_id
                ) VALUES
                    (1, 1, 'Selected Range', 'TALKGROUP_RANGE', 'APCO25', NULL, 1, 1000,
                        NULL, NULL),
                    (2, 1, 'Selected Exact', 'TALKGROUP', 'APCO25', 1700,
                        NULL, NULL, NULL, NULL),
                    (3, 2, 'Other Exact', 'TALKGROUP', 'APCO25', 800, NULL, NULL, NULL, NULL),
                    (4, 1, 'Selected Narrow Range', 'TALKGROUP_RANGE', 'APCO25', NULL, 500, 900,
                        NULL, NULL)
                """);

            StatsAliasResolver resolver = new StatsAliasResolver();
            Map<String,Object> exact = observedP25Row(1700);
            exact.put("p25_identity_state_code", 2);
            exact.put("p25_home_wacn", 0xBEE00);
            exact.put("p25_home_system_id", 0x348);
            exact.put("p25_home_talkgroup_id", 700);
            Map<String,Object> ordinarySameNumber = observedP25Row(700);
            Map<String,Object> range = observedP25Row(800);
            Map<String,Object> none = observedP25Row(1200);
            Map<String,Object> unknown = observedP25Row(1201);
            unknown.put("p25_identity_state_code", 0);
            Map<String,Object> reservedZero = observedP25Row(0);
            reservedZero.put("p25_identity_state_code", 2);
            reservedZero.put("p25_home_wacn", 0xBEE00);
            reservedZero.put("p25_home_system_id", 0x348);
            reservedZero.put("p25_home_talkgroup_id", 0);
            Map<String,Object> reservedMaximum = observedP25Row(0xFFFF);
            reservedMaximum.put("p25_identity_state_code", 2);
            reservedMaximum.put("p25_home_wacn", 0xBEE00);
            reservedMaximum.put("p25_home_system_id", 0x348);
            reservedMaximum.put("p25_home_talkgroup_id", 0xFFFF);
            List<Map<String,Object>> rows = rows(exact, ordinarySameNumber, range, none, unknown,
                reservedZero, reservedMaximum);
            resolver.resolveObservedTalkgroups(connection, rows);

            assertEquals("exact", exact.get("match_kind"));
            assertEquals(2L, ((Number)exact.get("matched_alias_id")).longValue());
            assertEquals("Selected Exact", exact.get("matched_alias_name"));
            assertEquals(true, exact.get("promotion_supported"));
            assertEquals("range", ordinarySameNumber.get("match_kind"));
            assertEquals(4L, ((Number)ordinarySameNumber.get("matched_alias_id")).longValue());
            assertEquals("range", range.get("match_kind"),
                "An exact definition in another alias list must not claim this row");
            assertEquals(4L, ((Number)range.get("matched_alias_id")).longValue(),
                "Discovery must copy actions from the same covering range runtime selects");
            assertEquals("none", none.get("match_kind"));
            assertNull(none.get("matched_alias_id"));
            assertNull(none.get("matched_alias_name"));
            assertEquals(true, unknown.get("promotion_supported"));
            assertEquals("none", unknown.get("match_kind"));
            assertNull(unknown.get("promotion_reason"));
            assertEquals(false, reservedZero.get("promotion_supported"));
            assertEquals("none", reservedZero.get("match_kind"));
            assertEquals("The local P25 talkgroup address is reserved",
                reservedZero.get("promotion_reason"));
            assertEquals(false, reservedMaximum.get("promotion_supported"));
            assertEquals("none", reservedMaximum.get("match_kind"));

            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, matcher_type, protocol, value
                ) VALUES (5, 1, 'New Exact', 'TALKGROUP', 'APCO25', 800)
                """);
            resolver.resolveObservedTalkgroups(connection, rows(range));
            assertEquals("range", range.get("match_kind"), "The normal polling cache remains bounded");
            resolver.invalidate();
            resolver.resolveObservedTalkgroups(connection, rows(range));
            assertEquals("exact", range.get("match_kind"));
            assertEquals("New Exact", range.get("matched_alias_name"));
        }
    }

    @Test
    void resolvesAssignedListDescriptionsWithoutChangingAliasPrecedence() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (1, 'NXDN County', 'NXDN'),
                       (2, 'NXDN Other', 'NXDN'),
                       (3, 'P25 Conventional', 'P25'),
                       (4, 'DMR County', 'DMR')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, description, group_name, matcher_type, protocol,
                    value, min_value, max_value, wacn, p25_system_id
                ) VALUES
                    (1, 1, 'NXDN Dispatch', 'County dispatch operations', 'Dispatch',
                        'TALKGROUP', 'NXDN', 91, NULL, NULL, NULL, NULL),
                    (2, 1, 'NXDN Unit', 'County radio unit', 'Units',
                        'RADIO_ID', 'NXDN', 123, NULL, NULL, NULL, NULL),
                    (3, 2, 'Wrong NXDN Dispatch', 'Other county dispatch', 'Other',
                        'TALKGROUP', 'NXDN', 91, NULL, NULL, NULL, NULL),
                    (4, 3, 'P25 Local', 'Local conventional dispatch', 'Local',
                        'TALKGROUP', 'APCO25', 101, NULL, NULL, NULL, NULL),
                    (5, 3, 'P25 Unit', 'Local conventional unit', 'Units',
                        'RADIO_ID', 'APCO25', 456, NULL, NULL, NULL, NULL),
                    (6, 3, 'P25 Secondary', 'Secondary dispatch', 'Dispatch',
                        'TALKGROUP', 'APCO25', 102, NULL, NULL, NULL, NULL),
                    (7, 1, 'NXDN Range', 'Range fallback description', 'Range',
                        'TALKGROUP_RANGE', 'NXDN', NULL, 1, 200, NULL, NULL),
                    (8, 4, 'DMR Dispatch', 'DMR county dispatch', 'Dispatch',
                        'TALKGROUP', 'DMR', 301, NULL, NULL, NULL, NULL),
                    (9, 4, 'DMR Unit', 'DMR county unit', 'Units',
                        'RADIO_ID', 'DMR', 302, NULL, NULL, NULL, NULL)
                """);

            StatsAliasResolver resolver = new StatsAliasResolver();
            List<Map<String,Object>> nxdnTalkgroups = rows(
                row("NXDN County", 91), row("NXDN Other", 91));
            List<Map<String,Object>> nxdnRadios = rows(row("NXDN County", 123));
            List<Map<String,Object>> p25Talkgroups = rows(
                row("P25 Conventional", 101), row("P25 Conventional", 102));
            List<Map<String,Object>> p25Radios = rows(row("P25 Conventional", 456));
            List<Map<String,Object>> dmrTalkgroups = rows(row("DMR County", 301));
            List<Map<String,Object>> dmrRadios = rows(row("DMR County", 302));
            Map<String,Object> dmrActivityRow = activityRow("DMR", 1, "DMR County", 302, 301, 1);
            Map<String,Object> nxdnActivityRow = activityRow("NXDN", 1, "NXDN County", 123, 91, 1);
            Map<String,Object> p25ConventionalActivityRow =
                activityRow("APCO25", 2, "P25 Conventional", 456, 101, 1);
            List<Map<String,Object>> activity = rows(dmrActivityRow, nxdnActivityRow,
                p25ConventionalActivityRow);

            resolver.enrichNxdnTalkgroups(connection, nxdnTalkgroups, "identity_id", "talkgroup_alias_");
            resolver.enrichNxdnRadios(connection, nxdnRadios, "identity_id", "radio_alias_");
            resolver.enrichP25ConventionalTalkgroups(connection, p25Talkgroups, "identity_id", "alias_");
            resolver.enrichP25ConventionalRadios(connection, p25Radios, "identity_id", "alias_");
            resolver.enrichDmrTalkgroups(connection, dmrTalkgroups, "identity_id", "talkgroup_alias_");
            resolver.enrichDmrRadios(connection, dmrRadios, "identity_id", "radio_alias_");
            resolver.enrichActivity(connection, activity);

            assertEquals("NXDN Dispatch", nxdnTalkgroups.get(0).get("talkgroup_alias_name"));
            assertEquals("County dispatch operations",
                nxdnTalkgroups.get(0).get("talkgroup_alias_description"));
            assertEquals("Wrong NXDN Dispatch", nxdnTalkgroups.get(1).get("talkgroup_alias_name"));
            assertEquals("Other county dispatch",
                nxdnTalkgroups.get(1).get("talkgroup_alias_description"));
            assertEquals("NXDN Unit", nxdnRadios.getFirst().get("radio_alias_name"));
            assertEquals("County radio unit", nxdnRadios.getFirst().get("radio_alias_description"));
            assertEquals("P25 Local", p25Talkgroups.getFirst().get("alias_name"));
            assertEquals("Local conventional dispatch",
                p25Talkgroups.getFirst().get("alias_description"));
            assertEquals("P25 Secondary", p25Talkgroups.get(1).get("alias_name"));
            assertEquals("Secondary dispatch", p25Talkgroups.get(1).get("alias_description"));
            assertEquals("P25 Unit", p25Radios.getFirst().get("alias_name"));
            assertEquals("Local conventional unit", p25Radios.getFirst().get("alias_description"));
            assertEquals("DMR Dispatch", dmrTalkgroups.getFirst().get("talkgroup_alias_name"));
            assertEquals("DMR county dispatch",
                dmrTalkgroups.getFirst().get("talkgroup_alias_description"));
            assertEquals("DMR Unit", dmrRadios.getFirst().get("radio_alias_name"));
            assertEquals("DMR county unit", dmrRadios.getFirst().get("radio_alias_description"));
            assertEquals("DMR Unit", dmrActivityRow.get("source_alias_name"));
            assertEquals("DMR Dispatch", dmrActivityRow.get("target_alias_name"));
            assertEquals("NXDN Unit", nxdnActivityRow.get("source_alias_name"));
            assertEquals("NXDN Dispatch", nxdnActivityRow.get("target_alias_name"));
            assertEquals("P25 Unit", p25ConventionalActivityRow.get("source_alias_name"));
            assertEquals("P25 Local", p25ConventionalActivityRow.get("target_alias_name"));
        }
    }

    @Test
    void emitsDescriptionsForEveryNormalP25AliasPrefix() throws Exception
    {
        Path database = mTemporaryFolder.resolve("normal-p25.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (1, 'P25 Trunked', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, description, group_name, matcher_type, protocol,
                    value, wacn, p25_system_id
                ) VALUES
                    (1, 1, 'Local Dispatch', 'Local fallback description', 'Dispatch',
                        'TALKGROUP', 'APCO25', 700, NULL, NULL),
                    (3, 1, 'Local Unit', 'Local radio fallback', 'Units',
                        'RADIO_ID', 'APCO25', 800, NULL, NULL),
                    (4, 1, 'Qualified Unit', 'Full radio description', 'Units',
                        'P25_FULLY_QUALIFIED_RADIO_ID', 'APCO25', 800, 0xBEE00, 0x348)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (
                    guid, first_seen_ms, last_seen_ms, system_key, alias_list_name
                ) VALUES ('alias-prefix-test', 1, 2, 77, 'P25 Trunked')
                """);
            statement.executeUpdate("""
                INSERT INTO p25_system (system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (77, 0xBEE00, 0x348, 1, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, first_seen_ms, last_seen_ms, system_key
                ) VALUES (77, 'alias-prefix-context', 'alias-prefix-test', 1, 1, 1, 2, 77)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, p25_system_key,
                    first_seen_ms, last_seen_ms
                ) VALUES (77, 'p25:BEE00:348', 1, 1, 77, 1, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context (context_id, scope_id, first_seen_ms, last_seen_ms)
                VALUES (77, 77, 1, 2)
                """);

            StatsAliasResolver resolver = new StatsAliasResolver();
            List<Map<String,Object>> talkgroups = rows(p25Row());
            talkgroups.getFirst().put("talkgroup_id", 700);
            List<Map<String,Object>> radios = rows(p25Row());
            radios.getFirst().put("radio_id", 800);
            List<Map<String,Object>> activity = rows(p25Row(), p25Row());
            activity.get(0).put("source_radio_id", 800);
            activity.get(0).put("target_kind_code", 1);
            activity.get(0).put("target_id", 700);
            activity.get(1).put("source_radio_id", 800);
            activity.get(1).put("target_kind_code", 2);
            activity.get(1).put("target_id", 800);
            List<Map<String,Object>> relationships = rows(p25Row());
            relationships.getFirst().put("radio_id", 800);
            relationships.getFirst().put("talkgroup_id", 700);

            resolver.enrichTalkgroups(connection, talkgroups);
            resolver.enrichRadios(connection, radios);
            resolver.enrichActivity(connection, activity);
            resolver.enrichRelationships(connection, relationships);

            assertEquals("Local Dispatch", talkgroups.getFirst().get("alias_name"));
            assertEquals("Local fallback description", talkgroups.getFirst().get("alias_description"));
            assertEquals("Qualified Unit", radios.getFirst().get("alias_name"));
            assertEquals("Full radio description", radios.getFirst().get("alias_description"));
            assertEquals("Full radio description", activity.get(0).get("source_alias_description"));
            assertEquals("Local fallback description", activity.get(0).get("target_alias_description"));
            assertEquals("Full radio description", activity.get(1).get("target_alias_description"));
            assertEquals("Full radio description",
                relationships.getFirst().get("radio_alias_description"));
            assertEquals("Local fallback description",
                relationships.getFirst().get("talkgroup_alias_description"));
        }
    }

    @SafeVarargs
    private static List<Map<String,Object>> rows(Map<String,Object>... rows)
    {
        return new ArrayList<>(List.of(rows));
    }

    private static Map<String,Object> row(String aliasList, int identityId)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("alias_list_name", aliasList);
        row.put("identity_id", identityId);
        return row;
    }

    private static Map<String,Object> p25Row()
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("protocol", "APCO25");
        row.put("channel_kind_code", 1);
        row.put("wacn", 0xBEE00);
        row.put("system_id", 0x348);
        row.put("system_key", 77);
        return row;
    }

    private static Map<String,Object> observedP25Row(int talkgroup)
    {
        Map<String,Object> row = p25Row();
        row.put("topology", "TRUNKED");
        row.put("alias_list_name", "Selected");
        row.put("talkgroup_id", talkgroup);
        row.put("protocol_code", 1);
        row.put("p25_identity_state_code", 1);
        return row;
    }

    private static Map<String,Object> activityRow(String protocol, int channelKind, String aliasList,
                                                  int source, int target, int targetKind)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("protocol", protocol);
        row.put("channel_kind_code", channelKind);
        row.put("alias_list_name", aliasList);
        row.put("source_radio_id", source);
        row.put("target_id", target);
        row.put("target_kind_code", targetKind);
        return row;
    }
}
