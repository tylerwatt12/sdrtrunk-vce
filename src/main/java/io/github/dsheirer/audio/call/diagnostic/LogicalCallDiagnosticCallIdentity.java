/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegSummary;

/** Compact resolved-call identity safe for a diagnostic view. */
public record LogicalCallDiagnosticCallIdentity(long sessionLogicalCallSequence, String protocol, String decoder,
                                                 long startTimestamp, long endTimestamp, long resolvedTimestamp,
                                                 long resolutionWaitMilliseconds, String destinationValue,
                                                 String destinationAlias, String sourceValue, String sourceAlias,
                                                 CallEncryptionState encryptionState, Integer wacn, Integer system,
                                                 long durableAliasListId, String aliasListName,
                                                 int uniqueLearnedSiteCount,
                                                 CallLegSummary.P25IdentityObservation destinationP25Observation,
                                                 CallLegSummary.P25IdentityObservation sourceP25Observation,
                                                 String servingSystemKey)
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

    /** Source-compatible constructor for diagnostics captured without structured P25 subscriber identities. */
    public LogicalCallDiagnosticCallIdentity(long sessionLogicalCallSequence, String protocol, String decoder,
                                              long startTimestamp, long endTimestamp, long resolvedTimestamp,
                                              long resolutionWaitMilliseconds, String destinationValue,
                                              String destinationAlias, String sourceValue, String sourceAlias,
                                              CallEncryptionState encryptionState, Integer wacn, Integer system,
                                              long durableAliasListId, String aliasListName,
                                              int uniqueLearnedSiteCount)
    {
        this(sessionLogicalCallSequence, protocol, decoder, startTimestamp, endTimestamp, resolvedTimestamp,
            resolutionWaitMilliseconds, destinationValue, destinationAlias, sourceValue, sourceAlias,
            encryptionState, wacn, system, durableAliasListId, aliasListName, uniqueLearnedSiteCount,
            null, null, null);
    }
}
