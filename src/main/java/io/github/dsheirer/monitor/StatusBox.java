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

package io.github.dsheirer.monitor;

import io.github.dsheirer.application.update.UpdateCheckResult;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModuleManager;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.preference.encryption.ViewEncryptionKeyPreferenceEditorRequest;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultState;
import io.github.dsheirer.stats.StatsWebNavigationState;
import java.util.Objects;
import java.net.URI;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;

/**
 * Compact JavaFX status footer. Each item has a fixed width so changing values cannot shift adjacent items.
 */
public class StatusBox extends HBox
{
    private static final double FOOTER_HEIGHT = 24;
    private static final double METER_WIDTH = 56;
    private static final double METER_HEIGHT = 10;
    private static final double CELL_HORIZONTAL_PADDING = 8;
    private static final double CPU_CELL_WIDTH = 124;
    private static final double METER_CELL_WIDTH = 94;
    private static final double STORAGE_CELL_WIDTH = 134;
    private static final double DATABASE_CELL_WIDTH = 76;
    private static final double STATS_CELL_WIDTH = 76;
    private static final double HISTORY_CELL_WIDTH = 88;
    private static final double WEB_CELL_WIDTH = 88;
    private static final double VAULT_CELL_WIDTH = 80;
    private static final double UPDATE_CELL_WIDTH = 30;
    private static final Color ACTIVE_COLOR = Color.FORESTGREEN;
    private static final Color INACTIVE_COLOR = Color.DIMGRAY;
    private final ResourceMonitor mResourceMonitor;
    private final EncryptionKeyVaultService mVaultService;
    private final VoiceDecryptionModuleManager mModuleManager;
    private final Supplier<StatsWebNavigationState> mNavigationStateSupplier;
    private final Supplier<UpdateCheckResult> mUpdateResultSupplier;
    private final Consumer<URI> mUpdateReleasePageConsumer;
    private HBox mVaultStatusBox;
    private Tooltip mVaultTooltip;
    private Label mStatsStatusLabel;
    private Label mHistoryStatusLabel;
    private Label mWebStatusLabel;
    private Tooltip mStatsStatusTooltip;
    private Tooltip mHistoryStatusTooltip;
    private Tooltip mWebStatusTooltip;
    private StatsWebNavigationState mLastNavigationState;
    private boolean mNavigationStatusInitialized;
    private Timeline mNavigationStatusTimeline;
    private HBox mUpdateStatusBox;
    private Separator mUpdateStatusSeparator;
    private Tooltip mUpdateStatusTooltip;
    private UpdateCheckResult mLastUpdateResult;

    /**
     * Constructs an instance.
     * @param resourceMonitor for accessing resource usage statistics.
     */
    public StatusBox(ResourceMonitor resourceMonitor)
    {
        this(resourceMonitor, null, null, null, null, null);
    }

    /**
     * Constructs an instance.
     * @param resourceMonitor for accessing resource usage statistics.
     * @param vaultService for displaying encryption vault status.
     */
    public StatusBox(ResourceMonitor resourceMonitor, EncryptionKeyVaultService vaultService)
    {
        this(resourceMonitor, vaultService, null, null, null, null);
    }

    public StatusBox(ResourceMonitor resourceMonitor, EncryptionKeyVaultService vaultService,
                     VoiceDecryptionModuleManager moduleManager)
    {
        this(resourceMonitor, vaultService, moduleManager, null, null, null);
    }

    public StatusBox(ResourceMonitor resourceMonitor, EncryptionKeyVaultService vaultService,
                     VoiceDecryptionModuleManager moduleManager,
                     Supplier<StatsWebNavigationState> navigationStateSupplier,
                     Supplier<UpdateCheckResult> updateResultSupplier,
                     Consumer<URI> updateReleasePageConsumer)
    {
        mResourceMonitor = resourceMonitor;
        mVaultService = vaultService;
        mModuleManager = moduleManager;
        mNavigationStateSupplier = navigationStateSupplier;
        mUpdateResultSupplier = updateResultSupplier;
        mUpdateReleasePageConsumer = updateReleasePageConsumer;
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(1, 4, 1, 4));
        setSpacing(0);
        setMinHeight(FOOTER_HEIGHT);
        setPrefHeight(FOOTER_HEIGHT);
        setMaxHeight(FOOTER_HEIGHT);
        addResourceStatusCells();

