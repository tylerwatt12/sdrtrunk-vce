/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
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
    private static final String P25_CONFIGURATION_ID = "10000000-0000-4000-8000-000000000001";
    private static final String SECOND_P25_CONFIGURATION_ID = "10000000-0000-4000-8000-000000000002";
    private static final String P25_RADIORESOLVE_ID = "20000000-0000-4000-8000-000000000001";
    private static final String SECOND_P25_RADIORESOLVE_ID = "20000000-0000-4000-8000-000000000002";
    private static final String P25_SYSTEM_KEY = "p25:bee00:348";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void classifiesObservedGroupIdentitiesWithinOnlyTheSelectedAliasListAndObservesCommittedChanges() throws Exception
    {
        Path database = mTemporaryFolder.resolve("observed-group-identities.sqlite");
        createDatabase(database);

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
            Map<String,Object> ordinarySameNumber = observedP25Row(700);
            Map<String,Object> range = observedP25Row(800);
            Map<String,Object> none = observedP25Row(1200);
            Map<String,Object> unknown = observedP25Row(1201);
            Map<String,Object> reservedZero = observedP25Row(0);
            Map<String,Object> reservedMaximum = observedP25Row(0xFFFF);
            List<Map<String,Object>> rows = rows(exact, ordinarySameNumber, range, none, unknown,
                reservedZero, reservedMaximum);
            resolver.resolveObservedGroupIdentities(connection, rows);

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
            resolver.resolveObservedGroupIdentities(connection, rows(range));
            assertEquals("exact", range.get("match_kind"));
            assertEquals("New Exact", range.get("matched_alias_name"));
        }
    }

    @Test
    void resolvesAssignedListDescriptionsWithoutChangingAliasPrecedence() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        createDatabase(database);

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
            List<Map<String,Object>> nxdnTalkgroups = rows(row(1, 91), row(2, 91));
            List<Map<String,Object>> nxdnRadios = rows(row(1, 123));
            List<Map<String,Object>> p25Talkgroups = rows(
                row(3, 101), row(3, 102));
            List<Map<String,Object>> p25Radios = rows(row(3, 456));
            List<Map<String,Object>> dmrTalkgroups = rows(row(4, 301));
            List<Map<String,Object>> dmrRadios = rows(row(4, 302));
            Map<String,Object> dmrActivityRow = activityRow("DMR", 1, 4, 302, 301, 1);
            Map<String,Object> nxdnActivityRow = activityRow("NXDN", 1, 1, 123, 91, 1);
            Map<String,Object> p25ConventionalActivityRow =
                activityRow("APCO25", 2, 3, 456, 101, 1);
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
        createDatabase(database);

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
            insertP25Channel(statement, 77, P25_CONFIGURATION_ID, P25_RADIORESOLVE_ID, 1);

            StatsAliasResolver resolver = new StatsAliasResolver();
            List<Map<String,Object>> talkgroups = rows(p25Row());
            talkgroups.getFirst().put("talkgroup_id", 700);
            List<Map<String,Object>> radios = rows(p25Row());
            radios.getFirst().put("radio_id", 800);
            List<Map<String,Object>> activity = rows(p25Row(), p25Row());
            activity.forEach(row -> row.put("alias_list_id", 1L));
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
    void sharedP25SystemAliasIsBlankWhenAssignedListsDisagree() throws Exception
    {
        Path database = mTemporaryFolder.resolve("shared-system-alias.sqlite");
        createDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            clearFactoryAliasLists(statement);
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family)
                VALUES (1, 'North', 'P25'), (2, 'South', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(
                    id, alias_list_id, name, description, group_name, color, matcher_type, protocol, value
                ) VALUES
                    (1, 1, 'Dispatch', 'Shared dispatch', 'Operations', 123,
                        'TALKGROUP', 'APCO25', 700),
                    (2, 2, 'Dispatch', 'Shared dispatch', 'Operations', 123,
                        'TALKGROUP', 'APCO25', 700)
                """);
            insertP25Channel(statement, 77, P25_CONFIGURATION_ID, P25_RADIORESOLVE_ID, 1);
            insertP25Channel(statement, 78, SECOND_P25_CONFIGURATION_ID, SECOND_P25_RADIORESOLVE_ID, 2);

            StatsAliasResolver resolver = new StatsAliasResolver();
            Map<String,Object> unambiguous = p25Row();
            unambiguous.put("talkgroup_id", 700);
            resolver.enrichTalkgroups(connection, rows(unambiguous));
            assertEquals("Dispatch", unambiguous.get("alias_name"));

            statement.executeUpdate("UPDATE alias SET name='South Dispatch' WHERE id=2");
            Map<String,Object> conflicting = p25Row();
            conflicting.put("talkgroup_id", 700);
            resolver.enrichTalkgroups(connection, rows(conflicting));
            assertNull(conflicting.get("alias_name"),
                "system-level views must not choose an arbitrary Alias List when labels conflict");

            Map<String,Object> north = observedP25Row(700);
            north.put("alias_list_id", 1L);
            Map<String,Object> south = observedP25Row(700);
            south.put("alias_list_id", 2L);
            resolver.resolveObservedGroupIdentities(connection, rows(north, south));
            assertEquals("Dispatch", north.get("matched_alias_name"));
            assertEquals("South Dispatch", south.get("matched_alias_name"),
                "channel-level views continue to use that channel's exact Alias List");

            statement.executeUpdate("DELETE FROM alias WHERE id=2");
            Map<String,Object> missing = p25Row();
            missing.put("talkgroup_id", 700);
            resolver.enrichTalkgroups(connection, rows(missing));
            assertNull(missing.get("alias_name"),
                "system-level views require the identity to resolve in every assigned Alias List");
        }
    }

    @Test
    void sharedDmrAndNxdnSystemsRequireAliasAgreementAcrossAssignedLists() throws Exception
    {
        Path database = mTemporaryFolder.resolve("shared-native-system-alias.sqlite");
        createDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            clearFactoryAliasLists(statement);
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family)
                VALUES (1, 'DMR North', 'DMR'), (2, 'DMR South', 'DMR'),
                       (3, 'NXDN North', 'NXDN'), (4, 'NXDN South', 'NXDN')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(
                    id, alias_list_id, name, description, group_name, color, matcher_type, protocol, value
                ) VALUES
                    (1, 1, 'Dispatch', 'Shared dispatch', 'Operations', 123,
                        'TALKGROUP', 'DMR', 91),
                    (2, 2, 'Dispatch', 'Shared dispatch', 'Operations', 123,
                        'TALKGROUP', 'DMR', 91),
                    (3, 3, 'Dispatch', 'Shared dispatch', 'Operations', 123,
                        'TALKGROUP', 'NXDN', 91),
                    (4, 4, 'Dispatch', 'Shared dispatch', 'Operations', 123,
                        'TALKGROUP', 'NXDN', 91)
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                    auto_start, decoder_type, address_domain_code, primary_frequency_hz, config_json
                ) VALUES
                    ('30000000-0000-4000-8000-000000000001', 'TRUNKED', 1, 'DMR', 'North', 'North', 1,
                        0, 'DMR', 0, 451000000, '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                    ('30000000-0000-4000-8000-000000000002', 'TRUNKED', 2, 'DMR', 'South', 'South', 2,
                        0, 'DMR', 0, 452000000, '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                    ('40000000-0000-4000-8000-000000000001', 'TRUNKED', 3, 'NXDN', 'North', 'North', 3,
                        0, 'NXDN', 1, 153000000, '{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                    ('40000000-0000-4000-8000-000000000002', 'TRUNKED', 4, 'NXDN', 'South', 'South', 4,
                        0, 'NXDN', 1, 154000000, '{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system(
                    id, system_key, protocol_code, address_domain_code,
                    dmr_model_code, dmr_network_id, nxdn_location_category_code, nxdn_system_id,
                    first_seen_ms, last_seen_ms
                ) VALUES
                    (81, 'dmr:tier3:small:42', 3, 0, 2, 42, NULL, NULL, 1, 2),
                    (82, 'nxdn-c:local:303', 4, 1, NULL, NULL, 3, 303, 1, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(
                    id, configuration_id, first_seen_ms, last_seen_ms,
                    radio_system_id, radio_system_assigned_at_ms
                ) VALUES
                    (81, '30000000-0000-4000-8000-000000000001', 1, 2, 81, 1),
                    (82, '30000000-0000-4000-8000-000000000002', 1, 2, 81, 1),
                    (83, '40000000-0000-4000-8000-000000000001', 1, 2, 82, 1),
                    (84, '40000000-0000-4000-8000-000000000002', 1, 2, 82, 1)
                """);

            StatsAliasResolver resolver = new StatsAliasResolver();
            Map<String,Object> dmr = canonicalNativeRow("dmr:tier3:small:42", 3, 91);
            Map<String,Object> nxdn = canonicalNativeRow("nxdn-c:local:303", 4, 91);
            resolver.enrichCanonicalSystemTalkgroups(connection, rows(dmr, nxdn),
                "identity_summary_id", "identity_id", "alias_");
            assertEquals("Dispatch", dmr.get("alias_name"));
            assertEquals("Dispatch", nxdn.get("alias_name"));

            statement.executeUpdate("UPDATE alias SET name='South Dispatch' WHERE id IN (2, 4)");
            Map<String,Object> conflictingDmr = canonicalNativeRow("dmr:tier3:small:42", 3, 91);
            Map<String,Object> conflictingNxdn = canonicalNativeRow("nxdn-c:local:303", 4, 91);
            resolver.enrichCanonicalSystemTalkgroups(connection, rows(conflictingDmr, conflictingNxdn),
                "identity_summary_id", "identity_id", "alias_");
            assertNull(conflictingDmr.get("alias_name"));
            assertNull(conflictingNxdn.get("alias_name"));
        }
    }

    @Test
    void p25AliasesUseEachChannelsObservedLocalAddressAndWithholdAmbiguousSystemMetrics() throws Exception
    {
        Path database = mTemporaryFolder.resolve("p25-local-alias-evidence.sqlite");
        createDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            clearFactoryAliasLists(statement);
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family)
                VALUES (1, 'North', 'P25'), (2, 'South', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (1, 1, 'North Unit', 'RADIO_ID', 'APCO25', 100),
                       (2, 2, 'South Unit', 'RADIO_ID', 'APCO25', 200),
                       (3, 1, 'North Dispatch', 'TALKGROUP', 'APCO25', 300),
                       (4, 2, 'South Dispatch', 'TALKGROUP', 'APCO25', 300)
                """);
            insertP25Channel(statement, 77, P25_CONFIGURATION_ID, P25_RADIORESOLVE_ID, 1);
            insertP25Channel(statement, 78, SECOND_P25_CONFIGURATION_ID, SECOND_P25_RADIORESOLVE_ID, 2);
            statement.executeUpdate("""
                INSERT INTO p25_learned_site(
                    learned_site_id, radio_system_id, rfss, site, first_seen_ms, last_seen_ms
                ) VALUES (701, 77, 1, 1, 1, 2), (702, 77, 1, 2, 1, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(
                    id, radio_system_id, identity_kind_code, home_wacn, home_system_id,
                    identity_id, first_seen_ms, last_seen_ms
                ) VALUES (7001, 77, 2, 0xABCDE, 0x123, 9000001, 1, 2),
                         (7002, 77, 1, 0xABCDE, 0x123, 5000, 1, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_call_identity_bucket(
                    radio_system_id, learned_site_id, channel_id, bucket_start_ms, identity_role_code,
                    identity_summary_id, observed_local_id, last_observed_at_ms,
                    observed_call_count, encrypted_observed_call_count
                ) VALUES (77, 701, 77, 0, 2, 7001, 100, 1, 1, 0),
                         (77, 702, 78, 0, 2, 7001, 200, 1, 1, 0),
                         (77, 701, 77, 0, 1, 7002, 300, 1, 1, 0),
                         (77, 702, 78, 0, 1, 7002, 300, 1, 1, 0)
                """);

            StatsAliasResolver resolver = new StatsAliasResolver();
            Map<String,Object> north = activityRow("APCO25", 1, 1, 100, 300, 1);
            Map<String,Object> south = activityRow("APCO25", 1, 2, 200, 300, 1);
            resolver.enrichActivity(connection, rows(north, south));
            assertEquals("North Unit", north.get("source_alias_name"));
            assertEquals("North Dispatch", north.get("target_alias_name"));
            assertEquals("South Unit", south.get("source_alias_name"));
            assertEquals("South Dispatch", south.get("target_alias_name"));

            Map<String,Object> roamingRadio = canonicalEvidenceRow(7001, 2, 9_000_001);
            Map<String,Object> sameNumberInTwoLists = canonicalEvidenceRow(7002, 1, 5_000);
            resolver.resolveEvidenceAliases(connection, rows(roamingRadio, sameNumberInTwoLists));
            assertNull(roamingRadio.get("resolved_alias_id"),
                "system totals must not be assigned when channel-local addresses resolve to different Aliases");
            assertNull(sameNumberInTwoLists.get("resolved_alias_id"),
                "equal numbers in different Alias Lists must not make either Alias own system totals");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aliasCatalogUsesCurrentChannelAndRadioSystemKeys() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-alias-catalog.sqlite");
        createDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            clearFactoryAliasLists(statement);
            statement.executeUpdate("INSERT INTO alias_list(id, name, family) VALUES (1, 'County', 'P25')");
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (1, 1, 'Dispatch', 'TALKGROUP', 'APCO25', 700)
                """);
            insertP25Channel(statement, 77, P25_CONFIGURATION_ID, P25_RADIORESOLVE_ID, 1);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(
                    radio_system_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms
                ) VALUES (77, 1, 700, 1, 2)
                """);
            statement.executeUpdate("UPDATE alias_list SET name='Renamed County' WHERE id=1");

            Map<String,Object> response = new StatsAliasCatalog(new StatsAliasResolver()).alias(connection, 1);
            Map<String,Object> alias = (Map<String,Object>)response.get("alias");
            List<Map<String,Object>> breakdown = (List<Map<String,Object>>)response.get("breakdown");
            Map<String,Object> source = breakdown.getFirst();

            assertEquals(1L, ((Number)alias.get("coverage_source_count")).longValue());
            assertEquals(1L, ((Number)alias.get("observed_source_count")).longValue());
            assertEquals("Renamed County", alias.get("alias_list_name"));
            assertEquals(77L, ((Number)source.get("radio_system_id")).longValue());
            assertEquals(P25_SYSTEM_KEY, source.get("radio_system_key"));
            assertEquals("Metro", source.get("source_label"));
            assertNull(source.get("channel_id"), "shared P25 systems do not select one arbitrary channel owner");
            assertFalse(source.containsKey("scope_key"));
            assertFalse(source.containsKey("scope_label"));
        }
    }

    @Test
    void loadsOnlyRulesForTheBoundedPageIdentityAndAliasList() throws Exception
    {
        Path database = mTemporaryFolder.resolve("bounded-alias-rules.sqlite");
        createDatabase(database);

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
            irrelevant.put("alias_list_id", 2L);
            resolver.resolveObservedGroupIdentities(connection, rows(selected, irrelevant));

            assertEquals("exact", selected.get("match_kind"));
            assertEquals("Selected 42", selected.get("matched_alias_name"));
            assertEquals("exact", irrelevant.get("match_kind"));
            assertEquals("Irrelevant 99", irrelevant.get("matched_alias_name"));
        }
    }

    @Test
    void systemAliasListPairBudgetIsSharedAcrossSystemsAndCountsRepeatedListIds()
    {
        StatsAliasResolver.AliasListPairBudget budget = new StatsAliasResolver.AliasListPairBudget(2);
        budget.add("p25:bee00:001", 1);
        budget.add("p25:bee00:002", 1);
        assertEquals(1, budget.queryLimit());

        StatsApiException overflow = assertThrows(StatsApiException.class,
            () -> budget.add("p25:bee00:003", 1));
        assertEquals(413, overflow.status());
        assertEquals("response_too_large", overflow.code());
    }

    @Test
    void assignedAliasListLookupSurvivesListRenameBecauseIdentityUsesId() throws Exception
    {
        Path database = mTemporaryFolder.resolve("alias-list-rename.sqlite");
        createDatabase(database);

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
            Map<String,Object> identity = row(1, 42);

            statement.executeUpdate("UPDATE alias_list SET name='Renamed List' WHERE id=1");

            new StatsAliasResolver().enrichDmrTalkgroups(connection, rows(identity),
                "identity_id", "alias_");

            assertEquals("Case Dispatch", identity.get("alias_name"));
            assertEquals(1L, ((Number)identity.get("alias_list_id")).longValue());
            assertEquals("Renamed List", identity.get("alias_list_name"));
        }
    }

    @Test
    void rejectsUnboundedEnrichmentInputBeforeQueryingAliases() throws Exception
    {
        Path database = mTemporaryFolder.resolve("bounded-alias-input.sqlite");
        createDatabase(database);
        List<Map<String,Object>> rows = new ArrayList<>();

        for(int x = 0; x <= StatsAliasResolver.MAX_INPUT_ROWS; x++)
        {
            rows.add(row(1, x + 1));
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

    private static Map<String,Object> row(long aliasListId, int identityId)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("alias_list_id", aliasListId);
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
        row.put("radio_system_key", P25_SYSTEM_KEY);
        return row;
    }

    private static Map<String,Object> observedP25Row(int groupIdentity)
    {
        Map<String,Object> row = p25Row();
        row.put("topology", "TRUNKED");
        row.put("alias_list_id", 1L);
        row.put("group_identity_id", groupIdentity);
        row.put("protocol_code", 1);
        return row;
    }

    private static Map<String,Object> canonicalEvidenceRow(long summaryId, int identityKind, int identityId)
    {
        Map<String,Object> row = p25Row();
        row.put("protocol_code", 1);
        row.put("topology", "TRUNKED");
        row.put("identity_summary_id", summaryId);
        row.put("identity_kind_code", identityKind);
        row.put("identity_id", identityId);
        return row;
    }

    private static Map<String,Object> canonicalNativeRow(String radioSystemKey, int protocolCode, int identityId)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("radio_system_key", radioSystemKey);
        row.put("protocol_code", protocolCode);
        row.put("identity_id", identityId);
        return row;
    }

    private static Map<String,Object> activityRow(String protocol, int channelKind, long aliasListId,
                                                  int source, int target, int targetKind)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("protocol", protocol);
        row.put("channel_kind_code", channelKind);
        row.put("alias_list_id", aliasListId);
        row.put("source_radio_id", source);
        row.put("target_id", target);
        row.put("target_kind_code", targetKind);
        return row;
    }

    private static void createDatabase(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
        }
    }

    private static void insertP25Channel(Statement statement, long receiverChannelId, String configurationId,
                                         String radioResolveId, long aliasListId) throws Exception
    {
        statement.executeUpdate("""
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json
            ) VALUES ('%s', 'TRUNKED', 0, 'Metro', 'Downtown', 'Control', %d,
                '%s', 0, 'P25_PHASE1', 851000000, '{}')
            """.formatted(configurationId, aliasListId, radioResolveId));
        statement.executeUpdate("""
            INSERT OR IGNORE INTO radio_system(
                id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                first_seen_ms, last_seen_ms
            ) VALUES (77, '%s', 1, 0, 0xBEE00, 0x348, 1, 2)
            """.formatted(P25_SYSTEM_KEY));
        statement.executeUpdate("""
            INSERT INTO receiver_channel(
                id, configuration_id, first_seen_ms, last_seen_ms, radio_system_id,
                radio_system_assigned_at_ms
            ) VALUES (%d, '%s', 1, 2, 77, 1)
            """.formatted(receiverChannelId, configurationId));
    }
}
