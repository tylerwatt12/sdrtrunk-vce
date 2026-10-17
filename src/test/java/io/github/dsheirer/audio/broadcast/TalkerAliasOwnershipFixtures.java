/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import java.util.List;

public final class TalkerAliasOwnershipFixtures
{
    private TalkerAliasOwnershipFixtures() {}

    public static List<Sample> samples()
    {
        return List.of(
            new Sample(APCO25RadioIdentifier.createFrom(101), APCO25RadioIdentifier.createFrom(202),
                P25TalkerAliasIdentifier.create("P25 UNIT")),
            new Sample(DMRRadio.createFrom(101), DMRRadio.createFrom(202),
                DmrTalkerAliasIdentifier.create("DMR UNIT")),
            new Sample(NXDNRadioIdentifier.createFrom(101), NXDNRadioIdentifier.createFrom(202),
                new NXDNTalkerAliasIdentifier("NXDN UNIT")));
    }

    public static AudioRecording recording(Identifier<?>... identifiers)
    {
        return new AudioRecording(null, List.of(), new IdentifierCollection(List.of(identifiers)), 0, 0);
    }

    public static Sample canonicalWithoutNumericSource()
    {
        return new Sample(APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x123, 900_001),
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x123, 900_002),
            P25TalkerAliasIdentifier.create("CANONICAL RADIO"));
    }

    public record Sample(RadioIdentifier source, RadioIdentifier other, TalkerAliasIdentifier raw)
    {
        public TalkerAliasIdentifier owned()
        {
            return raw.bindTo(source);
        }
    }
}
