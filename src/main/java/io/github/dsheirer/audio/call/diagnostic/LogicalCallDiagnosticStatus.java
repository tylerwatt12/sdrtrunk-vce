/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/** Session-only status for the bounded in-memory duplicate history; decisionsObserved counts confirmed merges. */
public record LogicalCallDiagnosticStatus(boolean accepting, long decisionsObserved,
                                          long recordsRejectedAfterClose)
{
}
