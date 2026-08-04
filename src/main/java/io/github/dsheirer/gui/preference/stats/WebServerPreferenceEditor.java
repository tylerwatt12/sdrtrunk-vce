/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.gui.preference.stats;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.application.WebCertificateMode;
import io.github.dsheirer.stats.StatsWebPath;
import io.github.dsheirer.stats.StatsWebServerService;
import io.github.dsheirer.stats.WebServerRuntimeState;
import io.github.dsheirer.web.auth.Pbkdf2PasswordHasher;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.network.WebCertificateIdentity;
import io.github.dsheirer.web.network.WebNetworkAddressDiscovery;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import java.awt.Desktop;
import java.net.Inet4Address;
import java.net.URI;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded browser server preferences. Network access is HTTPS-only and certificate/private-key management remains
 * local to this JavaFX screen.
 */
public class WebServerPreferenceEditor extends HBox
{
    private static final Logger mLog = LoggerFactory.getLogger(WebServerPreferenceEditor.class);
    private static final DateTimeFormatter CERTIFICATE_DATE = DateTimeFormatter.ofPattern("MMM d, uuuu")
        .withZone(ZoneId.systemDefault());
    private static final Duration REFRESH_INTERVAL = Duration.seconds(30);

    private final ApplicationPreference mApplicationPreference;
    private final Path mDatabasePath;
    private final Path mDataRoot;
    private StatsWebServerService mStatsWebServerService;
    private WebTlsMaterialService mTlsMaterialService;
    private GridPane mEditorPane;
    private CheckBox mServerCheckBox;
    private RadioButton mLocalOnlyRadioButton;
    private RadioButton mNetworkRadioButton;
    private ToggleGroup mAccessModeToggleGroup;
    private TextField mPortField;
    private Label mRuntimeStatusLabel;
    private Label mLocalUrlLabel;
    private Label mNetworkUrlLabel;
    private Button mOpenButton;
    private Button mCopyButton;
    private Label mTlsStatusLabel;
    private Button mManageCertificateButton;
    private Label mAdminStatusLabel;
    private Button mConfigureAdminButton;
    private ProgressIndicator mAdminProgressIndicator;
    private Timeline mRefreshTimeline;
    private Window mRefreshWindow;
    private final ChangeListener<Window> mRefreshSceneWindowListener =
        (observable, oldWindow, newWindow) -> attachRefreshWindow(newWindow);
    private final ChangeListener<Boolean> mRefreshWindowShowingListener =
        (observable, oldValue, newValue) -> updatePeriodicRefresh(newValue);
    private WebCertificateIdentity mCertificateIdentity = fallbackCertificateIdentity();
    private TlsMaterial mInstalledTlsMaterial;
    private String mLocalUrl;
    private String mPreferredNetworkUrl;
    private boolean mUpdatingControls;
    private boolean mAdminOperationRunning;
    private boolean mAdminStatusKnown;
    private boolean mAdminConfigured;
    private boolean mPortValidationError;

    public WebServerPreferenceEditor(UserPreferences userPreferences)
    {
        this(userPreferences, null);
    }

    public WebServerPreferenceEditor(UserPreferences userPreferences, StatsWebServerService statsWebServerService)
    {
        mApplicationPreference = userPreferences.getApplicationPreference();
        mDatabasePath = SdrTrunkDatabasePath.getDatabasePath(userPreferences);
        mDataRoot = userPreferences.getDirectoryPreference().getDirectoryApplicationRoot();
        mStatsWebServerService = statsWebServerService;
        setMaxWidth(Double.MAX_VALUE);
        ScrollPane content = new ScrollPane(getEditorPane());
        content.setFitToWidth(true);
        content.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        content.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(content, Priority.ALWAYS);
        getChildren().add(content);
        configurePeriodicRefresh();
    }

