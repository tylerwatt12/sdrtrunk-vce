/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.DMRTrafficChannelManager;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNTrafficChannelManager;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrafficChannelConfigurationIdentityTest
{
    private static final String CONFIGURATION_ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void p25TrafficPoolsInheritParentIdentity() throws Exception
    {
        Channel parent = parent();
        DecodeConfigP25Phase1 decode = new DecodeConfigP25Phase1();
        decode.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(decode);

        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);

        assertOwned(channels(manager, "mManagedPhase1TrafficChannels"));
        assertOwned(channels(manager, "mManagedPhase2TrafficChannels"));
    }

    @Test
    void dmrTrafficPoolInheritsParentIdentity() throws Exception
    {
        Channel parent = parent();
        DecodeConfigDMR decode = new DecodeConfigDMR();
        decode.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(decode);

        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);

        assertOwned(channels(manager, "mAllocatedTrafficChannels"));
    }

    @Test
    void nxdnTrafficPoolInheritsParentIdentity() throws Exception
    {
        Channel parent = parent();
        DecodeConfigNXDN decode = new DecodeConfigNXDN();
        decode.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(decode);

        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);

        assertOwned(channels(manager, "mManagedTrafficChannels"));
    }

    private static Channel parent()
    {
        Channel parent = new Channel("Control");
        parent.setConfigurationId(CONFIGURATION_ID);
        return parent;
    }

    private static void assertOwned(List<Channel> channels)
    {
        assertEquals(1, channels.size());
        assertEquals(CONFIGURATION_ID, channels.get(0).getConfigurationId());
    }

    @SuppressWarnings("unchecked")
    private static List<Channel> channels(Object manager, String fieldName) throws Exception
    {
        Field field = manager.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (List<Channel>)field.get(manager);
    }
}
