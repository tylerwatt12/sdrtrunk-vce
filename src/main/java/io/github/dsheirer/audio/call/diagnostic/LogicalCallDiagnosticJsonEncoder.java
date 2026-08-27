/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit JSONL field whitelist.  Do not replace this encoder with reflective serialization of coordinator records.
 */
final class LogicalCallDiagnosticJsonEncoder implements LogicalCallDiagnosticRecordEncoder
{
    static final String FORMAT = "sdrtrunk-vce-logical-call-diagnostics-v3";
    private static final int MAXIMUM_TEXT_CODE_POINTS = 256;
    private static final String REDACTED_PATH = "[redacted_path]";
    private static final String REDACTED_SECRET = "[redacted_secret]";
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    @Override
    public byte[] encodeSessionHeader(String sessionId, long sessionStartedAtEpochMillis, long segmentNumber,
                                      LogicalCallDiagnosticConfiguration configuration)
    {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(configuration, "configuration cannot be null");
        return encode(generator ->
        {
            generator.writeStartObject();
            generator.writeStringField("record_type", "session_header");
            generator.writeStringField("format", FORMAT);
            generator.writeStringField("session_id", safeText(sessionId));
            generator.writeNumberField("session_started_at_ms", sessionStartedAtEpochMillis);
            generator.writeNumberField("segment_number", segmentNumber);
            generator.writeNumberField("recent_decision_capacity", configuration.recentDecisionCapacity());
            generator.writeNumberField("file_queue_capacity", configuration.queueCapacity());
            generator.writeNumberField("maximum_file_bytes", configuration.maximumFileBytes());
            generator.writeNumberField("maximum_files", configuration.maximumFiles());
            generator.writeNumberField("maximum_record_bytes", configuration.maximumRecordBytes());
            generator.writeBooleanField("contains_audio", false);
            generator.writeBooleanField("contains_voice_fingerprints", false);
            generator.writeBooleanField("contains_encryption_message_indicators", false);
            generator.writeBooleanField("contains_credentials", false);
            generator.writeEndObject();
        });
    }

    @Override
    public byte[] encodeDecision(LogicalCallDiagnosticDecision decision)
    {
        Objects.requireNonNull(decision, "decision cannot be null");
        return encode(generator ->
        {
            generator.writeStartObject();
            generator.writeStringField("record_type", "logical_call_decision");
            generator.writeNumberField("decision_sequence", decision.decisionSequence());
            generator.writeNumberField("decided_at_ms", decision.decidedAtMs());
            writeEnum(generator, "outcome", decision.outcome());
            writeCallIdentity(generator, decision.callIdentity());
            writeOutputPolicy(generator, decision.outputPolicy());
            writeWinner(generator, decision.winner());
            writeCopies(generator, decision);
            writeComparisonSummary(generator, decision.evidence());
            writeEnumArray(generator, "decision_reasons", decision.decisionReasons());
            generator.writeEndObject();
        });
    }

    @Override
    public byte[] encodeOutput(LogicalCallDiagnosticOutputEvent event)
    {
        Objects.requireNonNull(event, "event cannot be null");
        return encode(generator ->
        {
            generator.writeStartObject();
            generator.writeStringField("record_type", "output_confirmation");
            generator.writeStringField("confirmation_scope", "local_submission_only");
            generator.writeNumberField("logical_call_sequence", event.logicalCallSequence());
            generator.writeNumberField("occurred_at_ms", event.occurredAtEpochMillis());
            writeEnum(generator, "output_type", event.outputType());
            generator.writeEndObject();
        });
    }

    private static void writeCallIdentity(JsonGenerator generator, LogicalCallDiagnosticCallIdentity identity)
        throws IOException
    {
        generator.writeObjectFieldStart("call_identity");

        if(identity != null)
        {
            generator.writeNumberField("logical_call_sequence", identity.sessionLogicalCallSequence());
            writeText(generator, "protocol", identity.protocol());
            writeText(generator, "decoder", identity.decoder());
            generator.writeNumberField("start_at_ms", identity.startTimestamp());
            generator.writeNumberField("end_at_ms", identity.endTimestamp());
            generator.writeNumberField("resolved_at_ms", identity.resolvedTimestamp());
            generator.writeNumberField("resolution_wait_ms", identity.resolutionWaitMilliseconds());
            writeText(generator, "destination_value", identity.destinationValue());
            writeText(generator, "destination_alias", identity.destinationAlias());
            writeText(generator, "source_value", identity.sourceValue());
            writeText(generator, "source_alias", identity.sourceAlias());
            writeEnum(generator, "encryption_state", identity.encryptionState());
            writeNumber(generator, "wacn", identity.wacn());
            writeNumber(generator, "system_id", identity.system());
            generator.writeNumberField("durable_alias_list_id", identity.durableAliasListId());
            writeText(generator, "alias_list_name", identity.aliasListName());
            generator.writeNumberField("unique_learned_site_count", identity.uniqueLearnedSiteCount());
        }

        generator.writeEndObject();
    }

