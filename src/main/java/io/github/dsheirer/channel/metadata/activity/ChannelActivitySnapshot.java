/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelContextKey;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of one browser Live Systems activity table.
 */
public record ChannelActivitySnapshot(String tableId, String title, String systemName, String siteName,
                                      String channelName, String configurationId, String guid, boolean controlActive,
                                      boolean channelRunning, List<IdentifierField> identifiers, List<Row> rows)
{
    public ChannelActivitySnapshot
    {
        tableId = tableId != null ? tableId : "";
        title = title != null ? title : "";
        systemName = systemName != null ? systemName : "";
        siteName = siteName != null ? siteName : "";
        channelName = channelName != null ? channelName : "";
        configurationId = configurationId != null ? configurationId : "";
        identifiers = identifiers != null ? List.copyOf(identifiers) : List.of();
        rows = rows != null ? List.copyOf(rows) : List.of();
    }

    /** Compatibility constructor for callers that predate explicit channel lifecycle state. */
    public ChannelActivitySnapshot(String tableId, String title, String systemName, String siteName,
                                   String channelName, String configurationId, String guid, boolean controlActive,
                                   List<IdentifierField> identifiers, List<Row> rows)
    {
        this(tableId, title, systemName, siteName, channelName, configurationId, guid, controlActive, true,
            identifiers, rows);
    }

    public static ChannelActivitySnapshot from(ChannelActivityTableState table)
    {
        Channel owner = table != null ? table.getOwnerChannel() : null;
        String tableId = owner != null ? "channel-" + owner.getChannelID() : "conventional";
        String guid = owner != null && owner.hasRadresGuid() ? owner.getRadresGuid() : null;
        List<Row> rows = table != null ? table.getRows().stream().map(row -> Row.from(row, owner)).toList() : List.of();
        return new ChannelActivitySnapshot(tableId, table != null ? table.getTitle() : "",
            owner != null ? owner.getSystem() : "", owner != null ? owner.getSite() : "",
            owner != null ? owner.getName() : "Conventional", owner != null ? owner.getConfigurationId() : null,
            guid, table != null && table.isControlActive(), table != null && table.isChannelRunning(),
            table != null ? table.getIdentifiers() : List.of(), rows);
    }

    /**
     * A protocol-neutral learned system or site identifier.  Labels retain the protocol's native terminology while
     * consumers can render every available value without protocol-specific branches.
     */
    public record IdentifierField(String group, String label, String value)
    {
        public IdentifierField
        {
            group = group != null ? group : "";
            label = label != null ? label : "";
            value = value != null ? value : "";
        }
    }

    public record Row(String key, String channelName, String configurationId, String status, List<String> tags,
                      String lcn, long frequencyHz, String callsign,
                      Double signalDbfs, Double decodeHealthPercent, long qualityObservedAtMs,
                      long controlValidFrames, long controlInvalidFrames, long controlCorrectedBits,
                      long controlSyncLossBits, long controlDroppedBits, long controlLastValidDecodeMs,
                      VoiceCallQuality voiceQuality,
                      Integer timeslot, String sourceId, String sourceForm, String sourceAlias,
                      String sourceAliasDescription, String talkerAlias, String sourceAliasDisplay, String targetId,
                      String targetForm, String targetAlias, String targetAliasDescription, String decoder,
                      String encryptionDetails, Navigation navigation, String role)
    {
        /**
         * Compatibility constructor for snapshots that do not supply browser navigation metadata or an explicit role.
         */
        public Row(String key, String channelName, String configurationId, String status, List<String> tags,
                   String lcn, long frequencyHz, String callsign,
                   Double signalDbfs, Double decodeHealthPercent, long qualityObservedAtMs,
                   long controlValidFrames, long controlInvalidFrames, long controlCorrectedBits,
                   long controlSyncLossBits, long controlDroppedBits, long controlLastValidDecodeMs,
                   VoiceCallQuality voiceQuality,
                   Integer timeslot, String sourceId, String sourceForm, String sourceAlias,
                   String sourceAliasDescription, String talkerAlias, String sourceAliasDisplay, String targetId,
                   String targetForm, String targetAlias, String targetAliasDescription, String decoder,
                   String encryptionDetails)
        {
            this(key, channelName, configurationId, status, tags, lcn, frequencyHz, callsign, signalDbfs,
                decodeHealthPercent, qualityObservedAtMs, controlValidFrames, controlInvalidFrames,
                controlCorrectedBits, controlSyncLossBits, controlDroppedBits, controlLastValidDecodeMs, voiceQuality,
                timeslot, sourceId, sourceForm, sourceAlias, sourceAliasDescription, talkerAlias, sourceAliasDisplay,
                targetId, targetForm, targetAlias, targetAliasDescription, decoder, encryptionDetails, null, null);
        }

        /**
         * Compatibility constructor for snapshots that do not supply alias descriptions, browser navigation
         * metadata, or an explicit role.
         */
        public Row(String key, String channelName, String configurationId, String status, List<String> tags,
                   String lcn, long frequencyHz, String callsign, Double signalDbfs, Double decodeHealthPercent,
                   long qualityObservedAtMs, long controlValidFrames, long controlInvalidFrames,
                   long controlCorrectedBits, long controlSyncLossBits, long controlDroppedBits,
                   long controlLastValidDecodeMs, VoiceCallQuality voiceQuality, Integer timeslot, String sourceId,
                   String sourceForm, String sourceAlias, String talkerAlias, String sourceAliasDisplay,
                   String targetId, String targetForm, String targetAlias, String decoder, String encryptionDetails)
        {
            this(key, channelName, configurationId, status, tags, lcn, frequencyHz, callsign, signalDbfs,
                decodeHealthPercent, qualityObservedAtMs, controlValidFrames, controlInvalidFrames,
                controlCorrectedBits, controlSyncLossBits, controlDroppedBits, controlLastValidDecodeMs, voiceQuality,
                timeslot, sourceId, sourceForm, sourceAlias, null, talkerAlias, sourceAliasDisplay, targetId,
                targetForm, targetAlias, null, decoder, encryptionDetails);
        }

        private static Row from(ChannelActivityRow row, Channel owner)
        {
            String channelName = row.getRole() == ChannelActivityRow.Role.CONVENTIONAL ? row.getChannelName() : null;
            String configurationId = row.getChannel() != null ? row.getChannel().getConfigurationId() : null;
            Channel channel = owner != null ? owner : row.getChannel();
            ChannelActivityDecodeQuality quality = row.getDecodeQuality();
            return new Row(row.getKey(), channelName, configurationId, row.getState().name(),
                row.getTags().stream().map(Enum::name).toList(), row.getLcn(),
                row.getFrequency(), row.getCallsign(), row.getSignalDbfs(),
                row.getDecodeHealthPercent(), row.getQualityObservedAt(),
                quality != null ? quality.controlValidFrames() : 0,
                quality != null ? quality.controlInvalidFrames() : 0,
                quality != null ? quality.controlCorrectedBits() : 0,
                quality != null ? quality.controlSyncLossBits() : 0,
                quality != null ? quality.controlDroppedBits() : 0,
                quality != null ? quality.controlLastValidDecodeMs() : 0,
                row.getVoiceCallQuality(), row.getTimeslot(),
                value(row.getSource()), form(row.getSource()), aliases(row.getSourceAliases()),
                aliasDescriptions(row.getSourceAliases()), value(row.getTalkerAlias()), row.getSourceAliasDisplay(),
                value(row.getTarget()), form(row.getTarget()), aliases(row.getTargetAliases()),
                aliasDescriptions(row.getTargetAliases()),
                row.getDecoder(), row.getEncryptionDetails(), new Navigation(ChannelContextKey.configured(channel),
                channel != null ? channel.getAliasListName() : null, protocol(row.getSource(), row.getTarget()),
                aliasReferences(row.getSourceAliases()), matcher(row.getSource()),
                aliasReferences(row.getTargetAliases()), matcher(row.getTarget())), row.getRole().name());
        }

        private static String protocol(Identifier<?> first, Identifier<?> second)
        {
            String protocol = protocol(first != null ? first.getProtocol() : null);
            return protocol != null ? protocol : protocol(second != null ? second.getProtocol() : null);
        }

        private static String protocol(Protocol protocol)
        {
            return switch(protocol)
            {
                case AM -> "am";
                case APCO25, APCO25_PHASE2 -> "p25";
                case DMR -> "dmr";
                case NXDN -> "nxdn";
                case NBFM -> "nbfm";
                case FLEETSYNC -> "fleetsync";
                case MDC1200 -> "mdc1200";
                case null, default -> null;
            };
        }

        private static MatcherReference matcher(Identifier<?> identifier)
        {
            if(identifier == null || !identifier.isValid() ||
                identifier instanceof FullyQualifiedRadioIdentifier ||
                identifier instanceof FullyQualifiedTalkgroupIdentifier || !(identifier.getValue() instanceof Number value))
            {
                return null;
            }

            String type = identifier.getForm() == Form.RADIO ? "radio" :
                identifier.getForm() == Form.TALKGROUP ? "talkgroup" : null;
            String protocol = protocol(identifier.getProtocol());

            if(type == null || protocol == null)
            {
                return null;
            }

            String variant = identifier.getProtocol() == Protocol.APCO25_PHASE2 ? "phase_2" :
                identifier.getProtocol() == Protocol.APCO25 ? "phase_1" : null;
            return new MatcherReference(type, protocol, variant, value.intValue());
        }

        private static List<AliasReference> aliasReferences(List<Alias> aliases)
        {
            if(aliases == null || aliases.isEmpty())
            {
                return List.of();
            }

            Map<Long,AliasReference> references = new LinkedHashMap<>();

            for(Alias alias: aliases)
            {
                if(alias != null && alias.getId() > 0 && alias.getAliasListId() > 0)
                {
                    references.putIfAbsent(alias.getId(),
                        new AliasReference(alias.getId(), alias.getAliasListId(), alias.getName()));
                }
            }

            return List.copyOf(references.values());
        }

        private static String form(Identifier<?> identifier)
        {
            return identifier != null && identifier.getForm() != null ? identifier.getForm().name() : null;
        }

        private static String value(Identifier<?> identifier)
        {
            Object value = identifier != null ? identifier.getValue() : null;
            return value != null ? value.toString() : null;
        }

        private static String aliases(List<Alias> aliases)
        {
            if(aliases == null || aliases.isEmpty())
            {
                return null;
            }

            return aliases.stream().filter(Objects::nonNull).map(Alias::getName).filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        }

        private static String aliasDescriptions(List<Alias> aliases)
        {
            if(aliases == null || aliases.isEmpty())
            {
                return null;
            }

            String descriptions = aliases.stream().filter(Objects::nonNull).map(Alias::getDescription)
                .filter(description -> description != null && !description.isBlank()).map(String::strip).distinct()
                .collect(Collectors.joining(", "));
            return descriptions.isEmpty() ? null : descriptions;
        }
    }

    /** Browser navigation metadata detached from mutable receiver and Alias objects. */
    public record Navigation(String contextKey, String aliasListName, String protocol,
                             List<AliasReference> sourceAliases, MatcherReference sourceMatcher,
                             List<AliasReference> targetAliases, MatcherReference targetMatcher)
    {
        public Navigation
        {
            sourceAliases = sourceAliases != null ? List.copyOf(sourceAliases) : List.of();
            targetAliases = targetAliases != null ? List.copyOf(targetAliases) : List.of();
        }
    }

    public record AliasReference(long aliasId, long aliasListId, String name)
    {
        public AliasReference
        {
            name = name != null ? name : "";
        }
    }

    public record MatcherReference(String type, String protocol, String variant, int value)
    {
    }
}
