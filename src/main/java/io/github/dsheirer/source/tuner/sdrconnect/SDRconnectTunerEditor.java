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

package io.github.dsheirer.source.tuner.sdrconnect;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.tuner.TunerEvent;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.TunerEditor;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Component;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.plaf.basic.BasicSliderUI;

/**
 * SDRconnect tuner configuration editor
 */
public class SDRconnectTunerEditor extends TunerEditor<SDRconnectTuner, SDRconnectTunerConfiguration>
{
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(SDRconnectTunerEditor.class);
    private static final String CONNECTION_STATUS_CONNECTED = "Connected";
    private static final String CONNECTION_STATUS_NOT_CONNECTED = "Not Connected";
    private static final String WRAP = "wrap";
    private static final String GROW_X = "growx";
    private static final int CONFIG_CONTROL_WIDTH = 100;
    private final DecimalFormat mMeasuredErrorPpmFormat = new DecimalFormat("0.0");
    private final DecimalFormat mSignalMetricFormat = new DecimalFormat("0.0",
        DecimalFormatSymbols.getInstance(Locale.US));

    private JTextField mHostField;
    private JSpinner mPortSpinner;
    private JTextField mDeviceNameField;
    private JComboBox<String> mSampleRateCombo;
    private JComboBox<String> mAntennaCombo;
    private JSlider mLnaStateSlider;
    private JPanel mLnaStateControlPanel;
    private JLabel mLnaStateValueLabel;
    private boolean mLnaStateAdjusting;
    private JLabel mSignalPowerLabel;
    private JLabel mSignalSnrLabel;
    private JPanel mMeasuredErrorPanel;
    private JLabel mMeasuredErrorHzLabel;
    private JLabel mMeasuredErrorPpmLabel;
    private Font mMeasuredStatusFont;
    private FrequencyPanel mSDRconnectFrequencyPanel;
    private final transient ISourceEventProcessor mFrequencySaveListener = event ->
    {
        if(hasTuner() && !isLoading())
        {
            save();
        }
    };

    /**
     * Constructs an instance
     * @param userPreferences for settings
     * @param tunerManager for saving configurations
     * @param discoveredTuner for this configuration
     */
    public SDRconnectTunerEditor(UserPreferences userPreferences, TunerManager tunerManager,
                                  DiscoveredTuner discoveredTuner)
    {
        super(userPreferences, tunerManager, discoveredTuner, SDRconnectTuner.class, SDRconnectTunerConfiguration.class);
        init();
        tunerStatusUpdated();
    }

    @Override
    public void setTunerLockState(boolean locked)
    {
        getFrequencyPanel().updateControls();
    }

    @Override
    public long getMinimumTunableFrequency()
    {
        return SDRconnectTunerController.MINIMUM_FREQUENCY;
    }

    @Override
    public long getMaximumTunableFrequency()
    {
        return SDRconnectTunerController.MAXIMUM_FREQUENCY;
    }

