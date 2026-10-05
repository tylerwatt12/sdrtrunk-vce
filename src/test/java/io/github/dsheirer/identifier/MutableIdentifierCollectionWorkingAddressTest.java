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
