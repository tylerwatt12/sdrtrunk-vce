/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurationManagerIdentityTest
{
    @Test
    void replacesDuplicatePersistedChannelIdentitiesBeforeStartup()
    {
        String duplicate = "11111111-2222-4333-8444-555555555555";
        Channel first = new Channel("First");
        Channel second = new Channel("Second");
        first.setConfigurationId(duplicate);
        second.setConfigurationId(duplicate);
        ConfigurationState state = new ConfigurationState();
        state.setChannels(List.of(first, second));

        assertTrue(ConfigurationManager.ensureUniqueChannelConfigurationIds(state));
        assertNotEquals(first.getConfigurationId(), second.getConfigurationId());
        assertFalse(first.isConfigurationIdPersistenceRequired());
        assertTrue(second.isConfigurationIdPersistenceRequired());
    }

    @Test
    void leavesUniquePersistedIdentitiesUnchanged()
    {
        Channel first = new Channel("First");
        Channel second = new Channel("Second");
        first.setConfigurationId("11111111-2222-4333-8444-555555555555");
        second.setConfigurationId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        ConfigurationState state = new ConfigurationState();
        state.setChannels(List.of(first, second));

        assertFalse(ConfigurationManager.ensureUniqueChannelConfigurationIds(state));
    }
}
