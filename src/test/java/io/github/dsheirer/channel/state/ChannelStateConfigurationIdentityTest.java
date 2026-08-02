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

package io.github.dsheirer.channel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.decoder.TrafficChannelIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelStateConfigurationIdentityTest
{
    private static final String CONFIGURATION_ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void singleChannelStatePublishesConfigurationIdentity()
    {
        Channel channel = channel();
        SingleChannelState state = new SingleChannelState(channel, new AliasModel());
        List<IdentifierUpdateNotification> updates = new ArrayList<>();
        state.setIdentifierUpdateListener(updates::add);

        state.start();

        assertIdentity(updates, 0);
    }

    @Test
    void multiChannelStatePublishesConfigurationIdentityForEveryTimeslot()
    {
        Channel channel = channel();
        MultiChannelState state = new MultiChannelState(channel, new AliasModel(), new int[] {1, 2});
        List<IdentifierUpdateNotification> updates = new ArrayList<>();
        state.setIdentifierUpdateListener(updates::add);

        state.start();

        assertIdentity(updates, 1);
        assertIdentity(updates, 2);
    }

    @Test
    void injectedSingleChannelTrafficIdentifierIsRebroadcast()
    {
        SingleChannelState state = new SingleChannelState(channel(), new AliasModel());
        List<IdentifierUpdateNotification> updates = new ArrayList<>();
        state.setIdentifierUpdateListener(updates::add);
        APCO25Talkgroup talkgroup = APCO25Talkgroup.create(4400);

        state.updateChannelStateIdentifiers(new IdentifierUpdateNotification(talkgroup,
            IdentifierUpdateNotification.Operation.ADD, 0));

        List<IdentifierUpdateNotification> matches = updates.stream()
            .filter(IdentifierUpdateNotification::isAdd)
            .filter(update -> talkgroup.equals(update.getIdentifier()))
            .toList();
        assertEquals(1, matches.size());
    }

    @Test
    void injectedSingleChannelIdentifierIgnoresAnotherTimeslot()
    {
        SingleChannelState state = new SingleChannelState(channel(), new AliasModel());
        List<IdentifierUpdateNotification> updates = new ArrayList<>();
        state.setIdentifierUpdateListener(updates::add);

        state.updateChannelStateIdentifiers(new IdentifierUpdateNotification(APCO25Talkgroup.create(4400),
            IdentifierUpdateNotification.Operation.ADD, 1));

        assertEquals(0, updates.size());
    }

    @Test
    void injectedTrafficMarkerReachesBothMultiChannelTimeslots()
    {
        MultiChannelState state = new MultiChannelState(channel(), new AliasModel(), new int[]{1, 2});
        List<IdentifierUpdateNotification> updates = new ArrayList<>();
        state.setIdentifierUpdateListener(updates::add);

        for(int timeslot: new int[]{1, 2})
        {
            state.updateChannelStateIdentifiers(new IdentifierUpdateNotification(TrafficChannelIdentifier.create(),
                IdentifierUpdateNotification.Operation.ADD, timeslot));
        }

        List<Integer> timeslots = updates.stream()
            .filter(IdentifierUpdateNotification::isAdd)
            .filter(update -> update.getIdentifier().getForm() == Form.TRAFFIC_CHANNEL)
            .map(IdentifierUpdateNotification::getTimeslot)
            .sorted()
            .toList();
        assertEquals(List.of(1, 2), timeslots);
    }

    private static Channel channel()
    {
        Channel channel = new Channel("Control");
        channel.setConfigurationId(CONFIGURATION_ID);
        return channel;
    }

    private static void assertIdentity(List<IdentifierUpdateNotification> updates, int timeslot)
    {
        List<IdentifierUpdateNotification> matches = updates.stream()
            .filter(IdentifierUpdateNotification::isAdd)
            .filter(update -> update.getTimeslot() == timeslot)
            .filter(update -> update.getIdentifier().getForm() == Form.UNIQUE_ID)
            .toList();

        assertEquals(1, matches.size());
        assertEquals(CONFIGURATION_ID, matches.get(0).getIdentifier().getValue());
    }
}
