package io.github.dsheirer.gui.setup;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JmbeSetupMessagesTest
{
    @Test void onlyKnownStagesBecomeUserFacingHeadlines()
    {
        assertEquals("Downloading digital voice tools…",JmbeSetupMessages.stage("Downloading JMBE creator..."));
        assertTrue(JmbeSetupMessages.stage("Building JMBE...").contains("few minutes"));
        assertNull(JmbeSetupMessages.stage("compiler diagnostics and a path to a generated file"));
        assertNull(JmbeSetupMessages.stage(null));
        assertNull(JmbeSetupMessages.stage("x".repeat(1024)));
    }
}