    @Override
    protected void tunerStatusUpdated()
    {
        setLoading(true);
        updateConnectionFieldEditState();

        if(hasTuner())
        {
            getTunerIdLabel().setText(getTuner().getPreferredName());

            SDRconnectTunerController controller = getTuner().getController();
            updateSelectedSampleRate((int)controller.getCurrentSampleRate());
            getSampleRateCombo().setEnabled(!controller.isLockedSampleRate());
            updateAntennaOptions(controller.getValidAntennas());
            updateSelectedAntenna(controller.getCurrentAntenna());
            getAntennaCombo().setEnabled(true);
            updateLnaStateSlider(controller.getLnaStateMinimum(), controller.getLnaStateMaximum(), controller.getLnaState());
            getLnaStateSlider().setEnabled(true);
            updateSignalPower(controller.getSignalPower());
            updateSignalSnr(controller.getSignalSnr());
            controller.setAntennaChangeListener(this::onAntennaChanged);
            controller.setValidAntennasChangeListener(this::onValidAntennasChanged);
            controller.setSampleRateChangeListener(this::onSampleRateChanged);
            controller.setLnaStateChangeListener(this::onLnaStateChanged);
            controller.setSignalPowerChangeListener(this::onSignalPowerChanged);
            controller.setSignalSnrChangeListener(this::onSignalSnrChanged);
        }
        else
        {
            getTunerIdLabel().setText("SDRconnect");
            updateSelectedSampleRate(SDRconnectTunerController.DEFAULT_SAMPLE_RATE);
            getSampleRateCombo().setEnabled(hasConfiguration());
            getAntennaCombo().setEnabled(false);
            updateLnaStateSlider(0, 0, 0);
            getLnaStateSlider().setEnabled(false);
            updateSignalPower(Double.NaN);
            updateSignalSnr(Double.NaN);
        }

        String status = getDiscoveredTuner().getTunerStatus().toString();
        if(getDiscoveredTuner().hasErrorMessage())
        {
            status += " - " + getDiscoveredTuner().getErrorMessage();
        }
        status += hasTuner() && getTuner().getController().isRunning() ?
            " - " + CONNECTION_STATUS_CONNECTED : " - " + CONNECTION_STATUS_NOT_CONNECTED;
        getTunerStatusLabel().setText(status);
        getButtonPanel().updateControls();
        getFrequencyPanel().updateControls();

        if(hasConfiguration())
        {
            SDRconnectTunerConfiguration config = getConfiguration();
            getHostField().setText(config.getHost());
            getPortSpinner().setValue(config.getPort());
            getDeviceNameField().setText(config.getDeviceName());

            if(!hasTuner())
            {
                updateSelectedSampleRate(config.getSampleRate());
                updateSelectedAntenna(config.getAntenna());
                updateLnaStateSlider(0, 0, config.getLnaState());
                getLnaStateSlider().setEnabled(false);
                updateSignalPower(Double.NaN);
                updateSignalSnr(Double.NaN);
            }
        }

        setLoading(false);
    }

    private void updateConnectionFieldEditState()
    {
        boolean editable = !hasTuner();
        getHostField().setEditable(editable);
        getHostField().setEnabled(editable);
        getPortSpinner().setEnabled(editable);
        getDeviceNameField().setEditable(editable);
        getDeviceNameField().setEnabled(editable);
    }

    private void init()
    {
        setLayout(new MigLayout("fill,insets 0", "[450!][1!][grow,fill]", "[fill]"));

        JPanel leftPanel = new JPanel(new MigLayout("fillx,wrap 4", "[right][pref!][right][grow,fill]",
            "[][][][][][][][]push"));
        leftPanel.add(new JLabel("Tuner:"));
        leftPanel.add(getTunerIdLabel(), "span 3,wrap");
        leftPanel.add(new JLabel("Status:"));
        leftPanel.add(getTunerStatusLabel(), "span 3,wrap");
        leftPanel.add(new JLabel("Host:"));
        leftPanel.add(getHostField(), "span 3,grow,wrap");
        leftPanel.add(new JLabel("Port:"));
        leftPanel.add(getPortSpinner(), "align right");
        leftPanel.add(new JLabel("Device:"));
        leftPanel.add(getDeviceNameField(), WRAP);
        leftPanel.add(new JLabel("Sample Rate:"));
        leftPanel.add(getSampleRateCombo());
        leftPanel.add(new JLabel("Antenna:"));
        leftPanel.add(getAntennaCombo(), WRAP);
        leftPanel.add(new JLabel("LNA State:"));
        leftPanel.add(getLnaStateControlPanel(), "span 3,growx");
        JPanel rightPanel = new JPanel(new MigLayout("fillx,gapy 0,wrap 1", "[grow,fill]", "[][]"));
        rightPanel.add(getButtonPanel(), "shrink,align left");
        rightPanel.add(getFrequencyPanel(), GROW_X);

        add(leftPanel, "grow");
        add(new JSeparator(SwingConstants.VERTICAL), "growy");
        add(rightPanel, "grow");
    }

    @Override
    protected FrequencyPanel getFrequencyPanel()
    {
        if(mSDRconnectFrequencyPanel == null)
        {
            mSDRconnectFrequencyPanel = new SDRconnectFrequencyPanel();
        }

        return mSDRconnectFrequencyPanel;
    }

