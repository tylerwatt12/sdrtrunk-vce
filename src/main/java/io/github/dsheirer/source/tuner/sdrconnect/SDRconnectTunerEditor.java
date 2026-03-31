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
import java.awt.Dimension;
import java.awt.Font;
import java.text.DecimalFormat;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

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

    private JTextField mHostField;
    private JSpinner mPortSpinner;
    private JTextField mDeviceNameField;
    private JLabel mSampleRateLabel;
    private JComboBox<String> mSampleRateCombo;
    private JLabel mAntennaLabel;
    private JComboBox<String> mAntennaCombo;
    private JPanel mMeasuredErrorPanel;
    private JLabel mMeasuredErrorHzLabel;
    private JLabel mMeasuredErrorPpmLabel;
    private FrequencyPanel mSDRconnectFrequencyPanel;
    private final ISourceEventProcessor mFrequencySaveListener = event ->
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
        super(userPreferences, tunerManager, discoveredTuner);
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
            getSampleRateLabel().setText(formatSampleRate(controller.getCurrentSampleRate()));
            updateSelectedSampleRate((int)controller.getCurrentSampleRate());
            getSampleRateCombo().setEnabled(!controller.isLockedSampleRate());
            updateAntennaOptions(controller.getValidAntennas());
            String antenna = controller.getCurrentAntenna();
            getAntennaLabel().setText(formatAntenna(antenna));
            updateSelectedAntenna(antenna);
            getAntennaCombo().setEnabled(true);
            controller.setAntennaChangeListener(this::onAntennaChanged);
            controller.setSampleRateChangeListener(this::onSampleRateChanged);
        }
        else
        {
            getTunerIdLabel().setText("SDRconnect");
            getSampleRateLabel().setText("N/A");
            updateSelectedSampleRate(SDRconnectTunerController.DEFAULT_SAMPLE_RATE);
            getSampleRateCombo().setEnabled(false);
            getAntennaLabel().setText("N/A");
            getAntennaCombo().setEnabled(false);
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
        leftPanel.add(getHostField(), "span,grow,wrap");
        leftPanel.add(new JLabel("Port:"));
        leftPanel.add(getPortSpinner(), "align right");
        leftPanel.add(new JLabel("Device:"));
        leftPanel.add(getDeviceNameField(), WRAP);
        leftPanel.add(new JLabel("Sample Rate:"));
        leftPanel.add(getSampleRateCombo());
        leftPanel.add(new JLabel("In Use:"));
        leftPanel.add(getSampleRateLabel(), WRAP);
        leftPanel.add(new JLabel("Antenna:"));
        leftPanel.add(getAntennaCombo());
        leftPanel.add(new JLabel("In Use:"));
        leftPanel.add(getAntennaLabel(), WRAP);

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
     * Sample rate display label
     */
    private JLabel getSampleRateLabel()
    {
        if(mSampleRateLabel == null)
        {
            mSampleRateLabel = new JLabel("N/A");
        }
        return mSampleRateLabel;
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

    private JLabel getAntennaLabel()
    {
        if(mAntennaLabel == null)
        {
            mAntennaLabel = new JLabel("N/A");
        }
        return mAntennaLabel;
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
        private Font mMeasuredErrorFont;

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
                mMeasuredErrorPanel = new JPanel(new MigLayout("insets 0,wrap 1,gapy 0", "[right]", "[]0[]"));
                mMeasuredErrorPanel.add(getMeasuredErrorHzLabel());
                mMeasuredErrorPanel.add(getMeasuredErrorPpmLabel());
            }

            return mMeasuredErrorPanel;
        }

        private JLabel getMeasuredErrorHzLabel()
        {
            if(mMeasuredErrorHzLabel == null)
            {
                mMeasuredErrorHzLabel = new JLabel("");
                mMeasuredErrorHzLabel.setFont(getMeasuredErrorFont());
            }

            return mMeasuredErrorHzLabel;
        }

        private JLabel getMeasuredErrorPpmLabel()
        {
            if(mMeasuredErrorPpmLabel == null)
            {
                mMeasuredErrorPpmLabel = new JLabel("");
                mMeasuredErrorPpmLabel.setFont(getMeasuredErrorFont());
            }

            return mMeasuredErrorPpmLabel;
        }

        private Font getMeasuredErrorFont()
        {
            if(mMeasuredErrorFont == null)
            {
                mMeasuredErrorFont = getMeasuredPPMLabel().getFont().deriveFont(Font.ITALIC);
            }

            return mMeasuredErrorFont;
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
        if(!isLoading() && hasTuner())
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
            getAntennaLabel().setText(formatAntenna(antenna));
            updateSelectedAntenna(antenna);
            save();
        });
    }

    private void onSampleRateChanged(int sampleRate)
    {
        SwingUtilities.invokeLater(() -> {
            getSampleRateLabel().setText(formatSampleRate(sampleRate));
            updateSelectedSampleRate(sampleRate);
            getSampleRateCombo().setEnabled(!getTuner().getController().isLockedSampleRate());
            save();
        });
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
            config.setUniqueID(SDRconnectTunerConfiguration.getUniqueId(
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
            // Force PPM to 0 - correction should be done in SDRconnect
            config.setFrequencyCorrection(0.0);
            saveConfiguration();
        }
    }

}
