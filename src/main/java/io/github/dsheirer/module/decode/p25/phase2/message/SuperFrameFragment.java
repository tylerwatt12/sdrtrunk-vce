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

package io.github.dsheirer.module.decode.p25.phase2.message;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.isch.IISCH;
import io.github.dsheirer.module.decode.p25.phase2.message.isch.ISCHDecoder;
import io.github.dsheirer.module.decode.p25.phase2.message.isch.SISCH;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.AbstractSignalingTimeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.FacchTimeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.SacchTimeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.ScramblingSequence;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.Timeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.TimeslotFactory;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * APCO25 Phase 2 SuperFrame fragment containing 4 timeslots.  Each superframe contains 12 timeslots and
 * this fragment represents 1/3 of a superframe.
 */
public class SuperFrameFragment implements IMessage
{
    /** TIA-102.BBAB clause 5.1 defines each physical radio slot as 30 ms. */
    private static final long RADIO_SLOT_DURATION_MILLISECONDS = 30L;
    private static final int CHANNEL_A_ISCH_START = 0;
    private static final int TIMESLOT_A_START = 40;

    private static final int CHANNEL_B_ISCH_START = 360;
    private static final int TIMESLOT_B_START = 400;

    private static final int CHANNEL_C_ISCH_START = 720;
    private static final int TIMESLOT_C_START = 760;

    private static final int CHANNEL_D_ISCH_START = 1080;
    private static final int TIMESLOT_D_START = 1120;
    private static final int TIMESLOT_D_END = 1440;

    private static final ISCHDecoder ISCH_DECODER = new ISCHDecoder();
    private long mTimestamp;
    private CorrectedBinaryMessage mMessage;
    private IISCH mIISCH1;
    private IISCH mIISCH2;
    private SISCH mSISCH1;
    private SISCH mSISCH2;
    private List<Timeslot> mTimeslots;
    private ScramblingSequence mScramblingSequence;
    private Timeslot[] mPhysicalTimeslots;
    private int mTimeslotOffset;


    /**
     * Constructs a fragment from the message with the specified timestamp.
     *
     * @param message containing 1440-bit super-frame fragment (ie 1/3 of a superframe)
     * @param timestamp of the final bit of this fragment
     */
    public SuperFrameFragment(CorrectedBinaryMessage message, long timestamp, ScramblingSequence scramblingSequence)
    {
        mMessage = message;
        mTimestamp = timestamp;
        mScramblingSequence = scramblingSequence;
    }

    /**
     * Transmitted binary message that represents this fragment
     */
    protected CorrectedBinaryMessage getMessage()
    {
        return mMessage;
    }


    /**
     * Unused.  Implements the parent class abstract method.
     */
    @Override
    public int getTimeslot()
    {
        return 0;
    }

    /**
     * Timestamp for the final bit of this fragment
     */
    @Override
    public long getTimestamp()
    {
        return mTimestamp;
    }

    /**
     * IISCH Timeslot 1
     */
    public IISCH getIISCH1()
    {
        if(mIISCH1 == null)
        {
            CorrectedBinaryMessage message = ISCH_DECODER.decode(getMessage()
                    .getSubMessage(CHANNEL_A_ISCH_START, TIMESLOT_A_START), P25P2Message.TIMESLOT_1);
            mIISCH1 = new IISCH(message, 0, 1, getTimestamp());
            mIISCH1.setValid(message.getCorrectedBitCount() < 3);
        }

        return mIISCH1;
    }

    /**
     * IISCH Timeslot 2
     */
    public IISCH getIISCH2()
    {
        if(mIISCH2 == null)
        {
            CorrectedBinaryMessage message = ISCH_DECODER.decode(getMessage()
                    .getSubMessage(CHANNEL_B_ISCH_START, TIMESLOT_B_START), P25P2Message.TIMESLOT_2);
            mIISCH2 = new IISCH(message, 0, 2, getTimestamp());
            mIISCH2.setValid(message.getCorrectedBitCount() < 3);
        }

        return mIISCH2;
    }

    /**
     * SISCH Timeslot 1
     */
    public SISCH getSISCH1()
    {
        if(mSISCH1 == null)
        {
            CorrectedBinaryMessage message = getMessage().getSubMessage(CHANNEL_C_ISCH_START, TIMESLOT_C_START);
            mSISCH1 = new SISCH(message, 0, 1, getTimestamp());
        }

        return mSISCH1;
    }

