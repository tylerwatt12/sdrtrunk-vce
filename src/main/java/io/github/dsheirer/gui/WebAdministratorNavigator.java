/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.preference.PreferenceEditorType;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.StatsWebNavigationState;
import io.github.dsheirer.stats.StatsWebServerService;
import io.github.dsheirer.stats.WebServerRuntimeState;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One desktop boundary for opening authenticated administrator web editors.
 */
public final class WebAdministratorNavigator
{
    private static final Logger mLog = LoggerFactory.getLogger(WebAdministratorNavigator.class);
    private final UserPreferences mUserPreferences;
    private final StatsWebServerService mStatsWebServerService;

    public WebAdministratorNavigator(UserPreferences userPreferences, StatsWebServerService statsWebServerService)
    {
        mUserPreferences = userPreferences;
        mStatsWebServerService = statsWebServerService;
    }

    /**
     * Opens the Alias catalog after a one-use local administrator handoff.
     */
    public void openAliases(Window owner)
    {
        openAlias(owner, 0, 0);
    }

    /**
     * Opens one persisted Alias after a one-use local administrator handoff.
     */
    public void openAlias(Window owner, long aliasListId, long aliasId)
    {
        if(aliasListId < 0 || aliasId < 0 || (aliasListId == 0) != (aliasId == 0))
        {
            throw new IllegalArgumentException("Alias List and Alias IDs must both be zero or both be positive");
        }

        boolean exactAlias = aliasListId > 0 && aliasId > 0;
        open(owner, "Alias editor", navigation -> exactAlias ?
                navigation.aliasEditorUri(aliasListId, aliasId) : navigation.aliasEditorUri(),
            () -> exactAlias ?
                mStatsWebServerService.createDesktopAdministratorAliasHandoffUri(aliasListId, aliasId) :
                mStatsWebServerService.createDesktopAdministratorAliasHandoffUri());
    }

    /** Opens a new site-scoped P25 bandplan override draft. */
    public void openP25BandplanOverride(Window owner, P25SiteIdentity identity)
    {
        if(identity == null)
        {
            throw new IllegalArgumentException("P25 site identity cannot be null");
        }

        open(owner, "P25 bandplan override editor", navigation -> navigation.baseUri().resolve(String.format(
                Locale.ROOT, "?view=admin&tab=p25-bandplans&createP25Override=1&wacn=%05X&system=%03X&rfss=%02X&site=%02X",
                identity.wacn(), identity.system(), identity.rfss(), identity.site())),
            () -> mStatsWebServerService.createDesktopAdministratorP25BandplanOverrideHandoffUri(identity));
    }

    private void open(Window owner, String editorName, Function<StatsWebNavigationState,URI> editorUriFactory,
                      Supplier<URI> handoffUriFactory)
    {
        if(mStatsWebServerService == null || mUserPreferences == null ||
            !mUserPreferences.getApplicationPreference().isStatsWebServerEnabled())
        {
            showSettings(owner, "The web interface is disabled",
                "The " + editorName + " requires the web interface. Enable it in Settings first.");
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
                "The " + editorName + " is unavailable until the web server starts. " +
                    runtimeState.statusMessage());
            return;
        }

        URI editorUri = editorUriFactory.apply(navigation);
        URI handoffUri;
        try
        {
            handoffUri = handoffUriFactory.get();
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Unable to prepare the web administrator editor sign-in", exception);
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
                "Interface Settings before opening the " + editorName + ".";
            showSettings(owner, "The " + editorName + " requires an administrator", message);
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
            mLog.warn("Unable to open the web administrator editor", exception);
            showWarning(owner, "The web browser could not be opened",
                "Open " + editorUri + " manually and sign in as the administrator.");
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
        alert.setTitle("Web Configuration Editor");
        alert.setHeaderText(header);
        if(owner != null)
        {
            alert.initOwner(owner);
        }
        return alert;
    }
}
