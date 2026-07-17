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

    private static TalkerAliasIdentifier talkerAlias(MutableIdentifierCollection identifiers)
    {
        return (TalkerAliasIdentifier)identifiers.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
    }
}