    /**
     * SISCH Timeslot 2
     */
    public SISCH getSISCH2()
    {
        if(mSISCH2 == null)
        {
            CorrectedBinaryMessage message = getMessage().getSubMessage(CHANNEL_D_ISCH_START, TIMESLOT_D_START);
            mSISCH2 = new SISCH(message, 0, 2, getTimestamp());
        }

        return mSISCH2;
    }

    /**
     * Reset the timeslots when the scrambling code has been updated so that any scrambled timeslots can be correctly
     * decoded.
     */
    public void resetTimeslots()
    {
        mTimeslots = null;
        mPhysicalTimeslots = null;
        mTimeslotOffset = 0;
    }

    /**
     * Timeslot A
     */
    public Timeslot getTimeslotA()
    {
        return getPhysicalTimeslots()[0];
    }

    /**
     * Timeslot B
     */
    public Timeslot getTimeslotB()
    {
        return getPhysicalTimeslots()[1];
    }

    /**
     * Timeslot C
     */
    public Timeslot getTimeslotC()
    {
        return getPhysicalTimeslots()[2];
    }

    /**
     * Timeslot D
     */
    public Timeslot getTimeslotD()
    {
        return getPhysicalTimeslots()[3];
    }

    public List<Timeslot> getTimeslots()
    {
        if(mTimeslots == null)
        {
            mTimeslots = new ArrayList<>();
            mTimeslots.add(getTimeslotA());
            mTimeslots.add(getTimeslotB());

            if(isFinalFragment())
            {
                mTimeslots.add(getTimeslotD());
                mTimeslots.add(getTimeslotC());
            }
            else
            {
                mTimeslots.add(getTimeslotC());
                mTimeslots.add(getTimeslotD());
            }
        }

        return mTimeslots;
    }

    /**
     * Channel 1 timeslots
     */
    public List<Timeslot> getChannel1Timeslots()
    {
        List<Timeslot> timeslots = new ArrayList<>();
        timeslots.add(getTimeslotA());

        if(isFinalFragment())
        {
            timeslots.add(getTimeslotD());
        }
        else
        {
            timeslots.add(getTimeslotC());
        }

        return timeslots;
    }

    /**
     * Channel 2 timeslots
     */
    public List<Timeslot> getChannel2Timeslots()
    {
        List<Timeslot> timeslots = new ArrayList<>();

        timeslots.add(getTimeslotB());

        if(isFinalFragment())
        {
            timeslots.add(getTimeslotC());
        }
        else
        {
            timeslots.add(getTimeslotD());
        }

        return timeslots;
    }

    /**
     * Extracts the timeslot from the parent message and creates a timeslot parser instance
     *
     * @param start bit of the timeslot
     * @param end bit for the message (exclusive)
     * @param index of the physical timeslot within this fragment (0-3)
     * @param timeslot logical channel (1 or 2)
     * @param offset of this fragment within the superframe (0, 4 or 8)
     * @return timeslot parser instance
     */
    private Timeslot getTimeslot(int start, int end, int index, int timeslot, int offset)
    {
        CorrectedBinaryMessage message = getMessage().getSubMessage(start, end);
        BinaryMessage timeslotSequence = mScramblingSequence.getTimeslotSequence(offset + index);
        long remainingSlots = 3L - index;
        long timeslotTimestamp = Math.max(1L,
            getTimestamp() - remainingSlots * RADIO_SLOT_DURATION_MILLISECONDS);
        return TimeslotFactory.getTimeslot(message, timeslotSequence, timeslot, timeslotTimestamp);
    }

    /**
     * Timeslot offset reported by the I-ISCH, or -1 when neither I-ISCH decodes.
     */
    private int getIschTimeslotOffset()
    {
        if(getIISCH1().isValid())
        {
            return getIISCH1().getIschSequence().getTimeslotOffset();
        }

        if(getIISCH2().isValid())
        {
            return getIISCH2().getIschSequence().getTimeslotOffset();
        }

        return -1;
    }

