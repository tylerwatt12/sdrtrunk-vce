/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.sample.Listener;

/** Listener for timestamped, ordered remote P25 bitstream packets. */
public interface IP25RemoteBitstreamListener
{
    Listener<P25RemoteBitstreamPacket> getRemoteBitstreamListener();
}
