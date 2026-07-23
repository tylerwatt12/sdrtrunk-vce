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

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.TrunkedSiteMetadataClassifier;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.nxdn.layer3.type.Service;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigRecording;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Converts protocol-neutral DMR/NXDN metadata into compact database summaries.
 */
final class TrunkedSiteMetadataMapper
{
    private TrunkedSiteMetadataMapper()
    {
    }

    static TrunkedSiteSchema.Snapshot map(ProtocolSiteMetadataEvent event)
    {
        if(!TrunkedSiteMetadataClassifier.isKnownTrunkingMetadata(event) || event.channel() == null ||
            event.snapshot() == null || event.snapshot().protocol() == null)
        {
            return null;
        }

        Channel channel = event.channel();
        String guid = blankToNull(channel.getRadresGuid());

        if(guid == null)
        {
            return null;
        }

        long observedAt = event.observedAtEpochMilliseconds() > 0 ?
            event.observedAtEpochMilliseconds() : System.currentTimeMillis();
        Long primaryFrequency = primaryFrequency(channel);
        Long configuredCurrentControl = channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency ?
            null : primaryFrequency;
        String configuredSystem = blankToNull(channel.getSystem());
        String configuredSite = blankToNull(channel.getSite());
        String channelName = configuredSite != null ? configuredSite : blankToNull(channel.getName());
        String hash = sha256(String.join("|", safe(event.snapshot()), safe(configuredSystem), safe(channelName),
            safe(channel.getAliasListName()), safe(primaryFrequency)));

        if(event.snapshot() instanceof DMRNetworkConfigurationSnapshot dmr)
        {
            return mapDmr(observedAt, guid, hash, configuredSystem, channelName,
                blankToNull(channel.getAliasListName()), primaryFrequency, configuredCurrentControl, dmr);
        }
        else if(event.snapshot() instanceof NXDNNetworkConfigurationSnapshot nxdn)
        {
            return mapNxdn(observedAt, guid, hash, configuredSystem, channelName,
                blankToNull(channel.getAliasListName()), primaryFrequency, configuredCurrentControl, nxdn);
        }

        return null;
    }

    private static TrunkedSiteSchema.Snapshot mapDmr(long observedAt, String guid, String hash,
                                                      String configuredSystem, String channelName,
                                                      String aliasListName, Long primaryFrequency,
                                                      Long configuredCurrentControl,
                                                      DMRNetworkConfigurationSnapshot snapshot)
    {
        Integer modelCode = dmrModel(snapshot.model());
        List<TrunkedSiteSchema.Channel> channels = new ArrayList<>();

        for(DMRNetworkConfigurationSnapshot.Channel channel: snapshot.channels())
        {
            if(channel != null)
            {
                int role = TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED;

                if(channel.roles().contains(DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC))
                {
                    role |= TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC;
                }
                if(channel.roles().contains(DMRNetworkConfigurationSnapshot.ChannelRole.CONTROL) &&
                    (channel.downlink() == null || !channel.downlink().equals(configuredCurrentControl)))
                {
                    role |= TrunkedSiteSchema.CHANNEL_ROLE_ALTERNATE_CONTROL;
                }

                if(channel.downlink() != null && channel.downlink().equals(configuredCurrentControl))
                {
                    role |= TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL;
                }

                if(channel.frequencySource() == DMRNetworkConfigurationSnapshot.FrequencySource.CONFIGURED_MAP)
                {
                    role |= TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_FROM_CONFIGURED_MAP;
                }
                else if(channel.frequencySource() == DMRNetworkConfigurationSnapshot.FrequencySource.OVER_THE_AIR)
                {
                    role |= TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_ANNOUNCED_OVER_THE_AIR;
                }

                channels.add(new TrunkedSiteSchema.Channel(channel.logicalChannelNumber(), null, channel.timeslot(),
                    channel.downlink(), channel.uplink(), role,
                    observedAt(channel.observedAtEpochMilliseconds(), observedAt)));
            }
        }

        addCurrentControlIfMissing(channels, configuredCurrentControl, observedAt);
        List<TrunkedSiteSchema.Neighbor> neighbors = new ArrayList<>();

        for(DMRNetworkConfigurationSnapshot.NeighborSite neighbor: snapshot.neighborSites())
        {
            if(neighbor != null)
            {
                int status = Boolean.TRUE.equals(neighbor.networkConnectionActive()) ?
                    TrunkedSiteSchema.NEIGHBOR_STATUS_ACTIVE : 0;
                Integer neighborModel = dmrModel(neighbor.model());
                neighbors.add(new TrunkedSiteSchema.Neighbor(dmrVariant(neighbor.variant()),
                    neighborModel != null ? neighborModel : 0, neighbor.network(), null, neighbor.site(),
                    neighbor.logicalChannelNumber(), neighbor.downlink(), status,
                    observedAt(neighbor.observedAtEpochMilliseconds(), observedAt)));
            }
        }

        return new TrunkedSiteSchema.Snapshot(observedAt, guid, hash, TrunkedSiteSchema.PROTOCOL_DMR,
            dmrVariant(snapshot.variant()), modelCode != null ? modelCode : 0, configuredSystem, channelName,
            aliasListName, snapshot.decoder(), snapshot.network(), null, snapshot.site(), null, modelCode,
            dmrBrand(snapshot.brand()), dmrMode(snapshot.mode()), dmrChannelType(snapshot.channelType()),
            snapshot.colorCodeTimeslot1(), snapshot.colorCodeTimeslot2(), null, 0, null, primaryFrequency,
            configuredCurrentControl, channels, neighbors);
    }

