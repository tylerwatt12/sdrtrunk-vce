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
import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.util.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SDRconnect tuner controller - connects to SDRconnect WebSocket API for IQ streaming
 */
public class SDRconnectTunerController extends TunerController implements WebSocket.Listener
{
    private static final Logger mLog = LoggerFactory.getLogger(SDRconnectTunerController.class);

    // Binary message headers from SDRconnect
    private static final int HEADER_AUDIO = 0x0001;
    private static final int HEADER_IQ = 0x0002;
    private static final int HEADER_SPECTRUM = 0x0003;
    // Default connection settings
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 5454;
    public static final String DEFAULT_DEVICE_NAME = "nRSP-ST 1";
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
    private final AtomicInteger mReconnectAttempts = new AtomicInteger(0);
    private ScheduledExecutorService mReconnectExecutor;
    private volatile boolean mLastStartedState = false; // Track SDRconnect's started state to detect recovery

    private int mSampleRate = DEFAULT_SAMPLE_RATE;
    private long mCenterFrequency = DEFAULT_FREQUENCY;
    private String mValidDevices = "";
    private String mActiveDevice = "";
    private String mDeviceName = DEFAULT_DEVICE_NAME;
    private final Gson mGson = new Gson();

    // Buffer for accumulating partial WebSocket messages
    private ByteBuffer mPartialBuffer;
    private StringBuilder mPartialTextBuffer;
    private final SDRconnectNativeBufferFactory mNativeBufferFactory;
    private volatile CountDownLatch mDeviceDiscoveryLatch;
    private volatile CountDownLatch mSettingsLatch;
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
        mNativeBufferFactory.setSamplesPerMillisecond((float)mSampleRate / 1000.0f);

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
                queryProperty("valid_devices");
                queryProperty("active_device");
                awaitLatch(mDeviceDiscoveryLatch, 2, TimeUnit.SECONDS,
                    "SDRconnect device discovery");

                selectPreferredDevice();

                // Query current SDRconnect settings
                prepareSettingsLatch();
                queryProperty("valid_devices");
                queryProperty("active_device");
                queryProperty("device_sample_rate");
                queryProperty("device_center_frequency");
                awaitLatch(mSettingsLatch, 2, TimeUnit.SECONDS,
                    "SDRconnect initial settings");

                mLog.info("SDRconnect connected: {} MHz center, {} MHz sample rate",
                    String.format("%.3f", mCenterFrequency / 1e6),
                    String.format("%.1f", mSampleRate / 1e6));

                // Enable device stream first
                sendCommand("device_stream_enable", "true");
                // SDRconnect needs a brief courtesy delay between enabling the device stream and IQ streaming.
                Thread.sleep(100);

                // Enable IQ streaming
                enableIqStream(true);

                // Re-apply the configured center frequency now that the connection is established and the device
                // selection handshake has completed.
                if(mFrequencyController.getFrequency() != mCenterFrequency)
                {
                    setTunedFrequency(mFrequencyController.getFrequency());
                }

                // Reset reconnect state on successful connection
                mReconnectAttempts.set(0);
                mReconnecting.set(false);
                mLastStartedState = true; // Assume started since we just enabled streaming

