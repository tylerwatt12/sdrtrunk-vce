/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2018 Dennis Sheirer
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
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.passport;


import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.edac.CRC;
import io.github.dsheirer.edac.CRCPassport;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.Message;
import io.github.dsheirer.message.MessageType;
import io.github.dsheirer.module.decode.passport.identifier.PassportRadioId;
import io.github.dsheirer.module.decode.passport.identifier.PassportTalkgroup;
import io.github.dsheirer.protocol.Protocol;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class PassportMessage extends Message
{
    private static final String CHANNEL_LABEL = " CHAN:";
    private static final String FREE_LABEL = " FREE:";
    private static final String SITE_LABEL = " SITE:";
    private static final String TALKGROUP_LABEL = " TG:";

    private static final IntField DIGITAL_COLOR_CODE = IntField.length2(9);
    private static final IntField CHANNEL_NUMBER = IntField.range(11, 21);
    private static final IntField SITE = IntField.range(22, 28);
    private static final IntField GROUP = IntField.length16(29);
    private static final IntField RADIO_ID = IntField.range(22, 44);
    private static final IntField NEIGHBOR_BAND = IntField.length4(33);
    private static final IntField SITE_BAND = IntField.length4(41);
    private static final IntField TYPE = IntField.length4(45);
    private static final IntField FREE = IntField.range(49, 59);

    private CorrectedBinaryMessage mMessage;
    private CRC mCRC;
    private PassportMessage mIdleMessage;
    private PassportRadioId mFromIdentifier;
    private PassportTalkgroup mToIdentifier;
    private List<Identifier> mIdentifiers;

    public PassportMessage(CorrectedBinaryMessage message, PassportMessage idleMessage)
    {
        mMessage = CRCPassport.correct(message);
        mIdleMessage = idleMessage;
        mCRC = CRCPassport.check(mMessage);
    }

    public PassportMessage(CorrectedBinaryMessage message)
    {
        this(message, null);
    }

    public CorrectedBinaryMessage getMessage()
    {
        return mMessage;
    }

    public PassportTalkgroup getToIdentifier()
    {
        if(mToIdentifier == null)
        {
            mToIdentifier = PassportTalkgroup.create(getMessage().getInt(GROUP));
        }

        return mToIdentifier;
    }

    public PassportRadioId getFromIdentifier()
    {
        if(mFromIdentifier == null)
        {
            mFromIdentifier = PassportRadioId.create(getMessage().getInt(RADIO_ID));
        }

        return mFromIdentifier;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();

            if(hasFromIdentifier())
            {
                mIdentifiers.add(getFromIdentifier());
            }

            if(hasToIdentifier())
            {
                mIdentifiers.add(getToIdentifier());
            }
        }

        return mIdentifiers;
    }

    public boolean isValid()
    {
        return mCRC.passes();
    }

    public CRC getCRC()
    {
        return mCRC;
    }

    public MessageType getMessageType()
    {
        MessageType retVal = MessageType.UN_KNWN;

        int type = getMessageTypeNumber();
        int lcn = getLCN();

        switch(type)
        {
            case 0: //Group Call
                retVal = MessageType.CA_STRT;
                break;
            case 1:
                if(getFree() == 2042)
                {
                    retVal = MessageType.ID_TGAS;
                }
                else if(lcn < 1792)
                {
                    retVal = MessageType.CA_STRT;
                }
                else if(lcn == 1792 || lcn == 1793)
                {
                    retVal = MessageType.SY_IDLE;
                }
                else if(lcn == 2047)
                {
                    retVal = MessageType.CA_ENDD;
                }
                break;
            case 2:
                retVal = MessageType.CA_STRT;
                break;
            case 5:
                retVal = MessageType.CA_PAGE;
                break;
            case 6:
                retVal = MessageType.ID_RDIO;
                break;
            case 9:
                retVal = MessageType.DA_STRT;
                break;
            case 11:
                retVal = MessageType.RA_REGI;
                break;
            default:
                break;
        }

        return retVal;
    }


    public int getColorCode()
    {
        return getMessage().getInt(DIGITAL_COLOR_CODE);
    }

    public int getSite()
    {
        return getMessage().getInt(SITE);
    }

    public int getMessageTypeNumber()
    {
        return getMessage().getInt(TYPE);
    }

    public boolean hasToIdentifier()
    {
        return getMessageType() != MessageType.SY_IDLE;
    }

    public int getLCN()
    {
        return getMessage().getInt(CHANNEL_NUMBER);
    }

    public long getLCNFrequency()
    {
        return getSiteFrequency(getLCN());
    }

    public PassportBand getSiteBand()
    {
        return PassportBand.lookup(getMessage().getInt(SITE_BAND));
    }

    public PassportBand getNeighborBand()
    {
        return PassportBand.lookup(getMessage().getInt(NEIGHBOR_BAND));
    }

    public int getFree()
    {
        return getMessage().getInt(FREE);
    }

    public long getFreeFrequency()
    {
        return getSiteFrequency(getFree());
    }

    public long getNeighborFrequency()
    {
        if(getMessageType() == MessageType.SY_IDLE)
        {
            PassportBand band = getNeighborBand();

            return band.getFrequency(getFree());
        }

        return 0;
    }

    public boolean hasFromIdentifier()
    {
        return getMessageType() == MessageType.ID_RDIO;
    }

    /**
     * Pads an integer value with additional zeroes to make it decimalPlaces long
     */
    public String format(int number, int decimalPlaces)
    {
        return StringUtils.leftPad(Integer.toString(number), decimalPlaces, '0');
    }

    public String format(String val, int places)
    {
        return StringUtils.leftPad(val, places);
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.PASSPORT;
    }

    public long getSiteFrequency(int channel)
    {
        if(mIdleMessage != null && 0 < channel && channel < 1792)
        {
            PassportBand band = mIdleMessage.getSiteBand();

            if(band != PassportBand.BAND_UNKNOWN)
            {
                return band.getFrequency(channel);
            }
        }

        return 0;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("DCC:").append(getColorCode());

        switch(getMessageType())
        {
            case SY_IDLE:
                sb.append(" IDLE SITE:").append(format(getSite(), 3));
                sb.append(" NEIGHBOR:").append(format(getFree(), 3)).append("/").append(getFreeFrequency());
                break;
            case CA_PAGE:
                sb.append(" PAGING TG:").append(getToIdentifier());
                sb.append(SITE_LABEL).append(format(getSite(), 3));
                sb.append(CHANNEL_LABEL).append(format(getLCN(), 4)).append("/").append(getLCNFrequency());
                sb.append(FREE_LABEL).append(format(getFree(), 3)).append("/").append(getFreeFrequency());
                break;
            case CA_STRT:
                sb.append(" CALL TG:").append(getToIdentifier());
                sb.append(SITE_LABEL).append(format(getSite(), 3));
                sb.append(CHANNEL_LABEL).append(format(getLCN(), 4)).append("/").append(getLCNFrequency());
                sb.append(FREE_LABEL).append(format(getFree(), 3)).append("/").append(getFreeFrequency());
                break;
            case DA_STRT:
                sb.append(" ** DATA TG:").append(getToIdentifier());
                sb.append(SITE_LABEL).append(format(getSite(), 3));
                sb.append(CHANNEL_LABEL).append(format(getLCN(), 4)).append("/").append(getLCNFrequency());
                sb.append(FREE_LABEL).append(format(getFree(), 3)).append("/").append(getFreeFrequency());
                break;
            case CA_ENDD:
                sb.append(" END  TG:").append(getToIdentifier());
                sb.append(SITE_LABEL).append(format(getSite(), 3));
                sb.append(CHANNEL_LABEL).append(format(getLCN(), 4)).append("/").append(getLCNFrequency());
                sb.append(FREE_LABEL).append(format(getFree(), 3)).append("/").append(getFreeFrequency());
                break;
            case ID_RDIO:
                sb.append(" MOBILE ID MIN:").append(getFromIdentifier());
                sb.append(FREE_LABEL).append(format(getFree(), 3)).append("/").append(getFreeFrequency());
                break;
            case ID_TGAS:
                sb.append(" ASSIGN TALKGROUP:").append(getToIdentifier());
                sb.append(SITE_LABEL).append(format(getSite(), 3));
                sb.append(CHANNEL_LABEL).append(format(getLCN(), 4)).append("/").append(getLCNFrequency());
                break;
            case RA_REGI:
                sb.append(" RADIO REGISTER TG: ").append(getToIdentifier());
                break;
            default:
                sb.append(" UNKNOWN SITE:").append(format(getSite(), 3));
                sb.append(CHANNEL_LABEL).append(format(getLCN(), 4)).append("/").append(getLCNFrequency());
                sb.append(FREE_LABEL);
                int free = getFree();
                sb.append(format(free, 3));
                if(free > 0 && free < 896)
                {
                    sb.append("/");
                    sb.append(getFreeFrequency());
                }
                sb.append(" TYP:").append(format(getMessageTypeNumber(), 2));
                sb.append(TALKGROUP_LABEL).append(getToIdentifier());
                break;
        }

        sb.append(" MSG:").append(getMessage().toString());

        return sb.toString();
    }

    public boolean matches(PassportMessage otherMessage)
    {
        return this.getMessage().equals(otherMessage.getMessage());
    }
}
