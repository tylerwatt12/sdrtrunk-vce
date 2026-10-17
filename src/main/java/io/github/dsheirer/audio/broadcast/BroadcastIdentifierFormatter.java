/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;

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
            //Receiving system is physical provenance, independent of the resolved caller's home system.
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

    public static String format(Identifier<?> identifier, String servingSystemKey)
    {
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