    /**
     * Host address field
     */
    private JTextField getHostField()
    {
        if(mHostField == null)
        {
            mHostField = new JTextField(SDRconnectTunerController.DEFAULT_HOST);
            mHostField.setToolTipText("SDRconnect host address (e.g., 127.0.0.1)");
        }
        return mHostField;
    }

    /**
     * Port spinner
     */
    private JSpinner getPortSpinner()
    {
        if(mPortSpinner == null)
        {
            SpinnerNumberModel model = new SpinnerNumberModel(
                    SDRconnectTunerController.DEFAULT_PORT, 1, 65535, 1);
            mPortSpinner = new JSpinner(model);
            mPortSpinner.setToolTipText("SDRconnect WebSocket port (default: 5454)");
            setPreferredControlWidth(mPortSpinner);
        }
        return mPortSpinner;
    }

    /**
     * Device name field
     */
    private JTextField getDeviceNameField()
    {
        if(mDeviceNameField == null)
        {
            mDeviceNameField = new JTextField(SDRconnectTunerController.DEFAULT_DEVICE_NAME);
            mDeviceNameField.setToolTipText("SDRconnect device name or serial number, or leave blank to use the first discovered device");
        }
        return mDeviceNameField;
    }

    /**
     * Sample rate selection combo box
     */
    private JComboBox<String> getSampleRateCombo()
    {
        if(mSampleRateCombo == null)
        {
            String[] rates = new String[SDRconnectTunerController.SUPPORTED_SAMPLE_RATES.length];
            for(int i = 0; i < rates.length; i++)
            {
                rates[i] = formatSampleRate(SDRconnectTunerController.SUPPORTED_SAMPLE_RATES[i]);
            }
            mSampleRateCombo = new JComboBox<>(rates);
            updateSelectedSampleRate(SDRconnectTunerController.DEFAULT_SAMPLE_RATE);
            mSampleRateCombo.setToolTipText("Select sample rate for SDRconnect");
            setPreferredControlWidth(mSampleRateCombo);
            mSampleRateCombo.addActionListener(e -> onSampleRateSelected());
        }
        return mSampleRateCombo;
    }

    private void updateSelectedSampleRate(int sampleRate)
    {
        if(mSampleRateCombo == null)
        {
            return;
        }

        for(int i = 0; i < SDRconnectTunerController.SUPPORTED_SAMPLE_RATES.length; i++)
        {
            if(SDRconnectTunerController.SUPPORTED_SAMPLE_RATES[i] == sampleRate)
            {
                mSampleRateCombo.setSelectedIndex(i);
                return;
            }
        }

        mSampleRateCombo.setSelectedIndex(0);
    }

