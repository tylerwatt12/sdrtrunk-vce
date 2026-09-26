/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.reference.ExtendedFunction;
import io.github.dsheirer.module.decode.p25.reference.QueuedResponseReason;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class P25SignalingSemanticsTest
{
    @Test
    void mapsOnlyExactP25SignalTypes()
    {
        assertEquals(P25SignalingSemantics.Action.DENIAL, action(DecodeEventType.DENIAL));
        assertEquals(P25SignalingSemantics.Action.CHECK, action(DecodeEventType.RADIO_CHECK));
        assertEquals(P25SignalingSemantics.Action.CHECK, action(DecodeEventType.QUERY));
        assertEquals(P25SignalingSemantics.Action.EMERGENCY, action(DecodeEventType.EMERGENCY));
        assertEquals(P25SignalingSemantics.Action.PAGE, action(DecodeEventType.PAGE));
        assertEquals(P25SignalingSemantics.Action.PAGE, action(DecodeEventType.CALL_ALERT));
        assertEquals(P25SignalingSemantics.Action.BUSY, P25SignalingSemantics.action(
            new P25SignalingEvent(DecodeEventType.RESPONSE, 1_000L, P25SignalingSemantics.Action.BUSY)));

        assertNull(action(DecodeEventType.REQUEST));
        assertNull(action(DecodeEventType.RESPONSE));
        assertNull(action(DecodeEventType.COMMAND));
        assertFalse(P25SignalingSemantics.mayProduceObservation(event(DecodeEventType.EMERGENCY, Protocol.DMR)));
    }

    @Test
    void decoderHelpersPreserveRequestsAndTypeOnlyKnownSignals()
    {
        assertEquals(DecodeEventType.RADIO_CHECK,
            P25SignalingSemantics.eventType(ExtendedFunction.RADIO_CHECK, DecodeEventType.COMMAND));
        assertEquals(DecodeEventType.RADIO_CHECK,
            P25SignalingSemantics.eventType(ExtendedFunction.RADIO_CHECK_ACK, DecodeEventType.RESPONSE));
        assertEquals(DecodeEventType.COMMAND,
            P25SignalingSemantics.eventType(ExtendedFunction.RADIO_INHIBIT, DecodeEventType.COMMAND));
        assertEquals(DecodeEventType.RESPONSE,
            P25SignalingSemantics.eventType(ExtendedFunction.RADIO_INHIBIT_ACK, DecodeEventType.RESPONSE));

        assertTrue(P25SignalingSemantics.isBusy(QueuedResponseReason.REQUESTING_UNIT_BUSY_OTHER_SERVICE));
        assertTrue(P25SignalingSemantics.isBusy(QueuedResponseReason.TARGET_GROUP_CURRENTLY_ACTIVE));
        assertFalse(P25SignalingSemantics.isBusy(QueuedResponseReason.TARGET_UNIT_QUEUED_THIS_CALL));
        assertFalse(P25SignalingSemantics.isBusy(QueuedResponseReason.CHANNEL_RESOURCES_UNAVAILABLE));
    }

    private static P25SignalingSemantics.Action action(DecodeEventType type)
    {
        DecodeEvent event = event(type, Protocol.APCO25);
        assertEquals(P25SignalingSemantics.action(event) != null,
            P25SignalingSemantics.mayProduceObservation(event));
        return P25SignalingSemantics.action(event);
    }

    private static DecodeEvent event(DecodeEventType type, Protocol protocol)
    {
        DecodeEvent event = new DecodeEvent(type, 1_000L);
        event.setProtocol(protocol);
        return event;
    }
}
