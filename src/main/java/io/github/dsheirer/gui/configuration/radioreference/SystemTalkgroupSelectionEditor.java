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

package io.github.dsheirer.gui.configuration.radioreference;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.configuration.AliasMutationUi;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.rrapi.type.System;
import io.github.dsheirer.rrapi.type.Talkgroup;
import io.github.dsheirer.rrapi.type.TalkgroupCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javafx.animation.RotateTransition;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.controlsfx.control.textfield.TextFields;

public class SystemTalkgroupSelectionEditor extends GridPane
{

    private final TalkgroupCategory ALL_TALKGROUPS = new TalkgroupCategory();
    private ConfigurationManager mConfigurationManager;
    private TableView<AliasedTalkgroup> mTalkgroupTableView;
    private ComboBox<TalkgroupCategory> mTalkgroupCategoryComboBox;
    private TextField mSearchField;
    private TalkgroupEditor mTalkgroupEditor;
    private ComboBox<String> mAliasListNameComboBox;
    private FilteredList<String> mCompatibleAliasLists;
    private TalkgroupFilter mTalkgroupFilter = new TalkgroupFilter();
    private FilteredList<AliasedTalkgroup> mTalkgroupFilteredList;
    private ObservableList<AliasedTalkgroup> mTalkgroupList = FXCollections.observableArrayList();
    private System mCurrentSystem;
    private RadioReferenceDecoder mRadioReferenceDecoder;
    private AliasList mAliasList;
    private AliasListChangeListener mAliasListChangeListener = new AliasListChangeListener();
    private DeferredRefresh mAliasMatchRefresh = new DeferredRefresh();
    private Button mImportSelectedTalkgroupsButton;
    private Button mImportAllTalkgroupsButton;
    private Label mImportResultLabel;
    private Label mPlaceholderLabel;
    private ProgressIndicator mProgressIndicator;

    public SystemTalkgroupSelectionEditor(ConfigurationManager configurationManager)
    {
        //Register to receive flash alias box requests
        MyEventBus.getGlobalEventBus().register(this);

        mConfigurationManager = configurationManager;

        ALL_TALKGROUPS.setName("(All Talkgroups)");

        setPadding(new Insets(10,0,0,0));
        setVgap(10);
        setHgap(10);
        setMaxHeight(Double.MAX_VALUE);

        int row = 0;

        ColumnConstraints column1 = new ColumnConstraints();
        ColumnConstraints column2 = new ColumnConstraints();
        column2.setPercentWidth(40);
        getColumnConstraints().addAll(column1, column2);

        HBox listBox = new HBox();
        listBox.setSpacing(5);
        listBox.setAlignment(Pos.CENTER);
        Label importLabel = new Label("Import To Alias List:");
        listBox.getChildren().addAll(importLabel, getAliasListNameComboBox());
        GridPane.setConstraints(listBox, 0, row);
        getChildren().add(listBox);

        HBox searchBox = new HBox();
        searchBox.setSpacing(5);
        searchBox.setAlignment(Pos.CENTER);
        searchBox.getChildren().addAll(new Label("Search"), getSearchField());
        GridPane.setConstraints(searchBox, 0, ++row);
        getChildren().add(searchBox);

        HBox importBox = new HBox(10, getImportSelectedTalkgroupsButton(), getImportAllTalkgroupsButton(),
            getImportResultLabel());
        importBox.setAlignment(Pos.CENTER);
        HBox.setHgrow(getImportResultLabel(), Priority.ALWAYS);
        GridPane.setHgrow(importBox, Priority.ALWAYS);
        GridPane.setConstraints(importBox, 1, row);
        getChildren().add(importBox);

        HBox categoryBox = new HBox();
        categoryBox.setAlignment(Pos.CENTER_LEFT);
        categoryBox.setSpacing(5);
        HBox.setHgrow(getTalkgroupCategoryComboBox(), Priority.ALWAYS);
        categoryBox.getChildren().addAll(new Label("Category"), getTalkgroupCategoryComboBox());
        GridPane.setHgrow(categoryBox, Priority.ALWAYS);
        GridPane.setConstraints(categoryBox, 0, ++row);
        getChildren().add(categoryBox);

        Separator separator = new Separator(Orientation.HORIZONTAL);
        GridPane.setHgrow(separator, Priority.ALWAYS);
        GridPane.setConstraints(separator, 1, row);
        getChildren().add(separator);

        GridPane.setHgrow(getTalkgroupTableView(), Priority.ALWAYS);
        GridPane.setVgrow(getTalkgroupTableView(), Priority.ALWAYS);
        GridPane.setConstraints(getTalkgroupTableView(), 0, ++row);
        getChildren().add(getTalkgroupTableView());

        GridPane.setHgrow(getTalkgroupEditor(), Priority.ALWAYS);
        GridPane.setVgrow(getTalkgroupEditor(), Priority.ALWAYS);
        GridPane.setConstraints(getTalkgroupEditor(), 1, row);
        getChildren().add(getTalkgroupEditor());
    }

