/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.playback.AudioPlaybackState;
import io.github.dsheirer.audio.playback.AudioPlaybackCall;
import io.github.dsheirer.audio.playback.IAudioPlaybackSession;
import io.github.dsheirer.audio.playback.PlaybackAudioFrame;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsLiveServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void streamsOneContinuousPlaybackMixWithoutCallIds() throws Exception
    {
        Path database = mTemporaryFolder.resolve("live.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestPlaybackSession playback = new TestPlaybackSession();
        StatsLiveService service = new StatsLiveService(
            new StatsWebDatabase(new UserPreferences(), database), playback, null);
        service.start();

        try(StatsLiveService.AudioSubscription subscription = service.subscribeAudio())
        {
            assertNotNull(subscription);
            PlaybackAudioFrame frame = toneFrame();

            for(int index = 0; index < 160; index++)
            {
                playback.emit(frame);
            }

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
            int bytes = 0;

            while(System.currentTimeMillis() < deadline && bytes == 0)
            {
                byte[] chunk = subscription.poll(250, TimeUnit.MILLISECONDS);

                if(chunk != null && !subscription.isEnd(chunk))
                {
                    bytes += chunk.length;
                }
            }

            assertTrue(bytes > 0);
            assertEquals(1, playback.mAudioListeners.size());
        }
        finally
        {
            service.close();
        }

        assertTrue(playback.mAudioListeners.isEmpty());
    }

    @Test
    void publishesNowMetadataOnTheEncodedAudioTimeline() throws Exception
    {
        Path database = mTemporaryFolder.resolve("timeline.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TestPlaybackSession playback = new TestPlaybackSession();
        StatsLiveService service = new StatsLiveService(
            new StatsWebDatabase(new UserPreferences(), database), playback, null);
        service.start();

        try(StatsLiveEventHub.Subscription playbackEvents = service.subscribePlayback();
            StatsLiveService.AudioSubscription ignored = service.subscribeAudio())
        {
            AudioPlaybackCall call = new AudioPlaybackCall("call-1", "Mono", "P25", "1001", "Car 1",
                "56132", "Dispatch", 854_187_500L, 1, false, 50);
            playback.emit(new PlaybackAudioFrame(toneFrame().pcm(), 1, List.of(call)));

            StatsLiveEventHub.LiveEvent event = playbackEvents.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals("audio_timeline", event.name());

            @SuppressWarnings("unchecked")
            List<Map<String,Object>> playing = (List<Map<String,Object>>)((Map<?,?>)event.data()).get("playing");
            assertEquals("Dispatch", playing.getFirst().get("target_alias"));
        }
        finally
        {
            service.close();
        }
    }

    private static PlaybackAudioFrame toneFrame()
    {
        ByteBuffer buffer = ByteBuffer.allocate(160 * 2).order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer samples = buffer.asShortBuffer();

        for(int index = 0; index < 160; index++)
        {
            samples.put((short)(Math.sin(2.0 * Math.PI * 440.0 * index / 8000.0) * Short.MAX_VALUE * 0.2));
        }

        return new PlaybackAudioFrame(buffer.array(), 1);
    }

    private static class TestPlaybackSession implements IAudioPlaybackSession
    {
        private final List<Listener<AudioPlaybackState>> mStateListeners = new ArrayList<>();
        private final List<Listener<PlaybackAudioFrame>> mAudioListeners = new ArrayList<>();
        private AudioPlaybackState mState = new AudioPlaybackState(false, List.of(), List.of(), null, null, List.of());

        @Override
        public AudioPlaybackState getPlaybackState()
        {
            return mState;
        }

        @Override
        public void addPlaybackStateListener(Listener<AudioPlaybackState> listener)
        {
            mStateListeners.add(listener);
            listener.receive(mState);
        }

        @Override
        public void removePlaybackStateListener(Listener<AudioPlaybackState> listener)
        {
            mStateListeners.remove(listener);
        }

        @Override
        public void addPlaybackAudioListener(Listener<PlaybackAudioFrame> listener)
        {
            mAudioListeners.add(listener);
        }

        @Override
        public void removePlaybackAudioListener(Listener<PlaybackAudioFrame> listener)
        {
            mAudioListeners.remove(listener);
        }

        @Override
        public boolean toggleHoldOnCurrentCall()
        {
            return false;
        }

        @Override
        public boolean avoidCurrentCall()
        {
            return false;
        }

        @Override
        public void clearAvoids()
        {
        }

        private void emit(PlaybackAudioFrame frame)
        {
            List.copyOf(mAudioListeners).forEach(listener -> listener.receive(frame));
        }
    }
}
