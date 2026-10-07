/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.identifier.alias;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TalkerAliasManagerTest
{
    @Test
    void removesPreviousAliasWhenFromRadioChanges()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        RadioIdentifier firstRadio = APCO25RadioIdentifier.createFrom(1_880_231);
        RadioIdentifier consoleRadio = APCO25RadioIdentifier.createFrom(1_102);
        TalkerAliasIdentifier firstAlias = P25TalkerAliasIdentifier.create("CDP #0231");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        manager.update(firstRadio, firstAlias);
        identifiers.update(firstRadio);

        manager.enrichMutable(identifiers);
        assertEquals(firstAlias, talkerAlias(identifiers));

        identifiers.update(consoleRadio);
        manager.enrichMutable(identifiers);

        assertEquals(consoleRadio, identifiers.getFromIdentifier());
        assertNull(talkerAlias(identifiers));
    }

    @Test
    void replacesPreviousAliasWithAliasForCurrentRadio()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        RadioIdentifier firstRadio = APCO25RadioIdentifier.createFrom(1_880_231);
        RadioIdentifier secondRadio = APCO25RadioIdentifier.createFrom(1_880_292);
        TalkerAliasIdentifier firstAlias = P25TalkerAliasIdentifier.create("CDP #0231");
        TalkerAliasIdentifier secondAlias = P25TalkerAliasIdentifier.create("CDP #0292");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        manager.update(firstRadio, firstAlias);
        manager.update(secondRadio, secondAlias);
        identifiers.update(firstRadio);
        manager.enrichMutable(identifiers);
        identifiers.update(secondRadio);

        manager.enrichMutable(identifiers);

        assertEquals(secondAlias, talkerAlias(identifiers));
    }

    @Test
    void canonicalHomeAndOrdinaryNativeAliasShareOneCacheEntry()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var canonical = APCO25FullyQualifiedRadioIdentifier.createFrom(10_900_077,
            0xBEE00, 0x123, 10_900_077);
        var nativeRadio = APCO25RadioIdentifier.createFrom(10_900_077);
        var first = P25TalkerAliasIdentifier.create("UNIT 77");
        var revised = P25TalkerAliasIdentifier.create("UNIT 77 NEW");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        manager.update(canonical, first, "p25:bee00:123");
        identifiers.update(nativeRadio);
        manager.enrichMutable(identifiers, 0xBEE00, 0x123);
        assertEquals(first, talkerAlias(identifiers));

        manager.update(nativeRadio, revised, "p25:bee00:123");
        identifiers.update(canonical);
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertEquals(revised, talkerAlias(identifiers));

        identifiers.update(nativeRadio);
        manager.enrichMutable(identifiers);
        assertNull(talkerAlias(identifiers), "Unknown serving scope must not borrow a canonical name");
        manager.enrichMutable(identifiers, "p25:bee00:124");
        assertNull(talkerAlias(identifiers));
    }

    @Test
    void foreignSameNumberAndReusedWorkingAddressesDoNotContaminateNativeAliases()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var home = APCO25FullyQualifiedRadioIdentifier.createFrom(501, 0xBEE00, 0x123, 501);
        var visiting = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501,
            0xABCDE, 0x321, 501);
        var reassigned = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501,
            0xABCDE, 0x321, 502);
        var homeAlias = P25TalkerAliasIdentifier.create("HOME");
        var visitingAlias = P25TalkerAliasIdentifier.create("VISITOR");
        var reassignedAlias = P25TalkerAliasIdentifier.create("NEXT VISITOR");
        manager.update(home, homeAlias, "p25:bee00:123");
        manager.update(visiting, visitingAlias, "p25:bee00:123");
        manager.update(reassigned, reassignedAlias, "p25:bee00:123");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25RadioIdentifier.createFrom(501));
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertEquals(homeAlias, talkerAlias(identifiers));
        identifiers.update(visiting);
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertEquals(visitingAlias, talkerAlias(identifiers));
        identifiers.update(reassigned);
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertEquals(reassignedAlias, talkerAlias(identifiers));
        identifiers.update(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(777,
            0xABCDE, 0x321, 501));
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertEquals(visitingAlias, talkerAlias(identifiers));
        identifiers.update(APCO25RadioIdentifier.createFrom(777));
        manager.enrichMutable(identifiers, "p25:bee00:123");
        assertNull(talkerAlias(identifiers));
    }

    @Test
    void dmrAndNxdnKeepTheirNumericAliasBehavior()
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var dmr = DMRRadio.createFrom(501);
        var nxdn = NXDNRadioIdentifier.createFrom(501);
        var dmrAlias = P25TalkerAliasIdentifier.create("DMR");
        var nxdnAlias = P25TalkerAliasIdentifier.create("NXDN");
        manager.update(dmr, dmrAlias);
        manager.update(nxdn, nxdnAlias);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(dmr);
        manager.enrichMutable(identifiers);
        assertEquals(dmrAlias, talkerAlias(identifiers));
        identifiers.remove(IdentifierClass.USER, Form.RADIO, Role.FROM);
        identifiers.update(nxdn);
        manager.enrichMutable(identifiers);
        assertEquals(nxdnAlias, talkerAlias(identifiers));
    }

    @Test
    void aliasProjectionDoesNotWaitForAContendedManagerMonitor() throws Exception
    {
        TalkerAliasManager manager = new TalkerAliasManager();
        var radio = APCO25RadioIdentifier.createFrom(501);
        var alias = P25TalkerAliasIdentifier.create("HOME");
        manager.update(radio, alias, "p25:bee00:123");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(radio);
        try(var executor = Executors.newSingleThreadExecutor())
        {
            synchronized(manager)
            {
                executor.submit(() -> manager.enrichMutable(identifiers, 0xBEE00, 0x123))
                    .get(1, TimeUnit.SECONDS);
            }
        }
        assertEquals(alias, talkerAlias(identifiers));
    }

    private static TalkerAliasIdentifier talkerAlias(MutableIdentifierCollection identifiers)
    {
        return (TalkerAliasIdentifier)identifiers.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
    }
}
