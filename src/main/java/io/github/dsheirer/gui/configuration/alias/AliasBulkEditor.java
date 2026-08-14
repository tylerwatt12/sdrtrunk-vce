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

package io.github.dsheirer.gui.configuration.alias;

import com.google.common.collect.Ordering;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.gui.configuration.Editor;
import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.configuration.ConfigurationManager;
import java.util.List;
import java.util.function.Consumer;
import javafx.collections.transformation.SortedList;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.util.Callback;
import org.controlsfx.control.ToggleSwitch;

/**
 * Editor for multiple selected aliases providing limited options for changing attributes of multiple aliases
 */
public class AliasBulkEditor extends Editor<List<Alias>>
{
    private ConfigurationManager mConfigurationManager;
    private final Consumer<List<Long>> mAliasesSavedListener;
    private Label mEditingLabel;
    private ColorPicker mColorPicker;
    private Button mApplyColorButton;
    private Button mResetColorButton;
    private ComboBox<Icon> mIconNodeComboBox;
    private Button mApplyIconButton;
    private ToggleSwitch mRecordToggleSwitch;
    private Button mApplyRecordButton;

    /**
     * Constructs an instance
     *
     * @param configurationManager for accessing icon manager
     */
    public AliasBulkEditor(ConfigurationManager configurationManager, Consumer<List<Long>> aliasesSavedListener)
    {
        mConfigurationManager = configurationManager;
        mAliasesSavedListener = aliasesSavedListener;

        GridPane gridPane = new GridPane();
        gridPane.setPadding(new Insets(10, 10, 10, 10));
        gridPane.setHgap(10);
        gridPane.setVgap(10);

        int row = 0;

        Label editorLabel = new Label("Multiple Alias Editor");
        GridPane.setConstraints(editorLabel, 0, row, 3, 1);
        gridPane.getChildren().add(editorLabel);

        GridPane.setConstraints(getEditingLabel(), 3, row, 3, 1);
        GridPane.setHalignment(getEditingLabel(), HPos.RIGHT);
        gridPane.getChildren().add(getEditingLabel());

        Separator separator = new Separator();
        separator.setMaxWidth(Double.MAX_VALUE);
        GridPane.setConstraints(separator, 0, ++row, 6, 1);
        gridPane.getChildren().add(separator);

        Label colorLabel = new Label("Color");
        GridPane.setHalignment(colorLabel, HPos.RIGHT);
        GridPane.setConstraints(colorLabel, 0, ++row);
        gridPane.getChildren().add(colorLabel);

        GridPane.setConstraints(getColorPicker(), 1, row, 3, 1);
        gridPane.getChildren().add(getColorPicker());

        GridPane.setConstraints(getApplyColorButton(), 4, row);
        gridPane.getChildren().add(getApplyColorButton());

        GridPane.setConstraints(getResetColorButton(), 5, row);
        gridPane.getChildren().add(getResetColorButton());

        Label iconLabel = new Label("Icon");
        GridPane.setHalignment(iconLabel, HPos.RIGHT);
        GridPane.setConstraints(iconLabel, 0, ++row);
        gridPane.getChildren().add(iconLabel);

        GridPane.setConstraints(getIconNodeComboBox(), 1, row, 3, 1);
        gridPane.getChildren().add(getIconNodeComboBox());

        GridPane.setConstraints(getApplyIconButton(), 4, row);
        gridPane.getChildren().add(getApplyIconButton());

        Label recordLabel = new Label("Record");
        GridPane.setHalignment(recordLabel, HPos.RIGHT);
        GridPane.setConstraints(recordLabel, 0, ++row);
        gridPane.getChildren().add(recordLabel);

        GridPane.setConstraints(getRecordToggleSwitch(), 1, row);
        gridPane.getChildren().add(getRecordToggleSwitch());

        GridPane.setConstraints(getApplyRecordButton(), 4, row);
        gridPane.getChildren().add(getApplyRecordButton());

        getChildren().add(gridPane);
    }

    @Override
    public List<Alias> getItem()
    {
        return super.getItem();
    }

    @Override
    public void setItem(List<Alias> item)
    {
        super.setItem(item);
        getEditingLabel().setText("Editing " + item.size() + " Aliases");
    }

    @Override
    public void save()
    {
        //no-op
    }

    @Override
    public void dispose()
    {
        //no-op
    }

    private Label getEditingLabel()
    {
        if(mEditingLabel == null)
        {
            mEditingLabel = new Label("Editing 0 Aliases");
        }

        return mEditingLabel;
    }

    private ColorPicker getColorPicker()
    {
        if(mColorPicker == null)
        {
            mColorPicker = new ColorPicker(Color.BLACK);
            mColorPicker.setEditable(true);
            mColorPicker.setStyle("-fx-color-rect-width: 60px; -fx-color-label-visible: false;");
        }

        return mColorPicker;
    }

