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
import io.github.dsheirer.debug.ReceiverIncidentController;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentCaptureResult;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentReportState;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentState;
import io.github.dsheirer.debug.ReceiverIncidentController.ReceiverIncidentStatus;
import io.github.dsheirer.debug.ReceiverIncidentController.ThreadDumpState;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
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
    private static final String INCIDENT_RELATIVE_DIRECTORY = "diagnostics/receiver-incidents/";

    private final TunerManager mTunerManager;
    private final Supplier<ReceiverIncidentController> mIncidentControllerSupplier;
    private final TextArea mReadout = new TextArea();
    private final Label mActionStatus = new Label();
    private final Label mIncidentStatus = new Label();
    private final Tooltip mIncidentStatusTooltip = new Tooltip();
    private final Timeline mTimeline;
    private String mCurrentReadout = "";
    private String mCurrentLocalTimestamp = "";

    public ReceiverQueuesView(TunerManager tunerManager)
    {
        this(tunerManager, () -> null);
    }

    public ReceiverQueuesView(TunerManager tunerManager, ReceiverIncidentController incidentController)
    {
        this(tunerManager, () -> incidentController);
    }

    public ReceiverQueuesView(TunerManager tunerManager,
                              Supplier<ReceiverIncidentController> incidentControllerSupplier)
    {
        mTunerManager = tunerManager;
        mIncidentControllerSupplier = incidentControllerSupplier != null ? incidentControllerSupplier : () -> null;

        Button copyButton = new Button("Copy Snapshot");
        copyButton.setOnAction(event -> copySnapshot());
        Button captureIncidentButton = new Button("Capture Incident");
        captureIncidentButton.setOnAction(event -> captureIncident(false));
        Button captureWithThreadsButton = new Button("Capture Incident + Threads");
        captureWithThreadsButton.setOnAction(event -> captureIncident(true));
        Button copyLatestIncidentButton = new Button("Copy Latest Incident");
        copyLatestIncidentButton.setOnAction(event -> copyLatestIncident());
        HBox controls = new HBox(10, copyButton, captureIncidentButton, captureWithThreadsButton,
            copyLatestIncidentButton, mActionStatus);
        controls.setPadding(new Insets(8, 8, 0, 8));
        HBox.setHgrow(mActionStatus, Priority.ALWAYS);
        mIncidentStatus.setMaxWidth(Double.MAX_VALUE);
        mIncidentStatus.setTooltip(mIncidentStatusTooltip);
        VBox.setVgrow(mIncidentStatus, Priority.NEVER);
        VBox header = new VBox(5, controls, mIncidentStatus);
        header.setPadding(new Insets(0, 0, 8, 0));
        VBox.setMargin(mIncidentStatus, new Insets(0, 8, 0, 8));

        mReadout.setEditable(false);
        mReadout.setWrapText(false);
        mReadout.setStyle("-fx-font-family: monospace;");

        setTop(header);
        setCenter(mReadout);
        BorderPane.setMargin(mReadout, new Insets(0, 8, 8, 8));

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
        mActionStatus.setText("Copied queue snapshot captured " + mCurrentLocalTimestamp);
    }

    private void captureIncident(boolean includeThreads)
    {
        ReceiverIncidentController controller = incidentController();

        if(controller == null)
        {
            mActionStatus.setText("The receiver flight recorder is unavailable.");
            return;
        }

        String reason = includeThreads ? "Manual capture with thread dump from Receiver Queues" :
            "Manual capture from Receiver Queues";
        IncidentCaptureResult result = controller.captureIncident(reason, includeThreads);
        mActionStatus.setText(switch(result)
        {
            case STARTED -> includeThreads ?
                "Incident recording started; a thread dump was requested." : "Incident recording started.";
            case COALESCED -> includeThreads ?
                "Added the thread-dump request to the incident already being recorded." :
                "Added this request to the incident already being recorded.";
            case REJECTED_BUSY -> "Diagnostics are busy; the request was not started.";
            case REJECTED_CLOSED -> "The receiver flight recorder is closed.";
        });
        updateIncidentStatus();
    }

    private void copyLatestIncident()
    {
        ReceiverIncidentController controller = incidentController();

        if(controller == null)
        {
            mActionStatus.setText("The receiver flight recorder is unavailable.");
            return;
        }

        String incident = composeLatestIncidentClipboard(controller.getStatus(), controller.getLatestIncidentText(),
            controller.getLatestIncidentJson());

        if(incident == null || incident.isBlank())
        {
            mActionStatus.setText("No completed incident is available to copy yet.");
            return;
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(incident);
        Clipboard.getSystemClipboard().setContent(content);
        mActionStatus.setText("Copied the latest completed incident.");
    }

    static String composeLatestIncidentClipboard(ReceiverIncidentStatus status, String summary, byte[] json)
    {
        if(status == null || status.latestIncidentReportState() != IncidentReportState.SAVED ||
            status.savedIncidentCount() <= 0 || status.latestIncidentAtMs() <= 0 ||
            status.latestIncidentFileName() == null || status.latestIncidentFileName().isBlank() ||
            json == null || json.length == 0)
        {
            return "";
        }

        String evidence = new String(json, StandardCharsets.UTF_8);

        if(evidence.isBlank())
        {
            return "";
        }

        StringBuilder report = new StringBuilder(summary != null ? summary.length() + evidence.length() + 256 :
            evidence.length() + 256);
        report.append("RECEIVER INCIDENT TROUBLESHOOTING REPORT\n");
        report.append("Portable saved report: ").append(INCIDENT_RELATIVE_DIRECTORY)
            .append(fileNameOnly(status.latestIncidentFileName())).append("\n\n");

        if(summary != null && !summary.isBlank())
        {
            report.append("SUMMARY\n").append(summary.strip()).append("\n\n");
        }

        report.append("FULL BOUNDED JSON EVIDENCE\n").append(evidence.strip()).append('\n');
        return report.toString();
    }

    private static String fileNameOnly(String value)
    {
        String normalized = value.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private void refresh()
    {
        Instant captured = Instant.now();
        mCurrentLocalTimestamp = LOCAL_TIMESTAMP.format(captured.atZone(ZoneId.systemDefault()));
        mCurrentReadout = render(captured, System.nanoTime());
        updateIncidentStatus();
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

        if(mActionStatus.getText().isBlank())
        {
            mActionStatus.setText("Live readout captured " + mCurrentLocalTimestamp);
        }
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
        renderIncidentSummary(sb, incidentStatus());

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

    private ReceiverIncidentStatus incidentStatus()
    {
        ReceiverIncidentController controller = incidentController();
        return controller != null ? controller.getStatus() : null;
    }

    private ReceiverIncidentController incidentController()
    {
        try
        {
            return mIncidentControllerSupplier.get();
        }
        catch(RuntimeException e)
        {
            return null;
        }
    }

    private void updateIncidentStatus()
    {
        ReceiverIncidentStatus status = incidentStatus();
        String banner = formatIncidentBanner(status);
        mIncidentStatus.setText(banner);
        mIncidentStatusTooltip.setText(banner);
        mIncidentStatus.setStyle(incidentBannerStyle(status));
    }

    static String formatIncidentBanner(ReceiverIncidentStatus status)
    {
        if(status == null)
        {
            return "Flight recorder unavailable — queue snapshots remain available.";
        }

        StringBuilder sb = new StringBuilder("Flight recorder: ");

        if(status.state() == IncidentState.RECORDING)
        {
            sb.append("RECORDING — ").append(valueOrNone(status.activeReason()));

            if(status.threadDumpState() == ThreadDumpState.SCHEDULED ||
                status.threadDumpState() == ThreadDumpState.CAPTURING)
            {
                sb.append(" — thread dump ")
                    .append(status.threadDumpState() == ThreadDumpState.SCHEDULED ? "scheduled" : "in progress");
            }
        }
        else if(status.state() == IncidentState.CLOSED)
        {
            sb.append("closed");
        }
        else
        {
            sb.append("ARMED — ").append(status.retainedSamples()).append('/')
                .append(status.retainedSampleLimit()).append(" recent samples retained");
        }

        if(status.threadDumpState() == ThreadDumpState.CAPTURED && status.lastThreadDumpAtMs() > 0)
        {
            sb.append(" — Thread dump captured ").append(formatLocalTimestamp(status.lastThreadDumpAtMs()))
                .append(" — ").append(valueOrNone(status.lastThreadDumpReason()));
        }
        else if(status.threadDumpState() == ThreadDumpState.FAILED)
        {
            sb.append(" — Thread dump failed");

            if(status.lastThreadDumpAtMs() > 0)
            {
                sb.append(' ').append(formatLocalTimestamp(status.lastThreadDumpAtMs()));
            }

            sb.append(" — ").append(valueOrNone(status.lastThreadDumpReason()));

            if(status.lastError() != null && !status.lastError().isBlank())
            {
                sb.append(" (").append(valueOrNone(status.lastError())).append(')');
            }
        }

        if(status.latestIncidentReportState() == IncidentReportState.CAPTURED_PENDING_SAVE)
        {
            sb.append(" — Incident evidence captured in memory; durable save is pending");
        }
        else if(status.latestIncidentReportState() == IncidentReportState.SAVED &&
            status.latestIncidentFileName() != null)
        {
            sb.append(" — Incident report saved: ").append(INCIDENT_RELATIVE_DIRECTORY)
                .append(fileNameOnly(status.latestIncidentFileName()));
        }
        else if(status.latestIncidentReportState() == IncidentReportState.SAVE_FAILED)
        {
            sb.append(" — Incident report save FAILED");

            if(status.lastError() != null && !status.lastError().isBlank())
            {
                sb.append(" — ").append(valueOrNone(status.lastError()));
            }
        }

        return sb.toString();
    }

    static void renderIncidentSummary(StringBuilder sb, ReceiverIncidentStatus status)
    {
        if(status == null)
        {
            sb.append("Flight recorder : unavailable\n");
            sb.append("Latest incident : none saved\n");
            sb.append("Thread dump     : none captured\n");
            return;
        }

        sb.append("Flight recorder : ");

        if(status.state() == IncidentState.RECORDING)
        {
            sb.append("RECORDING — ").append(valueOrNone(status.activeReason()));
        }
        else
        {
            sb.append(status.state()).append(" — ").append(status.retainedSamples()).append('/')
                .append(status.retainedSampleLimit()).append(" recent samples retained");
        }

        sb.append('\n');
        sb.append("Latest incident : ");

        if(status.latestIncidentReportState() == IncidentReportState.CAPTURED_PENDING_SAVE)
        {
            sb.append("captured in memory; durable save pending");
        }
        else if(status.latestIncidentReportState() == IncidentReportState.SAVE_FAILED)
        {
            sb.append("SAVE FAILED — ").append(valueOrNone(status.lastError()));
        }
        else if(status.latestIncidentReportState() == IncidentReportState.SAVED &&
            status.latestIncidentAtMs() > 0)
        {
            sb.append(formatLocalTimestamp(status.latestIncidentAtMs())).append(" — ")
                .append(valueOrNone(status.latestIncidentReason())).append(" — ")
                .append(valueOrNone(status.latestIncidentFileName()));
        }
        else
        {
            sb.append("none saved");
        }

        sb.append('\n');
        sb.append("Thread dump     : ");

        if(status.threadDumpState() == ThreadDumpState.NONE)
        {
            sb.append("none captured");
        }
        else
        {
            sb.append(status.threadDumpState());

            if(status.lastThreadDumpAtMs() > 0)
            {
                sb.append(" at ").append(formatLocalTimestamp(status.lastThreadDumpAtMs()));
            }

            sb.append(" — ").append(valueOrNone(status.lastThreadDumpReason()));

            if(status.lastThreadDumpDurationMs() > 0)
            {
                sb.append(" — ").append(status.lastThreadDumpDurationMs()).append(" ms");
            }

            if(status.threadDumpState() == ThreadDumpState.FAILED && status.lastError() != null &&
                !status.lastError().isBlank())
            {
                sb.append(" — ").append(valueOrNone(status.lastError()));
            }
        }

        sb.append('\n');
    }

    private static String incidentBannerStyle(ReceiverIncidentStatus status)
    {
        if(status != null && (status.threadDumpState() == ThreadDumpState.FAILED ||
            status.latestIncidentReportState() == IncidentReportState.SAVE_FAILED || status.lastError() != null &&
            !status.lastError().isBlank()))
        {
            return "-fx-text-fill: #b00020; -fx-font-weight: bold;";
        }

        if(status != null && (status.threadDumpState() == ThreadDumpState.CAPTURED ||
            status.threadDumpState() == ThreadDumpState.SCHEDULED ||
            status.threadDumpState() == ThreadDumpState.CAPTURING || status.state() == IncidentState.RECORDING ||
            status.latestIncidentReportState() == IncidentReportState.CAPTURED_PENDING_SAVE))
        {
            return "-fx-text-fill: #b26a00; -fx-font-weight: bold;";
        }

        return "-fx-text-fill: #2e6b37;";
    }

    private static String formatLocalTimestamp(long epochMilliseconds)
    {
        return LOCAL_TIMESTAMP.format(Instant.ofEpochMilli(epochMilliseconds).atZone(ZoneId.systemDefault()));
    }

    private static String valueOrNone(String value)
    {
        return value == null || value.isBlank() ? "none" : value.replace('\r', ' ').replace('\n', ' ');
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
