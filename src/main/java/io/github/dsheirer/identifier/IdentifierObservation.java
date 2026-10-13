/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.identifier;

import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.IdentityResolutionEvidence;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import java.util.Objects;

/** Observation updates are independent of canonical entity equality. Shared by collections and call trackers. */
public final class IdentifierObservation
{
    private IdentifierObservation() {}

    /** Missing fields do not withdraw a known address; retain the new direct observation separately. */
    public static Identifier retainAssignment(Identifier existing, Identifier candidate)
    {
        if(existing instanceof APCO25FullyQualifiedRadioIdentifier retained &&
            candidate instanceof FullyQualifiedRadioIdentifier observed && retained.equals(observed) &&
            retained.getWorkingAddress() != null &&
            (retained.getResolutionEvidence() == IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT ||
                retained.getResolutionEvidence() == IdentityResolutionEvidence.DIRECT) &&
            observed.getResolutionEvidence() == IdentityResolutionEvidence.DIRECT && observed.getWorkingAddress() == null)
        {
            var raw = observed.getRecordingIdentifier();
            return same(retained.getRecordingIdentifier(), raw) ? retained :
                APCO25FullyQualifiedRadioIdentifier.withRecordingIdentifier(retained, raw);
        }
        if(existing instanceof APCO25FullyQualifiedTalkgroupIdentifier retained &&
            candidate instanceof FullyQualifiedTalkgroupIdentifier observed && retained.equals(observed) &&
            retained.getLocalAddress() != null &&
            (retained.getResolutionEvidence() == IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT ||
                retained.getResolutionEvidence() == IdentityResolutionEvidence.DIRECT) &&
            observed.getResolutionEvidence() == IdentityResolutionEvidence.DIRECT && observed.getLocalAddress() == null)
        {
            var raw = observed.getRecordingIdentifier();
            return same(retained.getRecordingIdentifier(), raw) ? retained :
                APCO25FullyQualifiedTalkgroupIdentifier.withRecordingIdentifier(retained, raw);
        }
        if(existing instanceof APCO25FullyQualifiedTalkgroupIdentifier retained &&
            candidate instanceof FullyQualifiedTalkgroupIdentifier observed && retained.equals(observed) &&
            retained.getResolutionEvidence() == IdentityResolutionEvidence.DIRECT &&
            observed.getResolutionEvidence() == IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT &&
            retained.getLocalAddress() != null && retained.getLocalAddress().equals(observed.getLocalAddress()))
        {
            var raw = observed.getRecordingIdentifier();
            return same(retained.getRecordingIdentifier(), raw) ? retained :
                APCO25FullyQualifiedTalkgroupIdentifier.withRecordingIdentifier(retained, raw);
        }
        return candidate;
    }

    public static boolean shouldReplace(Identifier existing, Identifier candidate)
    {
        if(existing == null || !existing.equals(candidate)) return true;
        if(existing instanceof FullyQualifiedRadioIdentifier old && candidate instanceof FullyQualifiedRadioIdentifier next)
        {
            return next.getRecordingIdentifier() != next && !same(old.getRecordingIdentifier(), next.getRecordingIdentifier()) ||
                next.getWorkingAddress() != null && (!next.getWorkingAddress().equals(old.getWorkingAddress()) ||
                    next.getResolutionEvidence() == IdentityResolutionEvidence.DIRECT && old.getResolutionEvidence() == IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT);
        }
        if(existing instanceof FullyQualifiedTalkgroupIdentifier old && candidate instanceof FullyQualifiedTalkgroupIdentifier next)
        {
            return next.getRecordingIdentifier() != next && !same(old.getRecordingIdentifier(), next.getRecordingIdentifier()) ||
                next.getLocalAddress() != null && (!next.getLocalAddress().equals(old.getLocalAddress()) ||
                    next.getResolutionEvidence() == IdentityResolutionEvidence.DIRECT && old.getResolutionEvidence() == IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT);
        }
        return existing instanceof PatchGroupIdentifier && candidate instanceof PatchGroupIdentifier && !same(existing, candidate);
    }

    /** Compares raw field observations without using observation-insensitive canonical equality alone. */
    public static boolean same(Identifier first, Identifier second)
    {
        if(first == second) return true;
        if(first == null || second == null || !first.equals(second)) return false;
        if(first instanceof PatchGroupIdentifier a && second instanceof PatchGroupIdentifier b)
        {
            if(a.getValue() == null || b.getValue() == null) return a.getValue() == b.getValue();
            return a.getValue().getVersion() == b.getValue().getVersion() &&
                same(a.getValue().getPatchGroup(), b.getValue().getPatchGroup()) &&
                sameList(a.getValue().getPatchedTalkgroupIdentifiers(), b.getValue().getPatchedTalkgroupIdentifiers()) &&
                sameList(a.getValue().getPatchedRadioIdentifiers(), b.getValue().getPatchedRadioIdentifiers());
        }
        if(!Objects.equals(first.getValue(), second.getValue())) return false;
        if(first instanceof FullyQualifiedRadioIdentifier a && second instanceof FullyQualifiedRadioIdentifier b)
            return a.hasExplicitWorkingAddress() == b.hasExplicitWorkingAddress() && a.getResolutionEvidence() == b.getResolutionEvidence() &&
                (a.getRecordingIdentifier() == a && b.getRecordingIdentifier() == b || same(a.getRecordingIdentifier(), b.getRecordingIdentifier()));
        if(first instanceof FullyQualifiedTalkgroupIdentifier a && second instanceof FullyQualifiedTalkgroupIdentifier b)
            return a.hasExplicitLocalAddress() == b.hasExplicitLocalAddress() && a.getResolutionEvidence() == b.getResolutionEvidence() &&
                (a.getRecordingIdentifier() == a && b.getRecordingIdentifier() == b || same(a.getRecordingIdentifier(), b.getRecordingIdentifier()));
        return true;
    }

    public static boolean sameList(java.util.List<? extends Identifier> first, java.util.List<? extends Identifier> second)
    {
        if(first.size() != second.size()) return false;
        for(int index = 0; index < first.size(); index++)
            if(!same(first.get(index), second.get(index))) return false;
        return true;
    }
}
