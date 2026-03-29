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
import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.ui.DiscoveredTunerModel;
import io.github.dsheirer.util.ThreadPool;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Dimension;
import java.awt.Window;
import java.net.InetAddress;

/**
 * Dialog to add an SDRconnect tuner by specifying host and port
 */
public class AddSDRconnectTunerDialog extends JDialog
{
    private static final Logger mLog = LoggerFactory.getLogger(AddSDRconnectTunerDialog.class);

    private final UserPreferences mUserPreferences;
    private final DiscoveredTunerModel mDiscoveredTunerModel;
    private final TunerConfigurationManager mTunerConfigurationManager;

    private JTextField mHostTextField;
    private JSpinner mPortSpinner;
    private JTextField mDeviceNameTextField;
    private JButton mTestButton;
    private JButton mAddButton;
    private JButton mCancelButton;
    private JLabel mStatusLabel;

    public AddSDRconnectTunerDialog(Window owner, UserPreferences userPreferences,
                                    DiscoveredTunerModel discoveredTunerModel,
                                    TunerConfigurationManager tunerConfigurationManager)
    {
        super(owner, "Add SDRconnect Tuner", ModalityType.APPLICATION_MODAL);
        Validate.notNull(userPreferences, "UserPreferences cannot be null");
        Validate.notNull(discoveredTunerModel, "TunerModel cannot be null");
        Validate.notNull(tunerConfigurationManager, "TunerConfigurationManager cannot be null");

        mUserPreferences = userPreferences;
        mDiscoveredTunerModel = discoveredTunerModel;
        mTunerConfigurationManager = tunerConfigurationManager;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(new Dimension(450, 230));

        JPanel content = new JPanel();
        content.setLayout(new MigLayout("", "[align right][grow,fill][]", "[][][][][][][grow][]"));

        content.add(new JLabel("Host:"));
        mHostTextField = new JTextField(SDRconnectTunerController.DEFAULT_HOST);
        mHostTextField.setToolTipText("SDRconnect host address (e.g., 127.0.0.1 or hostname)");
        content.add(mHostTextField, "span 2,wrap");

        content.add(new JLabel("Port:"));
        SpinnerNumberModel model = new SpinnerNumberModel(
                SDRconnectTunerController.DEFAULT_PORT, 1, 65535, 1);
        mPortSpinner = new JSpinner(model);
        mPortSpinner.setToolTipText("SDRconnect WebSocket port (default: 5454)");
        content.add(mPortSpinner);

        mTestButton = new JButton("Test");
        mTestButton.addActionListener(e -> testConnection());
        content.add(mTestButton, "wrap");

        content.add(new JLabel("Device:"));
        mDeviceNameTextField = new JTextField(SDRconnectTunerController.DEFAULT_DEVICE_NAME);
        mDeviceNameTextField.setToolTipText("SDRconnect device display name (for example: nRSP-ST 1)");
        content.add(mDeviceNameTextField, "span 2,wrap");

        content.add(new JLabel("Status:"));
        mStatusLabel = new JLabel("Not tested");
        content.add(mStatusLabel, "span 2,wrap");

        content.add(new JLabel(""));
        content.add(new JLabel(""), "wrap");

        content.add(new JLabel(""));

        mAddButton = new JButton("Add");
        mAddButton.addActionListener(e -> addTuner());
        content.add(mAddButton, "split 2");

        mCancelButton = new JButton("Cancel");
        mCancelButton.addActionListener(e -> dispose());
        content.add(mCancelButton);

        setContentPane(content);
    }

    private void testConnection()
    {
        String host = mHostTextField.getText().trim();
        int port = (Integer) mPortSpinner.getValue();

        mStatusLabel.setText("Testing...");
        mTestButton.setEnabled(false);

        // Run test in background thread
        ThreadPool.CACHED.execute(() -> {
            try
            {
                // Try to resolve the host
                InetAddress.getByName(host);

                // Try to connect to the port
                try (java.net.Socket socket = new java.net.Socket())
                {
                    socket.connect(new java.net.InetSocketAddress(host, port), 3000);
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        mStatusLabel.setText("Connection successful!");
                        mTestButton.setEnabled(true);
                    });
                }
            }
            catch(Exception e)
            {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    mStatusLabel.setText("Connection failed: " + e.getMessage());
                    mTestButton.setEnabled(true);
                });
            }
        });
    }

    private void addTuner()
    {
        String host = mHostTextField.getText().trim();
        int port = (Integer) mPortSpinner.getValue();
        String deviceName = mDeviceNameTextField.getText().trim();

        if(host.isEmpty())
        {
            JOptionPane.showMessageDialog(this,
                    "Please provide a host address",
                    "Host Required",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        if(deviceName.isEmpty())
        {
            JOptionPane.showMessageDialog(this,
                    "Please provide a device name",
                    "Device Name Required",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        mLog.info("Adding SDRconnect tuner - host [{}] port [{}] device [{}]",
            host, port, deviceName);

        try
        {
            SDRconnectTunerConfiguration config = SDRconnectTunerConfiguration.create(host, port, deviceName);
            mTunerConfigurationManager.addTunerConfiguration(config);

            ChannelizerType channelizerType = mUserPreferences.getTunerPreference().getChannelizerType();
            DiscoveredSDRconnectTuner discoveredTuner =
                new DiscoveredSDRconnectTuner(host, port, deviceName, channelizerType);
            discoveredTuner.setTunerConfiguration(config);
            mDiscoveredTunerModel.addDiscoveredTuner(discoveredTuner);

            // Start the tuner off the EDT so WebSocket setup doesn't block the dialog/UI.
            ThreadPool.CACHED.execute(discoveredTuner::start);

            dispose();
        }
        catch(Exception ex)
        {
            mLog.error("Error adding SDRconnect tuner", ex);
            JOptionPane.showMessageDialog(this,
                    "Error adding tuner: " + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
