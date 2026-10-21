/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.radio.P25Subscriber;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AliasModelPublicationTest
{
    @Test
    void metadataReplacementRefreshesOnlyItsCachedListAndRetainsEqualDefinitions()
    {
        AliasListDefinition firstDefinition = definition(1, "First");
        AliasListDefinition secondDefinition = definition(2, "Second");
        Alias changed = alias(10, firstDefinition, "Original", 100);
        Alias sameList = alias(11, firstDefinition, "Same List", 101);
        Alias unrelated = alias(20, secondDefinition, "Unrelated", 200);
        AliasModel model = model(List.of(firstDefinition, secondDefinition), List.of(changed, sameList, unrelated));
        AliasList first = model.getAliasList(firstDefinition);
        AliasList second = model.getAliasList(secondDefinition);
        int[] firstChanges = changes(model, first);
        int[] secondChanges = changes(model, second);
        int[] definitionChanges = {0};
        int[] nameChanges = {0};
        model.addChangeListener(change -> { if(change.definitionsChanged()) definitionChanges[0]++; });
        model.addChangeListener(change -> { if(change.namesChanged()) nameChanges[0]++; });

        AliasListDefinition firstCopy = copy(firstDefinition);
        AliasListDefinition secondCopy = copy(secondDefinition);
        Alias replacement = copy(changed, firstCopy);
        replacement.setName("Updated");
        replacement.setDescription("Updated description");
        replacement.setRecordable(true);
        model.publishCommittedConfiguration(List.of(firstCopy, secondCopy),
            List.of(replacement, copy(sameList, firstCopy), copy(unrelated, secondCopy)), Set.of(changed.getId()),
            true);

        assertSame(first, model.getAliasList(firstDefinition));
        assertSame(second, model.getAliasList(secondDefinition));
        assertEquals(1, firstChanges[0]);
        assertEquals(0, secondChanges[0]);
        assertEquals(0, definitionChanges[0]);
        assertEquals(0, nameChanges[0]);
        assertSame(firstDefinition, model.getAliasListDefinition(1));
        assertSame(secondDefinition, model.getAliasListDefinition(2));
        assertSame(firstDefinition, replacement.getAliasListDefinition());
        assertSame(firstDefinition, sameList.getAliasListDefinition());
        assertSame(secondDefinition, unrelated.getAliasListDefinition());
        assertSame(sameList, model.getAlias(11));
        assertSame(unrelated, model.getAlias(20));
        assertSame(replacement, first.getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertEquals("Updated description", first.getAliases(APCO25Talkgroup.create(100)).getFirst().getDescription());
        assertTrue(first.getAliases(APCO25Talkgroup.create(100)).getFirst().isRecordable());
    }

    @Test
    void unchangedAliasPublicationDoesNotRefreshCachedListsOrDefinitionProjections()
    {
        AliasListDefinition definition = definition(1, "First");
        Alias original = alias(10, definition, "Original", 100);
        AliasModel model = model(List.of(definition), List.of(original));
        AliasList cached = model.getAliasList(definition);
        int[] runtimeChanges = changes(model, cached);
        int[] aliasChanges = {0};
        int[] definitionChanges = {0};
        model.addChangeListener(change -> { if(change.aliasesChanged()) aliasChanges[0]++; });
        model.addChangeListener(change -> { if(change.definitionsChanged()) definitionChanges[0]++; });

        AliasListDefinition detachedDefinition = copy(definition);
        model.publishCommittedConfiguration(List.of(detachedDefinition),
            List.of(copy(original, detachedDefinition)), Set.of(), true);

        assertEquals(0, runtimeChanges[0]);
        assertEquals(0, aliasChanges[0]);
        assertEquals(0, definitionChanges[0]);
        assertSame(original, model.getAlias(10));
        assertSame(definition, original.getAliasListDefinition());
        assertSame(definition, model.getAliasListDefinition(1));
    }

    @Test
    void movingAnAliasRefreshesBothOwnersOnceAndLeavesUnrelatedListUntouched()
    {
        AliasListDefinition firstDefinition = definition(1, "First");
        AliasListDefinition secondDefinition = definition(2, "Second");
        AliasListDefinition thirdDefinition = definition(3, "Third");
        Alias moving = alias(10, firstDefinition, "Moving", 100);
        Alias secondAlias = alias(20, secondDefinition, "Second", 200);
        Alias unrelated = alias(30, thirdDefinition, "Unrelated", 300);
        List<AliasListDefinition> definitions = List.of(firstDefinition, secondDefinition, thirdDefinition);
        AliasModel model = model(definitions, List.of(moving, secondAlias, unrelated));
        AliasList first = model.getAliasList(firstDefinition);
        AliasList second = model.getAliasList(secondDefinition);
        AliasList third = model.getAliasList(thirdDefinition);
        int[] firstChanges = changes(model, first);
        int[] secondChanges = changes(model, second);
        int[] thirdChanges = changes(model, third);
        Alias replacement = copy(moving, secondDefinition);

        model.publishCommittedConfiguration(definitions, List.of(replacement, secondAlias, unrelated),
            Set.of(moving.getId()), false);

        assertEquals(1, firstChanges[0]);
        assertEquals(1, secondChanges[0]);
        assertEquals(0, thirdChanges[0]);
        assertSame(first, model.getAliasList(firstDefinition));
        assertSame(second, model.getAliasList(secondDefinition));
        assertTrue(first.getAliases(APCO25Talkgroup.create(100)).isEmpty());
        assertSame(replacement, second.getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertSame(secondAlias, model.getAlias(20));
        assertSame(unrelated, model.getAlias(30));
    }

    @Test
    void definitionChangesRefreshOnlyThatListAndRetainUnchangedDefinitionsByIdentity()
    {
        AliasListDefinition firstDefinition = definition(1, "First");
        AliasListDefinition secondDefinition = definition(2, "Second");
        Alias firstAlias = alias(10, firstDefinition, "First", 100);
        Alias firstSibling = alias(11, firstDefinition, "First Sibling", 100);
        Alias firstOther = alias(12, firstDefinition, "First Other", 101);
        Alias secondAlias = alias(20, secondDefinition, "Second", 200);
        AliasModel model = model(List.of(firstDefinition, secondDefinition),
            List.of(firstAlias, firstSibling, firstOther, secondAlias));
        AliasList first = model.getAliasList(firstDefinition);
        AliasList second = model.getAliasList(secondDefinition);
        int[] firstChanges = changes(model, first);
        int[] secondChanges = changes(model, second);
        int[] overlapChanges = {0};
        firstAlias.getMatchIdentifier().addChangeListener(() -> overlapChanges[0]++);
        AliasListDefinition changedDefinition = new AliasListDefinition("Renamed", AliasListFamily.P25,
            new UnmatchedTalkgroupPolicy(true, List.of()), NewAliasBehavior.DEFAULT);
        changedDefinition.setId(firstDefinition.getId());
        AliasListDefinition unchangedCopy = copy(secondDefinition);

        //Definition order is independent of identity; an equal definition in another position stays live.
        model.publishCommittedConfiguration(List.of(unchangedCopy, changedDefinition),
            List.of(copy(firstAlias, changedDefinition), copy(firstSibling, changedDefinition),
                copy(firstOther, changedDefinition), copy(secondAlias, unchangedCopy)), Set.of(), true);

        assertEquals(1, firstChanges[0]);
        assertEquals(0, secondChanges[0]);
        assertEquals(2, overlapChanges[0], "A rename must reset and recompute overlaps only once for the list");
        assertSame(changedDefinition, model.getAliasListDefinition(1));
        assertSame(secondDefinition, model.getAliasListDefinition(2));
        assertSame(firstAlias, model.getAlias(10));
        assertSame(firstSibling, model.getAlias(11));
        assertSame(firstOther, model.getAlias(12));
        assertSame(secondAlias, model.getAlias(20));
        assertSame(changedDefinition, firstAlias.getAliasListDefinition());
        assertSame(secondDefinition, secondAlias.getAliasListDefinition());
        assertSame(first, model.getAliasList("Renamed"));
        assertEquals("Renamed", first.getName());
        assertTrue(first.getUnmatchedTalkgroupPolicy(APCO25Talkgroup.create(999)).isRecordEnabled());
    }

    @Test
    void orderOnlyPublicationPreservesDuplicateWinnerSemanticsWithoutRefreshingOtherLists()
    {
        AliasListDefinition firstDefinition = definition(1, "First");
        AliasListDefinition secondDefinition = definition(2, "Second");
        Alias firstAlias = alias(10, firstDefinition, "First", 100);
        Alias secondAlias = alias(11, firstDefinition, "Second", 100);
        Alias unrelated = alias(20, secondDefinition, "Unrelated", 200);
        List<AliasListDefinition> definitions = List.of(firstDefinition, secondDefinition);
        AliasModel model = model(definitions, List.of(firstAlias, secondAlias, unrelated));
        AliasList first = model.getAliasList(firstDefinition);
        AliasList second = model.getAliasList(secondDefinition);
        int[] firstChanges = changes(model, first);
        int[] secondChanges = changes(model, second);
        int[] overlapChanges = {0};
        firstAlias.getMatchIdentifier().addChangeListener(() -> overlapChanges[0]++);
        assertSame(secondAlias, first.getAliases(APCO25Talkgroup.create(100)).getFirst());

        model.publishCommittedConfiguration(definitions, List.of(secondAlias, unrelated, firstAlias), Set.of(),
            false);

        assertEquals(1, firstChanges[0]);
        assertEquals(0, secondChanges[0]);
        assertEquals(2, overlapChanges[0], "An order change must reset and recompute overlaps only once");
        assertSame(firstAlias, first.getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertEquals(List.of(secondAlias, unrelated, firstAlias), model.getAliases());
    }

    @Test
    void replacementAndRemovalRetainDifferentNumericAndSubscriberDuplicateWinners()
    {
        AliasListDefinition definition = definition(1, "First");
        Alias numericFirst = alias(10, definition, "Numeric First", 100);
        Alias numericLast = alias(11, definition, "Numeric Last", 100);
        Alias subscriberFirst = alias(20, definition, "Subscriber First", new P25Subscriber(0xBEE00, 0x348, 501));
        Alias subscriberLast = alias(21, definition, "Subscriber Last", new P25Subscriber(0xBEE00, 0x348, 501));
        AliasModel model = model(List.of(definition),
            List.of(numericFirst, numericLast, subscriberFirst, subscriberLast));
        AliasList cached = model.getAliasList(definition);
        APCO25FullyQualifiedRadioIdentifier subscriber =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(900, 0xBEE00, 0x348, 501);
        Alias numericReplacement = copy(numericFirst, definition);
        numericReplacement.setName("Numeric Updated");
        Alias subscriberReplacement = copy(subscriberFirst, definition);
        subscriberReplacement.setName("Subscriber Updated");

        model.publishCommittedConfiguration(List.of(definition),
            List.of(numericReplacement, numericLast, subscriberReplacement, subscriberLast),
            Set.of(numericFirst.getId(), subscriberFirst.getId()), false);

        assertSame(numericLast, cached.getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertSame(subscriberReplacement, cached.getAliases(subscriber).getFirst());
        assertTrue(numericReplacement.getMatchIdentifier().isOverlap());
        assertTrue(subscriberReplacement.getMatchIdentifier().isOverlap());

        model.publishCommittedConfiguration(List.of(definition), List.of(numericReplacement, subscriberLast),
            Set.of(numericLast.getId(), subscriberReplacement.getId()), false);

        assertSame(numericReplacement, cached.getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertSame(subscriberLast, cached.getAliases(subscriber).getFirst());
        assertFalse(numericReplacement.getMatchIdentifier().isOverlap());
        assertFalse(subscriberLast.getMatchIdentifier().isOverlap());
    }

    @Test
    void deletedAliasesAndTheirMatchersNoLongerTriggerLookupRebuilds()
    {
        AliasListDefinition definition = definition(1, "First");
        Alias deleted = alias(10, definition, "Deleted", 100);
        Alias survivingFirst = alias(11, definition, "First Survivor", 200);
        Alias survivingLast = alias(12, definition, "Last Survivor", 200);
        AliasModel model = model(List.of(definition), List.of(deleted, survivingFirst, survivingLast));
        AliasList cached = model.getAliasList(definition);

        model.publishCommittedConfiguration(List.of(definition), List.of(survivingFirst, survivingLast),
            Set.of(deleted.getId()), false);
        int[] overlapChanges = {0};
        survivingFirst.getMatchIdentifier().addChangeListener(() -> overlapChanges[0]++);
        ((Talkgroup)deleted.getMatchIdentifier()).setValue(101);
        deleted.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 102));
        ((Talkgroup)deleted.getMatchIdentifier()).setValue(103);
        deleted.setAliasListName("Retired");

        assertEquals(0, overlapChanges[0], "Detached objects must not rebuild the surviving colliding aliases");
        assertTrue(cached.getAliases(APCO25Talkgroup.create(100)).isEmpty());
        assertTrue(cached.getAliases(APCO25Talkgroup.create(103)).isEmpty());
        assertSame(survivingLast, cached.getAliases(APCO25Talkgroup.create(200)).getFirst());
    }

    @Test
    void replacementDetachesItsFormerObjectAndObservesTheNewMatcher()
    {
        AliasListDefinition definition = definition(1, "First");
        Alias previous = alias(10, definition, "Previous", 100);
        Alias last = alias(11, definition, "Last", 100);
        AliasModel model = model(List.of(definition), List.of(previous, last));
        AliasList cached = model.getAliasList(definition);
        Alias replacement = copy(previous, definition);
        replacement.setName("Replacement");
        model.publishCommittedConfiguration(List.of(definition), List.of(replacement, last),
            Set.of(previous.getId()), false);
        int[] overlapChanges = {0};
        replacement.getMatchIdentifier().addChangeListener(() -> overlapChanges[0]++);

        ((Talkgroup)previous.getMatchIdentifier()).setValue(101);
        previous.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 102));
        ((Talkgroup)previous.getMatchIdentifier()).setValue(103);
        assertEquals(0, overlapChanges[0], "The previous object must no longer trigger a collision rebuild");

        ((Talkgroup)replacement.getMatchIdentifier()).setValue(104);
        assertSame(replacement, cached.getAliases(APCO25Talkgroup.create(104)).getFirst());
        assertSame(last, cached.getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertTrue(cached.getAliases(APCO25Talkgroup.create(103)).isEmpty());
        assertFalse(last.getMatchIdentifier().isOverlap());
    }

    @Test
    void removedDefinitionsClearPreviouslyCachedAliasesBeforeCacheRemoval()
    {
        AliasListDefinition removedDefinition = definition(1, "Removed");
        AliasListDefinition retainedDefinition = definition(2, "Retained");
        Alias removed = alias(10, removedDefinition, "Removed", 100);
        Alias retained = alias(20, retainedDefinition, "Retained", 200);
        AliasModel model = model(List.of(removedDefinition, retainedDefinition), List.of(removed, retained));
        AliasList removedList = model.getAliasList(removedDefinition);
        AliasList retainedList = model.getAliasList(retainedDefinition);
        int[] retainedChanges = changes(model, retainedList);

        model.publishCommittedConfiguration(List.of(copy(retainedDefinition)), List.of(copy(retained, retainedDefinition)),
            Set.of(removed.getId()), true);

        assertTrue(removedList.aliases().isEmpty());
        assertTrue(removedList.getAliases(APCO25Talkgroup.create(100)).isEmpty());
        assertTrue(model.getAliasList(removedDefinition).aliases().isEmpty());
        assertEquals(0, retainedChanges[0]);
        assertSame(retainedList, model.getAliasList(retainedDefinition));
        assertSame(retainedDefinition, retained.getAliasListDefinition());
    }

    @Test
    void publicationRecoveryRefreshesAnIndexWhoseFormerOwnerWasAlreadyRemoved()
    {
        AliasListDefinition definition = definition(1, "First");
        Alias survivor = alias(11, definition, "Survivor", 100);
        Alias removed = alias(10, definition, "Removed", 100);
        AliasModel model = model(List.of(definition), List.of(survivor, removed));
        AliasList cached = model.getAliasList(definition);
        assertSame(removed, cached.getAliases(APCO25Talkgroup.create(100)).getFirst());
        boolean[] failNext = {true};
        survivor.getMatchIdentifier().addChangeListener(() -> {
            if(failNext[0] && !survivor.getMatchIdentifier().isOverlap())
            {
                failNext[0] = false;
                throw new AssertionError("Injected overlap observer failure before index publication");
            }
        });

        assertThrows(AssertionError.class, () -> model.publishCommittedConfiguration(List.of(definition),
            List.of(survivor), Set.of(removed.getId()), false));
        assertEquals(List.of(survivor), model.getAliases());
        assertEquals(List.of(survivor), cached.aliases());
        assertSame(removed, cached.getAliases(APCO25Talkgroup.create(100)).getFirst(),
            "The observer failed before the complete prepared lookup could replace its old snapshot");

        model.publishCommittedConfiguration(List.of(copy(definition)), List.of(copy(survivor, definition)),
            Set.of(removed.getId()), true);

        assertSame(cached, model.getAliasList(definition));
        assertSame(survivor, cached.getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertFalse(survivor.getMatchIdentifier().isOverlap());
    }

    private static AliasModel model(List<AliasListDefinition> definitions, List<Alias> aliases)
    {
        AliasModel model = new AliasModel();
        model.replaceCommittedConfiguration(definitions, aliases);
        return model;
    }

    private static AliasListDefinition definition(long id, String name)
    {
        AliasListDefinition definition = new AliasListDefinition(name, AliasListFamily.P25);
        definition.setId(id);
        return definition;
    }

    private static AliasListDefinition copy(AliasListDefinition definition)
    {
        AliasListDefinition copy = new AliasListDefinition(definition.getName(), definition.getFamily(),
            definition.getUnmatchedTalkgroupPolicy(), definition.getNewAliasBehavior());
        copy.setId(definition.getId());
        return copy;
    }

    private static Alias alias(long id, AliasListDefinition definition, String name, int talkgroup)
    {
        return alias(id, definition, name, new Talkgroup(Protocol.APCO25, talkgroup));
    }

    private static Alias alias(long id, AliasListDefinition definition, String name, AliasID matcher)
    {
        Alias alias = new Alias(name);
        alias.setId(id);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(matcher);
        return alias;
    }

    private static Alias copy(Alias alias, AliasListDefinition definition)
    {
        Alias copy = AliasFactory.copyOf(alias);
        copy.setId(alias.getId());
        copy.setAliasListDefinition(definition);
        return copy;
    }

    private static int[] changes(AliasModel model, AliasList list)
    {
        int[] changes = {0};
        Object[] previous = {AliasModelTestAccess.lookupIndex(list)};
        model.addChangeListener(change -> {
            Object current = AliasModelTestAccess.lookupIndex(list);
            if(current != previous[0])
            {
                changes[0]++;
                previous[0] = current;
            }
        });
        return changes;
    }
}
