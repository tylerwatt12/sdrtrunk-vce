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
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * SDRconnect tuner controller - connects to SDRconnect WebSocket API for IQ streaming.
 * Uses Java-WebSocket (org.java-websocket) for reliable long-lived connection handling,
 * including automatic ping/pong keep-alive and proper close/error surfacing.
 */
public class SDRconnectTunerController extends TunerController
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
    // Retune tolerance: 1/50th of sample rate, floored at 1 kHz. At the narrowest supported rate (62.5 kHz)
    // the divisor yields 1,250 Hz, so the floor is inert for all real sample rates.
    private static final long MINIMUM_RETUNE_TOLERANCE_HZ = 1_000L;
    private static final int RETUNE_TOLERANCE_DIVISOR = 50;

    // Auto-reconnection settings
    private static final int RECONNECT_INITIAL_DELAY_SECONDS = 5;
    private static final int RECONNECT_MAX_DELAY_SECONDS = 60;
    private static final int RECONNECT_MAX_ATTEMPTS = 10;
    private static final long IQ_LIVENESS_CHECK_INTERVAL_MS = 5_000;
    private static final long IQ_PACKET_STALL_WARNING_MS = 5_000;
    private static final long IQ_PACKET_STALL_RECOVERY_MS = 15_000;
    private static final AtomicBoolean APPLICATION_SHUTTING_DOWN = new AtomicBoolean(false);

    static
    {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> APPLICATION_SHUTTING_DOWN.set(true),
            "sdrconnect-controller-shutdown"));
    }

    private final String mHost;
    private final int mPort;
    private final String mLogPrefix;

    private SDRconnectWebSocketClient mWebSocket;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final AtomicBoolean mIqStreamEnabled = new AtomicBoolean(false);
    private final AtomicBoolean mShouldBeRunning = new AtomicBoolean(false);
    private final AtomicBoolean mReconnecting = new AtomicBoolean(false);
    private final AtomicBoolean mReconnectScheduledFromError = new AtomicBoolean(false);
    private final AtomicBoolean mIqStallRecoveryInProgress = new AtomicBoolean(false);
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
    private final AtomicLong mLastBinaryPacketTimestamp = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong mLastTextFrameTimestamp = new AtomicLong(0);    // any text frame received
    private final AtomicLong mLastTextMessageTimestamp = new AtomicLong(0);  // last meaningful (parsed) text message
    private final AtomicReference<String> mLastTextSummary = new AtomicReference<>("");
    private final AtomicLong mBinaryPacketCount = new AtomicLong();
    private ScheduledFuture<?> mIqLivenessMonitorFuture;

    private final SDRconnectPropertyUpdateHandler mPropertyUpdateHandler;
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
        mLogPrefix = "[" + mHost + ":" + mPort + "]";
        mNativeBufferFactory = new SDRconnectNativeBufferFactory();
        mPropertyUpdateHandler = createPropertyUpdateHandler();
        mNativeBufferFactory.setSamplesPerMillisecond(mSampleRate / 1000.0f);

        setMinimumFrequency(MINIMUM_FREQUENCY);
        setMaximumFrequency(MAXIMUM_FREQUENCY);
        setMiddleUnusableHalfBandwidth(0);
        setUsableBandwidthPercentage(USABLE_BANDWIDTH_PERCENT);

        try
        {
            mFrequencyController.setSampleRate(mSampleRate);
        }
        catch(SourceException se)
        {
            mLog.error("{} Error setting initial sample rate", mLogPrefix, se);
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

    public String getHost()
    {
        return mHost;
    }

    public int getPort()
    {
        return mPort;
    }

    public boolean isRunning()
    {
        return mRunning.get();
    }

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
        mShouldBeRunning.set(true);
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
                boolean reconnecting = mReconnecting.get();
                mLog.info("{} Connecting", mLogPrefix);

                URI uri = URI.create("ws://" + mHost + ":" + mPort);
                mWebSocket = new SDRconnectWebSocketClient(uri);
                // Java-WebSocket automatic ping/pong keep-alive — detects dead connections and fires onClose
                mWebSocket.setConnectionLostTimeout(30);

                if(!mWebSocket.connectBlocking(5, TimeUnit.SECONDS))
                {
                    mRunning.set(false);
                    throw new SourceException("Timed out connecting to SDRconnect at " + mHost + ":" + mPort);
                }

                mLog.info("{} Connected to WebSocket", mLogPrefix);

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

                mLog.info("{} Initial settings queried; applying startup configuration", mLogPrefix);

                // Enable device stream first
                sendCommand(SDRconnectProtocol.EVENT_DEVICE_STREAM_ENABLE, "true");
                // SDRconnect needs a brief courtesy delay between enabling the device stream and IQ streaming.
                Thread.sleep(100);

                // Enable IQ streaming
                enableIqStream(true);
                startIqLivenessMonitor();

                // Re-apply configured settings now that the connection is established and the device
                // selection handshake has completed.
                if(reconnecting && mFrequencyController.getFrequency() != mCenterFrequency)
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
                mIqStallRecoveryInProgress.set(false);
                mLog.info("{} IQ streaming started", mLogPrefix);
            }
            catch(SourceException se)
            {
                throw se;
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
            mLog.debug("{} {} did not fully complete within {} {}", mLogPrefix, description, timeout,
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
        mShouldBeRunning.set(false);
        mIqStallRecoveryInProgress.set(false);
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
            mLog.info("{} Disconnecting from SDRconnect", mLogPrefix);
            stopIqLivenessMonitor();

            try
            {
                if(mIqStreamEnabled.get())
                {
                    enableIqStream(false);
                }

                if(mWebSocket != null)
                {
                    mWebSocket.closeBlocking();
                    mWebSocket = null;
                }
            }
            catch(Exception e)
            {
                mLog.error("{} Error disconnecting from SDRconnect", mLogPrefix, e);
                mWebSocket = null;
            }
        }
    }

    private void stopReconnectExecutor()
    {
        if(mReconnectExecutor != null && !mReconnectExecutor.isShutdown())
        {
            mReconnectExecutor.shutdownNow();
            mReconnectExecutor = null;
        }
    }

    private void startIqLivenessMonitor()
    {
        stopIqLivenessMonitor();
        mLastBinaryPacketTimestamp.set(System.currentTimeMillis());

        mIqLivenessMonitorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(() -> {
            if(mRunning.get() && mIqStreamEnabled.get())
            {
                long now = System.currentTimeMillis();
                long lastBinaryTimestamp = mLastBinaryPacketTimestamp.get();
                long binaryAgeMs = now - lastBinaryTimestamp;

                if(binaryAgeMs >= IQ_PACKET_STALL_WARNING_MS)
                {
                    long lastBroadcastAgeMs = now - getLastNativeBufferBroadcastTimestamp();
                    long lastFrameTimestamp = mLastTextFrameTimestamp.get();
                    long lastMsgTimestamp = mLastTextMessageTimestamp.get();
                    String frameInfo = lastFrameTimestamp == 0 ? "no text frame received"
                        : (now - lastFrameTimestamp) + " ms ago";
                    String msgInfo = lastMsgTimestamp == 0 ? "no parsed message"
                        : (now - lastMsgTimestamp) + " ms ago [" + mLastTextSummary.get() + "]";
                    mLog.warn("{} No IQ binary packets received for {} ms while connected (packets received: {}, last broadcast {} ms ago, last text frame: {}, last parsed message: {})",
                        mLogPrefix, binaryAgeMs, mBinaryPacketCount.get(), lastBroadcastAgeMs, frameInfo, msgInfo);

                    if(binaryAgeMs >= IQ_PACKET_STALL_RECOVERY_MS)
                    {
                        triggerIqStallRecovery(binaryAgeMs);
                    }
                }
            }
        }, IQ_LIVENESS_CHECK_INTERVAL_MS, IQ_LIVENESS_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void triggerIqStallRecovery(long binaryAgeMs)
    {
        if(!mShouldBeRunning.get() || APPLICATION_SHUTTING_DOWN.get())
        {
            return;
        }

        if(mIqStallRecoveryInProgress.compareAndSet(false, true))
        {
            mLog.error("{} IQ stream stalled for {} ms while connection remains open - forcing reconnect",
                mLogPrefix, binaryAgeMs);

            ThreadPool.CACHED.execute(() -> {
                try
                {
                    doDisconnect();
                    scheduleReconnect();
                }
                finally
                {
                    if(!mReconnecting.get())
                    {
                        mIqStallRecoveryInProgress.set(false);
                    }
                }
            });
        }
    }

    private void stopIqLivenessMonitor()
    {
        if(mIqLivenessMonitorFuture != null)
        {
            mIqLivenessMonitorFuture.cancel(false);
            mIqLivenessMonitorFuture = null;
        }
    }

    private void scheduleReconnect()
    {
        if(!mShouldBeRunning.get())
        {
            mLog.debug("{} Not scheduling reconnect - tuner was stopped by user", mLogPrefix);
            return;
        }

        if(mReconnecting.compareAndSet(false, true))
        {
            int attempts = mReconnectAttempts.incrementAndGet();

            if(attempts > RECONNECT_MAX_ATTEMPTS)
            {
                mLog.error("{} SDRconnect reconnection failed after {} attempts - giving up", mLogPrefix,
                    RECONNECT_MAX_ATTEMPTS);
                mReconnecting.set(false);
                setErrorMessage("SDRconnect reconnection failed after " + RECONNECT_MAX_ATTEMPTS + " attempts");
                return;
            }

            // Exponential backoff: 5s, 10s, 20s, 40s, 60s (capped)
            int delaySeconds = Math.min(RECONNECT_INITIAL_DELAY_SECONDS * (1 << (attempts - 1)), RECONNECT_MAX_DELAY_SECONDS);

            mLog.info("{} SDRconnect connection lost - reconnecting in {} seconds (attempt {}/{})",
                mLogPrefix, delaySeconds, attempts, RECONNECT_MAX_ATTEMPTS);

            stopReconnectExecutor();
            mReconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "sdrconnect-reconnect");
                thread.setDaemon(true);
                return thread;
            });
            mReconnectExecutor.schedule(this::attemptReconnect, delaySeconds, TimeUnit.SECONDS);
        }
    }

    private void attemptReconnect()
    {
        if(!mShouldBeRunning.get())
        {
            mLog.debug("{} Aborting reconnect - tuner was stopped by user", mLogPrefix);
            mReconnecting.set(false);
            return;
        }

        mLog.info("{} Attempting to reconnect to SDRconnect...", mLogPrefix);

        try
        {
            doConnect();
            mLog.info("{} SDRconnect reconnection successful!", mLogPrefix);
        }
        catch(SourceException e)
        {
            mLog.warn("{} SDRconnect reconnection failed: {}", mLogPrefix, e.getMessage());
            mReconnecting.set(false);
            scheduleReconnect();
        }
    }

    public void enableIqStream(boolean enable)
    {
        sendCommand(SDRconnectProtocol.EVENT_IQ_STREAM_ENABLE, enable ? "true" : "false");
        mIqStreamEnabled.set(enable);
    }

    private void sendCommand(String eventType, String value)
    {
        SDRconnectWebSocketClient ws = mWebSocket;
        if(ws != null && ws.isOpen())
        {
            JsonObject msg = new JsonObject();
            msg.addProperty(SDRconnectProtocol.JSON_EVENT_TYPE, eventType);
            msg.addProperty(SDRconnectProtocol.JSON_PROPERTY, "");
            msg.addProperty(SDRconnectProtocol.JSON_VALUE, value != null ? value : "");
            ws.send(mGson.toJson(msg));
        }
    }

    private void queryProperty(String property)
    {
        SDRconnectWebSocketClient ws = mWebSocket;
        if(ws != null && ws.isOpen())
        {
            JsonObject msg = new JsonObject();
            msg.addProperty(SDRconnectProtocol.JSON_EVENT_TYPE, SDRconnectProtocol.EVENT_GET_PROPERTY);
            msg.addProperty(SDRconnectProtocol.JSON_PROPERTY, property);
            ws.send(mGson.toJson(msg));
        }
    }

    private void setProperty(String property, String value)
    {
        SDRconnectWebSocketClient ws = mWebSocket;
        if(ws != null && ws.isOpen())
        {
            JsonObject msg = new JsonObject();
            msg.addProperty(SDRconnectProtocol.JSON_EVENT_TYPE, SDRconnectProtocol.EVENT_SET_PROPERTY);
            msg.addProperty(SDRconnectProtocol.JSON_PROPERTY, property);
            msg.addProperty(SDRconnectProtocol.JSON_VALUE, value);
            ws.send(mGson.toJson(msg));
        }
        else
        {
            mLog.warn("{} Cannot set {} - not connected", mLogPrefix, property);
        }
    }

    private void selectPreferredDevice()
    {
        String preferredDevice = getPreferredDeviceName();
        sendCommand(SDRconnectProtocol.EVENT_SELECTED_DEVICE_NAME, preferredDevice);
        mLog.trace("{} Requested SDRconnect active device: {} (preferred mode: {})",
            mLogPrefix, preferredDevice, DEFAULT_NETWORK_MODE);
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

    private boolean isAutomaticDeviceSelection()
    {
        return mDeviceName == null || mDeviceName.isBlank();
    }

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

    private boolean prefersDefaultNetworkMode(String advertisedDevice)
    {
        return advertisedDevice != null &&
            advertisedDevice.toLowerCase().contains(DEFAULT_NETWORK_MODE.toLowerCase());
    }

    @Override
    public void apply(TunerConfiguration config) throws SourceException
    {
        // Force auto-PPM off before the base apply() path runs, so the FrequencyErrorCorrectionManager
        // is never enabled for SDRconnect regardless of what the stored config says.
        config.setAutoPPMCorrectionEnabled(false);
        super.apply(config);

        if(config instanceof SDRconnectTunerConfiguration sdrconnectConfig)
        {
            seedStartupConfiguration(sdrconnectConfig);
        }
    }

    @Override
    public void setFrequencyCorrection(double correction) throws SourceException
    {
        // SDRconnect manages frequency correction internally; PPM correction is not applied here.
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

        long retuneTolerance = getRetuneToleranceHz();
        long frequencyDelta = Math.abs(frequency - mCenterFrequency);

        if(frequencyDelta > retuneTolerance)
        {
            setProperty(SDRconnectProtocol.PROPERTY_DEVICE_CENTER_FREQUENCY, String.valueOf(frequency));
            mLog.info("{} Requested frequency: {} Hz (current: {} Hz)", mLogPrefix, frequency,
                mCenterFrequency);
        }
        else if(frequencyDelta > 0)
        {
            mLog.debug("{} Frequency {} Hz close enough to current {} Hz within {} Hz, not re-tuning",
                mLogPrefix, frequency, mCenterFrequency, retuneTolerance);
        }
    }

    private long getRetuneToleranceHz()
    {
        return Math.max(MINIMUM_RETUNE_TOLERANCE_HZ, mSampleRate / RETUNE_TOLERANCE_DIVISOR);
    }

    @Override
    public double getCurrentSampleRate()
    {
        return mSampleRate;
    }

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
            mLog.error("{} Error setting sample rate on frequency controller", mLogPrefix, se);
        }
        if(mSampleRateChangeListener != null)
        {
            mSampleRateChangeListener.accept(sampleRate);
        }
    }

    public void requestSampleRate(int sampleRate)
    {
        if(mFrequencyController.isSampleRateLocked())
        {
            mLog.warn("{} Ignoring sample rate change to {} Hz while the tuner sample rate is locked",
                mLogPrefix, sampleRate);
            return;
        }

        mLog.info("{} Requesting sample rate: {} Hz", mLogPrefix, sampleRate);
        setProperty(SDRconnectProtocol.PROPERTY_DEVICE_SAMPLE_RATE, String.valueOf(sampleRate));
    }

    public void requestAntenna(String antenna)
    {
        mLog.info("{} Requesting antenna: {}", mLogPrefix, antenna);
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

    private static final String SKIP_SIGNAL_POWER = "\"property\":\"" + SDRconnectProtocol.PROPERTY_SIGNAL_POWER + "\"";
    private static final String SKIP_SIGNAL_SNR   = "\"property\":\"" + SDRconnectProtocol.PROPERTY_SIGNAL_SNR + "\"";

    private void handleTextMessage(String json)
    {
        // Update raw transport liveness before any early-exit
        mLastTextFrameTimestamp.set(System.currentTimeMillis());

        // signal_power and signal_snr arrive with every IQ packet and have no consumer — skip full parse
        if(json.contains(SKIP_SIGNAL_POWER) || json.contains(SKIP_SIGNAL_SNR))
        {
            return;
        }

        try
        {
            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();

            String eventType = msg.has(SDRconnectProtocol.JSON_EVENT_TYPE) ?
                msg.get(SDRconnectProtocol.JSON_EVENT_TYPE).getAsString() : "";
            String property = msg.has(SDRconnectProtocol.JSON_PROPERTY) ?
                msg.get(SDRconnectProtocol.JSON_PROPERTY).getAsString() : "";

            mLastTextMessageTimestamp.set(System.currentTimeMillis());
            mLastTextSummary.set(property.isEmpty() ? eventType : eventType + "/" + property);

            String value = msg.has(SDRconnectProtocol.JSON_VALUE) ?
                msg.get(SDRconnectProtocol.JSON_VALUE).getAsString() : "";

            if(SDRconnectProtocol.EVENT_PROPERTY_CHANGED.equals(eventType) ||
                SDRconnectProtocol.EVENT_GET_PROPERTY_RESPONSE.equals(eventType))
            {
                mPropertyUpdateHandler.handle(property, value);
            }
            else if(SDRconnectProtocol.EVENT_ERROR.equals(eventType))
            {
                mLog.error("{} SDRconnect error: {}", mLogPrefix, json);
            }
        }
        catch(Exception e)
        {
            mLog.warn("{} Error parsing SDRconnect message: {}", mLogPrefix, e.getMessage());
        }
    }

    private void handleBinaryMessage(ByteBuffer buffer)
    {
        mIqStallRecoveryInProgress.set(false);
        mLastBinaryPacketTimestamp.set(System.currentTimeMillis());
        mBinaryPacketCount.incrementAndGet();

        try
        {
            if(buffer.remaining() >= 2)
            {
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                int header = buffer.getShort() & 0xFFFF;

                if(header == HEADER_IQ && buffer.remaining() > 0)
                {
                    broadcast(mNativeBufferFactory.getBuffer(buffer, System.currentTimeMillis()));
                }
            }
        }
        catch(Exception e)
        {
            mLog.warn("{} Error processing SDRconnect binary data: {}", mLogPrefix, e.getMessage());
        }
    }

    private void handleClose(int code, String reason, boolean remote)
    {
        mLog.info("{} WebSocket closed: {} - {} (remote: {})", mLogPrefix, code, reason, remote);
        mRunning.set(false);
        mIqStreamEnabled.set(false);
        stopIqLivenessMonitor();
        mWebSocket = null;
        mIqStallRecoveryInProgress.set(false);

        // 1000 = normal closure initiated by us; reconnect on any other code if user wants running
        if(code != org.java_websocket.framing.CloseFrame.NORMAL && mShouldBeRunning.get()
            && !APPLICATION_SHUTTING_DOWN.get()
            && !mReconnectScheduledFromError.getAndSet(false))
        {
            mLog.warn("{} SDRconnect connection lost unexpectedly - will attempt to reconnect", mLogPrefix);
            scheduleReconnect();
        }
        else
        {
            mReconnectScheduledFromError.set(false);
        }
    }

    private void handleError(Exception ex)
    {
        mLog.error("{} SDRconnect WebSocket error: {}", mLogPrefix,
            ex != null ? ex.getMessage() : "unknown");
        mRunning.set(false);
        mIqStreamEnabled.set(false);
        mWebSocket = null;
        mIqStallRecoveryInProgress.set(false);

        if(mShouldBeRunning.get() && !APPLICATION_SHUTTING_DOWN.get())
        {
            mReconnectScheduledFromError.set(true);
            mLog.warn("{} SDRconnect error - will attempt to reconnect", mLogPrefix);
            scheduleReconnect();
        }
        else if(!APPLICATION_SHUTTING_DOWN.get())
        {
            setErrorMessage("SDRconnect error: " + (ex != null ? ex.getMessage() : "unknown"));
        }
    }

    private SDRconnectPropertyUpdateHandler createPropertyUpdateHandler()
    {
        return new SDRconnectPropertyUpdateHandler(mLog, mLogPrefix, new SDRconnectPropertyUpdateHandler.Callback()
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
            mLog.error("{} Error updating frequency controller", mLogPrefix, se);
        }
    }

    private void scheduleRecoveryReinitialization()
    {
        mLog.info("{} SDRconnect recovered - reinitializing tuner", mLogPrefix);
        ThreadPool.CACHED.execute(() -> {
            try
            {
                Thread.sleep(1000);

                mLog.info("{} Querying SDRconnect settings after recovery...", mLogPrefix);
                queryProperty(SDRconnectProtocol.PROPERTY_DEVICE_SAMPLE_RATE);
                queryProperty(SDRconnectProtocol.PROPERTY_DEVICE_CENTER_FREQUENCY);
                Thread.sleep(500);

                sendCommand(SDRconnectProtocol.EVENT_DEVICE_STREAM_ENABLE, "true");
                Thread.sleep(200);

                enableIqStream(true);
                Thread.sleep(100);

                mLog.info("{} SDRconnect recovery complete - IQ streaming re-enabled", mLogPrefix);
            }
            catch(InterruptedException _)
            {
                Thread.currentThread().interrupt();
                mLog.warn("{} Recovery interrupted", mLogPrefix);
            }
        });
    }

    /**
     * Java-WebSocket client inner class. Delegates all callbacks to the enclosing controller's
     * handle* methods, keeping the WebSocket library boundary contained here.
     */
    private class SDRconnectWebSocketClient extends WebSocketClient
    {
        SDRconnectWebSocketClient(URI serverUri)
        {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake)
        {
            // Connection is established; doConnect() continues after connectBlocking() returns.
        }

        @Override
        public void onMessage(String message)
        {
            handleTextMessage(message);
        }

        @Override
        public void onMessage(ByteBuffer bytes)
        {
            // Java-WebSocket delivers fully reassembled binary messages — no accumulator needed.
            handleBinaryMessage(bytes);
        }

        @Override
        public void onClose(int code, String reason, boolean remote)
        {
            handleClose(code, reason, remote);
        }

        @Override
        public void onError(Exception ex)
        {
            handleError(ex);
        }
    }
}
