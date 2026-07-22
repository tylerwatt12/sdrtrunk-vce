/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HeadlessStartupPolicyTest
{
    @Test
    void disablesAPresentLockedVaultWhenVoiceDecryptionIsLoaded()
    {
        HeadlessStartupPolicy policy = HeadlessStartupPolicy.evaluate(true, true, false);

        assertEquals(HeadlessStartupPolicy.VaultAction.DISABLE_FOR_RUN, policy.vaultAction());
    }

    @Test
    void keepsUnlockedMissingAndUnusedVaultStates()
    {
        assertEquals(HeadlessStartupPolicy.VaultAction.KEEP_CURRENT_STATE,
            HeadlessStartupPolicy.evaluate(true, true, true).vaultAction());
        assertEquals(HeadlessStartupPolicy.VaultAction.KEEP_CURRENT_STATE,
            HeadlessStartupPolicy.evaluate(true, false, false).vaultAction());
        assertEquals(HeadlessStartupPolicy.VaultAction.KEEP_CURRENT_STATE,
            HeadlessStartupPolicy.evaluate(false, true, false).vaultAction());
    }

    @Test
    void neverBlocksForCalibrationOrChannelConfirmation()
    {
        HeadlessStartupPolicy policy = HeadlessStartupPolicy.evaluate(true, true, false);

        assertEquals(HeadlessStartupPolicy.CalibrationAction.CONTINUE_WITH_CURRENT_IMPLEMENTATIONS,
            policy.calibrationAction());
        assertEquals(HeadlessStartupPolicy.ChannelAction.START_CONFIGURED_CHANNELS_IMMEDIATELY,
            policy.channelAction());
    }
}
