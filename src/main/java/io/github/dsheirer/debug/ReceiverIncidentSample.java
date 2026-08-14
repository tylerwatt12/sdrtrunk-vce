/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.debug;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One compact projection of the already-collected debug telemetry. */
record ReceiverIncidentSample(long sequence, long observedAtMs, String bootId, String telemetryState,
                              Double processCpuLoad, long heapUsedBytes, long heapCommittedBytes,
                              long heapMaximumBytes, long gcPauseDeltaMs, int threadCount, int deadlockedThreadCount,
                              List<TunerSample> tuners, int activeControlChannels, int staleControlChannels,
                              int activeNeverDecodedControlChannels, Double worstControlHealthPercent,
                              long oldestValidDecodeAgeMs)
{
    static ReceiverIncidentSample from(JsonNode root, String tunerPseudonymSalt)
    {
        JsonNode process = root.path("process");
        List<TunerSample> tuners = new ArrayList<>();

        for(JsonNode tuner: root.path("tuners").path("rows"))
        {
            tuners.add(TunerSample.from(tuner, tunerPseudonymSalt));
        }

        long gcPauseDelta = 0L;

        for(JsonNode collector: process.path("garbage_collectors"))
        {
            gcPauseDelta += nonNegativeLong(collector, "collection_time_since_previous_sample_ms");
        }

        int activeControls = 0;
        int staleControls = 0;
        int neverDecodedControls = 0;
        Double worstHealth = null;
        long oldestValidAge = 0L;

        for(JsonNode control: root.path("control_channels").path("rows"))
        {
            if(control.path("active").asBoolean(false))
            {
                activeControls++;
                JsonNode lastValidAge = control.path("last_valid_decode_age_ms");
                long age = lastValidAge.isNumber() ? Math.max(0L, lastValidAge.asLong()) : 0L;
                oldestValidAge = Math.max(oldestValidAge, age);

                if(!lastValidAge.isNumber())
                {
                    neverDecodedControls++;
                }
                else if(age >= ReceiverIncidentRecorder.STALE_CONTROL_MILLISECONDS)
                {
                    staleControls++;
                }

                if(control.path("decode_health_percent").isNumber())
                {
                    double health = control.path("decode_health_percent").asDouble();
                    worstHealth = worstHealth == null ? health : Math.min(worstHealth, health);
                }
            }
        }

        return new ReceiverIncidentSample(nonNegativeLong(root, "sequence"),
            nonNegativeLong(root, "observed_at_ms"), textOrNull(root.path("telemetry"), "boot_id"),
            textOrNull(root.path("telemetry"), "state"), nullableDouble(process, "process_cpu_load"),
            nonNegativeLong(process, "heap_used_bytes"), nonNegativeLong(process, "heap_committed_bytes"),
            nonNegativeLong(process, "heap_max_bytes"), gcPauseDelta, process.path("thread_count").asInt(0),
            process.path("deadlocked_thread_count").asInt(0), List.copyOf(tuners), activeControls, staleControls,
            neverDecodedControls, worstHealth, oldestValidAge);
    }

    long totalRawDroppedBuffers()
    {
        return tuners.stream().mapToLong(TunerSample::rawDroppedBuffers).sum();
    }

    long totalRawDroppedMs()
    {
        return tuners.stream().mapToLong(TunerSample::rawDroppedMs).sum();
    }

    long totalDownstreamDropped()
    {
        return tuners.stream().mapToLong(TunerSample::downstreamDropped).sum();
    }

    long totalRawReceivedBuffers()
    {
        return tuners.stream().mapToLong(TunerSample::rawReceivedBuffers).sum();
    }

    long totalRawProcessedBuffers()
    {
        return tuners.stream().mapToLong(TunerSample::rawProcessedBuffers).sum();
    }

    boolean hasTunerError()
    {
        return tuners.stream().anyMatch(tuner -> "error".equals(tuner.status()));
    }

    boolean rawAboveThreshold()
    {
        return tuners.stream().anyMatch(TunerSample::rawAboveThreshold);
    }

    Map<String,Object> toMap()
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("sequence", sequence);
        value.put("observed_at_ms", observedAtMs);
        value.put("observed_at", observedAtMs > 0 ? Instant.ofEpochMilli(observedAtMs).toString() : null);
        value.put("boot_id", bootId);
        value.put("telemetry_state", telemetryState);
        value.put("process_cpu_load", processCpuLoad);
        value.put("heap_used_bytes", heapUsedBytes);
        value.put("heap_committed_bytes", heapCommittedBytes);
        value.put("heap_max_bytes", heapMaximumBytes);
        value.put("gc_pause_delta_ms", gcPauseDeltaMs);
        value.put("thread_count", threadCount);
        value.put("deadlocked_thread_count", deadlockedThreadCount);
        value.put("active_control_channels", activeControlChannels);
        value.put("stale_control_channels", staleControlChannels);
        value.put("active_never_decoded_control_channels", activeNeverDecodedControlChannels);
        value.put("worst_control_health_percent", worstControlHealthPercent);
        value.put("oldest_valid_decode_age_ms", oldestValidDecodeAgeMs);
        value.put("tuners", tuners.stream().map(TunerSample::toMap).toList());
        return value;
    }

    private static long nonNegativeLong(JsonNode node, String field)
    {
        JsonNode value = node.path(field);
        return value.isNumber() ? Math.max(0L, value.asLong()) : 0L;
    }

    private static Double nullableDouble(JsonNode node, String field)
    {
        JsonNode value = node.path(field);
        return value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
    }

    private static String textOrNull(JsonNode node, String field)
    {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    record TunerSample(String id, String status, long centerFrequencyHz, long sampleRateHz, boolean rawRunning,
                       long rawLimitMs, long rawWaitingMs, long rawInFlightMs, long rawReceivedBuffers,
                       long rawProcessedBuffers, long rawDroppedBuffers, long rawDroppedMs, long lastIngressAgeMs,
                       long lastCompletionAgeMs, int ifftWaiting, int ifftLimit, long ifftDropped,
                       long ifftDiscarded, int channelOutputCount, int maximumOutputOutstanding,
                       long outputDropped, long outputDiscarded, long oldestActiveCallbackAgeMs)
    {
        static TunerSample from(JsonNode tuner, String pseudonymSalt)
        {
            JsonNode raw = tuner.path("raw_input");
            JsonNode ifft = tuner.path("ifft");
            JsonNode outputs = tuner.path("channel_outputs");
            int maxOutstanding = 0;
            long outputDropped = 0L;
            long outputDiscarded = 0L;
            long oldestCallback = 0L;

            for(JsonNode row: outputs.path("rows"))
            {
                JsonNode output = row.path("output");
                maxOutstanding = Math.max(maxOutstanding, output.path("outstanding").asInt(0));
                outputDropped += nonNegativeLong(output, "dropped");
                outputDiscarded += nonNegativeLong(output, "discarded");
                oldestCallback = Math.max(oldestCallback, nonNegativeLong(output, "callback_active_age_ms"));
            }

            JsonNode hidden = outputs.path("hidden_summary");
            maxOutstanding = Math.max(maxOutstanding, hidden.path("maximum_outstanding").asInt(0));
            outputDropped += nonNegativeLong(hidden, "dropped");
            outputDiscarded += nonNegativeLong(hidden, "discarded");
            oldestCallback = Math.max(oldestCallback, nonNegativeLong(hidden, "oldest_active_age_ms"));

            return new TunerSample(pseudonym(pseudonymSalt, textOrNull(tuner, "id")),
                textOrNull(tuner, "status"),
                nonNegativeLong(tuner, "center_frequency_hz"), nonNegativeLong(tuner, "sample_rate_hz"),
                raw.path("running").asBoolean(false), nonNegativeLong(raw, "limit_ms"),
                nonNegativeLong(raw, "waiting_ms"), nonNegativeLong(raw, "in_flight_ms"),
                nonNegativeLong(raw, "received_buffers"), nonNegativeLong(raw, "processed_buffers"),
                nonNegativeLong(raw, "dropped_buffers"), nonNegativeLong(raw, "dropped_ms"),
                nonNegativeLong(raw, "last_ingress_age_ms"), nonNegativeLong(raw, "last_completion_age_ms"),
                ifft.path("waiting").asInt(0), ifft.path("limit").asInt(0), nonNegativeLong(ifft, "dropped"),
                nonNegativeLong(ifft, "discarded"), outputs.path("total_count").asInt(0), maxOutstanding,
                outputDropped, outputDiscarded, oldestCallback);
        }

        private static String pseudonym(String salt, String rawId)
        {
            try
            {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update((salt != null ? salt : "").getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0);
                digest.update((rawId != null ? rawId : "unavailable").getBytes(StandardCharsets.UTF_8));
                byte[] hash = digest.digest();
                return "tuner-" + HexFormat.of().formatHex(hash, 0, 8);
            }
            catch(NoSuchAlgorithmException e)
            {
                //SHA-256 is mandatory in every Java runtime.  Keep the fallback opaque if a nonconforming runtime is
                //ever encountered, and never return the raw hardware ID or configured recording path.
                return "tuner-" + Integer.toUnsignedString(Objects.hash(salt, rawId), 36);
            }
        }

        boolean rawAboveThreshold()
        {
            return rawRunning && rawLimitMs > 0L && rawWaitingMs * 100L >= rawLimitMs * 75L;
        }

        long downstreamDropped()
        {
            return ifftDropped + outputDropped;
        }

        long downstreamDiscarded()
        {
            return ifftDiscarded + outputDiscarded;
        }

        Map<String,Object> toMap()
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("status", status);
            value.put("center_frequency_hz", centerFrequencyHz);
            value.put("sample_rate_hz", sampleRateHz);
            value.put("raw_running", rawRunning);
            value.put("raw_limit_ms", rawLimitMs);
            value.put("raw_waiting_ms", rawWaitingMs);
            value.put("raw_in_flight_ms", rawInFlightMs);
            value.put("raw_received_buffers", rawReceivedBuffers);
            value.put("raw_processed_buffers", rawProcessedBuffers);
            value.put("raw_dropped_buffers", rawDroppedBuffers);
            value.put("raw_dropped_ms", rawDroppedMs);
            value.put("last_ingress_age_ms", lastIngressAgeMs);
            value.put("last_completion_age_ms", lastCompletionAgeMs);
            value.put("ifft_waiting", ifftWaiting);
            value.put("ifft_limit", ifftLimit);
            value.put("ifft_dropped", ifftDropped);
            value.put("ifft_discarded", ifftDiscarded);
            value.put("channel_output_count", channelOutputCount);
            value.put("maximum_output_outstanding", maximumOutputOutstanding);
            value.put("output_dropped", outputDropped);
            value.put("output_discarded", outputDiscarded);
            value.put("oldest_active_callback_age_ms", oldestActiveCallbackAgeMs);
            return value;
        }
    }
}