    public void setStatsWebServerService(StatsWebServerService statsWebServerService)
    {
        mStatsWebServerService = statsWebServerService;
        mTlsMaterialService = statsWebServerService == null ? null : statsWebServerService.getTlsMaterialService();

        if(mEditorPane != null)
        {
            refreshRuntimeAndAddresses();
            refreshAdminStatus();
        }
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

            mEditorPane.add(sectionLabel("Web Interface"), 0, row++, 3, 1);
            mEditorPane.add(getServerCheckBox(), 0, row++, 3, 1);
            mEditorPane.add(new Label("Status"), 0, row);
            mEditorPane.add(getRuntimeStatusLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Access"), 0, row);
            mEditorPane.add(new HBox(12, getLocalOnlyRadioButton(), getNetworkRadioButton()), 1, row++, 2, 1);
            mEditorPane.add(new Label("This computer"), 0, row);
            mEditorPane.add(new HBox(8, getLocalUrlLabel(), getOpenButton()), 1, row++, 2, 1);
            mEditorPane.add(new Label("Other devices"), 0, row);
            HBox networkAddressBox = new HBox(8, getNetworkUrlLabel(), getCopyButton());
            HBox.setHgrow(getNetworkUrlLabel(), Priority.ALWAYS);
            mEditorPane.add(networkAddressBox, 1, row++, 2, 1);
            Label networkHelp = new Label(
                "Access from other devices is always encrypted with HTTPS and listens on every IPv4 network " +
                    "interface. " +
                    "Your firewall controls who can reach this port. Switching back to This computer may require " +
                    "signing in again in an already-open browser.");
            networkHelp.setWrapText(true);
            mEditorPane.add(networkHelp, 0, row++, 3, 1);

            mEditorPane.add(sectionLabel("Administrator"), 0, row++, 3, 1);
            mEditorPane.add(new Label("Primary account"), 0, row);
            mEditorPane.add(getAdminStatusLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Password"), 0, row);
            mEditorPane.add(new HBox(8, getConfigureAdminButton(), getAdminProgressIndicator()),
                1, row++, 2, 1);
            Label adminHelp = new Label(
                "The fixed admin password can be changed while the web interface is running. Existing admin " +
                    "sessions are signed out after a change.");
            adminHelp.setWrapText(true);
            mEditorPane.add(adminHelp, 0, row++, 3, 1);

            mEditorPane.add(sectionLabel("Security"), 0, row++, 3, 1);
            mEditorPane.add(new Label("Certificate"), 0, row);
            mEditorPane.add(getTlsStatusLabel(), 1, row++, 2, 1);
            mEditorPane.add(new Label("Certificate setup"), 0, row);
            mEditorPane.add(getManageCertificateButton(), 1, row++, 2, 1);
            Label certificateHelp = new Label(
                "The automatic certificate encrypts traffic, but a browser may show a trust warning. Import a " +
                    "certificate trusted by your devices to remove that warning.");
            certificateHelp.setWrapText(true);
            mEditorPane.add(certificateHelp, 0, row++, 3, 1);

            mEditorPane.add(getAdvancedPane(), 0, row, 3, 1);

            ColumnConstraints labelColumn = new ColumnConstraints();
            labelColumn.setPercentWidth(27);
            ColumnConstraints valueColumn = new ColumnConstraints();
            valueColumn.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(labelColumn, valueColumn);

            refreshRuntimeAndAddresses();
            refreshCertificateStatus();
            refreshAdminStatus();
        }

        return mEditorPane;
    }

    private static Label sectionLabel(String text)
    {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 1.08em;");
        return label;
    }

    private CheckBox getServerCheckBox()
    {
        if(mServerCheckBox == null)
        {
            mServerCheckBox = new CheckBox("Enable Web Interface");
            mServerCheckBox.setSelected(mApplicationPreference.isStatsWebServerEnabled());
            mServerCheckBox.setOnAction(event -> {
                if(!mUpdatingControls)
                {
                    setServerEnabled(mServerCheckBox.isSelected());
                }
            });
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
            mLocalOnlyRadioButton = new RadioButton("This computer");
            mLocalOnlyRadioButton.setToggleGroup(getAccessModeToggleGroup());
            mLocalOnlyRadioButton.setTooltip(new Tooltip("Listens only on 127.0.0.1."));
            mLocalOnlyRadioButton.setOnAction(event -> {
                if(!mUpdatingControls && mLocalOnlyRadioButton.isSelected())
                {
                    setNetworkAccessEnabled(false);
                }
            });
        }

        return mLocalOnlyRadioButton;
    }

    private RadioButton getNetworkRadioButton()
    {
        if(mNetworkRadioButton == null)
        {
            mNetworkRadioButton = new RadioButton("Other devices");
            mNetworkRadioButton.setToggleGroup(getAccessModeToggleGroup());
            mNetworkRadioButton.setTooltip(new Tooltip(
                "Listens on all IPv4 network interfaces using HTTPS."));
            mNetworkRadioButton.setOnAction(event -> {
                if(!mUpdatingControls && mNetworkRadioButton.isSelected())
                {
                    confirmNetworkAccess();
                }
            });
        }

        return mNetworkRadioButton;
    }

    private Label getRuntimeStatusLabel()
    {
        if(mRuntimeStatusLabel == null)
        {
            mRuntimeStatusLabel = wrappingLabel();
        }

        return mRuntimeStatusLabel;
    }

    private Label getLocalUrlLabel()
    {
        if(mLocalUrlLabel == null)
        {
            mLocalUrlLabel = wrappingLabel();
        }

        return mLocalUrlLabel;
    }

    private Label getNetworkUrlLabel()
    {
        if(mNetworkUrlLabel == null)
        {
            mNetworkUrlLabel = wrappingLabel();
        }

        return mNetworkUrlLabel;
    }

    private Button getOpenButton()
    {
        if(mOpenButton == null)
        {
            mOpenButton = new Button("Open");
            mOpenButton.setOnAction(event -> openLocalUrl());
        }

        return mOpenButton;
    }

    private Button getCopyButton()
    {
        if(mCopyButton == null)
        {
            mCopyButton = new Button("Copy");
            mCopyButton.setOnAction(event -> copyPreferredNetworkUrl());
        }

        return mCopyButton;
    }

    private Label getTlsStatusLabel()
    {
        if(mTlsStatusLabel == null)
        {
            mTlsStatusLabel = wrappingLabel();
        }

        return mTlsStatusLabel;
    }

    private Button getManageCertificateButton()
    {
        if(mManageCertificateButton == null)
        {
            mManageCertificateButton = new Button("Manage...");
            mManageCertificateButton.setOnAction(event -> showCertificateManager());
        }

        return mManageCertificateButton;
    }

    private Label getAdminStatusLabel()
    {
        if(mAdminStatusLabel == null)
        {
            mAdminStatusLabel = wrappingLabel();
            mAdminStatusLabel.setText("Checking the primary administrator...");
        }

        return mAdminStatusLabel;
    }

    private Button getConfigureAdminButton()
    {
        if(mConfigureAdminButton == null)
        {
            mConfigureAdminButton = new Button("Set Password...");
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

    private TitledPane getAdvancedPane()
    {
        GridPane advanced = new GridPane();
        advanced.setHgap(8);
        advanced.setVgap(8);
        advanced.setPadding(new Insets(8));
        advanced.add(new Label("Port"), 0, 0);
        advanced.add(getPortField(), 1, 0);
        advanced.add(new Label("Editable assets folder"), 0, 1);
        Label assets = wrappingLabel();
        assets.setText(StatsWebPath.getAssetsPath().toString());
        advanced.add(assets, 1, 1);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setPercentWidth(27);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        advanced.getColumnConstraints().addAll(labels, values);
        TitledPane pane = new TitledPane("Advanced", advanced);
        pane.setExpanded(false);
        return pane;
    }

    private TextField getPortField()
    {
        if(mPortField == null)
        {
            mPortField = new TextField(Integer.toString(mApplicationPreference.getStatsWebServerPort()));
            mPortField.setMaxWidth(110);
            mPortField.setOnAction(event -> commitPort());
            mPortField.focusedProperty().addListener((observable, oldValue, focused) -> {
                if(!focused)
                {
                    commitPort();
                }
            });
        }

        return mPortField;
    }

    private static Label wrappingLabel()
    {
        Label label = new Label();
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);
        return label;
    }

    private void configurePeriodicRefresh()
    {
        mRefreshTimeline = new Timeline(new KeyFrame(REFRESH_INTERVAL, event -> refreshRuntimeAndAddresses()));
        mRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            attachRefreshWindow(null);

            if(oldScene != null)
            {
                oldScene.windowProperty().removeListener(mRefreshSceneWindowListener);
            }

            if(newScene != null)
            {
                newScene.windowProperty().addListener(mRefreshSceneWindowListener);
                attachRefreshWindow(newScene.getWindow());
            }
        });

        if(getScene() != null)
        {
            attachRefreshWindow(getScene().getWindow());
        }
    }

