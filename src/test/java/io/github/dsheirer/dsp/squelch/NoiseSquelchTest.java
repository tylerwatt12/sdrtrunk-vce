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

package io.github.dsheirer.dsp.squelch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.squelch.SquelchState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoiseSquelchTest
{
    @Test
    void manualOverrideReportsUnsquelchedUntilReleased()
    {
        NoiseSquelch squelch = new NoiseSquelch(NoiseSquelch.DEFAULT_NOISE_OPEN_THRESHOLD,
                NoiseSquelch.DEFAULT_NOISE_CLOSE_THRESHOLD, NoiseSquelch.DEFAULT_HYSTERESIS_OPEN_THRESHOLD,
                NoiseSquelch.DEFAULT_HYSTERESIS_CLOSE_THRESHOLD);

        assertTrue(squelch.isSquelched());

        squelch.setSquelchOverride(true);
        assertFalse(squelch.isSquelched());

        squelch.setSquelchOverride(false);
        assertTrue(squelch.isSquelched());
    }

    @Test
    void releasingOverrideClosesDownstreamAudioWhenDetectorIsSquelched()
    {
        NoiseSquelch squelch = new NoiseSquelch(NoiseSquelch.DEFAULT_NOISE_OPEN_THRESHOLD,
                NoiseSquelch.DEFAULT_NOISE_CLOSE_THRESHOLD, NoiseSquelch.DEFAULT_HYSTERESIS_OPEN_THRESHOLD,
                NoiseSquelch.DEFAULT_HYSTERESIS_CLOSE_THRESHOLD);
        List<SquelchState> states = new ArrayList<>();
        squelch.setSquelchStateListener(states::add);

        squelch.setSquelchOverride(true);
        squelch.setSquelchOverride(true);
        squelch.setSquelchOverride(false);

        assertEquals(List.of(SquelchState.UNSQUELCH, SquelchState.SQUELCH), states);
    }
}