    /**
     * Scores decoded signaling: valid FACCH/SACCH messages passed RS and CRC and count 4; other formats count 1.
     */
    private int scoreTimeslots(Timeslot[] timeslots)
    {
        int score = 0;

        for(Timeslot timeslot : timeslots)
        {
            if(timeslot instanceof AbstractSignalingTimeslot signalingTimeslot)
            {
                //VCE rejects FACCH/SACCH after RS failure, so every valid MAC here passed both RS and CRC.
                int weight = timeslot instanceof FacchTimeslot || timeslot instanceof SacchTimeslot ? 4 : 1;

                for(MacMessage macMessage : signalingTimeslot.getMacMessages())
                {
                    if(macMessage.isValid())
                    {
                        score += weight;
                    }
                }
            }
        }

        return score;
    }

    /**
     * Creates physical slots for one candidate scrambling phase, with their logical ownership and timestamps.
     */
    private Timeslot[] createPhysicalTimeslots(int offset)
    {
        boolean finalFragment = offset == 8;
        return new Timeslot[]{
            getTimeslot(TIMESLOT_A_START, CHANNEL_B_ISCH_START, 0, 1, offset),
            getTimeslot(TIMESLOT_B_START, CHANNEL_C_ISCH_START, 1, 2, offset),
            getTimeslot(TIMESLOT_C_START, CHANNEL_D_ISCH_START, 2, finalFragment ? 2 : 1, offset),
            getTimeslot(TIMESLOT_D_START, TIMESLOT_D_END, 3, finalFragment ? 1 : 2, offset)
        };
    }

    /**
     * Resolves the scrambling phase using valid signaling, with I-ISCH breaking ties. Without a vote, retains
     * I-ISCH or offset zero when neither header decodes. The selected slot parsers are reused by all accessors.
     */
    private Timeslot[] getPhysicalTimeslots()
    {
        if(mPhysicalTimeslots == null)
        {
            int ischOffset = getIschTimeslotOffset();
            int fallback = ischOffset < 0 ? 0 : ischOffset;
            int best = fallback;
            //Evaluate the header/default first to retain its tie priority; other offsets stay in ascending order.
            Timeslot[] selected = createPhysicalTimeslots(fallback);
            boolean hasSignaling = false;

            for(Timeslot timeslot : selected)
            {
                if(timeslot instanceof AbstractSignalingTimeslot)
                {
                    hasSignaling = true;
                    break;
                }
            }

            //The factory selects each slot type before descrambling. Without signaling, no phase can supply a vote.
            if(hasSignaling)
            {
                int bestScore = scoreTimeslots(selected);

                for(int candidate = 0; candidate <= 8; candidate += 4)
                {
                    if(candidate == fallback)
                    {
                        continue;
                    }

                    Timeslot[] timeslots = createPhysicalTimeslots(candidate);
                    int score = scoreTimeslots(timeslots);

                    if(score > bestScore)
                    {
                        best = candidate;
                        bestScore = score;
                        selected = timeslots;
                    }
                }
            }

            //Publish the winning objects themselves, already carrying their actual slot ownership and timestamps.
            mTimeslotOffset = best;
            mPhysicalTimeslots = selected;
        }

        return mPhysicalTimeslots;
    }

    private int getTimeslotOffset()
    {
        getPhysicalTimeslots();
        return mTimeslotOffset;
    }

    /**
     * Indicates if this fragment is the final fragment (3 of 3) for a superframe which indicates that the final two
     * timeslots are inverted.
     */
    private boolean isFinalFragment()
    {
        //TIA-102.BBAC-A (2019), 3.1.1/Figure 1: physical slots 10/11 have inverted logical channel ownership.
        //Use the same resolved position for descrambling and routing, including when payload validation replaces
        //an unreadable or incorrect I-ISCH. With no payload vote the resolved offset retains the I-ISCH fallback.
        return getTimeslotOffset() == 8;
    }

    /**
     * Indicates if this fragment is valid
     */
    @Override
    public boolean isValid()
    {
        //TODO: can we do validity checking here??
        return true;
    }

    /**
     * Proocol for this fragment
     */
    @Override
    public Protocol getProtocol()
    {
        return Protocol.APCO25_PHASE2;
    }

    /**
     * No identifiers for this message
     */
    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }

    /**
     * Decoded representation of this fragment
     */
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getIISCH1());
        sb.append(" | ").append(getIISCH2());
        sb.append(" | Channel 1:").append(getChannel1Timeslots());
        sb.append(" | Channel 2:").append(getChannel2Timeslots());
        return sb.toString();
    }
}
