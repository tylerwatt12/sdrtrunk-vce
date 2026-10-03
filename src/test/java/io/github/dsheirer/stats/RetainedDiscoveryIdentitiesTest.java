/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedDiscoveryIdentitiesTest
{
    private static final String DMR = "00000000-0000-0000-0000-000000000081";
    private static final String NXDN = "00000000-0000-0000-0000-000000000082";
    @TempDir Path root;
    private Path path;
    private StatsWebDatabase database;

    @BeforeEach void setup() throws Exception
    {
        path = root.resolve("sdrtrunk.sqlite");
        try(Connection connection = connection(); Statement statement = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(81,'DMR','DMR'),(82,'NXDN','NXDN')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,alias_list_id,
                    decoder_type,address_domain_code,primary_frequency_hz,config_json) VALUES
                ('%s','TRUNKED',1,81,'DMR',0,451000000,'{"decodeConfiguration":{"channelMode":"TRUNKED"}}'),
                ('%s','TRUNKED',2,82,'NXDN',1,452000000,'{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
                """.formatted(DMR,NXDN));
            statement.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,dmr_model_code,
                    dmr_network_id,nxdn_location_category_code,nxdn_system_id,first_seen_ms,last_seen_ms) VALUES
                (81,'dmr:tier3:small:17',3,0,2,17,NULL,NULL,1000,4000),
                (82,'nxdn-c:regional:41',4,1,NULL,NULL,2,41,1000,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,radio_system_id,radio_system_assigned_at_ms,
                    first_seen_ms,last_seen_ms) VALUES(81,'%s',81,1000,1000,4000),(82,'%s',82,1000,1000,4000)
                """.formatted(DMR,NXDN));
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot(channel_id,snapshot_hash,protocol_code,variant_code,
                    observed_location_category_code,observed_network_id,observed_system_id,observed_site_id,
                    observed_model_code,primary_frequency_hz,current_control_hz,first_seen_ms,last_seen_ms,observation_count)
                VALUES(81,'%s',3,1,0,17,NULL,3,2,451000000,451000000,1000,4000,4),
                      (82,'%s',4,1,2,NULL,41,5,NULL,452000000,452000000,1000,4000,4)
                """.formatted("a".repeat(64),"b".repeat(64)));
        }
        database = new StatsWebDatabase(new UserPreferences(),path);
    }

    @Test void returnsOnlyExactNativeSystemsFromRepeatedCurrentSiteHistory()
    {
        var dmr = database.retainedDiscoveryIdentities(evidence("dmr", "dmr:tier3:small:17", 3));
        assertEquals(1,dmr.size());
        assertEquals(DMR,dmr.getFirst().configurationId());
        assertEquals("dmr:tier3:small:17:site:3",dmr.getFirst().identity().siteKey());
        assertEquals(451000000,dmr.getFirst().observedControlHz());
        var nxdn = database.retainedDiscoveryIdentities(evidence("nxdn","nxdn-c:regional:41",5));
        assertEquals(1,nxdn.size());
        assertEquals(NXDN,nxdn.getFirst().configurationId());
        assertTrue(database.retainedDiscoveryIdentities(evidence("dmr","dmr:tier3:small:18",3)).isEmpty());
    }

    @Test void excludesInsufficientConflictingAndChangedProtocolObservations() throws Exception
    {
        var proof = evidence("dmr","dmr:tier3:small:17",3);
        execute("UPDATE trunked_site_snapshot SET observation_count=2 WHERE channel_id=81");
        assertTrue(database.retainedDiscoveryIdentities(proof).isEmpty());
        execute("UPDATE trunked_site_snapshot SET observation_count=4,last_seen_ms=2000 WHERE channel_id=81");
        assertTrue(database.retainedDiscoveryIdentities(proof).isEmpty());
        execute("UPDATE trunked_site_snapshot SET last_seen_ms=4000,observed_network_id=18 WHERE channel_id=81");
        assertTrue(database.retainedDiscoveryIdentities(proof).isEmpty());
        execute("UPDATE trunked_site_snapshot SET observed_network_id=17,color_code_ts1=2,color_code_ts2=3 WHERE channel_id=81");
        assertTrue(database.retainedDiscoveryIdentities(proof).isEmpty());
        execute("UPDATE trunked_site_snapshot SET color_code_ts2=2 WHERE channel_id=81");
        execute("UPDATE configuration_channel SET channel_kind='CONVENTIONAL'," +
            "config_json='{\"decodeConfiguration\":{\"channelMode\":\"CONVENTIONAL\"}}' WHERE configuration_id='"+DMR+"'");
        assertTrue(database.retainedDiscoveryIdentities(proof).isEmpty());
    }

    @Test void absentDatabaseReturnsNoRetainedMatch()
    {
        var missing = new StatsWebDatabase(new UserPreferences(),root.resolve("absent.sqlite"));
        assertTrue(missing.retainedDiscoveryIdentities(evidence("dmr","dmr:tier3:small:17",3)).isEmpty());
    }

    private Connection connection() throws Exception { return DriverManager.getConnection("jdbc:sqlite:"+path); }
    private void execute(String sql) throws Exception
    { try(Connection connection=connection(); Statement statement=connection.createStatement()) { statement.executeUpdate(sql); } }
    private static TrunkedDiscoveryEvidence evidence(String protocol,String key,int site)
    {
        String[] parts=key.split(":"); int number=Integer.parseInt(parts[parts.length-1]);
        var identity=new TrunkedDiscoveryEvidence.Identity(null,key,key+":site:"+site,"dmr".equals(protocol)?number:null,
            number,site,"dmr".equals(protocol)?parts[2]:null,"nxdn".equals(protocol)?parts[1]:null,null,null,null);
        return new TrunkedDiscoveryEvidence(protocol,"dmr".equals(protocol)?"TIER_III":"TYPE_C",identity,
            Map.of("channel_mode","TRUNKED"),List.of(),100,100,25,0,4000,"confirmed");
    }
}
