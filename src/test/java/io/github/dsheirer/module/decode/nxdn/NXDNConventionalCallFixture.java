package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.TransmissionRelease;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallType;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import java.util.ArrayList;
import java.util.List;

/** Exercises real conventional NXDN headers and decoder identifier notifications without requiring a vocoder. */
public final class NXDNConventionalCallFixture
{
    private NXDNConventionalCallFixture() { }

    public static DecodedCall decodeGroupCall(AliasList aliases, int source, int group)
    {
        Channel channel = new Channel("NXDN fixture", Channel.ChannelType.STANDARD);
        channel.setConfigurationId("00000000-0000-0000-0000-000000000099");
        DecodeConfigNXDN configuration = new DecodeConfigNXDN();
        configuration.setChannelMode(NXDNChannelMode.CONVENTIONAL);
        channel.setDecodeConfiguration(configuration);
        NXDNDecoderState state = new NXDNDecoderState(channel, null);
        state.setCurrentFrequency(461_125_000L);
        CallLegSource callSource = new CallLegSource(DecoderType.NXDN, channel.getConfigurationId(),
            channel.getName(), null, aliases.getId(), null, TrunkedIdentityDomain.NXDN_TYPE_C,
            ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL, false);
        FixtureAudioModule audio = new FixtureAudioModule(aliases, callSource);
        state.setIdentifierUpdateListener(audio.getIdentifierUpdateListener());
        EventBus localBus = new EventBus();
        state.setInterModuleEventBus(localBus);
        audio.setInterModuleEventBus(localBus);
        audio.getIdentifierUpdateListener().receive(new IdentifierUpdateNotification(
            ChannelConfigurationIdentifier.create(channel.getConfigurationId()),
            IdentifierUpdateNotification.Operation.ADD, 0));
        audio.getIdentifierUpdateListener().receive(new IdentifierUpdateNotification(
            FrequencyConfigurationIdentifier.create(461_125_000L), IdentifierUpdateNotification.Operation.ADD, 0));
        CompletionSubscriber completed = new CompletionSubscriber();
        MyEventBus.getGlobalEventBus().register(completed);

        try
        {
            // TS 1-B v2.0, section 8.1.1.1 uses group conference and Null Group ID for an unaddressed call.
            state.receive(new VoiceCall(callBits(source, group), 1_000L,
                NXDNMessageType.TRAFFIC_OUT_01_CC_VOICE_CALL, 3,
                LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
            for(int frame = 0; frame < 50; frame++)
            {
                audio.addAudio(new float[160], null, 1_020L + frame * 20L);
            }
            audio.finish(2_000L);
            state.receive(new TransmissionRelease(callBits(source, group), 2_000L,
                NXDNMessageType.TRAFFIC_OUT_08_CC_TRANSMISSION_RELEASE, 3,
                LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1));
            assertEquals(1, completed.calls.size());
            List<AudioCallEvent> audioCompletions = audio.events.stream()
                .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).toList();
            assertEquals(1, audioCompletions.size());
            List<float[]> buffers = audio.events.stream()
                .filter(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME)
                .map(AudioCallEvent::audioFrame).toList();
            return new DecodedCall(completed.calls.getFirst(),
                new CompletedAudioCall(audioCompletions.getFirst().snapshot(), buffers));
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(completed);
            state.dispose();
            audio.dispose();
        }
    }

    private static CorrectedBinaryMessage callBits(int source, int group)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(176);
        message.load(16, 3, CallType.GROUP_CONFERENCE.getValue());
        message.load(24, 16, source);
        message.load(40, 16, group);
        return message;
    }

    public record DecodedCall(NXDNConventionalCallEvent completion, CompletedAudioCall audio) { }

    private static final class CompletionSubscriber
    {
        private final List<NXDNConventionalCallEvent> calls = new ArrayList<>();
        @Subscribe public void receive(NXDNConventionalCallEvent call) { calls.add(call); }
    }

    private static final class FixtureAudioModule extends AbstractAudioModule
    {
        private final List<AudioCallEvent> events = new ArrayList<>();

        private FixtureAudioModule(AliasList aliases, CallLegSource source)
        {
            super(aliases, 0, 60_000L, source);
            setAudioCallEventListener(events::add);
        }

        private void finish(long timestamp) { closeAudioSegment(timestamp); }
        @Override public void reset() { }
        @Override public void start() { }
    }
}
