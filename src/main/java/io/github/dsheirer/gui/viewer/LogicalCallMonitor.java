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

package io.github.dsheirer.gui.viewer;

import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDecisionOutcome;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticCallIdentity;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticCounters;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticDecision;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticFileState;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticLeg;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticOverlap;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticOutputPolicy;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticService;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticServiceSnapshot;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticSnapshot;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticStatus;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticWinner;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallMergeProof;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallSeparationReason;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallWinnerCriterion;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TitledPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * Temporary, session-only view of the logical-call resolver. The view polls immutable, bounded in-memory snapshots;
 * it never opens a database, file, or network connection on the JavaFX application thread.
 */
public class LogicalCallMonitor extends BorderPane
{
    private static final int MAXIMUM_VISIBLE_DECISIONS = 100;
    private static final Duration REFRESH_INTERVAL = Duration.seconds(1);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withLocale(Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DecimalFormat ONE_DECIMAL =
        new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final Color HEALTHY_COLOR = Color.FORESTGREEN;
    private static final Color WARNING_COLOR = Color.DARKORANGE;
    private static final Color ERROR_COLOR = Color.FIREBRICK;
    private static final Color INACTIVE_COLOR = Color.DIMGRAY;

    private final Timeline mRefreshTimeline;
    private final TreeItem<MonitorRow> mDecisionRoot = new TreeItem<>();
    private final TreeTableView<MonitorRow> mDecisionTable = new TreeTableView<>(mDecisionRoot);
    private final Map<Long,TreeItem<MonitorRow>> mDecisionItemsBySequence = new LinkedHashMap<>();
    private final Label mResolverHealthLabel = statusLabel();
    private final Label mReceivingValueLabel = metricValue();
    private final Label mWaitingValueLabel = metricValue();
    private final Label mCompletedValueLabel = metricValue();
    private final Label mDuplicatesValueLabel = metricValue();
    private final Label mUncertainValueLabel = metricValue();
    private final Label mResolverStateLabel = new Label();
    private final Label mSessionLabel = new Label();
    private final Label mIngressLabel = new Label();
    private final Label mFileHealthLabel = statusLabel();
    private final Label mFileDropsValueLabel = metricValue();
    private final Label mFileStateLabel = new Label();
    private final Label mFilePathLabel = new Label();
    private final Tooltip mFilePathTooltip = new Tooltip();
    private final Label mFooterLabel = new Label();
    private final Button mPauseButton = new Button("Pause");
    private final Button mOpenLogFolderButton = new Button("Open Logs");
    private final CheckBox mDuplicatesOnlyCheckBox = new CheckBox("Show only duplicates");
    private LogicalCallDiagnosticService mDiagnosticService;
    private AudioCallCoordinator mCoordinator;
    private Consumer<Path> mOpenDirectoryConsumer;
    private boolean mPaused;
    private long mLastDecisionSequence = Long.MIN_VALUE;
    private int mLastDecisionCount = -1;
    private long mLastEvictedCount = -1L;
    private boolean mLastDuplicatesOnly;
    private String mCoordinatorSessionId;

    public LogicalCallMonitor(LogicalCallDiagnosticService diagnosticService, AudioCallCoordinator coordinator,
                              Consumer<Path> openDirectoryConsumer)
    {
        mDiagnosticService = diagnosticService;
        mCoordinator = coordinator;
        mOpenDirectoryConsumer = openDirectoryConsumer;
        setId("call-matching-monitor");
        mDecisionTable.setId("call-matching-decision-table");
        mDuplicatesOnlyCheckBox.setId("call-matching-duplicates-only");
        mPauseButton.setId("call-matching-pause");
        mOpenLogFolderButton.setId("call-matching-open-logs");
        setPadding(new Insets(10));
        setTop(createHeader());
        configureDecisionTable();
        setCenter(mDecisionTable);
        setBottom(createFooter());
        mRefreshTimeline = new Timeline(new KeyFrame(REFRESH_INTERVAL, event -> refresh()));
        mRefreshTimeline.setCycleCount(Animation.INDEFINITE);
    }

    /** Replaces the live sources if application startup wires them after the lazy window was constructed. */
    public void setSources(LogicalCallDiagnosticService diagnosticService, AudioCallCoordinator coordinator,
                           Consumer<Path> openDirectoryConsumer)
    {
        mDiagnosticService = diagnosticService;
        mCoordinator = coordinator;
        mOpenDirectoryConsumer = openDirectoryConsumer;
        clearDecisionView();
        refresh();
    }

    /** Starts bounded polling while the window is visible. */
    public void activate()
    {
        refresh();
        mRefreshTimeline.playFromStart();
    }

    /** Stops polling as soon as the window is hidden. */
    public void deactivate()
    {
        mRefreshTimeline.stop();
    }

    private VBox createHeader()
    {
        VBox header = new VBox(9);
        header.setPadding(new Insets(0, 0, 10, 0));

        Label title = sectionLabel("Call Matching Monitor");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label explanation = new Label(
            "A copy is one receiver/site's version of a call. Expand a call to compare its copies and quality.");
        explanation.setWrapText(true);
        explanation.setStyle("-fx-opacity: 0.78;");
        VBox introduction = new VBox(2, title, explanation);

        FlowPane actions = new FlowPane(8, 6, mDuplicatesOnlyCheckBox, mPauseButton, mOpenLogFolderButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox titleRow = new VBox(6, introduction, actions);

        mDuplicatesOnlyCheckBox.setTooltip(new Tooltip(
            "Show confirmed duplicate calls that were received by more than one site and combined."));
        mDuplicatesOnlyCheckBox.setOnAction(event -> {
            resetDecisionView();
            refresh();
        });
        mPauseButton.setOnAction(event -> {
            mPaused = !mPaused;
            mPauseButton.setText(mPaused ? "Resume" : "Pause");
            mFooterLabel.setText(mPaused ? "Display paused; diagnostic capture continues." : "");

            if(!mPaused)
            {
                refresh();
            }
        });
        mOpenLogFolderButton.setOnAction(event -> openDiagnosticDirectory());

        FlowPane summary = new FlowPane(8, 8);
        summary.setAlignment(Pos.CENTER_LEFT);
        summary.getChildren().addAll(metric("Call matching", mResolverHealthLabel),
            metric("Receiving now", mReceivingValueLabel), metric("Waiting to match", mWaitingValueLabel),
            metric("Calls completed", mCompletedValueLabel), metric("Duplicates combined", mDuplicatesValueLabel),
            metric("Uncertain calls kept", mUncertainValueLabel), metric("Diagnostic log", mFileHealthLabel),
            metric("Log drops", mFileDropsValueLabel));

        mResolverStateLabel.setWrapText(true);
        mIngressLabel.setWrapText(true);
        mSessionLabel.setWrapText(true);
        mFileStateLabel.setWrapText(true);
        mFilePathLabel.setWrapText(true);
        mFilePathLabel.setTooltip(mFilePathTooltip);
        VBox technicalContent = new VBox(5, mResolverStateLabel, mIngressLabel, mSessionLabel, mFileStateLabel,
            mFilePathLabel);
        technicalContent.setPadding(new Insets(7, 9, 9, 9));
        TitledPane technicalDetails = new TitledPane("Technical details", technicalContent);
        technicalDetails.setId("call-matching-technical-health");
        technicalDetails.setAnimated(false);
        technicalDetails.setExpanded(false);

        header.getChildren().addAll(titleRow, summary, technicalDetails);
        return header;
    }

    private HBox createFooter()
    {
        HBox footer = new HBox(8, mFooterLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(7, 0, 0, 0));
        return footer;
    }

    private void configureDecisionTable()
    {
        mDecisionTable.setShowRoot(false);
        mDecisionTable.setPlaceholder(new Label("No logical-call decisions have been captured in this session."));
        mDecisionTable.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        addColumn("Time", 105, MonitorRow::time);
        TreeTableColumn<MonitorRow,String> callColumn = addColumn("Call", 155, MonitorRow::result);
        addColumn("Talkgroup", 170, MonitorRow::destination);
        addColumn("Radio", 145, MonitorRow::source);
        addColumn("Site", 210, MonitorRow::site);
        addColumn("Duration", 120, MonitorRow::timing);
        addColumn("Match / Quality", 310, MonitorRow::details);
        addColumn("Outputs", 180, MonitorRow::outputs);
        mDecisionTable.setTreeColumn(callColumn);
        mDecisionTable.setRowFactory(ignored -> {
            TreeTableRow<MonitorRow> row = new TreeTableRow<>();
            row.setOnMouseClicked(event -> {
                TreeItem<MonitorRow> item = row.getTreeItem();

                if(event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty() &&
                    item != null && !item.isLeaf() && !isDisclosureNode(event.getTarget()))
                {
                    item.setExpanded(!item.isExpanded());
                }
            });
            return row;
        });
    }

    private TreeTableColumn<MonitorRow,String> addColumn(String name, double width,
                                                         java.util.function.Function<MonitorRow,String> value)
    {
        TreeTableColumn<MonitorRow,String> column = new TreeTableColumn<>(name);
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setCellValueFactory(features -> {
            MonitorRow row = features.getValue().getValue();
            return new ReadOnlyStringWrapper(row != null ? value.apply(row) : "");
        });
        column.setCellFactory(ignored -> new TreeTableCell<>()
        {
            private final Tooltip mTooltip = new Tooltip();

            @Override
            protected void updateItem(String item, boolean empty)
            {
                super.updateItem(item, empty);
                String text = !empty && item != null ? item : null;
                setText(text);

                if(text != null && text.length() > 24)
                {
                    mTooltip.setText(text);
                    setTooltip(mTooltip);
                }
                else
                {
                    setTooltip(null);
                }
            }
        });
        mDecisionTable.getColumns().add(column);
        return column;
    }

    private static boolean isDisclosureNode(Object target)
    {
        Node node = target instanceof Node targetNode ? targetNode : null;

        while(node != null)
        {
            if(node.getStyleClass().contains("tree-disclosure-node"))
            {
                return true;
            }

            node = node.getParent();
        }

        return false;
    }

    private void refresh()
    {
        if(mPaused)
        {
            return;
        }

        LogicalCallDiagnosticService service = mDiagnosticService;
        AudioCallCoordinator coordinator = mCoordinator;

        if(service == null || coordinator == null)
        {
            showUnavailable();
            return;
        }

        LogicalCallDiagnosticServiceSnapshot serviceSnapshot = service.snapshot();
        LogicalCallDiagnosticSnapshot resolverSnapshot = coordinator.getDiagnosticSnapshot();
        AudioCallCoordinator.CoordinatorQueueStatus queueStatus = coordinator.getQueueStatus();
        updateResolverStatus(resolverSnapshot, queueStatus);
        updateFileStatus(serviceSnapshot.status(), service.diagnosticDirectory());
        updateDecisions(serviceSnapshot, resolverSnapshot.sessionId());
        mOpenLogFolderButton.setDisable(mOpenDirectoryConsumer == null || service.diagnosticDirectory() == null);

        if(!mPaused)
        {
            String kind = mDuplicatesOnlyCheckBox.isSelected() ? " confirmed duplicates" : " calls";
            mFooterLabel.setText("Showing " + mDecisionRoot.getChildren().size() + kind +
                " from the bounded in-memory history. " + serviceSnapshot.recentDecisionsEvicted() +
                " older calls have rolled out of this window; file logging continues independently.");
        }
    }

    private void showUnavailable()
    {
        setStatus(mResolverHealthLabel, "Unavailable", ERROR_COLOR);
        mReceivingValueLabel.setText("—");
        mWaitingValueLabel.setText("—");
        mCompletedValueLabel.setText("—");
        mDuplicatesValueLabel.setText("—");
        mUncertainValueLabel.setText("—");
        mFileDropsValueLabel.setText("—");
        mResolverStateLabel.setText("The call-matching diagnostic source is not connected.");
        mSessionLabel.setText("");
        setStatus(mFileHealthLabel, "Unavailable", ERROR_COLOR);
        mFileStateLabel.setText("");
        mIngressLabel.setText("");
        mFilePathLabel.setText("");
        mOpenLogFolderButton.setDisable(true);
    }

    private void updateResolverStatus(LogicalCallDiagnosticSnapshot snapshot,
                                      AudioCallCoordinator.CoordinatorQueueStatus queue)
    {
        LogicalCallDiagnosticCounters counters = snapshot.counters();
        boolean pressure = queue.totalIngressCapacity() > 0 &&
            queue.ingressDepth() * 4 >= queue.totalIngressCapacity() * 3;
        boolean loss = queue.droppedOperations() > 0 || queue.abortedCalls() > 0 ||
            counters.diagnosticDecisionsRejected() > 0;
        long snapshotAge = Math.max(0L, System.currentTimeMillis() - snapshot.generatedAtMs());

        if(snapshot.disposed())
        {
            setStatus(mResolverHealthLabel, "Stopped", ERROR_COLOR);
        }
        else if(snapshot.accepting() && snapshotAge > 3_000L)
        {
            setStatus(mResolverHealthLabel, "Unresponsive", ERROR_COLOR);
        }
        else if(!snapshot.accepting())
        {
            setStatus(mResolverHealthLabel, "Draining", WARNING_COLOR);
        }
        else if(loss || pressure)
        {
            setStatus(mResolverHealthLabel, "Warning", WARNING_COLOR);
        }
        else
        {
            setStatus(mResolverHealthLabel, "Healthy", HEALTHY_COLOR);
        }

        mReceivingValueLabel.setText(Long.toString(snapshot.activeLegCount()));
        mWaitingValueLabel.setText(Long.toString(snapshot.activeCohortCount()));
        mCompletedValueLabel.setText(Long.toString(counters.emittedLogicalCalls()));
        mDuplicatesValueLabel.setText(counters.mergedLogicalCalls() + " calls · " +
            counters.mergedReceiverCopies() + " extra copies");
        mUncertainValueLabel.setText(Long.toString(counters.failOpenLogicalCalls()));
        mResolverStateLabel.setText("Receiving copies: " + snapshot.activeLegCount() + " · Calls waiting for other " +
            "sites: " + snapshot.activeCohortCount() + " · Retained audio: " +
            formatSamples(snapshot.retainedAudioSampleCount()) + " · Status age: " + formatDuration(snapshotAge));
        mSessionLabel.setText("Completed calls: " + counters.emittedLogicalCalls() + " · Received copies: " +
            counters.completedReceiverLegs() + " · Duplicates combined: " + counters.mergedLogicalCalls() +
            " · Extra copies not sent: " + counters.mergedReceiverCopies() + " · Single calls: " +
            counters.independentLogicalCalls() + " · Uncertain calls kept separate: " +
            counters.failOpenLogicalCalls());
        mIngressLabel.setText("Resolver queue: " + queue.ingressDepth() + "/" + queue.totalIngressCapacity() +
            " (regular capacity " + queue.regularIngressCapacity() + ") · Accepted: " + queue.acceptedIngress() +
            " · Dropped: " + queue.droppedIngress() + " · Lifecycle dropped: " + queue.droppedLifecycle() +
            " · Aborted: " + queue.abortedCalls());
    }

    private void updateFileStatus(LogicalCallDiagnosticStatus status, Path diagnosticDirectory)
    {
        boolean drops = status.recordsDroppedAtQueue() > 0 || status.recordsRejectedAfterClose() > 0 ||
            status.fileRecordsDropped() > 0 || status.oversizedRecordsDropped() > 0;
        long totalDrops = status.recordsDroppedAtQueue() + status.recordsRejectedAfterClose() +
            status.fileRecordsDropped() + status.oversizedRecordsDropped();

        if(status.fileState() == LogicalCallDiagnosticFileState.DISABLED || status.fileWriteFailures() > 0)
        {
            setStatus(mFileHealthLabel, "Error", ERROR_COLOR);
        }
        else if(drops || status.queuedRecords() * 4 >= Math.max(1, status.queueCapacity()) * 3)
        {
            setStatus(mFileHealthLabel, "Warning", WARNING_COLOR);
        }
        else if(status.fileState() == LogicalCallDiagnosticFileState.ACTIVE)
        {
            setStatus(mFileHealthLabel, "Active", HEALTHY_COLOR);
        }
        else
        {
            setStatus(mFileHealthLabel, friendly(status.fileState()), INACTIVE_COLOR);
        }

        mFileDropsValueLabel.setText(Long.toString(totalDrops));
        mFileStateLabel.setText("Diagnostic log: " + friendly(status.fileState()) + " · Writer queue: " +
            status.queuedRecords() + "/" + status.queueCapacity() + " · Current file: " +
            formatBytes(status.activeFileBytes()) + "/" + formatBytes(status.maximumFileBytes()) +
            " · Files retained: " + status.retainedFileCount() + "/" + status.maximumFiles() +
            " · Records written: " + status.fileRecordsWritten() + " · Recorded confirmations: " +
            status.recordedConfirmationsObserved() + " · Stream confirmations: " +
            status.streamSubmittedConfirmationsObserved() + " · Write errors: " +
            status.fileWriteFailures() + " · Queue drops: " + status.recordsDroppedAtQueue() +
            " · File drops: " + status.fileRecordsDropped() + " · Oversized drops: " +
            status.oversizedRecordsDropped());
        String path = diagnosticDirectory != null ? diagnosticDirectory.toString() : "Unavailable";
        mFilePathLabel.setText("Log folder: " + path);
        mFilePathTooltip.setText(path);
    }

    private void updateDecisions(LogicalCallDiagnosticServiceSnapshot snapshot, String coordinatorSessionId)
    {
        List<LogicalCallDiagnosticDecision> decisions = snapshot.recentDecisions();
        long latestSequence = decisions.isEmpty() ? 0L : decisions.getLast().decisionSequence();
        boolean duplicatesOnly = mDuplicatesOnlyCheckBox.isSelected();
        boolean sessionChanged = !Objects.equals(mCoordinatorSessionId, coordinatorSessionId);

        if(sessionChanged)
        {
            mCoordinatorSessionId = coordinatorSessionId;
            clearRenderedDecisionItems();
            resetDecisionView();
        }

        if(!sessionChanged && latestSequence == mLastDecisionSequence && decisions.size() == mLastDecisionCount &&
            snapshot.recentDecisionsEvicted() == mLastEvictedCount && duplicatesOnly == mLastDuplicatesOnly)
        {
            return;
        }

        List<LogicalCallDiagnosticDecision> visible = visibleDecisions(decisions, duplicatesOnly,
            MAXIMUM_VISIBLE_DECISIONS);
        List<TreeItem<MonitorRow>> items = new ArrayList<>(visible.size());
        Set<Long> retainedSequences = new LinkedHashSet<>();

        for(LogicalCallDiagnosticDecision decision : decisions)
        {
            retainedSequences.add(decision.decisionSequence());
        }

        mDecisionItemsBySequence.keySet().removeIf(sequence -> !retainedSequences.contains(sequence));

        for(LogicalCallDiagnosticDecision decision : visible)
        {
            items.add(mDecisionItemsBySequence.computeIfAbsent(decision.decisionSequence(),
                ignored -> decisionItem(decision)));
        }

        reconcileStableItems(mDecisionRoot.getChildren(), items);
        mLastDecisionSequence = latestSequence;
        mLastDecisionCount = decisions.size();
        mLastEvictedCount = snapshot.recentDecisionsEvicted();
        mLastDuplicatesOnly = duplicatesOnly;
        mDecisionTable.setPlaceholder(new Label(duplicatesOnly ?
            "No confirmed duplicate calls are currently in the recent history." :
            "No calls have been captured in this session."));
    }

    static List<LogicalCallDiagnosticDecision> visibleDecisions(List<LogicalCallDiagnosticDecision> decisions,
                                                                 boolean duplicatesOnly, int maximum)
    {
        List<LogicalCallDiagnosticDecision> filtered = decisions != null ? decisions.stream()
            .filter(decision -> !duplicatesOnly || decision.outcome() == LogicalCallDecisionOutcome.MERGED)
            .toList() : List.of();
        int first = Math.max(0, filtered.size() - Math.max(0, maximum));
        List<LogicalCallDiagnosticDecision> visible = new ArrayList<>(filtered.subList(first, filtered.size()));
        Collections.reverse(visible);
        return List.copyOf(visible);
    }

    private void resetDecisionView()
    {
        mLastDecisionSequence = Long.MIN_VALUE;
        mLastDecisionCount = -1;
        mLastEvictedCount = -1L;
        mLastDuplicatesOnly = !mDuplicatesOnlyCheckBox.isSelected();
    }

    private void clearDecisionView()
    {
        mCoordinatorSessionId = null;
        clearRenderedDecisionItems();
        resetDecisionView();
    }

    private void clearRenderedDecisionItems()
    {
        mDecisionTable.getSelectionModel().clearSelection();
        mDecisionTable.getFocusModel().focus(-1);
        mDecisionItemsBySequence.clear();
        mDecisionRoot.getChildren().clear();
    }

    /**
     * Reconciles a live JavaFX tree without replacing items that still represent the same immutable decision. Keeping
     * the same {@link TreeItem} instances preserves expansion, selection, and virtualized-row state while new calls
     * are inserted at the top of the table.
     */
    static <T> void reconcileStableItems(ObservableList<T> current, List<T> desired)
    {
        Objects.requireNonNull(current, "current cannot be null");
        Objects.requireNonNull(desired, "desired cannot be null");

        Set<T> desiredIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
        desiredIdentities.addAll(desired);
        current.removeIf(item -> !desiredIdentities.contains(item));

        for(int desiredIndex = 0; desiredIndex < desired.size(); desiredIndex++)
        {
            T desiredItem = desired.get(desiredIndex);

            if(desiredIndex < current.size() && current.get(desiredIndex) == desiredItem)
            {
                continue;
            }

            int currentIndex = identityIndexOf(current, desiredItem, desiredIndex + 1);

            if(currentIndex >= 0)
            {
                current.remove(currentIndex);
            }

            current.add(desiredIndex, desiredItem);
        }

        while(current.size() > desired.size())
        {
            current.remove(current.size() - 1);
        }
    }

    private static <T> int identityIndexOf(List<T> values, T sought, int startIndex)
    {
        for(int index = Math.max(0, startIndex); index < values.size(); index++)
        {
            if(values.get(index) == sought)
            {
                return index;
            }
        }

        return -1;
    }

    private TreeItem<MonitorRow> decisionItem(LogicalCallDiagnosticDecision decision)
    {
        LogicalCallDiagnosticCallIdentity identity = decision.callIdentity();
        LogicalCallDiagnosticWinner winner = decision.winner();
        MonitorRow row = new MonitorRow(decision.decisionSequence(), formatTime(decision.decidedAtMs()),
            decisionResult(decision, identity),
            identityValue(identity != null ? identity.destinationAlias() : null,
                identity != null ? identity.destinationValue() : null),
            identityValue(identity != null ? identity.sourceAlias() : null,
                identity != null ? identity.sourceValue() : null),
            decisionSiteSummary(decision), decisionTiming(identity), decisionDetails(decision, winner),
            outputSummary(decision.outputPolicy()));
        TreeItem<MonitorRow> item = new TreeItem<>(row);

        for(LogicalCallDiagnosticLeg leg : decision.legs())
        {
            item.getChildren().add(new TreeItem<>(legRow(decision, leg)));
        }

        return item;
    }

    private MonitorRow legRow(LogicalCallDiagnosticDecision decision, LogicalCallDiagnosticLeg leg)
    {
        boolean aborted = decision.outcome() == LogicalCallDecisionOutcome.ABORTED;
        String state = aborted ? "Discarded copy" : leg.winner() ? "Selected copy" : "Other copy";

        if(leg.ingressLoss() || leg.audioTruncated())
        {
            state += " · damaged";
        }

        String quality = ONE_DECIMAL.format(leg.qualityPercent()) + "% usable · decoded " +
            leg.decodedFrameCount() + " · observed " + leg.observedFrameCount() + " · expected " +
            leg.expectedFrameCount() + " · usable frames " + leg.usableFrameCount() + " · missing " +
            leg.missingFrameCount() + " · concealed " +
            leg.concealedFrameCount() + " · missing/concealed rate " +
            formatRate(leg.missingAndConcealedRate()) + " · repeated " + leg.repeatedFrameCount() + " (" +
            formatRate(leg.repeatedFrameRate()) + ") · corrected-bit errors " + leg.fecErrorCount() + "/" +
            leg.fecProtectedBitCount() + " (" + formatRate(leg.normalizedFecErrorRate()) + ") · retained " +
            formatSamples(leg.retainedAudioSampleCount());
        String timing = formatDuration(leg.durationMilliseconds()) + " · " +
            formatTime(leg.startTimestamp()) + " - " + formatTime(leg.endTimestamp());
        String outputs = aborted ? "Discarded; no output" : leg.winner() ?
            outputSummary(decision.outputPolicy()) : "Not sent separately";
        String evidence = copyEvidence(decision, leg);
        String overlap = copyOverlap(decision, leg);

        if(leg.ingressLoss())
        {
            evidence += " · receiver input loss";
        }

        if(leg.audioTruncated())
        {
            evidence += " · audio was truncated";
        }

        String details = overlap != null ? overlap + " · " + quality + " · " + evidence :
            quality + " · " + evidence;
        return new MonitorRow(decision.decisionSequence(), "", state, "", "",
            copySite(leg), timing, details, outputs);
    }

    private static String decisionSiteSummary(LogicalCallDiagnosticDecision decision)
    {
        LogicalCallDiagnosticLeg winner = decision.legs().stream().filter(LogicalCallDiagnosticLeg::winner)
            .findFirst().orElse(null);
        LogicalCallDiagnosticCallIdentity identity = decision.callIdentity();
        List<String> values = new ArrayList<>();

        if(winner != null)
        {
            values.add("Selected: " + copySite(winner));
        }

        if(identity != null && identity.uniqueLearnedSiteCount() > 0)
        {
            values.add(identity.uniqueLearnedSiteCount() + " site" +
                (identity.uniqueLearnedSiteCount() == 1 ? "" : "s"));
        }

        return values.isEmpty() ? "No winner" : String.join("  |  ", values);
    }

    private static String decisionResult(LogicalCallDiagnosticDecision decision,
                                         LogicalCallDiagnosticCallIdentity identity)
    {
        int copies = decision.legs().size();
        String result = switch(decision.outcome())
        {
            case MERGED -> "Duplicate combined";
            case INDEPENDENT -> "Single call";
            case FAIL_OPEN -> "Uncertain · kept separate";
            case ABORTED -> "Discarded copy";
        };
        result += " · " + copies + " cop" + (copies == 1 ? "y" : "ies");
        CallEncryptionState encryptionState = identity != null ? identity.encryptionState() :
            CallEncryptionState.UNKNOWN;

        return switch(encryptionState)
        {
            case ENCRYPTED -> result + " · encrypted";
            case UNKNOWN -> result + " · encryption unknown";
            case CLEAR -> result;
        };
    }

    private static String decisionTiming(LogicalCallDiagnosticCallIdentity identity)
    {
        if(identity == null)
        {
            return "Unknown";
        }

        return formatDuration(identity.endTimestamp() - identity.startTimestamp()) + " · waited " +
            formatDuration(identity.resolutionWaitMilliseconds());
    }

    private static String winnerSummary(LogicalCallDiagnosticWinner winner)
    {
        if(winner == null)
        {
            return "No selected-copy details";
        }

        if(winner.criterion() == null || winner.criterion() == LogicalCallWinnerCriterion.SINGLE_LEG)
        {
            return "Only copy received";
        }

        if(winner.criterion() == LogicalCallWinnerCriterion.SITE_GUID ||
            winner.criterion() == LogicalCallWinnerCriterion.CHANNEL_CONFIGURATION_ID ||
            winner.criterion() == LogicalCallWinnerCriterion.CALL_LEG_ID)
        {
            return "Quality tied; stable receiver order selected the copy";
        }

        String values = winner.winnerValue() != null && winner.winnerValue().display() != null ?
            ": " + winner.winnerValue().display() : "";
        String runnerUp = winner.runnerUpValue() != null && winner.runnerUpValue().display() != null ?
            " vs " + winner.runnerUpValue().display() : "";
        return winnerCriterion(winner.criterion()) + values + runnerUp;
    }

    private static String decisionDetails(LogicalCallDiagnosticDecision decision, LogicalCallDiagnosticWinner winner)
    {
        LinkedHashSet<String> values = new LinkedHashSet<>();

        if(decision.outcome() == LogicalCallDecisionOutcome.MERGED)
        {
            LinkedHashSet<String> proofs = new LinkedHashSet<>();
            decision.evidence().mergeProofCounts().keySet().forEach(proof -> proofs.add(mergeProof(proof)));

            if(!proofs.isEmpty())
            {
                values.add("Matched by " + String.join(", ", proofs));
            }

            String overlap = decisionOverlapSummary(decision);

            if(overlap != null)
            {
                values.add(overlap);
            }

            values.add("Selected by " + winnerSummary(winner));
        }
        else if(decision.outcome() == LogicalCallDecisionOutcome.FAIL_OPEN)
        {
            decision.decisionReasons().stream().filter(LogicalCallSeparationReason::isFailOpen)
                .map(LogicalCallMonitor::separationReason).forEach(values::add);
        }
        else if(decision.outcome() == LogicalCallDecisionOutcome.ABORTED)
        {
            decision.decisionReasons().stream().map(LogicalCallMonitor::separationReason).forEach(values::add);
        }
        else
        {
            values.add("No matching copy found");
        }

        return values.isEmpty() ? "Kept separate because the match was uncertain" : String.join(" · ", values);
    }

    static String decisionOverlapSummary(LogicalCallDiagnosticDecision decision)
    {
        LogicalCallDiagnosticLeg leastCoveredCopy = null;
        LogicalCallDiagnosticOverlap leastCoverage = null;

        for(LogicalCallDiagnosticLeg leg : decision.legs())
        {
            if(leg.winner())
            {
                continue;
            }

            LogicalCallDiagnosticOverlap overlap = LogicalCallDiagnosticOverlap.forCopy(decision, leg).orElse(null);

            if(overlap != null && (leastCoverage == null ||
                overlap.selectedCopyCoveragePercent() < leastCoverage.selectedCopyCoveragePercent()))
            {
                leastCoverage = overlap;
                leastCoveredCopy = leg;
            }
        }

        if(leastCoverage == null || leastCoveredCopy == null)
        {
            return null;
        }

        return "Least shared coverage: " + copySite(leastCoveredCopy) + " · " +
            formatDuration(leastCoverage.overlapMilliseconds()) + " shared · " +
            formatPercent(leastCoverage.shorterCopyOverlapPercent()) + " of shorter copy · " +
            formatPercent(leastCoverage.selectedCopyCoveragePercent()) + " of selected copy";
    }

    static String copyOverlap(LogicalCallDiagnosticDecision decision, LogicalCallDiagnosticLeg leg)
    {
        LogicalCallDiagnosticOverlap overlap = LogicalCallDiagnosticOverlap.forCopy(decision, leg).orElse(null);

        if(overlap == null)
        {
            return null;
        }

        if(leg.winner())
        {
            return "Selected copy is the overlap reference";
        }

        return "Shared with selected: " + formatDuration(overlap.overlapMilliseconds()) + " · " +
            formatPercent(overlap.shorterCopyOverlapPercent()) + " of shorter copy · " +
            formatPercent(overlap.selectedCopyCoveragePercent()) + " of selected copy · " +
            formatSignedOffset("starts", overlap.startOffsetFromSelectedMilliseconds()) + " · " +
            formatSignedOffset("ends", overlap.endOffsetFromSelectedMilliseconds());
    }

    private static String formatSignedOffset(String event, long offsetMilliseconds)
    {
        if(offsetMilliseconds == 0L)
        {
            return event + " together";
        }

        long magnitude = offsetMilliseconds == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(offsetMilliseconds);
        return event + " " + formatDuration(magnitude) + (offsetMilliseconds > 0L ? " later" : " earlier");
    }

    private static String copyEvidence(LogicalCallDiagnosticDecision decision, LogicalCallDiagnosticLeg leg)
    {
        LinkedHashSet<String> values = new LinkedHashSet<>();

        if(decision.outcome() == LogicalCallDecisionOutcome.MERGED)
        {
            decision.evidence().mergeProofCounts().keySet().forEach(proof -> values.add(mergeProof(proof)));

            if(leg.winner())
            {
                values.add("selected as the best-quality copy");
            }
            else
            {
                values.add("confirmed duplicate; not sent separately");
            }
        }

        if(values.isEmpty())
        {
            decision.decisionReasons().forEach(reason -> values.add(separationReason(reason)));
        }

        return values.isEmpty() ? "Only copy received" : String.join(", ", values);
    }

    private static String outputSummary(LogicalCallDiagnosticOutputPolicy policy)
    {
        if(policy == null)
        {
            return "No output requested";
        }

        List<String> values = new ArrayList<>();

        if(policy.recordRequested())
        {
            values.add("record requested");
        }

        if(policy.streamRoutingKeyCount() > 0)
        {
            StringBuilder streams = new StringBuilder("stream requested: ")
                .append(policy.streamRoutingKeyCount());
            int shown = Math.min(5, policy.streamRoutingKeys().size());

            if(shown > 0)
            {
                streams.append(" [").append(String.join(", ", policy.streamRoutingKeys().subList(0, shown)));

                int remaining = Math.max(0, policy.streamRoutingKeyCount() - shown);

                if(remaining > 0)
                {
                    streams.append(", +").append(remaining);
                }

                streams.append(']');
            }

            values.add(streams.toString());
        }

        if(policy.browserOffered())
        {
            values.add("browser offered");
        }

        return values.isEmpty() ? "No output requested" : String.join("  |  ", values);
    }

    private static String copySite(LogicalCallDiagnosticLeg leg)
    {
        List<String> values = new ArrayList<>();

        String channelName = friendlyChannelName(leg.channelName());

        if(channelName != null)
        {
            values.add(channelName);
        }

        if(leg.rfss() != null && leg.site() != null)
        {
            values.add("RFSS " + leg.rfss() + " / Site " + leg.site());
        }

        if(values.isEmpty() && leg.wacn() != null && leg.system() != null)
        {
            values.add(String.format(Locale.ROOT, "System %05X-%03X", leg.wacn(), leg.system()));
        }

        if(values.isEmpty() && leg.decoder() != null && !leg.decoder().isBlank())
        {
            values.add(leg.decoder());
        }

        return values.isEmpty() ? "Unknown site" : String.join("  |  ", values);
    }

    private static String friendlyChannelName(String channelName)
    {
        if(channelName == null || channelName.isBlank())
        {
            return null;
        }

        String value = channelName.trim();
        return value.startsWith("T-") && value.length() > 2 ? value.substring(2) : value;
    }

    private void openDiagnosticDirectory()
    {
        LogicalCallDiagnosticService service = mDiagnosticService;
        Consumer<Path> consumer = mOpenDirectoryConsumer;

        if(service != null && consumer != null)
        {
            Path directory = service.diagnosticDirectory();

            if(directory != null)
            {
                consumer.accept(directory);
            }
        }
    }

    private static String identityValue(String alias, String value)
    {
        if(alias != null && !alias.isBlank() && value != null && !value.isBlank())
        {
            return alias + " (" + value + ")";
        }
        else if(alias != null && !alias.isBlank())
        {
            return alias;
        }
        else if(value != null && !value.isBlank())
        {
            return value;
        }

        return "Unknown";
    }

    private static String formatTime(long timestamp)
    {
        return timestamp > 0L ? TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp)) : "Unknown";
    }

