/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.controller.channel.event.PreloadDataContent;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1NACPreloadDataContent;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class P25TrafficChannelNACPreloadTest
{
    private static final int EXPECTED_NAC = 0x491;

    @Test
    void phase1TrafficStartCarriesRequestScopedExpectedNAC()
    {
        StartRequestSubscriber subscriber = new StartRequestSubscriber();
        P25TrafficChannelManager manager = manager(subscriber);
        MutableIdentifierCollection identifiers = identifiers(EXPECTED_NAC);

        manager.processP1ControlDirectedChannelGrant(channel(), null, identifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000L);

        ChannelStartProcessingRequest request = subscriber.request.get();
        assertNotNull(request);
        P25P1NACPreloadDataContent preload = request.getPreloadDataContents().stream()
            .filter(P25P1NACPreloadDataContent.class::isInstance)
            .map(P25P1NACPreloadDataContent.class::cast)
            .findFirst()
            .orElseThrow();
        assertEquals(EXPECTED_NAC, preload.getNAC());
    }

    @Test
    void phase1TrafficStartSupportsNACZero()
    {
        StartRequestSubscriber subscriber = new StartRequestSubscriber();
        P25TrafficChannelManager manager = manager(subscriber);

        manager.processP1ControlDirectedChannelGrant(channel(), null, identifiers(0),
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000L);

        ChannelStartProcessingRequest request = subscriber.request.get();
        assertNotNull(request);
        P25P1NACPreloadDataContent preload = request.getPreloadDataContents().stream()
            .filter(P25P1NACPreloadDataContent.class::isInstance)
            .map(P25P1NACPreloadDataContent.class::cast)
            .findFirst()
            .orElseThrow();
        assertEquals(0, preload.getNAC());
    }

    @Test
    void phase1TrafficStartFailsClosedWithoutConcreteNAC()
    {
        StartRequestSubscriber missingSubscriber = new StartRequestSubscriber();
        P25TrafficChannelManager missingManager = manager(missingSubscriber);
        MutableIdentifierCollection missing = new MutableIdentifierCollection();
        missing.update(APCO25Talkgroup.create(1_201));

        missingManager.processP1ControlDirectedChannelGrant(channel(), null, missing,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000L);
        assertNull(missingSubscriber.request.get());

        missingManager.processP1ControlDirectedChannelGrant(channel(), null, identifiers(EXPECTED_NAC),
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_001L);
        assertNotNull(missingSubscriber.request.get(), "rejected start must return its channel to the pool");

        StartRequestSubscriber wildcardSubscriber = new StartRequestSubscriber();
        P25TrafficChannelManager wildcardManager = manager(wildcardSubscriber);

        wildcardManager.processP1ControlDirectedChannelGrant(channel(), null, identifiers(0xF7E),
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000L);
        assertNull(wildcardSubscriber.request.get());
    }

    @Test
    void controlGrantResolutionUsesOnlyTheConfirmedManagerBandPlan()
    {
        P25TrafficChannelManager manager = manager(new StartRequestSubscriber());
        APCO25Channel grant = APCO25Channel.create(0, 1);
        grant.setFrequencyBand(new P25FrequencyBand(0, 450_000_000L, -5_000_000L, 12_500L, 12_500, 1));

        assertTrue(manager.resolveControlChannel(grant));
        assertEquals(851_012_500L, grant.getDownlinkFrequency());

        manager.setCurrentControlFrequency(852_000_000L, new Channel("Rotated Control"));
        assertFalse(manager.resolveControlChannel(grant), "a source change must require fresh band-plan confirmation");
    }

    private static P25TrafficChannelManager manager(StartRequestSubscriber subscriber)
    {
        Channel parent = new Channel("Control");
        DecodeConfigP25Phase1 configuration = new DecodeConfigP25Phase1();
        configuration.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(configuration);
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        P25FrequencyBand frequencyBand = frequencyBand();
        manager.processFrequencyBand(frequencyBand);
        manager.processFrequencyBand(frequencyBand);
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        return manager;
    }

    private static APCO25Channel channel()
    {
        APCO25Channel channel = APCO25Channel.create(0, 1);
        channel.setFrequencyBand(frequencyBand());
        return channel;
    }

    private static P25FrequencyBand frequencyBand()
    {
        return new P25FrequencyBand(0, 851_006_250L, -45_000_000L, 6_250L, 12_500, 1);
    }

    private static MutableIdentifierCollection identifiers(int nac)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Nac.create(nac));
        identifiers.update(APCO25Talkgroup.create(1_201));
        return identifiers;
    }

    private static class StartRequestSubscriber
    {
        private final AtomicReference<ChannelStartProcessingRequest> request = new AtomicReference<>();

        @Subscribe
        public void receive(ChannelStartProcessingRequest startRequest)
        {
            request.set(startRequest);
        }
    }
}
