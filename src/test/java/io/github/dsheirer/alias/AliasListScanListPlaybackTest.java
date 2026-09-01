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
package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AliasListScanListPlaybackTest
{
    @Test
    void anyEnabledAliasWinsWithoutMutedVeto()
    {
        AliasListDefinition definition = definition(20);
        ScanListModel scanLists = model(Map.of(10L, Set.of(1L), 11L, Set.of(2L)), Map.of());
        AliasList aliases = new AliasList(definition, scanLists);
        aliases.addAliases(List.of(alias(10, "destination", new Talkgroup(Protocol.APCO25, 100)),
            alias(11, "source", new Radio(Protocol.APCO25, 200))));

        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(100));
        identifiers.update(APCO25RadioIdentifier.createFrom(200));

        assertTrue(aliases.shouldListen(identifiers));
    }

    @Test
    void nonDefaultMembershipDoesNotEnablePlayback()
    {
        AliasListDefinition definition = definition(20);
        ScanListModel scanLists = model(Map.of(10L, Set.of(2L)), Map.of());
        AliasList aliases = new AliasList(definition, scanLists);
        aliases.addAlias(alias(10, "hidden only", new Talkgroup(Protocol.APCO25, 100)));
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(100));

        assertFalse(aliases.shouldListen(identifiers));
    }

    @Test
    void unmatchedTalkgroupUsesAliasListDefaultMembership()
    {
        AliasListDefinition definition = definition(20);
        ScanListModel enabled = model(Map.of(), Map.of(20L, Set.of(1L)));
        AliasList enabledAliases = new AliasList(definition, enabled);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(999));

        assertTrue(enabledAliases.shouldListen(identifiers));

        ScanListModel disabled = model(Map.of(), Map.of(20L, Set.of(2L)));
        assertFalse(new AliasList(definition, disabled).shouldListen(identifiers));
    }

    private static Alias alias(long id, String name, io.github.dsheirer.alias.id.AliasID matcher)
    {
        Alias alias = new Alias(name);
        alias.setId(id);
        alias.setAliasListDefinition(definition(20));
        alias.setMatchIdentifier(matcher);
        return alias;
    }

    private static AliasListDefinition definition(long id)
    {
        AliasListDefinition definition = new AliasListDefinition("P25", AliasListFamily.P25);
        definition.setId(id);
        return definition;
    }

    private static ScanListModel model(Map<Long,Set<Long>> aliases, Map<Long,Set<Long>> unmatched)
    {
        ScanListModel model = new ScanListModel(null);
        model.replaceConfiguration(new ScanListConfiguration(List.of(
            new ScanList(1, 0, "Default", null, true, true),
            new ScanList(2, 1, "Hidden", null, false, false)), aliases, unmatched));
        return model;
    }
}