    private static String formatDuration(long milliseconds)
    {
        long value = Math.max(0L, milliseconds);
        return value < 1_000L ? value + " ms" : ONE_DECIMAL.format(value / 1_000.0d) + " s";
    }

    private static String formatSamples(long samples)
    {
        if(samples < 1_000L)
        {
            return Long.toString(Math.max(0L, samples));
        }
        else if(samples < 1_000_000L)
        {
            return ONE_DECIMAL.format(samples / 1_000.0d) + "k samples";
        }

        return ONE_DECIMAL.format(samples / 1_000_000.0d) + "M samples";
    }

    private static String formatBytes(long bytes)
    {
        long value = Math.max(0L, bytes);

        if(value >= 1024L * 1024L)
        {
            return ONE_DECIMAL.format(value / (1024.0d * 1024.0d)) + " MiB";
        }
        else if(value >= 1024L)
        {
            return ONE_DECIMAL.format(value / 1024.0d) + " KiB";
        }

        return value + " B";
    }

    private static String formatRate(double rate)
    {
        return ONE_DECIMAL.format(Math.max(0.0d, rate) * 100.0d) + "%";
    }

    private static String formatPercent(double percent)
    {
        return ONE_DECIMAL.format(Math.max(0.0d, Math.min(100.0d, percent))) + "%";
    }

