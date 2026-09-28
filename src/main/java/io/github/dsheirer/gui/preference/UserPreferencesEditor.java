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

package io.github.dsheirer.gui.preference;

import io.github.dsheirer.gui.preference.stats.WebServerPreferenceEditor;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.StatsWebServerService;
import java.util.EnumMap;
import java.util.Map;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Preferences editor dialog
 */
public class UserPreferencesEditor extends BorderPane
{

    private Map<PreferenceEditorType,Node> mEditors = new EnumMap<>(PreferenceEditorType.class);
    private UserPreferences mUserPreferences;
    private StatsWebServerService mStatsWebServerService;
    private MenuBar mMenuBar;
    private TreeView<Object> mEditorSelectionTreeView;
    private VBox mEditorAndButtonsBox;
    private Node mEditor;
    private HBox mButtonsBox;

    /**
     * Constructs an instance
     *
     * @param userPreferences to edit
     */
    public UserPreferencesEditor(UserPreferences userPreferences)
    {
        this(userPreferences, null);
    }

    public UserPreferencesEditor(UserPreferences userPreferences, StatsWebServerService statsWebServerService)
    {
        mUserPreferences = userPreferences;
        mStatsWebServerService = statsWebServerService;

        setTop(getMenuBar());

        HBox contentBox = new HBox();
        HBox.setHgrow(getEditorAndButtonsBox(), Priority.ALWAYS);
        contentBox.getChildren().addAll(getEditorSelectionTreeView(), getEditorAndButtonsBox());
        setCenter(contentBox);
    }

    public void setStatsWebServerService(StatsWebServerService statsWebServerService)
    {
        mStatsWebServerService = statsWebServerService;
        Node editor = mEditors.get(PreferenceEditorType.WEB_SERVER);

        if(editor instanceof WebServerPreferenceEditor webServerPreferenceEditor)
        {
            webServerPreferenceEditor.setStatsWebServerService(statsWebServerService);
        }
    }

    private VBox getEditorAndButtonsBox()
    {
        if(mEditorAndButtonsBox == null)
        {
            mEditorAndButtonsBox = new VBox();
            mEditor = getDefaultEditor();
            VBox.setVgrow(getDefaultEditor(), Priority.ALWAYS);
            VBox.setVgrow(getButtonsBox(), Priority.NEVER);
            mEditorAndButtonsBox.getChildren().addAll(getDefaultEditor(), getButtonsBox());
        }

        return mEditorAndButtonsBox;
    }

    private Node getDefaultEditor()
    {
        Node editor = mEditors.get(PreferenceEditorType.DEFAULT);

        if(editor == null)
        {
            VBox defaultEditor = new VBox();
            defaultEditor.setPadding(new Insets(10, 10, 10, 10));
            Label label = new Label("Please select a preference ...");
            defaultEditor.getChildren().add(label);
            mEditors.put(PreferenceEditorType.DEFAULT, defaultEditor);
            editor = defaultEditor;
        }

        return editor;
    }

