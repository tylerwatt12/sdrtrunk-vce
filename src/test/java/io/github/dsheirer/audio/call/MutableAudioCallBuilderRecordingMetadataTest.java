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
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.P25Subscriber;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.RadioResolveConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutableAudioCallBuilderRecordingMetadataTest
{
    @Test
    void keepsRadioResolveIdSeparateFromDisplayNames()
    {
        String radioResolveId = "11111111-2222-4333-8444-555555555555";
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            SystemConfigurationIdentifier.create("County"),
            SiteConfigurationIdentifier.create("North"),
            RadioResolveConfigurationIdentifier.create(radioResolveId)));

        AudioCallRecordingMetadata metadata = AudioCallRecordingMetadata.captureAtSnapshot(null, identifiers);
        assertEquals(radioResolveId, metadata.radioResolveId());

        AudioCallRecordingMetadata withoutRadioResolveId = AudioCallRecordingMetadata.captureAtSnapshot(null,
            new IdentifierCollection(List.of(SystemConfigurationIdentifier.create("County"),
                SiteConfigurationIdentifier.create("North"))));
        assertNull(withoutRadioResolveId.radioResolveId());
    }

    @Test
    void freezesAliasNamesAndRecordDecisionWhenIdentifiersJoinTheCall()
    {
        Alias destinationAlias = new Alias("Fire Dispatch");
        destinationAlias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 56138));
        destinationAlias.setDescription("Primary fire dispatch");
        destinationAlias.setGroup("Fire");
        destinationAlias.setRecordable(true);
        Alias sourceAlias = new Alias("Engine 12");
        sourceAlias.setMatchIdentifier(new Radio(Protocol.APCO25, 120012));
        sourceAlias.setDescription("Station 12 engine");
        sourceAlias.setGroup("Apparatus");
        AliasList aliasList = new AliasList(
            new AliasListDefinition("Primary", AliasListFamily.P25));
        aliasList.addAlias(destinationAlias);
        aliasList.addAlias(sourceAlias);
        MutableAudioCallBuilder builder = new MutableAudioCallBuilder(aliasList, 1);

        builder.addIdentifiers(List.of(APCO25Talkgroup.create(56138),
            APCO25RadioIdentifier.createFrom(120012)));

        destinationAlias.setName("Renamed Dispatch");
        destinationAlias.setDescription("Changed destination description");
        destinationAlias.setGroup("Changed destination group");
        destinationAlias.setRecordable(false);
        sourceAlias.setName("Renamed Radio");
        sourceAlias.setDescription("Changed source description");
        sourceAlias.setGroup("Changed source group");
        AudioCallRecordingMetadata metadata = builder.getRecordingMetadata();

        assertEquals("APCO25:TALKGROUP:56138", metadata.destinationIdentity());
        assertEquals("Fire Dispatch", metadata.destinationAlias());
        assertEquals("Primary fire dispatch", metadata.destinationDescription());
        assertEquals("Fire", metadata.destinationGroup());
        assertEquals("Engine 12", metadata.sourceAlias());
        assertEquals("Station 12 engine", metadata.sourceDescription());
        assertEquals("Apparatus", metadata.sourceGroup());
        assertTrue(metadata.destinationRecordEnabled());
        assertTrue(builder.isRecordAudio());
        assertSame(metadata, builder.getRecordingMetadata());
    }

    @Test
    void phase2MatchersRetainTheirExactAndRangeIdentities()
    {
        Alias exactAlias = new Alias("Phase 2 Exact");
        exactAlias.setMatchIdentifier(new Talkgroup(Protocol.APCO25_PHASE2, 101));
        AliasList exactList = new AliasList(
            new AliasListDefinition("Exact", AliasListFamily.P25));
        exactList.addAlias(exactAlias);

        AudioCallRecordingMetadata.DestinationDecision exact =
            AudioCallRecordingMetadata.captureDestination(exactList, APCO25Talkgroup.create(101));
        assertEquals("exact:APCO-25 P2:101", exact.matcherIdentity());

        Alias rangeAlias = new Alias("Phase 2 Range");
        rangeAlias.setMatchIdentifier(new TalkgroupRange(Protocol.APCO25_PHASE2, 200, 299));
        AliasList rangeList = new AliasList(
            new AliasListDefinition("Range", AliasListFamily.P25));
        rangeList.addAlias(rangeAlias);

        AudioCallRecordingMetadata.DestinationDecision range =
            AudioCallRecordingMetadata.captureDestination(rangeList, APCO25Talkgroup.create(250));
        assertEquals("range:APCO-25 P2:200:299", range.matcherIdentity());
    }

    @Test
    void decodedHomeIdentityUsesTheLocalTalkgroupMatcherAndValue()
    {
        Alias alias = new Alias("ISSI Dispatch");
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 99));
        alias.setRecordable(true);
        AliasList aliasList = new AliasList(new AliasListDefinition("P25", AliasListFamily.P25));
        aliasList.addAlias(alias);

        AudioCallRecordingMetadata.DestinationDecision decision = AudioCallRecordingMetadata.captureDestination(
            aliasList, APCO25FullyQualifiedTalkgroupIdentifier.createTo(99, 0xABCDE, 0x321, 1200));

        assertEquals("ISSI Dispatch", decision.aliasName());
        assertEquals("99", decision.value());
        assertEquals("v1-g-abcde-321-1200", decision.receivedIdentity());
        assertEquals("exact:APCO-25:99", decision.matcherIdentity());
        assertTrue(decision.recordEnabled());
    }

    @Test
    void privateRadioDestinationUsesItsLocalAliasPolicyAndCanonicalHomeIdentity()
    {
        Alias alias = new Alias("Roaming Subscriber");
        alias.setMatchIdentifier(new Radio(Protocol.APCO25, 123));
        alias.setRecordable(true);
        AliasList aliasList = new AliasList(new AliasListDefinition("P25", AliasListFamily.P25));
        aliasList.addAlias(alias);

        AudioCallRecordingMetadata.DestinationDecision decision = AudioCallRecordingMetadata.captureDestination(
            aliasList, APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(
                123, 0xABCDE, 0x321, 9_001));

        assertEquals("Roaming Subscriber", decision.aliasName());
        assertEquals("123", decision.value());
        assertEquals("v1-r-abcde-321-9001", decision.receivedIdentity());
        assertEquals("exact:APCO-25:123", decision.matcherIdentity());
        assertTrue(decision.recordEnabled());
    }

    @Test
    void identityOnlyPrivateRadioDoesNotBorrowAnOrdinaryLocalAlias()
    {
        Alias alias = new Alias("Unrelated local subscriber");
        alias.setMatchIdentifier(new Radio(Protocol.APCO25, 9_001));
        AliasList aliasList = new AliasList(new AliasListDefinition("P25", AliasListFamily.P25));
        aliasList.addAlias(alias);

        AudioCallRecordingMetadata.DestinationDecision decision = AudioCallRecordingMetadata.captureDestination(
            aliasList, APCO25FullyQualifiedRadioIdentifier.createTo(9_001, 0xABCDE, 0x321, 9_001));

        assertNull(decision.aliasName());
        assertEquals("v1-r-abcde-321-9001", decision.matcherIdentity(),
            "the canonical received identity remains available even when no alias matches");
    }

    @Test
    void sourceAndPrivateCallDestinationRetainCanonicalAndWorkingIdentitiesSeparately()
    {
        Alias sourceAlias = new Alias("Source Subscriber");
        sourceAlias.setMatchIdentifier(new P25Subscriber(0xBEE00, 0x348, 2_115_288));
        Alias destinationAlias = new Alias("Destination Subscriber");
        destinationAlias.setMatchIdentifier(new P25Subscriber(0xABCDE, 0x456, 9_001));
        destinationAlias.setRecordable(true);
        AliasList aliasList = new AliasList(new AliasListDefinition("P25", AliasListFamily.P25));
        aliasList.addAlias(sourceAlias);
        aliasList.addAlias(destinationAlias);

        APCO25FullyQualifiedRadioIdentifier source =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                501, 0xBEE00, 0x348, 2_115_288);
        APCO25FullyQualifiedRadioIdentifier destination =
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(
                777, 0xABCDE, 0x456, 9_001);
        AudioCallRecordingMetadata metadata = AudioCallRecordingMetadata.captureAtSnapshot(aliasList,
            new IdentifierCollection(List.of(source, destination)));

        assertEquals(new P25SubscriberIdentity(0xBEE00, 0x348, 2_115_288),
            metadata.sourceP25Identity());
        assertEquals(501, metadata.sourceObservedWorkingId());
        assertEquals("501", metadata.sourceValue());
        assertEquals("Source Subscriber", metadata.sourceAlias());
        assertEquals(new P25SubscriberIdentity(0xABCDE, 0x456, 9_001),
            metadata.destinationP25Identity());
        assertEquals(777, metadata.destinationObservedWorkingId());
        assertEquals("777", metadata.destinationValue());
        assertEquals("Destination Subscriber", metadata.destinationAlias());
        assertEquals("canonical:APCO25:ABCDE.456.9001", metadata.destinationMatcherIdentity());
    }

    @Test
    void ordinaryAndIdentityOnlyQualifiedRadiosHaveNoWorkingAssignment()
    {
        AudioCallRecordingMetadata ordinary = AudioCallRecordingMetadata.captureAtSnapshot(null,
            new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(501),
                APCO25RadioIdentifier.createTo(777))));

        assertEquals("501", ordinary.sourceValue());
        assertNull(ordinary.sourceP25Identity());
        assertNull(ordinary.sourceObservedWorkingId());
        assertEquals("777", ordinary.destinationValue());
        assertNull(ordinary.destinationP25Identity());
        assertNull(ordinary.destinationObservedWorkingId());

        AudioCallRecordingMetadata nativeIdentity = AudioCallRecordingMetadata.captureAtSnapshot(null,
            new IdentifierCollection(List.of(
                APCO25FullyQualifiedRadioIdentifier.createFrom(9_001, 0xABCDE, 0x321, 9_001),
                APCO25FullyQualifiedRadioIdentifier.createTo(9_002, 0xABCDE, 0x321, 9_002))));

        assertEquals(new P25SubscriberIdentity(0xABCDE, 0x321, 9_001), nativeIdentity.sourceP25Identity());
        assertNull(nativeIdentity.sourceObservedWorkingId());
        assertEquals(new P25SubscriberIdentity(0xABCDE, 0x321, 9_002),
            nativeIdentity.destinationP25Identity());
        assertNull(nativeIdentity.destinationObservedWorkingId());

        AudioCallRecordingMetadata explicitEqualIdentity = AudioCallRecordingMetadata.captureAtSnapshot(null,
            new IdentifierCollection(List.of(
                APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                    9_001, 0xABCDE, 0x321, 9_001),
                APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(
                    9_002, 0xABCDE, 0x321, 9_002))));
        assertEquals(9_001, explicitEqualIdentity.sourceObservedWorkingId());
        assertEquals(9_002, explicitEqualIdentity.destinationObservedWorkingId());
    }

    @Test
    void finalResolvedIdentifiersPromoteBothStructuredP25SidesWithoutChangingFrozenPolicy()
    {
        AudioCallRecordingMetadata snapshot = AudioCallRecordingMetadata.captureAtSnapshot(null,
            new IdentifierCollection());
        APCO25FullyQualifiedRadioIdentifier source =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                401, 0xBEE00, 0x348, 2_115_288);
        APCO25FullyQualifiedRadioIdentifier destination =
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(
                402, 0xABCDE, 0x456, 9_001);

        AudioCallRecordingMetadata resolved = snapshot.withResolvedUserIdentifiers(destination, source);

        assertEquals(new P25SubscriberIdentity(0xBEE00, 0x348, 2_115_288),
            resolved.sourceP25Identity());
        assertEquals(401, resolved.sourceObservedWorkingId());
        assertEquals(new P25SubscriberIdentity(0xABCDE, 0x456, 9_001),
            resolved.destinationP25Identity());
        assertEquals(402, resolved.destinationObservedWorkingId());
        assertNull(resolved.sourceAlias());
        assertNull(resolved.destinationAlias());
    }

    @Test
    void specialP25AddressesNeverThrowWhileCanonicalDirectoryKeysStayAbsent()
    {
        AudioCallRecordingMetadata allCall = assertDoesNotThrow(() ->
            AudioCallRecordingMetadata.captureAtSnapshot(null, new IdentifierCollection(List.of(
                APCO25FullyQualifiedTalkgroupIdentifier.createTo(65_535, 0xABCDE, 0x321, 65_535)))));
        assertNull(allCall.destinationIdentity());

        for(int radio: new int[]{0, 0xFFFFFD, 0xFFFFFF})
        {
            AudioCallRecordingMetadata metadata = assertDoesNotThrow(() ->
                AudioCallRecordingMetadata.captureAtSnapshot(null, new IdentifierCollection(List.of(
                    APCO25FullyQualifiedRadioIdentifier.createFrom(123, 0xABCDE, 0x321, radio)))));
            assertEquals("123", metadata.sourceValue());
            assertNull(metadata.sourceP25Identity());
            assertNull(metadata.sourceObservedWorkingId());
        }
    }

    @Test
    void usesCarrierTimestampsAndDoesNotRegressOnDelayedFrames()
    {
        MutableAudioCallBuilder builder = new MutableAudioCallBuilder(AliasList.empty("test"), 0);

        builder.begin(1_000);
        builder.beginBurst(1_010);
        builder.touch(1_200);
        builder.touch(1_100);
        builder.addAudio(new float[160], 1_300);
        builder.endBurst(1_400);
        builder.complete(1_500);

        assertEquals(1_000, builder.getStartTimestamp());
        assertEquals(1_400, builder.getLastActivityTimestamp());
        assertEquals(1_010, builder.getLastBurstStartTimestamp());
        assertEquals(1_400, builder.getLastBurstEndTimestamp());
        assertTrue(builder.isComplete());
    }
}
