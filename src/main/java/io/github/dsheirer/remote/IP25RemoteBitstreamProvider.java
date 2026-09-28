/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.sample.Listener;

/** Provider of timestamped, ordered remote P25 bitstream packets. */
public interface IP25RemoteBitstreamProvider
{
    void setRemoteBitstreamListener(Listener<P25RemoteBitstreamPacket> listener);

    void removeRemoteBitstreamListener(Listener<P25RemoteBitstreamPacket> listener);
}
