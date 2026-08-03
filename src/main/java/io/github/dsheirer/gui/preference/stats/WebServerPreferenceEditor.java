/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.gui.preference.stats;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.stats.StatsWebPath;
import io.github.dsheirer.web.auth.Pbkdf2PasswordHasher;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded browser server preferences. Stats logging is configured separately.
 */
public class WebServerPreferenceEditor extends HBox
{
    private static final Logger mLog = LoggerFactory.getLogger(WebServerPreferenceEditor.class);
    private final ApplicationPreference mApplicationPreference;
    private final Path mDatabasePath;
    private GridPane mEditorPane;
    private CheckBox mServerCheckBox;
    private RadioButton mLocalOnlyRadioButton;
    private RadioButton mAnyIpRadioButton;
    private ToggleGroup mAccessModeToggleGroup;
    private Spinner<Integer> mPortSpinner;
    private CheckBox mHttpsCheckBox;
    private TextField mCertificateCommonNameField;
    private TextField mCertificateSanField;
    private Button mGenerateSelfSignedButton;
    private Button mImportTlsPairButton;
    private Label mTlsStatusLabel;
    private Label mUrlLabel;
    private Label mAdminStatusLabel;
    private Button mConfigureAdminButton;
    private ProgressIndicator mAdminProgressIndicator;
    private boolean mAdminOperationRunning;
    private boolean mAdminStatusKnown;
    private boolean mAdminConfigured;
    private WebTlsMaterialService mTlsMaterialService;