    /**
     * Preference type selection list
     */
    private TreeView<Object> getEditorSelectionTreeView()
    {
        if(mEditorSelectionTreeView == null)
        {
            TreeItem<Object> treeRoot = new TreeItem<>("Root node");

            TreeItem<Object> applicationItem = new TreeItem<>("Application");
            applicationItem.getChildren().add(new TreeItem<>(PreferenceEditorType.APPEARANCE));
            treeRoot.getChildren().add(applicationItem);
            applicationItem.setExpanded(true);

            TreeItem<Object> cpuItem = new TreeItem<>("CPU");
            cpuItem.getChildren().add(new TreeItem<>(PreferenceEditorType.VECTOR_CALIBRATION));
            treeRoot.getChildren().add(cpuItem);
            cpuItem.setExpanded(true);

            TreeItem<Object> decoderItem = new TreeItem<>("Decoder");
            decoderItem.getChildren().add(new TreeItem<>(PreferenceEditorType.JMBE_LIBRARY));
            decoderItem.getChildren().add(new TreeItem<>(PreferenceEditorType.VOICE_DECRYPTION_MODULE));
            treeRoot.getChildren().add(decoderItem);
            decoderItem.setExpanded(true);

            TreeItem<Object> storageItem = new TreeItem<>("File Storage");
            storageItem.getChildren().add(new TreeItem<>(PreferenceEditorType.DIRECTORY));
            treeRoot.getChildren().add(storageItem);
            storageItem.setExpanded(true);

            TreeItem<Object> statsAndWebItem = new TreeItem<>("Stats & Web");
            statsAndWebItem.getChildren().add(new TreeItem<>(PreferenceEditorType.STATS_SERVER));
            statsAndWebItem.getChildren().add(new TreeItem<>(PreferenceEditorType.WEB_SERVER));
            treeRoot.getChildren().add(statsAndWebItem);
            statsAndWebItem.setExpanded(true);

            TreeItem<Object> sourceItem = new TreeItem<>("Source");
            sourceItem.getChildren().add(new TreeItem<>(PreferenceEditorType.SOURCE_TUNERS));
            treeRoot.getChildren().add(sourceItem);
            sourceItem.setExpanded(true);

            mEditorSelectionTreeView = new TreeView<>();
            mEditorSelectionTreeView.setShowRoot(false);
            mEditorSelectionTreeView.setRoot(treeRoot);
            treeRoot.setExpanded(true);
            mEditorSelectionTreeView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
            mEditorSelectionTreeView.getSelectionModel().selectedItemProperty().addListener(new EditorTreeSelectionListener());

            mEditorSelectionTreeView.setMinWidth(Control.USE_PREF_SIZE);
        }

        return mEditorSelectionTreeView;
    }

    /**
     * Control box with OK button.
     */
    private HBox getButtonsBox()
    {
        if(mButtonsBox == null)
        {
            mButtonsBox = new HBox();
            mButtonsBox.setMaxWidth(Double.MAX_VALUE);
            Button okButton = new Button("Ok");
            okButton.setOnAction(event -> {
                Stage stage = (Stage)getButtonsBox().getScene().getWindow();
                stage.close();
            });
            HBox.setMargin(okButton, new Insets(5, 5, 5, 5));
            mButtonsBox.setAlignment(Pos.CENTER_RIGHT);
            mButtonsBox.getChildren().add(okButton);
        }

        return mButtonsBox;
    }

    private MenuBar getMenuBar()
    {
        if(mMenuBar == null)
        {
            mMenuBar = new MenuBar();

            //File Menu
            Menu fileMenu = new Menu("File");

            MenuItem closeItem = new MenuItem("Close");
            closeItem.setOnAction(event -> getMenuBar().getParent().getScene().getWindow().hide());
            fileMenu.getItems().add(closeItem);

            mMenuBar.getMenus().add(fileMenu);

        }

        return mMenuBar;
    }

    /**
     * Listens for editor tree selection events and creates a preference editor instance for each type as needed.
     *
     * Constructed editors are cached for reuse.
     */
    public class EditorTreeSelectionListener implements ChangeListener<TreeItem<Object>>
    {
        @Override
        public void changed(ObservableValue<? extends TreeItem<Object>> observable, TreeItem<Object> oldValue, TreeItem<Object> newValue)
        {
            if(newValue != null)
            {
                Object value = newValue.getValue();

                if(value instanceof PreferenceEditorType)
                {
                    setEditor((PreferenceEditorType)value);
                    return;
                }
            }

            setEditor(PreferenceEditorType.DEFAULT);
        }

        private void setEditor(PreferenceEditorType type)
        {
            Node editor = mEditors.get(type);

            if(editor == null)
            {
                if(type == PreferenceEditorType.DEFAULT)
                {
                    editor = getDefaultEditor();
                }
                else
                {
                    editor = PreferenceEditorFactory.getEditor(type, getUserPreferences(), mStatsWebServerService);
                    mEditors.put(type, editor);
                }
            }

            getEditorAndButtonsBox().getChildren().remove(mEditor);
            VBox.setVgrow(editor, Priority.ALWAYS);
            mEditor = editor;
            getEditorAndButtonsBox().getChildren().add(0, mEditor);
        }

        private UserPreferences getUserPreferences()
        {
            if(mUserPreferences == null)
            {
                mUserPreferences = new UserPreferences();
            }

            return mUserPreferences;
        }
    }
}
