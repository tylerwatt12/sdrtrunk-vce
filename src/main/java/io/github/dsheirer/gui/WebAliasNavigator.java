/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.preference.PreferenceEditorType;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.StatsWebNavigationState;
import io.github.dsheirer.stats.StatsWebServerService;
import io.github.dsheirer.stats.WebServerRuntimeState;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.Optional;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One desktop boundary for opening the authenticated web Alias editor.
 */
public final class WebAliasNavigator
{
    private static final Logger mLog = LoggerFactory.getLogger(WebAliasNavigator.class);
    private final UserPreferences mUserPreferences;
    private final StatsWebServerService mStatsWebServerService;

    public WebAliasNavigator(UserPreferences userPreferences, StatsWebServerService statsWebServerService)
    {
        mUserPreferences = userPreferences;
        mStatsWebServerService = statsWebServerService;
    }

    /**
     * Opens the Alias catalog after a one-use local administrator handoff.
     */
    public void open(Window owner)
    {
        open(owner, 0, 0);
    }

    /**
     * Opens one persisted Alias after a one-use local administrator handoff.
     */
    public void open(Window owner, long aliasListId, long aliasId)
    {
        if(aliasListId < 0 || aliasId < 0 || (aliasListId == 0) != (aliasId == 0))
        {
            throw new IllegalArgumentException("Alias List and Alias IDs must both be zero or both be positive");
        }

        if(mStatsWebServerService == null || mUserPreferences == null ||
            !mUserPreferences.getApplicationPreference().isStatsWebServerEnabled())
        {
            showSettings(owner, "The web interface is disabled",
                "Alias editing has moved to the web interface. Enable the web interface in Settings first.");
            return;
        }

        StatsWebNavigationState navigation;
        try
        {
            navigation = mStatsWebServerService.getNavigationState();
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Unable to inspect the embedded web interface", exception);
            showSettings(owner, "The web interface status could not be read",
                "Open Web Interface Settings and check the server configuration.");
            return;
        }

        if(!navigation.running())
        {
            WebServerRuntimeState runtimeState = mStatsWebServerService.getRuntimeState();
            showSettings(owner, "The web interface is not running",
                "Alias editing is unavailable until the web server starts. " + runtimeState.statusMessage());
            return;
        }

        boolean exactAlias = aliasListId > 0 && aliasId > 0;
        URI aliasEditorUri = exactAlias ? navigation.aliasEditorUri(aliasListId, aliasId) : navigation.aliasEditorUri();
        URI handoffUri;
        try
        {
            handoffUri = exactAlias ?
                mStatsWebServerService.createDesktopAdministratorAliasHandoffUri(aliasListId, aliasId) :
                mStatsWebServerService.createDesktopAdministratorAliasHandoffUri();
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Unable to prepare the web Alias editor sign-in", exception);
            showSettings(owner, "The administrator sign-in could not be prepared",
                "Open Web Interface Settings and check the administrator configuration.");
            return;
        }

        if(handoffUri == null)
        {
            boolean administratorConfigured;
            try
            {
                administratorConfigured = mStatsWebServerService.isPrimaryAdminConfigured();
            }
            catch(IOException | SQLException exception)
            {
                mLog.warn("Unable to inspect the web administrator configuration", exception);
                showSettings(owner, "The administrator configuration could not be read",
                    "Open Web Interface Settings and check the administrator configuration.");
                return;
            }

            String message = administratorConfigured ? "The local administrator sign-in could not be prepared. " +
                "Try again or check Web Interface Settings." : "Set the primary administrator password in Web " +
                "Interface Settings before editing Aliases.";
            showSettings(owner, "The web Alias editor requires an administrator", message);
            return;
        }

        try
        {
            if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            {
                throw new UnsupportedOperationException("Desktop browser integration is unavailable");
            }

            Desktop.getDesktop().browse(handoffUri);
        }
        catch(IOException | SecurityException | UnsupportedOperationException exception)
        {
            mStatsWebServerService.cancelDesktopAdministratorHandoff();
            mLog.warn("Unable to open the web Alias editor", exception);
            showWarning(owner, "The web browser could not be opened",
                "Open " + aliasEditorUri + " manually and sign in as the administrator.");
        }
    }

    private static void showSettings(Window owner, String header, String message)
    {
        ButtonType settings = new ButtonType("Web Interface Settings", ButtonBar.ButtonData.OK_DONE);
        Alert alert = alert(owner, Alert.AlertType.INFORMATION, header, message, settings, ButtonType.CANCEL);
        Optional<ButtonType> selected = alert.showAndWait();
        if(selected.filter(settings::equals).isPresent())
        {
            MyEventBus.getGlobalEventBus().post(
                new ViewUserPreferenceEditorRequest(PreferenceEditorType.WEB_SERVER));
        }
    }

    private static void showWarning(Window owner, String header, String message)
    {
        alert(owner, Alert.AlertType.WARNING, header, message, ButtonType.OK).showAndWait();
    }

    private static Alert alert(Window owner, Alert.AlertType type, String header, String message,
                               ButtonType... buttons)
    {
        Alert alert = new Alert(type, message, buttons);
        alert.setTitle("Web Alias Editor");
        alert.setHeaderText(header);
        if(owner != null)
        {
            alert.initOwner(owner);
        }
        return alert;
    }
}
