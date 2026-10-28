/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationChangeNotification;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannel;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNEncryptionKey;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.DataCallAssignment;
import io.github.dsheirer.module.decode.nxdn.layer3.call.ShortDataCallRequestHeader;
import io.github.dsheirer.module.decode.nxdn.layer3.data.GPS;
import io.github.dsheirer.module.decode.nxdn.layer3.data.PacketSequence;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallTimer;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallType;
import io.github.dsheirer.module.decode.nxdn.layer3.type.VoiceCallOption;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NXDNEventConfigurationContextTest
{
    private static final long CONTROL_FREQUENCY = 451_000_000L;
    private static final long VOICE_FREQUENCY = 452_000_000L;
    private static final long DATA_FREQUENCY = 453_000_000L;
    private static final NXDNRadioIdentifier RADIO = NXDNRadioIdentifier.createFrom(91);
    private static final EncryptionKeyIdentifier CLEAR = EncryptionKeyIdentifier.create(Protocol.NXDN,
        NXDNEncryptionKey.create(0, 0));

    @Test
    void voiceAndDataEventsResolveTheNamedParentAliasListWhenRadioIdsRepeat()
    {
        try(Fixture first = new Fixture(1, NXDNChannelMode.TRUNKED);
            Fixture second = new Fixture(2, NXDNChannelMode.TRUNKED))
        {
            for(Fixture fixture: List.of(first, second))
            {
                assertParentContext(fixture, fixture.voice(1_000L), VOICE_FREQUENCY);
                assertParentContext(fixture, fixture.data(2_000L), DATA_FREQUENCY);
            }
        }
    }

    @Test
    void gpsRetainsParentAliasAndCurrentSourceContextWithoutStaleCallUsers()
    {
        try(Fixture first = new Fixture(1, NXDNChannelMode.CONVENTIONAL);
            Fixture second = new Fixture(2, NXDNChannelMode.CONVENTIONAL))
        {
            for(Fixture fixture: List.of(first, second))
            {
                fixture.chain.broadcast(SourceEvent.frequencyChange(null, DATA_FREQUENCY));
                fixture.state.getIdentifierCollection().update(NXDNRadioIdentifier.createFrom(999));
                fixture.state.getIdentifierCollection().update(NXDNTalkgroupIdentifier.createTo(888));

                fixture.state.receive(gps());
                IDecodeEvent event = fixture.events.getLast();
                assertEquals(DecodeEventType.GPS, event.getEventType());
                assertParentIdentity(fixture, event);
                assertEquals(DATA_FREQUENCY, identifier(event, Form.CHANNEL_FREQUENCY).getValue());
                assertEquals(91, event.getIdentifierCollection().getFromIdentifier().getValue());
                assertFalse(event.getIdentifierCollection().getIdentifiers(IdentifierClass.USER).stream()
                    .anyMatch(identifier -> Integer.valueOf(999).equals(identifier.getValue()) ||
                        Integer.valueOf(888).equals(identifier.getValue())));
            }
        }
    }

    @Test
    void parentAliasRebindAndIncompatibleRemovalReachNewVoiceAndDataEvents()
    {
        try(Fixture fixture = new Fixture(1, NXDNChannelMode.TRUNKED))
        {
            fixture.voice(1_000L);
            AliasListDefinition replacement = definition(7, AliasListFamily.NXDN);
            fixture.aliases.replaceCommittedConfiguration(List.of(replacement), List.of(alias(replacement)));
            fixture.parent.setAliasListDefinition(replacement);
            fixture.chain.channelConfigurationChanged(new ChannelConfigurationChangeNotification(fixture.parent));
            //Separate calls exceed the tracker timeout; this does not redefine an already tracked call's context.
            IDecodeEvent rebound = fixture.data(10_000L);
            assertEquals(7, rebound.getIdentifierCollection().getAliasListConfiguration().getAliasListId());
            assertEquals("Radio 7", fixture.aliases.getAliasList(rebound.getIdentifierCollection())
                .getAliases(RADIO).getFirst().getName());

            AliasListDefinition incompatible = definition(8, AliasListFamily.P25);
            fixture.aliases.replaceCommittedConfiguration(List.of(replacement, incompatible), List.of(alias(replacement)));
            fixture.parent.setAliasListDefinition(incompatible);
            fixture.chain.channelConfigurationChanged(new ChannelConfigurationChangeNotification(fixture.parent));
            assertNull(fixture.voice(20_000L).getIdentifierCollection().getAliasListConfiguration());
            assertNull(fixture.data(30_000L).getIdentifierCollection().getAliasListConfiguration());
            assertEquals(fixture.parent.getConfigurationId(), identifier(rebound, Form.UNIQUE_ID).getValue());
            assertEquals(7, rebound.getIdentifierCollection().getAliasListConfiguration().getAliasListId(),
                "Already published events keep their captured alias identity");
        }
    }

    @Test
    void continuingVoiceAndDataCallsKeepCapturedContextAcrossParentRebind()
    {
        try(Fixture fixture = new Fixture(1, NXDNChannelMode.TRUNKED))
        {
            IDecodeEvent originalVoice = fixture.voice(1_000L);
            IDecodeEvent originalData = fixture.data(1_050L);
            AliasListDefinition replacement = definition(7, AliasListFamily.NXDN);
            fixture.aliases.replaceCommittedConfiguration(List.of(replacement), List.of(alias(replacement)));
            fixture.parent.setAliasListDefinition(replacement);
            fixture.parent.setName("Rebound channel");
            fixture.chain.channelConfigurationChanged(new ChannelConfigurationChangeNotification(fixture.parent));

            IDecodeEvent continuingVoice = fixture.voice(1_100L);
            IDecodeEvent continuingData = fixture.data(1_150L);
            assertSame(originalVoice, continuingVoice);
            assertSame(originalData, continuingData);
            for(IDecodeEvent continuing: List.of(continuingVoice, continuingData))
            {
                assertEquals(1, continuing.getIdentifierCollection().getAliasListConfiguration().getAliasListId());
                assertEquals("Channel 1", identifier(continuing, Form.CHANNEL).getValue());
            }

            //The next calls are beyond the tracker timeout and capture the newly validated parent binding.
            IDecodeEvent nextVoice = fixture.voice(10_000L);
            IDecodeEvent nextData = fixture.data(11_000L);
            assertNotSame(originalVoice, nextVoice);
            assertNotSame(originalData, nextData);
            for(IDecodeEvent next: List.of(nextVoice, nextData))
            {
                assertEquals(7, next.getIdentifierCollection().getAliasListConfiguration().getAliasListId());
                assertEquals("Rebound channel", identifier(next, Form.CHANNEL).getValue());
            }
        }
    }

    @Test
    void stopAndListenerDetachCannotKeepAnOldParentBinding()
    {
        try(Fixture fixture = new Fixture(1, NXDNChannelMode.TRUNKED))
        {
            IDecodeEvent oldVoice = fixture.voice(1_000L);
            IDecodeEvent oldData = fixture.data(1_050L);
            fixture.manager.stop();
            fixture.manager.start();
            fixture.chain.getChannelState().start();
            IDecodeEvent restartedVoice = fixture.voice(1_100L);
            IDecodeEvent restartedData = fixture.data(1_150L);
            assertNotSame(oldVoice, restartedVoice, "Stop must retire an active call even within its timeout");
            assertNotSame(oldData, restartedData);
            assertEquals(1_100L, restartedVoice.getTimeStart());
            assertEquals(1_150L, restartedData.getTimeStart());
            assertNotNull(restartedVoice.getIdentifierCollection().getAliasListConfiguration());
            assertNotNull(restartedData.getIdentifierCollection().getAliasListConfiguration());
            fixture.chain.removeModule(fixture.manager);
            fixture.manager.addDecodeEventListener(fixture.events::add);
            fixture.manager.stop();
            fixture.chain.getChannelState().start();
            assertNull(fixture.voice(3_000L).getIdentifierCollection().getAliasListConfiguration(),
                "The processing chain must remove the same listener instance it registered");
        }
    }

    private static void assertParentContext(Fixture fixture, IDecodeEvent event, long trafficFrequency)
    {
        assertParentIdentity(fixture, event);
        assertEquals(trafficFrequency, event.getChannelDescriptor().getDownlinkFrequency());
        assertNull(identifier(event, Form.CHANNEL_FREQUENCY),
            "A traffic event must not carry the parent's different control frequency");
        assertEquals(91, event.getIdentifierCollection().getFromIdentifier().getValue());
    }

    private static void assertParentIdentity(Fixture fixture, IDecodeEvent event)
    {
        IdentifierCollection identifiers = event.getIdentifierCollection();
        assertEquals(fixture.definition.getId(), identifiers.getAliasListConfiguration().getAliasListId());
        assertEquals(fixture.definition.getName(), identifiers.getAliasListConfiguration().getValue());
        assertEquals(fixture.parent.getConfigurationId(), identifier(event, Form.UNIQUE_ID).getValue());
        assertEquals(fixture.parent.getName(), identifier(event, Form.CHANNEL).getValue());
        assertEquals(fixture.parent.getSystem(), identifier(event, Form.SYSTEM).getValue());
        assertEquals(fixture.parent.getSite(), identifier(event, Form.SITE).getValue());
        assertEquals("Radio " + fixture.definition.getId(), fixture.aliases.getAliasList(identifiers)
            .getAliases(identifiers.getFromIdentifier()).getFirst().getName());
    }

    private static Identifier<?> identifier(IDecodeEvent event, Form form)
    {
        return event.getIdentifierCollection().getIdentifier(IdentifierClass.CONFIGURATION, form, Role.ANY);
    }

    private static AliasListDefinition definition(long id, AliasListFamily family)
    {
        AliasListDefinition definition = new AliasListDefinition("Aliases " + id, family);
        definition.setId(id);
        return definition;
    }

    private static Alias alias(AliasListDefinition definition)
    {
        Alias alias = new Alias("Radio " + definition.getId());
        alias.setId(definition.getId());
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Radio(Protocol.NXDN, 91));
        return alias;
    }

    private static NXDNChannel channel(long frequency)
    {
        NXDNChannelLookup channel = new NXDNChannelLookup(1);
        channel.receive(null, Map.of(1, new ChannelFrequency(1, frequency, frequency)));
        return channel;
    }

    private static GPS gps()
    {
        CorrectedBinaryMessage headerBits = new CorrectedBinaryMessage(176);
        headerBits.load(24, 16, 91);
        headerBits.load(40, 16, 101);
        ShortDataCallRequestHeader header = new ShortDataCallRequestHeader(headerBits, 1_000L,
            NXDNMessageType.CONTROL_OUT_56_CC_SHORT_DATA_CALL_REQUEST_HEADER, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1);
        CorrectedBinaryMessage payload = new CorrectedBinaryMessage(288);
        payload.load(152, 7, 26);
        payload.load(159, 4, 1);
        return new GPS(new PacketSequence(payload, header, CLEAR, null));
    }

    private static class Fixture implements AutoCloseable
    {
        private final AliasListDefinition definition;
        private final AliasModel aliases = new AliasModel();
        private final Channel parent;
        private final ProcessingChain chain;
        private final NXDNTrafficChannelManager manager;
        private final NXDNDecoderState state;
        private final List<IDecodeEvent> events = new ArrayList<>();

        private Fixture(long id, NXDNChannelMode mode)
        {
            definition = definition(id, AliasListFamily.NXDN);
            aliases.replaceCommittedConfiguration(List.of(definition), List.of(alias(definition)));
            parent = new Channel("Channel " + id);
            parent.setSystem("System " + id);
            parent.setSite("Site " + id);
            parent.setAliasListDefinition(definition);
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(CONTROL_FREQUENCY);
            parent.setSourceConfiguration(source);
            DecodeConfigNXDN config = new DecodeConfigNXDN();
            config.setChannelMode(mode);
            config.setTrafficChannelPoolSize(0);
            config.setIgnoreDataCalls(true);
            parent.setDecodeConfiguration(config);
            chain = new ProcessingChain(parent, aliases);
            manager = new NXDNTrafficChannelManager(parent);
            state = new NXDNDecoderState(parent, manager);
            chain.addModule(manager);
            chain.addModule(state);
            chain.addDecodeEventListener(events::add);
            chain.getChannelState().start();
        }

        private IDecodeEvent voice(long timestamp)
        {
            manager.processVoiceCall(List.of(RADIO, NXDNTalkgroupIdentifier.createTo(101), CLEAR),
                channel(VOICE_FREQUENCY), CallType.GROUP_BROADCAST, CLEAR, timestamp, new VoiceCallOption(0),
                CallTimer.UNSPECIFIED);
            return events.getLast();
        }

        private IDecodeEvent data(long timestamp)
        {
            manager.processDataCallAssignment(new DataCallAssignment(new CorrectedBinaryMessage(176), timestamp,
                NXDNMessageType.CONTROL_OUT_14_CC_DATA_CALL_ASSIGNMENT, 3,
                LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1)
            {
                @Override public NXDNChannel getChannel() { return channel(DATA_FREQUENCY); }
                @Override public List<Identifier> getIdentifiers()
                {
                    return List.of(RADIO, NXDNTalkgroupIdentifier.createTo(101), CLEAR);
                }
            });
            return events.getLast();
        }

        @Override
        public void close()
        {
            manager.stop();
            chain.dispose();
        }
    }
}
