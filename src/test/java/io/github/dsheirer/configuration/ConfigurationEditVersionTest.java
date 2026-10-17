/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigurationEditVersionTest
{
    @Test
    void equivalentJavaAndBrowserNumbersAndMapOrderHaveTheSameVersion()
    {
        var java = Map.of("nested", List.of(Map.of("gain", 1.0f, "threshold", 0.1f)), "zero", -0.0d);
        var browser = Map.of("zero", 0, "nested", List.of(Map.of("threshold", 0.1d, "gain", 1)));
        assertEquals(ConfigurationEditVersion.of(java), ConfigurationEditVersion.of(browser));
        assertNotEquals(ConfigurationEditVersion.of(java), ConfigurationEditVersion.of(
            Map.of("nested", List.of(Map.of("gain", 2, "threshold", 0.1d)), "zero", 0)));
    }
}
