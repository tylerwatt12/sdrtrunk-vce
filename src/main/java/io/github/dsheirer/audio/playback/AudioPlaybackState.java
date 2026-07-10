/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.playback;

import java.util.List;

/**
 * Immutable local playback scheduler state.
 */
public record AudioPlaybackState(boolean localMuted, List<AudioPlaybackCall> playing, List<AudioPlaybackCall> queued,
                                 String currentTarget, String holdTarget, List<String> avoidedTargets)
{
    public AudioPlaybackState
    {
        playing = playing != null ? List.copyOf(playing) : List.of();
        queued = queued != null ? List.copyOf(queued) : List.of();
        avoidedTargets = avoidedTargets != null ? List.copyOf(avoidedTargets) : List.of();
    }

    public int queuedCallCount()
    {
        return queued.size();
    }
}
