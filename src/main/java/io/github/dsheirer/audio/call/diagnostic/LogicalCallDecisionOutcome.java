/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/** Final resolver disposition for one diagnostic decision. */
public enum LogicalCallDecisionOutcome
{
    /** Two or more confirmed receiver copies became one logical call. */
    MERGED,
    /** One receiver leg was intentionally emitted by itself. */
    INDEPENDENT,
    /** P25 evidence was incomplete or uncertain, so the receiver leg was preserved by itself. */
    FAIL_OPEN,
    /** A compromised or capacity-rejected physical leg was discarded before output. */
    ABORTED
}
