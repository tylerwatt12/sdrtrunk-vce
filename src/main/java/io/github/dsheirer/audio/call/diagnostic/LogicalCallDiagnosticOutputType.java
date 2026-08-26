/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/**
 * Local downstream action that was submitted for a resolved logical call.
 *
 * <p>These values confirm only local submission.  They do not claim that a streaming provider received, accepted,
 * or published a call.</p>
 */
public enum LogicalCallDiagnosticOutputType
{
    RECORDED,
    STREAM_SUBMITTED
}
