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
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultException;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultState;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
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
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * Preference editor for voice encryption keys.
 */
public class EncryptionKeyPreferenceEditor extends BorderPane
{
    private final EncryptionKeyPreference mPreference;
    private final EncryptionKeyVaultService mVaultService;
    private ObservableList<VoiceEncryptionKey> mKeys;
    private TableView<VoiceEncryptionKey> mTableView;
    private Label mVaultStatusLabel;
    private CheckBox mPromptOnLaunchCheckBox;
    private Button mCreateVaultButton;
    private Button mUnlockVaultButton;
    private Button mLockVaultButton;
    private Button mChangePasswordButton;
    private Button mForgetPasswordButton;
    private Button mExportVaultButton;
    private Button mEditButton;
    private Button mRemoveButton;

    public EncryptionKeyPreferenceEditor(UserPreferences userPreferences)
    {
        mPreference = userPreferences.getEncryptionKeyPreference();
        mVaultService = mPreference.getVaultService();
        mVaultService.stateProperty().addListener((observable, oldValue, newValue) -> refresh());
        mVaultService.savedPasswordPresentProperty().addListener((observable, oldValue, newValue) -> updateButtons());
        setPadding(new Insets(10, 10, 10, 10));
        setCenter(createContent());
        refresh();
    }

    public void activate()
    {
        mVaultService.refreshState();
        refresh();

        if(!mVaultService.isUnlocked())
        {
            promptForVaultAccess();
        }
    }

    private VBox createContent()
    {
        VBox content = new VBox(10);
        Label title = new Label("Voice Encryption Keys");
        Label storage = new Label("Keys are stored in a password-protected vault. Decryption is unavailable while the vault is locked.");
        storage.setWrapText(true);
        VBox vaultBox = new VBox(6);
        FlowPane vaultButtonBox = new FlowPane(8, 6);
        vaultButtonBox.getChildren().addAll(getCreateVaultButton(), getUnlockVaultButton(),
            getLockVaultButton(), getChangePasswordButton(), getForgetPasswordButton(), getExportVaultButton());
        vaultBox.getChildren().addAll(getVaultStatusLabel(), vaultButtonBox, getPromptOnLaunchCheckBox());
        HBox buttonBox = new HBox(8);
        buttonBox.getChildren().addAll(createAddButton(), getEditButton(), getRemoveButton());
        content.getChildren().addAll(title, storage, vaultBox, getTableView(), buttonBox);
        VBox.setVgrow(getTableView(), Priority.ALWAYS);
        return content;
    }

    private void refresh()
    {
        if(mVaultService.isUnlocked())
        {
            getKeys().setAll(mPreference.getKeys());
        }
        else
        {
            getKeys().clear();
        }

        updateButtons();
    }

    private Label getVaultStatusLabel()
    {
        if(mVaultStatusLabel == null)
        {
            mVaultStatusLabel = new Label();
            mVaultStatusLabel.setMaxWidth(Double.MAX_VALUE);
            mVaultStatusLabel.setWrapText(true);
        }

        return mVaultStatusLabel;
    }

