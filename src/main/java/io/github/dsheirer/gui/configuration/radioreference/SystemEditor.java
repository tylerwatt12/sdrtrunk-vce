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

package io.github.dsheirer.gui.configuration.radioreference;

import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.rrapi.RadioReferenceException;
import io.github.dsheirer.rrapi.type.CountyInfo;
import io.github.dsheirer.rrapi.type.Flavor;
import io.github.dsheirer.rrapi.type.Site;
import io.github.dsheirer.rrapi.type.System;
import io.github.dsheirer.rrapi.type.SystemInformation;
import io.github.dsheirer.rrapi.type.Tag;
import io.github.dsheirer.rrapi.type.Talkgroup;
import io.github.dsheirer.rrapi.type.TalkgroupCategory;
import io.github.dsheirer.rrapi.type.Type;
import io.github.dsheirer.rrapi.type.Voice;
import io.github.dsheirer.service.radioreference.RadioReference;
import io.github.dsheirer.util.ThreadPool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Radio Reference editor for trunked radio systems
 */
public class SystemEditor extends VBox
{
    private static final Logger mLog = LoggerFactory.getLogger(SystemEditor.class);
    private static final Comparator<System> SYSTEM_ORDER =
        Comparator.comparing(System::getLastUpdated, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(System::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparingInt(System::getSystemId);

    private UserPreferences mUserPreferences;
    private RadioReference mRadioReference;
    private ConfigurationManager mConfigurationManager;
    private Level mLevel;
    private ComboBox<System> mSystemComboBox;
    private IntegerProperty mSystemCountProperty = new SimpleIntegerProperty();
    private TabPane mTabPane;
    private Tab mSystemTab;
    private Tab mTalkgroupTab;
    private SystemSiteSelectionEditor mSystemSiteSelectionEditor;
    private SystemTalkgroupSelectionEditor mSystemTalkgroupSelectionEditor;
    private RadioReferenceDecoder mRadioReferenceDecoder;
    private volatile int mSystemRequestSequence;
    private int mAlertedRequestSequence = -1;

    /**
     * Constructs an instance
     * @param userPreferences for preferences
     * @param radioReference to access radio reference
     * @param configurationManager
     * @param level STATE or COUNTY
     */
    public SystemEditor(UserPreferences userPreferences, RadioReference radioReference,
                        ConfigurationManager configurationManager, Level level)
    {
        mUserPreferences = userPreferences;
        mRadioReference = radioReference;
        mConfigurationManager = configurationManager;
        mLevel = level;
        mSystemCountProperty.bind(Bindings.size(getSystemComboBox().getItems()));

        setPadding(new Insets(20,10,10,10));
        setSpacing(10);
        HBox systemBox = new HBox();
        HBox.setHgrow(getSystemComboBox(), Priority.ALWAYS);
        systemBox.setAlignment(Pos.CENTER_LEFT);
        systemBox.setSpacing(5);
        systemBox.setMaxWidth(Double.MAX_VALUE);
        systemBox.getChildren().addAll(new Label("System"), getSystemComboBox());
        VBox.setVgrow(getTabPane(), Priority.ALWAYS);
        getChildren().addAll(systemBox, getTabPane());
    }

    /**
     * Observable count of systems in the editor
     */
    public IntegerProperty systemCountProperty()
    {
        return mSystemCountProperty;
    }

    public void clear()
    {
        mSystemRequestSequence++;
        getSystemComboBox().getItems().clear();
        getSystemSiteSelectionEditor().clear();
        getSystemTalkgroupSelectionEditor().clear();
    }

    /**
     * Sets the list of displayed systems and clears out any existing systems.  Auto-selects a system if the user
     * has selected that system before.
     *
     * Note: this should only be invoked on the FX application thread
     *
     * @param systems to display
     */
    public void setSystems(List<System> systems)
    {
        clear();
        List<System> sortedSystems = sortedSystems(systems);

        if(!sortedSystems.isEmpty())
        {
            getSystemComboBox().getItems().addAll(sortedSystems);

            int preferredSystemId = mUserPreferences.getRadioReferencePreference().getPreferredSystemId(mLevel);

            for(System system: getSystemComboBox().getItems())
            {
                if(system.getSystemId() == preferredSystemId)
                {
                    getSystemComboBox().getSelectionModel().select(system);
                    return;
                }
            }
        }
    }

    /**
     * Orders systems by the RadioReference update date so that recently maintained systems are easiest to find.
     */
    static List<System> sortedSystems(List<System> systems)
    {
        return (systems != null ? systems : List.<System>of()).stream()
            .filter(Objects::nonNull)
            .sorted(SYSTEM_ORDER)
            .toList();
    }

    private TabPane getTabPane()
    {
        if(mTabPane == null)
        {
            mTabPane = new TabPane();
            mTabPane.setMaxHeight(Double.MAX_VALUE);
            mTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            mTabPane.getTabs().addAll(getSystemTab(), getTalkgroupTab());
        }

        return mTabPane;
    }

    private SystemSiteSelectionEditor getSystemSiteSelectionEditor()
    {
        if(mSystemSiteSelectionEditor == null)
        {
            mSystemSiteSelectionEditor = new SystemSiteSelectionEditor(mUserPreferences, mConfigurationManager);
        }

        return mSystemSiteSelectionEditor;
    }

    private Tab getSystemTab()
    {
        if(mSystemTab == null)
        {
            mSystemTab = new Tab("System View");
            mSystemTab.setContent(getSystemSiteSelectionEditor());
        }

        return mSystemTab;
    }

    private SystemTalkgroupSelectionEditor getSystemTalkgroupSelectionEditor()
    {
        if(mSystemTalkgroupSelectionEditor == null)
        {
            mSystemTalkgroupSelectionEditor = new SystemTalkgroupSelectionEditor(mConfigurationManager);
        }

        return mSystemTalkgroupSelectionEditor;
    }

    private Tab getTalkgroupTab()
    {
        if(mTalkgroupTab == null)
        {
            mTalkgroupTab = new Tab("Talkgroup View");
            mTalkgroupTab.setContent(getSystemTalkgroupSelectionEditor());
        }

        return mTalkgroupTab;
    }

    private ComboBox<System> getSystemComboBox()
    {
        if(mSystemComboBox == null)
        {
            mSystemComboBox = new ComboBox<>();
            mSystemComboBox.setMaxWidth(Double.MAX_VALUE);
            mSystemComboBox.setCellFactory(param -> new SystemListCell());
            mSystemComboBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> setSystem(newValue));
        }

        return mSystemComboBox;
    }

    /**
     * Sets the system to be displayed in the editor and updates the system and talkgroup view editors
     */
    private void setSystem(System system)
    {
        if(system != null)
        {
            int requestSequence = ++mSystemRequestSequence;
            getSystemSiteSelectionEditor().clearAndSetLoading();
            getSystemTalkgroupSelectionEditor().clearAndSetLoading();

            mUserPreferences.getRadioReferencePreference().setPreferredSystemId(system.getSystemId(), mLevel);

            //Retrieve the radio reference data on a separate thread and then load the editors on the FX thread
            ThreadPool.CACHED.execute(() -> {
                try
                {
                    if(mRadioReferenceDecoder == null)
                    {
                        initRadioReferenceDecoder();
                    }

                    //The two primary panes are independent.  A failure or slow optional enrichment in one pane must
                    //not keep useful data out of the other.
                    ThreadPool.CACHED.execute(() -> loadSites(system, requestSequence));
                    ThreadPool.CACHED.execute(() -> loadTalkgroups(system, requestSequence));
                }
                catch(Exception t)
                {
                    mLog.error("Error initializing RadioReference system metadata for system ID {}",
                        system.getSystemId(), t);
                    Platform.runLater(() -> {
                        if(isCurrentSystemRequest(system, requestSequence))
                        {
                            getSystemTalkgroupSelectionEditor().setLoadFailed();
                            getSystemSiteSelectionEditor().setLoadFailed();
                            showUnavailableAlert(requestSequence);
                        }
                    });
                }
            });
        }
        else
        {
            mSystemRequestSequence++;
            getSystemSiteSelectionEditor().clear();
            getSystemTalkgroupSelectionEditor().clear();
        }
    }

    private void loadSites(System system, int requestSequence)
    {
        try
        {
            List<Site> sites = mRadioReference.getService().getSites(system.getSystemId());
            List<EnrichedSite> displaySites = enrich(sites, Map.of());
            SystemInformation systemInformation = null;

            try
            {
                systemInformation = mRadioReference.getService().getSystemInformation(system.getSystemId());
            }
            catch(Exception t)
            {
                //System information supplements site import.  Sites can still be browsed when it is unavailable.
                mLog.warn("RadioReference system information enrichment failed for system ID {}",
                    system.getSystemId(), t);
            }

            SystemInformation finalSystemInformation = systemInformation;
            Platform.runLater(() -> {
                if(isCurrentSystemRequest(system, requestSequence))
                {
                    getSystemSiteSelectionEditor().setSystem(system, displaySites, mRadioReferenceDecoder,
                        finalSystemInformation);
                }
            });

            enrichCountyNames(system, sites, requestSequence);
        }
        catch(Exception t)
        {
            mLog.error("Error retrieving RadioReference sites for system ID {}", system.getSystemId(), t);
            Platform.runLater(() -> {
                if(isCurrentSystemRequest(system, requestSequence))
                {
                    getSystemSiteSelectionEditor().setLoadFailed();
                    showUnavailableAlert(requestSequence);
                }
            });
        }
    }

    private void loadTalkgroups(System system, int requestSequence)
    {
        try
        {
            List<Talkgroup> talkgroups = mRadioReference.getService().getTalkgroups(system.getSystemId());
            Platform.runLater(() -> {
                if(isCurrentSystemRequest(system, requestSequence))
                {
                    getSystemTalkgroupSelectionEditor().setSystem(system, talkgroups, List.of(),
                        mRadioReferenceDecoder);
                }
            });

            //Categories are only labels and filters.  Fetch them after the talkgroups are available so a slow or
            //failed category endpoint cannot delay or erase the talkgroup table.
            ThreadPool.CACHED.execute(() -> loadTalkgroupCategories(system, requestSequence));
        }
        catch(Exception t)
        {
            mLog.error("Error retrieving RadioReference talkgroups for system ID {}", system.getSystemId(), t);
            Platform.runLater(() -> {
                if(isCurrentSystemRequest(system, requestSequence))
                {
                    getSystemTalkgroupSelectionEditor().setLoadFailed();
                    showUnavailableAlert(requestSequence);
                }
            });
        }
    }

    private void loadTalkgroupCategories(System system, int requestSequence)
    {
        try
        {
            List<TalkgroupCategory> categories =
                mRadioReference.getService().getTalkgroupCategories(system.getSystemId());
            Platform.runLater(() -> {
                if(isCurrentSystemRequest(system, requestSequence))
                {
                    getSystemTalkgroupSelectionEditor().updateCategories(categories);
                }
            });
        }
        catch(Exception t)
        {
            mLog.warn("RadioReference category enrichment failed for system ID {}; talkgroups remain available",
                system.getSystemId(), t);
        }
    }

    /**
     * Resolves distinct county names on four bounded workers.  The initial site table is already visible while this
     * optional work runs, and any individual county failure leaves only that county name blank.
     */
    private void enrichCountyNames(System system, List<Site> sites, int requestSequence)
    {
        Set<Integer> distinctCountyIds = new LinkedHashSet<>();

        for(Site site: sites)
        {
            int countyId = site.getCountyId();

            //Temporary sites whose location is unknown can use this undocumented sentinel value.
            if(countyId > 0 && countyId != 99999)
            {
                distinctCountyIds.add(countyId);
            }
        }

        if(distinctCountyIds.isEmpty())
        {
            return;
        }

        List<Integer> countyIds = new ArrayList<>(distinctCountyIds);
        Map<Integer,CountyInfo> counties = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger failedCount = new AtomicInteger();
        int workerCount = Math.min(4, countyIds.size());
        List<CompletableFuture<Void>> workers = new ArrayList<>(workerCount);

        for(int worker = 0; worker < workerCount; worker++)
        {
            int firstIndex = worker;
            workers.add(CompletableFuture.runAsync(() -> {
                for(int index = firstIndex; index < countyIds.size(); index += workerCount)
                {
                    if(requestSequence != mSystemRequestSequence)
                    {
                        return;
                    }

                    int countyId = countyIds.get(index);

                    try
                    {
                        CountyInfo countyInfo = mRadioReference.getService().getCountyInfo(countyId);

                        if(countyInfo != null)
                        {
                            counties.put(countyId, countyInfo);
                        }
                    }
                    catch(Exception t)
                    {
                        failedCount.incrementAndGet();
                        mLog.debug("Optional RadioReference county enrichment failed for county ID {}", countyId, t);
                    }
                }
            }, ThreadPool.CACHED));
        }

        CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            if(failedCount.get() > 0)
            {
                mLog.warn("{} of {} optional RadioReference county lookups failed for system ID {}",
                    failedCount.get(), countyIds.size(), system.getSystemId());
            }

            List<EnrichedSite> enrichedSites = enrich(sites, counties);
            Platform.runLater(() -> {
                if(isCurrentSystemRequest(system, requestSequence))
                {
                    getSystemSiteSelectionEditor().updateSites(enrichedSites);
                }
            });
        });
    }

    private static List<EnrichedSite> enrich(List<Site> sites, Map<Integer,CountyInfo> counties)
    {
        List<EnrichedSite> enrichedSites = new ArrayList<>(sites.size());

        for(Site site: sites)
        {
            enrichedSites.add(new EnrichedSite(site, counties.get(site.getCountyId())));
        }

        return enrichedSites;
    }

    private void showUnavailableAlert(int requestSequence)
    {
        if(mAlertedRequestSequence != requestSequence)
        {
            mAlertedRequestSequence = requestSequence;
            new RadioReferenceUnavailableAlert(getSystemComboBox()).showAndWait();
        }
    }

    private boolean isCurrentSystemRequest(System system, int requestSequence)
    {
        System selectedSystem = getSystemComboBox().getSelectionModel().getSelectedItem();
        return requestSequence == mSystemRequestSequence && system != null && selectedSystem != null &&
            selectedSystem.getSystemId() == system.getSystemId();
    }

    /**
     * Initializes the Radio Reference Decoder
     * @throws RadioReferenceException
     */
    private void initRadioReferenceDecoder() throws RadioReferenceException
    {
        Map<Integer, Type> typeMap = mRadioReference.getService().getTypesMap();
        Map<Integer, Flavor> flavorMap = mRadioReference.getService().getFlavorsMap();
        Map<Integer, Voice> voiceMap = mRadioReference.getService().getVoicesMap();
        Map<Integer, Tag> tagMap = mRadioReference.getService().getTagsMap();
        mRadioReferenceDecoder = new RadioReferenceDecoder(mUserPreferences, typeMap, flavorMap,
                voiceMap, tagMap);
    }

    public class SystemListCell extends ListCell<System>
    {
        private HBox mHBox;
        private Label mName;
        private Label mProtocol;

        public SystemListCell()
        {
            mHBox = new HBox();
            mHBox.setSpacing(15);
            mHBox.setPadding(new Insets(0,15,0,0));
            mHBox.setMaxWidth(Double.MAX_VALUE);
            mName = new Label();
            mName.setMaxWidth(Double.MAX_VALUE);
            mName.setAlignment(Pos.CENTER_LEFT);
            mProtocol = new Label();
            mProtocol.setMaxWidth(Double.MAX_VALUE);
            mProtocol.setAlignment(Pos.CENTER_RIGHT);
            mProtocol.setContentDisplay(ContentDisplay.RIGHT);
            HBox.setHgrow(mName, Priority.ALWAYS);
            HBox.setHgrow(mProtocol, Priority.ALWAYS);
            mHBox.getChildren().addAll(mName, mProtocol);
        }

        @Override
        protected void updateItem(System item, boolean empty)
        {
            super.updateItem(item, empty);

            setText(null);

            if(empty || item == null)
            {
                setGraphic(null);
                mName.setText(null);
                mProtocol.setText(null);
            }
            else
            {
                mName.setText(item.getName());
                mProtocol.setText(getType(item));
                IconNode iconNode;

                if(isSupported(item))
                {
                    iconNode = new IconNode(FontAwesome.CHECK);
                    iconNode.setFill(Color.GREEN);
                }
                else
                {
                    iconNode = new IconNode(FontAwesome.BAN);
                    iconNode.setFill(Color.RED);
                }

                mProtocol.setGraphic(iconNode);
                setGraphic(mHBox);
            }
        }

        private boolean isSupported(System system)
        {
            return mRadioReferenceDecoder != null && mRadioReferenceDecoder.hasSupportedProtocol(system);
        }

        private String getType(System system)
        {
            if(mRadioReferenceDecoder == null)
            {
                try
                {
                    initRadioReferenceDecoder();
                }
                catch(Exception e)
                {
                    mLog.error("Error retrieving system information", e);
                }
            }

            if(mRadioReferenceDecoder != null)
            {
                Type type = mRadioReferenceDecoder.getType(system);
                Flavor flavor = mRadioReferenceDecoder.getFlavor(system);

                if(type != null)
                {
                    if(flavor != null)
                    {
                        return type.getName() + " " + flavor.getName();
                    }
                    else
                    {
                        return type.getName();
                    }
                }
            }

            return "Unknown";
        }
    }

}
