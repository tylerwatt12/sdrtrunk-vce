/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.gui.configuration;

import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.icon.ViewIconManagerRequest;
import io.github.dsheirer.gui.configuration.alias.AliasEditor;
import io.github.dsheirer.gui.configuration.alias.AliasTabRequest;
import io.github.dsheirer.gui.configuration.channel.ChannelEditor;
import io.github.dsheirer.gui.configuration.channel.ChannelTabRequest;
import io.github.dsheirer.gui.configuration.radioreference.RadioReferenceEditor;
import io.github.dsheirer.gui.configuration.streaming.StreamingEditor;
import io.github.dsheirer.gui.preference.PreferenceEditorType;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.stats.StatsWebServerService;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * JavaFX channels, aliases, streaming, and radioreference.com import editor.
 */
public class ConfigurationEditor extends BorderPane
{
    private static final Logger mLog = LoggerFactory.getLogger(ConfigurationEditor.class);

    private ConfigurationManager mConfigurationManager;
    private TunerManager mTunerManager;
    private UserPreferences mUserPreferences;
    private StatsWebServerService mStatsWebServerService;
    private MenuBar mMenuBar;
    private TabPane mTabPane;
    private Tab mChannelsTab;
    private Tab mAliasesTab;
    private Tab mRadioReferenceTab;
    private Tab mStreamingTab;
    private AliasEditor mAliasEditor;
    private ChannelEditor mChannelEditor;

    /**
     * Constructs an instance
     * @param configurationManager for alias and channel models
     * @param tunerManager for tuners
     * @param userPreferences for settings
     */
    public ConfigurationEditor(ConfigurationManager configurationManager, TunerManager tunerManager,
                               UserPreferences userPreferences)
    {
        this(configurationManager, tunerManager, userPreferences, null);
    }

    /**
     * Constructs an instance with access to the embedded web interface.
     * @param configurationManager for alias and channel models
     * @param tunerManager for tuners
     * @param userPreferences for settings
     * @param statsWebServerService for web-interface navigation, or null in standalone editor testing
     */
    public ConfigurationEditor(ConfigurationManager configurationManager, TunerManager tunerManager,
                               UserPreferences userPreferences, StatsWebServerService statsWebServerService)
    {
        mConfigurationManager = configurationManager;
        mTunerManager = tunerManager;
        mUserPreferences = userPreferences;
        mStatsWebServerService = statsWebServerService;

        //Throw a new runnable back onto the FX thread to lazy load the editor content after the editor has been
        //constructed and shown.
        Platform.runLater(() -> {
            setTop(getMenuBar());
            setCenter(getTabPane());
        });
    }

    /**
     * Connects the editor to the embedded web interface when it becomes available after construction.
     */
    public void setStatsWebServerService(StatsWebServerService statsWebServerService)
    {
        mStatsWebServerService = statsWebServerService;

        if(mAliasEditor != null)
        {
            mAliasEditor.setStatsWebServerService(statsWebServerService);
        }
    }

    /**
     * Process requests for sub-editor actions like view an alias or view a channel.
     *
     * Note: this method must be invoked on the JavaFX platform thread
     * @param request to process
     */
    public void process(ConfigurationEditorRequest request)
    {
        switch(request.getTabName())
        {
            case ALIAS:
                if(request instanceof AliasTabRequest)
                {
                    getTabPane().getSelectionModel().select(getAliasesTab());
                    getAliasEditor().process((AliasTabRequest)request);
                }
                break;
            case CHANNEL:
                if(request instanceof ChannelTabRequest)
                {
                    getTabPane().getSelectionModel().select(getChannelsTab());
                    getChannelEditor().process((ChannelTabRequest)request);
                }
                break;
            case CONFIGURATION:
                getTabPane().getSelectionModel().select(getChannelsTab());
                break;
            default:
                mLog.warn("Unrecognized configuration editor request: " + request.getClass());
                break;
        }
    }

    private MenuBar getMenuBar()
    {
        if(mMenuBar == null)
        {
            mMenuBar = new MenuBar();

            //File Menu
            Menu fileMenu = new Menu("_File");
            fileMenu.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.ALT_ANY));

