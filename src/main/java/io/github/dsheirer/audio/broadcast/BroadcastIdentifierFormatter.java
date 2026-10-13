/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.record.BasicRecordingContract;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Legacy stream-title identifiers, independent of the decoder's canonical identity display.
 * Qualification requires a proven foreign home system. Missing context never establishes a foreign system.
 */
public final class BroadcastIdentifierFormatter
{
    private BroadcastIdentifierFormatter() {}

    /** Captured serving system of the audio winner, rather than a promoted logical-call identity. */
    public static String servingSystemKey(CompletedCallBroadcastMetadata metadata, IdentifierCollection identifiers)
    {
        if(metadata != null && metadata.isAvailable())
        {
            //Captured before the winner's original collection is discarded; never use a promoted sibling's key.
            return metadata.servingSystemKey();
        }
        return servingSystemKey(identifiers);
    }

    static String servingSystemKey(CallLegSource source, IdentifierCollection identifiers)
    {
        return RadioSystemKey.p25Serving(source != null ? source.radioSystemKey() : null,
            source != null ? source.p25SiteIdentity() : null, identifiers);
    }

    public static String servingSystemKey(IdentifierCollection identifiers)
    {
        return RadioSystemKey.p25Broadcast(identifiers);
    }

    /**
     * Projects the winning leg's observed identifiers into a legacy stream title without changing shared call facts
     * or the destination already selected by patch routing. Configuration identifiers retain their stream values.
     */
    public static IdentifierCollection streamIdentifiers(CompletedCallBroadcastMetadata metadata,
                                                         IdentifierCollection identifiers)
    {
        if(identifiers == null || metadata == null || !metadata.isAvailable() ||
            metadata.recordingIdentifiers() == null)
        {
            return identifiers;
        }

        IdentifierCollection observed = new IdentifierCollection(metadata.recordingIdentifiers());
        Identifier<?> originalTarget = observed.getToIdentifier();
        var projected = new ArrayList<Identifier>();
        for(Identifier identifier: identifiers.getIdentifiers())
        {
            if(identifier.getIdentifierClass() == IdentifierClass.CONFIGURATION)
            {
                projected.add(identifier);
            }
            else if(identifier.getRole() == Role.TO)
            {
                projected.add(observedTarget(identifier, originalTarget));
            }
            else if(identifier.getRole() != Role.FROM)
            {
                projected.add(identifier);
            }
        }
        //A missing winner source stays missing even if the resolver selected an agreed source from another leg.
        observed.getIdentifiers(Role.FROM).stream()
            .filter(identifier -> identifier.getIdentifierClass() != IdentifierClass.CONFIGURATION)
            .forEach(projected::add);
        IdentifierCollection result = new IdentifierCollection(projected);
        result.setTimeslot(identifiers.getTimeslot());
        return result;
    }

    private static Identifier<?> observedTarget(Identifier<?> current, Identifier<?> original)
    {
        if(current instanceof PatchGroupIdentifier patch)
        {
            PatchGroup group = patch.getValue();
            PatchGroup projected = new PatchGroup(
                (TalkgroupIdentifier)observedTarget(group.getPatchGroup(), original), group.getVersion());
            group.getPatchedTalkgroupIdentifiers().forEach(member -> projected.addPatchedTalkgroup(
                (TalkgroupIdentifier)observedTarget(member, original)));
            group.getPatchedRadioIdentifiers().forEach(member -> projected.addPatchedRadio(
                (RadioIdentifier)observedTarget(member, original)));
            return APCO25PatchGroup.create(projected);
        }
        if(original instanceof PatchGroupIdentifier patch)
        {
            PatchGroup group = patch.getValue();
            var candidates = new ArrayList<Identifier<?>>();
            candidates.add(group.getPatchGroup());
            candidates.addAll(group.getPatchedTalkgroupIdentifiers());
            candidates.addAll(group.getPatchedRadioIdentifiers());
            //An exact home identity wins over a plain primary/member that happens to share the local number.
            for(Identifier<?> member: candidates)
            {
                if(sameObservedAddress(current, member) && sameQualifiedIdentity(current, member)) return member;
            }
            for(Identifier<?> member: candidates)
            {
                if(sameObservedAddress(current, member)) return member;
            }
        }
        return sameObservedAddress(current, original) ? original : current;
    }

    private static boolean sameObservedAddress(Identifier<?> current, Identifier<?> original)
    {
        return current != null && original != null && current.getForm() == original.getForm() &&
            current.getIdentifierClass() == original.getIdentifierClass() &&
            current.getProtocol() == original.getProtocol() && Objects.equals(current.getValue(), original.getValue()) &&
            (!(isQualified(current) && isQualified(original)) || sameQualifiedIdentity(current, original));
    }

    private static boolean isQualified(Identifier<?> identifier)
    {
        return identifier instanceof FullyQualifiedRadioIdentifier || identifier instanceof FullyQualifiedTalkgroupIdentifier;
    }

    private static boolean sameQualifiedIdentity(Identifier<?> first, Identifier<?> second)
    {
        if(first instanceof FullyQualifiedRadioIdentifier radio && second instanceof FullyQualifiedRadioIdentifier other)
        {
            return radio.getWacn() == other.getWacn() && radio.getSystem() == other.getSystem() &&
                radio.getRadio() == other.getRadio();
        }
        if(first instanceof FullyQualifiedTalkgroupIdentifier group && second instanceof FullyQualifiedTalkgroupIdentifier other)
        {
            return group.getWacn() == other.getWacn() && group.getSystem() == other.getSystem() &&
                group.getTalkgroup() == other.getTalkgroup();
        }
        return false;
    }

    public static String format(Identifier<?> identifier, String servingSystemKey)
    {
        //Restore the observed identifier when later identity resolution enriched an ordinary local ID.
        identifier = BasicRecordingContract.recordingIdentifier(identifier);
        if(identifier instanceof FullyQualifiedRadioIdentifier radio)
        {
            if(!RadioSystemKey.isForeignP25(servingSystemKey, radio.getWacn(), radio.getSystem()))
            {
                return Integer.toString(radio.getValue() != 0 ? radio.getValue() : radio.getRadio());
            }
            String home = radio.getWacn() + "." + radio.getSystem() + "." + radio.getRadio();
            return radio.getValue() != 0 && radio.getValue() != radio.getRadio() ?
                "ROAM " + radio.getValue() + "(" + home + ")" : "ISSI " + home;
        }
        if(identifier instanceof FullyQualifiedTalkgroupIdentifier group)
        {
            if(!RadioSystemKey.isForeignP25(servingSystemKey, group.getWacn(), group.getSystem()))
            {
                return Integer.toString(group.getValue() != 0 ? group.getValue() : group.getTalkgroup());
            }
            String home = group.getWacn() + "." + group.getSystem() + "." + group.getTalkgroup();
            return "ISSI " + (group.getValue() != 0 && group.getValue() != group.getTalkgroup() ?
                group.getValue() + "(" + home + ")" : home);
        }
        if(identifier instanceof PatchGroupIdentifier patch)
        {
            var group = patch.getValue();
            String value = "P:" + format(group.getPatchGroup(), servingSystemKey);
            if(group.hasPatchedTalkgroups()) value += " " + group.getPatchedTalkgroupIdentifiers().stream()
                .map(member -> format(member, servingSystemKey)).toList();
            if(group.hasPatchedRadios()) value += " " + group.getPatchedRadioIdentifiers().stream()
                .map(member -> format(member, servingSystemKey)).toList();
            return value;
        }
        return identifier != null ? identifier.toString() : "";
    }

}
