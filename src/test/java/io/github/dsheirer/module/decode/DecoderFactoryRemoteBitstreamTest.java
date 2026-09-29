/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1BitstreamDecoder;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderC4FM;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderLSM;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.P25P2BitstreamDecoder;
import io.github.dsheirer.module.decode.p25.phase2.P25P2DecoderHDQPSK;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigRemote;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecoderFactoryRemoteBitstreamTest
{
    @Test
    void selectsBitOnlyPhase1DecoderForRemoteSource()
    {
        List<Module> modules = modules(new DecodeConfigP25Phase1());

        assertTrue(modules.stream().anyMatch(P25P1BitstreamDecoder.class::isInstance));
        assertFalse(modules.stream().anyMatch(P25P1DecoderC4FM.class::isInstance));
        assertFalse(modules.stream().anyMatch(P25P1DecoderLSM.class::isInstance));
    }

    @Test
    void selectsBitOnlyPhase2DecoderForRemoteSource()
    {
        List<Module> modules = modules(new DecodeConfigP25Phase2());

        assertTrue(modules.stream().anyMatch(P25P2BitstreamDecoder.class::isInstance));
        assertFalse(modules.stream().anyMatch(P25P2DecoderHDQPSK.class::isInstance));
    }

    @Test
    void bitstreamDecodersExposeStableListenerIdentity()
    {
        P25P1BitstreamDecoder phase1 = new P25P1BitstreamDecoder(true);
        P25P2BitstreamDecoder phase2 = new P25P2BitstreamDecoder(true);

        assertSame(phase1.getRemoteBitstreamListener(), phase1.getRemoteBitstreamListener());
        assertSame(phase2.getRemoteBitstreamListener(), phase2.getRemoteBitstreamListener());
    }

    private static List<Module> modules(Object decodeConfiguration)
    {
        Channel channel = new Channel("Remote", Channel.ChannelType.STANDARD);

        if(decodeConfiguration instanceof DecodeConfigP25Phase1 phase1)
        {
            channel.setDecodeConfiguration(phase1);
        }
        else
        {
            channel.setDecodeConfiguration((DecodeConfigP25Phase2)decodeConfiguration);
        }

        SourceConfigRemote source = new SourceConfigRemote();
        source.setSenderId("c583c158-1581-4f14-90ce-745635c05526");
        source.setFeedId("f02e5285-33c9-4491-964c-afd88298d8df");
        source.setFrequency(851_006_250L);
        channel.setSourceConfiguration(source);
        return DecoderFactory.getPrimaryModules(channel, new AliasModel(), new UserPreferences(), null, null,
            4_800.0, null);
    }
}
