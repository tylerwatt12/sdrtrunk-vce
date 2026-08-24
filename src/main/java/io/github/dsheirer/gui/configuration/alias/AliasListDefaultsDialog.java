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

package io.github.dsheirer.gui.configuration.alias;

import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasListDefaults;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.scanlist.ScanList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** JavaFX editor for the list-owned catch-all and new-talkgroup defaults. */
final class AliasListDefaultsDialog
{
    private static final ButtonType SAVE = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
    private static final String WARNING = "Warning: These settings act as a catch-all and can play, record, or " +
        "stream traffic that has not been individually reviewed, including sensitive traffic. If a selected " +
        "streaming destination sends to Broadcastify or another third-party provider, leave catch-all Streaming " +
        "disabled and configure approved talkgroups individually.";

    private AliasListDefaultsDialog()
    {
    }

    static Optional<AliasAdministrationService.MutationResult> show(ConfigurationManager manager,
                                                                    AliasListDefinition definition, Window owner)
    {
        AliasAdministrationService service = manager.getAliasAdministrationService();
        AliasAdministrationService.AliasListDefaultsEntry entry = service.getAliasListDefaults(definition.getId());
        AliasListDefaults defaults = entry.defaults();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Alias List Defaults · " + definition.getName());
        if(owner != null)
        {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, SAVE);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(720);
        content.getChildren().add(wrapped("These settings apply when a destination talkgroup or patch group has no " +
            "exact Alias or covering talkgroup range in this Alias List. New talkgroup Aliases created in this list " +
            "start with the same selections. Existing Aliases are not changed."));

        CheckBox record = new CheckBox("Record calls");
        record.setSelected(defaults.isRecordEnabled());
        VBox recording = new VBox(8, wrapped("Records completed unmatched talkgroup calls in the configured " +
            "recording directory. New talkgroup Aliases are created with these defaults. Existing Aliases are " +
            "unchanged."), record);

        List<ScanList> scanLists = manager.getScanListModel().scanLists();
        AliasRoutingSelectionPane<ScanList> scanSelector = new AliasRoutingSelectionPane<>();
        scanSelector.setValues(scanLists,
            scanLists.stream().filter(scanList -> defaults.scanListIds().contains(scanList.getId())).toList());
        VBox scan = new VBox(8, wrapped("Routes unmatched talkgroup calls to the selected scan lists for browser " +
            "playback. New talkgroup Aliases are created with these defaults."), scanSelector);

        List<String> destinations = manager.getBroadcastModel().getBroadcastConfigurationNames();
        Set<String> allDestinations = new LinkedHashSet<>(destinations);
        allDestinations.addAll(defaults.streamDestinationNames());
        AliasRoutingSelectionPane<String> streamSelector = new AliasRoutingSelectionPane<>();
        streamSelector.setValues(allDestinations, defaults.streamDestinationNames());
        VBox streaming = new VBox(8, wrapped("Sends unmatched talkgroup calls to the selected external streaming " +
            "destinations. New talkgroup Aliases are created with these defaults."), streamSelector);

        TitledPane recordingPane = new TitledPane("Recording", recording);
        TitledPane scanPane = new TitledPane("Scan List", scan);
        TitledPane streamingPane = new TitledPane("Streaming", streaming);
        recordingPane.setExpanded(true);
        scanPane.setExpanded(false);
        streamingPane.setExpanded(false);
        Label warning = wrapped(WARNING);
        warning.getStyleClass().add("warning");
        content.getChildren().addAll(recordingPane, scanPane, streamingPane, warning);
        dialog.getDialogPane().setContent(content);

        final AliasAdministrationService.MutationResult[] saved = new AliasAdministrationService.MutationResult[1];
        final long[] revision = {entry.revision()};
        Node save = dialog.getDialogPane().lookupButton(SAVE);
        save.addEventFilter(ActionEvent.ACTION, event ->
        {
            Set<Long> selectedScanLists = scanSelector.selectedValues().stream().map(ScanList::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            AliasListDefaults replacement = new AliasListDefaults(
                new UnmatchedTalkgroupPolicy(record.isSelected(), streamSelector.selectedValues()),
                selectedScanLists);
            Optional<AliasAdministrationService.MutationResult> result = AliasMutationUi.execute(save,
                "Save Alias List Defaults", () -> service.updateAliasListDefaults(definition.getId(), replacement,
                    revision[0]));
            if(result.isEmpty())
            {
                revision[0] = service.currentRevision();
                event.consume();
            }
            else
            {
                saved[0] = result.get();
            }
        });
        dialog.showAndWait();
        return Optional.ofNullable(saved[0]);
    }

    private static Label wrapped(String text)
    {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }
}
