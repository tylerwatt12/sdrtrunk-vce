/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
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
        identifiers.update(APCO25FullyQualifiedRadioIdentifier.createFrom(9_001, 0xABCDE, 0x321, 9_001));
        assertSame(mapped, identifiers.getFromIdentifier(), "Identity-only evidence must retain the working address");
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
