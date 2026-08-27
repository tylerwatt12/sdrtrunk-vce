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
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticWinner;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallMergeProof;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallWinnerCriterion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
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
    void monitorUsesBoundedVisibleOnlyPollingAndTwoPanelCallDetails() throws Exception
    {
        String monitor = Files.readString(MONITOR);

        assertTrue(monitor.contains("MAXIMUM_VISIBLE_DECISIONS = 100"));
        assertTrue(monitor.contains("REFRESH_INTERVAL = Duration.seconds(1)"));
        assertTrue(monitor.contains("new TableView<>(mDecisionRows)"));
        assertTrue(monitor.contains("mDecisionTable.setFixedCellSize(28.0d)"),
            "The call list must keep compact, uniform rows");
        assertTrue(monitor.contains("new SplitPane(mDecisionTable, detailScrollPane)"));
        assertTrue(monitor.contains("splitPane.setOrientation(Orientation.VERTICAL)"));
        assertTrue(monitor.contains("call-matching-two-panel"));
        assertTrue(monitor.contains("call-matching-selected-details"));
        assertTrue(monitor.contains("call-matching-details-scroll"));
        assertTrue(monitor.contains("Select a call above to compare its receiver/site copies."));
        assertTrue(monitor.contains("mDecisionTable.getSelectionModel().selectedItemProperty().addListener"));
        assertTrue(monitor.contains("showDecisionDetails(selected != null ? selected.decision() : null)"));
        assertTrue(monitor.contains("decisionSequence == mDisplayedDecisionSequence"),
            "A refresh of the selected call must not rebuild and flash the details panel");
        assertTrue(monitor.contains("Receiver copy comparison"));
        assertTrue(monitor.contains("decisionComparisonGrid(decision)"));
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
        assertTrue(monitor.contains("mDecisionRowsBySequence.computeIfAbsent"));
        assertTrue(monitor.contains("reconcileStableItems(mDecisionRows, rows)"));
        assertTrue(monitor.contains("mSelectedDecisionSequence"));
        assertTrue(monitor.contains("mReconcilingRows"));
        assertTrue(monitor.contains("shorterCopyOverlapPercent()"));
        assertTrue(monitor.contains("selectedCopyCoveragePercent()"));
        assertTrue(monitor.contains("startOffsetFromSelectedMilliseconds()"));
        assertTrue(monitor.contains("endOffsetFromSelectedMilliseconds()"));
        assertTrue(monitor.contains("case UNKNOWN -> result + \" · encryption unknown\""));
        assertTrue(monitor.contains("column.setSortable(false)"));
        assertTrue(monitor.contains("addColumn(\"Match / Quality\""));
        assertTrue(monitor.contains("GridPane"));
        assertFalse(monitor.contains("TreeTableView"));
        assertFalse(monitor.contains("TreeTableColumn"));
        assertFalse(monitor.contains("TreeTableRow"));
        assertFalse(monitor.contains("TreeItem"));
        assertFalse(monitor.contains("isDisclosureNode"));
        assertFalse(monitor.contains("event.getClickCount()"));
        assertFalse(monitor.contains("MouseButton"));
        assertFalse(monitor.contains("mDecisionRoot"));
        assertFalse(monitor.contains("item.getChildren().add"));
        assertFalse(monitor.contains("addMatchQualityColumn("));
        assertFalse(monitor.contains("String quality = ONE_DECIMAL.format(leg.qualityPercent())"),
            "Quality must not return to one long, clipped sentence");
        assertFalse(monitor.contains("LogicalCallDiagnosticPairDecision"));
        assertFalse(monitor.contains("alias-list #"));
        assertFalse(monitor.contains("siteGuid()"));
        assertFalse(monitor.contains("Files."));
        assertFalse(monitor.contains("Database"));
        assertFalse(monitor.contains("HttpClient"));
    }

    @Test
    void structuredMatchQualityComparesTheLiveShapedWinnerAndRunnerUp()
    {
        LogicalCallDiagnosticLeg selected = qualityLeg("selected", "T-Cuyahoga Simulcast", 1, 1_000L,
            4_600L, 171L, 167L, 167L, 3L, 1L, 9L, 101L, 23_427L, 28_800L, true);
        LogicalCallDiagnosticLeg runnerUp = qualityLeg("runner", "T-Elyria", 2, 1_048L,
            4_590L, 171L, 164L, 164L, 7L, 0L, 9L, 704L, 23_427L, 28_640L, false);
        LogicalCallDiagnosticWinner winner = new LogicalCallDiagnosticWinner(selected.legId(), runnerUp.legId(),
            LogicalCallWinnerCriterion.USABLE_FRAME_COUNT,
            new LogicalCallDiagnosticWinner.CriterionValue("167/180 (92.778%)", 167L, 180L),
            new LogicalCallDiagnosticWinner.CriterionValue("164/180 (91.111%)", 164L, 180L));
        LogicalCallDiagnosticEvidence evidence = new LogicalCallDiagnosticEvidence(1L, 0L, 0L,
            Map.of(LogicalCallMergeProof.SHARED_VOICE_CONTENT, 1L), Map.of());
        LogicalCallDiagnosticDecision decision = new LogicalCallDiagnosticDecision(1L, 4_700L,
            new LogicalCallId(1L, 1L), LogicalCallDecisionOutcome.MERGED, null, null, winner,
            List.of(selected, runnerUp), evidence, List.of());

        var parent = LogicalCallMonitor.decisionMatchQuality(decision);
        assertEquals(List.of("Match", "Selected by", "Selected", "Runner-up"),
            parent.lines().stream().map(line -> line.label()).toList());
        assertTrue(parent.lines().get(0).value().contains("matching voice frames"));
        assertTrue(parent.lines().get(0).value().contains("3.5 s shared"));
        assertEquals("Most usable voice frames", parent.lines().get(1).value());
        assertTrue(parent.lines().get(2).value().contains("Cuyahoga Simulcast"));
        assertTrue(parent.lines().get(2).value().contains("167/180 (92.778%)"));
        assertTrue(parent.lines().get(3).value().contains("Elyria"));
        assertTrue(parent.lines().get(3).value().contains("164/180 (91.111%)"));

        var selectedDetails = LogicalCallMonitor.copyMatchQuality(decision, selected);
        var runnerDetails = LogicalCallMonitor.copyMatchQuality(decision, runnerUp);
        List<String> expectedLabels = List.of("Match", "Usable", "Observed / decoded",
            "Missing + concealed", "Repeated", "FEC", "Retained audio", "Damage");
        assertEquals(expectedLabels, selectedDetails.lines().stream().map(line -> line.label()).toList());
        assertEquals(expectedLabels, runnerDetails.lines().stream().map(line -> line.label()).toList());

        assertTrue(selectedDetails.lines().get(0).value().contains("Overlap reference"));
        assertTrue(runnerDetails.lines().get(0).value().contains("3.5 s shared"));
        assertTrue(runnerDetails.lines().get(0).value().contains("100.0% of shorter copy"));
        assertTrue(runnerDetails.lines().get(0).value().contains("98.4% of selected copy"));
        assertTrue(runnerDetails.lines().get(0).value().contains("starts 48 ms later"));
        assertTrue(runnerDetails.lines().get(0).value().contains("ends 10 ms earlier"));

        assertTrue(selectedDetails.lines().get(1).value().contains("167/180"));
        assertTrue(selectedDetails.lines().get(1).value().contains("92.8%"));
        assertTrue(runnerDetails.lines().get(1).value().contains("164/180"));
        assertTrue(runnerDetails.lines().get(1).value().contains("91.1%"));
        assertTrue(selectedDetails.lines().get(1).emphasized());
        assertTrue(runnerDetails.lines().get(1).emphasized());
        assertTrue(selectedDetails.lines().stream()
            .filter(line -> !"Usable".equals(line.label())).noneMatch(line -> line.emphasized()));
        assertTrue(runnerDetails.lines().stream()
            .filter(line -> !"Usable".equals(line.label())).noneMatch(line -> line.emphasized()));

        assertTrue(selectedDetails.lines().get(2).value().contains("171 observed"));
        assertTrue(selectedDetails.lines().get(2).value().contains("167 decoded"));
        assertTrue(runnerDetails.lines().get(2).value().contains("171 observed"));
        assertTrue(runnerDetails.lines().get(2).value().contains("164 decoded"));
        assertTrue(selectedDetails.lines().get(3).value().contains("10/180"));
        assertTrue(selectedDetails.lines().get(3).value().contains("5.6%"));
        assertTrue(selectedDetails.lines().get(3).value().contains("9 missing"));
        assertTrue(selectedDetails.lines().get(3).value().contains("1 concealed"));
        assertTrue(runnerDetails.lines().get(3).value().contains("9/180"));
        assertTrue(runnerDetails.lines().get(3).value().contains("5.0%"));
        assertTrue(runnerDetails.lines().get(3).value().contains("0 concealed"));
        assertTrue(selectedDetails.lines().get(4).value().contains("3/180"));
        assertTrue(selectedDetails.lines().get(4).value().contains("1.7%"));
        assertTrue(runnerDetails.lines().get(4).value().contains("7/180"));
        assertTrue(runnerDetails.lines().get(4).value().contains("3.9%"));
        assertTrue(selectedDetails.lines().get(5).value().contains("101/23,427"));
        assertTrue(selectedDetails.lines().get(5).value().contains("0.43%"));
        assertTrue(runnerDetails.lines().get(5).value().contains("704/23,427"));
        assertTrue(runnerDetails.lines().get(5).value().contains("3.01%"));
        assertTrue(selectedDetails.lines().get(6).value().contains("28.8k samples"));
        assertTrue(runnerDetails.lines().get(6).value().contains("28.6k samples"));
        assertEquals("None", selectedDetails.lines().get(7).value());
        assertEquals("None", runnerDetails.lines().get(7).value());
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
    void liveRefreshReusesFlatRowsAndPreservesSelectedIdentity()
    {
        Object newest = new Object();
        Object selected = new Object();
        Object oldest = new Object();
        ObservableList<Object> current = FXCollections.observableArrayList(selected, oldest);

        LogicalCallMonitor.reconcileStableItems(current, List.of(newest, selected));

        assertEquals(List.of(newest, selected), List.copyOf(current));
        assertTrue(current.get(1) == selected, "The selected flat row must keep object identity");

        Object later = new Object();
        LogicalCallMonitor.reconcileStableItems(current, List.of(later, newest, selected));

        assertTrue(current.get(2) == selected, "The same selected row must survive another live insert");
    }

    @Test
    void comparisonOrdersWinnerRunnerUpAndRemainingCopies()
    {
        LogicalCallDiagnosticLeg selected = leg("selected", "T-Cuyahoga Simulcast", 1_000L, 5_000L, true);
        LogicalCallDiagnosticLeg runnerUp = leg("runner", "T-Elyria", 1_020L, 4_980L, false);
        LogicalCallDiagnosticLeg firstOther = leg("first-other", "T-Lake", 1_030L, 4_970L, false);
        LogicalCallDiagnosticLeg secondOther = leg("second-other", "T-Geauga", 1_040L, 4_960L, false);
        LogicalCallDiagnosticWinner winner = new LogicalCallDiagnosticWinner(selected.legId(), runnerUp.legId(),
            LogicalCallWinnerCriterion.USABLE_FRAME_COUNT,
            LogicalCallDiagnosticWinner.CriterionValue.empty(),
            LogicalCallDiagnosticWinner.CriterionValue.empty());
        LogicalCallDiagnosticDecision decision = new LogicalCallDiagnosticDecision(1L, 5_100L,
            new LogicalCallId(1L, 1L), LogicalCallDecisionOutcome.MERGED, null, null, winner,
            List.of(firstOther, runnerUp, selected, secondOther), LogicalCallDiagnosticEvidence.EMPTY, List.of());

        assertEquals(List.of("selected", "runner", "first-other", "second-other"),
            LogicalCallMonitor.orderedCopies(decision).stream().map(LogicalCallDiagnosticLeg::legId).toList());
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
        int siteMetadataDrain = application.indexOf("channelProcessingManager.awaitSiteMetadataDrain(");
        int coordinatorStop = application.indexOf("mAudioCallCoordinator.disposeAndAwait(");
        int streamingStop = application.indexOf("mAudioStreamingManager.stop();");
        int recordingStop = application.indexOf("mAudioRecordingManager.stop();");
        int statisticsStop = application.indexOf("mP25ActivityLogService.disposeAndAwait(");
        int serviceClose = application.indexOf("mLogicalCallDiagnosticService.close();");

        assertTrue(serviceCreation >= 0 && serviceCreation < coordinatorCreation);
        assertTrue(application.contains("LogicalCallDiagnosticOutputType.RECORDED"));
        assertTrue(application.contains("LogicalCallDiagnosticOutputType.STREAM_SUBMITTED"));
        assertTrue(application.contains("path -> EventQueue.invokeLater(() -> openFileExplorer(path.toFile()))"));
        assertTrue(streamingStop >= 0 && streamingStop < serviceClose);
        assertTrue(recordingStop >= 0 && recordingStop < serviceClose);
        assertTrue(recordingStop < statisticsStop && statisticsStop < serviceClose,
            "Output managers must drain before statistics and diagnostic sinks close");
        assertTrue(siteMetadataDrain >= 0 && siteMetadataDrain < coordinatorStop);
        assertTrue(coordinatorStop >= 0 && coordinatorStop < recordingStop && coordinatorStop < statisticsStop,
            "Every asynchronous statistics producer must stop before the observation barrier");
        assertTrue(application.contains("databaseBoundarySafe &= siteMetadataDrained;"));
        assertTrue(application.contains("databaseBoundarySafe &= callCoordinatorStopped;"));
        assertTrue(application.contains("if(!statisticsDrained || !statisticsStopped)"),
            "Database replacement must not proceed until statistics observations and workers both stop");
        assertTrue(application.contains("if(!releaseDataRootLock && !databaseBoundarySafe)"));
        assertTrue(application.contains("if(releaseDataRootLock && databaseBoundarySafe)"),
            "A failed lifecycle fence must retain the portable-data lock until process termination");
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

    private static LogicalCallDiagnosticLeg qualityLeg(String id, String channelName, int site, long start,
                                                        long end, long observed, long usable, long decoded,
                                                        long repeated, long concealed, long missing, long fecErrors,
                                                        long fecProtectedBits, long retainedSamples, boolean selected)
    {
        long expected = 180L;
        return new LogicalCallDiagnosticLeg(id, "P25P2", "channel-" + id, channelName, "site-guid-" + id, 42L,
            0xBEE00, 0x123, 1, site, start, end, Math.max(0L, end - start), expected, observed, usable, decoded,
            repeated, concealed, missing, fecErrors, fecProtectedBits, 100.0d * usable / expected,
            (double)(missing + concealed) / expected, (double)repeated / expected,
            (double)fecErrors / fecProtectedBits, retainedSamples, false, false, selected);
    }
}
