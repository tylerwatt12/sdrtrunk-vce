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

package io.github.dsheirer.metadata.site;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Learns stable, over-the-air announced P25 control channels into the owning playlist channel.
 */
public class SiteControlChannelLearner implements SiteMetadataListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SiteControlChannelLearner.class);
    private static final String ROLE_CURRENT_CONTROL = "current_control";
    private static final String ROLE_SECONDARY_CONTROL = "secondary_control";

    private final PlaylistManager mPlaylistManager;

    /**
     * Constructs an instance.
     */
    public SiteControlChannelLearner(PlaylistManager playlistManager)
    {
        mPlaylistManager = playlistManager;
    }

    @Override
    public void receiveSiteMetadata(SiteMetadataEvent event)
    {
        if(event == null || !event.isUseful())
        {
            return;
        }

        Channel channel = event.channel();

        if(channel == null || !channel.isStandardChannel() ||
            !(channel.getDecodeConfiguration() instanceof DecodeConfigP25 decodeConfig) ||
            !decodeConfig.getLearnAnnouncedControlChannels() ||
            !(channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency sourceConfig))
        {
            return;
        }

        Set<Long> controlFrequencies = getControlChannelFrequencies(event.snapshot());

        if(controlFrequencies.isEmpty())
        {
            return;
        }

        boolean changed = false;

        for(Long frequency: controlFrequencies)
        {
            if(frequency != null && frequency > 0 && !sourceConfig.getFrequencies().contains(frequency))
            {
                sourceConfig.addFrequency(frequency);
                changed = true;
                LOGGER.info("Learned announced P25 control channel {} Hz for channel {}", frequency, channel.getName());
            }
        }

        if(changed)
        {
            mPlaylistManager.schedulePlaylistSave();
        }
    }

    private Set<Long> getControlChannelFrequencies(P25NetworkConfigurationSnapshot snapshot)
    {
        Set<Long> frequencies = new LinkedHashSet<>();

        if(snapshot == null || snapshot.channels() == null)
        {
            return frequencies;
        }

        for(P25NetworkConfigurationSnapshot.Channel channel: snapshot.channels())
        {
            if(channel != null && isCurrentSiteControlChannel(channel.role()) &&
                channel.downlink() != null && channel.downlink() > 0)
            {
                frequencies.add(channel.downlink());
            }
        }

        return frequencies;
    }

    private boolean isCurrentSiteControlChannel(String role)
    {
        return ROLE_CURRENT_CONTROL.equals(role) || ROLE_SECONDARY_CONTROL.equals(role);
    }
}
