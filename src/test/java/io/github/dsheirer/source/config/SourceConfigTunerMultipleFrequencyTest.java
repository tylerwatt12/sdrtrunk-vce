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

package io.github.dsheirer.source.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceConfigTunerMultipleFrequencyTest
{
    @Test
    void fallsBackToFirstFrequencyWhenPreferredFrequencyIsRemoved()
    {
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(851_012_500L, 852_012_500L));
        source.setPreferredFrequency(852_012_500L);

        source.setFrequencies(List.of(851_012_500L));

        assertEquals(851_012_500L, source.getPreferredFrequency());
    }
}
