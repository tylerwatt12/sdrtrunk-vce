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
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Preference editor for voice encryption keys.
 */
public class EncryptionKeyPreferenceEditor extends BorderPane
{
    private final EncryptionKeyPreference mPreference;
    private ObservableList<VoiceEncryptionKey> mKeys;
    private TableView<VoiceEncryptionKey> mTableView;
    private Button mEditButton;
    private Button mRemoveButton;

    public EncryptionKeyPreferenceEditor(UserPreferences userPreferences)
    {
        mPreference = userPreferences.getEncryptionKeyPreference();
        setPadding(new Insets(10, 10, 10, 10));
        setCenter(createContent());
        refresh();
    }

    private VBox createContent()
    {
        VBox content = new VBox(10);
        Label title = new Label("Voice Encryption Keys");
        Label storage = new Label("Keys are stored in user preferences and are not protected by secure secret storage.");
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
        Button button = new Button("Add");
        button.setOnAction(event -> showKeyDialog(null).ifPresent(key -> {
            mPreference.addKey(key);
            refresh();
            selectKey(key);
        }));
        return button;
    }

    private Button getEditButton()
    {
        if(mEditButton == null)
        {
            mEditButton = new Button("Edit");
            mEditButton.setOnAction(event -> {
                VoiceEncryptionKey selected = getTableView().getSelectionModel().getSelectedItem();
                showKeyDialog(selected).ifPresent(key -> {
                    mPreference.updateKey(key);
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
            mRemoveButton = new Button("Remove");
            mRemoveButton.setOnAction(event -> {
                VoiceEncryptionKey selected = getTableView().getSelectionModel().getSelectedItem();

                if(selected != null)
                {
                    mPreference.removeKey(selected);
                    refresh();
                }
            });
        }

        return mRemoveButton;
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
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

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
        algorithmComboBox.setCellFactory(listView -> new VoiceEncryptionAlgorithmCell());
        algorithmComboBox.setButtonCell(new VoiceEncryptionAlgorithmCell());
        TextField algorithmIdField = new TextField();
        algorithmIdField.setEditable(false);
        TextField keyIdField = new TextField();
        PasswordField keyField = new PasswordField();
        TextField scopeField = new TextField();
        boolean[] revertingUnsupportedSelection = new boolean[1];

        protocolComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            algorithmComboBox.getItems().setAll(VoiceEncryptionAlgorithm.getAlgorithms(newValue));
            selectSupportedAlgorithm(algorithmComboBox, VoiceEncryptionAlgorithm.getFirstSupported(newValue));
        });

        algorithmComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if(revertingUnsupportedSelection[0])
            {
                return;
            }

            if(newValue != null && !newValue.isSupported())
            {
                revertingUnsupportedSelection[0] = true;
                VoiceEncryptionAlgorithm replacement = oldValue != null && oldValue.isSupported() ? oldValue :
                    VoiceEncryptionAlgorithm.getFirstSupported(protocolComboBox.getSelectionModel().getSelectedItem());
                selectSupportedAlgorithm(algorithmComboBox, replacement);
                revertingUnsupportedSelection[0] = false;
            }
            else if(newValue != null)
            {
                algorithmIdField.setText(formatInteger(newValue.getValue()));
            }
        });

        VoiceEncryptionKey seed = existing == null ? createDefaultKey() : existing.copy();
        enabledCheckBox.setSelected(seed.isEnabled());
        labelField.setText(seed.getLabel());
        protocolComboBox.getSelectionModel().select(seed.getProtocol());
        VoiceEncryptionAlgorithm seedAlgorithm = VoiceEncryptionAlgorithm.fromValue(seed.getProtocol(),
            seed.getAlgorithmId());
        selectSupportedAlgorithm(algorithmComboBox, seedAlgorithm.isSupported() ? seedAlgorithm :
            VoiceEncryptionAlgorithm.getFirstSupported(seed.getProtocol()));
        keyIdField.setText(formatInteger(seed.getKeyId()));
        keyField.setText(seed.getKeyHex());
        scopeField.setText(seed.getScope());

        int row = 0;
        addLabeledControl(gridPane, "Enabled:", enabledCheckBox, row++);
        addLabeledControl(gridPane, "Label:", labelField, row++);
        addLabeledControl(gridPane, "Protocol:", protocolComboBox, row++);
        addLabeledControl(gridPane, "Algorithm:", algorithmComboBox, row++);
        Label algorithmHelpLabel = new Label("Unsupported algorithms are listed in gray because SDRTrunk can identify them but cannot decrypt them.");
        algorithmHelpLabel.setWrapText(true);
        algorithmHelpLabel.setMaxWidth(460);
        gridPane.add(algorithmHelpLabel, 1, row++);
        addLabeledControl(gridPane, "Algorithm ID:", algorithmIdField, row++);
        addLabeledControl(gridPane, "Key ID:", keyIdField, row++);
        addLabeledControl(gridPane, "Key Hex:", keyField, row++);
        addLabeledControl(gridPane, "Scope:", scopeField, row++);
        Label scopeHelpLabel = new Label("Optional. Leave blank to match any call with this protocol, algorithm, and key ID. Enter any part of the current call identifiers, such as a talkgroup or radio ID, to limit where this key is used.");
        scopeHelpLabel.setWrapText(true);
        scopeHelpLabel.setMaxWidth(460);
        gridPane.add(scopeHelpLabel, 1, row);

        dialog.getDialogPane().setContent(gridPane);

        Button okButton = (Button)dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            String validationMessage = validate(protocolComboBox.getSelectionModel().getSelectedItem(),
                algorithmComboBox.getSelectionModel().getSelectedItem(), algorithmIdField.getText(),
                keyIdField.getText(), keyField.getText());

            if(validationMessage != null)
            {
                event.consume();
                Alert alert = new Alert(Alert.AlertType.ERROR, validationMessage, ButtonType.OK);
                alert.setTitle("Invalid Voice Encryption Key");
                alert.setHeaderText("Invalid Voice Encryption Key");
                alert.showAndWait();
            }
        });

        dialog.setResultConverter(buttonType -> {
            if(buttonType == ButtonType.OK)
            {
                VoiceEncryptionKey key = seed.copy();
                key.setEnabled(enabledCheckBox.isSelected());
                key.setLabel(labelField.getText());
                key.setProtocol(protocolComboBox.getSelectionModel().getSelectedItem());
                key.setAlgorithmId(algorithmComboBox.getSelectionModel().getSelectedItem().getValue());
                key.setKeyId(parseInteger(keyIdField.getText()));
                key.setKeyHex(keyField.getText());
                key.setScope(scopeField.getText());
                return key;
            }

            return null;
        });

        return dialog.showAndWait();
    }

    private VoiceEncryptionKey createDefaultKey()
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setProtocol(VoiceEncryptionProtocol.APCO25);
        key.setAlgorithmId(VoiceEncryptionAlgorithm.APCO25_ADP.getValue());
        return key;
    }

    private void selectSupportedAlgorithm(ComboBox<VoiceEncryptionAlgorithm> algorithmComboBox,
                                          VoiceEncryptionAlgorithm algorithm)
    {
        if(algorithm != null && algorithm.isSupported() && algorithmComboBox.getItems().contains(algorithm))
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

    private String validate(VoiceEncryptionProtocol protocol, VoiceEncryptionAlgorithm algorithm, String algorithmId,
                            String keyId, String keyHex)
    {
        if(protocol == null)
        {
            return "Select a protocol.";
        }

        int parsedAlgorithmId;

        try
        {
            parsedAlgorithmId = parseInteger(algorithmId);
        }
        catch(NumberFormatException nfe)
        {
            return "Enter a valid algorithm ID.";
        }

        try
        {
            parseInteger(keyId);
        }
        catch(NumberFormatException nfe)
        {
            return "Enter a valid key ID.";
        }

        if(algorithm == null || !algorithm.isSupported())
        {
            return "Select a supported algorithm. Unsupported algorithms are shown for reference but cannot be entered.";
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

    private int parseInteger(String value)
    {
        if(value == null || value.isBlank())
        {
            throw new NumberFormatException("Blank integer value");
        }

        String trimmed = value.trim();

        if(trimmed.startsWith("0x") || trimmed.startsWith("0X"))
        {
            return Integer.parseInt(trimmed.substring(2), 16);
        }

        return Integer.parseInt(trimmed);
    }

    private String formatInteger(int value)
    {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private static class VoiceEncryptionAlgorithmCell extends ListCell<VoiceEncryptionAlgorithm>
    {
        @Override
        protected void updateItem(VoiceEncryptionAlgorithm algorithm, boolean empty)
        {
            super.updateItem(algorithm, empty);

            if(empty || algorithm == null)
            {
                setText(null);
                setDisable(false);
                setOpacity(1.0);
            }
            else
            {
                String support = algorithm.isSupported() ? "" : " (not supported)";
                String note = algorithm.getSupportNote() == null ? "" : " - " + algorithm.getSupportNote();
                setText(algorithm + support + note);
                setDisable(!algorithm.isSupported());
                setOpacity(algorithm.isSupported() ? 1.0 : 0.45);
            }
        }
    }
}
