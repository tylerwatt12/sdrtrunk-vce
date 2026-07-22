/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.application;

/**
 * Explicit non-interactive policy for unattended receiver startup.
 */
public record HeadlessStartupPolicy(VaultAction vaultAction, CalibrationAction calibrationAction,
                                    ChannelAction channelAction)
{
    public static HeadlessStartupPolicy evaluate(boolean voiceModuleLoaded, boolean vaultPresent,
                                                 boolean vaultUnlocked)
    {
        VaultAction vaultAction = voiceModuleLoaded && vaultPresent && !vaultUnlocked ?
            VaultAction.DISABLE_FOR_RUN : VaultAction.KEEP_CURRENT_STATE;
        return new HeadlessStartupPolicy(vaultAction,
            CalibrationAction.CONTINUE_WITH_CURRENT_IMPLEMENTATIONS,
            ChannelAction.START_CONFIGURED_CHANNELS_IMMEDIATELY);
    }

    public enum VaultAction
    {
        KEEP_CURRENT_STATE,
        DISABLE_FOR_RUN
    }

    public enum CalibrationAction
    {
        CONTINUE_WITH_CURRENT_IMPLEMENTATIONS
    }

    public enum ChannelAction
    {
        START_CONFIGURED_CHANNELS_IMMEDIATELY
    }
}
