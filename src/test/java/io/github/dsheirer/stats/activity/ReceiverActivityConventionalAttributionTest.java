/*
 * Copyright (C) 2026 Dennis Sheirer
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, version 3 or later.
 */
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25ConventionalCallUpdateEvent;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Same-call attribution is credited by the committing writer, independently of audio output. */
class ReceiverActivityConventionalAttributionTest
{
    private static final String CONFIGURATION = "00000000-0000-0000-0000-000000000731";
    private static final long FREQUENCY = 154_875_000L;
    private static final int SOURCE = 700_001;
    private static final int TARGET = 1_201;
    private final ReceiverActivityMapper mMapper = new ReceiverActivityMapper();
    @TempDir Path mTemporaryFolder;

    @Test
    void lateSourceCreditsOriginalHourWithoutRecountingChannelTargetAliasOrOutput() throws Exception
    {
        for(boolean details: List.of(true, false))
        {
            Path database = database("late-" + details);
            long hour = System.currentTimeMillis() / 3_600_000L * 3_600_000L - 3_600_000L;
            long start = hour + 3_599_000L;
            ReceiverActivityWriter writer = writer(database, details, 16);
            writer.start();
            writer.enqueue(observation("late", start, null, TARGET, true, false));
            writer.enqueue(observation("late", start, SOURCE, TARGET, false, false));
            writer.enqueue(observation("late", start, SOURCE, TARGET, false, true));
            writer.enqueue(observation("late", start, SOURCE, TARGET, true, false));
            writer.enqueue(output(start));
            writer.close();

            try(Connection connection = connection(database))
            {
                assertEquals(1, scalar(connection, "SELECT sum(call_count) FROM conventional_activity_summary"));
                assertEquals(1, scalar(connection, "SELECT sum(streamed_count) FROM conventional_activity_summary"));
                assertEquals(1, identityMetric(connection, 2, SOURCE, "call_count"));
                assertEquals(1, identityMetric(connection, 1, TARGET, "call_count"));
                assertEquals(1, identityMetric(connection, 2, SOURCE, "streamed_count"));
                assertEquals(hour, scalar(connection,
                    "SELECT bucket_start_ms FROM conventional_call_identity_bucket WHERE identity_role_code=2"));
                assertEquals(1, aliasMetric(connection, "Source", "logical_call_count"));
                assertEquals(1, aliasMetric(connection, "Target", "logical_call_count"));
                assertEquals(1, aliasMetric(connection, "Source", "stream_submitted_logical_call_count"));
                assertEquals(details ? 1 : 0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
                if(details)
                {
                    assertEquals(SOURCE, scalar(connection,
                        "SELECT source_observed_local_id FROM receiver_activity_event"));
                    assertEquals(start, scalar(connection, "SELECT observed_at_ms FROM receiver_activity_event"));
                }
                assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
            }
        }
    }

    @Test
    void aKnownSourceAndAConflictingLaterSourceNeverReceiveAdditionalCallCredit() throws Exception
    {
        Path database = database("known");
        long start = System.currentTimeMillis();
        ReceiverActivityWriter writer = writer(database, true, 16);
        writer.start();
        writer.enqueue(observation("known", start, SOURCE, TARGET, true, false));
        writer.enqueue(observation("known", start, SOURCE, TARGET, false, false));
        writer.enqueue(observation("known", start, SOURCE + 1, TARGET, false, true));
        writer.close();
        try(Connection connection = connection(database))
        {
            assertEquals(1, identityMetric(connection, 2, SOURCE, "call_count"));
            assertEquals(0, identityMetric(connection, 2, SOURCE + 1, "call_count"));
            assertEquals(1, aliasMetric(connection, "Source", "logical_call_count"));
            assertEquals(SOURCE, scalar(connection,
                "SELECT source_observed_local_id FROM receiver_activity_event"));
        }
    }

    @Test
    void droppedStartCanBeRecoveredByFullCompletionWithinTheCurrentCollection() throws Exception
    {
        Path database = database("recovered");
        ReceiverActivityWriter writer = writer(database, true, 16);
        long start = System.currentTimeMillis() + 1;
        writer.start();
        writer.enqueue(observation("recovered", start, SOURCE, TARGET, false, true));
        writer.enqueue(observation("recovered", start, SOURCE, TARGET, false, true));
        writer.enqueue(observation("recovered", start, SOURCE, TARGET, true, false));
        writer.close();
        try(Connection connection = connection(database))
        {
            assertEquals(1, scalar(connection, "SELECT sum(call_count) FROM conventional_activity_summary"));
            assertEquals(1, identityMetric(connection, 2, SOURCE, "call_count"));
            assertEquals(1, identityMetric(connection, 1, TARGET, "call_count"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
        }
    }

    @Test
    void completionFromBeforeThisCollectionCannotInventACall() throws Exception
    {
        Path database = database("prior-collection");
        ReceiverActivityWriter writer = writer(database, true, 16);
        writer.start();
        writer.enqueue(observation("old", System.currentTimeMillis() - 30_000, SOURCE, TARGET, false, true));
        writer.close();
        try(Connection connection = connection(database))
        {
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM conventional_activity_summary"));
        }
    }

    @Test
    void transactionRollbackAndConstraintIsolationDoNotRememberUncommittedCredits() throws Exception
    {
        Path database = database("rollback");
        long start = System.currentTimeMillis();
        ReceiverActivityWriter writer = writer(database, true, 3);
        writer.start();
        writer.enqueue(observation("rollback", start, null, TARGET, true, false));
        writer.enqueue(new ReceiverActivityRecords.ActivityEvent(start + 1, CONFIGURATION,
            ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_P25, "APCO25", ReceiverActivityRecords.Action.CALL,
            "CALL_GROUP", null, Integer.toString(TARGET), "TALKGROUP", List.of(), FREQUENCY, null, 1,
            true, -1, 1, null, null, null, null, null, null, false, null, null,
            TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.ORDINARY,
            ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(), null));
        writer.enqueue(observation("rollback", start, SOURCE, TARGET, false, true));
        writer.close();
        assertEquals(1, writer.getDroppedRecords());
        try(Connection connection = connection(database))
        {
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT sum(call_count) FROM conventional_activity_summary"));
            assertEquals(1, identityMetric(connection, 2, SOURCE, "call_count"));
            assertEquals(1, identityMetric(connection, 1, TARGET, "call_count"));
            assertEquals(1, aliasMetric(connection, "Source", "logical_call_count"));
        }
    }

    @Test
    void statsResetRetiresActiveCallCreditsAndTheirLateSnapshots() throws Exception
    {
        Path database = database("reset");
        long start = System.currentTimeMillis() - 1_000;
        ReceiverActivityWriter writer = writer(database, true, 1);
        writer.start();
        writer.enqueue(observation("before-reset", start, null, TARGET, true, false));
        StatsDatabaseMaintenanceRequest reset = StatsDatabaseMaintenanceRequest.forOperation(
            ReceiverActivityMaintenance.Operation.RESET_STATS);
        writer.submitMaintenance(reset);
        reset.result().get(5, TimeUnit.SECONDS);
        writer.enqueue(observation("before-reset", start, SOURCE, TARGET, false, true));
        writer.enqueue(observation("after-reset", System.currentTimeMillis() + 1, SOURCE, TARGET, true, false));
        writer.close();
        try(Connection connection = connection(database))
        {
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, identityMetric(connection, 2, SOURCE, "call_count"));
            assertEquals(1, scalar(connection, "SELECT sum(call_count) FROM conventional_activity_summary"));
        }
    }

    @Test
    void missingTargetCanBeCreditedLaterWithoutRecountingKnownSource() throws Exception
    {
        Path database = database("late-target");
        long start = System.currentTimeMillis();
        ReceiverActivityWriter writer = writer(database, true, 16);
        writer.start();
        writer.enqueue(observation("target", start, SOURCE, null, true, false));
        writer.enqueue(observation("target", start, SOURCE, TARGET, false, true));
        writer.enqueue(observation("target", start, SOURCE, TARGET, false, true));
        writer.close();
        try(Connection connection = connection(database))
        {
            assertEquals(1, identityMetric(connection, 2, SOURCE, "call_count"));
            assertEquals(1, identityMetric(connection, 1, TARGET, "call_count"));
            assertEquals(1, aliasMetric(connection, "Source", "logical_call_count"));
            assertEquals(1, aliasMetric(connection, "Target", "logical_call_count"));
            assertEquals(TARGET, scalar(connection,
                "SELECT target_observed_local_id FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, """
                SELECT sum(call_count) FROM conventional_call_identity_bucket WHERE identity_role_code=1
                """));
        }
    }

    @Test
    void conflictingTargetCannotSuppressTheLateSourceAliasCredit() throws Exception
    {
        Path database = database("conflicting-target");
        long start = System.currentTimeMillis();
        ReceiverActivityWriter writer = writer(database, true, 16);
        writer.start();
        writer.enqueue(radioObservation("conflict", start, null, SOURCE + 1, true));
        writer.enqueue(radioObservation("conflict", start, SOURCE, SOURCE, false));
        writer.close();
        try(Connection connection = connection(database))
        {
            assertEquals(1, aliasMetric(connection, "Source", "logical_call_count"));
            assertEquals(1, aliasMetric(connection, "Other", "logical_call_count"));
            assertEquals(1, identityMetric(connection, 1, SOURCE + 1, "call_count"));
            assertEquals(0, identityMetric(connection, 1, SOURCE, "call_count"));
            assertEquals(SOURCE + 1, scalar(connection,
                "SELECT target_observed_local_id FROM receiver_activity_event"));
        }
    }

    @Test
    void conflictingSourceCannotSuppressTheLateTargetAliasCredit() throws Exception
    {
        Path database = database("conflicting-source");
        long start = System.currentTimeMillis();
        ReceiverActivityWriter writer = writer(database, true, 16);
        writer.start();
        writer.enqueue(radioObservation("conflict", start, SOURCE, null, true));
        writer.enqueue(radioObservation("conflict", start, SOURCE + 1, SOURCE + 1, false));
        writer.close();
        try(Connection connection = connection(database))
        {
            assertEquals(1, aliasMetric(connection, "Source", "logical_call_count"));
            assertEquals(1, aliasMetric(connection, "Other", "logical_call_count"));
            assertEquals(1, identityMetric(connection, 2, SOURCE, "call_count"));
            assertEquals(0, identityMetric(connection, 2, SOURCE + 1, "call_count"));
            assertEquals(SOURCE, scalar(connection,
                "SELECT source_observed_local_id FROM receiver_activity_event"));
        }
    }

    @Test
    void reservedPositiveDestinationsKeepUnknownCreditAndPermitLaterValidEvidence() throws Exception
    {
        for(boolean invalidInitially: List.of(true, false))
        {
            for(String kind: List.of("TALKGROUP", "RADIO"))
            {
                int reserved = "RADIO".equals(kind) ? 0xFFFFFF : 0xFFFF;
                int valid = "RADIO".equals(kind) ? SOURCE + 1 : TARGET;
                Path database = database("reserved-" + kind + "-" + invalidInitially);
                long start = System.currentTimeMillis();
                ReceiverActivityRecords.ActivityEvent empty = observation("reserved", start, SOURCE, null,
                    true, false).activity();
                ReceiverActivityRecords.ActivityEvent invalid = empty.withConventionalIdentities(SOURCE,
                    Integer.toString(reserved), kind, List.of(), ReceiverActivityRecords.P25Identity.ORDINARY,
                    ReceiverActivityRecords.P25Identity.ORDINARY, null, null);
                ReceiverActivityRecords.ActivityEvent learned = empty.withConventionalIdentities(SOURCE,
                    Integer.toString(valid), kind, List.of(), ReceiverActivityRecords.P25Identity.ORDINARY,
                    ReceiverActivityRecords.P25Identity.ORDINARY, null, null);
                ReceiverActivityRecords.ActivityEvent initial = invalidInitially ? invalid : empty;
                try(Connection connection = connection(database))
                {
                    var credit = ReceiverActivitySchema.recordConventionalCall(connection, initial, true);
                    credit = ReceiverActivitySchema.enrichConventionalCall(connection, initial, invalid, credit);
                    assertEquals(1, identityMetric(connection, 1, 0, "call_count"));
                    assertEquals(0, identityMetric(connection, 1, reserved, "call_count"));
                    credit = ReceiverActivitySchema.enrichConventionalCall(connection, initial, learned, credit);
                    ReceiverActivitySchema.enrichConventionalCall(connection, initial, learned, credit);
                    assertEquals(0, identityMetric(connection, 1, 0, "call_count"));
                    assertEquals(1, identityMetric(connection, 1, valid, "call_count"));
                    assertEquals(1, scalar(connection, """
                        SELECT sum(call_count) FROM conventional_call_identity_bucket WHERE identity_role_code=1
                        """));
                    assertEquals(valid, scalar(connection,
                        "SELECT target_observed_local_id FROM receiver_activity_event"));
                }
            }
        }
    }

    private ReceiverActivityRecords.ConventionalCallObservation radioObservation(String token, long start,
        Integer source, Integer target, boolean initial)
    {
        List<Identifier> identifiers = new ArrayList<>();
        if(source != null) identifiers.add(APCO25RadioIdentifier.createFrom(source));
        if(target != null) identifiers.add(APCO25RadioIdentifier.createTo(target));
        P25CallStartEvent call = new P25CallStartEvent(CONFIGURATION, DecoderType.P25_CONVENTIONAL,
            DecodeEventType.CALL_UNIT_TO_UNIT, start, identifiers, FREQUENCY, null, null, 1,
            false, false, null, token);
        return assertInstanceOf(ReceiverActivityRecords.ConventionalCallObservation.class,
            initial ? mMapper.mapCallStart(call) : mMapper.map(new P25ConventionalCallUpdateEvent(call, true)));
    }

    private ReceiverActivityRecords.ConventionalCallObservation observation(String token, long start,
        Integer source, Integer target, boolean initial, boolean complete)
    {
        List<Identifier> identifiers = new ArrayList<>();
        if(source != null) identifiers.add(APCO25RadioIdentifier.createFrom(source));
        if(target != null) identifiers.add(APCO25Talkgroup.create(target));
        P25CallStartEvent call = new P25CallStartEvent(CONFIGURATION, DecoderType.P25_CONVENTIONAL,
            DecodeEventType.CALL_GROUP, start, identifiers, FREQUENCY, null, null, 1, false, false, null, token);
        ReceiverActivityRecord record = initial ? mMapper.mapCallStart(call) :
            mMapper.map(new P25ConventionalCallUpdateEvent(call, complete));
        return assertInstanceOf(ReceiverActivityRecords.ConventionalCallObservation.class, record);
    }

    private ReceiverActivityRecords.ConventionalCallOutput output(long start)
    {
        return new ReceiverActivityRecords.ConventionalCallOutput(start, CONFIGURATION,
            ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_P25, "APCO25", FREQUENCY, 1, TARGET,
            "TALKGROUP", List.of(), SOURCE, ReceiverActivityRecords.CallOutput.STREAMED,
            TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.ORDINARY,
            ReceiverActivityRecords.P25Identity.ORDINARY, List.of());
    }

    private ReceiverActivityWriter writer(Path database, boolean details, int batch)
    {
        return new ReceiverActivityWriter(database, 30, details, 32, batch, 10_000);
    }

    private Path database(String name) throws Exception
    {
        Path path = mTemporaryFolder.resolve(name + ".sqlite");
        try(Connection connection = connection(path); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,
                    name,alias_list_id,decoder_type,primary_frequency_hz,config_json)
                SELECT '%s','CONVENTIONAL',0,'System','Site','Channel',id,'P25_CONVENTIONAL',%d,'{}'
                FROM alias_list WHERE family='P25' LIMIT 1
                """.formatted(CONFIGURATION, FREQUENCY));
            for(String[] alias: List.of(new String[]{"Target", "TALKGROUP", Integer.toString(TARGET)},
                new String[]{"Source", "RADIO_ID", Integer.toString(SOURCE)},
                new String[]{"Other", "RADIO_ID", Integer.toString(SOURCE + 1)}))
            {
                statement.executeUpdate("""
                    INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value)
                    SELECT id,'%s','%s','APCO25',%s FROM alias_list WHERE family='P25' LIMIT 1
                    """.formatted(alias[0], alias[1], alias[2]));
            }
        }
        return path;
    }

    private static Connection connection(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static long identityMetric(Connection connection, int role, int identity, String metric) throws Exception
    {
        return scalar(connection, "SELECT coalesce(sum(" + metric + "),0) FROM conventional_call_identity_bucket " +
            "WHERE identity_role_code=" + role + " AND identity_id=" + identity);
    }

    private static long aliasMetric(Connection connection, String name, String metric) throws Exception
    {
        return scalar(connection, "SELECT coalesce(sum(" + metric + "),0) FROM alias_activity_summary " +
            "WHERE alias_id=(SELECT id FROM alias WHERE name='" + name + "')");
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
