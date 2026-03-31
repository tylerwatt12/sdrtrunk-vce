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
import io.github.dsheirer.source.tuner.TunerEvent;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.TunerEditor;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.JSpinner;
import javax.swing.JComboBox;
import javax.swing.SpinnerNumberModel;

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

    private JTextField mHostField;
    private JSpinner mPortSpinner;
    private JTextField mDeviceNameField;
    private JLabel mSampleRateLabel;
    private JComboBox<String> mSampleRateCombo;
    private JLabel mAntennaLabel;
    private JComboBox<String> mAntennaCombo;
    private FrequencyPanel mSDRconnectFrequencyPanel;

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

        if(hasTuner())
        {
            getTunerIdLabel().setText(getTuner().getPreferredName());

            SDRconnectTunerController controller = getTuner().getController();
            getSampleRateLabel().setText(String.format("%.3f MHz", controller.getCurrentSampleRate() / 1e6));
            updateSelectedSampleRate((int)controller.getCurrentSampleRate());
            getSampleRateCombo().setEnabled(!controller.isLockedSampleRate());
            updateAntennaOptions(controller.getValidAntennas());
            String antenna = controller.getCurrentAntenna();
            getAntennaLabel().setText(antenna.isEmpty() ? "N/A" : antenna);
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
            getHostField().setText(getConfiguration().getHost());
            getPortSpinner().setValue(getConfiguration().getPort());
            getDeviceNameField().setText(getConfiguration().getDeviceName());
        }

        setLoading(false);
    }

    private void init()
    {
        setLayout(new MigLayout("fill,insets 0", "[450!][1!][grow,fill]", "[fill]"));

        JPanel leftPanel = new JPanel(new MigLayout("fillx,wrap 2", "[right][grow,fill]",
            "[][][][][][][][][][]push"));
        leftPanel.add(new JLabel("Tuner:"));
        leftPanel.add(getTunerIdLabel(), WRAP);
        leftPanel.add(new JLabel("Status:"));
        leftPanel.add(getTunerStatusLabel(), WRAP);
        leftPanel.add(new JLabel("Host:"));
        leftPanel.add(getHostField(), WRAP);
        leftPanel.add(new JLabel("Port:"));
        leftPanel.add(getPortSpinner(), WRAP);
        leftPanel.add(new JLabel("Device:"));
        leftPanel.add(getDeviceNameField(), WRAP);
        leftPanel.add(new JLabel("Sample Rate:"));
        leftPanel.add(getSampleRateLabel(), "split 2");
        leftPanel.add(getSampleRateCombo(), "wrap");
        leftPanel.add(new JLabel("Antenna:"));
        leftPanel.add(getAntennaLabel(), "split 2");
        leftPanel.add(getAntennaCombo(), "wrap");

        JPanel rightPanel = new JPanel(new MigLayout("fill,wrap 1", "[grow,fill]", "[][grow,push]"));
        rightPanel.add(getButtonPanel(), "shrink,align left");
        JPanel frequencyPanel = new JPanel(new MigLayout("fill,wrap 1", "[grow,fill]", "[]"));
        frequencyPanel.add(getFrequencyPanel(), "growx");
        rightPanel.add(frequencyPanel, "grow");

        add(leftPanel, "grow");
        add(new JSeparator(JSeparator.VERTICAL), "growy");
        add(rightPanel, "grow");
    }

    @Override
    protected FrequencyPanel getFrequencyPanel()
    {
        if(mSDRconnectFrequencyPanel == null)
        {
            mSDRconnectFrequencyPanel = new FrequencyPanel()
            {
                @Override
                public void updateControls()
                {
                    super.updateControls();
                    getFrequencyCorrectionSpinner().setValue(0.0);
                    getFrequencyCorrectionSpinner().setEnabled(false);
                    getFrequencyCorrectionSpinner().setToolTipText(
                        "PPM correction is not available for SDRconnect - configure frequency correction in SDRconnect instead");
                    getAutoPPMCheckBox().setSelected(false);
                    getAutoPPMCheckBox().setEnabled(false);
                    getAutoPPMCheckBox().setToolTipText(
                        "PPM correction is not available for SDRconnect - configure frequency correction in SDRconnect instead");
                }
            };
            mSDRconnectFrequencyPanel.setToolTipText("Tuner frequency controls for SDRconnect");
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
                rates[i] = String.format("%.0f MHz", SDRconnectTunerController.SUPPORTED_SAMPLE_RATES[i] / 1e6);
            }
            mSampleRateCombo = new JComboBox<>(rates);
            updateSelectedSampleRate(SDRconnectTunerController.DEFAULT_SAMPLE_RATE);
            mSampleRateCombo.setToolTipText("Select sample rate for SDRconnect");
            mSampleRateCombo.addActionListener(e -> {
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
            });
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
        if(mAntennaCombo == null || antenna == null || antenna.isEmpty())
        {
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

    @Override
    public void receive(TunerEvent tunerEvent)
    {
        super.receive(tunerEvent);
        if(tunerEvent.getEvent() == TunerEvent.Event.UPDATE_LOCK_STATE && hasTuner())
        {
            SwingUtilities.invokeLater(() ->
                getSampleRateCombo().setEnabled(!getTuner().getController().isLockedSampleRate()));
        }
    }

    private void onAntennaChanged(String antenna)
    {
        SwingUtilities.invokeLater(() -> {
            getAntennaLabel().setText(antenna.isEmpty() ? "N/A" : antenna);
            updateSelectedAntenna(antenna);
        });
    }

    private void onSampleRateChanged(int sampleRate)
    {
        SwingUtilities.invokeLater(() -> {
            getSampleRateLabel().setText(String.format("%.3f MHz", sampleRate / 1e6));
            updateSelectedSampleRate(sampleRate);
            getSampleRateCombo().setEnabled(!getTuner().getController().isLockedSampleRate());
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
            // Force PPM to 0 - correction should be done in SDRconnect
            config.setFrequencyCorrection(0.0);
            saveConfiguration();
        }
    }

}
