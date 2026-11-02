/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.audio.call.SourceCallReference;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that call enrichment replaces redundant voice activity without losing independently useful evidence. */
class VoiceCallConsolidationTest
{
    private static final String CONFIGURATION = "123e4567-e89b-42d3-a456-426614174000";
    private static final String SYSTEM_KEY = "p25:bee00:3a9";
    private static final String CALL_TOKEN = "consolidated-voice:1";
    private static final long FREQUENCY = 460_012_500L;
    @TempDir Path directory;

    @Test
    void lateFactsAndTonesEnrichTheOriginalCallWithoutAdditionalActivityCredits() throws Exception
    {
        verifyCallEnrichment(true);
    }

    @Test
    void identityAndCallStatisticsRemainCompleteWhenDetailedHistoryIsDisabled() throws Exception
    {
        verifyCallEnrichment(false);
    }

    private void verifyCallEnrichment(boolean detailed) throws Exception
    {
        Path database = database("enrichment-" + detailed + ".sqlite");
        long start = System.currentTimeMillis();
        var initial = activity(start, ReceiverActivityRecords.Action.CALL, "CALL_GROUP", null,
            CallEncryptionState.UNKNOWN, null, null, null, CALL_TOKEN);
        var learned = activity(start, ReceiverActivityRecords.Action.CALL, "CALL_GROUP", 202,
            CallEncryptionState.ENCRYPTED, 0x84, 42, "Unit 202", CALL_TOKEN);
        var completed = learned.withDurationMilliseconds(750L);
        var tone = new ReceiverActivityMapper().mapCompletedToneSequence(tone(start));
        assertNotNull(tone);

        var writer = new ReceiverActivityWriter(database, 30, detailed, 64, 1, 0);
        long originalId;
        writer.start();
        try
        {
            assertTrue(writer.enqueue(new ReceiverActivityRecords.P25CallObservation(CALL_TOKEN, initial, true, false)));
            awaitWritten(writer, 1);
            try(Connection connection = ActivityTestDatabase.openPair(database))
            {
                originalId = scalar(connection, "SELECT coalesce(max(id),0) FROM receiver_activity_event");
            }

            assertTrue(writer.enqueue(tone));
            assertTrue(writer.enqueue(new ReceiverActivityRecords.P25CallObservation(CALL_TOKEN, learned, false, false)
                .withFactsObservedAt(start + 400)));
            assertTrue(writer.enqueue(talkerAlias(start, start + 600,
                ReceiverActivityRecords.P25Identity.ORDINARY, "Fresh Unit 202")));
            assertTrue(writer.enqueue(new ReceiverActivityRecords.P25CallObservation(CALL_TOKEN, completed, false, true)
                .withFactsObservedAt(start + 750)));
            assertTrue(writer.enqueue(new ReceiverActivityRecords.P25CallObservation(CALL_TOKEN, completed, false, true)
                .withFactsObservedAt(start + 750)));
            assertTrue(writer.enqueue(tone));
            var resolved = resolved(start);
            assertTrue(writer.enqueue(resolved));
            assertTrue(writer.enqueue(resolved));
        }
        finally
        {
            writer.close();
        }
        assertEquals(0, writer.getDroppedRecords());
        assertEquals(ReceiverActivityStatus.State.STOPPED, writer.getStatus().state());

        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(detailed ? 1 : 0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(detailed ? 1 : 0, scalar(connection, "SELECT count(*) FROM receiver_activity_tone_sequence"));
            assertEquals(detailed ? 1 : 0, scalar(connection, "SELECT count(*) FROM receiver_activity_tone"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM trunked_signaling_activity_bucket"));
            assertEquals(0, scalar(connection, "SELECT coalesce(sum(active_count),0) FROM radio_system_identity_summary"));
            assertEquals(0, scalar(connection, "SELECT coalesce(sum(active_count),0) FROM trunked_radio_group_summary"));
            assertEquals(1, scalar(connection, "SELECT sum(logical_call_count) FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection, "SELECT sum(encrypted_logical_call_count) FROM trunked_logical_call_bucket"));
            assertTrue(scalar(connection, "SELECT last_seen_ms FROM radio_system_identity_summary " +
                "WHERE identity_kind_code=2 AND identity_id=202") >= start + 750,
                "Late source observations must refresh directory metadata independently of the call start");
            assertEquals("Fresh Unit 202", text(connection, "SELECT last_talker_alias FROM radio_system_identity_summary " +
                "WHERE identity_kind_code=2 AND identity_id=202"));
            assertEquals(start + 600, scalar(connection,
                "SELECT last_talker_alias_seen_ms FROM radio_system_identity_summary " +
                    "WHERE identity_kind_code=2 AND identity_id=202"),
                "A later completion must not retimestamp or overwrite an inherited talker alias");
            assertTrue(scalar(connection, "SELECT last_seen_ms FROM receiver_channel") >= start + 750);
            if(detailed)
            {
                try(var query = connection.prepareStatement("""
                    SELECT id,observed_at_ms,action_code,source_observed_local_id,target_observed_local_id,
                        source_observed_working_id,encryption_state,encryption_algorithm_id,encryption_key_id,
                        source_identity_summary_id,target_identity_summary_id,duration_ms
                    FROM receiver_activity_event WHERE source_call_uuid=?
                    """))
                {
                    query.setString(1, SourceCallReference.fromToken(CALL_TOKEN));
                    try(var rows = query.executeQuery())
                    {
                        assertTrue(rows.next());
                        assertEquals(originalId, rows.getLong("id"));
                        assertEquals(start, rows.getLong("observed_at_ms"));
                        assertEquals(ReceiverActivityRecords.Action.CALL.code(), rows.getInt("action_code"));
                        assertEquals(202, rows.getInt("source_observed_local_id"));
                        assertEquals(101, rows.getInt("target_observed_local_id"));
                        assertEquals(202, rows.getInt("source_observed_working_id"));
                        assertEquals(CallEncryptionState.ENCRYPTED.code(), rows.getInt("encryption_state"));
                        assertEquals(0x84, rows.getInt("encryption_algorithm_id"));
                        assertEquals(42, rows.getInt("encryption_key_id"));
                        assertTrue(rows.getLong("source_identity_summary_id") > 0);
                        assertTrue(rows.getLong("target_identity_summary_id") > 0);
                        assertEquals(750L, rows.getLong("duration_ms"));
                        assertFalse(rows.next());
                    }
                }
                assertEquals(originalId, scalar(connection, "SELECT event_id FROM receiver_activity_tone_sequence"));
            }
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void voiceTrackerCopiesDisappearWhileDataGrantsAndIndependentSignalingRemain() throws Exception
    {
        Path database = database("distinct-activity.sqlite");
        long now = System.currentTimeMillis();
        var mapper = new ReceiverActivityMapper();
        Channel channel = new Channel("Fixture site");
        channel.setConfigurationId(CONFIGURATION);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        channel.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x3A9, 1, 1));
        var identifiers = identifiers();
        for(DecodeEventType type: DecodeEventType.VOICE_CALL_EVENTS)
        {
            var tracker = P25ChannelGrantEvent.builder(type, now, new VoiceServiceOptions(0))
                .identifiers(identifiers).build();
            assertNull(mapper.map(channel, tracker), type + " must enrich its call through the typed call path");
        }
        var dataTracker = P25ChannelGrantEvent.builder(DecodeEventType.DATA_CALL, now, null)
            .identifiers(identifiers).build();
        var data = mapper.map(channel, dataTracker);
        assertNotNull(data);
        assertEquals(ReceiverActivityRecords.Action.ACTIVE, data.action());

        var writer = new ReceiverActivityWriter(database, 30, true, 32, 1, 0);
        writer.start();
        try
        {
            assertTrue(writer.enqueue(data));
            for(var action: List.of(ReceiverActivityRecords.Action.GRANT,
                ReceiverActivityRecords.Action.EMERGENCY, ReceiverActivityRecords.Action.PAGE))
            {
                String type = action == ReceiverActivityRecords.Action.GRANT ? "CALL_GROUP" : action.name();
                assertTrue(writer.enqueue(activity(now + action.code(), action, type, 202,
                    CallEncryptionState.UNKNOWN, null, null, null, null)));
            }
        }
        finally
        {
            writer.close();
        }
        assertEquals(0, writer.getDroppedRecords());
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(4, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT sum(active_count) FROM trunked_signaling_activity_bucket"));
            assertEquals(1, scalar(connection, "SELECT sum(grant_count) FROM trunked_signaling_activity_bucket"));
            assertEquals(1, scalar(connection, "SELECT sum(emergency_count) FROM trunked_signaling_activity_bucket"));
            assertEquals(1, scalar(connection, "SELECT sum(page_count) FROM trunked_signaling_activity_bucket"));
            assertEquals(1, scalar(connection, "SELECT active_count FROM radio_system_identity_summary " +
                "WHERE identity_kind_code=2 AND identity_id=202"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE source_call_uuid IS NOT NULL"));
        }
    }

    @Test
    void knownLocalEndpointsGainQualifiedIdentitiesWorkingAddressesAndMissingChannelFacts() throws Exception
    {
        Path database = database("qualified-context.sqlite");
        long start = System.currentTimeMillis();
        var initial = withContext(activity(start, ReceiverActivityRecords.Action.CALL, "CALL_GROUP", 202,
            CallEncryptionState.UNKNOWN, null, null, null, CALL_TOKEN).withP25Identities(202, "101", "TALKGROUP",
                List.of(), ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY,
                null, null), null, null, null, null, null, null);
        var learned = qualifiedCall(start);
        var writer = new ReceiverActivityWriter(database, 30, true, 32, 1, 0);
        long originalId;
        writer.start();
        try
        {
            assertTrue(writer.enqueue(observation(initial, true, false, start)));
            awaitWritten(writer, 1);
            try(Connection connection = ActivityTestDatabase.openPair(database))
            {
                originalId = scalar(connection, "SELECT id FROM receiver_activity_event");
                assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE " +
                    "frequency_hz IS NULL AND lcn_band IS NULL AND lcn_number IS NULL AND timeslot IS NULL " +
                    "AND observed_nac IS NULL AND observed_rfss IS NULL AND observed_site IS NULL " +
                    "AND source_observed_working_id IS NULL AND target_observed_working_id IS NULL"));
            }
            assertTrue(writer.enqueue(observation(learned, false, false, start + 500)));
        }
        finally
        {
            writer.close();
        }
        assertEquals(0, writer.getDroppedRecords());
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(originalId, scalar(connection, "SELECT id FROM receiver_activity_event"));
            assertEquals(start, scalar(connection, "SELECT observed_at_ms FROM receiver_activity_event"));
            assertEquals(SourceCallReference.fromToken(CALL_TOKEN), text(connection,
                "SELECT source_call_uuid FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, """
                SELECT count(*) FROM receiver_activity_event event
                JOIN radio_system_identity_summary source ON source.id=event.source_identity_summary_id
                JOIN radio_system_identity_summary target ON target.id=event.target_identity_summary_id
                WHERE event.source_observed_local_id=202 AND event.target_observed_local_id=101
                    AND event.source_observed_working_id=202 AND event.target_observed_working_id=101
                    AND source.home_wacn=781824 AND source.home_system_id=273 AND source.identity_id=777
                    AND target.home_wacn=781824 AND target.home_system_id=546 AND target.identity_id=888
                    AND event.frequency_hz=460012500 AND event.lcn_band=0 AND event.lcn_number=1
                    AND event.timeslot=1 AND event.observed_nac=837 AND event.observed_rfss=2 AND event.observed_site=3
                """));
            assertEquals(0, scalar(connection, "SELECT coalesce(sum(active_count),0) FROM radio_system_identity_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void staleAndConflictingSnapshotsCannotReplaceAcceptedFactsButDelayedCompletionCanFillDuration() throws Exception
    {
        Path database = database("snapshot-boundaries.sqlite");
        long start = System.currentTimeMillis();
        var current = qualifiedCall(start);
        var ordinary = withContext(activity(start, ReceiverActivityRecords.Action.CALL, "CALL_GROUP", 202,
            CallEncryptionState.CLEAR, null, null, "Outdated", CALL_TOKEN), FREQUENCY, "0-1", 1, 0x345, 2, 3);
        var conflictingSource = current.withP25Identities(202, "101", "TALKGROUP", List.of(),
            ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00, 0x111, 999),
            current.p25TargetIdentity(), 202, 101);
        var conflictingTarget = current.withP25Identities(202, "101", "TALKGROUP", List.of(),
            current.p25SourceIdentity(), ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(0xBEE00, 0x222, 999),
            202, 101);
        var writer = new ReceiverActivityWriter(database, 30, true, 32, 1, 0);
        writer.start();
        try
        {
            assertTrue(writer.enqueue(observation(current, true, false, start + 1_000)));
            awaitWritten(writer, 1);
            assertTrue(writer.enqueue(talkerAlias(start, start + 1_050,
                current.p25SourceIdentity(), "Fresh Qualified Unit")));
            assertTrue(writer.enqueue(observation(ordinary, false, false, start + 100)));
            assertTrue(writer.enqueue(observation(ordinary.withDurationMilliseconds(900L), false, true, start + 200)));
            long rejectedAt = start + 1_100;
            for(var conflicting: List.of(conflictingSource, conflictingTarget,
                withContext(current, FREQUENCY + 12_500, "0-2", 1, 0x345, 2, 3),
                withContext(current, FREQUENCY, "0-1", 2, 0x345, 2, 3)))
            {
                assertTrue(writer.enqueue(observation(conflicting.withDurationMilliseconds(100L), false, true,
                    rejectedAt++)));
            }
        }
        finally
        {
            writer.close();
        }
        assertEquals(0, writer.getDroppedRecords());
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(900, scalar(connection, "SELECT duration_ms FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, """
                SELECT count(*) FROM receiver_activity_event event
                JOIN radio_system_identity_summary source ON source.id=event.source_identity_summary_id
                JOIN radio_system_identity_summary target ON target.id=event.target_identity_summary_id
                WHERE source.home_system_id=273 AND source.identity_id=777
                    AND target.home_system_id=546 AND target.identity_id=888
                    AND event.source_observed_working_id=202 AND event.target_observed_working_id=101
                    AND event.encryption_state=2 AND event.encryption_algorithm_id=132 AND event.encryption_key_id=42
                    AND event.frequency_hz=460012500 AND event.timeslot=1
                """));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary"),
                "Rejected snapshots must not leave unrelated identity evidence behind");
            assertEquals(start + 1_000, scalar(connection, "SELECT last_seen_ms FROM receiver_channel"));
            assertEquals("Fresh Qualified Unit", text(connection, "SELECT last_talker_alias FROM radio_system_identity_summary " +
                "WHERE identity_kind_code=2"));
            assertEquals(start + 1_050, scalar(connection,
                "SELECT last_talker_alias_seen_ms FROM radio_system_identity_summary WHERE identity_kind_code=2"));
            assertEquals(0, scalar(connection, "SELECT coalesce(sum(active_count),0) FROM radio_system_identity_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void equivalentCanonicalEndpointsAcceptNewRawAddressesWithoutReplacingOriginalCallContext() throws Exception
    {
        Path database = database("canonical-address-change.sqlite");
        long start = System.currentTimeMillis();
        var initial = qualifiedCall(start);
        var updated = initial.withP25Identities(303, "404", "TALKGROUP", List.of(),
            initial.p25SourceIdentity(), initial.p25TargetIdentity(), 303, 404).withDurationMilliseconds(700L);
        var writer = new ReceiverActivityWriter(database, 30, true, 32, 1, 0);
        long originalId;
        writer.start();
        try
        {
            assertTrue(writer.enqueue(observation(initial, true, false, start)));
            awaitWritten(writer, 1);
            try(Connection connection = ActivityTestDatabase.openPair(database))
            {
                originalId = scalar(connection, "SELECT id FROM receiver_activity_event");
            }
            assertTrue(writer.enqueue(observation(updated, false, true, start + 700)));
        }
        finally
        {
            writer.close();
        }
        assertEquals(0, writer.getDroppedRecords());
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(originalId, scalar(connection, "SELECT id FROM receiver_activity_event"));
            assertEquals(start, scalar(connection, "SELECT observed_at_ms FROM receiver_activity_event"));
            assertEquals(SourceCallReference.fromToken(CALL_TOKEN), text(connection,
                "SELECT source_call_uuid FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, """
                SELECT count(*) FROM receiver_activity_event event
                JOIN radio_system_identity_summary source ON source.id=event.source_identity_summary_id
                JOIN radio_system_identity_summary target ON target.id=event.target_identity_summary_id
                WHERE event.source_observed_local_id=202 AND event.target_observed_local_id=101
                    AND event.source_observed_working_id=202 AND event.target_observed_working_id=101
                    AND source.home_system_id=273 AND source.identity_id=777
                    AND target.home_system_id=546 AND target.identity_id=888 AND event.duration_ms=700
                """));
            assertEquals(start + 700, scalar(connection, "SELECT last_seen_ms FROM receiver_channel"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary"));
            assertEquals(0, scalar(connection, "SELECT coalesce(sum(active_count),0) FROM radio_system_identity_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void abbreviatedPatchSnapshotsRetainEarlierQualifiedMemberEvidence() throws Exception
    {
        Path database = database("patch-members.sqlite");
        long start = System.currentTimeMillis();
        var ordinary = activity(start, ReceiverActivityRecords.Action.CALL, "CALL_PATCH_GROUP", 202,
            CallEncryptionState.CLEAR, null, null, null, CALL_TOKEN).withP25Identities(202, "101", "PATCH_GROUP",
                List.of(111, 112), ReceiverActivityRecords.P25Identity.ORDINARY,
                ReceiverActivityRecords.P25Identity.ORDINARY, 202, null);
        var qualified = withMemberEvidence(ordinary, List.of(new ReceiverActivityRecords.P25PatchMemberIdentity(111,
            ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(0xBEE00, 0x111, 777)),
            new ReceiverActivityRecords.P25PatchMemberIdentity(0,
                ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(0xBEE00, 0x111, 888))));
        var anotherQualifiedMember = withMemberEvidence(ordinary, List.of(
            new ReceiverActivityRecords.P25PatchMemberIdentity(0,
                ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(0xBEE00, 0x111, 999))));
        var writer = new ReceiverActivityWriter(database, 30, true, 32, 1, 0);
        writer.start();
        try
        {
            assertTrue(writer.enqueue(observation(qualified, true, false, start)));
            awaitWritten(writer, 1);
            assertTrue(writer.enqueue(observation(anotherQualifiedMember, false, false, start + 200)));
            assertTrue(writer.enqueue(observation(ordinary.withDurationMilliseconds(500L), false, true, start + 500)));
            assertTrue(writer.enqueue(observation(ordinary.withDurationMilliseconds(500L), false, true, start + 600)));
        }
        finally
        {
            writer.close();
        }
        assertEquals(0, writer.getDroppedRecords());
        try(Connection connection = ActivityTestDatabase.openPair(database))
        {
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, """
                SELECT count(*) FROM activity_event_identity_member member
                JOIN radio_system_identity_summary identity ON identity.id=member.identity_summary_id
                WHERE member.observed_local_id=111 AND identity.home_wacn=781824
                    AND identity.home_system_id=273 AND identity.identity_id=777
                """));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM activity_event_identity_member WHERE observed_local_id=111"),
                "An abbreviated update must not add a second local-only identity for a known qualified member");
            assertEquals(2, scalar(connection, """
                SELECT count(*) FROM activity_event_identity_member member
                JOIN radio_system_identity_summary identity ON identity.id=member.identity_summary_id
                WHERE member.observed_local_id=0 AND identity.home_system_id=273
                    AND identity.identity_id IN (888,999)
                """), "Distinct canonical members sharing local alias zero must both survive abbreviated updates");
            assertEquals(4, scalar(connection, "SELECT count(*) FROM activity_event_identity_member"));
            assertEquals(500, scalar(connection, "SELECT duration_ms FROM receiver_activity_event"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    private Path database(String name) throws Exception
    {
        Path database = directory.resolve(name);
        io.github.dsheirer.database.SdrTrunkTestDatabase.create(database);
        try(Connection connection = ActivityTestDatabase.openPair(database); var insert = connection.prepareStatement("""
            INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                alias_list_id,decoder_type,address_domain_code,primary_frequency_hz,config_json)
            VALUES(?,'TRUNKED',0,'Fixture system','Fixture site','Fixture channel',
                (SELECT id FROM alias_list WHERE family='P25' LIMIT 1),'P25_PHASE1',0,?,'{}')
            """))
        {
            insert.setString(1, CONFIGURATION);
            insert.setLong(2, FREQUENCY);
            insert.executeUpdate();
        }
        return database;
    }

    private static ReceiverActivityRecords.ActivityEvent activity(long start, ReceiverActivityRecords.Action action,
        String type, Integer source, CallEncryptionState encryption, Integer algorithm, Integer key,
        String talkerAlias, String token)
    {
        return new ReceiverActivityRecords.ActivityEvent(start, CONFIGURATION,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25", action, type,
            source != null ? source.toString() : null, "101", "TALKGROUP", List.of(), FREQUENCY, "0-1", null,
            encryption, algorithm, key, 0xBEE00, 0x3A9, 0x293, 1, 1, talkerAlias,
            action == ReceiverActivityRecords.Action.CALL, null, null, TrunkedIdentityDomain.STANDARD,
            ReceiverActivityRecords.P25Identity.ORDINARY,
            source != null ? ReceiverActivityRecords.P25Identity.ORDINARY : ReceiverActivityRecords.P25Identity.UNKNOWN,
            List.of(), SYSTEM_KEY, source, null, null, null, token);
    }

    private static ReceiverActivityRecords.P25CallObservation observation(ReceiverActivityRecords.ActivityEvent facts,
        boolean initial, boolean complete, long observedAt)
    {
        return new ReceiverActivityRecords.P25CallObservation(facts.activityCallToken(), facts, initial, complete)
            .withFactsObservedAt(observedAt);
    }

    private static ReceiverActivityRecords.TalkerAliasUpdate talkerAlias(long start, long observedAt,
        ReceiverActivityRecords.P25Identity identity, String alias)
    {
        return new ReceiverActivityRecords.TalkerAliasUpdate(observedAt, start, CONFIGURATION, "APCO25",
            0xBEE00, 0x3A9, 202, identity, alias, TrunkedIdentityDomain.STANDARD, SYSTEM_KEY);
    }

    private static ReceiverActivityRecords.ActivityEvent qualifiedCall(long start)
    {
        return withContext(activity(start, ReceiverActivityRecords.Action.CALL, "CALL_GROUP", 202,
            CallEncryptionState.ENCRYPTED, 0x84, 42, "Unit 202", CALL_TOKEN).withP25Identities(202, "101", "TALKGROUP",
                List.of(), ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00, 0x111, 777),
                ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(0xBEE00, 0x222, 888), 202, 101),
            FREQUENCY, "0-1", 1, 0x345, 2, 3);
    }

    private static ReceiverActivityRecords.ActivityEvent withContext(ReceiverActivityRecords.ActivityEvent event,
        Long frequency, String lcn, Integer timeslot, Integer nac, Integer rfss, Integer site)
    {
        return new ReceiverActivityRecords.ActivityEvent(event.observedAtEpochMilliseconds(), event.configurationId(),
            event.receiverKind(), event.protocol(), event.action(), event.eventType(), event.sourceRadioId(), event.targetId(),
            event.targetKind(), event.patchMemberTalkgroupIds(), frequency, lcn, timeslot, event.encryptionState(),
            event.encryptionAlgorithmId(), event.encryptionKeyId(), event.wacn(), event.systemId(), nac, rfss, site,
            event.talkerAlias(), event.countedCall(), event.dedupeKey(), event.radioPresenceUpdate(), event.identityDomain(),
            event.p25TargetIdentity(), event.p25SourceIdentity(), event.p25PatchMemberIdentities(), event.radioSystemKey(),
            event.sourceObservedWorkingId(), event.targetObservedWorkingId(), event.p25WuidObservation(),
            event.analogCallAttribution(), event.activityCallToken(), event.durationMilliseconds());
    }

    private static ReceiverActivityRecords.ActivityEvent withMemberEvidence(ReceiverActivityRecords.ActivityEvent event,
        List<ReceiverActivityRecords.P25PatchMemberIdentity> members)
    {
        return new ReceiverActivityRecords.ActivityEvent(event.observedAtEpochMilliseconds(), event.configurationId(),
            event.receiverKind(), event.protocol(), event.action(), event.eventType(), event.sourceRadioId(), event.targetId(),
            event.targetKind(), event.patchMemberTalkgroupIds(), event.frequencyHertz(), event.lcn(), event.timeslot(),
            event.encryptionState(), event.encryptionAlgorithmId(), event.encryptionKeyId(), event.wacn(), event.systemId(),
            event.nac(), event.rfss(), event.site(), event.talkerAlias(), event.countedCall(), event.dedupeKey(),
            event.radioPresenceUpdate(), event.identityDomain(), event.p25TargetIdentity(), event.p25SourceIdentity(),
            members, event.radioSystemKey(), event.sourceObservedWorkingId(), event.targetObservedWorkingId(),
            event.p25WuidObservation(), event.analogCallAttribution(), event.activityCallToken(), event.durationMilliseconds());
    }

    private static ReceiverActivityRecords.ResolvedLogicalCall resolved(long start)
    {
        return new ReceiverActivityRecords.ResolvedLogicalCall(new LogicalCallId(91, 1), start, CONFIGURATION,
            "APCO25", TrunkedIdentityDomain.STANDARD, 0xBEE00, 0x3A9, 101, "TALKGROUP", List.of(), 202,
            true, 0x84, 42, ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY,
            List.of(), List.of(), SYSTEM_KEY, 202, null, List.of(SourceCallReference.fromToken(CALL_TOKEN)));
    }

    private static IdentifierCollection identifiers()
    {
        return new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(202), APCO25Talkgroup.create(101),
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x3A9),
            FrequencyConfigurationIdentifier.create(FREQUENCY)));
    }

    private static CompletedToneSequence tone(long start)
    {
        AudioCallId id = new AudioCallId(91, 1, 0);
        var source = new CallLegSource(DecoderType.P25_PHASE1, CONFIGURATION, "Fixture channel", null, 0,
            new P25SiteIdentity(0xBEE00, 0x3A9, 1, 1), TrunkedIdentityDomain.STANDARD,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED, true);
        var snapshot = new AudioCallSnapshot(id, null, null, identifiers(), Set.of(), start, start + 750,
            1, 1, start, start + 750, false, true, CallEncryptionState.ENCRYPTED, false, null,
            VoiceCallQuality.EMPTY, CallLegId.from(id), source, null, null, CALL_TOKEN);
        return new CompletedToneSequence(snapshot, start, CompletedToneSequence.Source.DECODED_AUDIO,
            CompletedToneSequence.Method.P25_PCM_AMBE_TONE_V2,
            List.of(new CompletedToneSequence.Element(List.of(1_000_000), null, 100L, 200L, .99)));
    }

    private static void awaitWritten(ReceiverActivityWriter writer, long count) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(writer.getWrittenRecords() < count && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(writer.getWrittenRecords() >= count, "The initial call must commit before later batches");
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws Exception
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
