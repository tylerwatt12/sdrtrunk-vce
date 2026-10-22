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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionEvidence;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DMRConventionalCallEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallEvent;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallFixture;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNEncryptionKey;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelEventTracker;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25SignalingEvent;
import io.github.dsheirer.module.decode.p25.P25SignalingSemantics;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.P25RadioPresence;
import io.github.dsheirer.module.decode.p25.P25IdentityResolver;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25IncompleteRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.CallSystemIdentity;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Focused mapper coverage for the immutable conventional producer records. */
class ReceiverActivityMapperTest
{
    private static final String CONFIGURATION_ID = "223e4567-e89b-42d3-a456-426614174000";

    @Test
    void p25SummaryCannotResurrectEncryptionFromARetainedIdentifier()
    {
        for(DecoderType decoder: List.of(DecoderType.P25_PHASE1, DecoderType.P25_PHASE2))
        {
            for(CallEncryptionState state: List.of(CallEncryptionState.CLEAR, CallEncryptionState.UNKNOWN))
            {
                var call = completedCall(decoder, state, encryptedIdentifiers(decoder));
                var record = new ReceiverActivityMapper().mapResolvedLogicalCall(call);
                assertNotNull(record);
                assertFalse(record.encrypted(), "a retained key identifier must not override " + state);
                assertNull(record.encryptionAlgorithmId());
                assertNull(record.encryptionKeyId());
            }
        }
    }

    @Test
    void p25SummaryPreservesTheBuildersRejectedEncryptionAfterConflictingEvidence()
    {
        for(DecoderType decoder: List.of(DecoderType.P25_PHASE1, DecoderType.P25_PHASE2))
        {
            var builder = new MutableAudioCallBuilder(AliasList.empty("Test"), 1);
            encryptedIdentifiers(decoder).getIdentifiers().forEach(builder::addIdentifier);
            builder.observeEncryptionState(CallEncryptionState.CLEAR);
            builder.setCallEncryptionEvidence(new CallEncryptionEvidence(0x84, 12328, 1L));
            builder.addAudio(new float[160], 1_100L);
            assertEquals(CallEncryptionState.UNKNOWN, builder.getEncryptionState());
            assertNotNull(builder.getIdentifierCollection().getEncryptionIdentifier());

            var record = new ReceiverActivityMapper().mapResolvedLogicalCall(
                completedCall(decoder, builder.getEncryptionState(), builder.getIdentifierCollection()));
            assertNotNull(record);
            assertFalse(record.encrypted());
            assertNull(record.encryptionAlgorithmId());
            assertNull(record.encryptionKeyId());
        }
    }

    @Test
    void p25SummaryPreservesAcceptedEncryptionIncludingDecodedDecryptedAudio()
    {
        for(DecoderType decoder: List.of(DecoderType.P25_PHASE1, DecoderType.P25_PHASE2))
        {
            var builder = new MutableAudioCallBuilder(AliasList.empty("Test"), 1);
            encryptedIdentifiers(decoder).getIdentifiers().forEach(builder::addIdentifier);
            builder.setCallEncryptionEvidence(new CallEncryptionEvidence(0x84, 12328, 1L));
            builder.addAudio(new float[160], 1_100L);
            assertEquals(CallEncryptionState.ENCRYPTED, builder.getEncryptionState());
            var record = new ReceiverActivityMapper().mapResolvedLogicalCall(
                completedCall(decoder, builder.getEncryptionState(), builder.getIdentifierCollection()));
            assertNotNull(record);
            assertTrue(record.encrypted());
            assertEquals(0x84, record.encryptionAlgorithmId());
            assertEquals(12328, record.encryptionKeyId());
        }
    }