    private void attachRefreshWindow(Window window)
    {
        if(mRefreshWindow != null)
        {
            mRefreshWindow.showingProperty().removeListener(mRefreshWindowShowingListener);
        }

        mRefreshWindow = window;

        if(mRefreshWindow != null)
        {
            mRefreshWindow.showingProperty().addListener(mRefreshWindowShowingListener);
            updatePeriodicRefresh(mRefreshWindow.isShowing());
        }
        else
        {
            updatePeriodicRefresh(false);
        }
    }

    private void updatePeriodicRefresh(boolean showing)
    {
        if(showing)
        {
            refreshRuntimeAndAddresses();
            mRefreshTimeline.playFromStart();
        }
        else
        {
            mRefreshTimeline.stop();
        }
    }

    private void setServerEnabled(boolean enabled)
    {
        if(enabled && mApplicationPreference.isStatsWebServerAnyIpEnabled() && !ensureNetworkTlsMaterial())
        {
            mUpdatingControls = true;
            getServerCheckBox().setSelected(false);
            mUpdatingControls = false;
            return;
        }

        mApplicationPreference.setStatsWebServerEnabled(enabled);
        refreshRuntimeAndAddresses();
    }

    private void confirmNetworkAccess()
    {
        ButtonType enable = new ButtonType("Enable", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.WARNING,
            "Other devices may reach the web interface through any network connected to this computer, including " +
                "LAN, VPN, and public-facing IPv4 interfaces. Your firewall controls access. HTTPS will be enabled " +
                "automatically.\n\nDo not expose this port directly to the public internet.", enable,
            ButtonType.CANCEL);
        alert.setTitle("Enable Access From Other Devices");
        alert.setHeaderText("Allow connections from other devices?");
        initializeOwner(alert);

        if(alert.showAndWait().filter(enable::equals).isPresent())
        {
            setNetworkAccessEnabled(true);
        }
        else
        {
            selectAccessMode(mApplicationPreference.isStatsWebServerAnyIpEnabled());
        }
    }

