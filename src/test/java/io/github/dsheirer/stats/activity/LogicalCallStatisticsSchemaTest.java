/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogicalCallStatisticsSchemaTest
{
    private static final long CALL_START = 1_700_000_000_000L;

    @TempDir
    Path mTemporaryFolder;

    @Test
    void countsOneSystemCallAndOneObservationPerDistinctLearnedSite() throws Exception
    {
        try(Connection connection = open("logical-call.sqlite"))
        {
            long aliasListId = insertAliasList(connection);
            P25ActivityLogRecords.ResolvedLogicalCall call = call(aliasListId,
                List.of(site(1, 1), site(2, 2), site(1, 1)));

            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, call));
            assertEquals(1, scalar(connection,
                "SELECT logical_call_count FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection,
                "SELECT encrypted_logical_call_count FROM trunked_logical_call_bucket"));
            assertEquals(2, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertEquals(2, scalar(connection,
                "SELECT SUM(observed_call_count) FROM p25_site_call_bucket"));
            assertEquals(1, scalar(connection, """
                SELECT logical_call_count FROM trunked_logical_call_identity_bucket
                WHERE identity_role_code=1 AND identity_kind_code=1 AND identity_id=1201
                """));
            assertEquals(2, scalar(connection, """
                SELECT SUM(observed_call_count) FROM p25_site_call_identity_bucket
                WHERE identity_role_code=1 AND identity_kind_code=1 AND identity_id=1201
                """));

            P25ActivityLogRecords.LogicalCallOutput output = new P25ActivityLogRecords.LogicalCallOutput(call,
                P25ActivityLogRecords.CallOutput.RECORDED);
            assertTrue(P25ActivityLogSchema.applyLogicalCallOutput(connection, output));
            assertEquals(1, scalar(connection,
                "SELECT recorded_output_count FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection, """
                SELECT recorded_output_count FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=1201
                """));
        }
    }

    @Test
    void missingAliasIdentityUsesContextScopeButFrozenIdentitySurvivesConfigurationDeletion() throws Exception
    {
        try(Connection connection = open("scope-guard.sqlite"))
        {
            long aliasListId = insertAliasList(connection);
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection,
                call(0, List.of(site(1, 1)))));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope
                WHERE protocol_code=1 AND scope_kind_code=2
                """));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
            try(PreparedStatement delete = connection.prepareStatement("DELETE FROM alias_list WHERE id=?"))
            {
                delete.setLong(1, aliasListId);
                assertEquals(1, delete.executeUpdate());
            }

            P25SiteIdentity otherSystem = new P25SiteIdentity(0x924, 0x650, 3, 3);
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection,
                call(aliasListId, List.of(site(1, 1), otherSystem))));
            assertEquals(2, scalar(connection, "SELECT SUM(logical_call_count) FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
        }
    }

    @Test
    void prunesHourlyFactsAndUsesTimeLeadingIndexes() throws Exception
    {
        try(Connection connection = open("retention-plan.sqlite"))
        {
            long aliasListId = insertAliasList(connection);
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection,
                call(aliasListId, List.of(site(1, 1), site(2, 2)))));

            assertTrue(queryPlan(connection, """
                SELECT scope_id, logical_call_count
                FROM trunked_logical_call_bucket
                WHERE bucket_start_ms BETWEEN ? AND ?
                ORDER BY bucket_start_ms
                """, CALL_START - 1, CALL_START + 3_600_000L)
                .contains("idx_trunked_logical_call_bucket_time"));
            assertTrue(queryPlan(connection, """
                SELECT learned_site_id, observed_call_count
                FROM p25_site_call_bucket
                WHERE bucket_start_ms BETWEEN ? AND ?
                ORDER BY bucket_start_ms
                """, CALL_START - 1, CALL_START + 3_600_000L)
                .contains("idx_p25_site_call_bucket_time"));

            assertTrue(P25ActivityLogSchema.deleteOlderThan(connection, CALL_START + 3_600_001L) > 0);
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_learned_site"));
            P25ActivityLogSchema.validate(connection);
        }
    }

    @Test
    void uncertainP25CopiesRemainSeparateContextCalls() throws Exception
    {
        try(Connection connection = open("uncertain-copies.sqlite"))
        {
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection,
                uncertainCall(1, "uncertain-a")));
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection,
                uncertainCall(2, "uncertain-b")));

            assertEquals(2, scalar(connection, "SELECT SUM(logical_call_count) FROM trunked_logical_call_bucket"));
            assertEquals(2, scalar(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope
                WHERE protocol_code=1 AND scope_kind_code=2
                """));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
        }
    }

    @Test
    void clearSiteRemovesPhysicalSiteFactsButKeepsSystemLogicalCall() throws Exception
    {
        try(Connection connection = open("clear-site.sqlite"))
        {
            long aliasListId = insertAliasList(connection);
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection,
                call(aliasListId, List.of(site(1, 1), site(2, 2)))));
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO receiver_context(
                    context_key, guid, kind_code, protocol_code, first_seen_ms, last_seen_ms,
                    system_key, rfss, site
                ) VALUES('GUID:clear-me', 'clear-me', 1, 1, ?, ?,
                    (SELECT system_key FROM p25_system WHERE wacn=0x924 AND system_id=0x649), 1, 1)
                """))
            {
                statement.setLong(1, CALL_START);
                statement.setLong(2, CALL_START);
                statement.executeUpdate();
            }

            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertTrue(P25ActivityLogSchema.clearSiteStats(connection, "clear-me") > 0);
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM p25_learned_site"));
            assertEquals(1, scalar(connection,
                "SELECT logical_call_count FROM trunked_logical_call_bucket"));
        }
    }

    @Test
    void cleanSchemaDoesNotExposeRemovedTrunkedCompatibilityObjects() throws Exception
    {
        try(Connection connection = open("clean-shape.sqlite"))
        {
            for(String removedTable: List.of("p25_site_activity_bucket", "p25_site_talkgroup_bucket",
                "call_identity_bucket"))
            {
                assertEquals(0, scalar(connection,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='" + removedTable + "'"));
            }

            for(String table: List.of("trunked_identity_summary", "trunked_radio_talkgroup_summary",
                "trunked_logical_call_bucket", "trunked_logical_call_identity_bucket",
                "p25_site_call_bucket", "p25_site_call_identity_bucket",
                "trunked_signaling_activity_bucket"))
            {
                for(String removedColumn: List.of("call_count", "encrypted_count", "recorded_count",
                    "streamed_count"))
                {
                    assertFalse(hasColumn(connection, table, removedColumn), table + "." + removedColumn);
                }
            }

            assertTrue(hasColumn(connection, "trunked_logical_call_bucket", "logical_call_count"));
            assertTrue(hasColumn(connection, "p25_site_call_bucket", "observed_call_count"));
            P25ActivityLogSchema.validate(connection);
        }
    }

    private Connection open(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static long insertAliasList(Connection connection) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_list(name, family) VALUES ('MARCS', 'P25') RETURNING id
            """))
        {
            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static P25ActivityLogRecords.ResolvedLogicalCall call(long aliasListId,
                                                                   List<P25SiteIdentity> sites)
    {
        return new P25ActivityLogRecords.ResolvedLogicalCall(new LogicalCallId(9, 1), CALL_START,
            "GUID:winner", "winner", Protocol.APCO25.name(), P25ActivityLogRecords.IdentityDomain.STANDARD,
            0x924, 0x649, aliasListId, 1201, Form.TALKGROUP.name(), List.of(), 700001, true,
            0x84, 1, P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of(), sites);
    }

    private static P25ActivityLogRecords.ResolvedLogicalCall uncertainCall(long sequence, String guid)
    {
        return new P25ActivityLogRecords.ResolvedLogicalCall(new LogicalCallId(19, sequence), CALL_START + sequence,
            "GUID:" + guid, guid, Protocol.APCO25.name(), P25ActivityLogRecords.IdentityDomain.STANDARD,
            null, null, 0, 1201, Form.TALKGROUP.name(), List.of(), 700001, false,
            null, null, P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of(), List.of());
    }

    private static P25SiteIdentity site(int rfss, int site)
    {
        return new P25SiteIdentity(0x924, 0x649, rfss, site);
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                if(column.equals(resultSet.getString("name")))
                {
                    return true;
                }
            }
            return false;
        }
    }

    private static String queryPlan(Connection connection, String sql, long start, long end) throws Exception
    {
        StringBuilder plan = new StringBuilder();
        try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
        {
            statement.setLong(1, start);
            statement.setLong(2, end);
            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    plan.append(resultSet.getString("detail")).append('\n');
                }
            }
        }
        return plan.toString();
    }
}
