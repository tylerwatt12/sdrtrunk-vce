/*
 * Copyright (C) 2014-2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.record;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
import io.github.dsheirer.identifier.string.StringIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.util.StringUtils;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared recording filenames and standard ID3/WAV metadata.
 *
 * <p>External importers depend on Basic filenames and existing ID3/WAV fields in every recording/export mode.
 * Qualified radio and talkgroup text uses the captured local numeric ID, including zero, independently of the
 * receiving system. Full home identities and explicit working-address facts belong in the additional ID3 fields.
 * VCE assignment enrichment must not turn an ordinary over-air radio into a qualified recording identity.
 * Call resolution must likewise retain the audio winner's original recording identifiers.</p>
 */
public final class BasicRecordingContract
{
    private BasicRecordingContract() {}

    /** Receiving system captured by the audio winner, independent of logical-call identity promotion. */
    public static String servingSystemKey(CompletedAudioCall call)
    {
        if(call == null) return null;
        CallLegSource source = call.snapshot().callLegSource();
        return RadioSystemKey.p25Serving(source != null ? source.radioSystemKey() : null,
            source != null ? source.p25SiteIdentity() : null, call.recordingIdentifierCollection());
    }

    /** Only a complete over-air network broadcast can supply context when no call source is available. */
    public static String servingSystemKey(IdentifierCollection identifiers)
    {
        return RadioSystemKey.p25Broadcast(identifiers);
    }

    /** Restores the captured over-air identifier for standard recording text and Alias List lookup. */
    public static Identifier<?> recordingIdentifier(Identifier<?> identifier)
    {
        if(identifier instanceof FullyQualifiedRadioIdentifier radio)
        {
            if(radio.getRecordingIdentifier() != radio)
            {
                return recordingIdentifier(radio.getRecordingIdentifier());
            }
            if(radio.getResolutionEvidence() == ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT)
            {
                return new APCO25RadioIdentifier(radio.getValue(), radio.getRole());
            }
        }
        return identifier;
    }

    /** Mainline qualified-radio tags match a qualified Alias only, without a local working-ID fallback. */
    public static List<Alias> recordingAliases(AliasList aliases, Identifier<?> identifier)
    {
        return aliases != null && identifier != null ? aliases.getAliases(aliasLookupIdentifier(identifier)) : List.of();
    }

    private static Identifier<?> aliasLookupIdentifier(Identifier<?> identifier)
    {
        identifier = recordingIdentifier(identifier);
        if(identifier instanceof FullyQualifiedRadioIdentifier radio)
        {
            //An identity-only lookup retains the exact home tuple while disabling VCE's working-address fallback.
            return new APCO25FullyQualifiedRadioIdentifier(0, radio.getWacn(), radio.getSystem(), radio.getRadio(),
                radio.getRole());
        }
        if(identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null)
        {
            PatchGroup original = patch.getValue();
            PatchGroup lookup = new PatchGroup(original.getPatchGroup(), original.getVersion());
            lookup.addPatchedTalkgroups(original.getPatchedTalkgroupIdentifiers());
            for(RadioIdentifier radio: original.getPatchedRadioIdentifiers())
            {
                lookup.addPatchedRadio((RadioIdentifier)aliasLookupIdentifier(radio));
            }
            return APCO25PatchGroup.create(lookup);
        }
        return identifier;
    }

    private static String identifierText(Identifier<?> identifier)
    {
        identifier = recordingIdentifier(identifier);
        if(identifier instanceof FullyQualifiedRadioIdentifier radio)
        {
            return Integer.toString(radio.getValue());
        }
        if(identifier instanceof FullyQualifiedTalkgroupIdentifier group)
        {
            return Integer.toString(group.getValue());
        }
        if(identifier instanceof PatchGroupIdentifier patch)
        {
            var group = patch.getValue();
            String value = "P:" + identifierText(group.getPatchGroup());
            if(group.hasPatchedTalkgroups()) value += " " + group.getPatchedTalkgroupIdentifiers().stream()
                .map(BasicRecordingContract::identifierText).toList();
            if(group.hasPatchedRadios()) value += " " + group.getPatchedRadioIdentifiers().stream()
                .map(BasicRecordingContract::identifierText).toList();
            return value;
        }
        return identifier != null ? identifier.toString() : "";
    }

