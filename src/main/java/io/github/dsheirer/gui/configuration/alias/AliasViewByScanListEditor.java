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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.gui.control.MaxLengthUnaryOperator;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;

/**
 * Assigns aliases from one Alias List to one Scan List.
 */
public class AliasViewByScanListEditor extends VBox
{
    private final ConfigurationManager mConfigurationManager;
    private final ComboBox<String> mAliasListComboBox;
    private final ComboBox<ScanList> mScanListComboBox;
    private final FilteredList<Alias> mAvailableAliases;
    private final FilteredList<Alias> mScanListAliases;
    private final TableView<Alias> mAvailableAliasTable;
    private final TableView<Alias> mScanListAliasTable;
    private final Button mAddButton = new Button();
    private final Button mAddAllButton = new Button();
    private final Button mRemoveButton = new Button();
    private final Button mRemoveAllButton = new Button();
    private final Button mCreateScanListButton = new Button();
    private final Button mRenameScanListButton = new Button();
    private final Button mDeleteScanListButton = new Button();

    public AliasViewByScanListEditor(ConfigurationManager configurationManager)
    {
        mConfigurationManager = configurationManager;
        mAliasListComboBox = new ComboBox<>(mConfigurationManager.getAliasModel().aliasListNames());
        mScanListComboBox = new ComboBox<>(FXCollections.observableArrayList());
        mAvailableAliases = new FilteredList<>(mConfigurationManager.getAliasModel().aliasList(), alias -> false);
        mScanListAliases = new FilteredList<>(mConfigurationManager.getAliasModel().aliasList(), alias -> false);
        mAvailableAliasTable = createAliasTable(mAvailableAliases, false, "No aliases available");
        mScanListAliasTable = createAliasTable(mScanListAliases, true, "No aliases assigned");

        mAliasListComboBox.getSelectionModel().selectedItemProperty()
            .addListener((observable, oldValue, newValue) -> updateFilters());
        mScanListComboBox.getSelectionModel().selectedItemProperty()
            .addListener((observable, oldValue, newValue) -> updateFilters());
        mAvailableAliasTable.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<Alias>)change -> selectionChanged(mAvailableAliasTable, mScanListAliasTable));
        mScanListAliasTable.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<Alias>)change -> selectionChanged(mScanListAliasTable, mAvailableAliasTable));
        mAvailableAliases.addListener((ListChangeListener<Alias>)change -> updateButtons());
        mScanListAliases.addListener((ListChangeListener<Alias>)change -> updateButtons());

        configureButton(mAddAllButton, FontAwesome.ANGLE_DOUBLE_RIGHT, () -> updateMemberships(mAvailableAliases,
            AliasAdministrationService.MembershipOperation.ADD, mAddAllButton));
        configureButton(mAddButton, FontAwesome.ANGLE_RIGHT, () -> updateMemberships(
            mAvailableAliasTable.getSelectionModel().getSelectedItems(),
            AliasAdministrationService.MembershipOperation.ADD, mAddButton));
        configureButton(mRemoveButton, FontAwesome.ANGLE_LEFT, () -> updateMemberships(
            mScanListAliasTable.getSelectionModel().getSelectedItems(),
            AliasAdministrationService.MembershipOperation.REMOVE, mRemoveButton));
        configureButton(mRemoveAllButton, FontAwesome.ANGLE_DOUBLE_LEFT, () -> updateMemberships(mScanListAliases,
            AliasAdministrationService.MembershipOperation.REMOVE, mRemoveAllButton));
        configureManagementButton(mCreateScanListButton, FontAwesome.PLUS, "Create Scan List", this::createScanList);
        configureManagementButton(mRenameScanListButton, FontAwesome.PENCIL, "Rename Scan List", this::renameScanList);
        configureManagementButton(mDeleteScanListButton, FontAwesome.TRASH, "Delete Scan List", this::deleteScanList);

        VBox buttons = new VBox(5, mAddAllButton, mAddButton, mRemoveButton, mRemoveAllButton);
        buttons.setAlignment(Pos.CENTER);
        buttons.getChildren().forEach(node -> ((Button)node).setMaxWidth(Double.MAX_VALUE));

        VBox available = createListPane("Alias List", mAliasListComboBox, "Available Aliases", mAvailableAliasTable);
        VBox assigned = createListPane("Scan List", mScanListComboBox, "Scan List Aliases", mScanListAliasTable,
            mCreateScanListButton, mRenameScanListButton, mDeleteScanListButton);
        HBox lists = new HBox(10, available, buttons, assigned);
        lists.setPadding(new Insets(10));
        HBox.setHgrow(available, Priority.ALWAYS);
        HBox.setHgrow(assigned, Priority.ALWAYS);
        VBox.setVgrow(lists, Priority.ALWAYS);
        getChildren().add(lists);

        if(!mAliasListComboBox.getItems().isEmpty())
        {
            mAliasListComboBox.getSelectionModel().selectFirst();
        }
        refresh();
    }

    /** Refreshes Scan List definitions and membership filters when this tab is shown. */
    public void refresh()
    {
        ScanList selected = mScanListComboBox.getSelectionModel().getSelectedItem();
        long selectedId = selected != null ? selected.getId() : ScanList.UNASSIGNED_ID;
        mScanListComboBox.getItems().setAll(mConfigurationManager.getScanListModel().scanLists());
        mScanListComboBox.getItems().stream().filter(scanList -> scanList.getId() == selectedId).findFirst()
            .ifPresentOrElse(scanList -> mScanListComboBox.getSelectionModel().select(scanList),
                () -> mScanListComboBox.getSelectionModel().selectFirst());
        updateFilters();
    }

    private VBox createListPane(String selectorLabel, ComboBox<?> selector, String listLabel, TableView<Alias> table,
                                Node... selectorActions)
    {
        Label label = new Label(selectorLabel);
        HBox selectorBox = new HBox(5, label, selector);
        selectorBox.getChildren().addAll(selectorActions);
        selectorBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(selector, Priority.ALWAYS);
        selector.setMaxWidth(Double.MAX_VALUE);

        VBox pane = new VBox(5, selectorBox, new Label(listLabel), table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return pane;
    }

    private TableView<Alias> createAliasTable(FilteredList<Alias> aliases, boolean showAliasList,
                                               String placeholder)
    {
        TableView<Alias> table = new TableView<>();
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        if(showAliasList)
        {
            table.getColumns().add(column("Alias List", "aliasListName", 150));
        }
        table.getColumns().add(column("Alias", "name", 200));
        table.getColumns().add(column("Description", "description", 260));
        table.getColumns().add(column("Group", "group", 150));
        table.setPlaceholder(new Label(placeholder));

        SortedList<Alias> sorted = new SortedList<>(aliases);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        return table;
    }

    private static TableColumn<Alias,String> column(String label, String property, double width)
    {
        TableColumn<Alias,String> column = new TableColumn<>(label);
        column.setPrefWidth(width);
        column.setCellValueFactory(new PropertyValueFactory<>(property));
        return column;
    }

    private static void configureButton(Button button, FontAwesome icon, Runnable action)
    {
        button.setDisable(true);
        button.setGraphic(new IconNode(icon));
        button.setOnAction(event -> action.run());
    }

    private static void configureManagementButton(Button button, FontAwesome icon, String label, Runnable action)
    {
        button.setGraphic(new IconNode(icon));
        button.setTooltip(new Tooltip(label));
        button.setAccessibleText(label);
        button.setOnAction(event -> action.run());
    }

    private void createScanList()
    {
        TextInputDialog dialog = scanListNameDialog("Create Scan List", "Enter a name for the new Scan List.", "");
        dialog.showAndWait().ifPresent(name ->
        {
            int sortOrder = mConfigurationManager.getScanListModel().scanLists().stream()
                .mapToInt(ScanList::getSortOrder).max().orElse(-1) + 1;
            AliasAdministrationService service = mConfigurationManager.getAliasAdministrationService();
            AliasMutationUi.execute(mCreateScanListButton, "Create Scan List", () -> service.createScanList(
                new ScanList(ScanList.UNASSIGNED_ID, sortOrder, name, null, true, false),
                service.currentRevision())).ifPresent(result -> refreshAndSelect(result.scanListId()));
        });
    }

    private void renameScanList()
    {
        ScanList selected = mScanListComboBox.getSelectionModel().getSelectedItem();
        if(selected == null)
        {
            return;
        }

        TextInputDialog dialog = scanListNameDialog("Rename Scan List",
            "Enter a new name for " + selected.getName() + ".", selected.getName());
        dialog.showAndWait().ifPresent(name ->
        {
            if(selected.getName().equals(name.strip()))
            {
                return;
            }

            AliasAdministrationService service = mConfigurationManager.getAliasAdministrationService();
            AliasMutationUi.execute(mRenameScanListButton, "Rename Scan List", () -> service.updateScanList(
                selected.getId(), selected.withDefinition(selected.getSortOrder(), name, selected.getDescription(),
                    selected.isPublished(), selected.isDefault()), service.currentRevision()))
                .ifPresent(result -> refreshAndSelect(result.scanListId()));
        });
    }

    private void deleteScanList()
    {
        ScanList selected = mScanListComboBox.getSelectionModel().getSelectedItem();
        if(selected == null || selected.isDefault())
        {
            return;
        }

        AliasAdministrationService service = mConfigurationManager.getAliasAdministrationService();
        AliasAdministrationService.ScanListEntry entry = service.getScanList(selected.getId());
        ButtonType delete = new ButtonType("Delete Scan List", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.CANCEL, delete);
        alert.setTitle("Delete Scan List");
        alert.setHeaderText("Delete " + selected.getName() + "?");
        alert.setContentText(entry.aliasIds().size() + " alias membership(s) and " +
            entry.unmatchedAliasListIds().size() + " unmatched-talkgroup route(s) will be removed.\n\n" +
            "The Aliases and Alias Lists themselves will not be deleted.");
        if(mDeleteScanListButton.getScene() != null)
        {
            alert.initOwner(mDeleteScanListButton.getScene().getWindow());
        }

        alert.showAndWait().filter(delete::equals).ifPresent(button ->
            AliasMutationUi.execute(mDeleteScanListButton, "Delete Scan List", () ->
                service.deleteScanList(selected.getId(), entry.revision())).ifPresent(result -> refresh()));
    }

    private TextInputDialog scanListNameDialog(String title, String header, String initialValue)
    {
        TextInputDialog dialog = new TextInputDialog(initialValue);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText("Name:");
        dialog.getEditor().setTextFormatter(
            new TextFormatter<String>(new MaxLengthUnaryOperator(ScanList.MAXIMUM_NAME_LENGTH)));
        if(mScanListComboBox.getScene() != null)
        {
            dialog.initOwner(mScanListComboBox.getScene().getWindow());
        }
        return dialog;
    }

    private void refreshAndSelect(long scanListId)
    {
        refresh();
        mScanListComboBox.getItems().stream().filter(scanList -> scanList.getId() == scanListId).findFirst()
            .ifPresent(scanList -> mScanListComboBox.getSelectionModel().select(scanList));
    }

    private void selectionChanged(TableView<Alias> selectedTable, TableView<Alias> otherTable)
    {
        if(!selectedTable.getSelectionModel().getSelectedItems().isEmpty())
        {
            otherTable.getSelectionModel().clearSelection();
        }
        updateButtons();
    }

    private void updateFilters()
    {
        ScanListConfiguration configuration = mConfigurationManager.getScanListModel().configuration();
        ScanList scanList = mScanListComboBox.getSelectionModel().getSelectedItem();
        Set<Long> memberIds = scanList != null ? configuration.aliasMemberships().entrySet().stream()
            .filter(entry -> entry.getValue().contains(scanList.getId())).map(java.util.Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet()) : Set.of();
        mAvailableAliases.setPredicate(new ScanListAliasPredicate(
            mAliasListComboBox.getSelectionModel().getSelectedItem(), memberIds, false));
        mScanListAliases.setPredicate(new ScanListAliasPredicate(null, memberIds, true));
        mAvailableAliasTable.getSelectionModel().clearSelection();
        mScanListAliasTable.getSelectionModel().clearSelection();
        updateButtons();
    }

    private void updateButtons()
    {
        ScanList selected = mScanListComboBox.getSelectionModel().getSelectedItem();
        boolean noScanList = selected == null;
        mAddButton.setDisable(noScanList || mAvailableAliasTable.getSelectionModel().getSelectedItems().isEmpty());
        mAddAllButton.setDisable(noScanList || mAvailableAliases.isEmpty());
        mRemoveButton.setDisable(noScanList || mScanListAliasTable.getSelectionModel().getSelectedItems().isEmpty());
        mRemoveAllButton.setDisable(noScanList || mScanListAliases.isEmpty());
        mCreateScanListButton.setDisable(mScanListComboBox.getItems().size() >=
            AliasAdministrationService.MAX_SCAN_LISTS);
        mRenameScanListButton.setDisable(noScanList);
        mDeleteScanListButton.setDisable(noScanList || selected.isDefault());
        mDeleteScanListButton.getTooltip().setText(selected != null && selected.isDefault() ?
            "Select another default Scan List in the web admin interface before deleting this one" :
            "Delete Scan List");
    }

    private void updateMemberships(Collection<Alias> aliases,
                                   AliasAdministrationService.MembershipOperation operation, Node owner)
    {
        ScanList scanList = mScanListComboBox.getSelectionModel().getSelectedItem();
        List<Long> aliasIds = aliases.stream().map(Alias::getId).filter(id -> id > Alias.UNASSIGNED_ID).distinct()
            .toList();
        if(scanList == null || aliasIds.isEmpty())
        {
            return;
        }

        AliasAdministrationService service = mConfigurationManager.getAliasAdministrationService();
        AliasMutationUi.execute(owner, "Update Scan List", () -> service.updateScanListMemberships(scanList.getId(),
            aliasIds, operation, service.currentRevision())).ifPresent(result -> updateFilters());
    }

    static final class ScanListAliasPredicate implements Predicate<Alias>
    {
        private final String mAliasListName;
        private final Set<Long> mMemberIds;
        private final boolean mMembers;

        ScanListAliasPredicate(String aliasListName, Set<Long> memberIds, boolean members)
        {
            mAliasListName = aliasListName;
            mMemberIds = Set.copyOf(memberIds);
            mMembers = members;
        }

        @Override
        public boolean test(Alias alias)
        {
            if(alias == null || alias.getId() <= Alias.UNASSIGNED_ID || mMembers != mMemberIds.contains(alias.getId()))
            {
                return false;
            }
            return mMembers || (mAliasListName != null && mAliasListName.equals(alias.getAliasListName()));
        }
    }
}
