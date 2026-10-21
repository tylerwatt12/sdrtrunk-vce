/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.controller.channel;

/** Test fixture access to presentation state, independent from the actual processing-chain authority. */
public final class ChannelTestAccess
{
    private ChannelTestAccess() {}
    public static void setProcessing(Channel channel, boolean processing) { channel.setProcessing(processing); }
}
