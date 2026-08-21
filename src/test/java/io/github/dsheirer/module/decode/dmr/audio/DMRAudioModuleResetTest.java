/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.SourceEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DMRAudioModuleResetTest
{
    @Test
    void sourceBoundaryCompletesTheCurrentAudioCall()
    {
        TestDMRAudioModule module = new TestDMRAudioModule();
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);

        try
        {
            module.openAudioCall();
            module.getSourceEventListener().receive(SourceEvent.stopSampleStreamNotification(null));

            assertEquals(1, events.stream()
                .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).count());
        }
        finally
        {
            module.dispose();
        }
    }

    private static class TestDMRAudioModule extends DMRAudioModule
    {
        private TestDMRAudioModule()
        {
            super(new UserPreferences(), AliasList.empty("test"), 1);
        }

        private void openAudioCall()
        {
            beginCurrentAudioSegment();
        }
    }
}
