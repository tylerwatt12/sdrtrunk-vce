/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.reference.MotorolaBsi;
import java.util.List;

/**
 * Motorola Phase 1 voice-carrier BSI, observed in TDULC opcode 0x05 / MFID 0x90.
 * The character layout is capture-verified; the final octet's meaning is unverified.
 * This metadata word does not identify a speaker or terminate a call.
 */
public class LCMotorolaBaseStationId extends LinkControlWord
{
    public LCMotorolaBaseStationId(CorrectedBinaryMessage message)
    {
        super(message);
    }

    public String getCallsign()
    {
        return MotorolaBsi.decodeCallsign(getMessage());
    }

    public int getRawFinalOctet()
    {
        return getMessage().getInt(64, 71);
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return List.of();
    }

    @Override
    public String toString()
    {
        return getMessageStub() + " BSI:" + getCallsign() + " RAW FINAL OCTET:" +
            getMessage().getHex(64, 71) + " MSG:" + getMessage().toHexString();
    }
}
