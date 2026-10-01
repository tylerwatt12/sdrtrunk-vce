/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerSelectedTunerTest
{
    @Test void failedWizardStartUsesOnlySelectedReceiverAndDoesNotChangeOrdinaryStartup() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        TrackingManager tuners = new TrackingManager(preferences);
        ChannelProcessingManager manager = new ChannelProcessingManager(null, tuners, new AliasModel(), preferences);
        DiscoveredTuner selected = new DiscoveredTuner()
        {
            @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
            @Override public String getId() { return "selected-spectrum-tuner"; }
            @Override public void start() { }
        };
        try
        {
            for(var decoder: java.util.List.of(new DecodeConfigAM(), new DecodeConfigNBFM(), new DecodeConfigP25Phase1()))
            {
                Channel channel = new Channel("Discovered " + decoder.getDecoderType());
                channel.setDecodeConfiguration(decoder);
                SourceConfigTuner source = new SourceConfigTuner();
                source.setFrequency(155_100_000);
                channel.setSourceConfiguration(source);
                assertThrows(ChannelException.class, () -> manager.startAtCurrentCenter(channel, selected));
                assertSame(selected, tuners.selected);
                assertFalse(channel.isProcessing());
            }
            assertEquals(3, tuners.selectedRequests);
            assertEquals(0, tuners.ordinaryRequests, "No ordinary allocation may follow a selected-tuner failure");
            Channel ordinary = new Channel("Ordinary FM");
            ordinary.setDecodeConfiguration(new DecodeConfigNBFM());
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(155_200_000);
            ordinary.setSourceConfiguration(source);
            assertThrows(ChannelException.class, () -> manager.start(ordinary));
            assertEquals(1, tuners.ordinaryRequests, "The wizard's source restriction must not leak to later starts");
            assertEquals(3, tuners.selectedRequests);
        }
        finally { manager.close(); }
    }

    private static class TrackingManager extends TunerManager
    {
        DiscoveredTuner selected;
        int selectedRequests;
        int ordinaryRequests;
        TrackingManager(UserPreferences preferences) { super(preferences); }
        @Override public Source getSource(SourceConfiguration config, ChannelSpecification specification, String threadName)
        {
            ordinaryRequests++;
            return null;
        }
        @Override public Source getSourceAtCurrentCenter(SourceConfiguration config, ChannelSpecification specification,
                                                        String threadName, DiscoveredTuner tuner)
        {
            selected = tuner;
            selectedRequests++;
            return null;
        }
    }
}
