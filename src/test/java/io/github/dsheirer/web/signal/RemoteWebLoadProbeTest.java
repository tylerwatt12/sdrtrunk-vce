/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.spectrum.stream.SpectrumFrame;
import io.github.dsheirer.spectrum.stream.SpectrumFrameCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteWebLoadProbeTest
{
    @Test
    void parsesBoundedConfigurationAndDerivesEndpoints()
    {
        RemoteWebLoadProbe.Configuration configuration = RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "https://receiver.example:8090", "--origin", "https://receiver.example:8090",
            "--viewers", "1", "--feed-clients", "10", "--duration-seconds", "900", "--max-fps", "20",
            "--target", "airspy"
        });

        assertEquals(URI.create("https://receiver.example:8090/"), configuration.baseUri());
        assertEquals(URI.create("https://receiver.example:8090/api/status"), configuration.statusUri());
        assertEquals(URI.create("https://receiver.example:8090/live/web-calls"), configuration.feedUri());
        assertEquals(URI.create("https://receiver.example:8090/api/v1/auth/login"), configuration.loginUri());
        assertEquals(URI.create("wss://receiver.example:8090/api/v1/ws/signal"), configuration.webSocketUri());
        assertEquals("https://receiver.example:8090", configuration.origin());
        assertEquals(1, configuration.viewerCount());
        assertEquals(10, configuration.feedClientCount());
        assertEquals(900, configuration.duration().toSeconds());
        assertEquals("AIRSPY", configuration.targetId());
    }

    @Test
    void rejectsCredentialsAndUnboundedLoad()
    {
        assertThrows(IllegalArgumentException.class, () -> RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "https://user:secret@receiver.example:8090"
        }));
        assertThrows(IllegalArgumentException.class, () -> RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "https://receiver.example:8090", "--viewers", "2"
        }));
        assertThrows(IllegalArgumentException.class, () -> RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "https://receiver.example:8090", "--duration-seconds", "901"
        }));
        assertThrows(IllegalArgumentException.class, () -> RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "http://192.0.2.10:8090"
        }));
        assertThrows(IllegalArgumentException.class, () -> RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "https://receiver.example:8090", "--origin", "https://other.example:8090"
        }));

        RemoteWebLoadProbe.Configuration tunnel = RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "http://127.0.0.1:18090"
        });
        assertEquals("http://127.0.0.1:18090", tunnel.origin());
    }

    @Test
    void acceptsNoCredentialCommandLineOptionAndNeverEchoesAnAccidentalSecret()
    {
        for(String option: List.of("--password", "--admin-password", "--password-file", "--credential-file"))
        {
            String accidentalSecret = "do-not-echo-this-secret";
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> RemoteWebLoadProbe.Configuration.parse(new String[]{
                    "--base-url", "https://receiver.example:8090", option, accidentalSecret
                }));
            assertFalse(exception.getMessage().contains(accidentalSecret));
        }

        assertFalse(RemoteWebLoadProbe.Configuration.usage().contains("--password"));
        assertFalse(RemoteWebLoadProbe.Configuration.usage().contains("--credential"));
    }

    @Test
    void buildsLoginJsonWithoutCreatingAPasswordStringAndRedactsSessionDiagnostics() throws Exception
    {
        char[] password = {'s', 'e', 'c', 'r', 'e', 't', '"', '\\', '\n', '\ud83d', '\ude80'};
        byte[] body = RemoteWebLoadProbe.loginRequestBody("admin", password);

        try
        {
            String json = new String(body, StandardCharsets.UTF_8);
            assertEquals("{\"username\":\"admin\",\"password\":\"secret\\\"\\\\\\n\\ud83d\\ude80\"}", json);
        }
        finally
        {
            Arrays.fill(body, (byte)0);
            Arrays.fill(password, '\0');
        }

        String cookieToken = "a".repeat(43);
        String csrfToken = "b".repeat(43);
        String cookie = RemoteWebLoadProbe.parseSessionCookie(List.of(
            "sdrtrunk_admin_session=" + cookieToken + "; Path=/; HttpOnly; SameSite=Strict; Secure"), true);
        RemoteWebLoadProbe.AdminSession session = new RemoteWebLoadProbe.AdminSession(cookie, csrfToken);
        assertEquals("sdrtrunk_admin_session=" + cookieToken, session.cookieHeader());
        assertFalse(session.toString().contains(cookieToken));
        assertFalse(session.toString().contains(csrfToken));
        assertEquals(csrfToken, RemoteWebLoadProbe.parseLoginCsrfToken(
            ("{\"configured\":true,\"authenticated\":true,\"csrfToken\":\"" + csrfToken + "\"}")
                .getBytes(StandardCharsets.UTF_8)));

        String malformedSecret = "never-report-this-cookie-value";
        IOException exception = assertThrows(IOException.class, () -> RemoteWebLoadProbe.parseSessionCookie(List.of(
            "sdrtrunk_admin_session=" + malformedSecret + "; Path=/"), false));
        assertFalse(exception.getMessage().contains(malformedSecret));
    }

    @Test
    void validatesSpectrumHeaderWithoutAllocatingBinsPerFrame()
    {
        SpectrumFrame frame = SpectrumFrame.float32(0, 4, 27, 100, 0, 851_012_500L, 2_400_000L,
            new float[]{-100.0f, -90.0f, -80.0f, -70.0f});
        ByteBuffer encoded = SpectrumFrameCodec.encodeReadOnly(frame);
        RemoteWebLoadProbe.FrameHeader header = RemoteWebLoadProbe.inspectFrame(encoded);

        assertEquals(4, header.targetGeneration());
        assertEquals(27, header.sequence());
        assertEquals(851_012_500L, header.centerFrequencyHz());
        assertEquals(2_400_000L, header.sampleRateHz());
        assertEquals(4, header.binCount());
        assertEquals(SpectrumFrameCodec.HEADER_BYTE_COUNT + 16, header.frameBytes());

        ByteBuffer invalid = ByteBuffer.wrap(SpectrumFrameCodec.encode(frame));
        invalid.putInt(SpectrumFrameCodec.OFFSET_PAYLOAD_BYTE_COUNT, 8);
        assertThrows(IllegalArgumentException.class, () -> RemoteWebLoadProbe.inspectFrame(invalid));
    }

    @Test
    void parsesBoundedSseEventsAndIgnoresHeartbeats() throws Exception
    {
        String stream = ": heartbeat\n\nevent: ready\ndata: {\"state\":\"live\"}\n\n" +
            "event: call\ndata: {\"audio_url\":\"/api/web-player/calls/1/audio\"}\n\n";
        RemoteWebLoadProbe.SseEventReader reader = new RemoteWebLoadProbe.SseEventReader(
            new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)));

        RemoteWebLoadProbe.SseEvent ready = reader.next();
        RemoteWebLoadProbe.SseEvent call = reader.next();
        assertEquals("ready", ready.name());
        assertEquals("{\"state\":\"live\"}", ready.data());
        assertEquals("call", call.name());
        assertEquals("{\"audio_url\":\"/api/web-player/calls/1/audio\"}", call.data());
        assertEquals(null, reader.next());
    }

    @Test
    void requiresNinetyPercentOfRequestedFrameRate()
    {
        assertFalse(result(10, 20, 100, List.of()).passed(),
            "A 20-to-10 FPS regression must fail the probe");
        assertTrue(result(10, 20, 180, List.of()).passed(),
            "The 90% boundary should tolerate normal scheduling jitter");
    }

    @Test
    void requiresAudioSuccessFromEveryFeedClientWhenCallsAreObserved()
    {
        RemoteWebLoadProbe.FeedSnapshot covered = new RemoteWebLoadProbe.FeedSnapshot(3, 1, 1, 0, 2, 512);
        RemoteWebLoadProbe.FeedSnapshot uncovered = new RemoteWebLoadProbe.FeedSnapshot(3, 1, 0, 0, 2, 0);

        assertFalse(result(10, 20, 200, List.of(covered, uncovered)).passed(),
            "Aggregate audio success must not hide an uncovered feed client");
        assertTrue(result(10, 20, 200, List.of(covered, covered)).passed());

        RemoteWebLoadProbe.FeedSnapshot quiet = new RemoteWebLoadProbe.FeedSnapshot(0, 0, 0, 0, 0, 0);
        assertTrue(result(10, 20, 200, List.of(quiet, quiet)).passed(),
            "Audio coverage is not required when the probe observes no calls");
    }

    @Test
    void reportsPerClientAudioCoverage()
    {
        RemoteWebLoadProbe.FeedSnapshot covered = new RemoteWebLoadProbe.FeedSnapshot(3, 1, 1, 0, 2, 512);
        RemoteWebLoadProbe.FeedSnapshot uncovered = new RemoteWebLoadProbe.FeedSnapshot(3, 1, 0, 0, 2, 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;

        try
        {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            result(10, 20, 200, List.of(covered, uncovered)).print();
        }
        finally
        {
            System.setOut(original);
        }

        String result = output.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("audioCoveredClients=1 audioRequiredClients=2"));
        assertTrue(result.contains("audioPerClient=1[events:3,successes:1,failures:0];" +
            "2[events:3,successes:0,failures:0]"));
    }

    private static RemoteWebLoadProbe.Result result(long elapsedSeconds, int framesPerSecond,
                                                     long minimumViewerFrames,
                                                     List<RemoteWebLoadProbe.FeedSnapshot> feedByClient)
    {
        RemoteWebLoadProbe.FeedSnapshot aggregate = new RemoteWebLoadProbe.FeedSnapshot(
            feedByClient.stream().mapToLong(RemoteWebLoadProbe.FeedSnapshot::callEvents).sum(),
            feedByClient.stream().mapToLong(RemoteWebLoadProbe.FeedSnapshot::audioRequests).sum(),
            feedByClient.stream().mapToLong(RemoteWebLoadProbe.FeedSnapshot::audioSuccesses).sum(),
            feedByClient.stream().mapToLong(RemoteWebLoadProbe.FeedSnapshot::audioFailures).sum(),
            feedByClient.stream().mapToLong(RemoteWebLoadProbe.FeedSnapshot::audioSkippedBusy).sum(),
            feedByClient.stream().mapToLong(RemoteWebLoadProbe.FeedSnapshot::audioBytes).sum());
        RemoteWebLoadProbe.ServerSnapshot server = new RemoteWebLoadProbe.ServerSnapshot(
            200, 200, 0, 0, 0, 200, 0, 1_000, 50_000, 1, 0);
        return new RemoteWebLoadProbe.Result(1, feedByClient.size(), framesPerSecond, 1,
            elapsedSeconds * 1_000_000_000L, minimumViewerFrames, minimumViewerFrames, minimumViewerFrames,
            minimumViewerFrames * 128, 0, 0, 0, 0, 50_000_000, aggregate, List.copyOf(feedByClient), server,
            List.of());
    }
}