    public static String sourceIdentifier(Identifier<?> identifier)
    {
        return identifierText(identifier);
    }

    public static String targetIdentifier(Identifier<?> identifier)
    {
        return identifierText(identifier);
    }

    static String filenameIdentifier(Identifier<?> identifier)
    {
        return sourceIdentifier(identifier).replace(":", "").replace(".", "_")
            .replace("(", "_").replace(")", "");
    }

    public static String composer()
    {
        return composer(ApplicationInfo.getVersion(), ApplicationInfo.getBuildTimestamp());
    }

    static String composer(String version, String buildTimestamp)
    {
        if(version == null) return "sdrtrunk";
        return version.contains("nightly") && buildTimestamp != null ?
            "sdrtrunk nightly - " + buildTimestamp : "sdrtrunk v" + version;
    }

    static Path nextPath(Path directory, IdentifierCollection identifiers, RecordFormat format,
                         long writerTimestamp, int unknownIndex) throws IOException
    {
        for(int version = 1; version > 0; version++)
        {
            Path path = directory.resolve(filename(identifiers, format, writerTimestamp, unknownIndex, version));
            if(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return path;
        }
        throw new IOException("No unused Basic recording filename is available");
    }

    static String filename(IdentifierCollection identifiers, RecordFormat format, long writerTimestamp,
                           int unknownIndex, int version)
    {
        StringBuilder body = new StringBuilder();
        if(identifiers == null)
        {
            body.append("audio_recording_no_metadata_").append(unknownIndex);
        }
        else
        {
            for(Form form: List.of(Form.SYSTEM, Form.SITE, Form.CHANNEL))
            {
                Identifier<?> identifier = identifiers.getIdentifier(IdentifierClass.CONFIGURATION, form, Role.ANY);
                if(identifier instanceof StringIdentifier value) body.append(value.getValue()).append("_");
            }
            Identifier<?> target = identifiers.getIdentifier(IdentifierClass.USER, Form.TALKGROUP, Role.TO);
            if(target == null)
            {
                List<Identifier> targets = identifiers.getIdentifiers(Role.TO);
                if(!targets.isEmpty()) target = targets.getFirst();
            }
            if(target != null) body.append("_TO_").append(filenameIdentifier(target));
            Identifier<?> source = identifiers.getIdentifier(IdentifierClass.USER, Form.RADIO, Role.FROM);
            if(source == null)
            {
                for(Identifier<?> candidate: identifiers.getIdentifiers(Role.FROM))
                {
                    if(candidate.getForm() != Form.TONE) { source = candidate; break; }
                }
            }
            if(source != null) body.append("_FROM_").append(filenameIdentifier(source));
            List<Identifier> tones = identifiers.getIdentifiers(IdentifierClass.USER, Form.TONE);
            if(!tones.isEmpty() && tones.getFirst() instanceof ToneIdentifier tone && tone.getValue() != null &&
                tone.getValue().hasTones())
            {
                body.append("_TONES");
                for(Tone value: tone.getValue().getTones()) body.append("_").append(value.getAmbeTone().toString()
                    .replace("TONE", "").trim().replace(" ", "_"));
            }
        }
        String timestamp = TimeStamp.getTimeStamp(writerTimestamp, "_") + "_";
        String suffix = version > 1 ? "_V" + version : "";
        String cleaned = StringUtils.replaceIllegalCharacters(body.toString());
        int length = 255 - timestamp.length() - ("_V" + Math.max(2, version)).length() - format.getExtension().length();
        if(cleaned.length() > length) cleaned = cleaned.substring(0, length);
        return timestamp + cleaned + suffix + format.getExtension();
    }
}
