/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.health.CallOutcome;
import io.github.dsheirer.health.CallTally;
import io.github.dsheirer.health.HealthRegistry;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.dmr.channel.DMRTier3Channel;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import org.junit.jupiter.api.Test;

/**
 * What a DMR control channel's calls are counted as, for node health reports: each call once, by what finally became
 * of it.
 */
class DMRTrafficChannelManagerCallOutcomeTest
{
    private static final long FREQUENCY_1 = 451_012_500L;
    private static final long FREQUENCY_2 = 451_037_500L;

    /**
     * Stands in for the channel processing manager: starts each traffic channel asked for, or, with no tuner, rejects
     * the request while it's made, as ChannelProcessingManager does when no tuner covers the frequency.
     */
    private static class Starter
    {
        private DMRTrafficChannelManager mManager;
        private boolean mNoTuner;
        private int mRequests;

        @Subscribe
        public void start(ChannelStartProcessingRequest request)
        {
            mRequests++;

            if(mNoTuner)
            {
                mManager.getChannelEventListener().receive(new ChannelEvent(request.getChannel(),
                    ChannelEvent.Event.NOTIFICATION_PROCESSING_START_REJECTED, "TUNER UNAVAILABLE"));
            }
        }
    }

    private record Site(DMRTrafficChannelManager manager, Starter starter, CallTally calls)
    {
        void grant(int number, int timeslot, long frequency, int talkgroup, long timestamp)
        {
            grant(number, timeslot, frequency, talkgroup, false, timestamp);
        }

        void grant(int number, int timeslot, long frequency, int talkgroup, boolean encrypted, long timestamp)
        {
            DMRTier3Channel channel = new DMRTier3Channel(number, timeslot);
            TimeslotFrequency mapping = new TimeslotFrequency();
            mapping.setNumber(number);
            mapping.setDownlinkFrequency(frequency);
            channel.setTimeslotFrequency(mapping);
            MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
            identifiers.update(DMRRadio.createFrom(1000 + talkgroup));
            identifiers.update(DMRTalkgroup.create(talkgroup));
            manager.processChannelGrant(channel, identifiers, Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT,
                timestamp, encrypted);
        }
    }

