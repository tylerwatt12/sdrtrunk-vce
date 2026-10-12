/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationContext;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationDecision;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerSelectedTunerTest
{
    @Test void requiredTunerSourceStartupFailureUpdatesMovementAndRunsCleanup() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliases = new AliasModel();
        FailingStartSource source = new FailingStartSource();
        TunerManager tuners = new TunerManager(preferences)
        {
            @Override public Source getSourceAtCurrentCenter(SourceConfiguration configuration,
                ChannelSpecification specification, String threadName, DiscoveredTuner selected,
                TunerAllocationContext context)
            {
                getTunerAllocationDiagnosticService().offer(new TunerAllocationDecision(System.currentTimeMillis(),
                    context, source.getTunerIdentity(), TunerAllocationDecision.Outcome.MOVED,
                    TunerAllocationDecision.Reason.SPARE_TUNER, 155_200_000L, 12_500L, 154_000_000L,
                    155_000_000L, 155_000_000L, 2_160_000L, 5_000L, null, null, null, java.util.List.of()));
                return source;
            }
        };
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliases, preferences), tuners, aliases, preferences);
        DiscoveredTuner selected = new DiscoveredTuner()
        {
            @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
            @Override public String getId() { return "failed-source-start-tuner"; }
            @Override public void start() { }
        };
        Channel channel = new Channel("Startup failure FM");
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        SourceConfigTuner configuration = new SourceConfigTuner();
        configuration.setFrequency(155_200_000);
        channel.setSourceConfiguration(configuration);

        try
        {
            assertThrows(ChannelException.class, () -> manager.startAtCurrentCenter(channel, selected));
            assertEquals(1, source.starts, "The failure must occur when the tuner source starts");
            assertTrue(source.stops > 0);
            assertTrue(source.disposals > 0);
            assertFalse(channel.isProcessing());
            assertNull(manager.getProcessingChain(channel));
            var history = tuners.getTunerAllocationDiagnosticService().snapshot().decisions();
            assertEquals(1, history.size(), "Startup failure updates the move instead of adding a second decision");
            assertEquals(TunerAllocationDecision.Outcome.MOVED_CALL_FAILED, history.getFirst().decision().outcome());
            assertEquals(154_000_000L, history.getFirst().decision().beforeFrequencyHz());
            assertEquals(155_000_000L, history.getFirst().decision().afterFrequencyHz());
        }
        finally
        {
            manager.close();
            tuners.stop();
        }
    }

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
        @Override public Source getSource(SourceConfiguration config, ChannelSpecification specification, String threadName,
                                          TunerAllocationContext context)
        {
            ordinaryRequests++;
            return null;
        }
        @Override public Source getSourceAtCurrentCenter(SourceConfiguration config, ChannelSpecification specification,
                                                        String threadName, DiscoveredTuner tuner,
                                                        TunerAllocationContext context)
        {
            selected = tuner;
            selectedRequests++;
            return null;
        }
    }

    private static class FailingStartSource extends TunerChannelSource
    {
        private int starts;
        private int stops;
        private int disposals;

        private FailingStartSource()
        {
            super(event -> {
                if(event.getEvent() == SourceEvent.Event.REQUEST_STOP_SAMPLE_STREAM &&
                    event.getSource() instanceof FailingStartSource stopped)
                {
                    stopped.dispose();
                }
            }, new TunerChannel(155_200_000, 12_500), "test tuner source startup", null);
            setTunerIdentity("failed-source-start-tuner");
        }

        @Override public double getSampleRate() { return 50_000; }
        @Override public void setFrequency(long frequency) { mTunerChannel.setFrequency(frequency); }
        @Override public void setFrequencyCorrection(long correction) { }
        @Override protected void setSampleRate(double sampleRate) { }
        @Override public void setListener(Listener<ComplexSamples> listener) { }
        @Override public void start() { starts++; throw new IllegalStateException("Injected tuner source startup failure"); }
        @Override public void stop() { stops++; super.stop(); }
        @Override public void dispose() { disposals++; super.dispose(); }
    }
}
