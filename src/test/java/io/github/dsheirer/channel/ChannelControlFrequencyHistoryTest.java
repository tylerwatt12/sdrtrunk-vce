/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelControlFrequencyHistoryTest
{
    private static final long FIRST = 851_012_500L;
    private static final long SECOND = 852_012_500L;
    @TempDir Path mTemporaryFolder;

    @Test
    void selectsNewestDecodeRatherThanNewestObservationAndKeepsSavedChannelOwnership() throws Exception
    {
        try(Connection connection = history(); Statement sql = connection.createStatement())
        {
            sample(sql, 1, FIRST, 50_000, 1_000);
            sample(sql, 1, SECOND, 40_000, 39_000);
            sample(sql, 1, FIRST, 60_000, 0);
            sample(sql, 2, FIRST, 70_000, 69_000);
            try(PreparedStatement query = connection.prepareStatement(ChannelControlFrequencyHistory.LAST_CONTROL_SQL))
            {
                assertEquals(SECOND, ChannelControlFrequencyHistory.lastConfirmed(query, "one", Set.of(FIRST, SECOND)));
                assertEquals(FIRST, ChannelControlFrequencyHistory.lastConfirmed(query, "one", Set.of(FIRST)));
                assertEquals(FIRST, ChannelControlFrequencyHistory.lastConfirmed(query, "two", Set.of(FIRST, SECOND)));
                assertNull(ChannelControlFrequencyHistory.lastConfirmed(query, "missing", Set.of(FIRST, SECOND)));
                assertNull(ChannelControlFrequencyHistory.lastConfirmed(query, "one", Set.of(853_012_500L)));
            }
        }
    }

    @Test
    void unresolvedBoundedSearchFallsBackInsteadOfGuessing() throws Exception
    {
        try(Connection connection = history(); Statement sql = connection.createStatement())
        {
            for(int index = 0; index <= ChannelControlFrequencyHistory.MAXIMUM_OBSERVATIONS; index++)
            {
                sample(sql, 1, FIRST, 10_000L * (index + 1), index == 256 ? 1 : 0);
            }
            try(PreparedStatement query = connection.prepareStatement(ChannelControlFrequencyHistory.LAST_CONTROL_SQL))
            {
                assertNull(ChannelControlFrequencyHistory.lastConfirmed(query, "one", Set.of(FIRST, SECOND)),
                    "A newer observation does not prove a newer decode when the read budget is exhausted");
                sample(sql, 1, SECOND, 3_000_000, 2_999_999);
                assertEquals(SECOND, ChannelControlFrequencyHistory.lastConfirmed(query, "one", Set.of(FIRST, SECOND)),
                    "The newest successful decode can resolve before older observations are read");
            }
        }
    }

    @Test
    void representativeHistoryUsesOwnerAndTimeSeeksWithoutSorting() throws Exception
    {
        try(Connection connection = history(); Statement sql = connection.createStatement())
        {
            sql.executeUpdate("""
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<10000)
                INSERT INTO trunked_control_channel_quality
                SELECT 2, 851012500, value*10000, value*10000, value*10000 FROM n
                """);
            sample(sql, 1, SECOND, 30_000, 29_000);
            StringBuilder plan = new StringBuilder();
            try(PreparedStatement query = connection.prepareStatement("EXPLAIN QUERY PLAN " +
                ChannelControlFrequencyHistory.LAST_CONTROL_SQL))
            {
                query.setString(1, "one"); query.setInt(2, 257);
                try(var rows = query.executeQuery())
                {
                    while(rows.next()) plan.append(rows.getString("detail")).append('\n');
                }
            }
            assertTrue(plan.toString().contains("idx_trunked_control_quality_channel_time (channel_id=?)"), plan.toString());
            assertTrue(plan.toString().contains("configuration_id=?"), plan.toString());
            assertFalse(plan.toString().contains("TEMP B-TREE"), plan.toString());
            assertFalse(plan.toString().contains("SCAN trunked_control_channel_quality"), plan.toString());
        }
    }

    @Test
    void missingOptionalHistoryNeverCreatesADatabase() throws Exception
    {
        Path missing = mTemporaryFolder.resolve("missing.sqlite");
        assertTrue(ChannelControlFrequencyHistory.read(missing, List.of(summary("one", "TRUNKED", "configured"))).isEmpty());
        assertFalse(Files.exists(missing));
        Path database = mTemporaryFolder.resolve("history.sqlite");
        try(Connection connection = history(database); Statement sql = connection.createStatement())
        {
            sample(sql, 1, SECOND, 30_000, 29_000);
        }
        assertEquals(SECOND, ChannelControlFrequencyHistory.read(database,
            List.of(summary("one", "TRUNKED", "configured"))).get("one"));
        assertTrue(ChannelControlFrequencyHistory.read(database,
            List.of(summary("one", "CONVENTIONAL", "configured"), summary("two", "TRUNKED", "active-control"))).isEmpty());
    }

    @Test
    void activeControlRequiresCurrentMatchingFreshDecodeProof()
    {
        Channel channel = new Channel("Control", Channel.ChannelType.STANDARD);
        channel.setConfigurationId("123e4567-e89b-12d3-a456-426614174000");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        assertTrue(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST, quality(channel, FIRST, 20_000, 19_999), 20_001));
        assertFalse(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST, quality(channel, FIRST, 20_000, 0), 20_001));
        assertFalse(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST, quality(channel, SECOND, 20_000, 19_999), 20_001));
        assertFalse(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST, quality(channel, FIRST, 20_000, 1_000), 20_001));
        assertFalse(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST, quality(channel, FIRST, 20_000, 19_999), 30_000));
        assertFalse(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST,
            quality(new Channel("Other", Channel.ChannelType.STANDARD), FIRST, 20_000, 19_999), 20_001));
    }

    @Test
    void liveControlProofKeepsTheInclusiveFiveSecondFreshnessBoundary()
    {
        Channel channel = new Channel("Control", Channel.ChannelType.STANDARD);
        channel.setConfigurationId("123e4567-e89b-12d3-a456-426614174000");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        ControlChannelQualitySnapshot lastProof = quality(channel, FIRST, 25_000, 25_000);
        assertTrue(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST, lastProof, 30_000));
        assertFalse(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST, lastProof, 30_001));
        assertTrue(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST,
            quality(channel, FIRST, 30_000, 25_000), 30_000));
        assertFalse(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST,
            quality(channel, FIRST, 30_000, 24_999), 30_000));
        assertFalse(ChannelAdministrationService.isConfirmedActiveControl(channel, FIRST,
            quality(channel, FIRST, 30_001, 30_000), 30_000));
    }

    private static ControlChannelQualitySnapshot quality(Channel channel, long frequency, long observed, long decoded)
    {
        return new ControlChannelQualitySnapshot(channel, channel.getConfigurationId(), frequency, observed, true,
            null, null, null, null, null, 1, 0, 0, 0, 0, decoded);
    }

    private Connection history() throws Exception
    {
        return history(mTemporaryFolder.resolve("history.sqlite"));
    }

    /** Exact production lookup keys in an isolated projection; no runtime schema is created by the reader. */
    private static Connection history(Path database) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(Statement sql = connection.createStatement())
        {
            sql.executeUpdate("CREATE TABLE receiver_channel(id INTEGER PRIMARY KEY, configuration_id TEXT UNIQUE)");
            sql.executeUpdate("INSERT INTO receiver_channel VALUES(1,'one'),(2,'two')");
            sql.executeUpdate("""
                CREATE TABLE trunked_control_channel_quality(channel_id INTEGER NOT NULL, frequency_hz INTEGER NOT NULL,
                    bucket_start_ms INTEGER NOT NULL, observed_at_ms INTEGER NOT NULL,
                    last_valid_decode_ms INTEGER NOT NULL CHECK(last_valid_decode_ms>=0 AND last_valid_decode_ms<=observed_at_ms),
                    PRIMARY KEY(channel_id,frequency_hz,bucket_start_ms)) WITHOUT ROWID
                """);
            sql.executeUpdate("CREATE INDEX idx_trunked_control_quality_channel_time ON trunked_control_channel_quality(channel_id,observed_at_ms DESC)");
        }
        return connection;
    }

    private static void sample(Statement sql, int owner, long frequency, long observed, long decoded) throws Exception
    {
        sql.executeUpdate("INSERT INTO trunked_control_channel_quality VALUES(" + owner + "," + frequency + "," +
            observed / 10_000 * 10_000 + "," + observed + "," + decoded + ")");
    }

    private static ChannelAdministrationService.ChannelSummary summary(String id, String kind, String source)
    {
        return new ChannelAdministrationService.ChannelSummary(id, "p25-phase1", "P25 Phase 1", kind, "County", "Site",
            "Control", List.of(FIRST, SECOND), ChannelAdministrationService.ProcessingState.STOPPED, null, 0, "", true,
            null, null, FIRST, source);
    }
}
