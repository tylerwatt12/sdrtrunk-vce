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

package io.github.dsheirer.source.tuner.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dsheirer.gui.preference.tuner.RspDuoSelectionMode;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.properties.SystemProperties;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.mixer.MixerManager;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerFactory;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.MultiFrequencyTunerChannelSource;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrconnect.DiscoveredSDRconnectTuner;
import io.github.dsheirer.source.tuner.sdrconnect.SDRconnectTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.DiscoveredRspTuner;
import io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayException;
import io.github.dsheirer.source.tuner.sdrplay.api.SDRplay;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.ui.DiscoveredTunerModel;
import io.github.dsheirer.util.ThreadPool;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceList;
import org.usb4java.HotplugCallback;
import org.usb4java.HotplugCallbackHandle;
import org.usb4java.LibUsb;

/**
 * Tuner manager provides access to tuners using USB, recording, sound-card and system-daemon accessible devices. This
 * manager also supports hot-plug detection and black-listing of discovered tuners so that they can be used with other
 * software applications.
 */
public class TunerManager implements IDiscoveredTunerStatusListener
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerManager.class);
    private static final String SDRCONNECT_HEADLESS_PATH_PROPERTY = "sdrconnect.headless.path";
    private static final String SDRCONNECT_HEADLESS_AUTOSTART_PROPERTY = "sdrconnect.headless.autostart";
    private static final String SDRCONNECT_HEADLESS_START_DELAY_MS_PROPERTY = "sdrconnect.headless.start.delay.ms";
    private static final String DEFAULT_SDRCONNECT_HEADLESS_PATH = "/Applications/SDRconnect.app/Contents/MacOS/SDRconnect_headless";
    private static final int SDRCONNECT_HEADLESS_START_TIMEOUT_MS = 10000;
    private static final int SDRCONNECT_HEADLESS_START_RETRY_INTERVAL_MS = 250;
    private static final int DEFAULT_SDRCONNECT_HEADLESS_START_DELAY_MS = 15000;
    private final UserPreferences mUserPreferences;
    private final DiscoveredTunerModel mDiscoveredTunerModel;
    private final TunerConfigurationManager mTunerConfigurationManager;
    private final HotplugEventSupport mHotplugEventSupport = new HotplugEventSupport();
    private final Context mLibUsbApplicationContext = new Context();
    private final Map<Integer, Process> mManagedSDRconnectProcesses = new HashMap<>();
    private final Thread mManagedSDRconnectShutdownHook;
    private boolean mLibUsbInitialized = false;
    private SDRplay mSDRplay;

    /**
     * Constructs an instance
     * @param userPreferences for preferences
     */
    public TunerManager(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        mTunerConfigurationManager = new TunerConfigurationManager(userPreferences);
        mDiscoveredTunerModel = new DiscoveredTunerModel(mTunerConfigurationManager);
        mManagedSDRconnectShutdownHook = new Thread(this::stopManagedSDRconnectProcesses,
            "sdrconnect-headless-shutdown");
        Runtime.getRuntime().addShutdownHook(mManagedSDRconnectShutdownHook);
    }

    /**
     * Discovered tuner model
     */
    public DiscoveredTunerModel getDiscoveredTunerModel()
    {
        return mDiscoveredTunerModel;
    }

    /**
     * Tuner configuration manager
     */
    public TunerConfigurationManager getTunerConfigurationManager()
    {
        return mTunerConfigurationManager;
    }

    /**
     * Starts the tuner manager and loads all available tuners
     */
    public void start()
    {
        mLog.info("Discovering tuners ...");

        boolean libUsbAvailable = false;

        try
        {
            int api = LibUsb.getApiVersion();
            libUsbAvailable = true;
            mLog.info("LibUsb API Version: " + ((api >> 24) & 0xFF) + "." + ((api >> 16) & 0xFF) + "." + (api & 0xFFFF));
            mLog.info("LibUsb Version: " + LibUsb.getVersion());
        }
        catch(Exception e)
        {
            final String os = System.getProperty("os.name").toLowerCase();
            final String version = System.getProperty("os.version");

            //Special handling for MacOS Tahoe version 26 issue with LibUsb.
            if(os != null && os.startsWith("macos") && version != null && version.startsWith("26"))
            {
                mLog.warn("Unable to load libusb library for communication with USB tuner(s)");
                mLog.warn("Mac OS Tahoe [" + os + "] version [" + version + "] detected.  There is an issue");
                mLog.warn("with libusb for this OS version.  You might consider manually installing the latest");
                mLog.warn("version of the libusb library.  In a terminal window type:");
                mLog.warn("");
                mLog.warn("\t\tbrew install libusb --HEAD");
                mLog.warn("");
            }
            else
            {
                mLog.error("Error while loading libusb for os [" + os + "] version [" + version + "]", e);
            }
        }

        if(libUsbAvailable)
        {
            try
            {
                int status = LibUsb.init(mLibUsbApplicationContext);

                if(status == LibUsb.SUCCESS)
                {
                    mLibUsbInitialized = true;
                    //            LibUsb.setOption(mLibUsbApplicationContext, LibUsb.OPTION_LOG_LEVEL, LibUsb.LOG_LEVEL_DEBUG);
                    discoverUSBTuners();
                }
            }
            catch(Exception e)
            {
                mLog.error("Error initializing LibUsb and usb4java library", e);
            }

            if(mLibUsbInitialized)
            {
                mHotplugEventSupport.start();
            }

            discoverSdrPlayTuners();
        }

        discoverRecordingTuners();
        discoverSDRconnectTuners();
        autoDiscoverSDRconnect(); // Auto-detect SDRconnect if no saved config
    }

    /**
     * Stops or shuts down the tuner manager and releases all devices
     */
    public void stop()
    {
        //Stop all tuners
        mDiscoveredTunerModel.releaseDiscoveredTuners();
        stopManagedSDRconnectProcesses();

        //Shutdown SDRplay API instance
        if(mSDRplay != null)
        {
            mSDRplay.close();
            mSDRplay = null;
        }

        //Shutdown LibUsb
        if(mLibUsbInitialized)
        {
            mHotplugEventSupport.stop();
            LibUsb.exit(mLibUsbApplicationContext);
            mLibUsbInitialized = false;
        }

        try
        {
            Runtime.getRuntime().removeShutdownHook(mManagedSDRconnectShutdownHook);
        }
        catch(IllegalStateException ignored)
        {
            //JVM is already shutting down.
        }
    }

    /**
     * Requests to save the current state of the tuner configurations
     */
    public void saveConfigurations()
    {
        mTunerConfigurationManager.saveConfigurations();
    }

    /**
     * Discover or rediscovers USB tuners
     */
    private void discoverUSBTuners()
    {
        if(mLibUsbInitialized)
        {
            List<DiscoveredUSBTuner> discoveredUSBTuners = new ArrayList<>();

            try
            {
                DeviceList deviceList = new DeviceList();
                int deviceCount = LibUsb.getDeviceList(mLibUsbApplicationContext, deviceList);

                if(deviceCount >= 0)
                {
                    mLog.info("LibUsb - discovered [" + deviceCount + "] potential usb devices");

                    for(Device device: deviceList)
                    {
                        int bus = LibUsb.getBusNumber(device);
                        int port = LibUsb.getPortNumber(device);

                        if(port > 0)
                        {
                            DeviceDescriptor deviceDescriptor = new DeviceDescriptor();
                            int status = LibUsb.getDeviceDescriptor(device, deviceDescriptor);

                            if(status == LibUsb.SUCCESS)
                            {
                                TunerClass tunerClass = TunerClass.lookup(deviceDescriptor.idVendor(),
                                        deviceDescriptor.idProduct());

                                String portAddress = getPortAddress(device);

                                if(tunerClass.isSupportedUsbTuner())
                                {
                                    mLog.info("Discovered tuner at USB Bus [" + bus + "] Port [" + portAddress +
                                            "] Tuner Class [" + tunerClass + "]");
                                    ChannelizerType channelizerType = mUserPreferences.getTunerPreference().getChannelizerType();
                                    DiscoveredUSBTuner discoveredUSBTuner = new DiscoveredUSBTuner(tunerClass, bus,
                                            portAddress, channelizerType);
                                    discoveredUSBTuners.add(discoveredUSBTuner);
                                }
                            }
                            else
                            {
                                mLog.error("LibUsb - unable to get device descriptor for device on bus [" + bus +
                                        "] port [" + port + "] - status [" + status + "] - " + LibUsb.errorName(status));
                            }
                        }

                        //Unref the device - it will be rediscovered under the device context when it is started
                        LibUsb.unrefDevice(device);
                    }

                    LibUsb.freeDeviceList(deviceList, false);
                }
                //If the get device list operation generated error -99, turn on warning logging and try device list
                // again to see what's causing it.
                else if(deviceCount == LibUsb.ERROR_OTHER)
                {
                    try
                    {
                        mLog.info("LibUsb reported error -99 during get device list - increasing logging level and retrying");
                        LibUsb.setOption(mLibUsbApplicationContext, LibUsb.OPTION_LOG_LEVEL, LibUsb.LOG_LEVEL_WARNING);
                        deviceCount = LibUsb.getDeviceList(mLibUsbApplicationContext, deviceList);
                        if(deviceCount >=0)
                        {
                            mLog.info("LibUsb - get device list count [" + deviceCount + "] during retry");
                            LibUsb.freeDeviceList(deviceList, false);
                        }
                        else
                        {
                            mLog.info("LibUsb - get device list reported error [" + deviceCount + "/" +
                                    LibUsb.errorName(deviceCount) + "] during retry");
                        }
                        LibUsb.setOption(mLibUsbApplicationContext, LibUsb.OPTION_LOG_LEVEL, LibUsb.LOG_LEVEL_NONE);
                        mLog.info("LibUse - get device list reattempt complete - if additional logging was generated please notify developer");
                    }
                    catch(Exception e)
                    {
                        mLog.info("LibUsb error during get device list reattempt", e);
                    }
                }
                else
                {
                    mLog.error("LibUsb - error [" + deviceCount + "/" + LibUsb.errorName(deviceCount) + "] during get device list");
                }
            }
            catch(Exception e)
            {
                mLog.error("LibUsb - error during USB device discovery", e);
            }

            for(DiscoveredUSBTuner discoveredUSBTuner: discoveredUSBTuners)
            {
                addUsbTuner(discoveredUSBTuner);
            }
        }
    }

    /**
     * Determines if the USB device is a supported tuner and add if it has not already been added/discovered.
     * @param discoveredUSBTuner to add
     */
    private void addUsbTuner(DiscoveredUSBTuner discoveredUSBTuner)
    {
        if(!mDiscoveredTunerModel.hasUsbTuner(discoveredUSBTuner.getBus(), discoveredUSBTuner.getPortAddress()))
        {
            startAndConfigureTuner(discoveredUSBTuner);
        }
    }

    /**
     * Stops and removes the USB tuner if it is currently discovered.
     * @param bus usb for the tuner
     * @param portAddress usb for the tuner
     */
    private DiscoveredTuner removeUsbTuner(int bus, String portAddress)
    {
        return mDiscoveredTunerModel.removeUsbTuner(bus, portAddress);
    }

    /**
     * Starts, configures and adds the tuner to the tuner model.
     * @param discoveredTuner to add and configure
     */
    private void startAndConfigureTuner(DiscoveredTuner discoveredTuner)
    {
        discoveredTuner.addTunerStatusListener(this);

        //Set the tuner to disabled if the user has previously blacklisted the tuner
        if(mTunerConfigurationManager.isDisabled(discoveredTuner))
        {
            discoveredTuner.setEnabled(false);
            mLog.info("Tuner: " + discoveredTuner + " - Added / Disabled");
        }
        else
        {
            mLog.info("Tuner: " + discoveredTuner + " - Added / Starting ...");
            //Attempt to start the discovered tuner and determine the tuner type
            tunerStatusUpdated(discoveredTuner, TunerStatus.DISABLED, TunerStatus.ENABLED);
        }

        mDiscoveredTunerModel.addDiscoveredTuner(discoveredTuner);
    }

    /**
     * Discover SDRPlay RSP tuners
     */
    private void discoverSdrPlayTuners()
    {
        ChannelizerType channelizerType = mUserPreferences.getTunerPreference().getChannelizerType();
        RspDuoSelectionMode duoSelectionMode = mUserPreferences.getTunerPreference().getRspDuoTunerMode();

        //Note: we have to keep this first API instance open while we use any RSP tuners, otherwise the additional API
        //instance(s) used by the individual tuners become unresponsive.  Note sure why.

        try
        {
            mSDRplay = new SDRplay();
        }
        catch(SDRPlayException se)
        {
            mLog.error("Couldn't load SDRPlay API");
            mSDRplay = null;
        }

        if(mSDRplay != null && mSDRplay.isAvailable())
        {
            try
            {
                List<DeviceInfo> deviceInfos = mSDRplay.getDeviceInfos();

                mLog.info("Discovered [" + deviceInfos.size() + "] RSP devices from SDRplay API");

                if(deviceInfos.isEmpty())
                {
                    mSDRplay.close();
                    mSDRplay = null;
                    return;
                }

                for(DeviceInfo deviceInfo: deviceInfos)
                {
                    List<DiscoveredRspTuner> tuners = TunerFactory.getRspTuners(deviceInfo, channelizerType, duoSelectionMode);

                    for(DiscoveredRspTuner tuner: tuners)
                    {
                        startAndConfigureTuner(tuner);
                    }
                }
            }
            catch(SDRPlayException se)
            {
                mLog.info("Unable to get list of devices from SDRplay API");
            }
        }
        else
        {
            if(mSDRplay != null)
            {
                mSDRplay.close();
            }

            mSDRplay = null;
        }
    }

    /**
     * Discover recording based tuners
     */
    private void discoverRecordingTuners()
    {
        List<TunerConfiguration> tunerConfigurations = getTunerConfigurationManager().getTunerConfigurations(TunerType.RECORDING);

        if(tunerConfigurations.size() > 0)
        {
            mLog.info("Discovered [" + tunerConfigurations.size() + "] recording tuners");
        }

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof RecordingTunerConfiguration recordingTunerConfiguration)
            {
                DiscoveredRecordingTuner discoveredRecordingTuner =
                        new DiscoveredRecordingTuner(mUserPreferences, recordingTunerConfiguration);

                discoveredRecordingTuner.addTunerStatusListener(this);
                discoveredRecordingTuner.setEnabled(false);
                mLog.info("Tuner Added: " + discoveredRecordingTuner);
                mDiscoveredTunerModel.addDiscoveredTuner(discoveredRecordingTuner);
            }
        }
    }

    /**
     * Discover SDRconnect based tuners - connects to SDRplay devices via SDRconnect WebSocket API
     */
    private void discoverSDRconnectTuners()
    {
        ChannelizerType channelizerType = mUserPreferences.getTunerPreference().getChannelizerType();
        List<TunerConfiguration> tunerConfigurations = getTunerConfigurationManager().getTunerConfigurations(TunerType.SDRCONNECT);

        if(tunerConfigurations.size() > 0)
        {
            mLog.info("Discovered [" + tunerConfigurations.size() + "] SDRconnect tuners from saved configurations");
        }

        prelaunchConfiguredSDRconnectProcesses(tunerConfigurations);

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig)
            {
                boolean available = ensureSDRconnectAvailable(sdrconnectConfig.getHost(), sdrconnectConfig.getPort());
                DiscoveredSDRconnectTuner discoveredTuner =
                        new DiscoveredSDRconnectTuner(sdrconnectConfig.getHost(), sdrconnectConfig.getPort(),
                                sdrconnectConfig.getDeviceName(), channelizerType);

                discoveredTuner.setTunerConfiguration(sdrconnectConfig);
                discoveredTuner.addTunerStatusListener(this);

                // Probe SDRconnect to see if it's available - if so, auto-start
                if(available)
                {
                    mLog.info("SDRconnect detected at {}:{} - auto-starting tuner", sdrconnectConfig.getHost(), sdrconnectConfig.getPort());
                    discoveredTuner.setEnabled(true);
                }
                else
                {
                    mLog.info("SDRconnect not available at {}:{} - tuner disabled", sdrconnectConfig.getHost(), sdrconnectConfig.getPort());
                    discoveredTuner.setEnabled(false);
                }

                mLog.info("SDRconnect Tuner Added: " + discoveredTuner);
                mDiscoveredTunerModel.addDiscoveredTuner(discoveredTuner);

                if(available)
                {
                    ThreadPool.CACHED.execute(discoveredTuner::start);
                }
            }
        }
    }

    /**
     * Probe SDRconnect to check if it's available
     * @param host SDRconnect host
     * @param port SDRconnect port
     * @return true if SDRconnect responds
     */
    private boolean probeSDRconnect(String host, int port)
    {
        try (java.net.Socket socket = new java.net.Socket())
        {
            socket.connect(new java.net.InetSocketAddress(host, port), 2000);
            return true;
        }
        catch(Exception e)
        {
            return false;
        }
    }

    /**
     * Ensures SDRconnect is available on the specified host/port. For local loopback addresses, this can launch
     * an SDRconnect_headless instance when the port is not already available.
     */
    private boolean ensureSDRconnectAvailable(String host, int port)
    {
        if(probeSDRconnect(host, port))
        {
            return true;
        }

        if(!isLocalSDRconnectHost(host))
        {
            return false;
        }

        return launchManagedSDRconnectProcess(port);
    }

    /**
     * Launches any missing local SDRconnect headless processes for configured ports, then waits once for startup.
     */
    private void prelaunchConfiguredSDRconnectProcesses(List<TunerConfiguration> tunerConfigurations)
    {
        if(!SystemProperties.getInstance().get(SDRCONNECT_HEADLESS_AUTOSTART_PROPERTY, true))
        {
            return;
        }

        Map<Integer, String> launchedPorts = new HashMap<>();

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig &&
                isLocalSDRconnectHost(sdrconnectConfig.getHost()) &&
                !probeSDRconnect(sdrconnectConfig.getHost(), sdrconnectConfig.getPort()))
            {
                if(launchManagedSDRconnectProcess(sdrconnectConfig.getPort()))
                {
                    launchedPorts.put(sdrconnectConfig.getPort(), sdrconnectConfig.getHost());
                }
            }
        }

        if(!launchedPorts.isEmpty())
        {
            int timeoutMs = SystemProperties.getInstance().get(SDRCONNECT_HEADLESS_START_DELAY_MS_PROPERTY,
                DEFAULT_SDRCONNECT_HEADLESS_START_DELAY_MS);
            mLog.info("Waiting up to {} ms for SDRconnect headless readiness on port(s) {}", timeoutMs,
                launchedPorts.keySet());

            List<CompletableFuture<Boolean>> readinessChecks = new ArrayList<>();

            for(Map.Entry<Integer, String> entry : launchedPorts.entrySet())
            {
                readinessChecks.add(CompletableFuture.supplyAsync(
                    () -> waitForReadySDRconnect(entry.getValue(), entry.getKey(), timeoutMs),
                    ThreadPool.CACHED));
            }

            for(CompletableFuture<Boolean> readinessCheck : readinessChecks)
            {
                readinessCheck.join();
            }
        }
    }

    /**
     * Indicates if the host is a local loopback address we can manage.
     */
    private boolean isLocalSDRconnectHost(String host)
    {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    /**
     * Launches an SDRconnect_headless process for the specified websocket port if one is not already managed.
     */
    private boolean launchManagedSDRconnectProcess(int port)
    {
        if(!SystemProperties.getInstance().get(SDRCONNECT_HEADLESS_AUTOSTART_PROPERTY, true))
        {
            return false;
        }

        Process existing = mManagedSDRconnectProcesses.get(port);

        if(existing != null)
        {
            if(existing.isAlive())
            {
                return true;
            }

            mManagedSDRconnectProcesses.remove(port);
        }

        Path executable = Path.of(SystemProperties.getInstance().get(SDRCONNECT_HEADLESS_PATH_PROPERTY,
            DEFAULT_SDRCONNECT_HEADLESS_PATH));

        if(!Files.isExecutable(executable))
        {
            mLog.warn("SDRconnect headless executable not found or not executable at [{}]", executable);
            return false;
        }

        try
        {
            File logFile = mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog()
                .resolve("sdrconnect_headless_" + port + ".log").toFile();

            ProcessBuilder processBuilder = new ProcessBuilder(
                executable.toString(),
                "--websocket_port=" + port);
            processBuilder.directory(executable.getParent().toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

            Process process = processBuilder.start();
            mManagedSDRconnectProcesses.put(port, process);
            mLog.info("Started SDRconnect headless process on port {} using [{}]", port, executable);
            return true;
        }
        catch(IOException ioe)
        {
            mLog.error("Unable to start SDRconnect headless on port {}", port, ioe);
        }

        return false;
    }

    /**
     * Waits for an SDRconnect websocket port to become ready for use.
     */
    private boolean waitForReadySDRconnect(String host, int port, int timeoutMs)
    {
        int effectiveTimeoutMs = Math.max(timeoutMs, SDRCONNECT_HEADLESS_START_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + effectiveTimeoutMs;

        while(System.currentTimeMillis() < deadline)
        {
            Process managed = mManagedSDRconnectProcesses.get(port);

            if(managed != null && !managed.isAlive())
            {
                mLog.warn("SDRconnect headless on port {} exited early with code {}. See log [{}]",
                    port, managed.exitValue(),
                    mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog()
                        .resolve("sdrconnect_headless_" + port + ".log"));
                mManagedSDRconnectProcesses.remove(port);
                return false;
            }

            if(isSDRconnectReady(host, port))
            {
                mLog.info("SDRconnect headless on port {} is ready", port);
                return true;
            }

            try
            {
                Thread.sleep(SDRCONNECT_HEADLESS_START_RETRY_INTERVAL_MS);
            }
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        mLog.warn("Timed out waiting for SDRconnect headless on port {} readiness after {} ms", port,
            effectiveTimeoutMs);
        return false;
    }

    /**
     * Indicates if SDRconnect is ready for use and no longer returning placeholder startup values.
     */
    private boolean isSDRconnectReady(String host, int port)
    {
        if(!probeSDRconnect(host, port))
        {
            return false;
        }

        SDRconnectReadyProbe probe = new SDRconnectReadyProbe();
        WebSocket webSocket = null;

        try
        {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://" + host + ":" + port), probe);
            webSocket = future.get(2, TimeUnit.SECONDS);
            boolean ready = probe.awaitReady(2, TimeUnit.SECONDS);
            return ready && probe.isReady();
        }
        catch(Exception e)
        {
            return false;
        }
        finally
        {
            if(webSocket != null)
            {
                try
                {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "probe");
                }
                catch(Exception ignored)
                {
                }
            }
        }
    }

    /**
     * Stops any SDRconnect_headless processes that were launched by this application.
     */
    private void stopManagedSDRconnectProcesses()
    {
        Map<Integer, Process> managedProcesses = new HashMap<>(mManagedSDRconnectProcesses);
        mManagedSDRconnectProcesses.clear();

        for(Map.Entry<Integer, Process> entry : managedProcesses.entrySet())
        {
            Process process = entry.getValue();

            if(process != null && process.isAlive())
            {
                interruptProcessTree(process, entry.getKey());

                if(process.isAlive())
                {
                    process.destroy();
                }

                if(process.isAlive())
                {
                    try
                    {
                        process.waitFor(5, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                    }
                }

                if(process.isAlive())
                {
                    process.destroyForcibly();
                }

                if(process.isAlive())
                {
                    try
                    {
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * Sends SIGINT to a launched process and any descendant processes it may have spawned.
     */
    private void interruptProcessTree(Process process, int port)
    {
        LinkedHashSet<Long> pids = new LinkedHashSet<>();
        ProcessHandle handle = process.toHandle();
        handle.descendants().map(ProcessHandle::pid).forEach(pids::add);
        pids.add(handle.pid());

        for(Long pid : pids)
        {
            try
            {
                new ProcessBuilder("/bin/kill", "-INT", Long.toString(pid)).start().waitFor(2, TimeUnit.SECONDS);
            }
            catch(Exception e)
            {
                mLog.warn("Unable to interrupt SDRconnect headless process [{}] on port {}", pid, port, e);
            }
        }
    }

    /**
     * Minimal readiness probe for SDRconnect headless startup.
     */
    private static class SDRconnectReadyProbe implements WebSocket.Listener
    {
        private final CountDownLatch mReady = new CountDownLatch(1);
        private final StringBuilder mPartialText = new StringBuilder();
        private volatile String mValidDevices = "";
        private volatile String mActiveDevice = "";

        @Override
        public void onOpen(WebSocket webSocket)
        {
            webSocket.sendText("{\"event_type\":\"get_property\",\"property\":\"valid_devices\"}", true);
            webSocket.sendText("{\"event_type\":\"get_property\",\"property\":\"active_device\"}", true);
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            mPartialText.append(data);

            if(last)
            {
                try
                {
                    JsonObject message = JsonParser.parseString(mPartialText.toString()).getAsJsonObject();
                    String eventType = message.has("event_type") ? message.get("event_type").getAsString() : "";

                    if("property_changed".equals(eventType) || "get_property_response".equals(eventType))
                    {
                        String property = message.has("property") ? message.get("property").getAsString() : "";
                        String value = message.has("value") ? message.get("value").getAsString() : "";

                        if("valid_devices".equals(property))
                        {
                            mValidDevices = value;
                        }
                        else if("active_device".equals(property))
                        {
                            mActiveDevice = value;
                        }

                        if(isReady())
                        {
                            mReady.countDown();
                        }
                    }
                }
                catch(Exception ignored)
                {
                }

                mPartialText.setLength(0);
            }

            webSocket.request(1);
            return null;
        }

        private boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mReady.await(timeout, unit);
        }

        private boolean isReady()
        {
            return isReadyValue(mValidDevices) && isReadyValue(mActiveDevice);
        }

        private static boolean isReadyValue(String value)
        {
            return value != null && !value.isBlank() && !"Refreshing...".equalsIgnoreCase(value.trim());
        }
    }

    /**
     * Auto-discover SDRconnect on default port if no saved configuration exists
     */
    private void autoDiscoverSDRconnect()
    {
        // Check if we already have an SDRconnect tuner configured
        List<TunerConfiguration> existing = getTunerConfigurationManager().getTunerConfigurations(TunerType.SDRCONNECT);
        if(!existing.isEmpty())
        {
            return; // Already have saved config, don't auto-discover
        }

        // Probe default SDRconnect port
        String defaultHost = "127.0.0.1";
        int defaultPort = 5454;

        if(probeSDRconnect(defaultHost, defaultPort))
        {
            mLog.info("Auto-discovered SDRconnect at {}:{}", defaultHost, defaultPort);

            ChannelizerType channelizerType = mUserPreferences.getTunerPreference().getChannelizerType();
            SDRconnectTunerConfiguration config = SDRconnectTunerConfiguration.create(defaultHost, defaultPort);
            getTunerConfigurationManager().addTunerConfiguration(config);

            DiscoveredSDRconnectTuner discoveredTuner =
                    new DiscoveredSDRconnectTuner(defaultHost, defaultPort, channelizerType);
            discoveredTuner.setTunerConfiguration(config);
            discoveredTuner.addTunerStatusListener(this);
            discoveredTuner.setEnabled(true); // Auto-enable since we detected it

            mLog.info("SDRconnect auto-discovered and enabled: " + discoveredTuner);
            mDiscoveredTunerModel.addDiscoveredTuner(discoveredTuner);
        }
    }

    /**
     * Handles tuner status change events.  Events are sent to the tuner configuration manager so that it can save
     * configuration updates and events are also monitored to detect when a user changes the tuner state of a tuner
     * so that we can auto-start the tuner and apply a tuner configuration.
     *
     * @param discoveredTuner that has a status change.
     * @param previous tuner status
     * @param current tuner status
     */
    @Override
    public void tunerStatusUpdated(DiscoveredTuner discoveredTuner, TunerStatus previous, TunerStatus current)
    {
        if(current == TunerStatus.ENABLED)
        {
            discoveredTuner.start();
        }

        //Special handling for RSPduo to auto-update enabled state for slave device when configured for master/slave operation
        if(discoveredTuner instanceof DiscoveredRspDuoTuner1 rspDuoTuner1 && rspDuoTuner1.getDeviceInfo().getDeviceSelectionMode().isMasterMode())
        {
            String id = rspDuoTuner1.getId();
            id = id.replace(DiscoveredRspDuoTuner1.RSP_DUO_ID_PREFIX + "1", DiscoveredRspDuoTuner1.RSP_DUO_ID_PREFIX + "2");
            DiscoveredTuner rspDuoTuner2 = getDiscoveredTunerModel().getDiscoveredTuner(id);

            if(rspDuoTuner2 != null)
            {
                if(previous == TunerStatus.ENABLED && current == TunerStatus.DISABLED)
                {
                    rspDuoTuner2.setEnabled(false);
                }
                else if(previous == TunerStatus.DISABLED && current == TunerStatus.ENABLED)
                {
                    rspDuoTuner2.setEnabled(true);
                    rspDuoTuner2.start();
                }
            }

            //Notify tuner configuration manager to apply tuner configurations & update disabled tuner states
            mTunerConfigurationManager.tunerStatusUpdated(rspDuoTuner1, previous, current);

            if(rspDuoTuner2 != null)
            {
                mTunerConfigurationManager.tunerStatusUpdated(rspDuoTuner2, previous, current);
            }
        }
        else
        {
            //Notify tuner configuration manager to apply tuner configuration
            mTunerConfigurationManager.tunerStatusUpdated(discoveredTuner, previous, current);
        }
    }

    /**
     * Find a tuner that matches the name argument
     *
     * @param preferredTunerName of the tuner
     * @return named tuner or null
     */
    public DiscoveredTuner getDiscoveredTuner(String preferredTunerName)
    {
        if(preferredTunerName != null)
        {
            for(DiscoveredTuner discoveredTuner : getDiscoveredTunerModel().getAvailableTuners())
            {
                if(discoveredTuner.isAvailable() &&
                   discoveredTuner.getTuner().getPreferredName().equalsIgnoreCase(preferredTunerName))
                {
                    return discoveredTuner;
                }
            }
        }

        return null;
    }

    /**
     * Sorted list of preferred tuner names
     */
    public List<String> getPreferredTunerNames()
    {
        List<String> preferredNames = new ArrayList<>();
        List<DiscoveredTuner> availableTuners = getAvailableTuners();

        for(DiscoveredTuner discoveredTuner: availableTuners)
        {
            String preferredName = discoveredTuner.getTuner().getPreferredName();

            if(!preferredNames.contains(preferredName))
            {
                preferredNames.add(preferredName);
            }
        }

        Collections.sort(preferredNames);

        return preferredNames;
    }

    /**
     * Tuners currently available for use
     */
    public List<DiscoveredTuner> getAvailableTuners()
    {
        return mDiscoveredTunerModel.getAvailableTuners();
    }

    /**
     * Obtain a channel (sample stream) source from one of the tuners.
     * @param config for the source
     * @param channelSpecification required by the decoder
     * @param thread name to apply for the channel's scheduled executor
     * @return a configured source or null if there aren't any tuners that can provide the source.
     * @throws SourceException if there is an issue.
     */
    public Source getSource(SourceConfiguration config, ChannelSpecification channelSpecification,
                            String threadName) throws SourceException
    {
        Source retVal = null;

        switch(config.getSourceType())
        {
            case MIXER:
                retVal = MixerManager.getSource(config);
                break;
            case TUNER:
                if(config instanceof SourceConfigTuner)
                {
                    SourceConfigTuner sourceConfigTuner = (SourceConfigTuner)config;
                    TunerChannel tunerChannel = sourceConfigTuner.getTunerChannel(channelSpecification.getBandwidth());
                    String preferredTuner = sourceConfigTuner.getPreferredTuner();
                    retVal = getSource(tunerChannel, channelSpecification, preferredTuner, threadName +
                            " " + tunerChannel.getFrequency());
                }
                break;
            case TUNER_MULTIPLE_FREQUENCIES:
                if(config instanceof SourceConfigTunerMultipleFrequency)
                {
                    SourceConfigTunerMultipleFrequency sourceConfigTuner = (SourceConfigTunerMultipleFrequency)config;
                    TunerChannel tunerChannel = sourceConfigTuner.getTunerChannel(channelSpecification.getBandwidth());
                    String preferredTuner = sourceConfigTuner.getPreferredTuner();

                    Source source = getSource(tunerChannel, channelSpecification, preferredTuner, threadName);

                    if(source instanceof TunerChannelSource)
                    {
                        retVal = new MultiFrequencyTunerChannelSource(this, (TunerChannelSource)source,
                                sourceConfigTuner.getFrequencies(), channelSpecification,
                                sourceConfigTuner.getPreferredTuner(), threadName + " MULTI FREQ");
                    }
                }
                break;
            default:
                break;
        }

        return retVal;
    }

    /**
     * Iterates current available tuners to get a tuner channel source for the specified frequency and bandwidth
     * @param tunerChannel requested
     * @param channelSpecification from the decoder
     * @param preferredTuner name
     * @param threadName to assign to the channel's thread pool
     *
     * Returns null if no tuner can source the channel
     */
    public Source getSource(TunerChannel tunerChannel, ChannelSpecification channelSpecification, String preferredTuner,
                            String threadName)
    {
        TunerChannelSource source = null;

        if(tunerChannel != null && channelSpecification != null)
        {
            DiscoveredTuner discoveredTuner;

            if(preferredTuner != null)
            {
                discoveredTuner = getDiscoveredTuner(preferredTuner);

                if(discoveredTuner != null)
                {
                    try
                    {
                        source = discoveredTuner.getTuner().getChannelSourceManager().getSource(tunerChannel,
                                channelSpecification, threadName);

                        if(source != null)
                        {
                            return source;
                        }
                    }
                    catch(Exception e)
                    {
                        //Fall through to logger below
                    }
                }

                mLog.info("Unable to source channel [" + tunerChannel.getFrequency() + "] from preferred tuner [" +
                        preferredTuner + "] - searching for another tuner");
            }

            Iterator<DiscoveredTuner> it = mDiscoveredTunerModel.getAvailableTuners().iterator();

            while(it.hasNext() && source == null)
            {
                discoveredTuner = it.next();

                if(discoveredTuner.hasTuner())
                {
                    try
                    {
                        source = discoveredTuner.getTuner().getChannelSourceManager().getSource(tunerChannel,
                                channelSpecification, threadName);
                    }
                    catch(Exception e)
                    {
                        mLog.error("Error obtaining channel from tuner [" + discoveredTuner.getTuner().getPreferredName() + "]", e);
                    }
                }
            }
        }

        return source;
    }

    /**
     * Creates a dotted-notation string representing the port numbers for the device from the root hub through any
     * intermediate hubs.
     * @param device to inspect
     * @return formatted address
     */
    public static String getPortAddress(Device device)
    {
        ByteBuffer portNumberArray = ByteBuffer.allocateDirect(7);  //7 ports maximum.
        int portCount = LibUsb.getPortNumbers(device, portNumberArray);
        portNumberArray.rewind();

        StringBuilder sb = new StringBuilder();

        for(int x = 0; x < portCount; x++)
        {
            if(x > 0)
            {
                sb.append(".");
            }

            sb.append(portNumberArray.get());
        }

        return sb.toString();
    }

    /**
     * USB hotplug event listener to register, unregister and detect when USB devices are added or removed.
     *
     * Note: hotplug is not supported on all platforms.
     */
    public class HotplugEventSupport implements HotplugCallback
    {
        private static final int HOTPLUG_CONTINUE_EVENT_SUPPORT = 0;
        private HotplugCallbackHandle mHotplugCallbackHandle;
        private ScheduledFuture<?> mEventProcessorFuture;

        /**
         * LibUsb hotplug event notification
         * @param context used for hotplug event registration
         * @param device that arrived or left
         * @param event value, arrived event or left event
         * @param userData not used
         * @return
         */
        @Override
        public int processEvent(Context context, Device device, int event, Object userData)
        {
            int bus = LibUsb.getBusNumber(device);
            int port = LibUsb.getPortNumber(device);

            if(port > 0)
            {
                String portAddress = getPortAddress(device);

                switch(event)
                {
                    case LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED:
                        DeviceDescriptor deviceDescriptor = new DeviceDescriptor();
                        int status = LibUsb.getDeviceDescriptor(device, deviceDescriptor);

                        if(status == LibUsb.SUCCESS)
                        {
                            TunerClass tunerClass = TunerClass.lookup(deviceDescriptor.idVendor(), deviceDescriptor.idProduct());

                            if(tunerClass.isSupportedUsbTuner())
                            {
                                mLog.info("Tuner plug-in detected at USB Bus [" + bus + "] Port [" + port +
                                        "] Tuner Class [" + tunerClass + "]");
                                ChannelizerType channelizerType = mUserPreferences.getTunerPreference().getChannelizerType();
                                DiscoveredUSBTuner discoveredUSBTuner = new DiscoveredUSBTuner(tunerClass, bus,
                                        portAddress, channelizerType);

                                if(tunerClass.isFuncubeTuner())
                                {
                                    //Funcube tuners take a few moments to init the sound card interface.  Delay adding
                                    //the tuner so that it can be started correctly.
                                    ThreadPool.SCHEDULED.schedule(() ->
                                    {
                                        addUsbTuner(discoveredUSBTuner);
                                    }, 2, TimeUnit.SECONDS);
                                }
                                else
                                {
                                    addUsbTuner(discoveredUSBTuner);
                                }
                            }
                        }
                        break;
                    case LibUsb.HOTPLUG_EVENT_DEVICE_LEFT:
                        DiscoveredTuner removed = removeUsbTuner(bus, portAddress);

                        if(removed != null)
                        {
                            mLog.info("Tuner Unplugged: " + removed.getId());
                        }
                        break;
                }
            }

            return HOTPLUG_CONTINUE_EVENT_SUPPORT;
        }

        /**
         * Registers for hotplug event notifications, if the capability is supported on this platform and starts an
         * event processing timer to process hotplug events.
         */
        public void start()
        {
            if(LibUsb.hasCapability(LibUsb.CAP_HAS_HOTPLUG))
            {
                mHotplugCallbackHandle = new HotplugCallbackHandle();
                int events = LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED | LibUsb.HOTPLUG_EVENT_DEVICE_LEFT;
                int flags = LibUsb.HOTPLUG_ENUMERATE;
                int vendorId = LibUsb.HOTPLUG_MATCH_ANY;
                int productId = LibUsb.HOTPLUG_MATCH_ANY;
                int deviceClass = LibUsb.HOTPLUG_MATCH_ANY;

                mLog.info("LibUsb - starting HotPlug detection");
                int status = LibUsb.hotplugRegisterCallback(mLibUsbApplicationContext, events, flags, vendorId,
                        productId, deviceClass, this, "sdrtrunk hotplug support", mHotplugCallbackHandle);

                if(status == LibUsb.SUCCESS)
                {
                    Runnable eventHandler = () -> LibUsb.handleEvents(mLibUsbApplicationContext);
                    mEventProcessorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(eventHandler,
                            0, 1, TimeUnit.SECONDS);
                }
                else
                {
                    mHotplugCallbackHandle = null;
                    mLog.info("LibUsb - unable to register device hotplug listener - " + LibUsb.errorName(status));
                }
            }
            else
            {
                mLog.info("LibUsb Hotplug event notification Is Not Supported on this platform.");
            }
        }

        /**
         * Unregisters from hotplug event notifications and stops the event processing timer
         */
        public void stop()
        {
            if(mHotplugCallbackHandle != null)
            {
                LibUsb.hotplugDeregisterCallback(mLibUsbApplicationContext, mHotplugCallbackHandle);
                mHotplugCallbackHandle = null;
            }

            if(mEventProcessorFuture != null)
            {
                mEventProcessorFuture.cancel(true);
                mEventProcessorFuture = null;
            }
        }
    }
}
