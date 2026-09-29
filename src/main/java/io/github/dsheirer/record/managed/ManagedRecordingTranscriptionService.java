/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.record.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveBuilder;
import io.github.dsheirer.preference.record.RecordPreference;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls completed Managed Recordings from the catalog on its own thread. No decoder, recording, or catalog callback
 * calls this service. The catalog writer only sees the small final result, after the network request has finished.
 */
public final class ManagedRecordingTranscriptionService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ManagedRecordingTranscriptionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration IDLE_DELAY = Duration.ofSeconds(30);
    private static final Duration FAILURE_DELAY = Duration.ofMinutes(5);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_TRANSCRIPT_CHARACTERS = 200_000;

    private final ManagedRecordingCatalog mCatalog;
    private final Supplier<Settings> mSettings;
    private final BooleanSupplier mRecordingBusy;
    private final HttpClient mClient;
    private final Duration mIdleDelay;
    private final Duration mFailureDelay;
    private final Thread mWorker;
    private volatile boolean mRunning;
    private volatile boolean mActive;
    private volatile String mLastError;

    public ManagedRecordingTranscriptionService(ManagedRecordingCatalog catalog, RecordPreference preferences)
    {
        this(catalog, preferences, () -> false);
    }

    public ManagedRecordingTranscriptionService(ManagedRecordingCatalog catalog, RecordPreference preferences,
                                                BooleanSupplier recordingBusy)
    {
        this(catalog, () -> new Settings(preferences.isTranscriptionEnabled(), preferences.getTranscriptionUrl(),
            preferences.getTranscriptionModel(), preferences.getTranscriptionApiKey(),
            preferences.getTranscriptionMinimumDurationMs()),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), IDLE_DELAY, FAILURE_DELAY, recordingBusy);
    }

    ManagedRecordingTranscriptionService(ManagedRecordingCatalog catalog, Supplier<Settings> settings,
                                         HttpClient client, Duration idleDelay, Duration failureDelay,
                                         BooleanSupplier recordingBusy)
    {
        mCatalog = Objects.requireNonNull(catalog);
        mSettings = Objects.requireNonNull(settings);
        mRecordingBusy = Objects.requireNonNull(recordingBusy);
        mClient = Objects.requireNonNull(client);
        mIdleDelay = Objects.requireNonNull(idleDelay);
        mFailureDelay = Objects.requireNonNull(failureDelay);
        mWorker = new Thread(this::workLoop, "managed-recordings-transcription");
        mWorker.setDaemon(true);
        mWorker.setPriority(Thread.MIN_PRIORITY);
    }

    public synchronized void start()
    {
        if(!mRunning && mWorker.getState() == Thread.State.NEW)
        {
            mRunning = true;
            mWorker.start();
        }
    }

    /** Non-sensitive operational state for the recording administrator. */
    public Map<String,Object> status()
    {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("active", mActive);
        result.put("last_error", mLastError);
        return result;
    }

    private void workLoop()
    {
        long afterId = 0L;
        while(mRunning)
        {
            try
            {
                Settings settings = mSettings.get();
                if(settings == null || !settings.enabled())
                {
                    mLastError = null;
                    pause(mIdleDelay);
                    continue;
                }
                if(!settings.valid())
                {
                    mLastError = "Transcription settings are incomplete or invalid";
                    pause(mIdleDelay);
                    continue;
                }
                if(mRecordingBusy.getAsBoolean() || mCatalog.pendingRecordingWrites() > 0)
                {
                    pause(Duration.ofSeconds(1));
                    continue;
                }

                long callId = mCatalog.nextPendingTranscription(afterId, settings.minimumDurationMs());
                if(callId == 0L)
                {
                    afterId = 0L;
                    pause(mIdleDelay);
                    continue;
                }
                afterId = callId;

                Path audio;
                try
                {
                    audio = mCatalog.audioPath(callId);
                }
                catch(IOException exception)
                {
                    awaitResult(mCatalog.failTranscription(callId));
                    continue;
                }
                if(audio == null)
                {
                    // A retention/delete race, or a damaged indexed file, must not pin the entire backlog.
                    awaitResult(mCatalog.failTranscription(callId));
                    continue;
                }

                mActive = true;
                try
                {
                    String transcript = transcribe(audio, settings);
                    if(awaitResult(mCatalog.storeTranscript(callId, transcript, System.currentTimeMillis())))
                    {
                        mLastError = null;
                    }
                }
                catch(TranscriptionFailure exception)
                {
                    mActive = false;
                    mLastError = exception.getMessage();
                    if(exception.permanent())
                    {
                        awaitResult(mCatalog.failTranscription(callId));
                    }
                    pause(mFailureDelay);
                }
                catch(IOException exception)
                {
                    mActive = false;
                    // Network and provider errors remain pending. Never log the configured URL, token, or response.
                    mLastError = "Transcription server could not be reached";
                    pause(mFailureDelay);
                }
                finally
                {
                    mActive = false;
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch(SQLException exception)
            {
                mLastError = "Recording catalog is unavailable";
                mLog.warn("Managed transcription catalog operation failed");
                if(!pauseUnlessInterrupted(mFailureDelay))
                {
                    break;
                }
            }
            catch(ExecutionException | RuntimeException exception)
            {
                mLastError = "Transcription worker could not finish a call";
                mLog.warn("Managed transcription worker failed");
                if(!pauseUnlessInterrupted(mFailureDelay))
                {
                    break;
                }
            }
        }
        mActive = false;
    }

    private String transcribe(Path audio, Settings settings) throws IOException, InterruptedException,
        TranscriptionFailure
    {
        RadioResolveBuilder multipart = new RadioResolveBuilder()
            .addPart("model", settings.model())
            .addPart("response_format", "json")
            .addFile("file", audio, "call.mp3");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.url()))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "multipart/form-data; boundary=" + multipart.getBoundary())
            .POST(multipart.build());
        if(!settings.apiKey().isBlank())
        {
            request.header("Authorization", "Bearer " + settings.apiKey());
        }

        HttpResponse<InputStream> response = mClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream body = response.body())
        {
            int status = response.statusCode();
            if(status < 200 || status >= 300)
            {
                // 413 describes this file; other errors may be fixed by changing provider settings or waiting.
                throw new TranscriptionFailure("Transcription server returned HTTP " + status, status == 413);
            }
            byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if(bytes.length > MAX_RESPONSE_BYTES)
            {
                throw new TranscriptionFailure("Transcription response is too large", false);
            }
            JsonNode result;
            try
            {
                result = MAPPER.readTree(bytes);
            }
            catch(IOException exception)
            {
                throw new TranscriptionFailure("Transcription server returned invalid JSON", false);
            }
            JsonNode text = result != null ? result.get("text") : null;
            if(text == null || !text.isTextual() || text.textValue().length() > MAX_TRANSCRIPT_CHARACTERS)
            {
                throw new TranscriptionFailure("Transcription server returned invalid text", false);
            }
            return text.textValue();
        }
    }

    private static boolean awaitResult(CompletableFuture<Boolean> result)
        throws InterruptedException, ExecutionException
    {
        return result.get();
    }

    private static void pause(Duration delay) throws InterruptedException
    {
        Thread.sleep(delay);
    }

    private static boolean pauseUnlessInterrupted(Duration delay)
    {
        try
        {
            pause(delay);
            return true;
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close()
    {
        mRunning = false;
        mWorker.interrupt();
        if(Thread.currentThread() != mWorker && mWorker.isAlive())
        {
            try
            {
                mWorker.join(5_000L);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    record Settings(boolean enabled, String url, String model, String apiKey, long minimumDurationMs)
    {
        boolean valid()
        {
            if(url == null || model == null || apiKey == null ||
                minimumDurationMs < RecordPreference.DEFAULT_TRANSCRIPTION_MIN_DURATION_MS ||
                minimumDurationMs > RecordPreference.MAX_TRANSCRIPTION_MIN_DURATION_MS ||
                model.isBlank() || model.indexOf('\r') >= 0 || model.indexOf('\n') >= 0 ||
                apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0)
            {
                return false;
            }
            try
            {
                URI uri = URI.create(url);
                return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) &&
                    uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null &&
                    uri.getRawFragment() == null;
            }
            catch(IllegalArgumentException exception)
            {
                return false;
            }
        }

        @Override
        public String toString()
        {
            return "Settings[enabled=" + enabled + ", minimumDurationMs=" + minimumDurationMs + "]";
        }
    }

    private static final class TranscriptionFailure extends Exception
    {
        private final boolean mPermanent;

        private TranscriptionFailure(String message, boolean permanent)
        {
            super(message);
            mPermanent = permanent;
        }

        private boolean permanent()
        {
            return mPermanent;
        }
    }
}
