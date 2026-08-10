/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded in-memory cache and announcement stream for completed calls played independently by web browsers.
 */
final class StatsWebCallService implements AutoCloseable
{
    private static final int MAXIMUM_CLIENTS = 32;
    private static final int EVENT_QUEUE_CAPACITY = 256;
    private static final int MAXIMUM_CALLS = 512;
    private static final long MAXIMUM_AUDIO_BYTES = 128L * 1024L * 1024L;
    static final int MAXIMUM_CALL_AUDIO_BYTES = 16 * 1024 * 1024;
    static final long MAXIMUM_PENDING_AUDIO_BYTES = MAXIMUM_CALL_AUDIO_BYTES;
    static final int WAVE_HEADER_BYTES = 44;
    private static final long MAXIMUM_AGE_MS = TimeUnit.MINUTES.toMillis(30);
    private static final int SAMPLE_RATE = 8000;
    private final StatsLiveEventHub mEventHub = new StatsLiveEventHub(MAXIMUM_CLIENTS, EVENT_QUEUE_CAPACITY);
    private final ExecutorService mEncoderExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2), new NamingThreadFactory("stats completed call audio"),
        new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,CachedCall> mCalls = new LinkedHashMap<>();
    private final AtomicLong mSequence = new AtomicLong();
    private final AtomicLong mPendingAudioBytes = new AtomicLong();
    private long mAudioBytes;
    private volatile boolean mRunning;

    void start()
    {
        mRunning = true;
    }

    synchronized void stop()
    {
        mRunning = false;
        mCalls.clear();
        mAudioBytes = 0;
    }

    void receive(CompletedAudioCall call)
    {
        AudioCallSnapshot snapshot = call != null ? call.snapshot() : null;

        if(!mRunning || !mEventHub.hasSubscribers() || call == null || !call.hasAudio() || snapshot == null ||
            snapshot.isDoNotMonitor() || snapshot.duplicate() || isUnresolvedTrafficCall(snapshot))
        {
            return;
        }

        int waveLength = checkedWaveLength(call.audioBuffers());

        if(waveLength < 0 || !reservePendingAudio(waveLength))
        {
            return;
        }

        try
        {
            mEncoderExecutor.execute(() -> {
                try
                {
                    cache(call, waveLength);
                }
                finally
                {
                    mPendingAudioBytes.addAndGet(-waveLength);
                }
            });
        }
        catch(RuntimeException e)
        {
            mPendingAudioBytes.addAndGet(-waveLength);
            // Service is shutting down.
        }
    }

    StatsLiveEventHub.Subscription subscribe()
    {
        return mRunning ? mEventHub.subscribe() : null;
    }

    private static boolean isUnresolvedTrafficCall(AudioCallSnapshot snapshot)
    {
        IdentifierCollection identifiers = snapshot != null ? snapshot.identifierCollection() : null;
        return identifiers != null && identifiers.getToIdentifier() == null &&
            identifiers.getIdentifier(IdentifierClass.DECODER, Form.TRAFFIC_CHANNEL, Role.ANY) != null;
    }

    synchronized CachedCall get(String id)
    {
        evictExpired(System.currentTimeMillis());
        return mCalls.get(id);
    }

    synchronized Map<String,Object> status()
    {
        evictExpired(System.currentTimeMillis());
        return Map.of("cached_calls", mCalls.size(), "cached_audio_bytes", mAudioBytes,
            "maximum_calls", MAXIMUM_CALLS, "maximum_audio_bytes", MAXIMUM_AUDIO_BYTES,
            "maximum_call_audio_bytes", MAXIMUM_CALL_AUDIO_BYTES,
            "pending_audio_bytes", mPendingAudioBytes.get(),
            "maximum_pending_audio_bytes", MAXIMUM_PENDING_AUDIO_BYTES);
    }

    private boolean reservePendingAudio(int waveLength)
    {
        long current;
        long updated;

        do
        {
            current = mPendingAudioBytes.get();
            updated = current + waveLength;

            if(updated > MAXIMUM_PENDING_AUDIO_BYTES)
            {
                return false;
            }
        }
        while(!mPendingAudioBytes.compareAndSet(current, updated));

        return true;
    }

    private void cache(CompletedAudioCall call, int waveLength)
    {
        if(!mRunning)
        {
            return;
        }

        byte[] wave = wave(call, waveLength);

        if(wave == null)
        {
            return;
        }

        String id = Long.toUnsignedString(mSequence.incrementAndGet(), 36);
        long created = System.currentTimeMillis();
        Map<String,Object> metadata = metadata(id, call, created);
        CachedCall cachedCall = new CachedCall(metadata, wave, created);

        synchronized(this)
        {
            evictExpired(created);
            mCalls.put(id, cachedCall);
            mAudioBytes += wave.length;
            evictToLimits();
        }

        mEventHub.publish("call", metadata);
    }

    private synchronized void evictExpired(long now)
    {
        List<String> expired = new ArrayList<>();

        for(Map.Entry<String,CachedCall> entry: mCalls.entrySet())
        {
            if(now - entry.getValue().createdAtMs() > MAXIMUM_AGE_MS)
            {
                expired.add(entry.getKey());
            }
        }

        expired.forEach(this::remove);
    }

    private void evictToLimits()
    {
        while(mCalls.size() > MAXIMUM_CALLS || mAudioBytes > MAXIMUM_AUDIO_BYTES)
        {
            remove(mCalls.keySet().iterator().next());
        }
    }

    private void remove(String id)
    {
        CachedCall removed = mCalls.remove(id);

        if(removed != null)
        {
            mAudioBytes -= removed.wave().length;
        }
    }

    private static Map<String,Object> metadata(String id, CompletedAudioCall call, long completedAt)
    {
        AudioCallSnapshot snapshot = call.snapshot();
        IdentifierCollection identifiers = snapshot.identifierCollection();
        Identifier<?> source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Identifier<?> target = identifiers != null ? identifiers.getToIdentifier() : null;
        AliasList aliasList = snapshot.aliasList();
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        value.put("call_id", id);
        value.put("audio_url", StatsApiV1.CALLS + "/" + id + "/audio");
        value.put("completed_at_ms", completedAt);
        value.put("duration_ms", call.getDuration());
        put(value, "system", identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY));
        put(value, "channel", identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY));
        put(value, "decoder", identifierValue(identifiers, IdentifierClass.CONFIGURATION, Form.DECODER_TYPE, Role.ANY));
        put(value, "source_id", value(source));
        put(value, "source_alias", alias(aliasList, source));
        put(value, "source_form", form(source));
        put(value, "target_id", value(target));
        put(value, "target_alias", alias(aliasList, target));
        put(value, "target_form", form(target));
        value.put("frequency_hz", longValue(identifiers, Form.CHANNEL_FREQUENCY));
        value.put("timeslot", snapshot.timeslot());
        value.put("encrypted", snapshot.encrypted());
        if(snapshot.voiceCallQuality() != null && snapshot.voiceCallQuality().hasMeasurements())
        {
            value.put("vc_quality_pct", snapshot.voiceCallQuality().qualityPercent());
            value.put("vc_decoded_frames", snapshot.voiceCallQuality().decodedFrameCount());
            value.put("vc_repeated_frames", snapshot.voiceCallQuality().repeatedFrameCount());
            value.put("vc_concealed_frames", snapshot.voiceCallQuality().concealedFrameCount());
            value.put("vc_missing_frames", snapshot.voiceCallQuality().missingFrameCount());
            value.put("vc_fec_errors", snapshot.voiceCallQuality().fecErrorCount());
            value.put("vc_fec_protected_bits", snapshot.voiceCallQuality().fecProtectedBitCount());
        }
        return Map.copyOf(value);
    }

    private static Object identifierValue(IdentifierCollection identifiers, IdentifierClass identifierClass,
                                          Form form, Role role)
    {
        return value(identifiers != null ? identifiers.getIdentifier(identifierClass, form, role) : null);
    }

    private static String value(Identifier<?> identifier)
    {
        Object value = identifier != null ? identifier.getValue() : null;
        return value != null ? value.toString() : null;
    }

    private static String form(Identifier<?> identifier)
    {
        return identifier != null && identifier.getForm() != null ? identifier.getForm().name() : null;
    }

    private static long longValue(IdentifierCollection identifiers, Form form)
    {
        if(identifiers != null)
        {
            List<Identifier> matches = identifiers.getIdentifiers(form);

            if(!matches.isEmpty())
            {
                Object value = matches.getFirst().getValue();

                if(value instanceof Number number)
                {
                    return number.longValue();
                }

                try
                {
                    return Long.parseLong(String.valueOf(value));
                }
                catch(NumberFormatException e)
                {
                    // Unknown frequency representation.
                }
            }
        }

        return 0;
    }

    private static String alias(AliasList aliasList, Identifier<?> identifier)
    {
        if(aliasList != null && identifier != null)
        {
            List<Alias> aliases = aliasList.getAliases(identifier);

            if(!aliases.isEmpty())
            {
                return aliases.getFirst().getName();
            }
        }

        return null;
    }

    private static void put(Map<String,Object> values, String key, Object value)
    {
        if(value != null)
        {
            values.put(key, value);
        }
    }

    static byte[] wave(CompletedAudioCall call)
    {
        List<float[]> audioBuffers = call != null ? call.audioBuffers() : null;
        int waveLength = checkedWaveLength(audioBuffers);

        if(waveLength < 0)
        {
            return null;
        }

        return wave(call, waveLength);
    }

    private static byte[] wave(CompletedAudioCall call, int waveLength)
    {
        List<float[]> audioBuffers = call != null ? call.audioBuffers() : null;

        if(audioBuffers == null || waveLength < WAVE_HEADER_BYTES || waveLength > MAXIMUM_CALL_AUDIO_BYTES)
        {
            return null;
        }

        int pcmLength = waveLength - WAVE_HEADER_BYTES;
        ByteBuffer wave = ByteBuffer.allocate(waveLength).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(36 + pcmLength);
        wave.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        wave.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(16);
        wave.putShort((short)1);
        wave.putShort((short)1);
        wave.putInt(SAMPLE_RATE);
        wave.putInt(SAMPLE_RATE * Short.BYTES);
        wave.putShort((short)Short.BYTES);
        wave.putShort((short)16);
        wave.put("data".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(pcmLength);

        for(float[] audioBuffer: audioBuffers)
        {
            if(audioBuffer == null || audioBuffer.length > wave.remaining() / Short.BYTES)
            {
                return null;
            }

            for(float sample: audioBuffer)
            {
                wave.putShort(sample > 1.0f ? Short.MAX_VALUE : sample < -1.0f ? (short)-Short.MAX_VALUE :
                    (short)(sample * Short.MAX_VALUE));
            }
        }

        if(wave.hasRemaining())
        {
            return null;
        }

        return wave.array();
    }

    static int checkedWaveLength(List<float[]> audioBuffers)
    {
        if(audioBuffers == null || audioBuffers.isEmpty())
        {
            return -1;
        }

        long pcmBytes = 0;

        try
        {
            for(float[] audioBuffer: audioBuffers)
            {
                if(audioBuffer == null)
                {
                    return -1;
                }

                pcmBytes = Math.addExact(pcmBytes,
                    Math.multiplyExact((long)audioBuffer.length, Short.BYTES));

                if(pcmBytes > MAXIMUM_CALL_AUDIO_BYTES - WAVE_HEADER_BYTES)
                {
                    return -1;
                }
            }

            if(pcmBytes == 0)
            {
                return -1;
            }

            return Math.toIntExact(Math.addExact(WAVE_HEADER_BYTES, pcmBytes));
        }
        catch(ArithmeticException e)
        {
            return -1;
        }
    }

    @Override
    public void close()
    {
        stop();
        mEncoderExecutor.shutdownNow();
        mEventHub.close();
    }

    record CachedCall(Map<String,Object> metadata, byte[] wave, long createdAtMs)
    {
    }
}