    private void setNetworkAccessEnabled(boolean enabled)
    {
        if(enabled && !ensureNetworkTlsMaterial())
        {
            selectAccessMode(false);
            return;
        }

        mApplicationPreference.setStatsWebServerNetworkAccessEnabled(enabled);
        refreshRuntimeAndAddresses();
    }

    private boolean ensureNetworkTlsMaterial()
    {
        refreshCertificateIdentity();

        try
        {
            WebCertificateMode mode = effectiveCertificateMode();

            if(mode == WebCertificateMode.CUSTOM)
            {
                mInstalledTlsMaterial = getTlsMaterialService().validateInstalledMaterial();

                if(!mApplicationPreference.isStatsWebServerCertificateModeConfigured())
                {
                    mApplicationPreference.setStatsWebServerCertificateMode(WebCertificateMode.CUSTOM);
                }

                refreshCertificateStatus();
                return true;
            }

            try
            {
                mInstalledTlsMaterial = getTlsMaterialService().validateInstalledMaterial();

                if(mCertificateIdentity.requiredSubjectAlternativeNames().stream()
                    .allMatch(mInstalledTlsMaterial::coversHost))
                {
                    refreshCertificateStatus();
                    return true;
                }
            }
            catch(Exception exception)
            {
                mLog.debug("No reusable automatic HTTPS certificate is installed");
            }

            mInstalledTlsMaterial = getTlsMaterialService().generateSelfSigned(mCertificateIdentity.commonName(),
                mCertificateIdentity.subjectAlternativeNames());
            mApplicationPreference.setStatsWebServerCertificateMode(WebCertificateMode.AUTOMATIC);
            refreshCertificateStatus();
            return true;
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to prepare HTTPS for local network access", exception);
            showError("HTTPS Setup Failed",
                effectiveCertificateMode() == WebCertificateMode.CUSTOM ?
                    "The custom certificate is unavailable or invalid. Import a valid replacement before enabling " +
                        "local network access." :
                    "An automatic certificate could not be created. Local network access was not enabled.");
            return false;
        }
    }

    private void commitPort()
    {
        if(mUpdatingControls)
        {
            return;
        }

        try
        {
            int port = Integer.parseInt(getPortField().getText().strip());

            if(port < ApplicationPreference.MIN_STATS_WEB_SERVER_PORT ||
                port > ApplicationPreference.MAX_STATS_WEB_SERVER_PORT)
            {
                throw new NumberFormatException();
            }

            getPortField().setStyle("");
            mPortValidationError = false;

            if(port != mApplicationPreference.getStatsWebServerPort())
            {
                mApplicationPreference.setStatsWebServerPort(port);
            }

            refreshRuntimeAndAddresses();
        }
        catch(NumberFormatException exception)
        {
            if(getPortField().isFocused())
            {
                mPortValidationError = true;
                getPortField().setStyle("-fx-border-color: #c62828;");
                getRuntimeStatusLabel().setText("Port must be between " +
                    ApplicationPreference.MIN_STATS_WEB_SERVER_PORT + " and " +
                    ApplicationPreference.MAX_STATS_WEB_SERVER_PORT + ".");
            }
            else
            {
                mPortValidationError = false;
                getPortField().setText(Integer.toString(mApplicationPreference.getStatsWebServerPort()));
                getPortField().setStyle("");
                refreshRuntimeAndAddresses();
            }
        }
    }

