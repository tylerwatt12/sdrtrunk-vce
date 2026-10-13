/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TopicAlertOptionsTest
{
    @TempDir Path directory;
    @Test void catalogKeepsNamedSourcesAndTopologySeparateFromDecodedProtocolCompatibility() throws Exception
    {
        Path database = directory.resolve("options.sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        List<String> decoders = List.of("P25_PHASE1", "P25_PHASE2", "P25_CONVENTIONAL", "NBFM", "AM", "DMR", "NXDN");
        try(var connection = SdrTrunkDatabase.open(database))
        {
            for(int index = 0; index < decoders.size(); index++)
            {
                String decoder = decoders.get(index), id = "00000000-0000-4000-8000-%012d".formatted(index + 1);
                String family = decoder.startsWith("P25") ? "P25" : List.of("NBFM", "AM").contains(decoder) ? "NBFM" : decoder;
                String kind = List.of("P25_PHASE1", "P25_PHASE2", "DMR", "NXDN").contains(decoder) ? "TRUNKED" : "CONVENTIONAL";
                try(var sql = connection.prepareStatement("INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,alias_list_id,decoder_type,address_domain_code,config_json) VALUES(?,?,?,'County radio','East','Dispatch',(SELECT id FROM alias_list WHERE family=? LIMIT 1),?,?,?)"))
                {
                    sql.setString(1,id); sql.setString(2,kind); sql.setInt(3,index); sql.setString(4,family);
                    sql.setString(5,decoder); sql.setInt(6,"NXDN".equals(decoder) ? 1 : 0);
                    sql.setString(7,List.of("DMR","NXDN").contains(decoder) ? "{\"decodeConfiguration\":{\"channelMode\":\"TRUNKED\"}}" : "{}"); sql.executeUpdate();
                }
            }
            try(var sql = connection.createStatement())
            {
                sql.execute("INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) VALUES(1,'p25:00001:001',1,0,1,1,1000,2000)");
                sql.execute("INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,radio_system_assigned_at_ms) VALUES(1,'00000000-0000-4000-8000-000000000001',1000,2000,1,1000)");
                sql.execute("INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,rfss,site) VALUES(1,1000,2000,1,2)");
            }
        }
        var catalog = TopicAlertOptions.read(database);
        var channels = rows(catalog, "channels"); assertEquals(7, channels.size());
        for(var channel: channels)
        {
            assertTrue(((String)channel.get("label")).contains("Dispatch")); assertTrue(((String)channel.get("label")).contains("East"));
            assertFalse(((String)channel.get("protocol_family")).isEmpty()); assertTrue(channel.containsKey("channel_kind"));
            assertTrue(decoders.contains(channel.get("decoder_type")));
        }
        assertEquals("P25", rows(catalog,"systems").getFirst().get("protocol_family"));
        var site = rows(catalog,"sites").getFirst(); assertEquals("1:2",site.get("value")); assertEquals("East",site.get("label"));
        assertEquals("P25",site.get("protocol_family")); assertEquals("00000000-0000-4000-8000-000000000001",site.get("channel_id"));
        var protocols = rows(catalog,"protocols");
        for(String value: List.of("APCO25","APCO25_PHASE2")) assertEquals(List.of("P25"), protocols.stream().filter(p -> value.equals(p.get("value"))).findFirst().orElseThrow().get("protocol_families"));
        assertEquals(List.of("AM","NBFM"),protocols.stream().filter(p -> "DCS".equals(p.get("value"))).findFirst().orElseThrow().get("protocol_families"));
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> catalog, String key)
    { return (List<Map<String,Object>>)catalog.get(key); }
}
