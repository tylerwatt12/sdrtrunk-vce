/*
 * *****************************************************************************
 *  Copyright (C) 2014-2020 Dennis Sheirer
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

package io.github.dsheirer.module.decode.dmr.message.type;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.dmr.identifier.DMRNetwork;
import io.github.dsheirer.module.decode.dmr.identifier.DMRSite;

/**
 * DMR Tier III System Identity Code structure parser
 */
public class SystemIdentityCode extends AbstractStructure
{
    private static final IntField MODEL = IntField.length2(0);
    private static final IntField TINY_NET = IntField.range(2, 10);
    private static final IntField TINY_SITE = IntField.range(11, 13);
    private static final IntField SMALL_NET = IntField.range(2, 8);
    private static final IntField SMALL_SITE = IntField.range(9, 13);
    private static final IntField LARGE_NET = IntField.length4(2);
    private static final IntField LARGE_SITE = IntField.range(6, 13);
    private static final IntField HUGE_NET = IntField.length2(2);
    private static final IntField HUGE_SITE = IntField.length10(4);
    private static final IntField PAR_SUBFIELD = IntField.length2(14);

    private DMRNetwork mNetwork;
    private DMRSite mSite;
    private boolean mHasPAR;

    /**
     * Constructs an instance
     * @param message containing the system identity code
     * @param offset into the message to the start of the structure
     * @param hasPAR indicates if the structure includes the two least significant bits for the PAR value.
     */
    public SystemIdentityCode(CorrectedBinaryMessage message, int offset, boolean hasPAR)
    {
        super(message, offset);
        mHasPAR = hasPAR;
    }

    /**
     * Indicates if this structure has the PAR field that reflects if the site has a single control channel or
     * two control channels and the mobile subscribers are divided into categories A and/or B.
     * @return
     */
    public boolean hasPAR()
    {
        return mHasPAR;
    }

    /**
     * PAR reflects if the site has a single control channel or two control channels and the mobile subscribers are
     * divided into categories A and/or B
     */
    public PAR getPAR()
    {
        if(hasPAR())
        {
            return PAR.fromValue(getMessage().getInt(PAR_SUBFIELD, getOffset()));
        }

        return PAR.UNKNOWN;
    }

    /**
     * Network model structure
     */
    public Model getModel()
    {
        return Model.fromValue(getMessage().getInt(MODEL, getOffset()));
    }

    /**
     * Network identifier
     */
    public DMRNetwork getNetwork()
    {
        if(mNetwork == null)
        {
            switch(getModel())
            {
                case TINY:
                    mNetwork = new DMRNetwork(getMessage().getInt(TINY_NET, getOffset()));
                    break;
                case SMALL:
                    mNetwork = new DMRNetwork(getMessage().getInt(SMALL_NET, getOffset()));
                    break;
                case LARGE:
                    mNetwork = new DMRNetwork(getMessage().getInt(LARGE_NET, getOffset()));
                    break;
                case HUGE:
                    mNetwork = new DMRNetwork(getMessage().getInt(HUGE_NET, getOffset()));
                    break;
                default:
                    break;
            }
        }

        return mNetwork;
    }

    /**
     * Site identifier
     */
    public DMRSite getSite()
    {
        if(mSite == null)
        {
            switch(getModel())
            {
                case TINY:
                    mSite = new DMRSite(getMessage().getInt(TINY_SITE, getOffset()));
                    break;
                case SMALL:
                    mSite = new DMRSite(getMessage().getInt(SMALL_SITE, getOffset()));
                    break;
                case LARGE:
                    mSite = new DMRSite(getMessage().getInt(LARGE_SITE, getOffset()));
                    break;
                case HUGE:
                    mSite = new DMRSite(getMessage().getInt(HUGE_SITE, getOffset()));
                    break;
                default:
                    break;
            }
        }

        return mSite;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getModel());
        sb.append(" NETWORK:").append(getNetwork().getValue());
        sb.append(" SITE:").append(getSite().getValue());
        return sb.toString();
    }
}
