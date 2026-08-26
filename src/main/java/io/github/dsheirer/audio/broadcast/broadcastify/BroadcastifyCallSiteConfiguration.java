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

package io.github.dsheirer.audio.broadcast.broadcastify;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.beans.binding.Bindings;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Broadcastify Calls configuration that restricts delivery to calls observed by one saved trunked-site channel.
 * The Alias List durable ID is authoritative. Its name is retained as display context, while the channel
 * configuration ID identifies the selected saved channel across renames and restarts.
 */
public class BroadcastifyCallSiteConfiguration extends BroadcastifyCallConfiguration
{
    private final LongProperty mAliasListId = new SimpleLongProperty(AliasListDefinition.UNASSIGNED_ID);
    private final StringProperty mAliasListName = new SimpleStringProperty();
    private final StringProperty mChannelConfigurationId = new SimpleStringProperty();

    /**
     * Constructor for Jackson.
     */
    public BroadcastifyCallSiteConfiguration()
    {
        this(BroadcastFormat.MP3);
    }

    /**
     * Constructs an instance for the specified recording format.
     */
    public BroadcastifyCallSiteConfiguration(BroadcastFormat format)
    {
        super(format);

        //The parent owns this property, so replace its base Calls binding with the stricter site-provider contract.
        mValid.unbind();
        mValid.bind(Bindings.createBooleanBinding(() -> getSystemID() > 0 && getApiKey() != null &&
                getHost() != null && hasSiteSelection(), systemIDProperty(), apiKeyProperty(), hostProperty(),
            mAliasListId, mAliasListName, mChannelConfigurationId));
    }

    public LongProperty aliasListIdProperty()
    {
        return mAliasListId;
    }

    public long getAliasListId()
    {
        return mAliasListId.get();
    }

    public void setAliasListId(long aliasListId)
    {
        mAliasListId.set(aliasListId > AliasListDefinition.UNASSIGNED_ID ? aliasListId :
            AliasListDefinition.UNASSIGNED_ID);
    }

    public StringProperty aliasListNameProperty()
    {
        return mAliasListName;
    }

    public String getAliasListName()
    {
        return mAliasListName.get();
    }

    public void setAliasListName(String aliasListName)
    {
        mAliasListName.set(normalize(aliasListName));
    }

    public StringProperty channelConfigurationIdProperty()
    {
        return mChannelConfigurationId;
    }

    public String getChannelConfigurationId()
    {
        return mChannelConfigurationId.get();
    }

    /**
     * Sets the durable selected-channel identity. Malformed persisted values are retained as invalid text so they
     * fail closed instead of silently binding to a different channel.
     */
    public void setChannelConfigurationId(String channelConfigurationId)
    {
        String normalized = normalize(channelConfigurationId);

        if(isValidConfigurationId(normalized))
        {
            normalized = UUID.fromString(normalized).toString();
        }

        mChannelConfigurationId.set(normalized);
    }

    /**
     * Indicates whether all persisted site-selection fields are structurally usable. Live Alias List and channel
     * resolution must additionally pass before a call can be delivered.
     */
    @JsonIgnore
    public boolean hasSiteSelection()
    {
        return getAliasListId() > AliasListDefinition.UNASSIGNED_ID && getAliasListName() != null &&
            isValidConfigurationId(getChannelConfigurationId());
    }

    /**
     * Resolves the authoritative Alias List identity. The stored name is deliberately not compared so a rename of
     * the same durable Alias List remains valid.
     */
    @JsonIgnore
    public Optional<AliasListDefinition> resolveAliasList(AliasModel aliasModel)
    {
        return aliasModel != null ? Optional.ofNullable(aliasModel.getAliasListDefinition(getAliasListId())) :
            Optional.empty();
    }

    /**
     * Resolves the selected channel only when it is still a saved, supported trunked channel assigned to the
     * authoritative Alias List. A deleted, reassigned, conventional, traffic, or malformed channel fails closed.
     */
    @JsonIgnore
    public Optional<Channel> resolveChannel(AliasModel aliasModel, Collection<Channel> savedChannels)
    {
        Optional<AliasListDefinition> aliasList = resolveAliasList(aliasModel);

        if(aliasList.isEmpty() || !isValidConfigurationId(getChannelConfigurationId()))
        {
            return Optional.empty();
        }

        return eligibleChannels(savedChannels, aliasList.get()).stream()
            .filter(channel -> getChannelConfigurationId().equals(channel.getConfigurationId()))
            .findFirst();
    }

    /**
     * Tests the complete live site-selection contract used by provider-settings validation.
     */
    @JsonIgnore
    public boolean hasValidSiteSelection(AliasModel aliasModel, Collection<Channel> savedChannels)
    {
        return hasSiteSelection() && resolveChannel(aliasModel, savedChannels).isPresent();
    }

    /**
     * Returns the saved channels that can be selected for an Alias List, preserving saved-channel order.
     */
    public static List<Channel> eligibleChannels(Collection<Channel> savedChannels,
                                                 AliasListDefinition aliasList)
    {
        if(savedChannels == null || aliasList == null)
        {
            return List.of();
        }

        return savedChannels.stream().filter(channel -> isEligibleChannel(channel, aliasList)).toList();
    }

    /**
     * Provider-settings policy for a selectable trunked-site channel.
     */
    public static boolean isEligibleChannel(Channel channel, AliasListDefinition aliasList)
    {
        if(channel == null || aliasList == null || !channel.isStandardChannel() ||
            !ChannelConfigurationPolicy.isActive(channel) || channel.getAliasListName() == null ||
            !channel.getAliasListName().equalsIgnoreCase(aliasList.getName()))
        {
            return false;
        }

        DecodeConfiguration decodeConfiguration = channel.getDecodeConfiguration();

        if(decodeConfiguration == null ||
            !AliasMatchRegistry.isChannelCompatible(aliasList, decodeConfiguration.getDecoderType()))
        {
            return false;
        }

        return decodeConfiguration instanceof DecodeConfigP25 ||
            decodeConfiguration instanceof DecodeConfigDMR dmr && dmr.isTrunked() ||
            decodeConfiguration instanceof DecodeConfigNXDN nxdn && nxdn.isTrunked();
    }

    private static boolean isValidConfigurationId(String configurationId)
    {
        if(configurationId == null)
        {
            return false;
        }

        try
        {
            UUID.fromString(configurationId);
            return true;
        }
        catch(IllegalArgumentException e)
        {
            return false;
        }
    }

    private static String normalize(String value)
    {
        return value != null && !value.isBlank() ? value.strip() : null;
    }

    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.BROADCASTIFY_CALL_SITE;
    }

    @Override
    public BroadcastConfiguration copyOf()
    {
        BroadcastifyCallSiteConfiguration copy = new BroadcastifyCallSiteConfiguration();
        copy.setSystemID(getSystemID());
        copy.setAliasListId(getAliasListId());
        copy.setAliasListName(getAliasListName());
        copy.setChannelConfigurationId(getChannelConfigurationId());
        return copy;
    }
}
