/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.DMRTier3Channel;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNEncryptionKey;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartTracker;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrunkedCallActivityMapperTest
{
    private static final String DMR_CONFIGURATION_ID = "123e4567-e89b-12d3-a456-426614174011";
    private static final String NXDN_CONFIGURATION_ID = "123e4567-e89b-12d3-a456-426614174012";
    private static final String DMR_RADIORESOLVE_ID = "123e4567-e89b-12d3-a456-426614174001";
    private static final String NXDN_RADIORESOLVE_ID = "123e4567-e89b-12d3-a456-426614174002";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void mapsDmrTrunkedCallStartAsSignalingWithoutInventingP25Identity()
    {
        Channel parent = dmrParent();
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(DMRRadio.createFrom(101));
        identifiers.update(DMRTalkgroup.create(91));
        TrunkedCallStartEvent start = new TrunkedCallStartTracker(5_000).observe(parent, Protocol.DMR,
            dmrChannel(451_012_500L, 2), 2, identifiers, DecodeEventType.CALL_GROUP_ENCRYPTED, 1_000L);

        ReceiverActivityRecords.ActivityEvent record = new TrunkedCallActivityMapper().map(start);

        assertNotNull(record);
        assertEquals(ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, record.receiverKind());
        assertEquals("DMR", record.protocol());
        assertEquals(parent.getConfigurationId().toString(), record.configurationId());
        assertEquals(451_012_500L, record.frequencyHertz());
        assertEquals(2, record.timeslot());
        assertEquals("101", record.sourceRadioId());
        assertEquals("91", record.targetId());
        assertEquals(" LCN:12 CHANID:26", record.lcn());
        assertTrue(record.encrypted());
        assertTrue(record.countedCall(), "typed start still identifies a call but completion owns counters");
        assertNull(record.wacn());
    }

    @Test
    void mapsNxdnEncryptionAndAddressDomainWithoutInventingATimeslot()
    {
        Channel parent = new Channel("NXDN Site", Channel.ChannelType.STANDARD);
        parent.setConfigurationId(NXDN_CONFIGURATION_ID);
        parent.setDecodeConfiguration(new DecodeConfigNXDN(TransmissionMode.TYPE_D));
        parent.setRadioResolveId(NXDN_RADIORESOLVE_ID);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(NXDNRadioIdentifier.createTypeDFrom(0x1134));
        identifiers.update(NXDNTalkgroupIdentifier.createTypeDTo(0x2223));
        identifiers.update(EncryptionKeyIdentifier.create(Protocol.NXDN, NXDNEncryptionKey.create(3, 7)));
        ThrowingNxdnLookupChannel channel = new ThrowingNxdnLookupChannel(12);
        channel.receive(null, Map.of(12, new ChannelFrequency(12, 452_012_500L, 0)));
        TrunkedCallStartEvent start = new TrunkedCallStartTracker(3_000).observe(parent, Protocol.NXDN,
            channel, null, identifiers, DecodeEventType.CALL_GROUP_ENCRYPTED, 2_000L);

        ReceiverActivityRecords.ActivityEvent record = new TrunkedCallActivityMapper().map(start);

        assertNotNull(record);
        assertEquals("NXDN", record.protocol());
        assertEquals(parent.getConfigurationId().toString(), record.configurationId());
        assertNull(record.timeslot());
        assertEquals(Integer.toString(0x1134), record.sourceRadioId());
        assertEquals(Integer.toString(0x2223), record.targetId());
        assertEquals("DN:452.0125 MHZ", record.lcn());
        assertEquals(3, record.encryptionAlgorithmId());
        assertEquals(7, record.encryptionKeyId());
        assertEquals(TrunkedIdentityDomain.NXDN_TYPE_D, record.identityDomain());
        assertEquals(0, channel.toStringCalls(),
            "NXDN producer capture and worker projection must both use frozen scalars");
    }

    @Test
    void decoderPathSnapshotsDescriptorScalarsAndFormatsOnlyInTheMapper()
    {
        Channel parent = dmrParent();
        ThrowingTierThreeChannel channel = new ThrowingTierThreeChannel(12, 2);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(12);
        mapping.setDownlinkFrequency(451_012_500L);
        channel.setTimeslotFrequency(mapping);
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(5_000L);

        TrunkedCallStartTracker.ObservationResult initial = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 2, new MutableIdentifierCollection(), DecodeEventType.CALL_GROUP, 1_000L);
        MutableIdentifierCollection identified = new MutableIdentifierCollection();
        identified.update(DMRRadio.createFrom(101));
        identified.update(DMRTalkgroup.create(91));
        TrunkedCallStartTracker.ObservationResult enriched = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 2, identified, DecodeEventType.CALL_GROUP, 1_100L);

        assertNotNull(initial.callStart());
        assertNotNull(enriched.attribution());
        assertEquals(0, channel.toStringCalls(),
            "call-start and attribution capture must not format a live descriptor");
        ReceiverActivityRecords.ActivityEvent projected = new TrunkedCallActivityMapper().map(initial.callStart());
        assertEquals(" LCN:12 CHANID:26", projected.lcn());
        assertEquals(0, channel.toStringCalls(),
            "the mapper must format from frozen scalars rather than the live descriptor");
    }

    @Test
    void typedDmrAttributionEnrichesDirectoryButNeverCountsALogicalCall() throws Exception
    {
        Channel parent = dmrParent();
        DMRTier3Channel channel = dmrChannel(451_012_500L, 2);
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(5_000);
        TrunkedCallStartTracker.ObservationResult initial = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 2, new MutableIdentifierCollection(), DecodeEventType.CALL_GROUP, 3_599_000L);
        MutableIdentifierCollection identified = new MutableIdentifierCollection();
        identified.update(DMRRadio.createFrom(101));
        identified.update(DMRTalkgroup.create(91));
        identified.update(EncryptionKeyIdentifier.create(Protocol.DMR, encryptedKey(0x84, 101)));
        TrunkedCallStartTracker.ObservationResult enriched = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 2, identified, DecodeEventType.CALL_GROUP_ENCRYPTED, 3_601_000L);
        TrunkedCallActivityMapper mapper = new TrunkedCallActivityMapper();
        ReceiverActivityRecords.ActivityEvent start = mapper.map(initial.callStart());
        ReceiverActivityRecords.TrunkedCallAttribution attribution = mapper.map(enriched.attribution());
        Path database = mTemporaryFolder.resolve("dmr-attribution.sqlite");
        createDatabase(database, parent);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            ReceiverActivitySchema.recordActivity(connection, start, true);
            assertTrue(ReceiverActivitySchema.applyTrunkedCallAttribution(connection, attribution));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM radio_system_identity_summary"));
            assertEquals(0, scalar(connection,
                "SELECT SUM(logical_call_count) FROM radio_system_identity_summary"));
            assertEquals(0, scalar(connection,
                "SELECT SUM(encrypted_logical_call_count) FROM radio_system_identity_summary"));
            assertEquals(0, scalar(connection,
                "SELECT SUM(source_logical_call_count) FROM radio_system_identity_summary"));
            assertEquals(0, scalar(connection,
                "SELECT SUM(target_logical_call_count) FROM radio_system_identity_summary"));
            assertEquals(0x84, scalar(connection,
                "SELECT last_encryption_algorithm_id FROM radio_system_identity_summary WHERE identity_id=91"));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM trunked_logical_call_bucket"));

            ReceiverActivityRecords.ResolvedLogicalCall completed =
                new ReceiverActivityRecords.ResolvedLogicalCall(new LogicalCallId(9, 1), 3_599_000L,
                    parent.getConfigurationId().toString(), Protocol.DMR.name(), TrunkedIdentityDomain.STANDARD,
                    null, null, 91, Form.TALKGROUP.name(), java.util.List.of(), 101, true, 0x84, 101,
                    ReceiverActivityRecords.P25Identity.UNKNOWN, ReceiverActivityRecords.P25Identity.UNKNOWN,
                    java.util.List.of(), java.util.List.of(),
                    "dmr:channel:" + parent.getConfigurationId());
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection, completed));
            assertEquals(1, scalar(connection, """
                SELECT logical_call_count FROM radio_system_identity_summary
                WHERE identity_kind_code=1 AND identity_id=91
                """));
            assertEquals(1, scalar(connection, """
                SELECT target_logical_call_count FROM radio_system_identity_summary
                WHERE identity_kind_code=1 AND identity_id=91
                """));
            assertEquals(1, scalar(connection, """
                SELECT logical_call_count FROM radio_system_identity_summary
                WHERE identity_kind_code=2 AND identity_id=101
                """));
            assertEquals(1, scalar(connection, """
                SELECT source_logical_call_count FROM radio_system_identity_summary
                WHERE identity_kind_code=2 AND identity_id=101
                """));
        }
    }

    @Test
    void rejectsProtocolAndDecoderMismatch()
    {
        Channel parent = dmrParent();
        TrunkedCallStartEvent start = new TrunkedCallStartTracker(5_000).observe(parent, Protocol.NXDN,
            dmrChannel(451_012_500L, 2), 2, new MutableIdentifierCollection(),
            DecodeEventType.CALL_GROUP, 1_000L);
        assertNull(new TrunkedCallActivityMapper().map(start));
    }

    private static Channel dmrParent()
    {
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        parent.setConfigurationId(DMR_CONFIGURATION_ID);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        parent.setDecodeConfiguration(config);
        parent.setSite("Downtown");
        parent.setAliasListName("Metro DMR");
        parent.setRadioResolveId(DMR_RADIORESOLVE_ID);
        return parent;
    }

    private static DMRTier3Channel dmrChannel(long frequency, int timeslot)
    {
        DMRTier3Channel channel = new DMRTier3Channel(12, timeslot);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(12);
        mapping.setDownlinkFrequency(frequency);
        channel.setTimeslotFrequency(mapping);
        return channel;
    }

    private static final class ThrowingTierThreeChannel extends DMRTier3Channel
    {
        private int mToStringCalls;

        private ThrowingTierThreeChannel(int channel, int timeslot)
        {
            super(channel, timeslot);
        }

        @Override
        public String toString()
        {
            mToStringCalls++;
            throw new AssertionError("Live descriptor formatting is not allowed on decoder callbacks");
        }

        private int toStringCalls()
        {
            return mToStringCalls;
        }
    }

    private static final class ThrowingNxdnLookupChannel extends NXDNChannelLookup
    {
        private int mToStringCalls;

        private ThrowingNxdnLookupChannel(int channel)
        {
            super(channel);
        }

        @Override
        public String toString()
        {
            mToStringCalls++;
            throw new AssertionError("Live NXDN descriptor formatting is not allowed on decoder callbacks");
        }

        private int toStringCalls()
        {
            return mToStringCalls;
        }
    }

    private static EncryptionKey encryptedKey(int algorithm, int key)
    {
        return new EncryptionKey(algorithm, key)
        {
            @Override public boolean isEncrypted() { return true; }
        };
    }

    private static void createDatabase(Path database, Channel channel) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json
                ) VALUES ('%s', 'TRUNKED', 0, 'Metro', 'Downtown', 'DMR Site',
                    '%s', 0, 'DMR', 451012500,
                    '{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
                """.formatted(channel.getConfigurationId(), DMR_RADIORESOLVE_ID));
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }
}
