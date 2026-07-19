/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.spectrum.stream.SpectrumFrame;
import io.github.dsheirer.spectrum.stream.SpectrumFrameCodec;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RemoteWebLoadProbeTest
{
    @Test
    void parsesBoundedConfigurationAndDerivesEndpoints()
    {
        RemoteWebLoadProbe.Configuration configuration = RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "http://192.0.2.10:8090", "--origin", "http://console.example:8090",
            "--viewers", "10", "--feed-clients", "10", "--duration-seconds", "900", "--max-fps", "20"
        });

        assertEquals(URI.create("http://192.0.2.10:8090/"), configuration.baseUri());
        assertEquals(URI.create("http://192.0.2.10:8090/api/status"), configuration.statusUri());
        assertEquals(URI.create("http://192.0.2.10:8090/live/web-calls"), configuration.feedUri());
        assertEquals(URI.create("ws://192.0.2.10:8090/api/v1/ws/signal"), configuration.webSocketUri());
        assertEquals("http://console.example:8090", configuration.origin());
        assertEquals(10, configuration.viewerCount());
        assertEquals(10, configuration.feedClientCount());
        assertEquals(900, configuration.duration().toSeconds());
    }

    @Test
    void rejectsCredentialsAndUnboundedLoad()
    {
        assertThrows(IllegalArgumentException.class, () -> RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "http://user:secret@192.0.2.10:8090"
        }));
        assertThrows(IllegalArgumentException.class, () -> RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "http://192.0.2.10:8090", "--viewers", "11"
        }));
        assertThrows(IllegalArgumentException.class, () -> RemoteWebLoadProbe.Configuration.parse(new String[]{
            "--base-url", "http://192.0.2.10:8090", "--duration-seconds", "901"
        }));
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
}
