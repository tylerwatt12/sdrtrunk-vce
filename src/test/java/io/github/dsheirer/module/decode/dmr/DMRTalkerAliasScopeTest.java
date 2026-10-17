/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.traffic.RadioSystemKeyEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DMRTalkerAliasScopeTest
{
    private static final String FIRST = "dmr:tier3:small:41";
    private static final String SECOND = "dmr:tier3:small:42";

    @Test
    void repeatedNativeKeyPreservesNamesButNewNetworkCannotReuseThem()
    {
        DMRTrafficChannelManager manager = manager();
        manager.updateNativeRadioSystemKey(FIRST);
        remember(manager, DmrTalkerAliasIdentifier.create("FIRST NETWORK"));
        TalkerAliasIdentifier first = enriched(manager, FIRST);
        assertEquals("FIRST NETWORK", first.getValue());
        manager.updateNativeRadioSystemKey(FIRST);
        assertEquals("FIRST NETWORK", enriched(manager, FIRST).getValue());

        manager.updateNativeRadioSystemKey(SECOND);
        assertNull(enriched(manager, SECOND));
        assertFalse(manager.getTalkerAliasManager().hasAlias(DMRRadio.createFrom(101), FIRST));
        remember(manager, first);
        assertNull(enriched(manager, SECOND), "a bound name from the old network cannot be relabelled");
        remember(manager, DmrTalkerAliasIdentifier.create("SECOND NETWORK"));
        assertEquals("SECOND NETWORK", enriched(manager, SECOND).getValue());
    }

    @Test
    void unknownGenerationAndRepeatedResetRetireCompleteNames()
    {
        DMRTrafficChannelManager manager = manager();
        manager.updateNativeRadioSystemKey(FIRST);
        remember(manager, DmrTalkerAliasIdentifier.create("KNOWN"));
        manager.updateNativeRadioSystemKey(null);
        assertNull(enriched(manager, null));
        remember(manager, DmrTalkerAliasIdentifier.create("UNKNOWN GENERATION"));
        assertEquals("UNKNOWN GENERATION", enriched(manager, null).getValue());
        manager.reset();
        assertNull(enriched(manager, null));
        remember(manager, DmrTalkerAliasIdentifier.create("NEXT UNKNOWN"));
        manager.stop();
        assertNull(enriched(manager, null));
    }

    @Test
    void cacheIsRetiredBeforeNewNativeKeyIsPublished()
    {
        DMRTrafficChannelManager manager = manager();
        manager.updateNativeRadioSystemKey(FIRST);
        remember(manager, DmrTalkerAliasIdentifier.create("FIRST NETWORK"));
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

    private static void remember(DMRTrafficChannelManager manager, TalkerAliasIdentifier alias)
    {
        MutableIdentifierCollection context = new MutableIdentifierCollection(1);
        context.update(DMRRadio.createFrom(101));
        manager.processTalkerAlias(null, alias, DMRRadio.createFrom(101), context, 1_000);
    }

    private static TalkerAliasIdentifier enriched(DMRTrafficChannelManager manager, String key)
    {
        MutableIdentifierCollection context = new MutableIdentifierCollection(1);
        context.update(DMRRadio.createFrom(101));
        manager.getTalkerAliasManager().enrichMutable(context, key);
        return TalkerAliasIdentifier.find(context);
    }

    private static DMRTrafficChannelManager manager()
    {
        Channel channel = new Channel("DMR scope", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(0);
        channel.setDecodeConfiguration(config);
        return new DMRTrafficChannelManager(channel);
    }
}
