/*
 * *****************************************************************************
 *  Copyright (C) 2014-2020 Dennis Sheirer
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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.preference.PreferenceEditorType;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.StatsWebNavigationState;
import io.github.dsheirer.stats.StatsWebServerService;
import java.awt.Desktop;
import java.net.URI;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Primary alias editor with tabbed panes for view-by alias editing support
 */
public class AliasEditor extends TabPane
{
    private static final String RETIREMENT_MESSAGE = "The Alias editor in the Java desktop interface will be retired " +
        "in a future release. Please try the web Alias editor and report any missing workflows before the transition.";
    private ConfigurationManager mConfigurationManager;
    private UserPreferences mUserPreferences;
    private StatsWebServerService mStatsWebServerService;
    private AliasConfigurationEditor mAliasConfigurationEditor;
    private AliasViewByIdentifierEditor mAliasViewByIdentifierEditor;
    private Tab mAliasConfigurationTab;
    private Tab mAliasIdentifierTab;
    private Tab mAliasRecordingTab;
    private boolean mRetirementNoticeShown;

    /**
     * Constructs an instance
     * @param configurationManager for alias model access
     * @param userPreferences for settings
     */
    public AliasEditor(ConfigurationManager configurationManager, UserPreferences userPreferences,
                       StatsWebServerService statsWebServerService)
    {
        mConfigurationManager = configurationManager;
        mUserPreferences = userPreferences;
        mStatsWebServerService = statsWebServerService;

        setPadding(new Insets(4,0,0,0));
        setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab viewByTab = new Tab("View By:");
        viewByTab.setDisable(true);
        getTabs().addAll(viewByTab, getAliasConfigurationTab(), getAliasIdentifierTab(), getAliasRecordingTab());
    }

    /**
     * Updates the embedded web-interface service used by the retirement notice.
     */
    public void setStatsWebServerService(StatsWebServerService statsWebServerService)
    {
        mStatsWebServerService = statsWebServerService;
    }

    /**
     * Shows the Java Alias-editor retirement notice at most once for this application launch.
     */
    public void showRetirementNotice()
    {
        if(!mRetirementNoticeShown)
        {
            mRetirementNoticeShown = true;
            Platform.runLater(this::showRetirementNoticeNow);
        }
    }

    private void showRetirementNoticeNow()
    {
        boolean webAliasEditorAvailable = isWebAliasEditorAvailable();
        ButtonType webButton = new ButtonType(webAliasEditorAvailable ? "Open Web Alias Editor" :
            "Web Interface Settings", ButtonBar.ButtonData.OK_DONE);
        ButtonType continueButton = new ButtonType("Continue in Java", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.INFORMATION, RETIREMENT_MESSAGE, continueButton, webButton);
        alert.setTitle("Java Alias Editor Retirement");
        alert.setHeaderText("Try the web Alias editor");

        if(getScene() != null && getScene().getWindow() != null)
        {
            alert.initOwner(getScene().getWindow());
        }

        Optional<ButtonType> result = alert.showAndWait();
        if(result.isPresent() && result.get() == webButton)
        {
            if(webAliasEditorAvailable)
            {
                openWebAliasEditor();
            }
            else
            {
                openWebInterfaceSettings();
            }
        }
    }

    private boolean isWebAliasEditorAvailable()
    {
        return mStatsWebServerService != null && mStatsWebServerService.getNavigationState().running();
    }

    private void openWebAliasEditor()
    {
        StatsWebNavigationState navigation = mStatsWebServerService != null ?
            mStatsWebServerService.getNavigationState() : null;

        if(navigation == null || !navigation.running())
        {
            openWebInterfaceSettings();
            return;
        }

        URI aliasEditorUri = navigation.aliasEditorUri();

        try
        {
            if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            {
                throw new IllegalStateException("Desktop browser integration is unavailable");
            }

            Desktop.getDesktop().browse(aliasEditorUri);
        }
        catch(Exception exception)
        {
            Alert alert = new Alert(Alert.AlertType.WARNING,
                "Unable to open the web browser. Open " + aliasEditorUri + " manually.", ButtonType.OK);
            alert.setTitle("Web Alias Editor");
            alert.setHeaderText("The web browser could not be opened");
            if(getScene() != null && getScene().getWindow() != null)
            {
                alert.initOwner(getScene().getWindow());
            }
            alert.showAndWait();
        }
    }

    private void openWebInterfaceSettings()
    {
        MyEventBus.getGlobalEventBus().post(new ViewUserPreferenceEditorRequest(PreferenceEditorType.WEB_SERVER));
    }

    /**
     * Processes the alias view request.
     *
     * Note: this method must be invoked on the JavaFX platform thread
     *
     * @param aliasTabRequest to process
     */
    public void process(AliasTabRequest aliasTabRequest)
    {
        if(aliasTabRequest instanceof ViewAliasRequest)
        {
            Alias alias = ((ViewAliasRequest)aliasTabRequest).getAlias();

            if(alias != null)
            {
                getSelectionModel().select(getAliasConfigurationTab());
                getAliasConfigurationEditor().show(alias);
            }
        }
        else if(aliasTabRequest instanceof ViewAliasIdentifierRequest)
        {
            AliasID aliasID = ((ViewAliasIdentifierRequest)aliasTabRequest).getAliasId();

            if(aliasID != null)
            {
                getSelectionModel().select(getAliasIdentifierTab());
                getAliasViewByIdentifierEditor().show(aliasID);
            }
        }
    }

    private Tab getAliasConfigurationTab()
    {
        if(mAliasConfigurationTab == null)
        {
            mAliasConfigurationTab = new Tab("Alias");
            mAliasConfigurationTab.setContent(getAliasConfigurationEditor());
        }

        return mAliasConfigurationTab;
    }

    private AliasConfigurationEditor getAliasConfigurationEditor()
    {
        if(mAliasConfigurationEditor == null)
        {
            mAliasConfigurationEditor = new AliasConfigurationEditor(mConfigurationManager, mUserPreferences);
        }

        return mAliasConfigurationEditor;
    }

    private Tab getAliasIdentifierTab()
    {
        if(mAliasIdentifierTab == null)
        {
            mAliasIdentifierTab = new Tab("Browse by Identifier");
            mAliasIdentifierTab.setContent(getAliasViewByIdentifierEditor());
        }

        return mAliasIdentifierTab;
    }

    private AliasViewByIdentifierEditor getAliasViewByIdentifierEditor()
    {
        if(mAliasViewByIdentifierEditor == null)
        {
            mAliasViewByIdentifierEditor = new AliasViewByIdentifierEditor(mConfigurationManager, getAliasIdentifierTab().selectedProperty());
        }

        return mAliasViewByIdentifierEditor;
    }

    private Tab getAliasRecordingTab()
    {
        if(mAliasRecordingTab == null)
        {
            mAliasRecordingTab = new Tab("Record");
            mAliasRecordingTab.setContent(new AliasViewByRecordingEditor(mConfigurationManager));
        }

        return mAliasRecordingTab;
    }
}
