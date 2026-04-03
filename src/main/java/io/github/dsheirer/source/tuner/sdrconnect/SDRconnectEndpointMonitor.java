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
import io.github.dsheirer.properties.SystemProperties;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.util.ThreadPool;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

class SDRconnectEndpointMonitor
{
    private static final String SDRCONNECT_HEADLESS_PATH_PROPERTY = "sdrconnect.headless.path";
    private static final String SDRCONNECT_HEADLESS_AUTOSTART_PROPERTY = "sdrconnect.headless.autostart";
    private static final String SDRCONNECT_HEADLESS_START_DELAY_MS_PROPERTY = "sdrconnect.headless.start.delay.ms";
    // macOS default; other platforms should configure the executable path explicitly via system properties/preferences.
    @SuppressWarnings("java:S1075")
    private static final String DEFAULT_SDRCONNECT_HEADLESS_PATH = "/Applications/SDRconnect.app/Contents/MacOS/SDRconnect_headless";
    private static final int SDRCONNECT_HEADLESS_START_TIMEOUT_MS = 5000;
    private static final int SDRCONNECT_HEADLESS_START_RETRY_INTERVAL_MS = 250;
    private static final int DEFAULT_SDRCONNECT_HEADLESS_START_DELAY_MS = 15000;
    private static final int SDRCONNECT_HEADLESS_RESTART_INITIAL_DELAY_MS = 1000;
    private static final int SDRCONNECT_HEADLESS_RESTART_MAX_DELAY_MS = 60000;
    private static final int SDRCONNECT_HEADLESS_RESTART_STABLE_RESET_MS = 30000;

    private final Logger mLog;
    private final UserPreferences mUserPreferences;
    private final Map<Integer, Process> mManagedSDRconnectProcesses = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> mManagedProcessRestartAttempts = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> mExpectedManagedProcessExits =
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Thread mManagedSDRconnectShutdownHook;
    private final HttpClient mSDRconnectReadyProbeHttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicBoolean mStopped = new AtomicBoolean(false);

    SDRconnectEndpointMonitor(UserPreferences userPreferences, Logger log)
    {
        mUserPreferences = userPreferences;
        // Reuse the coordinator logger so SDRconnect lifecycle messages stay grouped under the manager category.
        mLog = log;
        mManagedSDRconnectShutdownHook = new Thread(this::stopManagedSDRconnectProcesses,
            "sdrconnect-headless-shutdown");
        Runtime.getRuntime().addShutdownHook(mManagedSDRconnectShutdownHook);
    }

    Map<String, SDRconnectEndpointReadiness> prepareConfiguredEndpoints(List<TunerConfiguration> tunerConfigurations)
    {
        Map<String, SDRconnectEndpointReadiness> readinessByEndpoint = new HashMap<>();
        Map<String, String> endpointHosts = new HashMap<>();
        Map<String, Integer> endpointPorts = getConfiguredEndpointPorts(tunerConfigurations, endpointHosts);

        if(!endpointPorts.isEmpty())
        {
            int timeoutMs = SystemProperties.getInstance().get(SDRCONNECT_HEADLESS_START_DELAY_MS_PROPERTY,
                DEFAULT_SDRCONNECT_HEADLESS_START_DELAY_MS);
            mLog.info("Waiting up to {} ms for SDRconnect readiness on endpoint(s) {}", timeoutMs, endpointPorts.keySet());
            Map<String, CompletableFuture<SDRconnectEndpointReadiness>> readinessChecks =
                createEndpointReadinessChecks(endpointHosts, endpointPorts, timeoutMs);
            collectEndpointReadiness(readinessByEndpoint, readinessChecks, timeoutMs);
        }

        return readinessByEndpoint;
    }

    boolean probe(String host, int port)
    {
        try (Socket socket = new Socket())
        {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        }
        catch(Exception _)
        {
            return false;
        }
    }

