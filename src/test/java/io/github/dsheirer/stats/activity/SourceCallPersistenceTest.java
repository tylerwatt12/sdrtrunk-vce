/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.audio.call.SourceCallReference;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceCallPersistenceTest
{
    private static final String CHANNEL = "123e4567-e89b-42d3-a456-426614174000";
    private static final String SECOND_CHANNEL = "123e4567-e89b-42d3-a456-426614174001";
    @TempDir Path directory;

    @Test void writerStoresOnlyExactCallReferencesAndNeverTheTransientToken() throws Exception
    {
        Path database = createDatabase("call-writes.sqlite");
        configure(database,CHANNEL,"TRUNKED","P25_PHASE1","P25");
        long now = System.currentTimeMillis();
        var writer = new ReceiverActivityWriter(database,30,true,16,1,0);
        writer.start();
        try
        {
            writer.enqueue(activity(now,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,ReceiverActivityRecords.Action.CALL,"producer:1"));
            writer.enqueue(activity(now + 1,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,ReceiverActivityRecords.Action.CALL,"producer:2"));
            writer.enqueue(activity(now + 2,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,ReceiverActivityRecords.Action.GRANT,"producer:1"));
            writer.enqueue(activity(now + 3,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,ReceiverActivityRecords.Action.CALL,null));
        }
        finally { writer.close(); }
        assertEquals(4,writer.getWrittenRecords()); assertEquals(0,writer.getDroppedRecords());
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement();
            var rows = sql.executeQuery("SELECT source_call_uuid FROM receiver_activity_event ORDER BY observed_at_ms,id"))
        {
            assertTrue(rows.next()); assertEquals(SourceCallReference.fromToken("producer:1"),rows.getString(1));
            assertTrue(rows.next()); assertEquals(SourceCallReference.fromToken("producer:2"),rows.getString(1));
            assertTrue(rows.next()); assertNull(rows.getString(1));
            assertTrue(rows.next()); assertNull(rows.getString(1)); assertFalse(rows.next());
        }
    }

    @Test void conventionalCallUpdatesKeepOneOriginalActivityRowAndItsSourceReference() throws Exception
    {
        Path database = createDatabase("conventional-updates.sqlite");
        configure(database,CHANNEL,"CONVENTIONAL","P25_CONVENTIONAL","P25");
        long now = System.currentTimeMillis();
        var writer = new ReceiverActivityWriter(database,30,true,16,1,0);
        writer.start();
        try
        {
            var initial = activity(now,ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_P25,
                ReceiverActivityRecords.Action.CALL,"conventional:1");
            var complete = activity(now + 100,ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_P25,
                ReceiverActivityRecords.Action.CALL,"conventional:1");
            writer.enqueue(new ReceiverActivityRecords.ConventionalCallObservation("conventional:1",initial,true,false));
            writer.enqueue(new ReceiverActivityRecords.ConventionalCallObservation("conventional:1",complete,false,true));
        }
        finally { writer.close(); }
        assertEquals(0,writer.getDroppedRecords());
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement();
            var rows = sql.executeQuery("SELECT source_call_uuid FROM receiver_activity_event"))
        {
            assertTrue(rows.next()); assertEquals(SourceCallReference.fromToken("conventional:1"),rows.getString(1));
            assertFalse(rows.next());
        }
    }

    @Test void completedDmrAndNxdnConventionalCallsPreserveTheirProducerReferences() throws Exception
    {
        Path database = createDatabase("digital-conventional.sqlite");
        configure(database,CHANNEL,"CONVENTIONAL","DMR","DMR");
        configure(database,SECOND_CHANNEL,"CONVENTIONAL","NXDN","NXDN");
        long now = System.currentTimeMillis();
        var writer = new ReceiverActivityWriter(database,30,true,16,1,0);
        writer.start();
        try
        {
            writer.enqueue(new ReceiverActivityRecords.DmrConventionalCall(now,now + 100,CHANNEL,460012500,1,
                ReceiverActivityRecords.DmrTargetKind.GROUP,7,202,null,false,"dmr:1"));
            writer.enqueue(new ReceiverActivityRecords.NxdnConventionalCall(now + 1,now + 101,SECOND_CHANNEL,460012500,
                ReceiverActivityRecords.NxdnTargetKind.GROUP,7,202,null,false,TrunkedIdentityDomain.NXDN_TYPE_C,"nxdn:1"));
        }
        finally { writer.close(); }
        assertEquals(2,writer.getWrittenRecords()); assertEquals(0,writer.getDroppedRecords());
        List<String> references = new ArrayList<>();
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement();
            var rows = sql.executeQuery("SELECT source_call_uuid FROM receiver_activity_event ORDER BY observed_at_ms,id"))
        { while(rows.next()) references.add(rows.getString(1)); }
        assertEquals(List.of(SourceCallReference.fromToken("dmr:1"),SourceCallReference.fromToken("nxdn:1")),references);
    }

    private Path createDatabase(String name) throws Exception
    {
        Path database = directory.resolve(name);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection); SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
            ReceiverActivitySchema.create(connection); DmrActivitySchema.create(connection); TrunkedSiteSchema.create(connection);
        }
        return database;
    }

    private static void configure(Path database, String id, String kind, String decoder, String family) throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var insert = connection.prepareStatement("""
            INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                alias_list_id,radioresolve_id,decoder_type,address_domain_code,primary_frequency_hz,config_json)
            VALUES(?,?,0,'Fixture system','Fixture site','Fixture channel',
                (SELECT id FROM alias_list WHERE family=? LIMIT 1),NULL,?,?,460012500,?)
            """))
        {
            insert.setString(1,id); insert.setString(2,kind); insert.setString(3,family); insert.setString(4,decoder);
            insert.setInt(5, decoder.equals("NXDN") ? 1 : 0);
            insert.setString(6,decoder.equals("DMR") || decoder.equals("NXDN") ?
                "{\"decodeConfiguration\":{\"channelMode\":\"CONVENTIONAL\"}}" : "{}");
            insert.executeUpdate();
        }
    }

    private static ReceiverActivityRecords.ActivityEvent activity(long time, ReceiverActivityRecords.ReceiverKind kind,
        ReceiverActivityRecords.Action action, String token)
    {
        return new ReceiverActivityRecords.ActivityEvent(time,CHANNEL,kind,"APCO25",action,"CALL_GROUP","202","101",
            "TALKGROUP",List.of(),460012500L,null,1,false,null,null,0xBEE00,0x3A9,0x293,2,1,null,
            action == ReceiverActivityRecords.Action.CALL,null,null,TrunkedIdentityDomain.STANDARD,
            ReceiverActivityRecords.P25Identity.ORDINARY,ReceiverActivityRecords.P25Identity.ORDINARY,List.of(),
            null,null,null,null,null,token);
    }
}
