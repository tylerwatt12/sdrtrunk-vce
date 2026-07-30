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
package io.github.dsheirer.module.decode.nxdn;

/**
 * Immutable, one-time observation of a completed call from an explicitly conventional NXDN channel.
 *
 * <p>The normal decode event is mutable and is rebroadcast as a call progresses. This snapshot is emitted once when
 * that call closes so downstream statistics can count it without time-window deduplication.</p>
 */
public record NXDNConventionalCallEvent(long startTimestamp, long endTimestamp, String channelConfigurationId,
                                        String guid, String channelName, String aliasListName, long frequencyHertz,
                                        TargetKind targetKind, Integer talkgroupId, Integer sourceRadioId,
                                        Integer targetRadioId, boolean encrypted)
{
    public enum TargetKind
    {
        GROUP,
        PRIVATE,
        UNKNOWN
    }
}
