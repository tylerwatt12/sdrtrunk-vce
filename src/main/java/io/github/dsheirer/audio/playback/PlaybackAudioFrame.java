/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.playback;

import java.util.List;

/**
 * Final 8 kHz, signed 16-bit, little-endian PCM emitted by the shared playback output, tagged with the calls that
 * produced it so downstream sinks can align display metadata to the audio timeline.
 */
public record PlaybackAudioFrame(byte[] pcm, int channels, List<AudioPlaybackCall> playing)
{
    public PlaybackAudioFrame
    {
        playing = playing != null ? List.copyOf(playing) : List.of();
    }

    public PlaybackAudioFrame(byte[] pcm, int channels)
    {
        this(pcm, channels, List.of());
    }

    public int sampleCount()
    {
        return pcm != null && channels > 0 ? pcm.length / (Short.BYTES * channels) : 0;
    }
}
