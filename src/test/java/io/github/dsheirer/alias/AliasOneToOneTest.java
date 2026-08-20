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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.protocol.Protocol;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import org.junit.jupiter.api.Test;

class AliasOneToOneTest
{
    @Test
    void copyGetsNewIdentityAndDeepCopiesMatcherAndBehavior()
    {
        Alias original = new Alias("Dispatch");
        original.setId(41);
        original.setAliasListId(12);
        original.setAliasListName("Metro");
        original.setDescription("Primary dispatch");
        original.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        original.setRecordable(true);
        original.addBroadcastChannel("Stream A");
        original.setStreamTalkgroupAlias(new StreamAsTalkgroup(900));

        Alias copy = AliasFactory.copyOf(original);

        assertEquals(Alias.UNASSIGNED_ID, copy.getId());
        assertEquals(12, copy.getAliasListId());
        assertEquals("Primary dispatch", copy.getDescription());
        assertInstanceOf(Talkgroup.class, copy.getMatchIdentifier());
        assertNotSame(original.getMatchIdentifier(), copy.getMatchIdentifier());
        assertEquals(100, ((Talkgroup)copy.getMatchIdentifier()).getValue());
        assertTrue(copy.isRecordable());
        assertTrue(copy.hasBroadcastChannel("Stream A"));
        assertEquals(900, copy.getStreamTalkgroupAlias().getValue());
    }

    @Test
    void matcherCopiesPreserveCurrentOverlapState()
    {
        Dcs dcs = new Dcs();
        dcs.setDCSCode(DCSCode.N023);
        Esn esn = new Esn();
        esn.setEsn("ABC123");
        TonesID tones = new TonesID();

        for(AliasID matcher: List.of(dcs, esn, tones))
        {
            matcher.setOverlap(true);
            assertTrue(AliasFactory.copyOf(matcher).overlapProperty().get());
        }
    }

