/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AbstractAudioModuleTest
{
    @Test
    void distinguishesLinkedSegmentRolloverFromTerminalCompletion()
    {
        TestAudioModule module = new TestAudioModule();
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);

        module.appendFrame();
        module.appendFrame();
        module.stop();

        List<AudioCallEvent> completions = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED)
            .toList();
        assertEquals(2, completions.size());
        assertTrue(completions.get(0).continuationExpected());
        assertFalse(completions.get(1).continuationExpected());
    }

    private static class TestAudioModule extends AbstractAudioModule
    {
        private TestAudioModule()
        {
            super(AliasList.empty("Test"), 0, 20);
        }

        private void appendFrame()
        {
            addAudio(new float[160]);
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
        }
    }
}
