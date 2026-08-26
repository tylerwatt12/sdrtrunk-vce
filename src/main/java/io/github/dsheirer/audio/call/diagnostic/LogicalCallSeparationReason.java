/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/**
 * Exact reason why the resolver did not combine a physical leg with another candidate.
 *
 * @param failOpen true when uncertainty deliberately preserves an extra call
 */
public enum LogicalCallSeparationReason
{
    NON_P25_RESOLUTION_NOT_APPLICABLE(false),
    MISSING_CALL_SOURCE(true),
    MISSING_DECODER_TYPE(true),
    MISSING_DURABLE_ALIAS_LIST_ID(true),
    MISSING_LEARNED_SITE_IDENTITY(true),
    MISSING_DESTINATION_IDENTITY(true),
    MISSING_ENCRYPTION_STATE(true),
    INVALID_CALL_TIMING(true),
    COHORT_CAPACITY(true),
    ALIAS_LIST_MISMATCH(false),
    WACN_MISMATCH(false),
    SYSTEM_ID_MISMATCH(false),
    DESTINATION_MISMATCH(false),
    ENCRYPTION_STATE_MISMATCH(false),
    SOURCE_IDENTITY_MISMATCH(false),
    INSUFFICIENT_TIME_OVERLAP(false),
    INSUFFICIENT_DUPLICATE_PROOF(true),
    NO_CANDIDATE_LEG(false),
    INGRESS_COMPROMISED(true),
    ACTIVE_LEG_CAPACITY(true);

    private final boolean mFailOpen;

    LogicalCallSeparationReason(boolean failOpen)
    {
        mFailOpen = failOpen;
    }

    public boolean isFailOpen()
    {
        return mFailOpen;
    }
}
