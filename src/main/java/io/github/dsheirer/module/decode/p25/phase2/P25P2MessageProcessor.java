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
package io.github.dsheirer.module.decode.p25.phase2;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.p25.P25FrequencyBandValidator;
import io.github.dsheirer.module.decode.p25.P25FrequencyBandConfirmationTracker;
import io.github.dsheirer.module.decode.p25.P25FrequencyBandPreloadDataContent;
import io.github.dsheirer.module.decode.p25.P25NACAuthority;
import io.github.dsheirer.module.decode.p25.phase1.P25P1NACPreloadDataContent;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBandReceiver;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.module.decode.p25.phase2.message.EncryptionSynchronizationSequence;
import io.github.dsheirer.module.decode.p25.phase2.message.EncryptionSynchronizationSequenceProcessor;
import io.github.dsheirer.module.decode.p25.phase2.message.P25P2Message;
import io.github.dsheirer.module.decode.p25.phase2.message.SuperFrameFragment;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructureMultiFragment;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MultiFragmentContinuationMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.NetworkStatusBroadcastExplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.NetworkStatusBroadcastImplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaTalkerAliasAssembler;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaTalkerAliasDataBlock;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaTalkerAliasHeader;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.AbstractSignalingTimeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.AbstractVoiceTimeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.DatchTimeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.Timeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.Voice2Timeslot;
import io.github.dsheirer.sample.Listener;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P25P2MessageProcessor implements Listener<IMessage>
{
    private static final Logger mLog = LoggerFactory.getLogger(P25P2MessageProcessor.class);

    private EncryptionSynchronizationSequenceProcessor mESSProcessor1 = new EncryptionSynchronizationSequenceProcessor(P25P2Message.TIMESLOT_1);
    private EncryptionSynchronizationSequenceProcessor mESSProcessor2 = new EncryptionSynchronizationSequenceProcessor(P25P2Message.TIMESLOT_2);
    private MacMessage mMacMessageWithMultiFragment1;
    private MacStructureMultiFragment mMacStructureMultiFragment1;
    private MacStructureMultiFragment mMacStructureMultiFragment2;
    private MotorolaTalkerAliasAssembler mMotorolaTalkerAliasAssembler1 = new MotorolaTalkerAliasAssembler(P25P2Message.TIMESLOT_1);
    private MotorolaTalkerAliasAssembler mMotorolaTalkerAliasAssembler2 = new MotorolaTalkerAliasAssembler(P25P2Message.TIMESLOT_2);
    private Listener<IMessage> mMessageListener;
    private final boolean mControlNACGuardEnabled;
    private final P25NACAuthority mControlNACAuthority = new P25NACAuthority();
    private Predicate<ScrambleParameters> mScrambleParametersListener;

    //Map of up to 16 band identifiers per RFSS.  These identifier update messages are inserted into any message that
    // conveys channel information so that the uplink/downlink frequencies can be calculated
    private Map<Integer,IFrequencyBand> mFrequencyBandMap = new TreeMap<>();
    private final P25FrequencyBandConfirmationTracker mFrequencyBandConfirmationTracker =
        new P25FrequencyBandConfirmationTracker();

    public P25P2MessageProcessor()
    {
        this(false);
    }

    public P25P2MessageProcessor(boolean controlNACGuardEnabled)
    {
        mControlNACGuardEnabled = controlNACGuardEnabled;
    }

    /**
     * Preloads frequency band (ie identifier update) content from the control channel when this is a traffic channel.
     * @param content to preload
     */
    public synchronized void preload(P25FrequencyBandPreloadDataContent content)
    {
        if(content.hasData())
        {
            for(IFrequencyBand frequencyBand: content.getData())
            {
                processFrequencyBand(frequencyBand, "preload");
            }
        }
    }

    @Override
    public synchronized void receive(IMessage message)
    {
        if(mMessageListener != null)
        {
            if(message instanceof SuperFrameFragment)
            {
                SuperFrameFragment sff = (SuperFrameFragment)message;
                List<Timeslot> timeslots = sff.getTimeslots();

                if(mControlNACGuardEnabled)
                {
                    ControlPrepassResult prepass = preAuthorizeControl(timeslots);

                    if(prepass == ControlPrepassResult.REJECTED)
                    {
                        return;
                    }

                    if(prepass == ControlPrepassResult.AUTHORIZED_WITH_SCRAMBLE)
                    {
                        //The fragment cached its scrambled timeslots before the authorized LCCH supplied the new
                        //sequence. Recreate them before any voice or signaling can reach a shared listener.
                        sff.resetTimeslots();
                        timeslots = sff.getTimeslots();
                    }
                }

                mMessageListener.receive(sff.getIISCH1());
                mMessageListener.receive(sff.getIISCH2());

                for(Timeslot timeslot: timeslots)
                {
                    if(timeslot instanceof DatchTimeslot)
                    {
                        mMessageListener.receive(timeslot);
                    }
                    else if(timeslot instanceof AbstractSignalingTimeslot)
                    {
                        AbstractSignalingTimeslot ast = (AbstractSignalingTimeslot)timeslot;

                        for(MacMessage macMessage: ast.getMacMessages())
                        {
                            switch(macMessage.getMacPduType())
                            {
                                case MAC_1_PTT:
                                case MAC_2_END_PTT:
                                case MAC_6_HANGTIME:
                                case MAC_3_IDLE:
                                    if (timeslot.getTimeslot() == P25P2Message.TIMESLOT_1)
                                    {
                                        mESSProcessor1.reset();
                                    }
                                    else
                                    {
                                        mESSProcessor2.reset();
                                    }
                                    break;
                            }

                            //Process any multi-fragment mac structures transmitted on the LCCH for reassembly
                            if(macMessage.getMacStructure() instanceof MacStructureMultiFragment mf)
                            {
                                if(macMessage.getTimeslot() == P25P2Message.TIMESLOT_1)
                                {
                                    mMacMessageWithMultiFragment1 = macMessage;
                                    mMacStructureMultiFragment1 = mf;
                                }
                                else
                                {
                                    mMacStructureMultiFragment2 = mf;
                                }
                            }
                            //Process multi-fragment continuation messages transmitted on the LCCH
                            else if(macMessage.getMacStructure() instanceof MultiFragmentContinuationMessage mfcm)
                            {
                                if(macMessage.getTimeslot() == P25P2Message.TIMESLOT_1)
                                {
                                    if(mMacStructureMultiFragment1 != null)
                                    {
                                        mMacStructureMultiFragment1.addContinuationMessage(mfcm);
                                        //Replace the continuation message with the assembled base message.
                                        macMessage.setMacStructure(mMacStructureMultiFragment1);

                                        //Cleanup if we're now complete
                                        if(mMacStructureMultiFragment1.isComplete())
                                        {
                                            mMacStructureMultiFragment1 = null;
                                            mMacMessageWithMultiFragment1 = null;
                                        }
                                    }
                                }
                                else
                                {
                                    if(mMacStructureMultiFragment2 != null)
                                    {
                                        mMacStructureMultiFragment2.addContinuationMessage(mfcm);
                                        //Replace the continuation message with the assembled base message.
                                        macMessage.setMacStructure(mMacStructureMultiFragment2);

                                        //Cleanup if we're now complete
                                        if(mMacStructureMultiFragment2.isComplete())
                                        {
                                            mMacStructureMultiFragment2 = null;
                                        }
                                    }
                                    /**
                                     * Single slot LCCH can transmit the continuation fragments on either timeslot
                                     * so we attempt to combine a continuation fragment from timeslot 2 onto the base
                                     * message from timeslot 1 and then resend the original timeslot 1 as the carrier
                                     * message and also push the timeslot 2 mac message and mac structure to listener.
                                     */
                                    else if(mMacMessageWithMultiFragment1 != null && mMacStructureMultiFragment1 != null)
                                    {
                                        mMacStructureMultiFragment1.addContinuationMessage(mfcm);
                                        //Re-broadcast the original timeslot 1 mac message with the updated structure
                                        mMessageListener.receive(mMacMessageWithMultiFragment1);

                                        //Cleanup if we're now complete
                                        if(mMacStructureMultiFragment1.isComplete())
                                        {
                                            mMacStructureMultiFragment1 = null;
                                            mMacMessageWithMultiFragment1 = null;
                                        }
                                    }
                                }
                            }
                            else
                            {
                                //If it's not a multi-fragment or continuation message, then we're not in assembly mode.
                                if(macMessage.getTimeslot() == P25P2Message.TIMESLOT_1)
                                {
                                    mMacStructureMultiFragment1 = null;
                                    mMacMessageWithMultiFragment1 = null;
                                }
                                else
                                {
                                    mMacStructureMultiFragment2 = null;
                                }
                            }

                            /* Insert frequency band identifier update messages into channel-type messages */
                            if(macMessage.getMacStructure() instanceof IFrequencyBandReceiver receiver)
                            {
                                List<IChannelDescriptor> channels = receiver.getChannels();

                                for(IChannelDescriptor channel : channels)
                                {
                                    P25FrequencyBandValidator.applyFrequencyBands(channel, mFrequencyBandMap);
                                }
                            }

                            //Store band identifiers so that they can be injected into channel type messages
                            if(macMessage.getMacStructure() instanceof IFrequencyBand bandIdentifier)
                            {
                                processFrequencyBand(bandIdentifier, String.valueOf(macMessage.getMacStructure().getOpcode()));
                            }

                            //Send the message to the listener
                            mMessageListener.receive(macMessage);

                            /**
                             * We reassemble Motorola talker alias messages here so that we can send the assembled
                             * message to message listener, after the fragment has been sent to the listener.
                             */
                            if(macMessage.getMacStructure() instanceof MotorolaTalkerAliasHeader ||
                               macMessage.getMacStructure() instanceof MotorolaTalkerAliasDataBlock)
                            {
                                if(macMessage.getTimeslot() == P25P2Message.TIMESLOT_1 &&
                                   mMotorolaTalkerAliasAssembler1.add(macMessage.getMacStructure(), macMessage.getTimestamp()))
                                {
                                    mMessageListener.receive(mMotorolaTalkerAliasAssembler1.assemble());
                                }
                                else if(macMessage.getTimeslot() == P25P2Message.TIMESLOT_2 &&
                                        mMotorolaTalkerAliasAssembler2.add(macMessage.getMacStructure(), macMessage.getTimestamp()))
                                {
                                    mMessageListener.receive(mMotorolaTalkerAliasAssembler2.assemble());
                                }
                            }
                        }
                    }
                    else if(timeslot instanceof AbstractVoiceTimeslot)
                    {
                        mMessageListener.receive(timeslot);

                        if(timeslot.getTimeslot() == P25P2Message.TIMESLOT_1)
                        {
                            mESSProcessor1.process((AbstractVoiceTimeslot)timeslot);

                            if(timeslot instanceof Voice2Timeslot)
                            {
                                EncryptionSynchronizationSequence ess = mESSProcessor1.getSequence();

                                if(ess != null)
                                {
                                    mMessageListener.receive(ess);
                                }

                                mESSProcessor1.reset();
                            }
                        }
                        else
                        {
                            mESSProcessor2.process((AbstractVoiceTimeslot)timeslot);

                            if(timeslot instanceof Voice2Timeslot)
                            {
                                EncryptionSynchronizationSequence ess = mESSProcessor2.getSequence();

                                if(ess != null)
                                {
                                    mMessageListener.receive(ess);
                                }

                                mESSProcessor2.reset();
                            }
                        }

                    }
                    else
                    {
                        mMessageListener.receive(timeslot);
                    }
                }
            }
            else if(message instanceof SyncLossMessage)
            {
                mMessageListener.receive(message);
            }
        }
    }

    /**
     * Validates each protected LCCH unit before any content from the enclosing fragment is emitted. Multiple MAC
     * structures in one physical LCCH count as one authority observation. The physical fragment position is the
     * discriminator because two independently protected units can share both a timestamp and logical timeslot.
     */
    private ControlPrepassResult preAuthorizeControl(List<Timeslot> timeslots)
    {
        boolean authorityEstablished = false;
        int fragmentNAC = P25NACAuthority.NO_NAC;
        ScrambleParameters scrambleParameters = null;

        for(int position = 0; position < timeslots.size(); position++)
        {
            Timeslot timeslot = timeslots.get(position);

            if(timeslot instanceof AbstractSignalingTimeslot signaling && timeslot.getDataUnitID().isLCCH())
            {
                List<MacMessage> macMessages = signaling.getMacMessages();

                if(macMessages.isEmpty())
                {
                    rejectControlSource();
                    return ControlPrepassResult.REJECTED;
                }

                int unitNAC = P25NACAuthority.NO_NAC;

                for(MacMessage macMessage : macMessages)
                {
                    if(!macMessage.isValid() || !macMessage.hasNAC() ||
                        !(macMessage.getNAC().getValue() instanceof Number number) ||
                        !P25P1NACPreloadDataContent.isConcreteNAC(number.intValue()))
                    {
                        rejectControlSource();
                        return ControlPrepassResult.REJECTED;
                    }

                    int messageNAC = number.intValue();

                    if(unitNAC != P25NACAuthority.NO_NAC && unitNAC != messageNAC)
                    {
                        rejectControlSource();
                        return ControlPrepassResult.REJECTED;
                    }

                    unitNAC = messageNAC;
                    ScrambleParameters candidate = getScrambleParameters(macMessage);

                    if(candidate != null)
                    {
                        if(candidate.getNAC() != messageNAC ||
                            (scrambleParameters != null && !matches(scrambleParameters, candidate)))
                        {
                            rejectControlSource();
                            return ControlPrepassResult.REJECTED;
                        }

                        scrambleParameters = candidate;
                    }
                }

                if(fragmentNAC != P25NACAuthority.NO_NAC && fragmentNAC != unitNAC)
                {
                    rejectControlSource();
                    return ControlPrepassResult.REJECTED;
                }

                fragmentNAC = unitNAC;
            }
        }

        if(fragmentNAC == P25NACAuthority.NO_NAC)
        {
            rejectControlSource();
            return ControlPrepassResult.REJECTED;
        }

        int authorityNAC = mControlNACAuthority.getNAC();

        if(authorityNAC != P25NACAuthority.NO_NAC && authorityNAC != fragmentNAC)
        {
            rejectControlSource();
            return ControlPrepassResult.REJECTED;
        }

        if(authorityNAC == P25NACAuthority.NO_NAC)
        {
            //Only count observations after the complete fragment has passed validation. This keeps a rejected
            //fragment from partially advancing the authority candidate.
            for(int position = 0; position < timeslots.size(); position++)
            {
                Timeslot timeslot = timeslots.get(position);

                if(timeslot instanceof AbstractSignalingTimeslot signaling && timeslot.getDataUnitID().isLCCH())
                {
                    int unitNAC = ((Number)signaling.getMacMessages().getFirst().getNAC().getValue()).intValue();
                    P25NACAuthority.Result result =
                        mControlNACAuthority.observe(unitNAC, timeslot.getTimestamp(), position);

                    if(result == P25NACAuthority.Result.REJECTED)
                    {
                        rejectControlSource();
                        return ControlPrepassResult.REJECTED;
                    }

                    authorityEstablished |= result == P25NACAuthority.Result.ESTABLISHED;
                }
            }
        }

        if(mControlNACAuthority.getNAC() != fragmentNAC)
        {
            rejectControlSource();
            return ControlPrepassResult.REJECTED;
        }

        if(authorityEstablished)
        {
            clearDecodedSourceState();
        }

        boolean updated = scrambleParameters != null && mScrambleParametersListener != null &&
            mScrambleParametersListener.test(scrambleParameters);
        return updated ? ControlPrepassResult.AUTHORIZED_WITH_SCRAMBLE : ControlPrepassResult.AUTHORIZED;
    }

    private static ScrambleParameters getScrambleParameters(MacMessage message)
    {
        if(message.getMacStructure() instanceof NetworkStatusBroadcastImplicit nsb)
        {
            return nsb.getScrambleParameters();
        }
        else if(message.getMacStructure() instanceof NetworkStatusBroadcastExplicit nsb)
        {
            return nsb.getScrambleParameters();
        }

        return null;
    }

    private static boolean matches(ScrambleParameters first, ScrambleParameters second)
    {
        return first.getWACN() == second.getWACN() && first.getSystem() == second.getSystem() &&
            first.getNAC() == second.getNAC();
    }

    public void setScrambleParametersListener(Predicate<ScrambleParameters> listener)
    {
        mScrambleParametersListener = listener;
    }

    /**
     * Clears all source-specific reassembly, band-plan and NAC state at an RF source boundary.
     */
    public synchronized void resetForSourceFrequencyChange()
    {
        mControlNACAuthority.reset();
        clearDecodedSourceState();
    }

    private void clearDecodedSourceState()
    {
        clearReassemblyState();
        mFrequencyBandMap.clear();
        mFrequencyBandConfirmationTracker.reset();
    }

    /**
     * Closes output and discards partial source content while retaining the frozen NAC and confirmed band plan.
     */
    private void rejectControlSource()
    {
        clearReassemblyState();
    }

    private void clearReassemblyState()
    {
        mESSProcessor1.reset();
        mESSProcessor2.reset();
        mMacMessageWithMultiFragment1 = null;
        mMacStructureMultiFragment1 = null;
        mMacStructureMultiFragment2 = null;
        mMotorolaTalkerAliasAssembler1.reset();
        mMotorolaTalkerAliasAssembler2.reset();
    }

    public synchronized void dispose()
    {
        resetForSourceFrequencyChange();
        mScrambleParametersListener = null;
        mMessageListener = null;
    }

    public void setMessageListener(Listener<IMessage> listener)
    {
        mMessageListener = listener;
    }

    public void removeMessageListener()
    {
        mMessageListener = null;
    }

    private void processFrequencyBand(IFrequencyBand frequencyBand, String source)
    {
        P25FrequencyBandConfirmationTracker.ObservationResult observation =
            mFrequencyBandConfirmationTracker.observe(mFrequencyBandMap, frequencyBand, "preload".equals(source));

        if(observation.pending())
        {
            return;
        }

        P25FrequencyBandValidator.RegistrationResult result = observation.registration();

        if(result.replaced())
        {
            mLog.warn("P25 P2 frequency band replacing existing source:{} existing:{} with candidate:{}",
                source, P25FrequencyBandValidator.describe(result.existing()),
                P25FrequencyBandValidator.describe(frequencyBand));
        }
        else if(!result.accepted())
        {
            mLog.warn("P25 P2 frequency band rejected source:{} {} correctedBits:{} - {}{}",
                source, P25FrequencyBandValidator.describe(frequencyBand),
                P25FrequencyBandValidator.getCorrectedBitCount(frequencyBand),
                result.rejectReason().getDescription(),
                result.existing() != null ? " existing:" + P25FrequencyBandValidator.describe(result.existing()) : "");
        }
    }

    private enum ControlPrepassResult
    {
        REJECTED,
        AUTHORIZED,
        AUTHORIZED_WITH_SCRAMBLE
    }
}
