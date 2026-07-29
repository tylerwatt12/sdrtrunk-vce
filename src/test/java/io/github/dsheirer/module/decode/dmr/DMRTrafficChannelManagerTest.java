/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import com.google.common.eventbus.Subscribe;
import com.google.common.eventbus.EventBus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityRow;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.dmr.channel.DMRTier3Channel;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class DMRTrafficChannelManagerTest
{
    @Test
    void publishesOneCallStartPerTargetWithoutTrafficTuner()
    {
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(0);
        parent.setDecodeConfiguration(config);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        manager.setInterModuleEventBus(new EventBus());
        DMRTier3Channel channel = new DMRTier3Channel(12, 1);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(12);
        mapping.setDownlinkFrequency(451_012_500L);
        channel.setTimeslotFrequency(mapping);
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processChannelGrant(channel, identifiers(101, 91),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
            manager.processChannelGrant(channel, identifiers(102, 91),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_100L, false);
            manager.processChannelGrant(channel, identifiers(102, 92),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_200L, true);
            manager.processChannelGrant(channel, identifiers(102, 92),
                Opcode.STANDARD_TALKGROUP_DATA_CHANNEL_GRANT_SINGLE_ITEM, 1_300L, false);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(2, subscriber.events.size());
        assertEquals(91, subscriber.events.get(0).event().getIdentifierCollection().getToIdentifier().getValue());
        assertEquals(92, subscriber.events.get(1).event().getIdentifierCollection().getToIdentifier().getValue());
        assertEquals(1_000L, subscriber.events.get(0).event().getTimeStart());
        assertEquals(1_200L, subscriber.events.get(1).event().getTimeStart());
    }

    @Test
    void publishesResolvedTierThreeGrantToSystemsModel() throws Exception
    {
        ChannelActivityModel activityModel = new ChannelActivityModel(new AliasModel(),
            new NowPlayingPreference(type -> {}));
        Channel parent = new Channel("2.2", Channel.ChannelType.STANDARD);
        parent.setSystem("bus");
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(0);
        parent.setDecodeConfiguration(config);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(139_518_750L);
        parent.setSourceConfiguration(source);

        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        manager.setInterModuleEventBus(new EventBus());
        manager.setChannelActivityModel(activityModel);
        manager.setCurrentControlFrequency(139_518_750L, parent);

        DMRTier3Channel controlTimeslotCall = new DMRTier3Channel(901, 2);
        TimeslotFrequency controlMapping = new TimeslotFrequency();
        controlMapping.setNumber(901);
        controlMapping.setDownlinkFrequency(139_518_750L);
        controlTimeslotCall.setTimeslotFrequency(controlMapping);

        DMRTier3Channel grant = new DMRTier3Channel(802, 2);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(802);
        mapping.setDownlinkFrequency(139_068_750L);
        grant.setTimeslotFrequency(mapping);

        SwingUtilities.invokeAndWait(() -> activityModel.setEnabled(true));
        manager.processChannelGrant(controlTimeslotCall, new MutableIdentifierCollection(),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 900L, false);
        manager.processChannelGrant(grant, new MutableIdentifierCollection(),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(2, activityModel.getTables().size());
        assertEquals("DMR: bus / 2.2", activityModel.getTables().get(1).getTitle());
        assertTrue(activityModel.getTables().get(1).isControlActive());
        assertTrue(activityModel.getTables().get(1).getRows().stream()
            .anyMatch(row -> row.getRole() == ChannelActivityRow.Role.TRAFFIC &&
                row.getFrequency() == 139_068_750L && row.getTimeslot() == 2 && "802".equals(row.getLcn())));
        assertTrue(activityModel.getTables().get(1).getRows().stream()
            .anyMatch(row -> row.getRole() == ChannelActivityRow.Role.TRAFFIC &&
                row.getFrequency() == 139_518_750L && row.getTimeslot() == 2));
    }

    @Test
    void conventionalModeDoesNotAllocateOrPromoteTrunking() throws Exception
    {
        ChannelActivityModel activityModel = new ChannelActivityModel(new AliasModel(),
            new NowPlayingPreference(type -> {}));
        Channel parent = new Channel("Repeater", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.CONVENTIONAL);
        config.setTrafficChannelPoolSize(20);
        parent.setDecodeConfiguration(config);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(451_012_500L);
        parent.setSourceConfiguration(source);

        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        manager.setInterModuleEventBus(new EventBus());
        manager.setChannelActivityModel(activityModel);
        manager.setCurrentControlFrequency(451_012_500L, parent);

        DMRTier3Channel grant = new DMRTier3Channel(12, 1);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(12);
        mapping.setDownlinkFrequency(452_012_500L);
        grant.setTimeslotFrequency(mapping);

        SwingUtilities.invokeAndWait(() -> activityModel.setEnabled(true));
        manager.processChannelGrant(grant, new MutableIdentifierCollection(),
            Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(1, activityModel.getTables().size());
        assertTrue(activityModel.getConventionalTable().getRows().isEmpty());
    }

    private static MutableIdentifierCollection identifiers(int radio, int talkgroup)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(DMRRadio.createFrom(radio));
        identifiers.update(DMRTalkgroup.create(talkgroup));
        return identifiers;
    }

    private static class CallStartSubscriber
    {
        private final List<TrunkedCallStartEvent> events = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(TrunkedCallStartEvent event)
        {
            events.add(event);
        }
    }
}
