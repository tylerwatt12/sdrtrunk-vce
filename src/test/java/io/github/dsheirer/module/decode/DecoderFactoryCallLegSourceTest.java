/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import org.junit.jupiter.api.Test;

class DecoderFactoryCallLegSourceTest
{
    @Test
    void capturesDurableConfigurationAliasAndLearnedSiteIdentity()
    {
        Channel channel = new Channel("MARCS Site");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        channel.setConfigurationId("11111111-1111-1111-1111-111111111111");
        channel.setRadresGuid("22222222-2222-2222-2222-222222222222");
        P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x348, 2, 19);
        channel.setP25SiteIdentity(identity);
        AliasListDefinition definition = new AliasListDefinition("MARCS", AliasListFamily.P25);
        definition.setId(42);

        CallLegSource source = DecoderFactory.createCallLegSource(channel, new AliasList(definition));

        assertEquals(DecoderType.P25_PHASE2, source.decoderType());
        assertEquals("11111111-1111-1111-1111-111111111111", source.channelConfigurationId());
        assertEquals("MARCS Site", source.channelName());
        assertEquals("22222222-2222-2222-2222-222222222222", source.siteGuid());
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
}
