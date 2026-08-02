/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.playback;

import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AudioChannelTest
{
    @Test
    void lateDoNotMonitorPolicyClearsBufferedOpeningBeforeOutput() throws Exception
    {
        AudioChannel channel = new AudioChannel(new UserPreferences(), "Test");

        try
        {
            ManagedPlayableAudioCall call = call(false, Priority.DEFAULT_PRIORITY);
            channel.play(call);
            getAudioBuffer(channel).add(new float[AudioChannel.SAMPLES_PER_INTERVAL]);
            call.updateSnapshot(snapshot(false, Priority.DO_NOT_MONITOR));

            assertNull(channel.getAudio(),
                "A rejected call must not leak its buffered start tone or opening voice frame");
        }
        finally
        {
            channel.dispose();
        }
    }

    @Test
    void oldBufferedToneDoesNotCountAsAudioFromNewCall() throws Exception
    {
        AudioChannel channel = new AudioChannel(new UserPreferences(), "Test");

        try
        {
            getAudioBuffer(channel).add(new float[AudioChannel.SAMPLES_PER_INTERVAL]);
            ManagedPlayableAudioCall call = call(false, Priority.DEFAULT_PRIORITY);
            channel.play(call);

            assertNotNull(channel.getAudio(), "Expected the older buffered audio to drain first");
            call.updateSnapshot(snapshot(false, Priority.DO_NOT_MONITOR));

            assertNull(channel.getAudio(),
                "Older buffered audio must not make a newly rejected call emit a drop tone");
        }
        finally
        {
            channel.dispose();
        }
    }

    @Test
    void duplicateRemainsAudibleWhenListeningSuppressionIsDisabled() throws Exception
    {
        AudioChannel channel = new AudioChannel(new UserPreferences(), "Test");

        try
        {
            setDropDuplicates(channel, false);
            ManagedPlayableAudioCall call = call(true, Priority.DEFAULT_PRIORITY);
            channel.play(call);

            assertNotNull(channel.getAudio(),
                "Duplicate detection must not mute calls when Listening suppression is disabled");
        }
        finally
        {
            channel.dispose();
        }
    }

    private static ManagedPlayableAudioCall call(boolean duplicate, int monitorPriority)
    {
        ManagedPlayableAudioCall call = new ManagedPlayableAudioCall(snapshot(duplicate, monitorPriority));
        call.appendAudio(new float[AudioChannel.SAMPLES_PER_INTERVAL]);
        return call;
    }

    private static AudioCallSnapshot snapshot(boolean duplicate, int monitorPriority)
    {
        List<Identifier> identifiers = List.of(SystemConfigurationIdentifier.create("Test System"),
            APCO25Talkgroup.create(1200));
        return new AudioCallSnapshot(new AudioCallId(1, 1, 0), null, null,
            new IdentifierCollection(identifiers), Set.of(), 1_000L, 2_000L, 1, 1,
            1_000L, 2_000L, true, false, false, true, monitorPriority, duplicate);
    }

    private static AudioChannel.AudioBuffer getAudioBuffer(AudioChannel channel) throws Exception
    {
        Field field = AudioChannel.class.getDeclaredField("mAudioBuffer");
        field.setAccessible(true);
        return (AudioChannel.AudioBuffer)field.get(channel);
    }

    private static void setDropDuplicates(AudioChannel channel, boolean dropDuplicates) throws Exception
    {
        Field field = AudioChannel.class.getDeclaredField("mDropDuplicates");
        field.setAccessible(true);
        field.setBoolean(channel, dropDuplicates);
    }
}