    /**
     * Antenna selection combo box
     */
    private JComboBox<String> getAntennaCombo()
    {
        if(mAntennaCombo == null)
        {
            mAntennaCombo = new JComboBox<>();
            setPreferredControlWidth(mAntennaCombo);
            mAntennaCombo.setRenderer(new DefaultListCellRenderer()
            {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus)
                {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    setText(formatAntenna((String) value));
                    return this;
                }
            });
            mAntennaCombo.setToolTipText("Select antenna input");
            mAntennaCombo.addActionListener(e -> {
                if(!isLoading() && hasTuner())
                {
                    String antenna = (String) getAntennaCombo().getSelectedItem();
                    if(antenna != null)
                    {
                        getTuner().getController().requestAntenna(antenna);
                        mLog.info("Requested antenna: {}", antenna);
                    }
                }
            });
        }
        return mAntennaCombo;
    }

    private JPanel getLnaStateControlPanel()
    {
        if(mLnaStateControlPanel == null)
        {
            mLnaStateControlPanel = new JPanel(new MigLayout("insets 0,wrap 1,gapy 2", "[grow,fill]", "[][]"));
            mLnaStateControlPanel.add(getLnaStateValueLabel(), "align center");
            mLnaStateControlPanel.add(getLnaStateSlider(), "growx");
        }

        return mLnaStateControlPanel;
    }

    private JLabel getLnaStateValueLabel()
    {
        if(mLnaStateValueLabel == null)
        {
            mLnaStateValueLabel = new JLabel("");
            mLnaStateValueLabel.setVisible(false);
        }

        return mLnaStateValueLabel;
    }

    private JSlider getLnaStateSlider()
    {
        if(mLnaStateSlider == null)
        {
            mLnaStateSlider = new JSlider(SwingConstants.HORIZONTAL, 0, 0, 0);
            mLnaStateSlider.setToolTipText("Set SDRconnect LNA state (0 = maximum gain; higher values reduce gain).");
            mLnaStateSlider.setMajorTickSpacing(1);
            mLnaStateSlider.setPaintTicks(true);
            mLnaStateSlider.setInverted(true);
            mLnaStateSlider.setUI(new BasicSliderUI(mLnaStateSlider)
            {
                @Override
                public void paintTrack(Graphics graphics)
                {
                    Graphics2D graphics2D = (Graphics2D)graphics.create();
                    int trackHeight = Math.max(3, (trackRect.height - 2) / 2);
                    int trackY = trackRect.y + (trackRect.height - trackHeight) / 2;
                    int transitionWidth = Math.max(1, trackRect.width / 2);

                    graphics2D.setPaint(new GradientPaint(trackRect.x, 0, new Color(0x4CAF50),
                        trackRect.x + transitionWidth, 0, new Color(0xFDD835)));
                    graphics2D.fillRoundRect(trackRect.x, trackY, transitionWidth, trackHeight, trackHeight, trackHeight);

                    graphics2D.setPaint(new GradientPaint(trackRect.x + transitionWidth, 0, new Color(0xFDD835),
                        trackRect.x + trackRect.width, 0, new Color(0xD32F2F)));
                    graphics2D.fillRoundRect(trackRect.x + transitionWidth, trackY,
                        trackRect.width - transitionWidth, trackHeight, trackHeight, trackHeight);

                    graphics2D.setColor(Color.GRAY);
                    graphics2D.drawRoundRect(trackRect.x, trackY, trackRect.width - 1, trackHeight - 1,
                        trackHeight, trackHeight);
                    graphics2D.dispose();
                }
            });
            mLnaStateSlider.addChangeListener(e -> {
                updateLnaStateValueDisplay(mLnaStateSlider.getValue(),
                    mLnaStateAdjusting || mLnaStateSlider.getValueIsAdjusting());

                if(!isLoading())
                {
                    int lnaState = getLnaStateSlider().getValue();

                    if(hasTuner())
                    {
                        getTuner().getController().requestLnaState(lnaState);
                    }

                    save();
                }
            });
            mLnaStateSlider.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mousePressed(MouseEvent event)
                {
                    mLnaStateAdjusting = true;
                    updateLnaStateValueDisplay(mLnaStateSlider.getValue(), true);
                }

                @Override
                public void mouseReleased(MouseEvent event)
                {
                    mLnaStateAdjusting = false;
                    updateLnaStateValueDisplay(mLnaStateSlider.getValue(), false);
                }
            });
        }

        return mLnaStateSlider;
    }

    private JLabel getSignalPowerLabel()
    {
        if(mSignalPowerLabel == null)
        {
            mSignalPowerLabel = new JLabel("Power: N/A");
            mSignalPowerLabel.setFont(getMeasuredStatusFont());
        }

        return mSignalPowerLabel;
    }

    private JLabel getSignalSnrLabel()
    {
        if(mSignalSnrLabel == null)
        {
            mSignalSnrLabel = new JLabel("SNR: N/A");
            mSignalSnrLabel.setFont(getMeasuredStatusFont());
        }

        return mSignalSnrLabel;
    }

    private Font getMeasuredStatusFont()
    {
        if(mMeasuredStatusFont == null)
        {
            mMeasuredStatusFont = getMeasuredPPMLabel().getFont().deriveFont(Font.ITALIC);
        }

        return mMeasuredStatusFont;
    }

    private void updateLnaStateSlider(int minimum, int maximum, int current)
    {
        if(mLnaStateSlider == null)
        {
            return;
        }

        int boundedMaximum = Math.max(minimum, maximum);
        int boundedCurrent = Math.max(minimum, Math.min(boundedMaximum, current));
        mLnaStateSlider.setMinimum(minimum);
        mLnaStateSlider.setMaximum(boundedMaximum);
        mLnaStateSlider.setValue(boundedCurrent);
        updateLnaStateValueDisplay(boundedCurrent, mLnaStateAdjusting);
    }

    private void updateLnaStateValueDisplay(int value, boolean visible)
    {
        getLnaStateValueLabel().setText("State " + value + " (0 = max gain)");
        getLnaStateValueLabel().setVisible(visible);
    }

    private void updateAntennaOptions(String[] antennas)
    {
        if(mAntennaCombo == null)
        {
            return;
        }

        mAntennaCombo.removeAllItems();
        for(String antenna : antennas)
        {
            mAntennaCombo.addItem(antenna);
        }
    }

    private void updateSelectedAntenna(String antenna)
    {
        if(mAntennaCombo == null)
        {
            return;
        }

        if(antenna == null || antenna.isEmpty())
        {
            if(mAntennaCombo.getItemCount() > 0)
            {
                mAntennaCombo.setSelectedIndex(0);
            }
            return;
        }

        for(int i = 0; i < mAntennaCombo.getItemCount(); i++)
        {
            if(mAntennaCombo.getItemAt(i).equals(antenna))
            {
                mAntennaCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private void setPreferredControlWidth(Component component)
    {
        Dimension preferredSize = component.getPreferredSize();
        Dimension size = new Dimension(CONFIG_CONTROL_WIDTH, preferredSize.height);
        component.setMinimumSize(size);
        component.setPreferredSize(size);
        component.setMaximumSize(size);
    }

    /**
     * SDRconnect-specific frequency panel that omits the generic PPM controls, which do not apply to SDRconnect.
     */
    private class SDRconnectFrequencyPanel extends FrequencyPanel
    {
        SDRconnectFrequencyPanel()
        {
            removeAll();
            setLayout(new MigLayout("insets 0,fillx,wrap 2", "[pref!][grow,fill]", "[][]"));
            add(getFrequencyControl(), "align left");
            add(getMeasuredErrorPanel(), "align left");

            JPanel minMaxPanel = new JPanel(new MigLayout("insets 0", "[][][][][][grow,fill]", ""));
            minMaxPanel.add(new JLabel("Minimum:"));
            minMaxPanel.add(getMinimumFrequencyTextField());
            minMaxPanel.add(new JLabel("Maximum:"));
            minMaxPanel.add(getMaximumFrequencyTextField());
            minMaxPanel.add(getResetFrequenciesButton());
            add(minMaxPanel, "span 2," + GROW_X);
            setToolTipText("Tuner frequency controls for SDRconnect");
        }

        @Override
        public void updateControls()
        {
            getFrequencyControl().clearListeners();
            getFrequencyControl().addListener(mFrequencySaveListener);
            boolean hasTunerUnlocked = hasTuner() && !getTuner().getTunerController().isLockedSampleRate();
            getFrequencyControl().setEnabled(hasTunerUnlocked);
            getMinimumFrequencyTextField().setEnabled(hasTunerUnlocked);
            getMaximumFrequencyTextField().setEnabled(hasTunerUnlocked);
            getResetFrequenciesButton().setEnabled(hasTunerUnlocked);

            SDRconnectTuner tuner = getTuner();

            if(tuner != null)
            {
                getFrequencyControl().setFrequency(tuner.getTunerController().getFrequency(), false);
                getMinimumFrequencyTextField().setFrequency(tuner.getTunerController().getMinimumFrequency());
                getMaximumFrequencyTextField().setFrequency(tuner.getTunerController().getMaximumFrequency());
                updateMeasuredErrorDisplay(tuner.getController());
                getFrequencyControl().addListener(tuner.getTunerController());
                tuner.getTunerController().addListener(getFrequencyControl());
            }
            else
            {
                getFrequencyControl().setFrequency(0, false);
                updateMeasuredErrorDisplay(null);
            }
        }

        @Override
        public void updateFrequencyError()
        {
            SwingUtilities.invokeLater(() ->
            {
                if(hasTuner())
                {
                    updateMeasuredErrorDisplay(getTuner().getController());
                }
                else
                {
                    updateMeasuredErrorDisplay(null);
                }
            });
        }

        @Override
        public void updatePPM()
        {
            // SDRconnect frequency error is managed in SDRconnect rather than through the generic PPM UI.
        }

        private void updateMeasuredErrorDisplay(SDRconnectTunerController controller)
        {
            if(controller == null || controller.getMeasuredFrequencyError() == 0)
            {
                getMeasuredErrorHzLabel().setText("");
                getMeasuredErrorPpmLabel().setText("");
                return;
            }

            getMeasuredErrorHzLabel().setText(String.format("%+d Hz", controller.getMeasuredFrequencyError()));
            getMeasuredErrorPpmLabel().setText(
                mMeasuredErrorPpmFormat.format(Math.abs(controller.getPPMFrequencyError())) + " ppm");
        }

        private JPanel getMeasuredErrorPanel()
        {
            if(mMeasuredErrorPanel == null)
            {
                mMeasuredErrorPanel = new JPanel(new MigLayout("insets 0,gapx 12", "[][]", "[top]"));

                JPanel measuredErrorValues = new JPanel(new MigLayout("insets 0,wrap 1,gapy 0", "[right]", "[]0[]"));
                measuredErrorValues.add(getMeasuredErrorHzLabel());
                measuredErrorValues.add(getMeasuredErrorPpmLabel());

                JPanel signalMetricsPanel = new JPanel(new MigLayout("insets 0,wrap 1,gapy 0", "[left]", "[]0[]"));
                signalMetricsPanel.add(getSignalPowerLabel());
                signalMetricsPanel.add(getSignalSnrLabel());

                mMeasuredErrorPanel.add(measuredErrorValues, "aligny top");
                mMeasuredErrorPanel.add(signalMetricsPanel, "aligny top");
            }

            return mMeasuredErrorPanel;
        }

        private JLabel getMeasuredErrorHzLabel()
        {
            if(mMeasuredErrorHzLabel == null)
            {
                mMeasuredErrorHzLabel = new JLabel("");
                mMeasuredErrorHzLabel.setFont(getMeasuredStatusFont());
            }

            return mMeasuredErrorHzLabel;
        }

        private JLabel getMeasuredErrorPpmLabel()
        {
            if(mMeasuredErrorPpmLabel == null)
            {
                mMeasuredErrorPpmLabel = new JLabel("");
                mMeasuredErrorPpmLabel.setFont(getMeasuredStatusFont());
            }

            return mMeasuredErrorPpmLabel;
        }
    }

    @Override
    public void receive(TunerEvent tunerEvent)
    {
        super.receive(tunerEvent);
        if(tunerEvent.getEvent() == TunerEvent.Event.UPDATE_LOCK_STATE && hasTuner())
        {
            SDRconnectTunerController controller = getTuner().getController();
            SwingUtilities.invokeLater(() -> getSampleRateCombo().setEnabled(hasTuner() &&
                !controller.isLockedSampleRate()));
        }
    }

    private void onSampleRateSelected()
    {
        if(!isLoading())
        {
            if(hasTuner())
            {
                SDRconnectTunerController controller = getTuner().getController();
                if(controller.isLockedSampleRate())
                {
                    mLog.warn("Cannot change SDRconnect sample rate while tuner channels are active and the sample rate is locked");
                    return;
                }
                int selectedIndex = getSampleRateCombo().getSelectedIndex();
                if(selectedIndex >= 0 && selectedIndex < SDRconnectTunerController.SUPPORTED_SAMPLE_RATES.length)
                {
                    int sampleRate = SDRconnectTunerController.SUPPORTED_SAMPLE_RATES[selectedIndex];
                    controller.requestSampleRate(sampleRate);
                    mLog.info("Requested sample rate: {} Hz", sampleRate);
                }
            }

            save();
        }
    }

    private static String formatSampleRate(double hz)
    {
        if(hz >= 1_000_000)
        {
            double mhz = hz / 1e6;
            return mhz == Math.rint(mhz) ? String.format("%.0f MHz", mhz) : String.format("%.1f MHz", mhz);
        }

        double khz = hz / 1e3;
        return khz == Math.rint(khz) ? String.format("%.0f kHz", khz) : String.format("%.1f kHz", khz);
    }

    private static String formatAntenna(String antenna)
    {
        if(antenna == null || antenna.isEmpty())
        {
            return "N/A";
        }
        String stripped = antenna.replaceFirst("(?i)^antenna\\s+", "");
        return stripped.isEmpty() ? antenna : stripped;
    }

    private void onAntennaChanged(String antenna)
    {
        SwingUtilities.invokeLater(() -> {
            setLoading(true);
            updateSelectedAntenna(antenna);
            setLoading(false);
            save();
        });
    }

    private void onValidAntennasChanged(String[] antennas)
    {
        SwingUtilities.invokeLater(() -> {
            setLoading(true);
            updateAntennaOptions(antennas);

            if(hasTuner())
            {
                updateSelectedAntenna(getTuner().getController().getCurrentAntenna());
            }

            setLoading(false);
            save();
        });
    }

    private void onSampleRateChanged(int sampleRate)
    {
        SwingUtilities.invokeLater(() -> {
            setLoading(true);
            updateSelectedSampleRate(sampleRate);
            getSampleRateCombo().setEnabled(!getTuner().getController().isLockedSampleRate());
            setLoading(false);
        });
    }

    private void onLnaStateChanged(int lnaState)
    {
        SwingUtilities.invokeLater(() -> {
            setLoading(true);
            if(hasTuner())
            {
                SDRconnectTunerController controller = getTuner().getController();
                updateLnaStateSlider(controller.getLnaStateMinimum(), controller.getLnaStateMaximum(), lnaState);
            }
            else
            {
                updateLnaStateSlider(0, 0, lnaState);
            }
            setLoading(false);
            save();
        });
    }

    private void onSignalPowerChanged(double signalPower)
    {
        SwingUtilities.invokeLater(() -> updateSignalPower(signalPower));
    }

    private void onSignalSnrChanged(double signalSnr)
    {
        SwingUtilities.invokeLater(() -> updateSignalSnr(signalSnr));
    }

    private void updateSignalPower(double signalPower)
    {
        getSignalPowerLabel().setText(Double.isFinite(signalPower) ? "Power: " +
            mSignalMetricFormat.format(signalPower) + " dB" : "Power: N/A");
    }

    private void updateSignalSnr(double signalSnr)
    {
        getSignalSnrLabel().setText(Double.isFinite(signalSnr) ? "SNR: " +
            mSignalMetricFormat.format(signalSnr) + " dB" : "SNR: N/A");
    }

    @Override
    public void save()
    {
        if(hasConfiguration() && !isLoading())
        {
            SDRconnectTunerConfiguration config = getConfiguration();
            config.setFrequency(getFrequencyControl().getFrequency());
            config.setMinimumFrequency(getMinimumFrequencyTextField().getFrequency());
            config.setMaximumFrequency(getMaximumFrequencyTextField().getFrequency());
            config.setHost(getHostField().getText().trim());
            config.setPort((Integer) getPortSpinner().getValue());
            config.setDeviceName(getDeviceNameField().getText().trim());
            config.setUniqueID(SDRconnectTunerConfiguration.createUniqueId(
                config.getHost(), config.getPort()));
            int selectedIndex = getSampleRateCombo().getSelectedIndex();
            if(selectedIndex >= 0 && selectedIndex < SDRconnectTunerController.SUPPORTED_SAMPLE_RATES.length
                    && getSampleRateCombo().getItemCount() > 0)
            {
                config.setSampleRate(SDRconnectTunerController.SUPPORTED_SAMPLE_RATES[selectedIndex]);
            }
            String antenna = (String) getAntennaCombo().getSelectedItem();
            if(antenna != null && getAntennaCombo().getItemCount() > 0)
            {
                config.setAntenna(antenna);
            }
            config.setLnaState(getLnaStateSlider().getValue());
            // Force PPM to 0 - correction should be done in SDRconnect
            config.setFrequencyCorrection(0.0);
            saveConfiguration();
        }
    }

}
