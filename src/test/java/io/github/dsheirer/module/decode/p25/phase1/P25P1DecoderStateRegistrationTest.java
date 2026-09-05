/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25IncompleteRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCUnitRegistrationResponse;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.UnitRegistrationResponse;
import io.github.dsheirer.module.decode.p25.reference.Response;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationStabilizer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class P25P1DecoderStateRegistrationTest
{
    private static final int NAC = 0x293;
    private static final int RADIO_ID = 1_811_524;
    private static final String ABBREVIATED_REGISTRATION = "AC0009540CAE7EFFFD2657BA";

    @Test
    void tsbkAndAmbtcUnitRegistrationResponsesBroadcastStructuredState()
    {
        Channel channel = new Channel("P25 Registration", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState state = new P25P1DecoderState(channel, new P25TrafficChannelManager(channel));
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        state.addDecodeEventListener(events::add);

        state.receive(new TestTSBKUnitRegistration(Response.ACCEPTED, 1_000L));
        state.receive(new TestAMBTCUnitRegistration(Response.DENIED, 2_000L));

        assertEquals(2, events.size());
        assertRegistration(events.get(0), P25AffiliationEvent.Outcome.ACCEPTED, 1_000L);
        assertRegistration(events.get(1), P25AffiliationEvent.Outcome.REJECTED, 2_000L);
    }

    @Test
    void abbreviatedRegistrationUsesTheLearnedServingWacnWithoutReplacingItsHomeSystem()
    {
        Channel channel = new Channel("P25 Roaming Registration", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25NetworkConfigurationStabilizer stabilizer = stabilizer(0xBEE00, 0x3A9, 0x3A1);
        P25P1DecoderState state = new P25P1DecoderState(channel, new P25TrafficChannelManager(channel), stabilizer);
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        state.addDecodeEventListener(events::add);

        state.receive(new UnitRegistrationResponse(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            CorrectedBinaryMessage.loadHex(ABBREVIATED_REGISTRATION), 0x3A1, 1_000L));

        P25AffiliationEvent event = assertInstanceOf(P25AffiliationEvent.class, events.getFirst());
        APCO25FullyQualifiedRadioIdentifier radio = assertInstanceOf(
            APCO25FullyQualifiedRadioIdentifier.class, event.getRadioIdentifier());
        assertEquals(0xFFFD26, radio.getValue());
        assertEquals(0xBEE00, radio.getWacn());
        assertEquals(0x954, radio.getSystem());
        assertEquals(831_102, radio.getRadio());
    }

    @Test
    void abbreviatedRegistrationRemainsIncompleteBeforeTheServingWacnIsKnown()
    {
        Channel channel = new Channel("P25 Registration", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState state = new P25P1DecoderState(channel, new P25TrafficChannelManager(channel));
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        state.addDecodeEventListener(events::add);

        state.receive(new UnitRegistrationResponse(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            CorrectedBinaryMessage.loadHex(ABBREVIATED_REGISTRATION), 0x3A1, 1_000L));

        P25AffiliationEvent event = assertInstanceOf(P25AffiliationEvent.class, events.getFirst());
        assertInstanceOf(APCO25IncompleteRadioIdentifier.class, event.getRadioIdentifier());
        assertEquals(0xFFFD26, event.getRadioId());
    }

    private static P25NetworkConfigurationStabilizer stabilizer(int wacn, int system, int nac)
    {
        P25NetworkConfigurationStabilizer stabilizer = new P25NetworkConfigurationStabilizer("P25_PHASE_1");
        stabilizer.observe(new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(wacn, system, nac, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(system, nac, 1, 1, null, true),
            List.of(), List.of(), List.of(), List.of(), List.of()), 100L);
        return stabilizer;
    }

    private static void assertRegistration(IDecodeEvent candidate, P25AffiliationEvent.Outcome outcome,
                                           long timestamp)
    {
        P25AffiliationEvent event = assertInstanceOf(P25AffiliationEvent.class, candidate);
        assertEquals(DecodeEventType.REGISTER, event.getEventType());
        assertEquals(outcome, event.getOutcome());
        assertEquals(RADIO_ID, event.getRadioId());
        assertNull(event.getTalkgroupId());
        assertEquals(timestamp, event.getTimeStart());
    }

    private static AMBTCHeader header()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.set(2); //Outbound
        bits.setInt(Opcode.OSP_UNIT_REGISTRATION_RESPONSE.getCode(), IntField.length6(58));
        return new AMBTCHeader(bits, true);
    }

    private static class TestTSBKUnitRegistration extends UnitRegistrationResponse
    {
        private final Response mResponse;
        private final Identifier mRadio = APCO25RadioIdentifier.createTo(RADIO_ID);

        private TestTSBKUnitRegistration(Response response, long timestamp)
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), NAC, timestamp);
            mResponse = response;
        }

        @Override
        public Opcode getOpcode()
        {
            return Opcode.OSP_UNIT_REGISTRATION_RESPONSE;
        }

        @Override
        public Response getResponse()
        {
            return mResponse;
        }

        @Override
        public Identifier getRegisteredRadio()
        {
            return mRadio;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of(mRadio);
        }
    }

    private static class TestAMBTCUnitRegistration extends AMBTCUnitRegistrationResponse
    {
        private final Response mResponse;
        private final Identifier mRadio = APCO25RadioIdentifier.createTo(RADIO_ID);

        private TestAMBTCUnitRegistration(Response response, long timestamp)
        {
            super(new PDUSequence(header(), timestamp, NAC), NAC, timestamp);
            mResponse = response;
        }

        @Override
        public Response getResponse()
        {
            return mResponse;
        }

        @Override
        public Identifier getRegistrationAddress()
        {
            return mRadio;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of(mRadio);
        }
    }
}
