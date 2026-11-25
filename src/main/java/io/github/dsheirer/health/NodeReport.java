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

package io.github.dsheirer.health;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Makes a node health report (version 1): what one node's sites, conventional channels and tuners did over a window,
 * as counts and seconds.  The format is the one Trunk Recorder Pro, SDRTrunk and SDRTrunk VCE share
 * (https://github.com/blantonl/bcfy-calls-node-health/blob/main/node-health.md).  A field the recorder
 * doesn't measure is left out, never sent as 0.
 */
public class NodeReport
{
    public static final int VERSION = 1;
    public static final String RECORDER = "sdrtrunk-vce";

    private NodeReport()
    {
    }

    /**
     * The report of a window, or null when nothing feeding the node ran in it.
     * @param window of the report
     * @param node the service's fields that say which node (for Broadcastify Calls: systemId and apiKey)
     * @param version of this recorder
     * @param feeds which channels feed the node
     */
    public static JsonObject build(NodeReporter.Window window, JsonObject node, String version,
                                   Predicate<ChannelHealth.ChannelInfo> feeds)
    {
        if(incomplete(window, feeds))
        {
            return null;
        }

        HealthSnapshot now = window.now();
        HealthSnapshot base = window.base();
        JsonArray sites = new JsonArray();
        List<ChannelHealth.ChannelInfo> siteChannels = new ArrayList<>();
        JsonArray channels = new JsonArray();

        for(ChannelHealth.Totals a: now.channels())
        {
            if(!feeds.test(a.info()) || !a.collecting())
            {
                continue;
            }

            ChannelHealth.Totals b = base != null ? base.channel(a.info().channelId()) : null;

            if(a.runningMs() <= 0 && b == null)
            {
                continue; //Has never run, rather than a configured receiver that stopped or is deaf.
            }

            //The traffic manager establishes trunked coverage before the first decoded frame. A deaf site must
            //remain visible rather than be reinterpreted as a conventional channel.
            if(a.calls() != null && (a.isSite() || isTrunkedMode(a.info().mode())))
            {
                sites.add(site(a, b));
                siteChannels.add(a.info());
            }
            else if(a.info().mode() != null && a.frequency() > 0 && a.heardMs() != null)
            {
                channels.add(channel(a, b, now.timeMs() - window.startMs()));
            }
        }

        if(sites.isEmpty() && channels.isEmpty())
        {
            return null;
        }

        nameReceivers(sites, siteChannels);

        JsonObject report = new JsonObject();
        report.addProperty("v", VERSION);
        report.add("node", node);

        JsonObject recorder = new JsonObject();
        recorder.addProperty("name", RECORDER);
        recorder.addProperty("version", version != null ? version : "unknown");
        recorder.addProperty("upS", r1((now.timeMs() - now.sinceMs()) / 1000.0));
        report.add("recorder", recorder);

        JsonObject w = new JsonObject();
        w.addProperty("start", seconds(window.startMs()));
        w.addProperty("end", seconds(now.timeMs()));
        w.addProperty("seq", window.seq());

        if(window.gap())
        {
            w.addProperty("gap", true);
        }

        report.add("window", w);

        if(!sites.isEmpty())
        {
            report.add("sites", sites);
        }

        if(!channels.isEmpty())
        {
            report.add("channels", channels);
        }

        JsonArray radios = radios(base, now);

        if(!radios.isEmpty())
        {
            report.add("radios", radios);
        }

        return report;
    }

    /**
     * A site heard by more than one of the node's channels (receivers) has an entry for each: each says which
     * receiver it is, by its channel's name.  A site heard once doesn't.
     */
    private static void nameReceivers(JsonArray sites, List<ChannelHealth.ChannelInfo> channels)
    {
        Map<String,Integer> heard = new HashMap<>();

        for(int i = 0; i < sites.size(); i++)
        {
            heard.merge(sameSite(sites.get(i).getAsJsonObject()), 1, Integer::sum);
        }

        for(int i = 0; i < sites.size(); i++)
        {
            JsonObject site = sites.get(i).getAsJsonObject();

            if(heard.get(sameSite(site)) > 1)
            {
                ChannelHealth.ChannelInfo info = channels.get(i);
                site.addProperty("receiver", info.name() != null && !info.name().isBlank() ? info.name() :
                    "channel " + info.channelId());
            }
        }
    }

    /**
     * What makes two entries the same site: its protocol and the identity its control channel gives, or without one,
     * the name the user gave it.
     */
    private static String sameSite(JsonObject site)
    {
        JsonObject id = site.getAsJsonObject("id");
        return site.get("protocol").getAsString() + "/" +
            (id != null && !id.isEmpty() ? id.toString() : "name:" + site.get("name").getAsString());
    }

    private static boolean isTrunkedMode(String mode)
    {
        return "p25".equals(mode) || "dmr".equals(mode) || "nxdn".equals(mode);
    }

    /** A lossy observer interval cannot supply the report's mandatory complete call totals. */
    public static boolean incomplete(NodeReporter.Window window, Predicate<ChannelHealth.ChannelInfo> feeds)
    {
        for(ChannelHealth.Totals current: window.now().channels())
        {
            if(feeds.test(current.info()) && !current.timingComplete())
            {
                return true;
            }
            ChannelHealth.Totals baseline = window.base() != null ? window.base().channel(current.info().channelId()) : null;
            if(feeds.test(current.info()) && current.collecting() &&
                ((baseline != null && baseline.collectionEpoch() != current.collectionEpoch()) ||
                    window.startMs() < current.collectionSinceMs()))
            {
                return true;
            }
            if(feeds.test(current.info()) && baseline != null && !baseline.timingComplete())
            {
                return true;
            }
            if(feeds.test(current.info()) && baseline != null)
            {
                for(Map.Entry<String,Integer> field: baseline.identity().entrySet())
                {
                    Integer present = current.identity().get(field.getKey());
                    if(present != null && !present.equals(field.getValue()))
                    {
                        return true; //Do not assign an old site's counts to a newly decoded site identity.
                    }
                }
            }
            if(feeds.test(current.info()) && window.base() != null && current.info().configurationId() != null)
            {
                for(ChannelHealth.Totals before: window.base().channels())
                {
                    if(current.info().configurationId().equals(before.info().configurationId()) &&
                        current.info().channelId() != before.info().channelId())
                    {
                        return true; //A saved channel was replaced: discard this mixed-incarnation window.
                    }
                }
            }
            if(feeds.test(current.info()) && current.calls() != null)
            {
                ChannelHealth.Totals before = window.base() != null ?
                    window.base().channel(current.info().channelId()) : null;
                long previousLosses = before != null && before.calls() != null ? before.calls().losses() : 0;
                if(current.calls().losses() > previousLosses)
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonObject site(ChannelHealth.Totals a, ChannelHealth.Totals b)
    {
        JsonObject site = new JsonObject();
        site.addProperty("name", name(a.info()));
        site.addProperty("protocol", a.protocol() != null ? a.protocol() : a.info().mode());

        if(!a.identity().isEmpty())
        {
            JsonObject id = new JsonObject();

            for(Map.Entry<String,Integer> entry: a.identity().entrySet())
            {
                id.addProperty(entry.getKey(), entry.getValue());
            }

            site.add("id", id);
        }

        JsonObject cc = new JsonObject();

        //The control channel it's on at the end of the window: none while it hunts for one.
        if(a.frequency() > 0 && a.locked())
        {
            cc.addProperty("hz", a.frequency());
        }

        cc.addProperty("lockedS", r1((a.lockedMs() - (b != null ? b.lockedMs() : 0)) / 1000.0));
        cc.addProperty("good", a.good() - (b != null ? b.good() : 0));
        cc.addProperty("bad", a.bad() - (b != null ? b.bad() : 0));

        if(a.info().symbolRate() != null)
        {
            cc.addProperty("symbolRate", a.info().symbolRate());
        }

        if(a.syncLossSym() != null)
        {
            Long before = b != null ? b.syncLossSym() : null;
            cc.addProperty("syncLossSym", a.syncLossSym() - (before != null ? before : 0));
        }
        site.add("cc", cc);

        CallTally.Totals callsA = a.calls();
        CallTally.Totals callsB = b != null && b.calls() != null ? b.calls() : CallTally.Totals.EMPTY;
        JsonObject calls = new JsonObject();
        calls.addProperty("started", callsA.started() - callsB.started());
        calls.addProperty("recorded", callsA.get(CallOutcome.RECORDED) - callsB.get(CallOutcome.RECORDED));
        JsonObject notRecorded = new JsonObject();

        for(CallOutcome outcome: CallOutcome.values())
        {
            long count = callsA.get(outcome) - callsB.get(outcome);

            if(outcome != CallOutcome.RECORDED && count > 0)
            {
                notRecorded.addProperty(outcome.getReportName(), count);
            }
        }

        if(!notRecorded.isEmpty())
        {
            calls.add("notRecorded", notRecorded);
        }

        site.add("calls", calls);
        return site;
    }

    private static JsonObject channel(ChannelHealth.Totals a, ChannelHealth.Totals b, long windowMs)
    {
        JsonObject channel = new JsonObject();
        channel.addProperty("hz", a.frequency());
        channel.addProperty("mode", a.info().mode());
        channel.addProperty("name", name(a.info()));
        long heardBefore = b != null && b.heardMs() != null ? b.heardMs() : 0;
        channel.addProperty("heardS", r1(Math.max(0, Math.min(windowMs, a.heardMs() - heardBefore)) / 1000.0));
        channel.addProperty("opens", a.opens() - (b != null ? b.opens() : 0));
        channel.addProperty("openS", r1((a.openMs() - (b != null ? b.openMs() : 0)) / 1000.0));
        channel.addProperty("calls", a.closes() - (b != null ? b.closes() : 0));
        return channel;
    }

    private static JsonArray radios(HealthSnapshot base, HealthSnapshot now)
    {
        JsonArray radios = new JsonArray();

        for(HealthSnapshot.RadioTotals a: now.radios())
        {
            HealthSnapshot.RadioTotals b = base != null ? base.radio(a.name()) : null;
            double seconds = a.seconds() - (b != null ? b.seconds() : 0);

            if(seconds <= 0)
            {
                continue;
            }

            JsonObject radio = new JsonObject();
            radio.addProperty("name", a.name());
            radio.addProperty("kind", a.kind());
            radio.addProperty("seconds", r1(seconds));

            if(a.ppmError() != null)
            {
                radio.addProperty("ppmError", a.ppmError());
            }

            radios.add(radio);
        }

        return radios;
    }

    /**
     * The name a report gives a channel: the user's site name when there is one, else the channel's name.
     */
    private static String name(ChannelHealth.ChannelInfo info)
    {
        if(info.site() != null && !info.site().isBlank())
        {
            return info.site();
        }

        return info.name() != null ? info.name() : "";
    }

    /**
     * Unix milliseconds as seconds, written plainly (1794590200.437, not 1.794590200437E9).
     */
    private static BigDecimal seconds(long ms)
    {
        return BigDecimal.valueOf(ms, 3);
    }

    private static double r1(double value)
    {
        return Math.round(value * 10.0) / 10.0;
    }
}
