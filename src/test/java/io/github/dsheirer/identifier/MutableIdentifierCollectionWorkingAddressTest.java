/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutableIdentifierCollectionWorkingAddressTest
{
    @Test
    void explicitWorkingAddressEnrichesEqualCanonicalIdentity()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        APCO25FullyQualifiedRadioIdentifier identityOnly =
            APCO25FullyQualifiedRadioIdentifier.createFrom(9_001, 0xABCDE, 0x321, 9_001);
        APCO25FullyQualifiedRadioIdentifier explicit =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(9_001, 0xABCDE, 0x321, 9_001);

        identifiers.update(identityOnly);
        identifiers.update(explicit);

        assertSame(explicit, identifiers.getFromIdentifier());
        assertEquals(9_001,
            ((APCO25FullyQualifiedRadioIdentifier)identifiers.getFromIdentifier()).getWorkingAddress());
    }

    @Test
    void newerExplicitWorkingAddressReplacesStaleValueButIdentityOnlyDoesNotEraseIt()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        APCO25FullyQualifiedRadioIdentifier first =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xABCDE, 0x321, 9_001);
        APCO25FullyQualifiedRadioIdentifier second =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(502, 0xABCDE, 0x321, 9_001);

        identifiers.update(first);
        identifiers.update(second);
        identifiers.update(APCO25FullyQualifiedRadioIdentifier.createFrom(9_001, 0xABCDE, 0x321, 9_001));

        assertSame(second, identifiers.getFromIdentifier());
        assertEquals(502,
            ((APCO25FullyQualifiedRadioIdentifier)identifiers.getFromIdentifier()).getWorkingAddress());
        assertNull(APCO25FullyQualifiedRadioIdentifier.createFrom(
            9_001, 0xABCDE, 0x321, 9_001).getWorkingAddress());
    }

    @Test
    void silentUpdateAlsoPreservesExplicitProvenance()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        APCO25FullyQualifiedRadioIdentifier explicit =
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(777, 0xABCDE, 0x321, 9_001);

        identifiers.silentUpdate(APCO25FullyQualifiedRadioIdentifier.createTo(
            9_001, 0xABCDE, 0x321, 9_001));
        identifiers.silentUpdate(explicit);

        assertSame(explicit, identifiers.getToIdentifier());
    }

    @Test
    void directCompleteObservationStrengthensEvidenceWithoutDowngradingOrErasingItsWorkingAddress()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        var mapped = APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(501, 0xABCDE, 0x321,
            9_001, Role.FROM, ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT);
        var direct = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xABCDE, 0x321, 9_001);

        identifiers.update(mapped);
        var identityOnly = APCO25FullyQualifiedRadioIdentifier.createFrom(9_001, 0xABCDE, 0x321, 9_001);
        identifiers.update(identityOnly);
        var retained = (APCO25FullyQualifiedRadioIdentifier)identifiers.getFromIdentifier();
        assertEquals(mapped.getWorkingAddress(), retained.getWorkingAddress(),
            "Identity-only evidence must retain the working address");
        assertEquals(ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT, retained.getResolutionEvidence());
        assertSame(identityOnly, retained.getRecordingIdentifier());
        identifiers.update(direct);
        assertSame(direct, identifiers.getFromIdentifier());
        identifiers.update(mapped);
        assertSame(direct, identifiers.getFromIdentifier(), "Mapping evidence must not replace direct evidence");
    }

    @Test
    void silentUpdateAlsoStrengthensCompleteDirectEvidence()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        var mapped = APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(501, 0xABCDE, 0x321,
            9_001, Role.TO, ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT);
        var direct = APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(501, 0xABCDE, 0x321, 9_001);

        identifiers.silentUpdate(mapped);
        identifiers.silentUpdate(direct);
        assertSame(direct, identifiers.getToIdentifier());
        identifiers.silentUpdate(mapped);
        assertSame(direct, identifiers.getToIdentifier());
    }

    @Test
    void canonicalOnlyDirectRecordingObservationRetainsConfirmedWorkingFactsWithoutRepeatedNotifications()
    {
        for(boolean silent: List.of(false, true))
        {
            for(int workingId: List.of(501, 9_001))
            {
                for(Role role: List.of(Role.FROM, Role.TO))
                {
                    MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
                    var mapped = APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(workingId,
                        0xABCDE, 0x321, 9_001, role, ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT);
                    identifiers.update(mapped);
                    List<IdentifierUpdateNotification> notifications = new ArrayList<>();
                    MutableAudioCallBuilder audioCall = new MutableAudioCallBuilder(AliasList.empty("RecordingObservation"), 0);
                    audioCall.addIdentifier(mapped);
                    identifiers.setIdentifierUpdateListener(notification -> {
                        notifications.add(notification);
                        audioCall.receive(notification);
                    });
                    var observed = role == Role.FROM ?
                        APCO25FullyQualifiedRadioIdentifier.createFrom(9_001, 0xABCDE, 0x321, 9_001) :
                        APCO25FullyQualifiedRadioIdentifier.createTo(9_001, 0xABCDE, 0x321, 9_001);

                    if(silent) identifiers.silentUpdate(observed);
                    else identifiers.update(observed);

                    var retained = (APCO25FullyQualifiedRadioIdentifier)identifiers.getIdentifier(
                        mapped.getIdentifierClass(), mapped.getForm(), role);
                    assertEquals(workingId, retained.getValue());
                    assertEquals(workingId, retained.getWorkingAddress());
                    assertEquals(mapped, retained);
                    assertEquals(mapped.hashCode(), retained.hashCode());
                    assertEquals(ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT, retained.getResolutionEvidence());
                    assertSame(observed, retained.getRecordingIdentifier());
                    assertEquals(9_001, retained.getRecordingIdentifier().getValue());
                    assertNull(observed.getWorkingAddress());
                    assertSame(observed, observed.getRecordingIdentifier());
                    assertEquals(silent ? 0 : 2, notifications.size());
                    if(!silent)
                    {
                        assertSame(retained, audioCall.getIdentifierCollection().getIdentifier(
                            mapped.getIdentifierClass(), mapped.getForm(), role),
                            "The changed raw recording observation must reach the active audio call");
                    }

                    var repeated = role == Role.FROM ?
                        APCO25FullyQualifiedRadioIdentifier.createFrom(9_001, 0xABCDE, 0x321, 9_001) :
                        APCO25FullyQualifiedRadioIdentifier.createTo(9_001, 0xABCDE, 0x321, 9_001);
                    if(silent) identifiers.silentUpdate(repeated);
                    else identifiers.update(repeated);
                    assertSame(retained, identifiers.getIdentifier(mapped.getIdentifierClass(), mapped.getForm(), role));
                    assertEquals(silent ? 0 : 2, notifications.size(),
                        "An equivalent raw observation must not churn audio metadata notifications");
                }
            }
        }
    }

    @Test
    void recordingObservationIsExcludedFromSerializedIdentityFields() throws Exception
    {
        var mapped = APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(501, 0xABCDE, 0x321,
            9_001, Role.FROM, ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT);
        var observed = APCO25FullyQualifiedRadioIdentifier.createFrom(9_001, 0xABCDE, 0x321, 9_001);
        ObjectMapper mapper = new ObjectMapper();

        assertEquals(mapper.readTree(mapper.writeValueAsString(mapped)), mapper.readTree(mapper.writeValueAsString(
            APCO25FullyQualifiedRadioIdentifier.withRecordingIdentifier(mapped, observed))));
        assertFalse(mapper.readTree(mapper.writeValueAsString(mapped)).has("recordingIdentifier"));
    }

    @Test
    void equalFullyQualifiedImplementationsHaveEqualHashes()
    {
        APCO25FullyQualifiedRadioIdentifier first =
            APCO25FullyQualifiedRadioIdentifier.createFrom(9_001, 0xABCDE, 0x321, 9_001);
        APCO25FullyQualifiedRadioIdentifier compatibleSubclass =
            new APCO25FullyQualifiedRadioIdentifier(9_001, 0xABCDE, 0x321, 9_001, Role.FROM) {};

        assertEquals(first, compatibleSubclass);
        assertEquals(first.hashCode(), compatibleSubclass.hashCode());
    }
}
