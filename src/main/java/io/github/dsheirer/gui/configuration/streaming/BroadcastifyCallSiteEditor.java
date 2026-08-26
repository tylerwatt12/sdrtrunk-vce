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

package io.github.dsheirer.gui.configuration.streaming;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallSiteConfiguration;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import java.util.Comparator;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.HPos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;

/**
 * Editor for a Broadcastify Calls provider that is bound to one saved trunked-site channel.
 */
public class BroadcastifyCallSiteEditor extends BroadcastifyCallEditor
{
    static final String SITE_FIELD_LABEL = "Send calls only from this trunked site";
    static final String SITE_EXPLANATION = "Calls are uploaded only when an alias routes the call to this provider " +
        "and the selected trunked site observed the call. This prevents calls heard only on other sites from being " +
        "uploaded to this Broadcastify Calls node.";

    private final ObservableList<AliasListDefinition> mAliasLists = FXCollections.observableArrayList();
    private final ObservableList<Channel> mEligibleChannels = FXCollections.observableArrayList();
    private final ListChangeListener<AliasListDefinition> mAliasListChangeListener = change -> refreshFromModels();
    private final ListChangeListener<Channel> mChannelChangeListener = change -> refreshEligibleChannels();
    private ComboBox<AliasListDefinition> mAliasListComboBox;
    private ComboBox<Channel> mSiteComboBox;
    private Label mSelectionStatusLabel;
    private boolean mLoading;

