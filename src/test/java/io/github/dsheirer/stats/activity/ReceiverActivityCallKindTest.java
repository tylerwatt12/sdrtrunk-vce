/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Configured semantic kind owns accounting; allocation roles do not change a call's system. */
class ReceiverActivityCallKindTest
{
    private static final String CONFIGURATION_ID = "223e4567-e89b-42d3-a456-426614174000";
    private static final long START = 1_700_000_000_000L;

    @Test
    void standardTrunkedCallsAndTrafficChildrenBothReachLogicalAndOutputAccounting()
        throws Exception
    {
        for(TrunkedIdentityDomain domain : TrunkedIdentityDomain.values())
        {
            assertTrunkedAccounting(domain);
        }
    }

    private static void assertTrunkedAccounting(TrunkedIdentityDomain domain) throws Exception
    {
        ReceiverActivityMapper mapper = new ReceiverActivityMapper();
        try(Connection connection = database(domain))
        {
            for(boolean traffic : List.of(false, true))
            {
                CompletedAudioCall completed = call(domain, ChannelKind.TRUNKED, traffic);
                ReceiverActivityRecords.ResolvedLogicalCall logical = mapper.mapResolvedLogicalCall(completed);
                assertNotNull(logical);
                assertEquals(101, logical.sourceRadioId());
                assertEquals(91, logical.destinationId());
                assertNull(mapper.mapConventionalCallOutput(completed, ReceiverActivityRecords.CallOutput.RECORDED));
                assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection, logical));
                for(ReceiverActivityRecords.CallOutput output : ReceiverActivityRecords.CallOutput.values())
                {
                    assertTrue(ReceiverActivitySchema.applyLogicalCallOutput(connection,
                        new ReceiverActivityRecords.LogicalCallOutput(logical, output)));
                }
            }
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT SUM(logical_call_count), SUM(recorded_output_count), SUM(streamed_output_count)
                FROM trunked_logical_call_bucket
                """))
            {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
                assertEquals(2, rows.getInt(2));
                assertEquals(2, rows.getInt(3));
            }
        }
    }

    @Test
    void conventionalCallsKeepConventionalOutputWithoutInventingATrunkedSystem()
    {
        ReceiverActivityMapper mapper = new ReceiverActivityMapper();
        for(TrunkedIdentityDomain domain : TrunkedIdentityDomain.values())
        {
            CompletedAudioCall completed = call(domain, ChannelKind.CONVENTIONAL, false);
            assertNull(mapper.mapResolvedLogicalCall(completed));
            assertNotNull(mapper.mapConventionalCallOutput(completed, ReceiverActivityRecords.CallOutput.RECORDED));
        }
    }

    private static CompletedAudioCall call(TrunkedIdentityDomain domain, ChannelKind kind, boolean traffic)
    {
        DecoderType decoder = domain == TrunkedIdentityDomain.STANDARD ? DecoderType.DMR : DecoderType.NXDN;
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(ChannelConfigurationIdentifier.create(CONFIGURATION_ID));
        identifiers.update(DecoderTypeConfigurationIdentifier.create(decoder));
        identifiers.update(FrequencyConfigurationIdentifier.create(451_012_500L));
        if(decoder == DecoderType.DMR)
        {
            identifiers.update(DMRRadio.createFrom(101));
            identifiers.update(DMRTalkgroup.create(91));
        }
        else if(domain == TrunkedIdentityDomain.NXDN_TYPE_D)
        {
            identifiers.update(NXDNRadioIdentifier.createTypeDFrom(101));
            identifiers.update(NXDNTalkgroupIdentifier.createTypeDTo(91));
        }
        else
        {
            identifiers.update(NXDNRadioIdentifier.createFrom(101));
            identifiers.update(NXDNTalkgroupIdentifier.createTo(91));
        }
        AudioCallId id = new AudioCallId(9, traffic ? 2 : 1, 1);
        long timestamp = START + id.sequence();
        CallLegSource source = new CallLegSource(decoder, CONFIGURATION_ID, "Fixture", null, 0, null, domain,
            kind, traffic);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(id, null, null, identifiers, Set.of(), timestamp,
            timestamp + 100, 1, 1, timestamp, timestamp + 100, false, true, CallEncryptionState.CLEAR, true,
            null, VoiceCallQuality.EMPTY, CallLegId.from(id), source, null);
        return new CompletedAudioCall(snapshot, List.of());
    }

    private static Connection database(TrunkedIdentityDomain domain) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        SdrTrunkDatabaseSchema.create(connection);
        SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        String decoder = domain == TrunkedIdentityDomain.STANDARD ? "DMR" : "NXDN";
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, system_name, site_name,
                name, alias_list_id, auto_start, decoder_type, address_domain_code, primary_frequency_hz, config_json)
            VALUES (?, 'TRUNKED', 0, 'Fixture', 'Site', 'Control',
                (SELECT id FROM alias_list WHERE family=? LIMIT 1), 0, ?, ?, 451012500,
                '{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
            """))
        {
            statement.setString(1, CONFIGURATION_ID);
            statement.setString(2, decoder);
            statement.setString(3, decoder);
            statement.setInt(4, domain.ordinal());
            statement.executeUpdate();
        }
        return connection;
    }
}
