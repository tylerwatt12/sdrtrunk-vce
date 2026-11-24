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
import java.util.HashSet;
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
 * The node's sites and channels: a trunked-site node's one saved channel; otherwise those whose alias list has an
 * alias streaming to this configuration.  The report covers each whole (all its calls, not only those streamed here).
 */
public class BroadcastifyHealthReporter
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BroadcastifyHealthReporter.class);
    private static final long CHECK_INTERVAL_MS = 10_000;

    private final BroadcastifyCallConfiguration mConfiguration;
    private final AliasModel mAliasModel;
    private final HttpClient mHttpClient;
    private final String mUrl;
    private final NodeReporter mReporter;
    private ScheduledFuture<?> mFuture;
    private boolean mToldNotServing;
    private boolean mToldUnreachable;
    private String mLastMessage;

    /**
     * @param configuration of the node
     * @param aliasModel to find the node's channels
     * @param httpClient to send with
     */
    public BroadcastifyHealthReporter(BroadcastifyCallConfiguration configuration, AliasModel aliasModel,
                                      HttpClient httpClient)
    {
        this(configuration, aliasModel, httpClient, new NodeReporter(String.valueOf(configuration.getSystemID())));
    }

    /**
     * For tests: a given schedule.
     */
    BroadcastifyHealthReporter(BroadcastifyCallConfiguration configuration, AliasModel aliasModel,
                               HttpClient httpClient, NodeReporter reporter)
    {
        mConfiguration = configuration;
        mAliasModel = aliasModel;
        mHttpClient = httpClient;
        mUrl = BroadcastifyCallConfiguration.getHealthUrl(configuration.getHost());
        mReporter = reporter;
    }

    /**
     * Starts checking, every 10 seconds, whether the node's next report is due.
     */
    public synchronized void start()
    {
        if(mFuture != null)
        {
            return;
        }

        if(mUrl == null)
        {
            LOGGER.info("Broadcastify Calls system [" + mConfiguration.getSystemID() + "] - not sending health " +
                "reports: the upload server [" + mConfiguration.getHost() + "] doesn't end in call-upload");
            return;
        }

        //Starts the snapshots, if they haven't: the node's reports start at the latest.
        mReporter.begin(HealthRegistry.getInstance().getHistory().latest());
        mFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::check, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
    }

    public synchronized void stop()
    {
        if(mFuture != null)
        {
            mFuture.cancel(true);
            mFuture = null;
        }
    }

    /**
     * True when a channel's calls can stream to this node.  A trunked-site node takes the calls of its one saved
     * channel, while its alias list still exists; any other takes those of the channels whose alias list has an alias
     * streaming to it.
     */
    boolean feeds(ChannelHealth.ChannelInfo info)
    {
        if(mConfiguration instanceof BroadcastifyCallSiteConfiguration site)
        {
            return info.configurationId() != null && info.configurationId().equals(site.getChannelConfigurationId()) &&
                site.resolveAliasList(mAliasModel).isPresent();
        }

        String stream = mConfiguration.getConfigurationId();

        if(stream == null || stream.isBlank() || info.aliasListId() <= Alias.UNASSIGNED_ID)
        {
            return false;
        }

        for(Alias alias: mAliasModel.getAliases())
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
            if(mReporter.isStopped() || mUrl == null)
            {
                return;
            }

            HealthSnapshot now = history.latest();

            if(!mReporter.isDue(now))
            {
                return;
            }

            NodeReporter.Window window = mReporter.window(history);

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
            node.addProperty("systemId", mConfiguration.getSystemID());
            node.addProperty("apiKey", mConfiguration.getApiKey());
            JsonObject report = NodeReport.build(window, node, ApplicationInfo.getVersion(),
                info -> feeding.contains(info.channelId()));

            if(report != null)
            {
                send(report, window.now());
            }
        }
        catch(Throwable t)
        {
            LOGGER.error("Error sending a Broadcastify Calls health report", t);
        }
    }

    private void send(JsonObject report, HealthSnapshot now)
    {
        int systemId = mConfiguration.getSystemID();
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
            unreachable(now, systemId, e.getMessage());
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
                notServing(now, "it answered [" + said + "]");
                return;
            }

            mToldUnreachable = false;
            mToldNotServing = false;
            Double nextS = number(answer, "nextS");
            mReporter.delivered(nextS);
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
            notServing(now, "HTTP " + status);
        }
        else if(status == 401 || status == 403)
        {
            LOGGER.warn("Broadcastify Calls refused the health report of system [" + systemId + "] (HTTP " +
                status + ": " + said + ") - not sending its reports until its settings change");
            mReporter.failed(now, NodeReporter.Failure.REFUSED, null);
        }
        else if(status == 429 || status == 503)
        {
            Double retryAfter = response.headers().firstValue("Retry-After")
                .map(value -> retryAfterSeconds(value, System.currentTimeMillis())).orElse(null);
            mReporter.failed(now, NodeReporter.Failure.BUSY, retryAfter);
        }
        else if(status == 400)
        {
            LOGGER.warn("Broadcastify Calls turned down the health report of system [" + systemId + "]: " + said);
            mReporter.failed(now, NodeReporter.Failure.REJECTED, null);
        }
        else
        {
            unreachable(now, systemId, "HTTP " + status);
        }
    }

    private void unreachable(HealthSnapshot now, int systemId, String why)
    {
        if(!mToldUnreachable)
        {
            mToldUnreachable = true;
            LOGGER.info("Broadcastify Calls health report of system [" + systemId + "] not sent: " + why);
        }

        mReporter.failed(now, NodeReporter.Failure.UNREACHABLE, null);
    }

    /**
     * The server doesn't take reports (or something else answered, such as a proxy's page): said once, and tried
     * less and less often, up to hourly, as for a busy server.
     */
    private void notServing(HealthSnapshot now, String why)
    {
        if(!mToldNotServing)
        {
            mToldNotServing = true;
            LOGGER.info(mUrl + " doesn't take node health reports (" + why + ") - trying again later, up to hourly");
        }

        mReporter.failed(now, NodeReporter.Failure.BUSY, null);
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
