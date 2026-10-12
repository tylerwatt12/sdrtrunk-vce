/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.diagnostic;

import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;

/** Saved channel context captured once before requesting a receiver source. No observer performs channel lookup. */
public record TunerAllocationContext(String channelId, String channelName, String systemName, String siteName,
                                     String preferredTuner, Role role)
{
    public enum Role { CONTROL, CONVENTIONAL, CALL }

    public static TunerAllocationContext from(Channel channel)
    {
        Role role = !channel.isStandardChannel() ? Role.CALL :
            ChannelConfigurationPolicy.requireChannelKind(channel) == ChannelConfigurationPolicy.ChannelKind.TRUNKED ?
                Role.CONTROL : Role.CONVENTIONAL;
        String preferred = channel.getSourceConfiguration() instanceof SourceConfigTuner single ?
            single.getPreferredTuner() : channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency multiple ?
            multiple.getPreferredTuner() : null;
        return new TunerAllocationContext(channel.getPersistedConfigurationId(), channel.getName(),
            channel.getSystem(), channel.getSite(), preferred, role);
    }
}
