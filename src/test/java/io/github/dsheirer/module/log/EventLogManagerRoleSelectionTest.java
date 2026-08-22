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

package io.github.dsheirer.module.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventLogManagerRoleSelectionTest
{
    @Test
    void standardChannelSelectsOnlyStandardLoggers()
    {
        CapturingEventLogManager manager = new CapturingEventLogManager();
        List<Module> modules = manager.getLoggers(channel(Channel.ChannelType.STANDARD));

        assertEquals(List.of(EventLogType.CALL_EVENT, EventLogType.DECODED_MESSAGE), manager.getSelectedTypes());
        assertEquals(2, modules.size());
    }

    @Test
    void trafficChannelSelectsOnlyTrafficLoggers()
    {
        CapturingEventLogManager manager = new CapturingEventLogManager();
        List<Module> modules = manager.getLoggers(channel(Channel.ChannelType.TRAFFIC));

        assertEquals(List.of(EventLogType.TRAFFIC_CALL_EVENT, EventLogType.TRAFFIC_DECODED_MESSAGE),
            manager.getSelectedTypes());
        assertEquals(2, modules.size());
    }

    private static Channel channel(Channel.ChannelType channelType)
    {
        Channel channel = new Channel("Role Selection", channelType);
        EventLogConfiguration configuration = new EventLogConfiguration();
        configuration.addLogger(EventLogType.CALL_EVENT);
        configuration.addLogger(EventLogType.TRAFFIC_CALL_EVENT);
        configuration.addLogger(EventLogType.DECODED_MESSAGE);
        configuration.addLogger(EventLogType.TRAFFIC_DECODED_MESSAGE);
        configuration.addLogger(EventLogType.BINARY_MESSAGE);
        channel.setEventLogConfiguration(configuration);
        return channel;
    }

    private static class CapturingEventLogManager extends EventLogManager
    {
        private final List<EventLogType> mSelectedTypes = new ArrayList<>();

        private CapturingEventLogManager()
        {
            super(null, null);
        }

        @Override
        public EventLogger getLogger(EventLogType eventLogType, String prefix, long frequency)
        {
            mSelectedTypes.add(eventLogType);
            return new TestEventLogger();
        }

        private List<EventLogType> getSelectedTypes()
        {
            return List.copyOf(mSelectedTypes);
        }
    }

    private static class TestEventLogger extends EventLogger
    {
        private TestEventLogger()
        {
            super(Path.of("."), "role-selection.log", 0);
        }

        @Override
        public String getHeader()
        {
            return "";
        }

        @Override
        public void reset()
        {
        }
    }
}
