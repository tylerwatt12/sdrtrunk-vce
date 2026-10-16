/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.codec.mbe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/** Optional, format-bearing JMBE output capability. The installed library owns its processing. */
public final class JmbeAudioOutputProcessor
{
    public static final int MAXIMUM_SAMPLES = 16_777_216;
    private final Object mProvider;
    private final Method mProcess;

    public JmbeAudioOutputProcessor(Object provider)
    {
        mProvider = Objects.requireNonNull(provider);
        try
        {
            mProcess = provider.getClass().getMethod("process", float[].class, int.class, String.class);
        }
        catch(ReflectiveOperationException exception)
        {
            throw new IllegalArgumentException("JMBE does not provide an audio output processor", exception);
        }
    }

    public Output process(float[] input, int inputRate, String codec)
    {
        Object value;
        try { value = mProcess.invoke(mProvider, input, inputRate, codec); }
        catch(InvocationTargetException exception)
        {
            throw new IllegalStateException("JMBE audio output processing failed", exception.getCause());
        }
        catch(ReflectiveOperationException exception)
        {
            throw new IllegalStateException("JMBE audio output processing is unavailable", exception);
        }
        if(!(value instanceof Map<?,?> result) || !(result.get("samples") instanceof float[] samples) ||
            !(result.get("sample_rate") instanceof Integer rate) || rate < 8000 || rate > 96000 ||
            samples.length > MAXIMUM_SAMPLES || (long)samples.length * inputRate != (long)input.length * rate)
        {
            throw new IllegalStateException("Invalid JMBE audio output format or duration");
        }
        for(float sample : samples)
            if(!Float.isFinite(sample)) throw new IllegalStateException("Invalid JMBE audio output sample");
        return new Output(samples, rate);
    }

    public record Output(float[] samples, int sampleRate) {}
}
