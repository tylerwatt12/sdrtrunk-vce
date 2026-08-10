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

package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.UnitRegistrationResponseAbbreviated;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.UnitRegistrationResponseExtended;
import io.github.dsheirer.module.decode.p25.reference.Response;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class P25P2DecoderStateRegistrationTest
{
    private static final int NAC = 0x293;
    private static final int RADIO_ID = 1_811_524;

    @Test
    void abbreviatedAndExtendedUnitRegistrationResponsesBroadcastStructuredState()
    {
        Channel channel = new Channel("P25 Phase 2 Registration", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        P25P2DecoderState state = new P25P2DecoderState(channel, 0, new P25TrafficChannelManager(channel),
            new PatchGroupManager());
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        state.addDecodeEventListener(events::add);

        state.receive(message(1_000L, new TestAbbreviatedUnitRegistration(Response.ACCEPTED)));
        state.receive(message(2_000L, new TestExtendedUnitRegistration(Response.REFUSED)));

        assertEquals(2, events.size());
        assertRegistration(events.get(0), P25AffiliationEvent.Outcome.ACCEPTED, 1_000L);
        assertRegistration(events.get(1), P25AffiliationEvent.Outcome.REJECTED, 2_000L);
    }

    private static MacMessage message(long timestamp, MacStructure structure)
    {
        MacMessage message = new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH,
            new CorrectedBinaryMessage(180), timestamp, structure);
        message.setNAC(NAC);
        return message;
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

    private static class TestAbbreviatedUnitRegistration extends UnitRegistrationResponseAbbreviated
    {
        private final Response mResponse;
        private final Identifier mRadio = APCO25RadioIdentifier.createTo(RADIO_ID);

        private TestAbbreviatedUnitRegistration(Response response)
        {
            super(new CorrectedBinaryMessage(180), 0);
            mResponse = response;
        }

        @Override
        public MacOpcode getOpcode()
        {
            return MacOpcode.PHASE1_6C_UNIT_REGISTRATION_RESPONSE_ABBREVIATED;
        }

        @Override
        public Response getResponse()
        {
            return mResponse;
        }

        @Override
        public Identifier getTargetAddress()
        {
            return mRadio;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of(mRadio);
        }
    }

    private static class TestExtendedUnitRegistration extends UnitRegistrationResponseExtended
    {
        private final Response mResponse;
        private final Identifier mRadio = APCO25RadioIdentifier.createTo(RADIO_ID);

        private TestExtendedUnitRegistration(Response response)
        {
            super(new CorrectedBinaryMessage(180), 0);
            mResponse = response;
        }

        @Override
        public MacOpcode getOpcode()
        {
            return MacOpcode.PHASE1_EC_UNIT_REGISTRATION_RESPONSE_EXTENDED;
        }

        @Override
        public Response getResponse()
        {
            return mResponse;
        }

        @Override
        public Identifier getTargetAddress()
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