    private void refreshRuntimeAndAddresses()
    {
        List<String> previousAddresses = mCertificateIdentity.networkAddresses().stream()
            .map(WebNetworkAddressDiscovery.DiscoveredAddress::hostAddress).toList();
        refreshCertificateIdentity();
        List<String> currentAddresses = mCertificateIdentity.networkAddresses().stream()
            .map(WebNetworkAddressDiscovery.DiscoveredAddress::hostAddress).toList();

        if(!previousAddresses.equals(currentAddresses))
        {
            refreshCertificateStatus();
        }

        WebServerRuntimeState runtimeState = mStatsWebServerService != null ?
            mStatsWebServerService.getRuntimeState() : null;

        if(runtimeState != null && runtimeState.running() && runtimeState.https() &&
            !activeCertificateMatchesInstalled(runtimeState.certificateFingerprint()))
        {
            refreshCertificateStatus();
        }

        boolean configuredEnabled = mApplicationPreference.isStatsWebServerEnabled();
        boolean configuredNetwork = mApplicationPreference.isStatsWebServerAnyIpEnabled();
        int configuredPort = mApplicationPreference.getStatsWebServerPort();
        boolean running = runtimeState != null && runtimeState.running();
        int displayPort = running ? runtimeState.port() : configuredPort;
        boolean displayHttps = running ? runtimeState.https() : configuredNetwork;
        String scheme = displayHttps ? "https" : "http";

        mUpdatingControls = true;
        getServerCheckBox().setSelected(configuredEnabled);
        selectAccessMode(configuredNetwork);

        if(!getPortField().isFocused())
        {
            mPortValidationError = false;
            getPortField().setText(Integer.toString(configuredPort));
            getPortField().setStyle("");
        }

        mUpdatingControls = false;

        if(mPortValidationError && getPortField().isFocused())
        {
            // Preserve the range explanation until the focused edit is corrected or abandoned.
        }
        else if(running)
        {
            String activeListener = "Running — " + (runtimeState.https() ? "HTTPS" : "HTTP") + " — " +
                (runtimeState.anyIpEnabled() ? "Other devices" : "This computer") + " — Port " +
                runtimeState.port();

            if("Web server is running.".equals(runtimeState.statusMessage()))
            {
                getRuntimeStatusLabel().setText(activeListener);
            }
            else
            {
                getRuntimeStatusLabel().setText(runtimeState.statusMessage() + " Active listener: " +
                    activeListener.substring("Running — ".length()));
            }
        }
        else if(configuredEnabled && runtimeState != null)
        {
            getRuntimeStatusLabel().setText(runtimeState.statusMessage());
        }
        else
        {
            getRuntimeStatusLabel().setText("Stopped");
        }

        mLocalUrl = scheme + "://127.0.0.1:" + displayPort + "/";
        getLocalUrlLabel().setText(mLocalUrl);
        getOpenButton().setDisable(!running);
        updateNetworkAddresses(runtimeState, configuredNetwork, configuredPort);
        getManageCertificateButton().setDisable(mAdminOperationRunning);
    }

    private boolean activeCertificateMatchesInstalled(String activeFingerprint)
    {
        try
        {
            return mInstalledTlsMaterial != null &&
                activeFingerprint.equals(mInstalledTlsMaterial.leafSha256Fingerprint());
        }
        catch(Exception exception)
        {
            return false;
        }
    }

    private void refreshCertificateIdentity()
    {
        try
        {
            mCertificateIdentity = WebCertificateIdentity.discover();
        }
        catch(Exception exception)
        {
            mCertificateIdentity = fallbackCertificateIdentity();
            mLog.debug("Unable to enumerate current LAN and VPN addresses");
        }
    }

    private void updateNetworkAddresses(WebServerRuntimeState runtimeState, boolean configuredNetwork,
                                        int configuredPort)
    {
        List<WebNetworkAddressDiscovery.DiscoveredAddress> addresses = mCertificateIdentity.networkAddresses()
            .stream().filter(address -> address.address() instanceof Inet4Address).toList();
        boolean activeNetwork = runtimeState != null && runtimeState.running() && runtimeState.anyIpEnabled() &&
            runtimeState.https();
        boolean activeCertificateIsInstalled = !activeNetwork ||
            activeCertificateMatchesInstalled(runtimeState.certificateFingerprint());
        int advertisedPort = activeNetwork ? runtimeState.port() : configuredPort;
        mPreferredNetworkUrl = null;

        if(addresses.isEmpty())
        {
            getNetworkUrlLabel().setText("No usable LAN or VPN IPv4 address detected.");
            getCopyButton().setDisable(true);
            return;
        }

        StringBuilder display = new StringBuilder();

        for(WebNetworkAddressDiscovery.DiscoveredAddress address: addresses)
        {
            if(display.length() > 0)
            {
                display.append('\n');
            }

            if(activeNetwork || configuredNetwork)
            {
                String url = address.url("https", advertisedPort);

                if(mPreferredNetworkUrl == null)
                {
                    mPreferredNetworkUrl = url;
                }

                display.append(url);

                if(activeNetwork && !activeCertificateIsInstalled)
                {
                    display.append(" — active certificate differs; name coverage unknown");
                }
                else if(mInstalledTlsMaterial != null && !mInstalledTlsMaterial.coversHost(address.hostAddress()))
                {
                    display.append(" — certificate name mismatch");
                }

                if(!activeNetwork)
                {
                    display.append(" — not currently listening");
                }
            }
            else
            {
                display.append(address.hostAddress()).append(" — network access is off");
            }

            display.append(" (").append(address.interfaceDisplayName()).append(')');
        }

        getNetworkUrlLabel().setText(display.toString());
        getCopyButton().setDisable(!activeNetwork || mPreferredNetworkUrl == null);
    }

    private void selectAccessMode(boolean network)
    {
        boolean previouslyUpdating = mUpdatingControls;
        mUpdatingControls = true;
        getNetworkRadioButton().setSelected(network);
        getLocalOnlyRadioButton().setSelected(!network);
        mUpdatingControls = previouslyUpdating;
    }

    private void openLocalUrl()
    {
        if(mLocalUrl == null)
        {
            return;
        }

        try
        {
            if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            {
                throw new IllegalStateException("Desktop browser integration is unavailable");
            }

            Desktop.getDesktop().browse(URI.create(mLocalUrl));
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to open the embedded web interface", exception);
            showError("Open Web Interface", "The web address could not be opened in a browser.");
        }
    }

