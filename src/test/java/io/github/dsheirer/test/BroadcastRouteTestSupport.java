/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.test;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Test-only helpers that keep legacy display-name fixtures separate from current stable provider routes. */
public final class BroadcastRouteTestSupport
{
    private BroadcastRouteTestSupport()
    {
    }

    public static BroadcastChannel legacyRoute(String name)
    {
        return new BroadcastChannel(name);
    }

    public static List<BroadcastChannel> legacyRoutes(String... names)
    {
        return Arrays.stream(names).map(BroadcastRouteTestSupport::legacyRoute).toList();
    }

    public static BroadcastChannel route(BroadcastConfiguration configuration)
    {
        return new BroadcastChannel(configuration.getConfigurationId(), configuration.getName());
    }

    /** Creates a deterministic current route for tests that do not own a broadcast configuration. */
    public static BroadcastChannel route(String name)
    {
        return new BroadcastChannel(configurationId(name), name);
    }

    public static String configurationId(String name)
    {
        return UUID.nameUUIDFromBytes(("test-broadcast-route:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static boolean hasRouteNamed(Alias alias, String name)
    {
        return alias.getBroadcastChannels().stream()
            .anyMatch(route -> name.equals(route.getChannelName()));
    }

    public static List<String> routeNames(UnmatchedTalkgroupPolicy policy)
    {
        return policy.getStreamDestinations().stream().map(BroadcastChannel::getChannelName).toList();
    }
}
