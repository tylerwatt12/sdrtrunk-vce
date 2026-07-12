/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ChannelActivityModelTest
{
    @Test
    void exposesExistingTablesWhenRendererAttachesAfterChannelStart() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(), new NowPlayingPreference(type -> {}));
        Channel channel = new Channel("Test Site", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(856_137_500L);
        channel.setSourceConfiguration(source);

        SwingUtilities.invokeAndWait(() -> {
            model.setEnabled(true);
            model.channelStarted(channel, List.of());
        });

        List<ChannelActivityTableModel> tables = model.getTables();
        assertEquals(2, tables.size());
        assertSame(model.getConventionalTable(), tables.getFirst());
        assertSame(channel, tables.get(1).getOwnerChannel());
    }
}
