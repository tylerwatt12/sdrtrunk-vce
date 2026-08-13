/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the diagnostic snapshot's inclusion of disabled and error-state discovered tuners. */
class DiscoveredTunerModelSnapshotTest
{
    @Test
    void diagnosticSnapshotDoesNotFilterOnLiveTunerPresence() throws Exception
    {
        String source = Files.readString(
            Path.of("src/main/java/io/github/dsheirer/source/tuner/ui/DiscoveredTunerModel.java"));
        String method = source.substring(source.indexOf("public List<DiscoveredTuner> getDiscoveredTuners()"));
        method = method.substring(0, method.indexOf("\n    }", method.indexOf("\n    {") + 1) + 6);
        assertTrue(method.contains("List.copyOf(mDiscoveredTuners)"));
        assertFalse(method.contains("hasTuner()"));
    }
}
