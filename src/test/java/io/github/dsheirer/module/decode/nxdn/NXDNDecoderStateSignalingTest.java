/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.RemoteControlRequest;
import io.github.dsheirer.module.decode.nxdn.layer3.call.RemoteControlResponse;
import io.github.dsheirer.module.decode.nxdn.layer3.call.StatusInquiryRequest;
import io.github.dsheirer.module.decode.nxdn.layer3.call.StatusResponse;
import io.github.dsheirer.module.decode.nxdn.layer3.mobility.RegistrationClearResponse;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CauseMM;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CauseSS;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NXDNDecoderStateSignalingTest
{
    @Test
    void preservesControlOperationsAndResponsesWithoutAssumingSuccess()
    {
        NXDNDecoderState state = state();
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        for(int command: new int[]{0, 1, 2, 4})
        {
            state.receive(new RemoteControlRequest(bits(command), 1_000L + command,
                NXDNMessageType.CONTROL_OUT_52_CC_REMOTE_CONTROL_REQUEST, 3,
                LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
            for(CauseSS cause: List.of(CauseSS.ACCEPTED, CauseSS.CALLED_SU_NOT_PERMITTED_FOR_SERVICE))
            {
                CorrectedBinaryMessage response = bits(command);
                response.load(64, 8, cause.getValue());
                state.receive(new RemoteControlResponse(response, 2_000L + command,
                    NXDNMessageType.CONTROL_OUT_53_CC_REMOTE_CONTROL_RESPONSE, 3,
                    LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
            }
        }
        assertEquals(List.of(DecodeEventType.STUN, DecodeEventType.STUN_RESPONSE, DecodeEventType.STUN_RESPONSE,
            DecodeEventType.REVIVE, DecodeEventType.REVIVE_RESPONSE, DecodeEventType.REVIVE_RESPONSE,
            DecodeEventType.KILL, DecodeEventType.KILL_RESPONSE, DecodeEventType.KILL_RESPONSE,
            DecodeEventType.REMOTE_MONITOR, DecodeEventType.REMOTE_MONITOR_RESPONSE,
            DecodeEventType.REMOTE_MONITOR_RESPONSE), events.stream().map(IDecodeEvent::getEventType).toList());
        assertTrue(events.get(0).getDetails().contains("STUN INHIBIT"));
        assertTrue(events.get(1).getDetails().contains("RESPONSE"));
        assertTrue(events.get(2).getDetails().contains("NOT PERMITTED"));
    }

    @Test
    void collectsInboundControlsButKeepsReservedCommandsGeneric()
    {
        NXDNDecoderState state = state();
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        state.receive(new RemoteControlRequest(bits(0), 1_000L,
            NXDNMessageType.TRAFFIC_IN_52_CC_REMOTE_CONTROL_REQUEST, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        state.receive(new RemoteControlResponse(bits(0), 2_000L,
            NXDNMessageType.TRAFFIC_IN_53_CC_REMOTE_CONTROL_RESPONSE, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        state.receive(new RemoteControlRequest(bits(31), 3_000L,
            NXDNMessageType.CONTROL_OUT_52_CC_REMOTE_CONTROL_REQUEST, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        state.receive(new RemoteControlResponse(bits(31), 4_000L,
            NXDNMessageType.CONTROL_OUT_53_CC_REMOTE_CONTROL_RESPONSE, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        assertEquals(List.of(DecodeEventType.STUN, DecodeEventType.STUN_RESPONSE, DecodeEventType.REQUEST,
            DecodeEventType.RESPONSE), events.stream().map(IDecodeEvent::getEventType).toList());
    }

    @Test
    void separatesStatusQueriesAndResultsAndOnlyLogsAcceptedRegistrationClearsAsLogout()
    {
        NXDNDecoderState state = state();
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        state.receive(new StatusInquiryRequest(bits(0), 1_000L,
            NXDNMessageType.CONTROL_OUT_48_CC_STATUS_INQUIRY_REQUEST, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        state.receive(new StatusResponse(bits(0), 2_000L, NXDNMessageType.CONTROL_OUT_51_CC_STATUS_RESPONSE, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        for(CauseMM cause: List.of(CauseMM.REGISTRATION_ACCEPTED, CauseMM.REGISTRATION_REFUSED, CauseMM.UNKNOWN))
        {
            CorrectedBinaryMessage message = bits(0);
            message.load(48, 8, cause == CauseMM.UNKNOWN ? 0xFF : cause.getValue());
            state.receive(new RegistrationClearResponse(message, 3_000L,
                NXDNMessageType.CONTROL_OUT_34_MM_REGISTRATION_CLEAR_RESPONSE, 3,
                LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        }
        assertEquals(List.of(DecodeEventType.QUERY, DecodeEventType.STATUS, DecodeEventType.DEREGISTER,
            DecodeEventType.DENIAL, DecodeEventType.RESPONSE),
            events.stream().map(IDecodeEvent::getEventType).toList());
    }

    private static CorrectedBinaryMessage bits(int command)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(176);
        message.load(19, 5, command);
        message.load(24, 16, 101);
        message.load(40, 16, 202);
        return message;
    }

    private static NXDNDecoderState state()
    {
        Channel channel = new Channel("NXDN signaling", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setChannelMode(NXDNChannelMode.CONVENTIONAL);
        channel.setDecodeConfiguration(config);
        return new NXDNDecoderState(channel, null);
    }
}