    private CheckBox getPromptOnLaunchCheckBox()
    {
        if(mPromptOnLaunchCheckBox == null)
        {
            mPromptOnLaunchCheckBox = new CheckBox("Prompt for vault password on SDRTrunk launch");
            mPromptOnLaunchCheckBox.setSelected(mVaultService.isPromptOnLaunch());
            mPromptOnLaunchCheckBox.setOnAction(event ->
                mVaultService.setPromptOnLaunch(mPromptOnLaunchCheckBox.isSelected()));
        }

        return mPromptOnLaunchCheckBox;
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
            mPreference.addKey(key);
            refresh();
            selectKey(key);
        }));
        return button;
    }

    private Button getCreateVaultButton()
    {
        if(mCreateVaultButton == null)
        {
            mCreateVaultButton = createButton("Create Vault");
            mCreateVaultButton.setOnAction(event -> showCreateVaultDialog());
        }

        return mCreateVaultButton;
    }

    private Button getUnlockVaultButton()
    {
        if(mUnlockVaultButton == null)
        {
            mUnlockVaultButton = createButton("Unlock Vault");
            mUnlockVaultButton.setOnAction(event -> showUnlockVaultDialog());
        }

        return mUnlockVaultButton;
    }

    private Button getLockVaultButton()
    {
        if(mLockVaultButton == null)
        {
            mLockVaultButton = createButton("Lock Vault");
            mLockVaultButton.setOnAction(event -> {
                mVaultService.lock();
                refresh();
            });
        }

        return mLockVaultButton;
    }

    private Button getChangePasswordButton()
    {
        if(mChangePasswordButton == null)
        {
            mChangePasswordButton = createButton("Change Vault Password");
            mChangePasswordButton.setOnAction(event -> showChangePasswordDialog());
        }

        return mChangePasswordButton;
    }

    private Button getForgetPasswordButton()
    {
        if(mForgetPasswordButton == null)
        {
            mForgetPasswordButton = createButton("Forget Vault Password");
            mForgetPasswordButton.setOnAction(event -> {
                mVaultService.forgetSavedPassword();
                showInformation("Saved Password Removed", "The unsafe saved vault password has been removed.");
                refresh();
            });
        }

        return mForgetPasswordButton;
    }

    private Button getExportVaultButton()
    {
        if(mExportVaultButton == null)
        {
            mExportVaultButton = createButton("Export Vault");
            mExportVaultButton.setOnAction(event -> showExportVaultDialog());
        }

        return mExportVaultButton;
    }

    private Button getEditButton()
    {
        if(mEditButton == null)
        {
            mEditButton = createButton("Edit");
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
            mRemoveButton = createButton("Remove");
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

    private Button createButton(String text)
    {
        Button button = new Button(text);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private void updateButtons()
    {
        EncryptionKeyVaultState state = mVaultService.getState();
        boolean unlocked = mVaultService.isUnlocked();
        boolean hasVault = mVaultService.hasVault();
        boolean selected = getTableView().getSelectionModel().getSelectedItem() != null;
        getVaultStatusLabel().setText("Vault: " + state + " - " + mVaultService.statusProperty().get());
        getTableView().setDisable(!unlocked);
        getCreateVaultButton().setDisable(hasVault);
        getUnlockVaultButton().setDisable(!hasVault || unlocked);
        getLockVaultButton().setDisable(!unlocked);
        getChangePasswordButton().setDisable(!unlocked);
        getForgetPasswordButton().setDisable(!mVaultService.hasSavedPassword());
        getExportVaultButton().setDisable(!hasVault);
        getPromptOnLaunchCheckBox().setSelected(mVaultService.isPromptOnLaunch());
        getEditButton().setDisable(!unlocked || !selected);
        getRemoveButton().setDisable(!unlocked || !selected);
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

    private void promptForVaultAccess()
    {
        if(!mVaultService.hasVault())
        {
            showCreateVaultDialog();
        }
        else
        {
            showUnlockVaultDialog();
        }
    }

    private void showCreateVaultDialog()
    {
        Optional<PasswordResult> result = showPasswordDialog("Create Encryption Key Vault", "Create Vault",
            true, false);

        result.ifPresent(passwordResult -> {
            try
            {
                mVaultService.createVault(passwordResult.password());
                refresh();
            }
            catch(EncryptionKeyVaultException e)
            {
                showError("Create Vault Failed", e.getMessage());
            }
            finally
            {
                passwordResult.clear();
            }
        });
    }

    private void showUnlockVaultDialog()
    {
        Optional<PasswordResult> result = showPasswordDialog("Unlock Encryption Key Vault", "Unlock Vault",
            false, true);

        result.ifPresent(passwordResult -> {
            try
            {
                mVaultService.unlock(passwordResult.password(), passwordResult.savePassword());
                refresh();
            }
            catch(EncryptionKeyVaultException e)
            {
                showError("Unlock Vault Failed", e.getMessage());
            }
            finally
            {
                passwordResult.clear();
            }
        });
    }

    private void showChangePasswordDialog()
    {
        Optional<PasswordChangeResult> result = showPasswordChangeDialog();

        result.ifPresent(passwordResult -> {
            try
            {
                mVaultService.changePassword(passwordResult.currentPassword(), passwordResult.newPassword());
                showInformation("Vault Password Changed", "The encryption key vault password has been changed.");
                refresh();
            }
            catch(EncryptionKeyVaultException e)
            {
                showError("Change Password Failed", e.getMessage());
            }
            finally
            {
                passwordResult.clear();
            }
        });
    }

    private void showExportVaultDialog()
    {
        Optional<PasswordResult> password = showPasswordDialog("Export Encryption Key Vault", "Verify Password",
            false, false);

        password.ifPresent(passwordResult -> {
            try
            {
                if(!mVaultService.verifyPassword(passwordResult.password()))
                {
                    showError("Export Vault Failed", "Incorrect encryption vault password.");
                    return;
                }

                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("Export Encryption Key Vault");
                fileChooser.setInitialFileName("encryption-key-vault.sqlite");
                File selected = fileChooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);

                if(selected != null)
                {
                    Path destination = selected.toPath();
                    mVaultService.exportVault(destination);
                    showInformation("Vault Exported", "Encryption key vault exported.");
                }
            }
            catch(EncryptionKeyVaultException e)
            {
                showError("Export Vault Failed", e.getMessage());
            }
            finally
            {
                passwordResult.clear();
            }
        });
    }

    private Optional<PasswordResult> showPasswordDialog(String title, String okText, boolean confirmPassword,
                                                       boolean allowSavePassword)
    {
        Dialog<PasswordResult> dialog = new Dialog<>();
        dialog.setTitle(title);
        ButtonType ok = new ButtonType(okText, javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        GridPane gridPane = new GridPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);
        gridPane.setPadding(new Insets(10, 10, 10, 10));

        PasswordField passwordField = new PasswordField();
        PasswordField confirmField = new PasswordField();
        CheckBox savePassword = new CheckBox("Save password (Warning! Unsafe!)");

        int row = 0;
        addLabeledControl(gridPane, "Password:", passwordField, row++);

        if(confirmPassword)
        {
            addLabeledControl(gridPane, "Confirm:", confirmField, row++);
        }

        if(allowSavePassword)
        {
            gridPane.add(savePassword, 1, row);
        }

        dialog.getDialogPane().setContent(gridPane);

        Button okButton = (Button)dialog.getDialogPane().lookupButton(ok);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            if(passwordField.getText() == null || passwordField.getText().isBlank())
            {
                event.consume();
                showError("Invalid Password", "Enter a vault password.");
            }
            else if(confirmPassword && !passwordField.getText().equals(confirmField.getText()))
            {
                event.consume();
                showError("Invalid Password", "Vault passwords do not match.");
            }
        });

        dialog.setResultConverter(buttonType -> buttonType == ok ?
            new PasswordResult(passwordField.getText().toCharArray(), savePassword.isSelected()) : null);

        return dialog.showAndWait();
    }

    private Optional<PasswordChangeResult> showPasswordChangeDialog()
    {
        Dialog<PasswordChangeResult> dialog = new Dialog<>();
        dialog.setTitle("Change Encryption Key Vault Password");
        ButtonType ok = new ButtonType("Change Password", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        GridPane gridPane = new GridPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);
        gridPane.setPadding(new Insets(10, 10, 10, 10));

        PasswordField currentPassword = new PasswordField();
        PasswordField newPassword = new PasswordField();
        PasswordField confirmPassword = new PasswordField();
        addLabeledControl(gridPane, "Current:", currentPassword, 0);
        addLabeledControl(gridPane, "New:", newPassword, 1);
        addLabeledControl(gridPane, "Confirm:", confirmPassword, 2);
        dialog.getDialogPane().setContent(gridPane);

        Button okButton = (Button)dialog.getDialogPane().lookupButton(ok);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            if(currentPassword.getText() == null || currentPassword.getText().isBlank() ||
                newPassword.getText() == null || newPassword.getText().isBlank())
            {
                event.consume();
                showError("Invalid Password", "Enter the current and new vault passwords.");
            }
            else if(!newPassword.getText().equals(confirmPassword.getText()))
            {
                event.consume();
                showError("Invalid Password", "New vault passwords do not match.");
            }
        });

        dialog.setResultConverter(buttonType -> buttonType == ok ?
            new PasswordChangeResult(currentPassword.getText().toCharArray(),
                newPassword.getText().toCharArray()) : null);
        return dialog.showAndWait();
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
        DualBaseIntegerField algorithmIdField = new DualBaseIntegerField(0, 0xFF);
        algorithmIdField.setEditable(false);
        DualBaseIntegerField keyIdField = new DualBaseIntegerField(0, 0xFFFF);
        PasswordField keyField = new PasswordField();
        TextField scopeField = new TextField();

        protocolComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            algorithmComboBox.getItems().setAll(VoiceEncryptionAlgorithm.getAlgorithms(newValue));
            selectAlgorithm(algorithmComboBox, VoiceEncryptionAlgorithm.getFirstSupported(newValue));
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
        Label scopeHelpLabel = new Label("Optional. Leave blank to match any call with this protocol, algorithm, and key ID. Enter any part of the current call identifiers, such as a talkgroup or radio ID, to limit where this key is used.");
        scopeHelpLabel.setWrapText(true);
        scopeHelpLabel.setMaxWidth(460);
        gridPane.add(scopeHelpLabel, 1, row);

        dialog.getDialogPane().setContent(gridPane);

        Button okButton = (Button)dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            String validationMessage = validate(protocolComboBox.getSelectionModel().getSelectedItem(),
                algorithmComboBox.getSelectionModel().getSelectedItem(), algorithmIdField, keyIdField, keyField.getText());

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
                key.setAlgorithmId(algorithmIdField.getValue());
                key.setKeyId(keyIdField.getValue());
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
        alert.showAndWait();
    }

    private void showInformation(String title, String message)
    {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    private record PasswordResult(char[] password, boolean savePassword)
    {
        private void clear()
        {
            Arrays.fill(password, '\0');
        }
    }

    private record PasswordChangeResult(char[] currentPassword, char[] newPassword)
    {
        private void clear()
        {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }
}
