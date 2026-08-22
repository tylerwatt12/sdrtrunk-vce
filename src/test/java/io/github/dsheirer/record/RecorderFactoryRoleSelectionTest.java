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

package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.audio.DMRCallSequenceRecorder;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.record.binary.BinaryRecorder;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.record.wave.ComplexSamplesWaveRecorder;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecorderFactoryRoleSelectionTest
{
    private static final long FREQUENCY = 451_012_500L;

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void standardChannelSelectsAllStandardRecorderKinds()
    {
        List<Module> modules = RecorderFactory.getRecorders(preferences(), channel(Channel.ChannelType.STANDARD,
            RecorderType.BASEBAND, RecorderType.DEMODULATED_BIT_STREAM, RecorderType.MBE_CALL_SEQUENCE));

        assertEquals(3, modules.size());
        assertInstanceOf(ComplexSamplesWaveRecorder.class, modules.get(0));
        assertInstanceOf(BinaryRecorder.class, modules.get(1));
        assertInstanceOf(DMRCallSequenceRecorder.class, modules.get(2));
    }

    @Test
    void trafficChannelSelectsAllTrafficRecorderKinds()
    {
        List<Module> modules = RecorderFactory.getRecorders(preferences(), channel(Channel.ChannelType.TRAFFIC,
            RecorderType.TRAFFIC_BASEBAND, RecorderType.TRAFFIC_DEMODULATED_BIT_STREAM,
            RecorderType.TRAFFIC_MBE_CALL_SEQUENCE));

        assertEquals(3, modules.size());
        assertInstanceOf(ComplexSamplesWaveRecorder.class, modules.get(0));
        assertInstanceOf(BinaryRecorder.class, modules.get(1));
        assertInstanceOf(DMRCallSequenceRecorder.class, modules.get(2));
    }

    @Test
    void standardChannelRejectsTrafficRecorderSettings()
    {
        List<Module> modules = RecorderFactory.getRecorders(preferences(), channel(Channel.ChannelType.STANDARD,
            RecorderType.TRAFFIC_BASEBAND, RecorderType.TRAFFIC_DEMODULATED_BIT_STREAM,
            RecorderType.TRAFFIC_MBE_CALL_SEQUENCE));

        assertTrue(modules.isEmpty());
    }

    @Test
    void trafficChannelRejectsStandardRecorderSettings()
    {
        List<Module> modules = RecorderFactory.getRecorders(preferences(), channel(Channel.ChannelType.TRAFFIC,
            RecorderType.BASEBAND, RecorderType.DEMODULATED_BIT_STREAM, RecorderType.MBE_CALL_SEQUENCE));

        assertTrue(modules.isEmpty());
    }

    private UserPreferences preferences()
    {
        return new TestUserPreferences(mTemporaryDirectory);
    }

    private static Channel channel(Channel.ChannelType channelType, RecorderType... recorderTypes)
    {
        Channel channel = new Channel("Role Selection", channelType);
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        SourceConfigTuner sourceConfiguration = new SourceConfigTuner();
        sourceConfiguration.setFrequency(FREQUENCY);
        channel.setSourceConfiguration(sourceConfiguration);
        RecordConfiguration recordConfiguration = new RecordConfiguration();

        for(RecorderType recorderType : recorderTypes)
        {
            recordConfiguration.addRecorder(recorderType);
        }

        channel.setRecordConfiguration(recordConfiguration);
        return channel;
    }

    private static class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path recordingDirectory)
        {
            mDirectoryPreference = new TestDirectoryPreference(recordingDirectory);
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }

    private static class TestDirectoryPreference extends DirectoryPreference
    {
        private final Path mRecordingDirectory;

        private TestDirectoryPreference(Path recordingDirectory)
        {
            super(preferenceType -> {});
            mRecordingDirectory = recordingDirectory;
        }

        @Override
        public Path getDirectoryRecording()
        {
            return mRecordingDirectory;
        }
    }
}
