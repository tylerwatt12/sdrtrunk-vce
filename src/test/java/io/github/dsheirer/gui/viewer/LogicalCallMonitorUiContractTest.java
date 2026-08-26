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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDecisionOutcome;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticDecision;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticEvidence;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticLeg;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;

class LogicalCallMonitorUiContractTest
{
    private static final Path MONITOR =
        Path.of("src/main/java/io/github/dsheirer/gui/viewer/LogicalCallMonitor.java");
    private static final Path WINDOW_MANAGER =
        Path.of("src/main/java/io/github/dsheirer/gui/JavaFxWindowManager.java");
    private static final Path APPLICATION =
        Path.of("src/main/java/io/github/dsheirer/gui/SDRTrunk.java");

    @Test
    void monitorUsesBoundedVisibleOnlyPollingAndExpandableCopies() throws Exception
    {
        String monitor = Files.readString(MONITOR);

        assertTrue(monitor.contains("MAXIMUM_VISIBLE_DECISIONS = 100"));
        assertTrue(monitor.contains("REFRESH_INTERVAL = Duration.seconds(1)"));
        assertTrue(monitor.contains("new TreeTableView<>(mDecisionRoot)"));
        assertTrue(monitor.contains("mRefreshTimeline.playFromStart()"));
        assertTrue(monitor.contains("mRefreshTimeline.stop()"));
        assertTrue(monitor.contains("service.snapshot()"));
        assertTrue(monitor.contains("coordinator.getDiagnosticSnapshot()"));
        assertTrue(monitor.contains("coordinator.getQueueStatus()"));
        assertTrue(monitor.contains("status.recordedConfirmationsObserved()"));
        assertTrue(monitor.contains("status.streamSubmittedConfirmationsObserved()"));
        assertTrue(monitor.contains("Show only duplicates"));
        assertTrue(monitor.contains("Selected copy"));
        assertTrue(monitor.contains("Other copy"));
        assertTrue(monitor.contains("Discarded copy"));
        assertTrue(monitor.contains("channelName()"));
        assertTrue(monitor.contains("missingAndConcealedRate()"));
        assertTrue(monitor.contains("normalizedFecErrorRate()"));
        assertTrue(monitor.contains("fecProtectedBitCount()"));
        assertTrue(monitor.contains("retainedAudioSampleCount()"));
        assertTrue(monitor.contains("call-matching-technical-health"));
        assertTrue(monitor.contains("Discarded; no output"));
        assertTrue(monitor.contains("mDecisionItemsBySequence.computeIfAbsent"));
        assertTrue(monitor.contains("reconcileStableItems(mDecisionRoot.getChildren(), items)"));
        assertTrue(monitor.contains("mDecisionTable.setTreeColumn(callColumn)"));
        assertTrue(monitor.contains("event.getClickCount() == 2"));
        assertTrue(monitor.contains("!isDisclosureNode(event.getTarget())"));
        assertTrue(monitor.contains("shorterCopyOverlapPercent()"));
        assertTrue(monitor.contains("selectedCopyCoveragePercent()"));
        assertTrue(monitor.contains("startOffsetFromSelectedMilliseconds()"));
        assertTrue(monitor.contains("endOffsetFromSelectedMilliseconds()"));
        assertTrue(monitor.contains("case UNKNOWN -> result + \" · encryption unknown\""));
        assertTrue(monitor.contains("column.setSortable(false)"));
        assertFalse(monitor.contains("mDecisionRoot.getChildren().setAll(items)"));
        assertFalse(monitor.contains("LogicalCallDiagnosticPairDecision"));
        assertFalse(monitor.contains("alias-list #"));
        assertFalse(monitor.contains("siteGuid()"));
        assertFalse(monitor.contains("Files."));
        assertFalse(monitor.contains("Database"));
        assertFalse(monitor.contains("HttpClient"));
    }

