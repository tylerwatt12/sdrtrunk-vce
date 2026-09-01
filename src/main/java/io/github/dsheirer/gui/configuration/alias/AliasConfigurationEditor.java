/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.gui.configuration.alias;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.gui.control.MaxLengthUnaryOperator;
import io.github.dsheirer.gui.configuration.Editor;
import io.github.dsheirer.gui.configuration.IAliasListRefreshListener;
import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Callback;
import jiconfont.IconCode;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;
import org.controlsfx.control.textfield.TextFields;

/**
 * Editor for aliases
 */
public class AliasConfigurationEditor extends SplitPane implements IAliasListRefreshListener
{

    private ConfigurationManager mConfigurationManager;
    private UserPreferences mUserPreferences;
    private AliasItemEditor mAliasItemEditor;
    private AliasBulkEditor mAliasBulkEditor;
    private Editor<?> mCurrentEditor;
    private TableView<Alias> mAliasTableView;
    private Label mPlaceholderLabel;
    private Button mNewAliasButton;
    private Button mDeleteAliasButton;
    private Button mCloneAliasButton;
    private MenuButton mMoveToAliasButton;
    private VBox mButtonBox;
    private HBox mSearchAndListSelectionBox;
    private TextField mSearchField;
    private ComboBox<String> mAliasListNameComboBox;
    private Button mNewAliasListButton;
    private Button mDeleteAliasListButton;
    private FilteredList<Alias> mAliasFilteredList;
    private SortedList<Alias> mAliasSortedList;
    private AliasPredicate mAliasPredicate;
    private boolean mIgnoreAliasSelectionChanges;
    private boolean mAliasSelectionRefreshPending;
    private long mAliasSelectionSequence;
    private long mLatestUserAliasSelectionSequence;
    private long mLastProgrammaticAliasSelectionSequence;

    /**
     * Constructs an instance
     * @param configurationManager for configuration operations
     * @param userPreferences for user preferences
     */
    public AliasConfigurationEditor(ConfigurationManager configurationManager, UserPreferences userPreferences)
    {
        mConfigurationManager = configurationManager;
        mConfigurationManager.addAliasListRefreshListener(this);
        mUserPreferences = userPreferences;

        VBox leftBox = new VBox();
        VBox.setVgrow(getAliasTableView(), Priority.ALWAYS);
        leftBox.getChildren().addAll(getSearchAndListSelectionBox(), getAliasTableView());

        HBox topBox = new HBox();
        HBox.setHgrow(leftBox, Priority.ALWAYS);
        topBox.getChildren().addAll(leftBox, getButtonBox());

        setOrientation(Orientation.VERTICAL);
        mCurrentEditor = getAliasItemEditor();
        getItems().addAll(topBox, getAliasItemEditor());
    }

    /**
     * Prepares for an alias list refresh by clearing the currently selected alias item from the editor.
     */
    @Override
    public void prepareForAliasListRefresh()
    {
        beginProgrammaticAliasPresentation();
        mIgnoreAliasSelectionChanges = true;

        try
        {
            getAliasTableView().getSelectionModel().clearSelection();
            getAliasItemEditor().setItem(null);
        }
        finally
        {
            mIgnoreAliasSelectionChanges = false;
        }
    }

    /**
     * Request to show the specified alias in the editor.
     * <p>
     * Note: this must be called on the FX platform thread
     *
     * @param alias to show
     */
    public void show(Alias alias)
    {
        if(alias != null)
        {
            if(alias.getId() > Alias.UNASSIGNED_ID)
            {
                selectAliasById(alias.getId());
            }
            else
            {
                showAliasDraft(alias);
            }
        }
    }

    private void selectAliasById(long aliasId)
    {
        Platform.runLater(() ->
        {
            Alias alias = mConfigurationManager.getAliasModel().getAlias(aliasId);

            if(alias != null)
            {
                selectAliases(List.of(alias));
            }
        });
    }

    private void selectAliases(List<Alias> aliases)
    {
        beginProgrammaticAliasPresentation();
        mIgnoreAliasSelectionChanges = true;

        try
        {
            getAliasTableView().getSelectionModel().clearSelection();

            for(Alias alias: aliases)
            {
                getAliasListNameComboBox().getSelectionModel().select(alias.getAliasListName());
                getAliasTableView().getSelectionModel().select(alias);
            }
        }
        finally
        {
            mIgnoreAliasSelectionChanges = false;
        }

        if(!aliases.isEmpty())
        {
            getAliasTableView().scrollTo(aliases.getFirst());
        }

        presentAliases(aliases);
    }

