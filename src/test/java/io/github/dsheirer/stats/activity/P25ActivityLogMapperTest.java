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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteGuidConfigurationIdentifier;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DMRConventionalCallEvent;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallEvent;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25EncryptionConfirmationTracker;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25DecodeEvent;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Rfss;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Site;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class P25ActivityLogMapperTest
{
    private static final String GUID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String CONFIGURATION_ID = "223e4567-e89b-12d3-a456-426614174000";
    private static final String CONFIGURATION_CONTEXT_KEY = "CONFIGURATION:" + CONFIGURATION_ID;

    @Test
    void mapsImmutableDmrConventionalCompletion()
    {
        DMRConventionalCallEvent event = new DMRConventionalCallEvent(1_000L, 2_000L, CONFIGURATION_ID,
            GUID, "County Repeater", "County DMR", 461_125_000L, 2,
            DMRConventionalCallEvent.TargetKind.PRIVATE, null, 101, 202, true);

        P25ActivityLogRecords.DmrConventionalCall record = new P25ActivityLogMapper().map(event);

        assertNotNull(record);
        assertEquals(CONFIGURATION_CONTEXT_KEY, record.contextKey());
        assertEquals("County DMR", record.aliasListName());
        assertEquals(461_125_000L, record.frequencyHertz());
        assertEquals(2, record.timeslot());
        assertEquals(P25ActivityLogRecords.DmrTargetKind.PRIVATE, record.targetKind());
        assertEquals(101, record.sourceRadioId());
        assertEquals(202, record.targetRadioId());
        assertTrue(record.encrypted());

        P25ActivityLogRecords.DmrConventionalCall missingIdentity = new P25ActivityLogMapper().map(
            new DMRConventionalCallEvent(1_000L, 2_000L, null, null, "County Repeater", null,
                461_125_000L, 2, DMRConventionalCallEvent.TargetKind.UNKNOWN, null, null, null, false));
        assertNull(missingIdentity);
    }

    @Test
    void mapsImmutableNxdnConventionalCompletion()
    {
        NXDNConventionalCallEvent event = new NXDNConventionalCallEvent(1_000L, 2_000L, CONFIGURATION_ID,
            GUID, "County Repeater", "County NXDN", 461_125_000L,
            NXDNConventionalCallEvent.TargetKind.GROUP, 91, 101, null, true);

        P25ActivityLogRecords.NxdnConventionalCall record = new P25ActivityLogMapper().map(event);

        assertNotNull(record);
        assertEquals(CONFIGURATION_CONTEXT_KEY, record.contextKey());
        assertEquals("County NXDN", record.aliasListName());
        assertEquals(461_125_000L, record.frequencyHertz());
        assertEquals(P25ActivityLogRecords.NxdnTargetKind.GROUP, record.targetKind());
        assertEquals(91, record.talkgroupId());
        assertEquals(101, record.sourceRadioId());
        assertNull(record.targetRadioId());
        assertTrue(record.encrypted());

        P25ActivityLogRecords.NxdnConventionalCall missingIdentity = new P25ActivityLogMapper().map(
            new NXDNConventionalCallEvent(1_000L, 2_000L, null, null, "County Repeater", null,
                461_125_000L, NXDNConventionalCallEvent.TargetKind.UNKNOWN, null, null, null, false));
        assertNull(missingIdentity);
    }

    @Test
    void retainsUsefulDmrAndNxdnSignalingButSuppressesDecoderNoise()
    {
        DecodeConfigDMR dmrConfig = new DecodeConfigDMR();
        dmrConfig.setChannelMode(DMRChannelMode.CONVENTIONAL);
        Channel dmr = new Channel("DMR Repeater", ChannelType.STANDARD);
        dmr.setDecodeConfiguration(dmrConfig);
        dmr.setConfigurationId(CONFIGURATION_ID);
        dmr.setRadresGuid(GUID);
        DecodeConfigNXDN nxdnConfig = new DecodeConfigNXDN();
        nxdnConfig.setChannelMode(NXDNChannelMode.TRUNKED);
        Channel nxdn = new Channel("NXDN Site", ChannelType.STANDARD);
        nxdn.setDecodeConfiguration(nxdnConfig);
        nxdn.setRadresGuid(GUID);
        DecodeEvent registration = DecodeEvent.builder(DecodeEventType.RADIO_REGISTRATION_SERVICE, 1_000L)
            .protocol(Protocol.DMR)
            .build();
        DecodeEvent page = DecodeEvent.builder(DecodeEventType.PAGE, 2_000L)
            .protocol(Protocol.NXDN)
            .timeslot(0)
            .build();
        DecodeEvent location = DecodeEvent.builder(DecodeEventType.GPS, 2_500L)
            .protocol(Protocol.LRRP)
            .timeslot(1)
            .build();
        DecodeEvent shortData = DecodeEvent.builder(DecodeEventType.SDM, 2_750L)
            .protocol(Protocol.DMR)
            .timeslot(1)
            .build();
        DecodeEvent noise = DecodeEvent.builder(DecodeEventType.UNKNOWN_PACKET, 3_000L)
            .protocol(Protocol.NXDN)
            .details("unclassified decoder output")
            .build();
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();

        P25ActivityLogRecords.ActivityEvent dmrRecord = mapper.map(dmr, registration);
        P25ActivityLogRecords.ActivityEvent nxdnRecord = mapper.map(nxdn, page);
        P25ActivityLogRecords.ActivityEvent locationRecord = mapper.map(dmr, location);
        P25ActivityLogRecords.ActivityEvent shortDataRecord = mapper.map(dmr, shortData);

        assertNotNull(dmrRecord);
        assertEquals(P25ActivityLogRecords.ContextKind.CONVENTIONAL_DMR, dmrRecord.contextKind());
        assertEquals(CONFIGURATION_CONTEXT_KEY, dmrRecord.contextKey());
        assertEquals(P25ActivityLogRecords.Action.REGISTER, dmrRecord.action());
        assertNotNull(nxdnRecord);
        assertEquals(P25ActivityLogRecords.ContextKind.TRUNKED_SITE, nxdnRecord.contextKind());
        assertEquals(P25ActivityLogRecords.Action.PAGE, nxdnRecord.action());
        assertNull(nxdnRecord.timeslot());
        assertEquals(P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C, nxdnRecord.identityDomain());
        assertNotNull(locationRecord);
        assertEquals("DMR", locationRecord.protocol());
        assertEquals(P25ActivityLogRecords.Action.GPS, locationRecord.action());
        assertNotNull(shortDataRecord);
        assertEquals(P25ActivityLogRecords.Action.DATA, shortDataRecord.action());
        assertNull(mapper.map(nxdn, noise));
    }

    @Test
    void coalescesOnlyEquivalentDmrSignalingBursts()
    {
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        Channel channel = new Channel("DMR Site", ChannelType.STANDARD);
        channel.setDecodeConfiguration(config);
        channel.setRadresGuid(GUID);
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();
        P25ActivityLogRecords.ActivityEvent register = mapper.map(channel,
            dmrSignaling(DecodeEventType.COMMAND, "REGISTER", 101, 201, 451_000_000L, 1));
        P25ActivityLogRecords.ActivityEvent repeated = mapper.map(channel,
            dmrSignaling(DecodeEventType.COMMAND, " register ", 101, 201, 451_000_000L, 1));
        P25ActivityLogRecords.ActivityEvent differentSubtype = mapper.map(channel,
            dmrSignaling(DecodeEventType.COMMAND, "CANCEL CALL", 101, 201, 451_000_000L, 1));
        P25ActivityLogRecords.ActivityEvent differentType = mapper.map(channel,
            dmrSignaling(DecodeEventType.REQUEST, "REGISTER", 101, 201, 451_000_000L, 1));
        P25ActivityLogRecords.ActivityEvent differentSource = mapper.map(channel,
            dmrSignaling(DecodeEventType.COMMAND, "REGISTER", 102, 201, 451_000_000L, 1));
        P25ActivityLogRecords.ActivityEvent differentTarget = mapper.map(channel,
            dmrSignaling(DecodeEventType.COMMAND, "REGISTER", 101, 202, 451_000_000L, 1));
        P25ActivityLogRecords.ActivityEvent differentChannel = mapper.map(channel,
            dmrSignaling(DecodeEventType.COMMAND, "REGISTER", 101, 201, 452_000_000L, 1));
        P25ActivityLogRecords.ActivityEvent differentSlot = mapper.map(channel,
            dmrSignaling(DecodeEventType.COMMAND, "REGISTER", 101, 201, 451_000_000L, 2));
        P25ActivityLogRecords.ActivityEvent denied = mapper.map(channel,
            dmrSignaling(DecodeEventType.DENIAL, "REGISTRATION DENIED", 101, 201, 451_000_000L, 1));

        assertNotNull(register);
        assertEquals(P25ActivityLogRecords.Action.REGISTER, register.action());
        assertTrue(register.dedupeKey().startsWith(P25ActivityLogMapper.PROTOCOL_SIGNAL_DEDUPE_PREFIX));
        assertEquals(register.dedupeKey(), repeated.dedupeKey());
        assertNotEquals(register.dedupeKey(), differentSubtype.dedupeKey());
        assertNotEquals(register.dedupeKey(), differentType.dedupeKey());
        assertNotEquals(register.dedupeKey(), differentSource.dedupeKey());
        assertNotEquals(register.dedupeKey(), differentTarget.dedupeKey());
        assertNotEquals(register.dedupeKey(), differentChannel.dedupeKey());
        assertNotEquals(register.dedupeKey(), differentSlot.dedupeKey());
        assertEquals(P25ActivityLogRecords.Action.DENIAL, denied.action());
        assertTrue(P25ActivityLogService.isWithinDedupeWindow(register.dedupeKey(), 1_000L, 1_500L));
        assertFalse(P25ActivityLogService.isWithinDedupeWindow(register.dedupeKey(), 1_000L, 1_501L));
    }

    @Test
    void suppressesRawDmrAndNxdnVoiceEventsOwnedByTypedCallPaths()
    {
        DecodeConfigDMR dmrConfig = new DecodeConfigDMR();
        dmrConfig.setChannelMode(DMRChannelMode.TRUNKED);
        Channel dmr = new Channel("DMR Site", ChannelType.STANDARD);
        dmr.setDecodeConfiguration(dmrConfig);
        Channel nxdn = new Channel("NXDN Site", ChannelType.STANDARD);
        nxdn.setDecodeConfiguration(new DecodeConfigNXDN());
        DecodeEvent dmrVoice = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .protocol(Protocol.DMR)
            .build();
        DecodeEvent nxdnVoice = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .protocol(Protocol.NXDN)
            .build();
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();

        assertTrue(P25ActivityLogMapper.isTypedCallOwnedObservation(dmr, dmrVoice));
        assertTrue(P25ActivityLogMapper.isTypedCallOwnedObservation(nxdn, nxdnVoice));
        assertNull(mapper.map(dmr, dmrVoice));
        assertNull(mapper.map(nxdn, nxdnVoice));
    }

    @Test
    void mapsEncryptedGrant()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createFrom(1811524));
        identifiers.update(APCO25Talkgroup.create(56138));
        identifiers.update(FrequencyConfigurationIdentifier.create(854187500L));
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        identifiers.update(APCO25Nac.create(0x348));
        identifiers.update(APCO25Rfss.create(2));
        identifiers.update(APCO25Site.create(1));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        EncryptionKeyIdentifier encryptionKey =
            EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x84, 101));
        identifiers.update(encryptionKey);
        identifiers.update(P25TalkerAliasIdentifier.create("CAR 201"));

        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED,
                1000L, VoiceServiceOptions.createEncrypted())
            .duration(2500L)
            .details("PHASE 1 CHANNEL GRANT")
            .identifiers(identifiers)
            .build();
        P25EncryptionConfirmationTracker.observe(event, encryptionKey, 1000L);
        P25EncryptionConfirmationTracker.observe(event, encryptionKey, 1360L);

        P25ActivityLogRecords.ActivityEvent record = new P25ActivityLogMapper().map(channel(DecoderType.P25_PHASE1),
            event);

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.Action.ACTIVE, record.action());
        assertEquals("1811524", record.sourceRadioId());
        assertEquals("56138", record.targetId());
        assertEquals("TALKGROUP", record.targetKind());
        assertEquals(854187500L, record.frequencyHertz());
        assertTrue(record.encrypted());
        assertEquals(0x84, record.encryptionAlgorithmId());
        assertEquals(101, record.encryptionKeyId());
        assertEquals(0xBEE00, record.wacn());
        assertEquals(0x348, record.systemId());
        assertEquals(0x348, record.nac());
        assertEquals(2, record.rfss());
        assertEquals(1, record.site());
        assertEquals(GUID, record.guid());
        assertNotNull(record.dedupeKey());
        assertFalse(record.countedCall());
        assertEquals("CAR 201", record.talkerAlias());

        P25ActivityLogRecords.ActivityEvent callStart = new P25ActivityLogMapper().map(
            new P25CallStartEvent(channel(DecoderType.P25_PHASE1), event));
        assertNotNull(callStart);
        assertEquals(P25ActivityLogRecords.Action.CALL, callStart.action());
        assertTrue(callStart.countedCall());
        assertNull(callStart.dedupeKey());

        P25ActivityLogRecords.ActivityEvent grant = new P25ActivityLogMapper().map(new P25GrantObservationEvent(
            channel(DecoderType.P25_PHASE1), null, identifiers, DecodeEventType.CALL_GROUP_ENCRYPTED, 1000L, false));
        assertNotNull(grant);
        assertEquals(P25ActivityLogRecords.Action.GRANT, grant.action());
        assertFalse(grant.countedCall());
        assertNull(grant.dedupeKey());
        assertFalse(grant.encrypted());
        assertNull(grant.encryptionAlgorithmId());
        assertNull(grant.encryptionKeyId());
        P25EncryptionConfirmationTracker.complete(event, 4000L);
    }

    @Test
    void mapsNbfmProtocolFromConfiguredDecoderWhenEventProtocolIsUnknown()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(FrequencyConfigurationIdentifier.create(154_920_000L));
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL, 1_000L)
            .duration(1_000L)
            .identifiers(identifiers)
            .build();
        Channel channel = channel(DecoderType.NBFM);
        channel.setAliasListName("Conventional Lorain Cnty");

        P25ActivityLogRecords.ActivityEvent record = new P25ActivityLogMapper().map(channel, event);

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG, record.contextKind());
        assertEquals("NBFM", record.protocol());
        assertEquals(CONFIGURATION_CONTEXT_KEY, record.contextKey());
        assertEquals("Test Channel", record.channelName());
        assertEquals("Conventional Lorain Cnty", record.aliasListName());
        assertTrue(record.configuredMetadataObserved());
        assertTrue(record.countedCall());
        assertNotNull(record.dedupeKey());

        event.update(1_500L);
        P25ActivityLogRecords.ActivityEvent continuation =
            new P25ActivityLogMapper().map(channel, event);
        DecodeEvent nextCall = DecodeEvent.builder(DecodeEventType.CALL, 2_000L)
            .duration(1_000L)
            .identifiers(identifiers)
            .build();
        P25ActivityLogRecords.ActivityEvent next =
            new P25ActivityLogMapper().map(channel, nextCall);

        assertEquals(record.dedupeKey(), continuation.dedupeKey());
        assertNotEquals(record.dedupeKey(), next.dedupeKey());
    }

    @Test
    void mapsAmAsAProtocolNeutralConventionalAnalogContext()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(FrequencyConfigurationIdentifier.create(118_500_000L));
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL, 1_000L)
            .duration(1_000L)
            .identifiers(identifiers)
            .build();

        P25ActivityLogRecords.ActivityEvent record = new P25ActivityLogMapper().map(channel(DecoderType.AM), event);

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG, record.contextKind());
        assertEquals("AM", record.protocol());
        assertEquals(CONFIGURATION_CONTEXT_KEY, record.contextKey());
    }

    @Test
    void countsOnlyTypedStartForConventionalP25Tracker()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createFrom(1_811_524));
        identifiers.update(APCO25Talkgroup.create(56_138));
        identifiers.update(FrequencyConfigurationIdentifier.create(154_875_000L));
        P25ChannelGrantEvent trackerEvent = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP,
                1_000L, VoiceServiceOptions.createUnencrypted())
            .identifiers(identifiers)
            .build();
        Channel channel = channel(DecoderType.P25_CONVENTIONAL);
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();

        assertNull(mapper.map(channel, trackerEvent));

        P25ActivityLogRecords.ActivityEvent callStart =
            mapper.map(new P25CallStartEvent(channel, trackerEvent));

        assertNotNull(callStart);
        assertEquals(P25ActivityLogRecords.Action.CALL, callStart.action());
        assertTrue(callStart.countedCall());
        assertNull(callStart.dedupeKey());
    }

    @Test
    void mapsEncryptedPatchCallToCanonicalAndEveryDistinctMember()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createFrom(1811524));
        identifiers.update(patchGroup());
        identifiers.update(FrequencyConfigurationIdentifier.create(854187500L));
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        EncryptionKeyIdentifier encryptionKey =
            EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x84, 101));
        identifiers.update(encryptionKey);

        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED,
                1_000L, VoiceServiceOptions.createEncrypted())
            .duration(2_500L)
            .identifiers(identifiers)
            .build();
        P25EncryptionConfirmationTracker.observe(event, encryptionKey, 1_000L);
        P25EncryptionConfirmationTracker.observe(event, encryptionKey, 1_360L);

        P25ActivityLogRecords.ActivityEvent record = new P25ActivityLogMapper().map(
            new P25CallStartEvent(channel(DecoderType.P25_PHASE1), event));

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.Action.CALL, record.action());
        assertEquals("56182", record.targetId());
        assertEquals("PATCH_GROUP", record.targetKind());
        assertEquals(List.of(56180, 56181), record.patchMemberTalkgroupIds());
        assertEquals("1811524", record.sourceRadioId());
        assertTrue(record.encrypted());
        assertTrue(record.countedCall());
        P25EncryptionConfirmationTracker.complete(event, 4_000L);
    }

    @Test
    void patchDedupeKeyIgnoresMemberOrderAndDuplicatesButTracksMembershipChanges()
    {
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();
        P25ActivityLogRecords.ActivityEvent original = mapper.map(channel(DecoderType.P25_PHASE1),
            patchEvent(56181, 56180, 56180));
        P25ActivityLogRecords.ActivityEvent reordered = mapper.map(channel(DecoderType.P25_PHASE1),
            patchEvent(56180, 56181));
        P25ActivityLogRecords.ActivityEvent changed = mapper.map(channel(DecoderType.P25_PHASE1),
            patchEvent(56180, 56181, 56183));

        assertNotNull(original);
        assertNotNull(reordered);
        assertNotNull(changed);
        assertNotNull(original.dedupeKey());
        assertEquals(original.dedupeKey(), reordered.dedupeKey());
        assertNotEquals(original.dedupeKey(), changed.dedupeKey());
    }

    @Test
    void suppressesEncryptionMetricsUntilTwoKnownMatchingObservations()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(56138));
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        EncryptionKeyIdentifier encryptionKey =
            EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x84, 101));
        identifiers.update(encryptionKey);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED,
                1000L, VoiceServiceOptions.createEncrypted())
            .identifiers(identifiers)
            .build();

        P25EncryptionConfirmationTracker.observe(event, encryptionKey, 1000L);
        P25ActivityLogRecords.ActivityEvent record =
            new P25ActivityLogMapper().map(channel(DecoderType.P25_PHASE1), event);

        assertNotNull(record);
        assertFalse(record.encrypted());
        assertNull(record.encryptionAlgorithmId());
        assertNull(record.encryptionKeyId());
        P25EncryptionConfirmationTracker.complete(event, 2000L);
    }

    @Test
    void suppressesRepeatedUnknownEncryptionAlgorithm()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(56138));
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        EncryptionKeyIdentifier encryptionKey =
            EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x08, 8322));
        identifiers.update(encryptionKey);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED,
                1000L, VoiceServiceOptions.createEncrypted())
            .identifiers(identifiers)
            .build();

        P25EncryptionConfirmationTracker.observe(event, encryptionKey, 1000L);
        P25EncryptionConfirmationTracker.observe(event, encryptionKey, 1360L);
        P25ActivityLogRecords.ActivityEvent record =
            new P25ActivityLogMapper().map(channel(DecoderType.P25_PHASE1), event);

        assertNotNull(record);
        assertFalse(record.encrypted());
        assertNull(record.encryptionAlgorithmId());
        assertNull(record.encryptionKeyId());
        P25EncryptionConfirmationTracker.complete(event, 2000L);
    }

    @Test
    void mapsLateTalkerAliasObservationWithoutCallEvent()
    {
        Channel channel = channel(DecoderType.P25_PHASE1);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        TrunkedTalkerAliasEvent event = new TrunkedTalkerAliasEvent(channel, Protocol.APCO25,
            APCO25RadioIdentifier.createFrom(1811524), P25TalkerAliasIdentifier.create(" CAR 201 "),
            identifiers, TrunkedIdentityDomain.STANDARD, 2000L);

        P25ActivityLogRecords.TalkerAliasUpdate update = new P25ActivityLogMapper().map(event);

        assertNotNull(update);
        assertEquals(2000L, update.observedAtEpochMilliseconds());
        assertEquals("GUID:" + GUID, update.contextKey());
        assertEquals(GUID, update.guid());
        assertEquals(0xBEE00, update.wacn());
        assertEquals(0x348, update.systemId());
        assertEquals(1811524, update.radioId());
        assertEquals("CAR 201", update.talkerAlias());
    }

    @Test
    void mapsDmrAndTypeDTalkerAliasDomains()
    {
        Channel dmr = new Channel("DMR", ChannelType.STANDARD);
        DecodeConfigDMR dmrConfig = new DecodeConfigDMR();
        dmrConfig.setChannelMode(DMRChannelMode.TRUNKED);
        dmr.setDecodeConfiguration(dmrConfig);
        dmr.setRadresGuid("323e4567-e89b-12d3-a456-426614174000");
        P25ActivityLogRecords.TalkerAliasUpdate dmrUpdate = new P25ActivityLogMapper().map(
            new TrunkedTalkerAliasEvent(dmr, Protocol.DMR, DMRRadio.createFrom(101),
                DmrTalkerAliasIdentifier.create("ENGINE 4"), new MutableIdentifierCollection(),
                TrunkedIdentityDomain.STANDARD, 2_000L));

        Channel nxdn = new Channel("NXDN Type-D", ChannelType.STANDARD);
        DecodeConfigNXDN nxdnConfig = new DecodeConfigNXDN();
        nxdnConfig.setTransmissionMode(TransmissionMode.TYPE_D);
        nxdn.setDecodeConfiguration(nxdnConfig);
        nxdn.setRadresGuid("423e4567-e89b-12d3-a456-426614174000");
        P25ActivityLogRecords.TalkerAliasUpdate nxdnUpdate = new P25ActivityLogMapper().map(
            new TrunkedTalkerAliasEvent(nxdn, Protocol.NXDN,
                NXDNRadioIdentifier.createTypeDFrom(0x1234), new NXDNTalkerAliasIdentifier("UNIT 12"),
                new MutableIdentifierCollection(), TrunkedIdentityDomain.NXDN_TYPE_D, 3_000L));

        assertNotNull(dmrUpdate);
        assertEquals(P25ActivityLogRecords.IdentityDomain.STANDARD, dmrUpdate.identityDomain());
        assertNotNull(nxdnUpdate);
        assertEquals(P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, nxdnUpdate.identityDomain());
    }

    @Test
    void mapsUserAndResponseActivity()
    {
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();

        assertEquals(P25ActivityLogRecords.Action.JOIN, map(mapper, event(DecodeEventType.AFFILIATE, null)).action());
        assertEquals(P25ActivityLogRecords.Action.REGISTER, map(mapper, event(DecodeEventType.REGISTER, null)).action());
        assertEquals(P25ActivityLogRecords.Action.LOGOUT, map(mapper, event(DecodeEventType.DEREGISTER, null)).action());
        assertEquals(P25ActivityLogRecords.Action.DENIAL,
            map(mapper, event(DecodeEventType.RESPONSE, "DENIED BY SYSTEM")).action());
        assertEquals(P25ActivityLogRecords.Action.BUSY,
            map(mapper, event(DecodeEventType.RESPONSE, "SYSTEM BUSY")).action());
        assertEquals(P25ActivityLogRecords.Action.QUEUED,
            map(mapper, event(DecodeEventType.RESPONSE, "QUEUED")).action());
        assertEquals(P25ActivityLogRecords.Action.PATCH_CREATE,
            map(mapper, event(DecodeEventType.DYNAMIC_REGROUP, "ACTIVATE 56182")).action());
        assertEquals(P25ActivityLogRecords.Action.GPS,
            map(mapper, event(DecodeEventType.GPS, "LOCATION")).action());
    }

    @Test
    void mapsStructuredAffiliationWithoutIdentifierRoleGuessing()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createTo(1811524));
        identifiers.update(APCO25Talkgroup.createAny(56133));
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));

        P25AffiliationEvent event = new P25AffiliationEvent(DecodeEventType.RESPONSE, 1000L,
            P25AffiliationEvent.Outcome.ACCEPTED, APCO25RadioIdentifier.createTo(1811524),
            APCO25Talkgroup.createAny(56133));
        event.setIdentifierCollection(identifiers);
        event.setDetails("ACCEPTED GROUP AFFILIATION");

        P25ActivityLogRecords.ActivityEvent record = new P25ActivityLogMapper().map(channel(DecoderType.P25_PHASE1),
            event);

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.Action.JOIN, record.action());
        assertEquals("1811524", record.sourceRadioId());
        assertEquals("56133", record.targetId());
        assertEquals("TALKGROUP", record.targetKind());
        assertNotNull(record.radioPresenceUpdate());
        assertEquals(1811524, record.radioPresenceUpdate().radioId());
        assertEquals(56133, record.radioPresenceUpdate().talkgroupId());
        assertEquals(P25ActivityLogRecords.RadioPresenceEvidence.AFFILIATION,
            record.radioPresenceUpdate().evidence());
        assertFalse(record.radioPresenceUpdate().cleared());
    }

    @Test
    void mapsAcceptedRegistrationWithoutInventingAnAffiliation()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createTo(1811524));
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));

        P25AffiliationEvent event = new P25AffiliationEvent(DecodeEventType.REGISTER, 1000L,
            P25AffiliationEvent.Outcome.ACCEPTED, APCO25RadioIdentifier.createTo(1811524), null);
        event.setIdentifierCollection(identifiers);
        event.setDetails("ACCEPTED UNIT REGISTRATION");

        P25ActivityLogRecords.ActivityEvent record = new P25ActivityLogMapper().map(channel(DecoderType.P25_PHASE1),
            event);

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.Action.REGISTER, record.action());
        assertNotNull(record.radioPresenceUpdate());
        assertEquals(1811524, record.radioPresenceUpdate().radioId());
        assertNull(record.radioPresenceUpdate().talkgroupId());
        assertEquals(P25ActivityLogRecords.RadioPresenceEvidence.REGISTRATION,
            record.radioPresenceUpdate().evidence());
        assertFalse(record.radioPresenceUpdate().cleared());
    }

    @Test
    void preservesFullyQualifiedStructuredAffiliationTarget()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createTo(1_811_524));
        identifiers.update(APCO25FullyQualifiedTalkgroupIdentifier.createAny(56_133, 0xABCDE, 0x321, 1_200));
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        P25AffiliationEvent event = new P25AffiliationEvent(DecodeEventType.RESPONSE, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED, APCO25RadioIdentifier.createTo(1_811_524),
            APCO25FullyQualifiedTalkgroupIdentifier.createAny(56_133, 0xABCDE, 0x321, 1_200));
        event.setIdentifierCollection(identifiers);

        P25ActivityLogRecords.ActivityEvent record = new P25ActivityLogMapper().map(
            channel(DecoderType.P25_PHASE1), event);

        assertNotNull(record);
        assertEquals("56133", record.targetId());
        assertFullyQualified(record.p25TargetIdentity(), 0xABCDE, 0x321, 1_200);
    }

    @Test
    void rejectedAffiliationDoesNotChangeCurrentState()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));

        P25AffiliationEvent event = new P25AffiliationEvent(DecodeEventType.RESPONSE, 1000L,
            P25AffiliationEvent.Outcome.REJECTED, APCO25RadioIdentifier.createTo(1811524),
            APCO25Talkgroup.createAny(56133));
        event.setIdentifierCollection(identifiers);

        P25ActivityLogRecords.ActivityEvent record = new P25ActivityLogMapper().map(channel(DecoderType.P25_PHASE1),
            event);

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.Action.DENIAL, record.action());
        assertNull(record.radioPresenceUpdate());
    }

    @Test
    void mapsPlainCall()
    {
        DecodeEvent event = event(DecodeEventType.CALL_GROUP, "VOICE");

        P25ActivityLogRecords.ActivityEvent record = new P25ActivityLogMapper().map(channel(DecoderType.P25_PHASE1),
            event);

        assertNotNull(record);
        assertEquals(P25ActivityLogRecords.Action.CALL, record.action());
        assertEquals("1811524", record.sourceRadioId());
        assertEquals("56138", record.targetId());
        assertEquals(854187500L, record.frequencyHertz());
        assertEquals(P25ActivityLogRecords.P25IdentityState.ORDINARY, record.p25TargetIdentity().state());
        assertTrue(record.dedupeKey() != null && !record.dedupeKey().isBlank());
        assertFalse(record.countedCall());
        assertNull(record.radioPresenceUpdate());
    }

    @Test
    void preservesFullyQualifiedP25TargetForActivityAndResolvedCall()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createFrom(1_811_524));
        identifiers.update(APCO25FullyQualifiedTalkgroupIdentifier.createTo(56_138, 0xABCDE, 0x321, 1_200));
        identifiers.update(FrequencyConfigurationIdentifier.create(854_187_500L));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        identifiers.update(DecoderTypeConfigurationIdentifier.create(DecoderType.P25_PHASE1));
        DecodeEvent event = P25DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .duration(1_000L)
            .identifiers(identifiers)
            .build();
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();

        P25ActivityLogRecords.ActivityEvent activity = mapper.map(channel(DecoderType.P25_PHASE1), event);
        P25ActivityLogRecords.ResolvedLogicalCall output = mapper.mapResolvedLogicalCall(
            trafficCompletedCall(1, identifiers, DecoderType.P25_PHASE1, 17,
                new P25SiteIdentity(0x924, 0x649, 1, 1), 1_000L, 2_000L));

        assertNotNull(activity);
        assertEquals("56138", activity.targetId());
        assertFullyQualified(activity.p25TargetIdentity(), 0xABCDE, 0x321, 1_200);
        assertNotNull(output);
        assertEquals(56_138, output.destinationId());
        assertFullyQualified(output.p25TargetIdentity(), 0xABCDE, 0x321, 1_200);

        MutableIdentifierCollection zeroLocalIdentifiers = new MutableIdentifierCollection();
        zeroLocalIdentifiers.update(APCO25RadioIdentifier.createFrom(1_811_524));
        zeroLocalIdentifiers.update(APCO25FullyQualifiedTalkgroupIdentifier.createTo(
            0, 0xABCDE, 0x321, 1_201));
        zeroLocalIdentifiers.update(FrequencyConfigurationIdentifier.create(854_187_500L));
        zeroLocalIdentifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        zeroLocalIdentifiers.update(DecoderTypeConfigurationIdentifier.create(DecoderType.P25_PHASE1));
        DecodeEvent zeroLocalEvent = P25DecodeEvent.builder(DecodeEventType.CALL_GROUP, 2_000L)
            .duration(1_000L)
            .identifiers(zeroLocalIdentifiers)
            .build();
        P25ActivityLogRecords.ActivityEvent zeroLocalActivity = mapper.map(
            channel(DecoderType.P25_PHASE1), zeroLocalEvent);
        P25ActivityLogRecords.ResolvedLogicalCall zeroLocalOutput = mapper.mapResolvedLogicalCall(
            trafficCompletedCall(2, zeroLocalIdentifiers, DecoderType.P25_PHASE1, 17,
                new P25SiteIdentity(0x924, 0x649, 1, 1), 2_000L, 3_000L));

        assertNotNull(zeroLocalActivity);
        assertEquals("0", zeroLocalActivity.targetId());
        assertFullyQualified(zeroLocalActivity.p25TargetIdentity(), 0xABCDE, 0x321, 1_201);
        assertNotNull(zeroLocalOutput);
        assertEquals(0, zeroLocalOutput.destinationId());
        assertEquals(Form.TALKGROUP.name(), zeroLocalOutput.destinationKind());
        assertFullyQualified(zeroLocalOutput.p25TargetIdentity(), 0xABCDE, 0x321, 1_201);
    }

    @Test
    void preservesFullyQualifiedPatchPrimary()
    {
        PatchGroup patch = new PatchGroup(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(56_182, 0xABCDE, 0x321, 1_201));
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(56_180));
        patch.addPatchedTalkgroup(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(56_181, 0xABCDE, 0x321, 1_202));
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25PatchGroup.create(patch));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        DecodeEvent event = P25DecodeEvent.builder(DecodeEventType.CALL_PATCH_GROUP, 1_000L)
            .identifiers(identifiers)
            .build();

        P25ActivityLogRecords.ActivityEvent activity = new P25ActivityLogMapper().map(
            channel(DecoderType.P25_PHASE1), event);

        assertNotNull(activity);
        assertEquals("56182", activity.targetId());
        assertEquals(Form.PATCH_GROUP.name(), activity.targetKind());
        assertFullyQualified(activity.p25TargetIdentity(), 0xABCDE, 0x321, 1_201);
        assertEquals(List.of(56_180, 56_181), activity.patchMemberTalkgroupIds());
        assertEquals(P25ActivityLogRecords.P25IdentityState.ORDINARY,
            activity.p25PatchMemberIdentities().get(0).targetIdentity().state());
        assertFullyQualified(activity.p25PatchMemberIdentities().get(1).targetIdentity(),
            0xABCDE, 0x321, 1_202);
    }

    @Test
    void mapsDmrAndNxdnTrafficLegsToLogicalCallsNotConventionalOutputs()
    {
        MutableIdentifierCollection dmrIdentifiers = new MutableIdentifierCollection();
        dmrIdentifiers.update(DMRRadio.createFrom(101));
        dmrIdentifiers.update(DMRTalkgroup.create(91));
        dmrIdentifiers.update(DecoderTypeConfigurationIdentifier.create(DecoderType.DMR));
        CompletedAudioCall dmr = trafficCompletedCall(10, dmrIdentifiers, DecoderType.DMR, 0, null,
            4_000L, 5_000L);

        MutableIdentifierCollection nxdnIdentifiers = new MutableIdentifierCollection();
        nxdnIdentifiers.update(NXDNRadioIdentifier.createTypeDFrom(0x1134));
        nxdnIdentifiers.update(NXDNTalkgroupIdentifier.createTypeDTo(0x2223));
        nxdnIdentifiers.update(DecoderTypeConfigurationIdentifier.create(DecoderType.NXDN));
        CompletedAudioCall nxdn = trafficCompletedCall(11, nxdnIdentifiers, DecoderType.NXDN, 0, null,
            6_000L, 7_000L);
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();

        P25ActivityLogRecords.ResolvedLogicalCall dmrResolved = mapper.mapResolvedLogicalCall(dmr);
        P25ActivityLogRecords.ResolvedLogicalCall nxdnResolved = mapper.mapResolvedLogicalCall(nxdn);
        assertNotNull(dmrResolved);
        assertEquals(Protocol.DMR.name(), dmrResolved.protocol());
        assertEquals(91, dmrResolved.destinationId());
        assertEquals(101, dmrResolved.sourceRadioId());
        assertTrue(dmrResolved.learnedP25Sites().isEmpty());
        assertNotNull(nxdnResolved);
        assertEquals(Protocol.NXDN.name(), nxdnResolved.protocol());
        assertEquals(P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, nxdnResolved.identityDomain());
        assertNull(mapper.mapConventionalCallOutput(dmr, P25ActivityLogRecords.CallOutput.RECORDED));
        assertNull(mapper.mapConventionalCallOutput(nxdn, P25ActivityLogRecords.CallOutput.STREAMED));
    }

    @Test
    void mapsConventionalCallOutputToSiteTalkgroupAndCallStartHour()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(56138));
        identifiers.update(ChannelConfigurationIdentifier.create(CONFIGURATION_ID));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        AudioCallId callId = new AudioCallId(1L, 2L, 1);
        CallLegSource source = new CallLegSource(DecoderType.P25_CONVENTIONAL, CONFIGURATION_ID,
            "P25 Conventional", GUID, 0, null, false);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), 3_600_123L, 3_605_000L, 1, 1, 3_600_123L, 3_605_000L,
            false, true, CallEncryptionState.CLEAR, true,
            AudioCallRecordingMetadata.captureAtSnapshot(null, identifiers),
            VoiceCallQuality.EMPTY, CallLegId.from(callId), source, null);
        CompletedAudioCall call = new CompletedAudioCall(snapshot, List.of(new float[800]));

        P25ActivityLogRecords.ConventionalCallOutput metric = new P25ActivityLogMapper().mapConventionalCallOutput(call,
            P25ActivityLogRecords.CallOutput.RECORDED);

        assertNotNull(metric);
        assertEquals(3_600_123L, metric.callStartEpochMilliseconds());
        assertEquals(GUID, metric.guid());
        assertEquals(CONFIGURATION_CONTEXT_KEY, metric.contextKey());
        assertEquals(56138, metric.talkgroupId());
        assertEquals(P25ActivityLogRecords.CallOutput.RECORDED, metric.output());
    }

    @Test
    void mapsConventionalOutputWithoutTalkgroupOrGuidUsingConfigurationIdentity()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(ChannelConfigurationIdentifier.create(CONFIGURATION_ID));
        identifiers.update(FrequencyConfigurationIdentifier.create(154_310_000L));
        identifiers.update(DecoderTypeConfigurationIdentifier.create(DecoderType.NBFM));
        AudioCallId callId = new AudioCallId(7L, 8L, 0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), 7_200_123L, 7_205_000L, 1, 1, 7_200_123L, 7_205_000L,
            false, true, CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), null, null);

        P25ActivityLogRecords.ConventionalCallOutput metric = new P25ActivityLogMapper().mapConventionalCallOutput(
            new CompletedAudioCall(snapshot, List.of(new float[800])),
            P25ActivityLogRecords.CallOutput.STREAMED);

        assertNotNull(metric);
        assertEquals(CONFIGURATION_CONTEXT_KEY, metric.contextKey());
        assertNull(metric.guid());
        assertEquals(154_310_000L, metric.frequencyHertz());
        assertEquals(0, metric.talkgroupId());
        assertEquals(P25ActivityLogRecords.CallOutput.STREAMED, metric.output());
    }

    @Test
    void mapsNxdnConventionalOutputWithoutGuidUsingConfigurationIdentity()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(ChannelConfigurationIdentifier.create(CONFIGURATION_ID));
        identifiers.update(FrequencyConfigurationIdentifier.create(461_125_000L));
        identifiers.update(DecoderTypeConfigurationIdentifier.create(DecoderType.NXDN));
        AudioCallId callId = new AudioCallId(9L, 10L, 0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), 7_200_123L, 7_205_000L, 1, 1, 7_200_123L, 7_205_000L,
            false, true, CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), null, null);

        P25ActivityLogRecords.ConventionalCallOutput metric = new P25ActivityLogMapper().mapConventionalCallOutput(
            new CompletedAudioCall(snapshot, List.of(new float[800])),
            P25ActivityLogRecords.CallOutput.RECORDED);

        assertNotNull(metric);
        assertEquals(CONFIGURATION_CONTEXT_KEY, metric.contextKey());
        assertEquals(461_125_000L, metric.frequencyHertz());
        assertEquals(P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C, metric.identityDomain());
    }

    @Test
    void preservesNxdnTypeDIdentityDomainForActivityAndCompletedOutputs()
    {
        DecodeConfigNXDN config = new DecodeConfigNXDN(TransmissionMode.TYPE_D);
        config.setChannelMode(NXDNChannelMode.TRUNKED);
        Channel channel = new Channel("NXDN Type-D Site", ChannelType.STANDARD);
        channel.setDecodeConfiguration(config);
        channel.setRadresGuid(GUID);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(ChannelConfigurationIdentifier.create(CONFIGURATION_ID));
        identifiers.update(NXDNRadioIdentifier.createTypeDFrom(0x1134));
        identifiers.update(NXDNTalkgroupIdentifier.createTypeDTo(0x2223));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        identifiers.update(DecoderTypeConfigurationIdentifier.create(DecoderType.NXDN));
        DecodeEvent signaling = DecodeEvent.builder(DecodeEventType.PAGE, 2_000L)
            .protocol(Protocol.NXDN)
            .identifiers(identifiers)
            .build();

        P25ActivityLogMapper mapper = new P25ActivityLogMapper();
        P25ActivityLogRecords.ActivityEvent activity = mapper.map(channel, signaling);
        AudioCallId callId = new AudioCallId(9L, 10L, 0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), 7_200_123L, 7_205_000L, 1, 1, 7_200_123L, 7_205_000L,
            false, true, CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), null, null);
        P25ActivityLogRecords.ConventionalCallOutput output = mapper.mapConventionalCallOutput(
            new CompletedAudioCall(snapshot, List.of(new float[800])),
            P25ActivityLogRecords.CallOutput.RECORDED);

        assertNotNull(activity);
        assertEquals(P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, activity.identityDomain());
        assertNotNull(output);
        assertEquals(P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, output.identityDomain());
    }

    @Test
    void mapsPrivateCallOutputDestinationAndSourceRadios()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(ChannelConfigurationIdentifier.create(CONFIGURATION_ID));
        identifiers.update(APCO25RadioIdentifier.createFrom(1_811_524));
        identifiers.update(APCO25RadioIdentifier.createTo(1_822_001));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        AudioCallId callId = new AudioCallId(7L, 10L, 1);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), 7_200_123L, 7_205_000L, 1, 1, 7_200_123L, 7_205_000L,
            false, true, CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), null, null);

        P25ActivityLogRecords.ConventionalCallOutput metric = new P25ActivityLogMapper().mapConventionalCallOutput(
            new CompletedAudioCall(snapshot, List.of(new float[800])),
            P25ActivityLogRecords.CallOutput.RECORDED);

        assertNotNull(metric);
        assertEquals(1_822_001, metric.talkgroupId());
        assertEquals("RADIO", metric.targetKind());
        assertEquals(1_811_524, metric.sourceRadioId());
    }

    @Test
    void usesStableConfigurationIdentityForOutputWithoutGuid()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(ChannelConfigurationIdentifier.create(CONFIGURATION_ID));
        identifiers.update(FrequencyConfigurationIdentifier.create(451_012_500L));
        identifiers.update(DecoderTypeConfigurationIdentifier.create(DecoderType.DMR));
        AudioCallId callId = new AudioCallId(7L, 9L, 1);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), 7_200_123L, 7_205_000L, 1, 1, 7_200_123L, 7_205_000L,
            false, true, CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), null, null);

        P25ActivityLogRecords.ConventionalCallOutput metric = new P25ActivityLogMapper().mapConventionalCallOutput(
            new CompletedAudioCall(snapshot, List.of(new float[800])),
            P25ActivityLogRecords.CallOutput.RECORDED);

        assertNotNull(metric);
        assertEquals(CONFIGURATION_CONTEXT_KEY, metric.contextKey());
    }

    @Test
    void preservesPatchMembersForConventionalCallOutput()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(ChannelConfigurationIdentifier.create(CONFIGURATION_ID));
        identifiers.update(patchGroup());
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        AudioCallId callId = new AudioCallId(1L, 2L, 1);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), 3_600_123L, 3_605_000L, 1, 1, 3_600_123L, 3_605_000L,
            false, true, CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), null, null);

        P25ActivityLogRecords.ConventionalCallOutput metric = new P25ActivityLogMapper().mapConventionalCallOutput(
            new CompletedAudioCall(snapshot, List.of(new float[800])),
            P25ActivityLogRecords.CallOutput.STREAMED);

        assertNotNull(metric);
        assertEquals(56182, metric.talkgroupId());
        assertEquals("PATCH_GROUP", metric.targetKind());
        assertEquals(List.of(56180, 56181), metric.patchMemberTalkgroupIds());
        assertEquals(P25ActivityLogRecords.CallOutput.STREAMED, metric.output());
    }

    @Test
    void mapsContextKindFromDecoderType()
    {
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();
        Channel conventionalChannel = channel(DecoderType.P25_CONVENTIONAL);
        conventionalChannel.setAliasListName("Elyria PD");
        Channel trunkedChannel = channel(DecoderType.P25_PHASE1);
        trunkedChannel.setSite("Lorain");
        trunkedChannel.setAliasListName("MARCS-IP");

        P25ActivityLogRecords.ActivityEvent conventional = mapper.map(conventionalChannel,
            event(DecodeEventType.CALL_GROUP, "VOICE", DecoderType.P25_PHASE1));
        P25ActivityLogRecords.ActivityEvent trunked = mapper.map(trunkedChannel,
            event(DecodeEventType.CALL_GROUP, "VOICE", DecoderType.P25_CONVENTIONAL));

        assertNotNull(conventional);
        assertNotNull(trunked);
        assertEquals(P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25, conventional.contextKind());
        assertEquals(P25ActivityLogRecords.ContextKind.TRUNKED_SITE, trunked.contextKind());
        assertEquals("Test Channel", conventional.channelName());
        assertEquals("Elyria PD", conventional.aliasListName());
        assertTrue(conventional.configuredMetadataObserved());
        assertEquals("Lorain", trunked.channelName());
        assertEquals("MARCS-IP", trunked.aliasListName());
        assertTrue(trunked.configuredMetadataObserved());
    }

    @Test
    void rejectsTrunkedActivityWithoutGuid()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createFrom(1811524));
        identifiers.update(APCO25Talkgroup.create(56138));

        DecodeEvent event = P25DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1000L)
            .details("VOICE")
            .identifiers(identifiers)
            .build();

        Channel channel = channel(DecoderType.P25_PHASE1);
        channel.setRadresGuid(null);
        P25ActivityLogRecords.ActivityEvent record = new P25ActivityLogMapper().map(channel, event);
        assertNull(record);
    }

    @Test
    void mapsSiteSnapshot()
    {
        Channel channel = new Channel("Control", ChannelType.STANDARD);
        channel.setSite(" Example Site ");
        channel.setAliasListName("Example System");
        channel.setRadresGuid(GUID);

        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25-1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x348, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x348, 2, 1, null, true),
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", "00-0821",
                856137500L, null, false, 1)),
            List.of(new P25NetworkConfigurationSnapshot.NeighborSite(0x348, null, 2, 2,
                null, "00-0661", 855137500L, null, "ACTIVE")),
            List.of(new P25NetworkConfigurationSnapshot.FrequencyBand(0, false,
                851006250L, 12500, 6250L, -45000000L, 1)),
            List.of(new P25NetworkConfigurationSnapshot.PatchGroup(56182, 1,
                List.of(56180), List.of(1811524))),
            List.of(new P25NetworkConfigurationSnapshot.TalkerAlias(1811524, "WPFF205")), null,
            List.of(new P25NetworkConfigurationSnapshot.ForeignSystemBand(0xBEE00, 0x9EF, 4, 1,
                935_012_500L, 12_500L, -39_000_000L)));

        P25ActivityLogRecords.SiteSnapshot record =
            new P25ActivityLogMapper().map(new SiteMetadataEvent(channel, snapshot, 5000L));

        assertNotNull(record);
        assertEquals(GUID, record.guid());
        assertEquals("Example Site", record.channelName());
        assertEquals(856137500L, record.currentControlHertz());
        assertEquals(0xBEE00, record.wacn());
        assertEquals(0x348, record.systemId());
        assertEquals(0x348, record.nac());
        assertEquals(2, record.rfss());
        assertEquals(1, record.site());
        assertEquals(Boolean.TRUE, record.activeRfssNetworkConnection());
        assertEquals(1, record.channels().size());
        assertEquals("primary_control", record.channels().get(0).role());
        assertEquals(1, record.patchGroups().size());
        assertEquals(56182, record.patchGroups().get(0).patchGroup());
        assertEquals(1, record.foreignSystemBands().size());
        assertEquals(0x9EF, record.foreignSystemBands().getFirst().system());
        assertNotNull(record.snapshotHash());
    }

    @Test
    void mapsCurrentSiteIdentityWithoutNetworkStatus()
    {
        Channel channel = new Channel("Control", ChannelType.STANDARD);
        channel.setRadresGuid(GUID);
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25-1", null,
            new P25NetworkConfigurationSnapshot.CurrentSite(0x321, 0x456, 7, 9, 2, false),
            List.of(), List.of(), List.of(), List.of(), List.of());

        P25ActivityLogRecords.SiteSnapshot record =
            new P25ActivityLogMapper().map(new SiteMetadataEvent(channel, snapshot, 5_000L));

        assertNotNull(record);
        assertEquals(0x321, record.systemId());
        assertEquals(0x456, record.nac());
        assertEquals(7, record.rfss());
        assertEquals(9, record.site());
        assertEquals(2, record.lra());
        assertEquals(Boolean.FALSE, record.activeRfssNetworkConnection());
    }

    @Test
    void siteSnapshotFallsBackToChannelNameWhenConfiguredSiteIsBlank()
    {
        Channel channel = new Channel(" Control ", ChannelType.STANDARD);
        channel.setSite(" ");
        channel.setRadresGuid(GUID);

        P25ActivityLogRecords.SiteSnapshot record = new P25ActivityLogMapper().map(
            new SiteMetadataEvent(channel, siteMetadataSnapshot(1_000L, true), 1_000L));

        assertNotNull(record);
        assertEquals("Control", record.channelName());
    }

    @Test
    void volatileTimingDoesNotChangeSiteInventoryHash()
    {
        Channel channel = new Channel("Example Site", ChannelType.STANDARD);
        channel.setRadresGuid(GUID);
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();

        P25ActivityLogRecords.SiteSnapshot first =
            mapper.map(new SiteMetadataEvent(channel, siteMetadataSnapshot(1_000L, true), 1_000L));
        P25ActivityLogRecords.SiteSnapshot clockUpdate =
            mapper.map(new SiteMetadataEvent(channel, siteMetadataSnapshot(2_000L, true), 2_000L));
        P25ActivityLogRecords.SiteSnapshot serviceUpdate =
            mapper.map(new SiteMetadataEvent(channel, siteMetadataSnapshot(2_000L, false), 2_001L));

        assertEquals(first.snapshotHash(), clockUpdate.snapshotHash());
        assertEquals(2_000L, clockUpdate.siteStatus().broadcastClockEpochMilliseconds());
        assertNotEquals(first.snapshotHash(), serviceUpdate.snapshotHash());
    }

    private static P25NetworkConfigurationSnapshot siteMetadataSnapshot(long broadcastClock, boolean voiceService)
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x348, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x348, 2, 1, null, true),
            List.of(), List.of(), List.of(), List.of(), List.of(),
            new P25NetworkConfigurationSnapshot.SiteStatus(broadcastClock, Math.toIntExact(broadcastClock / 10),
                true, "REQUEST", 30, true, 0, voiceService),
            List.of());
    }

    private static CompletedAudioCall trafficCompletedCall(long sequence, MutableIdentifierCollection identifiers,
                                                            DecoderType decoderType, long aliasListId,
                                                            P25SiteIdentity siteIdentity, long start, long end)
    {
        AudioCallId callId = new AudioCallId(100, sequence, 1);
        CallLegId callLegId = CallLegId.from(callId);
        CallLegSource source = new CallLegSource(decoderType, "traffic-configuration", "Traffic Site", GUID,
            aliasListId, siteIdentity, true);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null, identifiers, Set.of(), start, end,
            1, 1, start, end, false, true, CallEncryptionState.CLEAR, true,
            AudioCallRecordingMetadata.captureAtSnapshot(null, identifiers), VoiceCallQuality.EMPTY,
            callLegId, source, null);
        return new CompletedAudioCall(snapshot, List.of(new float[800]));
    }

    private static DecodeEvent event(DecodeEventType eventType, String details)
    {
        return event(eventType, details, null);
    }

    private static DecodeEvent event(DecodeEventType eventType, String details, DecoderType decoderType)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createFrom(1811524));
        identifiers.update(APCO25Talkgroup.create(56138));
        identifiers.update(FrequencyConfigurationIdentifier.create(854187500L));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));

        if(decoderType != null)
        {
            identifiers.update(DecoderTypeConfigurationIdentifier.create(decoderType));
        }

        return P25DecodeEvent.builder(eventType, 1000L)
            .duration(1000L)
            .details(details)
            .identifiers(identifiers)
            .build();
    }

    private static P25ActivityLogRecords.ActivityEvent map(P25ActivityLogMapper mapper, DecodeEvent event)
    {
        return mapper.map(channel(DecoderType.P25_PHASE1), event);
    }

    private static Channel channel(DecoderType decoderType)
    {
        Channel channel = new Channel("Test Channel",
            decoderType == DecoderType.P25_PHASE1 ? ChannelType.TRAFFIC : ChannelType.STANDARD);
        channel.setDecodeConfiguration(switch(decoderType)
        {
            case AM -> new DecodeConfigAM();
            case P25_CONVENTIONAL -> new DecodeConfigP25Conventional();
            case NBFM -> new DecodeConfigNBFM();
            default -> new DecodeConfigP25Phase1();
        });
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setRadresGuid(GUID);
        return channel;
    }

    private static APCO25PatchGroup patchGroup()
    {
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(56182));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(56181));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(56180));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(56180));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(56182));
        return APCO25PatchGroup.create(patchGroup);
    }

    private static DecodeEvent patchEvent(Integer... members)
    {
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(56182));

        for(Integer member: members)
        {
            patchGroup.getPatchedTalkgroupIdentifiers().add(APCO25Talkgroup.create(member));
        }

        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createFrom(1811524));
        identifiers.update(APCO25PatchGroup.create(patchGroup));
        identifiers.update(FrequencyConfigurationIdentifier.create(854187500L));
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));

        return P25DecodeEvent.builder(DecodeEventType.CALL_PATCH_GROUP, 1_000L)
            .duration(1_000L)
            .details("PATCH VOICE")
            .identifiers(identifiers)
            .build();
    }

    private static DecodeEvent dmrSignaling(DecodeEventType eventType, String details, int source, int target,
                                             long frequency, int timeslot)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(DMRRadio.createFrom(source));
        identifiers.update(DMRTalkgroup.create(target));
        identifiers.update(FrequencyConfigurationIdentifier.create(frequency));
        identifiers.update(SiteGuidConfigurationIdentifier.create(GUID));
        return DecodeEvent.builder(eventType, 1_000L)
            .protocol(Protocol.DMR)
            .channel(new StandardChannel(frequency))
            .details(details)
            .identifiers(identifiers)
            .timeslot(timeslot)
            .build();
    }

    private static void assertFullyQualified(P25ActivityLogRecords.P25TargetIdentity identity, int wacn,
                                             int system, int talkgroup)
    {
        assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED, identity.state());
        assertEquals(wacn, identity.homeWacn());
        assertEquals(system, identity.homeSystemId());
        assertEquals(talkgroup, identity.homeTalkgroupId());
    }
}
