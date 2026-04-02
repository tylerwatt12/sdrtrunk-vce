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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.util.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SDRconnect tuner controller - connects to SDRconnect WebSocket API for IQ streaming
 */
public class SDRconnectTunerController extends TunerController implements WebSocket.Listener
{
    private static final Logger mLog = LoggerFactory.getLogger(SDRconnectTunerController.class);

    // Binary message headers from SDRconnect
    private static final int HEADER_IQ = 0x0002;
    // Default connection settings
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 5454;
    public static final String DEFAULT_DEVICE_NAME = "";
    public static final String DEFAULT_NETWORK_MODE = "Full IQ";
    public static final long DEFAULT_FREQUENCY = 100000000L; // 100 MHz
    public static final int DEFAULT_SAMPLE_RATE = 5000000; // 5 MHz

    // Frequency limits for SDRplay devices
    public static final long MINIMUM_FREQUENCY = 1000L; // 1 kHz
    public static final long MAXIMUM_FREQUENCY = 2000000000L; // 2 GHz
    public static final double USABLE_BANDWIDTH_PERCENT = 0.95;

    // Auto-reconnection settings
    private static final int RECONNECT_INITIAL_DELAY_SECONDS = 5;
    private static final int RECONNECT_MAX_DELAY_SECONDS = 60;
    private static final int RECONNECT_MAX_ATTEMPTS = 10;
    private static final AtomicBoolean APPLICATION_SHUTTING_DOWN = new AtomicBoolean(false);