    private Button getApplyColorButton()
    {
        if(mApplyColorButton == null)
        {
            mApplyColorButton = createApplyButton();
            mApplyColorButton.setOnAction(event ->
            {
                int colorValue = ColorUtil.toInteger(getColorPicker().getValue());
                apply(new AliasAdministrationService.BulkEdit(aliasIds(), null, colorValue, null, null, null,
                    null, null, null, false), getApplyColorButton());
            });
        }

        return mApplyColorButton;
    }

    private Button getResetColorButton()
    {
        if(mResetColorButton == null)
        {
            mResetColorButton = new Button("Reset Color");
            mResetColorButton.setOnAction(event ->
            {
                apply(new AliasAdministrationService.BulkEdit(aliasIds(), null, 0, null, null, null,
                    null, null, null, false), getResetColorButton());
            });
        }

        return mResetColorButton;
    }

    private ComboBox<Icon> getIconNodeComboBox()
    {
        if(mIconNodeComboBox == null)
        {
            mIconNodeComboBox = new ComboBox<>();
            mIconNodeComboBox.setMaxWidth(Double.MAX_VALUE);
            mIconNodeComboBox.setItems(new SortedList<>(mConfigurationManager.getIconModel().iconsProperty(), Ordering.natural()));
            mIconNodeComboBox.setCellFactory(new IconCellFactory());
            mIconNodeComboBox.getSelectionModel().selectedItemProperty()
                    .addListener((observable, oldValue, newValue) ->
                        getApplyIconButton().setDisable(newValue == null));
        }

        return mIconNodeComboBox;
    }

    private Button getApplyIconButton()
    {
        if(mApplyIconButton == null)
        {
            mApplyIconButton = createApplyButton();
            mApplyIconButton.setDisable(true);
            mApplyIconButton.setOnAction(event ->
            {
                Icon icon = getIconNodeComboBox().getSelectionModel().getSelectedItem();

                if(icon != null)
                {
                    apply(new AliasAdministrationService.BulkEdit(aliasIds(), null, null, icon.getName(), null,
                        null, null, null, null, false), getApplyIconButton());
                }
            });
        }

        return mApplyIconButton;
    }

    private ToggleSwitch getRecordToggleSwitch()
    {
        if(mRecordToggleSwitch == null)
        {
            mRecordToggleSwitch = new ToggleSwitch();
            mRecordToggleSwitch.setSelected(false);
            mRecordToggleSwitch.selectedProperty()
                    .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mRecordToggleSwitch;
    }

    private Button getApplyRecordButton()
    {
        if(mApplyRecordButton == null)
        {
            mApplyRecordButton = createApplyButton();
            mApplyRecordButton.setOnAction(event -> {
                boolean recordable = getRecordToggleSwitch().isSelected();
                apply(new AliasAdministrationService.BulkEdit(aliasIds(), null, null, null, recordable,
                    null, null, null, null, false), getApplyRecordButton());
            });
        }

        return mApplyRecordButton;
    }

    private Button createApplyButton()
    {
        return new Button("Apply");
    }

    private List<Long> aliasIds()
    {
        return getItem().stream().map(Alias::getId).toList();
    }

    private void apply(AliasAdministrationService.BulkEdit edit, Node owner)
    {
        AliasMutationUi.execute(owner, "Edit Aliases", () ->
            mConfigurationManager.getAliasAdministrationService().bulkEdit(edit)).ifPresent(result ->
            mAliasesSavedListener.accept(result.aliasIds()));
    }

    /**
     * Cell factory for combo box for dislaying icon name and graphic
     */
    public class IconCellFactory implements Callback<ListView<Icon>, ListCell<Icon>>
    {
        @Override
        public ListCell<Icon> call(ListView<Icon> param)
        {
            Label iconLabel = new Label();
            Label textLabel = new Label();
            GridPane gridPane = new GridPane();
            gridPane.setHgap(5);
            GridPane.setHalignment(iconLabel, HPos.RIGHT);
            gridPane.getColumnConstraints().add(new ColumnConstraints(50));
            gridPane.add(iconLabel, 0, 0);
            gridPane.add(textLabel, 1, 0);

            return new ListCell<>()
            {
                @Override
                protected void updateItem(Icon item, boolean empty)
                {
                    super.updateItem(item, empty);

                    if(empty)
                    {
                        setText(null);
                        setGraphic(null);
                    }
                    else
                    {
                        textLabel.setText(item.getName());
                        iconLabel.setGraphic(new ImageView(item.getFxImage()));
                        setGraphic(gridPane);
                    }
                }
            };
        }
    }
}
