/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused contract tests for the current saved-channel and radio-system identity model.
 */
class RadioSystemIdentityModelTest
{
    private static final String P25_A = "11111111-1111-4111-8111-111111111111";
    private static final String P25_B = "22222222-2222-4222-8222-222222222222";
    private static final String P25_OTHER = "33333333-3333-4333-8333-333333333333";
    private static final String P25_UNRESOLVED = "44444444-4444-4444-8444-444444444444";
    private static final String DMR_A = "55555555-5555-4555-8555-555555555555";
    private static final String DMR_B = "66666666-6666-4666-8666-666666666666";
    private static final String NXDN_A = "77777777-7777-4777-8777-777777777777";
    private static final String NXDN_B = "88888888-8888-4888-8888-888888888888";
    private static final String CONVENTIONAL = "99999999-9999-4999-8999-999999999999";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void nativeP25IdentityIsSharedButTheSameTalkgroupOnAnotherSystemIsNot() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            insertChannel(connection, P25_B, "TRUNKED", "P25_PHASE1", 2, "P25 B", correlation(2));
            insertChannel(connection, P25_OTHER, "TRUNKED", "P25_PHASE1", 1, "P25 Other", correlation(3));

            //A native P25 identity can be observed through more than one saved channel.
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3A9, 4400, 1_000));
            record(connection, trunked(P25_B, "APCO25", 0xBEE00, 0x3A9, 4400, 2_000));
            record(connection, trunked(P25_OTHER, "APCO25", 0xBEE00, 0x3AA, 4400, 3_000));

            long shared = radioSystemId(connection, P25_A);
            assertEquals(shared, radioSystemId(connection, P25_B));
            assertNotEquals(shared, radioSystemId(connection, P25_OTHER));
            assertEquals("p25:bee00:3a9", systemKey(connection, shared));
            assertEquals(2, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=1 AND identity_id=4400
                """));

            long channelId = channelId(connection, P25_A);
            execute(connection, """
                UPDATE configuration_channel
                SET system_name='Renamed', site_name='Moved', name='New display name', alias_list_id=2,
                    radioresolve_id='%s'
                WHERE configuration_id='%s'
                """.formatted(correlation(9), P25_A));
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3A9, 4400, 4_000));
            assertEquals(channelId, channelId(connection, P25_A));
            assertEquals(shared, radioSystemId(connection, P25_A));
        }
    }

    @Test
    void interruptedP25BindingSaveRestoresOnlyThatChannelsVerifiedSiteBeforeAnotherSnapshot() throws Exception
    {
        Path database = mTemporaryFolder.resolve("interrupted-p25-binding.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationRepository repository = new ConfigurationRepository(database);
        Channel first = p25Channel("First P25 site", 851_012_500L);
        Channel second = p25Channel("Other P25 site", 852_012_500L);
        repository.replaceChannelAndBroadcastConfiguration(List.of(first, second), List.of());

        P25SiteIdentity verified = new P25SiteIdentity(0xBEE00, 0x3A9, 1, 1);
        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            ReceiverActivitySchema.insertSite(connection,
                p25Site(first.getConfigurationId(), 100, verified.wacn(), verified.system(), verified.rfss(),
                    verified.site()));
            ReceiverActivitySchema.validate(connection);
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE configuration_id='%s'
                  AND coalesce(json_type(config_json, '$.p25SiteIdentity'), 'null')='null'
                """.formatted(first.getConfigurationId())));
        }

        List<Channel> restored = repository.load().channels();
        Channel restoredFirst = restored.stream()
            .filter(channel -> first.getConfigurationId().equals(channel.getConfigurationId()))
            .findFirst().orElseThrow();
        Channel restoredSecond = restored.stream()
            .filter(channel -> second.getConfigurationId().equals(channel.getConfigurationId()))
            .findFirst().orElseThrow();
        assertEquals(verified, restoredFirst.getP25SiteIdentity());
        assertNull(restoredSecond.getP25SiteIdentity(),
            "a current site may only hydrate the saved channel with the same configuration UUID");
        assertFalse(restoredFirst.bindP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x3AA, 2, 3)),
            "the restored first verified site remains sticky after restart");

        //The next ordinary accepted save persists the restored value. Full runtime/startup validation succeeds
        //immediately, without waiting for another decoder snapshot to repair contradictory state.
        repository.replaceChannelAndBroadcastConfiguration(restored, List.of());
        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            assertEquals(verified.wacn(), scalar(connection, """
                SELECT json_extract(config_json, '$.p25SiteIdentity.wacn')
                FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(first.getConfigurationId())));
            assertEquals(verified.system(), scalar(connection, """
                SELECT json_extract(config_json, '$.p25SiteIdentity.system')
                FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(first.getConfigurationId())));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE configuration_id='%s'
                  AND coalesce(json_type(config_json, '$.p25SiteIdentity'), 'null')='null'
                """.formatted(second.getConfigurationId())));
            ReceiverActivitySchema.validate(connection);
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    @Test
    void nativeDmrTierThreeAndNxdnTypeCSystemsConvergeAcrossSavedChannels() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, DMR_A, "TRUNKED", "DMR", 1, "DMR A", correlation(4));
            insertChannel(connection, DMR_B, "TRUNKED", "DMR", 2, "DMR B", correlation(5));
            insertChannel(connection, NXDN_A, "TRUNKED", "NXDN", 1, "NXDN A", correlation(6));
            insertChannel(connection, NXDN_B, "TRUNKED", "NXDN", 2, "NXDN B", correlation(7));

            //Calls received before native system metadata is available are deliberately isolated by saved channel.
            record(connection, trunked(DMR_A, "DMR", null, null, 91, 1_000));
            record(connection, trunked(DMR_B, "DMR", null, null, 91, 2_000));
            record(connection, trunked(NXDN_A, "NXDN", null, null, 91, 3_000,
                TrunkedIdentityDomain.NXDN_TYPE_C));
            record(connection, trunked(NXDN_B, "NXDN", null, null, 91, 4_000,
                TrunkedIdentityDomain.NXDN_TYPE_C));
            assertEquals("dmr:channel:" + DMR_A, systemKey(connection, radioSystemId(connection, DMR_A)));
            assertEquals("nxdn-c:channel:" + NXDN_A, systemKey(connection, radioSystemId(connection, NXDN_A)));

            recordTrunkedSite(connection, dmrSite(DMR_A, 5_000, 1, 2, 42));
            recordTrunkedSite(connection, dmrSite(DMR_B, 6_000, 1, 2, 42));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 7_000, 1, 3, 303));
            recordTrunkedSite(connection, nxdnSite(NXDN_B, 8_000, 1, 3, 303));

            long dmrSystem = radioSystemId(connection, DMR_A);
            long nxdnSystem = radioSystemId(connection, NXDN_A);
            assertEquals(dmrSystem, radioSystemId(connection, DMR_B));
            assertEquals(nxdnSystem, radioSystemId(connection, NXDN_B));
            assertEquals("dmr:tier3:small:42", systemKey(connection, dmrSystem));
            assertEquals("nxdn-c:local:303", systemKey(connection, nxdnSystem));
            assertEquals(6, scalar(connection, "SELECT COUNT(*) FROM radio_system"));
            assertEquals(4, scalar(connection, """
                SELECT COUNT(*) FROM radio_system WHERE configuration_id IS NOT NULL
                """));
            assertEquals(8, scalar(connection, "SELECT COUNT(*) FROM radio_system_identity_summary"),
                "pre-identity fallback summaries remain historical and are never guessed into a native system");

            recordTrunkedSite(connection, dmrSite(DMR_A, 8_100, 1, null, null));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 8_200, 1, 0, null));
            assertEquals(dmrSystem, radioSystemId(connection, DMR_A));
            assertEquals(nxdnSystem, radioSystemId(connection, NXDN_A));
            assertEquals("dmr:tier3:small:42", systemKey(connection, dmrSystem));
            assertEquals("nxdn-c:local:303", systemKey(connection, nxdnSystem));
            assertEquals(42, scalar(connection, """
                SELECT observed_network_id FROM trunked_site_snapshot
                WHERE channel_id=(SELECT id FROM receiver_channel WHERE configuration_id='%s')
                """.formatted(DMR_A)));
            assertEquals(303, scalar(connection, """
                SELECT observed_system_id FROM trunked_site_snapshot
                WHERE channel_id=(SELECT id FROM receiver_channel WHERE configuration_id='%s')
                """.formatted(NXDN_A)));

            record(connection, trunked(DMR_A, "DMR", null, null, 91, 9_000));
            record(connection, trunked(DMR_B, "DMR", null, null, 91, 10_000));
            record(connection, trunked(NXDN_A, "NXDN", null, null, 91, 11_000,
                TrunkedIdentityDomain.NXDN_TYPE_C));
            record(connection, trunked(NXDN_B, "NXDN", null, null, 91, 12_000,
                TrunkedIdentityDomain.NXDN_TYPE_C));
            assertEquals(12, scalar(connection, "SELECT COUNT(*) FROM radio_system_identity_summary"),
                "native calls converge while retained channel-scoped history remains separate");
            assertEquals(16, scalar(connection,
                "SELECT SUM(grant_count) FROM radio_system_identity_summary"));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void incompleteSupportedNativeEvidenceStaysChannelScopedUntilTheTupleIsComplete() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, DMR_A, "TRUNKED", "DMR", 1, "DMR A", correlation(4));
            insertChannel(connection, NXDN_A, "TRUNKED", "NXDN", 1, "NXDN A", correlation(6));

            recordTrunkedSite(connection, dmrSite(DMR_A, 100, 1, 2, null));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 100, 1, 3, null));

            assertEquals("dmr:channel:" + DMR_A, systemKey(connection, radioSystemId(connection, DMR_A)));
            assertEquals("nxdn-c:channel:" + NXDN_A, systemKey(connection, radioSystemId(connection, NXDN_A)));
            ReceiverActivitySchema.validate(connection);

            recordTrunkedSite(connection, dmrSite(DMR_A, 200, 1, 2, 42));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 200, 1, 3, 303));
            assertEquals("dmr:tier3:small:42", systemKey(connection, radioSystemId(connection, DMR_A)));
            assertEquals("nxdn-c:local:303", systemKey(connection, radioSystemId(connection, NXDN_A)));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void newNativeSiteGenerationWaitsForCompleteIdentityWithoutReusingTheOldSystem() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, DMR_A, "TRUNKED", "DMR", 1, "DMR A", correlation(4));
            insertChannel(connection, NXDN_A, "TRUNKED", "NXDN", 1, "NXDN A", correlation(6));
            recordTrunkedSite(connection, dmrSite(DMR_A, 100, 1, 2, 42, 1));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 100, 1, 3, 303, 1));
            assertEquals("dmr:tier3:small:42", systemKey(connection, radioSystemId(connection, DMR_A)));
            assertEquals("nxdn-c:local:303", systemKey(connection, radioSystemId(connection, NXDN_A)));

            //A positively different site with only a partial native tuple is a new, unresolved generation.
            recordTrunkedSite(connection, dmrSite(DMR_A, 200, 1, null, null, 2));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 200, 1, 3, null, 2));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM receiver_channel
                WHERE configuration_id IN ('%s', '%s') AND radio_system_id IS NOT NULL
                """.formatted(DMR_A, NXDN_A)));
            assertEquals(2, scalar(connection, """
                SELECT COUNT(*) FROM receiver_channel
                WHERE configuration_id IN ('%s', '%s') AND radio_system_assigned_at_ms=200
                """.formatted(DMR_A, NXDN_A)));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));

            //A second partial observation must not reattach a synthetic fallback while this generation is unresolved.
            recordTrunkedSite(connection, dmrSite(DMR_A, 250, 1, null, null, 2));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 250, 1, 3, null, 2));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM receiver_channel
                WHERE configuration_id IN ('%s', '%s') AND radio_system_id IS NOT NULL
                """.formatted(DMR_A, NXDN_A)));
            assertEquals(2, scalar(connection, """
                SELECT COUNT(*) FROM receiver_channel
                WHERE configuration_id IN ('%s', '%s') AND radio_system_assigned_at_ms=250
                """.formatted(DMR_A, NXDN_A)));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM radio_system WHERE configuration_id IS NOT NULL"));

            //Generic signaling cannot recreate a fallback while a supported native generation is unresolved.
            record(connection, trunked(DMR_A, "DMR", null, null, 90, 260));
            record(connection, trunked(NXDN_A, "NXDN", null, null, 90, 260,
                TrunkedIdentityDomain.NXDN_TYPE_C));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM receiver_channel
                WHERE configuration_id IN ('%s', '%s') AND radio_system_id IS NOT NULL
                """.formatted(DMR_A, NXDN_A)));

            ReceiverActivityRecords.ActivityEvent queuedBeforePromotion =
                trunked(DMR_A, "DMR", null, null, 92, 275);

            //Delayed complete observations from the old generation cannot repopulate current site state.
            recordTrunkedSite(connection, dmrSite(DMR_A, 225, 1, 2, 42, 1));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 225, 1, 3, 303, 1));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));

            recordTrunkedSite(connection, dmrSite(DMR_A, 300, 1, 2, 43, 2));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 300, 1, 3, 304, 2));
            assertEquals("dmr:tier3:small:43", systemKey(connection, radioSystemId(connection, DMR_A)));
            assertEquals("nxdn-c:local:304", systemKey(connection, radioSystemId(connection, NXDN_A)));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));

            //A null-key signal captured before promotion cannot borrow the later native assignment.
            record(connection, queuedBeforePromotion);
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary WHERE identity_kind_code=1 AND identity_id=92
                """));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void completedCallsAndOutputsKeepTheirCapturedEventTimeSystem() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, DMR_A, "TRUNKED", "DMR", 1, "DMR A", correlation(4));
            insertChannel(connection, DMR_B, "TRUNKED", "DMR", 2, "DMR B", correlation(5));

            //The site becomes known after this call started but before its completion reaches SQLite.
            recordTrunkedSite(connection, dmrSite(DMR_A, 200, 1, 2, 42));
            ReceiverActivityRecords.ResolvedLogicalCall early = dmrCall(DMR_A, 1, 100,
                "dmr:channel:" + DMR_A);
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection, early));
            assertEquals("dmr:tier3:small:42", systemKey(connection, radioSystemId(connection, DMR_A)));
            assertEquals(1, scalar(connection, """
                SELECT logical_call_count
                FROM trunked_logical_call_bucket bucket
                JOIN radio_system system ON system.id=bucket.radio_system_id
                WHERE system.system_key='dmr:channel:%s'
                """.formatted(DMR_A)));
            assertTrue(ReceiverActivitySchema.applyLogicalCallOutput(connection,
                new ReceiverActivityRecords.LogicalCallOutput(early, ReceiverActivityRecords.CallOutput.RECORDED)));
            assertEquals(1, scalar(connection, """
                SELECT recorded_output_count
                FROM trunked_logical_call_bucket bucket
                JOIN radio_system system ON system.id=bucket.radio_system_id
                WHERE system.system_key='dmr:channel:%s'
                """.formatted(DMR_A)));
            assertTrue(ReceiverActivitySchema.applyTrunkedCallAttribution(connection,
                attribution(DMR_A, 100, 1002, "dmr:channel:" + DMR_A)));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*)
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='dmr:channel:%s'
                  AND identity.identity_kind_code=2 AND identity.identity_id=1002
                """.formatted(DMR_A)));

            //A second saved channel can resolve a captured native key even when no site snapshot reached SQLite.
            ReceiverActivityRecords.ResolvedLogicalCall nativeKnown = dmrCall(DMR_B, 2, 250,
                "dmr:tier3:small:42");
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection, nativeKnown));
            assertEquals(radioSystemId(connection, DMR_A), radioSystemId(connection, DMR_B));

            //A delayed call from the prior native system remains historical after the receiver changes systems.
            recordTrunkedSite(connection, dmrSite(DMR_A, 400, 1, 2, 43));
            ReceiverActivityRecords.ResolvedLogicalCall delayed = dmrCall(DMR_A, 3, 300,
                "dmr:tier3:small:42");
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection, delayed));
            assertEquals("dmr:tier3:small:43", systemKey(connection, radioSystemId(connection, DMR_A)));
            assertTrue(ReceiverActivitySchema.applyTrunkedCallAttribution(connection,
                attribution(DMR_A, 300, 1003, "dmr:tier3:small:42")));
            assertEquals("dmr:tier3:small:43", systemKey(connection, radioSystemId(connection, DMR_A)));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*)
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='dmr:tier3:small:42'
                  AND identity.identity_kind_code=2 AND identity.identity_id=1003
                """));
            assertTrue(ReceiverActivitySchema.applyLogicalCallOutput(connection,
                new ReceiverActivityRecords.LogicalCallOutput(delayed, ReceiverActivityRecords.CallOutput.STREAMED)));
            assertEquals(1, scalar(connection, """
                SELECT streamed_output_count
                FROM trunked_logical_call_bucket bucket
                JOIN radio_system system ON system.id=bucket.radio_system_id
                WHERE system.system_key='dmr:tier3:small:42'
                """));

            ReceiverActivityRecords.TalkerAliasUpdate delayedAlias =
                new ReceiverActivityRecords.TalkerAliasUpdate(500, 300, DMR_A, "DMR", null, null, 1003,
                    ReceiverActivityRecords.P25Identity.UNKNOWN, "OLD SYSTEM UNIT",
                    TrunkedIdentityDomain.STANDARD, "dmr:tier3:small:42");
            ReceiverActivitySchema.updateTalkerAlias(connection, delayedAlias);
            assertEquals("OLD SYSTEM UNIT", text(connection, """
                SELECT identity.last_talker_alias
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='dmr:tier3:small:42'
                  AND identity.identity_kind_code=2 AND identity.identity_id=1003
                """));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*)
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='dmr:tier3:small:43'
                  AND identity.identity_kind_code=2 AND identity.identity_id=1003
                """), "a delayed talker alias must not move to the receiver's newer system");
            assertEquals(500, scalar(connection, """
                SELECT identity.last_talker_alias_seen_ms
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='dmr:tier3:small:42'
                  AND identity.identity_kind_code=2 AND identity.identity_id=1003
                """));

            //NXDN uses the same split: completion time updates alias freshness, while call start protects the newer
            //receiver assignment from a late alias owned by the previous system.
            insertChannel(connection, NXDN_A, "TRUNKED", "NXDN", 1, "NXDN A", correlation(6));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 1_000, 1, 3, 303));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 1_300, 1, 3, 304));
            ReceiverActivitySchema.updateTalkerAlias(connection,
                new ReceiverActivityRecords.TalkerAliasUpdate(1_400, 1_200, NXDN_A, "NXDN", null, null, 2003,
                    ReceiverActivityRecords.P25Identity.UNKNOWN, "NXDN OLD SYSTEM UNIT",
                    TrunkedIdentityDomain.NXDN_TYPE_C, "nxdn-c:local:303"));
            assertEquals("nxdn-c:local:304", systemKey(connection, radioSystemId(connection, NXDN_A)));
            assertEquals("NXDN OLD SYSTEM UNIT", text(connection, """
                SELECT identity.last_talker_alias
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='nxdn-c:local:303'
                  AND identity.identity_kind_code=2 AND identity.identity_id=2003
                """));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*)
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='nxdn-c:local:304'
                  AND identity.identity_kind_code=2 AND identity.identity_id=2003
                """));

            //A delayed P25 completion uses its frozen key after the receiver changes systems. The key is enough to
            //recover the original WACN/System tuple without borrowing the receiver's newer assignment.
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3A9, 91, 500));
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3AA, 91, 800));
            assertEquals("p25:bee00:3aa", systemKey(connection, radioSystemId(connection, P25_A)));
            ReceiverActivityRecords.ResolvedLogicalCall p25Delayed = p25Call(P25_A, 4, 600,
                null, null, "p25:bee00:3a9");
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection, p25Delayed));
            assertEquals("p25:bee00:3aa", systemKey(connection, radioSystemId(connection, P25_A)));
            assertEquals(1, scalar(connection, """
                SELECT identity.logical_call_count
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='p25:bee00:3a9'
                  AND identity.identity_kind_code=1 AND identity.identity_id=91
                """));
            ReceiverActivitySchema.updateTalkerAlias(connection,
                new ReceiverActivityRecords.TalkerAliasUpdate(900, 600, P25_A, "APCO25", 0xBEE00, 0x3A9,
                    1004, ReceiverActivityRecords.P25Identity.ORDINARY, "P25 OLD SYSTEM UNIT",
                    TrunkedIdentityDomain.STANDARD, "p25:bee00:3a9"));
            assertEquals("p25:bee00:3aa", systemKey(connection, radioSystemId(connection, P25_A)));
            assertEquals("P25 OLD SYSTEM UNIT", text(connection, """
                SELECT identity.last_talker_alias
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='p25:bee00:3a9'
                  AND identity.identity_kind_code=2 AND identity.identity_id=1004
                """));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*)
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='p25:bee00:3aa'
                  AND identity.identity_kind_code=2 AND identity.identity_id=1004
                """));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void equalTimestampCannotReplaceFallbackOrNativeSystemIdentity() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, DMR_A, "TRUNKED", "DMR", 1, "DMR A", correlation(4));

            recordTrunkedSite(connection, dmrSite(DMR_A, 100, 5, null, null, 1));
            long fallback = radioSystemId(connection, DMR_A);
            assertEquals("dmr:channel:" + DMR_A, systemKey(connection, fallback));

            //Fallback -> native at the same instant is contradictory, not an enrichment.
            recordTrunkedSite(connection, dmrSite(DMR_A, 100, 1, 2, 42, 1));
            assertEquals(fallback, radioSystemId(connection, DMR_A));
            assertEquals(5, scalar(connection, "SELECT variant_code FROM trunked_site_snapshot"));

            recordTrunkedSite(connection, dmrSite(DMR_A, 200, 1, 2, 42, 1));
            long nativeSystem = radioSystemId(connection, DMR_A);
            assertEquals("dmr:tier3:small:42", systemKey(connection, nativeSystem));

            //Native -> different native and native -> fallback are likewise rejected at the same instant.
            recordTrunkedSite(connection, dmrSite(DMR_A, 200, 1, 2, 43, 1));
            recordTrunkedSite(connection, dmrSite(DMR_A, 200, 5, null, null, 1));
            recordTrunkedSite(connection, dmrSite(DMR_A, 200, 1, null, null, 1));
            recordTrunkedSite(connection, dmrSite(DMR_A, 200, 1, 2, 42, 2));
            assertEquals(nativeSystem, radioSystemId(connection, DMR_A));
            assertEquals(42, scalar(connection, "SELECT observed_network_id FROM trunked_site_snapshot"));
            assertEquals(2, scalar(connection, "SELECT observed_model_code FROM trunked_site_snapshot"));
            assertEquals(1, scalar(connection, "SELECT observed_site_id FROM trunked_site_snapshot"));
            assertEquals(1, scalar(connection, "SELECT variant_code FROM trunked_site_snapshot"));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void unprovenDmrVariantsAndNxdnTypeDStayChannelScopedWhileNativeDimensionsRemainDistinct()
        throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, DMR_A, "TRUNKED", "DMR", 1, "DMR A", correlation(4));
            insertChannel(connection, DMR_B, "TRUNKED", "DMR", 2, "DMR B", correlation(5));
            insertChannel(connection, NXDN_A, "TRUNKED", "NXDN", 1, "NXDN A", correlation(6));
            insertChannel(connection, NXDN_B, "TRUNKED", "NXDN", 2, "NXDN B", correlation(7));

            recordTrunkedSite(connection, dmrSite(DMR_A, 1_000, 1, 2, 5));
            recordTrunkedSite(connection, dmrSite(DMR_B, 2_000, 1, 3, 5));
            recordTrunkedSite(connection, nxdnSite(NXDN_A, 3_000, 1, 1, 303));
            execute(connection, "UPDATE configuration_channel SET address_domain_code=2 " +
                "WHERE configuration_id='" + NXDN_B + "'");
            recordTrunkedSite(connection, nxdnSite(NXDN_B, 4_000, 2, 4, 303));

            assertEquals("dmr:tier3:small:5", systemKey(connection, radioSystemId(connection, DMR_A)));
            assertEquals("dmr:tier3:large:5", systemKey(connection, radioSystemId(connection, DMR_B)));
            assertEquals("nxdn-c:global:303", systemKey(connection, radioSystemId(connection, NXDN_A)));
            assertEquals("nxdn-d:channel:" + NXDN_B,
                systemKey(connection, radioSystemId(connection, NXDN_B)));

            //The supported Capacity Plus/Connect Plus/Capacity Max/Hytera and unknown codes do not infer a system.
            recordTrunkedSite(connection, dmrSite(DMR_A, 5_000, 5, 2, 5));
            assertEquals("dmr:channel:" + DMR_A, systemKey(connection, radioSystemId(connection, DMR_A)));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void decodedNxdnDomainCannotReclassifyTheSavedChannel() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, NXDN_A, "TRUNKED", "NXDN", 1, "NXDN A", correlation(6));
            record(connection, trunked(NXDN_A, "NXDN", null, null, 91, 1_000,
                TrunkedIdentityDomain.NXDN_TYPE_C));
            long radioSystemId = radioSystemId(connection, NXDN_A);

            record(connection, trunked(NXDN_A, "NXDN", null, null, 92, 2_000,
                TrunkedIdentityDomain.NXDN_TYPE_D));

            assertEquals(1, scalar(connection,
                "SELECT address_domain_code FROM radio_system WHERE id=" + radioSystemId));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE radio_system_id=%d AND identity_id=91
                """.formatted(radioSystemId)));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE radio_system_id=%d AND identity_id=92
                """.formatted(radioSystemId)));
        }
    }

    @Test
    void p25FullyQualifiedAndPatchMemberIdentitiesKeepTheirNativeMeaning() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivityRecords.P25Identity zeroLocal =
                ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(0xABCDE, 0x321, 1_200);
            record(connection, p25Activity(P25_A, 1_000, "0", "TALKGROUP", List.of(), zeroLocal,
                List.of(), null));
            record(connection, p25Activity(P25_A, 2_000, "500", "PATCH_GROUP", List.of(501, 502),
                ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(0xABCDE, 0x323, 1_300), List.of(
                    new ReceiverActivityRecords.P25PatchMemberIdentity(502,
                        ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(0xABCDE, 0x322, 1_202))),
                null));

            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=1 AND home_wacn=0xABCDE AND home_system_id=0x321
                  AND identity_id=1200
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=3 AND home_wacn=0xABCDE AND home_system_id=0x323
                  AND identity_id=1300
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=1 AND home_wacn=0xBEE00 AND home_system_id=0x3A9
                  AND identity_id=501
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=1 AND home_wacn=0xABCDE AND home_system_id=0x322
                  AND identity_id=1202
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM receiver_activity_event
                WHERE target_observed_local_id=0
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM activity_event_identity_member
                WHERE observed_local_id=502
                """));
        }
    }

    @Test
    void p25PrivateDestinationPersistsCanonicalHomeAndEventLocalEvidence() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            record(connection, p25Activity(P25_A, 1_000, "123", "RADIO", List.of(),
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x321, 9_001),
                List.of(), null));

            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=2 AND home_wacn=0xABCDE AND home_system_id=0x321
                  AND identity_id=9001
                """));
            assertEquals(2, scalar(connection, """
                SELECT target_identity_kind_code FROM receiver_activity_event_resolved
                WHERE target_observed_local_id=123
                """));
            assertEquals("v1-r-abcde-321-9001", text(connection, """
                SELECT target_identity_key FROM receiver_activity_event_resolved
                WHERE target_observed_local_id=123
                """));
        }
    }

    @Test
    void roamingP25RadioKeepsItsPermanentIdentityAndHighWorkingAddress() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivityRecords.P25Identity roamingRadio =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00, 0x954, 831_102);

            record(connection, p25Presence(P25_A, 1_000, 0xFFFD26, roamingRadio, false));

            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=2 AND home_wacn=0xBEE00 AND home_system_id=0x954
                  AND identity_id=831102
                """));
            assertEquals(0xFFFD26, scalar(connection,
                "SELECT observed_local_id FROM trunked_radio_channel_presence"));
            assertEquals(0xFFFD26, scalar(connection, """
                SELECT source_observed_local_id FROM receiver_activity_event
                WHERE source_identity_summary_id IS NOT NULL
                """));

            record(connection, p25Presence(P25_A, 1_100, 0xFFFD26, roamingRadio, true));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));
            assertEquals(0xFFFD26, scalar(connection,
                "SELECT observed_local_id FROM trunked_radio_channel_presence_clear"));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void aRegistrationWithoutAGroupUpdatesRadioPresenceButNotAffiliation() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivityRecords.P25Identity roamingRadio =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00, 0x954, 831_102);
            ReceiverActivityRecords.RadioPresenceUpdate presence =
                ReceiverActivityRecords.RadioPresenceUpdate.confirmed(0xFFFD26, null,
                    ReceiverActivityRecords.RadioPresenceEvidence.AFFILIATION, roamingRadio,
                    ReceiverActivityRecords.P25Identity.UNKNOWN);

            record(connection, p25Activity(P25_A, 1_000, null, null, List.of(),
                ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(), presence));

            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_affiliation"));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM receiver_activity_event
                WHERE target_observed_local_id IS NOT NULL OR target_kind_code IS NOT NULL
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=2 AND home_wacn=0xBEE00 AND home_system_id=0x954
                  AND identity_id=831102
                """));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void authoritativeRadioPresenceUsesTimestampEvidenceAndClearWatermarks() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            record(connection, p25Activity(P25_A, 1_000, "91", "TALKGROUP", List.of(),
                ReceiverActivityRecords.P25Identity.ORDINARY, List.of(),
                ReceiverActivityRecords.RadioPresenceUpdate.confirmed(1_001, 91,
                    ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION)));
            record(connection, p25Activity(P25_A, 900, "92", "TALKGROUP", List.of(),
                ReceiverActivityRecords.P25Identity.ORDINARY, List.of(),
                ReceiverActivityRecords.RadioPresenceUpdate.confirmed(1_001, 92,
                    ReceiverActivityRecords.RadioPresenceEvidence.AFFILIATION)));

            assertEquals(91, scalar(connection,
                "SELECT talkgroup_observed_local_id FROM trunked_radio_affiliation"));
            assertEquals(1_000, scalar(connection,
                "SELECT confirmed_at_ms FROM trunked_radio_channel_presence"));

            record(connection, p25Activity(P25_A, 1_100, "91", "TALKGROUP", List.of(),
                ReceiverActivityRecords.P25Identity.ORDINARY, List.of(),
                ReceiverActivityRecords.RadioPresenceUpdate.cleared(1_001)));
            record(connection, p25Activity(P25_A, 1_100, "92", "TALKGROUP", List.of(),
                ReceiverActivityRecords.P25Identity.ORDINARY, List.of(),
                ReceiverActivityRecords.RadioPresenceUpdate.confirmed(1_001, 92,
                    ReceiverActivityRecords.RadioPresenceEvidence.AFFILIATION)));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_affiliation"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));

            record(connection, p25Activity(P25_A, 1_200, "93", "TALKGROUP", List.of(),
                ReceiverActivityRecords.P25Identity.ORDINARY, List.of(),
                ReceiverActivityRecords.RadioPresenceUpdate.confirmed(1_001, 93,
                    ReceiverActivityRecords.RadioPresenceEvidence.AFFILIATION)));
            record(connection, new ReceiverActivityRecords.ActivityEvent(1_300, P25_A,
                ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25",
                ReceiverActivityRecords.Action.CALL, "CALL_GROUP", "1002", "93", "TALKGROUP", List.of(),
                851_012_500L, "0-1", 1, false, null, null, 0xBEE00, 0x3A9, null, null, null, null,
                false, null, null, TrunkedIdentityDomain.STANDARD,
                ReceiverActivityRecords.P25Identity.ORDINARY,
                ReceiverActivityRecords.P25Identity.ORDINARY, List.of(), null));

            assertEquals(93, scalar(connection,
                "SELECT talkgroup_observed_local_id FROM trunked_radio_affiliation"));
            assertEquals(1_200, scalar(connection,
                "SELECT confirmed_at_ms FROM trunked_radio_channel_presence"));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM trunked_radio_channel_presence WHERE observed_local_id=1002
                """));
        }
    }

    @Test
    void fullyQualifiedCurrentPresenceSupersedesButIsNotDowngradedByTheSamePositiveLocalAlias()
        throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivityRecords.P25Identity roamingRadio =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x321, 9_001);

            record(connection, p25Presence(P25_A, 1_000, 123,
                ReceiverActivityRecords.P25Identity.ORDINARY, false));
            record(connection, p25Presence(P25_A, 1_100, 123, roamingRadio, false));
            record(connection, p25Presence(P25_A, 1_200, 123,
                ReceiverActivityRecords.P25Identity.ORDINARY, false));

            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));
            assertEquals(9_001, scalar(connection, """
                SELECT identity.identity_id
                FROM trunked_radio_channel_presence presence
                JOIN radio_system_identity_summary identity ON identity.id=presence.radio_identity_id
                """));
            assertEquals(0xABCDE, scalar(connection, """
                SELECT identity.home_wacn
                FROM trunked_radio_channel_presence presence
                JOIN radio_system_identity_summary identity ON identity.id=presence.radio_identity_id
                """));
            assertEquals(1_200, scalar(connection,
                "SELECT confirmed_at_ms FROM trunked_radio_channel_presence"));

            record(connection, p25Presence(P25_A, 1_300, 123,
                ReceiverActivityRecords.P25Identity.ORDINARY, true));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_affiliation"));

            record(connection, p25Presence(P25_A, 1_400, 124, roamingRadio, false));
            record(connection, p25Presence(P25_A, 1_500, 124,
                ReceiverActivityRecords.P25Identity.ORDINARY, false));
            assertEquals(9_001, scalar(connection, """
                SELECT identity.identity_id
                FROM trunked_radio_channel_presence presence
                JOIN radio_system_identity_summary identity ON identity.id=presence.radio_identity_id
                WHERE presence.observed_local_id=124
                """));
        }
    }

    @Test
    void fullyQualifiedCurrentPresenceUsesObservationTimeThenCanonicalTupleForReplacement() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivityRecords.P25Identity lower =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x321, 9_001);
            ReceiverActivityRecords.P25Identity higher =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x322, 9_002);

            record(connection, p25Presence(P25_A, 1_200, 123, higher, false));
            record(connection, p25Presence(P25_A, 1_100, 123, lower, false));
            assertEquals(9_002, scalar(connection, """
                SELECT identity.identity_id
                FROM trunked_radio_channel_presence presence
                JOIN radio_system_identity_summary identity ON identity.id=presence.radio_identity_id
                """));

            record(connection, p25Presence(P25_A, 1_300, 123, lower, false));
            assertEquals(9_001, scalar(connection, """
                SELECT identity.identity_id
                FROM trunked_radio_channel_presence presence
                JOIN radio_system_identity_summary identity ON identity.id=presence.radio_identity_id
                """));

            execute(connection, "DELETE FROM trunked_radio_affiliation");
            execute(connection, "DELETE FROM trunked_radio_channel_presence");
            record(connection, p25Presence(P25_A, 1_400, 124, higher, false));
            record(connection, p25Presence(P25_A, 1_400, 124, lower, false));
            assertEquals(9_001, scalar(connection, """
                SELECT identity.identity_id
                FROM trunked_radio_channel_presence presence
                JOIN radio_system_identity_summary identity ON identity.id=presence.radio_identity_id
                """));

            execute(connection, "DELETE FROM trunked_radio_affiliation");
            execute(connection, "DELETE FROM trunked_radio_channel_presence");
            record(connection, p25Presence(P25_A, 1_500, 125, lower, false));
            record(connection, p25Presence(P25_A, 1_500, 125, higher, false));
            assertEquals(9_001, scalar(connection, """
                SELECT identity.identity_id
                FROM trunked_radio_channel_presence presence
                JOIN radio_system_identity_summary identity ON identity.id=presence.radio_identity_id
                """));
        }
    }

    @Test
    void positiveLocalAliasClearIsScopedToTheObservingChannel() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            insertChannel(connection, P25_B, "TRUNKED", "P25_PHASE1", 2, "P25 B", correlation(2));
            ReceiverActivityRecords.P25Identity radioA =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x321, 9_001);
            ReceiverActivityRecords.P25Identity radioB =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x322, 9_002);

            record(connection, p25Presence(P25_A, 1_000, 123, radioA, false));
            record(connection, p25Presence(P25_A, 1_200, 123,
                ReceiverActivityRecords.P25Identity.ORDINARY, true));

            //This older observation arrived later, but a clear on another receiver channel cannot suppress it.
            record(connection, p25Presence(P25_B, 1_100, 123, radioB, false));

            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));
            assertEquals(9_002, scalar(connection, """
                SELECT identity.identity_id
                FROM trunked_radio_channel_presence presence
                JOIN receiver_channel channel ON channel.id=presence.channel_id
                JOIN radio_system_identity_summary identity ON identity.id=presence.radio_identity_id
                WHERE channel.configuration_id='%s' AND presence.observed_local_id=123
                """.formatted(P25_B)));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*)
                FROM trunked_radio_channel_presence presence
                JOIN receiver_channel channel ON channel.id=presence.channel_id
                WHERE channel.configuration_id='%s'
                """.formatted(P25_A)));
            assertEquals(2, scalar(connection,
                "SELECT COUNT(*) FROM trunked_radio_channel_presence_clear"));
        }
    }

    @Test
    void clearByPositiveLocalAliasStillRemovesCurrentStateWhenTheNewCanonicalIdentityIsAtCapacity()
        throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            record(connection, p25Presence(P25_A, 1_000, 123,
                ReceiverActivityRecords.P25Identity.ORDINARY, false));
            long radioSystemId = radioSystemId(connection, P25_A);
            execute(connection, """
                WITH digits(value) AS (
                    VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)
                ), identities(identity_id) AS (
                    SELECT 10000 + a.value * 10000 + b.value * 1000 + c.value * 100 + d.value * 10 + e.value
                    FROM digits a, digits b, digits c, digits d, digits e
                    LIMIT 99998
                )
                INSERT INTO radio_system_identity_summary(
                    radio_system_id, identity_kind_code, home_wacn, home_system_id,
                    identity_id, first_seen_ms, last_seen_ms)
                SELECT %d, 2, 0xBEE00, 0x3A9, identity_id, 1000, 1000 FROM identities
                """.formatted(radioSystemId));
            assertEquals(RadioSystemSchema.MAX_IDENTITIES_PER_SYSTEM, scalar(connection,
                "SELECT COUNT(*) FROM radio_system_identity_summary WHERE radio_system_id=" + radioSystemId));

            ReceiverActivityRecords.P25Identity unseen =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x321, 9_001);
            record(connection, p25Presence(P25_A, 1_100, 123, unseen, true));

            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_affiliation"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence_clear"));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE home_wacn=0xABCDE AND home_system_id=0x321 AND identity_id=9001
                """));
        }
    }

    @Test
    void conventionalActivityUsesOnlyTheSavedConfigurationIdentity() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, CONVENTIONAL, "CONVENTIONAL", "NBFM", 1, "Fire Dispatch", null);
            record(connection, conventional(CONVENTIONAL, 155_730_000L, 1_000));
            long channelId = channelId(connection, CONVENTIONAL);

            execute(connection, """
                UPDATE configuration_channel
                SET system_name='Renamed', site_name='New site', name='New channel label', alias_list_id=2,
                    radioresolve_id='%s'
                WHERE configuration_id='%s'
                """.formatted(correlation(8), CONVENTIONAL));
            record(connection, conventional(CONVENTIONAL, 155_730_000L, 2_000));

            assertEquals(channelId, channelId(connection, CONVENTIONAL));
            assertEquals(CONVENTIONAL, text(connection,
                "SELECT configuration_id FROM receiver_channel WHERE id=" + channelId));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM receiver_channel WHERE id=" + channelId + " AND radio_system_id IS NOT NULL"));
            assertEquals(2, scalar(connection, """
                SELECT call_count FROM conventional_activity_summary
                WHERE channel_id=%d AND frequency_hz=155730000
                """.formatted(channelId)));
        }
    }

    @Test
    void unresolvedP25KeepsDetailWithoutCreatingAProvisionalSystemThenPromotesToNative() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_UNRESOLVED, "TRUNKED", "P25_PHASE1", 1, "P25", correlation(4));
            record(connection, trunked(P25_UNRESOLVED, "APCO25", null, null, 1201, 1_000));
            long channelId = channelId(connection, P25_UNRESOLVED);
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM radio_system"));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM receiver_activity_event WHERE radio_system_id IS NULL
                """));
            execute(connection, """
                INSERT INTO p25_site_snapshot(channel_id, snapshot_hash, first_seen_ms, last_seen_ms, protocol)
                VALUES (%d, '%s', 1000, 1000, 'APCO25')
                """.formatted(channelId, "a".repeat(64)));
            execute(connection, """
                INSERT INTO p25_site_channel(channel_id, channel_key, downlink_hz, confirmed_at_ms)
                VALUES (%d, '0-1', 851012500, 1000)
                """.formatted(channelId));
            ReceiverActivitySchema.insertControlChannelQuality(connection,
                new ReceiverActivityRecords.ControlChannelQuality(1_500, P25_UNRESOLVED, "APCO25",
                    TrunkedIdentityDomain.STANDARD, 851_012_500,
                    -70.0, -71.0, -75.0, -68.0, 95.0, 100, 2, 3, 4, 5, 1_400));

            record(connection, trunked(P25_UNRESOLVED, "APCO25", 0xBEE00, 0x321, 1201, 2_000));

            assertEquals("p25:bee00:321",
                systemKey(connection, radioSystemId(connection, P25_UNRESOLVED)));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(1, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_channel WHERE channel_id=" + channelId));
            assertEquals(1, scalar(connection,
                "SELECT COUNT(*) FROM trunked_control_channel_quality WHERE channel_id=" + channelId));
        }
    }

    @Test
    void completeP25SiteBindingRejectsLaterConflictingPartialAndCompleteSnapshots()
        throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 100, 0xBEE00, 0x3A9, 1, 1));
            long channelId = channelId(connection, P25_A);
            assertEquals(1, scalar(connection,
                "SELECT site FROM p25_site_snapshot WHERE channel_id=" + channelId));

            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 200, null, 0x3AA, 1, 2));
            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 250, null, 0x3AA, 1, 2));
            assertEquals("p25:bee00:3a9", systemKey(connection, radioSystemId(connection, P25_A)));
            assertEquals(100, scalar(connection,
                "SELECT radio_system_assigned_at_ms FROM receiver_channel WHERE id=" + channelId));

            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 225, 0xBEE00, 0x3A9, 1, 1));
            assertEquals(1, scalar(connection,
                "SELECT site FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(225, scalar(connection,
                "SELECT last_seen_ms FROM p25_site_snapshot WHERE channel_id=" + channelId));

            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 300, 0xBEE00, 0x3AA, 1, 2));
            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 400, 0xBEE01, 0x3A9, 1, 1));
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3AA, 92, 500));
            assertEquals(1, scalar(connection,
                "SELECT site FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(225, scalar(connection,
                "SELECT last_seen_ms FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals("p25:bee00:3a9", systemKey(connection, radioSystemId(connection, P25_A)));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*)
                FROM radio_system_identity_summary identity
                JOIN radio_system system ON system.id=identity.radio_system_id
                WHERE system.system_key='p25:bee00:3aa'
                  AND identity.identity_kind_code=1 AND identity.identity_id=92
                """), "a complete late observation can remain historical without moving the saved channel");
        }
    }

    @Test
    void completeP25IdentityRequiresTheDecodedSourceToBeAnAdvertisedControl() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));

            ReceiverActivitySchema.insertSite(connection,
                p25Site(P25_A, 100, 0xBEE00, 0x3A9, 1, 1, 852_012_500L, 851_012_500L));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM receiver_channel"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM radio_system"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_snapshot"));

            ReceiverActivitySchema.insertSite(connection,
                p25Site(P25_A, 200, 0xBEE00, 0x3AA, 2, 3, 852_012_500L, 852_012_500L));
            assertEquals("p25:bee00:3aa", systemKey(connection, radioSystemId(connection, P25_A)));
            assertEquals(3, scalar(connection, "SELECT site FROM p25_site_snapshot"));
        }
    }

    @Test
    void partialP25UpdateCannotEraseACompleteBindingOrMakeAnotherSiteTakeIt() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 100, 0xBEE00, 0x3A9, 1, 1));
            long channelId = channelId(connection, P25_A);

            ReceiverActivitySchema.insertSite(connection,
                p25Site(P25_A, 200, 0xBEE00, 0x3A9, null, null));
            assertEquals(1, scalar(connection,
                "SELECT rfss FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(1, scalar(connection,
                "SELECT site FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(200, scalar(connection,
                "SELECT last_seen_ms FROM p25_site_snapshot WHERE channel_id=" + channelId));

            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 300, 0xBEE00, 0x3AA, 2, 2));
            assertEquals("p25:bee00:3a9", systemKey(connection, radioSystemId(connection, P25_A)));
            assertEquals(1, scalar(connection,
                "SELECT site FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(200, scalar(connection,
                "SELECT last_seen_ms FROM p25_site_snapshot WHERE channel_id=" + channelId));
        }
    }

    @Test
    void missingSiteComponentsCanBeEnrichedWithoutStartingANewGeneration() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivitySchema.insertSite(connection,
                p25Site(P25_A, 100, 0xBEE00, 0x3A9, null, null));
            long channelId = channelId(connection, P25_A);
            long radioSystemId = radioSystemId(connection, P25_A);

            ReceiverActivitySchema.insertSite(connection,
                p25Site(P25_A, 200, 0xBEE00, 0x3A9, 1, 2));

            assertEquals(radioSystemId, radioSystemId(connection, P25_A));
            assertEquals(100, scalar(connection,
                "SELECT radio_system_assigned_at_ms FROM receiver_channel WHERE id=" + channelId));
            assertEquals(100, scalar(connection,
                "SELECT first_seen_ms FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(2, scalar(connection,
                "SELECT site FROM p25_site_snapshot WHERE channel_id=" + channelId));
        }
    }

    @Test
    void persistedP25BindingRejectsAContradictoryFirstActivitySnapshot() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            execute(connection, """
                UPDATE configuration_channel
                SET config_json='{"p25SiteIdentity":{"wacn":781824,"system":937,"rfss":1,"site":1}}'
                WHERE configuration_id='%s'
                """.formatted(P25_A));

            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 100, 0xBEE00, 0x3AA, 1, 2));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM receiver_channel"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_snapshot"));

            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 200, 0xBEE00, 0x3A9, 1, 1));
            assertEquals("p25:bee00:3a9", systemKey(connection, radioSystemId(connection, P25_A)));
            assertEquals(1, scalar(connection, "SELECT site FROM p25_site_snapshot"));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void changedSavedP25BindingWaitsForItsVerifiedSiteThenReplacesTheLearnedBinding() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 100, 0xBEE00, 0x3A9, 1, 1));
            long channelId = channelId(connection, P25_A);
            record(connection, p25Presence(P25_A, 125, 123,
                ReceiverActivityRecords.P25Identity.ORDINARY, false));

            execute(connection, """
                UPDATE configuration_channel
                SET config_json='{"p25SiteIdentity":{"wacn":781824,"system":938,"rfss":1,"site":2}}'
                WHERE configuration_id='%s'
                """.formatted(P25_A));
            assertThrows(SQLException.class, () -> ReceiverActivitySchema.validate(connection));

            //A call for the newly configured system may be counted historically, but it cannot move the receiver.
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3AA, 92, 150));
            assertEquals("p25:bee00:3a9", systemKey(connection, radioSystemId(connection, P25_A)));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));

            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 200, 0xBEE00, 0x3AA, 1, 2));
            assertEquals("p25:bee00:3aa", systemKey(connection, radioSystemId(connection, P25_A)));
            assertEquals(2, scalar(connection,
                "SELECT site FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));
            ReceiverActivitySchema.validate(connection);

            execute(connection, """
                UPDATE configuration_channel SET config_json='{}' WHERE configuration_id='%s'
                """.formatted(P25_A));
            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 300, 0xBEE00, 0x3A9, 1, 1));
            assertEquals("p25:bee00:3aa", systemKey(connection, radioSystemId(connection, P25_A)));
            assertEquals(2, scalar(connection,
                "SELECT site FROM p25_site_snapshot WHERE channel_id=" + channelId));
        }
    }

    @Test
    void changedSavedP25SiteWithinTheSameSystemClearsTheOldSiteState() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 100, 0xBEE00, 0x3A9, 1, 1));
            long channelId = channelId(connection, P25_A);
            long radioSystemId = radioSystemId(connection, P25_A);
            record(connection, p25Presence(P25_A, 125, 123,
                ReceiverActivityRecords.P25Identity.ORDINARY, false));

            execute(connection, """
                UPDATE configuration_channel
                SET config_json='{"p25SiteIdentity":{"wacn":781824,"system":937,"rfss":2,"site":3}}'
                WHERE configuration_id='%s'
                """.formatted(P25_A));
            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 200, 0xBEE00, 0x3A9, 2, 3));

            assertEquals(radioSystemId, radioSystemId(connection, P25_A));
            assertEquals(2, scalar(connection,
                "SELECT rfss FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(3, scalar(connection,
                "SELECT site FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));
            assertEquals(200, scalar(connection,
                "SELECT radio_system_assigned_at_ms FROM receiver_channel WHERE id=" + channelId));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void completeP25SiteBindingRejectsASecondSiteWithoutClearingPresence() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 100, 0xBEE00, 0x3A9, 1, 1));
            record(connection, p25Presence(P25_A, 150, 123,
                ReceiverActivityRecords.P25Identity.ORDINARY, false));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));

            ReceiverActivitySchema.insertSite(connection, p25Site(P25_A, 200, 0xBEE00, 0x3A9, 1, 2));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_radio_channel_presence"));
            assertEquals(1, scalar(connection,
                "SELECT site FROM p25_site_snapshot WHERE channel_id=" + channelId(connection, P25_A)));

            //The rejected snapshot does not advance the generation watermark or disturb the original site's data.
            record(connection, p25Presence(P25_A, 175, 123,
                ReceiverActivityRecords.P25Identity.ORDINARY, false));
            assertEquals(175, scalar(connection,
                "SELECT confirmed_at_ms FROM trunked_radio_channel_presence"));

            record(connection, p25Presence(P25_A, 250, 123,
                ReceiverActivityRecords.P25Identity.ORDINARY, false));
            assertEquals(250, scalar(connection,
                "SELECT confirmed_at_ms FROM trunked_radio_channel_presence"));
        }
    }

    @Test
    void foreignKeysPreventCrossSystemPairsAndCascadesHaveBoringOwnership() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            insertChannel(connection, P25_B, "TRUNKED", "P25_PHASE1", 2, "P25 B", correlation(2));
            insertChannel(connection, DMR_A, "TRUNKED", "DMR", 1, "DMR A", correlation(3));
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3A9, 4400, 1_000));
            record(connection, trunked(P25_B, "APCO25", 0xBEE00, 0x3AA, 4400, 2_000));
            record(connection, trunked(DMR_A, "DMR", null, null, 91, 3_000));

            long systemA = radioSystemId(connection, P25_A);
            long systemB = radioSystemId(connection, P25_B);
            long channelB = channelId(connection, P25_B);
            long radioA = scalar(connection, """
                SELECT id FROM radio_system_identity_summary
                WHERE radio_system_id=%d AND identity_kind_code=2 AND identity_id=1001
                """.formatted(systemA));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_radio_channel_presence(
                    radio_system_id, radio_identity_id, channel_id, observed_local_id,
                    evidence_code, confirmed_at_ms
                ) VALUES (%d, %d, %d, 1001, 1, 4000)
                """.formatted(systemA, radioA, channelB)));

            execute(connection, """
                INSERT INTO p25_learned_site(
                    learned_site_id, radio_system_id, rfss, site, first_seen_ms, last_seen_ms
                ) VALUES (901, %d, 1, 1, 1000, 1000)
                """.formatted(systemA));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_call_bucket(
                    radio_system_id, learned_site_id, bucket_start_ms, observed_call_count
                ) VALUES (%d, 901, 0, 1)
                """.formatted(systemB)));

            long dmrSystem = radioSystemId(connection, DMR_A);
            execute(connection, "DELETE FROM configuration_channel WHERE configuration_id='" + DMR_A + "'");
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM receiver_channel WHERE configuration_id='" + DMR_A + "'"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM radio_system WHERE id=" + dmrSystem));

            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void sharedNativeSystemSurvivesOneChannelDeletionAndUnownedNativeSystemsPruneAfterFactsExpire()
        throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            insertChannel(connection, P25_B, "TRUNKED", "P25_PHASE1", 2, "P25 B", correlation(2));
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3A9, 4400, 1_000));
            record(connection, trunked(P25_B, "APCO25", 0xBEE00, 0x3A9, 4400, 2_000));
            long system = radioSystemId(connection, P25_A);

            execute(connection, "DELETE FROM configuration_channel WHERE configuration_id='" + P25_A + "'");
            assertEquals(system, radioSystemId(connection, P25_B));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM radio_system WHERE id=" + system));

            execute(connection, "DELETE FROM configuration_channel WHERE configuration_id='" + P25_B + "'");
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM radio_system WHERE id=" + system));
            execute(connection, "DELETE FROM radio_system_identity_summary WHERE radio_system_id=" + system);
            execute(connection, "DELETE FROM trunked_radio_group_summary WHERE radio_system_id=" + system);
            assertEquals(1, ReceiverActivitySchema.runRetentionPass(connection, 0));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM radio_system WHERE id=" + system));
        }
    }

    @Test
    void schemaHasOnlyCanonicalChannelAndSystemIdentityAndRejectsMalformedKeys() throws Exception
    {
        try(Connection connection = open())
        {
            assertEquals(Set.of("id", "configuration_id", "first_seen_ms", "last_seen_ms", "radio_system_id",
                    "radio_system_assigned_at_ms"),
                columns(connection, "receiver_channel"));
            assertFalse(columns(connection, "radio_system_identity_summary").contains("last_observed_local_id"));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM sqlite_master
                WHERE name IN ('receiver_context', 'trunked_identity_scope', 'trunked_identity_scope_context',
                    'radio_system_context', 'p25_system', 'trunked_identity_summary',
                    'trunked_radio_talkgroup_summary', 'trunked_radio_site_presence',
                    'trunked_radio_presence_lifecycle')
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type='table' AND name='trunked_radio_channel_presence_clear'
                """));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM database_metadata
                WHERE key IN ('p25_activity_schema_version', 'dmr_activity_schema_version',
                    'trunked_site_schema_version', 'trunked_identity_metrics_started_at_ms')
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO radio_system(
                    system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms
                ) VALUES ('p25:bee00:124', 1, 0, 0xBEE00, 0x123, 1000, 1000)
                """));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void validationRejectsAConventionalChannelAttachedToATrunkedRadioSystem() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, CONVENTIONAL, "CONVENTIONAL", "DMR", 1, "DMR Conventional", null);
            execute(connection, """
                INSERT INTO radio_system(
                    system_key, configuration_id, protocol_code, address_domain_code, first_seen_ms, last_seen_ms
                ) VALUES ('dmr:channel:%s', '%s', 3, 0, 1000, 1000)
                """.formatted(CONVENTIONAL, CONVENTIONAL));
            execute(connection, """
                INSERT INTO receiver_channel(
                    configuration_id, first_seen_ms, last_seen_ms, radio_system_id,
                    radio_system_assigned_at_ms)
                SELECT '%s', 1000, 1000, id, 1000 FROM radio_system
                WHERE system_key='dmr:channel:%s'
                """.formatted(CONVENTIONAL, CONVENTIONAL));

            assertThrows(SQLException.class, () -> ReceiverActivitySchema.validate(connection));
        }
    }

    private static Connection open() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        SdrTrunkDatabaseSchema.create(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        execute(connection, "INSERT INTO alias_list(id, name, family) VALUES (1, 'First', 'P25'), (2, 'Second', 'P25')");
        ReceiverActivitySchema.validate(connection);
        return connection;
    }

    private static Channel p25Channel(String name, long frequency)
    {
        Channel channel = new Channel(name);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(frequency));
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static void insertChannel(Connection connection, String configurationId, String kind, String decoder,
                                      long aliasListId, String name, String radioResolveId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                radioresolve_id, auto_start, decoder_type, address_domain_code, primary_frequency_hz, config_json
            ) VALUES (?, ?, 0, 'Configured system', 'Configured site', ?, ?, ?, 0, ?, ?, 851012500, ?)
            """))
        {
            statement.setString(1, configurationId);
            statement.setString(2, kind);
            statement.setString(3, name);
            statement.setLong(4, aliasListId);
            statement.setString(5, radioResolveId);
            statement.setString(6, decoder);
            statement.setInt(7, "NXDN".equals(decoder) ? 1 : 0);
            statement.setString(8, switch(decoder)
            {
                case "DMR", "NXDN" -> "{\"decodeConfiguration\":{\"channelMode\":\"" + kind + "\"}}";
                default -> "{}";
            });
            statement.executeUpdate();
        }
    }

    private static ReceiverActivityRecords.ActivityEvent trunked(String configurationId, String protocol,
                                                                  Integer wacn, Integer systemId, int talkgroup,
                                                                  long timestamp)
    {
        return trunked(configurationId, protocol, wacn, systemId, talkgroup, timestamp,
            TrunkedIdentityDomain.STANDARD);
    }

    private static ReceiverActivityRecords.ActivityEvent trunked(String configurationId, String protocol,
                                                                  Integer wacn, Integer systemId, int talkgroup,
                                                                  long timestamp,
                                                                  TrunkedIdentityDomain domain)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, configurationId,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, protocol, ReceiverActivityRecords.Action.GRANT,
            "CALL_GROUP", "1001", Integer.toString(talkgroup), "TALKGROUP", List.of(), 851_012_500L,
            "0-1", 1, false, null, null, wacn, systemId, null, null, null, null, false, null, null,
            domain, ReceiverActivityRecords.P25Identity.ORDINARY,
            ReceiverActivityRecords.P25Identity.ORDINARY,
            List.of(), null);
    }

    private static ReceiverActivityRecords.ActivityEvent p25Activity(String configurationId, long timestamp,
                                                                      String targetId, String targetKind,
                                                                      List<Integer> patchMembers,
                                                                      ReceiverActivityRecords.P25Identity target,
                                                                      List<ReceiverActivityRecords.P25PatchMemberIdentity>
                                                                          memberIdentities,
                                                                      ReceiverActivityRecords.RadioPresenceUpdate presence)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, configurationId,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25", ReceiverActivityRecords.Action.CALL,
            "CALL_GROUP", presence != null ? Integer.toString(presence.radioId()) : "1001", targetId, targetKind,
            patchMembers, 851_012_500L, "0-1", 1, false, null, null, 0xBEE00, 0x3A9, null, null, null, null,
            false, null, presence, TrunkedIdentityDomain.STANDARD, target,
            presence != null ? presence.radioIdentity() : ReceiverActivityRecords.P25Identity.ORDINARY,
            memberIdentities, null);
    }

    private static ReceiverActivityRecords.ActivityEvent p25Presence(String configurationId, long timestamp,
                                                                      int localRadio,
                                                                      ReceiverActivityRecords.P25Identity radio,
                                                                      boolean cleared)
    {
        ReceiverActivityRecords.RadioPresenceUpdate update = cleared ?
            ReceiverActivityRecords.RadioPresenceUpdate.cleared(localRadio, radio) :
            ReceiverActivityRecords.RadioPresenceUpdate.confirmed(localRadio, 91,
                ReceiverActivityRecords.RadioPresenceEvidence.AFFILIATION, radio,
                ReceiverActivityRecords.P25Identity.ORDINARY);
        return p25Activity(configurationId, timestamp, "91", "TALKGROUP", List.of(),
            ReceiverActivityRecords.P25Identity.ORDINARY, List.of(), update);
    }

    private static ReceiverActivityRecords.ActivityEvent conventional(String configurationId, long frequency,
                                                                       long timestamp)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, configurationId,
            ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_ANALOG, "NBFM", ReceiverActivityRecords.Action.CALL,
            "CALL", null, null, null, List.of(), frequency, null, null, false, null, null, null, null, null,
            null, null, null, true, null, null, TrunkedIdentityDomain.STANDARD,
            ReceiverActivityRecords.P25Identity.UNKNOWN, ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(),
            null);
    }

    private static ReceiverActivityRecords.ResolvedLogicalCall dmrCall(String configurationId, long sequence,
                                                                        long timestamp, String radioSystemKey)
    {
        return new ReceiverActivityRecords.ResolvedLogicalCall(new io.github.dsheirer.audio.call.LogicalCallId(7,
            sequence), timestamp, configurationId, "DMR", TrunkedIdentityDomain.STANDARD, null, null, 91,
            "TALKGROUP", List.of(), 1001, false, null, null, ReceiverActivityRecords.P25Identity.UNKNOWN,
            ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(), List.of(), radioSystemKey);
    }

    private static ReceiverActivityRecords.ResolvedLogicalCall p25Call(String configurationId, long sequence,
                                                                        long timestamp, Integer wacn,
                                                                        Integer systemId,
                                                                        String radioSystemKey)
    {
        return new ReceiverActivityRecords.ResolvedLogicalCall(new io.github.dsheirer.audio.call.LogicalCallId(8,
            sequence), timestamp, configurationId, "APCO25", TrunkedIdentityDomain.STANDARD, wacn, systemId, 91,
            "TALKGROUP", List.of(), null, false, null, null, ReceiverActivityRecords.P25Identity.ORDINARY,
            ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(), List.of(), radioSystemKey);
    }

    private static ReceiverActivityRecords.TrunkedCallAttribution attribution(String configurationId,
                                                                                long callStart,
                                                                                int sourceRadio,
                                                                                String radioSystemKey)
    {
        return new ReceiverActivityRecords.TrunkedCallAttribution(callStart, configurationId, "DMR",
            451_012_500L, 1, 91, "TALKGROUP", List.of(), sourceRadio, null, null,
            false, true, false, false, TrunkedIdentityDomain.STANDARD, radioSystemKey);
    }

    private static ReceiverActivityRecords.SiteSnapshot p25Site(String configurationId, long timestamp,
                                                                 Integer wacn, Integer systemId, Integer rfss,
                                                                 Integer site)
    {
        return p25Site(configurationId, timestamp, wacn, systemId, rfss, site,
            851_012_500L, 851_012_500L);
    }

    private static ReceiverActivityRecords.SiteSnapshot p25Site(String configurationId, long timestamp,
                                                                 Integer wacn, Integer systemId, Integer rfss,
                                                                 Integer site, long sourceFrequency,
                                                                 long advertisedControlFrequency)
    {
        return new ReceiverActivityRecords.SiteSnapshot(timestamp, configurationId,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "a".repeat(64), "APCO25", wacn, systemId,
            0x293, rfss, site, null, null, false, null, sourceFrequency, advertisedControlFrequency,
            advertisedControlFrequency,
            List.of(new io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot.Channel(
                "primary_control", null, advertisedControlFrequency, null, false, 1)),
            List.of(), List.of(), List.of(), List.of());
    }

    private static TrunkedSiteSchema.Snapshot dmrSite(String configurationId, long timestamp, int variantCode,
                                                       Integer modelCode, Integer networkId)
    {
        return dmrSite(configurationId, timestamp, variantCode, modelCode, networkId, 1);
    }

    private static TrunkedSiteSchema.Snapshot dmrSite(String configurationId, long timestamp, int variantCode,
                                                       Integer modelCode, Integer networkId, Integer siteId)
    {
        return new TrunkedSiteSchema.Snapshot(timestamp, configurationId, "%064x".formatted(timestamp),
            TrunkedSiteSchema.PROTOCOL_DMR, variantCode, 0, networkId, null, siteId, null, modelCode,
            null, null, null, null, null, null, 0, null, 851_012_500L, 851_012_500L,
            List.of(), List.of());
    }

    private static TrunkedSiteSchema.Snapshot nxdnSite(String configurationId, long timestamp, int variantCode,
                                                        int locationCategoryCode, Integer systemId)
    {
        return nxdnSite(configurationId, timestamp, variantCode, locationCategoryCode, systemId, 1);
    }

    private static TrunkedSiteSchema.Snapshot nxdnSite(String configurationId, long timestamp, int variantCode,
                                                        int locationCategoryCode, Integer systemId, Integer siteId)
    {
        return new TrunkedSiteSchema.Snapshot(timestamp, configurationId, "%064x".formatted(timestamp),
            TrunkedSiteSchema.PROTOCOL_NXDN, variantCode, locationCategoryCode, null, systemId, siteId, 1,
            null, null, null, null, null, null, null, 0, null, 155_000_000L, 155_000_000L,
            List.of(), List.of());
    }

    private static void recordTrunkedSite(Connection connection, TrunkedSiteSchema.Snapshot snapshot)
        throws SQLException
    {
        if(ReceiverActivitySchema.ensureTrunkedSiteRadioSystem(connection, snapshot))
        {
            TrunkedSiteSchema.upsert(connection, snapshot, 0);
        }
    }

    private static void record(Connection connection, ReceiverActivityRecords.ActivityEvent event) throws SQLException
    {
        ReceiverActivitySchema.recordActivity(connection, event, true);
    }

    private static long channelId(Connection connection, String configurationId) throws SQLException
    {
        return scalar(connection,
            "SELECT id FROM receiver_channel WHERE configuration_id='" + configurationId + "'");
    }

    private static long radioSystemId(Connection connection, String configurationId) throws SQLException
    {
        return scalar(connection,
            "SELECT radio_system_id FROM receiver_channel WHERE configuration_id='" + configurationId + "'");
    }

    private static String systemKey(Connection connection, long radioSystemId) throws SQLException
    {
        return text(connection, "SELECT system_key FROM radio_system WHERE id=" + radioSystemId);
    }

    private static String correlation(int suffix)
    {
        return "aaaaaaaa-aaaa-4aaa-8aaa-%012d".formatted(suffix);
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            java.util.ArrayList<String> columns = new java.util.ArrayList<>();
            while(resultSet.next())
            {
                columns.add(resultSet.getString("name"));
            }
            return columns.stream().collect(Collectors.toUnmodifiableSet());
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next(), sql);
            return resultSet.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next(), sql);
            return resultSet.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }
}
