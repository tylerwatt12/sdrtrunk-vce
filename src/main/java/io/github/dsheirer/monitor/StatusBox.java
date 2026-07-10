/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.preference.encryption.ViewEncryptionKeyPreferenceEditorRequest;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultState;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModuleManager;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;

/**
 * JavaFX status panel box.
 */
public class StatusBox extends HBox
{
    private ResourceMonitor mResourceMonitor;
    private EncryptionKeyVaultService mVaultService;
    private VoiceDecryptionModuleManager mModuleManager;
    private HBox mVaultStatusBox;
    private Tooltip mVaultTooltip;

    /**
     * Constructs an instance.
     * @param resourceMonitor for accessing resource usage statistics.
     */
    public StatusBox(ResourceMonitor resourceMonitor)
    {
        this(resourceMonitor, null, null);
    }

    /**
     * Constructs an instance.
     * @param resourceMonitor for accessing resource usage statistics.
     * @param vaultService for displaying encryption vault status.
     */
    public StatusBox(ResourceMonitor resourceMonitor, EncryptionKeyVaultService vaultService)
    {
        this(resourceMonitor, vaultService, null);
    }

    public StatusBox(ResourceMonitor resourceMonitor, EncryptionKeyVaultService vaultService,
                     VoiceDecryptionModuleManager moduleManager)
    {
        mResourceMonitor = resourceMonitor;
        mVaultService = vaultService;
        mModuleManager = moduleManager;
        setPadding(new Insets(1, 0, 1, 0));
        setSpacing(6);
        Label cpuLabel = new Label("CPU:");
        cpuLabel.setPadding(new Insets(0, 0, 0, 10));
        cpuLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(cpuLabel);

        ProgressBar cpuIndicator = new ProgressBar();
        cpuIndicator.progressProperty().bind(mResourceMonitor.cpuPercentageProperty());
        cpuIndicator.disableProperty().bind(mResourceMonitor.cpuAvailableProperty().not());
        cpuIndicator.setTooltip(new Tooltip("Java process CPU usage"));
        getChildren().add(cpuIndicator);

        Label cpuValueLabel = new Label();
        cpuValueLabel.textProperty().bind(mResourceMonitor.cpuLabelProperty());
        cpuValueLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(cpuValueLabel);

        Label memoryLabel = new Label("Allocated Heap:");
        memoryLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(memoryLabel);

        ProgressBar memoryBar = new ProgressBar();
        memoryBar.progressProperty().bind(mResourceMonitor.systemMemoryUsedPercentageProperty());
        Tooltip memoryTooltip = new Tooltip();
        memoryTooltip.textProperty().bind(mResourceMonitor.memoryAllocatedLabelProperty()
                .concat(" JVM heap committed out of max heap"));
        memoryBar.setTooltip(memoryTooltip);
        getChildren().add(memoryBar);

        Label javaMemoryLabel = new Label("Used Heap:");
        javaMemoryLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(javaMemoryLabel);

        ProgressBar javaMemoryBar = new ProgressBar();
        javaMemoryBar.progressProperty().bind(mResourceMonitor.javaMemoryUsedPercentageProperty());
        Tooltip javaMemoryTooltip = new Tooltip();
        javaMemoryTooltip.textProperty().bind(mResourceMonitor.memoryUsedLabelProperty()
                .concat(" JVM heap used out of committed heap"));
        javaMemoryBar.setTooltip(javaMemoryTooltip);
        getChildren().add(javaMemoryBar);

        Label eventLogsLabel = new Label("Event Logs:");
        eventLogsLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(eventLogsLabel);

        ProgressBar eventLogsBar = new ProgressBar();
        eventLogsBar.progressProperty().bind(mResourceMonitor.directoryUsePercentEventLogsProperty());
        eventLogsBar.setTooltip(new Tooltip("Percentage of drive space used for event logs based on user-specified max threshold in user preferences"));
        getChildren().add(eventLogsBar);

        Label eventLogsSizeLabel = new Label();
        eventLogsSizeLabel.textProperty().bind(mResourceMonitor.fileSizeEventLogsProperty());
        eventLogsSizeLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(eventLogsSizeLabel);

        Label recordingsLabel = new Label("Recordings:");
        recordingsLabel.setPadding(new Insets(0, 0, 0, 10));
        recordingsLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(recordingsLabel);

        ProgressBar recordingsBar = new ProgressBar();
        recordingsBar.progressProperty().bind(mResourceMonitor.directoryUsePercentRecordingsProperty());
        recordingsBar.setTooltip(new Tooltip("Percentage of drive space used for recordings based on user-specified max threshold in user preferences"));
        getChildren().add(recordingsBar);

        Label recordingsSizeLabel = new Label();
        recordingsSizeLabel.textProperty().bind(mResourceMonitor.fileSizeRecordingsProperty());
        recordingsSizeLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(recordingsSizeLabel);

        Label databaseLabel = new Label("Database:");
        databaseLabel.setPadding(new Insets(0, 0, 0, 10));
        databaseLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(databaseLabel);

        Label databaseSizeLabel = new Label();
        databaseSizeLabel.textProperty().bind(mResourceMonitor.fileSizeDatabaseProperty());
        databaseSizeLabel.setAlignment(Pos.CENTER_RIGHT);
        databaseSizeLabel.setTooltip(new Tooltip("SQLite database size on disk, including WAL and shared-memory side files"));
        getChildren().add(databaseSizeLabel);

        if(mVaultService != null)
        {
            Region spacer = new Region();
            spacer.setMinWidth(12);
            getChildren().add(spacer);
            getChildren().add(getVaultStatusBox());
            boolean moduleLoaded = mModuleManager == null || mModuleManager.isLoaded();
            spacer.setVisible(moduleLoaded);
            spacer.setManaged(moduleLoaded);
            mVaultStatusBox.setVisible(moduleLoaded);
            mVaultStatusBox.setManaged(moduleLoaded);

            if(mModuleManager != null)
            {
                mModuleManager.loadedProperty().addListener((observable, oldValue, loaded) -> {
                    spacer.setVisible(loaded);
                    spacer.setManaged(loaded);
                    mVaultStatusBox.setVisible(loaded);
                    mVaultStatusBox.setManaged(loaded);
                });
            }

            mVaultService.stateProperty().addListener((observable, oldValue, newValue) -> updateVaultStatus());
            mVaultService.statusProperty().addListener((observable, oldValue, newValue) -> updateVaultStatus());
            mVaultService.savedPasswordPresentProperty()
                .addListener((observable, oldValue, newValue) -> updateVaultStatus());
            updateVaultStatus();
        }
    }

    private HBox getVaultStatusBox()
    {
        if(mVaultStatusBox == null)
        {
            mVaultStatusBox = new HBox(4);
            mVaultStatusBox.setAlignment(Pos.CENTER_RIGHT);
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
        Label label = new Label("Encryption");
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