    private static void writeOutputPolicy(JsonGenerator generator, LogicalCallDiagnosticOutputPolicy policy)
        throws IOException
    {
        generator.writeObjectFieldStart("output_policy");

        if(policy != null)
        {
            generator.writeBooleanField("record_requested", policy.recordRequested());
            generator.writeArrayFieldStart("stream_route_names");

            for(String routeName: policy.streamRoutingKeys())
            {
                generator.writeString(safeText(routeName));
            }

            generator.writeEndArray();
            generator.writeNumberField("stream_route_count", policy.streamRoutingKeyCount());
            generator.writeBooleanField("browser_offered", policy.browserOffered());
        }

        generator.writeEndObject();
    }

    private static void writeWinner(JsonGenerator generator, LogicalCallDiagnosticWinner winner) throws IOException
    {
        generator.writeObjectFieldStart("winner");

        if(winner != null)
        {
            writeText(generator, "selected_copy_id", winner.winnerLegId());
            writeText(generator, "runner_up_copy_id", winner.runnerUpLegId());
            writeEnum(generator, "decisive_criterion", winner.criterion());
            writeCriterionValue(generator, "winner_value", winner.winnerValue());
            writeCriterionValue(generator, "runner_up_value", winner.runnerUpValue());
        }

        generator.writeEndObject();
    }

    private static void writeCriterionValue(JsonGenerator generator, String fieldName,
                                            LogicalCallDiagnosticWinner.CriterionValue value) throws IOException
    {
        generator.writeObjectFieldStart(fieldName);

        if(value != null)
        {
            writeText(generator, "display", value.display());
            writeNumber(generator, "numerator", value.numerator());
            writeNumber(generator, "denominator", value.denominator());
        }

        generator.writeEndObject();
    }

    private static void writeCopies(JsonGenerator generator, LogicalCallDiagnosticDecision decision)
        throws IOException
    {
        generator.writeArrayFieldStart("receiver_copies");

        for(LogicalCallDiagnosticLeg leg: decision.legs())
        {
            generator.writeStartObject();
            writeText(generator, "copy_id", leg.legId());
            writeText(generator, "decoder", leg.decoder());
            writeText(generator, "channel_configuration_id", leg.channelConfigurationId());
            writeText(generator, "channel_name", leg.channelName());
            writeText(generator, "learned_site_guid", leg.siteGuid());
            generator.writeNumberField("durable_alias_list_id", leg.durableAliasListId());
            writeNumber(generator, "wacn", leg.wacn());
            writeNumber(generator, "system_id", leg.system());
            writeNumber(generator, "rfss", leg.rfss());
            writeNumber(generator, "site", leg.site());
            generator.writeNumberField("start_at_ms", leg.startTimestamp());
            generator.writeNumberField("end_at_ms", leg.endTimestamp());
            generator.writeNumberField("duration_ms", leg.durationMilliseconds());
            generator.writeNumberField("expected_frame_count", leg.expectedFrameCount());
            generator.writeNumberField("observed_frame_count", leg.observedFrameCount());
            generator.writeNumberField("usable_frame_count", leg.usableFrameCount());
            generator.writeNumberField("decoded_frame_count", leg.decodedFrameCount());
            generator.writeNumberField("repeated_frame_count", leg.repeatedFrameCount());
            generator.writeNumberField("concealed_frame_count", leg.concealedFrameCount());
            generator.writeNumberField("missing_frame_count", leg.missingFrameCount());
            generator.writeNumberField("fec_error_count", leg.fecErrorCount());
            generator.writeNumberField("fec_protected_bit_count", leg.fecProtectedBitCount());
            generator.writeNumberField("quality_percent", leg.qualityPercent());
            generator.writeNumberField("missing_and_concealed_rate", leg.missingAndConcealedRate());
            generator.writeNumberField("repeated_frame_rate", leg.repeatedFrameRate());
            generator.writeNumberField("normalized_fec_error_rate", leg.normalizedFecErrorRate());
            generator.writeNumberField("retained_audio_sample_count", leg.retainedAudioSampleCount());
            generator.writeBooleanField("ingress_loss", leg.ingressLoss());
            generator.writeBooleanField("audio_truncated", leg.audioTruncated());
            generator.writeBooleanField("selected", leg.winner());
            writeWinnerOverlap(generator, LogicalCallDiagnosticOverlap.forCopy(decision, leg).orElse(null));
            generator.writeEndObject();
        }

        generator.writeEndArray();
    }

    private static void writeWinnerOverlap(JsonGenerator generator, LogicalCallDiagnosticOverlap overlap)
        throws IOException
    {
        if(overlap == null)
        {
            generator.writeNullField("overlap_with_selected");
            return;
        }

        generator.writeObjectFieldStart("overlap_with_selected");
        generator.writeNumberField("overlap_ms", overlap.overlapMilliseconds());
        generator.writeNumberField("shorter_copy_overlap_percent", overlap.shorterCopyOverlapPercent());
        generator.writeNumberField("selected_copy_coverage_percent", overlap.selectedCopyCoveragePercent());
        generator.writeNumberField("start_offset_from_selected_ms",
            overlap.startOffsetFromSelectedMilliseconds());
        generator.writeNumberField("end_offset_from_selected_ms", overlap.endOffsetFromSelectedMilliseconds());
        generator.writeEndObject();
    }

