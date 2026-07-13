/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable renderer-neutral snapshot of one Systems activity table.
 */
public record ChannelActivitySnapshot(String tableId, String title, String channelName, String guid,
                                      boolean closeable, boolean controlActive, List<Row> rows)
{
    public ChannelActivitySnapshot
    {
        rows = rows != null ? List.copyOf(rows) : List.of();
    }

    public static ChannelActivitySnapshot from(ChannelActivityTableModel table)
    {
        Channel owner = table != null ? table.getOwnerChannel() : null;
        String tableId = owner != null ? "channel-" + owner.getChannelID() : "conventional";
        String guid = owner != null && owner.hasRadresGuid() ? owner.getRadresGuid() : null;
        List<Row> rows = table != null ? table.getRows().stream().map(Row::from).toList() : List.of();
        return new ChannelActivitySnapshot(tableId, table != null ? table.getTitle() : "",
            owner != null ? owner.getName() : "Conventional", guid, table != null && table.isCloseable(),
            table != null && table.isControlActive(), rows);
    }

    public record Row(String key, String status, String role, String controlRole, String lcn, long frequencyHz,
                      Integer timeslot, String sourceId, String sourceAlias, String talkerAlias,
                      String sourceAliasDisplay, String targetId, String targetAlias, String decoder,
                      String encryptionDetails)
    {
        private static Row from(ChannelActivityRow row)
        {
            return new Row(row.getKey(), row.getState().name(), row.getRole().name(),
                row.getControlRole().name(), row.getLcn(), row.getFrequency(), row.getTimeslot(),
                value(row.getSource()), aliases(row.getSourceAliases()), value(row.getTalkerAlias()),
                row.getSourceAliasDisplay(), value(row.getTarget()), aliases(row.getTargetAliases()),
                row.getDecoder(), row.getEncryptionDetails());
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