    static
    {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> APPLICATION_SHUTTING_DOWN.set(true),
            "sdrconnect-controller-shutdown"));
    }

    private final String mHost;
    private final int mPort;

    private WebSocket mWebSocket;
    private HttpClient mHttpClient;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final AtomicBoolean mIqStreamEnabled = new AtomicBoolean(false);
    private final AtomicBoolean mShouldBeRunning = new AtomicBoolean(false); // User intent - should we try to stay connected?
    private final AtomicBoolean mReconnecting = new AtomicBoolean(false);
    private final AtomicBoolean mReconnectScheduledFromError = new AtomicBoolean(false);
    private final AtomicInteger mReconnectAttempts = new AtomicInteger(0);
    private ScheduledExecutorService mReconnectExecutor;
    private int mSampleRate = DEFAULT_SAMPLE_RATE;
    private long mCenterFrequency = DEFAULT_FREQUENCY;
    private String mValidDevices = "";
    private String mDeviceName = DEFAULT_DEVICE_NAME;
    private String mValidAntennas = "";
    private String mCurrentAntenna = "";
    private int mConfiguredSampleRate = DEFAULT_SAMPLE_RATE;
    private String mConfiguredAntenna = "";
    private Consumer<String> mAntennaChangeListener;
    private Consumer<Integer> mSampleRateChangeListener;
    private final Gson mGson = new Gson();

    // Buffer for accumulating partial WebSocket messages
    private final BinaryMessageAccumulator mBinaryMessageAccumulator = new BinaryMessageAccumulator();
    private final SDRconnectPropertyUpdateHandler mPropertyUpdateHandler;
    private final StringBuilder mPartialTextBuffer = new StringBuilder();
    private final SDRconnectNativeBufferFactory mNativeBufferFactory;
    private final AtomicReference<CountDownLatch> mDeviceDiscoveryLatch = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> mSettingsLatch = new AtomicReference<>();
    private final AtomicBoolean mValidDevicesReceived = new AtomicBoolean(false);
    private final AtomicBoolean mActiveDeviceReceived = new AtomicBoolean(false);
    private final AtomicBoolean mFrequencyReceived = new AtomicBoolean(false);
    private final AtomicBoolean mSampleRateReceived = new AtomicBoolean(false);

    /**
     * Constructs an instance
     * @param host SDRconnect host address
     * @param port SDRconnect WebSocket port
     * @param tunerErrorListener to receive error notifications
     */
    public SDRconnectTunerController(String host, int port, ITunerErrorListener tunerErrorListener)
    {
        super(tunerErrorListener);
        mHost = host;
        mPort = port;
        mNativeBufferFactory = new SDRconnectNativeBufferFactory();
        mPropertyUpdateHandler = createPropertyUpdateHandler();
        mNativeBufferFactory.setSamplesPerMillisecond(mSampleRate / 1000.0f);

        setMinimumFrequency(MINIMUM_FREQUENCY);
        setMaximumFrequency(MAXIMUM_FREQUENCY);
        setMiddleUnusableHalfBandwidth(0);
        setUsableBandwidthPercentage(USABLE_BANDWIDTH_PERCENT);

        // Initialize the frequency controller with the default sample rate
        try
        {
            mFrequencyController.setSampleRate(mSampleRate);
        }
        catch(SourceException se)
        {
            mLog.error("Error setting initial sample rate", se);
        }
    }

    /**
     * Constructs an instance with default host and port
     * @param tunerErrorListener to receive error notifications
     */
    public SDRconnectTunerController(ITunerErrorListener tunerErrorListener)
    {
        this(DEFAULT_HOST, DEFAULT_PORT, tunerErrorListener);
    }

    /**
     * Host address for SDRconnect
     */
    public String getHost()
    {
        return mHost;
    }

    /**
     * Port for SDRconnect WebSocket
     */
    public int getPort()
    {
        return mPort;
    }

    /**
     * Indicates if the WebSocket/controller is currently running.
     */
    public boolean isRunning()
    {
        return mRunning.get();
    }

    /**
     * Unique identifier for this tuner
     */
    public String getUniqueId()
    {
        return "SDRconnect-" + mHost + ":" + mPort;
    }

    public String getDeviceName()
    {
        return mDeviceName;
    }

    public void setDeviceName(String deviceName)
    {
        if(deviceName != null && !deviceName.isBlank())
        {
            mDeviceName = deviceName.trim();
        }
        else
        {
            mDeviceName = DEFAULT_DEVICE_NAME;
        }
    }

    /**
     * Seeds SDRconnect-specific startup settings that must be available before the initial connect sequence runs.
     * This intentionally avoids the base apply() path because that restores frequency through setFrequency(), which
     * requires a live WebSocket connection.
     */
    public void seedStartupConfiguration(SDRconnectTunerConfiguration config)
    {
        if(config != null)
        {
            setDeviceName(config.getDeviceName());
            mConfiguredSampleRate = config.getSampleRate();
            mConfiguredAntenna = config.getAntenna();
        }
    }

    @Override
    public TunerType getTunerType()
    {
        return TunerType.SDRCONNECT;
    }

    @Override
    public int getBufferSampleCount()
    {
        // SDRconnect sends ~100K samples per packet (400KB / 4 bytes per sample)
        return 100000;
    }

    @Override
    public void start() throws SourceException
    {
        mShouldBeRunning.set(true); // User wants us running
        mReconnectAttempts.set(0);
        doConnect();
    }

    /**
     * Internal connect method - used by both start() and reconnect()
     */
    private void doConnect() throws SourceException
    {
        if(mRunning.compareAndSet(false, true))
        {
            try
            {
                mLog.info("Connecting to SDRconnect at {}:{}", mHost, mPort);

                mHttpClient = HttpClient.newHttpClient();
                URI uri = URI.create("ws://" + mHost + ":" + mPort);

                CompletableFuture<WebSocket> future = mHttpClient.newWebSocketBuilder()
                        .buildAsync(uri, this);

                mWebSocket = future.get(5, TimeUnit.SECONDS);
                mLog.info("Connected to SDRconnect WebSocket");

                // Discover and select the expected device before enabling streaming.
                prepareDeviceDiscoveryLatch();
                queryProperty(SDRconnectProtocol.PROPERTY_VALID_DEVICES);
                queryProperty(SDRconnectProtocol.PROPERTY_ACTIVE_DEVICE);
                awaitLatch(mDeviceDiscoveryLatch.get(), 2, TimeUnit.SECONDS,
                    "SDRconnect device discovery");

                selectPreferredDevice();

                // Query current SDRconnect settings
                prepareSettingsLatch();
                queryProperty(SDRconnectProtocol.PROPERTY_VALID_DEVICES);
                queryProperty(SDRconnectProtocol.PROPERTY_ACTIVE_DEVICE);
                queryProperty(SDRconnectProtocol.PROPERTY_DEVICE_SAMPLE_RATE);
                queryProperty(SDRconnectProtocol.PROPERTY_DEVICE_CENTER_FREQUENCY);
                queryProperty(SDRconnectProtocol.PROPERTY_VALID_ANTENNAS);
                queryProperty(SDRconnectProtocol.PROPERTY_ACTIVE_ANTENNA);
                awaitLatch(mSettingsLatch.get(), 2, TimeUnit.SECONDS,
                    "SDRconnect initial settings");

                mLog.info("SDRconnect connected: {} MHz center, {} MHz sample rate",
                    String.format("%.3f", mCenterFrequency / 1e6),
                    String.format("%.1f", mSampleRate / 1e6));

                // Enable device stream first
                sendCommand(SDRconnectProtocol.EVENT_DEVICE_STREAM_ENABLE, "true");
                // SDRconnect needs a brief courtesy delay between enabling the device stream and IQ streaming.
                Thread.sleep(100);

                // Enable IQ streaming
                enableIqStream(true);

                // Re-apply configured settings now that the connection is established and the device
                // selection handshake has completed.
                if(mFrequencyController.getFrequency() != mCenterFrequency)
                {
                    setTunedFrequency(mFrequencyController.getFrequency());
                }
                if(mConfiguredSampleRate != mSampleRate)
                {
                    requestSampleRate(mConfiguredSampleRate);
                }
                if(!mConfiguredAntenna.isEmpty() && !mConfiguredAntenna.equals(mCurrentAntenna))
                {
                    requestAntenna(mConfiguredAntenna);
                }

                // Reset reconnect state on successful connection
                mReconnectAttempts.set(0);
                mReconnecting.set(false);
                mLog.info("SDRconnect IQ streaming started");
            }
            catch(TimeoutException te)
            {
                mRunning.set(false);
                throw new SourceException("Timed out connecting to SDRconnect at " + mHost + ":" + mPort, te);
            }
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                mRunning.set(false);
                throw new SourceException("Interrupted while connecting to SDRconnect at " + mHost + ":" + mPort, ie);
            }
            catch(Exception e)
            {
                mRunning.set(false);
                throw new SourceException("Failed to connect to SDRconnect at " + mHost + ":" + mPort, e);
            }
        }
    }

    private void prepareDeviceDiscoveryLatch()
    {
        mValidDevicesReceived.set(false);
        mActiveDeviceReceived.set(false);
        mDeviceDiscoveryLatch.set(new CountDownLatch(2));
    }

    private void prepareSettingsLatch()
    {
        mFrequencyReceived.set(false);
        mSampleRateReceived.set(false);
        mSettingsLatch.set(new CountDownLatch(2));
    }

    private void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit, String description)
        throws InterruptedException
    {
        if(latch != null && !latch.await(timeout, unit))
        {
            mLog.debug("{} did not fully complete within {} {}", description, timeout,
                unit.name().toLowerCase());
        }
    }

    private boolean isResolvedPropertyValue(String value)
    {
        return value != null && !value.isBlank() && !"Refreshing...".equalsIgnoreCase(value.trim());
    }

    private void markValidDevicesReceived(String value)
    {
        if(isResolvedPropertyValue(value) && mValidDevicesReceived.compareAndSet(false, true))
        {
            CountDownLatch latch = mDeviceDiscoveryLatch.get();
            if(latch != null)
            {
                latch.countDown();
            }
        }
    }

    private void markActiveDeviceReceived(String value)
    {
        if(isResolvedPropertyValue(value) && mActiveDeviceReceived.compareAndSet(false, true))
        {
            CountDownLatch latch = mDeviceDiscoveryLatch.get();
            if(latch != null)
            {
                latch.countDown();
            }
        }
    }

    private void markFrequencyReceived()
    {
        if(mFrequencyReceived.compareAndSet(false, true))
        {
            CountDownLatch latch = mSettingsLatch.get();
            if(latch != null)
            {
                latch.countDown();
            }
        }
    }

    private void markSampleRateReceived()
    {
        if(mSampleRateReceived.compareAndSet(false, true))
        {
            CountDownLatch latch = mSettingsLatch.get();
            if(latch != null)
            {
                latch.countDown();
            }
        }
    }


    @Override
    public void stop()
    {
        mShouldBeRunning.set(false); // User wants us stopped - don't reconnect
        stopReconnectExecutor();
        doDisconnect();
    }

    /**
     * Internal disconnect method
     */
    private void doDisconnect()
    {
        if(mRunning.compareAndSet(true, false))
        {
            mLog.info("Disconnecting from SDRconnect");

            try
            {
                // Disable IQ streaming
                if(mIqStreamEnabled.get())
                {
                    enableIqStream(false);
                }

                // Close WebSocket
                if(mWebSocket != null)
                {
                    mWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Shutdown");
                    mWebSocket = null;
                }

                if(mHttpClient instanceof AutoCloseable autoCloseable)
                {
                    autoCloseable.close();
                }

                mHttpClient = null;
            }
            catch(Exception e)
            {
                mLog.error("Error disconnecting from SDRconnect", e);
            }
        }
    }

    /**
     * Stop the reconnect executor if running
     */
    private void stopReconnectExecutor()
    {
        if(mReconnectExecutor != null && !mReconnectExecutor.isShutdown())
        {
            mReconnectExecutor.shutdownNow();
            mReconnectExecutor = null;
        }
    }

    /**
     * Schedule a reconnection attempt
     */
    private void scheduleReconnect()
    {
        if(!mShouldBeRunning.get())
        {
            mLog.debug("Not scheduling reconnect - tuner was stopped by user");
            return;
        }

        if(mReconnecting.compareAndSet(false, true))
        {
            int attempts = mReconnectAttempts.incrementAndGet();

            if(attempts > RECONNECT_MAX_ATTEMPTS)
            {
                mLog.error("SDRconnect reconnection failed after {} attempts - giving up", RECONNECT_MAX_ATTEMPTS);
                mReconnecting.set(false);
                setErrorMessage("SDRconnect reconnection failed after " + RECONNECT_MAX_ATTEMPTS + " attempts");
                return;
            }

            // Exponential backoff: 5s, 10s, 20s, 40s, 60s (capped)
            int delaySeconds = Math.min(RECONNECT_INITIAL_DELAY_SECONDS * (1 << (attempts - 1)), RECONNECT_MAX_DELAY_SECONDS);

            mLog.info("SDRconnect connection lost - reconnecting in {} seconds (attempt {}/{})",
                delaySeconds, attempts, RECONNECT_MAX_ATTEMPTS);

            stopReconnectExecutor();
            mReconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "sdrconnect-reconnect");
                thread.setDaemon(true);
                return thread;
            });
            mReconnectExecutor.schedule(this::attemptReconnect, delaySeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * Attempt to reconnect to SDRconnect
     */
    private void attemptReconnect()
    {
        if(!mShouldBeRunning.get())
        {
            mLog.debug("Aborting reconnect - tuner was stopped by user");
            mReconnecting.set(false);
            return;
        }

        mLog.info("Attempting to reconnect to SDRconnect...");

        try
        {
            doConnect();
            mLog.info("SDRconnect reconnection successful!");
        }
        catch(SourceException e)
        {
            mLog.warn("SDRconnect reconnection failed: {}", e.getMessage());
            mReconnecting.set(false);
            // Schedule another attempt
            scheduleReconnect();
        }
    }

    /**
     * Enable or disable IQ streaming
     */
    public void enableIqStream(boolean enable)
    {
        sendCommand(SDRconnectProtocol.EVENT_IQ_STREAM_ENABLE, enable ? "true" : "false");
        mIqStreamEnabled.set(enable);
    }

    /**
     * Send a command to SDRconnect
     */
    private void sendCommand(String eventType, String value)
    {
        if(mWebSocket != null)
        {
            // Some SDRconnect actions are command-style events rather than set_property updates.
            JsonObject msg = new JsonObject();
            msg.addProperty(SDRconnectProtocol.JSON_EVENT_TYPE, eventType);
            msg.addProperty(SDRconnectProtocol.JSON_PROPERTY, ""); // API requires property field
            msg.addProperty(SDRconnectProtocol.JSON_VALUE, value != null ? value : "");
            String json = mGson.toJson(msg);
            mWebSocket.sendText(json, true);
        }
    }

    /**
     * Query a property from SDRconnect
     */
    private void queryProperty(String property)
    {
        if(mWebSocket != null)
        {
            JsonObject msg = new JsonObject();
            msg.addProperty(SDRconnectProtocol.JSON_EVENT_TYPE, SDRconnectProtocol.EVENT_GET_PROPERTY);
            msg.addProperty(SDRconnectProtocol.JSON_PROPERTY, property);
            String json = mGson.toJson(msg);
            mWebSocket.sendText(json, true);
        }
    }

    /**
     * Set a property on SDRconnect
     */
    private void setProperty(String property, String value)
    {
        if(mWebSocket != null)
        {
            JsonObject msg = new JsonObject();
            msg.addProperty(SDRconnectProtocol.JSON_EVENT_TYPE, SDRconnectProtocol.EVENT_SET_PROPERTY);
            msg.addProperty(SDRconnectProtocol.JSON_PROPERTY, property);
            msg.addProperty(SDRconnectProtocol.JSON_VALUE, value);
            String json = mGson.toJson(msg);
            mLog.debug("SDRconnect set {} = {}", property, value);
            mWebSocket.sendText(json, true);
        }
        else
        {
            mLog.warn("Cannot set {} - not connected", property);
        }
    }

    /**
     * Selects the preferred SDRconnect device entry, preferring Full IQ mode when advertised.
     */
    private void selectPreferredDevice()
    {
        String preferredDevice = getPreferredDeviceName();
        sendCommand(SDRconnectProtocol.EVENT_SELECTED_DEVICE_NAME, preferredDevice);
        mLog.trace("Requested SDRconnect active device: {} (preferred mode: {})",
            preferredDevice, DEFAULT_NETWORK_MODE);
    }

    /**
     * Determines the best matching device entry from the valid_devices list.
     *
     * SDRconnect advertises devices as a comma-separated list of full selection strings, for example:
     * nRSP-ST (2405166650) (IQ Lite), nRSP-ST (2405166650) (Compact), nRSP-ST (2405166650) (Full IQ),
     * nRSP-ST 1 (IQ Lite), nRSP-ST 1 (Compact), nRSP-ST 1 (Full IQ), IQ File
     *
     * The configured selector may be blank, a friendly name such as "nRSP-ST 1", or a serial token such as
     * "2405166650". We resolve that selector to one of the advertised full strings and prefer the Full IQ variant
     * when multiple advertised modes match.
     */
    private String getPreferredDeviceName()
    {
        if(mValidDevices == null || mValidDevices.isBlank())
        {
            return mDeviceName;
        }

        String[] devices = mValidDevices.split(",");
        if(isAutomaticDeviceSelection())
        {
            return getFirstAvailableDeviceName(devices);
        }

        String fallback = mDeviceName;
        String normalizedDeviceName = mDeviceName.trim();

        for(String device : devices)
        {
            String trimmed = device.trim();

            if(trimmed.isEmpty())
            {
                continue;
            }

            if(matchesDeviceSelector(trimmed, normalizedDeviceName))
            {
                fallback = trimmed;

                if(prefersDefaultNetworkMode(trimmed))
                {
                    return trimmed;
                }
            }
        }

        return fallback;
    }

    /**
     * Indicates if the device selection should use the first advertised SDRconnect device.
     */
    private boolean isAutomaticDeviceSelection()
    {
        return mDeviceName == null || mDeviceName.isBlank();
    }

    /**
     * Selects the first advertised device, preferring the configured network mode variant when available.
     */
    private String getFirstAvailableDeviceName(String[] devices)
    {
        String fallback = DEFAULT_DEVICE_NAME;

        for(String device : devices)
        {
            String trimmed = device.trim();

            if(trimmed.isEmpty())
            {
                continue;
            }

            if(fallback.isBlank())
            {
                fallback = trimmed;
            }

            if(prefersDefaultNetworkMode(trimmed))
            {
                return trimmed;
            }
        }

        return fallback;
    }

    /**
     * Indicates if an advertised SDRconnect device entry matches the configured selector by friendly name or serial.
     */
    private boolean matchesDeviceSelector(String advertisedDevice, String configuredSelector)
    {
        if(configuredSelector == null || configuredSelector.isBlank())
        {
            return false;
        }

        String normalizedAdvertised = advertisedDevice.trim().toLowerCase();
        String normalizedSelector = configuredSelector.trim().toLowerCase();

        if(normalizedAdvertised.equals(normalizedSelector))
        {
            return true;
        }

        return normalizedAdvertised.contains(normalizedSelector);
    }

    /**
     * Indicates if an advertised SDRconnect entry matches the preferred network mode.
     */
    private boolean prefersDefaultNetworkMode(String advertisedDevice)
    {
        return advertisedDevice != null &&
            advertisedDevice.toLowerCase().contains(DEFAULT_NETWORK_MODE.toLowerCase());
    }

    @Override
    public void apply(TunerConfiguration config) throws SourceException
    {
        super.apply(config);

        if(config instanceof SDRconnectTunerConfiguration sdrconnectConfig)
        {
            seedStartupConfiguration(sdrconnectConfig);
        }
    }

    @Override
    public long getTunedFrequency() throws SourceException
    {
        return mCenterFrequency;
    }

    @Override
    public void setTunedFrequency(long frequency) throws SourceException
    {
        if(frequency < MINIMUM_FREQUENCY || frequency > MAXIMUM_FREQUENCY)
        {
            throw new SourceException("Frequency " + frequency + " is outside valid range");
        }

        // Only send request if we're not already at this frequency (within 100 kHz tolerance)
        // SDRconnect controls the actual frequency - we just request, it decides
        if(Math.abs(frequency - mCenterFrequency) > 100000)
        {
            setProperty(SDRconnectProtocol.PROPERTY_DEVICE_CENTER_FREQUENCY, String.valueOf(frequency));
            mLog.info("Requested SDRconnect frequency: {} Hz (current: {} Hz)", frequency, mCenterFrequency);
        }
        else
        {
            mLog.debug("Frequency {} Hz close enough to current {} Hz, not re-tuning", frequency, mCenterFrequency);
        }
    }

    @Override
    public double getCurrentSampleRate()
    {
        return mSampleRate;
    }

    /**
     * Sets the sample rate (called when SDRconnect reports the rate)
     */
    public void setSampleRate(int sampleRate)
    {
        mSampleRate = sampleRate;
        mNativeBufferFactory.setSamplesPerMillisecond(sampleRate / 1000.0f);
        try
        {
            mFrequencyController.setSampleRate(sampleRate);
        }
        catch(SourceException se)
        {
            mLog.error("Error setting sample rate on frequency controller", se);
        }
        if(mSampleRateChangeListener != null)
        {
            mSampleRateChangeListener.accept(sampleRate);
        }
    }

    /**
     * Request a sample rate change from SDRconnect
     * @param sampleRate in Hz (e.g., 2000000 for 2 MHz)
     */
    public void requestSampleRate(int sampleRate)
    {
        if(mFrequencyController.isSampleRateLocked())
        {
            mLog.warn("Ignoring SDRconnect sample rate change to {} Hz while the tuner sample rate is locked",
                sampleRate);
            return;
        }

        mLog.info("Requesting SDRconnect sample rate: {} Hz", sampleRate);
        setProperty(SDRconnectProtocol.PROPERTY_DEVICE_SAMPLE_RATE, String.valueOf(sampleRate));
    }

    /**
     * Request antenna selection from SDRconnect
     * @param antenna selection (e.g., "A", "B", "C", or "Hi-Z")
     */
    public void requestAntenna(String antenna)
    {
        mLog.info("Requesting SDRconnect antenna: {}", antenna);
        setProperty(SDRconnectProtocol.PROPERTY_ACTIVE_ANTENNA, antenna);
    }

    public String getCurrentAntenna()
    {
        return mCurrentAntenna;
    }

    public void setAntennaChangeListener(Consumer<String> listener)
    {
        mAntennaChangeListener = listener;
    }

    public void setSampleRateChangeListener(Consumer<Integer> listener)
    {
        mSampleRateChangeListener = listener;
    }

    public String[] getValidAntennas()
    {
        if(mValidAntennas == null || mValidAntennas.isBlank())
        {
            return new String[0];
        }
        return Arrays.stream(mValidAntennas.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
    }

    /**
     * Common sample rates supported by SDRplay devices (in Hz)
     */
    protected static final int[] SUPPORTED_SAMPLE_RATES = {
        62500,     // 62.5 KHz
        125000,    // 125 KHz
        250000,    // 250 KHz
        500000,    // 500 KHz
        1000000,   // 1 MHz
        2000000,   // 2 MHz
        5000000,   // 5 MHz
        6000000,   // 6 MHz
        7000000,   // 7 MHz
        8000000,   // 8 MHz
        9000000,   // 9 MHz
        10000000   // 10 MHz
    };

    // WebSocket.Listener implementation

    @Override
    public void onOpen(WebSocket webSocket)
    {
        mLog.info("SDRconnect WebSocket opened");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
    {
        mPartialTextBuffer.append(data);

        // Only process when we have the complete message
        if(last)
        {
            try
            {
                String json = mPartialTextBuffer.toString();
                mPartialTextBuffer.setLength(0);

                JsonObject msg = JsonParser.parseString(json).getAsJsonObject();

                String eventType = msg.has(SDRconnectProtocol.JSON_EVENT_TYPE) ?
                    msg.get(SDRconnectProtocol.JSON_EVENT_TYPE).getAsString() : "";
                String property = msg.has(SDRconnectProtocol.JSON_PROPERTY) ?
                    msg.get(SDRconnectProtocol.JSON_PROPERTY).getAsString() : "";
                String value = msg.has(SDRconnectProtocol.JSON_VALUE) ?
                    msg.get(SDRconnectProtocol.JSON_VALUE).getAsString() : "";
                if(SDRconnectProtocol.EVENT_PROPERTY_CHANGED.equals(eventType) ||
                    SDRconnectProtocol.EVENT_GET_PROPERTY_RESPONSE.equals(eventType))
                {
                    mPropertyUpdateHandler.handle(property, value);
                }
                else if(SDRconnectProtocol.EVENT_ERROR.equals(eventType))
                {
                    mLog.error("SDRconnect error: {}", json);
                }
            }
            catch(Exception e)
            {
                mLog.warn("Error parsing SDRconnect message: {}", e.getMessage());
                mPartialTextBuffer.setLength(0);
            }
        }

        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last)
    {
        try
        {
            if(!last)
            {
                mBinaryMessageAccumulator.append(data);
            }
            else
            {
                mBinaryMessageAccumulator.complete(data, buffer -> {
                    if(buffer.remaining() >= 2)
                    {
                        buffer.order(ByteOrder.LITTLE_ENDIAN);
                        int header = buffer.getShort() & 0xFFFF;

                        if(header == HEADER_IQ && buffer.remaining() > 0)
                        {
                            broadcast(mNativeBufferFactory.getBuffer(buffer, System.currentTimeMillis()));
                        }
                    }
                });
            }
        }
        catch(Exception e)
        {
            mLog.warn("Error processing SDRconnect binary data: {}", e.getMessage());
        }

        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
    {
        mLog.info("SDRconnect WebSocket closed: {} - {}", statusCode, reason);
        mRunning.set(false);
        mIqStreamEnabled.set(false);
        mWebSocket = null;

        // If this wasn't a normal closure and user wants us running, try to reconnect
        if(statusCode != WebSocket.NORMAL_CLOSURE && mShouldBeRunning.get() && !APPLICATION_SHUTTING_DOWN.get() &&
            !mReconnectScheduledFromError.getAndSet(false))
        {
            mLog.warn("SDRconnect connection lost unexpectedly - will attempt to reconnect");
            scheduleReconnect();
        }
        else
        {
            mReconnectScheduledFromError.set(false);
        }

        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error)
    {
        mLog.error("SDRconnect WebSocket error: {}", error.getMessage());
        mRunning.set(false);
        mIqStreamEnabled.set(false);
        mWebSocket = null;

        // If user wants us running, try to reconnect
        if(mShouldBeRunning.get() && !APPLICATION_SHUTTING_DOWN.get())
        {
            mReconnectScheduledFromError.set(true);
            mLog.warn("SDRconnect error - will attempt to reconnect");
            scheduleReconnect();
        }
        else if(!APPLICATION_SHUTTING_DOWN.get())
        {
            setErrorMessage("SDRconnect error: " + error.getMessage());
        }
    }

    private SDRconnectPropertyUpdateHandler createPropertyUpdateHandler()
    {
        return new SDRconnectPropertyUpdateHandler(mLog, new SDRconnectPropertyUpdateHandler.Callback()
        {
            @Override
            public long getCenterFrequency()
            {
                return mCenterFrequency;
            }

            @Override
            public void onCenterFrequencyChanged(long frequency)
            {
                mCenterFrequency = frequency;
                updateFrequencyController(frequency);
            }

            @Override
            public int getSampleRate()
            {
                return mSampleRate;
            }

            @Override
            public void onSampleRateChanged(int sampleRate)
            {
                setSampleRate(sampleRate);
            }

            @Override
            public String getValidAntennas()
            {
                return mValidAntennas;
            }

            @Override
            public String getActiveAntenna()
            {
                return mCurrentAntenna;
            }

            @Override
            public void onValidDevicesChanged(String validDevices)
            {
                mValidDevices = validDevices;
                markValidDevicesReceived(validDevices);
            }

            @Override
            public void onActiveDeviceChanged(String activeDevice)
            {
                markActiveDeviceReceived(activeDevice);
            }

            @Override
            public void onValidAntennasChanged(String validAntennas)
            {
                mValidAntennas = validAntennas;
            }

            @Override
            public void onActiveAntennaChanged(String activeAntenna)
            {
                mCurrentAntenna = activeAntenna;

                if(mAntennaChangeListener != null)
                {
                    mAntennaChangeListener.accept(activeAntenna);
                }
            }

            @Override
            public void markFrequencyReceived()
            {
                SDRconnectTunerController.this.markFrequencyReceived();
            }

            @Override
            public void markSampleRateReceived()
            {
                SDRconnectTunerController.this.markSampleRateReceived();
            }

            @Override
            public boolean shouldScheduleRecoveryReinitialization()
            {
                return mShouldBeRunning.get();
            }

            @Override
            public void scheduleRecoveryReinitialization()
            {
                SDRconnectTunerController.this.scheduleRecoveryReinitialization();
            }
        });
    }

    private void updateFrequencyController(long frequency)
    {
        try
        {
            mFrequencyController.setFrequency(frequency);
        }
        catch(SourceException se)
        {
            mLog.error("Error updating frequency controller", se);
        }
    }

    private void scheduleRecoveryReinitialization()
    {
        mLog.info("SDRconnect recovered - reinitializing tuner");
        ThreadPool.CACHED.execute(() -> {
            try
            {
                Thread.sleep(1000);

                mLog.info("Querying SDRconnect settings after recovery...");
                queryProperty(SDRconnectProtocol.PROPERTY_DEVICE_SAMPLE_RATE);
                queryProperty(SDRconnectProtocol.PROPERTY_DEVICE_CENTER_FREQUENCY);
                Thread.sleep(500);

                sendCommand(SDRconnectProtocol.EVENT_DEVICE_STREAM_ENABLE, "true");
                Thread.sleep(200);

                enableIqStream(true);
                Thread.sleep(100);

                mLog.info("SDRconnect recovery complete - IQ streaming re-enabled");
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                mLog.warn("Recovery interrupted");
            }
        });
    }

    private static class BinaryMessageAccumulator
    {
        // Observed fragmented SDRconnect IQ payloads are consistently about 1,000,034 bytes, so start
        // slightly above that steady-state size to avoid repeated growth on the fragmented binary path.
        private ByteBuffer mBuffer = ByteBuffer.allocate(1_048_576);

        private void append(ByteBuffer data)
        {
            int requiredCapacity = mBuffer.position() + data.remaining();

            if(requiredCapacity <= mBuffer.capacity())
            {
                mBuffer.put(data);
                return;
            }

            int newCapacity = Math.max(requiredCapacity, mBuffer.capacity() * 2);
            ByteBuffer expanded = ByteBuffer.allocate(newCapacity);
            mBuffer.flip();
            expanded.put(mBuffer);
            expanded.put(data);
            mBuffer = expanded;
        }

        private void complete(ByteBuffer finalFragment, Consumer<ByteBuffer> handler)
        {
            append(finalFragment);
            mBuffer.flip();
            try
            {
                handler.accept(mBuffer);
            }
            finally
            {
                mBuffer.clear();
            }
        }
    }

}
