/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.audio.broadcast.broadcastify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.health.ChannelHealth;
import io.github.dsheirer.health.HealthHistory;
import io.github.dsheirer.health.HealthRegistry;
import io.github.dsheirer.health.HealthSnapshot;
import io.github.dsheirer.health.NodeReport;
import io.github.dsheirer.health.NodeReporter;
import io.github.dsheirer.util.ThreadPool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends a Broadcastify Calls node (a system ID) its node health reports: every 5 minutes, at a random point of the
 * node's own, how well this recorder hears the sites and channels whose calls go to the node.  Broadcastify uses
 * them to choose between nodes that upload the same system, and to tell a node's owner about outages and problems.
 *
 * One reporter per node, shared by every destination that uploads as its system ID (to the same server): Broadcastify
 * takes one report per system ID, with all of its sites, never one per site or destination.  It reports while any of
 * those destinations runs, keeping its schedule while one restarts and another goes on.
 *
 * The node's sites and channels, from each of its destinations: a trunked-site destination's one saved channel;
 * otherwise those whose alias list has an alias streaming to the destination.  The report covers each whole (all its
 * calls, not only those streamed here).
 */
public class BroadcastifyHealthReporter
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BroadcastifyHealthReporter.class);
    private static final long CHECK_INTERVAL_MS = 10_000;
    /** How often, and over how long, the node's sites are looked at for the several-sites warning. */
    private static final long SITES_CHECK_MS = 60_000;
    private static final String APPLY_URL = "https://www.broadcastify.com/apply/calls";

    /** The nodes reporting, by health report address and system ID. */
    private static final Map<String,BroadcastifyHealthReporter> NODES = new HashMap<>();

    /**
     * A destination uploading as the node.
     */
    private record Destination(BroadcastifyCallConfiguration configuration, AliasModel aliasModel)
    {
    }

    private final String mUrl;
    private final int mSystemId;
    private final HttpClient mHttpClient;
    private final Map<String,Destination> mDestinations = new LinkedHashMap<>();
    private volatile NodeReporter mReporter;
    private ScheduledFuture<?> mFuture;
    private boolean mToldKeys;
    private volatile String mWarning;
    private List<String> mWarnedSites;
    private long mSitesCheckedAt;
    private boolean mToldNotServing;
    private boolean mToldUnreachable;
    private String mLastMessage;

    /**
     * @param url to send the node's reports to
     * @param systemId of the node
     * @param httpClient to send with
     * @param reporter the node's schedule
     */
    BroadcastifyHealthReporter(String url, int systemId, HttpClient httpClient, NodeReporter reporter)
    {
        mUrl = url;
        mSystemId = systemId;
        mHttpClient = httpClient;
        mReporter = reporter;
    }

    /**
     * A destination starts: its node reports, with the destination's sites and channels among its own.
     * @param configuration of the destination
     * @param aliasModel to find the destination's channels
     * @return the node's reporter, to leave when the destination stops, or null when it sends no reports
     */
    public static BroadcastifyHealthReporter join(BroadcastifyCallConfiguration configuration, AliasModel aliasModel)
    {
        String url = BroadcastifyCallConfiguration.getHealthUrl(configuration.getHost());

        if(url == null)
        {
            LOGGER.info("Broadcastify Calls system [" + configuration.getSystemID() + "] - not sending health " +
                "reports: the upload server [" + configuration.getHost() + "] doesn't end in call-upload");
            return null;
        }

        synchronized(NODES)
        {
            int systemId = configuration.getSystemID();
            BroadcastifyHealthReporter node = NODES.computeIfAbsent(key(url, systemId), key ->
                new BroadcastifyHealthReporter(url, systemId, HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build(), new NodeReporter(String.valueOf(systemId))));
            node.add(configuration, aliasModel);
            node.start();
            return node;
        }
    }

    /**
     * A destination stops: the node reports without it, and stops reporting once none of its destinations runs.
     */
    public void leave(BroadcastifyCallConfiguration configuration)
    {
        synchronized(NODES)
        {
            if(remove(configuration))
            {
                stop();
                NODES.remove(key(mUrl, mSystemId), this);
            }
        }
    }

    private static String key(String url, int systemId)
    {
        return url + "#" + systemId;
    }

    /**
     * Adds a destination uploading as the node (again, after it restarts).  Its API key is the node's when it's the
     * first; another destination's differing key is said once.  A node whose credentials were refused starts its
     * reports over: the destination's settings may have changed.
     */
    synchronized void add(BroadcastifyCallConfiguration configuration, AliasModel aliasModel)
    {
        mDestinations.put(configuration.getConfigurationId(), new Destination(configuration, aliasModel));
        Destination first = mDestinations.values().iterator().next();

        if(!mToldKeys && !Objects.equals(first.configuration().getApiKey(), configuration.getApiKey()))
        {
            mToldKeys = true;
            LOGGER.warn("Broadcastify Calls destinations [" + first.configuration().getName() + "] and [" +
                configuration.getName() + "] upload as system [" + mSystemId + "] with different API keys - its " +
                "node health reports use the key of [" + first.configuration().getName() + "]");
        }

        if(mReporter.isStopped())
        {
            NodeReporter reporter = new NodeReporter(String.valueOf(mSystemId));
            reporter.begin(HealthRegistry.getInstance().getHistory().latest());
            mReporter = reporter;
        }
    }

    /**
     * Removes a destination.
     * @return true when the node has none left
     */
    synchronized boolean remove(BroadcastifyCallConfiguration configuration)
    {
        mDestinations.remove(configuration.getConfigurationId());
        return mDestinations.isEmpty();
    }

    private synchronized List<Destination> destinations()
    {
        return new ArrayList<>(mDestinations.values());
    }

    /**
     * What the user should know about the node, or null: that its system ID uploads several sites.
     */
    public String getWarning()
    {
        return mWarning;
    }

    /**
     * The node's schedule.
     */
    NodeReporter getSchedule()
    {
        return mReporter;
    }

    /**
     * Starts checking, every 10 seconds, whether the node's next report is due.
     */
    private synchronized void start()
    {
        if(mFuture == null)
        {
            //Starts the snapshots, if they haven't: the node's reports start at the latest.
            mReporter.begin(HealthRegistry.getInstance().getHistory().latest());
            mFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::check, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void stop()
    {
        if(mFuture != null)
        {
            mFuture.cancel(true);
            mFuture = null;
        }
    }

    /**
     * True when a channel's calls can stream to the node: to any of its destinations.
     */
    boolean feeds(ChannelHealth.ChannelInfo info)
    {
        for(Destination destination: destinations())
        {
            if(feeds(destination.configuration(), destination.aliasModel(), info))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * True when a channel's calls can stream to a destination.  A trunked-site destination takes the calls of its one
     * saved channel, while its alias list still exists; any other takes those of the channels whose alias list has an
     * alias streaming to it.
     */
    static boolean feeds(BroadcastifyCallConfiguration configuration, AliasModel aliasModel,
                         ChannelHealth.ChannelInfo info)
    {
        if(configuration instanceof BroadcastifyCallSiteConfiguration site)
        {
            return info.configurationId() != null &&
                info.configurationId().equals(site.getChannelConfigurationId()) &&
                site.resolveAliasList(aliasModel).isPresent();
        }

        String stream = configuration.getConfigurationId();

        if(stream == null || stream.isBlank() || info.aliasListId() <= Alias.UNASSIGNED_ID)
        {
            return false;
        }

        for(Alias alias: aliasModel.getAliases())
        {
            if(alias.getAliasListId() == info.aliasListId() && alias.hasBroadcastConfiguration(stream))
            {
                return true;
            }
        }

        return false;
    }

    private void check()
    {
        check(HealthRegistry.getInstance().getHistory());
    }

    /**
     * Sends the node's report if one is due, from these snapshots.
     */
    void check(HealthHistory history)
    {
        try
        {
            NodeReporter reporter = mReporter;
            List<Destination> destinations = destinations();
            HealthSnapshot now = history.latest();

            if(destinations.isEmpty() || now == null)
            {
                return;
            }

            if(now.timeMs() - mSitesCheckedAt >= SITES_CHECK_MS)
            {
                mSitesCheckedAt = now.timeMs();
                checkSites(history, now);
            }

            if(reporter.isStopped())
            {
                return;
            }

            if(!reporter.isDue(now))
            {
                return;
            }

            NodeReporter.Window window = reporter.window(history);

            if(window == null)
            {
                return;
            }

            //Which channels feed the node: worked out once per report, since the alias lists can change.
            Set<Integer> feeding = new HashSet<>();

            for(ChannelHealth.Totals totals: window.now().channels())
            {
                if(feeds(totals.info()))
                {
                    feeding.add(totals.info().channelId());
                }
            }

            JsonObject node = new JsonObject();
            node.addProperty("systemId", mSystemId);
            node.addProperty("apiKey", destinations.getFirst().configuration().getApiKey());
            JsonObject report = NodeReport.build(window, node, ApplicationInfo.getVersion(),
                info -> feeding.contains(info.channelId()));

            if(report != null)
            {
                send(reporter, report, window.now());
            }
        }
        catch(Throwable t)
        {
            LOGGER.error("Error sending a Broadcastify Calls health report", t);
        }
    }

    /**
     * Broadcastify registers each system ID to one site, and labels every call uploaded as it as coming from that
     * site: one system ID uploading several sites labels the others' calls as the wrong site.  Warns of it, once each
     * time the sites change, and has the warning shown with the destination.  Uploads carry on.
     */
    void checkSites(HealthHistory history, HealthSnapshot now)
    {
        HealthSnapshot base = history.atOrAfter(now.timeMs() - SITES_CHECK_MS);
        List<String> sites = NodeReport.sites(base != now ? base : null, now, this::feeds);

        if(sites.size() < 2)
        {
            mWarning = null;
            mWarnedSites = null;
            return;
        }

        String warning = "Broadcastify system " + mSystemId + " is uploading " + sites.size() + " sites: " +
            String.join(", ", sites) + ". Broadcastify Calls needs a system ID for each site you upload, or calls " +
            "from the other sites are labeled as the wrong site. Give each site its own Broadcastify system ID. If " +
            "you need more system IDs, apply at " + APPLY_URL + " (you can use your existing API key).";

        if(!sites.equals(mWarnedSites))
        {
            mWarnedSites = sites;
            LOGGER.warn(warning);
        }

        mWarning = warning;
    }

    private void send(NodeReporter reporter, JsonObject report, HealthSnapshot now)
    {
        int systemId = mSystemId;
        HttpResponse<String> response;

        try
        {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(report.toString()))
                .build();
            response = mHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return;
        }
        catch(Exception e)
        {
            unreachable(reporter, now, e.getMessage());
            return;
        }

        int status = response.statusCode();
        String body = response.body() != null ? response.body().trim() : "";
        String said = body.length() > 200 ? body.substring(0, 200) : body;

        if(status >= 200 && status < 300)
        {
            JsonObject answer = jsonObject(body);

            if(answer == null)
            {
                notServing(reporter, now, "it answered [" + said + "]");
                return;
            }

            mToldUnreachable = false;
            mToldNotServing = false;
            Double nextS = number(answer, "nextS");
            reporter.delivered(nextS);
            String message = answer.has("message") && answer.get("message").isJsonPrimitive() ?
                answer.get("message").getAsString() : null;

            if(message != null && !message.isEmpty() && !message.equals(mLastMessage))
            {
                LOGGER.info("Broadcastify Calls system [" + systemId + "]: " + message);
                mLastMessage = message;
            }
        }
        else if(status == 404 || status == 405 || status == 410)
        {
            notServing(reporter, now, "HTTP " + status);
        }
        else if(status == 401 || status == 403)
        {
            LOGGER.warn("Broadcastify Calls refused the health report of system [" + systemId + "] (HTTP " +
                status + ": " + said + ") - not sending its reports until its settings change");
            reporter.failed(now, NodeReporter.Failure.REFUSED, null);
        }
        else if(status == 429 || status == 503)
        {
            Double retryAfter = response.headers().firstValue("Retry-After")
                .map(value -> retryAfterSeconds(value, System.currentTimeMillis())).orElse(null);
            reporter.failed(now, NodeReporter.Failure.BUSY, retryAfter);
        }
        else if(status == 400)
        {
            LOGGER.warn("Broadcastify Calls turned down the health report of system [" + systemId + "]: " + said);
            reporter.failed(now, NodeReporter.Failure.REJECTED, null);
        }
        else
        {
            unreachable(reporter, now, "HTTP " + status);
        }
    }

    private void unreachable(NodeReporter reporter, HealthSnapshot now, String why)
    {
        if(!mToldUnreachable)
        {
            mToldUnreachable = true;
            LOGGER.info("Broadcastify Calls health report of system [" + mSystemId + "] not sent: " + why);
        }

        reporter.failed(now, NodeReporter.Failure.UNREACHABLE, null);
    }

    /**
     * The server doesn't take reports (or something else answered, such as a proxy's page): said once, and tried
     * less and less often, up to hourly, as for a busy server.
     */
    private void notServing(NodeReporter reporter, HealthSnapshot now, String why)
    {
        if(!mToldNotServing)
        {
            mToldNotServing = true;
            LOGGER.info(mUrl + " doesn't take node health reports (" + why + ") - trying again later, up to hourly");
        }

        reporter.failed(now, NodeReporter.Failure.BUSY, null);
    }

    /**
     * A Retry-After header's wait, seconds: a number of seconds, or an HTTP date.  Null when it's neither.
     * @param value of the header
     * @param nowMs the time now, Unix milliseconds, for an HTTP date
     */
    static Double retryAfterSeconds(String value, long nowMs)
    {
        if(value == null || value.isBlank())
        {
            return null;
        }

        try
        {
            return Double.parseDouble(value.trim());
        }
        catch(NumberFormatException nfe)
        {
            //An HTTP date, next
        }

        try
        {
            ZonedDateTime at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0, at.toInstant().toEpochMilli() - nowMs) / 1000.0;
        }
        catch(Exception e)
        {
            return null;
        }
    }

    private static JsonObject jsonObject(String body)
    {
        try
        {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch(Exception e)
        {
            return null;
        }
    }

    private static Double number(JsonObject object, String name)
    {
        try
        {
            return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsDouble() : null;
        }
        catch(Exception e)
        {
            return null;
        }
    }
}