    private void copyPreferredNetworkUrl()
    {
        if(mPreferredNetworkUrl == null)
        {
            return;
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(mPreferredNetworkUrl);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void refreshCertificateStatus()
    {
        try
        {
            mInstalledTlsMaterial = getTlsMaterialService().validateInstalledMaterial();
            String mode = effectiveCertificateMode() == WebCertificateMode.AUTOMATIC ?
                "Automatic certificate" : "Custom certificate";
            StringBuilder status = new StringBuilder(mode).append(" — valid until ")
                .append(CERTIFICATE_DATE.format(mInstalledTlsMaterial.notAfter()));
            Optional<String> missingAddress = mCertificateIdentity.networkAddresses().stream()
                .map(WebNetworkAddressDiscovery.DiscoveredAddress::hostAddress)
                .filter(address -> !mInstalledTlsMaterial.coversHost(address)).findFirst();
            missingAddress.ifPresent(address -> status.append(" — does not cover ").append(address));

            if(mStatsWebServerService != null)
            {
                WebServerRuntimeState runtimeState = mStatsWebServerService.getRuntimeState();
                String installedFingerprint = mInstalledTlsMaterial.leafSha256Fingerprint();

                if(runtimeState.running() && runtimeState.https() &&
                    !installedFingerprint.equals(runtimeState.certificateFingerprint()))
                {
                    status.append(" — installed but not active");
                }
            }

            getTlsStatusLabel().setText(status.toString());
        }
        catch(Exception exception)
        {
            mInstalledTlsMaterial = null;
            getTlsStatusLabel().setText("No valid HTTPS certificate is installed.");
        }
    }

    private void showCertificateManager()
    {
        refreshCertificateStatus();
        ButtonType automatic = new ButtonType("Use Automatic", ButtonBar.ButtonData.OTHER);
        ButtonType importCertificate = new ButtonType("Import...", ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.INFORMATION, certificateDetails(mInstalledTlsMaterial),
            automatic, importCertificate, ButtonType.CLOSE);
        alert.setTitle("HTTPS Certificate");
        alert.setHeaderText(mInstalledTlsMaterial == null ? "No valid certificate is installed" :
            "Current certificate");
        initializeOwner(alert);
        Optional<ButtonType> choice = alert.showAndWait();

        if(choice.filter(automatic::equals).isPresent())
        {
            useAutomaticCertificate();
        }
        else if(choice.filter(importCertificate::equals).isPresent())
        {
            importCustomCertificate();
        }
    }

    private void useAutomaticCertificate()
    {
        ButtonType replace = new ButtonType("Replace", ButtonBar.ButtonData.OK_DONE);
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
            "A new self-signed certificate will be created for this computer and its current LAN/VPN IPv4 " +
                "addresses. Browsers may show a new trust warning.", replace, ButtonType.CANCEL);
        confirmation.setTitle("Use Automatic Certificate");
        confirmation.setHeaderText("Replace the installed certificate?");
        initializeOwner(confirmation);

        if(confirmation.showAndWait().filter(replace::equals).isEmpty())
        {
            return;
        }

        try
        {
            if(mStatsWebServerService != null)
            {
                StatsWebServerService.TlsActivation activation =
                    mStatsWebServerService.generateAndActivateAutomaticCertificate();
                finishCertificateActivation(activation.material(), activation.runtimeState());
            }
            else
            {
                refreshCertificateIdentity();
                TlsMaterial material = getTlsMaterialService().generateSelfSigned(mCertificateIdentity.commonName(),
                    mCertificateIdentity.subjectAlternativeNames());
                mApplicationPreference.setStatsWebServerCertificateMode(WebCertificateMode.AUTOMATIC);
                activateCertificate(material);
            }
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to generate the automatic HTTPS certificate", exception);
            showError("Automatic Certificate", "The automatic certificate could not be created.");
        }
    }

    private void importCustomCertificate()
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import HTTPS Certificate");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            "Certificate bundle or PEM certificate", "*.p12", "*.pfx", "*.pem", "*.crt", "*.cer"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        java.io.File selected = chooser.showOpenDialog(owner());

        if(selected == null)
        {
            return;
        }

        Path selectedPath = selected.toPath();
        String name = selectedPath.getFileName().toString().toLowerCase(Locale.ROOT);

        try
        {
            TlsMaterial material;

            if(name.endsWith(".p12") || name.endsWith(".pfx"))
            {
                Optional<char[]> password = requestBundlePassword();

                if(password.isEmpty())
                {
                    return;
                }

                char[] bundlePassword = password.get();

                try
                {
                    material = getTlsMaterialService().validatePkcs12(selectedPath, bundlePassword);
                }
                finally
                {
                    Arrays.fill(bundlePassword, '\u0000');
                }
            }
            else
            {
                Path privateKey = choosePrivateKey();

                if(privateKey == null)
                {
                    return;
                }

                material = getTlsMaterialService().validatePem(selectedPath, privateKey);
            }

            if(!confirmCertificateInstall(material))
            {
                return;
            }

            if(mStatsWebServerService != null)
            {
                StatsWebServerService.TlsActivation activation =
                    mStatsWebServerService.installAndActivateCustomCertificate(material);
                finishCertificateActivation(activation.material(), activation.runtimeState());
            }
            else
            {
                TlsMaterial installed = getTlsMaterialService().install(material);
                mApplicationPreference.setStatsWebServerCertificateMode(WebCertificateMode.CUSTOM);
                activateCertificate(installed);
            }
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to import the selected HTTPS certificate", exception);
            showError("Import Certificate",
                "The certificate was not imported. Select a valid certificate with its matching RSA or EC private " +
                    "key. PKCS#12 passwords are used only during import and are not stored.");
        }
    }

