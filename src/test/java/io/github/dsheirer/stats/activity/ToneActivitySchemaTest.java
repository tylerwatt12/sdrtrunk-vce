/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.audio.tone.ToneSummaryPayload;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToneActivitySchemaTest
{
    private static final String CONFIG = "123e4567-e89b-42d3-a456-426614174000";
    private static final String DMR_CONFIG = "123e4567-e89b-42d3-a456-426614174001";
    private static final String TRUNKED_CONFIG = "123e4567-e89b-42d3-a456-426614174002";
    @TempDir Path temporary;

    @Test void preservesExplicitContextAndSharedPayloadWithoutSummaryCredits() throws Exception
    {
        try(Connection connection = open(":memory:"))
        {
            CompletedToneSequence sequence = sequence(1, false, 2);
            var observation = new ReceiverActivityMapper().mapCompletedToneSequence(sequence);
            assertNotNull(observation); assertFalse(observation.activity().countedCall());
            assertTrue(ToneActivitySchema.record(connection, observation, true));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM conventional_activity_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM conventional_activity_bucket"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM trunked_signaling_activity_bucket"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM trunked_logical_call_bucket"));
            var rows = page(connection); ToneActivitySchema.enrichPage(connection, rows);
            assertEquals(851_000_000L, ((Number)rows.getFirst().get("frequency_hz")).longValue());
            assertEquals(ToneSummaryPayload.summary(sequence), rows.getFirst().get("paging_tone_summary"));
            assertEquals(24, ReceiverActivityFilterCatalog.actionCode("TONE"));
            assertEquals(62, ReceiverActivityFilterCatalog.eventTypeCode("tone"));
            integrity(connection);
        }
    }

    @Test void keepsUnknownEncodedFrequenciesAndOffsetsUnknown() throws Exception
    {
        try(Connection connection = open(":memory:"))
        {
            CompletedToneSequence sequence = sequence(2, true, 1);
            assertTrue(ToneActivitySchema.record(connection, new ReceiverActivityMapper().mapCompletedToneSequence(sequence), true));
            var rows = page(connection); ToneActivitySchema.enrichPage(connection, rows);
            assertEquals(ToneSummaryPayload.summary(sequence), rows.getFirst().get("paging_tone_summary"));
            assertFalse(((Map<?,?>)rows.getFirst().get("paging_tone_summary")).containsKey("phase"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_tone WHERE codec_symbol='BUSY_TONE' AND frequency_1_millihz IS NULL AND offset_ms IS NULL AND confidence_ppm IS NULL"));
        }
    }

    @Test void detailedHistoryOffAddsNoToneRowsOrCredits() throws Exception
    {
        try(Connection connection = open(":memory:"))
        {
            assertFalse(ToneActivitySchema.record(connection, new ReceiverActivityMapper().mapCompletedToneSequence(sequence(3, false, 1)), false));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_tone"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM conventional_activity_summary"));
        }
    }

    @Test void trunkedToneRetainsNativeOwnerAndIdentityWithoutCallSignalingOrAliasCredits() throws Exception
    {
        try(Connection connection = open(":memory:"))
        {
            var original = sequence(30, false, 1); var snapshot = original.snapshot();
            var source = new CallLegSource(DecoderType.P25_PHASE1, TRUNKED_CONFIG, "Trunked fixture", null, 0,
                new io.github.dsheirer.module.decode.p25.P25SiteIdentity(0xBEE00,0x123,1,2),
                io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
                ChannelConfigurationPolicy.ChannelKind.TRUNKED,true);
            var nativeSnapshot = new AudioCallSnapshot(snapshot.callId(),null,null,snapshot.identifierCollection(),Set.of(),
                snapshot.startTimestamp(),snapshot.lastActivityTimestamp(),1,1,snapshot.startTimestamp(),snapshot.lastActivityTimestamp(),
                false,true,CallEncryptionState.CLEAR,false,null,VoiceCallQuality.EMPTY,snapshot.callLegId(),source,null);
            var sequence = new CompletedToneSequence(nativeSnapshot,original.firstVoiceSampleTimestamp(),original.source(),original.method(),original.tones());
            assertTrue(ToneActivitySchema.record(connection,new ReceiverActivityMapper().mapCompletedToneSequence(sequence),true));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM receiver_activity_event WHERE radio_system_id IS NOT NULL AND source_identity_summary_id IS NOT NULL AND target_identity_summary_id IS NOT NULL AND observed_rfss=1 AND observed_site=2"));
            assertEquals(2,scalar(connection,"SELECT count(*) FROM radio_system_identity_summary"));
            assertEquals(0,scalar(connection,"SELECT sum(logical_call_count+source_logical_call_count+target_logical_call_count+encrypted_logical_call_count+recorded_output_count+streamed_output_count+grant_count+register_count+page_count+unknown_count) FROM radio_system_identity_summary"));
            assertEquals(0,scalar(connection,"SELECT count(*) FROM trunked_signaling_activity_bucket"));
            assertEquals(0,scalar(connection,"SELECT count(*) FROM trunked_logical_call_bucket"));
            assertEquals(0,scalar(connection,"SELECT coalesce(sum(coalesce(logical_call_count,0)+coalesce(grant_observation_count,0)+coalesce(register_observation_count,0)),0) FROM alias_activity_summary"));
            integrity(connection);
        }
    }

    @Test void committedWriterReceiptsSuppressDuplicatesAcrossBatches() throws Exception
    {
        Path database = temporary.resolve("writer.sqlite");
        try(Connection ignored = open(database.toString())) { }
        var observation = new ReceiverActivityMapper().mapCompletedToneSequence(sequence(4, false, 1));
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 1, 0);
        writer.start(); assertTrue(writer.enqueue(observation)); assertTrue(writer.enqueue(observation)); writer.close();
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_tone_sequence"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_tone"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM conventional_activity_summary"));
        }
    }

    @Test void cleanupCountsBoundedChildrenBeforeParentsAndResetRemovesFacts() throws Exception
    {
        try(Connection connection = open(":memory:"))
        {
            for(int index = 0; index < 40; index++) assertTrue(ToneActivitySchema.record(connection,
                new ReceiverActivityMapper().mapCompletedToneSequence(sequence(100 + index, false, 32)), true));
            long before = scalar(connection, "SELECT (SELECT count(*) FROM receiver_activity_event)+(SELECT count(*) FROM receiver_activity_tone_sequence)+(SELECT count(*) FROM receiver_activity_tone)");
            int reported = ReceiverActivitySchema.runRetentionPass(connection, System.currentTimeMillis() + 10_000);
            long after = scalar(connection, "SELECT (SELECT count(*) FROM receiver_activity_event)+(SELECT count(*) FROM receiver_activity_tone_sequence)+(SELECT count(*) FROM receiver_activity_tone)");
            assertTrue(before - after <= 1000); assertTrue(reported <= 1000); assertTrue(after > 0, "A pass cannot cascade all tone children");
            integrity(connection);
            ReceiverActivitySchema.resetStats(connection);
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_tone_sequence"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_tone")); integrity(connection);
        }
    }

    @Test void payloadLookupsSeekNaturalKeysAndDoNotSortHistory() throws Exception
    {
        try(Connection connection = open(":memory:"))
        {
            for(int index = 0; index < 40; index++) ToneActivitySchema.record(connection,
                new ReceiverActivityMapper().mapCompletedToneSequence(sequence(200 + index, false, 2)), true);
            for(String query: List.of("SELECT event_id,source_code FROM receiver_activity_tone_sequence WHERE event_id IN (1,20,40)",
                "SELECT event_id,ordinal FROM receiver_activity_tone WHERE event_id IN (1,20,40) ORDER BY event_id,ordinal"))
            {
                List<String> plan = new ArrayList<>();
                try(var statement = connection.createStatement(); var rows = statement.executeQuery("EXPLAIN QUERY PLAN " + query))
                { while(rows.next()) plan.add(rows.getString("detail")); }
                assertTrue(plan.stream().anyMatch(text -> text.contains("SEARCH") && text.contains("PRIMARY KEY")), plan.toString());
                assertFalse(plan.stream().anyMatch(text -> text.contains("TEMP B-TREE")), plan.toString());
            }
        }
    }

    @Test void partiallyPrunedExpiredSequenceDoesNotBreakTheBoundedPageOrPublishPartialEvidence() throws Exception
    {
        try(Connection connection = open(":memory:"))
        {
            ToneActivitySchema.record(connection, new ReceiverActivityMapper().mapCompletedToneSequence(sequence(500, false, 2)), true);
            try(var sql = connection.createStatement()) { sql.executeUpdate("DELETE FROM receiver_activity_tone WHERE ordinal=0"); }
            var rows = page(connection); ToneActivitySchema.enrichPage(connection, rows);
            assertEquals(1, rows.size()); assertFalse(rows.getFirst().containsKey("paging_tone_summary"));
            integrity(connection);
        }
    }

    private static CompletedToneSequence sequence(long index, boolean encoded, int count)
    {
        long now = System.currentTimeMillis(); AudioCallId id = new AudioCallId(7, index, 0);
        var source = new CallLegSource(encoded ? DecoderType.DMR : DecoderType.P25_CONVENTIONAL,
            encoded ? DMR_CONFIG : CONFIG, "Fixture", null, 0, null,
            io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD, ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL, false);
        var identifiers = new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(123), APCO25Talkgroup.create(456), FrequencyConfigurationIdentifier.create(851_000_000L)));
        var snapshot = new AudioCallSnapshot(id, null, null, identifiers, Set.of(), now - 10_000, now, 1, 1,
            now - 10_000, now, false, true, CallEncryptionState.CLEAR, false, null, VoiceCallQuality.EMPTY,
            CallLegId.from(id), source, null);
        var tones = new ArrayList<CompletedToneSequence.Element>();
        for(int ordinal = 0; ordinal < count; ordinal++) tones.add(encoded ?
            new CompletedToneSequence.Element(List.of(), "BUSY_TONE", null, 400, null) :
            new CompletedToneSequence.Element(List.of(1000_123 + ordinal), null, 100L + ordinal * 200, 200, .91234567));
        return new CompletedToneSequence(snapshot, now - 10_000, encoded ? CompletedToneSequence.Source.ENCODED : CompletedToneSequence.Source.DECODED_AUDIO,
            encoded ? CompletedToneSequence.Method.AMBE_TONE_V1 : CompletedToneSequence.Method.P25_PCM_TONE_V1, tones);
    }

    private static Connection open(String database) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(var statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        SdrTrunkDatabaseSchema.create(connection); SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        ReceiverActivitySchema.create(connection); DmrActivitySchema.create(connection); TrunkedSiteSchema.create(connection);
        try(var statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,alias_list_id,auto_start,decoder_type,primary_frequency_hz,config_json) VALUES('" + CONFIG + "','CONVENTIONAL',0,'Fixture',(SELECT id FROM alias_list WHERE family='P25' LIMIT 1),0,'P25_CONVENTIONAL',851000000,'{}')");
            statement.executeUpdate("INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,alias_list_id,auto_start,decoder_type,primary_frequency_hz,config_json) VALUES('" + DMR_CONFIG + "','CONVENTIONAL',1,'DMR fixture',(SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),0,'DMR',851000000,'{\"decodeConfiguration\":{\"channelMode\":\"CONVENTIONAL\"}}')");
            statement.executeUpdate("INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,alias_list_id,auto_start,decoder_type,primary_frequency_hz,config_json) VALUES('" + TRUNKED_CONFIG + "','TRUNKED',2,'Trunked fixture',(SELECT id FROM alias_list WHERE family='P25' LIMIT 1),0,'P25_PHASE1',851000000,'{}')");
        }
        return connection;
    }

    private static List<Map<String,Object>> page(Connection connection) throws Exception
    {
        List<Map<String,Object>> result = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,event_type,frequency_hz FROM receiver_activity_event_resolved ORDER BY observed_at_ms DESC,id DESC LIMIT 200"))
        { while(rows.next()) { var row = new LinkedHashMap<String,Object>(); row.put("id", rows.getLong(1)); row.put("event_type", rows.getString(2)); row.put("frequency_hz", rows.getLong(3)); result.add(row); } }
        return result;
    }
    private static long scalar(Connection connection, String sql) throws Exception
    { try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getLong(1); } }
    private static void integrity(Connection connection) throws Exception
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check")) { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
    }
}
