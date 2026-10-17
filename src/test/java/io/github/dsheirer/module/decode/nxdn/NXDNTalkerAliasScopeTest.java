/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.traffic.RadioSystemKeyEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NXDNTalkerAliasScopeTest
{
    private static final String FIRST = "nxdn-c:local:303";
    private static final String SECOND = "nxdn-c:local:304";

    @Test
    void repeatedNativeKeyPreservesNamesButNewNetworkCannotReuseThem()
    {
        NXDNTrafficChannelManager manager = manager();
        manager.updateNativeRadioSystemKey(FIRST);
        remember(manager, new NXDNTalkerAliasIdentifier("FIRST NETWORK"));
        TalkerAliasIdentifier first = enriched(manager, FIRST);
        assertEquals("FIRST NETWORK", first.getValue());
        manager.updateNativeRadioSystemKey(FIRST);
        assertEquals("FIRST NETWORK", enriched(manager, FIRST).getValue());

        manager.updateNativeRadioSystemKey(SECOND);
        assertNull(enriched(manager, SECOND));
        assertFalse(manager.getTalkerAliasManager().hasAlias(NXDNRadioIdentifier.createFrom(101), FIRST));
        remember(manager, first);
        assertNull(enriched(manager, SECOND), "a bound name from the old network cannot be relabelled");
        remember(manager, new NXDNTalkerAliasIdentifier("SECOND NETWORK"));
        assertEquals("SECOND NETWORK", enriched(manager, SECOND).getValue());
    }

    @Test
    void unknownGenerationAndRepeatedResetRetireCompleteNames()
    {
        NXDNTrafficChannelManager manager = manager();
        manager.updateNativeRadioSystemKey(FIRST);
        remember(manager, new NXDNTalkerAliasIdentifier("KNOWN"));
        manager.updateNativeRadioSystemKey(null);
        assertNull(enriched(manager, null));
        remember(manager, new NXDNTalkerAliasIdentifier("UNKNOWN GENERATION"));
        assertEquals("UNKNOWN GENERATION", enriched(manager, null).getValue());
        manager.reset();
        assertNull(enriched(manager, null));
        remember(manager, new NXDNTalkerAliasIdentifier("NEXT UNKNOWN"));
        manager.stop();
        assertNull(enriched(manager, null));
    }

    @Test
    void cacheIsRetiredBeforeNewNativeKeyIsPublished()
    {
        NXDNTrafficChannelManager manager = manager();
        manager.updateNativeRadioSystemKey(FIRST);
        remember(manager, new NXDNTalkerAliasIdentifier("FIRST NETWORK"));
        EventBus bus = new EventBus();
        manager.setInterModuleEventBus(bus);
        List<String> observed = new ArrayList<>();
        bus.register(new Object()
        {
            @Subscribe public void receive(RadioSystemKeyEvent event)
            {
                assertNull(enriched(manager, FIRST));
                observed.add(event.radioSystemKey());
            }
        });

        manager.updateNativeRadioSystemKey(SECOND);

        assertEquals(List.of(SECOND), observed);
    }

    private static void remember(NXDNTrafficChannelManager manager, TalkerAliasIdentifier alias)
    {
        manager.processTalkerAlias(null, alias, NXDNRadioIdentifier.createFrom(101), 1_000);
    }

    private static TalkerAliasIdentifier enriched(NXDNTrafficChannelManager manager, String key)
    {
        MutableIdentifierCollection context = new MutableIdentifierCollection();
        context.update(NXDNRadioIdentifier.createFrom(101));
        manager.getTalkerAliasManager().enrichMutable(context, key);
        return TalkerAliasIdentifier.find(context);
    }

    private static NXDNTrafficChannelManager manager()
    {
        Channel channel = new Channel("NXDN scope", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setTrafficChannelPoolSize(0);
        channel.setDecodeConfiguration(config);
        return new NXDNTrafficChannelManager(channel);
    }
}
