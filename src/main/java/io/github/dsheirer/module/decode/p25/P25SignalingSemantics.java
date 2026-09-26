/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.reference.ExtendedFunction;
import io.github.dsheirer.module.decode.p25.reference.QueuedResponseReason;
import io.github.dsheirer.protocol.Protocol;

/**
 * Exact P25 signaling categories shared by decoder event construction and bounded live projections.
 * Generic requests, responses, and display text are deliberately excluded.
 */
public final class P25SignalingSemantics
{
    public enum Action
    {
        DENIAL,
        CHECK,
        EMERGENCY,
        PAGE,
        BUSY
    }

    private P25SignalingSemantics()
    {
    }

    /** Allocation-free producer-side candidate check. Full identity/detail projection remains observer-owned. */
    public static boolean mayProduceObservation(IDecodeEvent event)
    {
        return action(event) != null;
    }

    public static Action action(IDecodeEvent event)
    {
        if(event == null || !isP25(event.getProtocol()))
        {
            return null;
        }

        return event instanceof P25SignalingEvent signalingEvent ? signalingEvent.getAction() :
            action(event.getEventType());
    }

    public static DecodeEventType eventType(ExtendedFunction function, DecodeEventType fallback)
    {
        if(function == null)
        {
            return fallback;
        }

        return switch(function)
        {
            case RADIO_CHECK, RADIO_CHECK_ACK -> DecodeEventType.RADIO_CHECK;
            case RADIO_UNINHIBIT -> DecodeEventType.RADIO_UNINHIBIT;
            case RADIO_INHIBIT -> DecodeEventType.RADIO_INHIBIT;
            case RADIO_UNINHIBIT_ACK -> DecodeEventType.RADIO_UNINHIBIT_ACK;
            case RADIO_INHIBIT_ACK -> DecodeEventType.RADIO_INHIBIT_ACK;
            default -> fallback;
        };
    }

    private static Action action(DecodeEventType eventType)
    {
        if(eventType == null)
        {
            return null;
        }

        return switch(eventType)
        {
            case DENIAL -> Action.DENIAL;
            case QUERY, RADIO_CHECK -> Action.CHECK;
            case EMERGENCY -> Action.EMERGENCY;
            case CALL_ALERT, PAGE -> Action.PAGE;
            default -> null;
        };
    }

    public static boolean isBusy(QueuedResponseReason reason)
    {
        return reason == QueuedResponseReason.REQUESTING_UNIT_BUSY_OTHER_SERVICE ||
            reason == QueuedResponseReason.TARGET_UNIT_BUSY_OTHER_SERVICE ||
            reason == QueuedResponseReason.TARGET_GROUP_CURRENTLY_ACTIVE ||
            reason == QueuedResponseReason.SUPERSEDING_SERVICE_CURRENTLY_ACTIVE;
    }

    private static boolean isP25(Protocol protocol)
    {
        return protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2;
    }
}