    public WebServerPreferenceEditor(UserPreferences userPreferences)
    {
        mApplicationPreference = userPreferences.getApplicationPreference();
        mDatabasePath = SdrTrunkDatabasePath.getDatabasePath(userPreferences);
        setMaxWidth(Double.MAX_VALUE);
        VBox vbox = new VBox(getEditorPane());
        vbox.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(vbox, Priority.ALWAYS);
        getChildren().add(vbox);
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            int row = 0;
            mEditorPane = new GridPane();
            mEditorPane.setMaxWidth(Double.MAX_VALUE);
            mEditorPane.setVgap(10);
            mEditorPane.setHgap(8);
            mEditorPane.setPadding(new Insets(10));
            mEditorPane.add(new Label("Web Server"), 0, row++, 3, 1);
            mEditorPane.add(getServerCheckBox(), 0, row++, 3, 1);
            mEditorPane.add(new Label("Network access"), 0, row);
            mEditorPane.add(new HBox(12, getLocalOnlyRadioButton(), getAnyIpRadioButton()), 1, row++, 2, 1);
            mEditorPane.add(new Label("Port"), 0, row);
            GridPane.setHalignment(getPortSpinner(), HPos.RIGHT);
            mEditorPane.add(getPortSpinner(), 1, row++);
            mEditorPane.add(getHttpsCheckBox(), 0, row++, 3, 1);
            mEditorPane.add(new Label("Certificate common name"), 0, row);
            mEditorPane.add(getCertificateCommonNameField(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Certificate names (SANs)"), 0, row);
            mEditorPane.add(getCertificateSanField(), 1, row++, 2, 1);
            mEditorPane.add(new Label("HTTPS certificate"), 0, row);
            mEditorPane.add(new HBox(8, getGenerateSelfSignedButton(), getImportTlsPairButton()),
                1, row++, 2, 1);
            mEditorPane.add(new Label("HTTPS status"), 0, row);
            mEditorPane.add(getTlsStatusLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Web URL"), 0, row);
            mEditorPane.add(getUrlLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Editable assets folder"), 0, row);
            Label assets = new Label(StatsWebPath.getAssetsPath().toString());
            assets.setWrapText(true);
            mEditorPane.add(assets, 1, row++, 2, 1);
            mEditorPane.add(new Label("Primary web administrator"), 0, row);
            mEditorPane.add(getAdminStatusLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Local account setup"), 0, row);
            mEditorPane.add(new HBox(8, getConfigureAdminButton(), getAdminProgressIndicator()),
                1, row++, 2, 1);
            Label adminHelp = new Label(
                "The fixed admin account is managed only from this JavaFX screen. Stop the embedded web server " +
                    "before setting or resetting its password.");
            adminHelp.setWrapText(true);
            mEditorPane.add(adminHelp, 0, row, 3, 1);

            ColumnConstraints labelColumn = new ColumnConstraints();
            labelColumn.setPercentWidth(30);
            ColumnConstraints valueColumn = new ColumnConstraints();
            valueColumn.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(labelColumn, valueColumn);
            updateControlState();
            refreshTlsStatus();
            refreshAdminStatus();
        }

        return mEditorPane;
    }

    private CheckBox getServerCheckBox()
    {
        if(mServerCheckBox == null)
        {
            mServerCheckBox = new CheckBox("Run Embedded Web Server");
            mServerCheckBox.setTooltip(new Tooltip(
                "Runs independently of statistics collection. Live Systems and web audio remain available when " +
                    "summary collection is off."));
            mServerCheckBox.setSelected(mApplicationPreference.isStatsWebServerEnabled());
            mServerCheckBox.setOnAction(event -> setServerEnabled(mServerCheckBox.isSelected()));
        }

        return mServerCheckBox;
    }

    private ToggleGroup getAccessModeToggleGroup()
    {
        if(mAccessModeToggleGroup == null)
        {
            mAccessModeToggleGroup = new ToggleGroup();
        }

        return mAccessModeToggleGroup;
    }

    private RadioButton getLocalOnlyRadioButton()
    {
        if(mLocalOnlyRadioButton == null)
        {
            mLocalOnlyRadioButton = new RadioButton("Local only");
            mLocalOnlyRadioButton.setToggleGroup(getAccessModeToggleGroup());
            mLocalOnlyRadioButton.setTooltip(new Tooltip(
                "Accepts connections only from this computer at 127.0.0.1."));
            mLocalOnlyRadioButton.setSelected(!mApplicationPreference.isStatsWebServerAnyIpEnabled());
            mLocalOnlyRadioButton.setOnAction(event -> {
                mApplicationPreference.setStatsWebServerAnyIpEnabled(false);
                updateControlState();
            });
        }

        return mLocalOnlyRadioButton;
    }

    private RadioButton getAnyIpRadioButton()
    {
        if(mAnyIpRadioButton == null)
        {
            mAnyIpRadioButton = new RadioButton("Any IP");
            mAnyIpRadioButton.setToggleGroup(getAccessModeToggleGroup());
            mAnyIpRadioButton.setTooltip(new Tooltip(
                "Accepts connections through any network interface, subject to the computer's firewall."));
            mAnyIpRadioButton.setSelected(mApplicationPreference.isStatsWebServerAnyIpEnabled());
            mAnyIpRadioButton.setOnAction(event -> confirmAnyIpAccess());
        }

        return mAnyIpRadioButton;
    }

    private void confirmAnyIpAccess()
    {
        ButtonType allow = new ButtonType("Allow Any IP", ButtonBar.ButtonData.OTHER);
        String transportWarning = getHttpsCheckBox().isSelected() ?
            "HTTPS encrypts the connection, but access controls and the computer's firewall still determine what " +
                "remote users can reach." :
            "The connection uses plain HTTP, so passwords, receiver activity, and decoded audio can be intercepted.";
        Alert alert = new Alert(Alert.AlertType.WARNING,
            "Anyone who can reach this computer on this port can reach the web interface. " + transportWarning +
                "\n\nDo not port-forward this port or expose it directly to the public internet. Use only on a " +
                "trusted LAN, Tailnet, VPN, or behind a firewall.",
            allow, ButtonType.CANCEL);
        alert.setTitle("Web Server Security Warning");
        alert.setHeaderText("Allow connections from any IP address?");
        alert.initOwner(getAnyIpRadioButton().getScene().getWindow());
        ((Button)alert.getDialogPane().lookupButton(allow)).setDefaultButton(false);
        ((Button)alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);

        if(alert.showAndWait().filter(allow::equals).isPresent())
        {
            mApplicationPreference.setStatsWebServerAnyIpEnabled(true);
        }
        else
        {
            getLocalOnlyRadioButton().setSelected(true);
            mApplicationPreference.setStatsWebServerAnyIpEnabled(false);
        }

        updateControlState();
    }

    private Spinner<Integer> getPortSpinner()
    {
        if(mPortSpinner == null)
        {
            mPortSpinner = new Spinner<>(ApplicationPreference.MIN_STATS_WEB_SERVER_PORT,
                ApplicationPreference.MAX_STATS_WEB_SERVER_PORT, mApplicationPreference.getStatsWebServerPort(), 1);
            mPortSpinner.valueProperty().addListener((observable, oldValue, newValue) -> {
                mApplicationPreference.setStatsWebServerPort(newValue);
                updateControlState();
            });
        }

        return mPortSpinner;
    }

    private CheckBox getHttpsCheckBox()
    {
        if(mHttpsCheckBox == null)
        {
            mHttpsCheckBox = new CheckBox("Enable HTTPS");
            mHttpsCheckBox.setTooltip(new Tooltip(
                "Encrypts browser connections using the installed certificate and matching private key."));
            mHttpsCheckBox.setSelected(mApplicationPreference.isStatsWebServerHttpsEnabled());
            mHttpsCheckBox.setOnAction(event -> setHttpsEnabled(mHttpsCheckBox.isSelected()));
        }

        return mHttpsCheckBox;
    }

    private TextField getCertificateCommonNameField()
    {
        if(mCertificateCommonNameField == null)
        {
            mCertificateCommonNameField = new TextField(defaultCertificateCommonName());
            mCertificateCommonNameField.setPromptText("receiver.example");
            mCertificateCommonNameField.setTooltip(new Tooltip(
                "Certificate display name. The computer name used by browsers is usually the best choice."));
        }

        return mCertificateCommonNameField;
    }

    private TextField getCertificateSanField()
    {
        if(mCertificateSanField == null)
        {
            String commonName = getCertificateCommonNameField().getText().strip();
            LinkedHashSet<String> defaults = new LinkedHashSet<>();
            defaults.add(commonName);
            defaults.add("localhost");
            defaults.add("127.0.0.1");
            mCertificateSanField = new TextField(String.join(", ", defaults));
            mCertificateSanField.setPromptText("receiver.example, 192.0.2.10, localhost, 127.0.0.1");
            mCertificateSanField.setTooltip(new Tooltip(
                "Comma-separated computer names and IP addresses that browsers use to reach this receiver."));
        }

        return mCertificateSanField;
    }

    private Button getGenerateSelfSignedButton()
    {
        if(mGenerateSelfSignedButton == null)
        {
            mGenerateSelfSignedButton = new Button("Generate Self-Signed");
            mGenerateSelfSignedButton.setTooltip(new Tooltip(
                "Generates and installs a one-year RSA certificate for the common name and SANs above."));
            mGenerateSelfSignedButton.setOnAction(event -> generateSelfSignedCertificate());
        }

        return mGenerateSelfSignedButton;
    }

    private Button getImportTlsPairButton()
    {
        if(mImportTlsPairButton == null)
        {
            mImportTlsPairButton = new Button("Import Certificate + Key");
            mImportTlsPairButton.setTooltip(new Tooltip(
                "Selects a PEM certificate chain and then its matching unencrypted PKCS#8 PEM private key. " +
                    "Nothing is replaced unless the complete pair validates."));
            mImportTlsPairButton.setOnAction(event -> importTlsPair());
        }

        return mImportTlsPairButton;
    }

    private Label getTlsStatusLabel()
    {
        if(mTlsStatusLabel == null)
        {
            mTlsStatusLabel = new Label("Checking installed certificate material...");
            mTlsStatusLabel.setWrapText(true);
            mTlsStatusLabel.setMaxWidth(Double.MAX_VALUE);
        }

        return mTlsStatusLabel;
    }

    private Label getAdminStatusLabel()
    {
        if(mAdminStatusLabel == null)
        {
            mAdminStatusLabel = new Label("Checking the local primary administrator account...");
            mAdminStatusLabel.setWrapText(true);
            mAdminStatusLabel.setMaxWidth(Double.MAX_VALUE);
        }

        return mAdminStatusLabel;
    }

    private Button getConfigureAdminButton()
    {
        if(mConfigureAdminButton == null)
        {
            mConfigureAdminButton = new Button("Set Primary Administrator");
            mConfigureAdminButton.setTooltip(new Tooltip(
                "Sets or resets the fixed admin account. Account recovery is available only on this computer."));
            mConfigureAdminButton.setOnAction(event -> showAdminCredentialDialog());
        }

        return mConfigureAdminButton;
    }

    private ProgressIndicator getAdminProgressIndicator()
    {
        if(mAdminProgressIndicator == null)
        {
            mAdminProgressIndicator = new ProgressIndicator();
            mAdminProgressIndicator.setMaxSize(20, 20);
            mAdminProgressIndicator.setVisible(false);
            mAdminProgressIndicator.setManaged(false);
        }

        return mAdminProgressIndicator;
    }

    private Label getUrlLabel()
    {
        if(mUrlLabel == null)
        {
            mUrlLabel = new Label();
            mUrlLabel.setWrapText(true);
        }

        return mUrlLabel;
    }

    private void setServerEnabled(boolean enabled)
    {
        mApplicationPreference.setStatsWebServerEnabled(enabled);
        getServerCheckBox().setSelected(enabled);
        updateControlState();
    }

    private void setHttpsEnabled(boolean enabled)
    {
        if(enabled)
        {
            try
            {
                getTlsMaterialService().validateInstalledMaterial();
            }
            catch(Exception exception)
            {
                mLog.warn("Unable to enable HTTPS because the installed certificate pair is unavailable or invalid");
                getHttpsCheckBox().setSelected(false);
                getTlsStatusLabel().setText(
                    "HTTPS was not enabled. Generate or import a valid certificate and matching private key first.");
                updateControlState();
                return;
            }
        }

        mApplicationPreference.setStatsWebServerHttpsEnabled(enabled);
        getHttpsCheckBox().setSelected(enabled);
        updateControlState();
    }

    private void generateSelfSignedCertificate()
    {
        String commonName = getCertificateCommonNameField().getText().strip();
        List<String> subjectAlternativeNames = parseSubjectAlternativeNames(commonName);

        if(commonName.isEmpty() || subjectAlternativeNames.isEmpty())
        {
            getTlsStatusLabel().setText(
                "Enter a certificate common name and at least one computer name or IP address.");
            return;
        }

        try
        {
            TlsMaterial material = getTlsMaterialService().generateSelfSigned(commonName, subjectAlternativeNames);
            showInstalledTlsMaterial(material, "Self-signed certificate and private key installed.", true);
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to generate local HTTPS certificate material", exception);
            getTlsStatusLabel().setText(
                "The self-signed certificate could not be generated. Check the common name and SAN values.");
        }
    }

    private List<String> parseSubjectAlternativeNames(String commonName)
    {
        LinkedHashSet<String> names = new LinkedHashSet<>();

        if(commonName != null && !commonName.isBlank())
        {
            names.add(commonName.strip());
        }

        for(String value: getCertificateSanField().getText().split("[,\\n]"))
        {
            String name = value.strip();

            if(!name.isEmpty())
            {
                names.add(name);
            }
        }

        return List.copyOf(names);
    }

    private void importTlsPair()
    {
        Path certificate = chooseTlsFile("Import HTTPS Certificate", "Certificates", "*.pem", "*.crt", "*.cer");

        if(certificate == null)
        {
            return;
        }

        Path privateKey = chooseTlsFile("Import HTTPS Private Key", "Private keys", "*.pem", "*.key");

        if(privateKey == null)
        {
            getTlsStatusLabel().setText("Import canceled. The installed certificate pair was not changed.");
            return;
        }

        try
        {
            TlsMaterial material = getTlsMaterialService().importPem(certificate, privateKey);
            showInstalledTlsMaterial(material, "Certificate and matching private key installed.", true);
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to import local HTTPS certificate and private-key material", exception);
            getTlsStatusLabel().setText(
                "The certificate pair was not imported. Select a valid certificate chain and its matching " +
                    "unencrypted PKCS#8 private key.");
        }
    }

    private Path chooseTlsFile(String title, String filterDescription, String... extensions)
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterDescription, extensions));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        java.io.File selected = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        return selected != null ? selected.toPath() : null;
    }

    private void refreshTlsStatus()
    {
        try
        {
            boolean certificateInstalled = Files.isRegularFile(getTlsMaterialService().certificatePath());
            boolean privateKeyInstalled = Files.isRegularFile(getTlsMaterialService().privateKeyPath());

            if(certificateInstalled && privateKeyInstalled)
            {
                showInstalledTlsMaterial(getTlsMaterialService().validateInstalledMaterial(),
                    "Certificate and private key are installed and valid.", false);
            }
            else if(certificateInstalled || privateKeyInstalled)
            {
                getTlsStatusLabel().setText(
                    "The installed HTTPS material is incomplete. Import a complete matching certificate and key pair.");
            }
            else
            {
                getTlsStatusLabel().setText("No HTTPS certificate or private key is installed.");
            }
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to validate local HTTPS certificate material");
            getTlsStatusLabel().setText(
                "The installed HTTPS certificate pair is invalid. Import or generate a replacement pair.");
        }
    }

    private void showInstalledTlsMaterial(TlsMaterial material, String message, boolean newlyInstalled)
    {
        try
        {
            String activation = newlyInstalled && getServerCheckBox().isSelected() &&
                getHttpsCheckBox().isSelected() ?
                " Restart the embedded web server to serve a newly installed certificate." :
                (!getHttpsCheckBox().isSelected() ? " Enable HTTPS to use it." : "");
            getTlsStatusLabel().setText(message + " Leaf SHA-256: " + material.leafSha256Fingerprint() + "." +
                activation);
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to calculate the installed HTTPS certificate fingerprint", exception);
            getTlsStatusLabel().setText(message + " The certificate fingerprint could not be displayed.");
        }
    }

    private WebTlsMaterialService getTlsMaterialService()
    {
        if(mTlsMaterialService == null)
        {
            mTlsMaterialService = new WebTlsMaterialService(PortableApplicationPaths.getDataRoot());
        }

        return mTlsMaterialService;
    }

    private static String defaultCertificateCommonName()
    {
        try
        {
            String hostName = InetAddress.getLocalHost().getHostName();

            if(hostName != null && !hostName.isBlank())
            {
                return hostName.strip();
            }
        }
        catch(Exception exception)
        {
            mLog.debug("Unable to determine the local computer name for the HTTPS certificate form");
        }

        return "localhost";
    }

    private void refreshAdminStatus()
    {
        setAdminOperationRunning(true, "Checking the local primary administrator account...");
        runAdminOperation(() -> new WebAccessService(mDatabasePath).isPrimaryAdminConfigured(), configured -> {
            mAdminStatusKnown = true;
            mAdminConfigured = configured;
            getAdminStatusLabel().setText(configured ?
                "Configured: " + WebAccessService.PRIMARY_ADMIN_USERNAME + " (managed only from JavaFX)." :
                "Not configured. Administrator login is unavailable until a password is set here.");
            getConfigureAdminButton().setText(configured ?
                "Reset Primary Administrator" : "Set Primary Administrator");
            updateControlState();
        }, "Unable to read the local primary administrator account.");
    }

    private void showAdminCredentialDialog()
    {
        if(getServerCheckBox().isSelected() || mAdminOperationRunning || !mAdminStatusKnown)
        {
            return;
        }

        Dialog<AdminPasswordInput> dialog = new Dialog<>();
        dialog.setTitle(mAdminConfigured ? "Reset Primary Web Administrator" : "Set Primary Web Administrator");
        dialog.setHeaderText(mAdminConfigured ?
            "Replace the password for the fixed admin account" :
            "Create the fixed admin account");
        if(getScene() != null)
        {
            dialog.initOwner(getScene().getWindow());
        }
        ButtonType saveButtonType = new ButtonType(mAdminConfigured ? "Reset Password" : "Set Password",
            ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(10);
        fields.setPadding(new Insets(10));
        Label username = new Label(WebAccessService.PRIMARY_ADMIN_USERNAME);
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS + "-" +
            Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS + " characters");
        PasswordField confirmField = new PasswordField();
        confirmField.setPromptText("Repeat password");
        Label validationLabel = new Label();
        validationLabel.setWrapText(true);
        fields.add(new Label("Username"), 0, 0);
        fields.add(username, 1, 0);
        fields.add(new Label("Password"), 0, 1);
        fields.add(passwordField, 1, 1);
        fields.add(new Label("Confirm password"), 0, 2);
        fields.add(confirmField, 1, 2);
        fields.add(validationLabel, 0, 3, 2, 1);
        GridPane.setHgrow(passwordField, Priority.ALWAYS);
        GridPane.setHgrow(confirmField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(fields);

        Button saveButton = (Button)dialog.getDialogPane().lookupButton(saveButtonType);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            String validationMessage = validateAdminPassword(passwordField, confirmField);

            if(validationMessage != null)
            {
                validationLabel.setText(validationMessage);
                event.consume();
            }
        });
        dialog.setResultConverter(buttonType -> {
            if(buttonType != saveButtonType)
            {
                return null;
            }

            char[] password = passwordField.getText().toCharArray();
            passwordField.clear();
            confirmField.clear();
            return new AdminPasswordInput(password);
        });

        Optional<AdminPasswordInput> result;

        try
        {
            result = dialog.showAndWait();
        }
        finally
        {
            passwordField.clear();
            confirmField.clear();
        }

        result.ifPresent(this::provisionOrResetAdmin);
    }