    /**
     * Sets the editor as the bottom alias editor, either single alias or bulk alias editor.
     */
    private void setEditor(Editor<?> editor)
    {
        if(editor != mCurrentEditor)
        {
            getItems().remove(mCurrentEditor);
            mCurrentEditor = editor;
            getItems().add(mCurrentEditor);
        }
    }

    private void setAliases()
    {
        List<Alias> selectedAliases = snapshotAliasSelection();
        Alias editedBeforeSave = getAliasItemEditor().getItem();

        if(!resolveModifiedAliasDraft())
        {
            restoreEditedAliasSelection();
            return;
        }

        selectAliases(resolveLiveAliases(selectedAliases, editedBeforeSave));
    }

    private List<Alias> snapshotAliasSelection()
    {
        return new ArrayList<>(getAliasTableView().getSelectionModel().getSelectedItems());
    }

    private List<Alias> resolveLiveAliases(List<Alias> selectedAliases, Alias editedBeforeSave)
    {
        List<Alias> aliases = new ArrayList<>(selectedAliases.size());
        Alias editedAfterSave = getAliasItemEditor().getItem();

        for(Alias selected: selectedAliases)
        {
            Alias alias = selected.getId() > Alias.UNASSIGNED_ID ?
                mConfigurationManager.getAliasModel().getAlias(selected.getId()) :
                mConfigurationManager.getAliasModel().getAliases().stream()
                    .filter(candidate -> candidate == selected).findFirst().orElse(null);

            if(alias == null && selected == editedBeforeSave && editedAfterSave != null &&
                editedAfterSave.getId() > Alias.UNASSIGNED_ID)
            {
                alias = mConfigurationManager.getAliasModel().getAlias(editedAfterSave.getId());
            }

            if(alias != null)
            {
                aliases.add(alias);
            }
        }

        return aliases;
    }

    private void restoreEditedAliasSelection()
    {
        Alias edited = getAliasItemEditor().getItem();

        if(edited == null)
        {
            return;
        }

        Alias live = edited.getId() > Alias.UNASSIGNED_ID ?
            mConfigurationManager.getAliasModel().getAlias(edited.getId()) : null;
        beginProgrammaticAliasPresentation();
        mIgnoreAliasSelectionChanges = true;

        try
        {
            getAliasTableView().getSelectionModel().clearSelection();

            if(live != null)
            {
                getAliasTableView().getSelectionModel().select(live);
                getAliasTableView().scrollTo(live);
            }
            else if(edited.getId() == Alias.UNASSIGNED_ID && edited.getAliasListName() != null)
            {
                getAliasListNameComboBox().getSelectionModel().select(edited.getAliasListName());
            }
        }
        finally
        {
            mIgnoreAliasSelectionChanges = false;
        }
    }

    private void beginProgrammaticAliasPresentation()
    {
        mLastProgrammaticAliasSelectionSequence = ++mAliasSelectionSequence;
    }

