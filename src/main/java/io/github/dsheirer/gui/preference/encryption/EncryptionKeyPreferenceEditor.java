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

package io.github.dsheirer.gui.preference.encryption;

import io.github.dsheirer.gui.control.DualBaseIntegerField;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.encryption.EncryptionKeyPreference;
import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.util.Optional;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Preference editor for voice encryption keys.
 */
public class EncryptionKeyPreferenceEditor extends BorderPane
{
    private final io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModuleManager mModuleManager;
    private final EncryptionKeyPreference mPreference;
    private ObservableList<VoiceEncryptionKey> mKeys;
    private TableView<VoiceEncryptionKey> mTableView;
    private Button mEditButton;
    private Button mRemoveButton;

    public EncryptionKeyPreferenceEditor(UserPreferences userPreferences)
    {
        mModuleManager = userPreferences.getVoiceDecryptionModulePreference().getModuleManager();
        mPreference = userPreferences.getEncryptionKeyPreference();
        setPadding(new Insets(10, 10, 10, 10));
        setCenter(createContent());
        refresh();
    }

    public void activate()
    {
        refresh();
    }

    private VBox createContent()
    {
        VBox content = new VBox(10);
        Label title = new Label("Voice Encryption Keys");
        Label storage = new Label("Manage keys used to decrypt received voice calls.");
        storage.setWrapText(true);
        HBox buttonBox = new HBox(8);
        buttonBox.getChildren().addAll(createAddButton(), getEditButton(), getRemoveButton());
        content.getChildren().addAll(title, storage, getTableView(), buttonBox);
        VBox.setVgrow(getTableView(), Priority.ALWAYS);
        return content;
    }

    private void refresh()
    {
        getKeys().setAll(mPreference.getKeys());
        updateButtons();
    }

    private ObservableList<VoiceEncryptionKey> getKeys()
    {
        if(mKeys == null)
        {
            mKeys = FXCollections.observableArrayList();
        }

        return mKeys;
    }

