package io.github.dsheirer.module.decode.nxdn.identifier;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import org.junit.jupiter.api.Test;

class NXDNRadioIdentifierTest
{
    @Test
    void equalNumbersInTypeCAndTypeDAreDifferentRadioIdentities()
    {
        NXDNRadioIdentifier typeC = NXDNRadioIdentifier.createFrom(101);
        NXDNRadioIdentifier typeD = NXDNRadioIdentifier.createTypeDFrom(101);
        assertNotEquals(typeC, typeD);
        assertNotEquals(typeD, typeC);
        assertNotEquals(typeC.hashCode(), typeD.hashCode());
        assertEquals(typeC, NXDNRadioIdentifier.createFrom(101));
        assertEquals(typeD, NXDNRadioIdentifier.createTypeDFrom(101));
        assertEquals(typeD.hashCode(), NXDNRadioIdentifier.createTypeDFrom(101).hashCode());
        assertNotEquals(typeD, NXDNRadioIdentifier.createTypeDTo(101));
    }

    @Test
    void changingAddressDomainReplacesTheSourceAndDropsItsPreviousOwnedAlias()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        NXDNRadioIdentifier typeC = NXDNRadioIdentifier.createFrom(101);
        NXDNRadioIdentifier typeD = NXDNRadioIdentifier.createTypeDFrom(101);
        identifiers.update(typeC);
        identifiers.update(new NXDNTalkerAliasIdentifier("TYPE C RADIO").bindTo(identifiers));
        assertNotNull(TalkerAliasIdentifier.find(identifiers));
        identifiers.update(typeD);
        assertSame(typeD, identifiers.getFromIdentifier());
        assertNull(TalkerAliasIdentifier.find(identifiers));
    }
}
