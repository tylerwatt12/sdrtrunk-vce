/*
 * Copyright (C) 2014-2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.TimeslotMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlOpcode;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.reference.UserAliasText;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;

/** A complete fourteen-octet User Alias from one matching odd or even A/B conveyance. */
public class LCHarrisTalkerAliasComplete extends LinkControlWord implements IMessage
{
    private final TalkerAliasIdentifier mTalkerAlias;
    private final long mTimestamp;
    private final int mConveyanceIndex;

    public LCHarrisTalkerAliasComplete(LCHarrisTalkerAliasBase first, LCHarrisTalkerAliasBase second, long timestamp)
    {
        super(first.getMessage());
        mTimestamp = timestamp;
        mConveyanceIndex = first.getConveyanceIndex();
        byte[] payload = new byte[14];
        System.arraycopy(first.getPayloadFragment().toByteArray(), 0, payload, 0, 7);
        System.arraycopy(second.getPayloadFragment().toByteArray(), 0, payload, 7, 7);
        String text = UserAliasText.decode(payload);
        mTalkerAlias = text != null ? P25TalkerAliasIdentifier.create(text) : null;
        setValid(mTalkerAlias != null);
    }

    public int getConveyanceIndex() { return mConveyanceIndex; }
    public TalkerAliasIdentifier getTalkerAlias() { return mTalkerAlias; }
    @Override public String toString() { return "L3HARRIS TALKER ALIAS REASSEMBLED:" + mTalkerAlias; }
    @Override public LinkControlOpcode getOpcode() { return LinkControlOpcode.TALKER_ALIAS_COMPLETE; }
    @Override public int getOpcodeNumber() { return LinkControlOpcode.TALKER_ALIAS_COMPLETE.getCode(); }
    @Override public long getTimestamp() { return mTimestamp; }
    @Override public Protocol getProtocol() { return Protocol.APCO25; }
    @Override public int getTimeslot() { return TimeslotMessage.TIMESLOT_0; }
    @Override public List<Identifier> getIdentifiers() { return mTalkerAlias != null ? List.of(mTalkerAlias) : List.of(); }
}
