/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.audio.codec.mbe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import jmbe.iface.IAudioCodec;

/** Optional, versioned corrected-frame evidence from JMBE, bound once when a codec is loaded. */
final class JmbeVoiceFrameFingerprint
{
    private MethodHandle mGetter;

    private JmbeVoiceFrameFingerprint(MethodHandle getter)
    {
        mGetter = getter;
    }

    static JmbeVoiceFrameFingerprint create(IAudioCodec codec)
    {
        try
        {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle getter = lookup.findVirtual(codec.getClass(), "getCorrectedVoiceFrameFingerprintV1",
                MethodType.methodType(long.class)).bindTo(codec);
            MethodHandle enable = lookup.findVirtual(codec.getClass(), "setCorrectedVoiceFrameFingerprintEnabled",
                MethodType.methodType(void.class, boolean.class)).bindTo(codec);
            enable.invokeExact(true);
            return new JmbeVoiceFrameFingerprint(getter);
        }
        catch(Throwable _)
        {
            //Older or incompatible optional capabilities leave audio and received-bit evidence available.
            return null;
        }
    }

    long get()
    {
        if(mGetter != null)
        {
            try
            {
                return (long)mGetter.invokeExact();
            }
            catch(Throwable _)
            {
                //Disable a broken optional getter once, without logging on the decoder callback.
                mGetter = null;
            }
        }

        return 0L;
    }
}
