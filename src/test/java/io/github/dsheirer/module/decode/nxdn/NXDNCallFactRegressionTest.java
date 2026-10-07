package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNEncryptionKey;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallAssignment;
import io.github.dsheirer.module.decode.nxdn.layer3.proprietary.TalkerAliasComplete;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallTimer;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallType;
import io.github.dsheirer.module.decode.nxdn.layer3.type.ChannelAccessInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.nxdn.layer3.type.VoiceCallOption;
import io.github.dsheirer.module.decode.traffic.TrunkedCallAttributionEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NXDNCallFactRegressionTest
{
    @Test
    void lateSourceAndEncryptionEnrichOneCallWithoutLosingItsDomainOrEndBoundary()
    {
        verifyLateCallFacts(false);
        verifyLateCallFacts(true);
    }

    private void verifyLateCallFacts(boolean typeD)
    {
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent(false, typeD));
        List<IDecodeEvent> events = new ArrayList<>();
        manager.addDecodeEventListener(events::add);
        CallFacts facts = new CallFacts();
        MyEventBus.getGlobalEventBus().register(facts);
        NXDNChannelLookup channel = channel();
        EncryptionKeyIdentifier clear = encryption(0, 0);
        EncryptionKeyIdentifier aes = encryption(3, 42);

        try
        {
            observe(manager, channel, 0, 91, typeD, clear, 1_000L);
            IDecodeEvent original = events.getFirst();
            assertNull(original.getIdentifierCollection().getFromIdentifier());

            observe(manager, channel, 101, 91, typeD, aes, 1_100L);
            assertSame(original, events.getLast());
            assertEquals(101, events.getLast().getIdentifierCollection().getFromIdentifier().getValue());
            assertEquals(typeD, ((NXDNRadioIdentifier)events.getLast().getIdentifierCollection()
                .getFromIdentifier()).isTypeD());
            assertEquals(DecodeEventType.CALL_GROUP_ENCRYPTED, events.getLast().getEventType());
            assertEquals(aes, key(events.getLast()));
            assertTrue(events.getLast().getDetails().startsWith("AES256 K:2A "));
            assertEquals(1, facts.starts.size());
            assertNull(facts.starts.getFirst().timeslot(), "FDMA call must not acquire a TDMA timeslot");
            assertEquals(typeD ? TrunkedIdentityDomain.NXDN_TYPE_D : TrunkedIdentityDomain.NXDN_TYPE_C,
                facts.starts.getFirst().identityDomain());
            assertEquals(1, facts.attributions.size());
            assertEquals(101, facts.attributions.getFirst().sourceRadioId());
            assertTrue(facts.attributions.getFirst().sourceBecameKnown());
            assertTrue(facts.attributions.getFirst().encryptionBecameKnown());
            assertEquals(1_000L, facts.attributions.getFirst().callStartEpochMilliseconds());

            observe(manager, channel, 0, 91, typeD, clear, 1_200L);
            assertSame(original, events.getLast());
            assertEquals(101, events.getLast().getIdentifierCollection().getFromIdentifier().getValue());
            assertEquals(aes, key(events.getLast()), "an incomplete repeat cannot erase known encryption");
            assertEquals(DecodeEventType.CALL_GROUP_ENCRYPTED, events.getLast().getEventType());
            assertEquals(1, facts.starts.size());

            observe(manager, channel, 202, 91, typeD, clear, 1_300L);
            assertNotSame(original, events.getLast());
            assertEquals(202, events.getLast().getIdentifierCollection().getFromIdentifier().getValue());
            assertEquals(DecodeEventType.CALL_GROUP, events.getLast().getEventType());
            assertEquals(clear, key(events.getLast()));
            assertEquals(1, facts.starts.size(), "talker changes update Events without recounting the logical call");
            assertEquals(1, facts.attributions.size(), "the first known radio retains logical call attribution");

            manager.processEndCall(channel, 1_400L);
            observe(manager, channel, 202, 91, typeD, clear, 1_500L);
            assertEquals(2, facts.starts.size(), "explicit release permits a new call with the same participants");
            assertEquals(1_500L, facts.starts.getLast().callStartEpochMilliseconds());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(facts);
        }
    }

    @Test
    void cachedAliasIsUsedAndCannotFollowADifferentSource()
    {
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent(false, false));
        manager.getTalkerAliasManager().update(NXDNRadioIdentifier.createFrom(101),
            new NXDNTalkerAliasIdentifier("UNIT 101"));
        List<IDecodeEvent> events = new ArrayList<>();
        manager.addDecodeEventListener(events::add);
        NXDNChannelLookup channel = channel();

        observe(manager, channel, 101, 91, false, encryption(0, 0), 1_000L);
        assertEquals("UNIT 101", alias(events.getLast().getIdentifierCollection()).getValue());
        observe(manager, channel, 202, 91, false, encryption(0, 0), 1_100L);
        assertNull(alias(events.getLast().getIdentifierCollection()));
        assertEquals(202, events.getLast().getIdentifierCollection().getFromIdentifier().getValue());
        manager.processTalkerAlias(channel, new NXDNTalkerAliasIdentifier("OLD SOURCE"),
            NXDNRadioIdentifier.createFrom(101), 1_200L);
        assertEquals(2, events.size(), "an older source alias cannot update the next talker's event");
        assertNull(alias(events.getLast().getIdentifierCollection()));
    }

    @Test
    void participantAndAliasObservationsUseMessageTimeWhenAudioAlreadyOwnsEventDuration()
    {
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent(false, false));
        List<IDecodeEvent> events = new ArrayList<>();
        manager.addDecodeEventListener(events::add);
        try(TimestampActivity activity = new TimestampActivity())
        {
            manager.setChannelActivityModel(activity);
            CorrectedBinaryMessage bits = new CorrectedBinaryMessage(176);
            bits.load(16, 3, CallType.GROUP_BROADCAST.getValue());
            bits.load(40, 16, 91);
            bits.load(62, 10, 12);
            VoiceCallAssignment assignment = new VoiceCallAssignment(bits, 1_000L,
                NXDNMessageType.CONTROL_OUT_04_CC_VOICE_CALL_ASSIGNMENT, 3,
                LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL);
            assignment.receive(new ChannelAccessInformation(new CorrectedBinaryMessage(6), 0),
                Map.of(12, new ChannelFrequency(12, 452_012_500L, 0)));
            manager.processVoiceCallAssignment(assignment);
            manager.processCallProgressUpdate(channel(), 1_100L);
            observe(manager, channel(), 101, 91, false, encryption(0, 0), 1_200L);
            assertEquals(1_100L, events.getLast().getTimeEnd(), "participant enrichment leaves audio duration alone");
            assertArrayEquals(new long[]{1_000L, 1_200L}, activity.timestamps.getLast());

            manager.processTalkerAlias(channel(), new NXDNTalkerAliasIdentifier("UNIT 101"),
                NXDNRadioIdentifier.createFrom(101), 1_250L);
            assertEquals(1_100L, events.getLast().getTimeEnd());
            assertArrayEquals(new long[]{1_000L, 1_250L}, activity.timestamps.getLast());
        }
    }

    @Test
    void completedAliasImmediatelyUpdatesDecoderAudioAndCurrentEventOnce()
    {
        verifyCompletedAlias(false);
        verifyCompletedAlias(true);
    }

    private void verifyCompletedAlias(boolean conventional)
    {
        Channel parent = parent(conventional, false);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        NXDNDecoderState state = new NXDNDecoderState(parent, manager);
        state.setCurrentChannel(channel());
        state.setCurrentFrequency(452_012_500L);
        List<IDecodeEvent> events = new ArrayList<>();
        if(conventional)
        {
            state.addDecodeEventListener(events::add);
        }
        else
        {
            manager.addDecodeEventListener(events::add);
        }
        MetadataAudioModule audio = new MetadataAudioModule();
        List<IdentifierUpdateNotification> updates = new ArrayList<>();
        state.setIdentifierUpdateListener(notification ->
        {
            updates.add(notification);
            audio.getIdentifierUpdateListener().receive(notification);
        });
        state.receive(voice(101, 91, 1_000L));
        audio.begin(1_000L);
        int initialEvents = events.size();
        int initialAudioEvents = audio.events.size();

        state.receive(new TalkerAliasComplete("UNIT 101", 1_100L, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));

        assertEquals("UNIT 101", alias(state.getIdentifierCollection()).getValue());
        assertEquals("UNIT 101", alias(audio.getIdentifierCollection()).getValue());
        assertEquals("UNIT 101", alias(events.getLast().getIdentifierCollection()).getValue());
        assertEquals(initialEvents + 1, events.size(), "one event update for this decoded alias");
        assertEquals(initialAudioEvents + 1, audio.events.size(), "one audio metadata update");
        assertEquals(AudioCallEventType.METADATA_UPDATED, audio.events.getLast().eventType());
        assertEquals("UNIT 101", alias(audio.events.getLast().snapshot().identifierCollection()).getValue());
        assertEquals(1, updates.stream().filter(update -> update.getIdentifier().getForm() == Form.TALKER_ALIAS &&
            update.getOperation() == IdentifierUpdateNotification.Operation.ADD).count());

        state.receive(new TalkerAliasComplete("UNIT 101", 1_200L, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        assertEquals(initialAudioEvents + 1, audio.events.size(), "identical alias sends no duplicate metadata");
        state.receive(voice(202, 91, 1_300L));
        assertNull(alias(state.getIdentifierCollection()));
        assertNull(alias(audio.getIdentifierCollection()));
        assertNull(alias(events.getLast().getIdentifierCollection()));
    }

    @Test
    void unknownSourceAndBlankAliasesStayUnassignedAndANewTargetCannotInheritTheSource()
    {
        Channel parent = parent(false, false);
        NXDNDecoderState state = new NXDNDecoderState(parent, new NXDNTrafficChannelManager(parent));
        state.setCurrentChannel(channel());
        state.receive(voice(0, 91, 1_000L));
        state.receive(new TalkerAliasComplete("UNASSIGNED", 1_100L, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        assertNull(alias(state.getIdentifierCollection()));
        state.receive(voice(101, 91, 1_200L));
        state.receive(new TalkerAliasComplete(" ", 1_300L, 3, LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        assertNull(alias(state.getIdentifierCollection()));
        state.receive(voice(0, 92, 1_400L));
        assertNull(state.getIdentifierCollection().getFromIdentifier());
        assertEquals(92, state.getIdentifierCollection().getToIdentifier().getValue());
    }

    @Test
    void conventionalRepeatedIncompleteHeaderRetainsTheCurrentSourceAndAliasWithoutAManager()
    {
        NXDNDecoderState state = new NXDNDecoderState(parent(true, false), null);
        state.setCurrentFrequency(452_012_500L);
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        state.receive(voice(101, 91, 1_000L));
        IDecodeEvent original = events.getLast();
        state.receive(new TalkerAliasComplete("UNIT 101", 1_100L, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
        state.receive(voice(0, 91, 1_200L));
        assertSame(original, events.getLast());
        assertEquals(101, state.getIdentifierCollection().getFromIdentifier().getValue());
        assertEquals("UNIT 101", alias(state.getIdentifierCollection()).getValue());
        assertEquals("UNIT 101", alias(events.getLast().getIdentifierCollection()).getValue());
    }

    private static void observe(NXDNTrafficChannelManager manager, NXDNChannelLookup channel, int source,
                                int target, boolean typeD, EncryptionKeyIdentifier encryption, long time)
    {
        List<Identifier> identifiers = new ArrayList<>();
        if(source > 0)
        {
            identifiers.add(typeD ? NXDNRadioIdentifier.createTypeDFrom(source) : NXDNRadioIdentifier.createFrom(source));
        }
        identifiers.add(typeD ? NXDNTalkgroupIdentifier.createTypeDTo(target) : NXDNTalkgroupIdentifier.createTo(target));
        identifiers.add(encryption);
        manager.processVoiceCall(identifiers, channel, CallType.GROUP_BROADCAST, encryption, time,
            new VoiceCallOption(0), CallTimer.UNSPECIFIED);
    }

    private static Channel parent(boolean conventional, boolean typeD)
    {
        Channel parent = new Channel("NXDN fixture", Channel.ChannelType.STANDARD);
        parent.setConfigurationId("00000000-0000-0000-0000-000000000099");
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setChannelMode(conventional ? NXDNChannelMode.CONVENTIONAL : NXDNChannelMode.TRUNKED);
        config.setTransmissionMode(typeD ? TransmissionMode.TYPE_D : TransmissionMode.M4800);
        config.setTrafficChannelPoolSize(0);
        parent.setDecodeConfiguration(config);
        return parent;
    }

    private static NXDNChannelLookup channel()
    {
        NXDNChannelLookup channel = new NXDNChannelLookup(12);
        channel.receive(null, Map.of(12, new ChannelFrequency(12, 452_012_500L, 0)));
        return channel;
    }

    private static VoiceCall voice(int source, int target, long time)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(176);
        message.load(16, 3, CallType.GROUP_BROADCAST.getValue());
        message.load(24, 16, source);
        message.load(40, 16, target);
        return new VoiceCall(message, time, NXDNMessageType.TRAFFIC_OUT_01_CC_VOICE_CALL, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1);
    }

    private static Identifier alias(IdentifierCollection identifiers)
    {
        return identifiers.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
    }

    private static Identifier key(IDecodeEvent event)
    {
        return event.getIdentifierCollection().getIdentifier(IdentifierClass.USER, Form.ENCRYPTION_KEY, Role.ANY);
    }

    private static EncryptionKeyIdentifier encryption(int algorithm, int key)
    {
        return EncryptionKeyIdentifier.create(Protocol.NXDN, NXDNEncryptionKey.create(algorithm, key));
    }

    private static class CallFacts
    {
        private final List<TrunkedCallStartEvent> starts = new ArrayList<>();
        private final List<TrunkedCallAttributionEvent> attributions = new ArrayList<>();
        @Subscribe public void receive(TrunkedCallStartEvent event) { starts.add(event); }
        @Subscribe public void receive(TrunkedCallAttributionEvent event) { attributions.add(event); }
    }

    private static class MetadataAudioModule extends AbstractAudioModule
    {
        private final List<AudioCallEvent> events = new ArrayList<>();
        private MetadataAudioModule()
        {
            super(AliasList.empty("NXDN fixture"), 0, 60_000L);
            setAudioCallEventListener(events::add);
        }
        private void begin(long time) { beginCurrentAudioSegment(time); }
        @Override public void reset() { }
        @Override public void start() { }
    }

    private static class TimestampActivity extends ChannelActivityModel
    {
        private final List<long[]> timestamps = new ArrayList<>();
        private TimestampActivity() { super(new AliasModel(), null); }
        @Override
        public void trunkedTrafficEvent(Channel parent, Channel traffic, IChannelDescriptor descriptor,
                                        Integer timeslot, IdentifierCollection identifiers, DecodeEventType type,
                                        long controlFrequency, long callStart, long observedAt)
        {
            timestamps.add(new long[]{callStart, observedAt});
        }
    }
}
