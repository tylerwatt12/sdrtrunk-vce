/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/** Positive proof used to confirm that two receiver legs carry the same transmission. */
public enum LogicalCallMergeProof
{
    SHARED_VOICE_CONTENT,
    MATCHING_SOURCE_IDENTITY_FALLBACK,
    MATCHING_ENCRYPTION_MESSAGE_INDICATOR
}
