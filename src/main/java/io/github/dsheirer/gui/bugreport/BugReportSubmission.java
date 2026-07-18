/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

/**
 * Successful server acknowledgement for a diagnostic report.
 */
public record BugReportSubmission(String reportCode, String receivedAtUtc, String retentionUntilUtc)
{
}
