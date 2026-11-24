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

package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.health.CallOutcome;
import io.github.dsheirer.health.CallTally;
import io.github.dsheirer.health.HealthRegistry;
import io.github.dsheirer.health.UnresolvedGrants;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import org.junit.jupiter.api.Test;

/**
 * What a P25 control channel's calls are counted as, for node health reports: each call once, by what finally became
 * of it.
 */
class P25TrafficChannelManagerCallOutcomeTest
{
    /** Service options with the encrypted flag. */
    private static final VoiceServiceOptions ENCRYPTED = new VoiceServiceOptions(0x40);

    /**
     * Stands in for the channel processing manager: starts each traffic channel asked for, or, with no tuner, rejects
     * the request while it's made, as ChannelProcessingManager does when no tuner covers the frequency.
     */
    private static class Starter
    {
        private P25TrafficChannelManager mManager;
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

    private record Site(P25TrafficChannelManager manager, Starter starter, CallTally calls)
    {
        void grant(int channelNumber, int talkgroup, long timestamp)
        {
            grant(channelNumber, talkgroup, null, timestamp);
        }

        void grant(int channelNumber, int talkgroup, VoiceServiceOptions options, long timestamp)
        {
            APCO25Channel channel = APCO25Channel.create(0, channelNumber);
            channel.setFrequencyBand(band());
            manager.processP1ControlDirectedChannelGrant(channel, options, identifiers(talkgroup),
                Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, timestamp);
        }

        /**
         * A grant on band 5, which the site never described.
         */
        void unresolvedGrant(int talkgroup, long timestamp)
        {
            manager.processP1ControlDirectedChannelGrant(APCO25Channel.create(5, 1), null, identifiers(talkgroup),
                Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, timestamp);
        }
    }

    private static Site site(String name, int trafficChannels, boolean ignoreEncrypted)
    {
        Channel parent = new Channel(name);
        DecodeConfigP25Phase1 configuration = new DecodeConfigP25Phase1();
        configuration.setTrafficChannelPoolSize(trafficChannels);
        configuration.setIgnoreEncryptedCalls(ignoreEncrypted);
        parent.setDecodeConfiguration(configuration);
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        manager.processFrequencyBand(band());
        manager.processFrequencyBand(band()); //Heard twice: confirmed
        Starter starter = new Starter();
        starter.mManager = manager;
        EventBus eventBus = new EventBus();
        eventBus.register(starter);
        manager.setInterModuleEventBus(eventBus);
        return new Site(manager, starter, HealthRegistry.getInstance().calls(parent));
    }

    private static P25FrequencyBand band()
    {
        return new P25FrequencyBand(0, 851_006_250L, -45_000_000L, 6_250L, 12_500, 1);
    }

    private static MutableIdentifierCollection identifiers(int talkgroup)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Nac.create(0x491));
        identifiers.update(APCO25Talkgroup.create(talkgroup));
        return identifiers;
    }

    @Test
    void a_followed_call_is_counted_once_however_many_grants_it_has()
    {
        Site site = site("followed", 2, false);
        site.grant(1, 100, 1_000);
        site.grant(1, 100, 1_500);
        site.grant(1, 100, 2_000);

        assertEquals(1, site.starter().mRequests);
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.RECORDED));
    }

    @Test
    void a_call_no_tuner_can_take_is_counted_as_no_source_not_recorded()
    {
        Site site = site("no tuner", 2, false);
        site.starter().mNoTuner = true;
        site.grant(1, 100, 1_000);

        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.NO_SOURCE), "rejected while it was requested");
        assertEquals(0, site.calls().get(CallOutcome.RECORDED));

        //Its next grant tries again and is refused again: still one call, still noSource.
        site.grant(1, 100, 1_500);
        assertEquals(2, site.starter().mRequests);
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.NO_SOURCE));

        //A tuner turns up: the call is followed after all.
        site.starter().mNoTuner = false;
        site.grant(1, 100, 2_000);
        assertEquals(1, site.calls().getStarted());
        assertEquals(0, site.calls().get(CallOutcome.NO_SOURCE));
        assertEquals(1, site.calls().get(CallOutcome.RECORDED));
    }

    @Test
    void a_call_with_every_traffic_channel_busy_is_no_recorder()
    {
        Site site = site("busy", 1, false);
        site.grant(1, 100, 1_000);
        site.grant(3, 200, 1_100);

        assertEquals(2, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.RECORDED));
        assertEquals(1, site.calls().get(CallOutcome.NO_RECORDER));
    }

    @Test
    void an_encrypted_call_the_user_chose_not_to_follow_is_encrypted()
    {
        Site site = site("encrypted", 2, true);
        site.grant(1, 100, ENCRYPTED, 1_000);
        site.grant(1, 100, ENCRYPTED, 1_500);

        assertEquals(0, site.starter().mRequests);
        assertEquals(1, site.calls().getStarted());
        assertEquals(1, site.calls().get(CallOutcome.ENCRYPTED));
    }

    @Test
    void a_call_on_a_channel_never_resolved_is_no_frequency_once_it_ends()
    {
        Site site = site("unresolved", 3, false);
        site.grant(1, 100, 0); //The site's first grant: the grace starts

        //Within the grace the band plan may still be on its way: not counted.
        site.unresolvedGrant(300, 2_000);
        site.unresolvedGrant(300, 4_000);

        //After it: one call, however many grants, counted when they stop.
        long t = UnresolvedGrants.GRACE_MS + 1_000;
        site.unresolvedGrant(400, t);
        site.unresolvedGrant(400, t + 1_000);
        site.unresolvedGrant(400, t + 2_000);
        assertEquals(1, site.calls().getStarted(), "not until it ends");

        //The next grant, a new call: the unresolved one has ended.
        site.grant(3, 500, t + 2_000 + UnresolvedGrants.QUIET_MS + 1);
        assertEquals(3, site.calls().getStarted());
        assertEquals(2, site.calls().get(CallOutcome.RECORDED));
        assertEquals(1, site.calls().get(CallOutcome.NO_FREQUENCY), "the one after the grace, counted once");
    }
}