    private static String validateAdminPassword(PasswordField passwordField, PasswordField confirmField)
    {
        char[] password = passwordField.getText().toCharArray();
        char[] confirmation = confirmField.getText().toCharArray();

        try
        {
            if(password.length < Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS ||
                password.length > Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS)
            {
                return "Password must contain " + Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS + "-" +
                    Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS + " characters.";
            }

            if(!Arrays.equals(password, confirmation))
            {
                return "Passwords do not match.";
            }

            return null;
        }
        finally
        {
            Arrays.fill(password, '\u0000');
            Arrays.fill(confirmation, '\u0000');
        }
    }

    private void provisionOrResetAdmin(AdminPasswordInput input)
    {
        setAdminOperationRunning(true, "Securing and saving the primary administrator account...");
        runAdminOperation(() -> {
            try
            {
                return new WebAccessService(mDatabasePath).provisionOrResetPrimaryAdmin(input.password()).username();
            }
            finally
            {
                input.clear();
            }
        }, username -> {
            mAdminStatusKnown = true;
            mAdminConfigured = true;
            getAdminStatusLabel().setText("Configured: " + username +
                " (managed only from JavaFX). Start the embedded web server to use the new password.");
            getConfigureAdminButton().setText("Reset Primary Administrator");
            updateControlState();
        }, "Unable to save the local primary administrator account.");
    }

