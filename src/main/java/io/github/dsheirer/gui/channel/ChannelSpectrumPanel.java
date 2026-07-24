/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.gui.channel;

import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelSource;
import io.github.dsheirer.gui.SplitPaneDividerHelper;
import io.github.dsheirer.gui.power.SignalPowerView;
import io.github.dsheirer.gui.squelch.NoiseSquelchView;
import io.github.dsheirer.gui.symbol.SymbolView;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.PrimaryDecoder;
import io.github.dsheirer.module.decode.am.AMDecoder;
import io.github.dsheirer.module.decode.nbfm.NBFMDecoder;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamplesToNativeBufferModule;
import io.github.dsheirer.settings.SettingsManager;
import io.github.dsheirer.source.ChannelFrequencyCorrectionStatusNotification;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.spectrum.ComplexDftProcessor;
import io.github.dsheirer.spectrum.FrequencyOverlayPanel;
import io.github.dsheirer.spectrum.SpectrumPanel;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import io.github.dsheirer.spectrum.converter.DFTResultsConverter;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.List;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.MouseInputAdapter;

/**
 * Display for channel FFT and squelch details
 */
public class ChannelSpectrumPanel extends JPanel implements Listener<SelectedFrequencyContext>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelSpectrumPanel.class);
    private static final DecimalFormat FREQUENCY_FORMAT = new DecimalFormat("0.00000");
    private static final int RF_PROBE_BANDWIDTH = 12500;
    private static final double RF_PROBE_MINIMUM_SAMPLE_RATE = 50000.0;
    private static final int CHANNEL_SPECTRUM_MINIMUM_WIDTH = 160;
    private static final String GROW_FILL = "[grow,fill]";
    private static final String SPLIT_PANE_DIVIDER_IDENTIFIER = "channel.spectrum.panel.split.pane.divider";
    private static final String CARD_NOISE_SQUELCH = "noise_squelch";
    private static final String CARD_SIGNAL_POWER = "signal_power";
    private static final String CARD_SYMBOL = "symbol";
    private static final String CARD_EMPTY = "empty";
    private final ConfigurationManager mConfigurationManager;
    private final TunerManager mTunerManager;
    private final UserPreferences mUserPreferences;
    private ProcessingChain mProcessingChain;
    private SelectedFrequencyContext mSelectedFrequencyContext;
    private TunerChannelSource mRfProbeSource;
    private final ComplexSamplesToNativeBufferModule mSampleStreamTapModule = new ComplexSamplesToNativeBufferModule();
    private final ComplexDftProcessor mComplexDftProcessor;
    private SpectrumPanel mSpectrumPanel;
    private final FrequencyOverlayPanel mFrequencyOverlayPanel;
    private final transient SourceEventProcessor mSourceEventProcessor = new SourceEventProcessor();
    private final SpinnerNumberModel mNoiseFloorSpinnerModel;
    private final JLabel mEstimatedCarrierOffsetFrequencyValueLabel;
    private final JLabel mViewedFrequencyValueLabel;
    private boolean mPanelVisible = false;
    private boolean mDftProcessing = false;
    private final NoiseSquelchView mNoiseSquelchView;
    private final SignalPowerView mSignalPowerView;
    private final SymbolView mSymbolView = new SymbolView();
    private final JFXPanel mNoiseSquelchPanel;
    private final JFXPanel mSymbolPanel;
    private JButton mInspectRfButton;
    private JSplitPane mSplitPane;
    private JPanel mRightCardPanel;
    private CardLayout mRightCardLayout;
    private boolean mSplitPaneDividerRestored;

    /**
     * Constructs an instance.
     */
    public ChannelSpectrumPanel(ConfigurationManager configurationManager, SettingsManager settingsManager,
                                UserPreferences userPreferences)
    {
        mConfigurationManager = configurationManager;
        mTunerManager = configurationManager.getTunerManager();
        mUserPreferences = userPreferences;
        mComplexDftProcessor = new ComplexDftProcessor(mUserPreferences.getSpectrumPreference());
        mNoiseSquelchView = new NoiseSquelchView(mConfigurationManager);
        mSignalPowerView = new SignalPowerView(mConfigurationManager);
        setLayout(new MigLayout("insets 0", GROW_FILL, GROW_FILL));

        JPanel fftPanel = new JPanel();
        fftPanel.setLayout(new MigLayout("insets 0", GROW_FILL, "[]" + GROW_FILL));
        fftPanel.setMinimumSize(new Dimension(CHANNEL_SPECTRUM_MINIMUM_WIDTH, 0));

        JPanel labelPanel = new JPanel();
        labelPanel.setLayout(new MigLayout("insets 2", GROW_FILL + "[grow,fill,left][right][right][][][]", ""));
        labelPanel.add(new JLabel("Channel Spectrum    "));

        mEstimatedCarrierOffsetFrequencyValueLabel = new JLabel(getPaddedCarrierOffsetLabel(0));
        mEstimatedCarrierOffsetFrequencyValueLabel.setEnabled(false);
        labelPanel.add(mEstimatedCarrierOffsetFrequencyValueLabel);

        mViewedFrequencyValueLabel = new JLabel(getPaddedViewedFrequencyLabel(0));
        mViewedFrequencyValueLabel.setEnabled(false);
        labelPanel.add(mViewedFrequencyValueLabel);

        mNoiseFloorSpinnerModel = new SpinnerNumberModel(18, 8, 36, 1);
        mNoiseFloorSpinnerModel.addChangeListener(e -> {
            Number number = mNoiseFloorSpinnerModel.getNumber();
            mSpectrumPanel.setSampleSize(number.doubleValue());
        });
        JSpinner noiseFloorSpinner = new JSpinner(mNoiseFloorSpinnerModel);
        labelPanel.add(noiseFloorSpinner);
        labelPanel.add(new JLabel("Noise Floor"));
        labelPanel.add(getInspectRfButton());

        JButton logIndexesButton = new JButton("Log Settings");
        logIndexesButton.addActionListener(e -> {
            if(mProcessingChain != null)
            {
                Source source = mProcessingChain.getSource();

                if(source instanceof PolyphaseChannelSource pcs)
                {
                    List<Integer> indexes = pcs.getOutputProcessorIndexes();
                    double sampleRate = pcs.getSampleRate();
                    long indexCenterFrequency = pcs.getIndexCenterFrequency();
                    long appliedFrequencyOffset = pcs.getFrequencyOffset();
                    long requestedCenterFrequency = pcs.getFrequency();

                    StringBuilder sb = new StringBuilder();
                    sb.append("Polyphase Channel - BW: ").append(FREQUENCY_FORMAT.format(sampleRate / 1E6d));
                    sb.append(" Center/Requested/Mixer: ").append(FREQUENCY_FORMAT.format(indexCenterFrequency / 1E6d));
                    sb.append("/").append(FREQUENCY_FORMAT.format(requestedCenterFrequency / 1E6d));
                    sb.append("/").append(FREQUENCY_FORMAT.format(appliedFrequencyOffset / 1E6d));
                    sb.append(" Polyphase Indexes: ").append(indexes);
                    sb.append(" Tuner SR:").append(FREQUENCY_FORMAT.format(pcs.getTunerSampleRate() / 1E6d));
                    sb.append(" CF:").append(FREQUENCY_FORMAT.format(pcs.getTunerCenterFrequency() / 1E6d));
                    LOGGER.info(sb.toString());
                    LOGGER.info("Output Processor: " + pcs.getStateDescription());
                }
                else
                {
                    LOGGER.info("Unsupported channel type: " + (source != null ? source.getClass() : " null"));
                }
            }
        });

        fftPanel.add(labelPanel, "wrap");

        mFrequencyOverlayPanel = new FrequencyOverlayPanel(settingsManager);
        mSpectrumPanel = new SpectrumPanel(settingsManager);
        mSpectrumPanel.setSampleSize(18.0);

        /**
         * The layered pane holds the overlapping spectrum and channel panels
         * and manages the sizing of each panel with the resize listener
         */
        JLayeredPane layeredPanel = new JLayeredPane();
        layeredPanel.addComponentListener(new ResizeListener());

        /**
         * Create a mouse adapter to handle mouse events over the spectrum
         * and waterfall panels
         */
        MouseEventProcessor mouser = new MouseEventProcessor();

        mFrequencyOverlayPanel.addMouseListener(mouser);
        mFrequencyOverlayPanel.addMouseMotionListener(mouser);
        mFrequencyOverlayPanel.addMouseWheelListener(mouser);

        //Add the spectrum and channel panels to the layered panel
        layeredPanel.add(mSpectrumPanel, 0, 0);
        layeredPanel.add(mFrequencyOverlayPanel, 1, 0);

        fftPanel.add(layeredPanel);

        mNoiseSquelchPanel = new JFXPanel();
        mSymbolPanel = new JFXPanel();

        //Spin noise squelch panel construction off onto the JavafX UI thread.
        Platform.runLater(() -> {
            Scene scene = new Scene(mNoiseSquelchView);
            mNoiseSquelchPanel.setScene(scene);
            Scene scene2 = new Scene(mSymbolView);
            URL resource = getClass().getResource("/sdrtrunk_style.css");

            if(resource != null)
            {
                scene2.getStylesheets().add(resource.toExternalForm());
            }
            else
            {
                LOGGER.warn("Can't find stylesheet resource for sdrtrunk");
            }

            mSymbolPanel.setScene(scene2);
        });

        //Keep all right-side panels in a CardLayout so JFXPanel instances are never removed from the
        //Swing component hierarchy.  Removing a JFXPanel from the hierarchy destroys its CVDisplayLink
        //connection on macOS, causing a new PulseTimer-CVDisplayLink thread to be spawned on every
        //channel switch — resulting in hundreds of leaked threads.
        mRightCardLayout = new CardLayout();
        mRightCardPanel = new JPanel(mRightCardLayout);
        mRightCardPanel.add(mNoiseSquelchPanel, CARD_NOISE_SQUELCH);
        mRightCardPanel.add(mSignalPowerView, CARD_SIGNAL_POWER);
        mRightCardPanel.add(mSymbolPanel, CARD_SYMBOL);
        mRightCardPanel.add(new JPanel(), CARD_EMPTY);
        mRightCardPanel.setMinimumSize(new Dimension(CHANNEL_SPECTRUM_MINIMUM_WIDTH, 0));
        mRightCardLayout.show(mRightCardPanel, CARD_EMPTY);

        mSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mSplitPane.add(fftPanel, JSplitPane.LEFT);
        mSplitPane.add(mRightCardPanel, JSplitPane.RIGHT);
        mSplitPane.addComponentListener(new ComponentAdapter()
        {
            @Override
            public void componentResized(ComponentEvent e)
            {
                restoreSplitPaneDividerLocation();
            }
        });
        mSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            int savedLocation = mUserPreferences.getSwingPreference().getInt(SPLIT_PANE_DIVIDER_IDENTIFIER, 700);
            mUserPreferences.getSwingPreference().setInt(SPLIT_PANE_DIVIDER_IDENTIFIER,
                SplitPaneDividerHelper.getDividerLocationOrDefault(mSplitPane, savedLocation,
                    CHANNEL_SPECTRUM_MINIMUM_WIDTH));
        });
        add(mSplitPane);
        EventQueue.invokeLater(this::restoreSplitPaneDividerLocation);

        mSampleStreamTapModule.setListener(mComplexDftProcessor);
        DFTResultsConverter DFTResultsConverter = new ComplexDecibelConverter();
        mComplexDftProcessor.addConverter(DFTResultsConverter);
        DFTResultsConverter.addListener(mSpectrumPanel);
        mSpectrumPanel.clearSpectrum();
    }

    private String getPaddedCarrierOffsetLabel(long value)
    {
        String paddedValue = StringUtils.leftPad(String.valueOf(value), 5);
        String paddedText = StringUtils.rightPad(paddedValue + " Hz", 20);
        return "Carrier Offset: " + paddedText;
    }

    private String getPaddedViewedFrequencyLabel(long frequency)
    {
        String frequencyText = frequency > 0 ? FREQUENCY_FORMAT.format(frequency / 1E6d) + " MHz" : "";
        return "Frequency: " + StringUtils.rightPad(frequencyText, 16);
    }

    private JButton getInspectRfButton()
    {
        if(mInspectRfButton == null)
        {
            mInspectRfButton = new JButton("Inspect RF");
            mInspectRfButton.setFocusable(false);
            mInspectRfButton.setToolTipText("Temporarily inspect the selected frequency with spare tuner capacity");
            mInspectRfButton.addActionListener(event -> inspectSelectedFrequency());
            updateInspectRfButton();
        }

        return mInspectRfButton;
    }

    private void inspectSelectedFrequency()
    {
        if(mSelectedFrequencyContext != null && mSelectedFrequencyContext.hasFrequency() &&
            mSelectedFrequencyContext.processingChain() == null && mPanelVisible)
        {
            stopRfProbe();
            startRfProbe(mSelectedFrequencyContext.frequency());
            updateFFTProcessing();
            updateInspectRfButton();
        }
    }

    private void updateInspectRfButton()
    {
        if(mInspectRfButton != null)
        {
            boolean canInspect = mPanelVisible && mSelectedFrequencyContext != null &&
                mSelectedFrequencyContext.hasFrequency() && mSelectedFrequencyContext.processingChain() == null &&
                mRfProbeSource == null;
            mInspectRfButton.setEnabled(canInspect);
        }
    }

    /**
     * Signals this panel to indicate if this panel is visible to turn on the FFT processor when the panel is visible
     * and turn off the FFT processor when it's not.
     *
     * Note: this method is intended to be called by the Swing event thread to ensure that only a single thread is
     * invoking either this method, or the receive() method, since there is no thread synchronization between these
     * two methods and they each depend on stable access to the mPanelVisible variable.
     *
     * @param visible true to indicate that this panel is showing/visible.
     */
    public void setPanelVisible(boolean visible)
    {
        mPanelVisible = visible;

        if(!mPanelVisible)
        {
            stopRfProbe();

            if(mSelectedFrequencyContext != null && mSelectedFrequencyContext.processingChain() == null)
            {
                disconnectProcessingChain();
            }
        }
        else if(mProcessingChain == null && mRfProbeSource == null && mSelectedFrequencyContext != null &&
            !mSelectedFrequencyContext.clearRequested())
        {
            if(mSelectedFrequencyContext.processingChain() != null)
            {
                attachProcessingChain(mSelectedFrequencyContext.processingChain());
            }
        }

        updateFFTProcessing();
        updateInspectRfButton();
        mNoiseSquelchView.setShowing(visible);
        mSymbolView.setShowing(visible);
    }

    public int getSplitPaneDividerLocation()
    {
        int savedLocation = mUserPreferences.getSwingPreference().getInt(SPLIT_PANE_DIVIDER_IDENTIFIER, 700);
        return SplitPaneDividerHelper.getDividerLocationOrDefault(mSplitPane, savedLocation,
            CHANNEL_SPECTRUM_MINIMUM_WIDTH);
    }

    private void restoreSplitPaneDividerLocation()
    {
        if(!mSplitPaneDividerRestored)
        {
            mSplitPaneDividerRestored = SplitPaneDividerHelper.restore(mSplitPane,
                mUserPreferences.getSwingPreference().getInt(SPLIT_PANE_DIVIDER_IDENTIFIER, 700),
                CHANNEL_SPECTRUM_MINIMUM_WIDTH);
        }
    }

    /**
     * Updates processing state for the DFT processor.  Turns on DFT processing when we have a processing chain and
     * when the user has this tab selected and visible.  Otherwise, turns off DFT processing.
     */
    private void updateFFTProcessing()
    {
        if(mPanelVisible && (mProcessingChain != null || mRfProbeSource != null))
        {
            startDftProcessing();
        }
        else
        {
            stopDftProcessing();
        }
    }

    /**
     * Starts DFT processing
     */
    private void startDftProcessing()
    {
        if(!mDftProcessing)
        {
            mDftProcessing = true;
            mSampleStreamTapModule.setListener(mComplexDftProcessor);
            mComplexDftProcessor.start();
        }
    }

    /**
     * Stops DFT processing
     */
    private void stopDftProcessing()
    {
        if(mDftProcessing)
        {
            mSampleStreamTapModule.removeListener();
            mComplexDftProcessor.stop();
            mSpectrumPanel.clearSpectrum();
            mDftProcessing = false;
        }
    }

    private void broadcast(SourceEvent sourceEvent)
    {
        if(mProcessingChain != null)
        {
            mProcessingChain.broadcast(sourceEvent);
        }
    }

    /**
     * Resets controls when changing processing chain source.  Note: this must be called on the Swing
     * dispatch thread because it directly invokes swing components.
     */
    private void reset()
    {
        mEstimatedCarrierOffsetFrequencyValueLabel.setText(getPaddedCarrierOffsetLabel(0));
        mEstimatedCarrierOffsetFrequencyValueLabel.setEnabled(false);
        mViewedFrequencyValueLabel.setText(getPaddedViewedFrequencyLabel(0));
        mViewedFrequencyValueLabel.setEnabled(false);
        mFrequencyOverlayPanel.process(SourceEvent.frequencyChange(null, 0));
        mFrequencyOverlayPanel.process(SourceEvent.sampleRateChange(0));
        mFrequencyOverlayPanel.setEstimatedCarrierOffsetFrequency(0);
        mFrequencyOverlayPanel.setChannelBandwidth(0);
    }

    private void updateViewedFrequency(long frequency)
    {
        EventQueue.invokeLater(() -> {
            mViewedFrequencyValueLabel.setText(getPaddedViewedFrequencyLabel(frequency));
            mViewedFrequencyValueLabel.setEnabled(frequency > 0);
        });
    }

    /**
     * Receive notifications of request to provide display of processing chain details.
     */
    @Override
    public void receive(SelectedFrequencyContext context)
    {
        if(context == null || context.clearRequested())
        {
            mSelectedFrequencyContext = context;
            disconnectProcessingChain();
            stopRfProbe();
            reset();
            mRightCardLayout.show(mRightCardPanel, CARD_EMPTY);
            updateFFTProcessing();
            updateInspectRfButton();
            return;
        }

        if(context.processingChain() != null)
        {
            mSelectedFrequencyContext = context;
            stopRfProbe();

            if(mProcessingChain != context.processingChain())
            {
                disconnectProcessingChain();
                reset();
                attachProcessingChain(context.processingChain());
            }

            updateInspectRfButton();
        }
        else if(context.hasFrequency())
        {
            mSelectedFrequencyContext = context;
            disconnectProcessingChain();
            stopRfProbe();
            reset();
            updateViewedFrequency(context.frequency());
            mRightCardLayout.show(mRightCardPanel, CARD_EMPTY);
            updateInspectRfButton();
        }
        else
        {
            mSelectedFrequencyContext = context;
            disconnectProcessingChain();
            stopRfProbe();
            reset();
            mRightCardLayout.show(mRightCardPanel, CARD_EMPTY);
            updateInspectRfButton();
        }

        updateFFTProcessing();
    }

    public void dispose()
    {
        disconnectProcessingChain();
        stopRfProbe();
        mSelectedFrequencyContext = null;
        reset();
        updateFFTProcessing();
        updateInspectRfButton();
    }

    private void disconnectProcessingChain()
    {
        if(mProcessingChain != null)
        {
            mNoiseSquelchView.setController(null);
            mSignalPowerView.setProcessingChain(null);
            mSymbolView.removeSymbolProvider();
            mSymbolView.setProtocol("");
            mProcessingChain.removeSourceEventListener(mSourceEventProcessor);
            mProcessingChain.removeModule(mSampleStreamTapModule);
            mProcessingChain = null;
        }
    }

    private void attachProcessingChain(ProcessingChain processingChain)
    {
        mProcessingChain = processingChain;

        if(mProcessingChain != null)
        {
            mProcessingChain.addSourceEventListener(mSourceEventProcessor);

            PrimaryDecoder primaryDecoder = mProcessingChain.getPrimaryDecoder();
            if(primaryDecoder instanceof NBFMDecoder nbfmDecoder)
            {
                mRightCardLayout.show(mRightCardPanel, CARD_NOISE_SQUELCH);
                mNoiseSquelchView.setController(nbfmDecoder);
            }
            else if(primaryDecoder instanceof AMDecoder)
            {
                mRightCardLayout.show(mRightCardPanel, CARD_SIGNAL_POWER);
                mSignalPowerView.setProcessingChain(mProcessingChain);
            }
            else if(primaryDecoder instanceof FeedbackDecoder feedbackDecoder)
            {
                mRightCardLayout.show(mRightCardPanel, CARD_SYMBOL);
                mSymbolView.setSymbolProvider(feedbackDecoder);
                mSymbolView.setProtocol(feedbackDecoder.getProtocolDescription());
            }
            else
            {
                mRightCardLayout.show(mRightCardPanel, CARD_EMPTY);
            }

            mProcessingChain.addModule(mSampleStreamTapModule);
            Source source = mProcessingChain.getSource();

            if(source instanceof TunerChannelSource tcs)
            {
                mFrequencyOverlayPanel.process(SourceEvent.frequencyChange(null, tcs.getFrequency()));
                mFrequencyOverlayPanel.process(SourceEvent.sampleRateChange(tcs.getSampleRate()));
                updateViewedFrequency(tcs.getFrequency());
            }

            Channel channel = mConfigurationManager.getChannelProcessingManager().getChannel(mProcessingChain);

            if(channel != null)
            {
                List<TunerChannel> tunerChannels = channel.getTunerChannels();

                if(!tunerChannels.isEmpty())
                {
                    mFrequencyOverlayPanel.setChannelBandwidth(tunerChannels.getFirst().getBandwidth());
                }
            }
        }
    }

    private void startRfProbe(long frequency)
    {
        Source source = mTunerManager.getSource(new TunerChannel(frequency, RF_PROBE_BANDWIDTH),
            new ChannelSpecification(RF_PROBE_MINIMUM_SAMPLE_RATE, RF_PROBE_BANDWIDTH,
                RF_PROBE_BANDWIDTH / 2.0d, RF_PROBE_BANDWIDTH * 0.60d),
            null, "now-playing-rf-probe-" + frequency);

        if(source instanceof TunerChannelSource tunerChannelSource)
        {
            mRfProbeSource = tunerChannelSource;
            mRfProbeSource.setSourceEventListener(mSourceEventProcessor);
            mRfProbeSource.setListener(mSampleStreamTapModule);
            mRfProbeSource.start();
            mRightCardLayout.show(mRightCardPanel, CARD_EMPTY);
            mFrequencyOverlayPanel.process(SourceEvent.frequencyChange(mRfProbeSource, mRfProbeSource.getFrequency()));
            mFrequencyOverlayPanel.process(SourceEvent.sampleRateChange(mRfProbeSource.getSampleRate()));
            mFrequencyOverlayPanel.setChannelBandwidth(RF_PROBE_BANDWIDTH);
            updateViewedFrequency(mRfProbeSource.getFrequency());
        }
        else
        {
            mRightCardLayout.show(mRightCardPanel, CARD_EMPTY);
            updateViewedFrequency(frequency);
            mFrequencyOverlayPanel.setChannelBandwidth(RF_PROBE_BANDWIDTH);
        }

        updateInspectRfButton();
    }

    private void stopRfProbe()
    {
        if(mRfProbeSource != null)
        {
            mRfProbeSource.setListener(null);
            mRfProbeSource.removeSourceEventListener();
            mRfProbeSource.stop();
            mRfProbeSource = null;
            updateInspectRfButton();
        }
    }

    /**
     * Processor for source event stream to capture power level and squelch related source events.
     */
    private class SourceEventProcessor implements Listener<SourceEvent>
    {
        @Override
        public void receive(SourceEvent sourceEvent)
        {
            if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_CHANNEL_FREQUENCY_CORRECTION_STATUS &&
                sourceEvent instanceof ChannelFrequencyCorrectionStatusNotification status)
            {
                updateEstimatedCarrierOffsetFrequency(-status.getDecoderCorrection());
            }
            else if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE &&
                sourceEvent.getValue() != null)
            {
                updateViewedFrequency(sourceEvent.getValue().longValue());
            }

            mSignalPowerView.receive(sourceEvent);
        }

        /**
         * Updates the current carrier offset tracking frequency.
         * @param carrierOffsetFrequency that is currently measured/estimated.
         */
        private void updateEstimatedCarrierOffsetFrequency(long carrierOffsetFrequency)
        {
            EventQueue.invokeLater(() -> {
                mEstimatedCarrierOffsetFrequencyValueLabel.setText(getPaddedCarrierOffsetLabel(carrierOffsetFrequency));
                mEstimatedCarrierOffsetFrequencyValueLabel.setEnabled(true);
            });

            mFrequencyOverlayPanel.setEstimatedCarrierOffsetFrequency(carrierOffsetFrequency);
        }
    }

    /**
     * Monitors the sizing of the layered pane and resizes the spectrum and
     * channel panels whenever the layered pane is resized
     */
    public class ResizeListener extends ComponentAdapter
    {
        @Override public void componentResized(ComponentEvent e)
        {
            Component c = e.getComponent();

            mSpectrumPanel.setBounds(0, 0, c.getWidth(), c.getHeight());
            mFrequencyOverlayPanel.setBounds(0, 0, c.getWidth(), c.getHeight());
        }
    }

    /**
     * Mouse event handler for the spectral display panel.
     */
    public class MouseEventProcessor extends MouseInputAdapter
    {
        @Override public void mouseMoved(MouseEvent event)
        {
            update(event);
        }

        /**
         * Updates the cursor display while the mouse is performing actions
         */
        private void update(MouseEvent event)
        {
            mFrequencyOverlayPanel.setCursorLocation(event.getPoint());
        }

        @Override public void mouseEntered(MouseEvent e)
        {
            mFrequencyOverlayPanel.setCursorVisible(true);
        }

        @Override public void mouseExited(MouseEvent e)
        {
            mFrequencyOverlayPanel.setCursorVisible(false);
        }
    }
}
