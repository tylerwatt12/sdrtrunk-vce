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
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.stats.StatsWebPath;
import io.github.dsheirer.web.auth.LocalWebAdminProvisioningService;
import io.github.dsheirer.web.auth.Pbkdf2PasswordHasher;
import io.github.dsheirer.web.auth.WebAdminCredential;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
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
    public enum RuntimeMode
    {
        RUNNING_APPLICATION,
        LOCAL_MAINTENANCE
    }

    private static final Logger mLog = LoggerFactory.getLogger(WebServerPreferenceEditor.class);
    private final ApplicationPreference mApplicationPreference;
    private final Path mDatabasePath;
    private final Path mPortableDataRoot;
    private final RuntimeMode mRuntimeMode;
    private GridPane mEditorPane;
    private CheckBox mServerCheckBox;
    private TextField mListenAddressField;
    private CheckBox mHttpsCheckBox;
    private Label mSanLabel;
    private TextField mSanField;
    private Button mGenerateSelfSignedButton;
    private Button mImportCertificateButton;
    private Button mImportPrivateKeyButton;
    private Label mTlsStatusLabel;
    private Label mUrlLabel;
    private Button mStartButton;
    private Button mStopButton;
    private Label mAdminStatusLabel;
    private Button mConfigureAdminButton;
    private ProgressIndicator mAdminProgressIndicator;
    private boolean mAdminOperationRunning;
    private boolean mAdminStatusKnown;
    private boolean mAdminConfigured;
    private boolean mAdminCredentialUnreadable;
    private String mAdminUsername;
    private Path mSelectedCertificatePath;
    private Path mSelectedPrivateKeyPath;
    private WebTlsMaterialService mTlsMaterialService;
    private volatile CompletableFuture<Void> mAdminCompletion = CompletableFuture.completedFuture(null);

    public WebServerPreferenceEditor(UserPreferences userPreferences)
    {
        this(userPreferences, RuntimeMode.RUNNING_APPLICATION);
    }

    public WebServerPreferenceEditor(UserPreferences userPreferences, RuntimeMode runtimeMode)
    {
        this(Objects.requireNonNull(userPreferences, "User preferences cannot be null").getApplicationPreference(),
            SdrTrunkDatabasePath.getDatabasePath(userPreferences), runtimeMode);
    }

    public WebServerPreferenceEditor(ApplicationPreference applicationPreference, Path databasePath,
                                     RuntimeMode runtimeMode)
    {
        mApplicationPreference = Objects.requireNonNull(applicationPreference,
            "Application preference cannot be null");
        mDatabasePath = Objects.requireNonNull(databasePath, "Database path cannot be null")
            .toAbsolutePath().normalize();
        Path databaseDirectory = mDatabasePath.getParent();

        if(databaseDirectory == null || databaseDirectory.getParent() == null)
        {
            throw new IllegalArgumentException("Database path must be inside the portable data directory");
        }

        mPortableDataRoot = databaseDirectory.getParent();
        mRuntimeMode = Objects.requireNonNull(runtimeMode, "Runtime mode cannot be null");
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
            mEditorPane.add(new Label("Listen Address"), 0, row);
            mEditorPane.add(getListenAddressField(), 1, row++, 2, 1);
            mEditorPane.add(getHttpsCheckBox(), 0, row++, 3, 1);
            mSanLabel = new Label("Certificate names (SANs)");
            mEditorPane.add(mSanLabel, 0, row);
            mEditorPane.add(getSanField(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Import HTTPS pair"), 0, row);
            mEditorPane.add(new HBox(8, getGenerateSelfSignedButton(), getImportCertificateButton(),
                getImportPrivateKeyButton()), 1, row++, 2, 1);
            mEditorPane.add(new Label("HTTPS status"), 0, row);
            mEditorPane.add(getTlsStatusLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Web URL"), 0, row);
            mEditorPane.add(getUrlLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Editable assets folder"), 0, row);
            Label assets = new Label(StatsWebPath.getAssetsPath().toString());
            assets.setWrapText(true);
            mEditorPane.add(assets, 1, row++, 2, 1);
            if(mRuntimeMode == RuntimeMode.RUNNING_APPLICATION)
            {
                mEditorPane.add(new Label("Server control"), 0, row);
                mEditorPane.add(new HBox(8, getStartButton(), getStopButton()), 1, row++, 2, 1);
            }
            else
            {
                Label restartHelp = new Label("Changes take effect the next time sdrtrunk-vce starts normally.");
                restartHelp.setWrapText(true);
                mEditorPane.add(new Label("Apply changes"), 0, row);
                mEditorPane.add(restartHelp, 1, row++, 2, 1);
            }
            mEditorPane.add(new Label("Web administrator"), 0, row);
            mEditorPane.add(getAdminStatusLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Local account setup"), 0, row);
            mEditorPane.add(new HBox(8, getConfigureAdminButton(), getAdminProgressIndicator()),
                1, row++, 2, 1);
            String adminHelpText = mRuntimeMode == RuntimeMode.RUNNING_APPLICATION ?
                "There is one web administrator account. Stop the web server before setting or resetting it. " +
                    "Account recovery is available only from this local JavaFX screen." :
                "There is one web administrator account. This maintenance window must be closed before normal " +
                    "sdrtrunk-vce is restarted.";
            Label adminHelp = new Label(adminHelpText);
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
            mServerCheckBox = new CheckBox(mRuntimeMode == RuntimeMode.RUNNING_APPLICATION ?
                "Run Embedded Web Server" : "Run Embedded Web Server on normal startup");
            mServerCheckBox.setTooltip(new Tooltip(
                "Runs independently of statistics collection. Live Systems and web audio remain available when " +
                    "summary collection is off."));
            mServerCheckBox.setSelected(mApplicationPreference.isStatsWebServerEnabled());
            mServerCheckBox.setOnAction(event -> setServerEnabled(mServerCheckBox.isSelected()));
        }

        return mServerCheckBox;
    }

    private TextField getListenAddressField()
    {
        if(mListenAddressField == null)
        {
            mListenAddressField = new TextField(mApplicationPreference.getStatsWebServerListenAddress());
            mListenAddressField.setPromptText("127.0.0.1:8090");
            mListenAddressField.setTooltip(new Tooltip(
                "Use 127.0.0.1:8090 for access from this computer only, or a receiver address for network access."));
            mListenAddressField.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(mListenAddressField, Priority.ALWAYS);
            mListenAddressField.setOnAction(event -> saveListenAddress());
            mListenAddressField.focusedProperty().addListener((observable, oldValue, focused) -> {
                if(!focused)
                {
                    saveListenAddress();
                }
            });
        }

        return mListenAddressField;
    }

    private void saveListenAddress()
    {
        String listenAddress = getListenAddressField().getText();
        String previousDefaultSan = defaultSan();
        String currentSans = getSanField().getText().strip();

        try
        {
            mApplicationPreference.setStatsWebServerListenAddress(listenAddress);
            getListenAddressField().setText(mApplicationPreference.getStatsWebServerListenAddress());

            if(currentSans.isBlank() || currentSans.equals(previousDefaultSan))
            {
                getSanField().setText(defaultSan());
            }
        }
        catch(IllegalArgumentException exception)
        {
            getListenAddressField().setText(mApplicationPreference.getStatsWebServerListenAddress());
            getTlsStatusLabel().setText("Listen address was not changed. Use an address such as 127.0.0.1:8090.");
        }

        updateControlState();
    }

    private CheckBox getHttpsCheckBox()
    {
        if(mHttpsCheckBox == null)
        {
            mHttpsCheckBox = new CheckBox("Enable HTTPS");
            mHttpsCheckBox.setSelected(mApplicationPreference.isStatsWebServerHttpsEnabled());
            mHttpsCheckBox.setOnAction(event -> {
                mApplicationPreference.setStatsWebServerHttpsEnabled(mHttpsCheckBox.isSelected());
                updateControlState();
                refreshTlsStatus();
            });
        }

        return mHttpsCheckBox;
    }

    private TextField getSanField()
    {
        if(mSanField == null)
        {
            mSanField = new TextField(defaultSan());
            mSanField.setPromptText("bosgame, 192.168.1.20");
            mSanField.setTooltip(new Tooltip(
                "Comma-separated computer names and IP addresses used to open the web interface."));
            mSanField.setMaxWidth(Double.MAX_VALUE);
        }

        return mSanField;
    }

    private String defaultSan()
    {
        String address = mApplicationPreference.getStatsWebServerListenAddress().strip();
        String host;

        if(address.startsWith("["))
        {
            int closingBracket = address.indexOf(']');
            host = closingBracket > 1 ? address.substring(1, closingBracket) : "";
        }
        else
        {
            int portSeparator = address.lastIndexOf(':');
            host = portSeparator > 0 ? address.substring(0, portSeparator) : address;
        }

        return switch(host)
        {
            case "0.0.0.0" -> "127.0.0.1";
            case "::" -> "::1";
            default -> host;
        };
    }

    private Button getGenerateSelfSignedButton()
    {
        if(mGenerateSelfSignedButton == null)
        {
            mGenerateSelfSignedButton = new Button("Generate Self-Signed");
            mGenerateSelfSignedButton.setOnAction(event -> generateSelfSigned());
        }

        return mGenerateSelfSignedButton;
    }

    private Button getImportCertificateButton()
    {
        if(mImportCertificateButton == null)
        {
            mImportCertificateButton = new Button("Select Certificate");
            mImportCertificateButton.setOnAction(event -> importCertificate());
        }

        return mImportCertificateButton;
    }

    private Button getImportPrivateKeyButton()
    {
        if(mImportPrivateKeyButton == null)
        {
            mImportPrivateKeyButton = new Button("Select Private Key");
            mImportPrivateKeyButton.setOnAction(event -> importPrivateKey());
        }

        return mImportPrivateKeyButton;
    }

    private Label getTlsStatusLabel()
    {
        if(mTlsStatusLabel == null)
        {
            mTlsStatusLabel = new Label("Checking local certificate material...");
            mTlsStatusLabel.setWrapText(true);
            mTlsStatusLabel.setMaxWidth(Double.MAX_VALUE);
        }

        return mTlsStatusLabel;
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

    private Button getStartButton()
    {
        if(mStartButton == null)
        {
            mStartButton = new Button("Start");
            mStartButton.setOnAction(event -> setServerEnabled(true));
        }

        return mStartButton;
    }

    private Button getStopButton()
    {
        if(mStopButton == null)
        {
            mStopButton = new Button("Stop");
            mStopButton.setOnAction(event -> setServerEnabled(false));
        }

        return mStopButton;
    }

    private Label getAdminStatusLabel()
    {
        if(mAdminStatusLabel == null)
        {
            mAdminStatusLabel = new Label("Checking local account...");
            mAdminStatusLabel.setWrapText(true);
            mAdminStatusLabel.setMaxWidth(Double.MAX_VALUE);
        }

        return mAdminStatusLabel;
    }

    private Button getConfigureAdminButton()
    {
        if(mConfigureAdminButton == null)
        {
            mConfigureAdminButton = new Button("Set Administrator");
            mConfigureAdminButton.setTooltip(new Tooltip(
                "Sets or replaces the only web administrator account. This is available only on this computer."));
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

    private void setServerEnabled(boolean enabled)
    {
        mApplicationPreference.setStatsWebServerEnabled(enabled);
        getServerCheckBox().setSelected(enabled);
        updateControlState();
    }

    private void updateControlState()
    {
        boolean enabled = getServerCheckBox().isSelected();
        boolean runningApplication = mRuntimeMode == RuntimeMode.RUNNING_APPLICATION;
        boolean settingsLocked = (runningApplication && enabled) || mAdminOperationRunning;
        boolean httpsEnabled = getHttpsCheckBox().isSelected();
        getServerCheckBox().setDisable(mAdminOperationRunning);
        getListenAddressField().setDisable(settingsLocked);
        getHttpsCheckBox().setDisable(settingsLocked);
        getSanField().setVisible(httpsEnabled);
        getSanField().setManaged(httpsEnabled);
        mSanLabel.setVisible(httpsEnabled);
        mSanLabel.setManaged(httpsEnabled);
        getSanField().setDisable(settingsLocked);
        getGenerateSelfSignedButton().setDisable(settingsLocked || !httpsEnabled);
        getImportCertificateButton().setDisable(settingsLocked || !httpsEnabled);
        getImportPrivateKeyButton().setDisable(settingsLocked || !httpsEnabled);

        if(runningApplication)
        {
            getStartButton().setDisable(enabled || mAdminOperationRunning);
            getStopButton().setDisable(!enabled || mAdminOperationRunning);
        }

        getConfigureAdminButton().setDisable(!mAdminStatusKnown || (runningApplication && enabled) ||
            mAdminOperationRunning);
        String scheme = httpsEnabled ? "https" : "http";
        String url = scheme + "://" + mApplicationPreference.getStatsWebServerListenAddress() + "/";
        getUrlLabel().setText(runningApplication ? (enabled ? url : "Stopped - " + url) :
            (enabled ? "Next normal start - " + url : "Disabled - " + url));
    }

    private void generateSelfSigned()
    {
        List<String> sans = parseSans();

        if(sans.isEmpty())
        {
            getTlsStatusLabel().setText("Enter at least one computer name or IP address before generating.");
            return;
        }

        try
        {
            getTlsMaterialService().generateSelfSigned(sans.getFirst(), sans);
            getTlsMaterialService().validateInstalledMaterial();
            getTlsStatusLabel().setText("Self-signed certificate and private key are ready for the next normal start.");
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to generate local HTTPS certificate material");
            getTlsStatusLabel().setText("Unable to generate the self-signed certificate.");
        }
    }

    private List<String> parseSans()
    {
        LinkedHashSet<String> sans = new LinkedHashSet<>();

        for(String value: getSanField().getText().split(","))
        {
            String san = value.strip();

            if(!san.isEmpty())
            {
                sans.add(san);
            }
        }

        return List.copyOf(sans);
    }

    private void importCertificate()
    {
        Path selected = chooseTlsFile("Import HTTPS Certificate", "Certificates", "*.pem", "*.crt", "*.cer");

        if(selected == null)
        {
            return;
        }

        mSelectedCertificatePath = selected;

        if(mSelectedPrivateKeyPath == null)
        {
            getTlsStatusLabel().setText("Certificate selected. Select the matching private key to import the pair.");
            return;
        }

        importSelectedPair();
    }

    private void importPrivateKey()
    {
        Path selected = chooseTlsFile("Import HTTPS Private Key", "Private keys", "*.pem", "*.key");

        if(selected == null)
        {
            return;
        }

        mSelectedPrivateKeyPath = selected;

        if(mSelectedCertificatePath == null)
        {
            getTlsStatusLabel().setText("Private key selected. Select the matching certificate to import the pair.");
            return;
        }

        importSelectedPair();
    }

    private void importSelectedPair()
    {
        Path certificate = mSelectedCertificatePath;
        Path privateKey = mSelectedPrivateKeyPath;
        mSelectedCertificatePath = null;
        mSelectedPrivateKeyPath = null;

        try
        {
            getTlsMaterialService().importPem(certificate, privateKey);
            refreshTlsStatus();
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to import local HTTPS certificate and private-key material");
            getTlsStatusLabel().setText("The certificate and private key were not imported.");
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
                getTlsMaterialService().validateInstalledMaterial();
                getTlsStatusLabel().setText("Certificate and private key are installed.");
            }
            else if(certificateInstalled)
            {
                getTlsStatusLabel().setText("Certificate imported. Import a private key to complete HTTPS setup.");
            }
            else if(privateKeyInstalled)
            {
                getTlsStatusLabel().setText("Private key imported. Import a certificate to complete HTTPS setup.");
            }
            else
            {
                getTlsStatusLabel().setText("No certificate or private key is installed.");
            }
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to validate local HTTPS certificate material");
            getTlsStatusLabel().setText("The installed certificate or private key could not be validated.");
        }
    }

    private WebTlsMaterialService getTlsMaterialService()
    {
        if(mTlsMaterialService == null)
        {
            mTlsMaterialService = new WebTlsMaterialService(mPortableDataRoot);
        }

        return mTlsMaterialService;
    }

    private void refreshAdminStatus()
    {
        setAdminOperationRunning(true, "Checking local account...");
        runAdminOperation(() -> {
            LocalWebAdminProvisioningService.Status status =
                new LocalWebAdminProvisioningService(mDatabasePath).inspect();
            return switch(status.state())
            {
                case CONFIGURED -> new AdminStatus(true, false, status.username());
                case UNCONFIGURED -> new AdminStatus(false, false, null);
                case UNREADABLE -> new AdminStatus(false, true, null);
            };
        }, this::showAdminStatus,
            "Unable to read the local web administrator account.");
    }

    private void showAdminCredentialDialog()
    {
        if((mRuntimeMode == RuntimeMode.RUNNING_APPLICATION && getServerCheckBox().isSelected()) ||
            mAdminOperationRunning)
        {
            return;
        }

        Dialog<AdminCredentialInput> dialog = new Dialog<>();
        dialog.setTitle(mAdminCredentialUnreadable ? "Repair Web Administrator" :
            (mAdminConfigured ? "Reset Web Administrator" : "Set Web Administrator"));
        dialog.setHeaderText(mAdminCredentialUnreadable ?
            "The stored administrator account is unreadable and will be replaced" :
            (mAdminConfigured ? "Replace the one web administrator account" :
                "Create the one web administrator account"));
        ButtonType saveButtonType = new ButtonType(mAdminCredentialUnreadable ? "Repair Account" :
            (mAdminConfigured ? "Reset Account" : "Set Account"),
            ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(10);
        fields.setPadding(new Insets(10));
        TextField usernameField = new TextField(mAdminUsername != null ? mAdminUsername : "admin");
        usernameField.setPromptText("admin");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS + "-" +
            Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS + " characters");
        PasswordField confirmField = new PasswordField();
        confirmField.setPromptText("Repeat password");
        Label validationLabel = new Label();
        validationLabel.setWrapText(true);
        fields.add(new Label("Username"), 0, 0);
        fields.add(usernameField, 1, 0);
        fields.add(new Label("Password"), 0, 1);
        fields.add(passwordField, 1, 1);
        fields.add(new Label("Confirm password"), 0, 2);
        fields.add(confirmField, 1, 2);
        fields.add(validationLabel, 0, 3, 2, 1);
        GridPane.setHgrow(usernameField, Priority.ALWAYS);
        GridPane.setHgrow(passwordField, Priority.ALWAYS);
        GridPane.setHgrow(confirmField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(fields);

        Button saveButton = (Button)dialog.getDialogPane().lookupButton(saveButtonType);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            String validationMessage = validateAdminInput(usernameField.getText(), passwordField, confirmField);

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

            try
            {
                return new AdminCredentialInput(WebAdminCredential.normalizeUsername(usernameField.getText()),
                    password);
            }
            catch(RuntimeException exception)
            {
                Arrays.fill(password, '\u0000');
                throw exception;
            }
            finally
            {
                passwordField.clear();
                confirmField.clear();
            }
        });

        Optional<AdminCredentialInput> result;

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

    private static String validateAdminInput(String username, PasswordField passwordField, PasswordField confirmField)
    {
        try
        {
            WebAdminCredential.normalizeUsername(username);
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            return "Use 1-64 lowercase letters, numbers, dots, underscores, or hyphens for the username.";
        }

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

    private void provisionOrResetAdmin(AdminCredentialInput input)
    {
        boolean repairUnreadable = mAdminCredentialUnreadable;
        setAdminOperationRunning(true, repairUnreadable ? "Repairing and securing the local account..." :
            "Securing and saving the local account...");
        runAdminOperation(() -> {
            try
            {
                LocalWebAdminProvisioningService provisioningService =
                    new LocalWebAdminProvisioningService(mDatabasePath);

                if(repairUnreadable)
                {
                    mLog.warn("Replacing an unreadable web administrator credential from local administrator settings");
                    return new AdminStatus(true, false,
                        provisioningService.repairUnreadable(input.username(), input.password()).username());
                }

                return new AdminStatus(true, false,
                    provisioningService.provisionOrReset(input.username(), input.password()).username());
            }
            finally
            {
                input.clear();
            }
        }, this::showAdminStatus, "Unable to save the local web administrator account.");
    }

    private <T> void runAdminOperation(CheckedSupplier<T> work, java.util.function.Consumer<T> completion,
                                       String failureMessage)
    {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon(true).name("sdrtrunk local web administrator").factory(),
            new ThreadPoolExecutor.AbortPolicy());

        CompletableFuture<T> future;

        try
        {
            future = CompletableFuture.supplyAsync(() -> {
                try
                {
                    return work.get();
                }
                catch(Exception exception)
                {
                    throw new RuntimeException(exception);
                }
            }, executor);
        }
        catch(RuntimeException exception)
        {
            executor.shutdownNow();
            mAdminCompletion.complete(null);
            setAdminOperationRunning(false, failureMessage);
            throw exception;
        }

        CompletableFuture<Void> completionBarrier = mAdminCompletion;
        future.whenComplete((value, throwable) -> {
            executor.shutdown();
            completionBarrier.complete(null);
            Platform.runLater(() -> {
                setAdminOperationRunning(false, null);

                if(throwable != null)
                {
                    mLog.error(failureMessage, unwrap(throwable));
                    getAdminStatusLabel().setText(failureMessage);
                }
                else
                {
                    completion.accept(value);
                }
            });
        });
    }

    private void showAdminStatus(AdminStatus status)
    {
        mAdminStatusKnown = true;
        mAdminConfigured = status.configured();
        mAdminCredentialUnreadable = status.unreadable();
        mAdminUsername = status.username();
        getAdminStatusLabel().setText(status.unreadable() ?
            "Stored account is unreadable - repair it before browser administrator login can be used." :
            (status.configured() ? "Configured: " + status.username() + " (single administrator)" :
                "Not configured - browser administrator login is unavailable."));
        getConfigureAdminButton().setText(status.unreadable() ? "Repair Administrator" :
            (status.configured() ? "Reset Administrator" : "Set Administrator"));
        updateControlState();
    }

    public boolean isAdminOperationRunning()
    {
        return mAdminOperationRunning;
    }

    public void awaitAdminOperationCompletion()
    {
        mAdminCompletion.handle((ignored, throwable) -> null).join();
    }

    private void setAdminOperationRunning(boolean running, String status)
    {
        if(running)
        {
            mAdminCompletion = new CompletableFuture<>();
        }

        mAdminOperationRunning = running;
        getAdminProgressIndicator().setVisible(running);
        getAdminProgressIndicator().setManaged(running);

        if(status != null)
        {
            getAdminStatusLabel().setText(status);
        }

        updateControlState();
    }

    private static Throwable unwrap(Throwable throwable)
    {
        Throwable current = throwable;

        while(current.getCause() != null && current != current.getCause())
        {
            current = current.getCause();
        }

        return current;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T>
    {
        T get() throws Exception;
    }

    private record AdminStatus(boolean configured, boolean unreadable, String username)
    {
    }

    private record AdminCredentialInput(String username, char[] password)
    {
        private void clear()
        {
            Arrays.fill(password, '\u0000');
        }
    }
}
