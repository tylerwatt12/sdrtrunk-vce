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

package io.github.dsheirer.module.decode.dmr.message.data.lc.full;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.Role;

/**
 * Aggregates a sequence of talker alias full link control messages to reconstruct the talker alias.
 */
public class TalkerAliasAssembler
{
    private final Assembly[] mAssemblies = {new Assembly(), new Assembly()};

    /** Clears both the fragments and the known call participants for this timeslot. */
    public void reset(int timeslot)
    {
        if(timeslot == 1 || timeslot == 2)
        {
            Assembly assembly = mAssemblies[timeslot - 1];
            assembly.clearFragments();
            assembly.source = null;
            assembly.target = null;
        }
    }

    /**
     * Voice link control is independent of the alias fragments. A changed known participant starts a new assembly
     * lifetime; repeated headers with an omitted participant retain the same known call context.
     */
    public boolean observeCall(FullLCMessage message)
    {
        if(message.getTimeslot() != 1 && message.getTimeslot() != 2) return false;
        boolean voice = switch(message.getOpcode())
        {
            case FULL_STANDARD_GROUP_VOICE_CHANNEL_USER, FULL_STANDARD_UNIT_TO_UNIT_VOICE_CHANNEL_USER,
                FULL_HYTERA_GROUP_VOICE_CHANNEL_USER, FULL_HYTERA_UNIT_TO_UNIT_VOICE_CHANNEL_USER,
                FULL_MOTOROLA_GROUP_VOICE_CHANNEL_USER, FULL_CAPACITY_PLUS_WIDE_AREA_VOICE_CHANNEL_USER,
                FULL_CAPACITY_MAX_GROUP_VOICE_CHANNEL_USER, FULL_CAPACITY_PLUS_ENCRYPTED_VOICE_CHANNEL_USER -> true;
            default -> false;
        };
        if(!voice) return false;
        Identifier source = null;
        Identifier target = null;
        for(Identifier identifier: message.getIdentifiers())
        {
            if(identifier.isValid() && identifier.getRole() == Role.FROM) source = identifier;
            if(identifier.isValid() && identifier.getRole() == Role.TO) target = identifier;
        }
        Assembly assembly = mAssemblies[message.getTimeslot() - 1];
        if((source != null && assembly.source != null && !source.equals(assembly.source)) ||
            (target != null && assembly.target != null && !target.equals(assembly.target)))
        {
            assembly.clearFragments();
        }
        if(source != null) assembly.source = source;
        if(target != null) assembly.target = target;
        return source != null || target != null;
    }

    /**
     * Each header begins one alias sequence. Blocks may arrive in any order after that header, but a block without a
     * header cannot be saved for a future radio or future alias. Completed fragments are consumed exactly once.
     */
    public TalkerAliasComplete process(FullLCMessage message)
    {
        if(message.getTimeslot() != 1 && message.getTimeslot() != 2) return null;
        Assembly assembly = mAssemblies[message.getTimeslot() - 1];
        if(message instanceof TalkerAliasHeader header)
        {
            assembly.clearFragments();
            assembly.header = header;
        }
        else if(assembly.header == null || message.getTimestamp() < assembly.header.getTimestamp())
        {
            return null;
        }
        else if(message instanceof TalkerAliasBlock1 block) assembly.block1 = block;
        else if(message instanceof TalkerAliasBlock2 block) assembly.block2 = block;
        else if(message instanceof TalkerAliasBlock3 block) assembly.block3 = block;
        else return null;

        TalkerAliasComplete complete = assemble(assembly.header, assembly.block1, assembly.block2, assembly.block3);
        if(complete != null) assembly.clearFragments();
        return complete;
    }

    private static class Assembly
    {
        private Identifier source;
        private Identifier target;
        private TalkerAliasHeader header;
        private TalkerAliasBlock1 block1;
        private TalkerAliasBlock2 block2;
        private TalkerAliasBlock3 block3;

        private void clearFragments()
        {
            header = null;
            block1 = null;
            block2 = null;
            block3 = null;
        }
    }

    /**
     * Assembles a complete alias from the header and block fragments.
     * @param header of the alias
     * @param block1 of the alias, optional
     * @param block2 of the alias, optional
     * @param block3 of the alias, optional
     * @return assembled alias or null.
     */
    private TalkerAliasComplete assemble(TalkerAliasHeader header,
                                         TalkerAliasBlock1 block1,
                                         TalkerAliasBlock2 block2,
                                         TalkerAliasBlock3 block3)
    {
        if(header != null)
        {
            int bitLength = header.getTotalBitLength();

            if(1 <= bitLength && bitLength <= 49)
            {
                return new TalkerAliasComplete(header.getPayloadFragment(), header.getFormat(), header.getCharacterLength(),
                        header.getTimestamp(), header.getTimeslot());
            }
            else if(50 <= bitLength && bitLength <= 105 && block1 != null)
            {
                CorrectedBinaryMessage message = new CorrectedBinaryMessage(105);
                message.load(0, header.getPayloadFragment());
                message.load(49, block1.getPayloadFragment());
                return new TalkerAliasComplete(message, header.getFormat(), header.getCharacterLength(),
                        header.getTimestamp(), header.getTimeslot());
            }
            else if(106 <= bitLength && bitLength <= 161 && block1 != null && block2 != null)
            {
                CorrectedBinaryMessage message = new CorrectedBinaryMessage(161);
                message.load(0, header.getPayloadFragment());
                message.load(49, block1.getPayloadFragment());
                message.load(105, block2.getPayloadFragment());
                return new TalkerAliasComplete(message, header.getFormat(), header.getCharacterLength(),
                        header.getTimestamp(), header.getTimeslot());
            }
            else if(162 <= bitLength && bitLength <= 217 && block1 != null && block2 != null && block3 != null)
            {
                CorrectedBinaryMessage message = new CorrectedBinaryMessage(217);
                message.load(0, header.getPayloadFragment());
                message.load(49, block1.getPayloadFragment());
                message.load(105, block2.getPayloadFragment());
                message.load(161, block3.getPayloadFragment());
                return new TalkerAliasComplete(message, header.getFormat(), header.getCharacterLength(),
                        header.getTimestamp(), header.getTimeslot());
            }
        }

        return null;
    }
}
