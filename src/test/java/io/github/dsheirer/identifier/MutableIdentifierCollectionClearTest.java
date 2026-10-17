/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutableIdentifierCollectionClearTest
{
    @Test
    void clearRemovesEveryIdentifierAndNotifiesTheSourceTimeslot()
    {
        MutableIdentifierCollection collection = new MutableIdentifierCollection(2);
        var source = APCO25RadioIdentifier.createFrom(101);
        var alias = P25TalkerAliasIdentifier.create("OLD RADIO").bindTo(source);
        List<Identifier> original = List.of(AliasListConfigurationIdentifier.create("Configured aliases"), source, alias);
        collection.update(original);
        List<IdentifierUpdateNotification> notifications = new ArrayList<>();
        collection.setIdentifierUpdateListener(notifications::add);

        collection.clear();

        assertTrue(collection.isEmpty());
        assertNull(collection.getFromIdentifier());
        assertNull(collection.getAliasListConfiguration());
        assertNull(TalkerAliasIdentifier.find(collection));
        assertEquals(original, notifications.stream().map(IdentifierUpdateNotification::getIdentifier).toList());
        assertTrue(notifications.stream().allMatch(notification -> notification.isRemove() && notification.getTimeslot() == 2));
        collection.clear();
        assertEquals(original.size(), notifications.size());
    }

    @Test
    void clearBeforeSameRadioReuseCannotReviveAnOldAliasOrMutateACompletedSnapshot()
    {
        MutableIdentifierCollection collection = new MutableIdentifierCollection();
        var source = APCO25RadioIdentifier.createFrom(101);
        collection.update(source);
        collection.update(P25TalkerAliasIdentifier.create("OLD RADIO").bindTo(source));
        IdentifierCollection completed = new IdentifierCollection(collection.getIdentifiers());

        collection.clear();
        collection.update(APCO25RadioIdentifier.createFrom(101));

        assertEquals(101, collection.getFromIdentifier().getValue());
        assertNull(TalkerAliasIdentifier.find(collection));
        assertEquals("OLD RADIO", TalkerAliasIdentifier.valueFor(completed));
        collection.update(P25TalkerAliasIdentifier.create("NEW RADIO").bindTo(collection));
        assertEquals("NEW RADIO", TalkerAliasIdentifier.valueFor(collection));
    }
}
