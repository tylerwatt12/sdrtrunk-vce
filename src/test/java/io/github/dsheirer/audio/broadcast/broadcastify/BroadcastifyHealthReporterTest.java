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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.health.ChannelHealth;
import io.github.dsheirer.health.HealthHistory;
import io.github.dsheirer.health.HealthSnapshot;
import io.github.dsheirer.health.NodeReporter;
import io.github.dsheirer.protocol.Protocol;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BroadcastifyHealthReporterTest
{
    private static final long SINCE = 1_794_590_000_000L;
    /** This node's Broadcastify Calls configuration, and another's. */
    private static final String STREAM = "6f1d0c52-0000-4000-8000-000000000001";
    private static final String OTHER_STREAM = "6f1d0c52-0000-4000-8000-000000000002";
    /** Saved channels: a site on the Downtown alias list, one on the County list. */
    private static final String DOWNTOWN_CHANNEL = "6f1d0c52-0000-4000-8000-000000009001";
    private static final String NORTH_CHANNEL = "6f1d0c52-0000-4000-8000-000000009002";
    private static final long DOWNTOWN = 1;
    private static final long COUNTY = 2;

    private HttpServer mServer;
    private final List<JsonObject> mReceived = new ArrayList<>();
    private final AtomicInteger mStatus = new AtomicInteger(200);
    private final AtomicReference<String> mAnswer = new AtomicReference<>("{\"ok\":true,\"nextS\":300}");
    private final AtomicReference<String> mRetryAfter = new AtomicReference<>();

    @BeforeEach
    void serve() throws Exception
    {
        mServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mServer.createContext("/call-health", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized(mReceived)
            {
                mReceived.add(JsonParser.parseString(body).getAsJsonObject());
            }
            if(mRetryAfter.get() != null)
            {
                exchange.getResponseHeaders().add("Retry-After", mRetryAfter.get());
            }
            byte[] answer = mAnswer.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(mStatus.get(), answer.length);
            try(OutputStream out = exchange.getResponseBody())
            {
                out.write(answer);
            }
        });
        mServer.start();
    }

    @AfterEach
    void stopServing()
    {
        mServer.stop(0);
    }

    private BroadcastifyCallConfiguration configuration()
    {
        return configure(new BroadcastifyCallConfiguration());
    }

    private <T extends BroadcastifyCallConfiguration> T configure(T configuration)
    {
        configuration.setConfigurationId(STREAM);
        configuration.setName("Downtown Calls");
        configuration.setSystemID(1234);
        configuration.setApiKey("secret");
        configuration.setHost("http://127.0.0.1:" + mServer.getAddress().getPort() + "/call-upload");
        return configuration;
    }

    /**
     * Two aliases lists: one with an alias streaming to the node, one with an alias streaming elsewhere.
     */
    private static AliasModel aliases()
    {
        AliasListDefinition downtown = new AliasListDefinition("Downtown", AliasListFamily.P25);
        downtown.setId(DOWNTOWN);
        AliasListDefinition county = new AliasListDefinition("County", AliasListFamily.P25);
        county.setId(COUNTY);
        Alias streams = new Alias("Fire Dispatch");
        streams.setId(10);
        streams.setAliasListDefinition(downtown);
        streams.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        streams.addBroadcastChannel(new BroadcastChannel(STREAM, "Downtown Calls"));
        Alias elsewhere = new Alias("Water");
        elsewhere.setId(20);
        elsewhere.setAliasListDefinition(county);
        elsewhere.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 200));
        elsewhere.addBroadcastChannel(new BroadcastChannel(OTHER_STREAM, "County Calls"));
        AliasModel model = new AliasModel();
        model.replaceCommittedConfiguration(List.of(downtown, county), List.of(streams, elsewhere));
        return model;
    }

    /**
     * A site on the node's alias list and one on another, every 10 s for 20 minutes.
     */
    private static HealthHistory history()
    {
        return history(SINCE + 1_200_000);
    }

    /**
     * The same, every 10 s up to a time.
     */
    private static HealthHistory history(long untilMs)
    {
        ChannelHealth node = new ChannelHealth(new ChannelHealth.ChannelInfo(9001, DOWNTOWN_CHANNEL, DOWNTOWN, "CC 1",
            "Metro", "Downtown", "Downtown", "p25", 4800));
        ChannelHealth other = new ChannelHealth(new ChannelHealth.ChannelInfo(9002, NORTH_CHANNEL, COUNTY, "CC 2",
            "County", "North", "County", "p25", 4800));
        node.started(SINCE);
        other.started(SINCE);
        node.setCallsCounted();
        other.setCallsCounted();
        HealthHistory history = new HealthHistory();

        for(long t = SINCE; t <= untilMs; t += 10_000)
        {
            node.control("p25", true, t);
            other.control("p25", true, t);
            history.add(new HealthSnapshot(t, SINCE, List.of(node.totals(t), other.totals(t)), List.of()));
        }

        return history;
    }

    /**
     * A node's schedule, its reports started when recording did.
     */
    private static NodeReporter schedule(long seed)
    {
        NodeReporter schedule = new NodeReporter(seed);
        schedule.begin(new HealthSnapshot(SINCE, SINCE, List.of(), List.of()));
        return schedule;
    }

    private BroadcastifyHealthReporter reporter(NodeReporter schedule)
    {
        return reporter(configuration(), schedule);
    }

    private BroadcastifyHealthReporter reporter(BroadcastifyCallConfiguration configuration, NodeReporter schedule)
    {
        BroadcastifyHealthReporter node = new BroadcastifyHealthReporter(
            BroadcastifyCallConfiguration.getHealthUrl(configuration.getHost()), configuration.getSystemID(),
            HttpClient.newHttpClient(), schedule);
        node.add(configuration, aliases());
        return node;
    }

    /**
     * Another destination uploading as the same system: the County alias list's.
     */
    private BroadcastifyCallConfiguration countyCalls()
    {
        BroadcastifyCallConfiguration county = configure(new BroadcastifyCallConfiguration());
        county.setConfigurationId(OTHER_STREAM);
        county.setName("County Calls");
        return county;
    }

    @Test
    void the_health_url_is_beside_the_upload_server()
    {
        assertEquals("https://api.broadcastify.com/call-health",
            BroadcastifyCallConfiguration.getHealthUrl("https://api.broadcastify.com/call-upload"));
        assertEquals("http://10.0.0.5:8080/call-health",
            BroadcastifyCallConfiguration.getHealthUrl("http://10.0.0.5:8080/call-upload/"));
        assertNull(BroadcastifyCallConfiguration.getHealthUrl("https://api.broadcastify.com/call-upload-dev"));
        assertNull(BroadcastifyCallConfiguration.getHealthUrl(null));
    }

    @Test
    void health_reports_are_on_by_default()
    {
        assertTrue(new BroadcastifyCallConfiguration().isHealthReports());
        assertTrue(((BroadcastifyCallConfiguration)configuration().copyOf()).isHealthReports());
    }

    @Test
    void a_node_reports_the_sites_whose_calls_can_stream_to_it()
    {
        BroadcastifyHealthReporter reporter = reporter(schedule(1));
        HealthHistory history = history();
        reporter.check(history);

        assertEquals(1, mReceived.size());
        JsonObject report = mReceived.getFirst();
        assertEquals(1234, report.getAsJsonObject("node").get("systemId").getAsInt());
        assertEquals("secret", report.getAsJsonObject("node").get("apiKey").getAsString());
        assertEquals(1, report.getAsJsonArray("sites").size(), "the node's site, not the other alias list's");
        assertEquals("Downtown", report.getAsJsonArray("sites").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(0, report.getAsJsonObject("window").get("seq").getAsInt());
        assertTrue(report.getAsJsonObject("window").has("gap"), "20 minutes since the start: the longest window");
    }

    @Test
    void a_trunked_site_node_reports_its_one_site()
    {
        BroadcastifyCallSiteConfiguration site = configure(new BroadcastifyCallSiteConfiguration());
        site.setAliasListId(COUNTY);
        site.setChannelConfigurationId(NORTH_CHANNEL);
        reporter(site, schedule(12)).check(history());

        assertEquals(1, mReceived.size());
        JsonObject report = mReceived.getFirst();
        assertEquals("sdrtrunk-vce", report.getAsJsonObject("recorder").get("name").getAsString());
        assertEquals(1, report.getAsJsonArray("sites").size(), "its saved channel, whatever its aliases stream to");
        assertEquals("North", report.getAsJsonArray("sites").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void a_trunked_site_node_whose_alias_list_is_gone_reports_nothing()
    {
        BroadcastifyCallSiteConfiguration site = configure(new BroadcastifyCallSiteConfiguration());
        site.setAliasListId(99);
        site.setChannelConfigurationId(NORTH_CHANNEL);
        reporter(site, schedule(13)).check(history());
        assertTrue(mReceived.isEmpty());
    }

    @Test
    void health_reports_can_be_turned_off_and_copies_keep_the_choice()
    {
        BroadcastifyCallSiteConfiguration site = new BroadcastifyCallSiteConfiguration();
        site.setHealthReports(false);
        assertFalse(((BroadcastifyCallConfiguration)site.copyOf()).isHealthReports());
    }

    @Test
    void a_delivered_report_is_followed_by_the_next_window()
    {
        NodeReporter schedule = schedule(2);
        BroadcastifyHealthReporter reporter = reporter(schedule);
        HealthHistory history = history();
        reporter.check(history);
        assertEquals(1, mReceived.size());
        assertTrue(schedule.getNextAt() > history.latest().timeMs(), "not due again until the next interval");

        reporter.check(history);
        assertEquals(1, mReceived.size(), "nothing more until it's due");
    }

    @Test
    void a_server_that_doesnt_take_reports_is_tried_less_often_not_given_up_on()
    {
        for(int status: new int[]{404, 405, 410})
        {
            mReceived.clear();
            mStatus.set(status);
            mAnswer.set("<html>Not Found</html>");
            NodeReporter schedule = schedule(3);
            BroadcastifyHealthReporter reporter = reporter(schedule);
            HealthHistory history = history();
            reporter.check(history);
            assertFalse(schedule.isStopped(), "HTTP " + status);
            long first = schedule.getNextAt() - history.latest().timeMs();
            assertTrue(first > 0);

            reporter.check(history);
            assertEquals(1, mReceived.size(), "not tried again until it's due");

            //Due again: still not taken, and it waits longer.
            HealthHistory later = history(schedule.getNextAt() + 10_000);
            reporter.check(later);
            assertEquals(2, mReceived.size());
            long second = schedule.getNextAt() - later.latest().timeMs();
            assertTrue(second > first, "backs off further: " + first + " then " + second);
            assertTrue(second <= NodeReporter.MAX_INTERVAL_MS + NodeReporter.JITTER_MS + 1000, "up to hourly");

            //Taken at last: delivered.
            mStatus.set(200);
            mAnswer.set("{\"ok\":true}");
            HealthHistory taken = history(schedule.getNextAt() + 10_000);
            reporter.check(taken);
            assertEquals(3, mReceived.size());
            HealthHistory next = history(taken.latest().timeMs() + 10_000);
            assertEquals(1, schedule.window(next).seq(), "the next report follows a delivered one");
        }
    }

    @Test
    void a_page_that_isnt_a_report_answer_is_backed_off_from()
    {
        mAnswer.set("<html>ok</html>");
        NodeReporter schedule = schedule(4);
        HealthHistory history = history();
        reporter(schedule).check(history);
        assertFalse(schedule.isStopped());
        assertTrue(schedule.getNextAt() > history.latest().timeMs());
        assertEquals(0, schedule.window(history).seq(), "not delivered");
    }

    @Test
    void a_report_turned_down_as_wrong_counts_as_sent()
    {
        mStatus.set(400);
        mAnswer.set("{\"error\":\"window.end must be after window.start\"}");
        NodeReporter schedule = schedule(14);
        HealthHistory history = history();
        reporter(schedule).check(history);
        assertFalse(schedule.isStopped());
        NodeReporter.Window next = schedule.window(history(history.latest().timeMs() + 10_000));
        assertEquals(1, next.seq());
        assertEquals(history.latest().timeMs(), next.startMs(), "not sent again");
    }

    @Test
    void any_other_answer_is_not_delivered_and_carried_by_the_next()
    {
        for(int status: new int[]{408, 413, 422, 500, 502})
        {
            mStatus.set(status);
            mAnswer.set("{\"error\":\"something\"}");
            NodeReporter schedule = schedule(15);
            HealthHistory history = history();
            reporter(schedule).check(history);
            assertFalse(schedule.isStopped(), "HTTP " + status);
            assertEquals(0, schedule.window(history).seq(), "HTTP " + status + " isn't counted as sent");
        }
    }

    @Test
    void retry_after_is_seconds_or_an_http_date()
    {
        long now = 1_445_412_480_000L; //Wed, 21 Oct 2015 07:28:00 GMT
        assertEquals(120.0, BroadcastifyHealthReporter.retryAfterSeconds("120", now));
        assertEquals(90.0, BroadcastifyHealthReporter.retryAfterSeconds("Wed, 21 Oct 2015 07:29:30 GMT", now));
        assertEquals(0.0, BroadcastifyHealthReporter.retryAfterSeconds("Wed, 21 Oct 2015 07:00:00 GMT", now),
            "a date gone by: now");
        assertNull(BroadcastifyHealthReporter.retryAfterSeconds("soon", now));
        assertNull(BroadcastifyHealthReporter.retryAfterSeconds(" ", now));
    }

    @Test
    void refused_credentials_stop_the_node()
    {
        mStatus.set(401);
        mAnswer.set("{\"error\":\"Invalid-API-Key\"}");
        NodeReporter schedule = schedule(5);
        reporter(schedule).check(history());
        assertTrue(schedule.isStopped());
    }

    @Test
    void a_paused_service_is_tried_again_after_retry_after()
    {
        mStatus.set(503);
        mRetryAfter.set("3600");
        mAnswer.set("{\"error\":\"not taking node health reports now\"}");
        NodeReporter schedule = schedule(6);
        HealthHistory history = history();
        BroadcastifyHealthReporter reporter = reporter(schedule);
        reporter.check(history);
        assertFalse(schedule.isStopped());
        assertEquals(history.latest().timeMs() + 3_600_000, schedule.getNextAt());
    }

    @Test
    void destinations_uploading_as_one_system_send_one_report_with_all_their_sites()
    {
        BroadcastifyHealthReporter node = reporter(schedule(20));
        BroadcastifyCallConfiguration county = countyCalls();
        county.setApiKey("another");
        node.add(county, aliases());
        node.check(history());

        assertEquals(1, mReceived.size(), "one report for the system, not one per destination");
        JsonObject report = mReceived.getFirst();
        assertEquals(1234, report.getAsJsonObject("node").get("systemId").getAsInt());
        assertEquals("secret", report.getAsJsonObject("node").get("apiKey").getAsString(), "the first one's key");
        List<String> sites = new ArrayList<>();
        report.getAsJsonArray("sites").forEach(site -> sites.add(site.getAsJsonObject().get("name").getAsString()));
        assertEquals(List.of("Downtown", "North"), sites.stream().sorted().toList());
    }

    @Test
    void a_trunked_site_destination_and_another_on_one_system_send_one_report()
    {
        BroadcastifyCallSiteConfiguration site = configure(new BroadcastifyCallSiteConfiguration());
        site.setConfigurationId("6f1d0c52-0000-4000-8000-000000000003");
        site.setAliasListId(COUNTY);
        site.setChannelConfigurationId(NORTH_CHANNEL);
        BroadcastifyHealthReporter node = reporter(schedule(21));
        node.add(site, aliases());
        node.check(history());

        assertEquals(1, mReceived.size());
        assertEquals(2, mReceived.getFirst().getAsJsonArray("sites").size());
    }

    @Test
    void a_node_reports_without_a_destination_that_left_and_keeps_its_schedule()
    {
        BroadcastifyHealthReporter node = reporter(schedule(22));
        BroadcastifyCallConfiguration county = countyCalls();
        node.add(county, aliases());
        NodeReporter schedule = node.getSchedule();
        assertFalse(node.remove(configuration()), "the County destination still uploads as the system");
        node.check(history());

        assertEquals(1, mReceived.size());
        assertEquals(1, mReceived.getFirst().getAsJsonArray("sites").size());
        assertEquals("North", mReceived.getFirst().getAsJsonArray("sites").get(0).getAsJsonObject().get("name")
            .getAsString());
        assertSame(schedule, node.getSchedule());
        assertTrue(node.remove(county), "none left");
    }

    @Test
    void destinations_share_their_systems_reporter_while_any_of_them_runs()
    {
        BroadcastifyCallConfiguration downtown = configuration();
        BroadcastifyCallConfiguration county = countyCalls();
        BroadcastifyCallConfiguration elsewhere = configuration();
        elsewhere.setConfigurationId("6f1d0c52-0000-4000-8000-000000000004");
        elsewhere.setSystemID(5678);
        BroadcastifyHealthReporter first = BroadcastifyHealthReporter.join(downtown, aliases());
        BroadcastifyHealthReporter second = BroadcastifyHealthReporter.join(county, aliases());
        BroadcastifyHealthReporter other = BroadcastifyHealthReporter.join(elsewhere, aliases());

        try
        {
            assertSame(first, second, "one system: one reporter");
            assertNotSame(first, other, "another system: its own");
            NodeReporter schedule = first.getSchedule();

            //The Downtown destination restarts while the County one goes on: the same reporter and schedule.
            first.leave(downtown);
            assertSame(first, BroadcastifyHealthReporter.join(downtown, aliases()));
            assertSame(schedule, first.getSchedule(), "the same phase and seq");

            //Both stop: the next start is a new reporter.
            first.leave(downtown);
            first.leave(county);
            BroadcastifyHealthReporter again = BroadcastifyHealthReporter.join(downtown, aliases());
            assertNotSame(first, again);
            again.leave(downtown);
        }
        finally
        {
            first.leave(downtown);
            first.leave(county);
            other.leave(elsewhere);
        }
    }

    @Test
    void a_destination_whose_server_takes_no_reports_joins_no_node()
    {
        BroadcastifyCallConfiguration configuration = configuration();
        configuration.setHost("https://api.broadcastify.com/call-upload-dev");
        assertNull(BroadcastifyHealthReporter.join(configuration, aliases()));
    }

    @Test
    void refused_credentials_start_over_when_a_destination_starts_again()
    {
        mStatus.set(403);
        mAnswer.set("{\"error\":\"Invalid-API-Key\"}");
        BroadcastifyHealthReporter node = reporter(schedule(23));
        node.check(history());
        assertTrue(node.getSchedule().isStopped());

        node.remove(configuration());
        node.add(configuration(), aliases());
        assertFalse(node.getSchedule().isStopped(), "its settings may have changed");
    }

    @Test
    void a_system_id_uploading_several_sites_is_warned_of_and_uploads_carry_on()
    {
        BroadcastifyHealthReporter node = reporter(schedule(24));
        HealthHistory history = history();
        node.checkSites(history, history.latest());
        assertNull(node.getWarning(), "one site");

        node.add(countyCalls(), aliases());
        node.checkSites(history, history.latest());
        assertEquals("Broadcastify system 1234 is uploading 2 sites: Downtown, North. Broadcastify Calls needs a " +
            "system ID for each site you upload, or calls from the other sites are labeled as the wrong site. Give " +
            "each site its own Broadcastify system ID. If you need more system IDs, apply at " +
            "https://www.broadcastify.com/apply/calls (you can use your existing API key).", node.getWarning());

        node.check(history);
        assertEquals(1, mReceived.size(), "still reported");
        assertEquals(2, mReceived.getFirst().getAsJsonArray("sites").size());

        node.remove(countyCalls());
        node.checkSites(history, history.latest());
        assertNull(node.getWarning(), "one site again");
    }
}
