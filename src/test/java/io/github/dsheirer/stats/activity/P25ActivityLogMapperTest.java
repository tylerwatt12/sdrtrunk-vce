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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteGuidConfigurationIdentifier;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25DecodeEvent;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Rfss;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Site;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25ActivityLogMapperTest
{
    private static final String GUID = "123e4567-e89b-12d3-a456-426614174000";

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
        identifiers.update(EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x84, 101)));

        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED,
                1000L, VoiceServiceOptions.createEncrypted())
            .duration(2500L)
            .details("PHASE 1 CHANNEL GRANT")
            .identifiers(identifiers)
            .build();

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
        assertEquals(2, record.rfss());
        assertEquals(1, record.site());
        assertEquals(GUID, record.guid());
        assertNotNull(record.dedupeKey());
        assertFalse(record.countedCall());

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
        assertNotNull(record.affiliationUpdate());
        assertEquals(1811524, record.affiliationUpdate().radioId());
        assertEquals(56133, record.affiliationUpdate().talkgroupId());
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
        assertNull(record.affiliationUpdate());
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
        assertTrue(record.dedupeKey() != null && !record.dedupeKey().isBlank());
        assertFalse(record.countedCall());
    }

    @Test
    void mapsContextKindFromDecoderType()
    {
        P25ActivityLogMapper mapper = new P25ActivityLogMapper();

        P25ActivityLogRecords.ActivityEvent conventional = mapper.map(channel(DecoderType.P25_CONVENTIONAL),
            event(DecodeEventType.CALL_GROUP, "VOICE", DecoderType.P25_PHASE1));
        P25ActivityLogRecords.ActivityEvent trunked = mapper.map(channel(DecoderType.P25_PHASE1),
            event(DecodeEventType.CALL_GROUP, "VOICE", DecoderType.P25_CONVENTIONAL));

        assertNotNull(conventional);
        assertNotNull(trunked);
        assertEquals(P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25, conventional.contextKind());
        assertEquals(P25ActivityLogRecords.ContextKind.TRUNKED_SITE, trunked.contextKind());
    }

    @Test
    void skipsActivityWithoutGuid()
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
        assertNull(new P25ActivityLogMapper().map(channel, event));
    }

    @Test
    void mapsSiteSnapshot()
    {
        Channel channel = new Channel("Example Site", ChannelType.STANDARD);
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
            List.of(new P25NetworkConfigurationSnapshot.TalkerAlias(1811524, "WPFF205")));

        P25ActivityLogRecords.SiteSnapshot record =
            new P25ActivityLogMapper().map(new SiteMetadataEvent(channel, snapshot, 5000L));

        assertNotNull(record);
        assertEquals(GUID, record.guid());
        assertEquals(856137500L, record.currentControlHertz());
        assertEquals(0xBEE00, record.wacn());
        assertEquals(0x348, record.systemId());
        assertEquals(2, record.rfss());
        assertEquals(1, record.site());
        assertEquals(1, record.channels().size());
        assertEquals("primary_control", record.channels().get(0).role());
        assertEquals(1, record.patchGroups().size());
        assertEquals(56182, record.patchGroups().get(0).patchGroup());
        assertNotNull(record.snapshotHash());
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
        channel.setDecodeConfiguration(decoderType == DecoderType.P25_CONVENTIONAL ?
            new DecodeConfigP25Conventional() : new DecodeConfigP25Phase1());
        channel.setRadresGuid(GUID);
        return channel;
    }
}