            MenuItem closeItem = new MenuItem("_Close");
            closeItem.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.ALT_ANY));
            closeItem.setOnAction(event -> getMenuBar().getParent().getScene().getWindow().hide());
            fileMenu.getItems().add(closeItem);
            mMenuBar.getMenus().add(fileMenu);

            Menu viewMenu = new Menu("_View");
            viewMenu.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.ALT_ANY));

            MenuItem iconManagerItem = new MenuItem("_Icon Manager");
            iconManagerItem.setAccelerator(new KeyCodeCombination(KeyCode.I, KeyCombination.ALT_ANY));
            iconManagerItem.setOnAction(event -> MyEventBus.getGlobalEventBus().post(new ViewIconManagerRequest()));
            viewMenu.getItems().add(iconManagerItem);

            MenuItem userPreferenceItem = new MenuItem("_User Preferences");
            userPreferenceItem.setAccelerator(new KeyCodeCombination(KeyCode.U, KeyCombination.ALT_ANY));
            userPreferenceItem.setOnAction(event -> MyEventBus.getGlobalEventBus()
                .post(new ViewUserPreferenceEditorRequest(PreferenceEditorType.TALKGROUP_FORMAT)));
            viewMenu.getItems().add(userPreferenceItem);

            mMenuBar.getMenus().add(viewMenu);

            Menu screenShot = new Menu("_Screenshot");
            IconNode cameraNode = new IconNode(FontAwesome.CAMERA);
            cameraNode.setFill(Color.DARKGRAY);
            screenShot.setGraphic(cameraNode);
            MenuItem menuItem = new MenuItem();
            screenShot.getItems().add(menuItem);
            screenShot.setOnShowing(event -> screenShot.hide());
            screenShot.setOnShown(event -> menuItem.fire());
            menuItem.setOnAction(event -> {
                WritableImage image = getMenuBar().getScene().snapshot(null);
                final BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);
                String filename = TimeStamp.getTimeStamp("_") + "_screen_capture.png";
                final Path captureFile = mUserPreferences.getDirectoryPreference().getDirectoryScreenCapture().resolve(filename);

                ThreadPool.CACHED.submit(() -> {
                    try
                    {
                        ImageIO.write(bufferedImage, "png", captureFile.toFile());
                    }
                    catch(IOException e)
                    {
                        mLog.error("Couldn't write screen capture to file [" + captureFile.toString() + "]", e);
                    }
                });
            });
            mMenuBar.getMenus().add(screenShot);

        }

        return mMenuBar;
    }

    private TabPane getTabPane()
    {
        if(mTabPane == null)
        {
            mTabPane = new TabPane();
            mTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            mTabPane.getTabs().addAll(getChannelsTab(), getAliasesTab(), getStreamingTab(),
                getRadioReferenceTab());
        }

        return mTabPane;
    }

    private Tab getAliasesTab()
    {
        if(mAliasesTab == null)
        {
            mAliasesTab = new Tab("Aliases");
            mAliasesTab.setContent(getAliasEditor());
            mAliasesTab.selectedProperty().addListener((observable, oldValue, selected) -> {
                if(selected)
                {
                    getAliasEditor().showRetirementNotice();
                }
            });
        }

        return mAliasesTab;
    }

    private AliasEditor getAliasEditor()
    {
        if(mAliasEditor == null)
        {
            mAliasEditor = new AliasEditor(mConfigurationManager, mUserPreferences, mStatsWebServerService);
        }

        return mAliasEditor;
    }

    private Tab getChannelsTab()
    {
        if(mChannelsTab == null)
        {
            mChannelsTab = new Tab("Channels");
            mChannelsTab.setContent(getChannelEditor());
        }

        return mChannelsTab;
    }

    private ChannelEditor getChannelEditor()
    {
        if(mChannelEditor == null)
        {
            mChannelEditor = new ChannelEditor(mConfigurationManager, mTunerManager, mUserPreferences);
        }

        return mChannelEditor;
    }

    private Tab getRadioReferenceTab()
    {
        if(mRadioReferenceTab == null)
        {
            mRadioReferenceTab = new Tab("Radio Reference");
            mRadioReferenceTab.setContent(new RadioReferenceEditor(mUserPreferences, mConfigurationManager));
        }

        return mRadioReferenceTab;
    }

    private Tab getStreamingTab()
    {
        if(mStreamingTab == null)
        {
            mStreamingTab = new Tab("Streaming");
            mStreamingTab.setContent(new StreamingEditor(mConfigurationManager));
        }

        return mStreamingTab;
    }
}
