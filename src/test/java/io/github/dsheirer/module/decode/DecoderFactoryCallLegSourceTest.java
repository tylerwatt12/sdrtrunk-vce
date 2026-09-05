/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.audio.AudioModule;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.CallPlaybackTarget;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecoderFactoryCallLegSourceTest
{
    @Test
    void capturesDurableConfigurationAliasAndLearnedSiteIdentity()
    {
        Channel channel = new Channel("MARCS Site");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        channel.setConfigurationId("11111111-1111-1111-1111-111111111111");
        channel.setRadioResolveId("22222222-2222-2222-2222-222222222222");
        P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x348, 2, 19);
        channel.setP25SiteIdentity(identity);
        AliasListDefinition definition = new AliasListDefinition("MARCS", AliasListFamily.P25);
        definition.setId(42);

        CallLegSource source = DecoderFactory.createCallLegSource(channel, new AliasList(definition));

        assertEquals(DecoderType.P25_PHASE2, source.decoderType());
        assertEquals("11111111-1111-1111-1111-111111111111", source.channelConfigurationId());
        assertEquals("MARCS Site", source.channelName());
        assertEquals("22222222-2222-2222-2222-222222222222", source.radioResolveId());
        assertEquals(42, source.aliasListId());
        assertEquals(identity, source.p25SiteIdentity());
        assertFalse(source.trafficChannel());
    }

    @Test
    void marksTrafficChannelScopeExplicitly()
    {
        Channel channel = new Channel("Traffic", Channel.ChannelType.TRAFFIC);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());

        CallLegSource source = DecoderFactory.createCallLegSource(channel, AliasList.empty("test"));

        assertTrue(source.trafficChannel());
    }

    @Test
    void doesNotPublishAnUnsavedCandidateAsDurableSourceIdentity()
    {
        Channel channel = new Channel("Draft");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());

        CallLegSource source = DecoderFactory.createCallLegSource(channel, AliasList.empty("test"));

        assertNull(source.channelConfigurationId());
    }

    @Test
    void analogFactoriesAttachTheSavedChannelToEmittedCalls()
    {
        assertAnalogSource(new DecodeConfigAM(), DecoderType.AM,
            "11111111-1111-1111-1111-111111111111");
        assertAnalogSource(new DecodeConfigNBFM(), DecoderType.NBFM,
            "22222222-2222-2222-2222-222222222222");
    }

    private static void assertAnalogSource(DecodeConfiguration decodeConfiguration, DecoderType expectedDecoder,
                                           String configurationId)
    {
        Channel channel = new Channel(expectedDecoder + " channel");
        channel.setConfigurationId(configurationId);
        channel.setDecodeConfiguration(decodeConfiguration);
        List<Module> modules = DecoderFactory.getPrimaryModules(channel, new AliasModel(), null, null, null,
            48_000.0, null);
        AudioModule audioModule = modules.stream().filter(AudioModule.class::isInstance)
            .map(AudioModule.class::cast).findFirst().orElseThrow();
        List<AudioCallEvent> events = new ArrayList<>();
        audioModule.setAudioCallEventListener(events::add);

        audioModule.getSquelchStateListener().receive(SquelchStateEvent.create(SquelchState.UNSQUELCH));
        audioModule.receive(new float[80]);
        audioModule.getSquelchStateListener().receive(SquelchStateEvent.create(SquelchState.SQUELCH));

        AudioCallEvent completed = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED)
            .findFirst().orElseThrow();
        CallLegSource source = completed.snapshot().callLegSource();
        assertEquals(expectedDecoder, source.decoderType());
        assertEquals(configurationId, source.channelConfigurationId());
        assertEquals(ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL, source.channelKind());
        assertEquals("channel:" + configurationId,
            CallPlaybackTarget.from(completed.snapshot(), null).key());
    }
}
