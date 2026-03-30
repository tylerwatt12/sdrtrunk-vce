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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.properties.SystemProperties;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.manager.IDiscoveredTunerStatusListener;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.DiscoveredTunerModel;
import io.github.dsheirer.util.ThreadPool;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates SDRconnect-specific startup, readiness probing, device assignment, and optional headless lifecycle
 * management so that {@link TunerManager} only coordinates the high-level tuner workflow.
 */
public class SDRconnectTunerManager
{
    private static final Logger mLog = LoggerFactory.getLogger(SDRconnectTunerManager.class);
    private static final String SDRCONNECT_HEADLESS_PATH_PROPERTY = "sdrconnect.headless.path";
    private static final String SDRCONNECT_HEADLESS_AUTOSTART_PROPERTY = "sdrconnect.headless.autostart";
    private static final String SDRCONNECT_HEADLESS_START_DELAY_MS_PROPERTY = "sdrconnect.headless.start.delay.ms";
    // macOS default; other platforms should configure the executable path explicitly via system properties/preferences.
    private static final String DEFAULT_SDRCONNECT_HEADLESS_PATH = "/Applications/SDRconnect.app/Contents/MacOS/SDRconnect_headless";
    private static final int SDRCONNECT_HEADLESS_START_TIMEOUT_MS = 5000;
    private static final int SDRCONNECT_HEADLESS_START_RETRY_INTERVAL_MS = 250;
    private static final int DEFAULT_SDRCONNECT_HEADLESS_START_DELAY_MS = 15000;

    private final UserPreferences mUserPreferences;
    private final DiscoveredTunerModel mDiscoveredTunerModel;
    private final TunerConfigurationManager mTunerConfigurationManager;
    private final IDiscoveredTunerStatusListener mTunerStatusListener;
    private final Map<Integer, Process> mManagedSDRconnectProcesses = new HashMap<>();
    private final Thread mManagedSDRconnectShutdownHook;
    private final HttpClient mSDRconnectReadyProbeHttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SDRconnectTunerManager(UserPreferences userPreferences, DiscoveredTunerModel discoveredTunerModel,
                                  TunerConfigurationManager tunerConfigurationManager,
                                  IDiscoveredTunerStatusListener tunerStatusListener)
    {
        mUserPreferences = userPreferences;
        mDiscoveredTunerModel = discoveredTunerModel;
        mTunerConfigurationManager = tunerConfigurationManager;
        mTunerStatusListener = tunerStatusListener;
        mManagedSDRconnectShutdownHook = new Thread(this::stopManagedSDRconnectProcesses,
            "sdrconnect-headless-shutdown");
        Runtime.getRuntime().addShutdownHook(mManagedSDRconnectShutdownHook);
    }