                mLog.info("SDRconnect IQ streaming started");
            }
            catch(TimeoutException te)
            {
                mRunning.set(false);
                throw new SourceException("Timed out connecting to SDRconnect at " + mHost + ":" + mPort, te);
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
        mDeviceDiscoveryLatch = new CountDownLatch(2);
    }

    private void prepareSettingsLatch()
    {
        mFrequencyReceived.set(false);
        mSampleRateReceived.set(false);
        mSettingsLatch = new CountDownLatch(2);
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
            CountDownLatch latch = mDeviceDiscoveryLatch;
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
            CountDownLatch latch = mDeviceDiscoveryLatch;
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
            CountDownLatch latch = mSettingsLatch;
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
            CountDownLatch latch = mSettingsLatch;
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
        sendCommand("iq_stream_enable", enable ? "true" : "false");
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
            msg.addProperty("event_type", eventType);
            msg.addProperty("property", ""); // API requires property field
            msg.addProperty("value", value != null ? value : "");
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
            msg.addProperty("event_type", "get_property");
            msg.addProperty("property", property);
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
            msg.addProperty("event_type", "set_property");
            msg.addProperty("property", property);
            msg.addProperty("value", value);
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
        sendCommand("selected_device_name", preferredDevice);
        mLog.trace("Requested SDRconnect active device: {} (preferred mode: {})",
            preferredDevice, DEFAULT_NETWORK_MODE);
    }

    /**
     * Determines the best matching device entry from the valid_devices list.
     */
    private String getPreferredDeviceName()
    {
        if(mValidDevices == null || mValidDevices.isBlank())
        {
            return mDeviceName;
        }

        String[] devices = mValidDevices.split(",");
        String fallback = mDeviceName;

        for(String device : devices)
        {
            String trimmed = device.trim();

            if(trimmed.equalsIgnoreCase(mDeviceName))
            {
                fallback = trimmed;
            }

            if(trimmed.equalsIgnoreCase(mDeviceName + ":" + DEFAULT_NETWORK_MODE))
            {
                return trimmed;
            }

            if(trimmed.toLowerCase().contains(mDeviceName.toLowerCase()) &&
                trimmed.toLowerCase().contains(DEFAULT_NETWORK_MODE.toLowerCase()))
            {
                return trimmed;
            }
        }

        return fallback;
    }

    @Override
    public void apply(io.github.dsheirer.source.tuner.configuration.TunerConfiguration config) throws SourceException
    {
        super.apply(config);

        if(config instanceof SDRconnectTunerConfiguration sdrconnectConfig)
        {
            setDeviceName(sdrconnectConfig.getDeviceName());
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
            setProperty("device_center_frequency", String.valueOf(frequency));
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
        mNativeBufferFactory.setSamplesPerMillisecond((float)sampleRate / 1000.0f);
        try
        {
            mFrequencyController.setSampleRate(sampleRate);
        }
        catch(SourceException se)
        {
            mLog.error("Error setting sample rate on frequency controller", se);
        }
    }

    /**
     * Force set the frequency on SDRconnect, bypassing tolerance checks.
     * Use this when the user explicitly wants to change the center frequency.
     * SDRconnect may require stopping the stream before changing frequency.
     * @param frequency in Hz
     */
    public void forceSetFrequency(long frequency)
    {
        if(frequency < MINIMUM_FREQUENCY || frequency > MAXIMUM_FREQUENCY)
        {
            mLog.error("Frequency {} Hz outside valid range", frequency);
            return;
        }

        mLog.info("Tuning SDRconnect to {} MHz", String.format("%.3f", frequency / 1e6));

        try
        {
            // Stop IQ stream before changing frequency (required by SDRconnect)
            enableIqStream(false);
            Thread.sleep(100);

            // Change frequency
            setProperty("device_center_frequency", String.valueOf(frequency));
            Thread.sleep(200);

            // Query to verify
            queryProperty("device_center_frequency");
            Thread.sleep(200);

            // Restart IQ stream
            enableIqStream(true);
        }
        catch(InterruptedException e)
        {
            mLog.warn("Interrupted during frequency change");
        }
    }

    /**
     * Request a sample rate change from SDRconnect
     * @param sampleRate in Hz (e.g., 2000000 for 2 MHz)
     */
    public void requestSampleRate(int sampleRate)
    {
        mLog.info("Requesting SDRconnect sample rate: {} Hz", sampleRate);
        setProperty("device_sample_rate", String.valueOf(sampleRate));
    }

    /**
     * Request antenna selection from SDRconnect
     * @param antenna selection (e.g., "A", "B", "C", or "Hi-Z")
     */
    public void requestAntenna(String antenna)
    {
        mLog.info("Requesting SDRconnect antenna: {}", antenna);
        setProperty("antenna_select", antenna);
    }

    /**
     * Common sample rates supported by SDRplay devices (in Hz)
     */
    public static final int[] SUPPORTED_SAMPLE_RATES = {
        2000000,   // 2 MHz
        3000000,   // 3 MHz
        4000000,   // 4 MHz
        5000000,   // 5 MHz
        6000000,   // 6 MHz
        7000000,   // 7 MHz
        8000000,   // 8 MHz
        9000000,   // 9 MHz
        10000000   // 10 MHz
    };

    /**
     * Antenna options for SDRplay devices
     */
    public static final String[] ANTENNA_OPTIONS = {"A", "B", "C", "Hi-Z"};

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
        // Accumulate text data
        if(mPartialTextBuffer == null)
        {
            mPartialTextBuffer = new StringBuilder();
        }
        mPartialTextBuffer.append(data);

        // Only process when we have the complete message
        if(last)
        {
            try
            {
                String json = mPartialTextBuffer.toString();
                mPartialTextBuffer = null;

                JsonObject msg = JsonParser.parseString(json).getAsJsonObject();

                String eventType = msg.has("event_type") ? msg.get("event_type").getAsString() : "";
                String property = msg.has("property") ? msg.get("property").getAsString() : "";
                String value = msg.has("value") ? msg.get("value").getAsString() : "";
                if("property_changed".equals(eventType) || "get_property_response".equals(eventType))
                {
                    handlePropertyUpdate(property, value);
                }
                else if("error".equals(eventType))
                {
                    mLog.error("SDRconnect error: {}", json);
                }
            }
            catch(Exception e)
            {
                mLog.warn("Error parsing SDRconnect message: {}", e.getMessage());
                mPartialTextBuffer = null;
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
            if(data.remaining() >= 2)
            {
                // Handle partial messages
                ByteBuffer buffer;
                if(mPartialBuffer != null)
                {
                    // Combine with previous partial data
                    buffer = ByteBuffer.allocate(mPartialBuffer.remaining() + data.remaining());
                    buffer.put(mPartialBuffer);
                    buffer.put(data);
                    buffer.flip();
                    mPartialBuffer = null;
                }
                else
                {
                    buffer = data;
                }

                if(!last)
                {
                    // Store partial message for later
                    mPartialBuffer = ByteBuffer.allocate(buffer.remaining());
                    mPartialBuffer.put(buffer);
                    mPartialBuffer.flip();
                }
                else
                {
                    // Process complete message
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    int header = buffer.getShort() & 0xFFFF;

                    if(header == HEADER_IQ)
                    {
                        processIqData(buffer);
                    }
                }
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
        if(statusCode != WebSocket.NORMAL_CLOSURE && mShouldBeRunning.get() && !APPLICATION_SHUTTING_DOWN.get())
        {
            mLog.warn("SDRconnect connection lost unexpectedly - will attempt to reconnect");
            scheduleReconnect();
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
            mLog.warn("SDRconnect error - will attempt to reconnect");
            scheduleReconnect();
        }
        else if(!APPLICATION_SHUTTING_DOWN.get())
        {
            setErrorMessage("SDRconnect error: " + error.getMessage());
        }
    }

    /**
     * Handle property updates from SDRconnect
     */
    private void handlePropertyUpdate(String property, String value)
    {
        try
        {
            switch(property)
            {
                case "device_center_frequency":
                    long newFrequency = Long.parseLong(value);
                    markFrequencyReceived();
                    if(newFrequency != mCenterFrequency)
                    {
                        mLog.info("SDRconnect frequency changed: {} MHz", String.format("%.3f", newFrequency / 1e6));
                        mCenterFrequency = newFrequency;
                        try
                        {
                            mFrequencyController.setFrequency(newFrequency);
                        }
                        catch(SourceException se)
                        {
                            mLog.error("Error updating frequency controller", se);
                        }
                    }
                    break;

                case "device_sample_rate":
                    int newSampleRate = Integer.parseInt(value);
                    markSampleRateReceived();
                    if(newSampleRate != mSampleRate)
                    {
                        mLog.info("SDRconnect sample rate changed: {} MHz", String.format("%.1f", newSampleRate / 1e6));
                        setSampleRate(newSampleRate);
                    }
                    break;

                case "started":
                    boolean started = "true".equalsIgnoreCase(value);

                    // Detect recovery: SDRconnect went from stopped to started
                    if(started && !mLastStartedState && mShouldBeRunning.get())
                    {
                        mLog.info("SDRconnect recovered - reinitializing tuner");
                        ThreadPool.CACHED.execute(() -> {
                            try
                            {
                                // Wait for SDRconnect to fully initialize
                                Thread.sleep(1000);

                                // Re-query sample rate and frequency to update channelizer
                                mLog.info("Querying SDRconnect settings after recovery...");
                                queryProperty("device_sample_rate");
                                queryProperty("device_center_frequency");
                                Thread.sleep(500);

                                // Enable device streaming
                                sendCommand("device_stream_enable", "true");
                                Thread.sleep(200);

                                // Enable IQ streaming
                                enableIqStream(true);
                                Thread.sleep(100);

                                mLog.info("SDRconnect recovery complete - IQ streaming re-enabled");
                            }
                            catch(InterruptedException e)
                            {
                                mLog.warn("Recovery interrupted");
                            }
                        });
                    }
                    mLastStartedState = started;
                    break;

                case "can_control":
                    break;

                case "valid_devices":
                    mValidDevices = value;
                    markValidDevicesReceived(value);
                    break;

                case "active_device":
                    mActiveDevice = value;
                    markActiveDeviceReceived(value);
                    break;

                // Ignore high-frequency status updates to reduce log spam
                case "signal_power":
                case "signal_snr":
                case "rds_ps":
                case "rds_pi":
                case "rds_pty":
                    // Silently ignore these frequent updates
                    break;

                default:
                    break;
            }
        }
        catch(NumberFormatException e)
        {
            mLog.warn("Error parsing property {} = {}", property, value);
        }
    }

    /**
     * Process IQ data from SDRconnect
     */
    private void processIqData(ByteBuffer buffer)
    {
        if(buffer.remaining() > 0)
        {
            // Create a copy of the buffer for the native buffer factory
            ByteBuffer copy = ByteBuffer.allocate(buffer.remaining());
            copy.put(buffer);
            copy.flip();

            // Create native buffer and broadcast to listeners
            long timestamp = System.currentTimeMillis();
            INativeBuffer nativeBuffer = mNativeBufferFactory.getBuffer(copy, timestamp);
            broadcast(nativeBuffer);
        }
    }
}