    private boolean resolveModifiedAliasDraft()
    {
        if(!getAliasItemEditor().modifiedProperty().get())
        {
            return true;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.getButtonTypes().clear();
        alert.getButtonTypes().addAll(ButtonType.NO, ButtonType.YES);
        alert.setTitle("Save Changes");
        alert.setHeaderText("Alias configuration has been modified");
        alert.setContentText("Do you want to save these changes?");
        alert.initOwner(getButtonBox().getScene().getWindow());
        alert.setResizable(true);
        alert.onShownProperty().addListener(e -> Platform.runLater(() -> alert.setResizable(false)));

        Optional<ButtonType> result = alert.showAndWait();
        return result.isEmpty() || result.get() != ButtonType.YES || getAliasItemEditor().save(false);
    }

    private void presentAliases(List<Alias> aliases)
    {
        if(aliases.size() <= 1)
        {
            setEditor(getAliasItemEditor());
            if(aliases.size() == 1)
            {
                getAliasItemEditor().setItem(aliases.get(0));
            }
            else
            {
                getAliasItemEditor().setItem(null);
            }
        }
        else
        {
            setEditor(getAliasBulkEditor());
            getAliasBulkEditor().setItem(aliases);
        }

        getCloneAliasButton().setDisable(aliases.size() != 1);
        getDeleteAliasButton().setDisable(aliases.isEmpty());
        getMoveToAliasButton().setDisable(aliases.isEmpty());
        updateNewAliasButtonState();
    }

    private void showAliasDraft(Alias draft)
    {
        beginProgrammaticAliasPresentation();
        mIgnoreAliasSelectionChanges = true;

        try
        {
            getAliasTableView().getSelectionModel().clearSelection();
        }
        finally
        {
            mIgnoreAliasSelectionChanges = false;
        }

        setEditor(getAliasItemEditor());
        getAliasItemEditor().setItem(draft);
        getCloneAliasButton().setDisable(true);
        getDeleteAliasButton().setDisable(true);
        getMoveToAliasButton().setDisable(true);
        updateNewAliasButtonState();
    }

    private void updateNewAliasButtonState()
    {
        if(mNewAliasButton == null)
        {
            return;
        }

        Alias current = mAliasItemEditor != null ? mAliasItemEditor.getItem() : null;
        AliasListDefinition definition = getAliasListDefinition(
            getAliasListNameComboBox().getSelectionModel().getSelectedItem());
        mNewAliasButton.setDisable(current != null && current.getId() == Alias.UNASSIGNED_ID ||
            definition == null || AliasMatchRegistry.allowed(definition).isEmpty());
    }

    private AliasItemEditor getAliasItemEditor()
    {
        if(mAliasItemEditor == null)
        {
            mAliasItemEditor = new AliasItemEditor(mConfigurationManager, mUserPreferences,
                alias -> selectAliasById(alias.getId()), () -> showPersistenceError("Save Alias"));
        }

        return mAliasItemEditor;
    }

    private AliasBulkEditor getAliasBulkEditor()
    {
        if(mAliasBulkEditor == null)
        {
            mAliasBulkEditor = new AliasBulkEditor(mConfigurationManager);
        }

        return mAliasBulkEditor;
    }

    private HBox getSearchAndListSelectionBox()
    {
        if(mSearchAndListSelectionBox == null)
        {
            mSearchAndListSelectionBox = new HBox();
            mSearchAndListSelectionBox.setAlignment(Pos.CENTER_LEFT);
            mSearchAndListSelectionBox.setPadding(new Insets(10, 0, 10, 10));
            mSearchAndListSelectionBox.setSpacing(5);


            Label listLabel = new Label("Alias List");
            Label searchLabel = new Label("Search");
            searchLabel.setAlignment(Pos.CENTER_RIGHT);

            HBox searchBox = new HBox();
            searchBox.setSpacing(5);
            searchBox.getChildren().addAll(searchLabel, getSearchField());
            HBox.setHgrow(searchBox, Priority.ALWAYS);
            searchBox.setAlignment(Pos.BASELINE_RIGHT);

            mSearchAndListSelectionBox.getChildren().addAll(listLabel, getAliasListNameComboBox(),
                getNewAliasListButton(), getDeleteAliasListButton(), searchBox);
        }

        return mSearchAndListSelectionBox;
    }

    private TextField getSearchField()
    {
        if(mSearchField == null)
        {
            mSearchField = TextFields.createClearableTextField();
            mSearchField.textProperty().addListener((observable, oldValue, newValue) -> update());
        }

        return mSearchField;
    }

    private void update()
    {
        getAliasFilteredList().setPredicate(null);
        getAliasPredicate().setAliasListName(getAliasListNameComboBox().getSelectionModel().getSelectedItem());
        getAliasPredicate().setSearchText(getSearchField().getText());
        getAliasFilteredList().setPredicate(getAliasPredicate());
    }

    private AliasPredicate getAliasPredicate()
    {
        if(mAliasPredicate == null)
        {
            mAliasPredicate = new AliasPredicate();
            mAliasPredicate.setAliasListName(getAliasListNameComboBox().getSelectionModel().getSelectedItem());
        }

        return mAliasPredicate;
    }

    private AliasListDefinition getAliasListDefinition(String name)
    {
        if(name == null)
        {
            return null;
        }

        return mConfigurationManager.getAliasModel().getAliasListDefinition(name);
    }

    private ComboBox<String> getAliasListNameComboBox()
    {
        if(mAliasListNameComboBox == null)
        {
            mAliasListNameComboBox = new ComboBox<>(mConfigurationManager.getAliasModel().aliasListNames());
            mAliasListNameComboBox.getSelectionModel().selectedItemProperty()
                    .addListener((observable, oldValue, newValue) ->
                    {
                        updateNewAliasButtonState();
                        update();
                    });

            if(!mAliasListNameComboBox.getItems().isEmpty())
            {
                mAliasListNameComboBox.getSelectionModel().select(0);
            }
        }

        return mAliasListNameComboBox;
    }

    private Button getNewAliasListButton()
    {
        if(mNewAliasListButton == null)
        {
            mNewAliasListButton = new Button("New Alias List");
            mNewAliasListButton.setOnAction(event ->
            {
                ChoiceDialog<AliasListFamily> familyDialog =
                    new ChoiceDialog<>(AliasListFamily.P25, AliasListFamily.values());
                familyDialog.setTitle("Create New Alias List");
                familyDialog.setHeaderText("Select the protocol family for this alias list.");
                familyDialog.setContentText("Protocol:");
                familyDialog.initOwner(getNewAliasListButton().getScene().getWindow());
                Optional<AliasListFamily> selectedFamily = familyDialog.showAndWait();

                if(selectedFamily.isEmpty())
                {
                    return;
                }

                TextInputDialog dialog = new TextInputDialog();
                dialog.setTitle("Create New Alias List");
                dialog.setHeaderText("Please enter an alias list name (max 25 chars).");
                dialog.setContentText("Name:");
                dialog.getEditor().setTextFormatter(new TextFormatter<String>(new MaxLengthUnaryOperator(25)));
                Optional<String> result = dialog.showAndWait();

                result.ifPresent(s ->
                {
                    String name = result.get();

                    if(name != null)
                    {
                        name = name.trim();

                        if(name.isEmpty())
                        {
                            return;
                        }

                        if(getAliasListDefinition(name) != null)
                        {
                            Alert alert = new Alert(Alert.AlertType.ERROR,
                                "An alias list named [" + name + "] already exists.", ButtonType.OK);
                            alert.setTitle("Create New Alias List");
                            alert.setHeaderText("Alias list name is already in use");
                            alert.initOwner(getNewAliasListButton().getScene().getWindow());
                            alert.showAndWait();
                            return;
                        }

                        mConfigurationManager.getAliasModel().addAliasListDefinition(
                            new AliasListDefinition(name, selectedFamily.get()));
                        getAliasListNameComboBox().getSelectionModel().select(name);
                    }
                });
            });
        }

        return mNewAliasListButton;
    }

    private Button getDeleteAliasListButton() {

        if (mDeleteAliasListButton == null) {
            mDeleteAliasListButton = new Button("Delete Alias List");
            mDeleteAliasListButton.setOnAction(event -> {
                String aliasListName = getAliasListNameComboBox().getSelectionModel().getSelectedItem();
                Alert alert = createDeleteAliasListAlert(Alert.AlertType.CONFIRMATION, ButtonType.NO, ButtonType.YES);
                alert.setHeaderText("Are you sure you want to delete the alias list " + aliasListName + " and all associated aliases?");

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent() && result.get() == ButtonType.YES)
                {
                    mConfigurationManager.deleteAliasList(aliasListName);
                }
            });
        }

        return mDeleteAliasListButton;
    }

