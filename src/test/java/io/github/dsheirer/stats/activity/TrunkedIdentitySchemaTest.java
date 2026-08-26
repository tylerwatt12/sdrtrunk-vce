/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrunkedIdentitySchemaTest
{
    private static final long START = 1_700_000_000_000L;

    @TempDir
    Path mTemporaryFolder;

    @Test
    void createsProtocolNeutralScopesWithNoAliasConfigurationCascade() throws Exception
    {
        try(Connection connection = open("scope-shape.sqlite"))
        {
            P25ActivityLogSchema.validate(connection);
            assertEquals("27", scalarText(connection, """
                SELECT value FROM database_metadata WHERE key='p25_activity_schema_version'
                """));
            assertTrue(Long.parseLong(scalarText(connection, """
                SELECT value FROM database_metadata
                WHERE key='trunked_logical_call_metrics_started_at_ms'
                """)) > 0);
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM pragma_foreign_key_list('trunked_identity_scope')
                WHERE "from"='alias_list_id'
                """));
        }
    }

    @Test
    void completedLogicalCallsOwnLifetimeIdentityCountersForAllTrunkedProtocols() throws Exception
    {
        try(Connection connection = open("all-protocols.sqlite"))
        {
            P25ActivityLogRecords.ResolvedLogicalCall p25 = p25Call(1, 17,
                List.of(new P25SiteIdentity(0x924, 0x649, 1, 1)));
            P25ActivityLogRecords.ResolvedLogicalCall dmr = contextCall(2, "dmr-site", Protocol.DMR,
                P25ActivityLogRecords.IdentityDomain.STANDARD, 91, 101);
            P25ActivityLogRecords.ResolvedLogicalCall nxdn = contextCall(3, "nxdn-site", Protocol.NXDN,
                P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, 301, 201);

            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, p25));
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, dmr));
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, nxdn));

            assertEquals(3, scalar(connection,
                "SELECT SUM(logical_call_count) FROM trunked_logical_call_bucket"));
            assertEquals(3, scalar(connection,
                "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals(6, scalar(connection,
                "SELECT SUM(logical_call_count) FROM trunked_identity_summary"));
            assertEquals(3, scalar(connection,
                "SELECT SUM(target_logical_call_count) FROM trunked_identity_summary"));
            assertEquals(3, scalar(connection,
                "SELECT SUM(source_logical_call_count) FROM trunked_identity_summary"));
            assertEquals(1, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_call_bucket"));
        }
    }

    @Test
    void p25AliasListsRemainSeparateSystemScopes() throws Exception
    {
        try(Connection connection = open("alias-scope.sqlite"))
        {
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, p25Call(1, 11, List.of())));
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, p25Call(2, 12, List.of())));
            assertEquals(2, scalar(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope
                WHERE protocol_code=1 AND scope_kind_code=1
                """));
            assertEquals(2, scalar(connection,
                "SELECT SUM(logical_call_count) FROM trunked_logical_call_bucket"));
        }
    }

    @Test
    void logicalOutputUpdatesTheResolvedScopeWithoutAddingAnotherCall() throws Exception
    {
        try(Connection connection = open("logical-output.sqlite"))
        {
            P25ActivityLogRecords.ResolvedLogicalCall call = p25Call(1, 17, List.of());
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, call));
            assertTrue(P25ActivityLogSchema.applyLogicalCallOutput(connection,
                new P25ActivityLogRecords.LogicalCallOutput(call, P25ActivityLogRecords.CallOutput.RECORDED)));

            assertEquals(1, scalar(connection,
                "SELECT logical_call_count FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection,
                "SELECT recorded_output_count FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection, """
                SELECT recorded_output_count FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=1201
                """));
        }
    }

    @Test
    void resetClearsLogicalSiteAndIdentityFactsButKeepsSchemaValid() throws Exception
    {
        try(Connection connection = open("reset.sqlite"))
        {
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, p25Call(1, 17,
                List.of(new P25SiteIdentity(0x924, 0x649, 1, 1)))));
            assertTrue(P25ActivityLogSchema.resetStats(connection) > 0);
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM trunked_identity_summary"));
            P25ActivityLogSchema.validate(connection);
        }
    }

    private Connection open(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static P25ActivityLogRecords.ResolvedLogicalCall p25Call(long sequence, long aliasListId,
                                                                     List<P25SiteIdentity> sites)
    {
        return new P25ActivityLogRecords.ResolvedLogicalCall(new LogicalCallId(1, sequence), START + sequence,
            "GUID:p25", "p25", Protocol.APCO25.name(), P25ActivityLogRecords.IdentityDomain.STANDARD,
            0x924, 0x649, aliasListId, 1201, Form.TALKGROUP.name(), List.of(), 700001, false,
            null, null, P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of(), sites);
    }

    private static P25ActivityLogRecords.ResolvedLogicalCall contextCall(
        long sequence, String guid, Protocol protocol, P25ActivityLogRecords.IdentityDomain domain,
        int destination, int source)
    {
        return new P25ActivityLogRecords.ResolvedLogicalCall(new LogicalCallId(2, sequence), START + sequence,
            "GUID:" + guid, guid, protocol.name(), domain, null, null, 0, destination,
            Form.TALKGROUP.name(), List.of(), source, true, 0x84, 1,
            P25ActivityLogRecords.P25TargetIdentity.UNKNOWN, List.of(), List.of());
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static String scalarText(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
