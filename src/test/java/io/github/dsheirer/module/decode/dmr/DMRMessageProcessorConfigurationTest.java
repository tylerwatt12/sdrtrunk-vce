/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.GroupVoiceChannelUser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DMRMessageProcessorConfigurationTest
{
    @Test
    void compressedTalkgroupsRequireTrunkedModeEvenForOlderPersistedConfigurations()
    {
        for(DMRChannelMode mode: DMRChannelMode.values())
        {
            for(boolean compressed: new boolean[]{false, true})
            {
                DecodeConfigDMR config = new DecodeConfigDMR();
                config.setChannelMode(mode);
                config.setUseCompressedTalkgroups(compressed);
                DMRMessageProcessor processor = new DMRMessageProcessor(config, new DMRCrcMaskManager(false));
                List<IMessage> delivered = new ArrayList<>();
                processor.setMessageListener(delivered::add);
                CorrectedBinaryMessage bits = new CorrectedBinaryMessage(72);
                bits.load(24, 24, 1331);
                bits.load(48, 24, 91);
                GroupVoiceChannelUser voice = new GroupVoiceChannelUser(bits, 100, 1);

                processor.receive(voice);

                assertEquals(1, delivered.size());
                assertSame(voice, delivered.getFirst());
                assertEquals(mode == DMRChannelMode.TRUNKED && compressed ? 1000 : 1331,
                    voice.getIdentifiers().stream().filter(DMRTalkgroup.class::isInstance)
                        .map(DMRTalkgroup.class::cast).findFirst().orElseThrow().getValue(), mode.name());
            }
        }
    }
}
