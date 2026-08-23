/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebDatabaseTest
{
    private static final int WACN = 0xBEE00;
    private static final int SYSTEM = 0x348;
    private static final int SECOND_SYSTEM = 0x49F;
    private static final String GUID = "test-site-guid";

    @TempDir
    Path mTemporaryFolder;
    private Path mDatabasePath;
    private StatsWebDatabase mDatabase;

    @Test
    void formatsKnownAndUnknownMfids()
    {
        assertEquals("Motorola (0x90)", StatsWebDatabase.mfidDisplay(0x90));
        assertEquals("0xAB", StatsWebDatabase.mfidDisplay(0xAB));
    }

    @BeforeEach
    void setUp() throws Exception
    {
        mDatabasePath = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(mDatabasePath);
        seed(mDatabasePath);
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
    }

    @Test
    void exposesSystemEntitiesWithAliasesAndLinks()
    {
        Map<String,Object> system = mDatabase.system(request(
            "/api/system?scope=p25:BEE00:348"));
        assertEquals(1L, number(map(system, "system").get("sites")));

        Map<String,Object> talkgroups = mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&limit=1"));
        Map<String,Object> talkgroup = rows(talkgroups).get(0);
        assertEquals("Dispatch", talkgroup.get("alias_name"));
        assertEquals(56132L, number(talkgroup.get("talkgroup_id")));
        assertEquals(0, number(talkgroup.get("recorded_count")));
        assertEquals(0, number(talkgroup.get("streamed_count")));
        assertFalse(talkgroup.containsKey("grant_count"));
        assertFalse((Boolean)talkgroups.get("hasMore"));
        assertEquals(1L, number(talkgroups.get("totalCount")));

        Map<String,Object> radios = mDatabase.systemRadios(request(
            "/api/system/radios?scope=p25:BEE00:348"));
        Map<String,Object> radio = rows(radios).get(0);
        assertEquals("Engine 1", radio.get("alias_name"));
        assertEquals(56132L, number(radio.get("affiliated_talkgroup_id")));
        assertEquals("Dispatch", radio.get("affiliated_talkgroup_alias_name"));
        assertEquals(1L, number(radios.get("totalCount")));

        Map<String,Object> talkerAliases = mDatabase.systemTalkerAliases(request(
            "/api/system/talker-aliases?scope=p25:BEE00:348"));
        Map<String,Object> talkerAlias = rows(talkerAliases).get(0);
        assertEquals(1811332L, number(talkerAlias.get("radio_id")));
        assertEquals("CAR 201", talkerAlias.get("last_talker_alias"));
        assertEquals("Engine 1", talkerAlias.get("alias_name"));
        assertEquals("Dispatch", talkerAlias.get("talkgroup_alias_name"));
        assertEquals(1L, number(talkerAliases.get("totalCount")));

        Map<String,Object> relationships = mDatabase.radioTalkgroupRelationships(request(
            "/api/relationships?scope=p25:BEE00:348&radio_id=1811332"));
        assertEquals("Dispatch", rows(relationships).get(0).get("talkgroup_alias_name"));

        StatsApiException unbounded = assertThrows(StatsApiException.class, () ->
            mDatabase.radioTalkgroupRelationships(request(
                "/api/relationships?scope=p25:BEE00:348")));
        assertEquals(400, unbounded.status());
    }

    @Test
    void embedsOnlyTheBoundedSitePreviewForRequestedSystemPages() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            for(int index = 0; index < StatsWebDatabase.MAXIMUM_SYSTEM_DIRECTORY_SITE_PREVIEW; index++)
            {
                seedP25Context(connection, 200 + index, "preview-site-" + String.format("%02d", index), 1);
            }
        }

        Map<String,Object> defaultDirectory = mDatabase.systemDirectory(request(
            "/api/v1/systems?limit=1"));
        assertFalse(rows(defaultDirectory).getFirst().containsKey("site_preview"));

        Map<String,Object> directory = mDatabase.systemDirectory(request(
            "/api/v1/systems?include_site_preview=true&limit=1"));
        assertEquals(1, rows(directory).size());
        assertEquals(1L, number(directory.get("limit")));
        assertEquals(0L, number(directory.get("offset")));
        assertEquals(StatsWebDatabase.MAXIMUM_SYSTEM_DIRECTORY_SITE_PREVIEW,
            number(directory.get("sitePreviewLimitPerSystem")));

        Map<String,Object> system = rows(directory).getFirst();
        List<Map<String,Object>> preview = rowsFrom(system, "site_preview");
        assertEquals(StatsWebDatabase.MAXIMUM_SYSTEM_DIRECTORY_SITE_PREVIEW, preview.size());
        assertEquals(Boolean.TRUE, system.get("site_preview_truncated"));
        assertEquals("preview-site-00", preview.getFirst().get("guid"));
        assertEquals("preview-site-24", preview.getLast().get("guid"));
        assertTrue(preview.stream().allMatch(site ->
            number(site.get("scope_id")) == number(system.get("scope_id"))));

        Map<String,Object> searched = mDatabase.systemDirectory(request(
            "/api/v1/systems?include_site_preview=true&limit=1&q=preview-site-24"));
        assertEquals("preview-site-24", rowsFrom(rows(searched).getFirst(), "site_preview").getFirst().get("guid"));

        Map<String,Object> searchedTruncatedSite = mDatabase.systemDirectory(request(
            "/api/v1/systems?include_site_preview=true&limit=1&q=" + GUID));
        List<Map<String,Object>> searchedPreview = rowsFrom(rows(searchedTruncatedSite).getFirst(), "site_preview");
        assertEquals(StatsWebDatabase.MAXIMUM_SYSTEM_DIRECTORY_SITE_PREVIEW, searchedPreview.size());
        assertEquals(GUID, searchedPreview.getFirst().get("guid"));
        assertEquals(Boolean.TRUE, rows(searchedTruncatedSite).getFirst().get("site_preview_truncated"));

        seedSecondSystem(mDatabasePath);
        Map<String,Object> smallDirectory = mDatabase.systemDirectory(request(
            "/api/v1/systems?include_site_preview=true&limit=1&q=Second%20Simulcast"));
        Map<String,Object> smallSystem = rows(smallDirectory).getFirst();
        assertEquals(1, rowsFrom(smallSystem, "site_preview").size());
        assertEquals(Boolean.FALSE, smallSystem.get("site_preview_truncated"));

        Map<String,Object> parentPage = mDatabase.systemDirectory(request(
            "/api/v1/systems?include_site_preview=true&limit=1"));
        assertEquals(1, rows(parentPage).size());
        assertEquals(Boolean.TRUE, parentPage.get("hasMore"));
        assertTrue(rowsFrom(rows(parentPage).getFirst(), "site_preview").stream().allMatch(site ->
            number(site.get("scope_id")) == number(rows(parentPage).getFirst().get("scope_id"))));

        StatsApiException oversized = assertThrows(StatsApiException.class, () ->
            mDatabase.systemDirectory(request(
                "/api/v1/systems?include_site_preview=true&limit=26")));
        assertEquals(400, oversized.status());
        assertEquals("limit", oversized.field());
    }

    @Test
    void exposesAuthoritativePresenceAndAppliesBoundedAffiliationAndSiteFilters() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, decoder,
                    first_seen_ms, last_seen_ms, system_key, nac, rfss, site
                ) VALUES (3, 'guidless-p25', NULL, 1, 1, 'Guidless P25', 'P25-1',
                    1500, 2600, 1, 0x49F, 2, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context (
                    scope_id, context_id, first_seen_ms, last_seen_ms
                ) VALUES (1, 3, 1500, 2600)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms
                ) VALUES (1, 2, 1811333, 1500, 2500),
                         (1, 2, 1811334, 1500, 2600)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_talkgroup_summary (
                    scope_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms
                ) VALUES (1, 1811333, 56132, 1, 1500, 2500)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_site_presence (
                    scope_id, radio_id, context_id, evidence_code, confirmed_at_ms
                ) VALUES (1, 1811333, 1, 1, 2500),
                         (1, 1811334, 3, 1, 2600)
                """);
        }

        Map<String,Object> affiliated = mDatabase.systemRadios(request(
            "/api/v1/systems/p25:BEE00:348/radios?scope=p25:BEE00:348&affiliated=true"));
        assertEquals(1L, number(affiliated.get("totalCount")));
        Map<String,Object> affiliatedRadio = rows(affiliated).getFirst();
        assertEquals(1811332L, number(affiliatedRadio.get("radio_id")));
        assertEquals(1L, number(affiliatedRadio.get("currently_affiliated")));
        assertEquals(2000L, number(affiliatedRadio.get("affiliation_confirmed_at_ms")));
        assertFalse(affiliatedRadio.containsKey("affiliation_updated_at_ms"));
        Map<String,Object> affiliatedPresence = map(affiliatedRadio, "presence");
        assertEquals("affiliation", affiliatedPresence.get("evidence"));
        assertEquals(2000L, number(affiliatedPresence.get("confirmed_at_ms")));
        Map<String,Object> affiliatedSite = map(affiliatedPresence, "site");
        assertEquals(GUID, affiliatedSite.get("guid"));
        assertEquals(1L, number(affiliatedSite.get("rfss")));
        assertEquals(1L, number(affiliatedSite.get("site")));

        Map<String,Object> registered = mDatabase.systemRadios(request(
            "/api/v1/systems/p25:BEE00:348/radios?scope=p25:BEE00:348&affiliated=false&site_guid=" + GUID));
        assertEquals(1L, number(registered.get("totalCount")));
        Map<String,Object> registeredRadio = rows(registered).getFirst();
        assertEquals(1811333L, number(registeredRadio.get("radio_id")));
        assertEquals(0L, number(registeredRadio.get("currently_affiliated")));
        assertEquals("registration", map(registeredRadio, "presence").get("evidence"));
        assertEquals(0L, number(mDatabase.systemRadios(request(
            "/api/v1/systems/p25:BEE00:348/radios?scope=p25:BEE00:348&site_guid=missing"))
            .get("totalCount")));
        assertEquals(1811332L, number(rows(mDatabase.systemRadios(request(
            "/api/v1/systems/p25:BEE00:348/radios?scope=p25:BEE00:348&sort=site&direction=asc")))
            .getFirst().get("radio_id")));

        Map<String,Object> relationships = mDatabase.radioTalkgroupRelationships(request(
            "/api/v1/systems/p25:BEE00:348/relationships?scope=p25:BEE00:348&talkgroup_id=56132" +
                "&affiliated=true&site_guid=" + GUID));
        assertEquals(1L, number(relationships.get("totalCount")));
        assertEquals(1L, number(rows(relationships).getFirst().get("currently_affiliated")));
        assertEquals(GUID, map(map(rows(relationships).getFirst(), "presence"), "site").get("guid"));

        Map<String,Object> notAffiliated = mDatabase.radioTalkgroupRelationships(request(
            "/api/v1/systems/p25:BEE00:348/relationships?scope=p25:BEE00:348&talkgroup_id=56132" +
                "&affiliated=false&site_guid=" + GUID));
        assertEquals(1L, number(notAffiliated.get("totalCount")));
        assertEquals(1811333L, number(rows(notAffiliated).getFirst().get("radio_id")));
        assertEquals(1811332L, number(rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/v1/systems/p25:BEE00:348/relationships?scope=p25:BEE00:348&talkgroup_id=56132" +
                "&sort=site&direction=asc"))).getFirst().get("radio_id")));

        Map<String,Object> talkgroup = map(mDatabase.talkgroup(request(
            "/api/v1/systems/p25:BEE00:348/group-identities/talkgroup/56132" +
                "?scope=p25:BEE00:348&talkgroup_id=56132")), "group_identity");
        assertEquals(1L, number(talkgroup.get("affiliated_radios")));
        assertEquals(1L, number(talkgroup.get("affiliated_sites")));
        assertEquals(Boolean.TRUE, map(talkgroup, "capabilities").get("radio_site_presence"));
        assertEquals(1L, number(map(mDatabase.system(request(
            "/api/v1/systems/p25:BEE00:348?scope=p25:BEE00:348")), "system").get("affiliated_radios")));
        assertEquals(1L, number(map(mDatabase.site(request(
            "/api/v1/sites/" + GUID + "?guid=" + GUID)), "site").get("affiliated_radios")));

        Map<String,Object> radioDetail = map(mDatabase.radio(request(
            "/api/v1/systems/p25:BEE00:348/radios/1811333?scope=p25:BEE00:348&radio_id=1811333")),
            "radio");
        assertEquals("registration", map(radioDetail, "presence").get("evidence"));
        assertEquals(Boolean.TRUE, map(radioDetail, "capabilities").get("radio_site_presence"));

        Map<String,Object> guidlessRadio = map(mDatabase.radio(request(
            "/api/v1/systems/p25:BEE00:348/radios/1811334?scope=p25:BEE00:348&radio_id=1811334")),
            "radio");
        Map<String,Object> guidlessSite = map(map(guidlessRadio, "presence"), "site");
        assertNull(guidlessSite.get("guid"));
        assertEquals(2L, number(guidlessSite.get("rfss")));
        assertEquals(3L, number(guidlessSite.get("site")));

        List<CSVRecord> csv = csvRows(mDatabase.csvExport(request(
            "/api/v1/exports/system-radios.csv?dataset=system-radios&scope=p25:BEE00:348" +
                "&affiliated=false&site_guid=" + GUID)));
        assertEquals(List.of("1811333"), csv.stream().map(row -> row.get("radio_id")).toList());
    }

    @Test
    void pagesMoreThanFiveHundredAffiliationsInlineWithoutASecondCollection() throws Exception
    {
        int talkgroupId = 60001;
        int firstRadioId = 2_000_000;
        int affiliationCount = 600;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement();
            PreparedStatement identities = connection.prepareStatement("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms
                ) VALUES (1, 2, ?, 3000, 4000)
                """);
            PreparedStatement relationships = connection.prepareStatement("""
                INSERT INTO trunked_radio_talkgroup_summary (
                    scope_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms
                ) VALUES (1, ?, ?, 1, 3000, 4000)
                """);
            PreparedStatement affiliations = connection.prepareStatement("""
                INSERT INTO trunked_radio_affiliation (
                    scope_id, radio_id, talkgroup_id, confirmed_at_ms
                ) VALUES (1, ?, ?, 4000)
                """);
            PreparedStatement presences = connection.prepareStatement("""
                INSERT INTO trunked_radio_site_presence (
                    scope_id, radio_id, context_id, evidence_code, confirmed_at_ms
                ) VALUES (1, ?, 1, 2, 4000)
                """))
        {
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms
                ) VALUES (1, 1, 60001, 3000, 4000)
                """);

            for(int offset = 0; offset < affiliationCount; offset++)
            {
                int radioId = firstRadioId + offset;
                identities.setInt(1, radioId);
                identities.addBatch();
                relationships.setInt(1, radioId);
                relationships.setInt(2, talkgroupId);
                relationships.addBatch();
                affiliations.setInt(1, radioId);
                affiliations.setInt(2, talkgroupId);
                affiliations.addBatch();
                presences.setInt(1, radioId);
                presences.addBatch();
            }

            identities.executeBatch();
            relationships.executeBatch();
            affiliations.executeBatch();
            presences.executeBatch();
            connection.commit();
        }

        String path = "/api/v1/systems/p25:BEE00:348/relationships?scope=p25:BEE00:348" +
            "&talkgroup_id=" + talkgroupId + "&affiliated=true&site_guid=" + GUID + "&limit=500";
        Map<String,Object> firstPage = mDatabase.radioTalkgroupRelationships(request(path));
        assertEquals(500, rows(firstPage).size());
        assertTrue((Boolean)firstPage.get("hasMore"));
        assertEquals(affiliationCount, number(firstPage.get("totalCount")));
        assertTrue(rows(firstPage).stream().allMatch(row -> number(row.get("currently_affiliated")) == 1 &&
            "affiliation".equals(map(row, "presence").get("evidence")) &&
            GUID.equals(map(map(row, "presence"), "site").get("guid"))));

        Map<String,Object> secondPage = mDatabase.radioTalkgroupRelationships(request(path + "&offset=500"));
        assertEquals(100, rows(secondPage).size());
        assertFalse((Boolean)secondPage.get("hasMore"));
        assertEquals(affiliationCount, number(secondPage.get("totalCount")));
        assertTrue(rows(secondPage).stream().allMatch(row -> number(row.get("currently_affiliated")) == 1 &&
            row.get("presence") instanceof Map));
    }

    @Test
    void exposesConfiguredAliasesWithWinnerOnlySummaryEvidenceAndCsvParity() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, min_value, max_value)
                VALUES (3, 1, 'County Range', 'TALKGROUP_RANGE', 'APCO25', 56000, 56200)
                """);
            statement.executeUpdate("""
                UPDATE trunked_identity_summary
                SET p25_identity_state_code=2, p25_home_wacn=0xBEE00,
                    p25_home_system_id=0x348, p25_home_talkgroup_id=56132
                WHERE scope_id=1 AND identity_kind_code=1 AND identity_id=56132
                """);
            statement.executeUpdate("""
                UPDATE trunked_radio_talkgroup_summary SET join_count=2
                WHERE scope_id=1 AND radio_id=1811332 AND talkgroup_id=56132
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel(sort_order, name, alias_list_name, decoder_type, config_json)
                VALUES(1, 'Configured P25', 'County', 'P25-1', '{}')
                """);
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(alias_id, channel_name)
                VALUES(1, 'Primary'), (1, 'Archive')
                """);
            statement.executeUpdate("""
                INSERT INTO scan_list(id, sort_order, name, description, published, is_default)
                VALUES(2, 1, 'Cleveland', 'Cleveland-area calls', 1, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_scan_list_membership(alias_id, scan_list_id)
                VALUES(1, 1), (1, 2), (3, 2)
                """);
        }

        Map<String,Object> lists = mDatabase.aliasLists(request("/api/v1/alias-lists"));
        Map<String,Object> county = rows(lists).getFirst();
        assertEquals(1L, number(county.get("alias_list_id")));
        assertEquals(1L, number(county.get("assigned_channel_count")));

        Map<String,Object> response = mDatabase.aliases(request(
            "/api/aliases?list=1&type=talkgroup&sort=call_count&direction=desc"));
        List<Map<String,Object>> aliases = rows(response);
        assertEquals(List.of("Dispatch", "County Range"), aliases.stream().map(row -> row.get("name")).toList());
        Map<String,Object> dispatch = aliases.getFirst();
        assertEquals("observed", dispatch.get("metrics_state"));
        assertEquals(12L, number(dispatch.get("call_count")));
        assertEquals(12L, number(dispatch.get("grant_count")));
        assertEquals(1L, number(dispatch.get("relationship_count")));
        assertEquals(1L, number(dispatch.get("join_relationship_count")));
        assertEquals(1L, number(dispatch.get("current_affiliation_count")));
        assertEquals(List.of("Archive", "Primary"), dispatch.get("broadcast_channels"));
        assertEquals(List.of(1L, 2L), dispatch.get("scan_list_ids"));
        assertEquals(List.of("Default", "Cleveland"), dispatch.get("scan_lists"));
        assertFalse(dispatch.containsKey("priority"));

        Map<String,Object> range = aliases.getLast();
        assertEquals("covered_no_evidence", range.get("metrics_state"));
        assertEquals(0L, number(range.get("call_count")));
        assertEquals(0L, number(range.get("relationship_count")));

        Map<String,Object> detail = mDatabase.alias(request("/api/alias?id=1"));
        assertEquals(1L, number(map(detail, "alias").get("alias_list_id")));
        assertEquals(List.of(1L, 2L), map(detail, "alias").get("scan_list_ids"));
        Map<String,Object> breakdown = rowsFrom(detail, "breakdown").getFirst();
        assertEquals("scope:1", breakdown.get("scope_key"));
        assertEquals("p25:BEE00:348", breakdown.get("scope_label"));
        assertEquals(1L, number(breakdown.get("alias_list_id")));

        List<CSVRecord> csv = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=aliases&list=County&type=talkgroup&sort=call_count&direction=desc")));
        assertEquals(List.of("Dispatch", "County Range"), csv.stream().map(row -> row.get("name")).toList());
        assertEquals("1; 2", csv.getFirst().get("scan_list_ids"));
        assertEquals("Default; Cleveland", csv.getFirst().get("scan_lists"));
        assertFalse(csv.getFirst().isMapped("priority"));
    }

    @Test
    void sortsAndFiltersMetricsAcrossMoreThanOneBoundedAliasBatch() throws Exception
    {
        int aliasCount = 6_627;
        int firstAliasId = 10_000;
        int firstTalkgroup = 20_000;
        int busiestTalkgroup = firstTalkgroup + ((aliasCount - 1) * 2);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement aliases = connection.prepareStatement("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (?, 1, ?, 'TALKGROUP', 'APCO25', ?)
                """))
        {
            connection.setAutoCommit(false);

            for(int offset = 0; offset < aliasCount; offset++)
            {
                int identity = firstTalkgroup + (offset * 2);
                aliases.setInt(1, firstAliasId + offset);
                aliases.setString(2, "Bulk Alias %04d".formatted(offset));
                aliases.setInt(3, identity);
                aliases.addBatch();
            }

            aliases.executeBatch();

            try(PreparedStatement evidence = connection.prepareStatement("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms, call_count
                ) VALUES (1, 1, ?, 3000, 4000, 99)
                """))
            {
                evidence.setInt(1, busiestTalkgroup);
                evidence.executeUpdate();
            }

            connection.commit();
        }

        Map<String,Object> sorted = mDatabase.aliases(request(
            "/api/v1/aliases?list=1&type=talkgroup&q=Bulk&sort=call_count&direction=desc&limit=25"));
        assertEquals("Bulk Alias 6626", rows(sorted).getFirst().get("name"));
        assertEquals(99L, number(rows(sorted).getFirst().get("call_count")));
        assertTrue((Boolean)sorted.get("hasMore"));

        Map<String,Object> filtered = mDatabase.aliases(request(
            "/api/v1/aliases?list=1&type=talkgroup&q=Bulk&use=used&sort=call_count&direction=desc"));
        assertEquals(List.of("Bulk Alias 6626"),
            rows(filtered).stream().map(row -> row.get("name")).toList());
        assertFalse((Boolean)filtered.get("hasMore"));
    }

    @Test
    void sortsAliasCallCountsWithoutMaterializingHighFanoutRelationshipsAndAffiliations() throws Exception
    {
        int fanout = StatsAliasCatalog.MAX_EVIDENCE_ROWS + 1;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement relationships = connection.prepareStatement("""
                INSERT INTO trunked_radio_talkgroup_summary (
                    scope_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, join_count
                ) VALUES (1, ?, 56132, 1, 3000, 4000, 1)
                """);
            PreparedStatement radioRelationships = connection.prepareStatement("""
                INSERT INTO trunked_radio_talkgroup_summary (
                    scope_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, join_count
                ) VALUES (1, 1811332, ?, 1, 3000, 4000, 1)
                """);
            PreparedStatement affiliations = connection.prepareStatement("""
                INSERT INTO trunked_radio_affiliation (scope_id, radio_id, talkgroup_id, confirmed_at_ms)
                VALUES (1, ?, 56132, 4000)
                """))
        {
            connection.setAutoCommit(false);

            for(int offset = 0; offset < fanout; offset++)
            {
                int radioId = 2_000_000 + offset;
                relationships.setInt(1, radioId);
                relationships.addBatch();
                radioRelationships.setInt(1, 100_000 + offset);
                radioRelationships.addBatch();
                affiliations.setInt(1, radioId);
                affiliations.addBatch();
            }

            relationships.executeBatch();
            radioRelationships.executeBatch();
            affiliations.executeBatch();
            connection.commit();
        }

        Map<String,Object> response = mDatabase.aliases(request(
            "/api/v1/aliases?list=1&type=talkgroup&sort=call_count&direction=desc"));
        Map<String,Object> dispatch = rows(response).getFirst();
        assertEquals("Dispatch", dispatch.get("name"));
        assertEquals(12L, number(dispatch.get("call_count")));
        assertEquals(fanout + 1L, number(dispatch.get("relationship_count")));
        assertEquals(fanout, number(dispatch.get("join_relationship_count")));
        assertEquals(fanout + 1L, number(dispatch.get("current_affiliation_count")));
        assertEquals(4000L, number(dispatch.get("last_evidence_ms")));

        Map<String,Object> radioResponse = mDatabase.aliases(request(
            "/api/v1/aliases?list=1&type=radio&sort=call_count&direction=desc"));
        Map<String,Object> engine = rows(radioResponse).getFirst();
        assertEquals("Engine 1", engine.get("name"));
        assertEquals(8L, number(engine.get("call_count")));
        assertEquals(fanout + 1L, number(engine.get("relationship_count")));
        assertEquals(fanout, number(engine.get("join_relationship_count")));
        assertEquals(1L, number(engine.get("current_affiliation_count")));
    }

    @Test
    void sortsLargeAliasListAcrossSeveralBusySystemsWithinPerSourceBounds() throws Exception
    {
        int aliasListId = 700;
        int aliasCount = 450;
        int systemCount = 20;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement();
            PreparedStatement systems = connection.prepareStatement("""
                INSERT INTO p25_system (system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (?, 0xAAA00, ?, 1000, 5000)
                """);
            PreparedStatement contexts = connection.prepareStatement("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name,
                    decoder, first_seen_ms, last_seen_ms, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz
                ) VALUES (?, ?, ?, 1, 1, ?, 'Large P25', 'P25-1', 1000, 5000, ?, 0x123, 1, ?, ?, ?)
                """);
            PreparedStatement sites = connection.prepareStatement("""
                INSERT INTO p25_site_snapshot (
                    guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz
                ) VALUES (?, ?, 1000, 5000, 10, 'APCO25', ?, 'Large P25', 'P25-1', ?, 0x123, 1, ?, ?, ?)
                """);
            PreparedStatement scopes = connection.prepareStatement("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (?, ?, 1, 1, 0, ?, 1000, 5000)
                """);
            PreparedStatement ownership = connection.prepareStatement("""
                INSERT INTO trunked_identity_scope_context (scope_id, context_id, first_seen_ms, last_seen_ms)
                VALUES (?, ?, 1000, 5000)
                """);
            PreparedStatement aliases = connection.prepareStatement("""
                INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (?, ?, ?, 'TALKGROUP', 'APCO25', ?)
                """);
            PreparedStatement identities = connection.prepareStatement("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    call_count, target_call_count, grant_count
                ) VALUES (?, 1, ?, 3000, ?, ?, ?, ?)
                """);
            PreparedStatement relationships = connection.prepareStatement("""
                INSERT INTO trunked_radio_talkgroup_summary (
                    scope_id, radio_id, talkgroup_id, target_kind_code,
                    first_seen_ms, last_seen_ms, join_count
                ) VALUES (?, ?, ?, 1, 3000, ?, 1)
                """);
            PreparedStatement affiliations = connection.prepareStatement("""
                INSERT INTO trunked_radio_affiliation (scope_id, radio_id, talkgroup_id, confirmed_at_ms)
                VALUES (?, ?, ?, ?)
                """))
        {
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO alias_list (id, name, family) VALUES (700, 'Large P25', 'P25')");

            for(int systemIndex = 0; systemIndex < systemCount; systemIndex++)
            {
                int key = 700 + systemIndex;
                int systemId = 0x100 + systemIndex;
                String guid = "large-p25-" + systemIndex;
                String channel = "Large P25 Site " + systemIndex;
                long frequency = 851_000_000L + systemIndex * 1_000_000L;

                systems.setInt(1, key);
                systems.setInt(2, systemId);
                systems.addBatch();

                contexts.setInt(1, key);
                contexts.setString(2, "large-p25-context-" + systemIndex);
                contexts.setString(3, guid);
                contexts.setString(4, channel);
                contexts.setInt(5, key);
                contexts.setInt(6, systemIndex + 1);
                contexts.setLong(7, frequency);
                contexts.setLong(8, frequency);
                contexts.addBatch();

                sites.setString(1, guid);
                sites.setString(2, "large-p25-hash-" + systemIndex);
                sites.setString(3, channel);
                sites.setInt(4, key);
                sites.setInt(5, systemIndex + 1);
                sites.setLong(6, frequency);
                sites.setLong(7, frequency);
                sites.addBatch();

                scopes.setInt(1, key);
                scopes.setString(2, "p25:AAA00:" + Integer.toHexString(systemId).toUpperCase());
                scopes.setInt(3, key);
                scopes.addBatch();

                ownership.setInt(1, key);
                ownership.setInt(2, key);
                ownership.addBatch();
            }

            systems.executeBatch();
            contexts.executeBatch();
            sites.executeBatch();
            scopes.executeBatch();
            ownership.executeBatch();

            for(int aliasIndex = 0; aliasIndex < aliasCount; aliasIndex++)
            {
                int aliasId = 700_000 + aliasIndex;
                int talkgroupId = 10_000 + aliasIndex;
                aliases.setInt(1, aliasId);
                aliases.setInt(2, aliasListId);
                aliases.setString(3, "Large Talkgroup " + aliasIndex);
                aliases.setInt(4, talkgroupId);
                aliases.addBatch();

                for(int systemIndex = 0; systemIndex < systemCount; systemIndex++)
                {
                    int scopeId = 700 + systemIndex;
                    int radioId = 4_000_000 + systemIndex * aliasCount + aliasIndex;
                    long lastSeen = 4000L + systemIndex;
                    int calls = aliasIndex == aliasCount - 1 ? 2 : 1;

                    identities.setInt(1, scopeId);
                    identities.setInt(2, talkgroupId);
                    identities.setLong(3, lastSeen);
                    identities.setInt(4, calls);
                    identities.setInt(5, calls);
                    identities.setInt(6, calls);
                    identities.addBatch();

                    relationships.setInt(1, scopeId);
                    relationships.setInt(2, radioId);
                    relationships.setInt(3, talkgroupId);
                    relationships.setLong(4, lastSeen);
                    relationships.addBatch();

                    affiliations.setInt(1, scopeId);
                    affiliations.setInt(2, radioId);
                    affiliations.setInt(3, talkgroupId);
                    affiliations.setLong(4, lastSeen);
                    affiliations.addBatch();

                    if(aliasIndex == aliasCount - 1)
                    {
                        int secondRadioId = 5_000_000 + systemIndex;
                        relationships.setInt(1, scopeId);
                        relationships.setInt(2, secondRadioId);
                        relationships.setInt(3, talkgroupId);
                        relationships.setLong(4, lastSeen);
                        relationships.addBatch();

                        affiliations.setInt(1, scopeId);
                        affiliations.setInt(2, secondRadioId);
                        affiliations.setInt(3, talkgroupId);
                        affiliations.setLong(4, lastSeen);
                        affiliations.addBatch();
                    }
                }
            }

            aliases.executeBatch();
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, matcher_type, protocol, min_value, max_value
                ) VALUES (800000, 700, 'Large Range', 'TALKGROUP_RANGE', 'APCO25', 20000, 20049)
                """);

            for(int systemIndex = 0; systemIndex < systemCount; systemIndex++)
            {
                int scopeId = 700 + systemIndex;
                long lastSeen = 4000L + systemIndex;

                for(int rangeOffset = 0; rangeOffset < 50; rangeOffset++)
                {
                    int talkgroupId = 20_000 + rangeOffset;
                    int radioId = 6_000_000 + systemIndex * 50 + rangeOffset;

                    identities.setInt(1, scopeId);
                    identities.setInt(2, talkgroupId);
                    identities.setLong(3, lastSeen);
                    identities.setInt(4, 1);
                    identities.setInt(5, 1);
                    identities.setInt(6, 1);
                    identities.addBatch();

                    relationships.setInt(1, scopeId);
                    relationships.setInt(2, radioId);
                    relationships.setInt(3, talkgroupId);
                    relationships.setLong(4, lastSeen);
                    relationships.addBatch();

                    affiliations.setInt(1, scopeId);
                    affiliations.setInt(2, radioId);
                    affiliations.setInt(3, talkgroupId);
                    affiliations.setLong(4, lastSeen);
                    affiliations.addBatch();
                }
            }

            identities.executeBatch();
            relationships.executeBatch();
            affiliations.executeBatch();
            connection.commit();
        }

        Map<String,Object> response = mDatabase.aliases(request(
            "/api/v1/aliases?list=700&type=talkgroup&sort=call_count&direction=desc"));
        Map<String,Object> busiest = rows(response).getFirst();
        assertEquals("Large Range", busiest.get("name"));
        assertEquals(1000L, number(busiest.get("call_count")));
        assertEquals(1000L, number(busiest.get("relationship_count")));
        assertEquals(1000L, number(busiest.get("join_relationship_count")));
        assertEquals(1000L, number(busiest.get("current_affiliation_count")));
        assertEquals(20L, number(busiest.get("coverage_scope_count")));
        assertEquals(20L, number(busiest.get("observed_scope_count")));
        assertEquals(4019L, number(busiest.get("last_evidence_ms")));
        assertTrue((Boolean)response.get("hasMore"));

        Map<String,Object> busiestExact = rows(response).stream()
            .filter(row -> "Large Talkgroup 449".equals(row.get("name")))
            .findFirst().orElseThrow();
        assertEquals(40L, number(busiestExact.get("call_count")));
        assertEquals(40L, number(busiestExact.get("relationship_count")));
        assertEquals(40L, number(busiestExact.get("current_affiliation_count")));

        for(String sort: List.of("relationship_count", "join_relationship_count", "current_affiliation_count"))
        {
            Map<String,Object> sorted = mDatabase.aliases(request(
                "/api/v1/aliases?list=700&type=talkgroup&sort=" + sort + "&direction=desc"));
            assertEquals("Large Range", rows(sorted).getFirst().get("name"));
        }

        Map<String,Object> filtered = mDatabase.aliases(request(
            "/api/v1/aliases?list=700&type=talkgroup&evidence=observed&use=used" +
                "&last_activity_after=4019&sort=call_count&direction=desc"));
        assertEquals("Large Range", rows(filtered).getFirst().get("name"));
    }

    @Test
    void rejectsOversizedAliasBroadcastRouteShapesBeforeReadingChildText() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO alias_broadcast_channel(alias_id, channel_name) VALUES(1, ?)
                """))
        {
            for(int index = 0; index <= StatsAliasCatalog.MAX_BROADCAST_CHANNELS_PER_ALIAS; index++)
            {
                insert.setString(1, "Route " + index);
                insert.addBatch();
            }

            insert.executeBatch();
        }

        StatsApiException fanout = assertThrows(StatsApiException.class,
            () -> mDatabase.alias(request("/api/v1/aliases/1?id=1")));
        assertEquals(413, fanout.status());
        assertEquals("alias_routes_too_large", fanout.code());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM alias_broadcast_channel WHERE alias_id=1");
            statement.executeUpdate("INSERT INTO alias_broadcast_channel(alias_id, channel_name) VALUES(1, '" +
                "x".repeat(StatsAliasCatalog.MAX_BROADCAST_CHANNEL_NAME_CHARACTERS + 1) + "')");
        }

        StatsApiException name = assertThrows(StatsApiException.class,
            () -> mDatabase.alias(request("/api/v1/aliases/1?id=1")));
        assertEquals(413, name.status());
        assertEquals("alias_routes_too_large", name.code());
    }

    @Test
    void aliasEnrichmentReadsOnlyEvidenceThatCanMatchTheRequestedPage() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    call_count, target_call_count, grant_count
                ) VALUES (1, 1, ?, 1000, 2000, 1, 1, 1)
                """))
        {
            connection.setAutoCommit(false);

            for(int identifier = 100_000; identifier < 112_000; identifier++)
            {
                insert.setInt(1, identifier);
                insert.addBatch();
            }

            insert.executeBatch();
            connection.commit();
        }

        List<Map<String,Object>> aliases = rows(mDatabase.aliases(request(
            "/api/v1/aliases?list=1&q=Dispatch&limit=1")));
        assertEquals(1, aliases.size());
        assertEquals("Dispatch", aliases.getFirst().get("name"));
        assertEquals(12L, number(aliases.getFirst().get("call_count")));
    }

    @Test
    void aliasEvidenceKeepsEachIdentityRangeCorrelatedWithItsAssignedList() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            seedContextScope(connection, 500, 500, "cross-list-north", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 501, 501, "cross-list-south", TrunkedSiteSchema.PROTOCOL_DMR, 0);

            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("""
                    UPDATE receiver_context SET alias_list_name = CASE id
                        WHEN 500 THEN 'Cross North'
                        WHEN 501 THEN 'Cross South'
                    END WHERE id IN (500, 501)
                    """);
                statement.executeUpdate("""
                    INSERT INTO alias_list(id, name, family)
                    VALUES (500, 'Cross North', 'DMR'), (501, 'Cross South', 'DMR')
                    """);
                statement.executeUpdate("""
                    INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, min_value, max_value)
                    VALUES (500, 500, 'Cross North Range', 'TALKGROUP_RANGE', 'DMR', 100000, 105000),
                           (501, 501, 'Cross South Range', 'TALKGROUP_RANGE', 'DMR', 200000, 205000)
                    """);
            }

            connection.setAutoCommit(false);

            try(PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    call_count, target_call_count, grant_count
                ) VALUES (?, 1, ?, 1000, 2000, 1, 1, 1)
                """))
            {
                for(int offset = 0; offset <= 5_000; offset++)
                {
                    insert.setLong(1, 500);
                    insert.setInt(2, 200_000 + offset);
                    insert.addBatch();
                    insert.setLong(1, 501);
                    insert.setInt(2, 100_000 + offset);
                    insert.addBatch();
                }

                insert.executeBatch();
            }

            connection.commit();
        }

        List<Map<String,Object>> aliases = rows(mDatabase.aliases(request(
            "/api/v1/aliases?q=Cross&sort=name&limit=10")));
        assertEquals(List.of("Cross North Range", "Cross South Range"),
            aliases.stream().map(row -> row.get("name")).toList());
        assertTrue(aliases.stream().allMatch(row -> "covered_no_evidence".equals(row.get("metrics_state"))));
        assertTrue(aliases.stream().allMatch(row -> number(row.get("call_count")) == 0));
    }

    @Test
    void aliasEvidenceProjectsSharedScopesOnlyToMatchingAliasLists() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            seedContextScope(connection, 600, 600, "shared-projection-0", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            connection.setAutoCommit(false);

            try(PreparedStatement context = connection.prepareStatement("""
                    INSERT INTO receiver_context (
                        id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name,
                        decoder, first_seen_ms, last_seen_ms
                    ) VALUES (?, ?, ?, 1, 3, ?, ?, 'DMR', 1000, 3000)
                    """);
                PreparedStatement ownership = connection.prepareStatement("""
                    INSERT INTO trunked_identity_scope_context (scope_id, context_id, first_seen_ms, last_seen_ms)
                    VALUES (600, ?, 1000, 3000)
                    """);
                PreparedStatement list = connection.prepareStatement("""
                    INSERT INTO alias_list(id, name, family) VALUES (?, ?, 'DMR')
                    """);
                PreparedStatement alias = connection.prepareStatement("""
                    INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, min_value, max_value)
                    VALUES (?, ?, ?, 'TALKGROUP_RANGE', 'DMR', ?, ?)
                    """))
            {
                for(int index = 0; index < 21; index++)
                {
                    int id = 600 + index;
                    String listName = "Shared Projection " + index;
                    list.setInt(1, id);
                    list.setString(2, listName);
                    list.addBatch();
                    alias.setInt(1, id);
                    alias.setInt(2, id);
                    alias.setString(3, listName + " Range");
                    int minimum = index == 0 ? 100_000 : 200_000 + index * 1_000;
                    alias.setInt(4, minimum);
                    alias.setInt(5, minimum + 499);
                    alias.addBatch();

                    if(index == 0)
                    {
                        try(PreparedStatement update = connection.prepareStatement(
                            "UPDATE receiver_context SET alias_list_name=? WHERE id=600"))
                        {
                            update.setString(1, listName);
                            update.executeUpdate();
                        }
                    }
                    else
                    {
                        context.setInt(1, id);
                        context.setString(2, "dmr-shared-projection-" + index);
                        context.setString(3, "shared-projection-" + index);
                        context.setString(4, "DMR Receiver " + index);
                        context.setString(5, listName);
                        context.addBatch();
                        ownership.setInt(1, id);
                        ownership.addBatch();
                    }
                }

                list.executeBatch();
                alias.executeBatch();
                context.executeBatch();
                ownership.executeBatch();
            }

            try(PreparedStatement evidence = connection.prepareStatement("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    call_count, target_call_count, grant_count
                ) VALUES (600, 1, ?, 1000, 2000, 1, 1, 1)
                """))
            {
                for(int identifier = 100_000; identifier < 100_500; identifier++)
                {
                    evidence.setInt(1, identifier);
                    evidence.addBatch();
                }

                evidence.executeBatch();
            }

            connection.commit();
        }

        List<Map<String,Object>> aliases = rows(mDatabase.aliases(request(
            "/api/v1/aliases?q=Shared%20Projection&sort=name&limit=100")));
        assertEquals(21, aliases.size());
        Map<String,Object> observed = aliases.stream()
            .filter(row -> number(row.get("alias_list_id")) == 600)
            .findFirst().orElseThrow();
        assertEquals("observed", observed.get("metrics_state"));
        assertEquals(500L, number(observed.get("call_count")));
        assertTrue(aliases.stream().filter(row -> row != observed)
            .allMatch(row -> "covered_no_evidence".equals(row.get("metrics_state")) &&
                number(row.get("call_count")) == 0));
    }

    @Test
    void trunkedDmrEvidenceIsProjectedIndependentlyForEveryAssignedAliasList() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            seedContextScope(connection, 300, 300, "shared-dmr-a", TrunkedSiteSchema.PROTOCOL_DMR, 0);

            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("""
                    UPDATE receiver_context SET alias_list_name='DMR North' WHERE id=300
                    """);
                statement.executeUpdate("""
                    INSERT INTO receiver_context (
                        id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name,
                        decoder, first_seen_ms, last_seen_ms
                    ) VALUES (301, 'dmr-shared-dmr-b', 'shared-dmr-b', 1, 3, 'DMR Receiver B',
                        'DMR South', 'DMR', 1000, 3000)
                    """);
                statement.executeUpdate("""
                    INSERT INTO trunked_identity_scope_context (
                        scope_id, context_id, first_seen_ms, last_seen_ms
                    ) VALUES (300, 301, 1000, 3000)
                    """);
                statement.executeUpdate("""
                    INSERT INTO alias_list (id, name, family)
                    VALUES (300, 'DMR North', 'DMR'), (301, 'DMR South', 'DMR')
                    """);
                statement.executeUpdate("""
                    INSERT INTO alias (id, alias_list_id, name, matcher_type, protocol, value)
                    VALUES (300, 300, 'North Dispatch', 'TALKGROUP', 'DMR', 150),
                           (301, 301, 'South Dispatch', 'TALKGROUP', 'DMR', 150),
                           (302, 300, 'North Unit', 'RADIO_ID', 'DMR', 900),
                           (303, 301, 'South Unit', 'RADIO_ID', 'DMR', 900)
                    """);
                statement.executeUpdate("""
                    INSERT INTO trunked_identity_summary (
                        scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                        call_count, target_call_count, grant_count, join_count
                    ) VALUES (300, 1, 150, 1000, 3000, 9, 9, 8, 7),
                             (300, 2, 900, 1000, 3000, 9, 0, 8, 7)
                    """);
                statement.executeUpdate("""
                    INSERT INTO trunked_radio_talkgroup_summary (
                        scope_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms,
                        call_count, grant_count
                    ) VALUES (300, 900, 150, 1, 1000, 3000, 9, 8)
                    """);
            }
        }

        Map<String,Object> north = rows(mDatabase.aliases(request(
            "/api/v1/aliases?list=300"))).getFirst();
        Map<String,Object> south = rows(mDatabase.aliases(request(
            "/api/v1/aliases?list=301"))).getFirst();

        for(Map<String,Object> alias: List.of(north, south))
        {
            assertEquals("observed", alias.get("metrics_state"));
            assertEquals(1L, number(alias.get("coverage_scope_count")));
            assertEquals(1L, number(alias.get("observed_scope_count")));
            assertEquals(9L, number(alias.get("call_count")));
            assertEquals(7L, number(alias.get("join_count")));
        }

        String scope = "dmr:guid:shared-dmr-a";
        Map<String,Object> talkgroup = rows(mDatabase.systemTalkgroups(request(
            "/api/v1/systems/talkgroups?scope=" + scope + "&sort=alias"))).getFirst();
        Map<String,Object> radio = rows(mDatabase.systemRadios(request(
            "/api/v1/systems/radios?scope=" + scope + "&sort=alias"))).getFirst();
        Map<String,Object> talkgroupDetail = map(mDatabase.talkgroup(request(
            "/api/v1/group?scope=" + scope + "&talkgroup_id=150")), "group_identity");
        Map<String,Object> radioDetail = map(mDatabase.radio(request(
            "/api/v1/radio?scope=" + scope + "&radio_id=900")), "radio");
        Map<String,Object> relationship = rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/v1/relationships?scope=" + scope + "&radio_id=900&sort=talkgroup_alias"))).getFirst();

        for(Map<String,Object> identity: List.of(talkgroup, radio, talkgroupDetail, radioDetail, relationship))
        {
            assertNull(identity.get("alias_list_name"));
        }

        assertNull(talkgroup.get("alias_name"));
        assertNull(radio.get("alias_name"));
        assertNull(talkgroupDetail.get("alias_name"));
        assertNull(radioDetail.get("alias_name"));
        assertNull(relationship.get("radio_alias_name"));
        assertNull(relationship.get("talkgroup_alias_name"));
    }

    @Test
    void aliasSortingUsesTheSameDuplicateExactAndRangeWinnerAsPresentedRows() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            seedContextScope(connection, 400, 400, "sort-exact", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 401, 401, "sort-range", TrunkedSiteSchema.PROTOCOL_DMR, 0);

            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("""
                    UPDATE receiver_context SET alias_list_name='Sort DMR' WHERE id IN (400, 401)
                    """);
                statement.executeUpdate("""
                    INSERT INTO alias_list (id, name, family) VALUES (400, 'Sort DMR', 'DMR')
                    """);
                statement.executeUpdate("""
                    INSERT INTO alias (
                        id, alias_list_id, name, matcher_type, protocol, value, min_value, max_value
                    ) VALUES
                        (400, 400, 'Alpha Exact Loser', 'TALKGROUP', 'DMR', 100, NULL, NULL),
                        (401, 400, 'Zulu Exact Winner', 'TALKGROUP', 'DMR', 100, NULL, NULL),
                        (402, 400, 'Middle Exact', 'TALKGROUP', 'DMR', 200, NULL, NULL),
                        (403, 400, 'Alpha Bound Loser', 'TALKGROUP_RANGE', 'DMR', NULL, 250, 350),
                        (404, 400, 'Beta Maximum Loser', 'TALKGROUP_RANGE', 'DMR', NULL, 290, 305),
                        (405, 400, 'Gamma Older Tie', 'TALKGROUP_RANGE', 'DMR', NULL, 290, 310),
                        (406, 400, 'Zulu Range Winner', 'TALKGROUP_RANGE', 'DMR', NULL, 290, 310),
                        (407, 400, 'Middle Range Comparator', 'TALKGROUP', 'DMR', 400, NULL, NULL)
                    """);
                statement.executeUpdate("""
                    INSERT INTO trunked_identity_summary (
                        scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                        call_count, target_call_count
                    ) VALUES
                        (400, 1, 100, 1000, 3000, 1, 1),
                        (400, 1, 200, 1000, 3000, 1, 1),
                        (401, 1, 300, 1000, 3000, 1, 1),
                        (401, 1, 400, 1000, 3000, 1, 1)
                    """);
            }
        }

        List<Map<String,Object>> exact = rows(mDatabase.systemTalkgroups(request(
            "/api/v1/systems/talkgroups?scope=dmr:guid:sort-exact&sort=alias&direction=asc")));
        assertEquals(List.of(200L, 100L), exact.stream().map(row -> number(row.get("talkgroup_id"))).toList());
        assertEquals(List.of("Middle Exact", "Zulu Exact Winner"),
            exact.stream().map(row -> row.get("alias_name")).toList());

        List<Map<String,Object>> ranged = rows(mDatabase.systemTalkgroups(request(
            "/api/v1/systems/talkgroups?scope=dmr:guid:sort-range&sort=alias&direction=asc")));
        assertEquals(List.of(400L, 300L), ranged.stream().map(row -> number(row.get("talkgroup_id"))).toList());
        assertEquals(List.of("Middle Range Comparator", "Zulu Range Winner"),
            ranged.stream().map(row -> row.get("alias_name")).toList());
    }

    @Test
    void exposesObservedTalkgroupsIndividuallyWithoutLettingRangesHideDiscovery() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, matcher_type, protocol, min_value, max_value
                ) VALUES (3, 1, 'County Range', 'TALKGROUP_RANGE', 'APCO25', 56000, 56200)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, p25_identity_state_code,
                    first_seen_ms, last_seen_ms,
                    call_count, target_call_count, grant_count, join_count, emergency_count,
                    register_count, logout_count, denial_count, data_count, encrypted_count,
                    recorded_count, streamed_count
                ) VALUES
                    (1, 1, 56180, 1, 2100, 3100, 7, 7, 6, 2, 1, 3, 1, 1, 4, 2, 3, 2),
                    (1, 3, 56190, 1, 2200, 3200, 5, 5, 5, 1, 0, 0, 0, 0, 0, 0, 1, 1),
                    (1, 1, 60000, 1, 2300, 3300, 4, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                """);
        }

        Map<String,Object> firstPage = mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=1&sort=talkgroup&direction=asc&limit=2"));
        List<Map<String,Object>> firstRows = rows(firstPage);
        assertEquals(List.of(56180L, 56190L), firstRows.stream()
            .map(row -> number(row.get("talkgroup_id"))).toList());
        assertTrue((Boolean)firstPage.get("hasMore"));
        assertEquals("range", firstRows.getFirst().get("match_kind"));
        assertEquals("County Range", firstRows.getFirst().get("matched_alias_name"));
        assertEquals(3L, number(firstRows.getFirst().get("matched_alias_id")));
        assertEquals("TRUNKED", firstRows.getFirst().get("topology"));
        assertEquals("P25", firstRows.getFirst().get("protocol"));
        assertEquals(true, firstRows.getFirst().get("promotion_supported"));
        assertEquals("p25:BEE00:348", firstRows.getFirst().get("scope_token"));
        assertEquals(7L, number(firstRows.getFirst().get("call_count")));
        assertEquals(3L, number(firstRows.getFirst().get("recorded_count")));
        assertEquals(2L, number(firstRows.getFirst().get("streamed_count")));
        assertEquals(6L, number(firstRows.getFirst().get("grant_count")));
        assertEquals(2L, number(firstRows.getFirst().get("join_count")));
        assertEquals("Patch Group", firstRows.getLast().get("identity_kind"));

        Map<String,Object> secondPage = mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=1&sort=talkgroup&direction=asc&limit=2&offset=2"));
        assertEquals(List.of(60000L), rows(secondPage).stream()
            .map(row -> number(row.get("talkgroup_id"))).toList());
        assertEquals("none", rows(secondPage).getFirst().get("match_kind"));
        assertNull(rows(secondPage).getFirst().get("matched_alias_id"));
        assertFalse((Boolean)secondPage.get("hasMore"));
        assertEquals("County", map(firstPage, "alias_list").get("name"));
        assertEquals(false, firstPage.get("include_exact"));

        List<Map<String,Object>> exact = rows(mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=1&include_exact=true&q=56132")));
        assertEquals(1, exact.size());
        assertEquals("exact", exact.getFirst().get("match_kind"));
        assertEquals("Dispatch", exact.getFirst().get("matched_alias_name"));

        assertEquals(400, assertThrows(StatsApiException.class, () -> mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=1&include_exact=maybe"))).status());
        assertEquals(404, assertThrows(StatsApiException.class, () -> mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=999"))).status());
    }

    @Test
    void retainsDecodedP25HomeIdentityWhileMatchingTheLocalTalkgroup() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, matcher_type, protocol, value
                ) VALUES (90, 1, 'ISSI Dispatch', 'TALKGROUP', 'APCO25', 1700)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, p25_identity_state_code,
                    p25_home_wacn, p25_home_system_id, p25_home_talkgroup_id,
                    first_seen_ms, last_seen_ms, call_count, target_call_count
                ) VALUES
                    (1, 1, 700, 1, NULL, NULL, NULL, 1000, 2000, 1, 1),
                    (1, 1, 1700, 2, 0xABCDE, 0x123, 700, 1000, 2000, 1, 1),
                    (1, 1, 1701, 2, 0xABCDE, 0x123, 701, 1000, 2000, 1, 1),
                    (1, 1, 1702, 3, NULL, NULL, NULL, 1000, 2000, 1, 1),
                    (1, 1, 1703, 0, NULL, NULL, NULL, 1000, 2000, 1, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_zero_local_fq_talkgroup_summary (
                    scope_id, home_wacn, home_system_id, home_talkgroup_id,
                    first_seen_ms, last_seen_ms, call_count, recorded_count
                ) VALUES
                    (1, 0xABCDE, 0x123, 700, 1000, 2100, 4, 2),
                    (1, 0xABCDE, 0x124, 700, 1100, 2200, 3, 1)
                """);
        }

        List<Map<String,Object>> rows = rows(mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=1&sort=talkgroup&direction=asc&limit=100")));
        Map<String,Object> ordinary = rows.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 700L).findFirst().orElseThrow();
        assertEquals("none", ordinary.get("match_kind"));
        assertEquals(true, ordinary.get("promotion_supported"));

        Map<String,Object> qualified = rows.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 1701L).findFirst().orElseThrow();
        assertEquals(2L, number(qualified.get("p25_identity_state_code")));
        assertEquals(0xABCDEL, number(qualified.get("p25_home_wacn")));
        assertEquals(0x123L, number(qualified.get("p25_home_system_id")));
        assertEquals(701L, number(qualified.get("p25_home_talkgroup_id")));
        assertEquals(true, qualified.get("promotion_supported"));
        assertEquals("none", qualified.get("match_kind"));

        Map<String,Object> zeroLocal = rows.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 0L &&
                number(row.get("p25_home_system_id")) == 0x124L).findFirst().orElseThrow();
        assertEquals(2L, number(zeroLocal.get("p25_identity_state_code")));
        assertEquals(0xABCDEL, number(zeroLocal.get("p25_home_wacn")));
        assertEquals(0x124L, number(zeroLocal.get("p25_home_system_id")));
        assertEquals(700L, number(zeroLocal.get("p25_home_talkgroup_id")));
        assertEquals(false, zeroLocal.get("promotion_supported"));
        assertEquals("none", zeroLocal.get("match_kind"));

        Map<String,Object> ambiguous = rows.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 1702L).findFirst().orElseThrow();
        assertEquals(true, ambiguous.get("promotion_supported"));
        Map<String,Object> historical = rows.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 1703L).findFirst().orElseThrow();
        assertEquals(true, historical.get("promotion_supported"));

        List<Map<String,Object>> exact = rows(mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=1&include_exact=true&q=1700")));
        assertEquals(1, exact.size());
        assertEquals("exact", exact.getFirst().get("match_kind"));
        assertEquals("ISSI Dispatch", exact.getFirst().get("matched_alias_name"));
        List<Map<String,Object>> zeroLocalExact = rows(mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=1&include_exact=true&q=ABCDE-123-700&limit=100")));
        assertEquals(2, zeroLocalExact.size(),
            "The ordinary local row remains separate while both local 1700 and local 0 can use the same home tuple");
        Map<String,Object> exactZero = zeroLocalExact.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 0L).findFirst().orElseThrow();
        assertEquals("none", exactZero.get("match_kind"));
        assertEquals(4L, number(exactZero.get("call_count")));
        assertEquals(2L, number(exactZero.get("recorded_count")));
        List<Map<String,Object>> qualifiedSearch = rows(mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=1&q=ABCDE-123-701")));
        assertEquals(1, qualifiedSearch.size(), "The displayed fully-qualified identity is searchable");
        assertEquals(1701L, number(qualifiedSearch.getFirst().get("talkgroup_id")));

        List<Map<String,Object>> aliases = rows(mDatabase.aliases(request(
            "/api/aliases?list=1&q=ISSI%20Dispatch")));
        assertEquals(1, aliases.size());
        assertEquals(1L, number(aliases.getFirst().get("call_count")),
            "Only observations using the configured local talkgroup contribute");
    }

    @Test
    void includesConventionalDmrUnknownTalkgroupsWithoutDuplicatingTimeslots() throws Exception
    {
        seedDmrConventionalRows(mDatabasePath);
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_talkgroup_summary (
                    context_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms,
                    call_count, encrypted_count
                ) VALUES (5, 451012500, 1, 93, 3000, 5000, 4, 1),
                         (5, 451012500, 2, 93, 4000, 6000, 6, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                    call_count, encrypted_count, recorded_count, streamed_count
                ) VALUES (5, %d, 1, 1, 93, 10, 3, 4, 5)
                """.formatted(bucket));
        }
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);

        List<Map<String,Object>> rows = rows(mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=100&sort=talkgroup&direction=asc")));
        assertEquals(1, rows.size(), "The two timeslots belong to one context/talkgroup identity");
        Map<String,Object> row = rows.getFirst();
        assertEquals(93L, number(row.get("talkgroup_id")));
        assertEquals("CONVENTIONAL", row.get("topology"));
        assertEquals("none", row.get("match_kind"));
        assertEquals(10L, number(row.get("call_count")));
        assertEquals(3L, number(row.get("encrypted_count")));
        assertEquals(4L, number(row.get("recorded_count")));
        assertEquals(5L, number(row.get("streamed_count")));
        assertEquals(2L, number(row.get("timeslot_count")));
        assertNull(row.get("timeslot"));
        assertNull(row.get("grant_count"), "Conventional DMR does not collect grant signaling");
    }

    @Test
    void includesConventionalP25AndNxdnTalkgroupsFromCompactCallBuckets() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name,
                    decoder, first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES
                    (210, 'conventional-p25-discovery', 'conventional-p25-discovery-guid', 2, 2,
                        'P25 Conventional', 'County', 'P25-2', 1000, 9000, 154875000),
                    (211, 'conventional-nxdn-discovery', 'conventional-nxdn-discovery-guid', 4, 4,
                        'NXDN Conventional', 'County NXDN', 'NXDN', 1000, 9000, 452125000)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (210, 'County NXDN', 'NXDN')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, matcher_type, protocol, min_value, max_value
                ) VALUES
                    (210, 1, 'P25 Discovery Range', 'TALKGROUP_RANGE', 'APCO25', 61000, 62000),
                    (211, 210, 'NXDN Discovery Range', 'TALKGROUP_RANGE', 'NXDN', 200, 300)
                """);
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                    call_count, encrypted_count, recorded_count, streamed_count
                ) VALUES
                    (210, 3600000, 1, 1, 61001, 3, 1, 1, 0),
                    (210, 7200000, 1, 1, 61001, 2, 0, 0, 2),
                    (210, 7200000, 1, 1, 56132, 9, 0, 0, 0),
                    (210, 7200000, 2, 2, 900001, 9, 0, 0, 0),
                    (211, 3600000, 1, 1, 250, 4, 2, 3, 1)
                """);
        }
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);

        List<Map<String,Object>> p25Rows = rows(mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=1&sort=talkgroup&direction=asc")));
        assertEquals(1, p25Rows.size(), "Exact and source-radio identities stay out of discovery by default");
        Map<String,Object> p25 = p25Rows.getFirst();
        assertEquals("P25", p25.get("protocol"));
        assertEquals("CONVENTIONAL", p25.get("topology"));
        assertEquals(61001L, number(p25.get("talkgroup_id")));
        assertEquals("range", p25.get("match_kind"));
        assertEquals("P25 Discovery Range", p25.get("matched_alias_name"));
        assertEquals(5L, number(p25.get("call_count")));
        assertEquals(1L, number(p25.get("encrypted_count")));
        assertEquals(1L, number(p25.get("recorded_count")));
        assertEquals(2L, number(p25.get("streamed_count")));
        assertEquals(3_600_000L, number(p25.get("first_seen_ms")));
        assertEquals(7_200_000L, number(p25.get("last_seen_ms")));
        assertEquals(154_875_000L, number(p25.get("frequency_hz")));
        assertEquals(1L, number(p25.get("frequency_count")));
        assertNull(p25.get("grant_count"));

        List<Map<String,Object>> nxdnRows = rows(mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=210")));
        assertEquals(1, nxdnRows.size());
        Map<String,Object> nxdn = nxdnRows.getFirst();
        assertEquals("NXDN", nxdn.get("protocol"));
        assertEquals("CONVENTIONAL", nxdn.get("topology"));
        assertEquals(250L, number(nxdn.get("talkgroup_id")));
        assertEquals("range", nxdn.get("match_kind"));
        assertEquals("NXDN Discovery Range", nxdn.get("matched_alias_name"));
        assertEquals(4L, number(nxdn.get("call_count")));
        assertEquals(2L, number(nxdn.get("encrypted_count")));
        assertEquals(3L, number(nxdn.get("recorded_count")));
        assertEquals(1L, number(nxdn.get("streamed_count")));
    }

    @Test
    void includesTrunkedDmrAndNxdnTalkgroupsForTheirAssignedLists() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("observed-dmr",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 0, "Observed DMR System", "DMR Site",
                10, 20, 1, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("observed-nxdn",
                TrunkedSiteSchema.PROTOCOL_NXDN, 1, 0, "Observed NXDN System", "NXDN Site",
                30, 40, 2, 3, List.of(), List.of()));
            seedContextScope(connection, 201, 201, "observed-dmr", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 202, 202, "observed-nxdn", TrunkedSiteSchema.PROTOCOL_NXDN, 0);

            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("""
                    INSERT INTO alias_list (id, name, family)
                    VALUES (200, 'Observed DMR', 'DMR'), (201, 'Observed NXDN', 'NXDN')
                    """);
                statement.executeUpdate("""
                    UPDATE receiver_context
                    SET alias_list_name = CASE id
                        WHEN 201 THEN 'Observed DMR'
                        WHEN 202 THEN 'Observed NXDN'
                    END
                    WHERE id IN (201, 202)
                    """);
                statement.executeUpdate("""
                    INSERT INTO alias (
                        id, alias_list_id, name, matcher_type, protocol, min_value, max_value
                    ) VALUES (200, 200, 'DMR Range', 'TALKGROUP_RANGE', 'DMR', 100, 200)
                    """);
                statement.executeUpdate("""
                    INSERT INTO trunked_identity_summary (
                        scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                        call_count, target_call_count, grant_count, encrypted_count,
                        recorded_count, streamed_count
                    ) VALUES (201, 1, 150, 1000, 3000, 4, 4, 4, 1, 2, 3),
                             (202, 1, 250, 1000, 3000, 5, 5, 5, 0, 1, 2)
                    """);
            }
        }
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);

        Map<String,Object> dmr = rows(mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=200"))).getFirst();
        assertEquals("DMR", dmr.get("protocol"));
        assertEquals("TRUNKED", dmr.get("topology"));
        assertEquals("range", dmr.get("match_kind"));
        assertEquals("DMR Range", dmr.get("matched_alias_name"));
        assertEquals("Observed DMR System", dmr.get("system_name"));

        Map<String,Object> nxdn = rows(mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=201"))).getFirst();
        assertEquals("NXDN", nxdn.get("protocol"));
        assertEquals("none", nxdn.get("match_kind"));
        assertEquals(250L, number(nxdn.get("talkgroup_id")));
        assertEquals("Observed NXDN System", nxdn.get("system_name"));
    }

    @Test
    void filtersAliasConfigurationAndEvidenceBeforePagingAndReportsOverlaps() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE alias SET group_name='Operations', record_enabled=1 WHERE id=1
                """);
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(alias_id, channel_name) VALUES(1, 'Primary')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value, min_value, max_value)
                VALUES (3, 1, 'Dispatch Duplicate', 'TALKGROUP', 'APCO25_PHASE2', 56132, NULL, NULL),
                       (4, 1, 'Range A', 'TALKGROUP_RANGE', 'APCO25', NULL, 57000, 57100),
                       (5, 1, 'Range B', 'TALKGROUP_RANGE', 'APCO25_PHASE2', NULL, 57050, 57200),
                       (6, 1, 'AAA No Calls', 'TALKGROUP', 'APCO25', 59000, NULL, NULL)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_scan_list_membership(alias_id, scan_list_id) VALUES(1, 1)
                """);
        }

        List<Map<String,Object>> configured = rows(mDatabase.aliases(request(
            "/api/aliases?list=1&group=operations&scan_list_id=1&record=enabled&stream=present")));
        assertEquals(List.of("Dispatch"), configured.stream().map(row -> row.get("name")).toList());
        assertEquals(List.of(1L), configured.getFirst().get("scan_list_ids"));
        assertEquals(List.of("Default"), configured.getFirst().get("scan_lists"));
        assertEquals(true, configured.getFirst().get("overlap"));
        assertEquals(List.of("overlap"), configured.getFirst().get("configuration_errors"));

        List<Map<String,Object>> ranges = rows(mDatabase.aliases(request(
            "/api/v1/aliases?list=1&family=p25&matcher=talkgroup_range&sort=name")));
        assertEquals(2, ranges.size());
        assertTrue(ranges.stream().allMatch(row -> Boolean.TRUE.equals(row.get("overlap"))));
        assertThrows(StatsApiException.class, () -> mDatabase.aliases(request(
            "/api/v1/aliases?list=1&matcher=TALKGROUP_RANGE")));
        assertThrows(StatsApiException.class, () -> mDatabase.aliases(request(
            "/api/v1/aliases?list=1&family=P25")));
        assertThrows(StatsApiException.class, () -> mDatabase.aliases(request(
            "/api/v1/aliases?list=1&evidence=OBSERVED")));

        Map<String,Object> observedResponse = mDatabase.aliases(request(
            "/api/v1/aliases?list=1&type=talkgroup&evidence=observed&use=used&last_activity_after=2000&limit=1"));
        List<Map<String,Object>> observed = rows(observedResponse);
        assertEquals(List.of("Dispatch Duplicate"), observed.stream().map(row -> row.get("name")).toList(),
            "Catalog evidence must follow the same later-exact-alias precedence as runtime");
        assertFalse((Boolean)observedResponse.get("hasMore"));

        List<Map<String,Object>> noCalls = rows(mDatabase.aliases(request(
            "/api/aliases?list=1&type=talkgroup&evidence=covered_no_evidence&use=unused&limit=10")));
        assertEquals(List.of("AAA No Calls", "Dispatch", "Range A", "Range B"),
            noCalls.stream().map(row -> row.get("name")).toList());

        assertTrue(rows(mDatabase.aliases(request(
            "/api/v1/aliases?list=1&evidence=observed&last_activity_before=1999"))).isEmpty());
        for(String scanListId: List.of("0", "01", "-1", "maybe", "9223372036854775808"))
        {
            StatsApiException exception = assertThrows(StatsApiException.class, () ->
                mDatabase.aliases(request("/api/aliases?list=1&scan_list_id=" + scanListId)));
            assertEquals(400, exception.status());
            assertEquals("scan_list_id", exception.field());
        }
        assertEquals(400, assertThrows(StatsApiException.class, () ->
            mDatabase.aliases(request("/api/aliases?list=1&evidence=unknown"))).status());
    }

    @Test
    void filtersScanListMembersAcrossAllAliasLists() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family) VALUES(900, 'Regional', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES(900, 900, 'Regional Dispatch', 'TALKGROUP', 'APCO25', 61000)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_scan_list_membership(alias_id, scan_list_id)
                VALUES(1, 1), (900, 1)
                """);
        }

        List<Map<String,Object>> members = rows(mDatabase.aliases(request(
            "/api/v1/aliases?scan_list_id=1&sort=list&limit=100")));
        assertEquals(List.of("Dispatch", "Regional Dispatch"),
            members.stream().map(row -> row.get("name")).toList());
        assertEquals(List.of("County", "Regional"),
            members.stream().map(row -> row.get("alias_list_name")).toList());
        assertTrue(members.stream().allMatch(row -> row.get("scan_list_ids").equals(List.of(1L))));

        List<Map<String,Object>> countyMembers = rows(mDatabase.aliases(request(
            "/api/v1/aliases?list=1&scan_list_id=1&sort=list&limit=100")));
        assertEquals(List.of("Dispatch"), countyMembers.stream().map(row -> row.get("name")).toList());
    }

    @Test
    void sortsEveryMatcherByItsDisplayValue() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, matcher_type, protocol, value, min_value, max_value,
                    text_value, numeric_value, tone_sequence
                ) VALUES
                    (5, 1, 'Small Range', 'TALKGROUP_RANGE', 'APCO25', NULL,
                        20, 30, NULL, NULL, NULL),
                    (6, 1, 'Status Ten', 'STATUS', NULL, NULL,
                        NULL, NULL, NULL, 10, NULL),
                    (7, 1, 'DCS Code', 'DCS', NULL, NULL,
                        NULL, NULL, 'D023N', NULL, NULL),
                    (8, 1, 'Tone Code', 'TONES', NULL, NULL,
                        NULL, NULL, NULL, NULL, 'A-B')
                """);
        }

        assertEquals(List.of("Status Ten", "Small Range", "Dispatch", "Engine 1", "Tone Code", "DCS Code"),
            rows(mDatabase.aliases(request("/api/aliases?sort=value&direction=asc&limit=20"))).stream()
                .map(row -> row.get("name")).toList());
    }

    @Test
    void exportsLatestAndHistoricalQualityWithoutSummingRollingCounters() throws Exception
    {
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("quiet-quality-site",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 0, "Quiet DMR", "Quiet Quality Site",
                7, null, 2, null, List.of(), List.of()));
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality (
                    guid, frequency_hz, bucket_start_ms, observed_at_ms, signal_dbfs,
                    average_signal_dbfs, minimum_signal_dbfs, maximum_signal_dbfs, decode_health_pct,
                    valid_frames, invalid_frames, corrected_bits, sync_loss_bits, dropped_bits,
                    last_valid_decode_ms
                ) VALUES ('test-site-guid', 856137500, %1$d, %1$d, -18.0, -19.0, -22.0, -17.0,
                    96.0, 30, 2, 4, 1, 0, %1$d)
                """.formatted((now / 10_000L) * 10_000L));
        }

        List<CSVRecord> latest = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=signal-health")));
        assertEquals(2, latest.size());
        CSVRecord sampled = latest.stream().filter(row -> GUID.equals(row.get("site_guid"))).findFirst()
            .orElseThrow();
        assertEquals("01", sampled.get("site_id_hex"));
        assertFalse(sampled.get("sample_age_seconds").isBlank());
        assertTrue(sampled.isMapped("valid_frames_rolling_30s"));
        CSVRecord quiet = latest.stream().filter(row -> "quiet-quality-site".equals(row.get("site_guid")))
            .findFirst().orElseThrow();
        assertEquals("", quiet.get("observed_utc"));
        assertEquals("", quiet.get("sample_age_seconds"));

        List<CSVRecord> history = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=site-quality&guid=test-site-guid&range=1h&points=60")));
        assertEquals(1, history.size());
        assertTrue(history.getFirst().isMapped("bucket_end_utc"));
        assertFalse(history.getFirst().isMapped("valid_frames_rolling_30s"));
        assertEquals("BEE00", history.getFirst().get("wacn_hex"));
    }

    @Test
    void enrichesConventionalDmrAliasesFromCompactSummariesAndOutputBuckets() throws Exception
    {
        seedDmrConventionalRows(mDatabasePath);
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, identity_role_code, identity_kind_code, identity_id, bucket_start_ms,
                    call_count, encrypted_count, recorded_count, streamed_count
                ) VALUES (5, 1, 1, 91, %1$d, 10, 2, 3, 2),
                         (5, 2, 2, 123456, %1$d, 10, 1, 3, 2)
                """.formatted(bucket));
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        List<Map<String,Object>> aliases = rows(mDatabase.aliases(request(
            "/api/aliases?list=100&sort=name&direction=asc")));
        Map<String,Object> dispatch = aliases.stream().filter(row -> "DMR Dispatch".equals(row.get("name")))
            .findFirst().orElseThrow();
        assertEquals("observed", dispatch.get("metrics_state"));
        assertEquals(10L, number(dispatch.get("call_count")));
        assertEquals(3L, number(dispatch.get("recorded_count")));
        assertEquals(2L, number(dispatch.get("streamed_count")));
        assertEquals(2L, number(dispatch.get("encrypted_evidence_count")));
        assertNull(dispatch.get("grant_count"));
        assertNull(dispatch.get("relationship_count"));
        assertEquals(1000L, number(dispatch.get("first_evidence_ms")));
        assertEquals(5000L, number(dispatch.get("last_evidence_ms")));

        Map<String,Object> radio = aliases.stream().filter(row -> "DMR Engine 1".equals(row.get("name")))
            .findFirst().orElseThrow();
        assertEquals(10L, number(radio.get("call_count")));
        assertEquals(3L, number(radio.get("recorded_count")));
        assertNull(radio.get("current_affiliation_count"));
    }

    @Test
    void returnsDurableAliasListIdsWithSiteAndConventionalRows()
    {
        assertEquals(1L, number(map(mDatabase.site(request("/api/site?guid=" + GUID)), "site")
            .get("alias_list_id")));
        assertEquals(1L, number(rows(mDatabase.conventional(request("/api/conventional"))).getFirst()
            .get("alias_list_id")));
        assertEquals(1L, number(map(mDatabase.conventionalDetail(
            request("/api/conventional/detail?context=conventional-fire")), "context").get("alias_list_id")));
    }

    @Test
    void aliasEvidenceQueriesUseScopeLeadingIndexesAndNeverDetailedEvents() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            List<String> identityPlan = explain(connection, """
                SELECT identity_id, call_count
                FROM trunked_identity_summary
                WHERE scope_id IN (?)
                ORDER BY scope_id, identity_kind_code, identity_id
                LIMIT ?
                """, 1, 500_001);
            assertTrue(identityPlan.stream().anyMatch(detail -> detail.contains("PRIMARY KEY") ||
                    detail.contains("idx_trunked_identity_scope_kind_last_seen")),
                () -> "Expected scope-leading identity lookup, plan was: " + identityPlan);
            assertTrue(identityPlan.stream().noneMatch(detail -> detail.contains("p25_activity_event")));

            List<String> relationshipPlan = explain(connection, """
                SELECT radio_id, talkgroup_id
                FROM trunked_radio_talkgroup_summary
                WHERE scope_id IN (?)
                ORDER BY scope_id, radio_id, talkgroup_id, target_kind_code
                LIMIT ?
                """, 1, 250_001);
            assertTrue(relationshipPlan.stream().anyMatch(detail -> detail.contains("PRIMARY KEY")),
                () -> "Expected scope-leading relationship lookup, plan was: " + relationshipPlan);
            assertTrue(relationshipPlan.stream().noneMatch(detail -> detail.contains("p25_activity_event")));
        }
    }

    @Test
    void authoritativePresenceAggregatesUseCurrentStateIndexesAndNeverDetailedEvents() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            List<String> talkgroupPlan = explain(connection, """
                SELECT COUNT(DISTINCT presence.context_id)
                FROM trunked_radio_affiliation affiliation
                JOIN trunked_radio_site_presence presence
                  ON presence.scope_id = affiliation.scope_id AND presence.radio_id = affiliation.radio_id
                WHERE affiliation.scope_id = ? AND affiliation.talkgroup_id = ?
                """, 1, 56132);
            assertTrue(talkgroupPlan.stream().anyMatch(detail ->
                    detail.contains("idx_trunked_radio_affiliation_talkgroup")),
                () -> "Expected talkgroup-leading affiliation lookup, plan was: " + talkgroupPlan);
            assertTrue(talkgroupPlan.stream().anyMatch(detail ->
                    detail.contains("SEARCH presence USING PRIMARY KEY")),
                () -> "Expected one-row presence lookup per affiliated radio, plan was: " + talkgroupPlan);

            List<String> sitePlan = explain(connection, """
                SELECT COUNT(*)
                FROM trunked_radio_site_presence presence
                JOIN receiver_context context ON context.id = presence.context_id
                JOIN trunked_radio_affiliation affiliation
                  ON affiliation.scope_id = presence.scope_id AND affiliation.radio_id = presence.radio_id
                WHERE context.guid = ?
                """, GUID);
            assertTrue(sitePlan.stream().anyMatch(detail -> detail.contains("idx_receiver_context_guid")),
                () -> "Expected exact GUID lookup, plan was: " + sitePlan);
            assertTrue(sitePlan.stream().anyMatch(detail ->
                    detail.contains("idx_trunked_radio_site_presence_context")),
                () -> "Expected context-leading presence lookup, plan was: " + sitePlan);
            assertTrue(sitePlan.stream().anyMatch(detail ->
                    detail.contains("SEARCH affiliation USING PRIMARY KEY")),
                () -> "Expected one-row affiliation lookup per present radio, plan was: " + sitePlan);

            assertTrue(java.util.stream.Stream.concat(talkgroupPlan.stream(), sitePlan.stream())
                .noneMatch(detail -> detail.contains("p25_activity_event") ||
                    detail.contains("call_identity_bucket")));
        }
    }

    @Test
    void observedTalkgroupQueryUsesBoundedSummaryIndexesAtRepresentativeVolume() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            connection.setAutoCommit(false);
            try(PreparedStatement trunkedContexts = connection.prepareStatement("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, alias_list_name,
                    first_seen_ms, last_seen_ms, system_key
                ) VALUES (?, ?, ?, 1, 1, 'County', 1000, 2000, 1)
                """);
                PreparedStatement ownership = connection.prepareStatement("""
                INSERT INTO trunked_identity_scope_context (
                    context_id, scope_id, first_seen_ms, last_seen_ms
                ) VALUES (?, 1, 1000, 2000)
                """);
                PreparedStatement trunkedIdentities = connection.prepareStatement("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, p25_identity_state_code,
                    first_seen_ms, last_seen_ms, call_count, target_call_count
                ) VALUES (1, 1, ?, 1, 1000, 2000, 1, 1)
                """);
                PreparedStatement zeroLocalIdentity = connection.prepareStatement("""
                INSERT INTO p25_zero_local_fq_talkgroup_summary (
                    scope_id, home_wacn, home_system_id, home_talkgroup_id,
                    first_seen_ms, last_seen_ms, call_count
                ) VALUES (1, 0xABCDE, 0x123, 1200, 1000, 2000, 1)
                """);
                PreparedStatement conventionalContexts = connection.prepareStatement("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, alias_list_name,
                    first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES (?, ?, ?, ?, ?, 'County', 1000, 2000, ?)
                """);
                PreparedStatement dmrIdentities = connection.prepareStatement("""
                INSERT INTO dmr_conventional_talkgroup_summary (
                    context_id, frequency_hz, timeslot, talkgroup_id,
                    first_seen_ms, last_seen_ms, call_count, encrypted_count
                ) VALUES (?, ?, ?, ?, 1000, 2000, 1, 0)
                """);
                PreparedStatement callIdentities = connection.prepareStatement("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code,
                    identity_id, call_count
                ) VALUES (?, 0, 1, 1, ?, 1)
                """);
                PreparedStatement aliases = connection.prepareStatement("""
                INSERT INTO alias (
                    id, alias_list_id, name, matcher_type, protocol, value
                ) VALUES (?, 1, ?, 'TALKGROUP', 'APCO25', ?)
                """))
            {
                for(int contextId = 5_000; contextId < 5_050; contextId++)
                {
                    trunkedContexts.setInt(1, contextId);
                    trunkedContexts.setString(2, "plan-trunked-" + contextId);
                    trunkedContexts.setString(3, "plan-trunked-guid-" + contextId);
                    trunkedContexts.addBatch();
                    ownership.setInt(1, contextId);
                    ownership.addBatch();
                }

                for(int identityId = 10_000; identityId < 15_000; identityId++)
                {
                    trunkedIdentities.setInt(1, identityId);
                    trunkedIdentities.addBatch();
                }

                for(int offset = 0; offset < 25; offset++)
                {
                    int dmrContextId = 6_000 + offset;
                    conventionalContexts.setInt(1, dmrContextId);
                    conventionalContexts.setString(2, "plan-dmr-" + offset);
                    conventionalContexts.setString(3, "plan-dmr-guid-" + offset);
                    conventionalContexts.setInt(4, 3);
                    conventionalContexts.setInt(5, 3);
                    conventionalContexts.setLong(6, 451_000_000L + offset * 12_500L);
                    conventionalContexts.addBatch();

                    int p25ContextId = 7_000 + offset;
                    conventionalContexts.setInt(1, p25ContextId);
                    conventionalContexts.setString(2, "plan-p25-" + offset);
                    conventionalContexts.setString(3, "plan-p25-guid-" + offset);
                    conventionalContexts.setInt(4, 2);
                    conventionalContexts.setInt(5, 2);
                    conventionalContexts.setLong(6, 154_000_000L + offset * 12_500L);
                    conventionalContexts.addBatch();

                    for(int identityOffset = 0; identityOffset < 100; identityOffset++)
                    {
                        int identityId = 20_000 + offset * 100 + identityOffset;
                        dmrIdentities.setInt(1, dmrContextId);
                        dmrIdentities.setLong(2, 451_000_000L + offset * 12_500L);
                        dmrIdentities.setInt(3, identityOffset % 2 + 1);
                        dmrIdentities.setInt(4, identityId);
                        dmrIdentities.addBatch();

                        callIdentities.setInt(1, dmrContextId);
                        callIdentities.setInt(2, identityId);
                        callIdentities.addBatch();
                        callIdentities.setInt(1, p25ContextId);
                        callIdentities.setInt(2, identityId);
                        callIdentities.addBatch();
                    }
                }

                for(int aliasOffset = 0; aliasOffset < 5_000; aliasOffset++)
                {
                    aliases.setInt(1, 100_000 + aliasOffset);
                    aliases.setString(2, "Plan Alias " + aliasOffset);
                    aliases.setInt(3, aliasOffset + 1);
                    aliases.addBatch();
                }

                trunkedContexts.executeBatch();
                ownership.executeBatch();
                trunkedIdentities.executeBatch();
                zeroLocalIdentity.executeUpdate();
                conventionalContexts.executeBatch();
                dmrIdentities.executeBatch();
                callIdentities.executeBatch();
                aliases.executeBatch();
            }
            connection.commit();
        }

        AtomicReference<StatsWebDatabase.ObservedTalkgroupQuery> captured = new AtomicReference<>();
        mDatabase.observedTalkgroups(request(
            "/api/alias-list/observed-talkgroups?list=1&sort=last_seen&direction=desc&limit=100"),
            captured::set);
        StatsWebDatabase.ObservedTalkgroupQuery query = captured.get();
        assertNotNull(query);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.execute("ANALYZE");
            List<String> plan = explain(connection, query.sql(), query.parameters().toArray());
            assertTrue(plan.stream().anyMatch(detail ->
                    detail.contains("idx_trunked_identity_scope_context_scope")),
                () -> "Expected scope-owned context lookup, plan was: " + plan);
            assertTrue(plan.stream().anyMatch(detail ->
                    detail.contains("idx_trunked_identity_scope_kind_last_seen") ||
                        detail.contains("SEARCH summary USING PRIMARY KEY (scope_id=?")),
                () -> "Expected scope-leading trunked identity lookup, plan was: " + plan);
            assertTrue(plan.stream().anyMatch(detail ->
                    detail.contains("idx_p25_zero_local_fq_scope_last_seen")),
                () -> "Expected indexed zero-local fully-qualified P25 lookup, plan was: " + plan);
            assertTrue(plan.stream().anyMatch(detail ->
                    detail.contains("SEARCH summary USING PRIMARY KEY (context_id=?")),
                () -> "Expected context-leading DMR identity lookup, plan was: " + plan);
            assertTrue(plan.stream().anyMatch(detail ->
                    detail.contains("SEARCH bucket USING PRIMARY KEY (context_id=?")),
                () -> "Expected context-leading compact call-bucket lookup, plan was: " + plan);
            assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_alias_talkgroup_value")),
                () -> "Expected indexed exact-alias lookup, plan was: " + plan);
            assertTrue(plan.stream().noneMatch(detail -> detail.startsWith("SCAN summary") ||
                    detail.startsWith("SCAN ownership") || detail.startsWith("SCAN bucket") ||
                    detail.startsWith("SCAN definition")),
                () -> "High-cardinality discovery tables must use scoped lookups, plan was: " + plan);
            assertTrue(plan.stream().noneMatch(detail -> detail.contains("p25_activity_event")),
                () -> "Discovery must not read detailed P25 events, plan was: " + plan);
        }
    }

    @Test
    void exportsCompleteFilteredAndSortedDatasetsWithoutUsingPageControls() throws Exception
    {
        seedSortingRows(mDatabasePath);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, decoder,
                    first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES (81, 'csv-p25-phase2', 'csv-p25-phase2-guid', 2, 2, 'CSV P25 Phase 2', 'P25-2',
                             1000, 3000, 851012500),
                         (82, 'csv-nxdn', 'csv-nxdn-guid', 4, 4, 'CSV NXDN', 'NXDN',
                             1000, 3000, 461125000)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary (
                    context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, call_count
                ) VALUES (81, 851012500, -1, 1000, 3000, 2),
                         (82, 461125000, -1, 1000, 3000, 3)
                """);
        }
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);

        StatsCsvExport talkgroups = mDatabase.csvExport(request(
            "/api/export.csv?dataset=system-talkgroups&scope=p25:BEE00:348" +
                "&sort=talkgroup&direction=asc&limit=1&offset=100000"));
        List<CSVRecord> talkgroupRows = csvRows(talkgroups);
        assertEquals(2, talkgroupRows.size());
        assertEquals("100", talkgroupRows.getFirst().get("talkgroup_id"));
        assertEquals("56132", talkgroupRows.getLast().get("talkgroup_id"));

        StatsCsvExport filtered = mDatabase.csvExport(request(
            "/api/export.csv?dataset=system-talkgroups&scope=p25:BEE00:348&q=56132"));
        assertEquals(List.of("56132"), csvRows(filtered).stream()
            .map(row -> row.get("talkgroup_id")).toList());

        assertTrue(mDatabase.csvExport(request(
            "/api/export.csv?dataset=system-radios&scope=p25:BEE00:348")).rowCount() > 0);
        assertTrue(mDatabase.csvExport(request(
            "/api/export.csv?dataset=site-channels&guid=" + GUID)).rowCount() > 0);
        mDatabase.csvExport(request("/api/export.csv?dataset=site-neighbors&guid=" + GUID));
        List<CSVRecord> conventional = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=conventional-channels")));
        assertTrue(conventional.size() > 0);
        CSVRecord nbfm = conventional.stream().filter(row ->
            "conventional-fire".equals(row.get("context"))).findFirst().orElseThrow();
        assertEquals("NBFM", nbfm.get("protocol"));
        assertEquals("", nbfm.get("timeslot"));
        assertEquals("P25", conventional.stream().filter(row ->
            "csv-p25-phase2".equals(row.get("context"))).findFirst().orElseThrow().get("protocol"));
        assertEquals("NXDN", conventional.stream().filter(row ->
            "csv-nxdn".equals(row.get("context"))).findFirst().orElseThrow().get("protocol"));

        seedDmrConventionalRows(mDatabasePath);
        assertEquals(1, mDatabase.csvExport(request(
            "/api/export.csv?dataset=conventional-talkgroups&context=conventional-dmr-county&q=dispatch"))
            .rowCount());
        List<CSVRecord> radios = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=conventional-radios&context=conventional-dmr-county&sort=radio" +
                "&direction=asc")));
        assertEquals("451012500", radios.getFirst().get("frequency_hz"));
        assertEquals("1", radios.getFirst().get("timeslot"));

        StatsApiException unsupported = assertThrows(StatsApiException.class,
            () -> mDatabase.csvExport(request("/api/export.csv?dataset=activity")));
        assertEquals(400, unsupported.status());
    }

    @Test
    void exposesPatchCallsForCanonicalMembersAndRadioRelationships() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms, call_count,
                    target_call_count, encrypted_count, recorded_count, streamed_count,
                    last_counterpart_kind_code, last_counterpart_id
                ) VALUES
                    (1, 1, 56180, 3000, 3000, 1, 1, 1, 1, 1, 2, 1811332),
                    (1, 1, 56181, 3000, 3000, 1, 1, 1, 1, 1, 2, 1811332),
                    (1, 3, 56182, 3000, 3000, 1, 1, 1, 1, 1, 2, 1811332)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_talkgroup_summary (
                    scope_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms,
                    call_count, encrypted_count
                ) VALUES
                    (1, 1811332, 56180, 1, 3000, 3000, 1, 1),
                    (1, 1811332, 56181, 1, 3000, 3000, 1, 1),
                    (1, 1811332, 56182, 3, 3000, 3000, 1, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, identity_role_code, identity_kind_code, identity_id, bucket_start_ms,
                    call_count, encrypted_count, recorded_count, streamed_count
                ) VALUES
                    (1, 1, 1, 56180, %1$d, 1, 1, 1, 1),
                    (1, 1, 1, 56181, %1$d, 1, 1, 1, 1),
                    (1, 1, 3, 56182, %1$d, 1, 1, 1, 1)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket (
                    context_id, bucket_start_ms, call_count, continue_count, gps_count, encrypted_count,
                    recorded_count, streamed_count
                ) VALUES (1, %d, 1, 3, 2, 1, 1, 1)
                """.formatted(bucket));
            statement.executeUpdate("""
                INSERT INTO p25_site_frequency_summary (
                    context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, call_count, encrypted_count
                ) VALUES (1, 854187500, 1, 3000, 3000, 1, 1)
                """);
        }

        List<Map<String,Object>> talkgroups = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&q=5618&sort=talkgroup&direction=asc")));
        assertEquals(List.of(56180L, 56181L, 56182L), talkgroups.stream()
            .map(row -> number(row.get("talkgroup_id"))).toList());

        for(Map<String,Object> talkgroup: talkgroups)
        {
            assertEquals(1L, number(talkgroup.get("call_count")));
            assertEquals(1L, number(talkgroup.get("encrypted_count")));
            assertEquals(1L, number(talkgroup.get("recorded_count")));
            assertEquals(1L, number(talkgroup.get("streamed_count")));
        }

        Map<String,Object> patch = map(mDatabase.talkgroup(request(
            "/api/talkgroup?scope=p25:BEE00:348&talkgroup_id=56182&kind=patch_group")), "group_identity");
        assertEquals(3L, number(patch.get("target_kind_code")));
        assertEquals(1L, number(patch.get("radios")));

        Map<String,Object> radio = map(mDatabase.radio(request(
            "/api/radio?scope=p25:BEE00:348&radio_id=1811332")), "radio");
        assertEquals(4L, number(radio.get("talkgroups")));

        List<Map<String,Object>> relationships = rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/relationships?scope=p25:BEE00:348&radio_id=1811332"))).stream()
            .filter(row -> number(row.get("talkgroup_id")) >= 56180L)
            .toList();
        assertEquals(List.of(56180L, 56181L, 56182L), relationships.stream()
            .map(row -> number(row.get("talkgroup_id"))).sorted().toList());
        assertEquals(1L, relationships.stream()
            .filter(row -> number(row.get("target_kind_code")) == 3L).count());

        List<Map<String,Object>> siteTalkgroups = rows(mDatabase.siteTalkgroups(request(
            "/api/site/talkgroups?guid=" + GUID))).stream()
            .filter(row -> number(row.get("talkgroup_id")) >= 56180L)
            .toList();
        assertEquals(3, siteTalkgroups.size());
        assertTrue(siteTalkgroups.stream().allMatch(row -> number(row.get("call_count")) == 1L &&
            number(row.get("encrypted_count")) == 1L && number(row.get("recorded_count")) == 1L &&
            number(row.get("streamed_count")) == 1L));

        Map<String,Object> systemResponse = mDatabase.system(request(
            "/api/system?scope=p25:BEE00:348"));
        Map<String,Object> system = map(systemResponse, "system");
        assertEquals(1L, number(system.get("activity_calls")));
        assertEquals(1L, number(system.get("activity_retained_calls")));
        assertEquals(1L, number(system.get("activity_recorded")));
        assertEquals(1L, number(system.get("activity_streamed")));
        assertEquals(1L, number(system.get("activity_encrypted")));

        List<Map<String,Object>> actionCounts = rowsFrom(systemResponse, "actionCounts");
        assertEquals(2, actionCounts.size());
        assertEquals("CONTINUE", actionCounts.getFirst().get("action"));
        assertEquals(3L, number(actionCounts.getFirst().get("count")));
        assertEquals("GPS", actionCounts.get(1).get("action"));
        assertEquals(2L, number(actionCounts.get(1).get("count")));
        assertTrue(actionCounts.stream().noneMatch(row ->
            "CALL".equals(row.get("action")) || "ENCRYPTED".equals(row.get("action"))));
    }

    @Test
    void keepsSameNumericTalkgroupAndPatchGroupDistinct() throws Exception
    {
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    call_count, target_call_count, join_count
                ) VALUES (1, 1, 60000, 1000, 3000, 2, 2, 4),
                         (1, 3, 60000, 1000, 3000, 3, 3, 5)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_talkgroup_summary (
                    scope_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms,
                    call_count
                ) VALUES (1, 1811332, 60000, 1, 1000, 3000, 2),
                         (1, 1811332, 60000, 3, 1000, 3000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, identity_role_code, identity_kind_code, identity_id, bucket_start_ms,
                    call_count
                ) VALUES (1, 1, 1, 60000, %1$d, 2),
                         (1, 1, 3, 60000, %1$d, 3)
                """.formatted(bucket));
            statement.executeUpdate("""
                UPDATE trunked_radio_affiliation
                SET talkgroup_id = 60000
                WHERE scope_id = 1 AND radio_id = 1811332
                """);
            statement.executeUpdate("""
                UPDATE trunked_identity_summary
                SET last_counterpart_kind_code = 3, last_counterpart_id = 60000
                WHERE scope_id = 1 AND identity_kind_code = 2 AND identity_id = 1811332
                """);
        }

        Map<String,Object> talkgroup = map(mDatabase.talkgroup(request(
            "/api/talkgroup?scope=p25:BEE00:348&talkgroup_id=60000")), "group_identity");
        Map<String,Object> patch = map(mDatabase.talkgroup(request(
            "/api/talkgroup?scope=p25:BEE00:348&talkgroup_id=60000&kind=patch_group")), "group_identity");
        assertEquals(1, number(talkgroup.get("target_kind_code")));
        assertEquals(2, number(talkgroup.get("call_count")));
        assertEquals(1, number(talkgroup.get("affiliated_radios")));
        assertEquals(Boolean.TRUE, map(talkgroup, "capabilities").get("current_affiliations"));
        assertEquals(3, number(patch.get("target_kind_code")));
        assertEquals(3, number(patch.get("call_count")));
        assertEquals(0, number(patch.get("affiliated_radios")));
        assertEquals(Boolean.FALSE, map(patch, "capabilities").get("current_affiliations"));

        Map<String,Object> radio = map(mDatabase.radio(request(
            "/api/radio?scope=p25:BEE00:348&radio_id=1811332")), "radio");
        assertEquals(60000, number(radio.get("last_talkgroup_id")));
        assertEquals(3, number(radio.get("last_talkgroup_kind_code")));
        Map<String,Object> talkerAlias = rows(mDatabase.systemTalkerAliases(request(
            "/api/system/talker-aliases?scope=p25:BEE00:348"))).getFirst();
        assertEquals(60000, number(talkerAlias.get("last_talkgroup_id")));
        assertEquals(3, number(talkerAlias.get("last_talkgroup_kind_code")));

        Map<String,Object> talkgroupHistory = mDatabase.talkgroupActivity(request(
            "/api/talkgroup/activity?scope=p25:BEE00:348&talkgroup_id=60000&range=24h"));
        Map<String,Object> patchHistory = mDatabase.talkgroupActivity(request(
            "/api/talkgroup/activity?scope=p25:BEE00:348&talkgroup_id=60000&kind=patch_group&range=24h"));
        assertEquals(2, number(map(talkgroupHistory, "totals").get("call_count")));
        assertEquals(4, number(map(talkgroupHistory, "totals").get("join_count")));
        assertEquals(3, number(map(patchHistory, "totals").get("call_count")));
        assertEquals(5, number(map(patchHistory, "totals").get("join_count")));

        assertEquals(1, number(rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/relationships?scope=p25:BEE00:348&talkgroup_id=60000"))).getFirst()
            .get("target_kind_code")));
        assertEquals(3, number(rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/relationships?scope=p25:BEE00:348&talkgroup_id=60000&kind=patch_group"))).getFirst()
            .get("target_kind_code")));
    }

    @Test
    void exposesSiteRfTablesAndTypedActivity()
    {
        Map<String,Object> site = map(mDatabase.site(request("/api/site?guid=" + GUID)), "site");
        assertEquals("Cleveland Simulcast", site.get("channel_name"));
        assertEquals(856_137_500L, number(site.get("current_control_hz")));
        assertEquals("WPFF205", site.get("callsign"));
        assertEquals("Motorola (0x90)", site.get("mfid_display"));
        assertEquals(110L, number(site.get("micro_slots")));
        assertEquals("Autonomous and by Request", site.get("data_access"));
        assertEquals("trunked", site.get("site_kind"));
        assertFalse(site.containsKey("site_type"));
        Map<String,Object> capabilities = map(site, "capabilities");
        assertEquals(Boolean.TRUE, capabilities.get("quality"));
        assertEquals(Boolean.TRUE, capabilities.get("frequency_bands"));
        assertEquals(Boolean.TRUE, capabilities.get("patch_groups"));
        assertEquals(Boolean.TRUE, capabilities.get("group_identities"));
        assertEquals(Boolean.TRUE, capabilities.get("activity"));
        assertEquals(Boolean.TRUE, capabilities.get("current_affiliations"));
        assertEquals(Boolean.TRUE, capabilities.get("radio_site_presence"));

        List<Map<String,Object>> channels = rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=" + GUID)));
        Map<String,Object> sharedChannel = channels.stream()
            .filter(row -> "0-509".equals(row.get("channel_key"))).findFirst().orElseThrow();
        Map<String,Object> controlChannel = channels.stream()
            .filter(row -> "0-821".equals(row.get("channel_key"))).findFirst().orElseThrow();
        assertEquals(List.of("VOICE", "DATA"), sharedChannel.get("tags"));
        assertEquals(4L, number(sharedChannel.get("voice_grant_observations")));
        assertEquals(2L, number(sharedChannel.get("data_grant_observations")));
        assertEquals(2L, number(sharedChannel.get("logical_channel_count")));
        assertEquals(1L, number(sharedChannel.get("logical_channels_included")));
        assertEquals(1L, number(sharedChannel.get("logical_channels_truncated")));
        assertEquals(854_187_500L, number(sharedChannel.get("downlink_hz")));
        assertEquals("0-821", controlChannel.get("descriptor"));
        assertEquals("WPFF205", controlChannel.get("callsign"));
        assertEquals(List.of("CURRENT_CONTROL"), controlChannel.get("current_tags"));
        assertEquals("CURRENT", controlChannel.get("state"));
        assertEquals("CURRENT", channels.stream().filter(row -> "0-900".equals(row.get("descriptor")))
            .findFirst().orElseThrow().get("state"));
        Map<String,Object> channelPage = mDatabase.siteChannels(request(
            "/api/site/channels?guid=" + GUID + "&limit=1&offset=1"));
        assertEquals(1, rows(channelPage).size());
        assertEquals("0-821", rows(channelPage).getFirst().get("descriptor"));
        assertEquals(1, number(channelPage.get("offset")));
        assertTrue((Boolean)channelPage.get("hasMore"));

        List<Map<String,Object>> neighbors = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID)));
        assertEquals("CURRENT", neighbors.get(0).get("state"));
        assertEquals("HISTORICAL", neighbors.get(1).get("state"));
        assertEquals("CURRENT", neighbors.get(2).get("state"));
        assertEquals(5, neighbors.size());
        assertEquals("ISSI", neighbors.get(3).get("entry_type"));
        assertEquals(0xBEE00L, number(neighbors.get(3).get("wacn")));
        assertEquals(0x954L, number(neighbors.get(3).get("system_id")));
        assertEquals(1L, number(neighbors.get(3).get("band_count")));
        assertEquals(1L, number(neighbors.get(3).get("has_fdma")));
        assertEquals("ISSI", neighbors.get(4).get("entry_type"));
        assertEquals(0x9EFL, number(neighbors.get(4).get("system_id")));
        assertEquals(2L, number(neighbors.get(4).get("band_count")));
        assertEquals(1L, number(neighbors.get(4).get("has_fdma")));
        assertEquals(1L, number(neighbors.get(4).get("has_tdma")));
        Map<String,Object> neighborPage = mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID + "&limit=2"));
        assertEquals(2, rows(neighborPage).size());
        assertTrue((Boolean)neighborPage.get("hasMore"));
        assertEquals(2, number(neighborPage.get("nextOffset")));
        Map<String,Object> nextNeighborPage = mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID + "&limit=2&offset=2"));
        assertEquals(2, number(nextNeighborPage.get("offset")));
        assertEquals(2, rows(nextNeighborPage).size());

        Map<String,Object> bands = mDatabase.siteBands(request("/api/site/bands?guid=" + GUID));
        List<Map<String,Object>> foreignBands = rowsFrom(bands, "foreign_rows");
        assertEquals(3, foreignBands.size());
        assertEquals(0x954L, number(foreignBands.get(0).get("foreign_system_id")));
        assertEquals(0x9EFL, number(foreignBands.get(1).get("foreign_system_id")));
        assertEquals(4L, number(foreignBands.get(1).get("band")));
        assertEquals(1L, number(foreignBands.get(1).get("channel_type_code")));
        assertEquals(935_012_500L, number(foreignBands.get(1).get("base_hz")));

        Map<String,Object> patches = mDatabase.sitePatches(request("/api/site/patches?guid=" + GUID));
        assertEquals("Dispatch", rowsFrom(patches, "groups").get(0).get("patch_alias_name"));
        assertEquals("Dispatch", rowsFrom(patches, "talkgroups").get(0).get("alias_name"));
        assertEquals("Engine 1", rowsFrom(patches, "radios").get(0).get("alias_name"));

        Map<String,Object> qualitySite = rowsFrom(mDatabase.qualityHistory(request(
            "/api/v1/sites/" + GUID + "/quality?guid=" + GUID + "&range=1h&points=60")), "sites").getFirst();
        assertEquals(856_137_500L, number(qualitySite.get("quality_frequency_hz")));
        assertEquals(98.5, ((Number)qualitySite.get("decode_health_pct")).doubleValue());

        Map<String,Object> activity = mDatabase.activity(request(
            "/api/activity?scope=p25:BEE00:348&talkgroup_id=56132&limit=1"));
        Map<String,Object> event = rows(activity).get(0);
        assertEquals(1L, number(event.get("target_kind_code")));
        assertEquals("Dispatch", event.get("target_alias_name"));
        assertEquals("Engine 1", event.get("source_alias_name"));
        assertEquals("Dispatch", mDatabase.activityByIds(List.of(number(event.get("id"))))
            .getFirst().get("target_alias_name"));

        Map<String,Object> radioActivity = mDatabase.activity(request(
            "/api/activity?scope=p25:BEE00:348&radio_id=1811332"));
        Map<String,Object> radioTargetEvent = rows(radioActivity).get(0);
        assertEquals(2L, number(radioTargetEvent.get("target_kind_code")));
        assertEquals("Engine 1", radioTargetEvent.get("target_alias_name"));
    }

    @Test
    void includesOnePatchEventOnEachMemberTalkgroupsActivityPage() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (
                    id, context_id, observed_at_ms, action_code, event_type_code, source_radio_id,
                    target_id, target_kind_code, frequency_hz, encrypted
                ) VALUES (500, 1, 2500, 0, 0, 1811332, 60000, 3, 855612500, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO activity_event_talkgroup_member(event_id, talkgroup_id)
                VALUES (500, 56133), (500, 56134)
                """);

            List<String> plan = new ArrayList<>();
            try(PreparedStatement query = connection.prepareStatement("""
                EXPLAIN QUERY PLAN
                SELECT event_id FROM activity_event_talkgroup_member WHERE talkgroup_id = ?
                """))
            {
                query.setInt(1, 56133);

                try(ResultSet resultSet = query.executeQuery())
                {
                    while(resultSet.next())
                    {
                        plan.add(resultSet.getString("detail"));
                    }
                }
            }

            assertTrue(plan.stream().anyMatch(
                detail -> detail.contains("idx_activity_event_member_talkgroup_event")),
                () -> "Expected member/event index, plan was: " + plan);
        }

        List<Map<String,Object>> memberActivity = rows(mDatabase.activity(request(
            "/api/activity?scope=p25:BEE00:348&talkgroup_id=56133")));
        assertEquals(1, memberActivity.size());
        assertEquals(500L, number(memberActivity.getFirst().get("id")));
        assertEquals(60000L, number(memberActivity.getFirst().get("target_id")));
        assertEquals(3L, number(memberActivity.getFirst().get("target_kind_code")));

        List<Map<String,Object>> patchActivity = rows(mDatabase.activity(request(
            "/api/activity?scope=p25:BEE00:348&talkgroup_id=60000&kind=patch_group")));
        assertEquals(1, patchActivity.size());
        assertEquals(500L, number(patchActivity.getFirst().get("id")));

        Map<String,Object> committedEvent = mDatabase.activityByIds(List.of(500L)).getFirst();
        assertEquals(List.of(56133L, 56134L), committedEvent.get("member_talkgroup_ids"));
        assertEquals(2L, number(committedEvent.get("member_talkgroup_ids_total")));
        assertEquals(Boolean.FALSE, committedEvent.get("member_talkgroup_ids_truncated"));
    }

    @Test
    void boundsPatchGroupMemberProjectionPerGroup() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement talkgroup = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group_talkgroup
                    (guid, patch_group, talkgroup_id, confirmed_at_ms)
                VALUES (?, 56132, ?, ?)
                """);
            PreparedStatement radio = connection.prepareStatement("""
                INSERT INTO p25_site_patch_group_radio
                    (guid, patch_group, radio_id, confirmed_at_ms)
                VALUES (?, 56132, ?, ?)
                """))
        {
            long now = System.currentTimeMillis();

            for(int index = 0; index < StatsWebDatabase.MAXIMUM_PATCH_MEMBERS_PER_GROUP + 8; index++)
            {
                talkgroup.setString(1, GUID);
                talkgroup.setInt(2, 62_000 + index);
                talkgroup.setLong(3, now);
                talkgroup.addBatch();
                radio.setString(1, GUID);
                radio.setInt(2, 1_900_000 + index);
                radio.setLong(3, now);
                radio.addBatch();
            }

            talkgroup.executeBatch();
            radio.executeBatch();
        }

        Map<String,Object> response = mDatabase.sitePatches(request("/api/site/patches?guid=" + GUID));
        Map<String,Object> group = rowsFrom(response, "groups").getFirst();
        assertEquals(StatsWebDatabase.MAXIMUM_PATCH_MEMBERS_PER_GROUP + 9L,
            number(group.get("talkgroup_count")));
        assertEquals(StatsWebDatabase.MAXIMUM_PATCH_MEMBERS_PER_GROUP,
            number(group.get("talkgroups_included")));
        assertEquals(Boolean.TRUE, group.get("talkgroups_truncated"));
        assertEquals(StatsWebDatabase.MAXIMUM_PATCH_MEMBERS_PER_GROUP,
            rowsFrom(response, "talkgroups").size());
        assertEquals(Boolean.TRUE, response.get("members_truncated"));
    }

    @Test
    void boundsPatchMembersPerCommittedActivityEvent() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (
                    id, context_id, observed_at_ms, action_code, event_type_code, source_radio_id,
                    target_id, target_kind_code, frequency_hz, encrypted
                ) VALUES (599, 1, 2600, 0, 0, 1811332, 60001, 3, 855612500, 0)
                """);

            try(PreparedStatement members = connection.prepareStatement("""
                INSERT INTO activity_event_talkgroup_member(event_id, talkgroup_id) VALUES (599, ?)
                """))
            {
                for(int index = 0; index < StatsWebDatabase.MAXIMUM_ACTIVITY_EVENT_MEMBERS + 16; index++)
                {
                    members.setInt(1, 70_000 + index);
                    members.addBatch();
                }

                members.executeBatch();
            }
        }

        Map<String,Object> event = mDatabase.activityByIds(List.of(599L)).getFirst();
        @SuppressWarnings("unchecked")
        List<Long> members = (List<Long>)event.get("member_talkgroup_ids");
        assertEquals(StatsWebDatabase.MAXIMUM_ACTIVITY_EVENT_MEMBERS, members.size());
        assertEquals(StatsWebDatabase.MAXIMUM_ACTIVITY_EVENT_MEMBERS + 16L,
            number(event.get("member_talkgroup_ids_total")));
        assertEquals(Boolean.TRUE, event.get("member_talkgroup_ids_truncated"));
    }

    @Test
    void sumsAffiliationSignalingForZeroCallTalkgroups() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms, join_count
                ) VALUES (1, 1, 57000, 1000, 3000, 9)
                """);
        }

        List<Map<String,Object>> talkgroups = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&sort=signaling&direction=desc")));
        Map<String,Object> affiliatedOnly = talkgroups.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 57000L).findFirst().orElseThrow();
        assertEquals(57000L, number(affiliatedOnly.get("talkgroup_id")));
        assertEquals(0L, number(affiliatedOnly.get("call_count")));
        assertEquals(9L, number(affiliatedOnly.get("signaling_count")));
        assertFalse(affiliatedOnly.containsKey("join_count"));
        assertFalse(affiliatedOnly.containsKey("evidence_total"));
        assertFalse(affiliatedOnly.containsKey("evidence_label"));
        assertFalse(affiliatedOnly.containsKey("evidence_count"));
        assertFalse(affiliatedOnly.containsKey("evidence_kind"));
        assertNull(affiliatedOnly.get("alias_name"));
    }

    @Test
    void sortsTalkgroupsBySignalingWithoutCountingCallOutputs() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    grant_count, denial_count, request_count
                ) VALUES (1, 1, 57001, 1000, 3000, 3, 7, 5)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    recorded_count, streamed_count
                ) VALUES (1, 1, 57002, 1000, 3000, 2, 3)
                """);
        }

        List<Map<String,Object>> talkgroups = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&sort=signaling&direction=desc")));
        assertEquals(57001L, number(talkgroups.getFirst().get("talkgroup_id")));
        Map<String,Object> signaling = talkgroups.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 57001L).findFirst().orElseThrow();
        assertEquals(15L, number(signaling.get("signaling_count")));
        assertFalse(signaling.containsKey("denial_count"));
        assertFalse(signaling.containsKey("request_count"));
        assertFalse(signaling.containsKey("evidence_total"));

        Map<String,Object> output = talkgroups.stream()
            .filter(row -> number(row.get("talkgroup_id")) == 57002L).findFirst().orElseThrow();
        assertEquals(0L, number(output.get("signaling_count")));
        assertEquals(2L, number(output.get("recorded_count")));
        assertEquals(3L, number(output.get("streamed_count")));
        assertFalse(output.containsKey("evidence_total"));

        List<Map<String,Object>> compatibilitySort = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&sort=evidence&direction=desc")));
        assertEquals(57001L, number(compatibilitySort.getFirst().get("talkgroup_id")));
    }

    @Test
    void resolvesNeighborNameAndGuidWithinTheSourceWacn() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (
                    guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    lra, mfid, micro_slots, data_service, registration_service, tdma, voice_service,
                    primary_frequency_hz, current_control_hz
                ) VALUES ('neighbor-site-guid', 'neighbor-hash', 1000, 4000, 1, 'APCO25',
                    'Neighbor Simulcast', 'County', 'P25-1', 1, 0x49F, 1, 2,
                    0, 0x90, 110, 1, 1, 1, 1, 855137500, 855137500)
                """);
            seedP25Context(connection, 70, "neighbor-site-guid", 1);
        }

        Map<String,Object> neighbor = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID))).stream()
            .filter(row -> number(row.get("rfss")) == 1 && number(row.get("site")) == 2)
            .findFirst().orElseThrow();
        assertEquals("Neighbor Simulcast", neighbor.get("neighbor_name"));
        assertEquals("neighbor-site-guid", neighbor.get("neighbor_guid"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    first_seen_ms, last_seen_ms
                ) VALUES (70, 'dmr:guid:neighbor-site-guid', 3, 2, 0, 5000, 5000)
                """);
            statement.executeUpdate("""
                UPDATE trunked_identity_scope_context
                SET scope_id = 70, first_seen_ms = 5000, last_seen_ms = 5000
                WHERE context_id = 70
                """);
        }

        neighbor = rows(mDatabase.siteNeighbors(request("/api/site/neighbors?guid=" + GUID))).stream()
            .filter(row -> number(row.get("rfss")) == 1 && number(row.get("site")) == 2)
            .findFirst().orElseThrow();
        assertNull(neighbor.get("neighbor_name"));
        assertNull(neighbor.get("neighbor_guid"));
    }

    @Test
    void excludesAliasListsFromContextsThatNoLongerOwnTheP25Scope() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name, decoder,
                    first_seen_ms, last_seen_ms
                ) VALUES (70, 'transitioned-site', 'transitioned-site-guid', 1, 3, 'Transitioned Site',
                    'Retired', 'DMR', 1000, 5000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (
                    guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count, protocol,
                    channel_name, alias_list_name, decoder, system_key, nac, rfss, site
                ) VALUES ('transitioned-site-guid', 'retained-p25', 1000, 2000, 1, 'APCO25',
                    'Former P25 Site', 'Retired', 'P25-1', 1, 0x123, 1, 9)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    first_seen_ms, last_seen_ms
                ) VALUES (70, 'dmr:guid:transitioned-site-guid', 3, 2, 0, 5000, 5000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context (context_id, scope_id, first_seen_ms, last_seen_ms)
                VALUES (70, 70, 5000, 5000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms, call_count
                ) VALUES (1, 1, 65000, 1000, 2000, 1)
                """);
            statement.executeUpdate("INSERT INTO alias_list (id, name, family) VALUES (70, 'Retired', 'P25')");
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                ) VALUES (70, 70, 'Zulu Retired Alias', 'Retired', 255, 'TALKGROUP', 'APCO25', 65000)
                """);
        }

        Map<String,Object> retired = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&q=65000"))).getFirst();
        assertNull(retired.get("alias_name"));
        assertEquals(65000, number(rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&sort=alias&direction=asc&limit=1")))
            .getFirst().get("talkgroup_id")));
    }

    @Test
    void refreshesCurrentP25AliasOwnershipWithoutWaitingForTheRuleCache() throws Exception
    {
        Map<String,Object> before = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&q=56132"))).getFirst();
        assertEquals("Dispatch", before.get("alias_name"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE p25_site_snapshot SET alias_list_name = NULL WHERE guid = 'test-site-guid'
                """);
            statement.executeUpdate("""
                UPDATE receiver_context SET alias_list_name = NULL WHERE guid = 'test-site-guid'
                """);
        }

        Map<String,Object> after = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&q=56132"))).getFirst();
        assertFalse(after.containsKey("alias_name"));
    }

    @Test
    void rejectsP25OnlyFactsAfterTheGuidTransitionsToAnotherProtocol() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.Snapshot snapshot = trunkedSnapshotAt(5000, GUID,
                TrunkedSiteSchema.PROTOCOL_DMR,
                1, 0, "Transitioned DMR", "Transitioned Site", 10, 20, 1, null, List.of(), List.of());
            assertTrue(TrunkedSiteSchema.upsert(connection, snapshot));
            try(PreparedStatement statement = connection.prepareStatement("""
                UPDATE receiver_context
                SET protocol_code = ?, decoder = 'DMR', last_seen_ms = ?
                WHERE guid = ?
                """))
            {
                statement.setInt(1, TrunkedSiteSchema.PROTOCOL_DMR);
                statement.setLong(2, snapshot.observedAtEpochMilliseconds());
                statement.setString(3, GUID);
                statement.executeUpdate();
            }
        }

        StatsApiException bands = assertThrows(StatsApiException.class,
            () -> mDatabase.siteBands(request("/api/site/bands?guid=" + GUID)));
        StatsApiException patches = assertThrows(StatsApiException.class,
            () -> mDatabase.sitePatches(request("/api/site/patches?guid=" + GUID)));
        assertEquals(404, bands.status());
        assertEquals(404, patches.status());
    }

    @Test
    void sortsOnlyTalkgroupCounterpartsAsLastTalkgroups() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms, call_count,
                    last_counterpart_kind_code, last_counterpart_id, last_talker_alias,
                    last_talker_alias_seen_ms
                ) VALUES (1, 2, 2000000, 1000, 3000, 1, 2, 65001, 'PRIVATE PEER', 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                ) VALUES (71, 1, 'Zulu Talkgroup', 'Test', 255, 'TALKGROUP', 'APCO25', 65001)
                """);
        }

        assertEquals(1811332, number(rows(mDatabase.systemRadios(request(
            "/api/system/radios?scope=p25:BEE00:348&sort=last_talkgroup&direction=desc&limit=1")))
            .getFirst().get("radio_id")));
        assertEquals(1811332, number(rows(mDatabase.systemTalkerAliases(request(
            "/api/system/talker-aliases?scope=p25:BEE00:348&sort=last_talkgroup_name&direction=desc&limit=1")))
            .getFirst().get("radio_id")));
    }

    @Test
    void exposesProtocolAwareEncryptionNames() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, decoder,
                    first_seen_ms, last_seen_ms
                ) VALUES
                    (20, 'dmr-encryption', 'dmr-encryption-guid', 1, 3, 'DMR Encryption', 'DMR', 1000, 3000),
                    (21, 'nxdn-encryption', 'nxdn-encryption-guid', 1, 4, 'NXDN Encryption', 'NXDN', 1000, 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (
                    context_id, observed_at_ms, action_code, event_type_code, encrypted,
                    encryption_algorithm_id, encryption_key_id
                ) VALUES
                    (1, 3001, 0, 0, 1, 1, 17),
                    (20, 3002, 0, 0, 1, 1, 17),
                    (21, 3003, 0, 0, 1, 1, 17),
                    (20, 3004, 0, 0, 1, NULL, NULL)
                """);
            statement.executeUpdate("""
                UPDATE trunked_identity_summary
                SET last_encryption_algorithm_id = 132, last_encryption_key_id = 52
                WHERE scope_id = 1 AND identity_kind_code = 1 AND identity_id = 56132
                """);
            statement.executeUpdate("""
                UPDATE trunked_identity_summary
                SET last_encryption_algorithm_id = 132, last_encryption_key_id = 52
                WHERE scope_id = 1 AND identity_kind_code = 2 AND identity_id = 1811332
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    first_seen_ms, last_seen_ms
                ) VALUES (20, 'dmr:guid:dmr-encryption-guid', 3, 2, 0, 1000, 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context (scope_id, context_id, first_seen_ms, last_seen_ms)
                VALUES (20, 20, 1000, 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    encrypted_count, last_encryption_algorithm_id, last_encryption_key_id,
                    last_talker_alias, last_talker_alias_seen_ms
                ) VALUES (20, 2, 1234, 1000, 3000, 1, 1, 17, 'DMR UNIT', 3000)
                """);
        }

        Map<String,Object> p25 = rows(mDatabase.activity(request(
            "/api/activity?context=site-cleveland"))).getFirst();
        assertEquals("BAT-E K:11", p25.get("encryption_display"));
        assertEquals("BATON AUTO EVEN K:11", p25.get("encryption_full_display"));

        List<Map<String,Object>> dmr = rows(mDatabase.activity(request(
            "/api/activity?context=dmr-encryption")));
        assertEquals("ENC", dmr.getFirst().get("encryption_display"));
        assertEquals("HYT-BP K:11", dmr.get(1).get("encryption_display"));
        assertEquals("Hytera Basic Privacy K:11", dmr.get(1).get("encryption_full_display"));

        Map<String,Object> nxdn = rows(mDatabase.activity(request(
            "/api/activity?context=nxdn-encryption"))).getFirst();
        assertEquals("SCRAM K:11", nxdn.get("encryption_display"));
        assertEquals("Scrambler K:11", nxdn.get("encryption_full_display"));

        long p25Id = number(p25.get("id"));
        assertEquals("BAT-E K:11", mDatabase.activityByIds(List.of(p25Id)).getFirst()
            .get("encryption_display"));

        Map<String,Object> talkgroup = map(mDatabase.talkgroup(request(
            "/api/talkgroup?scope=p25:BEE00:348&talkgroup_id=56132")), "group_identity");
        assertEquals("AES256", talkgroup.get("last_encryption_algorithm_display"));
        assertEquals("AES-256", talkgroup.get("last_encryption_algorithm_name"));
        Map<String,Object> radio = map(mDatabase.radio(request(
            "/api/radio?scope=p25:BEE00:348&radio_id=1811332")), "radio");
        assertEquals("AES256", radio.get("last_encryption_algorithm_display"));
        assertEquals("AES-256", radio.get("last_encryption_algorithm_name"));

        Map<String,Object> dmrRadio = map(mDatabase.radio(request(
            "/api/radio?scope=dmr:guid:dmr-encryption-guid&radio_id=1234")), "radio");
        assertEquals("Hytera Basic Privacy", dmrRadio.get("last_encryption_algorithm_name"));
        assertEquals("DMR UNIT", dmrRadio.get("last_talker_alias"));
        assertEquals(Boolean.TRUE, map(dmrRadio, "capabilities").get("talker_aliases"));
        assertEquals(Boolean.FALSE, map(dmrRadio, "capabilities").get("radio_site_presence"));
        assertEquals(1, rows(mDatabase.systemTalkerAliases(request(
            "/api/system/talker-aliases?scope=dmr:guid:dmr-encryption-guid"))).size());
    }

    @Test
    void countsRetainedPhysicalP25ChannelsConsistently() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary (guid, channel_key, descriptor, downlink_hz, uplink_hz,
                    tdma, timeslots, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', '2-1470', '2-1470', 771193750, NULL, 1, 2, 1000, 2000, 2),
                    ('test-site-guid', '10-2940', '10-2940', 771193750, NULL, 1, 2, 1000, 2000, 2),
                    ('test-site-guid', '2-999', '2-999', NULL, NULL, 1, 2, 1000, 2000, 1),
                    ('test-site-guid', '10-1998', '10-1998', NULL, NULL, 1, 2, 1000, 2000, 1)
                """);
        }

        assertEquals(6, number(map(mDatabase.site(request("/api/site?guid=" + GUID)), "site").get("channels")));
        assertEquals(6, rows(mDatabase.siteChannels(request("/api/site/channels?guid=" + GUID))).size());
        assertEquals(1, rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=" + GUID + "&limit=1"))).size());

        Map<String,Object> recentSite = rowsFrom(mDatabase.dashboard(), "recentReceivers").stream()
            .filter(row -> GUID.equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals(6, number(recentSite.get("channels")));

        Map<String,Object> directorySite = rows(mDatabase.systemDirectory(request("/api/system-directory"))).stream()
            .flatMap(system -> systemSitesFor(system).stream())
            .filter(row -> GUID.equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals(6, number(directorySite.get("channels")));
    }

    @Test
    void exposesConventionalContextsSeparately()
    {
        Map<String,Object> conventional = mDatabase.conventional(request("/api/conventional"));
        assertEquals(1, rows(conventional).size());
        assertEquals("County Fire", rows(conventional).get(0).get("channel_name"));
        assertEquals(10L, number(rows(conventional).get(0).get("protocol_code")));

        Map<String,Object> detail = mDatabase.conventionalDetail(request(
            "/api/conventional/detail?context=conventional-fire"));
        assertEquals("County Fire", map(detail, "context").get("channel_name"));
        assertEquals(10L, number(map(detail, "context").get("protocol_code")));
        assertTrue(rowsFrom(detail, "summaries").get(0).containsKey("frequency_hz"));
    }

    @Test
    void exposesAmAsItsOwnProtocolUnderConventional() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, kind_code, protocol_code, channel_name,
                    decoder, first_seen_ms, last_seen_ms, primary_frequency_hz)
                VALUES (99, 'conventional-airport', 10, 11, 'Airport Tower', 'AM', 1000, 2000, 118500000)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary (context_id, frequency_hz, timeslot, first_seen_ms,
                    last_seen_ms, call_count)
                VALUES (99, 118500000, -1, 1000, 2000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, frequency_hz)
                VALUES (99, 2000, 3, 118500000)
                """);
        }

        Map<String,Object> row = rows(mDatabase.conventional(request("/api/conventional"))).stream()
            .filter(value -> "conventional-airport".equals(value.get("context_key")))
            .findFirst().orElseThrow();
        assertEquals(11L, number(row.get("protocol_code")));
        assertEquals("am", StatsApiV1Payload.present(row).path("protocol").textValue());

        Map<String,Object> detail = mDatabase.conventionalDetail(request(
            "/api/conventional/detail?context=conventional-airport"));
        assertEquals(11L, number(map(detail, "context").get("protocol_code")));
        assertTrue((Boolean)map(map(detail, "context"), "capabilities").get("activity"));

        Map<String,Object> event = rows(mDatabase.activity(request(
            "/api/activity?context=conventional-airport"))).getFirst();
        assertEquals("AM", event.get("protocol"));
        assertEquals("am", StatsApiV1Payload.present(event).path("protocol").textValue());
    }

    @Test
    void exposesConventionalDmrIdentitiesWithExactContextAliases() throws Exception
    {
        seedDmrConventionalRows(mDatabasePath);

        Map<String,Object> detail = mDatabase.conventionalDetail(request(
            "/api/conventional/detail?context=conventional-dmr-county"));
        Map<String,Object> capabilities = map(map(detail, "context"), "capabilities");
        assertTrue((Boolean)capabilities.get("group_identities"));
        assertTrue((Boolean)capabilities.get("radios"));
        assertTrue((Boolean)capabilities.get("activity"));

        Map<String,Object> analogDetail = mDatabase.conventionalDetail(request(
            "/api/conventional/detail?context=conventional-fire"));
        Map<String,Object> analogCapabilities = map(map(analogDetail, "context"), "capabilities");
        assertTrue((Boolean)analogCapabilities.get("activity"));
        assertFalse((Boolean)analogCapabilities.get("group_identities"));
        assertFalse((Boolean)analogCapabilities.get("radios"));

        Map<String,Object> talkgroups = mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-county&sort=talkgroup&direction=asc"));
        assertEquals(2, rows(talkgroups).size());
        Map<String,Object> dispatch = rows(talkgroups).getFirst();
        assertEquals(91L, number(dispatch.get("talkgroup_id")));
        assertEquals("DMR Dispatch", dispatch.get("alias_name"));
        assertEquals("County DMR", dispatch.get("alias_list_name"));
        assertEquals("DMR Engine 1", dispatch.get("last_source_alias_name"));
        assertEquals(451_012_500L, number(dispatch.get("frequency_hz")));
        assertEquals(1L, number(dispatch.get("timeslot")));
        assertEquals(10L, number(dispatch.get("call_count")));
        assertEquals(2L, number(dispatch.get("encrypted_count")));

        Map<String,Object> radios = mDatabase.conventionalRadios(request(
            "/api/conventional/radios?context=conventional-dmr-county&sort=radio&direction=asc"));
        assertEquals(2, rows(radios).size());
        Map<String,Object> engine = rows(radios).getFirst();
        assertEquals(123_456L, number(engine.get("radio_id")));
        assertEquals("DMR Engine 1", engine.get("alias_name"));
        assertEquals("DMR Dispatch", engine.get("last_talkgroup_alias_name"));
        assertEquals("DMR Engine 2", engine.get("last_peer_alias_name"));
        assertEquals(7L, number(engine.get("source_call_count")));
        assertEquals(3L, number(engine.get("target_call_count")));
        assertFalse(engine.values().contains("Other Dispatch"));
        assertFalse(engine.values().contains("Other Engine"));
    }

    @Test
    void conventionalDmrIdentityPagesSortSearchAndStayContextScoped() throws Exception
    {
        seedDmrConventionalRows(mDatabasePath);

        Map<String,Object> firstPage = mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-county&sort=calls&limit=1"));
        assertEquals(92L, number(rows(firstPage).getFirst().get("talkgroup_id")));
        assertEquals(1L, number(firstPage.get("limit")));
        assertEquals(0L, number(firstPage.get("offset")));
        assertTrue((Boolean)firstPage.get("hasMore"));
        assertEquals(1L, number(firstPage.get("nextOffset")));

        Map<String,Object> secondPage = mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-county&sort=calls&limit=1&offset=1"));
        assertEquals(91L, number(rows(secondPage).getFirst().get("talkgroup_id")));
        assertFalse((Boolean)secondPage.get("hasMore"));

        Map<String,Object> aliasSearch = mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-county&q=dispatch"));
        assertEquals(List.of(91L), rows(aliasSearch).stream()
            .map(row -> number(row.get("talkgroup_id"))).toList());
        assertEquals("DMR Dispatch", rows(mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-county&sort=alias&direction=asc&limit=1")))
            .getFirst().get("alias_name"));

        Map<String,Object> radioSearch = mDatabase.conventionalRadios(request(
            "/api/conventional/radios?context=conventional-dmr-county&q=engine%202"));
        assertEquals(List.of(234_567L), rows(radioSearch).stream()
            .map(row -> number(row.get("radio_id"))).toList());
        assertEquals("DMR Engine 2", rows(mDatabase.conventionalRadios(request(
            "/api/conventional/radios?context=conventional-dmr-county&sort=alias&limit=1")))
            .getFirst().get("alias_name"));

        Map<String,Object> otherContext = mDatabase.conventionalTalkgroups(request(
            "/api/conventional/talkgroups?context=conventional-dmr-other"));
        assertEquals(1, rows(otherContext).size());
        assertEquals(999L, number(rows(otherContext).getFirst().get("call_count")));
        assertEquals("Other Dispatch", rows(otherContext).getFirst().get("alias_name"));

        StatsApiException wrongProtocol = assertThrows(StatsApiException.class,
            () -> mDatabase.conventionalTalkgroups(request(
                "/api/conventional/talkgroups?context=conventional-fire")));
        assertEquals(404, wrongProtocol.status());
        StatsApiException missingContext = assertThrows(StatsApiException.class,
            () -> mDatabase.conventionalRadios(request("/api/conventional/radios")));
        assertEquals(400, missingContext.status());
    }

    @Test
    void statusReportsRetainedDetailedHistory()
        throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO logger_status(key, value, updated_at_ms) VALUES (?, ?, 3000)
                """))
        {
            statement.setString(1, "last_successful_write_ms");
            statement.setString(2, "2002");
            statement.executeUpdate();
            statement.setString(1, "last_write_error");
            statement.setString(2, "/private/data/sdrtrunk.sqlite: database is locked");
            statement.executeUpdate();
        }

        Map<String,Object> status = mDatabase.status();
        assertTrue((Boolean)status.get("detailedHistoryAvailable"));
        assertEquals(2001L, number(status.get("lastDetailedHistoryMs")));
        List<Map<String,Object>> logger = rowsFrom(status, "logger");
        assertEquals(1, logger.size());
        assertEquals("last_successful_write_ms", logger.getFirst().get("key"));
        assertEquals(2002L, number(logger.getFirst().get("value")));
        assertFalse(status.toString().contains("/private/data"));
    }

    @Test
    void canHideGrantRowsBeforeActivityPagination() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code)
                VALUES (1, 3000, 11, 0)
                """);
        }

        Map<String,Object> activity = mDatabase.activity(request(
            "/api/activity?hide_grants=true&limit=1"));
        assertEquals(1, rows(activity).size());
        assertFalse("GRANT".equals(rows(activity).getFirst().get("action")));
        assertTrue((Boolean)activity.get("hasMore"));
    }

    @Test
    void activityPaginatesByTimestampAndIdInsteadOfInsertionOrder() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code)
                VALUES (1, 5000, 0, 0), (1, 4000, 0, 0), (1, 6000, 0, 0), (1, 4000, 0, 0)
                """);
        }

        Map<String,Object> firstPage = mDatabase.activity(request(
            "/api/activity?guid=" + GUID + "&limit=2"));
        assertEquals(List.of(6000L, 5000L), rows(firstPage).stream()
            .map(row -> number(row.get("observed_at_ms"))).toList());
        assertTrue((Boolean)firstPage.get("hasMore"));

        long firstCursor = number(firstPage.get("nextBeforeId"));
        Map<String,Object> secondPage = mDatabase.activity(request(
            "/api/activity?guid=" + GUID + "&limit=2&before_id=" + firstCursor));
        assertEquals(List.of(4000L, 4000L), rows(secondPage).stream()
            .map(row -> number(row.get("observed_at_ms"))).toList());
        assertTrue(number(rows(secondPage).getFirst().get("id")) >
            number(rows(secondPage).getLast().get("id")));

        long secondCursor = number(secondPage.get("nextBeforeId"));
        Map<String,Object> thirdPage = mDatabase.activity(request(
            "/api/activity?guid=" + GUID + "&limit=2&before_id=" + secondCursor));
        assertEquals(List.of(2001L, 2000L), rows(thirdPage).stream()
            .map(row -> number(row.get("observed_at_ms"))).toList());
        assertFalse((Boolean)thirdPage.get("hasMore"));
    }

    @Test
    void dashboardActivityActionsReturnsEveryMeaningfulActionAndExcludesContinue() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket (
                    context_id, bucket_start_ms, call_count, emergency_count, continue_count, data_count
                ) VALUES (1, %d, 2, 3, 4, 1)
                """.formatted(currentHour));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket (
                    context_id, frequency_hz, timeslot, bucket_start_ms,
                    call_count, emergency_count, continue_count
                ) VALUES (2, 154310000, -1, %d, 7, 5, 6)
                """.formatted(currentHour));
        }

        Map<String,Object> response = mDatabase.dashboardActivityActions(request(
            "/api/v1/activity/actions?range=24h"));
        assertEquals("24h", response.get("range"));
        assertEquals(18, number(response.get("total")));
        assertEquals(9, actionCount(response, "CALL"));
        assertEquals(8, actionCount(response, "EMERGENCY"));
        assertEquals(1, actionCount(response, "DATA"));
        assertEquals(22, rows(response).size());
        assertTrue(rows(response).stream().anyMatch(row ->
            "ACKNOWLEDGE".equals(row.get("action")) && number(row.get("count")) == 0));
        assertTrue(rows(response).stream().noneMatch(row -> "CONTINUE".equals(row.get("action"))));
    }

    @Test
    void dashboardActivityRadiosAggregatesMoreThanFiveThousandEventsAndUsesSourcesOnly() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        long eventHour = currentHour - 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket (context_id, bucket_start_ms, denial_count)
                VALUES (1, %d, 5102)
                """.formatted(eventHour));
            statement.executeUpdate("""
                WITH RECURSIVE sequence(value) AS (
                    SELECT 1
                    UNION ALL
                    SELECT value + 1 FROM sequence WHERE value < 5101
                )
                INSERT INTO p25_activity_event (
                    context_id, observed_at_ms, action_code, event_type_code, source_radio_id
                )
                SELECT 1, %d + value, 9, 1, 1811332 FROM sequence
                """.formatted(eventHour));
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (
                    context_id, observed_at_ms, action_code, event_type_code,
                    source_radio_id, target_id, target_kind_code
                ) VALUES (1, %d, 9, 1, NULL, 1999999, 2)
                """.formatted(eventHour + 5_102));
        }

        Map<String,Object> response = mDatabase.dashboardActivityRadios(request(
            "/api/v1/activity/radios?range=24h&action=denial&limit=10"));
        assertEquals("DENIAL", response.get("action"));
        assertEquals(5102, number(response.get("action_total")));
        assertEquals(5102, number(response.get("retained_event_count")));
        assertEquals(5101, number(response.get("identified_event_count")));
        assertEquals(1, number(response.get("unknown_source_event_count")));
        assertEquals(1, number(response.get("total_count")));
        assertFalse((Boolean)response.get("hasMore"));
        assertNull(response.get("nextOffset"));
        assertEquals(1, rows(response).size());
        assertEquals(1811332, number(rows(response).getFirst().get("radio_id")));
        assertEquals(5101, number(rows(response).getFirst().get("event_count")));
        assertEquals("Engine 1", rows(response).getFirst().get("alias_name"));
        assertTrue(rows(response).stream().noneMatch(row -> number(row.get("radio_id")) == 1999999));
    }

    @Test
    void dashboardActivityRadiosCoalescesScopesSeparatesFallbackContextsAndPagesExactly() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        long eventHour = currentHour - 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name,
                    decoder, first_seen_ms, last_seen_ms, system_key
                ) VALUES (3, 'site-east', 'site-east-guid', 1, 1, 'East Site', 'County',
                    'P25-1', 1000, 2000, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context (context_id, scope_id, first_seen_ms, last_seen_ms)
                VALUES (3, 1, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket (context_id, bucket_start_ms, emergency_count)
                VALUES (1, %1$d, 3), (3, %1$d, 1)
                """.formatted(eventHour));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket (
                    context_id, frequency_hz, timeslot, bucket_start_ms, emergency_count
                ) VALUES (2, 154310000, -1, %d, 1)
                """.formatted(eventHour));
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (
                    context_id, observed_at_ms, action_code, event_type_code, source_radio_id
                ) VALUES (1, %1$d, 10, 1, 1811332),
                         (1, %2$d, 10, 1, 1811332),
                         (3, %3$d, 10, 1, 1811332),
                         (2, %4$d, 10, 1, 1811332),
                         (1, %5$d, 10, 1, 1811333)
                """.formatted(eventHour + 1_000, eventHour + 2_000, eventHour + 3_000,
                eventHour + 3_500, eventHour + 4_000));
        }

        Map<String,Object> firstPage = mDatabase.dashboardActivityRadios(request(
            "/api/v1/activity/radios?range=24h&action=eMeRgEnCy&limit=2"));
        assertEquals(5, number(firstPage.get("action_total")));
        assertEquals(5, number(firstPage.get("retained_event_count")));
        assertEquals(5, number(firstPage.get("identified_event_count")));
        assertEquals(0, number(firstPage.get("unknown_source_event_count")));
        assertEquals(3, number(firstPage.get("total_count")));
        assertEquals(2, number(firstPage.get("limit")));
        assertEquals(0, number(firstPage.get("offset")));
        assertTrue((Boolean)firstPage.get("hasMore"));
        assertEquals(2, number(firstPage.get("nextOffset")));
        assertEquals(List.of(1811332L, 1811333L), rows(firstPage).stream()
            .map(row -> number(row.get("radio_id"))).toList());
        Map<String,Object> scoped = rows(firstPage).getFirst();
        assertEquals(3, number(scoped.get("event_count")));
        assertEquals("p25:BEE00:348", scoped.get("scope_token"));
        assertEquals(GUID, scoped.get("guid"));
        assertEquals("Engine 1", scoped.get("alias_name"));

        Map<String,Object> secondPage = mDatabase.dashboardActivityRadios(request(
            "/api/v1/activity/radios?range=24h&action=EMERGENCY&limit=2&offset=2"));
        assertEquals(3, number(secondPage.get("total_count")));
        assertEquals(2, number(secondPage.get("offset")));
        assertFalse((Boolean)secondPage.get("hasMore"));
        assertNull(secondPage.get("nextOffset"));
        assertEquals(1, rows(secondPage).size());
        assertEquals(1811332, number(rows(secondPage).getFirst().get("radio_id")));
        assertEquals("conventional-fire", rows(secondPage).getFirst().get("context_key"));
        assertNull(rows(secondPage).getFirst().get("scope_token"));
    }

    @Test
    void dashboardActivityRadiosMakesFrequencylessConventionalDetailDiscoverableInTheCompactTotal() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (
                    context_id, observed_at_ms, action_code, event_type_code, source_radio_id
                ) VALUES (2, %d, 10, 1, 1888000)
                """.formatted(currentHour + 1_000));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket (
                    context_id, frequency_hz, timeslot, bucket_start_ms, emergency_count
                ) VALUES (2, 0, -1, %d, 1)
                """.formatted(currentHour));
        }

        Map<String,Object> response = mDatabase.dashboardActivityRadios(request(
            "/api/v1/activity/radios?range=24h&action=EMERGENCY"));
        assertEquals(1, number(response.get("action_total")));
        assertEquals(1, number(response.get("retained_event_count")));
        assertEquals(1, number(response.get("identified_event_count")));
        assertEquals(1, number(response.get("total_count")));
        assertEquals(1, rows(response).size());
        assertEquals(1888000, number(rows(response).getFirst().get("radio_id")));
        assertEquals("conventional-fire", rows(response).getFirst().get("context_key"));
        assertEquals("NBFM", rows(response).getFirst().get("protocol"));
    }

    @Test
    void dashboardActivityRadiosDeduplicatesConventionalContextHoursAcrossFrequencyBuckets() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket (
                    context_id, frequency_hz, timeslot, bucket_start_ms, register_count
                ) VALUES (2, 154310000, -1, %1$d, 1),
                         (2, 154315000, 1, %1$d, 1)
                """.formatted(currentHour));
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (
                    context_id, observed_at_ms, action_code, event_type_code, source_radio_id
                ) VALUES (2, %d, 20, 1, 1888001)
                """.formatted(currentHour + 1_000));
        }

        Map<String,Object> response = mDatabase.dashboardActivityRadios(request(
            "/api/v1/activity/radios?range=24h&action=REGISTER"));
        assertEquals(2, number(response.get("action_total")));
        assertEquals(1, number(response.get("retained_event_count")));
        assertEquals(1, number(response.get("identified_event_count")));
        assertEquals(1, number(response.get("total_count")));
        assertEquals(1, rows(response).size());
        assertEquals(1, number(rows(response).getFirst().get("event_count")),
            "One context-hour slice must not duplicate its retained event across frequency/timeslot buckets");
    }

    @Test
    void dashboardActivityRadiosRejectsUnsupportedActionsAndUsesIndexedRetainedDetailPaths() throws Exception
    {
        StatsApiException missingAction = assertThrows(StatsApiException.class, () ->
            mDatabase.dashboardActivityRadios(request("/api/v1/activity/radios")));
        assertEquals(400, missingAction.status());
        assertEquals("action", missingAction.field());

        for(String action: List.of("CONTINUE", "not-real"))
        {
            StatsApiException invalidAction = assertThrows(StatsApiException.class, () ->
                mDatabase.dashboardActivityRadios(request(
                    "/api/v1/activity/radios?action=" + action)));
            assertEquals(400, invalidAction.status());
            assertEquals("action", invalidAction.field());
        }

        StatsApiException excessiveLimit = assertThrows(StatsApiException.class, () ->
            mDatabase.dashboardActivityRadios(request(
                "/api/v1/activity/radios?action=CALL&limit=501")));
        assertEquals(400, excessiveLimit.status());
        assertEquals("limit", excessiveLimit.field());

        Map<String,Object> deepPage = mDatabase.dashboardActivityRadios(request(
            "/api/v1/activity/radios?action=CALL&offset=100001"));
        assertEquals(100001, number(deepPage.get("offset")));
        assertFalse((Boolean)deepPage.get("hasMore"));
        assertTrue(rows(deepPage).isEmpty());

        Map<String,Object> maximumOffsetPage = mDatabase.dashboardActivityRadios(request(
            "/api/v1/activity/radios?action=CALL&offset=" + Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, number(maximumOffsetPage.get("offset")));
        assertFalse((Boolean)maximumOffsetPage.get("hasMore"));
        assertNull(maximumOffsetPage.get("nextOffset"));
        assertTrue(rows(maximumOffsetPage).isEmpty());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            List<String> plan = explain(connection, """
                WITH action_slices AS MATERIALIZED (
                    SELECT bucket.context_id, bucket.bucket_start_ms
                    FROM p25_site_activity_bucket AS bucket
                        INDEXED BY idx_p25_site_activity_bucket_time
                    WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
                      AND bucket.emergency_count > 0
                    UNION
                    SELECT bucket.context_id, bucket.bucket_start_ms
                    FROM conventional_activity_bucket AS bucket
                        INDEXED BY idx_conventional_bucket_dashboard_time
                    WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
                      AND bucket.emergency_count > 0
                ), grouped AS MATERIALIZED (
                    SELECT ownership.scope_id,
                        CASE WHEN ownership.scope_id IS NULL THEN event.context_id END
                            AS fallback_context_id,
                        event.source_radio_id AS radio_id, COUNT(*) AS event_count,
                        MAX(event.observed_at_ms) AS last_seen_ms
                    FROM action_slices AS slice
                    CROSS JOIN p25_activity_event AS event
                        INDEXED BY idx_p25_activity_event_context_time
                    LEFT JOIN trunked_identity_scope_context ownership
                        ON ownership.context_id = event.context_id
                    WHERE event.context_id = slice.context_id
                      AND event.observed_at_ms >= slice.bucket_start_ms
                      AND event.observed_at_ms < slice.bucket_start_ms + ?
                      AND event.action_code = ?
                    GROUP BY ownership.scope_id,
                        CASE WHEN ownership.scope_id IS NULL THEN event.context_id END,
                        event.source_radio_id
                )
                SELECT scope_id, fallback_context_id, radio_id, event_count, last_seen_ms
                FROM grouped
                WHERE radio_id > 0
                """, 0, Long.MAX_VALUE, 0, Long.MAX_VALUE, 3_600_000, 10);
            assertTrue(plan.stream().anyMatch(detail ->
                    detail.contains("idx_p25_site_activity_bucket_time")),
                () -> "Expected the trunked bucket time index, plan was: " + plan);
            assertTrue(plan.stream().anyMatch(detail ->
                    detail.contains("idx_conventional_bucket_dashboard_time")),
                () -> "Expected the conventional bucket time index, plan was: " + plan);
            assertTrue(plan.stream().anyMatch(detail ->
                    detail.contains("idx_p25_activity_event_context_time") && detail.contains("context_id=?") &&
                        detail.contains("observed_at_ms>?")),
                () -> "Expected context/time-indexed retained-event slices, plan was: " + plan);
            assertTrue(plan.stream().anyMatch(detail -> detail.contains("MATERIALIZE grouped")),
                () -> "Expected one grouped retained-event materialization, plan was: " + plan);
            assertTrue(plan.stream().noneMatch(detail -> detail.contains("MATERIALIZE matching")),
                () -> "Expected no event-cardinality retained-event materialization, plan was: " + plan);
            assertTrue(plan.stream().noneMatch(detail -> detail.equals("SCAN event")),
                () -> "Expected no full retained-event scan, plan was: " + plan);
        }
    }

    @Test
    void scopesAliasesToEachSystemsAssignedAliasList() throws Exception
    {
        seedSecondSystem(mDatabasePath);
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id, call_count
                ) VALUES (3, %d, 1, 1, 56132, 100)
                """.formatted(currentHour));
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);

        Map<String,Object> talkgroup = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:49F"))).get(0);
        assertEquals("Second Dispatch", talkgroup.get("alias_name"));

        Map<String,Object> radio = rows(mDatabase.systemRadios(request(
            "/api/system/radios?scope=p25:BEE00:49F"))).get(0);
        assertEquals("Second Engine", radio.get("alias_name"));

        Map<String,Object> relationship = rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/relationships?scope=p25:BEE00:49F&radio_id=1811332"))).get(0);
        assertEquals("Second Dispatch", relationship.get("talkgroup_alias_name"));
        assertEquals("Second Engine", relationship.get("radio_alias_name"));

        Map<String,Object> event = rows(mDatabase.activity(request(
            "/api/activity?scope=p25:BEE00:49F&talkgroup_id=56132"))).get(0);
        assertEquals("Second Dispatch", event.get("target_alias_name"));
        assertEquals("Second Engine", event.get("source_alias_name"));

        Map<String,Object> dashboardTalkgroup = rowsFrom(mDatabase.dashboard(), "topDestinations").stream()
            .filter(row -> number(row.get("system_id")) == SECOND_SYSTEM)
            .findFirst().orElseThrow();
        assertEquals("Second Dispatch", dashboardTalkgroup.get("alias_name"));
    }

    @Test
    void doesNotUseGlobalOrdinaryAliasesWithoutAnAssignedAliasList() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_system VALUES (2, " + WACN + ", " + SECOND_SYSTEM +
                ", 1000, 2000)");
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (2, 'p25:BEE00:49F', 1, 1, 0, 2, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    call_count, target_call_count, grant_count
                ) VALUES (2, 1, 56132, 1000, 2000, 1, 1, 1)
                """);
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        Map<String,Object> talkgroup = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:49F"))).get(0);
        assertFalse(talkgroup.containsKey("alias_name"));
    }

    @Test
    void dashboardProvidesProtocolNeutralZeroFilledCallsWithoutDoubleCountingGrants() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    decoder, first_seen_ms, last_seen_ms)
                VALUES (7, 'conventional-p25', 'conventional-p25-guid', 2, 1, 'P25 Conventional',
                           'P25-1', 1000, 2000),
                       (8, 'conventional-dmr', 'conventional-dmr-guid', 3, 3, 'DMR Conventional',
                           'DMR', 1000, 2000),
                       (9, 'site-dmr', 'site-dmr-guid', 1, 3, 'DMR Trunked', 'DMR', 1000, 2000),
                       (10, 'site-nxdn', 'site-nxdn-guid', 1, 4, 'NXDN Trunked', 'NXDN', 1000, 2000),
                       (11, 'site-p25-phase2', 'site-p25-phase2-guid', 1, 2, 'P25 Phase 2',
                           'P25-2', 1000, 2000),
                       (12, 'conventional-nxdn', 'conventional-nxdn-guid', 4, 4, 'NXDN Conventional',
                           'NXDN', 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket
                    (context_id, bucket_start_ms, call_count, grant_count, recorded_count, streamed_count,
                     encrypted_count)
                VALUES (1, %1$d, 7, 9, 5, 4, 2),
                       (9, %1$d, 5, 0, 2, 1, 1),
                       (10, %1$d, 6, 0, 3, 2, 2),
                       (11, %1$d, 1, 0, 1, 1, 0)
                """.formatted(currentHour));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket
                    (context_id, frequency_hz, timeslot, bucket_start_ms, call_count, recorded_count,
                     streamed_count, encrypted_count)
                VALUES (2, 154310000, -1, %1$d, 3, 1, 1, 0),
                       (7, 154875000, -1, %1$d, 2, 1, 1, 1),
                       (8, 451012500, 1, %1$d, 4, 2, 1, 1),
                       (12, 461125000, -1, %1$d, 7, 2, 1, 2)
                """.formatted(currentHour));
        }

        Map<String,Object> dashboard = mDatabase.dashboard();
        assertFalse(dashboard.containsKey("activityPerHour"));
        assertTrue(rowsFrom(mDatabase.system(request(
            "/api/system?scope=p25:BEE00:348")), "actionCounts").stream()
            .noneMatch(row -> "CALL".equals(row.get("action")) || "ENCRYPTED".equals(row.get("action"))));
        assertTrue(rowsFrom(mDatabase.system(request(
            "/api/system?scope=p25:BEE00:348")), "actionCounts").stream()
            .anyMatch(row -> "GRANT".equals(row.get("action"))));
        assertFalse(dashboard.containsKey("p25CallActivity"));
        Map<String,Object> callActivity = map(dashboard, "callActivity");
        Map<String,Object> totals = map(callActivity, "totals");
        assertEquals(35, number(totals.get("call_count")));
        assertEquals(17, number(totals.get("recorded_count")));
        assertEquals(12, number(totals.get("streamed_count")));
        assertEquals(9, number(totals.get("encrypted_count")));
        assertFalse(totals.containsKey("non_p25_call_count"));
        assertTrue(number(callActivity.get("metric_start_ms")) > 0);

        List<Map<String,Object>> breakdown = rowsFrom(callActivity, "breakdown");
        Map<String,Object> p25Trunked = breakdown.stream()
            .filter(row -> "P25".equals(row.get("protocol")) && "TRUNKED".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals(8, number(map(p25Trunked, "totals").get("call_count")));
        Map<String,Object> p25Conventional = breakdown.stream()
            .filter(row -> "P25".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals(2, number(map(p25Conventional, "totals").get("call_count")));
        Map<String,Object> nbfmConventional = breakdown.stream()
            .filter(row -> "NBFM".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals(3, number(map(nbfmConventional, "totals").get("call_count")));
        Map<String,Object> amConventional = breakdown.stream()
            .filter(row -> "AM".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals(0, number(map(amConventional, "totals").get("call_count")));
        assertFalse(map(p25Conventional, "totals").containsKey("non_p25_call_count"));

        List<Map<String,Object>> series = rowsFrom(callActivity, "series");
        assertEquals(8 * 24, series.size());
        Map<String,Object> currentP25Conventional = series.stream()
            .filter(row -> number(row.get("time_ms")) == currentHour &&
                "P25".equals(row.get("protocol")) && "CONVENTIONAL".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals(2, number(currentP25Conventional.get("call_count")));
        assertEquals(1, number(currentP25Conventional.get("recorded_count")));
        assertTrue(series.stream()
            .filter(row -> "P25".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")) &&
                number(row.get("time_ms")) < currentHour)
            .allMatch(row -> number(row.get("call_count")) == 0));
        assertTrue(series.stream()
            .filter(row -> ("P25".equals(row.get("protocol")) || "DMR".equals(row.get("protocol"))) &&
                "CONVENTIONAL".equals(row.get("channel_kind")) &&
                number(row.get("time_ms")) < currentHour)
            .allMatch(row -> row.get("encrypted_count") == null));
        assertTrue(series.stream()
            .filter(row -> "NBFM".equals(row.get("protocol")))
            .allMatch(row -> row.get("encrypted_count") == null));
        Map<String,Object> currentNxdnConventional = series.stream()
            .filter(row -> "NXDN".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")) &&
                number(row.get("time_ms")) == currentHour)
            .findFirst().orElseThrow();
        assertEquals(7, number(currentNxdnConventional.get("call_count")));
        assertEquals(2, number(currentNxdnConventional.get("recorded_count")));
        assertEquals(1, number(currentNxdnConventional.get("streamed_count")));
        assertEquals(2, number(currentNxdnConventional.get("encrypted_count")));
        assertTrue(series.stream()
            .filter(row -> ("DMR".equals(row.get("protocol")) || "NXDN".equals(row.get("protocol"))) &&
                "TRUNKED".equals(row.get("channel_kind")) &&
                number(row.get("time_ms")) < currentHour)
            .allMatch(row -> row.get("call_count") == null && row.get("encrypted_count") == null));
        assertEquals("PARTIAL", rowsFrom(callActivity, "coverage").stream()
            .filter(row -> "DMR".equals(row.get("protocol")) &&
                "TRUNKED".equals(row.get("channel_kind")))
            .findFirst().orElseThrow().get("call_count"));
        assertEquals("PARTIAL", rowsFrom(callActivity, "coverage").stream()
            .filter(row -> "NXDN".equals(row.get("protocol")) &&
                "TRUNKED".equals(row.get("channel_kind")))
            .findFirst().orElseThrow().get("encrypted_count"));
        Map<String,Object> nxdnConventionalCoverage = rowsFrom(callActivity, "coverage").stream()
            .filter(row -> "NXDN".equals(row.get("protocol")) &&
                "CONVENTIONAL".equals(row.get("channel_kind")))
            .findFirst().orElseThrow();
        assertEquals("PARTIAL", nxdnConventionalCoverage.get("status"));
        assertEquals("PARTIAL", nxdnConventionalCoverage.get("call_count"));

        Map<String,Object> sourceActivity = map(dashboard, "sourceActivity24h");
        List<Map<String,Object>> sources = rows(sourceActivity);
        assertEquals(8, sources.size());
        assertTrue(sources.stream().allMatch(row -> number(row.get("total_call_count")) == 35));
        assertTrue(sources.stream().anyMatch(row -> "P25".equals(row.get("protocol")) &&
            "CONVENTIONAL".equals(row.get("channel_kind")) &&
            number(row.get("call_count")) == 2));
        assertTrue(sources.stream().anyMatch(row -> "NXDN".equals(row.get("protocol")) &&
            "CONVENTIONAL".equals(row.get("channel_kind")) &&
            number(row.get("call_count")) == 7));
    }

    @Test
    void dashboardRanksEachActivitySourceSeparately() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        long firstHour = currentHour - 23L * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    first_seen_ms, last_seen_ms, system_key, rfss, site)
                VALUES (3, 'site-lakewood', 'test-site-lakewood', 1, 1, 'Lakewood', 1000, 2000, 1, 1, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket (context_id, bucket_start_ms, call_count)
                VALUES (1, %d, 7), (3, %d, 3), (1, %d, 5)
                """.formatted(currentHour, currentHour, firstHour));
        }

        Map<String,Object> activity = map(mDatabase.dashboard(), "sourceActivity24h");
        List<Map<String,Object>> rows = rows(activity);
        assertEquals(2, rows.size());
        assertEquals(GUID, rows.getFirst().get("guid"));
        assertEquals("P25", rows.getFirst().get("protocol"));
        assertEquals("TRUNKED", rows.getFirst().get("channel_kind"));
        assertEquals(12, number(rows.getFirst().get("call_count")));
        assertEquals("test-site-lakewood", rows.getLast().get("guid"));
        assertEquals(3, number(rows.getLast().get("call_count")));
        assertTrue(rows.stream().allMatch(row -> number(row.get("total_call_count")) == 15));
        assertEquals(firstHour, number(activity.get("from_ms")));
    }

    @Test
    void dashboardRanksProtocolNeutralDestinationsAndSourcesFromBoundedIdentityBuckets() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        seedDmrConventionalRows(mDatabasePath);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name,
                    decoder, first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES
                    (7, 'conventional-p25-alias', 'conventional-p25-alias-guid', 2, 1,
                        'P25 Conventional', 'County', 'P25-1', 1000, 2000, 154875000),
                    (9, 'site-dmr-alias', 'site-dmr-alias-guid', 1, 3,
                        'DMR Trunked', NULL, 'DMR', 1000, 2000, 461012500),
                    (10, 'site-nxdn-alias', 'site-nxdn-alias-guid', 1, 4,
                        'NXDN Trunked', NULL, 'NXDN', 1000, 2000, 452012500)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot (
                    guid, snapshot_hash, protocol_code, channel_name, alias_list_name, decoder,
                    primary_frequency_hz, current_control_hz, first_seen_ms, last_seen_ms
                ) VALUES
                    ('site-dmr-alias-guid', 'dmr-alias-hash', 3, 'DMR Trunked',
                        'County DMR', 'DMR', 461012500, 461012500, 1000, 2000),
                    ('site-nxdn-alias-guid', 'nxdn-alias-hash', 4, 'NXDN Trunked',
                        'NXDN County', 'NXDN', 452012500, 452012500, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family)
                VALUES (200, 'NXDN County', 'NXDN')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                ) VALUES
                    (200, 200, 'NXDN Dispatch', 'NXDN Dispatch', 255, 'TALKGROUP', 'NXDN', 77),
                    (201, 200, 'NXDN Unit', 'NXDN Units', 65280, 'RADIO_ID', 'NXDN', 700)
                """);
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                    call_count, encrypted_count, recorded_count, streamed_count
                ) VALUES
                    (1, %1$d, 1, 1, 56132, 8, 2, 5, 4),
                    (1, %1$d, 2, 2, 1811332, 8, 2, 5, 4),
                    (5, %1$d, 1, 1, 91, 12, 3, 6, 2),
                    (5, %1$d, 2, 2, 123456, 12, 3, 6, 2),
                    (7, %1$d, 1, 1, 56132, 10, 1, 4, 1),
                    (7, %1$d, 2, 2, 1811332, 10, 1, 4, 1),
                    (9, %1$d, 1, 1, 91, 9, 1, 2, 1),
                    (9, %1$d, 2, 2, 123456, 9, 1, 2, 1),
                    (10, %1$d, 1, 1, 77, 11, 2, 3, 2),
                    (10, %1$d, 2, 2, 700, 11, 2, 3, 2),
                    (2, %1$d, 1, 0, 0, 4, 0, 1, 0)
                """.formatted(currentHour));
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        Map<String,Object> dashboard = mDatabase.dashboard();
        List<Map<String,Object>> destinations = rowsFrom(dashboard, "topDestinations");
        List<Map<String,Object>> sources = rowsFrom(dashboard, "topSources");
        assertFalse(dashboard.containsKey("topTalkgroups"));
        assertFalse(dashboard.containsKey("topRadios"));
        assertEquals(List.of("DMR", "NXDN", "P25", "DMR", "P25", "NBFM"), destinations.stream()
            .map(row -> String.valueOf(row.get("protocol"))).toList());
        assertEquals(List.of("DMR", "NXDN", "P25", "DMR", "P25"), sources.stream()
            .map(row -> String.valueOf(row.get("protocol"))).toList());

        Map<String,Object> dmrDestination = destinations.getFirst();
        assertEquals("CONVENTIONAL", dmrDestination.get("channel_kind"));
        assertEquals("Talkgroup", dmrDestination.get("identity_kind"));
        assertEquals("DMR Dispatch", dmrDestination.get("alias_name"));
        assertEquals("conventional-talkgroups", dmrDestination.get("identity_detail_view"));
        assertEquals(12, number(dmrDestination.get("call_count")));
        assertEquals(6, number(dmrDestination.get("recorded_count")));
        assertEquals(2, number(dmrDestination.get("streamed_count")));

        Map<String,Object> nxdnDestination = destinations.stream()
            .filter(row -> "NXDN".equals(row.get("protocol"))).findFirst().orElseThrow();
        assertEquals("NXDN Dispatch", nxdnDestination.get("alias_name"));
        assertEquals("NXDN County", nxdnDestination.get("alias_list_name"));
        assertEquals(0, number(nxdnDestination.get("identity_detail_available")));
        Map<String,Object> nxdnSource = sources.stream()
            .filter(row -> "NXDN".equals(row.get("protocol"))).findFirst().orElseThrow();
        assertEquals("NXDN Unit", nxdnSource.get("alias_name"));

        Map<String,Object> dmrTrunkedDestination = destinations.stream()
            .filter(row -> "site-dmr-alias".equals(row.get("context_key"))).findFirst().orElseThrow();
        assertEquals("DMR Dispatch", dmrTrunkedDestination.get("alias_name"));
        assertEquals("County DMR", dmrTrunkedDestination.get("alias_list_name"));
        Map<String,Object> dmrTrunkedSource = sources.stream()
            .filter(row -> "site-dmr-alias".equals(row.get("context_key"))).findFirst().orElseThrow();
        assertEquals("DMR Engine 1", dmrTrunkedSource.get("alias_name"));

        Map<String,Object> p25Conventional = destinations.stream()
            .filter(row -> "conventional-p25-alias".equals(row.get("context_key"))).findFirst().orElseThrow();
        assertEquals("Dispatch", p25Conventional.get("alias_name"));
        assertEquals(0, number(p25Conventional.get("identity_detail_available")));

        Map<String,Object> p25TrunkedSource = sources.stream()
            .filter(row -> "P25".equals(row.get("protocol")) &&
                "TRUNKED".equals(row.get("channel_kind"))).findFirst().orElseThrow();
        assertEquals("Engine 1", p25TrunkedSource.get("alias_name"));
        assertEquals("CAR 201", p25TrunkedSource.get("last_talker_alias"));
        assertEquals(2000, number(p25TrunkedSource.get("last_talker_alias_seen_ms")));
        assertEquals("radio", p25TrunkedSource.get("identity_detail_view"));
        assertEquals(1, number(p25TrunkedSource.get("identity_detail_available")));
        assertTrue(destinations.stream().allMatch(row -> row.get("last_talker_alias") == null),
            "Destination identities must never inherit a matching radio's talker alias");

        Map<String,Object> unknownDestination = destinations.stream()
            .filter(row -> number(row.get("identity_kind_code")) == 0).findFirst().orElseThrow();
        assertEquals("Channel / Unknown", unknownDestination.get("identity_kind"));
        assertEquals("County Fire", unknownDestination.get("channel_name"));
        assertFalse(unknownDestination.containsKey("alias_name"),
            "NBFM calls are channel-scoped and do not carry a talkgroup/radio identity");
        assertEquals(0, number(unknownDestination.get("identity_detail_available")));
    }

    @Test
    void dashboardActivityQueriesUseBucketIndexesAtRepresentativeVolume() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        long firstHour = currentHour - 23L * 3_600_000L;
        long previousFirstHour = firstHour - 24L * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            connection.setAutoCommit(false);
            try(PreparedStatement contexts = connection.prepareStatement("""
                INSERT INTO receiver_context (id, context_key, kind_code, protocol_code, first_seen_ms,
                    last_seen_ms, system_key) VALUES (?, ?, 1, 1, 1000, 2000, 1)
                """);
                PreparedStatement buckets = connection.prepareStatement("""
                    INSERT INTO p25_site_activity_bucket (context_id, bucket_start_ms, call_count)
                    VALUES (?, ?, ?)
                    """);
                PreparedStatement identities = connection.prepareStatement("""
                    INSERT INTO call_identity_bucket (
                        context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id, call_count
                    ) VALUES (?, ?, 1, 1, ?, 1)
                    """))
            {
                for(int context = 100; context < 150; context++)
                {
                    contexts.setInt(1, context);
                    contexts.setString(2, "volume-site-" + context);
                    contexts.addBatch();

                    for(int hour = 0; hour < 48; hour++)
                    {
                        buckets.setInt(1, context);
                        buckets.setLong(2, previousFirstHour + hour * 3_600_000L);
                        buckets.setInt(3, 1);
                        buckets.addBatch();

                        identities.setInt(1, context);
                        identities.setLong(2, previousFirstHour + hour * 3_600_000L);
                        identities.setInt(3, 10_000 + context);
                        identities.addBatch();
                    }
                }

                contexts.executeBatch();
                buckets.executeBatch();
                identities.executeBatch();
            }
            connection.commit();

            for(String sql: List.of(StatsWebDatabase.DASHBOARD_CALL_ACTIVITY_SQL,
                StatsWebDatabase.DASHBOARD_SOURCE_ACTIVITY_SQL))
            {
                List<String> plan = new ArrayList<>();
                try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
                {
                    statement.setLong(1, firstHour);
                    statement.setLong(2, currentHour + 3_600_000L);
                    statement.setLong(3, firstHour);
                    statement.setLong(4, currentHour + 3_600_000L);
                    try(ResultSet resultSet = statement.executeQuery())
                    {
                        while(resultSet.next())
                        {
                            plan.add(resultSet.getString("detail"));
                        }
                    }
                }

                assertTrue(plan.stream().anyMatch(
                        detail -> detail.contains("idx_p25_site_activity_bucket_time")),
                    () -> "Expected time-indexed trunked bucket scan, plan was: " + plan);
                assertTrue(plan.stream().anyMatch(
                        detail -> detail.contains("idx_conventional_bucket_dashboard_time")),
                    () -> "Expected indexed conventional bucket scan, plan was: " + plan);
                assertTrue(plan.stream().noneMatch(detail -> detail.contains("p25_activity_event")));
            }

            List<String> identityPlan = new ArrayList<>();
            try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " +
                StatsWebDatabase.DASHBOARD_IDENTITY_ACTIVITY_SQL))
            {
                statement.setLong(1, firstHour);
                statement.setLong(2, currentHour + 3_600_000L);
                statement.setInt(3, 1);
                statement.setInt(4, 20);
                try(ResultSet resultSet = statement.executeQuery())
                {
                    while(resultSet.next())
                    {
                        identityPlan.add(resultSet.getString("detail"));
                    }
                }
            }

            assertTrue(identityPlan.stream().anyMatch(
                    detail -> detail.contains("idx_call_identity_bucket_dashboard_time")),
                () -> "Expected indexed identity bucket scan, plan was: " + identityPlan);
            assertTrue(identityPlan.stream().noneMatch(detail -> detail.contains("p25_activity_event")));
        }
    }

    @Test
    void detailedSiteActivityQueryUsesTheContextTimeIndexAtRepresentativeVolume() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE sequence(value) AS (
                    SELECT 1
                    UNION ALL
                    SELECT value + 1 FROM sequence WHERE value < 50000
                )
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code)
                SELECT 1, 10000 + value, 0, 0 FROM sequence
                """);

            List<String> plan = new ArrayList<>();
            try(PreparedStatement query = connection.prepareStatement("EXPLAIN QUERY PLAN " +
                StatsWebDatabase.ACTIVITY_SELECT_SQL +
                    " AND activity.guid = ?" + StatsWebDatabase.ACTIVITY_ORDER_SQL))
            {
                query.setString(1, GUID);
                query.setInt(2, 201);

                try(ResultSet resultSet = query.executeQuery())
                {
                    while(resultSet.next())
                    {
                        plan.add(resultSet.getString("detail"));
                    }
                }
            }

            assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_p25_activity_event_context_time")),
                () -> "Expected context/time-indexed activity scan, plan was: " + plan);
            assertTrue(plan.stream().noneMatch(detail -> detail.contains("USE TEMP B-TREE")),
                () -> "Expected index-ordered activity results, plan was: " + plan);
        }
    }

    @Test
    void detailedSystemActivityUsesBoundedPerContextCandidates() throws Exception
    {
        assertEquals(200, StatsWebDatabase.MAXIMUM_SYSTEM_ACTIVITY_CONTEXTS);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            StringBuilder sql = new StringBuilder(StatsWebDatabase.ACTIVITY_SELECT_SQL);
            List<Object> parameters = new ArrayList<>();
            StatsWebDatabase.appendScopeActivityCandidates(sql, parameters, List.of(1L, 2L), true,
                null, Long.MAX_VALUE, 201);
            sql.append(StatsWebDatabase.ACTIVITY_ORDER_SQL);
            parameters.add(201);
            List<String> plan = explain(connection, sql.toString(), parameters.toArray());

            assertTrue(plan.stream().anyMatch(
                    detail -> detail.contains("idx_p25_activity_event_context_time")),
                () -> "Expected bounded context/time candidate scans, plan was: " + plan);
            assertTrue(plan.stream().anyMatch(
                    detail -> detail.contains("SEARCH a USING INTEGER PRIMARY KEY")),
                () -> "Expected primary-key activity hydration, plan was: " + plan);
            assertTrue(plan.stream().noneMatch(
                    detail -> detail.contains("SCAN a USING INDEX idx_p25_activity_event_context_time")),
                () -> "Did not expect a full activity index scan, plan was: " + plan);
        }
    }

    @Test
    void foreignBandQueriesUseCompositePrimaryKeys() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            for(String table: List.of("p25_foreign_system_band", "p25_foreign_system_band_summary"))
            {
                List<String> plan = new ArrayList<>();
                try(PreparedStatement query = connection.prepareStatement(
                    "EXPLAIN QUERY PLAN SELECT * FROM " + table + " WHERE guid = ?"))
                {
                    query.setString(1, GUID);
                    try(ResultSet resultSet = query.executeQuery())
                    {
                        while(resultSet.next())
                        {
                            plan.add(resultSet.getString("detail"));
                        }
                    }
                }

                assertTrue(plan.stream().anyMatch(detail -> detail.contains("PRIMARY KEY") &&
                        detail.contains("guid=?")),
                    () -> "Expected GUID-scoped primary-key lookup for " + table + ", plan was: " + plan);
            }
        }
    }

    @Test
    void retainedQualityLookupUsesTheGuidTimeIndex() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement query = connection.prepareStatement("""
                EXPLAIN QUERY PLAN
                SELECT frequency_hz, observed_at_ms
                FROM p25_control_channel_quality
                WHERE guid = ?
                ORDER BY observed_at_ms DESC
                LIMIT ?
                """))
        {
            query.setString(1, GUID);
            query.setInt(2, 100);
            List<String> plan = new ArrayList<>();

            try(ResultSet resultSet = query.executeQuery())
            {
                while(resultSet.next())
                {
                    plan.add(resultSet.getString("detail"));
                }
            }

            assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_p25_control_quality_guid_time")),
                () -> "Expected GUID/time-indexed quality lookup, plan was: " + plan);
        }
    }

    @Test
    void retainedQualityHistoryUsesTheGuidTimeIndex() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement query = connection.prepareStatement("""
                EXPLAIN QUERY PLAN
                SELECT guid, (observed_at_ms / ?) * ? AS time_ms,
                    avg(average_signal_dbfs), avg(decode_health_pct)
                FROM p25_control_channel_quality
                WHERE observed_at_ms >= ? AND observed_at_ms <= ? AND guid = ?
                GROUP BY guid, time_ms
                ORDER BY guid, time_ms
                """))
        {
            query.setLong(1, 60_000L);
            query.setLong(2, 60_000L);
            query.setLong(3, 0L);
            query.setLong(4, System.currentTimeMillis());
            query.setString(5, GUID);
            List<String> plan = new ArrayList<>();

            try(ResultSet resultSet = query.executeQuery())
            {
                while(resultSet.next())
                {
                    plan.add(resultSet.getString("detail"));
                }
            }

            assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_p25_control_quality_guid_time") &&
                    detail.contains("guid=?") && detail.contains("observed_at_ms>?")),
                () -> "Expected GUID/time-indexed quality history, plan was: " + plan);
        }
    }

    @Test
    void providesSystemScopedZeroFilledTalkgroupActivityHistory() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        seedSecondSystem(mDatabasePath);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket
                    (context_id, identity_role_code, identity_kind_code, identity_id, bucket_start_ms,
                     call_count, recorded_count, streamed_count)
                VALUES (1, 1, 1, 56132, %d, 7, 5, 4),
                       (3, 1, 1, 56132, %d, 100, 90, 80),
                       (1, 1, 1, 56132, %d, 999, 999, 999)
                """.formatted(currentHour, currentHour, currentHour + 100L * 3_600_000L));
            statement.executeUpdate("""
                UPDATE trunked_identity_summary
                SET emergency_count = 1
                WHERE scope_id = 1 AND identity_kind_code = 1 AND identity_id = 56132
                """);
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        Map<String,Object> response = mDatabase.talkgroupActivity(request(
            "/api/talkgroup/activity?scope=p25:BEE00:348&talkgroup_id=56132&range=24h"));
        assertEquals("24h", response.get("range"));
        assertEquals(3_600_000L, number(response.get("bucket_ms")));
        assertEquals(metadataValue(mDatabasePath, "trunked_identity_metrics_started_at_ms"),
            number(response.get("metric_start_ms")));
        List<Map<String,Object>> series = rowsFrom(response, "series");
        Map<String,Object> current = series.stream()
            .filter(row -> number(row.get("time_ms")) == currentHour)
            .findFirst().orElseThrow();
        assertEquals(7, number(current.get("call_count")));
        assertFalse(current.containsKey("emergency_count"));
        assertEquals(5, number(current.get("recorded_count")));
        assertEquals(4, number(current.get("streamed_count")));
        assertFalse(current.containsKey("grant_count"));
        assertTrue(series.stream().anyMatch(row -> number(row.get("call_count")) == 0));
        Map<String,Object> totals = map(response, "totals");
        assertEquals(7, number(totals.get("call_count")));
        assertEquals(5, number(totals.get("recorded_count")));
        assertEquals(4, number(totals.get("streamed_count")));
        assertEquals(12, number(totals.get("grant_count")));
        assertEquals(1, number(totals.get("emergency_count")));

        StatsApiException error = assertThrows(StatsApiException.class, () -> mDatabase.talkgroupActivity(request(
            "/api/talkgroup/activity?scope=p25:BEE00:348&talkgroup_id=56132&range=forever")));
        assertEquals(400, error.status());
    }

    @Test
    void aggregatesTopTalkgroupsForOneSiteAndSelectedRange() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        seedSecondSystem(mDatabasePath);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket
                    (context_id, identity_role_code, identity_kind_code, identity_id, bucket_start_ms,
                     call_count, encrypted_count, recorded_count, streamed_count)
                VALUES (1, 1, 1, 56132, %d, 7, 1, 2, 3),
                       (1, 1, 1, 56132, %d, 5, 2, 4, 1),
                       (1, 1, 1, 60000, %d, 20, 0, 0, 0),
                       (1, 1, 1, 56132, %d, 50, 0, 50, 50),
                       (3, 1, 1, 56132, %d, 100, 0, 90, 80),
                       (1, 1, 1, 56132, %d, 999, 0, 999, 999)
                """.formatted(currentHour - 3_600_000L, currentHour, currentHour,
                    currentHour - 25L * 3_600_000L, currentHour,
                    currentHour + 100L * 3_600_000L));
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        Map<String,Object> response = mDatabase.siteTalkgroups(request(
            "/api/site/talkgroups?guid=" + GUID + "&range=24h&limit=20"));
        assertEquals("24h", response.get("range"));
        assertEquals(3_600_000L, number(response.get("bucket_ms")));
        assertEquals(2, rows(response).size());
        assertEquals(60000L, number(rows(response).getFirst().get("talkgroup_id")));

        Map<String,Object> dispatch = rows(response).stream()
            .filter(row -> number(row.get("talkgroup_id")) == 56132L)
            .findFirst().orElseThrow();
        assertEquals("Dispatch", dispatch.get("alias_name"));
        assertEquals(12L, number(dispatch.get("call_count")));
        assertEquals(6L, number(dispatch.get("recorded_count")));
        assertEquals(4L, number(dispatch.get("streamed_count")));
        assertEquals(3L, number(dispatch.get("encrypted_count")));
        assertEquals(currentHour, number(dispatch.get("last_active_ms")));
        assertFalse(dispatch.containsKey("first_seen_ms"));

        Map<String,Object> oneHour = mDatabase.siteTalkgroups(request(
            "/api/site/talkgroups?guid=" + GUID + "&range=1h&limit=20"));
        Map<String,Object> currentDispatch = rows(oneHour).stream()
            .filter(row -> number(row.get("talkgroup_id")) == 56132L)
            .findFirst().orElseThrow();
        assertEquals(5L, number(currentDispatch.get("call_count")));
        assertEquals(4L, number(currentDispatch.get("recorded_count")));
        assertEquals(1L, number(currentDispatch.get("streamed_count")));
    }

    @Test
    void dashboardRecentReceiversIncludeBothTopologiesAndAllSupportedProtocols() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot
                    (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count, channel_name, decoder)
                VALUES ('unidentified-site-guid', 'empty', 3000, 4000, 1, 'No Signal', 'P25-1')
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context (
                    id, context_key, kind_code, protocol_code, channel_name, decoder, nac,
                    first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES
                    (12, 'conventional-no-calls', 10, 0, 'Weather', 'NBFM', NULL,
                        3000, 7000, 162550000),
                    (13, 'trunked-call-before-metadata', 1, 3, 'DMR Call Context', 'DMR', NULL,
                        3000, 8000, 461025000),
                    (14, 'conventional-p25-no-calls', 2, 1, 'Sheriff P25', 'P25-1', 0x293,
                        3000, 9000, 154875000)
                """);
            TrunkedSiteSchema.upsert(connection, trunkedSnapshotAt(5000, "dashboard-dmr",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "Metro DMR", "DMR Dashboard",
                10, 20, 2, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshotAt(6000, "dashboard-nxdn",
                TrunkedSiteSchema.PROTOCOL_NXDN, 2, 4, "Regional NXDN", "NXDN Dashboard",
                7, 8, 9, 5, List.of(), List.of()));
        }

        Map<String,Object> dashboard = mDatabase.dashboard();
        List<Map<String,Object>> receivers = rowsFrom(dashboard, "recentReceivers");
        assertFalse(dashboard.containsKey("recentTrunkedSites"));
        Map<String,Object> p25 = receivers.stream().filter(row -> GUID.equals(row.get("guid")))
            .findFirst().orElseThrow();
        assertEquals(0x49F, number(p25.get("nac")));
        assertEquals(1, number(p25.get("rfss")));
        assertEquals(1, number(p25.get("site")));
        Map<String,Object> dmr = receivers.stream()
            .filter(row -> "dashboard-dmr".equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("DMR", dmr.get("protocol"));
        assertEquals(2, number(dmr.get("site_id")));
        assertNull(dmr.get("nac"));
        assertTrue(receivers.stream().anyMatch(row -> "dashboard-nxdn".equals(row.get("guid")) &&
            "NXDN".equals(row.get("protocol"))));
        assertTrue(receivers.stream().anyMatch(row -> "conventional-fire".equals(row.get("context_key")) &&
            "CONVENTIONAL".equals(row.get("channel_kind")) && "NBFM".equals(row.get("protocol"))));
        assertTrue(receivers.stream().anyMatch(row -> "conventional-no-calls".equals(row.get("context_key"))));
        Map<String,Object> conventionalP25 = receivers.stream()
            .filter(row -> "conventional-p25-no-calls".equals(row.get("context_key")))
            .findFirst().orElseThrow();
        assertEquals(0x293, number(conventionalP25.get("nac")));
        assertNull(conventionalP25.get("rfss"));
        assertNull(conventionalP25.get("site"));
        Map<String,Object> orphanTrunked = receivers.stream()
            .filter(row -> "trunked-call-before-metadata".equals(row.get("context_key")))
            .findFirst().orElseThrow();
        assertEquals("TRUNKED", orphanTrunked.get("channel_kind"));
        assertEquals("DMR", orphanTrunked.get("protocol"));
        assertEquals(0, number(orphanTrunked.get("detail_available")));
        assertEquals(3, number(map(dashboard, "counts").get("conventional_channels")));
        assertTrue(receivers.stream().filter(row -> "TRUNKED".equals(row.get("channel_kind")) &&
                !"trunked-call-before-metadata".equals(row.get("context_key")))
            .allMatch(row -> number(row.get("detail_available")) == 1));
        assertTrue(receivers.stream().anyMatch(row -> "unidentified-site-guid".equals(row.get("guid")) &&
            "P25".equals(row.get("protocol")) && "TRUNKED".equals(row.get("channel_kind"))));
    }

    @Test
    void exposesConfiguredSiteAndNameAcrossP25SiteSummaries() throws Exception
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    sort_order, system_name, site_name, name, radres_guid, decoder_type, config_json
                ) VALUES (1, 'Ohio MARCS', 'Cuyahoga County', 'MARCS Cleveland Simulcast',
                    'test-site-guid', 'P25-1',
                    '{"decodeConfiguration":{"modulation":"AUTO","autoPreferredModulation":"CQPSK"}}')
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_activity_bucket (context_id, bucket_start_ms, call_count)
                VALUES (1, %d, 3)
                """.formatted(currentHour));
            statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code,
                    identity_id, call_count
                ) VALUES (1, %d, 1, 1, 56132, 3)
                """.formatted(currentHour));
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (
                    guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count, protocol,
                    channel_name, alias_list_name, decoder, system_key, nac, rfss, site
                ) VALUES ('configured-neighbor-guid', 'configured-neighbor-hash', 1000, 3000, 1,
                    'APCO25', 'Legacy Neighbor Label', 'County', 'P25-1', 1, 0x49F, 1, 2)
                """);
            seedP25Context(connection, 201, "configured-neighbor-guid", 1);
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    sort_order, system_name, site_name, name, radres_guid, decoder_type, config_json
                ) VALUES (2, 'Ohio MARCS', 'Lake County', 'Painesville Simulcast',
                    'configured-neighbor-guid', 'P25-1', '{}')
                """);
        }

        Map<String,Object> site = map(mDatabase.site(request("/api/site?guid=" + GUID)), "site");
        assertEquals("Cuyahoga County", site.get("configured_site"));
        assertEquals("MARCS Cleveland Simulcast", site.get("configured_name"));
        assertEquals("Cleveland Simulcast", site.get("channel_name"));
        assertEquals("C4FM", site.get("p25_decoder_mode"));
        assertFalse(site.containsKey("p25_auto_preferred_decoder_mode"));

        Map<String,Object> quality = rowsFrom(mDatabase.qualityHistory(request(
            "/api/quality?guid=" + GUID + "&include_history=false")), "sites").getFirst();
        assertEquals("Cuyahoga County", quality.get("configured_site"));
        assertEquals("MARCS Cleveland Simulcast", quality.get("configured_name"));

        Map<String,Object> dashboard = mDatabase.dashboard();
        Map<String,Object> recent = rowsFrom(dashboard, "recentReceivers").stream()
            .filter(row -> GUID.equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("Cuyahoga County", recent.get("configured_site"));
        assertEquals("MARCS Cleveland Simulcast", recent.get("configured_name"));
        Map<String,Object> sourceActivity = rows(map(dashboard, "sourceActivity24h")).stream()
            .filter(row -> GUID.equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("Cuyahoga County", sourceActivity.get("configured_site"));
        assertEquals("MARCS Cleveland Simulcast", sourceActivity.get("configured_name"));
        Map<String,Object> destination = rowsFrom(dashboard, "topDestinations").stream()
            .filter(row -> GUID.equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("Cuyahoga County", destination.get("configured_site"));
        assertEquals("MARCS Cleveland Simulcast", destination.get("configured_name"));

        Map<String,Object> directoryChild = rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=MARCS%20Cleveland%20Simulcast"))).stream()
            .flatMap(parent -> systemSitesFor(parent).stream())
            .filter(row -> GUID.equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("Cuyahoga County", directoryChild.get("configured_site"));
        assertEquals("MARCS Cleveland Simulcast", directoryChild.get("configured_name"));
        Map<String,Object> systemSite = rows(mDatabase.systemSites(request(
            "/api/system/sites?scope=p25:BEE00:348"))).stream()
            .filter(row -> GUID.equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("Cuyahoga County", systemSite.get("configured_site"));
        assertEquals("MARCS Cleveland Simulcast", systemSite.get("configured_name"));

        Map<String,Object> neighbor = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID))).stream()
            .filter(row -> "configured-neighbor-guid".equals(row.get("neighbor_guid")))
            .findFirst().orElseThrow();
        assertEquals("Legacy Neighbor Label", neighbor.get("neighbor_name"));
        assertEquals("Lake County", neighbor.get("neighbor_configured_site"));
        assertEquals("Painesville Simulcast", neighbor.get("neighbor_configured_name"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel
                SET name = CASE radres_guid
                    WHEN 'test-site-guid' THEN 'Zulu Site'
                    WHEN 'configured-neighbor-guid' THEN 'Alpha Site'
                    ELSE name END
                """);
        }

        List<Map<String,Object>> sortedSites = rows(mDatabase.systemSites(request(
            "/api/system/sites?scope=p25:BEE00:348&sort=name&direction=asc")));
        assertEquals("configured-neighbor-guid", sortedSites.getFirst().get("guid"));
    }

    @Test
    void dashboardQualityAggregatesBoundedSiteSeries() throws Exception
    {
        long minute = Math.floorDiv(System.currentTimeMillis(), 60_000L) * 60_000L;
        long first = minute - 120_000L + 1_000L;
        long second = minute - 120_000L + 11_000L;
        long latest = minute - 60_000L + 1_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality (guid, frequency_hz, bucket_start_ms, observed_at_ms,
                    signal_dbfs, average_signal_dbfs, minimum_signal_dbfs, maximum_signal_dbfs,
                    decode_health_pct, valid_frames, invalid_frames, corrected_bits, sync_loss_bits,
                    dropped_bits, last_valid_decode_ms)
                VALUES ('test-site-guid', 856137500, %d, %d, -51.0, -50.0, -55.0, -45.0,
                    80.0, 80, 20, 4, 0, 0, %d),
                    ('test-site-guid', 856137500, %d, %d, -41.0, -40.0, -60.0, -35.0,
                    100.0, 100, 0, 2, 0, 0, %d),
                    ('test-site-guid', 855137500, %d, %d, -61.0, -60.0, -65.0, -55.0,
                    75.0, 75, 25, 6, 0, 0, %d)
                """.formatted(first - Math.floorMod(first, 10_000L), first, first,
                    second - Math.floorMod(second, 10_000L), second, second,
                    latest - Math.floorMod(latest, 10_000L), latest, latest));
        }

        Map<String,Object> response = mDatabase.qualityHistory(request(
            "/api/quality?guid=test-site-guid&range=1h&points=60"));
        assertEquals("1h", response.get("range"));
        assertEquals(60_000L, number(response.get("bucket_ms")));
        assertEquals(60L, number(response.get("target_points")));
        List<Map<String,Object>> sites = rowsFrom(response, "sites");
        assertEquals(1, sites.size());
        Map<String,Object> site = sites.getFirst();
        assertEquals("Cleveland Simulcast", site.get("channel_name"));
        assertEquals(0x49FL, number(site.get("nac")));
        assertEquals(855_137_500L, number(site.get("quality_frequency_hz")));
        assertEquals(-60.0, ((Number)site.get("average_signal_dbfs")).doubleValue());
        List<Map<String,Object>> series = rowsFrom(site, "series");
        assertEquals(2, series.size());
        assertEquals(-45.0, ((Number)series.getFirst().get("average_signal_dbfs")).doubleValue());
        assertEquals(-60.0, ((Number)series.getFirst().get("minimum_signal_dbfs")).doubleValue());
        assertEquals(-35.0, ((Number)series.getFirst().get("maximum_signal_dbfs")).doubleValue());
        assertEquals(90.0, ((Number)series.getFirst().get("decode_health_pct")).doubleValue());
        assertEquals(2L, number(series.getFirst().get("sample_count")));

        Map<String,Object> current = mDatabase.qualityHistory(request(
            "/api/quality?include_history=false"));
        assertFalse((Boolean)current.get("history_included"));
        assertTrue(rowsFrom(rowsFrom(current, "sites").getFirst(), "series").isEmpty());

        StatsApiException error = assertThrows(StatsApiException.class, () ->
            mDatabase.qualityHistory(request("/api/quality?range=forever")));
        assertEquals(400, error.status());
    }

    @Test
    void exposesRetainedQualityForP25DmrAndNxdnThroughOneContract() throws Exception
    {
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("quality-dmr", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 2, "Metro DMR", "DMR Quality", 10, 20, 3, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("quality-nxdn", TrunkedSiteSchema.PROTOCOL_NXDN,
                2, 4, "Regional NXDN", "NXDN Quality", 7, 8, 9, 5, List.of(), List.of()));
            insertQuality(connection, "quality-dmr", 451_012_500L, now - 2_000L, -52.0, 94.0);
            insertQuality(connection, "quality-nxdn", 155_012_500L, now - 1_000L, -61.0, 88.0);
        }

        Map<String,Object> dmrResponse = mDatabase.qualityHistory(request(
            "/api/quality?guid=quality-dmr&range=1h&points=60"));
        Map<String,Object> dmr = rowsFrom(dmrResponse, "sites").getFirst();
        assertEquals("DMR", dmr.get("protocol"));
        assertEquals(TrunkedSiteSchema.PROTOCOL_DMR, number(dmr.get("protocol_code")));
        assertEquals("Metro DMR", dmr.get("configured_system"));
        assertEquals(3, number(dmr.get("site_id")));
        assertEquals(451_012_500L, number(dmr.get("quality_frequency_hz")));
        assertEquals(94.0, ((Number)dmr.get("decode_health_pct")).doubleValue());
        assertEquals(1, rowsFrom(dmr, "series").size());

        Map<String,Object> nxdnResponse = mDatabase.qualityHistory(request(
            "/api/quality?guid=quality-nxdn&range=1h&points=60"));
        Map<String,Object> nxdn = rowsFrom(nxdnResponse, "sites").getFirst();
        assertEquals("NXDN", nxdn.get("protocol"));
        assertEquals(TrunkedSiteSchema.PROTOCOL_NXDN, number(nxdn.get("protocol_code")));
        assertEquals(5, number(nxdn.get("ran")));
        assertEquals(155_012_500L, number(nxdn.get("quality_frequency_hz")));
        assertEquals(88.0, ((Number)nxdn.get("decode_health_pct")).doubleValue());
        assertEquals(1, rowsFrom(nxdn, "series").size());

        List<Map<String,Object>> dmrRows = rowsFrom(rowsFrom(mDatabase.qualityHistory(request(
            "/api/v1/sites/quality-dmr/quality?guid=quality-dmr&range=1h&points=60")), "sites")
            .getFirst(), "series");
        assertEquals(1, dmrRows.size());
        assertEquals(451_012_500L, number(dmrRows.getFirst().get("frequency_hz")));

        List<Map<String,Object>> allSites = rowsFrom(mDatabase.qualityHistory(request(
            "/api/quality?include_history=false")), "sites");
        assertTrue(allSites.stream().anyMatch(row -> "P25".equals(row.get("protocol"))));
        assertTrue(allSites.stream().anyMatch(row -> "DMR".equals(row.get("protocol"))));
        assertTrue(allSites.stream().anyMatch(row -> "NXDN".equals(row.get("protocol"))));
    }

    @Test
    void exposesConfiguredSiteAndNameAcrossDmrAndNxdnSiteSummaries() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("configured-dmr",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "Metro DMR", "Legacy DMR Label",
                10, 20, 1, null, List.of(),
                List.of(new TrunkedSiteSchema.Neighbor(1, 2, 10, 20, 2, 42, 451_012_500L, 1))));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("configured-dmr-neighbor",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "Metro DMR", "Legacy DMR Neighbor",
                10, 20, 2, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("configured-nxdn",
                TrunkedSiteSchema.PROTOCOL_NXDN, 2, 4, "Regional NXDN", "Legacy NXDN Label",
                7, 8, 9, 5, List.of(), List.of()));
            seedContextScope(connection, 211, 211, "configured-dmr", TrunkedSiteSchema.PROTOCOL_DMR, 2);
            seedContextScope(connection, 212, 212, "configured-dmr-neighbor",
                TrunkedSiteSchema.PROTOCOL_DMR, 2);
            seedContextScope(connection, 213, 213, "configured-nxdn", TrunkedSiteSchema.PROTOCOL_NXDN, 2);
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    sort_order, system_name, site_name, name, radres_guid, decoder_type, config_json
                ) VALUES
                    (211, 'Metro DMR', 'Cuyahoga County', 'Downtown DMR',
                        'configured-dmr', 'DMR', '{}'),
                    (212, 'Metro DMR', 'Lake County', 'East DMR',
                        'configured-dmr-neighbor', 'DMR', '{}'),
                    (213, 'Regional NXDN', 'Geauga County', 'Chardon NXDN',
                        'configured-nxdn', 'NXDN', '{}')
                """);
        }

        Map<String,Object> dmrSite = map(mDatabase.site(request(
            "/api/site?guid=configured-dmr")), "site");
        assertEquals("Cuyahoga County", dmrSite.get("configured_site"));
        assertEquals("Downtown DMR", dmrSite.get("configured_name"));
        assertEquals("Legacy DMR Label", dmrSite.get("channel_name"));
        Map<String,Object> nxdnSite = map(mDatabase.site(request(
            "/api/site?guid=configured-nxdn")), "site");
        assertEquals("Geauga County", nxdnSite.get("configured_site"));
        assertEquals("Chardon NXDN", nxdnSite.get("configured_name"));
        assertEquals("Legacy NXDN Label", nxdnSite.get("channel_name"));

        Map<String,Object> dmrQuality = rowsFrom(mDatabase.qualityHistory(request(
            "/api/quality?guid=configured-dmr&include_history=false")), "sites").getFirst();
        assertEquals("Cuyahoga County", dmrQuality.get("configured_site"));
        assertEquals("Downtown DMR", dmrQuality.get("configured_name"));
        Map<String,Object> nxdnQuality = rowsFrom(mDatabase.qualityHistory(request(
            "/api/quality?guid=configured-nxdn&include_history=false")), "sites").getFirst();
        assertEquals("Geauga County", nxdnQuality.get("configured_site"));
        assertEquals("Chardon NXDN", nxdnQuality.get("configured_name"));

        List<Map<String,Object>> recent = rowsFrom(mDatabase.dashboard(), "recentReceivers");
        Map<String,Object> dmrRecent = recent.stream()
            .filter(row -> "configured-dmr".equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("Cuyahoga County", dmrRecent.get("configured_site"));
        assertEquals("Downtown DMR", dmrRecent.get("configured_name"));
        Map<String,Object> nxdnRecent = recent.stream()
            .filter(row -> "configured-nxdn".equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("Geauga County", nxdnRecent.get("configured_site"));
        assertEquals("Chardon NXDN", nxdnRecent.get("configured_name"));

        Map<String,Object> dmrChild = rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=Downtown%20DMR"))).stream()
            .flatMap(parent -> systemSitesFor(parent).stream())
            .filter(row -> "configured-dmr".equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("Cuyahoga County", dmrChild.get("configured_site"));
        assertEquals("Downtown DMR", dmrChild.get("configured_name"));
        Map<String,Object> nxdnChild = rows(mDatabase.systemSites(request(
            "/api/system/sites?scope=nxdn:guid:configured-nxdn"))).getFirst();
        assertEquals("Geauga County", nxdnChild.get("configured_site"));
        assertEquals("Chardon NXDN", nxdnChild.get("configured_name"));

        Map<String,Object> neighbor = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=configured-dmr"))).getFirst();
        assertEquals("Legacy DMR Neighbor", neighbor.get("neighbor_name"));
        assertEquals("Lake County", neighbor.get("neighbor_configured_site"));
        assertEquals("East DMR", neighbor.get("neighbor_configured_name"));
    }

    @Test
    void resolvesRetainedProtocolTransitionsByLatestObservationWithP25TieBreak() throws Exception
    {
        long trunkedLastSeen = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshotAt(trunkedLastSeen, GUID,
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "Transitioned DMR", "DMR Receiver", 10, 20, 2, null,
                List.of(new TrunkedSiteSchema.Channel(42, null, 1, 451_000_000L, 456_000_000L, 1)),
                List.of(new TrunkedSiteSchema.Neighbor(1, 2, 10, 20, 3, 43, 452_000_000L, 1))));

            try(PreparedStatement statement = connection.prepareStatement(
                "UPDATE receiver_context SET protocol_code = 3, decoder = 'DMR', last_seen_ms = ? WHERE guid = ?"))
            {
                statement.setLong(1, trunkedLastSeen + 1_000L);
                statement.setString(2, GUID);
                statement.executeUpdate();
            }
            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("""
                    INSERT INTO trunked_identity_scope (
                        scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                        first_seen_ms, last_seen_ms
                    ) VALUES (30, 'dmr:guid:test-site-guid', 3, 2, 0, 1000, 3000)
                    """);
                statement.executeUpdate("""
                    UPDATE trunked_identity_scope_context SET scope_id = 30 WHERE context_id = 1
                    """);
            }
        }

        Map<String,Object> latest = map(mDatabase.site(request("/api/site?guid=" + GUID)), "site");
        assertEquals("DMR", latest.get("protocol"));
        assertEquals(TrunkedSiteSchema.PROTOCOL_DMR, number(latest.get("protocol_code")));
        assertEquals(42, number(rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=" + GUID))).getFirst().get("channel_number")));
        assertEquals(3, number(rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID))).getFirst().get("site_id")));
        CSVRecord dmrChannelExport = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=site-channels&guid=" + GUID))).getFirst();
        assertEquals("DMR", dmrChannelExport.get("protocol"));
        assertEquals("10", dmrChannelExport.get("network_id"));
        assertEquals("20", dmrChannelExport.get("system_id"));
        assertEquals("2", dmrChannelExport.get("site_id"));

        List<Map<String,Object>> qualitySites = rowsFrom(mDatabase.qualityHistory(request(
            "/api/quality?guid=" + GUID + "&include_history=false")), "sites");
        assertEquals(1, qualitySites.size());
        assertEquals("DMR", qualitySites.getFirst().get("protocol"));
        assertNull(qualitySites.getFirst().get("nac"));

        List<Map<String,Object>> directory = rows(mDatabase.systemDirectory(request(
            "/api/system-directory")));
        List<Map<String,Object>> directoryChildren = directory.stream()
            .flatMap(parent -> systemSitesFor(parent).stream())
            .filter(child -> GUID.equals(child.get("guid"))).toList();
        assertEquals(1, directoryChildren.size());
        assertEquals("DMR", directoryChildren.getFirst().get("protocol"));
        Map<String,Object> transitionedDmrPreview = rows(mDatabase.systemDirectory(request(
            "/api/system-directory?include_site_preview=true"))).stream()
            .filter(parent -> "DMR".equals(parent.get("protocol")))
            .flatMap(parent -> rowsFrom(parent, "site_preview").stream())
            .filter(child -> GUID.equals(child.get("guid"))).findFirst().orElseThrow();
        assertEquals("Transitioned DMR", transitionedDmrPreview.get("configured_system"));
        assertEquals("DMR Receiver", transitionedDmrPreview.get("channel_name"));
        assertEquals(451_000_000L, number(transitionedDmrPreview.get("current_control_hz")));
        assertEquals(trunkedLastSeen, number(transitionedDmrPreview.get("last_seen_ms")));
        Map<String,Object> retainedP25Parent = directory.stream()
            .filter(parent -> "P25".equals(parent.get("protocol"))).findFirst().orElseThrow();
        assertEquals(0, number(retainedP25Parent.get("sites")));
        assertNull(retainedP25Parent.get("site_names"));
        Map<String,Object> recentReceiver = rowsFrom(mDatabase.dashboard(), "recentReceivers").stream()
            .filter(receiver -> GUID.equals(receiver.get("guid"))).findFirst().orElseThrow();
        assertEquals("DMR", recentReceiver.get("protocol"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE p25_site_snapshot SET last_seen_ms = ? WHERE guid = ?"))
        {
            statement.setLong(1, trunkedLastSeen);
            statement.setString(2, GUID);
            statement.executeUpdate();
            try(Statement ownership = connection.createStatement())
            {
                ownership.executeUpdate("""
                    UPDATE receiver_context SET protocol_code = 1, decoder = 'P25-1' WHERE id = 1
                    """);
                ownership.executeUpdate("""
                    UPDATE trunked_identity_scope_context SET scope_id = 1 WHERE context_id = 1
                    """);
            }
        }

        Map<String,Object> tied = map(mDatabase.site(request("/api/site?guid=" + GUID)), "site");
        assertEquals(1, number(tied.get("protocol_code")));
        assertEquals("trunked", tied.get("site_kind"));
        assertNotNull(rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=" + GUID))).getFirst().get("descriptor"));
        assertFalse(rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=" + GUID))).getFirst().containsKey("site_id"));
        CSVRecord p25ChannelExport = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=site-channels&guid=" + GUID))).getFirst();
        assertEquals("P25", p25ChannelExport.get("protocol"));
        assertEquals("BEE00", p25ChannelExport.get("wacn_hex"));
        assertEquals("49F", p25ChannelExport.get("nac_hex"));

        qualitySites = rowsFrom(mDatabase.qualityHistory(request(
            "/api/quality?guid=" + GUID + "&include_history=false")), "sites");
        assertEquals(1, qualitySites.size());
        assertEquals("P25", qualitySites.getFirst().get("protocol"));
        assertEquals(0x49FL, number(qualitySites.getFirst().get("nac")));

        directory = rows(mDatabase.systemDirectory(request("/api/system-directory")));
        directoryChildren = directory.stream().flatMap(parent -> systemSitesFor(parent).stream())
            .filter(child -> GUID.equals(child.get("guid"))).toList();
        assertEquals(1, directoryChildren.size());
        assertEquals("P25", directoryChildren.getFirst().get("protocol"));
        Map<String,Object> transitionedP25Preview = rows(mDatabase.systemDirectory(request(
            "/api/system-directory?include_site_preview=true"))).stream()
            .filter(parent -> "P25".equals(parent.get("protocol")))
            .flatMap(parent -> rowsFrom(parent, "site_preview").stream())
            .filter(child -> GUID.equals(child.get("guid"))).findFirst().orElseThrow();
        assertEquals("Cleveland Simulcast", transitionedP25Preview.get("channel_name"));
        assertEquals(856_137_500L, number(transitionedP25Preview.get("current_control_hz")));
        assertEquals(trunkedLastSeen, number(transitionedP25Preview.get("last_seen_ms")));
        retainedP25Parent = directory.stream()
            .filter(parent -> "P25".equals(parent.get("protocol"))).findFirst().orElseThrow();
        assertEquals(1, number(retainedP25Parent.get("sites")));
        assertEquals("Cleveland Simulcast", retainedP25Parent.get("site_names"));
        recentReceiver = rowsFrom(mDatabase.dashboard(), "recentReceivers").stream()
            .filter(receiver -> GUID.equals(receiver.get("guid"))).findFirst().orElseThrow();
        assertEquals("P25", recentReceiver.get("protocol"));
    }

    @Test
    void reportsFilteredTotalsForSystemIdentityPages() throws Exception
    {
        seedSortingRows(mDatabasePath);

        Map<String,Object> talkgroups = mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&limit=1"));
        assertEquals(2L, number(talkgroups.get("totalCount")));
        assertTrue((Boolean)talkgroups.get("hasMore"));
        assertEquals(1L, number(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&q=100")).get("totalCount")));

        Map<String,Object> radios = mDatabase.systemRadios(request(
            "/api/system/radios?scope=p25:BEE00:348&limit=1"));
        assertEquals(2L, number(radios.get("totalCount")));
        assertTrue((Boolean)radios.get("hasMore"));
        assertEquals(1L, number(mDatabase.systemRadios(request(
            "/api/system/radios?scope=p25:BEE00:348&q=100")).get("totalCount")));

        Map<String,Object> talkerAliases = mDatabase.systemTalkerAliases(request(
            "/api/system/talker-aliases?scope=p25:BEE00:348&limit=1"));
        assertEquals(2L, number(talkerAliases.get("totalCount")));
        assertTrue((Boolean)talkerAliases.get("hasMore"));
        assertEquals(1L, number(mDatabase.systemTalkerAliases(request(
            "/api/system/talker-aliases?scope=p25:BEE00:348&q=car")).get("totalCount")));
        assertEquals(0L, number(mDatabase.systemTalkerAliases(request(
            "/api/system/talker-aliases?scope=p25:BEE00:348&q=missing")).get("totalCount")));
    }

    @Test
    void sortsDisplayedDirectoryColumnsBeforePagination() throws Exception
    {
        seedSecondSystem(mDatabasePath);
        seedSortingRows(mDatabasePath);
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);

        assertEquals(SYSTEM, number(rows(mDatabase.systemDirectory(request(
            "/api/system-directory?sort=site_names&direction=asc"))).getFirst().get("system_id")));
        assertEquals(SYSTEM, number(rows(mDatabase.systemDirectory(request(
            "/api/system-directory?sort=affiliated_radios&direction=desc"))).getFirst().get("system_id")));

        Map<String,Object> talkgroup = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&sort=alias&direction=asc&limit=1"))).getFirst();
        assertEquals("Dispatch", talkgroup.get("alias_name"));
        assertEquals("Dispatch", rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&sort=group&direction=asc&limit=1")))
            .getFirst().get("alias_name"));
        assertEquals(100, number(rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&sort=recorded&direction=desc&limit=1")))
            .getFirst().get("talkgroup_id")));
        assertEquals(100, number(rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=p25:BEE00:348&sort=streamed&direction=desc&limit=1")))
            .getFirst().get("talkgroup_id")));

        assertEquals("Engine 1", rows(mDatabase.systemRadios(request(
            "/api/system/radios?scope=p25:BEE00:348&sort=alias&direction=asc&limit=1")))
            .getFirst().get("alias_name"));
        assertEquals("Engine 1", rows(mDatabase.systemRadios(request(
            "/api/system/radios?scope=p25:BEE00:348&sort=talker_alias&direction=desc&limit=1")))
            .getFirst().get("alias_name"));

        assertEquals("Engine 1", rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/relationships?scope=p25:BEE00:348&talkgroup_id=56132" +
                "&sort=radio_alias&direction=asc&limit=1"))).getFirst().get("radio_alias_name"));
        assertEquals("Dispatch", rows(mDatabase.radioTalkgroupRelationships(request(
            "/api/relationships?scope=p25:BEE00:348&radio_id=1811332" +
                "&sort=talkgroup_alias&direction=asc&limit=1"))).getFirst().get("talkgroup_alias_name"));

        assertEquals("Alpha Channel", rows(mDatabase.conventional(request(
            "/api/conventional?sort=name&direction=asc&limit=1"))).getFirst().get("channel_name"));
    }

    @Test
    void groupsSystemDirectoryParentsAndChildrenInFixedIdentityOrder() throws Exception
    {
        seedSecondSystem(mDatabasePath);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_system VALUES (3, 1, 4095, 1000, 4000)");
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz)
                VALUES ('earlier-child', 'earlier-child-hash', 1000, 2500, 1, 'APCO25', 'Earlier Child',
                    'County', 'P25-1', 1, 0x49F, 0, 9, 857137500, 857137500)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac,
                    primary_frequency_hz, current_control_hz)
                VALUES ('unknown-child', 'unknown-child-hash', 1000, 2400, 1, 'APCO25', 'Unknown Child',
                    'County', 'P25-1', 1, 0x49F, 858137500, 858137500)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (3, 'p25:00001:FFF', 1, 1, 0, 3, 1000, 4000)
                """);
            seedP25Context(connection, 31, "earlier-child", 1);
            seedP25Context(connection, 32, "unknown-child", 1);
        }

        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
        Map<String,Object> directory = mDatabase.systemDirectory(request(
            "/api/system-directory?sort=last_seen&direction=desc"));
        List<Map<String,Object>> systems = rows(directory);
        assertEquals(1, number(systems.getFirst().get("wacn")));
        assertEquals(4095, number(systems.getFirst().get("system_id")));
        assertEquals(SECOND_SYSTEM, number(systems.get(1).get("system_id")));
        assertEquals(SYSTEM, number(systems.getLast().get("system_id")));
        assertTrue(systems.stream().noneMatch(system -> system.containsKey("children")));
        List<Map<String,Object>> children = systemSitesFor(systems.getLast());
        assertEquals("earlier-child", children.getFirst().get("guid"));
        assertEquals("unknown-child", children.get(1).get("guid"));
        assertEquals(GUID, children.getLast().get("guid"));
    }

    @Test
    void usesConsensusConfiguredChannelSystemForP25Directory() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz)
                VALUES ('consensus-site-guid', 'consensus-hash', 1000, 2500, 1, 'APCO25', 'Consensus Child',
                    'County', 'P25-1', 1, 0x49F, 1, 2, 857137500, 857137500)
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel (sort_order, system_name, radres_guid, config_json)
                VALUES (1, ' Greater Cleveland ', 'test-site-guid', '{}'),
                       (2, 'greater cleveland', 'consensus-site-guid', '{}')
                """);
            seedP25Context(connection, 33, "consensus-site-guid", 1);
        }

        Map<String,Object> parent = rows(mDatabase.systemDirectory(request("/api/system-directory"))).stream()
            .filter(row -> number(row.get("system_id")) == SYSTEM).findFirst().orElseThrow();
        assertEquals("Greater Cleveland", parent.get("configured_system"));
        assertEquals(2, number(parent.get("sites")));
        assertFalse(parent.containsKey("children"));
        assertEquals(2, systemSitesFor(parent).size());
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=greater%20cleveland"))).size());
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=BEE00"))).size());
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=consensus-site-guid"))).size());
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=Consensus%20Child"))).size());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel SET system_name = 'Other System'
                WHERE radres_guid = 'consensus-site-guid'
                """);
        }

        parent = rows(mDatabase.systemDirectory(request("/api/system-directory"))).stream()
            .filter(row -> number(row.get("system_id")) == SYSTEM).findFirst().orElseThrow();
        assertNull(parent.get("configured_system"));
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=Other%20System"))).size());
    }

    @Test
    void exposesBoundedDmrAndNxdnSystemDirectoryAndSiteDetails() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-a", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 2, "Metro DMR", "DMR Downtown", 10, 20, 1, null,
                List.of(
                    new TrunkedSiteSchema.Channel(42, null, 1, 451_000_000L, 456_000_000L,
                        TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC |
                            TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED |
                            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_FROM_CONFIGURED_MAP |
                            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_ANNOUNCED_OVER_THE_AIR),
                    new TrunkedSiteSchema.Channel(43, null, 2, 452_000_000L, 457_000_000L, 2)),
                List.of(new TrunkedSiteSchema.Neighbor(1, 2, 10, 20, 2, 44, 453_000_000L, 1))));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-b", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 2, "Metro DMR", "DMR Airport", 10, 20, 2, null,
                List.of(new TrunkedSiteSchema.Channel(52, null, 1, 461_000_000L, 466_000_000L, 1)),
                List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("nxdn-a", TrunkedSiteSchema.PROTOCOL_NXDN,
                2, 4, "Regional NXDN", "NXDN North", 7, 8, 9, 5,
                List.of(new TrunkedSiteSchema.Channel(120, 121, null, 155_000_000L, 160_000_000L, 1)),
                List.of(new TrunkedSiteSchema.Neighbor(2, 4, 7, 8, 10, 122, 155_012_500L, 2))));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("nxdn-b", TrunkedSiteSchema.PROTOCOL_NXDN,
                2, 4, "Regional NXDN", "NXDN South", 7, 8, 10, 5,
                List.of(new TrunkedSiteSchema.Channel(130, 131, null, 155_025_000L, 160_025_000L, 1)),
                List.of()));
            seedContextScope(connection, 101, 101, "dmr-a", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 102, 102, "dmr-b", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 103, 103, "nxdn-a", TrunkedSiteSchema.PROTOCOL_NXDN, 2);
            seedContextScope(connection, 104, 104, "nxdn-b", TrunkedSiteSchema.PROTOCOL_NXDN, 2);
            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("""
                    INSERT INTO trunked_identity_summary (
                        scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                        call_count, source_call_count, target_call_count, last_counterpart_kind_code,
                        last_counterpart_id, last_talker_alias, last_talker_alias_seen_ms
                    ) VALUES (101, 1, 91, 1000, 3000, 3, 0, 3, 2, 1234, NULL, NULL),
                             (101, 2, 1234, 1000, 3000, 3, 3, 0, 1, 91, 'DMR UNIT', 3000),
                             (103, 1, 24921, 1000, 3000, 2, 0, 2, 2, 14358, NULL, NULL),
                             (103, 2, 14358, 1000, 3000, 2, 2, 0, 1, 24921, 'TYPE D UNIT', 3000)
                    """);
                statement.executeUpdate("""
                    INSERT INTO trunked_radio_talkgroup_summary (
                        scope_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms,
                        call_count
                    ) VALUES (103, 14358, 24921, 1, 1000, 3000, 2)
                    """);
            }
        }

        Map<String,Object> directory = mDatabase.systemDirectory(request("/api/system-directory"));
        List<Map<String,Object>> systems = rows(directory);
        assertEquals(5, systems.size());
        List<Map<String,Object>> previewSystems = rows(mDatabase.systemDirectory(request(
            "/api/v1/systems?include_site_preview=true&limit=25")));
        Map<String,Object> dmrPreviewSystem = previewSystems.stream()
            .filter(row -> "dmr:guid:dmr-a".equals(row.get("scope_token"))).findFirst().orElseThrow();
        Map<String,Object> dmrPreview = rowsFrom(dmrPreviewSystem, "site_preview").getFirst();
        assertEquals("dmr-a", dmrPreview.get("guid"));
        assertEquals(1L, number(dmrPreview.get("site_id")));
        assertEquals(2L, number(dmrPreview.get("channels")));
        assertEquals(Boolean.FALSE, dmrPreviewSystem.get("site_preview_truncated"));
        Map<String,Object> nxdnPreviewSystem = previewSystems.stream()
            .filter(row -> "nxdn:guid:nxdn-a".equals(row.get("scope_token"))).findFirst().orElseThrow();
        Map<String,Object> nxdnPreview = rowsFrom(nxdnPreviewSystem, "site_preview").getFirst();
        assertEquals("nxdn-a", nxdnPreview.get("guid"));
        assertEquals(9L, number(nxdnPreview.get("site_id")));
        assertEquals(5L, number(nxdnPreview.get("ran")));
        assertEquals(Boolean.FALSE, nxdnPreviewSystem.get("site_preview_truncated"));
        List<Map<String,Object>> dmr = systems.stream().filter(row -> "DMR".equals(row.get("protocol"))).toList();
        assertEquals(2, dmr.size());
        assertEquals(List.of("dmr:guid:dmr-a", "dmr:guid:dmr-b"),
            dmr.stream().map(row -> String.valueOf(row.get("scope_token"))).toList());
        assertTrue(dmr.stream().allMatch(row -> number(row.get("sites")) == 1));
        assertTrue(dmr.stream().allMatch(row -> "Metro DMR".equals(row.get("configured_system"))));
        assertTrue(dmr.stream().noneMatch(row -> row.containsKey("children")));
        assertEquals("dmr-a", systemSitesFor(dmr.getFirst()).getFirst().get("guid"));
        assertEquals("dmr-b", systemSitesFor(dmr.getLast()).getFirst().get("guid"));
        assertTrue(dmr.stream().flatMap(row -> systemSitesFor(row).stream())
            .allMatch(row -> "trunked".equals(row.get("site_kind"))));
        assertEquals("dmr-b", systemSitesFor(rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=DMR%20Airport"))).getFirst()).getFirst().get("guid"));
        assertEquals(2, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=20"))).size());

        List<Map<String,Object>> nxdn = systems.stream().filter(row -> "NXDN".equals(row.get("protocol"))).toList();
        assertEquals(2, nxdn.size());
        assertEquals(List.of("nxdn:guid:nxdn-a", "nxdn:guid:nxdn-b"),
            nxdn.stream().map(row -> String.valueOf(row.get("scope_token"))).toList());
        assertTrue(nxdn.stream().allMatch(row -> number(row.get("sites")) == 1));
        assertTrue(nxdn.stream().allMatch(row -> systemSitesFor(row).size() == 1));

        List<Map<String,Object>> nxdnSearch = rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=NXDN")));
        assertEquals(2, nxdnSearch.size());
        assertEquals("NXDN", nxdnSearch.getFirst().get("protocol"));
        assertEquals("nxdn-a", systemSitesFor(nxdnSearch.getFirst()).getFirst().get("guid"));
        assertEquals(1, rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=NXDN%20North"))).size());
        assertEquals("nxdn-a", systemSitesFor(rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=9"))).getFirst()).getFirst().get("guid"));

        Map<String,Object> site = map(mDatabase.site(request("/api/site?guid=dmr-a")), "site");
        assertEquals("DMR", site.get("protocol"));
        assertEquals("trunked", site.get("site_kind"));
        assertFalse(site.containsKey("site_type"));
        assertFalse(site.containsKey("snapshot_hash"));
        assertEquals(2, number(site.get("identity_domain_code")));
        assertEquals(20, number(site.get("system_id")));
        assertEquals(2, number(site.get("channels")));
        assertEquals(1, number(site.get("neighbors")));
        Map<String,Object> dmrCapabilities = map(site, "capabilities");
        assertEquals(Boolean.TRUE, dmrCapabilities.get("quality"));
        assertEquals(Boolean.FALSE, dmrCapabilities.get("frequency_bands"));
        assertEquals(Boolean.FALSE, dmrCapabilities.get("patch_groups"));
        assertEquals(Boolean.TRUE, dmrCapabilities.get("group_identities"));
        assertEquals(Boolean.TRUE, dmrCapabilities.get("activity"));

        Map<String,Object> nxdnSite = map(mDatabase.site(request("/api/site?guid=nxdn-a")), "site");
        assertEquals("NXDN", nxdnSite.get("protocol"));
        assertEquals(4, number(nxdnSite.get("identity_domain_code")));
        assertEquals(5, number(nxdnSite.get("ran")));
        assertEquals(Boolean.TRUE, map(nxdnSite, "capabilities").get("quality"));
        assertEquals(Boolean.TRUE, map(nxdnSite, "capabilities").get("activity"));

        Map<String,Object> typeDTalkgroup = rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=nxdn:guid:nxdn-a"))).getFirst();
        assertEquals(2, number(typeDTalkgroup.get("identity_domain_code")));
        assertEquals(24921, number(typeDTalkgroup.get("talkgroup_id")));
        assertEquals(24921, number(rows(mDatabase.systemTalkgroups(request(
            "/api/system/talkgroups?scope=nxdn:guid:nxdn-a&q=12-0345")))
            .getFirst().get("talkgroup_id")));
        Map<String,Object> typeDRadio = map(mDatabase.radio(request(
            "/api/radio?scope=nxdn:guid:nxdn-a&radio_id=14358")), "radio");
        assertEquals(2, number(typeDRadio.get("identity_domain_code")));
        assertEquals(14358, number(typeDRadio.get("radio_id")));
        assertEquals("TYPE D UNIT", typeDRadio.get("last_talker_alias"));
        assertEquals(24921, number(typeDRadio.get("last_talkgroup_id")));
        assertEquals(14358, number(rows(mDatabase.systemRadios(request(
            "/api/system/radios?scope=nxdn:guid:nxdn-a&q=07-0022")))
            .getFirst().get("radio_id")));
        assertEquals(14358, number(rows(mDatabase.systemTalkerAliases(request(
            "/api/system/talker-aliases?scope=nxdn:guid:nxdn-a&q=07-0022")))
            .getFirst().get("radio_id")));

        CSVRecord dmrTalkgroupExport = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=system-talkgroups&scope=dmr:guid:dmr-a"))).getFirst();
        assertEquals("DMR", dmrTalkgroupExport.get("protocol"));
        assertEquals("10", dmrTalkgroupExport.get("network_id"));
        assertEquals("20", dmrTalkgroupExport.get("system_id"));
        CSVRecord dmrRadioExport = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=system-radios&scope=dmr:guid:dmr-a"))).getFirst();
        assertEquals("10", dmrRadioExport.get("network_id"));
        assertEquals("1234", dmrRadioExport.get("radio_id"));

        CSVRecord nxdnTalkgroupExport = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=system-talkgroups&scope=nxdn:guid:nxdn-a"))).getFirst();
        assertEquals("7", nxdnTalkgroupExport.get("network_id"));
        assertEquals("12-0345", nxdnTalkgroupExport.get("formatted_talkgroup_id"));
        CSVRecord nxdnRadioExport = csvRows(mDatabase.csvExport(request(
            "/api/export.csv?dataset=system-radios&scope=nxdn:guid:nxdn-a"))).getFirst();
        assertEquals("7", nxdnRadioExport.get("network_id"));
        assertEquals("07-0022", nxdnRadioExport.get("formatted_radio_id"));

        Map<String,Object> dmrChannelPage = mDatabase.siteChannels(request(
            "/api/site/channels?guid=dmr-a&limit=1"));
        List<Map<String,Object>> channels = rows(dmrChannelPage);
        assertEquals(1, channels.size());
        assertTrue((Boolean)dmrChannelPage.get("hasMore"));
        assertEquals(1, number(dmrChannelPage.get("nextOffset")));
        assertEquals(42, number(channels.getFirst().get("channel_number")));
        assertEquals(451_000_000L, number(channels.getFirst().get("frequency_hz")));
        assertEquals(TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC |
            TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED |
            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_FROM_CONFIGURED_MAP |
            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_ANNOUNCED_OVER_THE_AIR,
            number(channels.getFirst().get("role_flags")));
        assertEquals(43, number(rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=dmr-a&limit=1&offset=1"))).getFirst().get("channel_number")));

        List<Map<String,Object>> neighbors = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=nxdn-a&limit=1")));
        assertEquals(1, neighbors.size());
        assertEquals(10, number(neighbors.getFirst().get("site_id")));
        assertEquals(4, number(neighbors.getFirst().get("identity_domain_code")));
        assertEquals(155_012_500L, number(neighbors.getFirst().get("frequency_hz")));

        List<Map<String,Object>> dmrNeighbors = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=dmr-a&limit=1")));
        assertEquals(1, dmrNeighbors.size());
        assertEquals(2, number(dmrNeighbors.getFirst().get("site_id")));
        assertEquals("DMR Airport", dmrNeighbors.getFirst().get("neighbor_name"));
        assertEquals("dmr-b", dmrNeighbors.getFirst().get("neighbor_guid"));

        List<Map<String,Object>> nxdnChannels = rows(mDatabase.siteChannels(request(
            "/api/site/channels?guid=nxdn-a&limit=1")));
        assertEquals(1, nxdnChannels.size());
        assertEquals(120, number(nxdnChannels.getFirst().get("channel_number")));

        assertTrue(rows(mDatabase.systemDirectory(request(
            "/api/system-directory?q=00000"))).isEmpty());

        Map<String,Object> counts = map(mDatabase.dashboard(), "counts");
        assertEquals(5, number(counts.get("trunked_systems")));
        assertEquals(5, number(counts.get("trunked_sites")));
        assertFalse(counts.containsKey("talkgroups"));
        assertFalse(counts.containsKey("radios"));
        assertFalse(counts.containsKey("frequencies"));
    }

    @Test
    void linksOnlyUniquelyOwnedAndFullyQualifiedTrunkedNeighbors() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-link-source",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, " Shared DMR ", "Site 3", 0, null, 3, null,
                List.of(), List.of(new TrunkedSiteSchema.Neighbor(1, 2, 0, null, 5, 802, null, 1))));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-link-target",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "shared dmr", "Site 5", 0, null, 5, null,
                List.of(new TrunkedSiteSchema.Channel(802, null, 1, 139_518_750L, null, 1)), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-link-unowned",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "Shared DMR", "Unowned Site 5", 0, null, 5, null,
                List.of(), List.of()));
            seedContextScope(connection, 170, 170, "dmr-link-source", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 171, 171, "dmr-link-target", TrunkedSiteSchema.PROTOCOL_DMR, 0);
        }

        Map<String,Object> neighbor = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=dmr-link-source"))).getFirst();
        assertEquals(5, number(neighbor.get("site_id")));
        assertEquals(802, number(neighbor.get("channel_number")));
        assertEquals("Site 5", neighbor.get("neighbor_name"));
        assertEquals("dmr-link-target", neighbor.get("neighbor_guid"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-link-duplicate",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "SHARED DMR", "Duplicate Site 5", 0, null, 5, null,
                List.of(), List.of()));
            seedContextScope(connection, 172, 172, "dmr-link-duplicate", TrunkedSiteSchema.PROTOCOL_DMR, 0);

            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-other-source",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "DMR Alpha", "Other Source", 1, null, 3, null,
                List.of(), List.of(new TrunkedSiteSchema.Neighbor(1, 2, 1, null, 5, 803, null, 1))));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-other-target",
                TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "DMR Beta", "Other Target", 1, null, 5, null,
                List.of(), List.of()));
            seedContextScope(connection, 173, 173, "dmr-other-source", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 174, 174, "dmr-other-target", TrunkedSiteSchema.PROTOCOL_DMR, 0);

            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-sparse-source",
                TrunkedSiteSchema.PROTOCOL_DMR, 2, 0, "Sparse DMR", "Sparse Source", null, null, 3, null,
                List.of(), List.of(new TrunkedSiteSchema.Neighbor(2, 0, null, null, 5, null, null, 1))));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-sparse-target",
                TrunkedSiteSchema.PROTOCOL_DMR, 2, 0, "Sparse DMR", "Sparse Target", null, null, 5, null,
                List.of(), List.of()));
            seedContextScope(connection, 175, 175, "dmr-sparse-source", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 176, 176, "dmr-sparse-target", TrunkedSiteSchema.PROTOCOL_DMR, 0);

            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-unclassified-source",
                TrunkedSiteSchema.PROTOCOL_DMR, 0, 0, "Unclassified DMR", "Unclassified Source", 2, null,
                3, null, List.of(),
                List.of(new TrunkedSiteSchema.Neighbor(0, 0, 2, null, 5, 804, null, 1))));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-unclassified-target",
                TrunkedSiteSchema.PROTOCOL_DMR, 0, 0, "Unclassified DMR", "Unclassified Target", 2, null,
                5, null, List.of(), List.of()));
            seedContextScope(connection, 177, 177, "dmr-unclassified-source", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 178, 178, "dmr-unclassified-target", TrunkedSiteSchema.PROTOCOL_DMR, 0);
        }

        neighbor = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=dmr-link-source"))).getFirst();
        assertNull(neighbor.get("neighbor_name"));
        assertNull(neighbor.get("neighbor_guid"));

        neighbor = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=dmr-other-source"))).getFirst();
        assertNull(neighbor.get("neighbor_name"));
        assertNull(neighbor.get("neighbor_guid"));

        neighbor = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=dmr-sparse-source"))).getFirst();
        assertNull(neighbor.get("neighbor_name"));
        assertNull(neighbor.get("neighbor_guid"));

        neighbor = rows(mDatabase.siteNeighbors(request(
            "/api/site/neighbors?guid=dmr-unclassified-source"))).getFirst();
        assertNull(neighbor.get("neighbor_name"));
        assertNull(neighbor.get("neighbor_guid"));
    }

    @Test
    void pagesTiedTrunkedSiteFactsUsingTheirFullPrimaryKeys() throws Exception
    {
        String guid = "dmr-pagination-ties";

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot(guid, TrunkedSiteSchema.PROTOCOL_DMR,
                1, 2, "Paging DMR", "Paging Site", 10, 20, 1, null,
                List.of(
                    new TrunkedSiteSchema.Channel(42, 102, 1, 451_000_000L, 456_000_000L, 1),
                    new TrunkedSiteSchema.Channel(42, 100, 1, 451_000_000L, 456_000_000L, 1),
                    new TrunkedSiteSchema.Channel(42, 101, 1, 451_000_000L, 456_000_000L, 1)),
                List.of(
                    new TrunkedSiteSchema.Neighbor(2, 2, 10, 20, 2, 44, 453_000_000L, 1),
                    new TrunkedSiteSchema.Neighbor(1, 2, 10, 20, 2, 44, 453_000_100L, 1),
                    new TrunkedSiteSchema.Neighbor(1, 2, 10, 20, 2, 44, 453_000_000L, 1))));
            seedContextScope(connection, 150, 150, guid, TrunkedSiteSchema.PROTOCOL_DMR, 0);
        }

        List<Long> inboundChannels = new ArrayList<>();
        List<String> neighbors = new ArrayList<>();

        for(int offset = 0; offset < 3; offset++)
        {
            Map<String,Object> channel = rows(mDatabase.siteChannels(request(
                "/api/site/channels?guid=" + guid + "&limit=1&offset=" + offset))).getFirst();
            inboundChannels.add(number(channel.get("inbound_channel_number")));
            Map<String,Object> neighbor = rows(mDatabase.siteNeighbors(request(
                "/api/site/neighbors?guid=" + guid + "&limit=1&offset=" + offset))).getFirst();
            neighbors.add(number(neighbor.get("variant_code")) + ":" + number(neighbor.get("frequency_hz")));
        }

        assertEquals(List.of(100L, 101L, 102L), inboundChannels);
        assertEquals(List.of("1:453000000", "1:453000100", "2:453000000"), neighbors);
    }

    @Test
    void keepsDmrModelsAndNxdnLocationCategoriesInSeparateSystemGroups() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-tiny", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 1, "Shared DMR", "Tiny Site", 10, null, 1, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-small", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 2, "Shared DMR", "Small Site", 10, null, 2, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("nxdn-global", TrunkedSiteSchema.PROTOCOL_NXDN,
                1, 1, "Shared NXDN", "Global Site", null, 8, 1, 5, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("nxdn-local", TrunkedSiteSchema.PROTOCOL_NXDN,
                1, 3, "Shared NXDN", "Local Site", null, 8, 2, 5, List.of(), List.of()));
            seedContextScope(connection, 111, 111, "dmr-tiny", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 112, 112, "dmr-small", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 113, 113, "nxdn-global", TrunkedSiteSchema.PROTOCOL_NXDN, 1);
            seedContextScope(connection, 114, 114, "nxdn-local", TrunkedSiteSchema.PROTOCOL_NXDN, 1);
        }

        List<Map<String,Object>> systems = rows(mDatabase.systemDirectory(request("/api/system-directory")));
        List<Map<String,Object>> dmr = systems.stream().filter(row -> "DMR".equals(row.get("protocol"))).toList();
        List<Map<String,Object>> nxdn = systems.stream().filter(row -> "NXDN".equals(row.get("protocol"))).toList();
        assertEquals(2, dmr.size());
        assertEquals(2, nxdn.size());
        assertEquals(List.of(0L, 0L), dmr.stream().map(row -> number(row.get("identity_domain_code"))).sorted()
            .toList());
        assertEquals(List.of(1L, 1L), nxdn.stream().map(row -> number(row.get("identity_domain_code"))).sorted()
            .toList());
    }

    @Test
    void keepsAConfiguredQuietTrunkedReceiverVisibleAfterItsSiteSnapshotExpires() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            seedContextScope(connection, 150, 150, "quiet-dmr", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            statement.executeUpdate("""
                UPDATE receiver_context
                SET channel_name='Quiet DMR', alias_list_name='DMR Aliases',
                    decoder='DMR', primary_frequency_hz=451012500
                WHERE id=150
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    sort_order, system_name, site_name, name, radres_guid, decoder_type, config_json
                ) VALUES(150, 'Quiet Network', 'Summit County', 'Quiet DMR Site',
                    'quiet-dmr', 'DMR', '{}')
                """);
        }

        Map<String,Object> quietSystem = rows(mDatabase.systemDirectory(request(
            "/api/system-directory"))).stream()
            .filter(row -> "dmr:guid:quiet-dmr".equals(row.get("scope_token")))
            .findFirst().orElseThrow();
        assertEquals(1, number(quietSystem.get("sites")));
        assertFalse(quietSystem.containsKey("children"));
        Map<String,Object> child = systemSitesFor(quietSystem).getFirst();
        assertEquals("quiet-dmr", child.get("guid"));
        assertEquals("Quiet DMR", child.get("channel_name"));
        assertEquals("Summit County", child.get("configured_site"));
        assertEquals("Quiet DMR Site", child.get("configured_name"));
        assertEquals(0, number(child.get("observation_count")));

        Map<String,Object> site = map(mDatabase.site(request("/api/site?guid=quiet-dmr")), "site");
        assertEquals("DMR", site.get("protocol"));
        assertEquals("trunked", site.get("site_kind"));
        assertEquals("Quiet DMR", site.get("channel_name"));
        assertEquals("Summit County", site.get("configured_site"));
        assertEquals("Quiet DMR Site", site.get("configured_name"));
        assertEquals(451_012_500L, number(site.get("primary_frequency_hz")));

        Map<String,Object> recent = rowsFrom(mDatabase.dashboard(), "recentReceivers").stream()
            .filter(row -> "quiet-dmr".equals(row.get("guid"))).findFirst().orElseThrow();
        assertEquals("Summit County", recent.get("configured_site"));
        assertEquals("Quiet DMR Site", recent.get("configured_name"));
    }

    @Test
    void keepsDmrVariantsWithOverlappingUnqualifiedIdsInSeparateSystemGroups() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath))
        {
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-tier3", TrunkedSiteSchema.PROTOCOL_DMR,
                1, 0, "Tier III", "Tier III Site", 10, 20, 1, null, List.of(), List.of()));
            TrunkedSiteSchema.upsert(connection, trunkedSnapshot("dmr-connect-plus",
                TrunkedSiteSchema.PROTOCOL_DMR, 2, 0, "Connect Plus", "Connect Plus Site", 10, 20, 2, null,
                List.of(), List.of()));
            seedContextScope(connection, 121, 121, "dmr-tier3", TrunkedSiteSchema.PROTOCOL_DMR, 0);
            seedContextScope(connection, 122, 122, "dmr-connect-plus", TrunkedSiteSchema.PROTOCOL_DMR, 0);
        }

        List<Map<String,Object>> dmr = rows(mDatabase.systemDirectory(request("/api/system-directory"))).stream()
            .filter(row -> "DMR".equals(row.get("protocol")))
            .toList();
        assertEquals(2, dmr.size());
        assertEquals(List.of(1L, 2L), dmr.stream().map(row -> number(row.get("variant_code"))).sorted().toList());
        assertTrue(dmr.stream().allMatch(row -> systemSitesFor(row).size() == 1));
        assertEquals(3, number(map(mDatabase.dashboard(), "counts").get("trunked_systems")));
    }

    private static StatsRequest request(String uri)
    {
        return StatsRequest.from(URI.create(uri));
    }

    private List<Map<String,Object>> systemSitesFor(Map<String,Object> system)
    {
        String scope = String.valueOf(system.get("scope_token"));
        return rows(mDatabase.systemSites(request(
            "/api/v1/systems/" + scope + "/sites?scope=" + scope + "&limit=500")));
    }

    private static List<CSVRecord> csvRows(StatsCsvExport export) throws Exception
    {
        String csv = new String(export.content(), 3, export.content().length - 3, StandardCharsets.UTF_8);
        try(CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
            .parse(new StringReader(csv)))
        {
            return parser.getRecords();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Map<String,Object> response, String key)
    {
        return (Map<String,Object>)response.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> response)
    {
        return (List<Map<String,Object>>)response.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rowsFrom(Map<String,Object> response, String key)
    {
        return (List<Map<String,Object>>)response.get(key);
    }

    private static long number(Object value)
    {
        return ((Number)value).longValue();
    }

    private static long actionCount(Map<String,Object> response, String action)
    {
        return rows(response).stream()
            .filter(row -> action.equals(row.get("action")))
            .map(row -> number(row.get("count")))
            .findFirst().orElseThrow();
    }

    private static List<String> explain(Connection connection, String sql, Object... parameters) throws Exception
    {
        List<String> plan = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
        {
            for(int x = 0; x < parameters.length; x++)
            {
                statement.setObject(x + 1, parameters[x]);
            }

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    plan.add(resultSet.getString("detail"));
                }
            }
        }

        return plan;
    }

    private static long metadataValue(Path database, String key) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static void seedContextScope(Connection connection, int scopeId, int contextId, String guid,
                                         int protocolCode, int identityDomainCode) throws Exception
    {
        String protocol = protocolCode == TrunkedSiteSchema.PROTOCOL_DMR ? "DMR" : "NXDN";

        try(PreparedStatement context = connection.prepareStatement("""
            INSERT INTO receiver_context (
                id, context_key, guid, kind_code, protocol_code, channel_name, decoder,
                first_seen_ms, last_seen_ms
            ) VALUES (?, ?, ?, 1, ?, ?, ?, 1000, 3000)
            """);
            PreparedStatement scope = connection.prepareStatement("""
            INSERT INTO trunked_identity_scope (
                scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                first_seen_ms, last_seen_ms
            ) VALUES (?, ?, ?, 2, ?, 1000, 3000)
            """);
            PreparedStatement ownership = connection.prepareStatement("""
            INSERT INTO trunked_identity_scope_context (scope_id, context_id, first_seen_ms, last_seen_ms)
            VALUES (?, ?, 1000, 3000)
            """))
        {
            context.setInt(1, contextId);
            context.setString(2, protocol.toLowerCase() + "-" + guid);
            context.setString(3, guid);
            context.setInt(4, protocolCode);
            context.setString(5, protocol + " Receiver");
            context.setString(6, protocol);
            context.executeUpdate();

            scope.setInt(1, scopeId);
            scope.setString(2, protocol.toLowerCase() + ":guid:" + guid);
            scope.setInt(3, protocolCode);
            scope.setInt(4, identityDomainCode);
            scope.executeUpdate();

            ownership.setInt(1, scopeId);
            ownership.setInt(2, contextId);
            ownership.executeUpdate();
        }
    }

    private static void seedP25Context(Connection connection, int contextId, String guid, int scopeId)
        throws Exception
    {
        try(PreparedStatement context = connection.prepareStatement("""
            INSERT INTO receiver_context (
                id, context_key, guid, kind_code, protocol_code, channel_name, decoder,
                first_seen_ms, last_seen_ms
            ) VALUES (?, ?, ?, 1, 1, 'P25 Receiver', 'P25-1', 1000, 3000)
            """);
            PreparedStatement ownership = connection.prepareStatement("""
            INSERT INTO trunked_identity_scope_context (scope_id, context_id, first_seen_ms, last_seen_ms)
            VALUES (?, ?, 1000, 3000)
            """))
        {
            context.setInt(1, contextId);
            context.setString(2, "p25-" + guid);
            context.setString(3, guid);
            context.executeUpdate();
            ownership.setInt(1, scopeId);
            ownership.setInt(2, contextId);
            ownership.executeUpdate();
        }
    }

    private static TrunkedSiteSchema.Snapshot trunkedSnapshot(String guid, int protocol, int variant, int domain,
                                                               String configuredSystem, String channelName,
                                                               Integer network, Integer system, Integer site,
                                                               Integer ran, List<TrunkedSiteSchema.Channel> channels,
                                                               List<TrunkedSiteSchema.Neighbor> neighbors)
    {
        return trunkedSnapshotAt(System.currentTimeMillis(), guid, protocol, variant, domain, configuredSystem,
            channelName, network, system, site, ran, channels, neighbors);
    }

    private static TrunkedSiteSchema.Snapshot trunkedSnapshotAt(long observedAt, String guid, int protocol,
                                                                 int variant, int domain, String configuredSystem,
                                                                 String channelName, Integer network, Integer system,
                                                                 Integer site, Integer ran,
                                                                 List<TrunkedSiteSchema.Channel> channels,
                                                                 List<TrunkedSiteSchema.Neighbor> neighbors)
    {
        return new TrunkedSiteSchema.Snapshot(observedAt, guid, "hash-" + guid, protocol, variant,
            domain, configuredSystem, channelName, "County", protocol == TrunkedSiteSchema.PROTOCOL_DMR ?
            "DMR" : "NXDN", network, system, site, ran, null, null, null, null, 1, 1, null, 0, null,
            channels.isEmpty() ? null : channels.getFirst().frequencyHertz(),
            channels.isEmpty() ? null : channels.getFirst().frequencyHertz(), channels, neighbors);
    }

    private static void insertQuality(Connection connection, String guid, long frequency, long observedAt,
                                      double signal, double decode) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_control_channel_quality (
                guid, frequency_hz, bucket_start_ms, observed_at_ms, signal_dbfs, average_signal_dbfs,
                minimum_signal_dbfs, maximum_signal_dbfs, decode_health_pct, valid_frames, invalid_frames,
                corrected_bits, sync_loss_bits, dropped_bits, last_valid_decode_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 100, 1, 2, 0, 0, ?)
            """))
        {
            statement.setString(1, guid);
            statement.setLong(2, frequency);
            statement.setLong(3, observedAt - Math.floorMod(observedAt, 10_000L));
            statement.setLong(4, observedAt);
            statement.setDouble(5, signal);
            statement.setDouble(6, signal);
            statement.setDouble(7, signal - 2.0);
            statement.setDouble(8, signal + 2.0);
            statement.setDouble(9, decode);
            statement.setLong(10, observedAt);
            statement.executeUpdate();
        }
    }

    private static void seed(Path database) throws Exception
    {
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_system VALUES (1, " + WACN + ", " + SYSTEM + ", " +
                (now - 10_000) + ", " + now + ")");
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, first_seen_ms, last_seen_ms, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz)
                VALUES (1, 'site-cleveland', 'test-site-guid', 1, 1, 'Cleveland Simulcast', 'County', 'P25-1',
                    1000, 2000, 1, 0x49F, 1, 1, 856137500, 856137500)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (1, 'p25:BEE00:348', 1, 1, 0, 1, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context (context_id, scope_id, first_seen_ms, last_seen_ms)
                VALUES (1, 1, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    lra, mfid, broadcast_clock_ms, micro_slots, data_service, data_access, wuid_lease_minutes,
                    registration_service, tdma, voice_service, primary_frequency_hz, current_control_hz)
                VALUES ('test-site-guid', 'hash', 1000, 2000, 10, 'APCO25', 'Cleveland Simulcast', 'County',
                    'P25-1', 1, 0x49F, 1, 1, 0, 0x90, 1784000000000, 110, 1,
                    'Autonomous and by Request', 240, 1, 1, 1, 856137500, 856137500)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality (guid, frequency_hz, bucket_start_ms, observed_at_ms,
                    signal_dbfs, average_signal_dbfs, minimum_signal_dbfs, maximum_signal_dbfs,
                    decode_health_pct, valid_frames, invalid_frames, corrected_bits, sync_loss_bits,
                    dropped_bits, last_valid_decode_ms)
                VALUES ('test-site-guid', 856137500, 0, 2000, -20.0, -21.0, -25.0, -18.0,
                    98.5, 100, 1, 4, 0, 0, 1999)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel (guid, channel_key, descriptor, downlink_hz, uplink_hz, tdma,
                    timeslots, callsign, confirmed_at_ms) VALUES ('test-site-guid', '0-821', '0-821',
                    856137500, 811137500, 0, 1, 'WPFF205', %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary (guid, channel_key, descriptor, downlink_hz, uplink_hz,
                    tdma, timeslots, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', '0-821', '0-821', 856137500, 811137500,
                    0, 1, 1000, 2000, 10)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary (guid, channel_key, descriptor, downlink_hz, uplink_hz,
                    tdma, timeslots, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', '0-509', '0-509', 854187500, NULL,
                    0, 1, 1000, 2000, 4),
                    ('test-site-guid', '0-510', '0-510', 854187500, NULL,
                    0, 1, 1000, 2000, 2),
                    ('test-site-guid', '0-900', '0-900', 857137500, NULL,
                    0, 1, 1000, %d, 2)
                """.formatted(now - 3_600_000L));
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_tag (guid, channel_key, tag, confirmed_at_ms)
                VALUES ('test-site-guid', '0-821', 'CURRENT_CONTROL', %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_tag_summary
                    (guid, channel_key, tag, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', '0-821', 'CONTROL', 1000, 2000, 10),
                    ('test-site-guid', '0-509', 'VOICE', 1000, 2000, 4),
                    ('test-site-guid', '0-510', 'DATA', 1000, 2000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_neighbor (guid, neighbor_key, system_id, rfss, site, channel_descriptor,
                    downlink_hz, status, confirmed_at_ms)
                VALUES ('test-site-guid', '348:1:2:0-661', 0x348, 1, 2, '0-661', 855137500,
                    '[VALID INFORMATION, ACTIVE RFSS CONNECTION]', %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_neighbor_summary (guid, neighbor_key, system_id, rfss, site,
                    channel_descriptor, downlink_hz, status, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', '348:1:2:0-661', 0x348, 1, 2, '0-661', 855137500,
                    '[VALID INFORMATION, ACTIVE RFSS CONNECTION]', 1000, 2000, 10),
                    ('test-site-guid', '348:1:3:0-677', 0x348, 1, 3, '0-677', 855237500,
                    '[VALID INFORMATION]', 1000, 1500, 2),
                    ('test-site-guid', '348:1:4:0-693', 0x348, 1, 4, '0-693', 855337500,
                    '[VALID INFORMATION]', 1000, %d, 2)
                """.formatted(now - 3_600_000L));
            statement.executeUpdate("""
                INSERT INTO p25_foreign_system_band
                    (guid, foreign_wacn, foreign_system_id, band, channel_type, base_hz, spacing_hz,
                     transmit_offset_hz, confirmed_at_ms)
                VALUES ('test-site-guid', 0xBEE00, 0x9EF, 4, 1, 935012500, 12500, -39000000, %1$d),
                    ('test-site-guid', 0xBEE00, 0x9EF, 5, 3, 935012500, 12500, -39000000, %1$d),
                    ('test-site-guid', 0xBEE00, 0x954, 0, 1, 851006250, 6250, -45000000, %1$d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_foreign_system_band_summary
                    (guid, foreign_wacn, foreign_system_id, band, channel_type, base_hz, spacing_hz,
                     transmit_offset_hz, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', 0xBEE00, 0x9EF, 4, 1, 935012500, 12500, -39000000,
                        1000, %1$d, 10),
                    ('test-site-guid', 0xBEE00, 0x9EF, 5, 3, 935012500, 12500, -39000000,
                        1000, %1$d, 10),
                    ('test-site-guid', 0xBEE00, 0x954, 0, 1, 851006250, 6250, -45000000,
                        1000, %1$d, 5)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group (guid, patch_group, version, confirmed_at_ms)
                VALUES ('test-site-guid', 56132, 0, %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_summary
                    (guid, patch_group, version, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', 56132, 0, 1000, 2000, 10)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_talkgroup (guid, patch_group, talkgroup_id, confirmed_at_ms)
                VALUES ('test-site-guid', 56132, 56132, %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_talkgroup_summary
                    (guid, patch_group, talkgroup_id, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', 56132, 56132, 1000, 2000, 10)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_radio (guid, patch_group, radio_id, confirmed_at_ms)
                VALUES ('test-site-guid', 56132, 1811332, %d)
                """.formatted(now));
            statement.executeUpdate("""
                INSERT INTO p25_site_patch_group_radio_summary
                    (guid, patch_group, radio_id, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('test-site-guid', 56132, 1811332, 1000, 2000, 10)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, p25_identity_state_code,
                    first_seen_ms, last_seen_ms,
                    call_count, target_call_count, grant_count, encrypted_count,
                    last_counterpart_kind_code, last_counterpart_id
                ) VALUES (1, 1, 56132, 1, 1000, 2000, 12, 12, 12, 2, 2, 1811332)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    call_count, source_call_count, grant_count, encrypted_count,
                    last_counterpart_kind_code, last_counterpart_id,
                    last_talker_alias, last_talker_alias_seen_ms
                ) VALUES (1, 2, 1811332, 1000, 2000, 8, 8, 8, 1, 1, 56132, 'CAR 201', 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_talkgroup_summary (scope_id, radio_id, talkgroup_id, target_kind_code,
                    first_seen_ms, last_seen_ms, call_count, grant_count, encrypted_count)
                VALUES (1, 1811332, 56132, 1, 1000, 2000, 8, 8, 1)
                """);
            statement.executeUpdate("INSERT INTO trunked_radio_affiliation VALUES (1, 1811332, 56132, 2000)");
            statement.executeUpdate("INSERT INTO trunked_radio_site_presence VALUES (1, 1811332, 1, 2, 2000)");
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code,
                    source_radio_id, target_id, target_kind_code, frequency_hz, lcn_band, lcn_number, timeslot,
                    encrypted, encryption_algorithm_id, encryption_key_id)
                VALUES (1, 2000, 0, 0, 1811332, 56132, 1, 855612500, 0, 737, 1, 1, 132, 52)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code,
                    source_radio_id, target_id, target_kind_code, frequency_hz, encrypted)
                VALUES (1, 2001, 0, 0, NULL, 1811332, 2, 856137500, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list
                    (id, name, family)
                VALUES (1, 'County', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                )
                VALUES (1, 1, 'Dispatch', 'Law Dispatch', 255,
                            'TALKGROUP', 'APCO25', 56132),
                       (2, 1, 'Engine 1', 'Fire', 65280,
                            'RADIO_ID', 'APCO25', 1811332)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, first_seen_ms, last_seen_ms, primary_frequency_hz)
                VALUES (2, 'conventional-fire', NULL, 10, 0, 'County Fire', 'County', 'NBFM', 1000, 2000,
                    154310000)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary (context_id, frequency_hz, timeslot, first_seen_ms,
                    last_seen_ms, call_count) VALUES (2, 154310000, -1, 1000, 2000, 4)
                """);
        }
    }

    private static void seedDmrConventionalRows(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, first_seen_ms, last_seen_ms, primary_frequency_hz)
                VALUES (5, 'conventional-dmr-county', 'dmr-county-guid', 3, 3, 'County DMR',
                        'County DMR', 'DMR', 1000, 5000, 451012500),
                       (6, 'conventional-dmr-other', 'dmr-other-guid', 3, 3, 'Other DMR',
                        'Other DMR', 'DMR', 1000, 6000, 461012500)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary (context_id, frequency_hz, timeslot, first_seen_ms,
                    last_seen_ms, call_count)
                VALUES (5, 451012500, 1, 1000, 5000, 10),
                       (5, 451012500, 2, 2000, 5000, 20),
                       (6, 461012500, 1, 1000, 6000, 999)
                """);
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_talkgroup_summary (
                    context_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms,
                    call_count, encrypted_count, last_source_radio_id
                ) VALUES (5, 451012500, 1, 91, 1000, 5000, 10, 2, 123456),
                         (5, 451012500, 2, 92, 2000, 5000, 20, 0, 234567),
                         (6, 461012500, 1, 91, 1000, 6000, 999, 0, 123456)
                """);
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_radio_summary (
                    context_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms, call_count,
                    source_call_count, target_call_count, group_call_count, private_call_count,
                    encrypted_count, last_talkgroup_id, last_peer_radio_id
                ) VALUES (5, 451012500, 1, 123456, 1000, 5000, 10, 7, 3, 8, 2, 1, 91, 234567),
                         (5, 451012500, 2, 234567, 2000, 5000, 20, 15, 5, 18, 2, 0, 92, 123456),
                         (6, 461012500, 1, 123456, 1000, 6000, 999, 999, 0, 999, 0, 0, 91, 234567)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list
                    (id, name, family)
                VALUES (100, 'County DMR', 'DMR'),
                       (101, 'Other DMR', 'DMR')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                )
                VALUES (100, 100, 'DMR Dispatch', 'Fire Dispatch', 255,
                            'TALKGROUP', 'DMR', 91),
                       (101, 100, 'DMR Operations', 'Fire Operations', 255,
                            'TALKGROUP', 'DMR', 92),
                       (102, 100, 'DMR Engine 1', 'Fire Units', 65280,
                            'RADIO_ID', 'DMR', 123456),
                       (103, 100, 'DMR Engine 2', 'Fire Units', 65280,
                            'RADIO_ID', 'DMR', 234567),
                       (104, 101, 'Other Dispatch', 'Other Dispatch', 255,
                            'TALKGROUP', 'DMR', 91),
                       (105, 101, 'Other Engine', 'Other Units', 65280,
                            'RADIO_ID', 'DMR', 123456)
                """);
        }
    }

    private static void seedSecondSystem(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_system VALUES (2, " + WACN + ", " + SECOND_SYSTEM +
                ", 1000, 3000)");
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, first_seen_ms, last_seen_ms, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz)
                VALUES (3, 'site-second', 'second-site-guid', 1, 1, 'Second Simulcast', 'Second', 'P25-1',
                    1000, 3000, 2, 0x123, 1, 1, 855137500, 855137500)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot (guid, snapshot_hash, first_seen_ms, last_seen_ms, observation_count,
                    protocol, channel_name, alias_list_name, decoder, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz)
                VALUES ('second-site-guid', 'second-hash', 1000, 3000, 10, 'APCO25', 'Second Simulcast',
                    'Second', 'P25-1', 2, 0x123, 1, 1, 855137500, 855137500)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope (
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (2, 'p25:BEE00:49F', 1, 1, 0, 2, 1000, 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context (scope_id, context_id, first_seen_ms, last_seen_ms)
                VALUES (2, 3, 1000, 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms, call_count,
                    target_call_count, grant_count, encrypted_count, last_counterpart_kind_code,
                    last_counterpart_id
                ) VALUES (2, 1, 56132, 1000, 3000, 100, 100, 100, 0, 2, 1811332),
                         (2, 2, 1811332, 1000, 3000, 100, 0, 100, 0, 1, 56132)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_talkgroup_summary (
                    scope_id, radio_id, talkgroup_id, target_kind_code,
                    first_seen_ms, last_seen_ms, call_count, grant_count, encrypted_count
                ) VALUES (2, 1811332, 56132, 1, 1000, 3000, 100, 100, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_activity_event (context_id, observed_at_ms, action_code, event_type_code,
                    source_radio_id, target_id, target_kind_code, frequency_hz, encrypted)
                VALUES (3, 3000, 0, 0, 1811332, 56132, 1, 855612500, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list
                    (id, name, family)
                VALUES (2, 'Second', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                )
                VALUES (3, 2, 'Second Dispatch', 'Second Law', 255,
                            'TALKGROUP', 'APCO25', 56132),
                       (4, 2, 'Second Engine', 'Second Fire', 65280,
                            'RADIO_ID', 'APCO25', 1811332)
                """);
        }
    }

    private static void seedSortingRows(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary (
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms, call_count,
                    source_call_count, target_call_count, grant_count, encrypted_count, recorded_count,
                    streamed_count, last_counterpart_kind_code, last_counterpart_id,
                    last_talker_alias, last_talker_alias_seen_ms
                ) VALUES (1, 1, 100, 1000, 3000, 100, 0, 100, 100, 0, 10, 12, 2, 100, NULL, NULL),
                         (1, 2, 100, 1000, 3000, 100, 100, 0, 100, 0, 0, 0, 1, 56132, 'AAA', 3000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_talkgroup_summary (
                    scope_id, radio_id, talkgroup_id, target_kind_code,
                    first_seen_ms, last_seen_ms, call_count, grant_count, encrypted_count)
                VALUES (1, 100, 56132, 1, 1000, 3000, 100, 100, 0),
                       (1, 1811332, 100, 1, 1000, 3000, 100, 100, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, group_name, color, matcher_type, protocol, value
                )
                VALUES (5, 1, 'Zulu Dispatch', 'Zulu Law', 255,
                            'TALKGROUP', 'APCO25', 100),
                       (6, 1, 'Zulu Unit', 'Zulu Fire', 65280,
                            'RADIO_ID', 'APCO25', 100)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context (id, context_key, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, nac, first_seen_ms, last_seen_ms, primary_frequency_hz)
                VALUES (4, 'conventional-alpha', 10, 20, 'Alpha Channel', 'County', 'P25-1', 0x123,
                    1000, 3000, 800000000)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary (context_id, frequency_hz, timeslot, first_seen_ms,
                    last_seen_ms, call_count, last_event_type_code)
                VALUES (4, 800000000, 1, 1000, 3000, 100, 1)
                """);
        }
    }
}