    private static TrunkedSiteSchema.Snapshot mapNxdn(long observedAt, String guid, String hash,
                                                       String configuredSystem, String channelName,
                                                       String aliasListName, Long primaryFrequency,
                                                       Long configuredCurrentControl,
                                                       NXDNNetworkConfigurationSnapshot snapshot)
    {
        NXDNNetworkConfigurationSnapshot.Location location = snapshot.currentLocation();
        int identityDomain = nxdnIdentityDomain(location != null ? location.category() : snapshot.variant());
        Integer network = location != null ? location.integrator() : null;
        Integer system = location != null ? location.system() : null;
        Integer site = location != null && location.site() != null ? location.site() : snapshot.typeDSite();
        List<TrunkedSiteSchema.Channel> channels = new ArrayList<>();

        for(NXDNNetworkConfigurationSnapshot.Channel channel: snapshot.controlChannels())
        {
            if(channel != null)
            {
                int role = "CONTROL_1".equals(channel.role()) ? TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL :
                    "CONTROL_2".equals(channel.role()) ? TrunkedSiteSchema.CHANNEL_ROLE_ALTERNATE_CONTROL :
                        TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED;
                Integer channelNumber = channel.channelNumber() != null ? channel.channelNumber() :
                    channel.outboundChannelNumber();
                channels.add(new TrunkedSiteSchema.Channel(channelNumber, channel.inboundChannelNumber(), null,
                    channel.downlink(), channel.uplink(), role,
                    observedAt(channel.observedAtEpochMilliseconds(), observedAt)));
            }
        }

        for(Integer repeater: snapshot.observedRepeaters())
        {
            if(repeater != null)
            {
                int role = repeater.equals(snapshot.currentRepeater()) ?
                    TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL : TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED;
                channels.add(new TrunkedSiteSchema.Channel(repeater, null, null, null, null, role,
                    observedAt(snapshot.observedRepeaterTimestamp(repeater), observedAt)));
            }
        }

        Long currentControl = channels.stream()
            .filter(channel -> (channel.roleFlags() & TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL) != 0)
            .map(TrunkedSiteSchema.Channel::frequencyHertz)
            .filter(frequency -> frequency != null && frequency > 0)
            .findFirst()
            .orElse(configuredCurrentControl);
        addCurrentControlIfMissing(channels, currentControl, observedAt);
        List<TrunkedSiteSchema.Neighbor> neighbors = new ArrayList<>();

        for(NXDNNetworkConfigurationSnapshot.NeighborSite neighbor: snapshot.neighborSites())
        {
            if(neighbor == null)
            {
                continue;
            }

            NXDNNetworkConfigurationSnapshot.Location neighborLocation = neighbor.location();
            NXDNNetworkConfigurationSnapshot.Channel neighborChannel = neighbor.channel();
            Integer neighborSite = neighborLocation != null && neighborLocation.site() != null ?
                neighborLocation.site() : neighbor.id();
            Integer neighborChannelNumber = neighborChannel != null && neighborChannel.channelNumber() != null ?
                neighborChannel.channelNumber() : neighborChannel != null ? neighborChannel.outboundChannelNumber() :
                null;
            Long neighborFrequency = neighborChannel != null ? neighborChannel.downlink() : null;
            int status = Boolean.TRUE.equals(neighbor.isolated()) ?
                TrunkedSiteSchema.NEIGHBOR_STATUS_ISOLATED : 0;
            long neighborObservedAt = neighbor.observedAtEpochMilliseconds() > 0 ?
                neighbor.observedAtEpochMilliseconds() :
                neighborChannel != null ? neighborChannel.observedAtEpochMilliseconds() : 0;
            neighbors.add(new TrunkedSiteSchema.Neighbor(nxdnVariant(neighbor.variant()),
                nxdnIdentityDomain(neighborLocation != null ? neighborLocation.category() : neighbor.variant()),
                neighborLocation != null ? neighborLocation.integrator() : null,
                neighborLocation != null ? neighborLocation.system() : null, neighborSite,
                neighborChannelNumber, neighborFrequency, status,
                observedAt(neighborObservedAt, observedAt)));
        }

        return new TrunkedSiteSchema.Snapshot(observedAt, guid, hash, TrunkedSiteSchema.PROTOCOL_NXDN,
            nxdnVariant(snapshot.variant()), identityDomain, configuredSystem, channelName, aliasListName,
            snapshot.decoder(), network, system, site, snapshot.ran(), null, null,
            nxdnRepeaterMode(snapshot.repeaterStatus()), null, null, null, snapshot.currentRepeater(),
            nxdnServiceFlags(snapshot.services()),
            nxdnFailureCode(snapshot.failureStatus()), primaryFrequency, currentControl, channels, neighbors);
    }

