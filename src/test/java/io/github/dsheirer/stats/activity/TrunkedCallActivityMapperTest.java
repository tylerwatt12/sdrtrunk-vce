/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
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
    private static final String DMR_GUID = "123e4567-e89b-12d3-a456-426614174001";
    private static final String NXDN_GUID = "123e4567-e89b-12d3-a456-426614174002";

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

        P25ActivityLogRecords.ActivityEvent record = new TrunkedCallActivityMapper().map(start);

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.ContextKind.TRUNKED_SITE, record.contextKind());
        assertEquals("DMR", record.protocol());
        assertEquals("GUID:" + DMR_GUID, record.contextKey());
        assertEquals("Downtown", record.channelName());
        assertEquals("Metro DMR", record.aliasListName());
        assertEquals(451_012_500L, record.frequencyHertz());
        assertEquals(2, record.timeslot());
        assertEquals("101", record.sourceRadioId());
        assertEquals("91", record.targetId());
        assertTrue(record.encrypted());
        assertTrue(record.countedCall(), "typed start still identifies a call but completion owns counters");
        assertNull(record.wacn());
    }

    @Test
    void mapsNxdnEncryptionAndAddressDomainWithoutInventingATimeslot()
    {
        Channel parent = new Channel("NXDN Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigNXDN(TransmissionMode.TYPE_D));
        parent.setRadresGuid(NXDN_GUID);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(NXDNRadioIdentifier.createTypeDFrom(0x1134));
        identifiers.update(NXDNTalkgroupIdentifier.createTypeDTo(0x2223));
        identifiers.update(EncryptionKeyIdentifier.create(Protocol.NXDN, NXDNEncryptionKey.create(3, 7)));
        NXDNChannelLookup channel = new NXDNChannelLookup(12);
        channel.receive(null, Map.of(12, new ChannelFrequency(12, 452_012_500L, 0)));
        TrunkedCallStartEvent start = new TrunkedCallStartTracker(3_000).observe(parent, Protocol.NXDN,
            channel, null, identifiers, DecodeEventType.CALL_GROUP_ENCRYPTED, 2_000L);

        P25ActivityLogRecords.ActivityEvent record = new TrunkedCallActivityMapper().map(start);

        assertNotNull(record);
        assertEquals("NXDN", record.protocol());
        assertEquals("GUID:" + NXDN_GUID, record.contextKey());
        assertNull(record.timeslot());
        assertEquals(Integer.toString(0x1134), record.sourceRadioId());
        assertEquals(Integer.toString(0x2223), record.targetId());
        assertEquals(3, record.encryptionAlgorithmId());
        assertEquals(7, record.encryptionKeyId());
        assertEquals(P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, record.identityDomain());
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
        P25ActivityLogRecords.ActivityEvent start = mapper.map(initial.callStart());
        P25ActivityLogRecords.TrunkedCallAttribution attribution = mapper.map(enriched.attribution());
        Path database = mTemporaryFolder.resolve("dmr-attribution.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection, start, true);
            assertTrue(P25ActivityLogSchema.applyTrunkedCallAttribution(connection, attribution));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM trunked_identity_summary"));
            assertEquals(0, scalar(connection,
                "SELECT SUM(logical_call_count) FROM trunked_identity_summary"));
            assertEquals(0, scalar(connection,
                "SELECT SUM(encrypted_logical_call_count) FROM trunked_identity_summary"));
            assertEquals(0x84, scalar(connection,
                "SELECT last_encryption_algorithm_id FROM trunked_identity_summary WHERE identity_id=91"));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
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
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        parent.setDecodeConfiguration(config);
        parent.setSite("Downtown");
        parent.setAliasListName("Metro DMR");
        parent.setRadresGuid(DMR_GUID);
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

    private static EncryptionKey encryptedKey(int algorithm, int key)
    {
        return new EncryptionKey(algorithm, key)
        {
            @Override public boolean isEncrypted() { return true; }
        };
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
