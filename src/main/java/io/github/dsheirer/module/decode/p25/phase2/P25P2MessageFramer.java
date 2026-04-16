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

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.dsp.psk.pll.IPhaseLockedLoop;
import io.github.dsheirer.dsp.symbol.Dibit;
import io.github.dsheirer.dsp.symbol.ISyncDetectListener;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.log.ApplicationLog;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.MessageProviderModule;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.audio.P25P2AudioModule;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.module.decode.p25.phase2.message.P25P2Message;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.record.binary.BinaryReader;
import io.github.dsheirer.sample.Listener;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * P25 Sync Detector and Message Framer.  Includes capability to detect PLL out-of-phase lock errors
 * and issue phase corrections.
 */
public class P25P2MessageFramer implements Listener<Dibit>
{
   private P25P2SuperFrameDetector mSuperFrameDetector;

    /**
     * Constructs an instance
     * @param phaseLockedLoop for use with the super frame detector
     */
    public P25P2MessageFramer(IPhaseLockedLoop phaseLockedLoop)
    {
        mSuperFrameDetector = new P25P2SuperFrameDetector(phaseLockedLoop);
    }

    /**
     * Sets or updates the scramble parameters for the current channel
     * @param scrambleParameters
     */
    public void setScrambleParameters(ScrambleParameters scrambleParameters)
    {
        if(mSuperFrameDetector != null)
        {
            mSuperFrameDetector.setScrambleParameters(scrambleParameters);
        }
    }

    /**
     * Sets the sample rate for the sync detector
     */
    public void setSampleRate(double sampleRate)
    {
        mSuperFrameDetector.setSampleRate(sampleRate);
    }

    /**
     * Registers a sync detect listener to be notified each time sync is detected or lost.
     *
     * Note: this actually registers the listener on the enclosed super frame detector which has access to the
     * actual sync pattern detector instance.
     */
    public void setSyncDetectListener(ISyncDetectListener listener)
    {
        mSuperFrameDetector.setSyncDetectListener(listener);
    }

    public void setSyncObservationListener(P25P2SyncObservationListener listener)
    {
        mSuperFrameDetector.setSyncObservationListener(listener);
    }

    /**
     * Registers the listener for messages produced by this message framer
     *
     * @param messageListener to receive framed and decoded messages
     */
    public void setListener(Listener<IMessage> messageListener)
    {
        mSuperFrameDetector.setListener(messageListener);
    }

    /**
     * Primary method for streaming decoded symbol dibits for message framing.
     *
     * @param dibit to process
     */
    @Override
    public void receive(Dibit dibit)
    {
        mSuperFrameDetector.receive(dibit);
    }

    /**
     * Primary method for streaming decoded symbol byte arrays.
     *
     * @param buffer to process into a stream of dibits for processing.
     */
    public void receive(ByteBuffer buffer)
    {
        for(byte value : buffer.array())
        {
            for(int x = 0; x <= 3; x++)
            {
                receive(Dibit.parse(value, x));
            }
        }
    }
}
