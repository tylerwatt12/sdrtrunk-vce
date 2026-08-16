/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of one browser Live Systems activity table.
 */
public record ChannelActivitySnapshot(String tableId, String title, String systemName, String siteName,
                                      String channelName, String configurationId, String guid, boolean controlActive,
                                      List<IdentifierField> identifiers, List<Row> rows)
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

    public static ChannelActivitySnapshot from(ChannelActivityTableState table)
    {
        Channel owner = table != null ? table.getOwnerChannel() : null;
        String tableId = owner != null ? "channel-" + owner.getChannelID() : "conventional";
        String guid = owner != null && owner.hasRadresGuid() ? owner.getRadresGuid() : null;
        List<Row> rows = table != null ? table.getRows().stream().map(Row::from).toList() : List.of();
        return new ChannelActivitySnapshot(tableId, table != null ? table.getTitle() : "",
            owner != null ? owner.getSystem() : "", owner != null ? owner.getSite() : "",
            owner != null ? owner.getName() : "Conventional", owner != null ? owner.getConfigurationId() : null,
            guid, table != null && table.isControlActive(),
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
                      Integer timeslot, String sourceId, String sourceForm, String sourceAlias, String talkerAlias,
                      String sourceAliasDisplay, String targetId, String targetForm, String targetAlias, String decoder,
                      String encryptionDetails)
    {
        private static Row from(ChannelActivityRow row)
        {
            String channelName = row.getRole() == ChannelActivityRow.Role.CONVENTIONAL ? row.getChannelName() : null;
            String configurationId = row.getChannel() != null ? row.getChannel().getConfigurationId() : null;
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
                value(row.getTalkerAlias()), row.getSourceAliasDisplay(), value(row.getTarget()),
                form(row.getTarget()), aliases(row.getTargetAliases()),
                row.getDecoder(), row.getEncryptionDetails());
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
    }
}
