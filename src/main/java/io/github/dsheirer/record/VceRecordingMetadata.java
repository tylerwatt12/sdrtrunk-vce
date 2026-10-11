/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.record;

import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CallLegSummary;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.ConfigurationLongIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.protocol.Protocol;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Additional recording facts projected on the recording worker from the completed audio winner's captured leg.
 * These named ID3 TXXX values supplement the standard recording contract without changing it. No lookup of live
 * decoder state, aliases, or subscriber assignments is performed, and logical-call identity promotion is excluded.
 */
public final class VceRecordingMetadata
{
    private VceRecordingMetadata() {}

    /** Returns known facts in deterministic order; unknown values have no entry. */
    public static Map<String, String> capture(CompletedAudioCall call)
    {
        if(call == null) return Map.of();

        Map<String, String> fields = new LinkedHashMap<>();
        IdentifierCollection identifiers = call.recordingIdentifierCollection();
        Identifier<?> source = identifier(identifiers, IdentifierClass.USER, Form.RADIO, Role.FROM);
        Identifier<?> destination = identifiers != null ? identifiers.getToIdentifier() : null;
        radio(fields, source, "VCE_P25_WUID", "VCE_P25_SUID");
        radio(fields, destination, "VCE_P25_DEST_WUID", "VCE_P25_DEST_SUID");

        String systemKey = BasicRecordingContract.servingSystemKey(call);
        if(RadioSystemKey.isP25Native(systemKey))
        {
            fields.put("VCE_P25_RX_WACN", systemKey.substring(4, 9).toUpperCase(Locale.ROOT));
            fields.put("VCE_P25_RX_SYSID", systemKey.substring(10, 13).toUpperCase(Locale.ROOT));

            P25SiteIdentity site = call.snapshot().callLegSource().p25SiteIdentity();
            if(site != null && systemKey.equals(RadioSystemKey.p25(site)))
            {
                fields.put("VCE_P25_RX_RFSS", Integer.toString(site.rfss()));
                fields.put("VCE_P25_RX_SITE", Integer.toString(site.site()));
            }
        }

        Identifier<?> nac = identifier(identifiers, IdentifierClass.NETWORK, Form.NETWORK_ACCESS_CODE,
            Role.BROADCAST);
        if(nac != null && isP25(nac.getProtocol()) && nac.getValue() instanceof Integer value &&
            value >= 0 && value <= 0xFFF)
        {
            fields.put("VCE_P25_NAC", String.format(Locale.ROOT, "%03X", value));
        }

        if(destination instanceof PatchGroupIdentifier patch)
        {
            if(patch.getValue() != null)
            {
                positiveInteger(fields, "VCE_PATCH_GROUP", patch.getValue().getPatchGroup());
            }
        }
        else if(destination != null && destination.getForm() == Form.TALKGROUP)
        {
            positiveInteger(fields, "VCE_TALKGROUP", destination);
        }

        CallLegSummary winner = call.callLegSummaries().stream().filter(CallLegSummary::winner)
            .findFirst().orElseThrow();
        Long frequency = winner.frequencyHz();
        if(frequency == null)
        {
            Identifier<?> capturedFrequency = identifier(identifiers, IdentifierClass.CONFIGURATION,
                Form.CHANNEL_FREQUENCY, Role.ANY);
            if(capturedFrequency instanceof ConfigurationLongIdentifier value)
            {
                frequency = value.getValue();
            }
        }
        if(frequency != null && frequency > 0L)
        {
            fields.put("VCE_FREQUENCY_HZ", Long.toString(frequency));
        }

        DecoderType decoder = decoder(call, winner, identifiers);
        Protocol protocol = decoder != null ? decoder.getProtocol() : protocol(destination, source);
        if(protocol != null && protocol != Protocol.UNKNOWN)
        {
            fields.put("VCE_PROTOCOL", isP25(protocol) ? Protocol.APCO25.name() : protocol.name());
        }
        if(decoder == DecoderType.P25_CONVENTIONAL || decoder == DecoderType.P25_PHASE1)
        {
            fields.put("VCE_P25_PHASE", "1");
        }
        else if(decoder == DecoderType.P25_PHASE2)
        {
            fields.put("VCE_P25_PHASE", "2");
        }

        int timeslot = winner.timeslot() != null ? winner.timeslot() : call.snapshot().timeslot();
        if(timeslot > 0) fields.put("VCE_TIMESLOT", Integer.toString(timeslot));
        if(call.snapshot().encryptionState().isKnown())
        {
            fields.put("VCE_ENCRYPTION", call.snapshot().encryptionState().name());
        }

        quality(fields, winner.voiceCallQuality());
        return Collections.unmodifiableMap(fields);
    }