    private static String friendly(Enum<?> value)
    {
        if(value == null)
        {
            return "Unknown";
        }

        String normalized = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static String winnerCriterion(LogicalCallWinnerCriterion criterion)
    {
        return switch(criterion)
        {
            case SINGLE_LEG -> "only copy received";
            case MISSING_AND_CONCEALED_RATE -> "least missing or concealed audio";
            case USABLE_FRAME_COUNT -> "most usable voice frames";
            case REPEATED_FRAME_RATE -> "least repeated audio";
            case NORMALIZED_FEC_ERROR_RATE -> "lowest corrected-bit error rate";
            case INGRESS_LOSS_OR_AUDIO_TRUNCATION -> "undamaged audio";
            case RETAINED_AUDIO_SAMPLE_COUNT -> "most complete retained audio";
            case SITE_GUID, CHANNEL_CONFIGURATION_ID, CALL_LEG_ID -> "stable receiver order";
        };
    }

    private static String mergeProof(LogicalCallMergeProof proof)
    {
        return switch(proof)
        {
            case SHARED_VOICE_CONTENT -> "matching voice frames";
            case MATCHING_SOURCE_IDENTITY_FALLBACK -> "matching radio with strong overlap";
            case MATCHING_ENCRYPTION_MESSAGE_INDICATOR -> "matching encrypted-call marker";
        };
    }

    private static String separationReason(LogicalCallSeparationReason reason)
    {
        return switch(reason)
        {
            case NON_P25_RESOLUTION_NOT_APPLICABLE -> "Duplicate matching does not apply to this decoder";
            case MISSING_CALL_SOURCE -> "Call source was unavailable";
            case MISSING_DECODER_TYPE -> "Decoder identity was unavailable";
            case MISSING_DURABLE_ALIAS_LIST_ID -> "Alias-list identity was unavailable";
            case MISSING_LEARNED_SITE_IDENTITY -> "Learned site identity was unavailable";
            case MISSING_DESTINATION_IDENTITY -> "Talkgroup identity was unavailable";
            case MISSING_ENCRYPTION_STATE -> "Encryption state wasn't confirmed";
            case INVALID_CALL_TIMING -> "Call timing was invalid";
            case COHORT_CAPACITY -> "Too many possible copies were already waiting";
            case ALIAS_LIST_MISMATCH -> "Different alias lists";
            case WACN_MISMATCH -> "Different WACNs";
            case SYSTEM_ID_MISMATCH -> "Different systems";
            case DESTINATION_MISMATCH -> "Different talkgroups";
            case ENCRYPTION_STATE_MISMATCH -> "Different encryption states";
            case SOURCE_IDENTITY_MISMATCH -> "Different radios";
            case INSUFFICIENT_TIME_OVERLAP -> "Calls did not overlap enough";
            case INSUFFICIENT_DUPLICATE_PROOF -> "Not enough evidence to safely combine the copies";
            case NO_CANDIDATE_LEG -> "No possible matching copy was found";
            case INGRESS_COMPROMISED -> "Receiver input was incomplete";
            case ACTIVE_LEG_CAPACITY -> "Receiver-copy capacity was reached";
        };
    }

    private static Label sectionLabel(String text)
    {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static Label statusLabel()
    {
        Label label = new Label();
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static Label metricValue()
    {
        Label label = new Label("—");
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        return label;
    }

    private static VBox metric(String caption, Label value)
    {
        Label captionLabel = new Label(caption);
        captionLabel.setStyle("-fx-font-size: 10px; -fx-opacity: 0.72;");
        VBox metric = new VBox(1, captionLabel, value);
        metric.setMinWidth(105);
        metric.setPadding(new Insets(5, 8, 5, 8));
        metric.setStyle("-fx-border-color: -fx-box-border; -fx-border-radius: 3; " +
            "-fx-background-color: -fx-control-inner-background; -fx-background-radius: 3;");
        return metric;
    }

    private static void setStatus(Label label, String text, Color color)
    {
        label.setText(text);
        label.setTextFill(color);
    }

    private record MonitorRow(long decisionSequence, String time, String result, String destination, String source,
                              String site, String timing, String details, String outputs)
    {
    }
}