        if(mNavigationStateSupplier != null)
        {
            addNavigationStatusCells();
        }

        if(mVaultService != null)
        {
            addVaultStatusCell();
        }

        if(mUpdateResultSupplier != null)
        {
            addUpdateStatusCell();
        }

        if(mNavigationStateSupplier != null || mUpdateResultSupplier != null)
        {
            mNavigationStatusTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateDynamicStatus()));
            mNavigationStatusTimeline.setCycleCount(Animation.INDEFINITE);
            mNavigationStatusTimeline.play();
        }
    }

    private void addResourceStatusCells()
    {
        HBox cpuCell = createCell(CPU_CELL_WIDTH);
        cpuCell.getChildren().add(fixedLabel("CPU", 26, Pos.CENTER_LEFT));
        ProgressBar cpuIndicator = fixedMeter();
        cpuIndicator.progressProperty().bind(mResourceMonitor.cpuPercentageProperty());
        cpuIndicator.disableProperty().bind(mResourceMonitor.cpuAvailableProperty().not());
        cpuIndicator.setTooltip(new Tooltip("Java process CPU usage"));
        cpuCell.getChildren().add(cpuIndicator);
        Label cpuValueLabel = fixedLabel(null, 30, Pos.CENTER_RIGHT);
        cpuValueLabel.textProperty().bind(mResourceMonitor.cpuLabelProperty());
        cpuCell.getChildren().add(cpuValueLabel);
        addCell(cpuCell);

        HBox allocatedCell = createCell(METER_CELL_WIDTH);
        allocatedCell.getChildren().add(fixedLabel("Alloc", 28, Pos.CENTER_LEFT));
        ProgressBar memoryBar = fixedMeter();
        memoryBar.progressProperty().bind(mResourceMonitor.systemMemoryUsedPercentageProperty());
        Tooltip memoryTooltip = new Tooltip();
        memoryTooltip.textProperty().bind(mResourceMonitor.memoryAllocatedLabelProperty()
            .concat(" JVM heap committed out of max heap"));
        memoryBar.setTooltip(memoryTooltip);
        allocatedCell.getChildren().add(memoryBar);
        addCell(allocatedCell);

        HBox heapCell = createCell(METER_CELL_WIDTH);
        heapCell.getChildren().add(fixedLabel("Heap", 28, Pos.CENTER_LEFT));
        ProgressBar javaMemoryBar = fixedMeter();
        javaMemoryBar.progressProperty().bind(mResourceMonitor.javaMemoryUsedPercentageProperty());
        Tooltip javaMemoryTooltip = new Tooltip();
        javaMemoryTooltip.textProperty().bind(mResourceMonitor.memoryUsedLabelProperty()
            .concat(" JVM heap used out of committed heap"));
        javaMemoryBar.setTooltip(javaMemoryTooltip);
        heapCell.getChildren().add(javaMemoryBar);
        addCell(heapCell);

        HBox eventLogsCell = createCell(STORAGE_CELL_WIDTH);
        eventLogsCell.getChildren().add(fixedLabel("Logs", 24, Pos.CENTER_LEFT));
        ProgressBar eventLogsBar = fixedMeter();
        eventLogsBar.progressProperty().bind(mResourceMonitor.directoryUsePercentEventLogsProperty());
        eventLogsBar.setTooltip(new Tooltip("Event-log storage usage relative to the configured limit"));
        eventLogsCell.getChildren().add(eventLogsBar);
        Label eventLogsSizeLabel = fixedLabel(null, 42, Pos.CENTER_RIGHT);
        eventLogsSizeLabel.textProperty().bind(mResourceMonitor.fileSizeEventLogsProperty());
        eventLogsCell.getChildren().add(eventLogsSizeLabel);
        addCell(eventLogsCell);

        HBox recordingsCell = createCell(STORAGE_CELL_WIDTH);
        recordingsCell.getChildren().add(fixedLabel("Rec", 24, Pos.CENTER_LEFT));
        ProgressBar recordingsBar = fixedMeter();
        recordingsBar.progressProperty().bind(mResourceMonitor.directoryUsePercentRecordingsProperty());
        recordingsBar.setTooltip(new Tooltip("Recording storage usage relative to the configured limit"));
        recordingsCell.getChildren().add(recordingsBar);
        Label recordingsSizeLabel = fixedLabel(null, 42, Pos.CENTER_RIGHT);
        recordingsSizeLabel.textProperty().bind(mResourceMonitor.fileSizeRecordingsProperty());
        recordingsCell.getChildren().add(recordingsSizeLabel);
        addCell(recordingsCell);

        HBox databaseCell = createCell(DATABASE_CELL_WIDTH);
        databaseCell.getChildren().add(fixedLabel("DB", 18, Pos.CENTER_LEFT));
        Label databaseSizeLabel = fixedLabel(null, 48, Pos.CENTER_RIGHT);
        databaseSizeLabel.textProperty().bind(mResourceMonitor.fileSizeDatabaseProperty());
        databaseSizeLabel.setTooltip(new Tooltip("SQLite database size including WAL and shared-memory side files"));
        databaseCell.getChildren().add(databaseSizeLabel);
        addCell(databaseCell);
    }

    private void addNavigationStatusCells()
    {
        HBox statsCell = createCell(STATS_CELL_WIDTH);
        mStatsStatusLabel = fixedLabel(null, STATS_CELL_WIDTH - CELL_HORIZONTAL_PADDING, Pos.CENTER_LEFT);
        mStatsStatusTooltip = new Tooltip();
        mStatsStatusLabel.setTooltip(mStatsStatusTooltip);
        statsCell.getChildren().add(mStatsStatusLabel);
        addCell(statsCell);

        HBox historyCell = createCell(HISTORY_CELL_WIDTH);
        mHistoryStatusLabel = fixedLabel(null, HISTORY_CELL_WIDTH - CELL_HORIZONTAL_PADDING, Pos.CENTER_LEFT);
        mHistoryStatusTooltip = new Tooltip();
        mHistoryStatusLabel.setTooltip(mHistoryStatusTooltip);
        historyCell.getChildren().add(mHistoryStatusLabel);
        addCell(historyCell);

        HBox webCell = createCell(WEB_CELL_WIDTH);
        mWebStatusLabel = fixedLabel(null, WEB_CELL_WIDTH - CELL_HORIZONTAL_PADDING, Pos.CENTER_LEFT);
        mWebStatusTooltip = new Tooltip();
        mWebStatusLabel.setTooltip(mWebStatusTooltip);
        webCell.getChildren().add(mWebStatusLabel);
        addCell(webCell);

        updateNavigationStatus();
    }

    private void updateDynamicStatus()
    {
        if(mNavigationStateSupplier != null)
        {
            updateNavigationStatus();
        }

        if(mUpdateResultSupplier != null)
        {
            updateUpdateStatus();
        }
    }

    private void addUpdateStatusCell()
    {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);
        mUpdateStatusSeparator = createSeparator();
        getChildren().add(mUpdateStatusSeparator);
        mUpdateStatusBox = createCell(UPDATE_CELL_WIDTH);
        mUpdateStatusBox.setAlignment(Pos.CENTER);
        IconNode updateIcon = new IconNode(FontAwesome.DOWNLOAD);
        updateIcon.setIconSize(14);
        updateIcon.setFill(Color.DARKORANGE);
        mUpdateStatusBox.getChildren().add(updateIcon);
        mUpdateStatusTooltip = new Tooltip();
        Tooltip.install(mUpdateStatusBox, mUpdateStatusTooltip);
        mUpdateStatusBox.setOnMouseClicked(event -> {
            UpdateCheckResult result = mUpdateResultSupplier.get();

            if(result != null && result.isUpdateAvailable() && mUpdateReleasePageConsumer != null)
            {
                mUpdateReleasePageConsumer.accept(result.releaseUri());
            }
        });
        setUpdateStatusVisible(false);
        updateUpdateStatus();
    }

    private void updateUpdateStatus()
    {
        UpdateCheckResult result = mUpdateResultSupplier.get();

        if(Objects.equals(result, mLastUpdateResult))
        {
            return;
        }

        mLastUpdateResult = result;
        boolean available = result != null && result.isUpdateAvailable();
        setUpdateStatusVisible(available);

        if(available)
        {
            mUpdateStatusTooltip.setText(result.track() + " update " + result.version() +
                " is available. Click to open the release page.");
        }
    }

    private void setUpdateStatusVisible(boolean visible)
    {
        mUpdateStatusSeparator.setVisible(visible);
        mUpdateStatusSeparator.setManaged(visible);
        mUpdateStatusBox.setVisible(visible);
        mUpdateStatusBox.setManaged(visible);
    }

    private void addVaultStatusCell()
    {
        Separator separator = createSeparator();
        getChildren().add(separator);
        getChildren().add(getVaultStatusBox());
        boolean moduleLoaded = mModuleManager == null || mModuleManager.isLoaded();
        separator.setVisible(moduleLoaded);
        separator.setManaged(moduleLoaded);
        mVaultStatusBox.setVisible(moduleLoaded);
        mVaultStatusBox.setManaged(moduleLoaded);

        if(mModuleManager != null)
        {
            mModuleManager.loadedProperty().addListener((observable, oldValue, loaded) -> {
                separator.setVisible(loaded);
                separator.setManaged(loaded);
                mVaultStatusBox.setVisible(loaded);
                mVaultStatusBox.setManaged(loaded);
            });
        }

        mVaultService.stateProperty().addListener((observable, oldValue, newValue) -> updateVaultStatus());
        mVaultService.statusProperty().addListener((observable, oldValue, newValue) -> updateVaultStatus());
        mVaultService.savedPasswordPresentProperty().addListener((observable, oldValue, newValue) -> updateVaultStatus());
        updateVaultStatus();
    }

    private HBox createCell(double width)
    {
        HBox cell = new HBox(2);
        cell.setAlignment(Pos.CENTER_LEFT);
        cell.setPadding(new Insets(0, 4, 0, 4));
        cell.setMinWidth(width);
        cell.setPrefWidth(width);
        cell.setMaxWidth(width);
        cell.setMinHeight(FOOTER_HEIGHT - 2);
        cell.setPrefHeight(FOOTER_HEIGHT - 2);
        cell.setMaxHeight(FOOTER_HEIGHT - 2);
        return cell;
    }

    private void addCell(HBox cell)
    {
        if(!getChildren().isEmpty())
        {
            getChildren().add(createSeparator());
        }

        getChildren().add(cell);
    }

    private static Separator createSeparator()
    {
        Separator separator = new Separator(Orientation.VERTICAL);
        separator.setMinWidth(2);
        separator.setPrefWidth(2);
        separator.setMaxWidth(2);
        return separator;
    }

    private static Label fixedLabel(String text, double width, Pos alignment)
    {
        Label label = new Label(text);
        label.setAlignment(alignment);
        label.setTextOverrun(OverrunStyle.CLIP);
        label.setMinWidth(width);
        label.setPrefWidth(width);
        label.setMaxWidth(width);
        return label;
    }

    private static ProgressBar fixedMeter()
    {
        ProgressBar meter = new ProgressBar();
        meter.setMinWidth(METER_WIDTH);
        meter.setPrefWidth(METER_WIDTH);
        meter.setMaxWidth(METER_WIDTH);
        meter.setStyle("-fx-accent: #2f7d73;");
        meter.setMinHeight(METER_HEIGHT);
        meter.setPrefHeight(METER_HEIGHT);
        meter.setMaxHeight(METER_HEIGHT);
        return meter;
    }

    private void updateNavigationStatus()
    {
        StatsWebNavigationState state = null;

        try
        {
            state = mNavigationStateSupplier != null ? mNavigationStateSupplier.get() : null;
        }
        catch(RuntimeException e)
        {
            //Report inactive until the next refresh while keeping the JavaFX pulse thread alive.
        }

        if(mNavigationStatusInitialized && Objects.equals(state, mLastNavigationState))
        {
            return;
        }

        mNavigationStatusInitialized = true;
        mLastNavigationState = state;

        boolean statsActive = state != null && state.summaryLoggingActive();
        boolean historyActive = state != null && state.detailedHistoryActive();
        boolean webActive = state != null && state.running();
        updateStateLabel(mStatsStatusLabel, "Stats", statsActive);
        mStatsStatusTooltip.setText("Summary statistics logging is " + (statsActive ? "active" : "inactive"));
        updateStateLabel(mHistoryStatusLabel, "History", historyActive);
        mHistoryStatusTooltip.setText("Detailed history logging is " + (historyActive ? "active" : "inactive"));

        mWebStatusLabel.setText(webActive ? "Web:" + state.port() : "Web OFF");
        mWebStatusLabel.setTextFill(webActive ? ACTIVE_COLOR : INACTIVE_COLOR);
        mWebStatusTooltip.setText(webActive ?
            "Embedded web server is running at " + state.baseUri() :
            "Embedded web server is not running");
    }

    private static void updateStateLabel(Label label, String name, boolean active)
    {
        label.setText(name + (active ? " ON" : " OFF"));
        label.setTextFill(active ? ACTIVE_COLOR : INACTIVE_COLOR);
    }

    private HBox getVaultStatusBox()
    {
        if(mVaultStatusBox == null)
        {
            mVaultStatusBox = createCell(VAULT_CELL_WIDTH);
            mVaultStatusBox.setSpacing(3);
            mVaultTooltip = new Tooltip();
            Tooltip.install(mVaultStatusBox, mVaultTooltip);
            mVaultStatusBox.setOnMouseClicked(event ->
                MyEventBus.getGlobalEventBus().post(new ViewEncryptionKeyPreferenceEditorRequest()));
        }

        return mVaultStatusBox;
    }

    private void updateVaultStatus()
    {
        if(mVaultService == null || mVaultStatusBox == null)
        {
            return;
        }

        EncryptionKeyVaultState state = mVaultService.getState();
        IconNode lockIcon = new IconNode(state == EncryptionKeyVaultState.UNLOCKED ? FontAwesome.UNLOCK : FontAwesome.LOCK);
        lockIcon.setIconSize(14);
        lockIcon.setFill(state == EncryptionKeyVaultState.UNLOCKED ? Color.FORESTGREEN : Color.DARKGRAY);
        Label label = fixedLabel("Keys", 30, Pos.CENTER_LEFT);
        mVaultStatusBox.getChildren().setAll(label, lockIcon);

        if(mVaultService.hasSavedPassword())
        {
            IconNode warningIcon = new IconNode(FontAwesome.EXCLAMATION_TRIANGLE);
            warningIcon.setIconSize(14);
            warningIcon.setFill(Color.ORANGERED);
            mVaultStatusBox.getChildren().add(warningIcon);
        }

        String saved = mVaultService.hasSavedPassword() ? " Saved password enabled: unsafe." : "";
        mVaultTooltip.setText(mVaultService.statusProperty().get() + "." + saved + " Click to manage encryption keys.");
    }
}
