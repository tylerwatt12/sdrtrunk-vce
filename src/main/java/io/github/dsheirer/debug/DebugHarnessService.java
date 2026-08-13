/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.channel.quality.ControlChannelQualityRegistry;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.debug.DebugHarnessControlAdapter.HttpResult;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional loopback-only receiver debug endpoint.  Telemetry is collected and serialized once per second on one
 * low-priority worker; HTTP clients only copy the cached bytes.  Mutating requests are sent through a separate,
 * bounded serial control service and can address saved channels only.
 */
public final class DebugHarnessService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DebugHarnessService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ROOT = "/debug/v1";
    private static final String MUTATION_HEADER = "X-SDRTrunk-Debug-Client";
    private static final String SESSION_HEADER = "X-SDRTrunk-Debug-Session";
    private static final int MAXIMUM_REQUEST_BODY_BYTES = 8_192;
    private static final int MAXIMUM_THREAD_COUNT = 256;
    private static final int MAXIMUM_THREAD_DEPTH = 32;
    private static final int HTTP_WORKERS = 2;
    private static final int HTTP_QUEUE_CAPACITY = 8;

    private final DebugHarnessConfiguration mConfiguration;
    private final DebugHarnessTelemetry mTelemetry;
    private final DebugHarnessControlAdapter mControls;
    private final int mRequestedPort;
    private final ThreadPoolExecutor mHttpExecutor;
    private final ScheduledExecutorService mSampler;
    private volatile HttpServer mServer;

    public DebugHarnessService(DebugHarnessConfiguration configuration, TunerManager tunerManager,
                               ControlChannelQualityRegistry qualityRegistry,
                               ConfigurationManager configurationManager)
    {
        this(configuration, new DebugHarnessTelemetry(tunerManager, qualityRegistry),
            new DebugHarnessControlService(configurationManager), configuration.port());
    }

    /** Test seam permitting an ephemeral port and a fake control boundary. */
    DebugHarnessService(DebugHarnessConfiguration configuration, DebugHarnessTelemetry telemetry,
                        DebugHarnessControlAdapter controls, int requestedPort)
    {
        mConfiguration = configuration;
        mTelemetry = telemetry;
        mControls = controls;
        mRequestedPort = requestedPort;
        mHttpExecutor = new ThreadPoolExecutor(HTTP_WORKERS, HTTP_WORKERS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(HTTP_QUEUE_CAPACITY), lowPriorityThreadFactory("receiver debug http"),
            new ThreadPoolExecutor.AbortPolicy());
        mSampler = Executors.newSingleThreadScheduledExecutor(lowPriorityThreadFactory("receiver debug sampler"));
    }

    /** Starts the service when enabled.  A bind failure is reported to the caller and cannot stop the receiver. */
    public synchronized void start() throws IOException
    {
        if(!mConfiguration.enabled() || mServer != null)
        {
            return;
        }

        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), mRequestedPort);
        HttpServer server = HttpServer.create(address, HTTP_QUEUE_CAPACITY);
        server.createContext(ROOT + "/", this::handle);
        server.setExecutor(mHttpExecutor);
        mServer = server;
        mSampler.scheduleWithFixedDelay(mTelemetry::sample, 0L,
            DebugHarnessTelemetry.EXPECTED_SAMPLE_INTERVAL_MILLISECONDS, TimeUnit.MILLISECONDS);
        server.start();
        mLog.info("Receiver debug harness listening on http://127.0.0.1:{}{} (controls: {})",
            getPort(), ROOT, mConfiguration.controlsAllowed() ? "enabled" : "disabled");
    }

    public boolean isRunning()
    {
        return mServer != null;
    }

    public int getPort()
    {
        HttpServer server = mServer;
        return server != null ? server.getAddress().getPort() : -1;
    }

    private void handle(HttpExchange exchange)
    {
        try
        {
            applyHeaders(exchange);

            if(exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null ||
                !exchange.getRemoteAddress().getAddress().isLoopbackAddress())
            {
                sendJson(exchange, 403, error("Loopback access is required"));
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if(path == null || path.length() > 256 || exchange.getRequestURI().getRawQuery() != null)
            {
                sendJson(exchange, 400, error("Invalid debug request target"));
                return;
            }

            switch(path)
            {
                case ROOT + "/status" -> handleStatus(exchange);
                case ROOT + "/snapshot" -> requireMethod(exchange, "GET", mTelemetry.getCachedJson());
                case ROOT + "/channels" -> handleChannels(exchange);
                case ROOT + "/threads" -> handleThreads(exchange);
                case ROOT + "/session" -> handleSession(exchange);
                case ROOT + "/channels/start" -> handleChannelMutation(exchange, true);
                case ROOT + "/channels/stop" -> handleChannelMutation(exchange, false);
                default -> sendJson(exchange, 404, error("Debug endpoint was not found"));
            }
        }
        catch(BadRequestException e)
        {
            try
            {
                sendJson(exchange, 400, error(e.getMessage()));
            }
            catch(Exception ignored)
            {
                //The peer may already be disconnected.
            }
        }
        catch(Exception e)
        {
            try
            {
                sendJson(exchange, 500, error("Debug request failed"));
            }
            catch(Exception ignored)
            {
                //The peer may already be disconnected.  Never propagate network failure to the HTTP dispatcher.
            }
        }
        finally
        {
            exchange.close();
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            methodNotAllowed(exchange, "GET");
            return;
        }

        Map<String,Object> status = new LinkedHashMap<>();
        status.put("running", isRunning());
        status.put("bind_address", "127.0.0.1");
        status.put("port", getPort());
        status.put("controls_allowed", mConfiguration.controlsAllowed());
        status.put("telemetry_interval_ms", DebugHarnessTelemetry.EXPECTED_SAMPLE_INTERVAL_MILLISECONDS);
        status.put("endpoints", List.of("status", "snapshot", "channels", "threads", "session",
            "channels/start", "channels/stop"));
        sendJson(exchange, 200, json(status));
    }

    private void handleChannels(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            methodNotAllowed(exchange, "GET");
            return;
        }

        sendJson(exchange, 200, mControls.channelsJson());
    }

    private void handleThreads(HttpExchange exchange) throws IOException
    {
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            methodNotAllowed(exchange, "GET");
            return;
        }

        //A thread dump is read-only but deliberately intrusive.  The custom header forces a browser preflight, and
        //this service never grants CORS, so an unrelated web page cannot repeatedly trigger dumps on loopback.
        if(!"1".equals(exchange.getRequestHeaders().getFirst(MUTATION_HEADER)))
        {
            sendJson(exchange, 403, error(MUTATION_HEADER + " header is required for a thread snapshot"));
            return;
        }

        sendJson(exchange, 200, threadSnapshot());
    }

    private void handleSession(HttpExchange exchange) throws IOException
    {
        String method = exchange.getRequestMethod();

        if("POST".equals(method))
        {
            if(!allowMutation(exchange))
            {
                return;
            }

            JsonNode body = readJsonBody(exchange);
            long duration = body.path("duration_seconds").asLong(0L);
            send(exchange, mControls.createSession(duration));
        }
        else if("GET".equals(method))
        {
            send(exchange, mControls.getSession(exchange.getRequestHeaders().getFirst(SESSION_HEADER)));
        }
        else if("DELETE".equals(method))
        {
            if(allowMutation(exchange))
            {
                send(exchange, mControls.endSession(exchange.getRequestHeaders().getFirst(SESSION_HEADER)));
            }
        }
        else
        {
            methodNotAllowed(exchange, "GET, POST, DELETE");
        }
    }

    private void handleChannelMutation(HttpExchange exchange, boolean processing) throws IOException
    {
        if(!"POST".equals(exchange.getRequestMethod()))
        {
            methodNotAllowed(exchange, "POST");
            return;
        }

        if(!allowMutation(exchange))
        {
            return;
        }

        JsonNode body = readJsonBody(exchange);
        String configurationId = body.path("configuration_id").asText(null);
        long revision = body.path("revision").asLong(Long.MIN_VALUE);

        if(configurationId == null || revision == Long.MIN_VALUE)
        {
            sendJson(exchange, 400, error("configuration_id and revision are required"));
            return;
        }

        send(exchange, mControls.setChannel(exchange.getRequestHeaders().getFirst(SESSION_HEADER), revision,
            configurationId, processing));
    }

    private boolean allowMutation(HttpExchange exchange) throws IOException
    {
        if(!mConfiguration.controlsAllowed())
        {
            sendJson(exchange, 403, error("Debug controls are disabled"));
            return false;
        }

        if(!"1".equals(exchange.getRequestHeaders().getFirst(MUTATION_HEADER)))
        {
            sendJson(exchange, 403, error(MUTATION_HEADER + " header is required"));
            return false;
        }

        return true;
    }

    private static JsonNode readJsonBody(HttpExchange exchange) throws IOException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        if(contentType == null || !contentType.toLowerCase().startsWith("application/json"))
        {
            throw new BadRequestException("Content-Type application/json is required");
        }

        byte[] body = readBounded(exchange.getRequestBody());

        try
        {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            return node != null && node.isObject() ? node : OBJECT_MAPPER.createObjectNode();
        }
        catch(IOException e)
        {
            throw new BadRequestException("Request body must be valid JSON");
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1_024];
        int total = 0;
        int read;

        while((read = input.read(buffer)) >= 0)
        {
            total += read;

            if(total > MAXIMUM_REQUEST_BODY_BYTES)
            {
                throw new BadRequestException("Debug request body is too large");
            }

            output.write(buffer, 0, read);
        }

        return output.toByteArray();
    }

    private static byte[] threadSnapshot()
    {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos;

        try
        {
            infos = bean.dumpAllThreads(true, true, MAXIMUM_THREAD_DEPTH);
        }
        catch(RuntimeException e)
        {
            return json(Map.of("error", "Thread snapshot is unavailable", "type", e.getClass().getSimpleName()));
        }

        int count = Math.min(MAXIMUM_THREAD_COUNT, infos.length);
        List<Map<String,Object>> threads = new ArrayList<>(count);

        for(int x = 0; x < count; x++)
        {
            ThreadInfo info = infos[x];
            Map<String,Object> thread = new LinkedHashMap<>();
            thread.put("id", info.getThreadId());
            thread.put("name", info.getThreadName());
            thread.put("state", info.getThreadState().name().toLowerCase());
            thread.put("lock_name", info.getLockName());
            thread.put("lock_owner_id", info.getLockOwnerId() >= 0 ? info.getLockOwnerId() : null);
            thread.put("lock_owner_name", info.getLockOwnerName());
            thread.put("suspended", info.isSuspended());
            thread.put("in_native", info.isInNative());
            List<String> frames = new ArrayList<>();

            for(StackTraceElement frame: info.getStackTrace())
            {
                frames.add(frame.toString());
            }

            thread.put("stack", frames);
            threads.add(thread);
        }

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("intrusive", true);
        result.put("thread_count", infos.length);
        result.put("thread_limit", MAXIMUM_THREAD_COUNT);
        result.put("threads_truncated", infos.length > count);
        result.put("stack_depth_limit", MAXIMUM_THREAD_DEPTH);
        result.put("threads", threads);
        return json(result);
    }

    private static void requireMethod(HttpExchange exchange, String method, byte[] body) throws IOException
    {
        if(method.equals(exchange.getRequestMethod()))
        {
            sendJson(exchange, 200, body);
        }
        else
        {
            methodNotAllowed(exchange, method);
        }
    }

    private static void send(HttpExchange exchange, HttpResult result) throws IOException
    {
        sendJson(exchange, result.status(), result.body());
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException
    {
        exchange.getResponseHeaders().set("Allow", allow);
        sendJson(exchange, 405, error("Method is not allowed"));
    }

    private static void applyHeaders(HttpExchange exchange)
    {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    }

    private static void sendJson(HttpExchange exchange, int status, byte[] body) throws IOException
    {
        byte[] safeBody = body != null ? body : new byte[0];
        exchange.sendResponseHeaders(status, safeBody.length);
        exchange.getResponseBody().write(safeBody);
    }

    private static byte[] error(String message)
    {
        return json(Map.of("error", message));
    }

    private static byte[] json(Object value)
    {
        try
        {
            return OBJECT_MAPPER.writeValueAsBytes(value);
        }
        catch(IOException e)
        {
            return "{\"error\":\"Debug response serialization failed\"}".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static ThreadFactory lowPriorityThreadFactory(String name)
    {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + " " + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }

    @Override
    public synchronized void close()
    {
        HttpServer server = mServer;
        mServer = null;

        if(server != null)
        {
            server.stop(0);
        }

        mSampler.shutdownNow();
        mHttpExecutor.shutdownNow();

        if(mControls instanceof AutoCloseable closeable)
        {
            try
            {
                closeable.close();
            }
            catch(Exception e)
            {
                mLog.debug("Error closing receiver debug controls", e);
            }
        }
    }

    private static final class BadRequestException extends IOException
    {
        private BadRequestException(String message)
        {
            super(message);
        }
    }
}