    private Optional<char[]> requestBundlePassword()
    {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle("PKCS#12 Password");
        dialog.setHeaderText("Enter the password for this certificate bundle");
        initializeOwner(dialog);
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Leave blank if the bundle has no password");
        dialog.getDialogPane().setContent(passwordField);
        ButtonType continueButton = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(continueButton, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == continueButton ? passwordField.getText().toCharArray() : null);

        try
        {
            return dialog.showAndWait();
        }
        finally
        {
            passwordField.clear();
        }
    }

    private Path choosePrivateKey()
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Matching HTTPS Private Key");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Private key", "*.pem", "*.key"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        java.io.File selected = chooser.showOpenDialog(owner());
        return selected == null ? null : selected.toPath();
    }

    private boolean confirmCertificateInstall(TlsMaterial material)
    {
        refreshCertificateIdentity();
        List<String> displayedNames = new ArrayList<>();
        displayedNames.add("127.0.0.1");
        displayedNames.addAll(mCertificateIdentity.networkAddresses().stream()
            .filter(address -> address.address() instanceof Inet4Address)
            .map(WebNetworkAddressDiscovery.DiscoveredAddress::hostAddress).toList());
        List<String> missingNames = displayedNames.stream().distinct()
            .filter(name -> !material.coversHost(name)).toList();
        String details = certificateDetails(material);
        String header = "Review the certificate before installation";

        if(!missingNames.isEmpty())
        {
            header = "Certificate does not match every displayed web address";
            details = "Browsers will show a certificate-name warning at: " +
                String.join(", ", missingNames) + ". You can still install it if your devices use another name " +
                "listed in the certificate.\n\n" + details;
        }

        ButtonType install = new ButtonType("Install", ButtonBar.ButtonData.OK_DONE);
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, details,
            install, ButtonType.CANCEL);
        confirmation.setTitle("Install HTTPS Certificate");
        confirmation.setHeaderText(header);
        initializeOwner(confirmation);
        return confirmation.showAndWait().filter(install::equals).isPresent();
    }

    private String certificateDetails(TlsMaterial material)
    {
        if(material == null)
        {
            return "Choose Automatic to create a local certificate, or Import to install a PKCS#12/PFX bundle or " +
                "a PEM certificate and matching private key.";
        }

        try
        {
            String names = certificateNames(material);
            return "Subject: " + material.subjectDisplayName() +
                "\nIssuer: " + material.issuerDisplayName() +
                "\nValid: " + CERTIFICATE_DATE.format(material.notBefore()) + " through " +
                CERTIFICATE_DATE.format(material.notAfter()) +
                "\nNames: " + names +
                "\nSHA-256: " + material.leafSha256Fingerprint();
        }
        catch(Exception exception)
        {
            return "The certificate is valid, but its display details could not be calculated.";
        }
    }

    private static String certificateNames(TlsMaterial material)
    {
        List<String> names = material.subjectAlternativeNames();

        if(names.isEmpty())
        {
            return "None";
        }

        int displayed = Math.min(8, names.size());
        String summary = String.join(", ", names.subList(0, displayed));
        return displayed == names.size() ? summary : summary + " (and " + (names.size() - displayed) + " more)";
    }

    private void activateCertificate(TlsMaterial material)
    {
        WebServerRuntimeState state = null;

        if(mStatsWebServerService != null && mApplicationPreference.isStatsWebServerEnabled() &&
            mApplicationPreference.isStatsWebServerAnyIpEnabled())
        {
            state = mStatsWebServerService.reloadActiveListener();
        }

        finishCertificateActivation(material, state);
    }

    private void finishCertificateActivation(TlsMaterial material, WebServerRuntimeState state)
    {
        mInstalledTlsMaterial = material;

        if(state != null && mApplicationPreference.isStatsWebServerEnabled() &&
            mApplicationPreference.isStatsWebServerAnyIpEnabled())
        {

            try
            {
                if(!state.running() || !state.https() ||
                    !material.leafSha256Fingerprint().equals(state.certificateFingerprint()))
                {
                    showError("Certificate Activation",
                        "The certificate was installed, but the HTTPS listener could not activate it. The previous " +
                            "working listener was retained where possible.");
                }
            }
            catch(Exception exception)
            {
                mLog.warn("Unable to compare active and installed HTTPS certificates", exception);
            }
        }

        refreshCertificateStatus();
        refreshRuntimeAndAddresses();
    }

    private void refreshAdminStatus()
    {
        if(mAdminOperationRunning)
        {
            return;
        }

        setAdminOperationRunning(true, "Checking the primary administrator...");
        runAdminOperation(() -> mStatsWebServerService != null ?
            mStatsWebServerService.isPrimaryAdminConfigured() :
            new WebAccessService(mDatabasePath).isPrimaryAdminConfigured(), configured -> {
            mAdminStatusKnown = true;
            mAdminConfigured = configured;
            getAdminStatusLabel().setText(configured ?
                "Configured: " + WebAccessService.PRIMARY_ADMIN_USERNAME :
                "Not configured. Administrator login is unavailable.");
            getConfigureAdminButton().setText(configured ? "Change Password..." : "Set Password...");
            updateAdminControlState();
        }, "Unable to read the primary administrator account.");
    }

    private void showAdminCredentialDialog()
    {
        if(mAdminOperationRunning || !mAdminStatusKnown)
        {
            return;
        }

        Dialog<AdminPasswordInput> dialog = new Dialog<>();
        dialog.setTitle(mAdminConfigured ? "Change Administrator Password" : "Set Administrator Password");
        dialog.setHeaderText("Primary web administrator: " + WebAccessService.PRIMARY_ADMIN_USERNAME);
        initializeOwner(dialog);
        ButtonType saveButtonType = new ButtonType(mAdminConfigured ? "Change" : "Set",
            ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(10);
        fields.setPadding(new Insets(10));
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(Pbkdf2PasswordHasher.MINIMUM_PASSWORD_CHARACTERS + "-" +
            Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS + " characters");
        PasswordField confirmField = new PasswordField();
        confirmField.setPromptText("Repeat password");
        Label validationLabel = wrappingLabel();
        fields.add(new Label("Password"), 0, 0);
        fields.add(passwordField, 1, 0);
        fields.add(new Label("Confirm"), 0, 1);
        fields.add(confirmField, 1, 1);
        fields.add(validationLabel, 0, 2, 2, 1);
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

            return Arrays.equals(password, confirmation) ? null : "Passwords do not match.";
        }
        finally
        {
            Arrays.fill(password, '\u0000');
            Arrays.fill(confirmation, '\u0000');
        }
    }

    private void provisionOrResetAdmin(AdminPasswordInput input)
    {
        setAdminOperationRunning(true, "Securing and saving the administrator password...");
        runAdminOperation(() -> {
            try
            {
                WebAccessAccount account = mStatsWebServerService != null ?
                    mStatsWebServerService.provisionOrResetPrimaryAdmin(input.password()) :
                    new WebAccessService(mDatabasePath).provisionOrResetPrimaryAdmin(input.password());
                return account.username();
            }
            finally
            {
                input.clear();
            }
        }, username -> {
            mAdminStatusKnown = true;
            mAdminConfigured = true;
            getAdminStatusLabel().setText("Configured: " + username);
            getConfigureAdminButton().setText("Change Password...");
            updateAdminControlState();
        }, "Unable to save the administrator password.");
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

        updateAdminControlState();
    }

    private void updateAdminControlState()
    {
        getConfigureAdminButton().setDisable(mAdminOperationRunning || !mAdminStatusKnown);
    }

    private WebTlsMaterialService getTlsMaterialService()
    {
        if(mStatsWebServerService != null)
        {
            return mStatsWebServerService.getTlsMaterialService();
        }

        if(mTlsMaterialService == null)
        {
            mTlsMaterialService = new WebTlsMaterialService(mDataRoot);
        }

        return mTlsMaterialService;
    }

    private WebCertificateMode effectiveCertificateMode()
    {
        if(mApplicationPreference.isStatsWebServerCertificateModeConfigured())
        {
            return mApplicationPreference.getStatsWebServerCertificateMode();
        }

        return java.nio.file.Files.exists(getTlsMaterialService().certificatePath()) ||
            java.nio.file.Files.exists(getTlsMaterialService().privateKeyPath()) ?
            WebCertificateMode.CUSTOM : WebCertificateMode.AUTOMATIC;
    }

    private static WebCertificateIdentity fallbackCertificateIdentity()
    {
        return new WebCertificateIdentity("localhost", List.of("localhost", "127.0.0.1"), List.of());
    }

    private Window owner()
    {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void initializeOwner(Dialog<?> dialog)
    {
        Window owner = owner();

        if(owner != null)
        {
            dialog.initOwner(owner);
        }
    }

    private void showError(String title, String message)
    {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        initializeOwner(alert);
        alert.showAndWait();
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
