/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui.receiver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.debug.ReceiverIncidentController.IncidentReportState;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentState;
import io.github.dsheirer.debug.ReceiverIncidentController.ReceiverIncidentStatus;
import io.github.dsheirer.debug.ReceiverIncidentController.ThreadDumpState;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.ChannelQueueMetrics;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.NativeBufferMetrics;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.QueueMetrics;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the fixed-line receiver queue readout. */
class ReceiverQueuesViewUiContractTest
{
    private static final Path VIEW =
        Path.of("src/main/java/io/github/dsheirer/gui/receiver/ReceiverQueuesView.java");

    @Test
    void dynamicStatesUseFixedRowsAndChannelSlots() throws Exception
    {
        String source = Files.readString(VIEW);
        assertTrue(source.contains("private static final int CHANNEL_OUTPUT_SLOTS = 24"));
        assertTrue(source.contains("slot < CHANNEL_OUTPUT_SLOTS"));
        assertTrue(source.contains("Additional active outputs not shown:"));
        assertTrue(source.contains("metrics.inFlightBuffers() > 0 ?"));
        assertTrue(source.contains("metrics.callbackActive() ?"));
        assertTrue(source.contains(": \"inactive\""));
        assertTrue(source.contains("mReadout.setScrollLeft(scrollLeft)"));
        assertTrue(source.contains("getDiscoveredTunerModel().getDiscoveredTuners()"));
        assertTrue(source.contains("Capture Incident + Threads"));
        assertTrue(source.contains("Copy Latest Incident"));
        assertTrue(source.contains("Thread dump captured"));
    }

    @Test
    void channelStartsStopsAndOverflowKeepTheSameLineCount()
    {
        long now = 10_000_000_000L;
        assertEquals(render(List.of(), now).lines().count(), render(channels(1, now), now).lines().count());
        assertEquals(render(List.of(), now).lines().count(), render(channels(24, now), now).lines().count());
        assertEquals(render(List.of(), now).lines().count(), render(channels(30, now), now).lines().count());
        assertTrue(render(channels(30, now), now).contains("Additional active outputs not shown: 6"));
        assertTrue(render(channels(30, now), now).contains("dropped/discarded 159/318"));
    }

    @Test
    void rawAndIfftStateChangesKeepTheSameLineCount()
    {
        long now = 10_000_000_000L;
        assertEquals(renderRaw(null, now).lines().count(), renderRaw(nativeMetrics(false, now), now).lines().count());
        assertEquals(renderRaw(null, now).lines().count(), renderRaw(nativeMetrics(true, now), now).lines().count());
        assertEquals(renderQueue(null, now).lines().count(), renderQueue(queueMetrics(false, now), now).lines().count());
        assertEquals(renderQueue(null, now).lines().count(), renderQueue(queueMetrics(true, now), now).lines().count());
    }

    @Test
    void incidentSummaryAlwaysUsesThreeStableLines()
    {
        assertEquals(3, renderIncident(null).lines().count());
        assertEquals(3, renderIncident(status(IncidentState.ARMED, ThreadDumpState.NONE, null)).lines().count());
        assertEquals(3, renderIncident(status(IncidentState.RECORDING, ThreadDumpState.SCHEDULED, null)).lines().count());
        assertEquals(3, renderIncident(status(IncidentState.ARMED, ThreadDumpState.CAPTURED, null)).lines().count());
        assertEquals(3, renderIncident(status(IncidentState.ARMED, ThreadDumpState.FAILED,
            "Thread capture was unavailable")).lines().count());
        assertEquals(3, renderIncident(status(IncidentState.ARMED, ThreadDumpState.CAPTURED, null,
            IncidentReportState.CAPTURED_PENDING_SAVE)).lines().count());
        assertEquals(3, renderIncident(status(IncidentState.ARMED, ThreadDumpState.CAPTURED,
            "Unable to save report", IncidentReportState.SAVE_FAILED)).lines().count());
    }

    @Test
    void threadDumpBannerGivesFriendlyPersistentTimestampReasonAndFailure()
    {
        String captured = ReceiverQueuesView.formatIncidentBanner(
            status(IncidentState.ARMED, ThreadDumpState.CAPTURED, null));
        assertTrue(captured.contains("Thread dump captured"));
        assertTrue(captured.contains("automatic: raw IQ queue remained above 75%"));
        assertTrue(captured.contains("1970-01-"));

        String failed = ReceiverQueuesView.formatIncidentBanner(
            status(IncidentState.ARMED, ThreadDumpState.FAILED, "Thread capture was unavailable"));
        assertTrue(failed.contains("Thread dump failed"));
        assertTrue(failed.contains("Thread capture was unavailable"));

        String pending = ReceiverQueuesView.formatIncidentBanner(status(IncidentState.ARMED,
            ThreadDumpState.CAPTURED, null, IncidentReportState.CAPTURED_PENDING_SAVE));
        assertTrue(pending.contains("captured in memory; durable save is pending"));
        String saveFailed = ReceiverQueuesView.formatIncidentBanner(status(IncidentState.ARMED,
            ThreadDumpState.CAPTURED, "Unable to save report", IncidentReportState.SAVE_FAILED));
        assertTrue(saveFailed.contains("Incident report save FAILED"));
    }