    @Test
    void overlapPresentationExplainsLateEntryAgainstFriendlySelectedSite()
    {
        LogicalCallDiagnosticLeg selected = leg("selected", "T-Cuyahoga Simulcast", 1_000L, 6_220L, true);
        LogicalCallDiagnosticLeg lateCopy = leg("late", "T-Elyria", 5_278L, 6_179L, false);
        LogicalCallDiagnosticDecision merged = decision(1L, LogicalCallDecisionOutcome.MERGED,
            List.of(selected, lateCopy));

        String parent = LogicalCallMonitor.decisionOverlapSummary(merged);
        String expanded = LogicalCallMonitor.copyOverlap(merged, lateCopy);

        assertTrue(parent.contains("Elyria"));
        assertFalse(parent.contains("site-guid"));
        assertTrue(parent.contains("901 ms shared"));
        assertTrue(parent.contains("100.0% of shorter copy"));
        assertTrue(parent.contains("17.3% of selected copy"));
        assertTrue(expanded.contains("Shared with selected: 901 ms"));
        assertTrue(expanded.contains("starts 4.3 s later"));
        assertTrue(expanded.contains("ends 41 ms earlier"));
        assertEquals("Selected copy is the overlap reference", LogicalCallMonitor.copyOverlap(merged, selected));

        LogicalCallDiagnosticDecision independent = decision(2L, LogicalCallDecisionOutcome.INDEPENDENT,
            List.of(selected));
        assertNull(LogicalCallMonitor.copyOverlap(independent, selected));
        assertNull(LogicalCallMonitor.decisionOverlapSummary(independent));
    }

    @Test
    void duplicateFilterRunsBeforeTheVisibleRowLimit()
    {
        List<LogicalCallDiagnosticDecision> decisions = new ArrayList<>();
        decisions.add(decision(1, LogicalCallDecisionOutcome.MERGED));

        for(int sequence = 2; sequence <= 151; sequence++)
        {
            decisions.add(decision(sequence, LogicalCallDecisionOutcome.INDEPENDENT));
        }

        decisions.add(decision(152, LogicalCallDecisionOutcome.FAIL_OPEN));
        decisions.add(decision(153, LogicalCallDecisionOutcome.ABORTED));
        decisions.add(decision(154, LogicalCallDecisionOutcome.MERGED));

        List<LogicalCallDiagnosticDecision> unfiltered =
            LogicalCallMonitor.visibleDecisions(decisions, false, 100);
        List<LogicalCallDiagnosticDecision> duplicates =
            LogicalCallMonitor.visibleDecisions(decisions, true, 100);

        assertEquals(100, unfiltered.size());
        assertFalse(unfiltered.stream().anyMatch(decision -> decision.decisionSequence() == 1));
        assertEquals(List.of(154L, 1L),
            duplicates.stream().map(LogicalCallDiagnosticDecision::decisionSequence).toList());
    }

    @Test
    void liveRefreshReusesTreeItemsAndPreservesExpansion()
    {
        TreeItem<String> newest = new TreeItem<>("newest");
        TreeItem<String> expanded = new TreeItem<>("expanded");
        TreeItem<String> oldest = new TreeItem<>("oldest");
        expanded.getChildren().add(new TreeItem<>("copy"));
        expanded.setExpanded(true);
        ObservableList<TreeItem<String>> current = FXCollections.observableArrayList(expanded, oldest);

        LogicalCallMonitor.reconcileStableItems(current, List.of(newest, expanded));

        assertEquals(List.of(newest, expanded), List.copyOf(current));
        assertTrue(current.get(1) == expanded, "The existing parent must keep object identity");
        assertTrue(current.get(1).isExpanded(), "A live insert must not collapse an examined call");
        assertEquals(1, current.get(1).getChildren().size(), "Child rows must not be rebuilt or duplicated");

        expanded.setExpanded(false);
        TreeItem<String> later = new TreeItem<>("later");
        LogicalCallMonitor.reconcileStableItems(current, List.of(later, newest, expanded));

        assertTrue(current.get(2) == expanded, "The same parent must survive another live insert");
        assertFalse(current.get(2).isExpanded(), "A deliberately collapsed call must stay collapsed");
    }