    private Alert createDeleteAliasListAlert(Alert.AlertType alertType, ButtonType... buttonTypes)
    {
        Alert alert = new Alert(alertType, "", buttonTypes);
        alert.setTitle("Delete Alias List");
        alert.initOwner(getDeleteAliasListButton().getScene().getWindow());
        return alert;
    }


    private TableView<Alias> getAliasTableView()
    {
        if(mAliasTableView == null)
        {
            mAliasTableView = new TableView<>();

            TableColumn<Alias, String> nameColumn = new TableColumn<>();
            nameColumn.setText("Alias");
            nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
            nameColumn.setPrefWidth(140);

            TableColumn<Alias, String> descriptionColumn = new TableColumn<>();
            descriptionColumn.setText("Description");
            descriptionColumn.setCellValueFactory(new PropertyValueFactory<>("description"));
            descriptionColumn.setPrefWidth(260);

            TableColumn<Alias, String> groupColumn = new TableColumn<>();
            groupColumn.setText("Group");
            groupColumn.setCellValueFactory(new PropertyValueFactory<>("group"));
            groupColumn.setPrefWidth(140);

            TableColumn<Alias, Integer> colorColumn = new TableColumn<>("Color");
            colorColumn.setCellValueFactory(new PropertyValueFactory<>("color"));
            colorColumn.setCellFactory(new ColorizedCell());

            TableColumn<Alias, String> iconColumn = new TableColumn<>("Icon");
            iconColumn.setCellValueFactory(new PropertyValueFactory<>("iconName"));
            iconColumn.setCellFactory(new IconTableCellFactory());

            TableColumn<Alias, Boolean> listenColumn = new TableColumn<>("Listen");
            listenColumn.setCellFactory(new IconCell(FontAwesome.VOLUME_UP, Color.GREEN));
            listenColumn.setCellValueFactory(new PropertyValueFactory<>("listen"));

            TableColumn<Alias, Boolean> recordColumn = new TableColumn<>("Record");
            recordColumn.setCellValueFactory(new PropertyValueFactory<>("recordable"));
            recordColumn.setCellFactory(new IconCell(FontAwesome.SQUARE, Color.RED));

            TableColumn<Alias, Boolean> streamColumn = new TableColumn<>("Stream");
            streamColumn.setCellValueFactory(new PropertyValueFactory<>("streamable"));
            streamColumn.setCellFactory(new IconCell(FontAwesome.VOLUME_UP, Color.DARKBLUE));

            TableColumn<Alias, String> typeColumn = new TableColumn<>("Type");
            typeColumn.setPrefWidth(150);
            typeColumn.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(
                features.getValue().getMatchIdentifier() != null ?
                    features.getValue().getMatchIdentifier().getType().toString() : ""));

            TableColumn<Alias, AliasID> identifierColumn = new TableColumn<>("Identifier");
            identifierColumn.setPrefWidth(220);
            identifierColumn.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(
                features.getValue().getMatchIdentifier()));
            identifierColumn.setComparator(AliasConfigurationEditor::compareAliasIdentifiers);
            identifierColumn.setCellFactory(column -> new TableCell<>()
            {
                @Override
                protected void updateItem(AliasID item, boolean empty)
                {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.toString());
                }
            });

            TableColumn<Alias, Boolean> errorsColumn = new TableColumn<>("Error");
            errorsColumn.setPrefWidth(120);
            errorsColumn.setCellValueFactory(new PropertyValueFactory<>("overlap"));
            errorsColumn.setCellFactory(param -> new TableCell<>()
            {
                @Override
                protected void updateItem(Boolean item, boolean empty)
                {
                    setAlignment(Pos.CENTER);
                    setText(null);

                    if(empty || item == null || !item)
                    {
                        setGraphic(null);
                    }
                    else
                    {
                        IconNode iconNode = new IconNode(FontAwesome.EXCLAMATION_CIRCLE);
                        iconNode.setFill(Color.RED);
                        setGraphic(iconNode);
                        setText("Identifier Overlap");
                    }
                }
            });


            mAliasTableView.getColumns().add(nameColumn);
            mAliasTableView.getColumns().add(descriptionColumn);
            mAliasTableView.getColumns().add(groupColumn);
            mAliasTableView.getColumns().add(colorColumn);
            mAliasTableView.getColumns().add(iconColumn);
            mAliasTableView.getColumns().add(listenColumn);
            mAliasTableView.getColumns().add(recordColumn);
            mAliasTableView.getColumns().add(streamColumn);
            mAliasTableView.getColumns().add(typeColumn);
            mAliasTableView.getColumns().add(identifierColumn);
            mAliasTableView.getColumns().add(errorsColumn);

            mAliasTableView.setPlaceholder(getPlaceholderLabel());
            mAliasTableView.setItems(getAliasSortedList());
            mAliasTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            mAliasTableView.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener.Change<? extends Alias> c) ->
                {
                    if(!mIgnoreAliasSelectionChanges)
                    {
                        mLatestUserAliasSelectionSequence = ++mAliasSelectionSequence;

                        if(!mAliasSelectionRefreshPending)
                        {
                            mAliasSelectionRefreshPending = true;
                            Platform.runLater(() ->
                            {
                                try
                                {
                                    if(mLatestUserAliasSelectionSequence >
                                        mLastProgrammaticAliasSelectionSequence)
                                    {
                                        setAliases();
                                    }
                                }
                                finally
                                {
                                    mAliasSelectionRefreshPending = false;
                                }
                            });
                        }
                    }
                });
        }

        return mAliasTableView;
    }

    static int compareAliasIdentifiers(AliasID first, AliasID second)
    {
        if(first == second)
        {
            return 0;
        }
        if(first == null)
        {
            return 1;
        }
        if(second == null)
        {
            return -1;
        }

        int typeComparison = first.getType().compareTo(second.getType());
        if(typeComparison != 0)
        {
            return typeComparison;
        }

        int valueComparison = 0;
        if(first instanceof Talkgroup firstTalkgroup && second instanceof Talkgroup secondTalkgroup)
        {
            valueComparison = firstTalkgroup.compareTo(secondTalkgroup);
        }
        else if(first instanceof TalkgroupRange firstRange && second instanceof TalkgroupRange secondRange)
        {
            valueComparison = firstRange.compareTo(secondRange);
        }
        else if(first instanceof Radio firstRadio && second instanceof Radio secondRadio)
        {
            valueComparison = firstRadio.compareTo(secondRadio);
        }
        else if(first instanceof RadioRange firstRange && second instanceof RadioRange secondRange)
        {
            valueComparison = firstRange.compareTo(secondRange);
        }

        return valueComparison != 0 ? valueComparison : first.toString().compareToIgnoreCase(second.toString());
    }

    private FilteredList<Alias> getAliasFilteredList()
    {
        if(mAliasFilteredList == null)
        {
            mAliasFilteredList = new FilteredList<>(mConfigurationManager.getAliasModel().aliasList(), getAliasPredicate());
        }

        return mAliasFilteredList;
    }

    private SortedList<Alias> getAliasSortedList()
    {
        if(mAliasSortedList == null)
        {
            mAliasSortedList = new SortedList<>(getAliasFilteredList());
            mAliasSortedList.comparatorProperty().bind(getAliasTableView().comparatorProperty());

            //Don't re-sort while the bulk editor is still applying changes to aliases
            getAliasBulkEditor().changeInProgressProperty().addListener((observable, oldValue, newValue) ->
            {
                if(newValue)
                {
                    mAliasSortedList.comparatorProperty().unbind();
                    mAliasSortedList.setComparator(null);
                }
                else
                {
                    mAliasSortedList.comparatorProperty().bind(getAliasTableView().comparatorProperty());
                }
            });
        }

        return mAliasSortedList;
    }

    private Label getPlaceholderLabel()
    {
        if(mPlaceholderLabel == null)
        {
            mPlaceholderLabel = new Label("Select an Alias List and click the New button to create new aliases");
        }

        return mPlaceholderLabel;
    }

    private VBox getButtonBox()
    {
        if(mButtonBox == null)
        {
            mButtonBox = new VBox();
            mButtonBox.setPadding(new Insets(10, 10, 10, 10));
            mButtonBox.setSpacing(10);
            mButtonBox.setMinWidth(Region.USE_PREF_SIZE);

            Button fillerButton = new Button();
            fillerButton.setVisible(false);
            mButtonBox.getChildren().addAll(fillerButton, getNewAliasButton(), getCloneAliasButton(),
                    getMoveToAliasButton(), getDeleteAliasButton());
        }

        return mButtonBox;
    }

    private Button getNewAliasButton()
    {
        if(mNewAliasButton == null)
        {
            mNewAliasButton = new Button("New");
            mNewAliasButton.setDisable(true);
            mNewAliasButton.setAlignment(Pos.CENTER);
            configureAliasActionButton(mNewAliasButton, "Create a new Alias in the selected Alias List");
            mNewAliasButton.setOnAction(event ->
            {
                if(!resolveModifiedAliasDraft())
                {
                    return;
                }

                Alias alias = new Alias("New Alias");
                AliasListDefinition definition = getAliasListDefinition(
                    getAliasListNameComboBox().getSelectionModel().getSelectedItem());

                if(definition == null || AliasMatchRegistry.allowed(definition).isEmpty())
                {
                    return;
                }

                alias.setAliasListDefinition(definition);
                alias.setMatchIdentifier(AliasMatchRegistry.allowed(definition).getFirst().create(definition));
                showAliasDraft(alias);
            });
            updateNewAliasButtonState();
        }

        return mNewAliasButton;
    }

    private Button getDeleteAliasButton()
    {
        if(mDeleteAliasButton == null)
        {
            mDeleteAliasButton = new Button("Delete");
            mDeleteAliasButton.setDisable(true);
            configureAliasActionButton(mDeleteAliasButton, "Delete the selected Aliases");
            mDeleteAliasButton.setOnAction(event ->
            {
                List<Alias> selectedAliases =
                    new ArrayList<>(getAliasTableView().getSelectionModel().getSelectedItems());
                int count = selectedAliases.size();

                if(selectedAliases.isEmpty())
                {
                    return;
                }

                Alias edited = getAliasItemEditor().getItem();
                boolean deletesEditedAlias = selectedAliases.stream()
                    .anyMatch(alias -> sameAliasIdentity(alias, edited));

                if(!deletesEditedAlias && !resolveModifiedAliasDraft())
                {
                    return;
                }

                Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                        "Do you want to delete [" + count + "] selected alias" + ((count > 1) ? "es?" : "?"),
                        ButtonType.NO, ButtonType.YES);
                alert.setTitle("Delete Alias");
                alert.setHeaderText("Are you sure?");
                alert.initOwner((getDeleteAliasButton()).getScene().getWindow());

                Optional<ButtonType> result = alert.showAndWait();

                if(result.isPresent() && result.get() == ButtonType.YES)
                {
                    beginProgrammaticAliasPresentation();
                    mIgnoreAliasSelectionChanges = true;

                    try
                    {
                        if(!mConfigurationManager.commitAliasChanges(List.of(), selectedAliases))
                        {
                            showPersistenceError("Delete Alias");
                            return;
                        }

                        getAliasTableView().getSelectionModel().clearSelection();

                        if(deletesEditedAlias)
                        {
                            getAliasItemEditor().setItem(null);
                        }
                    }
                    finally
                    {
                        mIgnoreAliasSelectionChanges = false;
                    }

                    selectAliases(List.of());
                }
            });
        }

        return mDeleteAliasButton;
    }

    private Button getCloneAliasButton()
    {
        if(mCloneAliasButton == null)
        {
            mCloneAliasButton = new Button("Clone");
            mCloneAliasButton.setDisable(true);
            configureAliasActionButton(mCloneAliasButton, "Clone the selected Alias");
            mCloneAliasButton.setOnAction(event ->
            {
                Alias selected = getAliasTableView().getSelectionModel().getSelectedItem();
                Alias editedBeforeSave = getAliasItemEditor().getItem();

                if(selected == null || !resolveModifiedAliasDraft())
                {
                    return;
                }

                List<Alias> resolved = resolveLiveAliases(List.of(selected), editedBeforeSave);

                if(resolved.size() == 1)
                {
                    showAliasDraft(AliasFactory.copyOf(resolved.getFirst()));
                }
            });
        }

        return mCloneAliasButton;
    }

    private MenuButton getMoveToAliasButton()
    {
        if(mMoveToAliasButton == null)
        {
            mMoveToAliasButton = new MenuButton("Move To");
            mMoveToAliasButton.setDisable(true);
            configureAliasActionButton(mMoveToAliasButton, "Move the selected Aliases to another compatible Alias List");
            mMoveToAliasButton.setOnShowing(event ->
            {
                mMoveToAliasButton.getItems().clear();

                MenuItem emptyItem = new MenuItem("Alias Lists");
                emptyItem.setDisable(true);
                mMoveToAliasButton.getItems().addAll(emptyItem, new SeparatorMenuItem());

                List<Alias> selectedAliases =
                    new ArrayList<>(getAliasTableView().getSelectionModel().getSelectedItems());

                for(AliasListDefinition definition :
                    mConfigurationManager.getAliasModel().aliasListDefinitions())
                {
                    if(!definition.getName().contentEquals(
                        getAliasListNameComboBox().getSelectionModel().getSelectedItem()))
                    {
                        boolean canPreserve = selectedAliases.stream().allMatch(alias ->
                            AliasMatchRegistry.isOperational(definition, alias.getMatchIdentifier()));

                        if(canPreserve)
                        {
                            mMoveToAliasButton.getItems().add(new MoveToAliasListItem(definition));
                        }
                    }
                }

                if(mMoveToAliasButton.getItems().size() == 2)
                {
                    MenuItem none = new MenuItem("No compatible alias lists");
                    none.setDisable(true);
                    mMoveToAliasButton.getItems().add(none);
                }
            });
        }

        return mMoveToAliasButton;
    }

    private static void configureAliasActionButton(ButtonBase button, String tooltip)
    {
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setTooltip(new Tooltip(tooltip));
    }

    public class MoveToAliasListItem extends MenuItem
    {
        private final AliasListDefinition mDefinition;

        public MoveToAliasListItem(AliasListDefinition definition)
        {
            super(definition.getName());
            mDefinition = definition;

            setOnAction(event ->
            {
                List<Alias> selectedAliasSnapshot = snapshotAliasSelection();

                if(selectedAliasSnapshot.isEmpty())
                {
                    return;
                }

                Alias editedBeforeSave = getAliasItemEditor().getItem();

                if(!resolveModifiedAliasDraft())
                {
                    restoreEditedAliasSelection();
                    return;
                }

                List<Alias> selectedAliases = resolveLiveAliases(selectedAliasSnapshot, editedBeforeSave);

                if(selectedAliases.size() != selectedAliasSnapshot.size())
                {
                    showPersistenceError("Move Aliases");
                    return;
                }

                List<Alias> replacements = new ArrayList<>(selectedAliases.size());

                for(Alias selected: selectedAliases)
                {
                    Alias replacement = AliasFactory.copyOf(selected);
                    replacement.setId(selected.getId());
                    replacement.setAliasListDefinition(mDefinition);
                    replacements.add(replacement);
                }

                if(mConfigurationManager.commitAliasReplacements(selectedAliases, replacements))
                {
                    selectAliases(replacements.stream()
                        .map(replacement -> mConfigurationManager.getAliasModel().getAlias(replacement.getId()))
                        .filter(java.util.Objects::nonNull)
                        .toList());
                }
                else
                {
                    showPersistenceError("Move Aliases");
                }
            });
        }
    }

    private static boolean sameAliasIdentity(Alias first, Alias second)
    {
        return first == second || first != null && second != null &&
            first.getId() > Alias.UNASSIGNED_ID && first.getId() == second.getId();
    }

    private void showPersistenceError(String operation)
    {
        Alert alert = new Alert(Alert.AlertType.ERROR,
            "The Alias change could not be saved. No live Alias rows were changed.", ButtonType.OK);
        alert.setTitle(operation);
        alert.setHeaderText("Unable to save Alias configuration");
        alert.initOwner(getButtonBox().getScene().getWindow());
        alert.showAndWait();
    }

    public class ColorizedCell implements Callback<TableColumn<Alias, Integer>, TableCell<Alias, Integer>>
    {
        @Override
        public TableCell<Alias, Integer> call(TableColumn<Alias, Integer> param)
        {
            final Rectangle rectangle = new Rectangle(20, 20);
            rectangle.setArcHeight(10);
            rectangle.setArcWidth(10);

            TableCell<Alias, Integer> tableCell = new TableCell<>()
            {
                @Override
                protected void updateItem(Integer item, boolean empty)
                {
                    super.updateItem(item, empty);

                    if(!empty && getTableRow() != null)
                    {
                        Alias alias = getTableRow().getItem();

                        if(alias != null)
                        {
                            rectangle.setVisible(true);
                            rectangle.setFill(ColorUtil.fromInteger(alias.getColor()));
                        }
                        else
                        {
                            rectangle.setVisible(false);
                        }
                    }
                    else
                    {
                        rectangle.setVisible(false);
                    }
                }
            };
            tableCell.setAlignment(Pos.CENTER);
            tableCell.setGraphic(rectangle);

            return tableCell;
        }
    }

    /**
     * Boolean table cell with an icon visibility bound to the boolean value
     */
    public class IconCell implements Callback<TableColumn<Alias, Boolean>, TableCell<Alias, Boolean>>
    {
        private IconCode mIconCode;
        private Color mColor;

        public IconCell(IconCode iconCode, Color color)
        {
            mIconCode = iconCode;
            mColor = color;
        }

        @Override
        public TableCell<Alias, Boolean> call(TableColumn<Alias, Boolean> param)
        {
            final IconNode iconNode = new IconNode(mIconCode);
            iconNode.setIconSize(20);
            iconNode.setFill(mColor);

            TableCell<Alias, Boolean> tableCell = new TableCell<>()
            {
                @Override
                protected void updateItem(Boolean item, boolean empty)
                {
                    super.updateItem(item, empty);

                    if(!empty && getTableRow() != null)
                    {
                        iconNode.setVisible(item);
                    }
                    else
                    {
                        iconNode.setVisible(false);
                    }
                }
            };
            tableCell.setAlignment(Pos.CENTER);
            tableCell.setGraphic(iconNode);
            return tableCell;
        }
    }

    public class CenteredCountCellFactory implements Callback<TableColumn<Alias, Integer>, TableCell<Alias, Integer>>
    {
        @Override
        public TableCell<Alias, Integer> call(TableColumn<Alias, Integer> param)
        {
            return new CenteredCountCell();
        }
    }

    public class CenteredCountCell extends TableCell<Alias, Integer>
    {
        public CenteredCountCell()
        {
            setAlignment(Pos.CENTER);
        }
    }

    public class IconTableCellFactory implements Callback<TableColumn<Alias, String>, TableCell<Alias, String>>
    {
        @Override
        public TableCell<Alias, String> call(TableColumn<Alias, String> param)
        {
            return new TableCell<>()
            {
                @Override
                protected void updateItem(String item, boolean empty)
                {
                    super.updateItem(item, empty);
                    setAlignment(Pos.CENTER);

                    if(empty)
                    {
                        setGraphic(null);
                    }
                    else
                    {
                        if(getTableRow() != null)
                        {
                            Alias alias = getTableRow().getItem();

                            if(alias != null)
                            {
                                Icon icon = mConfigurationManager.getIconModel().getIcon(alias.getIconName());

                                if(icon != null && icon.getFxImage() != null)
                                {
                                    setGraphic(new ImageView(icon.getFxImage()));
                                }
                                else
                                {
                                    setGraphic(null);
                                }
                            }
                        }
                    }
                }
            };
        }
    }

    /**
     * Alias filter predicate
     */
    public class AliasPredicate implements Predicate<Alias>
    {
        private String mAliasListName;
        private String mSearchText;

        @Override
        public boolean test(Alias alias)
        {
            if(mAliasListName == null)
            {
                return false;
            }
            else if(mAliasListName.equals(alias.getAliasListName()))
            {
                return (alias.getName() != null && alias.getName().toLowerCase().contains(mSearchText)) ||
                        (alias.getDescription() != null &&
                            alias.getDescription().toLowerCase().contains(mSearchText)) ||
                        (alias.getGroup() != null && alias.getGroup().toLowerCase().contains(mSearchText));
            }

            return false;
        }

        public void setAliasListName(String aliasListName)
        {
            if(aliasListName != null)
            {
                mAliasListName = aliasListName;
            }
        }

        public void setSearchText(String searchText)
        {
            if(searchText != null)
            {
                mSearchText = searchText.toLowerCase();
            }
            else
            {
                mSearchText = null;
            }
        }
    }
}
