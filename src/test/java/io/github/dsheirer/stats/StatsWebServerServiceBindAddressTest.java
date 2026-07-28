/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class StatsWebServerServiceBindAddressTest
{
    @Test
    void selectsLoopbackOrWildcardBinding()
    {
        InetSocketAddress localOnly = StatsWebServerService.createBindAddress(8090, false);
        InetSocketAddress anyIp = StatsWebServerService.createBindAddress(8090, true);

        assertTrue(localOnly.getAddress().isLoopbackAddress());
        assertTrue(anyIp.getAddress().isAnyLocalAddress());
        assertEquals(8090, localOnly.getPort());
        assertEquals(8090, anyIp.getPort());
    }
}
