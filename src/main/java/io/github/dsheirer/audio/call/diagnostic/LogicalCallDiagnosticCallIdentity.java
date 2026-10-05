/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;

/** Compact resolved-call identity safe for a diagnostic view. */
public record LogicalCallDiagnosticCallIdentity(long sessionLogicalCallSequence, String protocol, String decoder,
                                                 long startTimestamp, long endTimestamp, long resolvedTimestamp,
                                                 long resolutionWaitMilliseconds, String destinationValue,
                                                 String destinationAlias,
                                                 P25SubscriberIdentity destinationP25Identity,
                                                 Integer destinationObservedWorkingId, String sourceValue,
                                                 String sourceAlias, P25SubscriberIdentity sourceP25Identity,
                                                 Integer sourceObservedWorkingId,
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
        destinationObservedWorkingId = validWorkingId(destinationObservedWorkingId);
        sourceObservedWorkingId = validWorkingId(sourceObservedWorkingId);
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
            resolutionWaitMilliseconds, destinationValue, destinationAlias, null, null, sourceValue, sourceAlias,
            null, null, encryptionState, wacn, system, durableAliasListId, aliasListName, uniqueLearnedSiteCount);
    }

    private static Integer validWorkingId(Integer workingId)
    {
        return workingId != null && workingId > 0 &&
            workingId <= RadioSystemIdentityKey.MAX_P25_WORKING_UNIT_ID ? workingId : null;
    }
}
