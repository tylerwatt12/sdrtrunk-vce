/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.audio.playback;

import io.github.dsheirer.audio.AudioEvent;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.log.LoggingSuppressor;
import io.github.dsheirer.preference.playback.PlayTestAudioRequest;
import io.github.dsheirer.sample.Listener;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/**
 * Audio output/playback channel for a single audio mixer channel. Providers supply playable calls and broadcast
 * playback metadata to registered listeners (for example GUI components).
 */
public class AudioOutput implements LineListener
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioOutput.class);
    private static final LoggingSuppressor LOGGING_SUPPRESSOR = new LoggingSuppressor(mLog);
    private static final int BUFFER_SIZE_SAMPLES = 1000; //At 8 kHz audio
    private final AudioPlaybackDeviceDescriptor mAudioPlaybackDeviceDescriptor;
    private final AudioProvider mAudioProvider;
    private FloatControl mGainControl;
    private ScheduledExecutorService mScheduledExecutorService;
    private ScheduledFuture<?> mProcessorFuture;
    private SourceDataLine mSourceDataLine;
    private boolean mCanProcessAudio = false;
    private boolean mRunning = false;
    private volatile boolean mMuted;
    private volatile Listener<PlaybackAudioFrame> mPlaybackAudioListener;


    /**
     * Audio output for the selected audio playback device and provider.  Opens a SourceDataLine from the miser for the
     * audio format specified in the descriptor.  Employs a single threaded executor to process audio 10x a second and
     * manages the start/stop control for the dataline based on audio availability.
     *
     * @param descriptor for the mixer and audio format
     * @param audioProvider for access to audio from playable calls
     */
    public AudioOutput(AudioPlaybackDeviceDescriptor descriptor, AudioProvider audioProvider)
    {
        mAudioProvider = audioProvider;
        Mixer mixer = AudioSystem.getMixer(descriptor.getMixerInfo());

        if(mixer == null)
        {
            List<AudioPlaybackDeviceDescriptor> descriptors = AudioPlaybackDeviceManager.getAudioPlaybackDevices();

            int selected = 0;

            while((mixer == null) && (selected < descriptors.size()))
            {
                descriptor = descriptors.get(selected++);
                mixer = AudioSystem.getMixer(descriptor.getMixerInfo());
            }
        }

        mAudioPlaybackDeviceDescriptor = descriptor;

        if(mixer != null)
        {
            try
            {
                mSourceDataLine = (SourceDataLine) mixer.getLine(new DataLine.Info(SourceDataLine.class,
                        descriptor.getAudioFormat()));

                if(mSourceDataLine != null)
                {
                    mSourceDataLine.addLineListener(this);
                    initializeGainControl(mixer);

                    mCanProcessAudio = true;
                }
            }
            catch(LineUnavailableException e)
            {
                mLog.error("Couldn't open source data line for mixer: " + descriptor.getMixerInfo().getName(), e);
            }

            if(mCanProcessAudio)
            {
                openSourceDataLine();

                //The audio provider gives us audio 160 samples per interval representing 20 milliseconds of audio.
                // Run the scheduled executor at just slightly faster than that time, recognizing that it will
                // block against the mixer source data line until it can write all 160 samples.
                mScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new NamingThreadFactory(
                        "sdrtrunk audio output " + descriptor.getMixerInfo().getName()));
                resumeProcessing();
            }
            else
            {
                //Disable each audio channel so it stops queueing playable calls.
                for(AudioChannel audioChannel: mAudioProvider.getAudioChannels())
                {
                    audioChannel.setDisabled(true);
                }
            }
        }
    }

    private void initializeGainControl(Mixer mixer)
    {
        try
        {
            Control gain = mSourceDataLine.getControl(FloatControl.Type.MASTER_GAIN);
            mGainControl = (FloatControl)gain;
        }
        catch(IllegalArgumentException iae)
        {
            LOGGING_SUPPRESSOR.error("no gain control", 2, "Couldn't obtain " +
                "MASTER GAIN control for stereo line [" + mixer.getMixerInfo().getName() + "]");
        }
    }

    /**
     * Opens and starts the source data line.
     */
    private void openSourceDataLine()
    {
        try
        {
            if(mSourceDataLine != null && !mSourceDataLine.isOpen())
            {
                int bufferSize = BUFFER_SIZE_SAMPLES * 2 * mAudioPlaybackDeviceDescriptor.getAudioFormat().getChannels();
                mSourceDataLine.open(mAudioProvider.getAudioFormat(), bufferSize);
                bufferSize = mSourceDataLine.getBufferSize();
                //Fill the line with silence so we can start it
                mSourceDataLine.write(new byte[bufferSize], 0, bufferSize);
                if(!mMuted)
                {
                    mSourceDataLine.start();
                }

                for(AudioChannel audioChannel: mAudioProvider.getAudioChannels())
                {
                    audioChannel.setDisabled(false);
                }
            }
        }
        catch(LineUnavailableException e)
        {
            LOGGING_SUPPRESSOR.error("Can't open", 3, "Error opening source data line", e);
            mRunning = false;

            for(AudioChannel audioChannel: mAudioProvider.getAudioChannels())
            {
                audioChannel.setDisabled(true);
            }
        }
    }

    /**
     * Plays the test audio over the selected audio channel(s)
     * @param request to play test audio
     */
    public void playTestAudio(PlayTestAudioRequest request)
    {
        //If we're not running, resize the audio samples to fill the buffer enough to trigger playback.
        float[] audio = mRunning ? request.audio() : Arrays.copyOf(request.audio(), BUFFER_SIZE_SAMPLES);

        List<AudioChannel> channels = mAudioProvider.getAudioChannels();

        if(request.isAllChannels())
        {
            for(AudioChannel channel : channels)
            {
                channel.playTest(audio);
            }
        }
        else if(request.channel() < channels.size())
        {
            channels.get(request.channel()).playTest(audio);
        }
        else if(!channels.isEmpty())
        {
            channels.getFirst().playTest(audio);
        }
        else
        {
            LOGGING_SUPPRESSOR.info("No Audio Channels", 2,
                    "Unable to play test audio - no audio channels configured currently");
        }
    }

    /**
     * Audio playback device descriptor for this audio output
     */
    public AudioPlaybackDeviceDescriptor getAudioPlaybackDeviceDescriptor()
    {
        return mAudioPlaybackDeviceDescriptor;
    }

    /**
     * Audio provider managed by this audio output
     */
    public AudioProvider getAudioProvider()
    {
        return mAudioProvider;
    }

    /**
     * Listener for the final mixed PCM that is also sent to the local sound device.
     */
    public void setPlaybackAudioListener(Listener<PlaybackAudioFrame> listener)
    {
        mPlaybackAudioListener = listener;
    }

    /**
     * Prepares this audio output for disposal.
     */
    public void dispose()
    {
        if(mProcessorFuture != null)
        {
            mProcessorFuture.cancel(true);
        }
        mProcessorFuture = null;

        if(mScheduledExecutorService != null)
        {
            mScheduledExecutorService.shutdownNow();
            mScheduledExecutorService = null;
        }

        mAudioProvider.dispose();
        mCanProcessAudio = false;

        if(mSourceDataLine != null)
        {
            mSourceDataLine.stop();
            mSourceDataLine.flush();
            mSourceDataLine.close();
        }
        mSourceDataLine = null;
        mGainControl = null;
        mRunning = false;
    }

    /**
     * Mutes only the local speaker. The provider and final PCM listener remain active for independent web playback.
     */
    public void setMuted(boolean muted)
    {
        mMuted = muted;

        if(muted)
        {
            if(mSourceDataLine != null)
            {
                mSourceDataLine.stop();
                mSourceDataLine.flush();
            }
        }
        else
        {
            openSourceDataLine();

            if(mSourceDataLine != null && mSourceDataLine.isOpen() && !mSourceDataLine.isRunning())
            {
                mSourceDataLine.start();
            }
        }

        AudioEvent.Type type = muted ? AudioEvent.Type.AUDIO_MUTED : AudioEvent.Type.AUDIO_UNMUTED;
        mAudioProvider.notify(type);
    }

    /**
     * Clears all local playback state for each audio channel.
     */
    public void clearPlayback()
    {
        mAudioProvider.clearPlayback();

        if(mSourceDataLine != null)
        {
            mSourceDataLine.flush();
        }
    }

    /**
     * Pauses the output writer loop.
     */
    public void pauseProcessing()
    {
        if(mProcessorFuture != null)
        {
            mProcessorFuture.cancel(false);
            mProcessorFuture = null;
        }

        if(mSourceDataLine != null)
        {
            mSourceDataLine.stop();
            mSourceDataLine.flush();
        }
    }

    /**
     * Resumes the output writer loop.
     */
    public void resumeProcessing()
    {
        if(mCanProcessAudio && mScheduledExecutorService != null &&
            (mProcessorFuture == null || mProcessorFuture.isCancelled() || mProcessorFuture.isDone()))
        {
            openSourceDataLine();

            if(!mMuted && mSourceDataLine != null && mSourceDataLine.isOpen() && !mSourceDataLine.isRunning())
            {
                mSourceDataLine.start();
            }

            mProcessorFuture = mScheduledExecutorService.scheduleAtFixedRate(new AudioProcessor(),
                0, 19, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Current mute state for this audio output channel
     */
    public boolean isMuted()
    {
        return mMuted;
    }

    /**
     * Gain/volume control for this audio output channel, if one is available.
     */
    public FloatControl getGainControl()
    {
        return mGainControl;
    }

    /**
     * Indicates if this audio output has a gain control available
     */
    public boolean hasGainControl()
    {
        return mGainControl != null;
    }

    /**
     * Monitors the source data line playback state and broadcasts audio events to the registered listener as the
     * state changes
     */
    @Override
    public void update(LineEvent event)
    {
        LineEvent.Type type = event.getType();

        if(type == LineEvent.Type.START)
        {
            mAudioProvider.notify(AudioEvent.Type.AUDIO_STARTED);
        }
        else if(type == LineEvent.Type.STOP)
        {
            mAudioProvider.notify(AudioEvent.Type.AUDIO_STOPPED);
        }
    }

    /**
     * Audio Processor thread
     */
    public class AudioProcessor implements Runnable
    {
        private final AtomicBoolean mProcessing = new AtomicBoolean();
        private List<AudioPlaybackCall> mLastPlaybackCalls = List.of();

        /**
         * Process audio from the audio provider and writes it to the mixer's source data line. Calls to this method can
         * block on the source data line until it has capacity available to accept the audio.
         */
        private void processAudio()
        {
            Listener<PlaybackAudioFrame> playbackAudioListener = mPlaybackAudioListener;

            if(mSourceDataLine == null && playbackAudioListener == null)
            {
                LOGGING_SUPPRESSOR.error("null output", 2,
                    "Audio Output source data line is null - ignoring audio playback request");
                return;
            }

            List<AudioPlaybackCall> playing = playbackCalls();
            ByteBuffer buffer = mAudioProvider.getAudio();

            if(buffer != null)
            {
                if(playbackAudioListener != null)
                {
                    playbackAudioListener.receive(new PlaybackAudioFrame(
                        Arrays.copyOf(buffer.array(), buffer.array().length),
                        mAudioProvider.getAudioFormat().getChannels(), playing));
                    mLastPlaybackCalls = playing;
                }

                if(!mMuted && mSourceDataLine != null)
                {
                    //This is a blocking method call.
                    int wrote = mSourceDataLine.write(buffer.array(), 0, buffer.array().length);

                    //Around JDK 22 something started causing the source data line to fail to accept audio byte data via
                    //the write() method. Recycle the line when it stops accepting samples.
                    if(wrote <= 0)
                    {
                        LOGGING_SUPPRESSOR.info("Stalled Data Line", 3,
                            "Audio playback data line has stopped accepting samples - recycling to clear the error");
                        mSourceDataLine.close();
                        openSourceDataLine();
                    }
                }
            }
            else if(playbackAudioListener != null && !playing.equals(mLastPlaybackCalls))
            {
                playbackAudioListener.receive(new PlaybackAudioFrame(new byte[0],
                    mAudioProvider.getAudioFormat().getChannels(), playing));
                mLastPlaybackCalls = playing;
            }
        }

        private List<AudioPlaybackCall> playbackCalls()
        {
            List<AudioPlaybackCall> calls = new ArrayList<>();

            for(AudioChannel channel: mAudioProvider.getAudioChannels())
            {
                AudioPlaybackCall call = AudioPlaybackCall.from(channel.getChannelName(),
                    channel.getCurrentAudioCall());

                if(call != null)
                {
                    calls.add(call);
                }
            }

            return calls;
        }

        @Override
        public void run()
        {
            if(mProcessing.compareAndSet(false, true))
            {
                try
                {
                    processAudio();
                }
                catch(Exception e)
                {
                    mLog.error("Error while processing audio buffers", e);
                }

                mProcessing.set(false);
            }
        }
    }
}
