/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class DecodeEventViewServiceTest
{
    private static final String CONFIGURATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final long FREQUENCY = 851_012_500L;

    @Test
    void keepsStableIdentityWhileProjectingEventUpdates()
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED, 1_000L)
            .duration(250L)
            .channel(new StandardChannel(FREQUENCY))
            .details("  " + "x".repeat(600) + "  ")
            .protocol(Protocol.APCO25_PHASE2)
            .timeslot(1)
            .build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView initial = service.view(CONFIGURATION_ID, event);
            event.update(1_500L);
            DecodeEventViewService.EventView updated = service.view(CONFIGURATION_ID, event);

            assertEquals(initial.eventId(), updated.eventId());
            assertEquals(250L, initial.durationMs());
            assertEquals(500L, updated.durationMs());
            assertEquals("ENCRYPTED_VOICE", updated.category());
            assertEquals(FREQUENCY, updated.frequencyHz());
            assertEquals(1, updated.timeslot());
            assertEquals("APCO25_PHASE2", updated.protocol());
            assertEquals(512, updated.details().length());
            assertTrue(updated.details().endsWith("…"));
        }
    }

    @Test
    void scopeMatchesTheConfiguredReceiverAndOptionalFrequency()
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .channel(new StandardChannel(FREQUENCY))
            .timeslot(1)
            .build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView view = service.view(CONFIGURATION_ID, event);

            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, null, null).matches(view));
            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, null).matches(view));
            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, 1).matches(view));
            assertFalse(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, 2).matches(view));
            assertFalse(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY + 1, null).matches(view));
            assertFalse(new DecodeEventViewService.Scope("other", null, null).matches(view));
        }
    }

    @Test
    void usesTheProcessingSourceFrequencyWhenTheEventHasNoChannelDescriptor()
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L).build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView view = service.view(CONFIGURATION_ID, event, FREQUENCY);
            assertEquals(FREQUENCY, view.frequencyHz());
            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, null).matches(view));
        }
    }
}
