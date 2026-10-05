/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DMRConventionalCallEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallEvent;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25SignalingEvent;
import io.github.dsheirer.module.decode.p25.P25SignalingSemantics;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.P25RadioPresence;
import io.github.dsheirer.module.decode.p25.P25WuidAssignmentRegistry;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25IncompleteRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Focused mapper coverage for the immutable conventional producer records. */
class ReceiverActivityMapperTest
{
    private static final String CONFIGURATION_ID = "223e4567-e89b-42d3-a456-426614174000";

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
        P25WuidAssignmentRegistry.AssignmentObservation observation =
            new P25WuidAssignmentRegistry.AssignmentObservation(0xABCDE, 0x123, 0xFFFD26, subscriber,
                1_000L, 2_000L, P25WuidAssignmentRegistry.Evidence.REGISTRATION);
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
    void mapsRadioInhibitAcknowledgementsWithoutAddingPersistedActions()
    {
        assertEquals(ReceiverActivityRecords.Action.UNKNOWN, mapP25Event(DecodeEventType.RADIO_UNINHIBIT).action());
        assertEquals(ReceiverActivityRecords.Action.UNKNOWN, mapP25Event(DecodeEventType.RADIO_INHIBIT).action());
        assertEquals(ReceiverActivityRecords.Action.ACKNOWLEDGE,
            mapP25Event(DecodeEventType.RADIO_UNINHIBIT_ACK).action());
        assertEquals(ReceiverActivityRecords.Action.ACKNOWLEDGE,
            mapP25Event(DecodeEventType.RADIO_INHIBIT_ACK).action());
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