    private static void radio(Map<String, String> fields, Identifier<?> identifier, String workingField,
                              String subscriberField)
    {
        if(!(identifier instanceof RadioIdentifier radio) || !isP25(radio.getProtocol())) return;

        Integer working = radio instanceof FullyQualifiedRadioIdentifier qualified ?
            qualified.getWorkingAddress() : radio.getValue();
        if(working != null && working > 0 && working <= RadioSystemIdentityKey.MAX_P25_RADIO_ID)
        {
            fields.put(workingField, Integer.toString(working));
        }

        P25SubscriberIdentity subscriber = P25SubscriberIdentity.from(radio);
        if(subscriber != null)
        {
            fields.put(subscriberField, subscriber.display());
        }
    }

    private static void positiveInteger(Map<String, String> fields, String name, Identifier<?> identifier)
    {
        if(identifier != null && identifier.getValue() instanceof Integer value && value > 0)
        {
            fields.put(name, Integer.toString(value));
        }
    }

    private static DecoderType decoder(CompletedAudioCall call, CallLegSummary winner,
                                       IdentifierCollection identifiers)
    {
        CallLegSource source = winner.source();
        DecoderType decoder = source.decoderType();
        if(decoder == null) decoder = call.snapshot().callLegSource().decoderType();
        if(decoder == null)
        {
            Identifier<?> captured = identifier(identifiers, IdentifierClass.CONFIGURATION, Form.DECODER_TYPE,
                Role.ANY);
            if(captured != null && captured.getValue() instanceof DecoderType value) decoder = value;
        }
        return decoder;
    }

    private static Protocol protocol(Identifier<?> destination, Identifier<?> source)
    {
        Protocol protocol = destination != null ? destination.getProtocol() : null;
        return protocol != null && protocol != Protocol.UNKNOWN ? protocol :
            source != null ? source.getProtocol() : null;
    }

    private static boolean isP25(Protocol protocol)
    {
        return protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2;
    }

    private static Identifier<?> identifier(IdentifierCollection identifiers, IdentifierClass identifierClass,
                                            Form form, Role role)
    {
        return identifiers != null ? identifiers.getIdentifier(identifierClass, form, role) : null;
    }

    private static void quality(Map<String, String> fields, VoiceCallQuality quality)
    {
        if(quality.hasMeasurements())
        {
            fields.put("VCE_VOICE_DECODED_FRAMES", Long.toString(quality.decodedFrameCount()));
            fields.put("VCE_VOICE_REPEATED_FRAMES", Long.toString(quality.repeatedFrameCount()));
            fields.put("VCE_VOICE_CONCEALED_FRAMES", Long.toString(quality.concealedFrameCount()));
            fields.put("VCE_VOICE_MISSING_FRAMES", Long.toString(quality.missingFrameCount()));
        }
        if(quality.fecProtectedBitCount() > 0L)
        {
            fields.put("VCE_VOICE_FEC_ERRORS", Long.toString(quality.fecErrorCount()));
            fields.put("VCE_VOICE_FEC_PROTECTED_BITS", Long.toString(quality.fecProtectedBitCount()));
            fields.put("VCE_VOICE_FEC_CORRECTION_PERCENT", BigDecimal.valueOf(quality.fecErrorCount())
                .multiply(BigDecimal.valueOf(100L)).divide(BigDecimal.valueOf(quality.fecProtectedBitCount()),
                    6, RoundingMode.HALF_UP).toPlainString());
        }
    }
}
