/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.playback;

import io.github.dsheirer.sample.Listener;

/**
 * Shared local playback state, controls, and final audio output.
 */
public interface IAudioPlaybackSession
{
    AudioPlaybackState getPlaybackState();
    void addPlaybackStateListener(Listener<AudioPlaybackState> listener);
    void removePlaybackStateListener(Listener<AudioPlaybackState> listener);
    void addPlaybackAudioListener(Listener<PlaybackAudioFrame> listener);
    void removePlaybackAudioListener(Listener<PlaybackAudioFrame> listener);
    boolean toggleHoldOnCurrentCall();
    boolean avoidCurrentCall();
    void clearAvoids();
}
