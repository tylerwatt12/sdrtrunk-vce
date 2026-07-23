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
package io.github.dsheirer.record;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable handoff emitted only after a managed call recording has been fully written and published without
 * replacing an existing canonical file.
 */
public record RecordedCallArtifact(Path path, Path relativePath, RecordFormat format, long byteSize,
                                   AudioCallId callId, AudioCallRecordingMetadata metadata, long startAtMs,
                                   long completedAtMs, long durationMs, boolean encrypted,
                                   boolean destinationTalkgroupRecordEnabled)
{
    public RecordedCallArtifact
    {
        Objects.requireNonNull(path, "Recorded call path cannot be null");
        Objects.requireNonNull(relativePath, "Recorded call relative path cannot be null");
        Objects.requireNonNull(format, "Recorded call format cannot be null");
        Objects.requireNonNull(callId, "Recorded call ID cannot be null");
        Objects.requireNonNull(metadata, "Recorded call metadata cannot be null");

        Path normalizedPath = path.normalize();
        Path normalizedRelativePath = relativePath.normalize();
        ManagedRecordingPath parsedPath = ManagedRecordingPath.parse(relativePath).orElse(null);

        if(!path.isAbsolute() || !normalizedPath.equals(path) || relativePath.isAbsolute() ||
            !normalizedRelativePath.equals(relativePath) || parsedPath == null ||
            !normalizedPath.endsWith(relativePath) ||
            parsedPath.completedAtMs() != completedAtMs || parsedPath.format() != format)
        {
            throw new IllegalArgumentException("Recorded call paths are not a valid managed artifact");
        }

        if(byteSize <= 0 || startAtMs <= 0 || completedAtMs < startAtMs || durationMs < 0)
        {
            throw new IllegalArgumentException("Recorded call size, completion time, and duration are invalid");
        }
    }
}
