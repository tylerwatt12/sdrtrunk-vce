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

import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds a P25 trunked channel to its verified site identity and optionally maintains announced control frequencies.
 */
public class SiteControlChannelLearner implements SiteMetadataListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SiteControlChannelLearner.class);
    private static final String ROLE_CURRENT_CONTROL = "primary_control";
    private static final String ROLE_SECONDARY_CONTROL = "secondary_control";
    static final long ABSENT_FREQUENCY_RECONCILIATION_DELAY_MILLISECONDS = TimeUnit.MINUTES.toMillis(10);

    private final Runnable mConfigurationSaveScheduler;
    private final Map<String,Long> mMatchingIdentityObservedSince = new HashMap<>();
    private final Map<String,Long> mLastObservedSourceFrequency = new HashMap<>();

    public SiteControlChannelLearner(ConfigurationManager configurationManager)
    {
        this(configurationManager != null ? configurationManager::scheduleConfigurationSave : () -> {});
    }

    SiteControlChannelLearner(Runnable configurationSaveScheduler)
    {
        mConfigurationSaveScheduler = configurationSaveScheduler != null ? configurationSaveScheduler : () -> {};
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
            !(channel.getDecodeConfiguration() instanceof DecodeConfigP25 decodeConfig))
        {
            return;
        }

        P25SiteIdentity observedIdentity = P25SiteIdentity.from(event.snapshot());
        Set<Long> advertisedFrequencies = getControlChannelFrequencies(event.snapshot());
        long sourceFrequency = event.sourceFrequency();
        String channelKey = channel.getConfigurationId();

        //A complete identity is authoritative only when the site identifies the frequency currently being decoded as
        //one of its own controls. This prevents stale snapshots from authorizing a different tuning epoch.
        if(observedIdentity == null || sourceFrequency <= 0 || !advertisedFrequencies.contains(sourceFrequency))
        {
            mMatchingIdentityObservedSince.remove(channelKey);
            return;
        }

        P25SiteIdentity boundIdentity = channel.getP25SiteIdentity();
        SourceConfigTunerMultipleFrequency sourceConfig =
            channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency multiple ? multiple : null;
        boolean frequencyLearningEnabled = decodeConfig.getLearnAnnouncedControlChannels() && sourceConfig != null;

        if(boundIdentity != null && !boundIdentity.equals(observedIdentity))
        {
            mMatchingIdentityObservedSince.remove(channelKey);
            mLastObservedSourceFrequency.remove(channelKey);

            if(frequencyLearningEnabled &&
                removeRejectedLearnedSource(channel, decodeConfig, sourceConfig, sourceFrequency))
            {
                LOGGER.warn("Removed learned P25 control channel {} Hz for channel {}; decoded identity {} does not " +
                    "match bound identity {}", sourceFrequency, channel.getName(), observedIdentity.display(),
                    boundIdentity.display());
                saveChanges(channel, sourceConfig, true);
            }

            return;
        }

        boolean identityChanged = false;

        if(boundIdentity == null)
        {
            if(!channel.bindP25SiteIdentity(observedIdentity))
            {
                return;
            }

            boundIdentity = channel.getP25SiteIdentity();
            identityChanged = true;
            LOGGER.info("Bound P25 channel {} to site identity {}", channel.getName(), boundIdentity.display());
        }

        if(!frequencyLearningEnabled)
        {
            mMatchingIdentityObservedSince.remove(channelKey);
            mLastObservedSourceFrequency.remove(channelKey);
            boolean preferredFrequencyChanged = updatePreferredFrequency(sourceConfig, sourceFrequency);

            if(identityChanged || preferredFrequencyChanged)
            {
                saveChanges(channel, sourceConfig, false);
            }

            return;
        }

        boolean frequenciesChanged = false;

        for(Long frequency: advertisedFrequencies)
        {
            if(frequency != null && frequency > 0 && !sourceConfig.getFrequencies().contains(frequency))
            {
                sourceConfig.addFrequency(frequency);
                decodeConfig.addLearnedControlFrequency(frequency);
                frequenciesChanged = true;
                LOGGER.info("Learned announced P25 control channel {} Hz for channel {} ({})", frequency,
                    channel.getName(), boundIdentity.display());
            }
        }

        long observedAt = event.observedAtEpochMilliseconds() > 0 ? event.observedAtEpochMilliseconds() :
            System.currentTimeMillis();
        Long matchingSince = mMatchingIdentityObservedSince.get(channelKey);
        Long lastSourceFrequency = mLastObservedSourceFrequency.put(channelKey, sourceFrequency);

        if(matchingSince == null || observedAt < matchingSince || lastSourceFrequency == null ||
            lastSourceFrequency != sourceFrequency)
        {
            mMatchingIdentityObservedSince.put(channelKey, observedAt);
        }
        else if(observedAt - matchingSince >= ABSENT_FREQUENCY_RECONCILIATION_DELAY_MILLISECONDS &&
            reconcileAbsentLearnedFrequencies(decodeConfig, sourceConfig, advertisedFrequencies))
        {
            frequenciesChanged = true;
        }

        boolean preferredFrequencyChanged = updatePreferredFrequency(sourceConfig, sourceFrequency);

        if(identityChanged || frequenciesChanged || preferredFrequencyChanged)
        {
            saveChanges(channel, sourceConfig, frequenciesChanged);
        }
    }

    /**
     * Remembers a strongly confirmed control channel only when it is already a member of this channel's rotation
     * list.  The caller has already verified the complete site identity and that the site advertises the decoded
     * source as a current or secondary control channel.
     */
    private boolean updatePreferredFrequency(SourceConfigTunerMultipleFrequency sourceConfig, long sourceFrequency)
    {
        if(sourceConfig == null || !sourceConfig.getFrequencies().contains(sourceFrequency) ||
            sourceConfig.getPreferredFrequency() == sourceFrequency)
        {
            return false;
        }

        sourceConfig.setPreferredFrequency(sourceFrequency);

        if(sourceConfig.getPreferredFrequency() == sourceFrequency)
        {
            LOGGER.info("Remembered confirmed P25 control channel {} Hz", sourceFrequency);
            return true;
        }

        return false;
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
        return ROLE_CURRENT_CONTROL.equals(role) || "current_control".equals(role) ||
            ROLE_SECONDARY_CONTROL.equals(role);
    }

    private boolean removeRejectedLearnedSource(Channel channel, DecodeConfigP25 decodeConfig,
                                                SourceConfigTunerMultipleFrequency sourceConfig,
                                                long sourceFrequency)
    {
        if(!decodeConfig.getLearnedControlFrequencies().contains(sourceFrequency) ||
            !sourceConfig.getFrequencies().contains(sourceFrequency) || sourceConfig.getFrequencies().size() <= 1)
        {
            return false;
        }

        List<Long> retained = sourceConfig.getFrequencies().stream()
            .filter(frequency -> frequency != sourceFrequency)
            .toList();
        sourceConfig.setFrequencies(retained);
        decodeConfig.removeLearnedControlFrequency(sourceFrequency);
        return true;
    }

    private boolean reconcileAbsentLearnedFrequencies(DecodeConfigP25 decodeConfig,
                                                       SourceConfigTunerMultipleFrequency sourceConfig,
                                                       Set<Long> advertised)
    {
        Set<Long> remove = new LinkedHashSet<>();

        for(Long frequency: decodeConfig.getLearnedControlFrequencies())
        {
            if(frequency != null && !advertised.contains(frequency) &&
                sourceConfig.getFrequencies().contains(frequency) &&
                sourceConfig.getFrequencies().size() - remove.size() > 1)
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

        for(Long frequency: remove)
        {
            decodeConfig.removeLearnedControlFrequency(frequency);
            LOGGER.info("Removed P25 control channel {} Hz because the bound site no longer advertises it", frequency);
        }

        return true;
    }

    private void saveChanges(Channel channel, SourceConfigTunerMultipleFrequency sourceConfig,
                             boolean frequenciesChanged)
    {
        if(frequenciesChanged)
        {
            //Refresh observable frequencies and cached tuner-channel projections after mutating the rotation list.
            channel.setSourceConfiguration(sourceConfig);
        }

        mConfigurationSaveScheduler.run();
    }
}