    boolean prepareEndpointForStart(String host, int port)
    {
        if(probe(host, port))
        {
            return true;
        }

        if(!isLocalSDRconnectHost(host) || !launchManagedSDRconnectProcess(port))
        {
            return false;
        }

        return waitForReadySDRconnect(host, port, DEFAULT_SDRCONNECT_HEADLESS_START_DELAY_MS).isReady();
    }

    void stopManagedEndpoint(String host, int port)
    {
        if(!isLocalSDRconnectHost(host))
        {
            return;
        }

        Process process = mManagedSDRconnectProcesses.remove(port);

        if(process == null)
        {
            return;
        }

        mExpectedManagedProcessExits.add(port);
        mManagedProcessRestartAttempts.remove(port);

        if(process.isAlive())
        {
            interruptProcessTree(process, port);
            destroyManagedProcess(process);
        }
    }

    void stop()
    {
        mStopped.set(true);
        stopManagedSDRconnectProcesses();

        try
        {
            Runtime.getRuntime().removeShutdownHook(mManagedSDRconnectShutdownHook);
        }
        catch(IllegalStateException _)
        {
            // JVM shutdown is already in progress, so the hook cannot be removed.
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

    private Map<String, Integer> getConfiguredEndpointPorts(List<TunerConfiguration> tunerConfigurations,
                                                            Map<String, String> endpointHosts)
    {
        Map<String, Integer> endpointPorts = new HashMap<>();

        for(TunerConfiguration tunerConfiguration: tunerConfigurations)
        {
            if(tunerConfiguration instanceof SDRconnectTunerConfiguration sdrconnectConfig)
            {
                registerConfiguredEndpoint(sdrconnectConfig, endpointHosts, endpointPorts);
            }
        }

        return endpointPorts;
    }

    private void registerConfiguredEndpoint(SDRconnectTunerConfiguration sdrconnectConfig,
                                            Map<String, String> endpointHosts,
                                            Map<String, Integer> endpointPorts)
    {
        String endpointKey = getEndpointKey(sdrconnectConfig.getHost(), sdrconnectConfig.getPort());

        if(isAvailableOrLaunchable(sdrconnectConfig))
        {
            endpointHosts.put(endpointKey, sdrconnectConfig.getHost());
            endpointPorts.put(endpointKey, sdrconnectConfig.getPort());
        }
    }

    private boolean isAvailableOrLaunchable(SDRconnectTunerConfiguration sdrconnectConfig)
    {
        return probe(sdrconnectConfig.getHost(), sdrconnectConfig.getPort()) ||
            (isLocalSDRconnectHost(sdrconnectConfig.getHost()) && launchManagedSDRconnectProcess(sdrconnectConfig.getPort()));
    }

    private Map<String, CompletableFuture<SDRconnectEndpointReadiness>> createEndpointReadinessChecks(
        Map<String, String> endpointHosts, Map<String, Integer> endpointPorts, int timeoutMs)
    {
        Map<String, CompletableFuture<SDRconnectEndpointReadiness>> readinessChecks = new HashMap<>();

        for(Map.Entry<String, Integer> endpoint : endpointPorts.entrySet())
        {
            String host = endpointHosts.get(endpoint.getKey());
            int port = endpoint.getValue();
            readinessChecks.put(endpoint.getKey(), CompletableFuture.supplyAsync(
                () -> waitForReadySDRconnect(host, port, timeoutMs), ThreadPool.CACHED));
        }

        return readinessChecks;
    }

    private void collectEndpointReadiness(Map<String, SDRconnectEndpointReadiness> readinessByEndpoint,
                                          Map<String, CompletableFuture<SDRconnectEndpointReadiness>> readinessChecks,
                                          int timeoutMs)
    {
        for(Map.Entry<String, CompletableFuture<SDRconnectEndpointReadiness>> readinessCheck : readinessChecks.entrySet())
        {
            readinessByEndpoint.put(readinessCheck.getKey(),
                getEndpointReadiness(readinessCheck.getKey(), readinessCheck.getValue(), timeoutMs));
        }
    }

    private SDRconnectEndpointReadiness getEndpointReadiness(String endpointKey,
                                                             CompletableFuture<SDRconnectEndpointReadiness> readinessCheck,
                                                             int timeoutMs)
    {
        try
        {
            return readinessCheck.get((long)timeoutMs + SDRCONNECT_HEADLESS_START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            mLog.warn("Interrupted while waiting for SDRconnect readiness check to complete for {}", endpointKey, ie);
        }
        catch(Exception e)
        {
            mLog.warn("Error waiting for SDRconnect readiness check to complete for {}", endpointKey, e);
        }

        return SDRconnectEndpointReadiness.notReady();
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
            Path logPath = getManagedProcessLogPath(port);
            File logFile = logPath.toFile();

            ProcessBuilder processBuilder = new ProcessBuilder(executable.toString(), "--websocket_port=" + port);
            processBuilder.directory(executable.getParent().toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

            Process process = processBuilder.start();
            mManagedSDRconnectProcesses.put(port, process);
            registerUnexpectedExitLogger(port, process, logPath);
            scheduleRestartAttemptReset(port, process);
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
            catch(InterruptedException _)
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
        if(!probe(host, port))
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
        catch(InterruptedException _)
        {
            Thread.currentThread().interrupt();
            return SDRconnectEndpointReadiness.notReady();
        }
        catch(Exception _)
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
                catch(Exception _)
                {
                    // Ignore malformed probe responses and continue waiting for a usable readiness payload.
                }
            }
        }
    }

    private void stopManagedSDRconnectProcesses()
    {
        Map<Integer, Process> managedProcesses = new HashMap<>(mManagedSDRconnectProcesses);
        mExpectedManagedProcessExits.addAll(managedProcesses.keySet());
        mManagedSDRconnectProcesses.clear();

        for(Map.Entry<Integer, Process> entry : managedProcesses.entrySet())
        {
            Process process = entry.getValue();

            if(process != null && process.isAlive())
            {
                interruptProcessTree(process, entry.getKey());
                destroyManagedProcess(process);
            }
        }
    }

    private void destroyManagedProcess(Process process)
    {
        if(!process.isAlive())
        {
            return;
        }

        process.destroy();
        waitForProcessExit(process, 5, TimeUnit.SECONDS);

        if(process.isAlive())
        {
            process.destroyForcibly();
            waitForProcessExit(process, 2, TimeUnit.SECONDS);
        }
    }

    private void waitForProcessExit(Process process, long timeout, TimeUnit timeUnit)
    {
        if(process.isAlive())
        {
            try
            {
                process.waitFor(timeout, timeUnit);
            }
            catch(InterruptedException _)
            {
                Thread.currentThread().interrupt();
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

    private String getEndpointKey(String host, int port)
    {
        return host + ":" + port;
    }

    private Path getManagedProcessLogPath(int port)
    {
        return mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog()
            .resolve("sdrconnect_headless_" + port + ".log");
    }

    private void registerUnexpectedExitLogger(int port, Process process, Path logPath)
    {
        process.onExit().thenAccept(exitedProcess ->
        {
            Process current = mManagedSDRconnectProcesses.get(port);

            if(current == exitedProcess)
            {
                mManagedSDRconnectProcesses.remove(port, exitedProcess);
            }

            if(mExpectedManagedProcessExits.remove(port))
            {
                mManagedProcessRestartAttempts.remove(port);
                return;
            }

            mLog.error("Managed SDRconnect headless process on port {} exited unexpectedly with code {}. See log [{}]",
                port, exitedProcess.exitValue(), logPath);
            scheduleManagedProcessRestart(port);
        });
    }

    private void scheduleManagedProcessRestart(int port)
    {
        Integer previousAttempts = mManagedProcessRestartAttempts.putIfAbsent(port, 1);
        int attempts;

        if(previousAttempts == null)
        {
            attempts = 1;
        }
        else
        {
            attempts = previousAttempts + 1;
            mManagedProcessRestartAttempts.put(port, attempts);
        }

        long delayMs = Math.min(SDRCONNECT_HEADLESS_RESTART_INITIAL_DELAY_MS * (1L << (attempts - 1)),
            SDRCONNECT_HEADLESS_RESTART_MAX_DELAY_MS);
        mLog.info("Scheduling managed SDRconnect headless restart on port {} in {} ms (attempt {})",
            port, delayMs, attempts);

        ThreadPool.SCHEDULED.schedule(() ->
        {
            if(mStopped.get() || mExpectedManagedProcessExits.contains(port))
            {
                return;
            }

            mLog.info("Attempting to restart managed SDRconnect headless process on port {}", port);
            if(!launchManagedSDRconnectProcess(port))
            {
                mLog.warn("Unable to restart managed SDRconnect headless process on port {} - will retry", port);
                scheduleManagedProcessRestart(port);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleRestartAttemptReset(int port, Process process)
    {
        ThreadPool.SCHEDULED.schedule(() ->
        {
            if(mManagedSDRconnectProcesses.get(port) == process && process.isAlive())
            {
                mManagedProcessRestartAttempts.remove(port);
            }
        }, SDRCONNECT_HEADLESS_RESTART_STABLE_RESET_MS, TimeUnit.MILLISECONDS);
    }

    static class SDRconnectReadyProbe implements WebSocket.Listener
    {
        private final CountDownLatch mReady = new CountDownLatch(1);
        private final StringBuilder mPartialText = new StringBuilder();
        private volatile String mValidDevices = "";
        private volatile String mActiveDevice = "";

        @Override
        public void onOpen(WebSocket webSocket)
        {
            webSocket.sendText(createGetPropertyMessage(SDRconnectProtocol.PROPERTY_VALID_DEVICES), true);
            webSocket.sendText(createGetPropertyMessage(SDRconnectProtocol.PROPERTY_ACTIVE_DEVICE), true);
            webSocket.request(1);
        }

        private String createGetPropertyMessage(String property)
        {
            JsonObject message = new JsonObject();
            message.addProperty(SDRconnectProtocol.JSON_EVENT_TYPE, SDRconnectProtocol.EVENT_GET_PROPERTY);
            message.addProperty(SDRconnectProtocol.JSON_PROPERTY, property);
            return message.toString();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            mPartialText.append(data);

            if(last)
            {
                processCompletedTextMessage();
            }

            webSocket.request(1);
            return null;
        }

        private void processCompletedTextMessage()
        {
            try
            {
                JsonObject message = JsonParser.parseString(mPartialText.toString()).getAsJsonObject();

                if(isPropertyMessage(message))
                {
                    updatePropertyState(message);
                    countDownWhenReady();
                }
            }
            catch(Exception _)
            {
                // Ignore malformed probe responses and continue waiting for a usable readiness payload.
            }
            finally
            {
                mPartialText.setLength(0);
            }
        }

        private boolean isPropertyMessage(JsonObject message)
        {
            String eventType = getStringProperty(message, "event_type");
            return SDRconnectProtocol.EVENT_PROPERTY_CHANGED.equals(eventType) ||
                SDRconnectProtocol.EVENT_GET_PROPERTY_RESPONSE.equals(eventType);
        }

        private void updatePropertyState(JsonObject message)
        {
            String property = getStringProperty(message, "property");
            String value = getStringProperty(message, "value");

            if("valid_devices".equals(property))
            {
                mValidDevices = value;
            }
            else if("active_device".equals(property))
            {
                mActiveDevice = value;
            }
        }

        private void countDownWhenReady()
        {
            if(isReady())
            {
                mReady.countDown();
            }
        }

        private String getStringProperty(JsonObject message, String property)
        {
            return message.has(property) ? message.get(property).getAsString() : "";
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

}
