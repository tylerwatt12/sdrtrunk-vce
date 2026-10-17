/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.*;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.audio.call.SourceCallReference;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25CallUpdateEvent;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.PushToTalk;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.source.config.SourceConfigRemote;
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

    @Test void repeatedP25PushToTalkBeforeVoiceDoesNotCreateAdditionalActivityRows() throws Exception
    {
        Path database = createDatabase("repeated-ptt.sqlite");
        configure(database,CHANNEL,"TRUNKED","P25_PHASE2","P25");
        Channel parent = new Channel("Test site");
        parent.setConfigurationId(CHANNEL);
        parent.setDecodeConfiguration(new DecodeConfigP25Phase2());
        parent.setSourceConfiguration(new SourceConfigRemote());
        parent.setP25SiteIdentity(new P25SiteIdentity(0xBEE00,0x3A9,1,1));
        var manager = new P25TrafficChannelManager(parent);
        var band = new P25FrequencyBand(0,460012500L,0,12500L,12500,2);
        manager.processFrequencyBand(band); manager.processFrequencyBand(band);
        var observations = new P25Observations();
        long now = System.currentTimeMillis();
        MyEventBus.getGlobalEventBus().register(observations);
        try
        {
            for(int timeslot: List.of(1,2))
            {
                APCO25Channel channel = APCO25Channel.create(0,timeslot - 1);
                channel.setFrequencyBand(band);
                for(int call = 0; call < 2; call++)
                {
                    long start = now + call * 1000L;
                    CorrectedBinaryMessage bits = new CorrectedBinaryMessage(156);
                    bits.setInt(1,IntField.length3(0));
                    bits.setInt(0x80,IntField.length8(80));
                    bits.setInt(101 + call,IntField.length24(104));
                    bits.setInt(1201,IntField.length16(128));
                    PushToTalk ptt = new PushToTalk(bits);
                    MutableIdentifierCollection identifiers = new MutableIdentifierCollection(ptt.getIdentifiers());
                    identifiers.update(APCO25Wacn.create(0xBEE00));
                    identifiers.update(APCO25System.create(0x3A9));
                    var first = manager.processP2TrafficPushToTalk(channel.getDownlinkFrequency(),timeslot,
                        channel,identifiers,ptt,start);
                    var repeated = manager.processP2TrafficPushToTalk(channel.getDownlinkFrequency(),timeslot,
                        channel,identifiers,ptt,start + 60);
                    assertEquals(first.token(),repeated.token());
                    manager.processP2TrafficVoice(channel.getDownlinkFrequency(),timeslot,start + 120);
                    manager.processP2TrafficEndPushToTalk(channel.getDownlinkFrequency(),timeslot,start + 200);
                }
            }
        }
        finally { MyEventBus.getGlobalEventBus().unregister(observations); }
        var writer = new ReceiverActivityWriter(database,30,true,64,1,0);
        writer.start(); observations.records.forEach(writer::enqueue); writer.close();
        assertEquals(0,writer.getDroppedRecords());
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement())
        {
            assertEquals(4,scalar(sql,"SELECT count(*) FROM receiver_activity_event"));
            assertEquals(4,scalar(sql,"SELECT count(DISTINCT source_call_uuid) FROM receiver_activity_event"));
            assertEquals(0,scalar(sql,"SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    private static final class P25Observations
    {
        final ReceiverActivityMapper mapper = new ReceiverActivityMapper();
        final List<ReceiverActivityRecord> records = new ArrayList<>();
        @Subscribe public void started(P25CallStartEvent event)
        {
            if(CHANNEL.equals(event.configurationId())) records.add(mapper.mapCallStart(event));
        }
        @Subscribe public void updated(P25CallUpdateEvent event)
        {
            if(CHANNEL.equals(event.call().configurationId())) records.add(mapper.map(event));
        }
    }

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
            writer.enqueue(new ReceiverActivityRecords.P25CallObservation("conventional:1",initial,true,false));
            writer.enqueue(new ReceiverActivityRecords.P25CallObservation("conventional:1",complete,false,true));
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

    @Test void lateTrunkedFactsEnrichOnlyTheirExactCallAndLeaveSignalingAndCountsAlone() throws Exception
    {
        Path database = createDatabase("trunked-updates.sqlite");
        configure(database,CHANNEL,"TRUNKED","P25_PHASE1","P25");
        long now = System.currentTimeMillis();
        var initial = activity(now,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,
            ReceiverActivityRecords.Action.CALL,"trunked:1").withP25Identities(null,"101","TALKGROUP",List.of(),
                ReceiverActivityRecords.P25Identity.UNKNOWN,ReceiverActivityRecords.P25Identity.ORDINARY,null,null);
        var learned = initial.withP25Identities(202,"101","TALKGROUP",List.of(),
            ReceiverActivityRecords.P25Identity.ORDINARY,ReceiverActivityRecords.P25Identity.ORDINARY,202,null);
        var other = activity(now,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,
            ReceiverActivityRecords.Action.CALL,"trunked:2").withP25Identities(303,"101","TALKGROUP",List.of(),
                ReceiverActivityRecords.P25Identity.ORDINARY,ReceiverActivityRecords.P25Identity.ORDINARY,303,null);
        var grant = activity(now,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,
            ReceiverActivityRecords.Action.GRANT,null).withP25Identities(null,"101","TALKGROUP",List.of(),
                ReceiverActivityRecords.P25Identity.UNKNOWN,ReceiverActivityRecords.P25Identity.ORDINARY,null,null);
        var writer = new ReceiverActivityWriter(database,30,true,16,16,0);
        writer.start();
        writer.enqueue(new ReceiverActivityRecords.P25CallObservation("trunked:1",initial,true,false));
        writer.enqueue(new ReceiverActivityRecords.P25CallObservation("trunked:2",other,true,false));
        writer.enqueue(grant);
        writer.enqueue(new ReceiverActivityRecords.P25CallObservation("trunked:1",learned,false,true));
        writer.enqueue(new ReceiverActivityRecords.P25CallObservation("trunked:1",learned,false,true));
        writer.close();
        assertEquals(0,writer.getDroppedRecords());
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement())
        {
            assertEquals(3,scalar(sql,"SELECT count(*) FROM receiver_activity_event"));
            assertEquals(202,scalar(sql,"SELECT source_observed_local_id FROM receiver_activity_event WHERE source_call_uuid='" +
                SourceCallReference.fromToken("trunked:1") + "'"));
            assertEquals(303,scalar(sql,"SELECT source_observed_local_id FROM receiver_activity_event WHERE source_call_uuid='" +
                SourceCallReference.fromToken("trunked:2") + "'"));
            assertEquals(1,scalar(sql,"SELECT count(*) FROM receiver_activity_event WHERE source_call_uuid IS NULL AND source_observed_local_id IS NULL"));
            assertEquals(0,scalar(sql,"SELECT coalesce(sum(logical_call_count),0) FROM trunked_logical_call_bucket"));
            assertEquals(0,scalar(sql,"SELECT count(*) FROM conventional_activity_summary"));
            assertEquals(0,scalar(sql,"SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test void commonSourceUsesExactReferencesAndPreservesEachSitesObservedWorkingAddress() throws Exception
    {
        Path database = createDatabase("common-source.sqlite");
        configure(database,CHANNEL,"TRUNKED","P25_PHASE1","P25");
        configure(database,SECOND_CHANNEL,"TRUNKED","P25_PHASE1","P25");
        long now = System.currentTimeMillis();
        var source = ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00,0x111,777);
        var first = activity(now,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,
            ReceiverActivityRecords.Action.CALL,"site:1").withP25Identities(null,"101","TALKGROUP",List.of(),
                ReceiverActivityRecords.P25Identity.UNKNOWN,ReceiverActivityRecords.P25Identity.ORDINARY,null,null);
        var second = new ReceiverActivityRecords.ActivityEvent(now,SECOND_CHANNEL,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,"APCO25",ReceiverActivityRecords.Action.CALL,"CALL_GROUP",
            "5002","101","TALKGROUP",List.of(),460012500L,null,1,false,null,null,0xBEE00,0x3A9,0x293,2,2,null,
            true,null,null,TrunkedIdentityDomain.STANDARD,ReceiverActivityRecords.P25Identity.ORDINARY,source,List.of(),
            null,5002,null,null,null,"site:2");
        var unrelated = activity(now,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,
            ReceiverActivityRecords.Action.CALL,"next:1").withP25Identities(null,"101","TALKGROUP",List.of(),
                ReceiverActivityRecords.P25Identity.UNKNOWN,ReceiverActivityRecords.P25Identity.ORDINARY,null,null);
        var resolved = new ReceiverActivityRecords.ResolvedLogicalCall(
            new io.github.dsheirer.audio.call.LogicalCallId(91,1),now,CHANNEL,"APCO25",TrunkedIdentityDomain.STANDARD,
            0xBEE00,0x3A9,101,"TALKGROUP",List.of(),5001,false,null,null,
            ReceiverActivityRecords.P25Identity.ORDINARY,source,List.of(),List.of(),null,5001,null,
            List.of(SourceCallReference.fromToken("site:1"),SourceCallReference.fromToken("site:2")));
        var writer = new ReceiverActivityWriter(database,30,true,16,16,0);
        writer.start();
        writer.enqueue(new ReceiverActivityRecords.P25CallObservation("site:1",first,true,false));
        writer.enqueue(new ReceiverActivityRecords.P25CallObservation("site:2",second,true,false));
        writer.enqueue(new ReceiverActivityRecords.P25CallObservation("next:1",unrelated,true,false));
        writer.enqueue(resolved); writer.enqueue(resolved); writer.close();
        assertEquals(0,writer.getDroppedRecords());
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement())
        {
            assertEquals(1,scalar(sql,"SELECT count(DISTINCT source_identity_summary_id) FROM receiver_activity_event"));
            assertEquals(2,scalar(sql,"SELECT count(*) FROM receiver_activity_event_resolved WHERE source_identity_id=777"));
            assertEquals(1,scalar(sql,"SELECT count(*) FROM receiver_activity_event WHERE source_call_uuid='" +
                SourceCallReference.fromToken("site:1") + "' AND source_observed_local_id IS NULL AND source_observed_working_id IS NULL AND source_identity_summary_id IS NOT NULL"));
            assertEquals(1,scalar(sql,"SELECT count(*) FROM receiver_activity_event WHERE source_call_uuid='" +
                SourceCallReference.fromToken("site:2") + "' AND source_observed_local_id=5002 AND source_observed_working_id=5002"));
            assertEquals(1,scalar(sql,"SELECT count(*) FROM receiver_activity_event WHERE source_call_uuid='" +
                SourceCallReference.fromToken("next:1") + "' AND source_identity_summary_id IS NULL"));
            assertEquals(1,scalar(sql,"SELECT sum(logical_call_count) FROM trunked_logical_call_bucket"));
            assertEquals(0,scalar(sql,"SELECT count(*) FROM pragma_foreign_key_check"));
            try(var plan = sql.executeQuery("EXPLAIN QUERY PLAN UPDATE receiver_activity_event SET source_identity_summary_id=1 " +
                "WHERE source_call_uuid='" + SourceCallReference.fromToken("site:1") + "' AND action_code=1"))
            {
                assertTrue(plan.next());
                assertTrue(plan.getString("detail").contains("idx_receiver_activity_event_source_call"));
            }
        }
    }

    @Test void unheardCallsAcceptQualifiedProofWithoutRecountingOrChangingAKnownQualifiedCaller() throws Exception
    {
        Path database = createDatabase("qualified-updates.sqlite");
        configure(database,CHANNEL,"TRUNKED","P25_PHASE1","P25");
        long now = System.currentTimeMillis();
        var ordinary = activity(now,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,
            ReceiverActivityRecords.Action.CALL,"qualified:1").withP25Identities(5001,"101","TALKGROUP",List.of(),
                ReceiverActivityRecords.P25Identity.ORDINARY,ReceiverActivityRecords.P25Identity.ORDINARY,5001,null);
        var proof = ordinary.withP25Identities(5001,"101","TALKGROUP",List.of(),
            ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00,0x111,777),
            ReceiverActivityRecords.P25Identity.ORDINARY,5001,null);
        var conflict = ordinary.withP25Identities(5001,"101","TALKGROUP",List.of(),
            ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00,0x111,888),
            ReceiverActivityRecords.P25Identity.ORDINARY,5001,null);
        var writer = new ReceiverActivityWriter(database,30,true,16,16,0);
        writer.start();
        writer.enqueue(new ReceiverActivityRecords.P25CallObservation("qualified:1",ordinary,true,false));
        writer.enqueue(new ReceiverActivityRecords.P25CallObservation("qualified:1",proof,false,false));
        writer.enqueue(new ReceiverActivityRecords.P25CallObservation("qualified:1",conflict,false,true));
        for(int index = 2; index <= 3; index++)
        {
            String token = "qualified:" + index;
            var unknown = activity(now + index,ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,
                ReceiverActivityRecords.Action.CALL,token).withP25Identities(null,"101","TALKGROUP",List.of(),
                    ReceiverActivityRecords.P25Identity.UNKNOWN,ReceiverActivityRecords.P25Identity.ORDINARY,null,null);
            var qualified = unknown.withP25Identities(index == 2 ? Integer.valueOf(0) : null,"101","TALKGROUP",List.of(),
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00,0x111,900 + index),
                ReceiverActivityRecords.P25Identity.ORDINARY,null,null);
            writer.enqueue(new ReceiverActivityRecords.P25CallObservation(token,unknown,true,false));
            writer.enqueue(new ReceiverActivityRecords.P25CallObservation(token,qualified,false,true));
            var otherCaller = qualified.withP25Identities(9999,"101","TALKGROUP",List.of(),
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00,0x111,9999),
                ReceiverActivityRecords.P25Identity.ORDINARY,9999,null);
            writer.enqueue(new ReceiverActivityRecords.P25CallObservation(token,otherCaller,false,true));
        }
        writer.close();
        assertEquals(0,writer.getDroppedRecords());
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement())
        {
            assertEquals(3,scalar(sql,"SELECT count(*) FROM receiver_activity_event"));
            assertEquals(777,scalar(sql,"SELECT source_identity_id FROM receiver_activity_event_resolved WHERE id=(SELECT id FROM receiver_activity_event WHERE source_call_uuid='" +
                SourceCallReference.fromToken("qualified:1") + "')"));
            assertEquals(2,scalar(sql,"SELECT count(*) FROM receiver_activity_event_resolved WHERE source_identity_id IN (902,903) AND source_observed_local_id IS NULL"));
            assertEquals(0,scalar(sql,"SELECT coalesce(sum(logical_call_count),0) FROM trunked_logical_call_bucket"));
            assertEquals(0,scalar(sql,"SELECT count(*) FROM conventional_activity_summary"));
            assertEquals(0,scalar(sql,"SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    private static long scalar(java.sql.Statement statement, String query) throws Exception
    {
        try(var rows = statement.executeQuery(query)) { assertTrue(rows.next()); return rows.getLong(1); }
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
