/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.module.decode.p25.P25NACAuthority;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.reference.Direction;

/**
 * Stable NAC authority for a P25 Phase 1 trunked control channel.
 */
final class P25P1ControlNACAuthority
{
    private final P25NACAuthority mAuthority = new P25NACAuthority();

    Result observe(P25P1Message message)
    {
        Object value = message.getNAC().getValue();

        if(!(value instanceof Number number) || !P25P1NACPreloadDataContent.isConcreteNAC(number.intValue()))
        {
            return Result.REJECTED;
        }

        int nac = number.intValue();
        int authorityNAC = mAuthority.getNAC();

        if(authorityNAC != P25NACAuthority.NO_NAC)
        {
            return nac == authorityNAC && (message.isValid() || isControlFamily(message)) ?
                Result.AUTHORIZED : Result.REJECTED;
        }

        if(!isAuthorityCandidate(message))
        {
            return Result.REJECTED;
        }

        return switch(mAuthority.observe(nac, message.getTimestamp(), 0))
        {
            case ESTABLISHED -> Result.ESTABLISHED;
            case PENDING, DUPLICATE -> Result.CANDIDATE;
            case MATCH -> Result.AUTHORIZED;
            case REJECTED -> Result.REJECTED;
        };
    }

    int getNAC()
    {
        return mAuthority.getNAC();
    }

    void reset()
    {
        mAuthority.reset();
    }

    private static boolean isAuthorityCandidate(P25P1Message message)
    {
        if(!message.isValid())
        {
            return false;
        }

        if(message instanceof TSBKMessage tsbk)
        {
            return tsbk.getDUID() == P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1 &&
                tsbk.getDirection() == Direction.OUTBOUND;
        }

        return message instanceof AMBTCMessage ambtc && ambtc.getHeader().isValid() &&
            ambtc.getHeader().getDirection() == Direction.OUTBOUND;
    }

    static boolean isControlFamily(P25P1Message message)
    {
        return switch(message.getDUID())
        {
            case TRUNKING_SIGNALING_BLOCK_1, TRUNKING_SIGNALING_BLOCK_2, TRUNKING_SIGNALING_BLOCK_3,
                ALTERNATE_MULTI_BLOCK_TRUNKING_CONTROL, UNCONFIRMED_MULTI_BLOCK_TRUNKING_CONTROL -> true;
            default -> false;
        };
    }

    enum Result
    {
        REJECTED,
        CANDIDATE,
        ESTABLISHED,
        AUTHORIZED
    }
}