    private static void writeComparisonSummary(JsonGenerator generator, LogicalCallDiagnosticEvidence evidence)
        throws IOException
    {
        LogicalCallDiagnosticEvidence safeEvidence = evidence != null ? evidence : LogicalCallDiagnosticEvidence.EMPTY;
        generator.writeObjectFieldStart("comparison_summary");
        generator.writeNumberField("comparison_count", safeEvidence.candidateComparisonCount());
        generator.writeNumberField("confirmed_duplicate_count", safeEvidence.confirmedDuplicatePairCount());
        generator.writeNumberField("separated_count", safeEvidence.separatedPairCount());
        generator.writeNumberField("uncertain_count", safeEvidence.uncertainPairCount());
        writeEnumCounts(generator, "merge_proof_counts", safeEvidence.mergeProofCounts());
        writeEnumCounts(generator, "rejection_reason_counts", safeEvidence.rejectionReasonCounts());
        generator.writeEndObject();
    }

    private static void writeEnumCounts(JsonGenerator generator, String fieldName,
                                        Map<? extends Enum<?>,? extends Number> counts) throws IOException
    {
        generator.writeObjectFieldStart(fieldName);

        for(Map.Entry<? extends Enum<?>,? extends Number> entry : counts.entrySet())
        {
            if(entry.getKey() != null && entry.getValue() != null && entry.getValue().longValue() > 0L)
            {
                generator.writeNumberField(entry.getKey().name(), entry.getValue().longValue());
            }
        }

        generator.writeEndObject();
    }

    private static void writeText(JsonGenerator generator, String fieldName, String value) throws IOException
    {
        if(value == null)
        {
            generator.writeNullField(fieldName);
        }
        else
        {
            generator.writeStringField(fieldName, safeText(value));
        }
    }

    private static void writeNumber(JsonGenerator generator, String fieldName, Number value) throws IOException
    {
        if(value == null)
        {
            generator.writeNullField(fieldName);
        }
        else if(value instanceof Integer integer)
        {
            generator.writeNumberField(fieldName, integer);
        }
        else
        {
            generator.writeNumberField(fieldName, value.longValue());
        }
    }

    private static void writeEnum(JsonGenerator generator, String fieldName, Enum<?> value) throws IOException
    {
        if(value == null)
        {
            generator.writeNullField(fieldName);
        }
        else
        {
            generator.writeStringField(fieldName, value.name());
        }
    }

    private static void writeEnumArray(JsonGenerator generator, String fieldName,
                                       List<? extends Enum<?>> values) throws IOException
    {
        generator.writeArrayFieldStart(fieldName);

        for(Enum<?> value: values)
        {
            if(value != null)
            {
                generator.writeString(value.name());
            }
        }

        generator.writeEndArray();
    }

    private static String safeText(String value)
    {
        String trimmed = value.strip();
        String lower = trimmed.toLowerCase(Locale.US);

        if(looksLikeAbsolutePath(trimmed))
        {
            return REDACTED_PATH;
        }

        if(lower.contains("-----begin ") || lower.contains("password=") || lower.contains("password:") ||
            lower.contains("token=") || lower.contains("token:") || lower.contains("api_key=") ||
            lower.contains("api-key=") || lower.contains("apikey=") || looksLikeCredentialUrl(trimmed))
        {
            return REDACTED_SECRET;
        }

        StringBuilder cleaned = new StringBuilder(Math.min(trimmed.length(), MAXIMUM_TEXT_CODE_POINTS));
        int copiedCodePoints = 0;

        for(int offset = 0; offset < trimmed.length() && copiedCodePoints < MAXIMUM_TEXT_CODE_POINTS;)
        {
            int codePoint = trimmed.codePointAt(offset);
            offset += Character.charCount(codePoint);
            cleaned.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            copiedCodePoints++;
        }

        return cleaned.toString();
    }

    private static boolean looksLikeAbsolutePath(String value)
    {
        return value.startsWith("/") || value.startsWith("\\\\") ||
            (value.length() > 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':' &&
                (value.charAt(2) == '\\' || value.charAt(2) == '/'));
    }

    private static boolean looksLikeCredentialUrl(String value)
    {
        int scheme = value.indexOf("://");
        int at = value.indexOf('@');
        return scheme > 0 && at > scheme + 3;
    }

    private static byte[] encode(JsonWrite write)
    {
        try(ByteArrayOutputStream output = new ByteArrayOutputStream(1_024);
            JsonGenerator generator = JSON_FACTORY.createGenerator(output))
        {
            write.write(generator);
            generator.flush();
            return output.toByteArray();
        }
        catch(IOException exception)
        {
            throw new IllegalStateException("Unable to encode bounded logical-call diagnostic record", exception);
        }
    }

    @FunctionalInterface
    private interface JsonWrite
    {
        void write(JsonGenerator generator) throws IOException;
    }
}
