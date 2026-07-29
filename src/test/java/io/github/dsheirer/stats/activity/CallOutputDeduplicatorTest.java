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
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CallOutputDeduplicatorTest
{
    @Test
    void countsEachOutputOnceAcrossLinkedAudioSegments()
    {
        CallOutputDeduplicator deduplicator = new CallOutputDeduplicator();
        AudioCallId firstId = new AudioCallId(1, 1, 1);
        AudioCallId secondId = new AudioCallId(1, 2, 1);
        AudioCallSnapshot first = snapshot(firstId, null);
        AudioCallSnapshot second = snapshot(secondId, firstId);

        assertTrue(deduplicator.firstOutput(first, P25ActivityLogRecords.CallOutput.RECORDED, 1_000));
        assertFalse(deduplicator.firstOutput(first, P25ActivityLogRecords.CallOutput.RECORDED, 1_001));
        assertTrue(deduplicator.firstOutput(first, P25ActivityLogRecords.CallOutput.STREAMED, 1_002));
        assertFalse(deduplicator.firstOutput(second, P25ActivityLogRecords.CallOutput.RECORDED, 2_000));
        assertFalse(deduplicator.firstOutput(second, P25ActivityLogRecords.CallOutput.STREAMED, 2_001));
    }

    @Test
    void canCountTheFirstSuccessfulOutputOnALaterSegment()
    {
        CallOutputDeduplicator deduplicator = new CallOutputDeduplicator();
        AudioCallId firstId = new AudioCallId(2, 1, 1);
        AudioCallId secondId = new AudioCallId(2, 2, 1);
        AudioCallId thirdId = new AudioCallId(2, 3, 1);
        AudioCallSnapshot second = snapshot(secondId, firstId);
        AudioCallSnapshot third = snapshot(thirdId, secondId);

        assertTrue(deduplicator.firstOutput(second, P25ActivityLogRecords.CallOutput.RECORDED, 2_000));
        assertFalse(deduplicator.firstOutput(third, P25ActivityLogRecords.CallOutput.RECORDED, 3_000));
    }

    private static AudioCallSnapshot snapshot(AudioCallId callId, AudioCallId linkedCallId)
    {
        return new AudioCallSnapshot(callId, linkedCallId, null, new MutableIdentifierCollection(), Set.of(),
            1_000, 2_000, 1, 1, 1_000, 2_000, false, true, false, true, 100, false);
    }
}
