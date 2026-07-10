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
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Learns stable, over-the-air announced P25 control channels into the owning configuration channel.
 */
public class SiteControlChannelLearner implements SiteMetadataListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SiteControlChannelLearner.class);
    private static final String ROLE_CURRENT_CONTROL = "primary_control";
    private static final String ROLE_SECONDARY_CONTROL = "secondary_control";

    private final ConfigurationManager mConfigurationManager;
    private final Map<Integer,Set<Long>> mLearnedFrequenciesByChannel = new HashMap<>();

    /**
     * Constructs an instance.
     */
    public SiteControlChannelLearner(ConfigurationManager configurationManager)
    {
        mConfigurationManager = configurationManager;
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

        boolean changed = false;
        Set<Long> learned = mLearnedFrequenciesByChannel.computeIfAbsent(channel.getChannelID(),
            ignored -> new LinkedHashSet<>());

        for(Long frequency: controlFrequencies)
        {
            if(frequency != null && frequency > 0 && !sourceConfig.getFrequencies().contains(frequency))
            {
                sourceConfig.addFrequency(frequency);
                learned.add(frequency);
                changed = true;
                LOGGER.info("Learned announced P25 control channel {} Hz for channel {}", frequency, channel.getName());
            }
        }

        if(reconcileLearnedFrequencies(sourceConfig, learned, controlFrequencies))
        {
            changed = true;
        }

        if(changed)
        {
            mConfigurationManager.scheduleConfigurationSave();
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
        return ROLE_CURRENT_CONTROL.equals(role) || "current_control".equals(role) || ROLE_SECONDARY_CONTROL.equals(role);
    }

    private boolean reconcileLearnedFrequencies(SourceConfigTunerMultipleFrequency sourceConfig, Set<Long> learned,
                                                Set<Long> promoted)
    {
        if(learned.isEmpty())
        {
            return false;
        }

        Set<Long> remove = new LinkedHashSet<>();
        long preferredFrequency = sourceConfig.getPreferredFrequency();

        for(Long frequency: learned)
        {
            if(frequency != null && frequency != preferredFrequency && !promoted.contains(frequency))
            {
                remove.add(frequency);
            }
        }

        if(remove.isEmpty())
        {
            return false;
        }

        sourceConfig.setFrequencies(sourceConfig.getFrequencies().stream()
            .filter(frequency -> !remove.contains(frequency))
            .toList());
        learned.removeAll(remove);
        return true;
    }
}
