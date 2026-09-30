/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedStatisticsCatalogPresentationTest
{
    private static final String METRO = "p25:bee00:49f";
    private static final String COUNTY = "p25:bee00:4a0";
    private static final String METRO_SITE = "00000000-0000-0000-0000-000000000071";
    private static final String COUNTY_SITE = "00000000-0000-0000-0000-000000000072";
    private static final String P25_CONVENTIONAL = "00000000-0000-0000-0000-000000000073";
    private static final String DMR_CONVENTIONAL = "00000000-0000-0000-0000-000000000074";

    @TempDir
    Path mDirectory;

    private RetainedStatisticsCatalog mCatalog;

    @BeforeEach
    void setUp() throws Exception
    {
        Path database = mDirectory.resolve("retained-presentation.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            seed(statement);
        }
        mCatalog = new RetainedStatisticsCatalog(database);
    }

    @Test
    void sourcesSitesAndChannelsExposeFriendlyScopedContext() throws Exception
    {
        RetainedStatisticsCatalog.Page systems = mCatalog.sources("radio_system", null, 10, 0);
        assertEquals(2, systems.totalCount());
        Map<String,Object> metro = row(systems, "source_key", METRO);
        assertEquals("Metro P25", metro.get("label"));
        assertEquals("Metro P25 aliases", metro.get("alias_list_name"));
        assertEquals(71, number(metro, "alias_list_id"));
        assertEquals(Map.of("kind", "radio_system", "key", METRO), metro.get("entity_ref"));
        assertEquals(1, mCatalog.sources("radio_system", "Metro P25 aliases", 10, 0).totalCount());

        RetainedStatisticsCatalog.Page channels = mCatalog.sources("saved_channel", null, 10, 0);
        assertEquals(4, channels.totalCount());
        Map<String,Object> portable = row(channels, "source_key", P25_CONVENTIONAL);
        assertEquals("Portable P25", portable.get("label"), "an unassigned channel uses its saved name");
        assertNull(portable.get("radio_system_key"));
        assertEquals("Metro P25 aliases", portable.get("alias_list_name"));
        assertEquals(Map.of("kind", "channel", "key", P25_CONVENTIONAL),
            portable.get("entity_ref"));

        RetainedStatisticsCatalog.Page sites = mCatalog.sites("radio_system", METRO, null, 10, 0);
        assertEquals(1, sites.totalCount());
        Map<String,Object> north = sites.rows().getFirst();
        assertEquals("North site", north.get("label"));
        assertEquals("North control", north.get("channel_name"));
        assertEquals("Metro P25 aliases", north.get("alias_list_name"));
        assertEquals(Map.of("kind", "channel", "key", METRO_SITE), north.get("entity_ref"));
        Map<String,Object> portableSite = mCatalog.sites("saved_channel", P25_CONVENTIONAL,
            null, 10, 0).rows().getFirst();
        assertEquals("Portable P25", portableSite.get("label"));
        assertEquals("CONVENTIONAL", portableSite.get("channel_kind"));

        RetainedStatisticsCatalog.Page metroChannels = mCatalog.results("radio_system", METRO,
            "channels", null, null, 10, 0);
        assertEquals(1, metroChannels.totalCount(),
            "a conventional P25 channel sharing the alias list does not join trunked system scope");
        Map<String,Object> control = metroChannels.rows().getFirst();
        assertEquals("North control", control.get("label"));
        assertEquals("North site", control.get("site_name"));
        assertEquals("Metro P25 aliases", control.get("alias_list_name"));
        assertEquals(Map.of("kind", "channel", "key", METRO_SITE), control.get("entity_ref"));
        assertEquals(Map.of("kind", "channel", "configuration_id", METRO_SITE),
            control.get("target"));

        Map<String,Object> system = mCatalog.results("radio_system", METRO, "systems",
            null, null, 10, 0).rows().getFirst();
        assertEquals("Metro P25", system.get("label"));
        assertEquals("Metro P25 aliases", system.get("alias_list_name"));
        assertEquals(Map.of("kind", "radio_system", "key", METRO), system.get("entity_ref"));
    }

    @Test
    void localP25AliasesResolveBeforePagingWithoutCrossSystemLeakage() throws Exception
    {
        RetainedStatisticsCatalog.Page radios = mCatalog.results("radio_system", METRO,
            "radios", null, null, 1, 0);
        assertEquals(2, radios.totalCount());
        assertTrue(radios.hasMore());
        Map<String,Object> engine = radios.rows().getFirst();
        assertEquals("Engine 12 (Radio 202)", engine.get("label"));
        assertEquals("Engine 12", engine.get("alias_name"),
            "the exact alias wins over an overlapping range");
        assertEquals("Fire", engine.get("alias_group"));
        assertEquals("Metro P25 aliases", engine.get("alias_list_name"));
        assertEquals(71, number(engine, "alias_list_id"));
        assertEquals(202, number(engine, "native_id"));
        assertEquals(7, number(engine, "logical_call_count"));
        assertEquals(4000, number(engine, "last_seen_ms"));
        String engineKey = "v1-r-bee00-49f-202";
        assertEquals(Map.of("kind", "radio", "radio_system_key", METRO,
            "identity_key", engineKey), engine.get("entity_ref"));
        assertEquals(Map.of("kind", "radio", "radio_system_key", METRO,
            "identity_key", engineKey), engine.get("target"));

        RetainedStatisticsCatalog.Page searched = mCatalog.results("radio_system", METRO,
            "radios", null, "Support radios", 1, 0);
        assertEquals(1, searched.totalCount(), "alias text is filtered before LIMIT");
        Map<String,Object> support = searched.rows().getFirst();
        assertEquals("Support radios (Radio 303)", support.get("label"));
        assertEquals("Command", support.get("alias_group"));
        assertEquals(303, number(support, "native_id"));

        Map<String,Object> dispatch = mCatalog.results("radio_system", METRO, "talkgroups",
            null, null, 10, 0).rows().getFirst();
        assertEquals("Fire Dispatch (Talkgroup 30)", dispatch.get("label"));
        assertEquals("Dispatch", dispatch.get("alias_group"));
        assertEquals(30, number(dispatch, "native_id"));
        assertEquals(Map.of("kind", "talkgroup", "radio_system_key", METRO,
            "identity_key", "v1-g-bee00-49f-30"), dispatch.get("entity_ref"));
        assertEquals(dispatch.get("entity_ref"), dispatch.get("target"));

        Map<String,Object> county = mCatalog.results("radio_system", COUNTY, "radios",
            null, null, 10, 0).rows().getFirst();
        assertEquals("County Engine (Radio 202)", county.get("label"));
        assertEquals("County P25 aliases", county.get("alias_list_name"));
        assertEquals(COUNTY, ((Map<?,?>)county.get("target")).get("radio_system_key"));
        assertEquals(0, mCatalog.results("radio_system", METRO, "radios", null,
            "County Engine", 10, 0).totalCount());
        assertEquals(0, mCatalog.results("radio_system", COUNTY, "radios", null,
            "Engine 12", 10, 0).totalCount());
        assertEquals(2, mCatalog.results("radio_system", METRO, "radios", null,
            null, 10, 0).totalCount(), "the conventional P25 channel shares a list, not identities");
    }

    @Test
    void conventionalDmrAliasesRetainChannelAndFrequencyTargets() throws Exception
    {
        Map<String,Object> radio = mCatalog.results("saved_channel", DMR_CONVENTIONAL,
            "radios", null, "DMR Unit", 10, 0).rows().getFirst();
        assertEquals("DMR Unit (Radio 202)", radio.get("label"));
        assertEquals("Operations", radio.get("alias_group"));
        assertEquals("Metro DMR aliases", radio.get("alias_list_name"));
        assertEquals(Map.of("kind", "channel", "key", DMR_CONVENTIONAL), radio.get("entity_ref"));
        assertEquals(Map.of("kind", "conventional_radio", "configuration_id", DMR_CONVENTIONAL,
            "frequency_hz", 460012500L, "timeslot", 2, "native_id", 202), radio.get("target"));

        RetainedStatisticsCatalog.Page rangeSearch = mCatalog.results("saved_channel", DMR_CONVENTIONAL,
            "radios", null, "DMR Response", 1, 0);
        assertEquals(1, rangeSearch.totalCount());
        assertEquals("DMR Response (Radio 303)", rangeSearch.rows().getFirst().get("label"));

        Map<String,Object> group = mCatalog.results("saved_channel", DMR_CONVENTIONAL,
            "talkgroups", null, null, 10, 0).rows().getFirst();
        assertEquals("DMR Dispatch (Talkgroup 7)", group.get("label"));
        assertEquals(Map.of("kind", "channel", "key", DMR_CONVENTIONAL), group.get("entity_ref"));
        assertEquals(Map.of("kind", "conventional_talkgroup", "configuration_id", DMR_CONVENTIONAL,
            "frequency_hz", 460012500L, "timeslot", 2, "native_id", 7), group.get("target"));
    }

    private static Map<String,Object> row(RetainedStatisticsCatalog.Page page, String field, String value)
    {
        return page.rows().stream().filter(candidate -> value.equals(candidate.get(field)))
            .findFirst().orElseThrow();
    }

    private static long number(Map<String,Object> row, String field)
    {
        return ((Number)row.get(field)).longValue();
    }

    private static void seed(Statement statement) throws Exception
    {
        statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES " +
            "(71,'Metro P25 aliases','P25'),(72,'County P25 aliases','P25')," +
            "(74,'Metro DMR aliases','DMR')");
        statement.executeUpdate("""
            INSERT INTO alias(id,alias_list_id,name,description,group_name,matcher_type,protocol,
                value,min_value,max_value) VALUES
              (7101,71,'Engine 12','Engine company','Fire','RADIO_ID','APCO25',202,NULL,NULL),
              (7102,71,'Response radios',NULL,'Fire','RADIO_ID_RANGE','APCO25',NULL,200,210),
              (7103,71,'Support radios',NULL,'Command','RADIO_ID_RANGE','APCO25',NULL,300,310),
              (7104,71,'Fire Dispatch',NULL,'Dispatch','TALKGROUP','APCO25',30,NULL,NULL),
              (7201,72,'County Engine',NULL,'County','RADIO_ID','APCO25',202,NULL,NULL),
              (7401,74,'DMR Unit',NULL,'Operations','RADIO_ID','DMR',202,NULL,NULL),
              (7402,74,'DMR Dispatch',NULL,'Dispatch','TALKGROUP','DMR',7,NULL,NULL),
              (7403,74,'DMR Response',NULL,'Operations','RADIO_ID_RANGE','DMR',NULL,300,310)
            """);
        statement.executeUpdate("""
            INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,
                name,alias_list_id,decoder_type,primary_frequency_hz,config_json) VALUES
              ('%1$s','TRUNKED',71,'Metro P25','North site','North control',71,'P25_PHASE1',851012500,'{}'),
              ('%2$s','TRUNKED',72,'County P25','West site','West control',72,'P25_PHASE1',852012500,'{}'),
              ('%3$s','CONVENTIONAL',73,NULL,NULL,'Portable P25',71,'P25_PHASE1',155012500,'{}'),
              ('%4$s','CONVENTIONAL',74,'Metro DMR','DMR site','DMR repeater',74,'DMR',460012500,
               '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
            """.formatted(METRO_SITE, COUNTY_SITE, P25_CONVENTIONAL, DMR_CONVENTIONAL));
        statement.executeUpdate("""
            INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,
                first_seen_ms,last_seen_ms) VALUES
              (71,'%1$s',1,0,0xBEE00,0x49F,1000,4000),
              (72,'%2$s',1,0,0xBEE00,0x4A0,1000,4000)
            """.formatted(METRO, COUNTY));
        statement.executeUpdate("""
            INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,
                radio_system_assigned_at_ms) VALUES
              (71,'%1$s',1000,4000,71,1000),(72,'%2$s',1000,4000,72,1000),
              (73,'%3$s',1000,4000,NULL,NULL),(74,'%4$s',1000,4000,NULL,NULL)
            """.formatted(METRO_SITE, COUNTY_SITE, P25_CONVENTIONAL, DMR_CONVENTIONAL));
        statement.executeUpdate("""
            INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,observation_count,
                protocol,rfss,site,primary_frequency_hz,current_control_hz) VALUES
              (71,1000,4000,4,'APCO25',1,1,851012500,851012500),
              (72,1000,4000,4,'APCO25',1,2,852012500,852012500)
            """);
        statement.executeUpdate("""
            INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,
                home_system_id,identity_id,first_seen_ms,last_seen_ms,logical_call_count) VALUES
              (7101,71,2,0xBEE00,0x49F,202,1000,4000,7),
              (7102,71,2,0xBEE00,0x49F,303,1000,4000,2),
              (7103,71,1,0xBEE00,0x49F,30,1000,4000,11),
              (7201,72,2,0xBEE00,0x4A0,202,1000,4000,3)
            """);
        statement.executeUpdate("""
            INSERT INTO trunked_radio_channel_presence(radio_system_id,radio_identity_id,channel_id,
                observed_local_id,evidence_code,confirmed_at_ms) VALUES
              (71,7101,71,202,1,4000),(71,7102,71,303,1,4000),(72,7201,72,202,1,4000)
            """);
        statement.executeUpdate("""
            INSERT INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site,
                first_seen_ms,last_seen_ms) VALUES (7101,71,1,1,1000,4000)
            """);
        statement.executeUpdate("""
            INSERT INTO p25_site_call_identity_bucket(radio_system_id,learned_site_id,channel_id,
                bucket_start_ms,identity_role_code,identity_kind_code,identity_summary_id,
                observed_local_id,last_observed_at_ms,observed_call_count) VALUES
              (71,7101,71,0,1,1,7103,30,4000,11)
            """);
        statement.executeUpdate("""
            INSERT INTO dmr_conventional_radio_summary(channel_id,frequency_hz,timeslot,radio_id,
                first_seen_ms,last_seen_ms,call_count) VALUES
              (74,460012500,2,202,1000,4000,3),
              (74,460025000,1,303,1000,4000,1)
            """);
        statement.executeUpdate("""
            INSERT INTO dmr_conventional_talkgroup_summary(channel_id,frequency_hz,timeslot,talkgroup_id,
                first_seen_ms,last_seen_ms,call_count) VALUES (74,460012500,2,7,1000,4000,3)
            """);
    }
}
