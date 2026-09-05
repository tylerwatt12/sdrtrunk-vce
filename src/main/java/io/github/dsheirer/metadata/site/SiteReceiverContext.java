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
import io.github.dsheirer.controller.channel.ChannelConfigurationKey;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.SourceType;
import io.github.dsheirer.source.config.SourceConfigRecording;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable receiver facts captured when site metadata is produced.
 *
 * <p>Site metadata crosses several asynchronous observer boundaries.  Keeping these values beside the over-the-air
 * snapshot prevents a later channel edit, reload, or frequency change from relabeling an earlier observation.</p>
 */
public record SiteReceiverContext(String configurationId, int runtimeChannelId, long processingIncarnation,
                                  Channel.ChannelType channelType,
                                  DecoderType decoderType, Protocol protocol, ReceiverMode receiverMode,
                                  TrunkedIdentityDomain identityDomain,
                                  SourceType sourceType, Long configuredPrimaryFrequency,
                                  Long preferredFrequency, Long sourceFrequency, String radioResolveId,
                                  String channelName, String systemName, String siteName,
                                  long aliasListId, String aliasListName)
{
    /** Configured receiver behavior, independent from the protocol variant learned over the air. */
    public enum ReceiverMode
    {
        CONVENTIONAL,
        TRUNKED,
        UNKNOWN
    }

    /**
     * Captures a channel without claiming an exact currently decoded frequency.
     */
    public static SiteReceiverContext capture(Channel channel, Protocol protocol)
    {
        return capture(channel, protocol, 0);
    }

    /**
     * Captures a channel and, when positive, the exact frequency that produced the observation.
     */
    public static SiteReceiverContext capture(Channel channel, Protocol protocol, long sourceFrequency)
    {
        if(channel == null)
        {
            return null;
        }

        DecodeConfiguration decodeConfiguration = channel.getDecodeConfiguration();
        DecoderType decoderType = decodeConfiguration != null ? decodeConfiguration.getDecoderType() : null;
        Protocol capturedProtocol = protocol != null ? protocol :
            decoderType != null ? decoderType.getProtocol() : null;
        SourceConfiguration sourceConfiguration = channel.getSourceConfiguration();
        SourceType sourceType = sourceConfiguration != null ? sourceConfiguration.getSourceType() : null;
        Long configuredPrimaryFrequency = null;
        Long preferredFrequency = null;

        if(sourceConfiguration instanceof SourceConfigTuner tuner)
        {
            configuredPrimaryFrequency = positive(tuner.getFrequency());
        }
        else if(sourceConfiguration instanceof SourceConfigTunerMultipleFrequency multiple)
        {
            List<Long> frequencies = multiple.getFrequencies();

            if(frequencies != null)
            {
                for(Long frequency: frequencies)
                {
                    if(frequency != null && frequency > 0)
                    {
                        configuredPrimaryFrequency = frequency;
                        break;
                    }
                }
            }

            preferredFrequency = positive(multiple.getPreferredFrequency());
        }
        else if(sourceConfiguration instanceof SourceConfigRecording recording)
        {
            configuredPrimaryFrequency = positive(recording.getFrequency());
        }

        String radioResolveId = channel.hasRadioResolveId() ? channel.getRadioResolveId() : null;
        return new SiteReceiverContext(ChannelConfigurationKey.configured(channel), channel.getChannelID(),
            channel.getProcessingIncarnation(), channel.getChannelType(),
            decoderType, capturedProtocol, receiverMode(decodeConfiguration), identityDomain(decodeConfiguration),
            sourceType,
            configuredPrimaryFrequency, preferredFrequency, positive(sourceFrequency), radioResolveId,
            channel.getName(), channel.getSystem(), channel.getSite(), channel.getAliasListId(),
            channel.getAliasListName());
    }

    /** Minimal context for a detached quality-registry snapshot that has no live channel token. */
    public static SiteReceiverContext detached(String configurationId, long sourceFrequency)
    {
        return new SiteReceiverContext(ChannelConfigurationKey.canonical(configurationId), -1, 0, null, null, null,
            ReceiverMode.UNKNOWN, TrunkedIdentityDomain.STANDARD, null, null, null, positive(sourceFrequency),
            null, null, null, null, 0, null);
    }

    /** Returns the same frozen receiver facts with an exact producer-observed frequency. */
    public SiteReceiverContext withSourceFrequency(long frequency)
    {
        return new SiteReceiverContext(configurationId, runtimeChannelId, processingIncarnation, channelType,
            decoderType, protocol,
            receiverMode, identityDomain, sourceType, configuredPrimaryFrequency, preferredFrequency,
            positive(frequency), radioResolveId, channelName, systemName, siteName, aliasListId, aliasListName);
    }

    /** Applies an explicitly supplied durable key for compatibility with detached snapshot producers. */
    public SiteReceiverContext withConfigurationId(String explicitConfigurationId)
    {
        String canonical = ChannelConfigurationKey.canonical(explicitConfigurationId);
        return new SiteReceiverContext(canonical != null ? canonical : configurationId, runtimeChannelId,
            processingIncarnation, channelType, decoderType, protocol, receiverMode, identityDomain, sourceType,
            configuredPrimaryFrequency, preferredFrequency, sourceFrequency, radioResolveId, channelName,
            systemName, siteName, aliasListId, aliasListName);
    }

    /** Frequency used by the existing site-summary primary-frequency field. */
    public Long effectivePrimaryFrequency()
    {
        return preferredFrequency != null ? preferredFrequency : configuredPrimaryFrequency;
    }

    public boolean isStandardChannel()
    {
        return channelType == Channel.ChannelType.STANDARD;
    }

    public boolean isTrafficChannel()
    {
        return channelType == Channel.ChannelType.TRAFFIC;
    }

    public boolean isRotatingSource()
    {
        return sourceType == SourceType.TUNER_MULTIPLE_FREQUENCIES;
    }

    public boolean isConventional()
    {
        return receiverMode == ReceiverMode.CONVENTIONAL;
    }

    public boolean isTrunked()
    {
        return receiverMode == ReceiverMode.TRUNKED;
    }

    /**
     * Verifies that a retained live channel still represents this captured receiver. Display-only edits are allowed;
     * durable identity, decoder mode, source kind, and configured frequency ownership must still agree.
     */
    public boolean matchesCurrentChannel(Channel channel)
    {
        if(channel == null || channel.getChannelID() != runtimeChannelId || channel.getChannelType() != channelType)
        {
            return false;
        }

        if(!channel.matchesProcessingIncarnation(processingIncarnation, true))
        {
            return false;
        }

        SiteReceiverContext current = capture(channel, protocol, 0);
        return current != null && Objects.equals(configurationId, current.configurationId) &&
            decoderType == current.decoderType && receiverMode == current.receiverMode &&
            identityDomain == current.identityDomain &&
            sourceType == current.sourceType &&
            Objects.equals(configuredPrimaryFrequency, current.configuredPrimaryFrequency) &&
            Objects.equals(preferredFrequency, current.preferredFrequency);
    }

    /**
     * Verifies the receiver generation for a control-channel quality observation.  A running P25 receiver can learn
     * additional rotation frequencies and change its preferred start frequency without creating a new decoder
     * generation.  Those normal updates must not invalidate later quality measurements from the same monitor.
     * Decoder behavior, source kind, durable configuration identity, and the primary configured frequency remain
     * generation-defining facts.  Site metadata deliberately uses {@link #matchesCurrentChannel(Channel)} instead,
     * because its system/site assignment requires the stricter source-ownership check.
     */
    public boolean matchesCurrentQualityReceiver(Channel channel, boolean activeObservation)
    {
        if(channel == null || channel.getChannelID() != runtimeChannelId || channel.getChannelType() != channelType)
        {
            return false;
        }

        if(!channel.matchesProcessingIncarnation(processingIncarnation, activeObservation))
        {
            return false;
        }

        SiteReceiverContext current = capture(channel, null, 0);
        return current != null && Objects.equals(configurationId, current.configurationId) &&
            decoderType == current.decoderType && protocol == current.protocol &&
            receiverMode == current.receiverMode && identityDomain == current.identityDomain &&
            sourceType == current.sourceType &&
            Objects.equals(configuredPrimaryFrequency, current.configuredPrimaryFrequency);
    }

    /** Same running receiver/configuration generation, excluding display text and per-observation source frequency. */
    public boolean isSameReceiver(SiteReceiverContext other)
    {
        return other != null && runtimeChannelId == other.runtimeChannelId &&
            processingIncarnation == other.processingIncarnation &&
            Objects.equals(configurationId, other.configurationId) && channelType == other.channelType &&
            decoderType == other.decoderType && protocol == other.protocol && receiverMode == other.receiverMode &&
            identityDomain == other.identityDomain && sourceType == other.sourceType &&
            Objects.equals(configuredPrimaryFrequency, other.configuredPrimaryFrequency) &&
            Objects.equals(preferredFrequency, other.preferredFrequency);
    }

    private static ReceiverMode receiverMode(DecodeConfiguration configuration)
    {
        if(configuration instanceof DecodeConfigDMR dmr)
        {
            return dmr.isTrunked() ? ReceiverMode.TRUNKED : ReceiverMode.CONVENTIONAL;
        }
        else if(configuration instanceof DecodeConfigNXDN nxdn)
        {
            return nxdn.isTrunked() ? ReceiverMode.TRUNKED : ReceiverMode.CONVENTIONAL;
        }
        else if(configuration instanceof DecodeConfigP25)
        {
            return ReceiverMode.TRUNKED;
        }
        else if(configuration instanceof DecodeConfigP25Conventional)
        {
            return ReceiverMode.CONVENTIONAL;
        }
        else if(configuration != null)
        {
            DecoderType type = configuration.getDecoderType();

            if(type == DecoderType.AM || type == DecoderType.NBFM)
            {
                return ReceiverMode.CONVENTIONAL;
            }
        }

        return ReceiverMode.UNKNOWN;
    }

    private static TrunkedIdentityDomain identityDomain(DecodeConfiguration configuration)
    {
        if(configuration instanceof DecodeConfigNXDN nxdn && nxdn.getTransmissionMode() != null &&
            nxdn.getTransmissionMode().isTypeD())
        {
            return TrunkedIdentityDomain.NXDN_TYPE_D;
        }
        else if(configuration instanceof DecodeConfigNXDN)
        {
            return TrunkedIdentityDomain.NXDN_TYPE_C;
        }

        return TrunkedIdentityDomain.STANDARD;
    }

    private static Long positive(long value)
    {
        return value > 0 ? value : null;
    }
}
