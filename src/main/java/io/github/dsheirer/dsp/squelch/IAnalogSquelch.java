/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.squelch;

import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.sample.Listener;

/**
 * Squelch strategy for the shared conventional analog decoder.
 */
public interface IAnalogSquelch extends INoiseSquelchController
{
    boolean isSquelched();

    /**
     * Returns a lock-free snapshot for diagnostic displays.  Reading this value must not affect squelch behavior.
     */
    NoiseSquelchState getNoiseSquelchState();

    void setSampleRate(double sampleRate);

    void setAudioListener(Listener<float[]> listener);

    void setSquelchStateListener(Listener<SquelchState> listener);

    /**
     * Evaluates a demodulated audio buffer and its aligned filtered complex samples.
     */
    void process(float[] audio, float[] i, float[] q);
}
