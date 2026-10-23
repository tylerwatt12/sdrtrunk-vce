/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25CallUpdateEvent;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P25LateEncryptionHistoryTest
{
    private static final String CHANNEL = "ddc368ab-ad65-45e6-8ec9-37c493e50e01";
    private static final int ALGORITHM = 0x84;
    private static final int KEY = 12328;
    private static final int SOURCE = 202;
    private static final int TARGET = 101;
    private final ReceiverActivityMapper mMapper = new ReceiverActivityMapper();
    @TempDir Path directory;

    @Test
    void confirmationAfterStartMakesTheSameCallFindableWithoutRecounting() throws Exception
    {
        for(DecoderType decoder: List.of(DecoderType.P25_PHASE1, DecoderType.P25_PHASE2))
        {
            Path database = database(decoder.name(), decoder);
            long start = System.currentTimeMillis();
            var initial = call(start, decoder, "encrypted", false, true);
            var confirmed = call(start, decoder, "encrypted", true, true);
            var writer = new ReceiverActivityWriter(database, 30, true, 16, 16, 0);
            writer.start();
            writer.enqueue(mMapper.mapCallStart(initial));
            writer.enqueue(mMapper.mapCallStart(call(start, decoder, "clear", false, false)));
            writer.enqueue(mMapper.map(new P25CallUpdateEvent(confirmed, true)));
            writer.enqueue(mMapper.map(new P25CallUpdateEvent(confirmed, true)));
            var resolved = resolved(start, decoder);
            writer.enqueue(resolved);
            writer.enqueue(resolved);
            writer.close();
            assertEquals(0, writer.getDroppedRecords());
            try(Connection connection = connection(database))
            {
                assertEquals(2, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
                assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE encrypted=1"),
                    "the encrypted-only history filter must find late confirmation on the original call");
                assertEquals(ALGORITHM, scalar(connection,
                    "SELECT encryption_algorithm_id FROM receiver_activity_event WHERE encrypted=1"));
                assertEquals(KEY, scalar(connection,
                    "SELECT encryption_key_id FROM receiver_activity_event WHERE encrypted=1"));
                assertEquals(1, scalar(connection,
                    "SELECT sum(encrypted_logical_call_count) FROM trunked_logical_call_bucket"));
                assertEquals(1, scalar(connection,
                    "SELECT sum(logical_call_count) FROM trunked_logical_call_bucket"));
                assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
            }
        }
    }

    @Test
    void unconfirmedAndClearSnapshotsCannotMarkHistoryEncrypted() throws Exception
    {
        Path database = database("unconfirmed", DecoderType.P25_PHASE1);
        long start = System.currentTimeMillis();
        var writer = new ReceiverActivityWriter(database, 30, true, 16, 16, 0);
        writer.start();
        writer.enqueue(mMapper.mapCallStart(call(start, DecoderType.P25_PHASE1, "unknown", false, false)));
        writer.enqueue(mMapper.map(new P25CallUpdateEvent(
            call(start, DecoderType.P25_PHASE1, "unknown", false, true), false)));
        writer.enqueue(mMapper.map(new P25CallUpdateEvent(
            call(start, DecoderType.P25_PHASE1, "unknown", false, false), true)));
        writer.close();
        assertEquals(0, writer.getDroppedRecords());
        try(Connection connection = connection(database))
        {
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE encrypted=1 " +
                "OR encryption_algorithm_id IS NOT NULL OR encryption_key_id IS NOT NULL"));
        }
    }

    @Test
    void absentOrPrunedDetailIsNotRecreatedByConfirmation() throws Exception
    {
        for(boolean collectDetail: List.of(true, false))
        {
            Path database = database("absent-" + collectDetail, DecoderType.P25_PHASE1);
            long start = System.currentTimeMillis();
            var initial = mMapper.map(call(start, DecoderType.P25_PHASE1, "absent", false, false));
            var confirmed = mMapper.map(call(start, DecoderType.P25_PHASE1, "absent", true, true));
            try(Connection connection = connection(database); Statement sql = connection.createStatement())
            {
                var credit = ReceiverActivitySchema.recordP25Call(connection, initial, collectDetail);
                sql.executeUpdate("DELETE FROM receiver_activity_event");
                credit = ReceiverActivitySchema.enrichP25Call(connection, initial, confirmed, credit);
                ReceiverActivitySchema.enrichP25Call(connection, initial, confirmed, credit);
                assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
                assertEquals(0, scalar(connection, "SELECT count(*) FROM trunked_logical_call_bucket"));
            }
        }
    }

    @Test
    void rollbackDoesNotConsumeTheConfirmationCredit() throws Exception
    {
        Path database = database("rollback", DecoderType.P25_PHASE1);
        long start = System.currentTimeMillis();
        var initial = mMapper.map(call(start, DecoderType.P25_PHASE1, "retry", false, false));
        var confirmed = mMapper.map(call(start, DecoderType.P25_PHASE1, "retry", true, true));
        try(Connection connection = connection(database))
        {
            var credit = ReceiverActivitySchema.recordP25Call(connection, initial, true);
            connection.setAutoCommit(false);
            ReceiverActivitySchema.enrichP25Call(connection, initial, confirmed, credit);
            connection.rollback();
            assertEquals(0, scalar(connection, "SELECT encrypted FROM receiver_activity_event"));
            ReceiverActivitySchema.enrichP25Call(connection, initial, confirmed, credit);
            connection.commit();
            assertEquals(1, scalar(connection, "SELECT encrypted FROM receiver_activity_event"));
        }
    }

    @Test
    void prunedPatchGroupDetailDoesNotRecreateMemberRows() throws Exception
    {
        Path database = database("pruned-patch", DecoderType.P25_PHASE1);
        long start = System.currentTimeMillis();
        var initial = mMapper.map(call(start, DecoderType.P25_PHASE1, "patch", false, false))
            .withP25Identities(SOURCE, Integer.toString(TARGET), "PATCH_GROUP", List.of(102, 103),
                ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY,
                null, null);
        var confirmed = mMapper.map(call(start, DecoderType.P25_PHASE1, "patch", true, true))
            .withP25Identities(SOURCE, Integer.toString(TARGET), "PATCH_GROUP", List.of(102, 103),
                ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY,
                null, null);
        try(Connection connection = connection(database); Statement sql = connection.createStatement())
        {
            var credit = ReceiverActivitySchema.recordP25Call(connection, initial, true);
            assertEquals(2, scalar(connection, "SELECT count(*) FROM activity_event_identity_member"));
            sql.executeUpdate("DELETE FROM receiver_activity_event");
            ReceiverActivitySchema.enrichP25Call(connection, initial, confirmed, credit);
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM activity_event_identity_member"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void aDifferentCallTimestampCannotEnrichTheReceipt() throws Exception
    {
        Path database = database("wrong-call", DecoderType.P25_PHASE1);
        long start = System.currentTimeMillis();
        var initial = mMapper.map(call(start, DecoderType.P25_PHASE1, "same-token", false, false));
        var other = mMapper.map(call(start + 1, DecoderType.P25_PHASE1, "same-token", true, true));
        try(Connection connection = connection(database))
        {
            var credit = ReceiverActivitySchema.recordP25Call(connection, initial, true);
            assertNull(ReceiverActivitySchema.enrichP25Call(connection, initial, other, credit));
            assertEquals(0, scalar(connection, "SELECT encrypted FROM receiver_activity_event"));
        }
    }

    private static P25CallStartEvent call(long start, DecoderType decoder, String token,
        boolean confirmed, boolean includeEncryption)
    {
        List<Identifier> identifiers = new ArrayList<>(List.of(APCO25Wacn.create(0xBEE00),
            APCO25System.create(0x3A9), APCO25RadioIdentifier.createFrom(SOURCE), APCO25Talkgroup.create(TARGET)));
        if(includeEncryption)
            identifiers.add(EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(ALGORITHM, KEY)));
        return new P25CallStartEvent(CHANNEL, decoder, DecodeEventType.CALL_GROUP, start, identifiers,
            460_012_500L, null, null, 1, decoder == DecoderType.P25_PHASE2, confirmed,
            "p25:bee00:3a9", token);
    }

    private static ReceiverActivityRecords.ResolvedLogicalCall resolved(long start, DecoderType decoder)
    {
        return new ReceiverActivityRecords.ResolvedLogicalCall(new LogicalCallId(91, 1), start, CHANNEL,
            decoder == DecoderType.P25_PHASE2 ? "APCO25_PHASE2" : "APCO25", TrunkedIdentityDomain.STANDARD,
            0xBEE00, 0x3A9, TARGET, "TALKGROUP", List.of(), SOURCE, true, ALGORITHM, KEY,
            ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY,
            List.of(), List.of(), null);
    }

    private Path database(String name, DecoderType decoder) throws Exception
    {
        Path database = directory.resolve(name + ".sqlite");
        try(Connection connection = connection(database); Statement sql = connection.createStatement())
        {
            sql.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            sql.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,
                    name,alias_list_id,decoder_type,primary_frequency_hz,config_json)
                SELECT '%s','TRUNKED',0,'System','Site','Channel',id,'%s',460012500,'{}'
                FROM alias_list WHERE family='P25' LIMIT 1
                """.formatted(CHANNEL, decoder.name()));
        }
        return database;
    }

    private static Connection connection(Path database) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(Statement sql = connection.createStatement())
        {
            sql.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static long scalar(Connection connection, String query) throws Exception
    {
        try(Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(query))
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