    @Test
    void p25SummaryKeepsAcceptedEncryptionWhenKeyDetailsAreUnavailable()
    {
        var identifiers = new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(101),
            APCO25Talkgroup.create(91)));
        for(DecoderType decoder: List.of(DecoderType.P25_PHASE1, DecoderType.P25_PHASE2))
        {
            var record = new ReceiverActivityMapper().mapResolvedLogicalCall(
                completedCall(decoder, CallEncryptionState.ENCRYPTED, identifiers));
            assertNotNull(record);
            assertTrue(record.encrypted());
            assertNull(record.encryptionAlgorithmId());
            assertNull(record.encryptionKeyId());
        }
    }

    @Test
    void dmrAndNxdnSummaryAlsoRespectAcceptedEncryptionState()
    {
        for(DecoderType decoder: List.of(DecoderType.DMR, DecoderType.NXDN))
        {
            for(CallEncryptionState state: CallEncryptionState.values())
            {
                var record = new ReceiverActivityMapper().mapResolvedLogicalCall(
                    completedCall(decoder, state, encryptedIdentifiers(decoder)));
                assertNotNull(record);
                assertEquals(state.isEncrypted(), record.encrypted());
                if(state.isEncrypted())
                {
                    assertEquals(decoder == DecoderType.NXDN ? 3 : 0x84, record.encryptionAlgorithmId());
                    assertEquals(decoder == DecoderType.NXDN ? 7 : 12328, record.encryptionKeyId());
                }
                else
                {
                    assertNull(record.encryptionAlgorithmId());
                    assertNull(record.encryptionKeyId());
                }
            }
        }
    }

    private static IdentifierCollection encryptedIdentifiers(DecoderType decoder)
    {
        if(decoder == DecoderType.DMR)
        {
            EncryptionKey key = new EncryptionKey(0x84, 12328)
            {
                @Override public boolean isEncrypted() { return true; }
            };
            return new IdentifierCollection(List.of(DMRRadio.createFrom(101), DMRTalkgroup.create(91),
                EncryptionKeyIdentifier.create(Protocol.DMR, key)));
        }
        if(decoder == DecoderType.NXDN)
        {
            return new IdentifierCollection(List.of(NXDNRadioIdentifier.createFrom(101),
                NXDNTalkgroupIdentifier.createTo(91),
                EncryptionKeyIdentifier.create(Protocol.NXDN, NXDNEncryptionKey.create(3, 7))));
        }
        return new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(101), APCO25Talkgroup.create(91),
            EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x84, 12328))));
    }

    private static CompletedAudioCall completedCall(DecoderType decoder, CallEncryptionState state,
        IdentifierCollection identifiers)
    {
        boolean p25 = decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_PHASE2;
        CallLegSource source = new CallLegSource(decoder, CONFIGURATION_ID, "Test", null, 0,
            p25 ? new P25SiteIdentity(0xBEE00, 0x123, 1, 1) : null,
            decoder == DecoderType.NXDN ? TrunkedIdentityDomain.NXDN_TYPE_C : TrunkedIdentityDomain.STANDARD,
            ChannelKind.TRUNKED, true);
        AudioCallId id = new AudioCallId(91, 1, 1);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(id, null, null, identifiers, Set.of(),
            1_000L, 2_000L, 1, 1, 1_000L, 2_000L, false, true, state, true, null,
            VoiceCallQuality.EMPTY, CallLegId.from(id), source, null);
        return new CompletedAudioCall(snapshot, List.of());
    }

    @Test
    void activityFactsRejectNamesWithoutTheObservedRadioOwner()
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        var source = APCO25RadioIdentifier.createFrom(101);
        ReceiverActivityMapper mapper = new ReceiverActivityMapper();
        for(var alias: List.of(P25TalkerAliasIdentifier.create("UNBOUND"),
            P25TalkerAliasIdentifier.create("OTHER RADIO").bindTo(APCO25RadioIdentifier.createFrom(202))))
        {
            DecodeEvent event = new DecodeEvent(DecodeEventType.CALL_GROUP, 1_000L);
            event.setProtocol(Protocol.APCO25);
            event.setIdentifierCollection(new IdentifierCollection(List.of(source, APCO25Talkgroup.create(91), alias)));
            var mapped = mapper.map(channel, event);
            assertEquals("101", mapped.sourceRadioId());
            assertNull(mapped.talkerAlias());
        }
    }

    @Test
    void aliasObservationEventsRequireAnAliasBoundToTheirExplicitRadio()
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        var source = APCO25RadioIdentifier.createFrom(101);
        var context = new IdentifierCollection(List.of(source));
        var call = new CallSystemIdentity(1_000L, "p25:bee00:123");
        ReceiverActivityMapper mapper = new ReceiverActivityMapper();
        for(var alias: List.of(P25TalkerAliasIdentifier.create("UNBOUND"),
            P25TalkerAliasIdentifier.create("OTHER RADIO").bindTo(APCO25RadioIdentifier.createFrom(202))))
        {
            assertNull(mapper.map(new TrunkedTalkerAliasEvent(channel, Protocol.APCO25, source, alias,
                context, TrunkedIdentityDomain.STANDARD, 1_100L, call)));
        }
        var matching = P25TalkerAliasIdentifier.create("CURRENT RADIO").bindTo(source, call.radioSystemKey());
        assertEquals("CURRENT RADIO", mapper.map(new TrunkedTalkerAliasEvent(channel, Protocol.APCO25, source,
            matching, context, TrunkedIdentityDomain.STANDARD, 1_100L, call)).talkerAlias());
    }

    @Test
    void equalRadioNumbersCannotCarryAnAliasAcrossSavedChannelOwners()
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        var source = APCO25RadioIdentifier.createFrom(101);
        var context = new IdentifierCollection(List.of(source));
        var call = new CallSystemIdentity(1_000L, "p25:bee00:123");
        var alias = P25TalkerAliasIdentifier.create("OTHER RECEIVER").bindTo(source, null,
            "223e4567-e89b-42d3-a456-426614174999");

        assertNull(new ReceiverActivityMapper().map(new TrunkedTalkerAliasEvent(channel, Protocol.APCO25,
            source, alias, context, TrunkedIdentityDomain.STANDARD, 1_100L, call)));
    }

    @Test
    void mapsDmrCompletionUsingOnlyTheSavedConfigurationId()
    {
        DMRConventionalCallEvent event = new DMRConventionalCallEvent(1_000, 2_000, CONFIGURATION_ID,
            461_125_000, 2, DMRConventionalCallEvent.TargetKind.PRIVATE, null, 101, 202, true);
        ReceiverActivityRecords.DmrConventionalCall record = new ReceiverActivityMapper().map(event);

        assertEquals(CONFIGURATION_ID, record.configurationId());
        assertEquals(461_125_000, record.frequencyHertz());
        assertEquals(2, record.timeslot());
        assertEquals(ReceiverActivityRecords.DmrTargetKind.PRIVATE, record.targetKind());
        assertEquals(101, record.sourceRadioId());
        assertEquals(202, record.targetRadioId());
        assertTrue(record.encrypted());
    }

    @Test
    void mapsNxdnCompletionUsingOnlyTheSavedConfigurationId()
    {
        NXDNConventionalCallEvent event = new NXDNConventionalCallEvent(1_000, 2_000, CONFIGURATION_ID,
            461_125_000, NXDNConventionalCallEvent.TargetKind.GROUP, 91, 101, null, true,
            TrunkedIdentityDomain.NXDN_TYPE_C);
        ReceiverActivityRecords.NxdnConventionalCall record = new ReceiverActivityMapper().map(event);

        assertEquals(CONFIGURATION_ID, record.configurationId());
        assertEquals(ReceiverActivityRecords.NxdnTargetKind.GROUP, record.targetKind());
        assertEquals(91, record.talkgroupId());
        assertEquals(101, record.sourceRadioId());
        assertNull(record.targetRadioId());
        assertTrue(record.encrypted());
    }

    @Test
    void preservesNxdnNullGroupForStatistics()
    {
        NXDNConventionalCallEvent event = new NXDNConventionalCallEvent(1_000, 2_000, CONFIGURATION_ID,
            461_125_000, NXDNConventionalCallEvent.TargetKind.GROUP, 0, 101, null, false,
            TrunkedIdentityDomain.NXDN_TYPE_C);
        ReceiverActivityRecords.NxdnConventionalCall record = new ReceiverActivityMapper().map(event);

        assertEquals(ReceiverActivityRecords.NxdnTargetKind.GROUP, record.targetKind());
        assertEquals(0, record.talkgroupId());
        assertEquals(101, record.sourceRadioId());
    }

    @Test
    void preservesTheRealDecoderNullGroupCompletionInActivity()
    {
        var decoded = NXDNConventionalCallFixture.decodeGroupCall(AliasList.empty("NXDN fixture"), 101, 0);
        ReceiverActivityRecords.NxdnConventionalCall record = new ReceiverActivityMapper().map(decoded.completion());

        assertNotNull(record);
        assertEquals(ReceiverActivityRecords.NxdnTargetKind.GROUP, record.targetKind());
        assertEquals(0, record.talkgroupId());
        assertEquals(101, record.sourceRadioId());
        assertNull(record.targetRadioId());
        assertEquals(decoded.audio().snapshot().activityCallToken(), record.activityCallToken());
    }

    @Test
    void rejectsMissingOrNonCanonicalConfigurationIdentity()
    {
        ReceiverActivityMapper mapper = new ReceiverActivityMapper();
        assertNull(mapper.map(new DMRConventionalCallEvent(1_000, 2_000, null, 461_125_000, 1,
            DMRConventionalCallEvent.TargetKind.UNKNOWN, null, null, null, false)));
        assertNull(mapper.map(new NXDNConventionalCallEvent(1_000, 2_000, "display-name", 461_125_000,
            NXDNConventionalCallEvent.TargetKind.UNKNOWN, null, null, null, false,
            TrunkedIdentityDomain.NXDN_TYPE_C)));
    }

    @Test
    void keepsAnIncompleteP25RegistrationAsRawEvidenceOnly()
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        APCO25IncompleteRadioIdentifier radio = APCO25IncompleteRadioIdentifier.createTo(831_102);
        P25AffiliationEvent event = new P25AffiliationEvent(DecodeEventType.REGISTER, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED, new P25RadioPresence(null, 831_102), radio, null);
        event.setIdentifierCollection(new MutableIdentifierCollection(List.of(radio)));

        ReceiverActivityRecords.ActivityEvent record = new ReceiverActivityMapper().map(channel, event);

        assertEquals("831102", record.sourceRadioId());
        assertNull(record.targetId());
        assertNull(record.targetKind());
        assertEquals(ReceiverActivityRecords.P25IdentityState.ORDINARY, record.p25SourceIdentity().state());
        assertEquals(ReceiverActivityRecords.P25IdentityState.ORDINARY,
            record.radioPresenceUpdate().radioIdentity().state());
        assertNull(record.p25WuidObservation());
    }

    @Test
    void mapsAQualifiedP25RegistrationWithoutInventingARadioTarget()
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        APCO25FullyQualifiedRadioIdentifier radio =
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(0xFFFD26, 0xBEE00, 0x954, 831_102);
        P25SubscriberIdentity subscriber = new P25SubscriberIdentity(0xBEE00, 0x954, 831_102);
        P25IdentityResolver.AssignmentObservation observation =
            new P25IdentityResolver.AssignmentObservation(0xABCDE, 0x123, 0xFFFD26, subscriber,
                1_000L, 2_000L, P25IdentityResolver.Evidence.REGISTRATION);
        P25AffiliationEvent event = new P25AffiliationEvent(DecodeEventType.REGISTER, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED, P25RadioPresence.from(radio, radio.getWorkingAddress()), observation,
            radio, null);
        event.setIdentifierCollection(new MutableIdentifierCollection(List.of(radio)));

        ReceiverActivityRecords.ActivityEvent record = new ReceiverActivityMapper().map(channel, event);

        assertEquals(Integer.toString(0xFFFD26), record.sourceRadioId());
        assertNull(record.targetId());
        assertNull(record.targetKind());
        assertEquals(ReceiverActivityRecords.P25IdentityState.STABLE_FULLY_QUALIFIED,
            record.p25SourceIdentity().state());
        assertEquals(0xFFFD26, record.radioPresenceUpdate().radioId());
        assertNull(record.radioPresenceUpdate().talkgroupId());
        assertEquals(0xABCDE, record.p25WuidObservation().servingWacn());
        assertEquals(0x123, record.p25WuidObservation().servingSystem());
        assertEquals(0xFFFD26, record.p25WuidObservation().workingId());
        assertEquals(subscriber, record.p25WuidObservation().subscriber());
        assertEquals(2_000L, record.p25WuidObservation().expiresAtEpochMilliseconds());
        assertEquals(ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION,
            record.p25WuidObservation().evidence());
    }

    @Test
    void canonicalTalkerAliasDoesNotInventAWorkingAddress()
    {
        APCO25FullyQualifiedRadioIdentifier radio =
            APCO25FullyQualifiedRadioIdentifier.createFrom(831_102, 0xBEE00, 0x954, 831_102);
        ReceiverActivityRecords.TalkerAliasUpdate record = new ReceiverActivityMapper().map(
            new TrunkedTalkerAliasEvent(CONFIGURATION_ID, DecoderType.P25_PHASE1, Protocol.APCO25, radio,
                "Canonical Only", List.of(radio), TrunkedIdentityDomain.STANDARD, 2_000L, 1_000L,
                "p25:bee00:954"));

        assertNull(record.radioId());
        assertEquals(831_102, record.p25RadioIdentity().homeIdentityId());
    }

    @Test
    void talkerAliasPreservesAnExplicitWorkingAddressEqualToTheSubscriberNumber()
    {
        APCO25FullyQualifiedRadioIdentifier radio =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                831_102, 0xBEE00, 0x954, 831_102);
        ReceiverActivityRecords.TalkerAliasUpdate record = new ReceiverActivityMapper().map(
            new TrunkedTalkerAliasEvent(CONFIGURATION_ID, DecoderType.P25_PHASE1, Protocol.APCO25, radio,
                "Equal Working Address", List.of(radio), TrunkedIdentityDomain.STANDARD, 2_000L, 1_000L,
                "p25:bee00:954"));

        assertEquals(831_102, record.radioId());
        assertEquals(831_102, record.p25RadioIdentity().homeIdentityId());
    }

    @Test
    void repeatedActivityPreservesNewSourceAndTargetIdentityEvidence()
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        DecodeEvent event = new DecodeEvent(DecodeEventType.CALL_UNIT_TO_UNIT, 1_000L);
        event.setProtocol(Protocol.APCO25);
        var source = APCO25RadioIdentifier.createFrom(10_900_077);
        var target = APCO25RadioIdentifier.createTo(10_900_078);
        event.setIdentifierCollection(new MutableIdentifierCollection(List.of(source, target)));
        ReceiverActivityMapper mapper = new ReceiverActivityMapper();
        var ordinary = mapper.map(channel, event);
        assertEquals(ordinary.dedupeKey(), mapper.map(channel, event).dedupeKey());

        var canonicalSource = APCO25FullyQualifiedRadioIdentifier.createFrom(10_900_077,
            0xBEE00, 0x123, 10_900_077);
        event.setIdentifierCollection(new MutableIdentifierCollection(List.of(canonicalSource, target)));
        var sourceEnriched = mapper.map(channel, event);
        assertEquals(ordinary.sourceRadioId(), sourceEnriched.sourceRadioId());
        assertNotEquals(ordinary.dedupeKey(), sourceEnriched.dedupeKey());
        assertEquals(sourceEnriched.dedupeKey(), mapper.map(channel, event).dedupeKey());

        var canonicalTarget = APCO25FullyQualifiedRadioIdentifier.createTo(10_900_078,
            0xBEE00, 0x123, 10_900_078);
        event.setIdentifierCollection(new MutableIdentifierCollection(List.of(canonicalSource, canonicalTarget)));
        var targetEnriched = mapper.map(channel, event);
        assertEquals(ordinary.targetId(), targetEnriched.targetId());
        assertNotEquals(sourceEnriched.dedupeKey(), targetEnriched.dedupeKey());

        var assigned = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(10_900_077,
            0xBEE00, 0x123, 10_900_077);
        event.setIdentifierCollection(new MutableIdentifierCollection(List.of(assigned, canonicalTarget)));
        var explicitWorking = mapper.map(channel, event);
        assertNotEquals(targetEnriched.dedupeKey(), explicitWorking.dedupeKey());
        assertEquals(10_900_077, explicitWorking.sourceObservedWorkingId());
        assertEquals(ordinary.countedCall(), explicitWorking.countedCall());
    }

    @Test
    void repeatedTalkerAliasesRemainDistinctAcrossCallAndServingSystemGenerations()
    {
        ReceiverActivityMapper mapper = new ReceiverActivityMapper();
        var radio = APCO25FullyQualifiedRadioIdentifier.createFrom(10_900_077,
            0xBEE00, 0x123, 10_900_077);
        var first = mapper.map(new TrunkedTalkerAliasEvent(CONFIGURATION_ID, DecoderType.P25_PHASE1,
            Protocol.APCO25, radio, "UNIT 77", List.of(radio), TrunkedIdentityDomain.STANDARD,
            1_100L, 1_000L, "p25:bee00:123"));
        var repeat = mapper.map(new TrunkedTalkerAliasEvent(CONFIGURATION_ID, DecoderType.P25_PHASE1,
            Protocol.APCO25, radio, "UNIT 77", List.of(radio), TrunkedIdentityDomain.STANDARD,
            1_200L, 1_000L, "p25:bee00:123"));
        var nextCall = mapper.map(new TrunkedTalkerAliasEvent(CONFIGURATION_ID, DecoderType.P25_PHASE1,
            Protocol.APCO25, radio, "UNIT 77", List.of(radio), TrunkedIdentityDomain.STANDARD,
            2_100L, 2_000L, "p25:bee00:123"));
        var anotherSystem = mapper.map(new TrunkedTalkerAliasEvent(CONFIGURATION_ID, DecoderType.P25_PHASE1,
            Protocol.APCO25, radio, "UNIT 77", List.of(radio), TrunkedIdentityDomain.STANDARD,
            1_100L, 1_000L, "p25:bee00:124"));
        assertEquals(ReceiverActivityService.talkerAliasDedupeKey(first),
            ReceiverActivityService.talkerAliasDedupeKey(repeat));
        assertNotEquals(ReceiverActivityService.talkerAliasDedupeKey(first),
            ReceiverActivityService.talkerAliasDedupeKey(nextCall));
        assertNotEquals(ReceiverActivityService.talkerAliasDedupeKey(first),
            ReceiverActivityService.talkerAliasDedupeKey(anotherSystem));
    }

    @Test
    void queuedCallActivityKeepsItsCapturedServingSystemAfterTheReceiverChanges()
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        channel.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x123, 1, 2));
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(new MutableIdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(10_900_077),
                APCO25Talkgroup.create(10_003)))).build();
        new P25TrafficChannelEventTracker(event, "p25:bee00:123");
        channel.setP25SiteIdentity(new P25SiteIdentity(0xABCDE, 0x321, 3, 4));
        var record = new ReceiverActivityMapper().map(channel, event);
        assertEquals("p25:bee00:123", record.radioSystemKey());
        assertEquals("10900077", record.sourceRadioId());
        assertEquals(ReceiverActivityRecords.P25IdentityState.ORDINARY, record.p25SourceIdentity().state());
    }

    @Test
    void normalizesUnknownFrequencyIdentifiersBeforeCreatingActivityRecords()
    {
        assertNull(mapP25RegistrationFrequency(null).frequencyHertz());
        assertNull(mapP25RegistrationFrequency(0L).frequencyHertz());
        assertNull(mapP25RegistrationFrequency(-1L).frequencyHertz());
        assertEquals(851_012_500L, mapP25RegistrationFrequency(851_012_500L).frequencyHertz());
    }

    @Test
    void preservesStructuredP25BusySemanticsWithoutANewPersistedEventType()
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25SignalingEvent event = new P25SignalingEvent(DecodeEventType.RESPONSE, 1_000L,
            P25SignalingSemantics.Action.BUSY);

        ReceiverActivityRecords.ActivityEvent record = new ReceiverActivityMapper().map(channel, event);

        assertEquals(ReceiverActivityRecords.Action.BUSY, record.action());
        assertEquals(DecodeEventType.RESPONSE.name(), record.eventType());
    }

    @Test
    void keepsRadioInhibitCommandsSeparateFromAcknowledgements()
    {
        assertEquals(ReceiverActivityRecords.Action.RADIO_UNINHIBIT, mapP25Event(DecodeEventType.RADIO_UNINHIBIT).action());
        assertEquals(ReceiverActivityRecords.Action.RADIO_INHIBIT, mapP25Event(DecodeEventType.RADIO_INHIBIT).action());
        assertEquals(ReceiverActivityRecords.Action.ACKNOWLEDGE,
            mapP25Event(DecodeEventType.RADIO_UNINHIBIT_ACK).action());
        assertEquals(ReceiverActivityRecords.Action.ACKNOWLEDGE,
            mapP25Event(DecodeEventType.RADIO_INHIBIT_ACK).action());
    }

    @Test
    void typedCommandsSurviveCollectionAndKeepResponsesIndependentForRepeatSuppression()
    {
        for(var config: List.of(new io.github.dsheirer.module.decode.dmr.DecodeConfigDMR(),
            new io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN()))
        {
            Channel channel = new Channel("Typed signaling", Channel.ChannelType.STANDARD);
            channel.setConfigurationId(CONFIGURATION_ID);
            channel.setDecodeConfiguration(config);
            ReceiverActivityMapper mapper = new ReceiverActivityMapper();
            java.util.Set<String> keys = new java.util.HashSet<>();
            for(DecodeEventType type: List.of(DecodeEventType.STUN, DecodeEventType.REVIVE, DecodeEventType.KILL,
                DecodeEventType.REMOTE_MONITOR, DecodeEventType.AUTHENTICATE, DecodeEventType.CANCEL_CALL,
                DecodeEventType.RADIO_DETACH, DecodeEventType.PROTECT, DecodeEventType.VOTE_NOW,
                DecodeEventType.BUSY, DecodeEventType.QUEUED, DecodeEventType.STUN_RESPONSE,
                DecodeEventType.REVIVE_RESPONSE, DecodeEventType.KILL_RESPONSE, DecodeEventType.REMOTE_MONITOR_RESPONSE))
            {
                DecodeEvent event = new DecodeEvent(type, 1_000L);
                event.setProtocol(config.getDecoderType().getProtocol());
                //Known typed operations do not depend on display wording or accidentally become a denial.
                event.setDetails("DENIED UNKNOWN COMMAND");
                var record = mapper.map(channel, event);
                assertNotNull(record, type.name());
                assertEquals(type.name(), record.eventType());
                String expectedAction = type.name().replace("_RESPONSE", "");
                assertEquals(expectedAction, record.action().name());
                assertEquals(record.dedupeKey(), mapper.map(channel, event).dedupeKey());
                assertTrue(keys.add(record.dedupeKey()), type + " must not suppress another operation or its response");
            }
        }
    }

    @Test
    void rejectsExplicitP25SystemAndNacDisagreementEvenWhenTheSiteIsIncomplete()
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25NetworkConfigurationSnapshot mixedSystem = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x3A9, 0x293, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x3AA, 0x293, null, null, null, true),
            List.of(), List.of(), List.of(), List.of(), List.of());
        P25NetworkConfigurationSnapshot mixedNac = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x3A9, 0x293, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x3A9, 0x294, null, null, null, true),
            List.of(), List.of(), List.of(), List.of(), List.of());

        assertNull(new ReceiverActivityMapper().map(new SiteMetadataEvent(channel, mixedSystem, 1_000L)));
        assertNull(new ReceiverActivityMapper().map(new SiteMetadataEvent(channel, mixedNac, 1_000L)));
    }

    @Test
    void completeP25IdentityRequiresItsDecodedSourceToBeAnAdvertisedControl()
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        long advertised = 851_012_500L;
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x3A9, 0x293, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x3A9, 0x293, 1, 1, null, true),
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", null, advertised, null,
                false, 1)), List.of(), List.of(), List.of(), List.of());

        ReceiverActivityMapper mapper = new ReceiverActivityMapper();
        assertNull(mapper.map(new SiteMetadataEvent(channel, snapshot, 1_000L, 852_012_500L)));
        assertEquals(advertised,
            mapper.map(new SiteMetadataEvent(channel, snapshot, 2_000L, advertised)).sourceFrequencyHertz());
    }

    @Test
    void p25SiteMappingRejectsAProducerSnapshotAfterTheLiveReceiverIsEdited()
    {
        Channel channel = new Channel("Original", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(851_012_500L);
        channel.setSourceConfiguration(source);
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x3A9, 0x293, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x3A9, 0x293, 1, 1, null, true),
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", null, 851_012_500L,
                null, false, 1)), List.of(), List.of(), List.of(), List.of());
        SiteMetadataEvent event = new SiteMetadataEvent(channel, snapshot, 1_000L, 851_012_500L);

        channel.setConfigurationId("00000000-0000-0000-0000-000000000999");
        SourceConfigTuner replacement = new SourceConfigTuner();
        replacement.setFrequency(852_012_500L);
        channel.setSourceConfiguration(replacement);

        assertEquals(CONFIGURATION_ID, event.receiverContext().configurationId());
        assertEquals(851_012_500L, event.sourceFrequency());
        assertNull(new ReceiverActivityMapper().map(event));
    }

    private static ReceiverActivityRecords.ActivityEvent mapP25RegistrationFrequency(Long frequency)
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        APCO25IncompleteRadioIdentifier radio = APCO25IncompleteRadioIdentifier.createTo(831_102);
        P25AffiliationEvent event = new P25AffiliationEvent(DecodeEventType.REGISTER, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED, new P25RadioPresence(null, 831_102), radio, null);
        event.setIdentifierCollection(new MutableIdentifierCollection(frequency != null ?
            List.of(radio, FrequencyConfigurationIdentifier.create(frequency)) : List.of(radio)));
        return new ReceiverActivityMapper().map(channel, event);
    }

    private static ReceiverActivityRecords.ActivityEvent mapP25Event(DecodeEventType eventType)
    {
        Channel channel = new Channel("P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        DecodeEvent event = new DecodeEvent(eventType, 1_000L);
        event.setProtocol(io.github.dsheirer.protocol.Protocol.APCO25);
        return new ReceiverActivityMapper().map(channel, event);
    }
}
