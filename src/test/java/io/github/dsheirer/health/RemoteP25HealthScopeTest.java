/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.health;

import com.google.gson.JsonObject;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteP25HealthScopeTest
{
    private static final long START = 1_794_590_000_000L;
    private static final long ALIAS_LIST = 1_234;
    private static final String LOCAL_ID = "123e4567-e89b-12d3-a456-426614179001";
    private static final String REMOTE_ID = "123e4567-e89b-12d3-a456-426614179002";
    private static final Predicate<ChannelHealth.ChannelInfo> FEEDS = info -> info.aliasListId() == ALIAS_LIST;

    @Test
    void remote_add_restart_and_identity_changes_preserve_the_entire_local_window()
    {
        for(boolean remotePhase2: List.of(false, true))
        {
            try(Fixture fixture = new Fixture(remotePhase2))
            {
                Channel local = fixture.channel(false);
                ChannelHealth localHealth = fixture.registry.existingChannel(local);
                assertNotNull(localHealth);
                localHealth.setCollecting(true, START);
                localHealth.started(START);
                HealthSnapshot before = fixture.snapshot(START + 1_000);
                long epoch = localHealth.collectionEpoch();

                Channel remote = fixture.channel(true);
                localHealth.getCalls().count(CallOutcome.RECORDED);
                fixture.assertUnchangedLocalReport(before, START + 10_000, localHealth, 1);
                assertNull(fixture.registry.existingChannel(remote), "A mirror must not start a collection epoch");

                fixture.manager(remote).processNetworkConfigurationIdentity(new P25SiteIdentity(1, 2, 3, 4));
                fixture.manager(remote).processNetworkConfigurationIdentity(new P25SiteIdentity(5, 6, 7, 8));
                localHealth.getCalls().count(CallOutcome.NO_SOURCE);
                fixture.assertUnchangedLocalReport(before, START + 20_000, localHealth, 2);

                fixture.restart(remote).processNetworkConfigurationIdentity(new P25SiteIdentity(9, 10, 11, 12));
                localHealth.getCalls().count(CallOutcome.NO_RECORDER);
                fixture.assertUnchangedLocalReport(before, START + 30_000, localHealth, 3);
                assertNull(fixture.registry.existingChannel(remote));

                Channel replacement = fixture.channel(true); //An edit preserves the saved UUID with a new runtime ID.
                fixture.manager(replacement).processNetworkConfigurationIdentity(new P25SiteIdentity(13, 14, 15, 16));
                localHealth.getCalls().count(CallOutcome.IGNORED);
                fixture.assertUnchangedLocalReport(before, START + 40_000, localHealth, 4);
                assertNull(fixture.registry.existingChannel(replacement));
                assertEquals(epoch, localHealth.collectionEpoch(), "Remote lifecycle cannot restart local accounting");
                assertEquals(START, localHealth.collectionSinceMs(), "Remote lifecycle cannot advance the local baseline");
            }
        }
    }

    @Test
    void remote_only_scope_never_registers_measurements_or_emits_a_report()
    {
        for(boolean remotePhase2: List.of(false, true))
        {
            try(Fixture fixture = new Fixture(remotePhase2))
            {
                Channel remote = fixture.channel(true);
                HealthSnapshot before = fixture.snapshot(START + 1_000);
                fixture.manager(remote).processNetworkConfigurationIdentity(new P25SiteIdentity(1, 2, 3, 4));
                HealthSnapshot after = fixture.snapshot(START + 10_000);
                assertTrue(before.channels().isEmpty());
                assertTrue(after.channels().isEmpty());
                assertNull(NodeReport.build(new NodeReporter.Window(before, after, false, 0),
                    new JsonObject(), "test", FEEDS));
            }
        }
    }

    @Test
    void excluding_remote_mirrors_does_not_hide_a_deaf_local_site()
    {
        try(Fixture fixture = new Fixture(false))
        {
            Channel local = fixture.channel(false);
            ChannelHealth health = fixture.registry.existingChannel(local);
            assertNotNull(health);
            health.setCollecting(true, START);
            health.started(START);
            HealthSnapshot before = fixture.snapshot(START + 1_000);
            fixture.channel(true);
            JsonObject report = fixture.assertUnchangedLocalReport(before, START + 10_000, health, 0);
            assertEquals(1, report.getAsJsonArray("sites").size());
            JsonObject cc = report.getAsJsonArray("sites").get(0).getAsJsonObject().getAsJsonObject("cc");
            assertEquals(0, cc.get("good").getAsInt());
            assertFalse(cc.has("hz"), "A deaf local receiver stays visible without inventing a current lock");
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        private final HealthRegistry registry = HealthRegistry.getInstance();
        private final boolean remotePhase2;
        private final List<Channel> channels = new ArrayList<>();
        private final List<P25TrafficChannelManager> managers = new ArrayList<>();

        private Fixture(boolean remotePhase2)
        {
            this.remotePhase2 = remotePhase2;
        }

        private Channel channel(boolean remote)
        {
            Channel channel = new Channel(remote ? "Mirrored site" : "Local site");
            channel.setConfigurationId(remote ? REMOTE_ID : LOCAL_ID);
            channel.setAliasListId(ALIAS_LIST);
            channel.setSystem("Test system");
            channel.setSite(remote ? "Mirrored site" : "Local site");
            //The local receiver is a trunked control channel; only the mirrored stream's phase varies.
            if(remote && remotePhase2)
            {
                DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
                config.setTrafficChannelPoolSize(1);
                channel.setDecodeConfiguration(config);
            }
            else
            {
                DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
                config.setTrafficChannelPoolSize(1);
                channel.setDecodeConfiguration(config);
            }
            if(remote)
            {
                channel.setSourceConfiguration(new SourceConfigRemote());
            }
            else
            {
                SourceConfigTuner source = new SourceConfigTuner();
                source.setFrequency(851_000_000);
                channel.setSourceConfiguration(source);
            }
            channels.add(channel);
            managers.add(new P25TrafficChannelManager(channel));
            return channel;
        }

        private P25TrafficChannelManager manager(Channel channel)
        {
            return managers.get(channels.indexOf(channel));
        }

        private P25TrafficChannelManager restart(Channel channel)
        {
            int index = channels.indexOf(channel);
            managers.get(index).stop();
            P25TrafficChannelManager restarted = new P25TrafficChannelManager(channel);
            managers.set(index, restarted);
            return restarted;
        }

        /** Capture only registrations owned by this fixture, with the provider selecting the shared alias list. */
        private HealthSnapshot snapshot(long time)
        {
            List<ChannelHealth.Totals> totals = new ArrayList<>();
            for(Channel channel: channels)
            {
                ChannelHealth health = registry.existingChannel(channel);
                if(health != null)
                {
                    //Equivalent provider demand on a newly registered source, without starting a sampler thread.
                    if(!health.isCollecting()) health.setCollecting(true, time);
                    totals.add(health.totals(time));
                }
            }
            return new HealthSnapshot(time, START, totals, List.of());
        }

        private JsonObject assertUnchangedLocalReport(HealthSnapshot before, long time, ChannelHealth local,
                                                      long startedCalls)
        {
            HealthSnapshot actual = snapshot(time);
            HealthSnapshot localOnly = new HealthSnapshot(time, START, List.of(local.totals(time)), List.of());
            NodeReporter.Window actualWindow = new NodeReporter.Window(before, actual, false, 0);
            NodeReporter.Window expectedWindow = new NodeReporter.Window(before, localOnly, false, 0);
            assertFalse(NodeReport.incomplete(actualWindow, FEEDS), "A remote change cannot invalidate local coverage");
            JsonObject expected = NodeReport.build(expectedWindow, new JsonObject(), "test", FEEDS);
            JsonObject report = NodeReport.build(actualWindow, new JsonObject(), "test", FEEDS);
            assertNotNull(report);
            assertEquals(expected, report, "Remote lifecycle must not change any outbound local field or value");
            assertEquals(startedCalls, report.getAsJsonArray("sites").get(0).getAsJsonObject()
                .getAsJsonObject("calls").get("started").getAsLong());
            return report;
        }

        @Override
        public void close()
        {
            for(P25TrafficChannelManager manager: managers) manager.stop();
            for(Channel channel: channels) registry.retire(channel);
        }
    }
}
