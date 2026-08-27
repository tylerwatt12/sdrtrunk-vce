/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import io.github.dsheirer.portable.PortableApplicationPaths;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves the directory owned exclusively by logical-call diagnostics.
 */
public final class LogicalCallDiagnosticPath
{
    public static final String DIRECTORY_NAME = "logical-call-diagnostics";
    private static final String DEFAULT_APPLICATION_LOG_DIRECTORY = "logs";

    private LogicalCallDiagnosticPath()
    {
    }

    /**
     * Resolves the owned child beneath the configured application log directory.
     */
    public static Path forApplicationLogDirectory(Path applicationLogDirectory)
    {
        return Objects.requireNonNull(applicationLogDirectory, "applicationLogDirectory cannot be null")
            .resolve(DIRECTORY_NAME).normalize();
    }

    /**
     * Fallback used only when a configured application log directory is not available to the caller.
     */
    public static Path portableDefault()
    {
        return forApplicationLogDirectory(PortableApplicationPaths.getDataRoot()
            .resolve(DEFAULT_APPLICATION_LOG_DIRECTORY));
    }
}
