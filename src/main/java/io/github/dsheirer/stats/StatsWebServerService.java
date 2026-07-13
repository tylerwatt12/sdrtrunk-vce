/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.Subscribe;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.stats.activity.P25ActivityCommitListener;
import io.github.dsheirer.stats.activity.P25ActivityLogPath;
import io.github.dsheirer.stats.activity.P25ActivityLogService;
import io.github.dsheirer.stats.activity.P25ActivityLogStatus;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.dsheirer.eventbus.MyEventBus;

/**
 * Embedded stats web server. Static assets are served only from an external filesystem folder.
 */
public class StatsWebServerService implements AutoCloseable, P25ActivityCommitListener
{
    private static final Logger mLog = LoggerFactory.getLogger(StatsWebServerService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UserPreferences mUserPreferences;
    private final StatsWebDatabase mDatabase;
    private final StatsLiveService mLiveService;
    private final StatsWebCallService mWebCallService = new StatsWebCallService();
    private final ChannelProcessingManager mChannelProcessingManager;
    private final P25ActivityLogService mActivityLogService;
    private HttpServer mServer;
    private ExecutorService mExecutorService;
    private Path mAssetRoot;
    private int mPort;
    private boolean mLanEnabled;

    public StatsWebServerService(UserPreferences userPreferences)
    {
        this(userPreferences, null, null);
    }

    public StatsWebServerService(UserPreferences userPreferences, ChannelProcessingManager channelProcessingManager)
    {
        this(userPreferences, channelProcessingManager, null);
    }

    public StatsWebServerService(UserPreferences userPreferences, ChannelProcessingManager channelProcessingManager,
                                 P25ActivityLogService activityLogService)
    {
        mUserPreferences = userPreferences;
        mChannelProcessingManager = channelProcessingManager;
        mActivityLogService = activityLogService;
        mDatabase = new StatsWebDatabase(userPreferences);
        mLiveService = new StatsLiveService(mDatabase,
            channelProcessingManager != null ? channelProcessingManager.getChannelActivityModel() : null);
        MyEventBus.getGlobalEventBus().register(this);
        updateServerState();
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.APPLICATION || preferenceType == PreferenceType.DIRECTORY)
        {
            updateServerState();
        }
    }

    private synchronized void updateServerState()
    {
        ApplicationPreference preference = mUserPreferences.getApplicationPreference();

        if(!preference.isStatsWebServerEnabled())
        {
            stop();
            return;
        }

        int requestedPort = preference.getStatsWebServerPort();
        boolean requestedLan = preference.isStatsWebServerLanEnabled();
        Path requestedRoot = StatsWebPath.getAssetsPath();

        if(mServer != null && requestedPort == mPort && requestedLan == mLanEnabled &&
            requestedRoot.equals(mAssetRoot))
        {
            return;
        }

        stop();
        start(requestedRoot, requestedPort, requestedLan);
    }

