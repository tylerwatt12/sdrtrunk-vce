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

import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.preference.source.RspDuoSelectionMode;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerFactory;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.MultiFrequencyTunerChannelSource;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationContext;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationDecision;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationDiagnosticService;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationKnownSite;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.DiscoveredRspTuner;
import io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayException;
import io.github.dsheirer.source.tuner.sdrplay.api.SDRplay;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Tuner manager provides access to tuners using USB, recording, and system-daemon accessible devices. This manager
 * also supports hot-plug detection and black-listing of discovered tuners so that they can be used with other
 * software applications.
 */
public class TunerManager implements IDiscoveredTunerStatusListener
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerManager.class);
    private static final List<PolyphaseChannelSourceManager.AllocationMode> CHANNEL_ALLOCATION_STAGES = List.of(
        PolyphaseChannelSourceManager.AllocationMode.CURRENT_CENTER,
        PolyphaseChannelSourceManager.AllocationMode.ALLOW_IDLE_RETUNE,
        PolyphaseChannelSourceManager.AllocationMode.ALLOW_BUSY_RETUNE);
    public static final int USB_RESCAN_UNAVAILABLE = -1;
    public static final int USB_RESCAN_FAILED = -2;
    private final UserPreferences mUserPreferences;
    private final DiscoveredTunerRegistry mDiscoveredTunerRegistry;
    private final TunerConfigurationManager mTunerConfigurationManager;
    private final HotplugEventSupport mHotplugEventSupport = new HotplugEventSupport();
    private final Context mLibUsbApplicationContext = new Context();
    private final Object mUsbAttachLock = new Object();
    private final Object mUsbModelLock = new Object();
    private final Object mUsbRescanLock = new Object();
    private final ExecutorService mUsbRescanExecutor = createUsbRescanExecutor();
    private CompletableFuture<Integer> mUsbRescan;
    private boolean mUsbRescanClosed;
    private volatile boolean mLibUsbInitialized = false;
    private volatile boolean mStopping;
    private final AtomicLong mChannelAllocationRequests = new AtomicLong();
    private final AtomicLong mChannelAllocationSuccesses = new AtomicLong();
    private final AtomicLong mChannelAllocationFailures = new AtomicLong();
    private final TunerAllocationDiagnosticService mAllocationDiagnostics = new TunerAllocationDiagnosticService();
    private final TunerFrequencyKnowledge mFrequencyKnowledge = new TunerFrequencyKnowledge();
    private final AtomicBoolean mCoverageRepairQueued = new AtomicBoolean();
    private final AtomicLong mCoverageRepairRevision = new AtomicLong();
    private final ScheduledExecutorService mCoverageRepairExecutor = Executors.newSingleThreadScheduledExecutor(task ->
    {
        Thread thread = new Thread(task, "sdrtrunk idle tuner coverage");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private SDRplay mSDRplay;

    /**
     * Constructs an instance
     * @param userPreferences for preferences
     */
    public TunerManager(UserPreferences userPreferences)
    {
        this(userPreferences, new TunerConfigurationManager());
    }

    /**
     * Constructs an instance with an injected tuner-configuration manager for deterministic allocation tests.
     */
    TunerManager(UserPreferences userPreferences, TunerConfigurationManager tunerConfigurationManager)
    {
        mUserPreferences = userPreferences;
        mTunerConfigurationManager = Objects.requireNonNull(tunerConfigurationManager);
        mDiscoveredTunerRegistry = new DiscoveredTunerRegistry(mTunerConfigurationManager);
    }

    /** Receiver-owned tuner inventory, independent of the desktop UI. */
    public DiscoveredTunerRegistry getDiscoveredTunerRegistry()
    {
        return mDiscoveredTunerRegistry;
    }

    /**
     * Tuner configuration manager
     */
    public TunerConfigurationManager getTunerConfigurationManager()
    {
        return mTunerConfigurationManager;
    }

    public TunerAllocationDiagnosticService getTunerAllocationDiagnosticService() { return mAllocationDiagnostics; }

    /** Observer/maintenance-thread projection, never used to copy the catalog on decoder callbacks. */
    public List<TunerAllocationKnownSite> getKnownFrequencySites() { return mFrequencyKnowledge.snapshot(); }

    public void observeKnownFrequency(TunerAllocationContext context, long frequencyHz, int bandwidthHz)
    {
        if(mFrequencyKnowledge.observe(context, frequencyHz, bandwidthHz)) scheduleCoverageRepair();
    }

    /** Registers a locally managed I/Q recording as an initially disabled debugging tuner. */
    public synchronized void addRecordingTuner(Path validatedFile, long centerFrequencyHz)
    {
        Objects.requireNonNull(validatedFile);
        if(centerFrequencyHz <= 0 || centerFrequencyHz > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Recording center frequency is out of range");
        }
        Path file = validatedFile.toAbsolutePath().normalize();
        if(mDiscoveredTunerRegistry.find(file.toString()) != null)
        {
            throw new IllegalArgumentException("A tuner already uses this recording");
        }
        RecordingTunerConfiguration configuration = RecordingTunerConfiguration.createWithUniqueId();
        configuration.setFrequency(centerFrequencyHz);
        configuration.setPath(file.toString());
        DiscoveredRecordingTuner discovered = new DiscoveredRecordingTuner(configuration);
        mTunerConfigurationManager.addTunerConfiguration(configuration);
        discovered.addTunerStatusListener(this);
        mDiscoveredTunerRegistry.add(discovered);
    }

    /** Removes a registered recording tuner, its saved state, and no files. Never interrupts an active channel. */
    public RecordingTunerRemovalResult removeRecordingTuner(DiscoveredTuner tuner)
    {
        if(tuner == null || !mDiscoveredTunerRegistry.contains(tuner))
        {
            return RecordingTunerRemovalResult.NOT_FOUND;
        }
        if(!(tuner instanceof DiscoveredRecordingTuner recording))
        {
            return RecordingTunerRemovalResult.NOT_RECORDING;
        }
        if(!recording.tryAcquireForAllocation())
        {
            return RecordingTunerRemovalResult.BUSY;
        }

        try
        {
            if(!mDiscoveredTunerRegistry.contains(recording))
            {
                return RecordingTunerRemovalResult.NOT_FOUND;
            }
            if(recording.hasTuner() && recording.getTuner().getChannelSourceManager().getTunerChannelCount() > 0)
            {
                return RecordingTunerRemovalResult.IN_USE;
            }

            recording.stop();
            mDiscoveredTunerRegistry.remove(recording);
            recording.removeTunerStatusListener(this);
            mTunerConfigurationManager.removeRecordingTunerConfiguration(recording);
            return RecordingTunerRemovalResult.REMOVED;
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Unable to remove recording tuner ({})", exception.getClass().getSimpleName());
            return RecordingTunerRemovalResult.FAILED;
        }
        finally
        {
            recording.releaseAfterAllocation();
        }
    }

    public enum RecordingTunerRemovalResult
    {
        REMOVED, NOT_FOUND, NOT_RECORDING, IN_USE, BUSY, FAILED
    }

    /**
     * Starts the tuner manager and loads all available tuners
     */
    public void start()
    {
        mStopping = false;
        mLog.info("Discovering tuners ...");

        try
        {
            startUsbTuners();
        }
        catch(Exception | LinkageError e)
        {
            //A previous setup probe can leave LibUsb erroneous, causing NoClassDefFoundError on this attempt.
            mLog.error("Unable to start USB tuner discovery. Continuing with other tuner types.", e);
        }

        discoverSdrPlayTuners();
        discoverRecordingTuners();
    }

    /** Starts only the USB backend so a missing native dependency cannot prevent application startup. */
    void startUsbTuners()
    {
        int api = LibUsb.getApiVersion();
        mLog.info("LibUsb API Version: " + ((api >> 24) & 0xFF) + "." + ((api >> 16) & 0xFF) + "." + (api & 0xFFFF));
        mLog.info("LibUsb Version: " + LibUsb.getVersion());

        int status = LibUsb.init(mLibUsbApplicationContext);

        if(status == LibUsb.SUCCESS)
        {
            mLibUsbInitialized = true;
            discoverUSBTuners();
            mHotplugEventSupport.start();
        }
        else
        {
            mLog.error("Unable to initialize USB tuner discovery: {} ({})", LibUsb.errorName(status), status);
        }
    }

    /**
     * Stops or shuts down the tuner manager and releases all devices
     */
    public void stop()
    {
        mStopping = true;
        mCoverageRepairExecutor.shutdownNow();
        mAllocationDiagnostics.close();
        boolean rescanStopped = stopUsbRescanWorker(1, TimeUnit.SECONDS);
        boolean hotplugStopped = true;

        if(mLibUsbInitialized)
        {
            hotplugStopped = mHotplugEventSupport.stop();
        }

        //Stop all tuners and prevent a pending USB tuner from entering the model after this point.
        synchronized(mUsbModelLock)
        {
            mDiscoveredTunerRegistry.release();
        }

        //Shutdown SDRplay API instance
        if(mSDRplay != null)
        {
            mSDRplay.close();
            mSDRplay = null;
        }

        //Do not release the native context if either USB worker may still be using it.
        if(mLibUsbInitialized)
        {
            if(rescanStopped && hotplugStopped)
            {
                LibUsb.exit(mLibUsbApplicationContext);
                mLibUsbInitialized = false;
            }
            else
            {
                mLog.warn("Leaving the LibUsb context allocated because USB tuner work did not stop in time");
            }
        }
    }

    /**
     * Requests one add-only USB tuner scan. Requests made while a scan is running share the same result.
     */
    public CompletableFuture<Integer> requestUsbTunerRescan()
    {
        synchronized(mUsbRescanLock)
        {
            if(mStopping || mUsbRescanClosed)
            {
                return CompletableFuture.completedFuture(USB_RESCAN_UNAVAILABLE);
            }

            if(mUsbRescan != null && !mUsbRescan.isDone())
            {
                return mUsbRescan;
            }

            try
            {
                CompletableFuture<Integer> requested = CompletableFuture.supplyAsync(this::discoverUSBTuners,
                        mUsbRescanExecutor);
                mUsbRescan = requested;
                requested.whenComplete((result, error) ->
                {
                    synchronized(mUsbRescanLock)
                    {
                        if(mUsbRescan == requested)
                        {
                            mUsbRescan = null;
                        }
                    }
                });
                return requested;
            }
            catch(RejectedExecutionException ree)
            {
                return CompletableFuture.completedFuture(USB_RESCAN_UNAVAILABLE);
            }
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
     * Retries the LibUsb get device list operation with warning logging enabled to diagnose error -99.
     */
    private void retryGetDeviceList(DeviceList deviceList)
    {
        try
        {
            mLog.info("LibUsb reported error -99 during get device list - increasing logging level and retrying");
            LibUsb.setOption(mLibUsbApplicationContext, LibUsb.OPTION_LOG_LEVEL, LibUsb.LOG_LEVEL_WARNING);
            int deviceCount = LibUsb.getDeviceList(mLibUsbApplicationContext, deviceList);
            if(deviceCount >= 0)
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

    /**
     * Discover or rediscovers USB tuners
     */
    int discoverUSBTuners()
    {
        if(!mLibUsbInitialized || mStopping)
        {
            return USB_RESCAN_UNAVAILABLE;
        }

        List<DiscoveredUSBTuner> discoveredUSBTuners = new ArrayList<>();
        boolean successful = true;

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
                                DiscoveredUSBTuner discoveredUSBTuner = new DiscoveredUSBTuner(tunerClass, bus,
                                        portAddress);
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
                retryGetDeviceList(deviceList);
                successful = false;
            }
            else
            {
                mLog.error("LibUsb - error [" + deviceCount + "/" + LibUsb.errorName(deviceCount) + "] during get device list");
                successful = false;
            }
        }
        catch(Exception e)
        {
            mLog.error("LibUsb - error during USB device discovery", e);
            successful = false;
        }

        if(mStopping)
        {
            return USB_RESCAN_UNAVAILABLE;
        }

        int added = 0;

        for(DiscoveredUSBTuner discoveredUSBTuner: discoveredUSBTuners)
        {
            if(addUsbTuner(discoveredUSBTuner))
            {
                added++;
            }
        }

        if(!successful)
        {
            return USB_RESCAN_FAILED;
        }

        return added;
    }

    /**
     * Determines if the USB device is a supported tuner and add if it has not already been added/discovered.
     * @param discoveredUSBTuner to add
     */
    private boolean addUsbTuner(DiscoveredUSBTuner discoveredUSBTuner)
    {
        synchronized(mUsbAttachLock)
        {
            if(!mStopping && mLibUsbInitialized &&
                    !mDiscoveredTunerRegistry.hasUsbTuner(discoveredUSBTuner.getBus(), discoveredUSBTuner.getPortAddress()))
            {
                return startAndConfigureTuner(discoveredUSBTuner);
            }
        }

        return false;
    }

    /**
     * Starts, configures and adds the tuner to the tuner model.
     * @param discoveredTuner to add and configure
     */
    private boolean startAndConfigureTuner(DiscoveredTuner discoveredTuner)
    {
        mTunerConfigurationManager.prepareTunerConfiguration(discoveredTuner);
        discoveredTuner.configureOnStart(tuner ->
            mTunerConfigurationManager.getTunerConfiguration(tuner.getTunerType(), discoveredTuner.getId()));
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
            discoveredTuner.startConfigured();
        }

        if(discoveredTuner instanceof DiscoveredUSBTuner)
        {
            synchronized(mUsbModelLock)
            {
                if(!mStopping)
                {
                    mDiscoveredTunerRegistry.add(discoveredTuner);
                    return true;
                }
            }
            discoveredTuner.removeTunerStatusListener(this);
            discoveredTuner.stop();
            return false;
        }

        mDiscoveredTunerRegistry.add(discoveredTuner);
        return true;
    }

    /**
     * Discover SDRPlay RSP tuners
     */
    void discoverSdrPlayTuners()
    {
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
                    List<DiscoveredRspTuner> tuners = TunerFactory.getRspTuners(deviceInfo, duoSelectionMode);

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
                        new DiscoveredRecordingTuner(recordingTunerConfiguration);

                discoveredRecordingTuner.addTunerStatusListener(this);
                discoveredRecordingTuner.setEnabled(false);
                mLog.info("Tuner Added: " + discoveredRecordingTuner);
                mDiscoveredTunerRegistry.add(discoveredRecordingTuner);
            }
        }
    }

    /** Status observers persist completion; they never start, configure or cascade hardware commands. */
    @Override
    public void tunerStatusUpdated(DiscoveredTuner discoveredTuner, TunerStatus previous, TunerStatus current)
    {
        mTunerConfigurationManager.tunerStatusUpdated(discoveredTuner, previous, current);
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
        return mDiscoveredTunerRegistry.availableTuners();
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
        return getSource(config, channelSpecification, threadName, null, null);
    }

    public Source getSource(SourceConfiguration config, ChannelSpecification channelSpecification,
                             String threadName, TunerAllocationContext context) throws SourceException
    {
        return getSource(config, channelSpecification, threadName, null, context);
    }

    /** Initial wizard startup must use this exact receiver without moving its center or falling back. */
    public Source getSourceAtCurrentCenter(SourceConfiguration config, ChannelSpecification channelSpecification,
                                          String threadName, DiscoveredTuner selectedTuner) throws SourceException
    {
        return getSource(config, channelSpecification, threadName, Objects.requireNonNull(selectedTuner), null);
    }

    public Source getSourceAtCurrentCenter(SourceConfiguration config, ChannelSpecification channelSpecification,
                                           String threadName, DiscoveredTuner selectedTuner,
                                           TunerAllocationContext context) throws SourceException
    {
        return getSource(config, channelSpecification, threadName, Objects.requireNonNull(selectedTuner), context);
    }

    private Source getSource(SourceConfiguration config, ChannelSpecification channelSpecification,
                             String threadName, DiscoveredTuner selectedTuner, TunerAllocationContext context) throws SourceException
    {
        if(config == null || config.getSourceType() == null || !config.getSourceType().isActive())
        {
            throw new SourceException("Channel source is retired or unsupported");
        }

        Source retVal = null;
        if(context != null && channelSpecification != null)
        {
            if(config instanceof SourceConfigTuner single)
                observeKnownFrequency(context, single.getFrequency(), channelSpecification.getBandwidth());
            else if(config instanceof SourceConfigTunerMultipleFrequency multiple)
                multiple.getFrequencies().stream().limit(256).forEach(frequency ->
                    observeKnownFrequency(context, frequency, channelSpecification.getBandwidth()));
        }

        switch(config.getSourceType())
        {
            case TUNER:
                if(config instanceof SourceConfigTuner)
                {
                    SourceConfigTuner sourceConfigTuner = (SourceConfigTuner)config;
                    TunerChannel tunerChannel = sourceConfigTuner.getTunerChannel(channelSpecification.getBandwidth());
                    String preferredTuner = sourceConfigTuner.getPreferredTuner();
                    retVal = selectedTuner != null ? getSourceAtCurrentCenter(selectedTuner, tunerChannel,
                        channelSpecification, threadName, context) : getSource(tunerChannel, channelSpecification,
                        preferredTuner, threadName + " " + tunerChannel.getFrequency(), null, context);
                }
                break;
            case TUNER_MULTIPLE_FREQUENCIES:
                if(config instanceof SourceConfigTunerMultipleFrequency)
                {
                    SourceConfigTunerMultipleFrequency sourceConfigTuner = (SourceConfigTunerMultipleFrequency)config;
                    TunerChannel tunerChannel = sourceConfigTuner.getTunerChannel(channelSpecification.getBandwidth());
                    String preferredTuner = sourceConfigTuner.getPreferredTuner();
                    SortedSet<TunerChannel> tunerChannels = getTunerChannels(sourceConfigTuner, channelSpecification);

                    Source source = selectedTuner != null ? getSourceAtCurrentCenter(selectedTuner, tunerChannel,
                        channelSpecification, threadName, context) : getSource(tunerChannel, channelSpecification,
                        preferredTuner, threadName, tunerChannels, context);

                    if(source instanceof TunerChannelSource)
                    {
                        retVal = new MultiFrequencyTunerChannelSource(this, (TunerChannelSource)source,
                                sourceConfigTuner::getFrequencies, channelSpecification,
                                sourceConfigTuner.getPreferredTuner(), threadName + " MULTI FREQ",
                                sourceConfigTuner.getMinimumFrequency(), sourceConfigTuner.getMaximumFrequency(), context);
                    }
                }
                break;
            default:
                break;
        }

        return retVal;
    }

    private Source getSourceAtCurrentCenter(DiscoveredTuner selectedTuner, TunerChannel tunerChannel,
                                            ChannelSpecification channelSpecification, String threadName,
                                            TunerAllocationContext context)
    {
        mChannelAllocationRequests.incrementAndGet();
        Map<DiscoveredTuner,PolyphaseChannelSourceManager.AllocationAttempt> attempts = new LinkedHashMap<>();
        try
        {
            if(mDiscoveredTunerRegistry.availableTuners().contains(selectedTuner))
            {
                Source source = getSource(selectedTuner, tunerChannel, channelSpecification, threadName, null,
                    PolyphaseChannelSourceManager.AllocationMode.CURRENT_CENTER, context, attempts);
                if(source != null)
                {
                    mChannelAllocationSuccesses.incrementAndGet();
                    mAllocationDiagnostics.resolved(context, tunerChannel.getFrequency());
                    recordDecisions(context, tunerChannel, attempts, selectedTuner, true);
                    return source;
                }
            }
        }
        catch(Exception exception)
        {
            mLog.error("Error obtaining channel from selected tuner [{}]", selectedTuner.getId(), exception);
        }
        mChannelAllocationFailures.incrementAndGet();
        recordDecisions(context, tunerChannel, attempts, null, false);
        return null;
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
        return getSource(tunerChannel, channelSpecification, preferredTuner, threadName, null);
    }

    /**
     * Iterates current available tuners to get a tuner channel source for the specified frequency and bandwidth,
     * optionally using a broader channel set for polyphase center-frequency selection.
     */
    public Source getSource(TunerChannel tunerChannel, ChannelSpecification channelSpecification, String preferredTuner,
                            String threadName, SortedSet<TunerChannel> tunerChannels)
    {
        return getSource(tunerChannel, channelSpecification, preferredTuner, threadName, tunerChannels, null);
    }

    public Source getSource(TunerChannel tunerChannel, ChannelSpecification channelSpecification, String preferredTuner,
                             String threadName, SortedSet<TunerChannel> tunerChannels, TunerAllocationContext context)
    {
        boolean validRequest = tunerChannel != null && channelSpecification != null;

        if(validRequest)
        {
            mChannelAllocationRequests.incrementAndGet();
            observeKnownFrequency(context, tunerChannel.getFrequency(), tunerChannel.getBandwidth());
            List<DiscoveredTuner> allocationCandidates = getAllocationCandidates(preferredTuner);
            Map<DiscoveredTuner,PolyphaseChannelSourceManager.AllocationAttempt> attempts = new LinkedHashMap<>();

            for(PolyphaseChannelSourceManager.AllocationMode allocationMode: CHANNEL_ALLOCATION_STAGES)
            {
                //Lifecycle and availability are rechecked on each attempt.  The immutable candidate snapshot keeps
                //the preferred tuner first without rebuilding the list for each cumulative allocation stage.
                for(DiscoveredTuner discoveredTuner: orderedCandidates(allocationCandidates, preferredTuner,
                    tunerChannel, allocationMode))
                {
                    if(discoveredTuner.hasTuner())
                    {
                        try
                        {
                            TunerChannelSource source = getSource(discoveredTuner, tunerChannel, channelSpecification,
                                threadName, tunerChannels, allocationMode, context, attempts);

                            if(source != null)
                            {
                                mChannelAllocationSuccesses.incrementAndGet();
                                mAllocationDiagnostics.resolved(context, tunerChannel.getFrequency());
                                recordDecisions(context, tunerChannel, attempts, discoveredTuner, true);
                                return source;
                            }
                        }
                        catch(Exception e)
                        {
                            mLog.error("Error obtaining channel from tuner [{}]", discoveredTuner.getId(), e);
                        }
                    }
                }
            }

            if(preferredTuner != null)
            {
                mLog.info("Unable to source channel [" + tunerChannel.getFrequency() +
                    "] from any available tuner; configured preferred tuner was [" + preferredTuner + "]");
            }

            mChannelAllocationFailures.incrementAndGet();
            recordDecisions(context, tunerChannel, attempts, null, false);
        }

        return null;
    }

    /**
     * Available tuner snapshot ordered with the preferred tuner first and without duplicating it in the stage.
     */
    private List<DiscoveredTuner> getAllocationCandidates(String preferredTuner)
    {
        List<DiscoveredTuner> availableTuners = mDiscoveredTunerRegistry.availableTuners().stream()
            .filter(DiscoveredTuner::isAvailableForAllocation).toList();
        LinkedHashSet<DiscoveredTuner> candidates = new LinkedHashSet<>();

        if(preferredTuner != null)
        {
            for(DiscoveredTuner discoveredTuner: availableTuners)
            {
                Tuner tuner = discoveredTuner.getTuner();

                String name = null;
                try { if(tuner != null) name = tuner.getPreferredName(); }
                catch(RuntimeException removedDuringPreferenceRead) { /* Try the remaining receivers. */ }
                discoveredTuner.setAllocationPreferredName(name);
                if(name != null && name.equalsIgnoreCase(preferredTuner))
                {
                    candidates.add(discoveredTuner);
                    break;
                }
            }
        }

        candidates.addAll(availableTuners);
        return List.copyOf(candidates);
    }

    /**
     * Process-lifetime source-allocation measurements.  Updates are primitive atomics because allocation can be
     * requested from a decoder callback.
     */
    public TunerAllocationStatus getTunerAllocationStatus()
    {
        long successes = mChannelAllocationSuccesses.get();
        long failures = mChannelAllocationFailures.get();
        return new TunerAllocationStatus(mChannelAllocationRequests.get(), successes, failures);
    }

    public record TunerAllocationStatus(long requests, long successes, long failures)
    {
    }

    /**
     * Obtains a source from the discovered tuner, using the broader center-frequency context only for polyphase
     * tuners that can make use of it.
     */
    private TunerChannelSource getSource(DiscoveredTuner discoveredTuner, TunerChannel tunerChannel,
                                         ChannelSpecification channelSpecification, String threadName,
                                         SortedSet<TunerChannel> tunerChannels,
                                         PolyphaseChannelSourceManager.AllocationMode allocationMode,
                                         TunerAllocationContext context,
                                         Map<DiscoveredTuner,PolyphaseChannelSourceManager.AllocationAttempt> attempts)
    {
        PolyphaseChannelSourceManager.AllocationAttempt attempt = new PolyphaseChannelSourceManager.AllocationAttempt();
        attempt.reason = TunerAllocationDecision.Reason.TUNER_BUSY;
        PolyphaseChannelSourceManager.AllocationAttempt earlier = attempts.put(discoveredTuner, attempt);
        if(!discoveredTuner.tryAcquireForAllocation())
        {
            if(earlier != null && (earlier.before != earlier.after || earlier.tuneFailed)) attempts.put(discoveredTuner, earlier);
            return null;
        }

        try
        {
            if(!discoveredTuner.isAvailableForAllocation() || !discoveredTuner.hasTuner())
            {
                attempt.reason = TunerAllocationDecision.Reason.TUNER_UNAVAILABLE;
                return null;
            }

            ChannelSourceManager channelSourceManager = discoveredTuner.getTuner().getChannelSourceManager();

            if(channelSourceManager == null)
            {
                attempt.reason = TunerAllocationDecision.Reason.TUNER_UNAVAILABLE;
                return null;
            }

            if(channelSourceManager instanceof PolyphaseChannelSourceManager polyphaseChannelSourceManager)
            {
                polyphaseChannelSourceManager.setIdleCoverageListener(this::scheduleCoverageRepair, context);
                Tuner tuner = discoveredTuner.getTuner();
                TunerController tunerController = tuner.getTunerController();
                long previousCenterFrequency = tunerController.getFrequency();
                List<TunerChannel> known = mFrequencyKnowledge.channels(context != null ? context.channelId() : null);
                List<TunerCoveragePlanner.Window> otherWindows = known.isEmpty() ||
                    allocationMode == PolyphaseChannelSourceManager.AllocationMode.CURRENT_CENTER ? List.of() :
                    receivingWindows(discoveredTuner);
                TunerChannelSource source = polyphaseChannelSourceManager.getSource(tunerChannel,
                    channelSpecification, threadName, tunerChannels,
                    discoveredTuner.isDiscoveryHeld() ? PolyphaseChannelSourceManager.AllocationMode.CURRENT_CENTER :
                        allocationMode, known, otherWindows, attempt);

                long currentCenterFrequency = tunerController.getFrequency();

                if(currentCenterFrequency != previousCenterFrequency)
                {
                    mTunerConfigurationManager.updateTunerFrequency(tuner.getTunerType(), discoveredTuner.getId(),
                        currentCenterFrequency);
                }

                if(source != null)
                {
                    source.setTunerIdentity(discoveredTuner.getId());
                }
                return source;
            }

            //Non-polyphase sources do not support allocator-managed retuning.  They may participate in any cumulative
            //stage only when their current immutable recording window already covers the complete request.
            TunerController tunerController = discoveredTuner.getTuner().getTunerController();

            if(!tunerController.getLock().tryLock())
            {
                return null;
            }

            try
            {
                attempt.before = attempt.after = attempt.requested = tunerController.getFrequency();
                attempt.width = tunerController.getUsableBandwidth();
                attempt.gap = tunerController.getMiddleUnusableHalfBandwidth();
                attempt.minimum = tunerController.getMinimumFrequency();
                attempt.maximum = tunerController.getMaximumFrequency();
                attempt.reason = TunerAllocationDecision.Reason.OUTSIDE_LIMITS;
                if(!tunerController.canTune(tunerChannel.getMinFrequency()) ||
                    !tunerController.canTune(tunerChannel.getMaxFrequency()))
                {
                    return null;
                }

                SortedSet<TunerChannel> requestedChannels = new TreeSet<>();
                requestedChannels.add(tunerChannel);

                if(!tunerController.isTunedFor(requestedChannels))
                {
                    attempt.reason = TunerAllocationDecision.Reason.NO_ROOM;
                    return null;
                }

                //A pass-through source forwards the tuner stream without mixing an offset channel to baseband.
                if(tunerController.getFrequency() != tunerChannel.getFrequency())
                {
                    attempt.reason = TunerAllocationDecision.Reason.NO_ROOM;
                    return null;
                }

                TunerChannelSource source = channelSourceManager.getSource(tunerChannel, channelSpecification,
                    threadName);
                attempt.reason = TunerAllocationDecision.Reason.SOURCE_START_FAILED;
                if(source != null)
                {
                    source.setTunerIdentity(discoveredTuner.getId());
                }
                return source;
            }
            finally
            {
                tunerController.getLock().unlock();
            }
        }
        finally
        {
            if(earlier != null && (earlier.before != earlier.after || earlier.tuneFailed))
            {
                attempt.before = earlier.before;
                attempt.requested = earlier.requested;
                attempt.width = earlier.width;
                attempt.gap = earlier.gap;
                attempt.activeChannels = earlier.activeChannels;
                if(attempt.after == earlier.after && attempt.reason != TunerAllocationDecision.Reason.SPARE_TUNER &&
                    attempt.reason != TunerAllocationDecision.Reason.KEEP_CURRENT_CALLS)
                    attempt.tuneFailed |= earlier.tuneFailed;
                if(attempt.after == 0) attempt.after = earlier.after;
            }
            discoveredTuner.releaseAfterAllocation();
        }
    }

    /** Explicit preference remains first; occupied compact windows preserve spare receivers among other choices. */
    private List<DiscoveredTuner> orderedCandidates(List<DiscoveredTuner> candidates, String preferred,
        TunerChannel requested, PolyphaseChannelSourceManager.AllocationMode stage)
    {
        if(stage != PolyphaseChannelSourceManager.AllocationMode.CURRENT_CENTER) return candidates;
        record Ranked(DiscoveredTuner tuner, boolean preferred, boolean occupied, long addedSpan) {}
        List<Ranked> ranked = new ArrayList<>(candidates.size());
        for(DiscoveredTuner discovered: candidates)
        {
            Tuner tuner = discovered.getTuner();
            ChannelSourceManager manager = tuner != null ? tuner.getChannelSourceManager() : null;
            SortedSet<TunerChannel> channels;
            try { channels = manager != null ? manager.getTunerChannels() : new TreeSet<>(); }
            catch(RuntimeException removedDuringSnapshot) { channels = new TreeSet<>(); }
            long previousSpan = channels.isEmpty() ? 0 :
                channels.last().getMaxFrequency() - channels.first().getMinFrequency();
            long newSpan = channels.isEmpty() ? requested.getBandwidth() :
                Math.max(channels.last().getMaxFrequency(), requested.getMaxFrequency()) -
                    Math.min(channels.first().getMinFrequency(), requested.getMinFrequency());
            ranked.add(new Ranked(discovered, preferred != null &&
                preferred.equalsIgnoreCase(discovered.getAllocationPreferredName()), !channels.isEmpty(), newSpan - previousSpan));
        }
        ranked.sort(Comparator.comparing(Ranked::preferred).reversed()
            .thenComparing(Comparator.comparing(Ranked::occupied).reversed()).thenComparingLong(Ranked::addedSpan));
        return ranked.stream().map(Ranked::tuner).toList();
    }

    /** Best-effort volatile geometry. Never reserves another tuner's controller for placement hints. */
    private List<TunerCoveragePlanner.Window> receivingWindows(DiscoveredTuner excluded)
    {
        List<TunerCoveragePlanner.Window> windows = new ArrayList<>();
        for(DiscoveredTuner discovered: mDiscoveredTunerRegistry.availableTuners())
        {
            if(discovered == excluded || !discovered.isAvailableForAllocation()) continue;
            Tuner tuner = discovered.getTuner();
            if(tuner == null || !(tuner.getChannelSourceManager() instanceof PolyphaseChannelSourceManager)) continue;
            TunerController.ReceivingGeometry geometry = tuner.getTunerController().getReceivingGeometry();
            windows.add(new TunerCoveragePlanner.Window(geometry.frequencyHz(), geometry.usableBandwidthHz(),
                geometry.middleUnusableHalfBandwidthHz(), geometry.minimumFrequencyHz(), geometry.maximumFrequencyHz()));
        }
        return List.copyOf(windows);
    }

    private void recordDecisions(TunerAllocationContext context, TunerChannel request,
        Map<DiscoveredTuner,PolyphaseChannelSourceManager.AllocationAttempt> attempts,
        DiscoveredTuner selected, boolean successful)
    {
        List<TunerAllocationDecision.Candidate> candidates = attempts.entrySet().stream()
            .filter(entry -> entry.getKey() != selected).limit(TunerAllocationDecision.MAXIMUM_CANDIDATES)
            .map(entry -> new TunerAllocationDecision.Candidate(entry.getKey().getId(), entry.getValue().reason,
                entry.getValue().minimum > 0 ? entry.getValue().minimum : null,
                entry.getValue().maximum > 0 ? entry.getValue().maximum : null)).toList();
        boolean movementRecorded = false;
        for(Map.Entry<DiscoveredTuner,PolyphaseChannelSourceManager.AllocationAttempt> entry: attempts.entrySet())
        {
            PolyphaseChannelSourceManager.AllocationAttempt attempt = entry.getValue();
            if(attempt.before != attempt.after && attempt.before > 0 || attempt.tuneFailed)
            {
                TunerAllocationDecision.Outcome outcome = attempt.tuneFailed ? TunerAllocationDecision.Outcome.RETUNE_FAILED :
                    successful && entry.getKey() == selected ? TunerAllocationDecision.Outcome.MOVED :
                        TunerAllocationDecision.Outcome.MOVED_CALL_FAILED;
                TunerAllocationDecision.Reason reason = attempt.tuneFailed ? TunerAllocationDecision.Reason.HARDWARE_RETUNE_FAILED :
                    outcome == TunerAllocationDecision.Outcome.MOVED_CALL_FAILED ? TunerAllocationDecision.Reason.SOURCE_START_FAILED :
                        attempt.activeChannels > 0 ? TunerAllocationDecision.Reason.KEEP_CURRENT_CALLS :
                            TunerAllocationDecision.Reason.SPARE_TUNER;
                offerMovement(context, request, entry.getKey(), attempt, outcome, reason, candidates);
                movementRecorded = true;
            }
        }
        if(!successful && !movementRecorded)
        {
            TunerAllocationDecision.Reason reason = candidates.isEmpty() ? TunerAllocationDecision.Reason.NO_TUNER_AVAILABLE :
                candidates.stream().map(TunerAllocationDecision.Candidate::reason).distinct().count() == 1 ?
                    candidates.getFirst().reason() : TunerAllocationDecision.Reason.NO_TUNER_AVAILABLE;
            mAllocationDiagnostics.offer(new TunerAllocationDecision(System.currentTimeMillis(), context, null,
                TunerAllocationDecision.Outcome.ALLOCATION_FAILED, reason, request.getFrequency(),
                (long)request.getBandwidth(), null, null, null, null, null, null, null, null, candidates));
        }
    }

    private void offerMovement(TunerAllocationContext context, TunerChannel request, DiscoveredTuner tuner,
        PolyphaseChannelSourceManager.AllocationAttempt attempt, TunerAllocationDecision.Outcome outcome,
        TunerAllocationDecision.Reason reason, List<TunerAllocationDecision.Candidate> candidates)
    {
        mAllocationDiagnostics.offer(new TunerAllocationDecision(System.currentTimeMillis(), context, tuner.getId(),
            outcome, reason, request != null ? request.getFrequency() : null,
            request != null ? (long)request.getBandwidth() : null, attempt.before, attempt.requested, attempt.after,
            (long)attempt.width, (long)attempt.gap, null, attempt.coveredBefore, attempt.coveredAfter, candidates));
    }

    /** One queued repair regardless of how many calls end or frequencies arrive during the debounce period. */
    private void scheduleCoverageRepair()
    {
        mCoverageRepairRevision.incrementAndGet();
        queueCoverageRepair();
    }

    private void queueCoverageRepair()
    {
        if(mStopping || !mCoverageRepairQueued.compareAndSet(false, true)) return;
        try
        {
            mCoverageRepairExecutor.schedule(() ->
            {
                long revision = mCoverageRepairRevision.get();
                try { repairIdleCoverage(); }
                catch(RuntimeException exception) { mLog.debug("Idle tuner coverage refresh failed", exception); }
                finally
                {
                    mCoverageRepairQueued.set(false);
                    if(mCoverageRepairRevision.get() != revision) queueCoverageRepair();
                }
            }, 500, TimeUnit.MILLISECONDS);
        }
        catch(RejectedExecutionException exception) { mCoverageRepairQueued.set(false); }
    }

    void repairIdleCoverage()
    {
        if(mStopping) return;
        // Catalog size is fixed. The worker considers bounded placement hints but guards against losing coverage
        // across the complete retained catalog, including frequencies outside those hints.
        List<TunerAllocationKnownSite> sites = mFrequencyKnowledge.snapshot();
        List<TunerChannel> known = sites.stream().flatMap(site -> site.frequencies().stream())
            .map(frequency -> new TunerChannel(frequency.frequencyHz(), (int)frequency.bandwidthHz())).distinct().toList();
        if(known.isEmpty()) return;
        for(DiscoveredTuner discovered: mDiscoveredTunerRegistry.availableTuners())
        {
            if(mStopping || !discovered.isAvailableForAllocation() || discovered.isDiscoveryHeld()) continue;
            Tuner tuner = discovered.getTuner();
            if(tuner == null || !(tuner.getChannelSourceManager() instanceof PolyphaseChannelSourceManager manager)) continue;
            List<TunerCoveragePlanner.Window> others = receivingWindows(discovered);
            PolyphaseChannelSourceManager.IdleCoveragePlan plan = manager.planIdleCoverage(known, others);
            if(plan == null) continue;
            TunerCoveragePlanner.Window movedWindow = new TunerCoveragePlanner.Window(plan.centerHz(),
                plan.geometry().usableBandwidthHz(), plan.geometry().middleUnusableHalfBandwidthHz(),
                plan.geometry().minimumFrequencyHz(), plan.geometry().maximumFrequencyHz());
            TunerCoveragePlanner.Window oldWindow = movedWindow.at(plan.geometry().frequencyHz());
            TunerAllocationKnownSite beneficiarySite = sites.stream().filter(site -> site.frequencies().stream()
                .map(frequency -> new TunerChannel(frequency.frequencyHz(), (int)frequency.bandwidthHz()))
                .anyMatch(channel -> TunerCoveragePlanner.covers(movedWindow, channel) &&
                    !TunerCoveragePlanner.covers(oldWindow, channel) &&
                    others.stream().noneMatch(window -> TunerCoveragePlanner.covers(window, channel))))
                .findFirst().orElse(null);
            TunerAllocationContext beneficiary = beneficiarySite != null ? beneficiarySite.context() : manager.getAllocationContext();
            List<TunerChannel> beneficiaryChannels = beneficiarySite != null ? beneficiarySite.frequencies().stream()
                .map(frequency -> new TunerChannel(frequency.frequencyHz(), (int)frequency.bandwidthHz())).toList() : List.of();
            List<TunerCoveragePlanner.Window> beforeWindows = new ArrayList<>(others);
            beforeWindows.add(oldWindow);
            List<TunerCoveragePlanner.Window> afterWindows = new ArrayList<>(others);
            afterWindows.add(movedWindow);
            int siteCoveredBefore = TunerCoveragePlanner.coveredCount(beneficiaryChannels, beforeWindows);
            int siteCoveredAfter = TunerCoveragePlanner.coveredCount(beneficiaryChannels, afterWindows);
            if(!discovered.tryAcquireForAllocation()) continue;
            try
            {
                // A call/settings change during planning invalidates the hint; the next signal can try again.
                if(!discovered.isAvailableForAllocation() || discovered.getTuner() != tuner ||
                    !others.equals(receivingWindows(discovered))) continue;
                PolyphaseChannelSourceManager.AllocationAttempt attempt = new PolyphaseChannelSourceManager.AllocationAttempt();
                if(manager.applyIdleCoverage(plan, attempt))
                {
                    attempt.coveredBefore = beneficiarySite != null ? siteCoveredBefore : null;
                    attempt.coveredAfter = beneficiarySite != null ?
                        (attempt.tuneFailed ? siteCoveredBefore : siteCoveredAfter) : null;
                    if(attempt.after != attempt.before) mTunerConfigurationManager.updateTunerFrequency(
                        tuner.getTunerType(), discovered.getId(), attempt.after);
                    offerMovement(beneficiary, null, discovered, attempt,
                        attempt.tuneFailed ? TunerAllocationDecision.Outcome.RETUNE_FAILED : TunerAllocationDecision.Outcome.MOVED,
                        attempt.reason, List.of());
                }
            }
            finally { discovered.releaseAfterAllocation(); }
        }
    }

    /**
     * Creates tuner channels for the configured frequency list using the supplied channel bandwidth.
     */
    private SortedSet<TunerChannel> getTunerChannels(SourceConfigTunerMultipleFrequency sourceConfigTuner,
                                                     ChannelSpecification channelSpecification)
    {
        SortedSet<TunerChannel> tunerChannels = new TreeSet<>();

        if(sourceConfigTuner.hasFrequencyEnvelope())
        {
            long minimumFrequency = sourceConfigTuner.getMinimumFrequency();
            long maximumFrequency = sourceConfigTuner.getMaximumFrequency();
            long centerFrequency = minimumFrequency + ((maximumFrequency - minimumFrequency) / 2);
            int bandwidth = (int)((maximumFrequency - minimumFrequency) + channelSpecification.getBandwidth());
            tunerChannels.add(new TunerChannel(centerFrequency, bandwidth));
            return tunerChannels;
        }

        for(long frequency: sourceConfigTuner.getFrequencies())
        {
            tunerChannels.add(new TunerChannel(frequency, channelSpecification.getBandwidth()));
        }

        return tunerChannels;
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

    private static ExecutorService createUsbRescanExecutor()
    {
        NamingThreadFactory threadFactory = new NamingThreadFactory("usb tuner rescan");
        return Executors.newSingleThreadExecutor(runnable ->
        {
            Thread thread = threadFactory.newThread(runnable);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    boolean stopUsbRescanWorker(long timeout, TimeUnit timeUnit)
    {
        synchronized(mUsbRescanLock)
        {
            mUsbRescanClosed = true;

            if(mUsbRescan != null)
            {
                mUsbRescan.complete(USB_RESCAN_UNAVAILABLE);
                mUsbRescan = null;
            }

            mUsbRescanExecutor.shutdownNow();
        }

        try
        {
            return mUsbRescanExecutor.awaitTermination(timeout, timeUnit);
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * USB hotplug event listener to register, unregister and detect when USB devices are added or removed.
     *
     * Note: hotplug is not supported on all platforms.
     */
    public class HotplugEventSupport implements HotplugCallback
    {
        private static final int HOTPLUG_CONTINUE_EVENT_SUPPORT = 0;
        private static final long STOP_TIMEOUT_MS = 1000;
        private final Runnable mInterruptEventHandler;
        private HotplugCallbackHandle mHotplugCallbackHandle;
        private Thread mEventProcessorThread;
        private volatile boolean mProcessing;

        HotplugEventSupport()
        {
            this(() -> LibUsb.interruptEventHandler(mLibUsbApplicationContext), null);
        }

        HotplugEventSupport(Runnable interruptEventHandler, Thread eventProcessorThread)
        {
            mInterruptEventHandler = interruptEventHandler;
            mEventProcessorThread = eventProcessorThread;
        }

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
            if(mStopping)
            {
                return HOTPLUG_CONTINUE_EVENT_SUPPORT;
            }

            int bus = LibUsb.getBusNumber(device);
            int port = LibUsb.getPortNumber(device);

            if(port > 0)
            {
                String portAddress = getPortAddress(device);

                if(event == LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED)
                {
                    DeviceDescriptor deviceDescriptor = new DeviceDescriptor();
                    int status = LibUsb.getDeviceDescriptor(device, deviceDescriptor);

                    if(status == LibUsb.SUCCESS)
                    {
                        TunerClass tunerClass = TunerClass.lookup(deviceDescriptor.idVendor(), deviceDescriptor.idProduct());

                        if(tunerClass.isSupportedUsbTuner())
                        {
                            mLog.info("Tuner plug-in detected at USB Bus [" + bus + "] Port [" + port +
                                    "] Tuner Class [" + tunerClass + "]");
                            DiscoveredUSBTuner discoveredUSBTuner = new DiscoveredUSBTuner(tunerClass, bus,
                                    portAddress);

                            addUsbTuner(discoveredUSBTuner);
                        }
                    }
                }
                else if(event == LibUsb.HOTPLUG_EVENT_DEVICE_LEFT)
                {
                    DiscoveredTuner removed = removeUsbTuner(bus, portAddress);

                    if(removed != null)
                    {
                        mLog.info("Tuner Unplugged: " + removed.getId());
                    }
                }
            }

            return HOTPLUG_CONTINUE_EVENT_SUPPORT;
        }

        /**
         * Stops and removes the USB tuner if it is currently discovered.
         * @param bus usb for the tuner
         * @param portAddress usb for the tuner
         */
        private DiscoveredTuner removeUsbTuner(int bus, String portAddress)
        {
            synchronized(mUsbAttachLock)
            {
                return mDiscoveredTunerRegistry.removeUsbTuner(bus, portAddress);
            }
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
                    mProcessing = true;
                    mEventProcessorThread = new Thread(() -> {
                        while(mProcessing)
                        {
                            try
                            {
                                LibUsb.handleEventsTimeout(mLibUsbApplicationContext, 250);
                            }
                            catch(Throwable t)
                            {
                                if(mProcessing)
                                {
                                    mLog.error("Error while processing LibUsb hotplug timeout events", t);
                                }
                            }
                        }
                    }, "sdrtrunk libusb hotplug event processor");
                    mEventProcessorThread.setDaemon(true);
                    mEventProcessorThread.start();
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
        public boolean stop()
        {
            return stop(STOP_TIMEOUT_MS);
        }

        boolean stop(long timeoutMs)
        {
            mProcessing = false;
            Thread thread = mEventProcessorThread;

            if(thread != null)
            {
                try
                {
                    mInterruptEventHandler.run();
                }
                catch(Throwable t)
                {
                    mLog.warn("Unable to interrupt LibUsb hotplug event processor", t);
                }
            }

            if(mHotplugCallbackHandle != null)
            {
                LibUsb.hotplugDeregisterCallback(mLibUsbApplicationContext, mHotplugCallbackHandle);
                mHotplugCallbackHandle = null;
            }

            if(thread == null)
            {
                return true;
            }

            try
            {
                thread.join(timeoutMs);
            }
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                mLog.error("Interrupted while stopping LibUsb hotplug event processor thread", ie);
                return false;
            }

            if(thread.isAlive())
            {
                return false;
            }

            if(mEventProcessorThread == thread)
            {
                mEventProcessorThread = null;
            }

            return true;
        }
    }
}
