/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.TalkerAliasManager;
import io.github.dsheirer.identifier.alias.TalkerAliasManagerProvider;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.portable.PortablePreferencesTestSupport;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerTalkerAliasTest
{
    private static final String FIRST = "00000000-0000-0000-0000-000000000001";
    private static final String SECOND = "00000000-0000-0000-0000-000000000002";
    private static final RadioSystemIdentityKey.Identity IDENTITY = RadioSystemIdentityKey.parse("v1-r-x-x-501");

    @BeforeAll
    static void isolatePreferences() throws Exception
    {
        PortablePreferencesTestSupport.install();
    }

    @Test
    void fallbackForgetVisitsOnlyItsSavedOwnerAndAllowsFreshLearning() throws Exception
    {
        for(String protocol: List.of("dmr", "nxdn-c", "nxdn-d"))
        {
            var aliases = new AliasModel();
            var manager = new ChannelProcessingManager(null, null, aliases, new UserPreferences());
            var first = new TalkerAliasManager();
            var second = new TalkerAliasManager();
            RadioIdentifier radio = radio(protocol);
            String key = protocol + ":channel:" + FIRST;
            update(first, protocol, radio, "OLD", key);
            update(second, protocol, radio, "OLD", protocol + ":channel:" + SECOND);
            inject(manager, channel(protocol, FIRST), first, aliases);
            inject(manager, channel(protocol, SECOND), second, aliases);
            try
            {
                manager.forgetTalkerAlias(key, IDENTITY, "OLD");
                assertFalse(first.hasAlias(radio, key), protocol);
                assertTrue(second.hasAlias(radio, protocol + ":channel:" + SECOND), protocol);
                update(first, protocol, radio, "FRESH", key);
                manager.forgetTalkerAlias(key, IDENTITY, "OLD");
                assertTrue(first.hasAlias(radio, key), protocol);
                assertTrue(second.hasAlias(radio, protocol + ":channel:" + SECOND), protocol);
            }
            finally { manager.close(); }
        }
    }

    @Test
    void ambiguousSharedFallbackCacheIsPreservedWhileNativeForgetStaysExact() throws Exception
    {
        var aliases = new AliasModel();
        var manager = new ChannelProcessingManager(null, null, aliases, new UserPreferences());
        var shared = new TalkerAliasManager();
        var radio = DMRRadio.createFrom(501);
        String fallback = "dmr:channel:" + FIRST;
        String nativeKey = "dmr:tier3:small:17";
        shared.update(radio, DmrTalkerAliasIdentifier.create("OLD"), fallback);
        shared.update(radio, DmrTalkerAliasIdentifier.create("NATIVE"), nativeKey);
        inject(manager, channel("dmr", FIRST), shared, aliases);
        inject(manager, channel("dmr", SECOND), shared, aliases);
        try
        {
            manager.forgetTalkerAlias(fallback, IDENTITY, "OLD");
            assertTrue(shared.hasAlias(radio, fallback));
            assertTrue(shared.hasAlias(radio, nativeKey));
            manager.forgetTalkerAlias(nativeKey, IDENTITY, "NATIVE");
            assertFalse(shared.hasAlias(radio, nativeKey));
            assertTrue(shared.hasAlias(radio, fallback));
        }
        finally { manager.close(); }
    }

    @Test
    void sameSavedOwnerMayShareOneControlAndTrafficCache() throws Exception
    {
        var aliases = new AliasModel();
        var manager = new ChannelProcessingManager(null, null, aliases, new UserPreferences());
        AtomicInteger calls = new AtomicInteger();
        var cache = new TalkerAliasManager() {
            @Override public void forget(String system, RadioSystemIdentityKey.Identity identity, String expected)
            {
                calls.incrementAndGet();
                super.forget(system, identity, expected);
            }
        };
        var radio = DMRRadio.createFrom(501);
        String key = "dmr:channel:" + FIRST;
        cache.update(radio, DmrTalkerAliasIdentifier.create("OLD"), key);
        inject(manager, channel("dmr", FIRST), cache, aliases);
        var traffic = new Channel("Traffic fixture", Channel.ChannelType.TRAFFIC);
        traffic.setConfigurationId(FIRST);
        traffic.setDecodeConfiguration(new DecodeConfigDMR());
        inject(manager, traffic, cache, aliases);
        try
        {
            manager.forgetTalkerAlias(key, IDENTITY, "OLD");
            assertFalse(cache.hasAlias(radio, key));
            assertEquals(1, calls.get());
        }
        finally { manager.close(); }
    }

    private static RadioIdentifier radio(String protocol)
    {
        if(protocol.equals("dmr")) return DMRRadio.createFrom(501);
        var radio = NXDNRadioIdentifier.createFrom(501);
        radio.setTypeD(protocol.equals("nxdn-d"));
        return radio;
    }

    private static void update(TalkerAliasManager cache, String protocol, RadioIdentifier radio, String name, String key)
    {
        cache.update(radio, protocol.equals("dmr") ? DmrTalkerAliasIdentifier.create(name) :
            new NXDNTalkerAliasIdentifier(name), key);
    }

    private static Channel channel(String protocol, String id)
    {
        var channel = new Channel("Fixture");
        channel.setConfigurationId(id);
        channel.setDecodeConfiguration(protocol.equals("dmr") ? new DecodeConfigDMR() : new DecodeConfigNXDN());
        return channel;
    }

    @SuppressWarnings("unchecked")
    private static void inject(ChannelProcessingManager manager, Channel channel, TalkerAliasManager cache,
                               AliasModel aliases) throws Exception
    {
        var chain = new ProcessingChain(channel, aliases);
        chain.addModule(new CacheProvider(cache));
        Field field = ChannelProcessingManager.class.getDeclaredField("mProcessingChainsMap");
        field.setAccessible(true);
        ((Map<Channel,ProcessingChain>)field.get(manager)).put(channel, chain);
        chain.getEventBus().register(manager);
    }

    private static final class CacheProvider extends Module implements TalkerAliasManagerProvider
    {
        private final TalkerAliasManager cache;
        private CacheProvider(TalkerAliasManager cache) { this.cache = cache; }
        @Override public TalkerAliasManager getTalkerAliasManager() { return cache; }
        @Override public void reset() {}
        @Override public void start() {}
        @Override public void stop() {}
    }
}
