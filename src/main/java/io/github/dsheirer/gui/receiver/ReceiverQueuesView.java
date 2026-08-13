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
package io.github.dsheirer.gui.receiver;

import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.ChannelQueueMetrics;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.NativeBufferMetrics;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.QueueMetrics;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueProfile;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;

/**
 * Detailed, read-only receiver queue view.  This view samples lock-free counter snapshots once per second while it is
 * visible.  It never registers an IQ, FFT, symbol, or decoder listener and never examines queued buffer contents.
 */
public class ReceiverQueuesView extends BorderPane
{
    private static final String SOURCE_BASELINE = "v0.6.2-alpha-9 (d89a2da8f)";
    private static final DateTimeFormatter LOCAL_TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter UTC_TIMESTAMP = DateTimeFormatter.ISO_INSTANT;
    private static final int CHANNELIZER_BATCH_SAMPLE_COUNT = 1024;
    private static final int CHANNEL_OUTPUT_SLOTS = 24;

    private final TunerManager mTunerManager;
    private final TextArea mReadout = new TextArea();
    private final Label mCopyStatus = new Label();
    private final Timeline mTimeline;
    private String mCurrentReadout = "";
    private String mCurrentLocalTimestamp = "";

    public ReceiverQueuesView(TunerManager tunerManager)
    {
        mTunerManager = tunerManager;

        Button copyButton = new Button("Copy Snapshot");
        copyButton.setOnAction(event -> copySnapshot());

        HBox controls = new HBox(10, copyButton, mCopyStatus);
        controls.setPadding(new Insets(8));

        mReadout.setEditable(false);
        mReadout.setWrapText(false);
        mReadout.setStyle("-fx-font-family: monospace;");

        setTop(controls);
        setCenter(mReadout);
        BorderPane.setMargin(mReadout, new Insets(0, 8, 8, 8));
        HBox.setHgrow(mCopyStatus, Priority.ALWAYS);

        mTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh()));
        mTimeline.setCycleCount(Timeline.INDEFINITE);
    }

    /**
     * Starts sampling.  Intended to be called whenever the owning stage is shown.
     */
    public void start()
    {
        refresh();
        mTimeline.play();
    }

    /**
     * Stops sampling.  No receiver polling occurs while the owning stage is hidden.
     */
    public void stop()
    {
        mTimeline.stop();
    }

    private void copySnapshot()
    {
        ClipboardContent content = new ClipboardContent();
        content.putString(mCurrentReadout);
        Clipboard.getSystemClipboard().setContent(content);
        mCopyStatus.setText("Copied snapshot captured " + mCurrentLocalTimestamp);
    }

    private void refresh()
    {
        Instant captured = Instant.now();
        mCurrentLocalTimestamp = LOCAL_TIMESTAMP.format(captured.atZone(ZoneId.systemDefault()));
        mCurrentReadout = render(captured, System.nanoTime());
        double scrollTop = mReadout.getScrollTop();
        double scrollLeft = mReadout.getScrollLeft();
        int anchor = mReadout.getAnchor();
        int caret = mReadout.getCaretPosition();
        boolean firstRender = mReadout.getText().isEmpty();
        mReadout.setText(mCurrentReadout);

        if(firstRender)
        {
            mReadout.positionCaret(0);
        }
        else
        {
            int length = mCurrentReadout.length();
            mReadout.selectRange(Math.min(anchor, length), Math.min(caret, length));
            mReadout.setScrollTop(scrollTop);
            mReadout.setScrollLeft(scrollLeft);
        }

        mCopyStatus.setText("Live readout captured " + mCurrentLocalTimestamp);
    }

    private String render(Instant captured, long capturedNanos)
    {
        StringBuilder sb = new StringBuilder(8192);
        ReceiverQueueProfile profile = ReceiverQueueProfile.getActive();
        Runtime runtime = Runtime.getRuntime();

        sb.append("RECEIVER QUEUE SNAPSHOT\n");
        sb.append("Captured local : ").append(mCurrentLocalTimestamp).append('\n');
        sb.append("Captured UTC   : ").append(UTC_TIMESTAMP.format(captured.atOffset(ZoneOffset.UTC))).append('\n');
        sb.append("Application    : ").append(valueOrUnavailable(ApplicationInfo.getDisplayName())).append('\n');
        sb.append("Build time     : ").append(valueOrUnavailable(ApplicationInfo.getBuildTimestamp())).append('\n');
        sb.append("Source baseline: ").append(SOURCE_BASELINE).append('\n');
        sb.append("Queue profile  : ").append(profile.getDisplayName()).append(" (-D")
            .append(ReceiverQueueProfile.PROPERTY_NAME).append('=').append(profile.getPropertyValue()).append(")\n");
        sb.append("Process uptime : ").append(formatDuration(ManagementFactory.getRuntimeMXBean().getUptime())).append('\n');
        sb.append("Java heap      : ").append(formatBytes(runtime.totalMemory() - runtime.freeMemory())).append(" used / ")
            .append(formatBytes(runtime.totalMemory())).append(" committed / ")
            .append(formatBytes(runtime.maxMemory())).append(" maximum\n");

        if(profile.isRetainAll())
        {
            sb.append("WARNING        : retain-all queues are unbounded and can exhaust the Java heap.\n");
        }

        sb.append('\n');
        sb.append("Measurement method\n");
        sb.append("  Lock-free primitive counters are sampled once per second only while this window is open.\n");
        sb.append("  No IQ/FFT/symbol listener is attached, no buffer is inspected, and no receiver lock is acquired.\n");
        sb.append("  Values are a best-effort instant and can change independently while the snapshot is assembled.\n");
        sb.append("  Waiting = still in the queue. In-flight = drained from the queue but still held/being processed.\n");
        sb.append("  Dropped = queue-overflow loss. Discarded also includes shutdown/stopped-race cleanup.\n");
        sb.append("  Each tuner uses 24 fixed channel rows; adding or removing a tuner changes the total line count.\n");

        List<DiscoveredTuner> tuners = mTunerManager.getDiscoveredTunerModel().getDiscoveredTuners();

        if(tuners.isEmpty())
        {
            sb.append("\nNo tuner has been discovered.\n");
            return sb.toString();
        }

        int tunerNumber = 0;

        for(DiscoveredTuner discoveredTuner: tuners)
        {
            tunerNumber++;
            renderTuner(sb, tunerNumber, discoveredTuner, capturedNanos);
        }

        return sb.toString();
    }

    private void renderTuner(StringBuilder sb, int tunerNumber, DiscoveredTuner discoveredTuner, long capturedNanos)
    {
        sb.append("\n======================================================================\n");
        sb.append("TUNER ").append(tunerNumber).append(": ").append(discoveredTuner.getId()).append('\n');
        sb.append("Status         : ").append(discoveredTuner.getTunerStatus()).append('\n');

        String centerAndSample = "unavailable during tuner lifecycle change";
        String metricsStatus = "unavailable during tuner lifecycle change";
        ReceiverQueueMetricsSnapshot snapshot = null;

        try
        {
            Tuner tuner = discoveredTuner.getTuner();

            if(tuner != null)
            {
                centerAndSample = formatFrequency(tuner.getTunerController().getFrequency()) + " / " +
                    formatRate(tuner.getTunerController().getSampleRate());
                ChannelSourceManager sourceManager = tuner.getChannelSourceManager();

                if(sourceManager instanceof PolyphaseChannelSourceManager polyphaseManager)
                {
                    snapshot = polyphaseManager.getQueueMetricsSnapshot();
                    metricsStatus = snapshot.rawInput() == null && snapshot.ifft() == null ?
                        "unavailable during tuner lifecycle change" : "available";
                }
                else
                {
                    metricsStatus = "not a live polyphase tuner source";
                }
            }
            else
            {
                metricsStatus = "tuner instance is unavailable during a lifecycle change";
            }
        }
        catch(RuntimeException e)
        {
            metricsStatus = "unavailable during tuner lifecycle change (" + e.getClass().getSimpleName() + ")";
        }

        sb.append("Center/sample  : ").append(centerAndSample).append('\n');
        sb.append("Metrics        : ").append(metricsStatus).append('\n');

        NativeBufferMetrics rawInput = snapshot != null ? snapshot.rawInput() : null;
        QueueMetrics ifft = snapshot != null ? snapshot.ifft() : null;
        List<ChannelQueueMetrics> channels = snapshot != null ? snapshot.channels() : List.of();
        double channelizerOutputRate = channels.isEmpty() ? Double.NaN : channels.getFirst().sampleRate();
        renderRawInput(sb, rawInput, capturedNanos);
        renderQueue(sb, "IFFT (tuner-wide)", ifft, capturedNanos, channelizerOutputRate);
        renderChannelOutputs(sb, channels, capturedNanos);
    }

    static void renderRawInput(StringBuilder sb, NativeBufferMetrics metrics, long capturedNanos)
    {
        sb.append("\n  RAW TUNER IQ (before channelizer)\n");

        if(metrics == null)
        {
            appendUnavailableRawInput(sb);
            return;
        }

        sb.append("    State       : running=").append(metrics.running()).append(", disposed=")
            .append(metrics.disposed()).append('\n');
        sb.append("    Queue limit : ").append(metrics.unbounded() ? "unbounded" :
            metrics.configuredLimitMilliseconds() + " ms / " + formatCount(metrics.configuredLimitSamples()) + " samples")
            .append('\n');
        sb.append("    Waiting     : ").append(metrics.waitingBuffers()).append(" buffers / ")
            .append(formatCount(metrics.waitingSamples())).append(" samples / ")
            .append(metrics.waitingMilliseconds()).append(" ms\n");
        sb.append("    In-flight   : ").append(metrics.inFlightBuffers()).append(" buffers / ")
            .append(formatCount(metrics.inFlightSamples())).append(" samples / ")
            .append(metrics.inFlightMilliseconds()).append(" ms\n");
        sb.append("    Retained    : ").append(metrics.waitingBuffers() + metrics.inFlightBuffers()).append(" buffers / ")
            .append(formatCount(metrics.waitingSamples() + metrics.inFlightSamples())).append(" samples / ")
            .append(metrics.waitingMilliseconds() + metrics.inFlightMilliseconds()).append(" ms\n");
        sb.append("    High water  : ").append(metrics.highWaterWaitingBuffers()).append(" waiting buffers / ")
            .append(formatCount(metrics.highWaterWaitingSamples())).append(" samples / ")
            .append(metrics.highWaterWaitingMilliseconds()).append(" ms\n");
        sb.append("    Received    : ").append(formatCount(metrics.receivedBuffers())).append(" buffers / ")
            .append(formatCount(metrics.receivedSamples())).append(" samples / ")
            .append(metrics.receivedMilliseconds()).append(" ms RF time\n");
        sb.append("    Processed   : ").append(formatCount(metrics.processedBuffers())).append(" buffers / ")
            .append(formatCount(metrics.processedSamples())).append(" samples / ")
            .append(metrics.processedMilliseconds()).append(" ms RF time\n");
        sb.append("    Dropped     : ").append(formatCount(metrics.droppedBuffers())).append(" buffers / ")
            .append(formatCount(metrics.droppedSamples())).append(" samples / ")
            .append(metrics.droppedMilliseconds()).append(" ms RF time\n");
        sb.append("    Cleanup     : ").append(formatCount(metrics.cleanupBuffers())).append(" buffers / ")
            .append(formatCount(metrics.cleanupSamples())).append(" samples / ")
            .append(metrics.cleanupMilliseconds()).append(" ms RF time (stop/restart/dispose)\n");
        sb.append("    Last ingress: ").append(formatAge(capturedNanos, metrics.lastIngressNanos())).append('\n');
        sb.append("    Last finish : ").append(formatAge(capturedNanos, metrics.lastCompletionNanos())).append('\n');

        sb.append("    Active age  : ").append(metrics.inFlightBuffers() > 0 ?
            formatAge(capturedNanos, metrics.activeSinceNanos()) : "inactive").append('\n');
    }

    static void renderQueue(StringBuilder sb, String label, QueueMetrics metrics, long capturedNanos,
                            double channelSampleRate)
    {
        String indent = label.equals("Output") ? "      " : "    ";
        sb.append(label.equals("Output") ? "" : "\n  ").append(label.toUpperCase(Locale.ROOT)).append('\n');

        if(metrics == null)
        {
            appendUnavailableQueue(sb, indent);
            return;
        }

        sb.append(indent).append("State       : running=").append(metrics.running()).append(", callback active=")
            .append(metrics.callbackActive()).append('\n');
        sb.append(indent).append("Queue limit : ").append(metrics.unbounded() ? "unbounded" :
            metrics.configuredLimit() + " waiting batches").append('\n');
        sb.append(indent).append("Outstanding : ").append(metrics.outstanding()).append(" batches (")
            .append(metrics.waiting()).append(" waiting + ").append(metrics.inFlight()).append(" in-flight)")
            .append(formatBatchDuration(metrics.outstanding(), channelSampleRate)).append('\n');
        sb.append(indent).append("High water  : ").append(metrics.highWaterOutstanding()).append(" outstanding batches")
            .append(formatBatchDuration(metrics.highWaterOutstanding(), channelSampleRate)).append('\n');
        sb.append(indent).append("Totals      : received ").append(formatCount(metrics.received()))
            .append("; accepted ").append(formatCount(metrics.accepted()))
            .append("; processed ").append(formatCount(metrics.processed())).append('\n');
        sb.append(indent).append("Loss/cleanup: dropped ").append(formatCount(metrics.dropped()))
            .append("; discarded ").append(formatCount(metrics.discarded())).append('\n');
        sb.append(indent).append("Last ingress: ").append(formatAge(capturedNanos, metrics.lastIngressNanos())).append('\n');
        sb.append(indent).append("Last finish : ").append(formatAge(capturedNanos, metrics.lastCompletionNanos())).append('\n');

        sb.append(indent).append("Callback age: ").append(metrics.callbackActive() ?
            formatAge(capturedNanos, metrics.activeSinceNanos()) : "inactive").append('\n');
    }

    private static void appendUnavailableRawInput(StringBuilder sb)
    {
        sb.append("    State       : unavailable or not started\n");
        sb.append("    Queue limit : unavailable\n");
        sb.append("    Waiting     : unavailable\n");
        sb.append("    In-flight   : unavailable\n");
        sb.append("    Retained    : unavailable\n");
        sb.append("    High water  : unavailable\n");
        sb.append("    Received    : unavailable\n");
        sb.append("    Processed   : unavailable\n");
        sb.append("    Dropped     : unavailable\n");
        sb.append("    Cleanup     : unavailable\n");
        sb.append("    Last ingress: unavailable\n");
        sb.append("    Last finish : unavailable\n");
        sb.append("    Active age  : inactive\n");
    }

    private static void appendUnavailableQueue(StringBuilder sb, String indent)
    {
        sb.append(indent).append("State       : unavailable or not started\n");
        sb.append(indent).append("Queue limit : unavailable\n");
        sb.append(indent).append("Outstanding : unavailable\n");
        sb.append(indent).append("High water  : unavailable\n");
        sb.append(indent).append("Totals      : unavailable\n");
        sb.append(indent).append("Loss/cleanup: unavailable\n");
        sb.append(indent).append("Last ingress: unavailable\n");
        sb.append(indent).append("Last finish : unavailable\n");
        sb.append(indent).append("Callback age: inactive\n");
    }

    /**
     * Renders a fixed-size channel table so normal traffic-channel starts and stops do not change the readout's line
     * count or scrollbar range.  Any unusually large overflow is reported without expanding the table.
     */
    static void renderChannelOutputs(StringBuilder sb, List<ChannelQueueMetrics> channels, long capturedNanos)
    {
        List<ChannelQueueMetrics> sorted = new ArrayList<>(channels);
        sorted.sort((left, right) -> Long.compare(left.requestedFrequency(), right.requestedFrequency()));
        int visibleCount = Math.min(sorted.size(), CHANNEL_OUTPUT_SLOTS);

        sb.append("\n  ACTIVE CHANNEL OUTPUTS: ").append(channels.size()).append('\n');
        sb.append("    Fixed slots: ").append(CHANNEL_OUTPUT_SLOTS)
            .append(" (inactive slots remain visible to keep this readout stable)\n");
        sb.append("    #  Frequency      Rate         State       Queue W+F/limit  High  ")
            .append("Received/Accepted/Processed  Dropped/Discarded  Last in / finish  Callback\n");

        for(int slot = 0; slot < CHANNEL_OUTPUT_SLOTS; slot++)
        {
            if(slot < visibleCount)
            {
                appendChannelOutputRow(sb, slot + 1, sorted.get(slot), capturedNanos);
            }
            else
            {
                sb.append(String.format(Locale.US, "    %2d  %-13s %-12s %-11s %-16s %-5s %-28s %-18s %-17s %s\n",
                    slot + 1, "-- inactive --", "-", "-", "-", "-", "-", "-", "-", "-"));
            }
        }

        appendHiddenChannelSummary(sb, sorted.subList(visibleCount, sorted.size()), capturedNanos);
    }

    private static void appendHiddenChannelSummary(StringBuilder sb, List<ChannelQueueMetrics> hidden,
                                                   long capturedNanos)
    {
        int unavailable = 0;
        int maximumOutstanding = 0;
        int maximumHighWater = 0;
        int activeCallbacks = 0;
        long dropped = 0;
        long discarded = 0;
        long oldestActiveSince = Long.MAX_VALUE;

        for(ChannelQueueMetrics channel: hidden)
        {
            QueueMetrics metrics = channel.output();

            if(metrics == null)
            {
                unavailable++;
                continue;
            }

            maximumOutstanding = Math.max(maximumOutstanding, metrics.outstanding());
            maximumHighWater = Math.max(maximumHighWater, metrics.highWaterOutstanding());
            dropped += metrics.dropped();
            discarded += metrics.discarded();

            if(metrics.callbackActive())
            {
                activeCallbacks++;

                if(metrics.activeSinceNanos() > 0)
                {
                    oldestActiveSince = Math.min(oldestActiveSince, metrics.activeSinceNanos());
                }
            }
        }

        String oldestCallback = activeCallbacks > 0 && oldestActiveSince < Long.MAX_VALUE ?
            compactAge(capturedNanos, oldestActiveSince) : "inactive";
        sb.append("    Additional active outputs not shown: ").append(hidden.size())
            .append("; unavailable ").append(unavailable)
            .append("; max outstanding/high ").append(maximumOutstanding).append('/').append(maximumHighWater)
            .append("; dropped/discarded ").append(formatCount(dropped)).append('/').append(formatCount(discarded))
            .append("; active callbacks ").append(activeCallbacks)
            .append("; oldest callback ").append(oldestCallback).append('\n');
    }

    private static void appendChannelOutputRow(StringBuilder sb, int slot, ChannelQueueMetrics channel,
                                               long capturedNanos)
    {
        QueueMetrics metrics = channel.output();

        if(metrics == null)
        {
            sb.append(String.format(Locale.US, "    %2d  %-13s %-12s %-11s %-16s %-5s %-28s %-18s %-17s %s\n",
                slot, compactFrequency(channel.requestedFrequency()), formatRate(channel.sampleRate()), "unavailable",
                "-", "-", "-", "-", "-", "-"));
            return;
        }

        String state = metrics.running() ? (metrics.callbackActive() ? "run/active" : "run/idle") : "stopped";
        String limit = metrics.unbounded() ? "unbounded" : Integer.toString(metrics.configuredLimit());
        String queue = metrics.waiting() + "+" + metrics.inFlight() + "/" + limit;
        String totals = formatCount(metrics.received()) + "/" + formatCount(metrics.accepted()) + "/" +
            formatCount(metrics.processed());
        String loss = formatCount(metrics.dropped()) + "/" + formatCount(metrics.discarded());
        String progress = compactAge(capturedNanos, metrics.lastIngressNanos()) + " / " +
            compactAge(capturedNanos, metrics.lastCompletionNanos());
        String callback = metrics.callbackActive() ? compactAge(capturedNanos, metrics.activeSinceNanos()) : "inactive";
        sb.append(String.format(Locale.US, "    %2d  %-13s %-12s %-11s %-16s %-5d %-28s %-18s %-17s %s\n",
            slot, compactFrequency(channel.requestedFrequency()), formatRate(channel.sampleRate()), state, queue,
            metrics.highWaterOutstanding(), totals, loss, progress, callback));
    }

    private static String compactFrequency(long frequency)
    {
        return String.format(Locale.US, "%.6f MHz", frequency / 1_000_000.0);
    }

    private static String compactAge(long capturedNanos, long eventNanos)
    {
        if(eventNanos <= 0)
        {
            return "never";
        }

        return String.format(Locale.US, "%.3fs", Math.max(0, capturedNanos - eventNanos) / 1_000_000_000.0);
    }

    private static String formatBatchDuration(int batches, double channelSampleRate)
    {
        if(batches <= 0 || !Double.isFinite(channelSampleRate) || channelSampleRate <= 0)
        {
            return "";
        }

        double milliseconds = batches * CHANNELIZER_BATCH_SAMPLE_COUNT * 1000.0 / channelSampleRate;
        return String.format(Locale.US, "; approximately %.2f ms RF time", milliseconds);
    }

    private static String formatAge(long capturedNanos, long eventNanos)
    {
        if(eventNanos <= 0)
        {
            return "never";
        }

        double seconds = Math.max(0, capturedNanos - eventNanos) / 1_000_000_000.0;
        return String.format(Locale.US, "%.3f seconds ago", seconds);
    }

    private static String formatFrequency(long frequency)
    {
        return String.format(Locale.US, "%.6f MHz", frequency / 1_000_000.0);
    }

    private static String formatRate(double sampleRate)
    {
        if(sampleRate >= 1_000_000.0)
        {
            return String.format(Locale.US, "%.3f MS/s", sampleRate / 1_000_000.0);
        }

        return String.format(Locale.US, "%.3f kS/s", sampleRate / 1_000.0);
    }

    private static String formatCount(long value)
    {
        return String.format(Locale.US, "%,d", value);
    }

    private static String formatBytes(long bytes)
    {
        double gibibytes = bytes / (1024.0 * 1024.0 * 1024.0);

        if(gibibytes >= 1.0)
        {
            return String.format(Locale.US, "%.2f GiB", gibibytes);
        }

        return String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private static String formatDuration(long milliseconds)
    {
        long totalSeconds = milliseconds / 1000;
        long days = totalSeconds / 86400;
        long hours = totalSeconds % 86400 / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d d %02d:%02d:%02d", days, hours, minutes, seconds);
    }

    private static String valueOrUnavailable(String value)
    {
        return value == null || value.isBlank() ? "unavailable in unpackaged development run" : value;
    }
}
