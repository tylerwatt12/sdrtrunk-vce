/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
    void classifiesObservedTalkgroupsWithinOnlyTheSelectedAliasListAndObservesCommittedChanges() throws Exception
    {
        Path database = mTemporaryFolder.resolve("observed-talkgroups.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            clearFactoryAliasLists(statement);
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (1, 'Selected', 'P25'), (2, 'Other', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, matcher_type, protocol, value, min_value, max_value
                ) VALUES
                    (1, 1, 'Selected Range', 'TALKGROUP_RANGE', 'APCO25', NULL, 1, 1000),
                    (2, 1, 'Selected Exact', 'TALKGROUP', 'APCO25', 1700, NULL, NULL),
                    (3, 2, 'Other Exact', 'TALKGROUP', 'APCO25', 800, NULL, NULL),
                    (4, 1, 'Selected Narrow Range', 'TALKGROUP_RANGE', 'APCO25', NULL, 500, 900)
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
            clearFactoryAliasLists(statement);
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
                    value, min_value, max_value
                ) VALUES
                    (1, 1, 'NXDN Dispatch', 'County dispatch operations', 'Dispatch',
                        'TALKGROUP', 'NXDN', 91, NULL, NULL),
                    (2, 1, 'NXDN Unit', 'County radio unit', 'Units',
                        'RADIO_ID', 'NXDN', 123, NULL, NULL),
                    (3, 2, 'Wrong NXDN Dispatch', 'Other county dispatch', 'Other',
                        'TALKGROUP', 'NXDN', 91, NULL, NULL),
                    (4, 3, 'P25 Local', 'Local conventional dispatch', 'Local',
                        'TALKGROUP', 'APCO25', 101, NULL, NULL),
                    (5, 3, 'P25 Unit', 'Local conventional unit', 'Units',
                        'RADIO_ID', 'APCO25', 456, NULL, NULL),
                    (6, 3, 'P25 Secondary', 'Secondary dispatch', 'Dispatch',
                        'TALKGROUP', 'APCO25', 102, NULL, NULL),
                    (7, 1, 'NXDN Range', 'Range fallback description', 'Range',
                        'TALKGROUP_RANGE', 'NXDN', NULL, 1, 200),
                    (8, 4, 'DMR Dispatch', 'DMR county dispatch', 'Dispatch',
                        'TALKGROUP', 'DMR', 301, NULL, NULL),
                    (9, 4, 'DMR Unit', 'DMR county unit', 'Units',
                        'RADIO_ID', 'DMR', 302, NULL, NULL)
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
            clearFactoryAliasLists(statement);
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (1, 'P25 Trunked', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, description, group_name, matcher_type, protocol,
                    value
                ) VALUES
                    (1, 1, 'Local Dispatch', 'Local fallback description', 'Dispatch',
                        'TALKGROUP', 'APCO25', 700),
                    (3, 1, 'Local Unit', 'Local radio fallback', 'Units',
                        'RADIO_ID', 'APCO25', 800)
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
                    alias_list_id, first_seen_ms, last_seen_ms
                ) VALUES (77, 'p25:BEE00:348', 1, 1, 77, 1, 1, 2)
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
            assertEquals("Local Unit", radios.getFirst().get("alias_name"));
            assertEquals("Local radio fallback", radios.getFirst().get("alias_description"));
            assertEquals("Local radio fallback", activity.get(0).get("source_alias_description"));
            assertEquals("Local fallback description", activity.get(0).get("target_alias_description"));
            assertEquals("Local radio fallback", activity.get(1).get("target_alias_description"));
            assertEquals("Local radio fallback",
                relationships.getFirst().get("radio_alias_description"));
            assertEquals("Local fallback description",
                relationships.getFirst().get("talkgroup_alias_description"));
        }
    }

    @Test
    void loadsOnlyRulesForTheBoundedPageIdentityAndAliasList() throws Exception
    {
        Path database = mTemporaryFolder.resolve("bounded-alias-rules.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            clearFactoryAliasLists(statement);
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (1, 'Selected', 'P25'), (2, 'Irrelevant', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (alias_list_id, name, matcher_type, protocol, value)
                VALUES (1, 'Selected 42', 'TALKGROUP', 'APCO25', 42),
                       (2, 'Irrelevant 99', 'TALKGROUP', 'APCO25', 99)
                """);

            connection.setAutoCommit(false);

            try(PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO alias (alias_list_id, name, matcher_type, protocol, value)
                VALUES (?, ?, 'TALKGROUP', 'APCO25', ?)
                """))
            {
                for(int x = 0; x <= StatsAliasResolver.MAX_LOADED_RULES; x++)
                {
                    insert.setInt(1, 1);
                    insert.setString(2, "Wrong Selected pair " + x);
                    insert.setInt(3, 99);
                    insert.addBatch();
                    insert.setInt(1, 2);
                    insert.setString(2, "Wrong Irrelevant pair " + x);
                    insert.setInt(3, 42);
                    insert.addBatch();
                }

                insert.executeBatch();
            }

            connection.commit();
            connection.setAutoCommit(true);
            StatsAliasResolver resolver = new StatsAliasResolver();
            Map<String,Object> selected = observedP25Row(42);
            Map<String,Object> irrelevant = observedP25Row(99);
            irrelevant.put("alias_list_name", "Irrelevant");
            resolver.resolveObservedTalkgroups(connection, rows(selected, irrelevant));

            assertEquals("exact", selected.get("match_kind"));
            assertEquals("Selected 42", selected.get("matched_alias_name"));
            assertEquals("exact", irrelevant.get("match_kind"));
            assertEquals("Irrelevant 99", irrelevant.get("matched_alias_name"));
        }
    }

    @Test
    void systemAliasListPairBudgetIsSharedAcrossSystemsAndCountsRepeatedListNames()
    {
        StatsAliasResolver.AliasListPairBudget budget = new StatsAliasResolver.AliasListPairBudget(2);
        budget.add(1, "Shared");
        budget.add(2, "Shared");
        assertEquals(1, budget.queryLimit());

        StatsApiException overflow = assertThrows(StatsApiException.class,
            () -> budget.add(3, "Shared"));
        assertEquals(413, overflow.status());
        assertEquals("response_too_large", overflow.code());
    }

    @Test
    void assignedAliasListLookupUsesCaseInsensitiveConfigurationNames() throws Exception
    {
        Path database = mTemporaryFolder.resolve("alias-list-case.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            clearFactoryAliasLists(statement);
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family) VALUES (1, 'Mixed Case', 'DMR')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (1, 1, 'Case Dispatch', 'TALKGROUP', 'DMR', 42)
                """);
            Map<String,Object> identity = row("mixed case", 42);

            new StatsAliasResolver().enrichDmrTalkgroups(connection, rows(identity),
                "identity_id", "alias_");

            assertEquals("Case Dispatch", identity.get("alias_name"));
        }
    }

    @Test
    void rejectsUnboundedEnrichmentInputBeforeQueryingAliases() throws Exception
    {
        Path database = mTemporaryFolder.resolve("bounded-alias-input.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        List<Map<String,Object>> rows = new ArrayList<>();

        for(int x = 0; x <= StatsAliasResolver.MAX_INPUT_ROWS; x++)
        {
            rows.add(row("P25", x + 1));
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            StatsApiException exception = assertThrows(StatsApiException.class,
                () -> new StatsAliasResolver().enrichP25ConventionalTalkgroups(connection, rows,
                    "identity_id", "alias_"));
            assertEquals(413, exception.status());
            assertEquals("response_too_large", exception.code());
        }
    }

    @SafeVarargs
    private static List<Map<String,Object>> rows(Map<String,Object>... rows)
    {
        return new ArrayList<>(List.of(rows));
    }

    /**
     * These resolver tests deliberately install compact, fixed-ID Alias fixtures. Remove fresh-install factory rows
     * first so those IDs remain meaningful without changing production seeding.
     */
    private static void clearFactoryAliasLists(Statement statement) throws Exception
    {
        statement.executeUpdate("DELETE FROM alias_list_unmatched_talkgroup_scan_list_membership");
        statement.executeUpdate("DELETE FROM alias_list");
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
