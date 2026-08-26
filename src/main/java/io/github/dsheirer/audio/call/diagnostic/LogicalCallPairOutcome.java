/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/** Pairwise result produced by the same evidence evaluation that controls resolver grouping. */
public enum LogicalCallPairOutcome
{
    MERGED,
    SEPARATED,
    FAIL_OPEN
}