    public BroadcastifyCallSiteEditor(ConfigurationManager configurationManager)
    {
        super(configurationManager);
        addSiteControls(super.getEditorPane());
        configurationManager.getAliasModel().aliasListDefinitions().addListener(mAliasListChangeListener);
        configurationManager.getChannelModel().channelList().addListener(mChannelChangeListener);
        refreshFromModels();
    }

    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.BROADCASTIFY_CALL_SITE;
    }

    @Override
    public void setItem(BroadcastifyCallConfiguration item)
    {
        if(item != null && !(item instanceof BroadcastifyCallSiteConfiguration))
        {
            throw new IllegalArgumentException("Broadcastify trunked-site editor requires a site configuration");
        }

        mLoading = true;

        try
        {
            super.setItem(item);
            boolean disabled = item == null;
            getAliasListComboBox().setDisable(disabled);
            getSiteComboBox().setDisable(true);
            getAliasListComboBox().getSelectionModel().clearSelection();
            getSiteComboBox().getSelectionModel().clearSelection();
            refreshAliasLists();

            if(item instanceof BroadcastifyCallSiteConfiguration siteConfiguration)
            {
                Optional<AliasListDefinition> aliasList = siteConfiguration.resolveAliasList(
                    getConfigurationManager().getAliasModel());

                if(aliasList.isPresent())
                {
                    getAliasListComboBox().getSelectionModel().select(aliasList.get());
                    refreshEligibleChannels(siteConfiguration.getChannelConfigurationId());
                }
                else
                {
                    mEligibleChannels.clear();
                }
            }
            else
            {
                mEligibleChannels.clear();
            }

            updateSelectionStatus();
        }
        finally
        {
            mLoading = false;
            modifiedProperty().set(false);
        }
    }

    @Override
    public void save()
    {
        if(getItem() instanceof BroadcastifyCallSiteConfiguration siteConfiguration)
        {
            AliasListDefinition aliasList = getAliasListComboBox().getSelectionModel().getSelectedItem();
            Channel channel = getSiteComboBox().getSelectionModel().getSelectedItem();

            if(aliasList != null)
            {
                siteConfiguration.setAliasListId(aliasList.getId());
                siteConfiguration.setAliasListName(aliasList.getName());
            }
            else
            {
                siteConfiguration.setAliasListId(AliasListDefinition.UNASSIGNED_ID);
                siteConfiguration.setAliasListName(null);
            }

            if(channel != null && BroadcastifyCallSiteConfiguration.isEligibleChannel(channel, aliasList))
            {
                siteConfiguration.setChannelConfigurationId(channel.getConfigurationId());
            }
            else
            {
                siteConfiguration.setChannelConfigurationId(null);
            }
        }

        super.save();
        updateSelectionStatus();
    }

    @Override
    public void dispose()
    {
        getConfigurationManager().getAliasModel().aliasListDefinitions().removeListener(mAliasListChangeListener);
        getConfigurationManager().getChannelModel().channelList().removeListener(mChannelChangeListener);
        super.dispose();
    }

    private void addSiteControls(GridPane editorPane)
    {
        int row = editorPane.getRowCount();

        Label aliasListLabel = new Label("Alias List");
        GridPane.setHalignment(aliasListLabel, HPos.RIGHT);
        GridPane.setConstraints(aliasListLabel, 0, row);
        editorPane.getChildren().add(aliasListLabel);

        GridPane.setConstraints(getAliasListComboBox(), 1, row, 4, 1);
        GridPane.setHgrow(getAliasListComboBox(), Priority.ALWAYS);
        editorPane.getChildren().add(getAliasListComboBox());

        Label siteLabel = new Label(SITE_FIELD_LABEL);
        siteLabel.setTooltip(new Tooltip(SITE_EXPLANATION));
        GridPane.setHalignment(siteLabel, HPos.RIGHT);
        GridPane.setConstraints(siteLabel, 0, ++row);
        editorPane.getChildren().add(siteLabel);

        GridPane.setConstraints(getSiteComboBox(), 1, row, 4, 1);
        GridPane.setHgrow(getSiteComboBox(), Priority.ALWAYS);
        editorPane.getChildren().add(getSiteComboBox());

        Label explanation = new Label(SITE_EXPLANATION);
        explanation.setWrapText(true);
        explanation.setMaxWidth(650);
        GridPane.setConstraints(explanation, 1, ++row, 4, 1);
        editorPane.getChildren().add(explanation);

        GridPane.setConstraints(getSelectionStatusLabel(), 1, ++row, 4, 1);
        editorPane.getChildren().add(getSelectionStatusLabel());
    }

    private ComboBox<AliasListDefinition> getAliasListComboBox()
    {
        if(mAliasListComboBox == null)
        {
            mAliasListComboBox = new ComboBox<>(mAliasLists);
            mAliasListComboBox.setDisable(true);
            mAliasListComboBox.setMaxWidth(Double.MAX_VALUE);
            mAliasListComboBox.setPromptText("Select an Alias List");
            mAliasListComboBox.setOnAction(event -> {
                if(!mLoading)
                {
                    getSiteComboBox().getSelectionModel().clearSelection();
                    refreshEligibleChannels();
                    modifiedProperty().set(true);
                }
            });
        }

        return mAliasListComboBox;
    }

    private ComboBox<Channel> getSiteComboBox()
    {
        if(mSiteComboBox == null)
        {
            mSiteComboBox = new ComboBox<>(mEligibleChannels);
            mSiteComboBox.setDisable(true);
            mSiteComboBox.setMaxWidth(Double.MAX_VALUE);
            mSiteComboBox.setPromptText("Select a saved trunked-site channel");
            mSiteComboBox.setConverter(new StringConverter<>()
            {
                @Override
                public String toString(Channel channel)
                {
                    return channel != null ? channelLabel(channel) : null;
                }

                @Override
                public Channel fromString(String string)
                {
                    return null;
                }
            });
            mSiteComboBox.setOnAction(event -> {
                if(!mLoading)
                {
                    modifiedProperty().set(true);
                    updateSelectionStatus();
                }
            });
        }

        return mSiteComboBox;
    }

    private Label getSelectionStatusLabel()
    {
        if(mSelectionStatusLabel == null)
        {
            mSelectionStatusLabel = new Label();
            mSelectionStatusLabel.setWrapText(true);
            mSelectionStatusLabel.setMaxWidth(650);
        }

        return mSelectionStatusLabel;
    }

    private void refreshFromModels()
    {
        boolean previousLoading = mLoading;
        mLoading = true;

        try
        {
            long selectedAliasListId = selectedAliasListId();
            refreshAliasLists();
            mAliasLists.stream().filter(aliasList -> aliasList.getId() == selectedAliasListId).findFirst()
                .ifPresent(aliasList -> getAliasListComboBox().getSelectionModel().select(aliasList));
            refreshEligibleChannels(selectedChannelConfigurationId());
        }
        finally
        {
            mLoading = previousLoading;
        }

        updateSelectionStatus();
    }

    private void refreshAliasLists()
    {
        mAliasLists.setAll(getConfigurationManager().getAliasModel().aliasListDefinitions().stream()
            .sorted(Comparator.comparing(AliasListDefinition::getName, String.CASE_INSENSITIVE_ORDER)).toList());
    }

    private void refreshEligibleChannels()
    {
        refreshEligibleChannels(selectedChannelConfigurationId());
    }

    private void refreshEligibleChannels(String channelConfigurationId)
    {
        boolean previousLoading = mLoading;
        mLoading = true;

        try
        {
            AliasListDefinition aliasList = getAliasListComboBox().getSelectionModel().getSelectedItem();
            mEligibleChannels.setAll(BroadcastifyCallSiteConfiguration.eligibleChannels(
                    getConfigurationManager().getChannelModel().getChannels(), aliasList).stream()
                .sorted(Comparator.comparing(BroadcastifyCallSiteEditor::channelLabel,
                    String.CASE_INSENSITIVE_ORDER)).toList());

            getSiteComboBox().setDisable(getItem() == null || aliasList == null);
            getSiteComboBox().getSelectionModel().clearSelection();

            if(channelConfigurationId != null)
            {
                mEligibleChannels.stream().filter(channel ->
                        channelConfigurationId.equals(channel.getConfigurationId())).findFirst()
                    .ifPresent(channel -> getSiteComboBox().getSelectionModel().select(channel));
            }
        }
        finally
        {
            mLoading = previousLoading;
        }

        updateSelectionStatus();
    }

    private long selectedAliasListId()
    {
        AliasListDefinition selected = getAliasListComboBox().getSelectionModel().getSelectedItem();

        if(selected != null)
        {
            return selected.getId();
        }

        return getItem() instanceof BroadcastifyCallSiteConfiguration siteConfiguration ?
            siteConfiguration.getAliasListId() : AliasListDefinition.UNASSIGNED_ID;
    }

    private String selectedChannelConfigurationId()
    {
        Channel selected = getSiteComboBox().getSelectionModel().getSelectedItem();

        if(selected != null)
        {
            return selected.getConfigurationId();
        }

        return getItem() instanceof BroadcastifyCallSiteConfiguration siteConfiguration ?
            siteConfiguration.getChannelConfigurationId() : null;
    }

    private void updateSelectionStatus()
    {
        String message = null;

        if(getItem() instanceof BroadcastifyCallSiteConfiguration siteConfiguration)
        {
            AliasListDefinition aliasList = getAliasListComboBox().getSelectionModel().getSelectedItem();
            Channel channel = getSiteComboBox().getSelectionModel().getSelectedItem();

            if(aliasList == null)
            {
                message = siteConfiguration.getAliasListId() > AliasListDefinition.UNASSIGNED_ID ?
                    "The saved Alias List is no longer available. Calls will not upload until another Alias List " +
                        "and trunked site are selected." :
                    "Select an Alias List before selecting a trunked site.";
            }
            else if(channel == null)
            {
                message = siteConfiguration.getChannelConfigurationId() != null ?
                    "The saved trunked-site channel is missing, unsupported, conventional, or no longer uses this " +
                        "Alias List. Calls will not upload until a valid site is selected." :
                    "Select the trunked site whose observations this provider can upload.";
            }
        }

        getSelectionStatusLabel().setText(message);
        getSelectionStatusLabel().setVisible(message != null);
        getSelectionStatusLabel().setManaged(message != null);
    }

    static String channelLabel(Channel channel)
    {
        return displayPart(channel != null ? channel.getSystem() : null, "No System") + " / " +
            displayPart(channel != null ? channel.getSite() : null, "No Site") + " / " +
            displayPart(channel != null ? channel.getName() : null, "No Channel");
    }

    private static String displayPart(String value, String fallback)
    {
        return value != null && !value.isBlank() ? value.strip() : fallback;
    }
}