    private TableView<VoiceEncryptionKey> getTableView()
    {
        if(mTableView == null)
        {
            mTableView = new TableView<>(getKeys());
            mTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);

            TableColumn<VoiceEncryptionKey,String> enabledColumn = new TableColumn<>("Enabled");
            enabledColumn.setCellValueFactory(param ->
                new ReadOnlyStringWrapper(param.getValue().isEnabled() ? "Yes" : "No"));
            enabledColumn.setMaxWidth(80);

            TableColumn<VoiceEncryptionKey,String> labelColumn = new TableColumn<>("Label");
            labelColumn.setCellValueFactory(param -> new ReadOnlyStringWrapper(param.getValue().getLabel()));

            TableColumn<VoiceEncryptionKey,String> protocolColumn = new TableColumn<>("Protocol");
            protocolColumn.setCellValueFactory(param ->
                new ReadOnlyStringWrapper(param.getValue().getProtocol().toString()));
            protocolColumn.setMaxWidth(100);

            TableColumn<VoiceEncryptionKey,String> algorithmColumn = new TableColumn<>("Algorithm");
            algorithmColumn.setCellValueFactory(param ->
                new ReadOnlyStringWrapper(param.getValue().getAlgorithmLabel()));

            TableColumn<VoiceEncryptionKey,String> keyIdColumn = new TableColumn<>("Key ID");
            keyIdColumn.setCellValueFactory(param ->
                new ReadOnlyStringWrapper(formatInteger(param.getValue().getKeyId())));
            keyIdColumn.setMaxWidth(100);

            TableColumn<VoiceEncryptionKey,String> keyColumn = new TableColumn<>("Key");
            keyColumn.setCellValueFactory(param -> new ReadOnlyStringWrapper(param.getValue().getKeyStatus()));

            TableColumn<VoiceEncryptionKey,String> scopeColumn = new TableColumn<>("Scope");
            scopeColumn.setCellValueFactory(param -> new ReadOnlyStringWrapper(param.getValue().getScope()));

            mTableView.getColumns().addAll(enabledColumn, labelColumn, protocolColumn, algorithmColumn, keyIdColumn,
                keyColumn, scopeColumn);
            mTableView.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> updateButtons());
        }

        return mTableView;
    }

    private Button createAddButton()
    {
        Button button = createButton("Add");
        button.setOnAction(event -> showKeyDialog(null).ifPresent(key -> {
            refresh();
            selectKey(key);
        }));
        return button;
    }

    private Button getEditButton()
    {
        if(mEditButton == null)
        {
            mEditButton = createButton("Edit");
            mEditButton.setOnAction(event -> {
                VoiceEncryptionKey selected = getTableView().getSelectionModel().getSelectedItem();
                showKeyDialog(selected).ifPresent(key -> {
                    refresh();
                    selectKey(key);
                });
            });
        }

        return mEditButton;
    }

    private Button getRemoveButton()
    {
        if(mRemoveButton == null)
        {
            mRemoveButton = createButton("Remove");
            mRemoveButton.setOnAction(event -> {
                VoiceEncryptionKey selected = getTableView().getSelectionModel().getSelectedItem();

                if(selected != null)
                {
                    mRemoveButton.setDisable(true);
                    try
                    {
                        mPreference.removeKey(selected);
                        refresh();
                    }
                    catch(IllegalStateException e)
                    {
                        showError("Remove Key Failed", "The key could not be removed. Check that the application data folder is writable, then try again.");
                    }
                    finally
                    {
                        updateButtons();
                    }
                }
            });
        }

        return mRemoveButton;
    }

    private Button createButton(String text)
    {
        Button button = new Button(text);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private void updateButtons()
    {
        boolean selected = getTableView().getSelectionModel().getSelectedItem() != null;
        getEditButton().setDisable(!selected);
        getRemoveButton().setDisable(!selected);
    }

    private void selectKey(VoiceEncryptionKey key)
    {
        for(VoiceEncryptionKey candidate: getKeys())
        {
            if(candidate.getId().equals(key.getId()))
            {
                getTableView().getSelectionModel().select(candidate);
                return;
            }
        }
    }

    private Optional<VoiceEncryptionKey> showKeyDialog(VoiceEncryptionKey existing)
    {
        Dialog<VoiceEncryptionKey> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "Add Voice Encryption Key" : "Edit Voice Encryption Key");
        ButtonType save = new ButtonType("Save", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        GridPane gridPane = new GridPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);
        gridPane.setPadding(new Insets(10, 10, 10, 10));
        ColumnConstraints labelColumn = new ColumnConstraints();
        ColumnConstraints controlColumn = new ColumnConstraints();
        controlColumn.setHgrow(Priority.ALWAYS);
        gridPane.getColumnConstraints().addAll(labelColumn, controlColumn);

        CheckBox enabledCheckBox = new CheckBox();
        TextField labelField = new TextField();
        ComboBox<VoiceEncryptionProtocol> protocolComboBox = new ComboBox<>();
        protocolComboBox.getItems().addAll(VoiceEncryptionProtocol.values());
        ComboBox<VoiceEncryptionAlgorithm> algorithmComboBox = new ComboBox<>();
        DualBaseIntegerField algorithmIdField = new DualBaseIntegerField(0, 0xFF);
        algorithmIdField.setEditable(false);
        DualBaseIntegerField keyIdField = new DualBaseIntegerField(0, 0xFFFF);
        TextField keyField = new TextField();
        TextField scopeField = new TextField();

        protocolComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            algorithmComboBox.getItems().setAll(mModuleManager.getSupportedAlgorithms(newValue));
            selectAlgorithm(algorithmComboBox, algorithmComboBox.getItems().isEmpty() ? null :
                algorithmComboBox.getItems().getFirst());
        });

        algorithmComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if(newValue != null)
            {
                algorithmIdField.setValue(newValue.getValue());
                algorithmIdField.setEditable(false);
            }
        });

        VoiceEncryptionKey seed = existing == null ? createDefaultKey() : existing.copy();
        enabledCheckBox.setSelected(seed.isEnabled());
        labelField.setText(seed.getLabel());
        protocolComboBox.getSelectionModel().select(seed.getProtocol());
        VoiceEncryptionAlgorithm seedAlgorithm = VoiceEncryptionAlgorithm.fromValue(seed.getProtocol(),
            seed.getAlgorithmId());
        selectAlgorithm(algorithmComboBox, seedAlgorithm);
        algorithmIdField.setValue(seed.getAlgorithmId());
        algorithmIdField.setEditable(false);
        keyIdField.setValue(seed.getKeyId());
        keyField.setText(seed.getKeyHex());
        scopeField.setText(seed.getScope());

        int row = 0;
        addLabeledControl(gridPane, "Enabled:", enabledCheckBox, row++);
        addLabeledControl(gridPane, "Label:", labelField, row++);
        addLabeledControl(gridPane, "Protocol:", protocolComboBox, row++);
        addLabeledControl(gridPane, "Algorithm:", algorithmComboBox, row++);
        addLabeledControl(gridPane, "Algorithm ID:", algorithmIdField, row++);
        addLabeledControl(gridPane, "Key ID:", keyIdField, row++);
        addLabeledControl(gridPane, "Key Hex:", keyField, row++);
        addLabeledControl(gridPane, "Scope:", scopeField, row++);
        Label scopeHelpLabel = new Label("Optional. Enter part of a call identifier to limit where this key is used.");
        scopeHelpLabel.setWrapText(true);
        scopeHelpLabel.setMaxWidth(460);
        gridPane.add(scopeHelpLabel, 1, row);

        dialog.getDialogPane().setContent(gridPane);

        Button okButton = (Button)dialog.getDialogPane().lookupButton(save);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            String validationMessage = validate(protocolComboBox.getSelectionModel().getSelectedItem(),
                algorithmComboBox.getSelectionModel().getSelectedItem(), algorithmIdField, keyIdField, keyField.getText());

            if(validationMessage != null)
            {
                event.consume();
                showError("Invalid Voice Encryption Key", validationMessage);
                return;
            }

            seed.setEnabled(enabledCheckBox.isSelected());
            seed.setLabel(labelField.getText());
            seed.setProtocol(protocolComboBox.getSelectionModel().getSelectedItem());
            seed.setAlgorithmId(algorithmIdField.getValue());
            seed.setKeyId(keyIdField.getValue());
            seed.setKeyHex(keyField.getText());
            seed.setScope(scopeField.getText());
            okButton.setDisable(true);
            try
            {
                if(existing == null) mPreference.addKey(seed);
                else mPreference.updateKey(seed);
            }
            catch(IllegalStateException e)
            {
                event.consume();
                showError("Save Key Failed", "The key could not be saved. Check that the application data folder is writable, then try again.");
            }
            finally
            {
                okButton.setDisable(false);
            }
        });

        dialog.setResultConverter(buttonType -> buttonType == save ? seed : null);

        return dialog.showAndWait();
    }

    private VoiceEncryptionKey createDefaultKey()
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setProtocol(VoiceEncryptionProtocol.APCO25);
        java.util.List<VoiceEncryptionAlgorithm> algorithms =
            mModuleManager.getSupportedAlgorithms(VoiceEncryptionProtocol.APCO25);

        if(!algorithms.isEmpty())
        {
            key.setAlgorithmId(algorithms.getFirst().getValue());
        }

        return key;
    }

    private void selectAlgorithm(ComboBox<VoiceEncryptionAlgorithm> algorithmComboBox,
                                 VoiceEncryptionAlgorithm algorithm)
    {
        if(algorithm != null && algorithmComboBox.getItems().contains(algorithm))
        {
            algorithmComboBox.getSelectionModel().select(algorithm);
        }
        else
        {
            algorithmComboBox.getSelectionModel().clearSelection();
        }
    }

    private void addLabeledControl(GridPane gridPane, String labelText, javafx.scene.Node control, int row)
    {
        Label label = new Label(labelText);
        GridPane.setHalignment(label, HPos.RIGHT);
        gridPane.add(label, 0, row);
        gridPane.add(control, 1, row);
    }

    private String validate(VoiceEncryptionProtocol protocol, VoiceEncryptionAlgorithm algorithm,
                            DualBaseIntegerField algorithmIdField, DualBaseIntegerField keyIdField, String keyHex)
    {
        if(protocol == null)
        {
            return "Select a protocol.";
        }

        int parsedAlgorithmId;

        try
        {
            parsedAlgorithmId = algorithmIdField.getValue();
        }
        catch(NumberFormatException nfe)
        {
            return "Enter a valid algorithm ID.";
        }

        try
        {
            keyIdField.getValue();
        }
        catch(NumberFormatException nfe)
        {
            return "Enter a valid key ID.";
        }

        if(algorithm == null)
        {
            return "Select an algorithm.";
        }

        if(parsedAlgorithmId != algorithm.getValue())
        {
            return "Algorithm ID does not match the selected algorithm.";
        }

        if(!VoiceEncryptionKey.isValidHexKey(keyHex))
        {
            return "Enter a non-empty key as hexadecimal pairs.";
        }

        int keyByteLength = VoiceEncryptionKey.normalizeKeyHex(keyHex).length() / 2;

        if(algorithm == VoiceEncryptionAlgorithm.APCO25_ADP && keyByteLength > algorithm.getExpectedKeyBytes())
        {
            return algorithm + " expects a key no longer than " + algorithm.getExpectedKeyBytes() + " bytes.";
        }

        if(algorithm != VoiceEncryptionAlgorithm.APCO25_ADP && algorithm.hasExpectedKeyLength() &&
            keyByteLength != algorithm.getExpectedKeyBytes())
        {
            return algorithm + " expects a " + algorithm.getExpectedKeyBytes() + "-byte key.";
        }

        return null;
    }

    private String formatInteger(int value)
    {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private void showError(String title, String message)
    {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        if(getScene() != null) alert.initOwner(getScene().getWindow());
        alert.showAndWait();
    }
}
