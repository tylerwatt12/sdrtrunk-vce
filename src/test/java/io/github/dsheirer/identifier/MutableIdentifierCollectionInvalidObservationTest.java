/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.identifier;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutableIdentifierCollectionInvalidObservationTest
{
    @Test
    void invalidNotifiedUpdatesRetainBothKnownPartiesAndEmitNoWithdrawal()
    {
        var identifiers = new MutableIdentifierCollection();
        var source = DMRRadio.createFrom(101);
        var target = DMRTalkgroup.create(91);
        List<IdentifierUpdateNotification> notifications = new ArrayList<>();
        identifiers.setIdentifierUpdateListener(notifications::add);
        identifiers.update(source);
        identifiers.update(target);
        notifications.clear();

        identifiers.update(DMRRadio.createFrom(0));
        identifiers.update(DMRTalkgroup.create(0));

        assertSame(source, identifiers.getFromIdentifier());
        assertSame(target, identifiers.getToIdentifier());
        assertTrue(notifications.isEmpty(), "Invalid observations must not withdraw previously known identities");
        identifiers.remove(source);
        assertNull(identifiers.getFromIdentifier());
        assertEquals(1, notifications.size());
        assertTrue(notifications.getFirst().isRemove());
        assertSame(source, notifications.getFirst().getIdentifier());
    }

    @Test
    void invalidSilentUpdatesRetainBothKnownPartiesAndExplicitSilentRemovalStillWorks()
    {
        var identifiers = new MutableIdentifierCollection();
        var source = DMRRadio.createFrom(101);
        var target = DMRTalkgroup.create(91);
        List<IdentifierUpdateNotification> notifications = new ArrayList<>();
        identifiers.setIdentifierUpdateListener(notifications::add);
        identifiers.silentUpdate(source);
        identifiers.silentUpdate(target);

        identifiers.silentUpdate(DMRRadio.createFrom(0));
        identifiers.silentUpdate(DMRTalkgroup.create(0));

        assertSame(source, identifiers.getFromIdentifier());
        assertSame(target, identifiers.getToIdentifier());
        identifiers.silentRemove(target);
        assertNull(identifiers.getToIdentifier());
        assertSame(source, identifiers.getFromIdentifier());
        assertTrue(notifications.isEmpty());
    }

    @Test
    void invalidObservationsDoNotPopulateAnEmptyCollection()
    {
        var identifiers = new MutableIdentifierCollection();
        List<IdentifierUpdateNotification> notifications = new ArrayList<>();
        identifiers.setIdentifierUpdateListener(notifications::add);
        identifiers.update(DMRRadio.createFrom(0));
        identifiers.update(DMRTalkgroup.create(0));
        identifiers.silentUpdate(DMRRadio.createFrom(0));
        identifiers.silentUpdate(DMRTalkgroup.create(0));

        assertNull(identifiers.getFromIdentifier());
        assertNull(identifiers.getToIdentifier());
        assertTrue(identifiers.getIdentifiers().isEmpty());
        assertTrue(notifications.isEmpty());
    }

    @Test
    void canonicalOnlyIssiRadioRemainsValidDespiteItsZeroLocalAddress()
    {
        for(boolean silent: new boolean[]{false, true})
        {
            var identifiers = new MutableIdentifierCollection();
            var qualified = APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x348, 70_001);
            assertTrue(qualified.isValid(), "An absent working address does not invalidate a canonical subscriber");
            if(silent)
            {
                identifiers.silentUpdate(qualified);
                identifiers.silentUpdate(DMRRadio.createFrom(0));
            }
            else
            {
                identifiers.update(qualified);
                identifiers.update(DMRRadio.createFrom(0));
            }
            assertSame(qualified, identifiers.getFromIdentifier());
            assertEquals(70_001, qualified.getRadio());
            assertNull(qualified.getWorkingAddress());
        }
    }
}
