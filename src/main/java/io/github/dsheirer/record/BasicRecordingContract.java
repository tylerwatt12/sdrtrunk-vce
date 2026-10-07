/*
 * Copyright (C) 2014-2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.record;

import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.string.StringIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneIdentifier;
import io.github.dsheirer.util.StringUtils;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Basic/Classic recording compatibility with DSheirer/sdrtrunk a9120ed65e5b9f91cf460544efdab4863d7e4b45.
 *
 * <p>External importers depend on Basic filenames and existing ID3/WAV fields in every recording/export mode.
 * Do not change their schema or values
 * to follow application branding, display labels, or additional identity facts. New metadata must use new fields.
 * Qualified addresses deliberately retain the upstream decimal, local-address-first representation.</p>
 */
public final class BasicRecordingContract
{
    private BasicRecordingContract() {}

    private static String identifierText(Identifier<?> identifier)
    {
        if(identifier instanceof FullyQualifiedRadioIdentifier radio)
        {
            String home = radio.getWacn() + "." + radio.getSystem() + "." + radio.getRadio();
            return radio.getValue() != 0 && radio.getValue() != radio.getRadio() ?
                "ROAM " + radio.getValue() + "(" + home + ")" : "ISSI " + home;
        }
        if(identifier instanceof FullyQualifiedTalkgroupIdentifier group)
        {
            String home = group.getWacn() + "." + group.getSystem() + "." + group.getTalkgroup();
            return "ISSI " + (group.getValue() != 0 && group.getValue() != group.getTalkgroup() ?
                group.getValue() + "(" + home + ")" : home);
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
        return identifierText(identifier).replace("ISSI ", "").replace("ROAM ", "");
    }

    public static String targetIdentifier(Identifier<?> identifier)
    {
        return identifierText(identifier).replace("ISSI ", "");
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
