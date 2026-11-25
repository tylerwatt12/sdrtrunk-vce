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

package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.health.CallOutcome;
import io.github.dsheirer.health.CallTally;
import io.github.dsheirer.health.HealthRegistry;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannel;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNEncryptionKey;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallTimer;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallType;
import io.github.dsheirer.module.decode.nxdn.layer3.type.VoiceCallOption;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What an NXDN control channel's calls are counted as, for node health reports: each call once, by what finally
 * became of it.
 */
class NXDNTrafficChannelManagerCallOutcomeTest
{
    private static final EncryptionKeyIdentifier CLEAR = EncryptionKeyIdentifier.create(Protocol.NXDN,
        NXDNEncryptionKey.create(0, 0));
    private static final EncryptionKeyIdentifier ENCRYPTED = EncryptionKeyIdentifier.create(Protocol.NXDN,
        NXDNEncryptionKey.create(0x03, 0x2A));

    /**
     * Stands in for the channel processing manager: starts each traffic channel asked for, or, with no tuner, rejects
     * the request while it's made, as ChannelProcessingManager does when no tuner covers the frequency.
     */
    private static class Starter
    {
        private NXDNTrafficChannelManager mManager;
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

    private record Site(NXDNTrafficChannelManager manager, Starter starter, CallTally calls)
    {
        void call(NXDNChannel channel, int talkgroup, EncryptionKeyIdentifier encryption, long timestamp)
        {
            manager.processVoiceCall(List.of(NXDNRadioIdentifier.createFrom(1000 + talkgroup),
                    NXDNTalkgroupIdentifier.createTo(talkgroup), encryption), channel, CallType.GROUP_BROADCAST,
                encryption, timestamp, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
        }
    }

    private static Site site(String name, int trafficChannels, boolean ignoreEncrypted)
    {
        Channel parent = new Channel(name, Channel.ChannelType.STANDARD);
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setTrafficChannelPoolSize(trafficChannels);
        config.setIgnoreEncryptedCalls(ignoreEncrypted);
        parent.setDecodeConfiguration(config);
        HealthRegistry.getInstance().channel(parent).setCollecting(true, 0);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        Starter starter = new Starter();
        starter.mManager = manager;
        EventBus eventBus = new EventBus();
        eventBus.register(starter);
        manager.setInterModuleEventBus(eventBus);
        return new Site(manager, starter, HealthRegistry.getInstance().calls(parent));
    }

    /**
     * A channel the channel map gives a frequency for.
     */
    private static NXDNChannel channel(int number, long frequency)
    {
        NXDNChannelLookup channel = new NXDNChannelLookup(number);
        channel.receive(null, Map.of(number, new ChannelFrequency(number, frequency, 0)));
        return channel;
    }

    @Test
    void a_followed_call_is_counted_once_however_many_assignments_it_has()
    {
        Site site = site("NXDN followed", 2, false);
        NXDNChannel channel = channel(12, 452_012_500L);
        site.call(channel, 91, CLEAR, 1_000);
        site.call(channel, 91, CLEAR, 1_500);
        site.call(channel, 91, CLEAR, 2_000);

        assertEquals(1, site.starter().mRequests);
        site.manager().reset();
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.RECORDED));
    }

