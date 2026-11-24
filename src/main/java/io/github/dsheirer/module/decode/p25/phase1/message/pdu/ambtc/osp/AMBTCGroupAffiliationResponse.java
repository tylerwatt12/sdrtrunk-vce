/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp;

import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentityResolutionEvidence;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25AnnouncementTalkgroup;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;
import io.github.dsheirer.module.decode.p25.reference.Response;
import java.util.ArrayList;
import java.util.List;

/**
 * Extended group affiliation response (TIA-102.AABC-E section 6.2.8.2, Figure 6.2.8-2).
 * The target is a working radio address; the fully qualified identity belongs to the group.
 * Uses the current documented layout without attempting to infer the historical AABC-B arrangement.
 */
public class AMBTCGroupAffiliationResponse extends AMBTCMessage
{
    private static final IntField HEADER_GROUP_WACN = IntField.length16(64);
    private static final IntField BLOCK_0_GROUP_WACN = IntField.length4(0);
    private static final IntField BLOCK_0_GROUP_SYSTEM = IntField.length12(4);
    private static final IntField BLOCK_0_GROUP_ID = IntField.length16(16);
    private static final IntField BLOCK_0_ANNOUNCEMENT_GROUP = IntField.length16(32);
    private static final IntField BLOCK_0_WORKING_GROUP_ID = IntField.length16(48);
    private static final IntField BLOCK_0_GAV = IntField.length2(70);

    private RadioIdentifier mTargetAddress;
    private APCO25FullyQualifiedTalkgroupIdentifier mGroupAddress;
    private TalkgroupIdentifier mAnnouncementGroup;
    private List<Identifier> mIdentifiers;

    public AMBTCGroupAffiliationResponse(PDUSequence PDUSequence, int nac, long timestamp)
    {
        super(PDUSequence, nac, timestamp);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageStub());
        sb.append(" AFFILIATION ").append(getAffiliationResponse());
        if(getTargetAddress() != null)
        {
            sb.append(" FOR RADIO:").append(getTargetAddress());
        }
        if(getGroupAddress() != null)
        {
            sb.append(" TO TALKGROUP:").append(getGroupAddress());
        }
        if(getAnnouncementGroup() != null)
        {
            sb.append(" ANNOUNCEMENT GROUP:").append(getAnnouncementGroup());
        }

        return sb.toString();
    }

    /**
     * Indicates the response status for the group affiliation request
     */
    public Response getAffiliationResponse()
    {
        if(hasDataBlock(0))
        {
            return Response.fromValue(getDataBlock(0).getMessage().getInt(BLOCK_0_GAV));
        }

        return Response.UNKNOWN;
    }

    public RadioIdentifier getTargetAddress()
    {
        if(mTargetAddress == null)
        {
            mTargetAddress = APCO25RadioIdentifier.createTo(getHeader().getMessage().getInt(HEADER_ADDRESS));
        }

        return mTargetAddress;
    }

    public APCO25FullyQualifiedTalkgroupIdentifier getGroupAddress()
    {
        if(mGroupAddress == null && isValidGroup(getGroupId()))
        {
            int workingGroup = getWorkingGroupId();
            mGroupAddress = isValidGroup(workingGroup) ?
                APCO25FullyQualifiedTalkgroupIdentifier.createWithLocalAddress(workingGroup, getGroupWacn(),
                    getGroupSystem(), getGroupId(), Role.ANY, IdentityResolutionEvidence.DIRECT) :
                APCO25FullyQualifiedTalkgroupIdentifier.createAny(getGroupId(), getGroupWacn(), getGroupSystem(),
                    getGroupId());
        }

        return mGroupAddress;
    }

    public int getGroupWacn()
    {
        return hasDataBlock(0) ? (getHeader().getMessage().getInt(HEADER_GROUP_WACN) << 4) |
            getDataBlock(0).getMessage().getInt(BLOCK_0_GROUP_WACN) : 0;
    }

    public int getGroupSystem()
    {
        return hasDataBlock(0) ? getDataBlock(0).getMessage().getInt(BLOCK_0_GROUP_SYSTEM) : 0;
    }

    public int getGroupId()
    {
        return hasDataBlock(0) ? getDataBlock(0).getMessage().getInt(BLOCK_0_GROUP_ID) : 0;
    }

    /** Independently carried local group number, including zero/reserved values. */
    public int getWorkingGroupId()
    {
        return hasDataBlock(0) ? getDataBlock(0).getMessage().getInt(BLOCK_0_WORKING_GROUP_ID) : 0;
    }

    public int getAnnouncementGroupId()
    {
        return hasDataBlock(0) ? getDataBlock(0).getMessage().getInt(BLOCK_0_ANNOUNCEMENT_GROUP) : 0;
    }

    public Identifier getAnnouncementGroup()
    {
        if(mAnnouncementGroup == null && isValidGroup(getAnnouncementGroupId()))
        {
            mAnnouncementGroup = APCO25AnnouncementTalkgroup.create(getAnnouncementGroupId());
        }

        return mAnnouncementGroup;
    }

    private static boolean isValidGroup(int group)
    {
        return group > 0 && group < 0xFFFF;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            if(getTargetAddress() != null)
            {
                mIdentifiers.add(getTargetAddress());
            }
            if(getGroupAddress() != null)
            {
                mIdentifiers.add(getGroupAddress());
            }
            if(getAnnouncementGroup() != null)
            {
                mIdentifiers.add(getAnnouncementGroup());
            }
        }

        return mIdentifiers;
    }
}