    public void dispose()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
    }

    public void clear()
    {
        getTalkgroupTableView().getSelectionModel().clearSelection();
        mTalkgroupList.clear();
        getTalkgroupCategoryComboBox().getItems().clear();
        clearImportResult();
        updateImportButtonState();
    }

    public void clearAndSetLoading()
    {
        clear();
        setLoading(true);
    }

    private void setLoading(boolean loading)
    {
        getTalkgroupTableView().setPlaceholder(loading ? getProgressIndicator() : getPlaceholderLabel());
    }

    private ProgressIndicator getProgressIndicator()
    {
        if(mProgressIndicator == null)
        {
            mProgressIndicator = new ProgressIndicator();
            mProgressIndicator.setProgress(-1);
        }

        return mProgressIndicator;
    }

    private Label getPlaceholderLabel()
    {
        if(mPlaceholderLabel == null)
        {
            mPlaceholderLabel = new Label("No Talkgroups Available");
        }

        return mPlaceholderLabel;
    }

    private void updateFilter()
    {
        mTalkgroupFilter.setFilterText(getSearchField().getText());
        TalkgroupCategory category = getTalkgroupCategoryComboBox().getSelectionModel().getSelectedItem();

        if(category == ALL_TALKGROUPS)
        {
            mTalkgroupFilter.setCategory(null);
        }
        else
        {
            mTalkgroupFilter.setCategory(category != null ? category.getTalkgroupCategoryId() : null);
        }

        mTalkgroupFilteredList.setPredicate(null);
        mTalkgroupFilteredList.setPredicate(mTalkgroupFilter);
        updateImportButtonState();
    }

    public void setSystem(System system, List<Talkgroup> talkgroups, List<TalkgroupCategory> categories,
                          RadioReferenceDecoder decoder)
    {
        mCurrentSystem = system;
        mRadioReferenceDecoder = decoder;

        clearAndSetLoading();
        refreshCompatibleAliasLists();

        if(talkgroups != null && !talkgroups.isEmpty())
        {
            List<Talkgroup> sortedTalkgroups = new ArrayList<>(talkgroups);
            sortedTalkgroups.sort(Comparator.comparingInt(Talkgroup::getDecimalValue));

            for(Talkgroup talkgroup: sortedTalkgroups)
            {
                mTalkgroupList.add(new AliasedTalkgroup(talkgroup, getAlias(talkgroup)));
            }

            updateCategories(categories);
        }

        //If the protocol is supported then enable the talkgroup import controls
        boolean supported = getRadioReferenceDecoder() != null &&
            getRadioReferenceDecoder().hasSupportedProtocol(getCurrentSystem());
        boolean hasSystemName = getCurrentSystem() != null && getCurrentSystem().getName() != null &&
            !getCurrentSystem().getName().isBlank();
        getAliasListNameComboBox().setDisable(!supported || !hasSystemName);
        updateImportButtonState();
        setLoading(false);
    }

    /**
     * Adds optional category labels after talkgroups have loaded.  Talkgroups remain usable when this enrichment is
     * unavailable.
     */
    public void updateCategories(List<TalkgroupCategory> categories)
    {
        getTalkgroupCategoryComboBox().getItems().clear();

        if(!mTalkgroupList.isEmpty())
        {
            List<TalkgroupCategory> sortedCategories =
                categories == null ? new ArrayList<>() : new ArrayList<>(categories);
            sortedCategories.sort(Comparator.comparing(TalkgroupCategory::getName,
                Comparator.nullsLast(String::compareToIgnoreCase)));
            getTalkgroupCategoryComboBox().getItems().add(ALL_TALKGROUPS);
            getTalkgroupCategoryComboBox().getItems().addAll(sortedCategories);
            getTalkgroupCategoryComboBox().getSelectionModel().select(ALL_TALKGROUPS);

            //Category names are stored as the alias group during import, so refresh the comparison once this optional
            //enrichment is available.
            refreshAliasMatches();
        }
    }

    public void setLoadFailed()
    {
        mCurrentSystem = null;
        clear();
        updateImportButtonState();
        setLoading(false);
    }

    @Subscribe
    public void process(FlashAliasListComboBoxRequest request)
    {
        flashAliasListComboBox();
    }

    /**
     * Flashes the alias list combobox to let the user know that they must select an alias list
     */
    private void flashAliasListComboBox()
    {
        RotateTransition rt = new RotateTransition(Duration.millis(150), getAliasListNameComboBox());
        rt.setByAngle(20);
        rt.setCycleCount(6);
        rt.setAutoReverse(true);
        rt.play();
    }

    private Button getImportSelectedTalkgroupsButton()
    {
        if(mImportSelectedTalkgroupsButton == null)
        {
            mImportSelectedTalkgroupsButton = new Button("Import/Update Selected");
            mImportSelectedTalkgroupsButton.setDisable(true);
            mImportSelectedTalkgroupsButton.setOnAction(event -> {
                List<AliasedTalkgroup> selectedTalkgroups = snapshotRows(
                    getTalkgroupTableView().getSelectionModel().getSelectedItems());
                importTalkgroups(selectedTalkgroups, getImportSelectedTalkgroupsButton(),
                    "Import/Update Selected Talkgroups",
                    selectedTalkgroups.size() + " selected talkgroup" +
                        (selectedTalkgroups.size() == 1 ? "" : "s"));
            });
        }

        return mImportSelectedTalkgroupsButton;
    }

    private Button getImportAllTalkgroupsButton()
    {
        if(mImportAllTalkgroupsButton == null)
        {
            mImportAllTalkgroupsButton = new Button("Import All");
            mImportAllTalkgroupsButton.setDisable(true);
            mImportAllTalkgroupsButton.setTooltip(new Tooltip(
                "Import or update every talkgroup loaded for this system, regardless of search or category filters."));
            mImportAllTalkgroupsButton.setOnAction(event -> {
                List<AliasedTalkgroup> allTalkgroups = snapshotRows(mTalkgroupList);
                importTalkgroups(allTalkgroups, getImportAllTalkgroupsButton(), "Import All Talkgroups",
                    "all " + allTalkgroups.size() + " talkgroups loaded for this system");
            });
        }

        return mImportAllTalkgroupsButton;
    }

    private void importTalkgroups(List<AliasedTalkgroup> talkgroups, Button sourceButton, String title,
                                  String scopeDescription)
    {
        clearImportResult();

        if(getAliasListNameComboBox().getSelectionModel().getSelectedItem() == null)
        {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Please select an Alias List", ButtonType.OK);
            alert.setTitle("Alias List Required");
            alert.setHeaderText("An alias list is required to import talkgroups");
            alert.initOwner(sourceButton.getScene().getWindow());
            alert.showAndWait();
            flashAliasListComboBox();
            return;
        }

        long revision = mConfigurationManager.getAliasAdministrationService().currentRevision();
        List<Talkgroup> aliasesToCreate = new ArrayList<>();
        List<Alias> aliasesToUpdate = new ArrayList<>();
        int identical = 0;

        for(AliasedTalkgroup aliasedTalkgroup: talkgroups)
        {
            Talkgroup talkgroup = aliasedTalkgroup.getTalkgroup();
            TalkgroupCategory category = getTalkgroupCategory(talkgroup);
            Alias currentAlias = getAlias(talkgroup);
            ImportStatus status = getImportStatus(isCurrentSystemSupported(), currentAlias, talkgroup, category);

            if(status == ImportStatus.NOT_PRESENT)
            {
                aliasesToCreate.add(talkgroup);
            }
            else if(status == ImportStatus.DIFFERENT)
            {
                aliasesToUpdate.add(createRadioReferenceReplacement(currentAlias, talkgroup, category));
            }
            else if(status == ImportStatus.IDENTICAL)
            {
                identical++;
            }
        }

        List<Alias> created = createAliases(aliasesToCreate);
        List<AliasAdministrationService.AliasSaveRequest> changes = new ArrayList<>();
        for(int index = 0; index < created.size(); index++)
        {
            Alias alias = created.get(index);
            Talkgroup source = aliasesToCreate.get(index);
            if(TalkgroupEncryption.lookup(source.getEncryptionState()) == TalkgroupEncryption.FULL)
            {
                alias.setRecordable(false);
                alias.setBroadcastChannels(List.of());
                changes.add(AliasAdministrationService.AliasSaveRequest.explicit(alias, Set.of()));
            }
            else
            {
                changes.add(AliasAdministrationService.AliasSaveRequest.inherit(alias));
            }
        }
        aliasesToUpdate.stream().map(AliasAdministrationService.AliasSaveRequest::preserve)
            .forEach(changes::add);

        ButtonType apply = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
            "Add " + aliasesToCreate.size() + " new aliases\n" +
                "Update " + aliasesToUpdate.size() + " different aliases\n" +
                "Leave " + identical + " identical aliases unchanged",
            apply, ButtonType.CANCEL);
        confirmation.setTitle(title);
        confirmation.setHeaderText("Apply RadioReference changes to " + scopeDescription + "?");
        confirmation.initOwner(sourceButton.getScene().getWindow());
        int identicalCount = identical;

        if(confirmation.showAndWait().filter(apply::equals).isPresent())
        {
            if(changes.isEmpty())
            {
                getImportResultLabel().setText(formatImportCompletion(0, 0, identicalCount));
            }
            else
            {
                AliasMutationUi.execute(sourceButton, title, () ->
                    mConfigurationManager.getAliasAdministrationService().saveAliasRequests(changes, revision))
                    .ifPresent(ignored -> getImportResultLabel().setText(formatImportCompletion(
                        aliasesToCreate.size(), aliasesToUpdate.size(), identicalCount)));
            }
        }
    }

    private Label getImportResultLabel()
    {
        if(mImportResultLabel == null)
        {
            mImportResultLabel = new Label();
            mImportResultLabel.setWrapText(true);
            mImportResultLabel.setMaxWidth(Double.MAX_VALUE);
        }

        return mImportResultLabel;
    }

    private void clearImportResult()
    {
        if(mImportResultLabel != null)
        {
            mImportResultLabel.setText(null);
        }
    }

    static String formatImportCompletion(int added, int updated, int alreadyCurrent)
    {
        return "Import complete: " + added + " added, " + updated + " updated, " + alreadyCurrent +
            " already current";
    }

    /**
     * Copies the table selection before aliases are changed, since those changes can refresh the backing rows.
     */
    static <T> List<T> snapshotRows(List<? extends T> rows)
    {
        return rows == null || rows.isEmpty() ? List.of() : List.copyOf(rows);
    }

    /**
     * Creates a detached Alias for each talkgroup using the currently selected Alias list.
     * @param talkgroups to alias
     */
    private List<Alias> createAliases(List<Talkgroup> talkgroups)
    {
        AliasListDefinition definition = getAliasListDefinition(
            getAliasListNameComboBox().getSelectionModel().getSelectedItem());

        if(!isCurrentSystemSupported() || !isCurrentProtocolCompatible(definition))
        {
            throw new IllegalStateException("A protocol-compatible alias list is required for bulk import");
        }

        List<Alias> createdAliases = new ArrayList<>();

        for(Talkgroup talkgroup: talkgroups)
        {
            TalkgroupCategory talkgroupCategory = getTalkgroupCategory(talkgroup);
            String group = (talkgroupCategory != null ? talkgroupCategory.getName() : null);
            Alias alias = getRadioReferenceDecoder().createAlias(talkgroup, getCurrentSystem(),
                definition, group);

            createdAliases.add(alias);
        }

        return List.copyOf(createdAliases);
    }

    /**
     * Retrieves the talkgroup category that matches the specified id from the current set in the talkgroup category
     * combo box.
     * @param talkgroup to match
     * @return matching category or null
     */
    private TalkgroupCategory getTalkgroupCategory(Talkgroup talkgroup)
    {
        if(talkgroup != null)
        {
            for(TalkgroupCategory category: getTalkgroupCategoryComboBox().getItems())
            {
                if(category.getTalkgroupCategoryId() == talkgroup.getTalkgroupCategoryId())
                {
                    return category;
                }
            }
        }

        return null;
    }

    private System getCurrentSystem()
    {
        return mCurrentSystem;
    }

    private AliasList getAliasList()
    {
        if(mAliasList == null)
        {
            mAliasList = AliasList.empty("empty");
        }

        return mAliasList;
    }

    /**
     * Retrieves an alias with an exact talkgroup identifier from the currently selected alias list.  A talkgroup range
     * can match during normal alias lookup, but it does not mean that this RadioReference talkgroup was imported.
     */
    private Alias getAlias(Talkgroup talkgroup)
    {
        if(!isCurrentSystemSupported())
        {
            return null;
        }

        return findExactAlias(getAliasList(), getRadioReferenceDecoder(), talkgroup, getCurrentSystem());
    }

    /**
     * Resolves the current exact Alias at action time so a deferred table refresh cannot create a duplicate or replace
     * newly edited local fields with an older row snapshot.
     */
    static Alias findExactAlias(AliasList aliasList, RadioReferenceDecoder decoder, Talkgroup talkgroup, System system)
    {
        if(aliasList == null || decoder == null || talkgroup == null || system == null)
        {
            return null;
        }

        TalkgroupIdentifier talkgroupIdentifier = decoder.getIdentifier(talkgroup, system);
        List<Alias> aliases = aliasList.getAliases(talkgroupIdentifier);
        io.github.dsheirer.alias.id.talkgroup.Talkgroup expected =
            decoder.getTalkgroupAliasId(talkgroup, system);
        return aliases.stream().filter(alias -> hasExactTalkgroup(alias, expected)).findFirst().orElse(null);
    }

    static boolean hasExactTalkgroup(Alias alias,
                                     io.github.dsheirer.alias.id.talkgroup.Talkgroup expected)
    {
        if(alias != null && expected != null)
        {
            AliasID aliasID = alias.getMatchIdentifier();
            return aliasID instanceof io.github.dsheirer.alias.id.talkgroup.Talkgroup exact &&
                exact.matches(expected);
        }

        return false;
    }

    private RadioReferenceDecoder getRadioReferenceDecoder()
    {
        return mRadioReferenceDecoder;
    }

    private TalkgroupEditor getTalkgroupEditor()
    {
        if(mTalkgroupEditor == null)
        {
            mTalkgroupEditor = new TalkgroupEditor(mConfigurationManager);
        }

        return mTalkgroupEditor;
    }

    private TextField getSearchField()
    {
        if(mSearchField == null)
        {
            mSearchField = TextFields.createClearableTextField();
            mSearchField.textProperty().addListener((observable, oldValue, newValue) -> updateFilter());
        }

        return mSearchField;
    }

    private ComboBox<String> getAliasListNameComboBox()
    {
        if(mAliasListNameComboBox == null)
        {
            mCompatibleAliasLists =
                new FilteredList<>(mConfigurationManager.getAliasModel().aliasListNames());
            mAliasListNameComboBox = new ComboBox<>(mCompatibleAliasLists);
            mAliasListNameComboBox.setPrefWidth(150);
            mAliasListNameComboBox.setOnAction(event -> {
                updateAliasList(getAliasListNameComboBox().getSelectionModel().getSelectedItem());
                updateImportButtonState();
            });

            if(mAliasListNameComboBox.getItems().size() > 0)
            {
                mAliasListNameComboBox.getSelectionModel().select(0);
            }

            updateAliasList(getAliasListNameComboBox().getSelectionModel().getSelectedItem());
        }

        return mAliasListNameComboBox;
    }

    private void refreshCompatibleAliasLists()
    {
        if(mCompatibleAliasLists == null)
        {
            return;
        }

        mCompatibleAliasLists.setPredicate(name -> {
            if(name == null)
            {
                return false;
            }

            return isCurrentProtocolCompatible(getAliasListDefinition(name));
        });

        if(!mCompatibleAliasLists.contains(
            getAliasListNameComboBox().getSelectionModel().getSelectedItem()))
        {
            getAliasListNameComboBox().getSelectionModel().clearSelection();
            updateAliasList(null);
        }

        updateImportButtonState();
    }

    private void updateImportButtonState()
    {
        AliasListDefinition definition = getAliasListDefinition(
            mAliasListNameComboBox != null ?
                mAliasListNameComboBox.getSelectionModel().getSelectedItem() : null);
        boolean canImport = isCurrentSystemSupported() && isCurrentProtocolCompatible(definition);
        boolean hasSelection = mTalkgroupTableView != null &&
            !mTalkgroupTableView.getSelectionModel().getSelectedItems().isEmpty();

        if(mImportSelectedTalkgroupsButton != null)
        {
            mImportSelectedTalkgroupsButton.setDisable(!canImport || !hasSelection);
        }

        if(mImportAllTalkgroupsButton != null)
        {
            mImportAllTalkgroupsButton.setDisable(!canImport || mTalkgroupList.isEmpty());
        }
    }

    private AliasListDefinition getAliasListDefinition(String name)
    {
        return name == null ? null : mConfigurationManager.getAliasModel().getAliasListDefinition(name);
    }

    private boolean isCurrentProtocolCompatible(AliasListDefinition definition)
    {
        return isRadioReferenceListCompatible(definition,
            mCurrentSystem != null && mRadioReferenceDecoder != null ?
                mRadioReferenceDecoder.getDecoderType(mCurrentSystem) : null);
    }

    private boolean isCurrentSystemSupported()
    {
        return mRadioReferenceDecoder != null && mCurrentSystem != null &&
            mRadioReferenceDecoder.hasSupportedProtocol(mCurrentSystem);
    }

    /**
     * RadioReference creates channels without auxiliary decoders, so its alias-list capability profile must be the
     * primary decoder family.
     */
    static boolean isRadioReferenceListCompatible(AliasListDefinition definition, DecoderType decoderType)
    {
        return AliasMatchRegistry.isChannelCompatible(definition, decoderType);
    }

    /**
     * Updates the alias list whenever the alias list combo box changes.  Refreshes the alias for each talkgroup
     * table entry from the new list and registers a listener to detect changes to the alias list that might occur
     * on the alias tab, so that this table stays in sync with any alias changes.
     */
    private void updateAliasList(String aliasListName)
    {
        clearImportResult();

        if(mAliasList != null)
        {
            mAliasList.aliases().removeListener(mAliasListChangeListener);
        }

        mAliasList = mConfigurationManager.getAliasModel().getAliasList(aliasListName);

        if(mAliasList != null)
        {
            mAliasList.aliases().addListener(mAliasListChangeListener);
        }

        refreshAliasMatches();
    }

    /**
     * Refreshes exact alias matches and their RadioReference import status.
     */
    private void refreshAliasMatches()
    {
        for(AliasedTalkgroup item: mTalkgroupList)
        {
            item.setAlias(getAlias(item.getTalkgroup()));
        }

        refreshSelectedTalkgroupEditor();
    }

    /**
     * Keeps the detail editor synchronized when the alias list changes without changing the selected table row.
     */
    private void refreshSelectedTalkgroupEditor()
    {
        AliasedTalkgroup selected = getTalkgroupTableView().getSelectionModel().getSelectedItem();
        TalkgroupCategory talkgroupCategory =
            getTalkgroupCategory(selected != null ? selected.getTalkgroup() : null);
        String aliasListName = getAliasListNameComboBox().getSelectionModel().getSelectedItem();
        getTalkgroupEditor().setTalkgroup(selected != null ? selected.getTalkgroup() : null,
            getCurrentSystem(), getRadioReferenceDecoder(), selected != null ? selected.getAlias() : null,
            aliasListName, talkgroupCategory,
            selected != null ? selected.getImportStatus() :
                (isCurrentSystemSupported() ? ImportStatus.NOT_PRESENT : ImportStatus.NOT_COMPATIBLE));
    }

    private ComboBox<TalkgroupCategory> getTalkgroupCategoryComboBox()
    {
        if(mTalkgroupCategoryComboBox == null)
        {
            mTalkgroupCategoryComboBox = new ComboBox<>();
            mTalkgroupCategoryComboBox.setMaxWidth(Double.MAX_VALUE);
            mTalkgroupCategoryComboBox.setConverter(new TalkgroupCategoryStringConverter());
            mTalkgroupCategoryComboBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> updateFilter());
        }

        return mTalkgroupCategoryComboBox;
    }

    private TableView<AliasedTalkgroup> getTalkgroupTableView()
    {
        if(mTalkgroupTableView == null)
        {
            mTalkgroupTableView = new TableView<>();
            mTalkgroupTableView.setMaxHeight(Double.MAX_VALUE);
            mTalkgroupTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            TableColumn<AliasedTalkgroup,String> talkgroupColumn = new TableColumn<>("Talkgroup");
            talkgroupColumn.setCellValueFactory(new PropertyValueFactory<>("talkgroup"));

            TableColumn<AliasedTalkgroup,String> alphaTagColumn = new TableColumn<>("Alpha Tag");
            alphaTagColumn.setPrefWidth(170);
            alphaTagColumn.setCellValueFactory(new PropertyValueFactory<>("alphaTag"));

            TableColumn<AliasedTalkgroup,String> descriptionColumn = new TableColumn<>("Description");
            descriptionColumn.setPrefWidth(300);
            descriptionColumn.setCellValueFactory(new PropertyValueFactory<>("description"));

            TableColumn<AliasedTalkgroup,ImportStatus> importStatusColumn = new TableColumn<>("Import Status");
            importStatusColumn.setPrefWidth(110);
            importStatusColumn.setCellValueFactory(new PropertyValueFactory<>("importStatus"));

            mTalkgroupTableView.getColumns().add(talkgroupColumn);
            mTalkgroupTableView.getColumns().add(alphaTagColumn);
            mTalkgroupTableView.getColumns().add(descriptionColumn);
            mTalkgroupTableView.getColumns().add(importStatusColumn);
            mTalkgroupTableView.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, selected) -> refreshSelectedTalkgroupEditor());
            mTalkgroupTableView.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener.Change<? extends AliasedTalkgroup> change) -> {
                    clearImportResult();
                    updateImportButtonState();
                });
            mTalkgroupFilteredList = new FilteredList<>(mTalkgroupList);
            SortedList<AliasedTalkgroup> sortedList = new SortedList<>(mTalkgroupFilteredList);
            sortedList.comparatorProperty().bind(mTalkgroupTableView.comparatorProperty());
            mTalkgroupTableView.setItems(sortedList);
        }

        return mTalkgroupTableView;
    }

    public class TalkgroupCategoryListCell extends ListCell<TalkgroupCategory>
    {
        @Override
        protected void updateItem(TalkgroupCategory item, boolean empty)
        {
            super.updateItem(item, empty);
            setText((empty || item == null) ? null : item.getName());
        }
    }

    public class TalkgroupCategoryStringConverter extends StringConverter<TalkgroupCategory>
    {
        @Override
        public String toString(TalkgroupCategory cat)
        {
            return cat != null ? cat.getName() : null;
        }

        @Override
        public TalkgroupCategory fromString(String string)
        {
            for(TalkgroupCategory cat: getTalkgroupCategoryComboBox().getItems())
            {
                if(cat.getName().contentEquals(string))
                {
                    return cat;
                }
            }

            return null;
        }
    }

    public class TalkgroupFilter implements Predicate<AliasedTalkgroup>
    {
        private String mFilterText;
        private Integer mCategory;

        public void setFilterText(String filterText)
        {
            mFilterText = filterText != null ? filterText.toLowerCase() : null;
        }

        public void setCategory(Integer category)
        {
            mCategory = category;
        }

        @Override
        public boolean test(AliasedTalkgroup aliasedTalkgroup)
        {
            if(mCategory == null && (mFilterText == null  || mFilterText.isEmpty()))
            {
                return true;
            }

            Talkgroup talkgroup = aliasedTalkgroup.getTalkgroup();

            if(mCategory != null && mFilterText != null)
            {
                if(talkgroup.getTalkgroupCategoryId() != mCategory)
                {
                    return false;
                }

                return (aliasedTalkgroup.descriptionProperty().get() != null &&
                        aliasedTalkgroup.descriptionProperty().get().toLowerCase().contains(mFilterText)) ||
                    (aliasedTalkgroup.talkgroupProperty().get() != null &&
                        aliasedTalkgroup.talkgroupProperty().get().toLowerCase().contains(mFilterText)) ||
                    (aliasedTalkgroup.alphaTagProperty().get() != null &&
                        aliasedTalkgroup.alphaTagProperty().get().toLowerCase().contains(mFilterText));
            }
            else if(mCategory != null)
            {
                return talkgroup.getTalkgroupCategoryId() == mCategory;
            }
            else
            {
                if(aliasedTalkgroup.descriptionProperty().get() != null &&
                    aliasedTalkgroup.descriptionProperty().get().toLowerCase().contains(mFilterText))
                {
                    return true;
                }

                if(aliasedTalkgroup.talkgroupProperty().get() != null &&
                    aliasedTalkgroup.talkgroupProperty().get().toLowerCase().contains(mFilterText))
                {
                    return true;
                }

                return aliasedTalkgroup.alphaTagProperty().get() != null &&
                    aliasedTalkgroup.alphaTagProperty().get().toLowerCase().contains(mFilterText);
            }
        }
    }

    enum ImportStatus
    {
        NOT_COMPATIBLE("Not Compatible"),
        NOT_PRESENT("Not Present"),
        IDENTICAL("Identical"),
        DIFFERENT("Different");

        private final String mDisplayText;

        ImportStatus(String displayText)
        {
            mDisplayText = displayText;
        }

        @Override
        public String toString()
        {
            return mDisplayText;
        }
    }

    /**
     * Compares only fields populated by the RadioReference importer.  Local presentation and playback settings are
     * intentionally excluded.
     */
    static ImportStatus getImportStatus(Alias alias, Talkgroup talkgroup, TalkgroupCategory category)
    {
        if(alias == null)
        {
            return ImportStatus.NOT_PRESENT;
        }

        boolean identical = normalizedEquals(alias.getName(), talkgroup.getAlphaTag()) &&
            normalizedEquals(alias.getDescription(), talkgroup.getDescription());

        if(category != null)
        {
            identical &= normalizedEquals(alias.getGroup(), category.getName());
        }

        return identical ? ImportStatus.IDENTICAL : ImportStatus.DIFFERENT;
    }

    static ImportStatus getImportStatus(boolean compatible, Alias alias, Talkgroup talkgroup,
                                        TalkgroupCategory category)
    {
        return compatible ? getImportStatus(alias, talkgroup, category) : ImportStatus.NOT_COMPATIBLE;
    }

    static List<ImportedFieldChange> getImportedFieldChanges(Alias alias, Talkgroup talkgroup,
                                                              TalkgroupCategory category)
    {
        List<ImportedFieldChange> changes = new ArrayList<>();

        if(alias != null && talkgroup != null)
        {
            addChange(changes, "Name", alias.getName(), talkgroup.getAlphaTag());
            addChange(changes, "Description", alias.getDescription(), talkgroup.getDescription());

            if(category != null)
            {
                addChange(changes, "Group", alias.getGroup(), category.getName());
            }
        }

        return List.copyOf(changes);
    }

    static Alias createRadioReferenceReplacement(Alias alias, Talkgroup talkgroup, TalkgroupCategory category)
    {
        if(alias == null || talkgroup == null)
        {
            throw new IllegalArgumentException("Alias and RadioReference talkgroup are required");
        }

        Alias replacement = AliasFactory.copyOf(alias);
        replacement.setId(alias.getId());
        replacement.setName(talkgroup.getAlphaTag());
        replacement.setDescription(talkgroup.getDescription());

        if(category != null)
        {
            replacement.setGroup(category.getName());
        }

        return replacement;
    }

    private static void addChange(List<ImportedFieldChange> changes, String field, String oldValue, String newValue)
    {
        if(!normalizedEquals(oldValue, newValue))
        {
            changes.add(new ImportedFieldChange(field, normalize(oldValue), normalize(newValue)));
        }
    }

    record ImportedFieldChange(String field, String oldValue, String newValue)
    {
        String display()
        {
            return field + ": " + displayValue(oldValue) + " \u2192 " + displayValue(newValue);
        }

        private static String displayValue(String value)
        {
            return value == null ? "(blank)" : value;
        }
    }

    private static boolean normalizedEquals(String first, String second)
    {
        return Objects.equals(normalize(first), normalize(second));
    }

    private static String normalize(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Wrapper class for talkgroups and correlated aliases
     */
    public class AliasedTalkgroup
    {
        private Talkgroup mTalkgroup;
        private Alias mAlias;
        private StringProperty mAlphaTagProperty = new SimpleStringProperty();
        private StringProperty mDescriptionProperty = new SimpleStringProperty();
        private ObjectProperty<ImportStatus> mImportStatusProperty = new SimpleObjectProperty<>();
        private StringProperty mTalkgroupProperty = new SimpleStringProperty();

        public AliasedTalkgroup(Talkgroup talkgroup, Alias alias)
        {
            mTalkgroup = talkgroup;
            mAlphaTagProperty.setValue(mTalkgroup.getAlphaTag());
            mDescriptionProperty.setValue(mTalkgroup.getDescription());
            setAlias(alias);
            updateTalkgroup();
        }

        public boolean hasAlias()
        {
            return mAlias != null;
        }

        public ImportStatus getImportStatus()
        {
            return mImportStatusProperty.get();
        }

        public Alias getAlias()
        {
            return mAlias;
        }

        public int getTalkgroupValue()
        {
            return getRadioReferenceDecoder().getTalkgroupValue(mTalkgroup);
        }

        public void updateTalkgroup()
        {
            mTalkgroupProperty.set(getRadioReferenceDecoder().format(mTalkgroup, getCurrentSystem()));
        }

        public StringProperty alphaTagProperty()
        {
            return mAlphaTagProperty;
        }

        public StringProperty descriptionProperty()
        {
            return mDescriptionProperty;
        }

        public ObjectProperty<ImportStatus> importStatusProperty()
        {
            return mImportStatusProperty;
        }

        public StringProperty talkgroupProperty()
        {
            return mTalkgroupProperty;
        }

        public Talkgroup getTalkgroup()
        {
            return mTalkgroup;
        }

        public void setAlias(Alias alias)
        {
            mAlias = alias;
            mImportStatusProperty.setValue(SystemTalkgroupSelectionEditor.getImportStatus(
                isCurrentSystemSupported(), mAlias, mTalkgroup, getTalkgroupCategory(mTalkgroup)));
        }
    }

    /**
     * Observable list change listener to detect alias changes and update the talkgroup and alias table
     */
    public class AliasListChangeListener implements ListChangeListener<Alias>
    {
        @Override
        public void onChanged(ListChangeListener.Change<? extends Alias> change)
        {
            clearImportResult();
            mAliasMatchRefresh.request(Platform::runLater, SystemTalkgroupSelectionEditor.this::refreshAliasMatches);
        }
    }

    /**
     * Coalesces synchronous alias notifications into one refresh after the alias lookup index has been rebuilt.
     */
    static class DeferredRefresh
    {
        private boolean mPending;

        void request(Consumer<Runnable> scheduler, Runnable refresh)
        {
            if(!mPending)
            {
                mPending = true;
                scheduler.accept(() -> {
                    mPending = false;
                    refresh.run();
                });
            }
        }
    }
}
