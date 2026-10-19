/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.acknowledge.Acknowledge;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.acknowledge.RegistrationAccepted;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.ahoy.AuthenticateRegisterRadioCheck;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.ahoy.StunReviveKill;
import io.github.dsheirer.module.decode.dmr.message.type.Reason;
import io.github.dsheirer.module.decode.dmr.message.type.Tier3Gateway;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DMRDecoderStateTier3SignalingTest
{
    @Test
    void separatesRadioCheckFromAuthentication() throws Exception
    {
        DMRDecoderState state = state();
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);

        process(state, ahoy(1_000L, Tier3Gateway.TSI));
        process(state, ahoy(2_000L, Tier3Gateway.AUTHI));

        assertEquals(2, events.size());
        assertEquals(DecodeEventType.RADIO_CHECK, events.get(0).getEventType());
        assertEquals("RADIO CHECK", events.get(0).getDetails());
        assertEquals(DecodeEventType.AUTHENTICATE, events.get(1).getEventType());
        assertEquals("AUTHENTICATE", events.get(1).getDetails());
    }

    @Test
    void classifiesExactRegistrationResponsesAndRejectsFakeSourceRadio() throws Exception
    {
        DMRDecoderState state = state();
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        Acknowledge accepted = acknowledge(1_000L, Reason.TS_REGISTRATION_ACCEPTED);
        Acknowledge refused = acknowledge(2_000L, Reason.TS_REGISTRATION_REFUSED);
        Acknowledge denied = acknowledge(3_000L, Reason.TS_REGISTRATION_DENIED);

        process(state, accepted);
        process(state, refused);
        process(state, denied);

        assertEquals(List.of(DecodeEventType.REGISTER, DecodeEventType.DENIAL, DecodeEventType.DENIAL),
            events.stream().map(IDecodeEvent::getEventType).toList());
        assertNull(accepted.getIdentifiers().stream()
            .filter(identifier -> identifier.getRole() == io.github.dsheirer.identifier.Role.FROM)
            .findFirst().orElse(null));
        assertNull(events.get(0).getIdentifierCollection().getFromIdentifier());
        assertEquals(12345, events.get(0).getIdentifierCollection().getToIdentifier().getValue());
        assertFalse(accepted.toString().contains(" FM:"));
    }

    @Test
    void keepsSupplementaryCommandsDistinctAndDoesNotInventGatewaySubscribers() throws Exception
    {
        DMRDecoderState state = state();
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        process(state, supplementary(1_000L, Tier3Gateway.STUNI, false));
        process(state, supplementary(2_000L, Tier3Gateway.STUNI, true));
        process(state, supplementary(3_000L, Tier3Gateway.KILLI, false));
        process(state, supplementary(4_000L, Tier3Gateway.RESERVED, false));

        assertEquals(List.of(DecodeEventType.STUN, DecodeEventType.REVIVE, DecodeEventType.KILL,
            DecodeEventType.COMMAND), events.stream().map(IDecodeEvent::getEventType).toList());
        for(IDecodeEvent event: events.subList(0, 3))
        {
            assertNull(event.getIdentifierCollection().getFromIdentifier());
            assertEquals(12345, event.getIdentifierCollection().getToIdentifier().getValue());
        }
    }

    @Test
    void preservesBusyQueuedDenialAndUnknownAcknowledgementReasons() throws Exception
    {
        DMRDecoderState state = state();
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        for(Reason reason: List.of(Reason.TS_CALLED_PARTY_BUSY, Reason.MS_REFUSED_EQUIPMENT_BUSY,
            Reason.TS_QUEUED_FOR_BUSY_RADIO, Reason.TS_QUEUED_FOR_RESOURCE, Reason.TS_REFUSED_NOT_PERMITTED,
            Reason.TS_MESSAGE_ACCEPTED, Reason.UNKNOWN))
        {
            process(state, acknowledge(1_000L + events.size(), reason));
        }
        assertEquals(List.of(DecodeEventType.BUSY, DecodeEventType.BUSY, DecodeEventType.QUEUED,
            DecodeEventType.QUEUED, DecodeEventType.DENIAL, DecodeEventType.ACKNOWLEDGE, DecodeEventType.RESPONSE),
            events.stream().map(IDecodeEvent::getEventType).toList());
    }

    private static StunReviveKill supplementary(long timestamp, Tier3Gateway gateway, boolean revive)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(80);
        bits.load(2, 6, 28);
        bits.load(23, 1, revive ? 1 : 0);
        bits.load(28, 4, 13);
        bits.load(32, 24, 12345);
        bits.load(56, 24, gateway.getValue());
        return new StunReviveKill(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), timestamp, 1);
    }

    private static DMRDecoderState state()
    {
        Channel channel = new Channel("DMR Tier III", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        channel.setDecodeConfiguration(config);
        return new DMRDecoderState(channel, 1, null);
    }

    private static AuthenticateRegisterRadioCheck ahoy(long timestamp, Tier3Gateway gateway)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(80);
        bits.load(2, 6, 28);
        bits.load(28, 4, 14);
        bits.load(32, 24, 12345);
        bits.load(56, 24, gateway.getValue());
        return new AuthenticateRegisterRadioCheck(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(),
            timestamp, 1);
    }

    private static Acknowledge acknowledge(long timestamp, Reason reason)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(80);
        bits.load(2, 6, 32);
        bits.load(23, 8, reason == Reason.UNKNOWN ? 0x7F : reason.getValue());
        bits.load(32, 24, 12345);
        bits.load(56, 24, 54321);
        return reason == Reason.TS_REGISTRATION_ACCEPTED ?
            new RegistrationAccepted(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), timestamp, 1) :
            new Acknowledge(DMRSyncPattern.BASE_STATION_DATA, bits, null, slotType(), timestamp, 1);
    }

    private static SlotType slotType()
    {
        return new SlotType(new CorrectedBinaryMessage(24));
    }

    private static void process(DMRDecoderState state, CSBKMessage message) throws Exception
    {
        Method method = DMRDecoderState.class.getDeclaredMethod("processCSBK", CSBKMessage.class);
        method.setAccessible(true);
        method.invoke(state, message);
    }
}