    @Test
    void retry_across_a_report_boundary_settles_once_on_received_end()
    {
        Site site = site("NXDN no tuner", 2, false);
        site.starter().mNoTuner = true;
        NXDNChannel channel = channel(12, 452_012_500L);
        site.call(channel, 91, CLEAR, 299_500);
        assertEquals(0, site.calls().totals(300_000).started());
        site.starter().mNoTuner = false;
        site.call(channel, 91, CLEAR, 300_500);
        assertEquals(2, site.starter().mRequests);
        site.manager().processEndCall(channel, 301_000);
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.RECORDED));
        assertEquals(0, site.calls().get(CallOutcome.NO_SOURCE));
        site.manager().reset();
        assertEquals(1, site.calls().getStarted());
    }

    @Test
    void a_call_with_every_traffic_channel_busy_is_no_recorder()
    {
        Site site = site("NXDN busy", 1, false);
        site.call(channel(12, 452_012_500L), 91, CLEAR, 1_000);
        site.call(channel(13, 452_025_000L), 92, CLEAR, 1_100);

        site.manager().reset();
        assertEquals(2, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.RECORDED));
        assertEquals(1, site.calls().get(CallOutcome.NO_RECORDER));
    }

    @Test
    void a_call_on_a_channel_the_channel_map_lacks_is_no_frequency()
    {
        Site site = site("NXDN unmapped", 2, false);
        NXDNChannel unmapped = new NXDNChannelLookup(40);
        site.call(unmapped, 93, CLEAR, 1_000);
        site.call(unmapped, 93, CLEAR, 1_500);

        assertEquals(0, site.starter().mRequests);
        site.manager().reset();
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.NO_FREQUENCY));
    }

    @Test
    void an_encrypted_call_the_user_chose_not_to_follow_is_encrypted()
    {
        Site site = site("NXDN encrypted", 2, true);
        site.call(channel(12, 452_012_500L), 94, ENCRYPTED, 1_000);

        assertEquals(0, site.starter().mRequests);
        site.manager().reset();
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.ENCRYPTED));
    }
    @Test
    void traffic_progress_without_assignments_keeps_the_call_active_until_end()
    {
        Site site = site("NXDN long traffic", 2, false);
        NXDNChannel channel = channel(12, 452_012_500L);
        site.call(channel, 91, CLEAR, 1_000);
        for(long timestamp = 2_000; timestamp <= 302_000; timestamp += 1_000)
        {
            site.manager().processCallProgressUpdate(channel, timestamp);
            if(timestamp % 10_000 == 0) assertEquals(0, site.calls().totals(timestamp).started());
        }
        site.manager().processEndCall(channel, 302_500);
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.RECORDED));
        site.manager().stop();
        assertEquals(1, site.calls().getStarted());
    }
    @Test
    void mapping_a_matching_logical_call_cancels_its_tentative_no_frequency_result()
    {
        Site site = site("NXDN mapping arrives", 2, false);
        NXDNChannelLookup channel = new NXDNChannelLookup(40);
        site.call(channel, 93, CLEAR, 299_500);
        assertEquals(0, site.calls().totals(300_000).started());
        channel.receive(null, Map.of(40, new ChannelFrequency(40, 452_012_500L, 0)));
        site.call(channel, 93, CLEAR, 300_500);
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
    void unrelated_unmapped_logical_calls_do_not_share_frequency_zero_accounting()
    {
        Site site = site("NXDN two unmapped", 2, false);
        site.call(new NXDNChannelLookup(40), 93, CLEAR, 1_000);
        site.call(new NXDNChannelLookup(41), 94, CLEAR, 1_500);
        CallTally.Totals after = site.calls().totals(4_501);
        assertEquals(2, after.started());
        assertEquals(2, after.get(CallOutcome.NO_FREQUENCY));
        assertEquals(0, after.losses());
        site.manager().reset();
        assertEquals(after, site.calls().totals());
    }

    @Test
    void a_new_destination_on_a_resolved_channel_does_not_cancel_the_previous_unmapped_call()
    {
        Site site = site("NXDN different call after mapping", 2, false);
        NXDNChannelLookup channel = new NXDNChannelLookup(40);
        site.call(channel, 93, CLEAR, 1_000);
        channel.receive(null, Map.of(40, new ChannelFrequency(40, 452_012_500L, 0)));
        site.call(channel, 94, CLEAR, 1_500);
        CallTally.Totals after = site.calls().totals(4_501);
        assertEquals(2, after.started());
        assertEquals(1, after.get(CallOutcome.NO_FREQUENCY));
        assertEquals(1, after.get(CallOutcome.RECORDED));
        assertEquals(0, after.losses());
    }
    @Test
    void same_destination_on_two_unmapped_channels_keeps_independent_logical_health_keys()
    {
        Site site = site("NXDN same destination two channels", 2, false);
        site.call(new NXDNChannelLookup(40), 93, CLEAR, 1_000);
        site.call(new NXDNChannelLookup(41), 93, CLEAR, 1_500);
        CallTally.Totals after = site.calls().totals(4_501);
        assertEquals(2, after.started());
        assertEquals(2, after.get(CallOutcome.NO_FREQUENCY));
        assertEquals(0, after.losses());
    }
}