    private static Site site(String name, int trafficChannels, boolean ignoreEncrypted)
    {
        Channel parent = new Channel(name, Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(trafficChannels);
        config.setIgnoreEncryptedCalls(ignoreEncrypted);
        parent.setDecodeConfiguration(config);
        HealthRegistry.getInstance().channel(parent).setCollecting(true, 0);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        Starter starter = new Starter();
        starter.mManager = manager;
        EventBus eventBus = new EventBus();
        eventBus.register(starter);
        manager.setInterModuleEventBus(eventBus);
        return new Site(manager, starter, HealthRegistry.getInstance().calls(parent));
    }

    @Test
    void a_followed_call_is_counted_once_however_many_grants_it_has()
    {
        Site site = site("DMR followed", 2, false);
        site.grant(1, 1, FREQUENCY_1, 91, 1_000);
        site.grant(1, 1, FREQUENCY_1, 91, 1_500);
        site.grant(1, 1, FREQUENCY_1, 91, 2_000);

        assertEquals(1, site.starter().mRequests);
        assertEquals(0, site.calls().getStarted(), "the active call is not settled yet");

        //A call on the other timeslot of the running traffic channel: followed by it.
        site.grant(1, 2, FREQUENCY_1, 92, 2_100);
        assertEquals(1, site.starter().mRequests);
        site.manager().reset();
        assertEquals(2, site.calls().getStarted());
        assertEquals(2, site.calls().get(CallOutcome.RECORDED));
    }

    @Test
    void a_call_no_tuner_can_take_is_counted_as_no_source_not_recorded()
    {
        Site site = site("DMR no tuner", 2, false);
        site.starter().mNoTuner = true;
        site.grant(1, 1, FREQUENCY_1, 91, 1_000);

        assertEquals(1, site.starter().mRequests);
        site.manager().reset();
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.NO_SOURCE), "rejected while it was requested");
        assertEquals(0, site.calls().get(CallOutcome.RECORDED));
    }

    @Test
    void a_call_with_every_traffic_channel_busy_is_no_recorder()
    {
        Site site = site("DMR busy", 1, false);
        site.grant(1, 1, FREQUENCY_1, 91, 1_000);
        site.grant(2, 1, FREQUENCY_2, 92, 1_100);

        site.manager().reset();
        assertEquals(2, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.RECORDED));
        assertEquals(1, site.calls().get(CallOutcome.NO_RECORDER));
    }

    @Test
    void a_call_on_a_channel_without_a_frequency_is_no_frequency()
    {
        Site site = site("DMR unmapped", 2, false);
        site.grant(7, 1, 0, 93, 1_000);
        site.grant(7, 1, 0, 93, 1_500);

        assertEquals(0, site.starter().mRequests);
        site.manager().reset();
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.NO_FREQUENCY));
    }

    @Test
    void an_encrypted_call_the_user_chose_not_to_follow_is_encrypted()
    {
        Site site = site("DMR encrypted", 2, true);
        site.grant(1, 1, FREQUENCY_1, 94, true, 1_000);

        assertEquals(0, site.starter().mRequests);
        site.manager().reset();
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.ENCRYPTED));
    }
    @Test
    void retry_across_a_report_boundary_counts_the_final_outcome_only()
    {
        Site site = site("DMR retry", 2, false);
        site.starter().mNoTuner = true;
        site.grant(1, 1, FREQUENCY_1, 91, 299_500);
        assertEquals(0, site.calls().totals(300_000).started());
        site.starter().mNoTuner = false;
        site.grant(1, 1, FREQUENCY_1, 91, 300_500);
        CallTally.Totals after = site.calls().totals(303_501);
        assertEquals(1, after.started());
        assertEquals(1, after.get(CallOutcome.RECORDED));
        assertEquals(0, after.get(CallOutcome.NO_SOURCE));
        site.manager().stop();
        assertEquals(after, site.calls().totals());
    }

    @Test
    void managed_traffic_observations_keep_a_long_call_active_without_more_grants()
    {
        Site site = site("DMR long traffic", 2, false);
        site.grant(1, 1, FREQUENCY_1, 91, 1_000);
        DMRTier3Channel channel = new DMRTier3Channel(1, 1);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(1);
        mapping.setDownlinkFrequency(FREQUENCY_1);
        channel.setTimeslotFrequency(mapping);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(DMRRadio.createFrom(1091));
        identifiers.update(DMRTalkgroup.create(91));
        var traffic = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000).channel(channel)
            .timeslot(1).identifiers(identifiers).build();
        for(long timestamp = 2_000; timestamp <= 302_000; timestamp += 1_000)
        {
            site.manager().receiveTrafficChannelEvent(traffic, timestamp);
            if(timestamp % 10_000 == 0) assertEquals(0, site.calls().totals(timestamp).started());
        }
        site.manager().stop();
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.RECORDED));
    }
    @Test
    void logical_health_key_survives_frequency_mapping_mid_call()
    {
        Site site = site("DMR mapping arrives", 2, false);
        site.grant(7, 1, 0, 93, 299_500);
        assertEquals(0, site.calls().totals(300_000).started());
        site.grant(7, 1, FREQUENCY_1, 93, 300_500);
        assertEquals(1, site.starter().mRequests);
        CallTally.Totals after = site.calls().totals(303_501);
        assertEquals(1, after.started());
        assertEquals(1, after.get(CallOutcome.RECORDED));
        assertEquals(0, after.get(CallOutcome.NO_FREQUENCY));
        assertEquals(0, after.losses());
        site.manager().stop();
        assertEquals(after, site.calls().totals());
    }

    @Test
    void distinct_unmapped_logical_channels_have_independent_health_calls()
    {
        Site site = site("DMR two unmapped", 2, false);
        site.grant(7, 1, 0, 93, 1_000);
        site.grant(8, 1, 0, 94, 1_500);
        CallTally.Totals after = site.calls().totals(4_501);
        assertEquals(2, after.started());
        assertEquals(2, after.get(CallOutcome.NO_FREQUENCY));
        assertEquals(0, after.losses());
    }
}