    private <T> void runAdminOperation(CheckedSupplier<T> work, java.util.function.Consumer<T> completion,
                                       String failureMessage)
    {
        try
        {
            Thread.ofPlatform().daemon(true).name("sdrtrunk local web administrator").start(() -> {
                T value = null;
                Exception failure = null;

                try
                {
                    value = work.get();
                }
                catch(Exception exception)
                {
                    failure = exception;
                }

                T completedValue = value;
                Exception completedFailure = failure;
                Platform.runLater(() -> {
                    setAdminOperationRunning(false, null);

                    if(completedFailure != null)
                    {
                        mLog.error(failureMessage, completedFailure);
                        getAdminStatusLabel().setText(failureMessage);
                    }
                    else
                    {
                        completion.accept(completedValue);
                    }
                });
            });
        }
        catch(RuntimeException exception)
        {
            setAdminOperationRunning(false, failureMessage);
            mLog.error(failureMessage, exception);
        }
    }

    private void setAdminOperationRunning(boolean running, String status)
    {
        mAdminOperationRunning = running;
        getAdminProgressIndicator().setVisible(running);
        getAdminProgressIndicator().setManaged(running);

        if(status != null)
        {
            getAdminStatusLabel().setText(status);
        }

        updateControlState();
    }

    private void updateControlState()
    {
        boolean running = getServerCheckBox().isSelected();
        String host = mApplicationPreference.isStatsWebServerAnyIpEnabled() ? "<this-computer-ip>" : "127.0.0.1";
        String scheme = getHttpsCheckBox().isSelected() ? "https" : "http";
        String url = scheme + "://" + host + ":" + mApplicationPreference.getStatsWebServerPort() + "/";
        getUrlLabel().setText(running ? url : "Stopped - " + url);
        getServerCheckBox().setDisable(mAdminOperationRunning);
        getConfigureAdminButton().setDisable(running || mAdminOperationRunning || !mAdminStatusKnown);
    }

    @FunctionalInterface
    private interface CheckedSupplier<T>
    {
        T get() throws Exception;
    }

    private record AdminPasswordInput(char[] password)
    {
        private void clear()
        {
            Arrays.fill(password, '\u0000');
        }
    }
}
