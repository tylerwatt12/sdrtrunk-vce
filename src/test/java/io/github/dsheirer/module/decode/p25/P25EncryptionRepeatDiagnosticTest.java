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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25EncryptionRepeatDiagnosticTest
{
    @Test
    void confirmsMatchingRecognizedPairWithinOneCall()
    {
        List<String> reports = new ArrayList<>();
        P25EncryptionRepeatDiagnostic diagnostic = new P25EncryptionRepeatDiagnostic(true, 1, reports::add);
        P25ChannelGrantEvent event = event(1000L);
        EncryptionKeyIdentifier key = key(0x84, 12328);

        diagnostic.observeCall(P25EncryptionRepeatDiagnostic.Phase.PHASE_1,
            P25EncryptionRepeatDiagnostic.ObservationSource.HDU, event, key, 1000L);
        diagnostic.observeCall(P25EncryptionRepeatDiagnostic.Phase.PHASE_1,
            P25EncryptionRepeatDiagnostic.ObservationSource.LDU2, event, key, 1360L);
        diagnostic.completeCall(event, 2000L);

        P25EncryptionRepeatDiagnostic.Snapshot snapshot = diagnostic.snapshot();
        assertEquals(1, snapshot.completedCalls());
        assertEquals(1, snapshot.phase1Calls());
        assertEquals(1, snapshot.phase1CallsWithMatchingRepeat());
        assertEquals(1, snapshot.callsWithMatchingRepeat());
        assertEquals(1, snapshot.recognizedAlgorithmCalls());
        assertEquals(1, snapshot.recognizedAlgorithmCallsWithMatchingRepeat());
        assertEquals(0, snapshot.callsWithConflictingPairs());
        assertEquals(2, snapshot.totalObservations());
        assertEquals(1, snapshot.hduObservations());
        assertEquals(1, snapshot.ldu2Observations());
        assertEquals(1, reports.size());
    }

    @Test
    void identifiesConflictingPairsWithoutConfirmation()
    {
        P25EncryptionRepeatDiagnostic diagnostic = new P25EncryptionRepeatDiagnostic(true, 100, ignored -> {});
        P25ChannelGrantEvent event = event(1000L);

        diagnostic.observeCall(P25EncryptionRepeatDiagnostic.Phase.PHASE_2,
            P25EncryptionRepeatDiagnostic.ObservationSource.PUSH_TO_TALK, event, key(0x84, 12328), 1000L);
        diagnostic.observeCall(P25EncryptionRepeatDiagnostic.Phase.PHASE_2,
            P25EncryptionRepeatDiagnostic.ObservationSource.ESS, event, key(0x08, 8322), 1360L);
        diagnostic.completeCall(event, 2000L);

        P25EncryptionRepeatDiagnostic.Snapshot snapshot = diagnostic.snapshot();
        assertEquals(1, snapshot.callsWithoutMatchingRepeat());
        assertEquals(0, snapshot.callsWithMatchingRepeat());
        assertEquals(1, snapshot.recognizedAlgorithmCalls());
        assertEquals(0, snapshot.recognizedAlgorithmCallsWithMatchingRepeat());
        assertEquals(1, snapshot.callsWithConflictingPairs());
    }

    @Test
    void ignoresUnencryptedIdentifier()
    {
        P25EncryptionRepeatDiagnostic diagnostic = new P25EncryptionRepeatDiagnostic(true, 100, ignored -> {});
        P25ChannelGrantEvent event = event(1000L);

        diagnostic.observeCall(P25EncryptionRepeatDiagnostic.Phase.PHASE_1,
            P25EncryptionRepeatDiagnostic.ObservationSource.HDU, event, key(0x80, 0), 1000L);
        diagnostic.completeCall(event, 2000L);

        assertEquals(0, diagnostic.snapshot().completedCalls());
    }

    private static P25ChannelGrantEvent event(long timestamp)
    {
        return new P25ChannelGrantEvent(DecodeEventType.CALL_GROUP, timestamp);
    }

    private static EncryptionKeyIdentifier key(int algorithm, int key)
    {
        return EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(algorithm, key));
    }
}
