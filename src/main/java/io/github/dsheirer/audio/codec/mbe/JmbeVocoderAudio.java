/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.codec.mbe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/** Optional JMBE capability. The selected JMBE library owns models and audio processing. */
public final class JmbeVocoderAudio
{
    private final Object mProvider;
    private final Method mModelInfo;
    private final Method mProcess;

    public JmbeVocoderAudio(Object provider)
    {
        mProvider = Objects.requireNonNull(provider);
        try
        {
            mModelInfo = provider.getClass().getMethod("getModelInfo");
            mProcess = provider.getClass().getMethod("process", float[].class, String.class, boolean.class,
                boolean.class, long[].class, long[].class);
        }
        catch(ReflectiveOperationException exception)
        {
            throw new IllegalArgumentException("JMBE does not provide audio processing", exception);
        }
    }

    public Map<String,Object> modelInfo()
    {
        Object value = invoke(mModelInfo);
        if(!(value instanceof Map<?,?> values)) throw new IllegalStateException("Invalid JMBE model information");
        java.util.LinkedHashMap<String,Object> result = new java.util.LinkedHashMap<>();
        for(var entry : values.entrySet())
        {
            if(!(entry.getKey() instanceof String key)) throw new IllegalStateException("Invalid JMBE model information");
            result.put(key, entry.getValue());
        }
        return Map.copyOf(result);
    }

    public float[] process(float[] raw8, String codec, boolean enhance, boolean agc, long[] toneOffsets,
                           long[] toneDurations)
    {
        Object value = invoke(mProcess, raw8, codec, enhance, agc, toneOffsets, toneDurations);
        if(!(value instanceof float[] samples) || samples.length != (long)raw8.length * (enhance ? 2 : 1))
            throw new IllegalStateException("Invalid JMBE audio output length");
        for(float sample : samples)
            if(!Float.isFinite(sample)) throw new IllegalStateException("Invalid JMBE audio output sample");
        return samples;
    }

    private Object invoke(Method method, Object... arguments)
    {
        try { return method.invoke(mProvider, arguments); }
        catch(InvocationTargetException exception)
        {
            throw new IllegalStateException("JMBE audio processing failed", exception.getCause());
        }
        catch(ReflectiveOperationException exception)
        {
            throw new IllegalStateException("JMBE audio processing is unavailable", exception);
        }
    }
}
