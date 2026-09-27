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
package io.github.dsheirer.record.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordingStatusBroadcasterTest
{
    @Test
    void replacementListenerReceivesCurrentAndFutureRecordingStatus()
    {
        RecordingStatusBroadcaster broadcaster = new RecordingStatusBroadcaster();
        List<Status> originalUpdates = new ArrayList<>();
        List<Status> replacementUpdates = new ArrayList<>();
        IRecordingStatusListener original = (count, file, size) ->
                originalUpdates.add(new Status(count, file, size));
        IRecordingStatusListener replacement = (count, file, size) ->
                replacementUpdates.add(new Status(count, file, size));

        broadcaster.addListener(original);
        broadcaster.update(1, "capture.wav", 1_048_576);
        broadcaster.removeListener(original);
        broadcaster.addListener(replacement);

        assertEquals(List.of(new Status(1, "capture.wav", 1_048_576)), originalUpdates);
        assertEquals(List.of(new Status(1, "capture.wav", 1_048_576)), replacementUpdates);

        broadcaster.update(1, "capture.wav", 2_097_152);

        assertEquals(List.of(new Status(1, "capture.wav", 1_048_576)), originalUpdates);
        assertEquals(List.of(new Status(1, "capture.wav", 1_048_576),
                new Status(1, "capture.wav", 2_097_152)), replacementUpdates);
    }

    @Test
    void clearedStatusIsNotReplayedForStoppedRecording()
    {
        RecordingStatusBroadcaster broadcaster = new RecordingStatusBroadcaster();
        List<Status> updates = new ArrayList<>();
        broadcaster.update(1, "stopped.wav", 2048);
        broadcaster.clearStatus();
        broadcaster.addListener((count, file, size) -> updates.add(new Status(count, file, size)));

        assertEquals(List.of(), updates);
    }

    @Test
    void failureReachesCurrentListenerWithoutMasqueradingAsProgress()
    {
        RecordingStatusBroadcaster broadcaster = new RecordingStatusBroadcaster();
        List<String> failures = new ArrayList<>();
        broadcaster.addListener(new IRecordingStatusListener()
        {
            @Override
            public void update(int count, String file, long size)
            {
            }

            @Override
            public void failed(String reason)
            {
                failures.add(reason);
            }
        });

        broadcaster.failed("Incomplete recording");

        assertEquals(List.of("Incomplete recording"), failures);
    }

    private record Status(int fileCount, String file, long size) {}
}