    @Test
    void latestIncidentClipboardContainsSavedPathTimelineAndThreadEvidence()
    {
        String json = """
            {"timeline":[{"raw_queue_ms":100}],"thread_dumps":[{"threads":[{"name":"native buffer processor"}]}]}
            """;
        ReceiverIncidentStatus saved = status(IncidentState.ARMED, ThreadDumpState.CAPTURED, null);
        String copied = ReceiverQueuesView.composeLatestIncidentClipboard(saved,
            "Triggered by sustained raw queue pressure",
            json.getBytes(StandardCharsets.UTF_8));

        assertTrue(copied.contains("RECEIVER INCIDENT TROUBLESHOOTING REPORT"));
        assertTrue(copied.contains("diagnostics/receiver-incidents/receiver-incident.json"));
        assertTrue(copied.contains("Triggered by sustained raw queue pressure"));
        assertTrue(copied.contains("raw_queue_ms"));
        assertTrue(copied.contains("native buffer processor"));
        assertEquals("", ReceiverQueuesView.composeLatestIncidentClipboard(saved, "summary", new byte[0]));
        assertEquals("", ReceiverQueuesView.composeLatestIncidentClipboard(saved, "summary",
            "   \n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", ReceiverQueuesView.composeLatestIncidentClipboard(statusWithoutSavedIncident(), "summary",
            "{\"incident\":null}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", ReceiverQueuesView.composeLatestIncidentClipboard(status(IncidentState.ARMED,
            ThreadDumpState.CAPTURED, null, IncidentReportState.CAPTURED_PENDING_SAVE), "summary",
            json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String renderIncident(ReceiverIncidentStatus status)
    {
        StringBuilder sb = new StringBuilder();
        ReceiverQueuesView.renderIncidentSummary(sb, status);
        return sb.toString();
    }

    private static ReceiverIncidentStatus status(IncidentState incidentState, ThreadDumpState threadDumpState,
                                                  String error)
    {
        return status(incidentState, threadDumpState, error, IncidentReportState.SAVED);
    }

    private static ReceiverIncidentStatus status(IncidentState incidentState, ThreadDumpState threadDumpState,
                                                  String error, IncidentReportState reportState)
    {
        boolean saved = reportState == IncidentReportState.SAVED;
        return new ReceiverIncidentStatus(incidentState, "summary", 123, 900,
            incidentState == IncidentState.RECORDING ? "manual capture" : null,
            incidentState == IncidentState.RECORDING ? 500L : 0L, 120_500L, 1, threadDumpState,
            threadDumpState == ThreadDumpState.NONE ? 0L : 172_800_000L,
            "automatic: raw IQ queue remained above 75%", 37L, saved ? 1 : 0, saved ? 2_000L : 0L,
            saved ? "automatic receiver queue incident" : null, saved ? "receiver-incident.json" : null, error,
            reportState);
    }

    private static ReceiverIncidentStatus statusWithoutSavedIncident()
    {
        return new ReceiverIncidentStatus(IncidentState.ARMED, "armed", 123, 900, null, 0L, 0L, 0,
            ThreadDumpState.NONE, 0L, null, 0L, 0, 0L, null, null, null, IncidentReportState.NONE);
    }

    private static String render(List<ChannelQueueMetrics> channels, long now)
    {
        StringBuilder sb = new StringBuilder();
        ReceiverQueuesView.renderChannelOutputs(sb, channels, now);
        return sb.toString();
    }

    private static List<ChannelQueueMetrics> channels(int count, long now)
    {
        List<ChannelQueueMetrics> channels = new ArrayList<>();

        for(int x = 0; x < count; x++)
        {
            QueueMetrics metrics = new QueueMetrics("output-" + x, 8, x % 3, x % 2, x % 2 == 1,
                100 + x, 100 + x, 99 + x, x * 2L, x, 4, now - 1_000_000L, now - 2_000_000L,
                now - 500_000L, now, true);
            channels.add(new ChannelQueueMetrics(770_000_000L + x * 12_500L, 50_000.0, metrics));
        }

        return channels;
    }

    private static String renderRaw(NativeBufferMetrics metrics, long now)
    {
        StringBuilder sb = new StringBuilder();
        ReceiverQueuesView.renderRawInput(sb, metrics, now);
        return sb.toString();
    }

    private static String renderQueue(QueueMetrics metrics, long now)
    {
        StringBuilder sb = new StringBuilder();
        ReceiverQueuesView.renderQueue(sb, "IFFT (tuner-wide)", metrics, now, 50_000.0);
        return sb.toString();
    }

    private static NativeBufferMetrics nativeMetrics(boolean active, long now)
    {
        return new NativeBufferMetrics("raw", 10_000_000.0, 100, 1_000_000, 1, 65_536, 7,
            active ? 1 : 0, active ? 65_536 : 0, active ? 7 : 0, 4, 262_144, 27,
            100, 6_553_600, 655, 99, 6_488_064, 648, 1, 65_536, 7, 0, 0, 0,
            now - 1_000_000L, now - 2_000_000L, active ? now - 500_000L : 0, true, false);
    }

    private static QueueMetrics queueMetrics(boolean active, long now)
    {
        return new QueueMetrics("ifft", 8, 1, active ? 1 : 0, active, 100, 100, 99,
            0, 0, 4, now - 1_000_000L, now - 2_000_000L, active ? now - 500_000L : 0, now, true);
    }
}
