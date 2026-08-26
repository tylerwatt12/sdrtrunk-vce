/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/** First ordered quality field that distinguishes the winner from the runner-up. */
public enum LogicalCallWinnerCriterion
{
    SINGLE_LEG,
    MISSING_AND_CONCEALED_RATE,
    USABLE_FRAME_COUNT,
    REPEATED_FRAME_RATE,
    NORMALIZED_FEC_ERROR_RATE,
    INGRESS_LOSS_OR_AUDIO_TRUNCATION,
    RETAINED_AUDIO_SAMPLE_COUNT,
    SITE_GUID,
    CHANNEL_CONFIGURATION_ID,
    CALL_LEG_ID
}
