/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import org.junit.jupiter.api.Test;

class P25EncryptionConfirmationTrackerTest
{
    @Test
    void confirmsTwoMatchingKnownObservations()
    {
        P25ChannelGrantEvent event = event();
        EncryptionKeyIdentifier key = key(0x84, 12328);

        P25EncryptionConfirmationTracker.observe(event, key, 1000L);
        assertFalse(P25EncryptionConfirmationTracker.isConfirmed(event, 0x84, 12328));

        P25EncryptionConfirmationTracker.observe(event, key, 1360L);
        assertTrue(P25EncryptionConfirmationTracker.isConfirmed(event, 0x84, 12328));
        assertFalse(P25EncryptionConfirmationTracker.isConfirmed(event, 0x84, 12329));
        P25EncryptionConfirmationTracker.complete(event, 2000L);
    }

    @Test
    void ignoresUnknownAlgorithm()
    {
        P25ChannelGrantEvent event = event();
        EncryptionKeyIdentifier key = key(0x08, 8322);

        P25EncryptionConfirmationTracker.observe(event, key, 1000L);
        P25EncryptionConfirmationTracker.observe(event, key, 1360L);
        assertFalse(P25EncryptionConfirmationTracker.isConfirmed(event, 0x08, 8322));
        P25EncryptionConfirmationTracker.complete(event, 2000L);
    }

    @Test
    void conflictingKnownPairsFailClosed()
    {
        P25ChannelGrantEvent event = event();

        P25EncryptionConfirmationTracker.observe(event, key(0x84, 12328), 1000L);
        P25EncryptionConfirmationTracker.observe(event, key(0x81, 12328), 1100L);
        P25EncryptionConfirmationTracker.observe(event, key(0x84, 12328), 1360L);
        assertFalse(P25EncryptionConfirmationTracker.isConfirmed(event, 0x84, 12328));
        P25EncryptionConfirmationTracker.complete(event, 2000L);
    }

    @Test
    void exposesFullKnownAlgorithmList()
    {
        assertTrue(P25EncryptionConfirmationTracker.isKnownAlgorithm(0x00));
        assertTrue(P25EncryptionConfirmationTracker.isKnownAlgorithm(0x81));
        assertTrue(P25EncryptionConfirmationTracker.isKnownAlgorithm(0x84));
        assertTrue(P25EncryptionConfirmationTracker.isKnownAlgorithm(0x88));
        assertTrue(P25EncryptionConfirmationTracker.isKnownAlgorithm(0x89));
        assertTrue(P25EncryptionConfirmationTracker.isKnownAlgorithm(0xAA));
        assertTrue(P25EncryptionConfirmationTracker.isKnownAlgorithm(0xB0));
        assertFalse(P25EncryptionConfirmationTracker.isKnownAlgorithm(0x08));
        assertFalse(P25EncryptionConfirmationTracker.isKnownAlgorithm(0x80));
        assertFalse(P25EncryptionConfirmationTracker.isKnownAlgorithm(0xFE));
    }

    private static P25ChannelGrantEvent event()
    {
        return new P25ChannelGrantEvent(DecodeEventType.CALL_GROUP, 1000L);
    }

    private static EncryptionKeyIdentifier key(int algorithm, int key)
    {
        return EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(algorithm, key));
    }
}
