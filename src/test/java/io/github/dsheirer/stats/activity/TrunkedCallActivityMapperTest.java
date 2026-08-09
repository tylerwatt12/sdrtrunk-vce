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
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.traffic.TrunkedCallAttributionEvent;
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
    void mapsAndPersistsDmrTrunkedCallWithoutP25SystemIdentity() throws Exception
    {
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        parent.setDecodeConfiguration(config);
        parent.setSite("Downtown");
        parent.setAliasListName("Metro DMR");
        parent.setRadresGuid(DMR_GUID);
        DMRTier3Channel channel = dmrChannel(451_012_500L, 2);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(DMRRadio.createFrom(101));
        identifiers.update(DMRTalkgroup.create(91));
        TrunkedCallStartEvent start = new TrunkedCallStartTracker(5_000).observe(parent, Protocol.DMR,
            channel, 2, identifiers, DecodeEventType.CALL_GROUP_ENCRYPTED, 1_000L);

        P25ActivityLogRecords.ActivityEvent record = new TrunkedCallActivityMapper().map(start);

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.ContextKind.TRUNKED_SITE, record.contextKind());
        assertEquals("DMR", record.protocol());
        assertEquals("GUID:" + DMR_GUID, record.contextKey());
        assertEquals("Downtown", record.channelName());
        assertEquals("Metro DMR", record.aliasListName());
        assertTrue(record.configuredMetadataObserved());
        assertEquals(1_000L, record.observedAtEpochMilliseconds());
        assertEquals(451_012_500L, record.frequencyHertz());
        assertEquals(2, record.timeslot());
        assertEquals("101", record.sourceRadioId());
        assertEquals("91", record.targetId());
        assertEquals("TALKGROUP", record.targetKind());
        assertTrue(record.encrypted());
        assertTrue(record.countedCall());
        assertNull(record.dedupeKey());

        Path database = mTemporaryFolder.resolve("dmr-trunked-call.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection, record, true);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT context.kind_code, context.protocol_code, context.system_key,
                           context.channel_name, context.alias_list_name, context.decoder,
                           context.primary_frequency_hz, context.current_control_hz,
                           context.nac, context.rfss, context.site,
                           activity.call_count, event.source_radio_id, event.target_id,
                           event.frequency_hz, event.timeslot, event.encrypted
                    FROM receiver_context context
                    JOIN p25_site_activity_bucket activity ON activity.context_id = context.id
                    JOIN p25_activity_event event ON event.context_id = context.id
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("kind_code"));
                assertEquals(3, resultSet.getInt("protocol_code"));
                assertNull(resultSet.getObject("system_key"));
                assertEquals("Downtown", resultSet.getString("channel_name"));
                assertEquals("Metro DMR", resultSet.getString("alias_list_name"));
                assertEquals("DMR", resultSet.getString("decoder"));
                assertNull(resultSet.getObject("primary_frequency_hz"));
                assertNull(resultSet.getObject("current_control_hz"));
                assertNull(resultSet.getObject("nac"));
                assertNull(resultSet.getObject("rfss"));
                assertNull(resultSet.getObject("site"));
                assertEquals(1, resultSet.getInt("call_count"));
                assertEquals(101, resultSet.getInt("source_radio_id"));
                assertEquals(91, resultSet.getInt("target_id"));
                assertEquals(451_012_500L, resultSet.getLong("frequency_hz"));
                assertEquals(2, resultSet.getInt("timeslot"));
                assertEquals(1, resultSet.getInt("encrypted"));
                assertFalse(resultSet.next());
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT identity_role_code, identity_kind_code, identity_id, call_count, encrypted_count
                    FROM call_identity_bucket
                    ORDER BY identity_role_code
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(P25ActivityLogSchema.IDENTITY_ROLE_DESTINATION,
                    resultSet.getInt("identity_role_code"));
                assertEquals(P25ActivityLogSchema.IDENTITY_KIND_TALKGROUP,
                    resultSet.getInt("identity_kind_code"));
                assertEquals(91, resultSet.getInt("identity_id"));
                assertEquals(1, resultSet.getInt("call_count"));
                assertEquals(1, resultSet.getInt("encrypted_count"));
                assertTrue(resultSet.next());
                assertEquals(P25ActivityLogSchema.IDENTITY_ROLE_SOURCE,
                    resultSet.getInt("identity_role_code"));
                assertEquals(P25ActivityLogSchema.IDENTITY_KIND_RADIO,
                    resultSet.getInt("identity_kind_code"));
                assertEquals(101, resultSet.getInt("identity_id"));
                assertFalse(resultSet.next());
            }
        }
    }

    @Test
    void mapsEncryptedNxdnCallWithoutInventingATimeslot() throws Exception
    {
        Channel parent = new Channel("NXDN Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigNXDN());
        parent.setSite("North");
        parent.setAliasListName("Metro NXDN");
        parent.setRadresGuid(NXDN_GUID);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(NXDNRadioIdentifier.createFrom(201));
        identifiers.update(NXDNTalkgroupIdentifier.createTo(301));
        identifiers.update(EncryptionKeyIdentifier.create(Protocol.NXDN, NXDNEncryptionKey.create(3, 7)));
        NXDNChannelLookup channel = new NXDNChannelLookup(12);
        channel.receive(null, Map.of(12, new ChannelFrequency(12, 452_012_500L, 0)));
        TrunkedCallStartEvent start = new TrunkedCallStartTracker(3_000).observe(parent, Protocol.NXDN,
            channel, null, identifiers, DecodeEventType.CALL_GROUP_ENCRYPTED, 2_000L);

        P25ActivityLogRecords.ActivityEvent record = new TrunkedCallActivityMapper().map(start);

        assertNotNull(record);
        assertEquals("NXDN", record.protocol());
        assertEquals("GUID:" + NXDN_GUID, record.contextKey());
        assertEquals("North", record.channelName());
        assertEquals("Metro NXDN", record.aliasListName());
        assertTrue(record.configuredMetadataObserved());
        assertEquals(452_012_500L, record.frequencyHertz());
        assertNull(record.timeslot());
        assertEquals("201", record.sourceRadioId());
        assertEquals("301", record.targetId());
        assertTrue(record.encrypted());
        assertEquals(3, record.encryptionAlgorithmId());
        assertEquals(7, record.encryptionKeyId());
        assertTrue(record.countedCall());
        assertEquals(P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C, record.identityDomain());

        Path database = mTemporaryFolder.resolve("nxdn-trunked-call.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection, record, false);
            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT kind_code, protocol_code, system_key, channel_name, alias_list_name, decoder,
                           primary_frequency_hz, current_control_hz, nac, rfss, site
                    FROM receiver_context
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("kind_code"));
                assertEquals(4, resultSet.getInt("protocol_code"));
                assertNull(resultSet.getObject("system_key"));
                assertEquals("North", resultSet.getString("channel_name"));
                assertEquals("Metro NXDN", resultSet.getString("alias_list_name"));
                assertEquals("NXDN", resultSet.getString("decoder"));
                assertNull(resultSet.getObject("primary_frequency_hz"));
                assertNull(resultSet.getObject("current_control_hz"));
                assertNull(resultSet.getObject("nac"));
                assertNull(resultSet.getObject("rfss"));
                assertNull(resultSet.getObject("site"));
                assertFalse(resultSet.next());
            }
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM call_identity_bucket"));
            assertEquals(1, scalar(connection, """
                SELECT call_count FROM call_identity_bucket
                WHERE identity_role_code=1 AND identity_kind_code=1 AND identity_id=301
                """));
            assertEquals(1, scalar(connection, """
                SELECT call_count FROM call_identity_bucket
                WHERE identity_role_code=2 AND identity_kind_code=2 AND identity_id=201
                """));
        }
    }

    @Test
    void preservesNxdnTypeDAddressDomain()
    {
        Channel parent = new Channel("NXDN Type-D Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigNXDN(TransmissionMode.TYPE_D));
        parent.setRadresGuid(NXDN_GUID);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(NXDNRadioIdentifier.createTypeDFrom(0x1134));
        identifiers.update(NXDNTalkgroupIdentifier.createTypeDTo(0x2223));
        NXDNChannelLookup channel = new NXDNChannelLookup(12);
        channel.receive(null, Map.of(12, new ChannelFrequency(12, 452_012_500L, 0)));
        TrunkedCallStartEvent start = new TrunkedCallStartTracker(3_000).observe(parent, Protocol.NXDN,
            channel, null, identifiers, DecodeEventType.CALL_GROUP, 2_000L);

        P25ActivityLogRecords.ActivityEvent record = new TrunkedCallActivityMapper().map(start);

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, record.identityDomain());
        assertEquals(Integer.toString(0x1134), record.sourceRadioId());
        assertEquals(Integer.toString(0x2223), record.targetId());
    }

    @Test
    void movesAnUnidentifiedDmrCallToLateKnownIdentitiesWithoutCountingAnotherCall() throws Exception
    {
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        parent.setDecodeConfiguration(config);
        parent.setRadresGuid(DMR_GUID);
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
        P25ActivityLogRecords.ActivityEvent call = mapper.map(initial.callStart());
        P25ActivityLogRecords.TrunkedCallAttribution attribution = mapper.map(enriched.attribution());

        assertNotNull(call);
        assertNotNull(attribution);
        assertEquals(3_599_000L, attribution.callStartEpochMilliseconds());
        assertEquals(451_012_500L, attribution.frequencyHertz());
        assertEquals(2, attribution.timeslot());
        assertEquals(91, attribution.destinationId());
        assertEquals(101, attribution.sourceRadioId());
        assertTrue(attribution.destinationBecameKnown());
        assertTrue(attribution.sourceBecameKnown());
        assertTrue(attribution.encryptionBecameKnown());
        assertEquals(0x84, attribution.encryptionAlgorithmId());
        assertEquals(101, attribution.encryptionKeyId());

        Path database = mTemporaryFolder.resolve("dmr-late-attribution.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection, call, true);
            assertTrue(P25ActivityLogSchema.applyTrunkedCallAttribution(connection, attribution));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM p25_activity_event"));
            assertEquals(1, scalar(connection,
                "SELECT call_count FROM p25_site_activity_bucket"));
            assertEquals(1, scalar(connection,
                "SELECT encrypted_count FROM p25_site_activity_bucket"));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM call_identity_bucket"));
            assertEquals(1, scalar(connection, """
                SELECT call_count FROM call_identity_bucket
                WHERE identity_role_code=1 AND identity_kind_code=1 AND identity_id=91
                """));
            assertEquals(1, scalar(connection, """
                SELECT encrypted_count FROM call_identity_bucket
                WHERE identity_role_code=1 AND identity_kind_code=1 AND identity_id=91
                """));
            assertEquals(1, scalar(connection, """
                SELECT call_count FROM call_identity_bucket
                WHERE identity_role_code=2 AND identity_kind_code=2 AND identity_id=101
                """));
            assertEquals(1, scalar(connection, """
                SELECT encrypted_count FROM call_identity_bucket
                WHERE identity_role_code=2 AND identity_kind_code=2 AND identity_id=101
                """));
            assertEquals(1, scalar(connection,
                "SELECT call_count FROM p25_site_talkgroup_bucket WHERE talkgroup_id=91"));
            assertEquals(1, scalar(connection,
                "SELECT encrypted_count FROM p25_site_talkgroup_bucket WHERE talkgroup_id=91"));
            assertEquals(1, scalar(connection,
                "SELECT call_count FROM p25_site_frequency_summary WHERE frequency_hz=451012500"));
            assertEquals(1, scalar(connection,
                "SELECT encrypted_count FROM p25_site_frequency_summary WHERE frequency_hz=451012500"));
            assertEquals(0x84, scalar(connection,
                "SELECT encryption_algorithm_id FROM p25_activity_event"));
            assertEquals(101, scalar(connection,
                "SELECT encryption_key_id FROM p25_activity_event"));
        }
    }

    @Test
    void preservesFullyQualifiedP25LateAttribution()
    {
        Channel parent = new Channel("P25 Site", Channel.ChannelType.TRAFFIC);
        parent.setDecodeConfiguration(new DecodeConfigP25Phase1());
        parent.setRadresGuid("123e4567-e89b-12d3-a456-426614174003");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25FullyQualifiedTalkgroupIdentifier.createTo(56_138, 0xABCDE, 0x321, 1_200));
        TrunkedCallAttributionEvent event = new TrunkedCallAttributionEvent(parent, Protocol.APCO25,
            null, 1, 1_000L, identifiers, true, false, false, false);

        P25ActivityLogRecords.TrunkedCallAttribution attribution =
            new TrunkedCallActivityMapper().map(event);

        assertNotNull(attribution);
        assertEquals(56_138, attribution.destinationId());
        assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED,
            attribution.p25TargetIdentity().state());
        assertEquals(0xABCDE, attribution.p25TargetIdentity().homeWacn());
        assertEquals(0x321, attribution.p25TargetIdentity().homeSystemId());
        assertEquals(1_200, attribution.p25TargetIdentity().homeTalkgroupId());

        MutableIdentifierCollection zeroLocalIdentifiers = new MutableIdentifierCollection();
        zeroLocalIdentifiers.update(APCO25FullyQualifiedTalkgroupIdentifier.createTo(
            0, 0xABCDE, 0x321, 1_201));
        TrunkedCallAttributionEvent zeroLocalEvent = new TrunkedCallAttributionEvent(parent, Protocol.APCO25,
            null, 1, 1_100L, zeroLocalIdentifiers, true, false, false, false);
        P25ActivityLogRecords.TrunkedCallAttribution zeroLocal =
            new TrunkedCallActivityMapper().map(zeroLocalEvent);

        assertNotNull(zeroLocal);
        assertEquals(0, zeroLocal.destinationId());
        assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED,
            zeroLocal.p25TargetIdentity().state());
        assertEquals(1_201, zeroLocal.p25TargetIdentity().homeTalkgroupId());
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

    private static int scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static EncryptionKey encryptedKey(int algorithm, int key)
    {
        return new EncryptionKey(algorithm, key)
        {
            @Override
            public boolean isEncrypted()
            {
                return true;
            }
        };
    }
}
