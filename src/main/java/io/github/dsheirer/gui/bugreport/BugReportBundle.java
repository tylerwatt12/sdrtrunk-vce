/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import java.nio.file.Path;

/**
 * Temporary sanitized diagnostic bundle ready to upload.
 */
public record BugReportBundle(Path path, String clientReportId, long sizeBytes)
{
}
