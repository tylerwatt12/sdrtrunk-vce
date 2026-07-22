/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

/**
 * Immutable, renderer-neutral description of a selectable Live activity context.
 *
 * <p>The identifier is stable for the lifetime of the activity model.  Site selections intentionally retain one
 * identifier while the active control frequency changes.  Exact selections identify one table row, including its
 * frequency and timeslot.  Channel identifiers are process-local handles and are never persisted.</p>
 *
 * @param selectionId opaque browser-safe selection identifier
 * @param tableId activity table that owns the selection
 * @param rowKey current activity row key
 * @param tableTitle user-facing system/site table title
 * @param channelName user-facing configured channel name, when available
 * @param scope site-wide or exact-frequency selection scope
 * @param ownerChannelId process-local owner channel identifier, when this is a trunked site
 * @param rowChannelId process-local channel identifier currently associated with the row
 * @param frequencyHz selected frequency in hertz
 * @param timeslot selected timeslot, when applicable
 * @param decoderHint display-only decoder description
 */
public record ChannelActivitySelectionDescriptor(String selectionId, String tableId, String rowKey,
                                                  String tableTitle, String channelName,
                                                  ChannelActivitySelectionScope scope, Integer ownerChannelId,
                                                  Integer rowChannelId, long frequencyHz, Integer timeslot,
                                                  String decoderHint)
{
    public ChannelActivitySelectionDescriptor
    {
        if(selectionId == null || selectionId.isBlank())
        {
            throw new IllegalArgumentException("Selection identifier cannot be blank");
        }

        if(tableId == null || tableId.isBlank())
        {
            throw new IllegalArgumentException("Table identifier cannot be blank");
        }

        if(rowKey == null || rowKey.isBlank())
        {
            throw new IllegalArgumentException("Row key cannot be blank");
        }

        if(scope == null)
        {
            throw new IllegalArgumentException("Selection scope cannot be null");
        }
    }

    public boolean isSite()
    {
        return scope == ChannelActivitySelectionScope.SITE;
    }
}
