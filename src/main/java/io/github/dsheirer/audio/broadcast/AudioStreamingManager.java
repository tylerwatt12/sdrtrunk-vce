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

package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.AudioCallRecorder;
import io.github.dsheirer.record.RecordFormat;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams completed immutable audio calls by creating temporary recordings and enqueueing them for broadcast.
 */
public class AudioStreamingManager
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioStreamingManager.class);
    private LinkedTransferQueue<CompletedAudioCall> mNewAudioCalls = new LinkedTransferQueue<>();
    private List<CompletedAudioCall> mAudioCalls = new ArrayList<>();
    private Listener<AudioRecording> mAudioRecordingListener;
    private BroadcastFormat mBroadcastFormat;
    private UserPreferences mUserPreferences;
    private ScheduledFuture<?> mAudioSegmentProcessorFuture;
    private int mNextRecordingNumber = 1;

    /**
     * Constructs an instance
     * @param listener to receive completed audio recordings
     * @param broadcastFormat for temporary recordings
     * @param userPreferences to manage recording directories
     */
    public AudioStreamingManager(Listener<AudioRecording> listener, BroadcastFormat broadcastFormat, UserPreferences userPreferences)
    {
        mAudioRecordingListener = listener;
        mBroadcastFormat = broadcastFormat;
        mUserPreferences = userPreferences;
    }

    /**
     * Starts the scheduled completed-call processor.
     */
    public void start()
    {
        if(mAudioSegmentProcessorFuture == null)
        {
            mAudioSegmentProcessorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(new AudioSegmentProcessor(),
                0, 250, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops the scheduled completed-call processor.
     */
    public void stop()
    {
        if(mAudioSegmentProcessorFuture != null)
        {
            mAudioSegmentProcessorFuture.cancel(true);
            mAudioSegmentProcessorFuture = null;
        }

        mNewAudioCalls.clear();
        mAudioCalls.clear();
    }

    /**
     * Scheduled runnable to process completed calls.
     */
    public class AudioSegmentProcessor implements Runnable
    {
        /**
         * Creates a temporary streaming recording file path
         */
        private Path getTemporaryRecordingPath()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(BroadcastModel.TEMPORARY_STREAM_FILE_SUFFIX);

            //Check for integer overflow and readjust negative value to 0
            if(mNextRecordingNumber < 0)
            {
                mNextRecordingNumber = 1;
            }

            int recordingNumber = mNextRecordingNumber++;

            sb.append(recordingNumber).append("_");
            sb.append(TimeStamp.getLongTimeStamp("_"));
            sb.append(mBroadcastFormat.getFileExtension());

            return mUserPreferences.getDirectoryPreference().getDirectoryStreaming().resolve(sb.toString());
        }

        /**
         * Processes a completed call for streaming by creating a temporary MP3 recording and submitting the recording
         * to the specific broadcast channel(s).
         * @param completedAudioCall to process for streaming
         * @param identifierCollection to use for the streamed audio recording
         * @param broadcastChannels to receive the audio recording
         */
        private void processAudioCall(CompletedAudioCall completedAudioCall, IdentifierCollection identifierCollection,
                                         Set<BroadcastChannel> broadcastChannels)
        {
            Path path = getTemporaryRecordingPath();
            long length = completedAudioCall.getDuration();

            try
            {
                AudioCallRecorder.write(completedAudioCall, path, RecordFormat.MP3, mUserPreferences,
                    identifierCollection);

                AudioRecording audioRecording = new AudioRecording(path, broadcastChannels, identifierCollection,
                    completedAudioCall.snapshot().startTimestamp(), length);
                mAudioRecordingListener.receive(audioRecording);
            }
            catch(IOException ioe)
            {
                mLog.error("Error recording temporary stream MP3");
            }
        }

        /**
         * Main processing method to process completed calls.
         */
        private void processAudioSegments()
        {
            mNewAudioCalls.drainTo(mAudioCalls);

            Iterator<CompletedAudioCall> it = mAudioCalls.iterator();
            CompletedAudioCall completedAudioCall;
            while(it.hasNext())
            {
                completedAudioCall = it.next();

                if(completedAudioCall.snapshot().duplicate() &&
                    mUserPreferences.getCallManagementPreference().isDuplicateStreamingSuppressionEnabled())
                {
                    it.remove();
                }
                else
                {
                    it.remove();

                    if(mAudioRecordingListener != null && completedAudioCall.snapshot().hasBroadcastChannels())
                    {
                        IdentifierCollection identifiers =
                            new IdentifierCollection(completedAudioCall.snapshot().identifierCollection().getIdentifiers());

                        if(identifiers.getToIdentifier() instanceof PatchGroupIdentifier patchGroupIdentifier)
                        {
                            if(mUserPreferences.getCallManagementPreference()
                                .getPatchGroupStreamingOption() == PatchGroupStreamingOption.TALKGROUPS)
                            {
                                //Decompose the patch group into the individual (patched) talkgroups and process the
                                //completed call for each patched talkgroup.
                                PatchGroup patchGroup = patchGroupIdentifier.getValue();

                                List<Identifier> ids = new ArrayList<>();
                                ids.addAll(patchGroup.getPatchedTalkgroupIdentifiers());
                                ids.addAll(patchGroup.getPatchedRadioIdentifiers());

                                //If there are no patched radios/talkgroups, override user preference and stream as a patch group
                                if(ids.isEmpty() || completedAudioCall.snapshot().aliasList() == null)
                                {
                                    processAudioCall(completedAudioCall, identifiers,
                                        completedAudioCall.snapshot().broadcastChannels());
                                }
                                else
                                {
                                    AliasList aliasList = completedAudioCall.snapshot().aliasList();

                                    for(Identifier identifier: ids)
                                    {
                                        List<Alias> aliases = aliasList.getAliases(identifier);
                                        Set<BroadcastChannel> broadcastChannels = new HashSet<>();
                                        for(Alias alias: aliases)
                                        {
                                            broadcastChannels.addAll(alias.getBroadcastChannels());
                                        }

                                        if(!broadcastChannels.isEmpty())
                                        {
                                            MutableIdentifierCollection decomposedIdentifiers =
                                                new MutableIdentifierCollection(identifiers.getIdentifiers());
                                            //Remove patch group TO identifier & replace with the patched talkgroup/radio
                                            decomposedIdentifiers.remove(Role.TO);
                                            decomposedIdentifiers.update(identifier);
                                            processAudioCall(completedAudioCall, decomposedIdentifiers, broadcastChannels);
                                        }
                                    }
                                }
                            }
                            else
                            {
                                processAudioCall(completedAudioCall, identifiers,
                                    completedAudioCall.snapshot().broadcastChannels());
                            }
                        }
                        else
                        {
                            processAudioCall(completedAudioCall, identifiers,
                                completedAudioCall.snapshot().broadcastChannels());
                        }
                    }
                }
            }
        }

        @Override
        public void run()
        {
            try
            {
                processAudioSegments();
            }
            catch(Exception e)
            {
                mLog.error("Error processing completed audio calls for streaming", e);
            }
        }
    }

    public void receive(CompletedAudioCall completedAudioCall)
    {
        if(completedAudioCall != null)
        {
            mNewAudioCalls.add(completedAudioCall);
        }
    }
}