    @Test
    void activeModelRejectsMissingMatcher()
    {
        AliasListDefinition definition = new AliasListDefinition("Metro", AliasListFamily.P25);
        definition.setId(12);
        Alias alias = new Alias("Missing");
        alias.setId(41);
        alias.setAliasListDefinition(definition);
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));

        IllegalArgumentException exception =
            assertThrows(IllegalArgumentException.class, () -> model.addAlias(alias));

        assertTrue(exception.getMessage().contains("not supported"));
        assertTrue(model.getAliases().isEmpty());
        assertEquals(1, model.aliasListDefinitions().size());
    }

    @Test
    void committedReplacementUpdatesPublishedLookup()
    {
        AliasListDefinition definition = definition(12);
        Alias alias = alias(41, definition, "Dispatch", 100);
        Talkgroup original = new Talkgroup(Protocol.APCO25, 100);
        alias.setMatchIdentifier(original);
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));
        model.addAlias(alias);
        AliasList aliasList = model.getAliasList(definition);

        assertSame(alias, aliasList.getAliases(APCO25Talkgroup.create(100)).getFirst());

        Alias replacement = alias(41, definition, "Dispatch", 100);
        Radio radioMatcher = new Radio(Protocol.APCO25, 200);
        replacement.setMatchIdentifier(radioMatcher);
        model.addAlias(replacement);
        assertTrue(aliasList.getAliases(APCO25Talkgroup.create(100)).isEmpty());
        assertSame(replacement, aliasList.getAliases(APCO25RadioIdentifier.createFrom(200)).getFirst());

        original.setValue(101);
        assertTrue(aliasList.getAliases(APCO25Talkgroup.create(101)).isEmpty(),
            "Detached obsolete objects must not affect the published lookup");
    }

    @Test
    void runtimeAliasExposesOnlyOneMatcher()
    {
        Alias alias = new Alias("Dispatch");
        Talkgroup matcher = new Talkgroup(Protocol.APCO25, 100);
        alias.setMatchIdentifier(matcher);

        assertSame(matcher, alias.getMatchIdentifier());
        alias.setMatchIdentifier(new Radio(Protocol.APCO25, 200));
        assertInstanceOf(Radio.class, alias.getMatchIdentifier());
    }

    @Test
    void persistedListMembershipUsesTheSqliteIdentity()
    {
        AliasListDefinition definition = new AliasListDefinition("Current Name", AliasListFamily.P25);
        definition.setId(12);
        Alias alias = new Alias("Dispatch");
        alias.setAliasListId(12);
        alias.setAliasListName("Old Display Name");
        assertTrue(alias.belongsTo(definition));

        AliasListDefinition sameNameWrongId = new AliasListDefinition("Old Display Name", AliasListFamily.P25);
        sameNameWrongId.setId(13);
        assertFalse(alias.belongsTo(sameNameWrongId));

        Alias imported = new Alias("Imported");
        imported.setAliasListName("County");
        AliasListDefinition importedDefinition = new AliasListDefinition("county", AliasListFamily.P25);
        assertTrue(imported.belongsTo(importedDefinition));
    }

    @Test
    void modelQueriesPersistedAliasesByListIdentity()
    {
        AliasListDefinition definition = new AliasListDefinition("Current Name", AliasListFamily.P25);
        definition.setId(12);
        Alias alias = new Alias("Dispatch");
        alias.setId(41);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));
        model.addAlias(alias);

        alias.setAliasListName("Stale Display Name");
        assertSame(alias, model.getAliases("Current Name", alias.getMatchIdentifier().getType()).getFirst());
    }

    @Test
    void detachedPersistedAliasReplacesAndDeletesByStableIdentity()
    {
        AliasListDefinition definition = definition(12);
        Alias original = alias(41, definition, "Original", 100);
        Alias replacement = alias(41, definition, "Replacement", 101);
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));
        FilteredList<Alias> filtered = new FilteredList<>(model.aliasList(), alias -> true);
        SortedList<Alias> visibleRows = new SortedList<>(filtered, Comparator.comparing(Alias::getName));
        model.addAlias(original);
        AliasList runtime = model.getAliasList(definition);

        model.addAlias(replacement);

        assertEquals(List.of(replacement), model.getAliases());
        assertEquals(List.of(replacement), visibleRows);
        assertSame(replacement, model.getAlias(41));
        assertTrue(runtime.getAliases(APCO25Talkgroup.create(100)).isEmpty());
        assertSame(replacement, runtime.getAliases(APCO25Talkgroup.create(101)).getFirst());

        model.removeAliases(List.of(original));

        assertTrue(model.getAliases().isEmpty(),
            "A stale detached object must delete the current row with the same durable ID");
        assertTrue(visibleRows.isEmpty());
        assertTrue(runtime.getAliases(APCO25Talkgroup.create(101)).isEmpty());
    }

    @Test
    void batchReplacementPreservesModelAndCachedOverlapWinnerOrder()
    {
        AliasListDefinition definition = definition(12);
        Alias first = alias(41, definition, "First", 100);
        Alias second = alias(42, definition, "Second", 100);
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));
        model.addAliases(List.of(first, second));
        AliasList cached = model.getAliasList(definition);
        assertSame(second, cached.getAliases(APCO25Talkgroup.create(100)).getFirst());

        Alias firstReplacement = alias(41, definition, "First Updated", 100);
        Alias secondReplacement = alias(42, definition, "Second Updated", 100);
        model.addAliases(List.of(secondReplacement, firstReplacement));

        assertEquals(List.of(firstReplacement, secondReplacement), model.getAliases());
        assertSame(secondReplacement, cached.getAliases(APCO25Talkgroup.create(100)).getFirst());
    }

    @Test
    void activeModelRejectsUnassignedAndDuplicateIdentitiesBeforeMutation()
    {
        AliasListDefinition definition = definition(12);
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));
        Alias persisted = alias(40, definition, "Persisted", 100);
        model.addAlias(persisted);

        Alias unassigned = alias(Alias.UNASSIGNED_ID, definition, "Draft", 101);
        assertThrows(IllegalArgumentException.class, () -> model.addAlias(unassigned));
        assertEquals(List.of(persisted), model.getAliases());

        Alias replacementOne = alias(41, definition, "Replacement One", 102);
        Alias replacementTwo = alias(41, definition, "Replacement Two", 103);
        assertThrows(IllegalArgumentException.class,
            () -> model.addAliases(List.of(replacementOne, replacementTwo)));
        assertEquals(List.of(persisted), model.getAliases());
    }

    @Test
    void durableIdentitiesCanOnlyBeAssignedOnce()
    {
        Alias alias = new Alias("Draft");
        alias.setId(41);
        alias.setId(41);
        assertThrows(IllegalStateException.class, () -> alias.setId(42));
        assertThrows(IllegalStateException.class, () -> alias.setId(Alias.UNASSIGNED_ID));

        AliasListDefinition definition = new AliasListDefinition("Metro", AliasListFamily.P25);
        definition.setId(12);
        definition.setId(12);
        assertThrows(IllegalStateException.class, () -> definition.setId(13));
        assertThrows(IllegalStateException.class,
            () -> definition.setId(AliasListDefinition.UNASSIGNED_ID));
    }

    @Test
    void observableModelProjectionsAreReadOnly()
    {
        AliasListDefinition definition = definition(12);
        Alias alias = alias(41, definition, "Dispatch", 100);
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));
        model.addAlias(alias);

        assertThrows(UnsupportedOperationException.class, () -> model.aliasList().clear());
        assertThrows(UnsupportedOperationException.class, () -> model.aliasListNames().clear());
        assertThrows(UnsupportedOperationException.class, () -> model.aliasListDefinitions().clear());
        assertEquals(List.of(alias), model.getAliases());
        assertEquals(List.of(definition), model.aliasListDefinitions());
    }

    @Test
    void committedCreatePublishesOnlyTheNewRowWithoutRebuildingListNames()
    {
        AliasListDefinition definition = definition(12);
        Alias existing = alias(41, definition, "Existing", 100);
        AliasModel model = new AliasModel();
        model.replaceCommittedConfiguration(List.of(definition), List.of(existing));
        FilteredList<Alias> visible = new FilteredList<>(model.aliasList(),
            candidate -> candidate.getAliasListId() == definition.getId());
        int[] added = {0};
        int[] removed = {0};
        int[] listNameChanges = {0};
        model.aliasList().addListener((ListChangeListener<Alias>)change ->
        {
            while(change.next())
            {
                added[0] += change.getAddedSize();
                removed[0] += change.getRemovedSize();
            }
        });
        model.aliasListNames().addListener((ListChangeListener<String>)change -> listNameChanges[0]++);

        AliasListDefinition committedDefinition = definition(12);
        Alias unchangedDatabaseCopy = alias(41, committedDefinition, "Existing", 100);
        Alias created = alias(42, committedDefinition, "Created", 101);
        model.publishCommittedConfiguration(List.of(committedDefinition),
            List.of(unchangedDatabaseCopy, created), Set.of(created.getId()), false);

        assertEquals(1, added[0]);
        assertEquals(0, removed[0]);
        assertEquals(0, listNameChanges[0]);
        assertEquals(List.of(existing, created), model.getAliases());
        assertEquals(List.of(existing, created), visible);
        assertSame(existing, model.getAlias(existing.getId()));
        assertSame(created, model.getAlias(created.getId()));
    }

    @Test
    void definitionBackedListsRejectCrossFamilyMatchers()
    {
        AliasListDefinition p25 = new AliasListDefinition("P25", AliasListFamily.P25);
        Alias p25Alias = new Alias("Dispatch");
        p25Alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        AliasList p25List = new AliasList(p25);
        p25List.addAlias(p25Alias);
        assertSame(p25Alias, p25List.getAliases(APCO25Talkgroup.create(100)).getFirst());

        Alias dmrAlias = new Alias("Wrong Family");
        dmrAlias.setMatchIdentifier(new Talkgroup(Protocol.DMR, 100));
        assertThrows(IllegalArgumentException.class, () -> p25List.addAlias(dmrAlias));
    }

    @Test
    void runtimeChannelLookupAcceptsSameFamilyListAcrossSystems()
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        definition.setId(12);
        Alias alias = new Alias("Dispatch");
        alias.setId(41);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));
        model.addAlias(alias);

        Channel correct = channel("System A", "County");
        Channel wrongSystem = channel("System B", "County");

        assertSame(alias, model.getAliasListForChannel(correct)
            .getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertSame(alias, model.getAliasListForChannel(wrongSystem)
            .getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertTrue(model.isAliasListCompatible(wrongSystem));
    }

    private static Channel channel(String system, String aliasList)
    {
        Channel channel = new Channel("Control");
        channel.setSystem(system);
        channel.setAliasListName(aliasList);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        return channel;
    }

    private static AliasListDefinition definition(long id)
    {
        AliasListDefinition definition = new AliasListDefinition("Metro", AliasListFamily.P25);
        definition.setId(id);
        return definition;
    }

    private static Alias alias(long id, AliasListDefinition definition, String name, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setId(id);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
    }
}
