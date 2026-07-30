/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
                    (6, 3, 'Qualified Only', 'Trunked-only dispatch', 'Qualified',
                        'P25_FULLY_QUALIFIED_TALKGROUP', 'APCO25', 102, NULL, NULL, 0xBEE00, 0x348),
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
            assertFalse(p25Talkgroups.get(1).containsKey("alias_name"),
                "A conventional receiver cannot validate a fully-qualified P25 rule");
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
                    (2, 1, 'Qualified Dispatch', 'Full dispatch description', 'Dispatch',
                        'P25_FULLY_QUALIFIED_TALKGROUP', 'APCO25', 700, 0xBEE00, 0x348),
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

            assertEquals("Qualified Dispatch", talkgroups.getFirst().get("alias_name"));
            assertEquals("Full dispatch description", talkgroups.getFirst().get("alias_description"));
            assertEquals("Qualified Unit", radios.getFirst().get("alias_name"));
            assertEquals("Full radio description", radios.getFirst().get("alias_description"));
            assertEquals("Full radio description", activity.get(0).get("source_alias_description"));
            assertEquals("Full dispatch description", activity.get(0).get("target_alias_description"));
            assertEquals("Full radio description", activity.get(1).get("target_alias_description"));
            assertEquals("Full radio description",
                relationships.getFirst().get("radio_alias_description"));
            assertEquals("Full dispatch description",
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
