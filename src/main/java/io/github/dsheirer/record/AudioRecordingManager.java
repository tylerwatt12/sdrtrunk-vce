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

package io.github.dsheirer.record;

import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.string.StringIdentifier;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneIdentifier;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.util.StringUtils;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records completed immutable audio calls that have been flagged as recordable.
 */
public class AudioRecordingManager
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioRecordingManager.class);
    private LinkedTransferQueue<CompletedAudioCall> mCompletedAudioCallQueue = new LinkedTransferQueue<>();
    private ScheduledFuture<?> mQueueProcessorHandle;
    private UserPreferences mUserPreferences;
    private int mUnknownAudioRecordingIndex = 1;
    private int mDuplicateAudioRecordingSuffix = 1;
    private String mPreviousRecordingPath = null;

    /**
     * Constructs an instance
     * @param userPreferences to determine audio recording format
     */
    public AudioRecordingManager(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
    }

    /**
     * Starts the manager and begins audio segment recording.
     */
    public void start()
    {
        if(mQueueProcessorHandle == null)
        {
            mQueueProcessorHandle = ThreadPool.SCHEDULED.scheduleAtFixedRate(new QueueProcessor(),
                0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Stops the manager and records any remaining queued audio segments.
     */
    public void stop()
    {
        if(mQueueProcessorHandle != null)
        {
            mQueueProcessorHandle.cancel(true);
            processAudioSegments();
            mQueueProcessorHandle = null;
        }
    }

    /**
     * Processes any queued audio segments
     */
    private void processAudioSegments()
    {
        RecordFormat recordFormat = mUserPreferences.getRecordPreference().getAudioRecordFormat();
        CompletedAudioCall completedAudioCall = mCompletedAudioCallQueue.poll();

        while(completedAudioCall != null)
        {
            if(!(completedAudioCall.snapshot().duplicate() &&
                mUserPreferences.getCallManagementPreference().isDuplicateRecordingSuppressionEnabled()))
            {
                Path path = getAudioRecordingPath(completedAudioCall.snapshot().identifierCollection(), recordFormat);

                try
                {
                    AudioSegmentRecorder.write(completedAudioCall, path, recordFormat, mUserPreferences);
                }
                catch(IOException ioe)
                {
                    mLog.error("Error recording audio segment to [" + path.toString() + "]");
                }
            }

            //Grab the next one to record
            completedAudioCall = mCompletedAudioCallQueue.poll();
        }
    }

    public void receive(CompletedAudioCall completedAudioCall)
    {
        if(completedAudioCall != null && completedAudioCall.snapshot().recordAudio())
        {
            mCompletedAudioCallQueue.add(completedAudioCall);
        }
    }

    /**
     * Base path to recordings folder
     * @return
     */
    public Path getRecordingBasePath()
    {
        return mUserPreferences.getDirectoryPreference().getDirectoryRecording();
    }

    /**
     * Provides a formatted audio recording filename to use as the final audio filename.
     */
    private Path getAudioRecordingPath(IdentifierCollection identifierCollection, RecordFormat recordFormat)
    {
        StringBuilder sb = new StringBuilder();

        if(identifierCollection != null)
        {
            Identifier system = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY);

            if(system != null)
            {
                sb.append(((StringIdentifier)system).getValue()).append("_");
            }

            Identifier site = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, Form.SITE, Role.ANY);

            if(site != null)
            {
                sb.append(((StringIdentifier)site).getValue()).append("_");
            }

            Identifier channel = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY);

            if(channel != null)
            {
                sb.append(((StringIdentifier)channel).getValue()).append("_");
            }

            Identifier to = identifierCollection.getIdentifier(IdentifierClass.USER, Form.TALKGROUP, Role.TO);

            if(to != null)
            {
                sb.append("_TO_").append(clean(to.toString()));
            }
            else
            {
                List<Identifier> toIdentifiers = identifierCollection.getIdentifiers(Role.TO);

                if(!toIdentifiers.isEmpty())
                {
                    sb.append("_TO_").append(clean(toIdentifiers.get(0).toString()));
                }
            }

            Identifier from = identifierCollection.getIdentifier(IdentifierClass.USER, Form.RADIO, Role.FROM);

            if(from != null)
            {
                sb.append("_FROM_").append(clean(from.toString()));
            }
            else
            {
                List<Identifier> fromIdentifiers = identifierCollection.getIdentifiers(Role.FROM);

                if(!fromIdentifiers.isEmpty())
                {
                    for(Identifier identifier: fromIdentifiers)
                    {
                        if(identifier.getForm() != Form.TONE)
                        {
                            sb.append("_FROM_").append(clean(identifier.toString()));
                            break;
                        }
                    }
                }
            }

            List<Identifier> toneIdentifiers = identifierCollection.getIdentifiers(IdentifierClass.USER, Form.TONE);

            if(!toneIdentifiers.isEmpty())
            {
                try
                {
                    Identifier identifier = toneIdentifiers.get(0);

                    if(identifier instanceof ToneIdentifier)
                    {
                        ToneIdentifier toneIdentifier = (ToneIdentifier)identifier;
                        ToneSequence toneSequence = toneIdentifier.getValue();

                        if(toneSequence.hasTones())
                        {
                            sb.append("_TONES");

                            for(Tone tone: toneIdentifier.getValue().getTones())
                            {
                                String label = tone.getAmbeTone().toString();
                                label = label.replace("TONE", "").trim();
                                label = label.replace(" ", "_");
                                sb.append("_").append(label);
                            }
                        }
                    }
                }
                catch(Exception e)
                {
                    mLog.error("Error appending tones to audio recording filename");
                }
            }
        }
        else
        {
            sb.append("audio_recording_no_metadata_").append(mUnknownAudioRecordingIndex++);

            if(mUnknownAudioRecordingIndex < 0)
            {
                mUnknownAudioRecordingIndex = 1;
            }
        }

        StringBuilder sbFinal = new StringBuilder();
        sbFinal.append(TimeStamp.getTimeStamp("_")).append("_");

        //Remove any illegal filename characters
        String cleaned = StringUtils.replaceIllegalCharacters(sb.toString());

        //Ensure total length doesn't exceed 255 characters.  Allow room for timestamp, versioning and extension.
        int maxLength = 255 - sbFinal.length() - ("_V" + mDuplicateAudioRecordingSuffix).length() -
            recordFormat.getExtension().length();

        if(cleaned.length() > maxLength)
        {
            cleaned = cleaned.substring(0, maxLength);
        }

        sbFinal.append(cleaned);

        if(mPreviousRecordingPath != null && mPreviousRecordingPath.contentEquals(sbFinal.toString()))
        {
            sbFinal.append("_V").append(mDuplicateAudioRecordingSuffix++);
        }
        else
        {
            mDuplicateAudioRecordingSuffix = 2;
            mPreviousRecordingPath = sbFinal.toString();
        }

        sbFinal.append(recordFormat.getExtension());

        return getRecordingBasePath().resolve(sbFinal.toString());
    }

    public static String clean(String value)
    {
        if(value != null)
        {
            return value.replace(":", "")
                    .replace(".", "_")
                    .replace("(", "_")
                    .replace(")", "")
                    .replace("ROAM ", "")
                    .replace("ISSI ", "");
        }

        return null;
    }

    /**
     * Threaded queue processor to process/record each recordable audio segment
     */
    public class QueueProcessor implements Runnable
    {
        @Override
        public void run()
        {
            try
            {
                processAudioSegments();
            }
            catch(Exception e)
            {
                mLog.error("Error while processing queued audio segments to recordings", e);
            }
        }
    }
}
