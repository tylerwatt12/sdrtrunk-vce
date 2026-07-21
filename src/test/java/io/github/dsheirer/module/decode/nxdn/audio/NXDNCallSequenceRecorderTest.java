/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.nxdn.audio;

import io.github.dsheirer.audio.codec.mbe.VoiceFrame;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNEncryptionKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NXDNCallSequenceRecorderTest
{
    @Test
    void recordsTransmittedCipherTypeAndKeyIdOnNextFrame()
    {
        NXDNCallSequenceRecorder.EncryptionContextTracker tracker =
            new NXDNCallSequenceRecorder.EncryptionContextTracker();

        tracker.update(NXDNEncryptionKey.create(3, 27));
        VoiceFrame marker = tracker.createVoiceFrame(1000L, "001122334455667788");
        VoiceFrame following = tracker.createVoiceFrame(1020L, "001122334455667788");

        assertTrue(tracker.isEncrypted());
        assertEquals(3, marker.getAlgorithm());
        assertEquals(27, marker.getKeyId());
        assertNull(marker.getMessageIndicator());
        assertNull(following.getAlgorithm());
        assertNull(following.getKeyId());
    }

    @Test
    void emitsAnotherMarkerOnlyWhenTransmittedContextChanges()
    {
        NXDNCallSequenceRecorder.EncryptionContextTracker tracker =
            new NXDNCallSequenceRecorder.EncryptionContextTracker();

        tracker.update(NXDNEncryptionKey.create(2, 4));
        tracker.createVoiceFrame(1000L, "001122334455667788");
        tracker.update(NXDNEncryptionKey.create(2, 4));
        VoiceFrame unchanged = tracker.createVoiceFrame(1020L, "001122334455667788");
        tracker.update(NXDNEncryptionKey.create(2, 5));
        VoiceFrame changed = tracker.createVoiceFrame(1040L, "001122334455667788");

        assertNull(unchanged.getAlgorithm());
        assertEquals(2, changed.getAlgorithm());
        assertEquals(5, changed.getKeyId());
    }

    @Test
    void clearsMetadataForTransmittedUnencryptedContext()
    {
        NXDNCallSequenceRecorder.EncryptionContextTracker tracker =
            new NXDNCallSequenceRecorder.EncryptionContextTracker();

        tracker.update(NXDNEncryptionKey.create(1, 12));
        tracker.update(NXDNEncryptionKey.create(0, 0));
        VoiceFrame frame = tracker.createVoiceFrame(1000L, "001122334455667788");

        assertFalse(tracker.isEncrypted());
        assertNull(frame.getAlgorithm());
        assertNull(frame.getKeyId());
    }

    @Test
    void activatesFullInitializationVectorOnFollowingRfFrame()
    {
        NXDNCallSequenceRecorder.EncryptionContextTracker tracker =
            new NXDNCallSequenceRecorder.EncryptionContextTracker();

        tracker.update(NXDNEncryptionKey.create(3, 27));
        tracker.observeFullInitializationVector("0000000000000123", 1000L);
        tracker.beginAudioFrame(1000L);
        VoiceFrame sameRfFrame = tracker.createVoiceFrame(1000L, "001122334455667788");
        tracker.beginAudioFrame(1080L);
        VoiceFrame followingRfFrame = tracker.createVoiceFrame(1080L, "001122334455667788");

        assertNull(sameRfFrame.getMessageIndicator());
        assertEquals("0000000000000123", followingRfFrame.getMessageIndicator());
        assertEquals(3, followingRfFrame.getAlgorithm());
        assertEquals(27, followingRfFrame.getKeyId());
    }

    @Test
    void activatesAssembledTypeDInitializationVectorAtNextSuperframe()
    {
        NXDNCallSequenceRecorder.EncryptionContextTracker tracker =
            new NXDNCallSequenceRecorder.EncryptionContextTracker();

        tracker.update(NXDNEncryptionKey.create(2, 4));
        tracker.createVoiceFrame(1000L, "001122334455667788");

        tracker.beginTypeDSuperframe();
        tracker.observeTypeDInitializationVectorPart1(0x5AB, true);
        tracker.observeTypeDInitializationVectorPart2(0xCDE, true);
        tracker.beginAudioFrame(1080L);
        VoiceFrame currentSuperframe = tracker.createVoiceFrame(1080L, "001122334455667788");

        tracker.beginTypeDSuperframe();
        VoiceFrame followingSuperframe = tracker.createVoiceFrame(1400L, "001122334455667788");

        assertNull(currentSuperframe.getMessageIndicator());
        assertEquals("5ABCDE", followingSuperframe.getMessageIndicator());
        assertEquals(2, followingSuperframe.getAlgorithm());
        assertEquals(4, followingSuperframe.getKeyId());
    }

    @Test
    void doesNotCombineTypeDInitializationVectorAcrossDirections()
    {
        NXDNCallSequenceRecorder.EncryptionContextTracker tracker =
            new NXDNCallSequenceRecorder.EncryptionContextTracker();

        tracker.update(NXDNEncryptionKey.create(3, 8));
        tracker.createVoiceFrame(1000L, "001122334455667788");
        tracker.beginTypeDSuperframe();
        tracker.observeTypeDInitializationVectorPart1(0x5AB, true);
        tracker.observeTypeDInitializationVectorPart2(0xCDE, false);
        tracker.beginTypeDSuperframe();
        VoiceFrame frame = tracker.createVoiceFrame(1400L, "001122334455667788");

        assertNull(frame.getMessageIndicator());
        assertNull(frame.getAlgorithm());
    }

    @Test
    void preservesLateEntryTypeDInitializationVectorUntilKeyIdArrives()
    {
        NXDNCallSequenceRecorder.EncryptionContextTracker tracker =
            new NXDNCallSequenceRecorder.EncryptionContextTracker();

        tracker.beginTypeDSuperframe();
        tracker.observeTypeDInitializationVectorPart1(0x321, false);
        tracker.observeTypeDInitializationVectorPart2(0xABC, false);
        tracker.update(NXDNEncryptionKey.create(3, 19));
        tracker.beginTypeDSuperframe();
        VoiceFrame marker = tracker.createVoiceFrame(1400L, "001122334455667788");

        assertEquals(3, marker.getAlgorithm());
        assertEquals(19, marker.getKeyId());
        assertEquals("321ABC", marker.getMessageIndicator());
    }
}
