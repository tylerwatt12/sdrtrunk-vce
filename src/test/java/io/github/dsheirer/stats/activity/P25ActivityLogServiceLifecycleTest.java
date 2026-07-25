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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P25ActivityLogServiceLifecycleTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void lowersRetentionAndRunsMaintenanceWhileCollectionIsDisabled() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(now - TimeUnit.DAYS.toMillis(40)), true);
            P25ActivityLogSchema.recordActivity(connection,
                activity(now - TimeUnit.DAYS.toMillis(2)), true);

            try(var statement = connection.prepareStatement("""
                INSERT INTO trunked_site_snapshot (
                    guid, snapshot_hash, protocol_code, variant_code, identity_domain_code,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, 1, 1, ?, ?, 1)
                """))
            {
                insertTrunkedSite(statement, "expired-dmr", TrunkedSiteSchema.PROTOCOL_DMR,
                    now - TimeUnit.DAYS.toMillis(40));
                insertTrunkedSite(statement, "current-dmr", TrunkedSiteSchema.PROTOCOL_DMR,
                    now - TimeUnit.DAYS.toMillis(2));
                insertTrunkedSite(statement, "expired-nxdn", TrunkedSiteSchema.PROTOCOL_NXDN,
                    now - TimeUnit.DAYS.toMillis(40));
                insertTrunkedSite(statement, "current-nxdn", TrunkedSiteSchema.PROTOCOL_NXDN,
                    now - TimeUnit.DAYS.toMillis(2));
            }
        }

        TestApplicationPreference applicationPreference = new TestApplicationPreference(false, 30);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences);

        try
        {
            StatsDatabaseMaintenanceRequest initialCheck =
                StatsDatabaseMaintenanceRequest.forOperation(P25ActivityLogMaintenance.Operation.CHECK);
            service.receiveMaintenanceRequest(initialCheck);
            assertTrue(initialCheck.result().get(5, TimeUnit.SECONDS).checkOk());
            assertEquals(P25ActivityLogStatus.State.DISABLED, service.getStatus().state());
            //Startup maintenance used the 30-day setting even though collection was disabled.
            assertEquals(1, count(database, "p25_activity_event"));
            assertEquals(2, count(database, "trunked_site_snapshot"));
            assertEquals(1, countProtocol(database, TrunkedSiteSchema.PROTOCOL_DMR));
            assertEquals(1, countProtocol(database, TrunkedSiteSchema.PROTOCOL_NXDN));

            applicationPreference.setRetentionDays(1);
            service.preferenceUpdated(PreferenceType.APPLICATION);

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
            int remainingP25 = 1;
            int remainingTrunked = 2;

            while((remainingP25 != 0 || remainingTrunked != 0) && System.currentTimeMillis() < deadline)
            {
                remainingP25 = count(database, "p25_activity_event");
                remainingTrunked = count(database, "trunked_site_snapshot");

                if(remainingP25 != 0 || remainingTrunked != 0)
                {
                    Thread.sleep(25);
                }
            }

            assertEquals(0, remainingP25);
            assertEquals(0, remainingTrunked);

            StatsDatabaseMaintenanceRequest finalCheck =
                StatsDatabaseMaintenanceRequest.forOperation(P25ActivityLogMaintenance.Operation.CHECK);
            service.receiveMaintenanceRequest(finalCheck);
            assertTrue(finalCheck.result().get(5, TimeUnit.SECONDS).checkOk());
            assertEquals(P25ActivityLogStatus.State.DISABLED, service.getStatus().state());

            Channel channel = new Channel("Disabled collection", Channel.ChannelType.STANDARD);
            channel.setRadresGuid("00000000-0000-0000-0000-000000000102");
            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(channel,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 1, 2, null, null, null, null,
                    null, null, List.of(), List.of()),
                System.currentTimeMillis()));
            Thread.sleep(1_100);
            assertEquals(0, count(database, "trunked_site_snapshot"));
        }
        finally
        {
            service.dispose();
        }
    }

    @Test
    void exposesMaintenanceWriterFailureWhileCollectionIsDisabled() throws Exception
    {
        TestApplicationPreference applicationPreference = new TestApplicationPreference(false, 30);
        TestUserPreferences userPreferences = new TestUserPreferences(applicationPreference,
            new TestDirectoryPreference(mTemporaryFolder.resolve("missing-portable-data")));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences);

        try
        {
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);

            while(service.getStatus().state() != P25ActivityLogStatus.State.FAILED &&
                System.currentTimeMillis() < deadline)
            {
                Thread.sleep(25);
            }

            assertEquals(P25ActivityLogStatus.State.FAILED, service.getStatus().state());
            assertTrue(service.getStatus().lastError() != null && !service.getStatus().lastError().isBlank());
        }
        finally
        {
            service.dispose();
        }
    }

    @Test
    void persistsExplicitTrunkedDmrQualityWithoutPromotingConventionalDmr() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        P25ActivityLogService service = new P25ActivityLogService(userPreferences);

        try
        {
            long now = System.currentTimeMillis();
            Channel trunked = dmrChannel("00000000-0000-0000-0000-000000000201", DMRChannelMode.TRUNKED);
            service.getControlChannelQualityListener().receive(quality(trunked, now));
            awaitCount(database, "p25_control_channel_quality", 1);

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", null, 10, 20, null, null, null, null,
                    1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 10_000L));
            awaitCount(database, "p25_control_channel_quality", 2);
            assertEquals(0, count(database, "trunked_site_snapshot"));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 20_000L));
            awaitCount(database, "p25_control_channel_quality", 3);
            assertEquals(1, count(database, "trunked_site_snapshot"));

            applicationPreference.setCollectionEnabled(false);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            applicationPreference.setCollectionEnabled(true);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            service.getControlChannelQualityListener().receive(quality(trunked, now + 40_000L));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            Channel reusedGuid = dmrChannel(trunked.getRadresGuid(), DMRChannelMode.TRUNKED);
            service.getControlChannelQualityListener().receive(quality(reusedGuid, now + 60_000L));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 600_000L));
            awaitCount(database, "p25_control_channel_quality", 6);

            service.getControlChannelQualityListener().receive(quality(trunked, now + 610_000L, false));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 620_000L));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", null, 10, 20, null, null, null, null,
                    1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 640_000L));

            Channel conventional = dmrChannel("00000000-0000-0000-0000-000000000202");
            service.getControlChannelQualityListener().receive(quality(conventional, now + 660_000L));
            awaitCount(database, "p25_control_channel_quality", 8);
        }
        finally
        {
            service.dispose();
        }
    }

    private static int count(Path database, String table) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static int countProtocol(Path database, int protocol) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE protocol_code = ?"))
        {
            statement.setInt(1, protocol);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private static void awaitCount(Path database, String table, int expected) throws Exception
    {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        int actual = count(database, table);

        while(actual != expected && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
            actual = count(database, table);
        }

        assertEquals(expected, actual);
    }

    private static Channel dmrChannel(String guid)
    {
        return dmrChannel(guid, DMRChannelMode.CONVENTIONAL);
    }

    private static Channel dmrChannel(String guid, DMRChannelMode mode)
    {
        Channel channel = new Channel("DMR", Channel.ChannelType.STANDARD);
        channel.setRadresGuid(guid);
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(mode);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }

    private static ControlChannelQualitySnapshot quality(Channel channel, long observedAt)
    {
        return quality(channel, observedAt, true);
    }

    private static ControlChannelQualitySnapshot quality(Channel channel, long observedAt, boolean active)
    {
        return new ControlChannelQualitySnapshot(channel, channel.getRadresGuid(), 451_012_500L, observedAt,
            active, -20.0, -21.0, -25.0, -18.0, 95.0, 100, 2, 1, 0, 0, observedAt);
    }

    private static void insertTrunkedSite(java.sql.PreparedStatement statement, String guid, int protocol,
                                          long observedAt) throws Exception
    {
        statement.setString(1, guid);
        statement.setString(2, "hash-" + guid);
        statement.setInt(3, protocol);
        statement.setLong(4, observedAt);
        statement.setLong(5, observedAt);
        statement.executeUpdate();
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp)
    {
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.GRANT,
            "CALL_GROUP", "1811524", "56138", "TALKGROUP", 854_187_500L, "00-0509", 1, false,
            null, null, 0xBEE00, 0x348, 0x348, 2, 1, "Example Site", null, null, false, null, null);
    }

    private static class TestUserPreferences extends UserPreferences
    {
        private final ApplicationPreference mApplicationPreference;
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(ApplicationPreference applicationPreference,
                                    DirectoryPreference directoryPreference)
        {
            mApplicationPreference = applicationPreference;
            mDirectoryPreference = directoryPreference;
        }

        @Override
        public ApplicationPreference getApplicationPreference()
        {
            return mApplicationPreference;
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }

    private static class TestApplicationPreference extends ApplicationPreference
    {
        private boolean mCollectionEnabled;
        private int mRetentionDays;

        private TestApplicationPreference(boolean collectionEnabled, int retentionDays)
        {
            super(preferenceType -> {});
            mCollectionEnabled = collectionEnabled;
            mRetentionDays = retentionDays;
        }

        @Override
        public boolean isStatsLoggingEnabled()
        {
            return mCollectionEnabled;
        }

        private void setCollectionEnabled(boolean collectionEnabled)
        {
            mCollectionEnabled = collectionEnabled;
        }

        @Override
        public boolean isStatsDetailedHistoryEnabled()
        {
            return false;
        }

        @Override
        public int getStatsLoggingRetentionDays()
        {
            return mRetentionDays;
        }

        private void setRetentionDays(int retentionDays)
        {
            mRetentionDays = retentionDays;
        }
    }

    private static class TestDirectoryPreference extends DirectoryPreference
    {
        private final Path mRoot;

        private TestDirectoryPreference(Path root)
        {
            super(preferenceType -> {});
            mRoot = root;
        }

        @Override
        public Path getDirectoryApplicationRoot()
        {
            return mRoot;
        }
    }
}
