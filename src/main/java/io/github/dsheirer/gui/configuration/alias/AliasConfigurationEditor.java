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
import io.github.dsheirer.gui.control.MaxLengthUnaryOperator;
import io.github.dsheirer.gui.configuration.Editor;
import io.github.dsheirer.gui.configuration.IAliasListRefreshListener;
import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
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
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
    private boolean mAliasSelectionRefreshPending;
    private boolean mAliasSelectionRefreshRequested;

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
        getAliasTableView().getSelectionModel().clearSelection();
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
            getAliasListNameComboBox().getSelectionModel().select(alias.getAliasListName());
            getAliasTableView().getSelectionModel().clearSelection();
            getAliasTableView().getSelectionModel().select(alias);
            getAliasTableView().scrollTo(alias);
        }
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
        //Prompt the user to save if the contents of the current channel editor have been modified
        if(getAliasItemEditor().modifiedProperty().get())
        {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.getButtonTypes().clear();
            alert.getButtonTypes().addAll(ButtonType.NO, ButtonType.YES);
            alert.setTitle("Save Changes");
            alert.setHeaderText("Alias configuration has been modified");
            alert.setContentText("Do you want to save these changes?");
            alert.initOwner((getButtonBox()).getScene().getWindow());

            //Workaround for JavaFX KDE on Linux bug in FX 10/11: https://bugs.openjdk.java.net/browse/JDK-8179073
            alert.setResizable(true);
            alert.onShownProperty().addListener(e ->
                Platform.runLater(() -> alert.setResizable(false)));

            Optional<ButtonType> result = alert.showAndWait();

            if(result.isPresent() && result.get() == ButtonType.YES)
            {
                getAliasItemEditor().save();
            }
        }

        //Saving can change a sorted/filtered field and therefore alter the table selection.  Read the final selection
        //only after the draft has been resolved so the editor cannot be left showing the pre-save row.
        List<Alias> aliases = new ArrayList<>(getAliasTableView().getSelectionModel().getSelectedItems());

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
    }

    private AliasItemEditor getAliasItemEditor()
    {
        if(mAliasItemEditor == null)
        {
            mAliasItemEditor = new AliasItemEditor(mConfigurationManager, mUserPreferences);
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
        getAliasFilteredList().setPredicate(new AliasPredicate(
            getAliasListNameComboBox().getSelectionModel().getSelectedItem(), getSearchField().getText()));
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
                        AliasListDefinition definition = getAliasListDefinition(newValue);
                        getNewAliasButton().setDisable(definition == null ||
                            AliasMatchRegistry.allowed(definition).isEmpty());

                        if(oldValue != null)
                        {
                            getAliasTableView().getSelectionModel().clearSelection();
                            scheduleAliasSelectionRefresh();
                        }

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

            TableColumn<Alias, Integer> priorityColumn = new TableColumn<>("Listen");
            priorityColumn.setCellFactory(new PriorityCellFactory());
            priorityColumn.setCellValueFactory(new PropertyValueFactory<>("priority"));

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

            TableColumn<Alias, String> identifierColumn = new TableColumn<>("Identifier");
            identifierColumn.setPrefWidth(220);
            identifierColumn.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(
                features.getValue().getMatchIdentifier() != null ?
                    features.getValue().getMatchIdentifier().toString() : ""));

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
            mAliasTableView.getColumns().add(priorityColumn);
            mAliasTableView.getColumns().add(recordColumn);
            mAliasTableView.getColumns().add(streamColumn);
            mAliasTableView.getColumns().add(typeColumn);
            mAliasTableView.getColumns().add(identifierColumn);
            mAliasTableView.getColumns().add(errorsColumn);

            mAliasTableView.setPlaceholder(getPlaceholderLabel());
            mAliasTableView.setItems(getAliasSortedList());
            mAliasTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            mAliasTableView.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener.Change<? extends Alias> c) -> scheduleAliasSelectionRefresh());
        }

        return mAliasTableView;
    }

    private void scheduleAliasSelectionRefresh()
    {
        mAliasSelectionRefreshRequested = true;

        if(!mAliasSelectionRefreshPending)
        {
            mAliasSelectionRefreshPending = true;
            Platform.runLater(() ->
            {
                mAliasSelectionRefreshRequested = false;

                try
                {
                    setAliases();
                }
                finally
                {
                    mAliasSelectionRefreshPending = false;

                    //A save or sorted-list mutation can raise another selection event while this refresh is active.
                    //Run one more coalesced pass so that the last table selection always wins.
                    if(mAliasSelectionRefreshRequested)
                    {
                        scheduleAliasSelectionRefresh();
                    }
                }
            });
        }
    }

    private FilteredList<Alias> getAliasFilteredList()
    {
        if(mAliasFilteredList == null)
        {
            mAliasFilteredList = new FilteredList<>(mConfigurationManager.getAliasModel().aliasList(),
                new AliasPredicate(getAliasListNameComboBox().getSelectionModel().getSelectedItem(),
                    getSearchField().getText()));
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
            mNewAliasButton.setMaxWidth(Double.MAX_VALUE);
            mNewAliasButton.setOnAction(event ->
            {
                Alias alias = new Alias("New Alias");
                AliasListDefinition definition = getAliasListDefinition(
                    getAliasListNameComboBox().getSelectionModel().getSelectedItem());

                if(definition == null || AliasMatchRegistry.allowed(definition).isEmpty())
                {
                    return;
                }

                alias.setAliasListDefinition(definition);
                alias.setMatchIdentifier(AliasMatchRegistry.allowed(definition).getFirst().create(definition));
                mConfigurationManager.getAliasModel().addAlias(alias);

                //Queue a select alias action to allow table to update filter predicate and display the alias
                Platform.runLater(() ->
                {
                    getAliasTableView().getSelectionModel().clearSelection();
                    getAliasTableView().getSelectionModel().select(alias);
                    getAliasTableView().scrollTo(alias);
                });
            });
        }

        return mNewAliasButton;
    }

    private Button getDeleteAliasButton()
    {
        if(mDeleteAliasButton == null)
        {
            mDeleteAliasButton = new Button("Delete");
            mDeleteAliasButton.setDisable(true);
            mDeleteAliasButton.setMaxWidth(Double.MAX_VALUE);
            mDeleteAliasButton.setOnAction(event ->
            {
                int count = getAliasTableView().getSelectionModel().getSelectedItems().size();

                Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                        "Do you want to delete [" + count + "] selected alias" + ((count > 1) ? "es?" : "?"),
                        ButtonType.NO, ButtonType.YES);
                alert.setTitle("Delete Alias");
                alert.setHeaderText("Are you sure?");
                alert.initOwner((getDeleteAliasButton()).getScene().getWindow());

                Optional<ButtonType> result = alert.showAndWait();

                if(result.isPresent() && result.get() == ButtonType.YES)
                {
                    List<Alias> selectedAliases = new ArrayList<>(getAliasTableView().getSelectionModel().getSelectedItems());
                    mConfigurationManager.getAliasModel().removeAliases(selectedAliases);
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
            mCloneAliasButton.setMaxWidth(Double.MAX_VALUE);
            mCloneAliasButton.setOnAction(event ->
            {
                Alias original = getAliasTableView().getSelectionModel().getSelectedItem();
                Alias copy = AliasFactory.copyOf(original);
                mConfigurationManager.getAliasModel().addAlias(copy);
                getAliasTableView().getSelectionModel().clearSelection();
                getAliasTableView().getSelectionModel().select(copy);
                getAliasTableView().scrollTo(copy);
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

    public class MoveToAliasListItem extends MenuItem
    {
        private final AliasListDefinition mDefinition;

        public MoveToAliasListItem(AliasListDefinition definition)
        {
            super(definition.getName());
            mDefinition = definition;

            setOnAction(event ->
            {
                List<Alias> selectedAliases = new ArrayList<>(getAliasTableView().getSelectionModel().getSelectedItems());
                for(Alias selected : selectedAliases)
                {
                    moveAlias(selected, mDefinition);
                }
            });
        }
    }

    private void moveAlias(Alias alias, AliasListDefinition definition)
    {
        alias.setAliasListDefinition(definition);
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

    public class PriorityCellFactory implements Callback<TableColumn<Alias, Integer>, TableCell<Alias, Integer>>
    {
        @Override
        public TableCell<Alias, Integer> call(TableColumn<Alias, Integer> param)
        {
            return new TableCell<Alias, Integer>()
            {
                @Override
                protected void updateItem(Integer item, boolean empty)
                {
                    if(empty)
                    {
                        setText(null);
                        setGraphic(null);
                    }
                    else if(item == io.github.dsheirer.alias.id.priority.Priority.DO_NOT_MONITOR)
                    {
                        setText("Mute");
                        final IconNode iconNode = new IconNode(FontAwesome.VOLUME_OFF);
                        iconNode.setIconSize(20);
                        iconNode.setFill(Color.RED);
                        setGraphic(iconNode);
                    }
                    else if(item == io.github.dsheirer.alias.id.priority.Priority.DEFAULT_PRIORITY)
                    {
                        setText("Default");
                        final IconNode iconNode = new IconNode(FontAwesome.VOLUME_UP);
                        iconNode.setIconSize(20);
                        iconNode.setFill(Color.GREEN);
                        setGraphic(iconNode);
                    }
                    else
                    {
                        setText(item.toString());
                        final IconNode iconNode = new IconNode(FontAwesome.VOLUME_UP);
                        iconNode.setIconSize(20);
                        iconNode.setFill(Color.GREEN);
                        setGraphic(iconNode);
                    }
                }
            };
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

}