    private static void addCurrentControlIfMissing(List<TrunkedSiteSchema.Channel> channels, Long frequency,
                                                   long observedAt)
    {
        if(frequency != null && frequency > 0 && channels.stream()
            .noneMatch(channel -> frequency.equals(channel.frequencyHertz())))
        {
            channels.addFirst(new TrunkedSiteSchema.Channel(null, null, null, frequency, null,
                TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL, observedAt));
        }
    }

    private static long observedAt(long childObservedAt, long fallbackObservedAt)
    {
        return childObservedAt > 0 ? childObservedAt : fallbackObservedAt;
    }

    private static Long primaryFrequency(Channel channel)
    {
        long frequency = 0;

        if(channel.getSourceConfiguration() instanceof SourceConfigTuner tuner)
        {
            frequency = tuner.getFrequency();
        }
        else if(channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency multiple)
        {
            frequency = multiple.getPreferredFrequency();
        }
        else if(channel.getSourceConfiguration() instanceof SourceConfigRecording recording)
        {
            frequency = recording.getFrequency();
        }

        return frequency > 0 ? frequency : null;
    }

    private static int dmrVariant(String value)
    {
        return switch(TrunkedSiteMetadataClassifier.canonicalVariant(value))
        {
            case "TIER_III" -> 1;
            case "CONNECT_PLUS" -> 2;
            case "CAPACITY_MAX" -> 3;
            case "HYTERA_TIER_III" -> 4;
            case "CAPACITY_PLUS" -> 5;
            default -> 0;
        };
    }

    private static int nxdnVariant(String value)
    {
        return switch(TrunkedSiteMetadataClassifier.canonicalVariant(value))
        {
            case "TYPE_C" -> 1;
            case "TYPE_D" -> 2;
            default -> 0;
        };
    }

    private static int nxdnIdentityDomain(String value)
    {
        return switch(safe(value))
        {
            case "GLOBAL" -> 1;
            case "REGIONAL" -> 2;
            case "LOCAL" -> 3;
            case "TYPE-D", "TYPE_D" -> 4;
            case "RESERVED" -> 5;
            default -> 0;
        };
    }

    private static Integer dmrBrand(String value)
    {
        return switch(safe(value))
        {
            case "Tier III Trunking" -> 1;
            case "Motorola Connect+" -> 2;
            case "Capacity Max Tier III Trunking" -> 3;
            case "Hytera Tier III Trunking" -> 4;
            case "Motorola Capacity+" -> 5;
            default -> null;
        };
    }

    private static Integer dmrModel(String value)
    {
        return switch(safe(value))
        {
            case "TINY" -> 1;
            case "SMALL" -> 2;
            case "LARGE" -> 3;
            case "HUGE" -> 4;
            default -> null;
        };
    }

    private static Integer dmrMode(String value)
    {
        return switch(safe(value))
        {
            case "Open System" -> 1;
            case "Advantage" -> 2;
            default -> null;
        };
    }

    private static Integer dmrChannelType(String value)
    {
        return switch(safe(value))
        {
            case "Control" -> 1;
            case "Traffic" -> 2;
            default -> null;
        };
    }

    private static Integer nxdnRepeaterMode(String value)
    {
        return switch(safe(value))
        {
            case "IDLE" -> 1;
            case "FREE" -> 2;
            case "HALTED_CWID" -> 3;
            default -> null;
        };
    }

    private static int nxdnServiceFlags(List<String> values)
    {
        int flags = 0;

        for(String value: values != null ? values : List.<String>of())
        {
            for(Service service: Service.values())
            {
                if(service.toString().equals(value))
                {
                    flags |= service.getValue();
                    break;
                }
            }
        }

        return flags;
    }

    private static Integer nxdnFailureCode(NXDNNetworkConfigurationSnapshot.FailureStatus failure)
    {
        if(failure == null || failure.callTimer() == null)
        {
            return null;
        }

        String timer = failure.callTimer();

        if("UNSPECIFIED".equals(timer))
        {
            return 0;
        }

        int separator = timer.indexOf(' ');

        try
        {
            return Integer.parseInt(separator > 0 ? timer.substring(0, separator) : timer);
        }
        catch(NumberFormatException e)
        {
            return null;
        }
    }

    private static String sha256(String value)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch(NoSuchAlgorithmException e)
        {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String safe(Object value)
    {
        return value != null ? value.toString() : "";
    }

    private static String blankToNull(String value)
    {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