    public void discoverConfiguredTuners()
    {
        ChannelizerType channelizerType = mUserPreferences.getTunerPreference().getChannelizerType();
        List<TunerConfiguration> tunerConfigurations = mTunerConfigurationManager.getTunerConfigurations(TunerType.SDRCONNECT);
        Map<String, SDRconnectEndpointReadiness> readinessByEndpoint = prepareConfiguredSDRconnectEndpoints(tunerConfigurations);
        Map<String, String> runtimeDeviceAssignments =
            resolveConfiguredSDRconnectDeviceAssignments(tunerConfigurations, readinessByEndpoint);

        if(!tunerConfigurations.isEmpty())
        {
            mLog.info("Discovered [{}] SDRconnect tuners from saved configurations", tunerConfigurations.size());
        }

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig)
            {
                SDRconnectEndpointReadiness readiness = readinessByEndpoint.get(
                    getSDRconnectEndpointKey(sdrconnectConfig.getHost(), sdrconnectConfig.getPort()));
                boolean available = readiness != null && readiness.isReady();
                boolean disabled = mTunerConfigurationManager.isDisabled(new DiscoveredSDRconnectTuner(
                    sdrconnectConfig.getHost(), sdrconnectConfig.getPort(), sdrconnectConfig.getDeviceName(),
                    channelizerType));
                DiscoveredSDRconnectTuner discoveredTuner =
                    new DiscoveredSDRconnectTuner(sdrconnectConfig.getHost(), sdrconnectConfig.getPort(),
                        sdrconnectConfig.getDeviceName(), channelizerType);

                discoveredTuner.setTunerConfiguration(sdrconnectConfig);
                discoveredTuner.setRuntimeDeviceName(runtimeDeviceAssignments.get(sdrconnectConfig.getUniqueID()));
                discoveredTuner.addTunerStatusListener(mTunerStatusListener);

                if(disabled)
                {
                    mLog.info("SDRconnect configured at {}:{} is disabled", sdrconnectConfig.getHost(),
                        sdrconnectConfig.getPort());
                    discoveredTuner.setEnabled(false);
                }
                else if(available)
                {
                    mLog.info("SDRconnect detected at {}:{} - auto-starting tuner", sdrconnectConfig.getHost(),
                        sdrconnectConfig.getPort());
                }
                else
                {
                    mLog.warn("SDRconnect not available at {}:{} - tuner remains enabled and can be restarted from the UI",
                        sdrconnectConfig.getHost(), sdrconnectConfig.getPort());
                }

                mLog.info("SDRconnect Tuner Added: {}", discoveredTuner);
                mDiscoveredTunerModel.addDiscoveredTuner(discoveredTuner);

                if(!disabled && available)
                {
                    ThreadPool.CACHED.execute(() -> {
                        discoveredTuner.start();

                        if(discoveredTuner.hasTuner())
                        {
                            mDiscoveredTunerModel.tunerBecameAvailable(discoveredTuner);
                        }
                    });
                }
                else if(!disabled)
                {
                    discoveredTuner.setErrorMessage("SDRconnect is not available at " + sdrconnectConfig.getHost() +
                        ":" + sdrconnectConfig.getPort());
                }
            }
        }
    }

    public void autoDiscoverTuners()
    {
        List<TunerConfiguration> existing = mTunerConfigurationManager.getTunerConfigurations(TunerType.SDRCONNECT);
        if(!existing.isEmpty())
        {
            return;
        }

        String defaultHost = "127.0.0.1";
        int defaultPort = 5454;

        if(probeSDRconnect(defaultHost, defaultPort))
        {
            mLog.info("Auto-discovered SDRconnect at {}:{}", defaultHost, defaultPort);

            ChannelizerType channelizerType = mUserPreferences.getTunerPreference().getChannelizerType();
            SDRconnectTunerConfiguration config = SDRconnectTunerConfiguration.create(defaultHost, defaultPort);
            mTunerConfigurationManager.addTunerConfiguration(config);

            DiscoveredSDRconnectTuner discoveredTuner = new DiscoveredSDRconnectTuner(defaultHost, defaultPort,
                channelizerType);
            discoveredTuner.setTunerConfiguration(config);
            discoveredTuner.addTunerStatusListener(mTunerStatusListener);

            mLog.info("SDRconnect auto-discovered and enabled: {}", discoveredTuner);
            mDiscoveredTunerModel.addDiscoveredTuner(discoveredTuner);

            ThreadPool.CACHED.execute(() -> {
                discoveredTuner.start();

                if(discoveredTuner.hasTuner())
                {
                    mDiscoveredTunerModel.tunerBecameAvailable(discoveredTuner);
                }
            });
        }
    }

    public void stop()
    {
        stopManagedSDRconnectProcesses();

        try
        {
            Runtime.getRuntime().removeShutdownHook(mManagedSDRconnectShutdownHook);
        }
        catch(IllegalStateException ignored)
        {
        }

        if(mSDRconnectReadyProbeHttpClient instanceof AutoCloseable autoCloseable)
        {
            try
            {
                autoCloseable.close();
            }
            catch(Exception e)
            {
                mLog.debug("Unable to close SDRconnect readiness probe HTTP client", e);
            }
        }
    }

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

    private Map<String, SDRconnectEndpointReadiness> prepareConfiguredSDRconnectEndpoints(List<TunerConfiguration> tunerConfigurations)
    {
        Map<String, SDRconnectEndpointReadiness> readinessByEndpoint = new HashMap<>();
        Map<String, String> endpointHosts = new HashMap<>();
        Map<String, Integer> endpointPorts = new HashMap<>();

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig)
            {
                String endpointKey = getSDRconnectEndpointKey(sdrconnectConfig.getHost(), sdrconnectConfig.getPort());

                if(probeSDRconnect(sdrconnectConfig.getHost(), sdrconnectConfig.getPort()))
                {
                    endpointHosts.put(endpointKey, sdrconnectConfig.getHost());
                    endpointPorts.put(endpointKey, sdrconnectConfig.getPort());
                }
                else if(isLocalSDRconnectHost(sdrconnectConfig.getHost()) &&
                    launchManagedSDRconnectProcess(sdrconnectConfig.getPort()))
                {
                    endpointHosts.put(endpointKey, sdrconnectConfig.getHost());
                    endpointPorts.put(endpointKey, sdrconnectConfig.getPort());
                }
            }
        }

        if(!endpointPorts.isEmpty())
        {
            int timeoutMs = SystemProperties.getInstance().get(SDRCONNECT_HEADLESS_START_DELAY_MS_PROPERTY,
                DEFAULT_SDRCONNECT_HEADLESS_START_DELAY_MS);
            mLog.info("Waiting up to {} ms for SDRconnect readiness on endpoint(s) {}", timeoutMs, endpointPorts.keySet());

            Map<String, CompletableFuture<SDRconnectEndpointReadiness>> readinessChecks = new HashMap<>();

            for(Map.Entry<String, Integer> endpoint : endpointPorts.entrySet())
            {
                String host = endpointHosts.get(endpoint.getKey());
                int port = endpoint.getValue();
                readinessChecks.put(endpoint.getKey(), CompletableFuture.supplyAsync(
                    () -> waitForReadySDRconnect(host, port, timeoutMs), ThreadPool.CACHED));
            }

            for(Map.Entry<String, CompletableFuture<SDRconnectEndpointReadiness>> readinessCheck : readinessChecks.entrySet())
            {
                try
                {
                    readinessByEndpoint.put(readinessCheck.getKey(),
                        readinessCheck.getValue().get((long)timeoutMs + SDRCONNECT_HEADLESS_START_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS));
                }
                catch(InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    readinessByEndpoint.put(readinessCheck.getKey(), SDRconnectEndpointReadiness.notReady());
                    mLog.warn("Interrupted while waiting for SDRconnect readiness check to complete for {}",
                        readinessCheck.getKey(), ie);
                }
                catch(Exception e)
                {
                    readinessByEndpoint.put(readinessCheck.getKey(), SDRconnectEndpointReadiness.notReady());
                    mLog.warn("Error waiting for SDRconnect readiness check to complete for {}",
                        readinessCheck.getKey(), e);
                }
            }
        }

        return readinessByEndpoint;
    }

    private Map<String, String> resolveConfiguredSDRconnectDeviceAssignments(List<TunerConfiguration> tunerConfigurations,
                                                                             Map<String, SDRconnectEndpointReadiness> readinessByEndpoint)
    {
        Map<String, String> assignments = new HashMap<>();
        Map<String, Integer> representativePortsByHost = new LinkedHashMap<>();

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig)
            {
                String endpointKey = getSDRconnectEndpointKey(sdrconnectConfig.getHost(), sdrconnectConfig.getPort());
                SDRconnectEndpointReadiness readiness = readinessByEndpoint.get(endpointKey);

                if(readiness != null && readiness.isReady())
                {
                    representativePortsByHost.putIfAbsent(sdrconnectConfig.getHost(), sdrconnectConfig.getPort());
                }
            }
        }

        Map<String, List<SDRconnectDeviceSlot>> slotsByHost = new HashMap<>();

        for(Map.Entry<String, Integer> representative : representativePortsByHost.entrySet())
        {
            String endpointKey = getSDRconnectEndpointKey(representative.getKey(), representative.getValue());
            SDRconnectEndpointReadiness readiness = readinessByEndpoint.get(endpointKey);
            String validDevices = readiness != null ? readiness.getValidDevices() : null;

            if(validDevices != null && !validDevices.isBlank())
            {
                slotsByHost.put(representative.getKey(), parseSDRconnectDeviceSlots(validDevices));
            }
        }

        Map<String, Set<SDRconnectDeviceSlot>> claimedSlotsByHost = new HashMap<>();

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig &&
                sdrconnectConfig.getDeviceName() != null && !sdrconnectConfig.getDeviceName().isBlank())
            {
                SDRconnectDeviceSlot slot = findMatchingDeviceSlot(slotsByHost.get(sdrconnectConfig.getHost()),
                    sdrconnectConfig.getDeviceName());

                if(slot != null)
                {
                    assignments.put(sdrconnectConfig.getUniqueID(),
                        slot.getPreferredSelection(sdrconnectConfig.getDeviceName()));
                    claimedSlotsByHost.computeIfAbsent(sdrconnectConfig.getHost(), key -> new HashSet<>()).add(slot);
                }
            }
        }

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig &&
                (sdrconnectConfig.getDeviceName() == null || sdrconnectConfig.getDeviceName().isBlank()))
            {
                List<SDRconnectDeviceSlot> slots = slotsByHost.get(sdrconnectConfig.getHost());

                if(slots != null)
                {
                    Set<SDRconnectDeviceSlot> claimed =
                        claimedSlotsByHost.computeIfAbsent(sdrconnectConfig.getHost(), key -> new HashSet<>());

                    for(SDRconnectDeviceSlot slot : slots)
                    {
                        if(!claimed.contains(slot))
                        {
                            assignments.put(sdrconnectConfig.getUniqueID(), slot.getPreferredSelection(null));
                            claimed.add(slot);
                            break;
                        }
                    }
                }
            }
        }

        return assignments;
    }

    private String getSDRconnectEndpointKey(String host, int port)
    {
        return host + ":" + port;
    }

    /**
     * Parses the advertised SDRconnect devices into assignable slots, pairing serial-based and friendly-name aliases
     * when SDRconnect exposes both in the same ordered list.
     *
     * Note: the serial/named pairing is positional. This relies on SDRconnect currently advertising the serial-based
     * groups and friendly-name groups in the same physical-device order.
     */
    private List<SDRconnectDeviceSlot> parseSDRconnectDeviceSlots(String validDevices)
    {
        Map<String, List<String>> groupedEntries = new LinkedHashMap<>();

        for(String entry : validDevices.split(","))
        {
            String trimmed = entry.trim();

            if(trimmed.isEmpty() || "IQ File".equalsIgnoreCase(trimmed))
            {
                continue;
            }

            String deviceKey = trimmed.replaceFirst("\\s+\\([^)]*\\)$", "");
            groupedEntries.computeIfAbsent(deviceKey, key -> new ArrayList<>()).add(trimmed);
        }

        List<Map.Entry<String, List<String>>> serialGroups = new ArrayList<>();
        List<Map.Entry<String, List<String>>> namedGroups = new ArrayList<>();

        for(Map.Entry<String, List<String>> entry : groupedEntries.entrySet())
        {
            if(entry.getKey().matches(".*\\(\\d+\\)$"))
            {
                serialGroups.add(entry);
            }
            else
            {
                namedGroups.add(entry);
            }
        }

        List<SDRconnectDeviceSlot> slots = new ArrayList<>();

        if(!serialGroups.isEmpty() && serialGroups.size() == namedGroups.size())
        {
            for(int x = 0; x < serialGroups.size(); x++)
            {
                SDRconnectDeviceSlot slot = new SDRconnectDeviceSlot();
                slot.addAlias(serialGroups.get(x).getKey());
                slot.addEntries(serialGroups.get(x).getValue());
                slot.addAlias(namedGroups.get(x).getKey());
                slot.addEntries(namedGroups.get(x).getValue());
                slots.add(slot);
            }
        }
        else
        {
            for(Map.Entry<String, List<String>> entry : groupedEntries.entrySet())
            {
                SDRconnectDeviceSlot slot = new SDRconnectDeviceSlot();
                slot.addAlias(entry.getKey());
                slot.addEntries(entry.getValue());
                slots.add(slot);
            }
        }

        return slots;
    }

    private SDRconnectDeviceSlot findMatchingDeviceSlot(List<SDRconnectDeviceSlot> slots, String selector)
    {
        if(slots == null || selector == null || selector.isBlank())
        {
            return null;
        }

        SDRconnectDeviceSlot fallback = null;

        for(SDRconnectDeviceSlot slot : slots)
        {
            if(slot.matches(selector))
            {
                if(fallback == null)
                {
                    fallback = slot;
                }

                if(slot.prefersDefaultNetworkMode(selector))
                {
                    return slot;
                }
            }
        }

        return fallback;
    }

    private boolean isLocalSDRconnectHost(String host)
    {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

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

            ProcessBuilder processBuilder = new ProcessBuilder(executable.toString(), "--websocket_port=" + port);
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

    private SDRconnectEndpointReadiness waitForReadySDRconnect(String host, int port, int timeoutMs)
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
                return SDRconnectEndpointReadiness.notReady();
            }

            SDRconnectEndpointReadiness readiness = isSDRconnectReady(host, port);

            if(readiness.isReady())
            {
                mLog.info("SDRconnect headless on port {} is ready", port);
                return readiness;
            }

            try
            {
                Thread.sleep(SDRCONNECT_HEADLESS_START_RETRY_INTERVAL_MS);
            }
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                return SDRconnectEndpointReadiness.notReady();
            }
        }

        mLog.warn("Timed out waiting for SDRconnect headless on port {} readiness after {} ms", port,
            effectiveTimeoutMs);
        return SDRconnectEndpointReadiness.notReady();
    }

    private SDRconnectEndpointReadiness isSDRconnectReady(String host, int port)
    {
        if(!probeSDRconnect(host, port))
        {
            return SDRconnectEndpointReadiness.notReady();
        }

        SDRconnectReadyProbe probe = new SDRconnectReadyProbe();
        WebSocket webSocket = null;

        try
        {
            CompletableFuture<WebSocket> future = mSDRconnectReadyProbeHttpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://" + host + ":" + port), probe);
            webSocket = future.get(2, TimeUnit.SECONDS);
            boolean ready = probe.awaitReady(2, TimeUnit.SECONDS);
            return ready && probe.isReady() ? SDRconnectEndpointReadiness.ready(probe.getValidDevices()) :
                SDRconnectEndpointReadiness.notReady();
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return SDRconnectEndpointReadiness.notReady();
        }
        catch(Exception e)
        {
            return SDRconnectEndpointReadiness.notReady();
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
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                mLog.warn("Interrupted while trying to interrupt SDRconnect headless process [{}] on port {}", pid,
                    port, ie);
            }
            catch(Exception e)
            {
                mLog.warn("Unable to interrupt SDRconnect headless process [{}] on port {}", pid, port, e);
            }
        }
    }

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

        private String getValidDevices()
        {
            return mValidDevices;
        }

        private static boolean isReadyValue(String value)
        {
            return value != null && !value.isBlank() && !"Refreshing...".equalsIgnoreCase(value.trim());
        }
    }

    private static class SDRconnectEndpointReadiness
    {
        private final boolean mReady;
        private final String mValidDevices;

        private SDRconnectEndpointReadiness(boolean ready, String validDevices)
        {
            mReady = ready;
            mValidDevices = validDevices;
        }

        private static SDRconnectEndpointReadiness ready(String validDevices)
        {
            return new SDRconnectEndpointReadiness(true, validDevices);
        }

        private static SDRconnectEndpointReadiness notReady()
        {
            return new SDRconnectEndpointReadiness(false, null);
        }

        private boolean isReady()
        {
            return mReady;
        }

        private String getValidDevices()
        {
            return mValidDevices;
        }
    }

    private static class SDRconnectDeviceSlot
    {
        private final LinkedHashSet<String> mAliases = new LinkedHashSet<>();
        private final LinkedHashSet<String> mAdvertisedEntries = new LinkedHashSet<>();

        private void addAlias(String alias)
        {
            if(alias != null && !alias.isBlank())
            {
                mAliases.add(alias.trim());
            }
        }

        private void addEntries(List<String> entries)
        {
            for(String entry : entries)
            {
                if(entry != null && !entry.isBlank())
                {
                    mAdvertisedEntries.add(entry.trim());
                }
            }
        }

        private boolean matches(String selector)
        {
            if(selector == null || selector.isBlank())
            {
                return false;
            }

            String normalizedSelector = selector.trim().toLowerCase();

            for(String alias : mAliases)
            {
                if(alias.equalsIgnoreCase(selector) || alias.toLowerCase().contains(normalizedSelector))
                {
                    return true;
                }
            }

            for(String entry : mAdvertisedEntries)
            {
                if(entry.equalsIgnoreCase(selector) || entry.toLowerCase().contains(normalizedSelector))
                {
                    return true;
                }
            }

            return false;
        }

        private boolean prefersDefaultNetworkMode(String selector)
        {
            return getPreferredSelection(selector).toLowerCase()
                .contains(SDRconnectTunerController.DEFAULT_NETWORK_MODE.toLowerCase());
        }

        private String getPreferredSelection(String selector)
        {
            String matchedEntry = null;

            if(selector != null && !selector.isBlank())
            {
                String normalizedSelector = selector.trim().toLowerCase();

                for(String entry : mAdvertisedEntries)
                {
                    if(entry.equalsIgnoreCase(selector) || entry.toLowerCase().contains(normalizedSelector))
                    {
                        if(matchedEntry == null)
                        {
                            matchedEntry = entry;
                        }

                        if(entry.toLowerCase().contains(SDRconnectTunerController.DEFAULT_NETWORK_MODE.toLowerCase()))
                        {
                            return entry;
                        }
                    }
                }
            }

            if(matchedEntry != null)
            {
                return matchedEntry;
            }

            String namedFallback = null;

            for(String entry : mAdvertisedEntries)
            {
                if(!entry.matches(".*\\(\\d+\\)\\s+\\([^)]*\\)$") && namedFallback == null)
                {
                    namedFallback = entry;
                }

                if(entry.toLowerCase().contains(SDRconnectTunerController.DEFAULT_NETWORK_MODE.toLowerCase()))
                {
                    if(!entry.matches(".*\\(\\d+\\)\\s+\\([^)]*\\)$"))
                    {
                        return entry;
                    }
                }
            }

            if(namedFallback != null)
            {
                return namedFallback;
            }

            for(String entry : mAdvertisedEntries)
            {
                if(entry.toLowerCase().contains(SDRconnectTunerController.DEFAULT_NETWORK_MODE.toLowerCase()))
                {
                    return entry;
                }
            }

            return mAdvertisedEntries.iterator().next();
        }
    }
}
