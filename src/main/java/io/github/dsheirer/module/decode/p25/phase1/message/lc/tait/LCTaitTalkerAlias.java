/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase1.message.lc.tait;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlOpcode;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.reference.Vendor;
import java.util.List;

/**
 * Tait's observed Phase 1 format 0 carries eight ISO7 characters and no radio identity. The layout is derived
 * from existing decoder implementations, not a claim that all Tait firmware uses this form. A separate
 * identity message must not supply ownership without a proven association to this call.
 */
public class LCTaitTalkerAlias extends LinkControlWord
{
    private P25TalkerAliasIdentifier mTalkerAlias;

    public LCTaitTalkerAlias(CorrectedBinaryMessage message)
    {
        super(message);
    }

    /** Returns text only after parent FEC admission; ownership is established separately by the active call. */
    public P25TalkerAliasIdentifier getTalkerAlias()
    {
        if(getMessage().size() != 72 || !isValid() || isEncrypted() ||
            getVendor() != Vendor.TAIT || getOpcode() != LinkControlOpcode.TAIT_TALKER_ALIAS)
        {
            return null;
        }

        if(mTalkerAlias == null)
        {
            StringBuilder text = new StringBuilder(8);
            boolean padding = false;
            for(int index = 0; index < 8; index++)
            {
                int character = getMessage().getInt(16 + index * 7, 22 + index * 7);
                if(character == 0)
                {
                    padding = true;
                }
                else if(padding || character < 0x20 || character == 0x7F)
                {
                    //Reject controls and text after null padding instead of silently changing the transmitted name.
                    return null;
                }
                else
                {
                    text.append((char)character);
                }
            }
            if(text.toString().isBlank()) return null;
            mTalkerAlias = P25TalkerAliasIdentifier.create(text.toString());
        }
        return mTalkerAlias;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        //A source-less name must not reach call/audio/event consumers before call ownership is accepted.
        return List.of();
    }

    @Override
    public String toString()
    {
        P25TalkerAliasIdentifier alias = getTalkerAlias();
        return getMessageStub() + (alias != null ? " " + alias.getValue() : " TEXT UNAVAILABLE");
    }
}
