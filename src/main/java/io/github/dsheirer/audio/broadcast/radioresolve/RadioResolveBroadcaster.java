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

package io.github.dsheirer.audio.broadcast.radioresolve;

import com.google.common.net.HttpHeaders;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.configuration.radioreference.RadioReferenceDecoder;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.ConfigurationLongIdentifier;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataListener;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.util.ThreadPool;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RadioResolve completed-call and site-metadata publisher.
 */
public class RadioResolveBroadcaster extends AbstractAudioBroadcaster<RadioResolveConfiguration>
    implements SiteMetadataListener
{
    private static final Logger mLog = LoggerFactory.getLogger(RadioResolveBroadcaster.class);
    public static final String UPLOAD_PATH = "/api/node/upload-call";
    public static final String RF_STATE_PATH = "/api/node/rf-state";
    public static final String TEST_PATH = "/api/node/test";
    public static final String AGENT_VERSION = "sdrtrunk-radioresolve";
    private static final String MULTIPART_FORM_DATA = "multipart/form-data";
    private static final long METADATA_MINIMUM_SEND_INTERVAL_MILLISECONDS = TimeUnit.SECONDS.toMillis(30);
    private static final long MISSING_GUID_WARNING_INTERVAL_MILLISECONDS = TimeUnit.SECONDS.toMillis(60);
    private static final long[] RETRY_BACKOFF_MS = {5000, 15000, 30000, 60000, 120000};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Object mQueueLock = new Object();
    private final Deque<PendingUpload> mAudioRecordingQueue = new ArrayDeque<>();
    private final Map<String,MetadataState> mMetadataStateByGuid = new HashMap<>();
    private final AtomicInteger mInFlightUploads = new AtomicInteger();
    private ScheduledFuture<?> mAudioRecordingProcessorFuture;
    private HttpClient mHttpClient;
    private AliasModel mAliasModel;
    private volatile boolean mRunning;
    private volatile boolean mServerReachable;
    private long mLastConnectionAttempt;
    private long mConnectionAttemptInterval = 5000;
    private long mLastMissingGuidWarningTimestamp;
    private int mMissingGuidSkipCount;

    public RadioResolveBroadcaster(RadioResolveConfiguration config, InputAudioFormat inputAudioFormat,
                                   MP3Setting mp3Setting, AliasModel aliasModel)
    {
        super(config);
        mAliasModel = aliasModel;
        mHttpClient = createHttpClient(config);
    }

    @Override
    public void start()
    {
        mRunning = true;
        setBroadcastState(BroadcastState.CONNECTING);
        mServerReachable = testConnection(getBroadcastConfiguration());
        setBroadcastState(mServerReachable ? BroadcastState.CONNECTED : BroadcastState.ERROR);
        mLastConnectionAttempt = System.currentTimeMillis();

        if(mAudioRecordingProcessorFuture == null)
        {
            mAudioRecordingProcessorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(new AudioRecordingProcessor(),
                0, 500, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop()
    {
        mRunning = false;

        if(mAudioRecordingProcessorFuture != null)
        {
            mAudioRecordingProcessorFuture.cancel(true);
            mAudioRecordingProcessorFuture = null;
        }

        dispose();
        setBroadcastState(BroadcastState.DISCONNECTED);
    }

    @Override
    public void dispose()
    {
        PendingUpload pendingUpload;

        synchronized(mQueueLock)
        {
            pendingUpload = mAudioRecordingQueue.poll();
        }

        while(pendingUpload != null)
        {
            pendingUpload.getAudioRecording().removePendingReplay();

            synchronized(mQueueLock)
            {
                pendingUpload = mAudioRecordingQueue.poll();
            }
        }
    }

    @Override
    public int getAudioQueueSize()
    {
        synchronized(mQueueLock)
        {
            return mAudioRecordingQueue.size();
        }
    }

    @Override
    public void receive(AudioRecording audioRecording)
    {
        if(audioRecording == null)
        {
            return;
        }

        if(!getBroadcastConfiguration().isCallsAndMetadata())
        {
            audioRecording.removePendingReplay();
            return;
        }

        synchronized(mQueueLock)
        {
            mAudioRecordingQueue.offer(new PendingUpload(audioRecording));
        }

        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
    }

    @Override
    public void receiveSiteMetadata(SiteMetadataEvent event)
    {
        if(event == null || !event.isUseful())
        {
            return;
        }

        String guid = event.channel().getRadresGuid();

        if(guid == null || guid.isBlank())
        {
            return;
        }

        P25NetworkConfigurationSnapshot snapshot = event.snapshot();
        String hash = hash(snapshot);
        long now = System.currentTimeMillis();
        RadioResolveMetadataReadiness readiness = RadioResolveMetadataReadiness.evaluate(guid, snapshot);
        publishMetadataStatus(event, hash, now, RadioResolveMetadataStatusEvent.Stage.KNOWN, null, null, null);

        if(!readiness.ready())
        {
            return;
        }

        if(!connected())
        {
            return;
        }

        synchronized(mMetadataStateByGuid)
        {
            MetadataState state = mMetadataStateByGuid.computeIfAbsent(guid, ignored -> new MetadataState());
            boolean changed = !hash.equals(state.mLastSuccessfulHash);
            boolean heartbeatDue = now - state.mLastSuccessfulEpochMilliseconds >=
                METADATA_MINIMUM_SEND_INTERVAL_MILLISECONDS;

            if(!changed && !heartbeatDue)
            {
                return;
            }

            if(now - state.mLastAttemptEpochMilliseconds < METADATA_MINIMUM_SEND_INTERVAL_MILLISECONDS)
            {
                return;
            }

            state.mLastAttemptEpochMilliseconds = now;
        }

        ThreadPool.CACHED.execute(() -> sendSiteMetadata(event, hash, now));
    }

    private void sendSiteMetadata(SiteMetadataEvent event, String hash, long observedAt)
    {
        try
        {
            JsonObject payload = createSiteMetadataPayload(event, hash, getBroadcastConfiguration(), observedAt);
            int payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8).length;
            publishMetadataStatus(event, hash, observedAt, RadioResolveMetadataStatusEvent.Stage.ATTEMPT, null,
                payloadBytes, null);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(createUri(getBroadcastConfiguration().getHost(), RF_STATE_PATH))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getBroadcastConfiguration().getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.USER_AGENT, "sdrtrunk")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = mHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if(response.statusCode() >= 200 && response.statusCode() < 300)
            {
                markMetadataSent(event.channel().getRadresGuid(), hash, observedAt);
                setBroadcastState(BroadcastState.CONNECTED);
                publishMetadataStatus(event, hash, observedAt, RadioResolveMetadataStatusEvent.Stage.SUCCESS,
                    response.statusCode(), payloadBytes, "Accepted");
            }
            else if(response.statusCode() == 401 || response.statusCode() == 403)
            {
                setBroadcastState(BroadcastState.INVALID_CREDENTIALS);
                mLog.warn("RadioResolve site metadata rejected: invalid API key or access denied");
                publishMetadataStatus(event, hash, observedAt, RadioResolveMetadataStatusEvent.Stage.REJECTED,
                    response.statusCode(), payloadBytes, "Invalid API key or access denied");
            }
            else
            {
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                mLog.warn("RadioResolve site metadata rejected: HTTP {}", response.statusCode());
                publishMetadataStatus(event, hash, observedAt, RadioResolveMetadataStatusEvent.Stage.REJECTED,
                    response.statusCode(), payloadBytes, responseBodySummary(response.body()));
            }
        }
        catch(Exception e)
        {
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            mLog.warn("RadioResolve site metadata failed: {}", safeMessage(e));
            publishMetadataStatus(event, hash, observedAt, RadioResolveMetadataStatusEvent.Stage.FAILED, null, null,
                safeMessage(e));
        }
    }

    private void publishMetadataStatus(SiteMetadataEvent event, String hash, long timestamp,
                                       RadioResolveMetadataStatusEvent.Stage stage, Integer httpStatus,
                                       Integer payloadBytes, String resultMessage)
    {
        if(event == null)
        {
            return;
        }

        P25NetworkConfigurationSnapshot snapshot = event.snapshot();
        RadioResolveMetadataReadiness readiness =
            RadioResolveMetadataReadiness.evaluate(event.channel().getRadresGuid(), snapshot);

        MyEventBus.getGlobalEventBus().post(new RadioResolveMetadataStatusEvent(stage, timestamp,
            event.channel().getRadresGuid(), event.channel().getName(), event.channel().getAliasListName(),
            getNodeName(getBroadcastConfiguration()), getNodeTimezone(getBroadcastConfiguration()),
            getBroadcastConfiguration().getHost(), snapshot, hash, readiness.ready(), readiness.message(),
            httpStatus, payloadBytes, resultMessage));
    }

    private static String responseBodySummary(String body)
    {
        if(body == null || body.isBlank())
        {
            return "";
        }

        String singleLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() > 180 ? singleLine.substring(0, 180) : singleLine;
    }

    private void markMetadataSent(String guid, String hash, long observedAt)
    {
        synchronized(mMetadataStateByGuid)
        {
            MetadataState state = mMetadataStateByGuid.computeIfAbsent(guid, ignored -> new MetadataState());
            state.mLastSuccessfulHash = hash;
            state.mLastSuccessfulEpochMilliseconds = observedAt;
        }
    }

    private boolean connected()
    {
        if(getBroadcastState() == BroadcastState.INVALID_CREDENTIALS)
        {
            return false;
        }

        if(getBroadcastState() != BroadcastState.CONNECTED &&
            (System.currentTimeMillis() - mLastConnectionAttempt > mConnectionAttemptInterval))
        {
            setBroadcastState(BroadcastState.CONNECTING);
            mServerReachable = testConnection(getBroadcastConfiguration());
            setBroadcastState(mServerReachable ? BroadcastState.CONNECTED : BroadcastState.ERROR);
            mLastConnectionAttempt = System.currentTimeMillis();
        }

        return mServerReachable;
    }

    private boolean isValid(AudioRecording audioRecording)
    {
        return audioRecording != null && System.currentTimeMillis() - audioRecording.getStartTime() <=
            getBroadcastConfiguration().getMaximumRecordingAge();
    }

    private void processRecordingQueue()
    {
        ageOffInvalidRecordings();
        int maximumConcurrentUploads = getBroadcastConfiguration().getConcurrentUploads();

        while(connected() && mInFlightUploads.get() < maximumConcurrentUploads)
        {
            PendingUpload pendingUpload = getNextReadyUpload();

            if(pendingUpload == null)
            {
                return;
            }

            AudioRecording audioRecording = pendingUpload.getAudioRecording();
            String guid = getConfigurationIdentifier(audioRecording, Form.RADRES_GUID);

            if(guid == null || guid.isBlank())
            {
                warnMissingGuidSkipped(audioRecording);
                audioRecording.removePendingReplay();
                incrementAgedOffAudioCount();
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                continue;
            }

            if(!isValid(audioRecording) || audioRecording.getRecordingLength() <= 0)
            {
                audioRecording.removePendingReplay();
                continue;
            }

            try
            {
                HttpRequest request = createUploadRequest(getBroadcastConfiguration(), audioRecording, mAliasModel);
                mInFlightUploads.incrementAndGet();
                mHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, throwable) -> {
                        try
                        {
                            handleUploadResponse(pendingUpload, response, throwable);
                        }
                        finally
                        {
                            mInFlightUploads.decrementAndGet();
                        }
                    });
            }
            catch(FileNotFoundException fnfe)
            {
                mLog.error("RadioResolve upload file not found [{}]", audioRecording.getPath());
                incrementErrorAudioCount();
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
                audioRecording.removePendingReplay();
            }
            catch(Exception e)
            {
                incrementErrorAudioCount();
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
                retryOrRemove(pendingUpload, safeMessage(e));
            }
        }
    }

    private void warnMissingGuidSkipped(AudioRecording audioRecording)
    {
        mMissingGuidSkipCount++;
        long now = System.currentTimeMillis();

        if(now - mLastMissingGuidWarningTimestamp >= MISSING_GUID_WARNING_INTERVAL_MILLISECONDS)
        {
            mLog.warn("RadioResolve skipped {} call upload(s) missing radres_guid. Last recording identifiers: {}",
                mMissingGuidSkipCount, describeIdentifiers(audioRecording));
            mMissingGuidSkipCount = 0;
            mLastMissingGuidWarningTimestamp = now;
        }
    }

    private String describeIdentifiers(AudioRecording audioRecording)
    {
        if(audioRecording == null || !audioRecording.hasIdentifierCollection())
        {
            return "none";
        }

        return audioRecording.getIdentifierCollection().getIdentifiers().toString();
    }

    private void handleUploadResponse(PendingUpload pendingUpload, HttpResponse<String> response, Throwable throwable)
    {
        if(throwable != null)
        {
            incrementErrorAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
            retryOrRemove(pendingUpload, "temporary upload failure");
            return;
        }

        int statusCode = response.statusCode();

        if(statusCode >= 200 && statusCode < 300)
        {
            setBroadcastState(BroadcastState.CONNECTED);
            incrementStreamedAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
            pendingUpload.getAudioRecording().removePendingReplay();
        }
        else if(statusCode == 401 || statusCode == 403)
        {
            setBroadcastState(BroadcastState.INVALID_CREDENTIALS);
            incrementErrorAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
            pendingUpload.getAudioRecording().removePendingReplay();
            mLog.error("RadioResolve upload rejected: invalid API key or access denied");
        }
        else if(isRetryableStatus(statusCode))
        {
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            incrementErrorAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
            retryOrRemove(pendingUpload, "HTTP " + statusCode);
        }
        else
        {
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            incrementErrorAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
            pendingUpload.getAudioRecording().removePendingReplay();
            mLog.error("RadioResolve upload failed: HTTP {}", statusCode);
        }
    }

    private boolean isRetryableStatus(int statusCode)
    {
        return statusCode == 408 || statusCode == 429 || statusCode == 500 || statusCode == 502 ||
            statusCode == 503 || statusCode == 504;
    }

    private PendingUpload getNextReadyUpload()
    {
        PendingUpload pendingUpload = null;
        long now = System.currentTimeMillis();

        synchronized(mQueueLock)
        {
            int size = mAudioRecordingQueue.size();

            for(int x = 0; x < size; x++)
            {
                PendingUpload candidate = mAudioRecordingQueue.poll();

                if(candidate == null)
                {
                    break;
                }

                if(pendingUpload == null && candidate.getNextAttemptTime() <= now)
                {
                    pendingUpload = candidate;
                }
                else
                {
                    mAudioRecordingQueue.offer(candidate);
                }
            }
        }

        if(pendingUpload != null)
        {
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
        }

        return pendingUpload;
    }

    private void retryOrRemove(PendingUpload pendingUpload, String reason)
    {
        if(mRunning && isValid(pendingUpload.getAudioRecording()))
        {
            pendingUpload.retry();

            synchronized(mQueueLock)
            {
                mAudioRecordingQueue.offer(pendingUpload);
            }

            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
        }
        else
        {
            pendingUpload.getAudioRecording().removePendingReplay();
            incrementAgedOffAudioCount();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
            mLog.info("RadioResolve upload aged off [{}]", reason);
        }
    }

    private void ageOffInvalidRecordings()
    {
        boolean changed = false;

        synchronized(mQueueLock)
        {
            int size = mAudioRecordingQueue.size();

            for(int x = 0; x < size; x++)
            {
                PendingUpload pendingUpload = mAudioRecordingQueue.poll();

                if(pendingUpload == null)
                {
                    break;
                }

                if(isValid(pendingUpload.getAudioRecording()))
                {
                    mAudioRecordingQueue.offer(pendingUpload);
                }
                else
                {
                    pendingUpload.getAudioRecording().removePendingReplay();
                    incrementAgedOffAudioCount();
                    changed = true;
                }
            }
        }

        if(changed)
        {
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
        }
    }

    static HttpRequest createUploadRequest(RadioResolveConfiguration configuration, AudioRecording audioRecording,
                                           AliasModel aliasModel) throws IOException
    {
        Path path = audioRecording.getPath();

        if(!Files.exists(path))
        {
            throw new FileNotFoundException(path.toString());
        }

        String filename = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        JsonObject payload = createCallPayload(configuration, audioRecording, aliasModel);
        RadioResolveBuilder bodyBuilder = new RadioResolveBuilder();
        bodyBuilder.addFile(path, filename);

        for(String key: payload.keySet())
        {
            if(!payload.get(key).isJsonNull())
            {
                bodyBuilder.addPart(key, payload.get(key).getAsString());
            }
        }

        return HttpRequest.newBuilder()
            .uri(createUri(configuration.getHost(), UPLOAD_PATH))
            .version(HttpClient.Version.HTTP_1_1)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.getApiKey())
            .header(HttpHeaders.CONTENT_TYPE, MULTIPART_FORM_DATA + "; boundary=" + bodyBuilder.getBoundary())
            .header(HttpHeaders.USER_AGENT, "sdrtrunk")
            .POST(bodyBuilder.build())
            .build();
    }

    static JsonObject createCallPayload(RadioResolveConfiguration configuration, AudioRecording audioRecording,
                                        AliasModel aliasModel)
    {
        Path path = audioRecording.getPath();
        String filename = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        JsonObject root = new JsonObject();
        root.addProperty("call_time_ms", audioRecording.getStartTime());
        root.addProperty("duration_sec", formatSeconds(audioRecording.getRecordingLength()));
        root.addProperty("target_id", getTo(audioRecording, aliasModel));
        root.addProperty("target_type", getTargetType(audioRecording));
        root.addProperty("source_id", getFrom(audioRecording));
        root.addProperty("frequency_mhz", formatFrequencyMHz(getFrequency(audioRecording)));
        root.addProperty("system_label", getConfigurationIdentifier(audioRecording, Form.SYSTEM));
        root.addProperty("site_label", getConfigurationIdentifier(audioRecording, Form.SITE));
        root.addProperty("radres_guid", getConfigurationIdentifier(audioRecording, Form.RADRES_GUID));
        root.addProperty("p25_nac", getP25NetworkIdentifier(audioRecording, Form.NETWORK_ACCESS_CODE, 3));
        root.addProperty("logical_channel", getDecoderIdentifier(audioRecording, Form.CHANNEL_NAME));
        root.addProperty("audio_protocol", getConfigurationIdentifier(audioRecording, Form.DECODER_TYPE));
        root.addProperty("talkgroup_label", getTalkgroupLabel(audioRecording, aliasModel));
        root.addProperty("talkgroup_group", getTalkgroupGroup(audioRecording, aliasModel));
        root.addProperty("talker_alias", getTalkerAlias(audioRecording));
        addEncryption(root, audioRecording);
        root.addProperty("node_name", getNodeName(configuration));
        root.addProperty("node_timezone", getNodeTimezone(configuration));
        root.addProperty("agent_version", AGENT_VERSION);
        root.addProperty("original_filename", filename);
        return root;
    }

    static JsonObject createSiteMetadataPayload(SiteMetadataEvent event, String hash,
                                                RadioResolveConfiguration configuration, long observedAt)
    {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("agentVersion", AGENT_VERSION);
        root.addProperty("observedAtEpochMilliseconds", observedAt);
        root.addProperty("nodeName", getNodeName(configuration));
        root.addProperty("timezone", getNodeTimezone(configuration));
        root.addProperty("radresGuid", event.channel().getRadresGuid());
        root.addProperty("decoder", event.snapshot().decoder());
        root.addProperty("summaryHash", hash);

        JsonObject channelObject = new JsonObject();
        channelObject.addProperty("name", event.channel().getName());
        channelObject.addProperty("aliasList", event.channel().getAliasListName());
        root.add("channel", channelObject);
        root.add("network", GSON.toJsonTree(event.snapshot().network()));
        root.add("currentSite", GSON.toJsonTree(event.snapshot().currentSite()));
        root.add("channels", GSON.toJsonTree(event.snapshot().channels()));
        root.add("neighborSites", GSON.toJsonTree(event.snapshot().neighborSites()));
        root.add("frequencyBands", GSON.toJsonTree(event.snapshot().frequencyBands()));
        root.add("patchGroups", GSON.toJsonTree(event.snapshot().patchGroups()));
        return root;
    }

    private static void addEncryption(JsonObject root, AudioRecording audioRecording)
    {
        boolean encrypted = false;
        Integer algorithm = null;
        Integer key = null;

        if(audioRecording.hasIdentifierCollection() &&
            audioRecording.getIdentifierCollection().getEncryptionIdentifier() instanceof EncryptionKeyIdentifier eki)
        {
            EncryptionKey encryptionKey = eki.getValue();
            encrypted = eki.isEncrypted();

            if(encryptionKey != null)
            {
                algorithm = encryptionKey.getAlgorithm();
                key = encryptionKey.getKey();
            }
        }

        root.addProperty("encrypted", encrypted);

        if(algorithm != null)
        {
            root.addProperty("encryption_alg_id", algorithm);
        }

        if(key != null)
        {
            root.addProperty("encryption_key_id", key);
        }
    }

    private static String getFrom(AudioRecording audioRecording)
    {
        if(audioRecording.hasIdentifierCollection())
        {
            for(Identifier identifier: audioRecording.getIdentifierCollection().getIdentifiers(Role.FROM))
            {
                if(identifier instanceof RadioIdentifier radioIdentifier)
                {
                    return radioIdentifier.getValue().toString();
                }
            }
        }

        return "0";
    }

    private static String getTo(AudioRecording audioRecording, AliasModel aliasModel)
    {
        if(!audioRecording.hasIdentifierCollection())
        {
            return "0";
        }

        Identifier identifier = audioRecording.getIdentifierCollection().getToIdentifier();

        if(identifier != null)
        {
            AliasList aliasList = aliasModel != null ? aliasModel.getAliasList(audioRecording.getIdentifierCollection()) : null;

            if(aliasList != null)
            {
                List<Alias> aliases = aliasList.getAliases(identifier);
                Optional<Alias> streamAs = aliases.stream()
                    .filter(alias -> alias.getStreamTalkgroupAlias() != null)
                    .findFirst();

                if(streamAs.isPresent())
                {
                    return String.valueOf(streamAs.get().getStreamTalkgroupAlias().getValue());
                }
            }

            if(identifier instanceof PatchGroupIdentifier patchGroupIdentifier)
            {
                return patchGroupIdentifier.getValue().getPatchGroup().getValue().toString();
            }
            else if(identifier instanceof TalkgroupIdentifier talkgroupIdentifier)
            {
                return String.valueOf(RadioReferenceDecoder.convertToRadioReferenceTalkgroup(
                    talkgroupIdentifier.getValue(), talkgroupIdentifier.getProtocol()));
            }
            else if(identifier instanceof RadioIdentifier radioIdentifier)
            {
                return radioIdentifier.getValue().toString();
            }
        }

        return "0";
    }

    private static String getTargetType(AudioRecording audioRecording)
    {
        if(audioRecording.hasIdentifierCollection())
        {
            Identifier identifier = audioRecording.getIdentifierCollection().getToIdentifier();

            if(identifier instanceof PatchGroupIdentifier)
            {
                return "patch_group";
            }
            else if(identifier instanceof TalkgroupIdentifier)
            {
                return "talkgroup";
            }
            else if(identifier instanceof RadioIdentifier)
            {
                return "radio";
            }
        }

        return null;
    }

    private static String getTalkerAlias(AudioRecording audioRecording)
    {
        if(audioRecording.hasIdentifierCollection())
        {
            for(Identifier identifier: audioRecording.getIdentifierCollection().getIdentifiers(Role.FROM))
            {
                if(identifier instanceof TalkerAliasIdentifier talkerID && talkerID.isValid())
                {
                    return talkerID.getValue();
                }
            }
        }

        return "";
    }

    private static String getTalkgroupLabel(AudioRecording audioRecording, AliasModel aliasModel)
    {
        Alias alias = getFirstTargetAlias(audioRecording, aliasModel);
        return alias != null ? alias.toString() : "";
    }

    private static String getTalkgroupGroup(AudioRecording audioRecording, AliasModel aliasModel)
    {
        Alias alias = getFirstTargetAlias(audioRecording, aliasModel);
        return alias != null ? alias.getGroup() : "";
    }

    private static Alias getFirstTargetAlias(AudioRecording audioRecording, AliasModel aliasModel)
    {
        if(aliasModel == null || !audioRecording.hasIdentifierCollection())
        {
            return null;
        }

        AliasList aliasList = aliasModel.getAliasList(audioRecording.getIdentifierCollection());
        Identifier identifier = audioRecording.getIdentifierCollection().getToIdentifier();

        if(aliasList != null && identifier != null)
        {
            List<Alias> aliases = aliasList.getAliases(identifier);

            if(!aliases.isEmpty())
            {
                return aliases.get(0);
            }
        }

        return null;
    }

    private static Long getFrequency(AudioRecording audioRecording)
    {
        if(audioRecording.hasIdentifierCollection())
        {
            Identifier identifier = audioRecording.getIdentifierCollection().getIdentifier(IdentifierClass.CONFIGURATION,
                Form.CHANNEL_FREQUENCY, Role.ANY);

            if(identifier instanceof ConfigurationLongIdentifier configurationLongIdentifier)
            {
                return configurationLongIdentifier.getValue();
            }
        }

        return null;
    }

    private static String getConfigurationIdentifier(AudioRecording audioRecording, Form form)
    {
        if(audioRecording.hasIdentifierCollection())
        {
            Identifier identifier = audioRecording.getIdentifierCollection()
                .getIdentifier(IdentifierClass.CONFIGURATION, form, Role.ANY);

            if(identifier != null && identifier.getValue() != null)
            {
                return identifier.getValue().toString();
            }
        }

        return null;
    }

    private static String getDecoderIdentifier(AudioRecording audioRecording, Form form)
    {
        if(audioRecording.hasIdentifierCollection())
        {
            for(Identifier identifier: audioRecording.getIdentifierCollection().getIdentifiers(IdentifierClass.DECODER,
                form))
            {
                if(identifier.getValue() != null)
                {
                    return identifier.getValue().toString();
                }
            }
        }

        return null;
    }

    private static String getP25NetworkIdentifier(AudioRecording audioRecording, Form form, int width)
    {
        if(audioRecording.hasIdentifierCollection())
        {
            for(Identifier identifier: audioRecording.getIdentifierCollection().getIdentifiers(form))
            {
                if(identifier.getValue() instanceof Integer integer)
                {
                    return String.format(Locale.US, "%0" + width + "X", integer);
                }
                else if(identifier.getValue() != null)
                {
                    return identifier.getValue().toString();
                }
            }
        }

        return null;
    }

    private static String formatSeconds(long milliseconds)
    {
        return String.format(Locale.US, "%.3f", milliseconds / 1000.0d);
    }

    private static String formatFrequencyMHz(Long frequency)
    {
        if(frequency != null && frequency > 0)
        {
            return String.format(Locale.US, "%.5f", frequency / 1E6d);
        }

        return null;
    }

    private static String getNodeName(RadioResolveConfiguration configuration)
    {
        String nodeName = configuration.getNodeName();
        return nodeName != null && !nodeName.isBlank() ? nodeName : RadioResolveConfiguration.getDefaultNodeName();
    }

    private static String getNodeTimezone(RadioResolveConfiguration configuration)
    {
        String timezone = configuration.getNodeTimezone();
        return timezone != null && !timezone.isBlank() ? timezone : RadioResolveConfiguration.getDefaultNodeTimezone();
    }

    private static String hash(P25NetworkConfigurationSnapshot snapshot)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            P25NetworkConfigurationSnapshot summary = new P25NetworkConfigurationSnapshot(snapshot.decoder(),
                snapshot.network(), snapshot.currentSite(), snapshot.channels(), snapshot.neighborSites(),
                snapshot.frequencyBands(), snapshot.patchGroups(), List.of());
            byte[] hash = digest.digest(GSON.toJson(summary).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();

            for(byte b: hash)
            {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();
        }
        catch(Exception e)
        {
            throw new IllegalStateException("Could not hash RadioResolve site metadata", e);
        }
    }

    private static URI createUri(String host, String path)
    {
        String normalized = host != null ? host.trim() : "";

        while(normalized.endsWith("/"))
        {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return URI.create(normalized + path);
    }

    public static HttpClient createHttpClient(RadioResolveConfiguration configuration)
    {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20));

        if(configuration != null && configuration.isIgnoreCertificateErrors())
        {
            try
            {
                builder.sslContext(createTrustAllSSLContext());
                SSLParameters sslParameters = new SSLParameters();
                sslParameters.setEndpointIdentificationAlgorithm("");
                builder.sslParameters(sslParameters);
            }
            catch(Exception e)
            {
                mLog.error("Unable to configure RadioResolve certificate error bypass [{}]", safeMessage(e));
            }
        }

        return builder.build();
    }

    private static SSLContext createTrustAllSSLContext() throws Exception
    {
        TrustManager[] trustManagers = new TrustManager[] {
            new X509TrustManager()
            {
                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers()
                {
                    return new java.security.cert.X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
                {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                {
                }
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, new java.security.SecureRandom());
        return sslContext;
    }

    private boolean testConnection(RadioResolveConfiguration configuration)
    {
        if(configuration.getApiKey() == null || configuration.getApiKey().isBlank())
        {
            return false;
        }

        try
        {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(createUri(configuration.getHost(), TEST_PATH))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.getApiKey())
                .header(HttpHeaders.USER_AGENT, "sdrtrunk")
                .GET()
                .build();
            HttpResponse<String> response = mHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if(response.statusCode() == 401 || response.statusCode() == 403)
            {
                setBroadcastState(BroadcastState.INVALID_CREDENTIALS);
                return false;
            }

            return response.statusCode() >= 200 && response.statusCode() < 500;
        }
        catch(Exception e)
        {
            mLog.warn("RadioResolve connection test failed: {}", safeMessage(e));
            return false;
        }
    }

    private static String safeMessage(Throwable throwable)
    {
        if(throwable instanceof CompletionException && throwable.getCause() != null)
        {
            throwable = throwable.getCause();
        }

        String message = throwable.getMessage();
        return message != null ? message : throwable.getClass().getSimpleName();
    }

    public class AudioRecordingProcessor implements Runnable
    {
        @Override
        public void run()
        {
            try
            {
                processRecordingQueue();
            }
            catch(Exception e)
            {
                mLog.error("Error processing RadioResolve upload queue", e);
            }
        }
    }

    private static class MetadataState
    {
        private String mLastSuccessfulHash;
        private long mLastSuccessfulEpochMilliseconds;
        private long mLastAttemptEpochMilliseconds;
    }

    private static class PendingUpload
    {
        private final AudioRecording mAudioRecording;
        private int mAttemptCount;
        private long mNextAttemptTime;

        PendingUpload(AudioRecording audioRecording)
        {
            mAudioRecording = audioRecording;
        }

        AudioRecording getAudioRecording()
        {
            return mAudioRecording;
        }

        long getNextAttemptTime()
        {
            return mNextAttemptTime;
        }

        void retry()
        {
            long delay = RETRY_BACKOFF_MS[Math.min(mAttemptCount, RETRY_BACKOFF_MS.length - 1)];
            mAttemptCount++;
            mNextAttemptTime = System.currentTimeMillis() + delay;
        }
    }
}
