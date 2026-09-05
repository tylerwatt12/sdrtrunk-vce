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
            aliasList, APCO25FullyQualifiedRadioIdentifier.createTo(123, 0xABCDE, 0x321, 9_001));

        assertEquals("Roaming Subscriber", decision.aliasName());
        assertEquals("123", decision.value());
        assertEquals("v1-r-abcde-321-9001", decision.receivedIdentity());
        assertEquals("exact:APCO-25:123", decision.matcherIdentity());
        assertTrue(decision.recordEnabled());
    }

    @Test
    void specialP25AddressesNeverThrowWhileCanonicalDirectoryKeysStayAbsent()
    {
        AudioCallRecordingMetadata allCall = assertDoesNotThrow(() ->
            AudioCallRecordingMetadata.captureAtSnapshot(null, new IdentifierCollection(List.of(
                APCO25FullyQualifiedTalkgroupIdentifier.createTo(65_535, 0xABCDE, 0x321, 65_535)))));
        assertNull(allCall.destinationIdentity());

        for(int radio: new int[]{10_000_000, 0xFFFFFF})
        {
            AudioCallRecordingMetadata metadata = assertDoesNotThrow(() ->
                AudioCallRecordingMetadata.captureAtSnapshot(null, new IdentifierCollection(List.of(
                    APCO25FullyQualifiedRadioIdentifier.createFrom(123, 0xABCDE, 0x321, radio)))));
            assertEquals("123", metadata.sourceValue());
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