    private void start(Path assetRoot, int port, boolean lanEnabled)
    {
        try
        {
            if(mChannelProcessingManager != null)
            {
                mChannelProcessingManager.setChannelActivityEnabled("stats-web", true);
            }

            mLiveService.start();
            mWebCallService.start();

            Files.createDirectories(assetRoot);
            InetAddress bindAddress = lanEnabled ? InetAddress.getByName("0.0.0.0") : InetAddress.getLoopbackAddress();
            mServer = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            mExecutorService = Executors.newCachedThreadPool(new NamingThreadFactory("stats web server"));
            mServer.setExecutor(mExecutorService);
            mAssetRoot = assetRoot;
            mPort = port;
            mLanEnabled = lanEnabled;

            mServer.createContext("/api/status", exchange -> handleJson(exchange, this::status));
            mServer.createContext("/api/dashboard", exchange -> handleJson(exchange, mDatabase::dashboard));
            mServer.createContext("/api/systems", exchange -> handleJson(exchange,
                () -> mDatabase.systems(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/sites", exchange -> handleJson(exchange,
                () -> mDatabase.sites(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/system", exchange -> handleJson(exchange,
                () -> mDatabase.system(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/system/sites", exchange -> handleJson(exchange,
                () -> mDatabase.systemSites(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/system/talkgroups", exchange -> handleJson(exchange,
                () -> mDatabase.systemTalkgroups(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/system/radios", exchange -> handleJson(exchange,
                () -> mDatabase.systemRadios(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/system/talker-aliases", exchange -> handleJson(exchange,
                () -> mDatabase.systemTalkerAliases(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/talkgroup", exchange -> handleJson(exchange,
                () -> mDatabase.talkgroup(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/radio", exchange -> handleJson(exchange,
                () -> mDatabase.radio(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/affiliations", exchange -> handleJson(exchange,
                () -> mDatabase.currentAffiliations(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/radio-talkgroups", exchange -> handleJson(exchange,
                () -> mDatabase.radioTalkgroupRelationships(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/site", exchange -> handleJson(exchange,
                () -> mDatabase.site(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/site/channels", exchange -> handleJson(exchange,
                () -> mDatabase.siteChannels(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/site/bands", exchange -> handleJson(exchange,
                () -> mDatabase.siteBands(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/site/neighbors", exchange -> handleJson(exchange,
                () -> mDatabase.siteNeighbors(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/site/patches", exchange -> handleJson(exchange,
                () -> mDatabase.sitePatches(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/activity", exchange -> handleJson(exchange,
                () -> mDatabase.activity(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/activity/recent", exchange -> handleJson(exchange,
                () -> mDatabase.activity(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/conventional", exchange -> handleJson(exchange,
                () -> mDatabase.conventional(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/api/conventional/detail", exchange -> handleJson(exchange,
                () -> mDatabase.conventionalDetail(StatsRequest.from(exchange.getRequestURI()))));
            mServer.createContext("/live/systems", this::handleSystemsSse);
            mServer.createContext("/live/web-calls", this::handleWebCallsSse);
            mServer.createContext("/live/activity", this::handleActivitySse);
            mServer.createContext("/api/web-player/calls/", this::handleWebCallAudio);
            mServer.createContext("/", this::handleStatic);
            mServer.start();

            mLog.info("Stats web server started at http://{}:{}/ using assets [{}]",
                lanEnabled ? "0.0.0.0" : "127.0.0.1", port, assetRoot);
        }
        catch(IOException e)
        {
            mLog.warn("Unable to start stats web server on port [{}]", port, e);
            stop();
        }
    }

    private synchronized void stop()
    {
        mLiveService.stop();
        mWebCallService.stop();

        if(mServer != null)
        {
            mServer.stop(0);
            mServer = null;
            mLog.info("Stats web server stopped");
        }

        if(mExecutorService != null)
        {
            mExecutorService.shutdownNow();
            mExecutorService = null;
        }

        mAssetRoot = null;
        mPort = 0;
        mLanEnabled = false;

        if(mChannelProcessingManager != null)
        {
            mChannelProcessingManager.setChannelActivityEnabled("stats-web", false);
        }
    }

    private Map<String,Object> status()
    {
        Map<String,Object> status = new LinkedHashMap<>();
        status.put("server", Map.of(
            "enabled", mServer != null,
            "port", mPort,
            "lanEnabled", mLanEnabled,
            "assetRoot", mAssetRoot != null ? mAssetRoot.toString() : "",
            "assetsAvailable", mAssetRoot != null && Files.isRegularFile(mAssetRoot.resolve("index.html")),
            "liveChannels", Map.of(
                "systems", "/live/systems",
                "webCalls", "/live/web-calls",
                "activity", "/live/activity"
            )
        ));
        status.put("database", mDatabase.status());
        status.put("statsLogging", statsLoggingStatus());
        status.put("webPlayer", mWebCallService.status());
        return status;
    }

    private P25ActivityLogStatus statsLoggingStatus()
    {
        if(mActivityLogService != null)
        {
            return mActivityLogService.getStatus();
        }

        ApplicationPreference preference = mUserPreferences.getApplicationPreference();
        boolean summaryConfigured = preference.isStatsLoggingEnabled();
        return new P25ActivityLogStatus(summaryConfigured, preference.isStatsDetailedHistoryEnabled(), false, false,
            preference.getStatsLoggingRetentionDays(), summaryConfigured ? P25ActivityLogStatus.State.STOPPED :
            P25ActivityLogStatus.State.DISABLED, P25ActivityLogPath.getDatabasePath(mUserPreferences).toString(),
            0, 0, 0, null);
    }

    private void handleJson(HttpExchange exchange, JsonSupplier supplier) throws IOException
    {
        if(!isAllowed(exchange) || !requireMethod(exchange, "GET"))
        {
            return;
        }

        try
        {
            sendJson(exchange, 200, supplier.get());
        }
        catch(StatsApiException e)
        {
            sendJson(exchange, e.status(), Map.of("error", e.getMessage(), "status", e.status()));
        }
        catch(RuntimeException e)
        {
            mLog.warn("Stats web request failed [{}]", exchange.getRequestURI().getPath(), e);
            sendJson(exchange, 500, Map.of("error", "Stats request failed", "status", 500));
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Map<String,Object> value) throws IOException
    {
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(value);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);

        try(OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(body);
        }
    }

    private void handleSystemsSse(HttpExchange exchange) throws IOException
    {
        if(!isAllowed(exchange) || !requireMethod(exchange, "GET"))
        {
            return;
        }

        StatsLiveEventHub.Subscription subscription = mLiveService.subscribeSystems();

        if(subscription == null)
        {
            sendText(exchange, 429, "Too many live Stats Server clients");
            return;
        }

        streamSse(exchange, subscription, "snapshot", mLiveService.snapshot(), event -> true);
    }

    private void handleWebCallsSse(HttpExchange exchange) throws IOException
    {
        if(!isAllowed(exchange) || !requireMethod(exchange, "GET"))
        {
            return;
        }

        StatsLiveEventHub.Subscription subscription = mWebCallService.subscribe();

        if(subscription == null)
        {
            sendText(exchange, 429, "Too many live Stats Server clients");
            return;
        }

        streamSse(exchange, subscription, "ready", Map.of("state", "live"),
            event -> "call".equals(event.name()));
    }

    private void handleActivitySse(HttpExchange exchange) throws IOException
    {
        if(!isAllowed(exchange) || !requireMethod(exchange, "GET"))
        {
            return;
        }

        StatsRequest request = StatsRequest.from(exchange.getRequestURI());

        try
        {
            validateActivityRequest(request);
        }
        catch(StatsApiException e)
        {
            sendText(exchange, e.status(), e.getMessage());
            return;
        }

        StatsLiveEventHub.Subscription subscription = mLiveService.subscribeActivity();

        if(subscription == null)
        {
            sendText(exchange, 429, "Too many live Stats Server clients");
            return;
        }

        streamSse(exchange, subscription, "ready", Map.of("state", "live"),
            event -> event.data() instanceof Map<?,?> row && matchesActivity(row, request));
    }

    private void streamSse(HttpExchange exchange, StatsLiveEventHub.Subscription subscription, String initialEvent,
                           Object initialData, java.util.function.Predicate<StatsLiveEventHub.LiveEvent> filter)
        throws IOException
    {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        HttpServer server = mServer;

        try(subscription; OutputStream outputStream = exchange.getResponseBody())
        {
            writeSseEvent(outputStream, initialEvent, initialData);

            while(mServer == server && !subscription.isClosed())
            {
                StatsLiveEventHub.LiveEvent event = subscription.poll(15, TimeUnit.SECONDS);

                if(event == null)
                {
                    outputStream.write((": heartbeat " + System.currentTimeMillis() + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
                else if(filter.test(event))
                {
                    writeSseEvent(outputStream, event.name(), event.data());
                }
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch(IOException e)
        {
            // Client disconnected.
        }
    }

    private void handleWebCallAudio(HttpExchange exchange) throws IOException
    {
        if(!isAllowed(exchange) || !requireMethod(exchange, "GET"))
        {
            return;
        }

        String prefix = "/api/web-player/calls/";
        String path = exchange.getRequestURI().getPath();

        if(path == null || !path.startsWith(prefix) || !path.endsWith("/audio"))
        {
            sendText(exchange, 404, "Call audio not found");
            return;
        }

        String id = path.substring(prefix.length(), path.length() - "/audio".length());
        StatsWebCallService.CachedCall call = mWebCallService.get(id);

        if(call == null)
        {
            sendText(exchange, 404, "Call audio is no longer available");
            return;
        }

        byte[] wave = call.wave();
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "audio/wav");
        headers.set("Cache-Control", "no-store, no-transform");
        headers.set("Accept-Ranges", "none");
        exchange.sendResponseHeaders(200, wave.length);

        try(OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(wave);
        }
    }

    private static void writeSseEvent(OutputStream outputStream, String event, Object data)
        throws IOException
    {
        outputStream.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        outputStream.write(("data: " + OBJECT_MAPPER.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private static boolean matchesActivity(Map<?,?> row, StatsRequest request)
    {
        Integer wacn = request.optionalIdentifier("wacn");
        Integer system = request.optionalIdentifier("system_id");
        Integer talkgroup = request.optionalIdentifier("talkgroup_id");
        Integer radio = request.optionalIdentifier("radio_id");
        String guid = request.text("guid");
        String context = request.text("context");

        if(wacn != null && !numberEquals(row.get("wacn"), wacn) ||
            system != null && !numberEquals(row.get("system_id"), system) ||
            guid != null && !guid.equals(row.get("guid")) ||
            context != null && !context.equals(row.get("context_key")))
        {
            return false;
        }

        if(talkgroup != null && (!numberEquals(row.get("target_id"), talkgroup) ||
            !(numberEquals(row.get("target_kind_code"), 1) || numberEquals(row.get("target_kind_code"), 3))))
        {
            return false;
        }

        return radio == null || numberEquals(row.get("source_radio_id"), radio) ||
            numberEquals(row.get("target_id"), radio) && numberEquals(row.get("target_kind_code"), 2);
    }

    private static boolean numberEquals(Object value, int expected)
    {
        return value instanceof Number number && number.intValue() == expected;
    }

    private static void validateActivityRequest(StatsRequest request)
    {
        request.optionalIdentifier("wacn");
        request.optionalIdentifier("system_id");
        request.optionalIdentifier("talkgroup_id");
        request.optionalIdentifier("radio_id");
    }

    @Override
    public void activityCommitted(java.util.List<Long> rowIds)
    {
        mLiveService.activityCommitted(rowIds);
    }

    /**
     * Receives completed calls independently from local Java playback.
     */
    public void receive(CompletedAudioCall call)
    {
        mWebCallService.receive(call);
    }

    private void handleStatic(HttpExchange exchange) throws IOException
    {
        if(!isAllowed(exchange) || !requireMethod(exchange, "GET", "HEAD"))
        {
            return;
        }

        Path root = mAssetRoot;

        if(root == null)
        {
            sendText(exchange, 503, "Stats web server assets folder is unavailable.");
            return;
        }

        String requestPath = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);

        if(requestPath == null || requestPath.equals("/") || requestPath.isBlank())
        {
            requestPath = "/index.html";
        }

        Path file = root.resolve(requestPath.substring(1)).normalize();

        if(!file.startsWith(root))
        {
            sendText(exchange, 403, "Forbidden");
            return;
        }

        if(!Files.isRegularFile(file))
        {
            if("/index.html".equals(requestPath))
            {
                sendMissingAssetsPage(exchange, root);
            }
            else
            {
                sendText(exchange, 404, "Not found");
            }

            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(file));
        headers.set("Cache-Control", "no-cache");
        long length = Files.size(file);
        exchange.sendResponseHeaders(200, "HEAD".equals(exchange.getRequestMethod()) ? -1 : length);

        if(!"HEAD".equals(exchange.getRequestMethod()))
        {
            try(OutputStream outputStream = exchange.getResponseBody())
            {
                Files.copy(file, outputStream);
            }
        }
        else
        {
            exchange.close();
        }
    }

    private void sendMissingAssetsPage(HttpExchange exchange, Path root) throws IOException
    {
        sendHtml(exchange, 200, """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>sdrtrunk-vce</title>
              <style>body{font-family:Arial,sans-serif;margin:2rem;line-height:1.4}</style>
            </head>
            <body>
              <h1>sdrtrunk-vce</h1>
              <p>No web assets were found in:</p>
              <pre>%s</pre>
            </body>
            </html>
            """.formatted(root));
    }

    private boolean isAllowed(HttpExchange exchange) throws IOException
    {
        InetAddress remoteAddress = exchange.getRemoteAddress().getAddress();

        if(remoteAddress.isLoopbackAddress())
        {
            return true;
        }

        if(!mLanEnabled)
        {
            sendText(exchange, 403, "Stats web server is limited to this computer.");
            return false;
        }

        if(isPrivateOrTailnet(remoteAddress))
        {
            return true;
        }

        sendText(exchange, 403, "Stats web server only allows loopback, LAN, link-local, and Tailscale clients.");
        return false;
    }

    private static boolean isPrivateOrTailnet(InetAddress address)
    {
        if(address.isSiteLocalAddress() || address.isLinkLocalAddress())
        {
            return true;
        }

        byte[] bytes = address.getAddress();

        if(bytes.length == 4)
        {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        }

        int first = bytes[0] & 0xFF;
        return (first & 0xFE) == 0xFC;
    }

    private boolean requireMethod(HttpExchange exchange, String... methods) throws IOException
    {
        String actual = exchange.getRequestMethod();

        for(String method: methods)
        {
            if(method.equals(actual))
            {
                return true;
            }
        }

        sendText(exchange, 405, "Method not allowed");
        return false;
    }

    private static String contentType(Path file)
    {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if(name.endsWith(".html"))
        {
            return "text/html; charset=utf-8";
        }
        else if(name.endsWith(".css"))
        {
            return "text/css; charset=utf-8";
        }
        else if(name.endsWith(".js"))
        {
            return "application/javascript; charset=utf-8";
        }
        else if(name.endsWith(".json"))
        {
            return "application/json; charset=utf-8";
        }
        else if(name.endsWith(".txt"))
        {
            return "text/plain; charset=utf-8";
        }
        else if(name.endsWith(".svg"))
        {
            return "image/svg+xml";
        }
        else if(name.endsWith(".png"))
        {
            return "image/png";
        }

        return "application/octet-stream";
    }

    private static void sendHtml(HttpExchange exchange, int status, String body) throws IOException
    {
        send(exchange, status, "text/html; charset=utf-8", body);
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException
    {
        send(exchange, status, "text/plain; charset=utf-8", body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);

        try(OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(bytes);
        }
    }

    @Override
    public void close()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
        stop();
        mLiveService.close();
        mWebCallService.close();
    }

    private interface JsonSupplier
    {
        Map<String,Object> get();
    }
}
