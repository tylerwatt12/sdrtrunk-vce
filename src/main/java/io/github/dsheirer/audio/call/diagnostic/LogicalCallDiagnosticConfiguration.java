/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Fixed resource limits for one logical-call diagnostic service session.
 */
public record LogicalCallDiagnosticConfiguration(Path directory, int recentDecisionCapacity, int queueCapacity,
                                                 long maximumFileBytes, int maximumFiles,
                                                 int maximumRecordBytes, Duration closeTimeout)
{
    public static final int DEFAULT_RECENT_DECISION_CAPACITY = 256;
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    public static final long DEFAULT_MAXIMUM_FILE_BYTES = 2L * 1024L * 1024L;
    public static final int DEFAULT_MAXIMUM_FILES = 4;
    public static final int DEFAULT_MAXIMUM_RECORD_BYTES = 64 * 1024;
    public static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(2);

    public LogicalCallDiagnosticConfiguration
    {
        Objects.requireNonNull(directory, "directory cannot be null");
        Objects.requireNonNull(closeTimeout, "closeTimeout cannot be null");
        directory = directory.normalize();
        requirePowerOfTwo(recentDecisionCapacity, "recentDecisionCapacity");
        requirePowerOfTwo(queueCapacity, "queueCapacity");

        if(maximumFileBytes < 1_024)
        {
            throw new IllegalArgumentException("maximumFileBytes must be at least 1024");
        }

        if(maximumFiles < 1 || maximumFiles > 32)
        {
            throw new IllegalArgumentException("maximumFiles must be between 1 and 32");
        }

        if(maximumRecordBytes < 256 || maximumRecordBytes > maximumFileBytes)
        {
            throw new IllegalArgumentException("maximumRecordBytes must be between 256 and maximumFileBytes");
        }

        if(closeTimeout.isZero() || closeTimeout.isNegative() || closeTimeout.compareTo(Duration.ofSeconds(30)) > 0)
        {
            throw new IllegalArgumentException("closeTimeout must be greater than zero and no more than 30 seconds");
        }
    }

    /**
     * Creates production defaults beneath the caller-provided application log directory.
     */
    public static LogicalCallDiagnosticConfiguration defaults(Path applicationLogDirectory)
    {
        return defaultsForDiagnosticDirectory(LogicalCallDiagnosticPath.forApplicationLogDirectory(
            applicationLogDirectory));
    }

    /**
     * Creates production defaults beneath the portable data log directory when no configured log path is available.
     */
    public static LogicalCallDiagnosticConfiguration portableDefaults()
    {
        return defaultsForDiagnosticDirectory(LogicalCallDiagnosticPath.portableDefault());
    }

    private static LogicalCallDiagnosticConfiguration defaultsForDiagnosticDirectory(Path directory)
    {
        return new LogicalCallDiagnosticConfiguration(directory, DEFAULT_RECENT_DECISION_CAPACITY,
            DEFAULT_QUEUE_CAPACITY, DEFAULT_MAXIMUM_FILE_BYTES, DEFAULT_MAXIMUM_FILES,
            DEFAULT_MAXIMUM_RECORD_BYTES, DEFAULT_CLOSE_TIMEOUT);
    }

    private static void requirePowerOfTwo(int value, String name)
    {
        if(value < 2 || Integer.bitCount(value) != 1)
        {
            throw new IllegalArgumentException(name + " must be a power of two greater than one");
        }
    }
}
