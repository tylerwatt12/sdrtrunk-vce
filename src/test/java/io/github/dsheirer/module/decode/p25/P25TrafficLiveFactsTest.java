/*
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.dsheirer.module.decode.p25;

import com.google.common.eventbus.EventBus;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityRow;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P25TrafficLiveFactsTest
{
    @Test
    void phaseOneLateTrafficRadioUpdatesLiveAlongsideDecodeEvents() throws Exception
    {
        verifiesLiveParticipants(false, 1);
    }

    @Test
    void phaseTwoFirstSlotLateTrafficRadioUpdatesLiveAlongsideDecodeEvents() throws Exception
    {
        verifiesLiveParticipants(true, 1);
    }

    @Test
    void phaseTwoSecondSlotLateTrafficRadioUpdatesLiveAlongsideDecodeEvents() throws Exception
    {
        verifiesLiveParticipants(true, 2);
    }

    private void verifiesLiveParticipants(boolean phaseTwo, int timeslot) throws Exception
    {
        Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        parent.setSystem("Test system");
        parent.setSite("Test site");
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(852_000_000L);
        parent.setSourceConfiguration(source);
        if(phaseTwo)
        {
            DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
            config.setTrafficChannelPoolSize(0);
            parent.setDecodeConfiguration(config);
        }
        else
        {
            DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
            config.setTrafficChannelPoolSize(0);
            parent.setDecodeConfiguration(config);
        }

        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        manager.setInterModuleEventBus(new EventBus());
        P25FrequencyBand band = new P25FrequencyBand(0, 851_000_000L, -45_000_000L,
            phaseTwo ? 12_500L : 6_250L, 12_500, phaseTwo ? 2 : 1);
        manager.processFrequencyBand(band);
        manager.processFrequencyBand(band);
        APCO25Channel carrier = APCO25Channel.create(0, phaseTwo ? timeslot - 1 : 1);
        carrier.setFrequencyBand(band);
        List<IDecodeEvent> events = new ArrayList<>();
        manager.addDecodeEventListener(events::add);

        try(ChannelActivityModel live = new ChannelActivityModel(new AliasModel(), new NowPlayingPreference(t -> {})))
        {
            manager.setChannelActivityModel(live);
            live.channelStarted(parent, List.of());
            grant(manager, carrier, identifiers(null), phaseTwo, 1_000L);
            awaitIdle(live);
            ChannelActivityRow row = trafficRow(live);
            assertNull(row.getSource());

            if(phaseTwo)
            {
                manager.processP2TrafficVoice(carrier.getDownlinkFrequency(), timeslot, 1_050L);
                grant(manager, carrier, identifiers(null), true, 1_075L);
                awaitIdle(live);
            }

            MutableIdentifierCollection currentUser = identifiers(101);
            currentUser.update(P25TalkerAliasIdentifier.create("Portable 101"));
            if(phaseTwo)
            {
                manager.processP2TrafficCurrentUser(carrier.getDownlinkFrequency(), timeslot, carrier,
                    VoiceServiceOptions.createUnencrypted(), MacOpcode.TDMA_01_GROUP_VOICE_CHANNEL_USER_ABBREVIATED,
                    currentUser, 1_100L, null);
            }
            else
            {
                manager.processP1TrafficCurrentUser(carrier.getDownlinkFrequency(), carrier, DecodeEventType.CALL_GROUP,
                    VoiceServiceOptions.createUnencrypted(), currentUser, 1_100L, null);
            }

            awaitIdle(live);
            assertEquals(101, events.getLast().getIdentifierCollection().getFromIdentifier().getValue());
            assertEquals(101, row.getSource().getValue(), "Live must receive ordinary late traffic identifiers");
            assertEquals("Portable 101", row.getTalkerAlias().getValue());
            assertEquals(phaseTwo ? timeslot : null, row.getTimeslot());
            if(phaseTwo)
            {
                assertEquals(1_050L, events.getLast().getTimeEnd(),
                    "A newer participant observation must not extend the audio duration");
            }

            //A different talker on the same group is a new call, and cannot retain the previous OTA name.
            grant(manager, carrier, identifiers(202), phaseTwo, 1_200L);
            awaitIdle(live);
            assertEquals(202, row.getSource().getValue());
            assertNull(row.getTalkerAlias());
        }
    }

    private static MutableIdentifierCollection identifiers(Integer radio)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection(List.of(APCO25Talkgroup.create(91)));
        if(radio != null)
        {
            identifiers.update(APCO25RadioIdentifier.createFrom(radio));
        }
        return identifiers;
    }

    private static void grant(P25TrafficChannelManager manager, APCO25Channel carrier,
                              MutableIdentifierCollection identifiers, boolean phaseTwo, long timestamp)
    {
        if(phaseTwo)
        {
            manager.processP2ChannelGrant(carrier, VoiceServiceOptions.createUnencrypted(), identifiers,
                MacOpcode.PHASE1_40_GROUP_VOICE_CHANNEL_GRANT_IMPLICIT, timestamp);
        }
        else
        {
            manager.processP1ControlDirectedChannelGrant(carrier, VoiceServiceOptions.createUnencrypted(), identifiers,
                Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, timestamp);
        }
    }

    private static ChannelActivityRow trafficRow(ChannelActivityModel live)
    {
        return live.getTables().stream().flatMap(table -> table.getRows().stream())
            .filter(row -> row.getRole() == ChannelActivityRow.Role.TRAFFIC).findFirst().orElseThrow();
    }

    private static void awaitIdle(ChannelActivityModel live) throws Exception
    {
        Method idle = ChannelActivityModel.class.getDeclaredMethod("awaitIdle", long.class, TimeUnit.class);
        idle.setAccessible(true);
        assertTrue((boolean)idle.invoke(live, 5, TimeUnit.SECONDS), "Live observation worker did not become idle");
    }
}