    @Test
    void filteredRefreshMovesExistingItemsWithoutRecreatingThem()
    {
        Object single = new Object();
        Object firstDuplicate = new Object();
        Object secondDuplicate = new Object();
        ObservableList<Object> current =
            FXCollections.observableArrayList(single, firstDuplicate, secondDuplicate);
        List<Object> removed = new ArrayList<>();
        current.addListener((ListChangeListener<Object>)change -> {
            while(change.next())
            {
                removed.addAll(change.getRemoved());
            }
        });

        LogicalCallMonitor.reconcileStableItems(current, List.of(firstDuplicate, secondDuplicate));

        assertEquals(2, current.size());
        assertTrue(current.get(0) == firstDuplicate);
        assertTrue(current.get(1) == secondDuplicate);
        assertTrue(removed.stream().noneMatch(item -> item == firstDuplicate || item == secondDuplicate),
            "Retained rows must never be detached while a filter is reconciled");
    }

    @Test
    void windowIsTemporaryAndRefreshFollowsVisibility() throws Exception
    {
        String manager = Files.readString(WINDOW_MANAGER);
        String stage = block(manager, "public Stage getLogicalCallMonitorStage()",
            "public LogicalCallMonitor getLogicalCallMonitor()");

        assertTrue(stage.contains("setOnShown(event -> getLogicalCallMonitor().activate())"));
        assertTrue(stage.contains("setOnHidden(event -> getLogicalCallMonitor().deactivate())"));
        assertFalse(stage.contains("getJavaFxPreferences().monitor("));
        assertTrue(manager.contains("process(final ViewLogicalCallMonitorRequest request)"));
    }

    @Test
    void applicationWiresNonblockingOutputsAndClosesAfterTheyDrain() throws Exception
    {
        String application = Files.readString(APPLICATION);
        int serviceCreation = application.indexOf("new LogicalCallDiagnosticService(");
        int coordinatorCreation = application.indexOf("new AudioCallCoordinator(");
        int streamingStop = application.indexOf("mAudioStreamingManager.stop();");
        int recordingStop = application.indexOf("mAudioRecordingManager.stop();");
        int statisticsStop = application.indexOf("mP25ActivityLogService.dispose();");
        int serviceClose = application.indexOf("mLogicalCallDiagnosticService.close();");

        assertTrue(serviceCreation >= 0 && serviceCreation < coordinatorCreation);
        assertTrue(application.contains("LogicalCallDiagnosticOutputType.RECORDED"));
        assertTrue(application.contains("LogicalCallDiagnosticOutputType.STREAM_SUBMITTED"));
        assertTrue(application.contains("path -> EventQueue.invokeLater(() -> openFileExplorer(path.toFile()))"));
        assertTrue(streamingStop >= 0 && streamingStop < serviceClose);
        assertTrue(recordingStop >= 0 && recordingStop < serviceClose);
        assertTrue(recordingStop < statisticsStop && statisticsStop < serviceClose,
            "Output managers must drain before statistics and diagnostic sinks close");
        assertTrue(application.contains("new ViewLogicalCallMonitorRequest()"));
        assertTrue(application.contains("new JButton(\"Call Monitor\""));
        assertTrue(application.contains("getCallMatchingMonitorShortcutButton()"));
        assertTrue(application.contains("\"[][][][][grow,fill]\""),
            "The trailing spacer, not the Web button, must absorb toolbar width");
        assertTrue(application.contains("KeyStroke.getKeyStroke(KeyEvent.VK_M, ActionEvent.ALT_MASK)"));
    }

    private static String block(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, startMarker);
        assertTrue(end > start, endMarker);
        return source.substring(start, end);
    }

    private static LogicalCallDiagnosticDecision decision(long sequence, LogicalCallDecisionOutcome outcome)
    {
        return decision(sequence, outcome, List.of());
    }

    private static LogicalCallDiagnosticDecision decision(long sequence, LogicalCallDecisionOutcome outcome,
                                                            List<LogicalCallDiagnosticLeg> legs)
    {
        return new LogicalCallDiagnosticDecision(sequence, sequence, new LogicalCallId(1, sequence), outcome,
            null, null, null, legs, LogicalCallDiagnosticEvidence.EMPTY, List.of());
    }

    private static LogicalCallDiagnosticLeg leg(String id, String channelName, long start, long end,
                                                 boolean selected)
    {
        return new LogicalCallDiagnosticLeg(id, "P25P2", "channel-" + id, channelName, "site-guid-" + id, 42L,
            0xBEE00, 0x123, 1, 2, start, end, Math.max(0L, end - start), 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            0L, 0L, 0.0d, 0.0d, 0.0d, 0.0d, 0L, false, false, selected);
    }
}
