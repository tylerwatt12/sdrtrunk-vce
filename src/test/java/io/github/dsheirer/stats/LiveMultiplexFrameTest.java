package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LiveMultiplexFrameTest
{
    @Test
    void negotiatedGzipIsLosslessAndSharedWhileLegacyWireStaysUnchanged() throws Exception
    {
        StatsLiveEventHub.LiveEvent event = new StatsLiveEventHub.LiveEvent("activity_delta",
            Map.of("revision", 17, "alias", "Fire Dispatch ".repeat(1_000)));
        LiveMultiplexFrame first = event.frame(1);
        assertSame(first, event.frame(1), "common JSON must be encoded once, not per visitor");
        byte[] plain = first.bytes(false);
        byte[] gzip = first.bytes(true);
        assertSame(gzip, first.bytes(true), "common compression must be prepared once");
        assertSame(plain, first.bytes(false));
        assertEquals(1, Byte.toUnsignedInt(plain[5]));
        assertEquals(0, ByteBuffer.wrap(plain).getInt(12));
        assertEquals(0x81, Byte.toUnsignedInt(gzip[5]));
        assertEquals(plain.length - 16, ByteBuffer.wrap(gzip).getInt(12));
        assertTrue(gzip.length < plain.length / 2);
        try(GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(gzip, 16, gzip.length - 16)))
        {
            assertArrayEquals(Arrays.copyOfRange(plain, 16, plain.length), input.readAllBytes());
        }
    }

    @Test
    void smallFramesKeepPlainFallbackWithoutCodecOverhead() throws Exception
    {
        LiveMultiplexFrame frame = LiveMultiplexFrame.json(1, "activity_delta", Map.of("revision", 1));
        assertSame(frame.bytes(false), frame.bytes(true));
    }

    @Test
    void fullAndDeltaRowsMatchTheAuthoritativePublicSnapshot() throws Exception
    {
        Map<String,Object> row = Map.of("row_key", "row-1", "protocol", "DMR", "source_form", "RADIO",
            "target_form", "TALKGROUP", "source_alias", "Dispatch", "source_identity_summary_id", 23,
            "channel_id", 45, "encrypted", 1);
        Map<String,Object> table = Map.of("table_id", "table-1", "rows", List.of(row));
        JsonNode snapshotRow = StatsApiV1Payload.present(Map.of("tables", List.of(table)))
            .path("tables").get(0).path("rows").get(0);
        ObjectMapper mapper = new ObjectMapper();
        StatsLiveEventHub.LiveEvent full = new StatsLiveEventHub.LiveEvent("activity_table",
            Map.of("table_id", "table-1", "table", table));
        StatsLiveEventHub.LiveEvent delta = new StatsLiveEventHub.LiveEvent("activity_delta",
            Map.of("table_id", "table-1", "rows", List.of(row)), full);

        byte[] fullBytes = full.frame(1).bytes(false);
        byte[] deltaBytes = delta.frame(1).bytes(false);
        JsonNode fullRow = mapper.readTree(fullBytes, LiveMultiplexFrame.HEADER_BYTES,
            fullBytes.length - LiveMultiplexFrame.HEADER_BYTES).path("data").path("table").path("rows").get(0);
        JsonNode deltaRow = mapper.readTree(deltaBytes, LiveMultiplexFrame.HEADER_BYTES,
            deltaBytes.length - LiveMultiplexFrame.HEADER_BYTES).path("data").path("rows").get(0);

        assertEquals(snapshotRow, fullRow);
        assertEquals(snapshotRow, deltaRow);
        assertEquals("radio", deltaRow.path("source_form").asText());
        assertEquals("talkgroup", deltaRow.path("target_form").asText());
        assertEquals("dmr", deltaRow.path("protocol").asText());
        assertFalse(deltaRow.has("source_identity_summary_id"));
        assertFalse(deltaRow.has("channel_id"));
        assertTrue(deltaRow.path("encrypted").asBoolean());
        assertSame(full.frame(1), delta.baseline().frame(1));
    }
}
