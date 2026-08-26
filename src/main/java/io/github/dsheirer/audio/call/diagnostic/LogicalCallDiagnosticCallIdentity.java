/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import io.github.dsheirer.audio.call.CallEncryptionState;

/** Compact resolved-call identity safe for a diagnostic view. */
public record LogicalCallDiagnosticCallIdentity(long sessionLogicalCallSequence, String protocol, String decoder,
                                                 long startTimestamp, long endTimestamp, long resolvedTimestamp,
                                                 long resolutionWaitMilliseconds, String destinationValue,
                                                 String destinationAlias, String sourceValue, String sourceAlias,
                                                 CallEncryptionState encryptionState, Integer wacn, Integer system,
                                                 long durableAliasListId, String aliasListName,
                                                 int uniqueLearnedSiteCount)
{
    public LogicalCallDiagnosticCallIdentity
    {
        sessionLogicalCallSequence = Math.max(0L, sessionLogicalCallSequence);
        startTimestamp = Math.max(0L, startTimestamp);
        endTimestamp = Math.max(startTimestamp, endTimestamp);
        resolvedTimestamp = Math.max(0L, resolvedTimestamp);
        resolutionWaitMilliseconds = Math.max(0L, resolutionWaitMilliseconds);
        encryptionState = encryptionState != null ? encryptionState : CallEncryptionState.UNKNOWN;
        durableAliasListId = Math.max(0L, durableAliasListId);
        uniqueLearnedSiteCount = Math.max(0, uniqueLearnedSiteCount);
    }
}
