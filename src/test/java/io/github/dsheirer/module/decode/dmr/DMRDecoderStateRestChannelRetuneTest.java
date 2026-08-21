/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.MultiChannelState;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.CapacityPlusRestChannel;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationFrequencySelectionRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DMRDecoderStateRestChannelRetuneTest
{
    private static final long FREQUENCY_1 = 451_000_000L;
    private static final long FREQUENCY_2 = 452_000_000L;
    private static final long FREQUENCY_3 = 453_000_000L;

    @Test
    void timeslotOneRequestsEachNewRestFrequencyOnTheExistingChain()
    {
        Channel parent = multiFrequencyChannel(Channel.ChannelType.STANDARD);
        DMRDecoderState state = new DMRDecoderState(parent, 1, new DMRTrafficChannelManager(parent));
        SelectionSubscriber subscriber = new SelectionSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        state.setInterModuleEventBus(eventBus);
        state.setCurrentFrequency(FREQUENCY_1);

        state.receive(restChannel(3, FREQUENCY_2));
        assertEquals(List.of(FREQUENCY_2), subscriber.frequencies);

        //The source-frequency notification completes the first move.  A later nomination uses the same decoder,
        //manager, monitor, histories, and processing chain.
        state.receiveDecoderStateEvent(sourceFrequency(FREQUENCY_2));
        state.receive(restChannel(5, FREQUENCY_3));
        assertEquals(List.of(FREQUENCY_2, FREQUENCY_3), subscriber.frequencies);
    }

    @Test
    void nonOwningDecoderAndIneligibleChannelsDoNotRequestRetune()
    {
        SelectionSubscriber subscriber = new SelectionSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);

        Channel standard = multiFrequencyChannel(Channel.ChannelType.STANDARD);
        DMRDecoderState timeslotTwo = new DMRDecoderState(standard, 2, new DMRTrafficChannelManager(standard));
        timeslotTwo.setInterModuleEventBus(eventBus);
        timeslotTwo.setCurrentFrequency(FREQUENCY_1);
        timeslotTwo.receive(restChannel(3, FREQUENCY_2));

        Channel traffic = multiFrequencyChannel(Channel.ChannelType.TRAFFIC);
        DMRDecoderState trafficState = new DMRDecoderState(traffic, 1, new DMRTrafficChannelManager(standard));
        trafficState.setInterModuleEventBus(eventBus);
        trafficState.setCurrentFrequency(FREQUENCY_1);
        trafficState.receive(restChannel(3, FREQUENCY_2));

        Channel singleFrequency = dmrChannel(Channel.ChannelType.STANDARD);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(FREQUENCY_1);
        singleFrequency.setSourceConfiguration(source);
        DMRDecoderState singleFrequencyState = new DMRDecoderState(singleFrequency, 1,
            new DMRTrafficChannelManager(singleFrequency));
        singleFrequencyState.setInterModuleEventBus(eventBus);
        singleFrequencyState.setCurrentFrequency(FREQUENCY_1);
        singleFrequencyState.receive(restChannel(3, FREQUENCY_2));

        DMRDecoderState currentFrequencyState = new DMRDecoderState(standard, 1,
            new DMRTrafficChannelManager(standard));
        currentFrequencyState.setInterModuleEventBus(eventBus);
        currentFrequencyState.setCurrentFrequency(FREQUENCY_2);
        currentFrequencyState.receive(restChannel(3, FREQUENCY_2));
        currentFrequencyState.receive(restChannel(3, 0));
        currentFrequencyState.receive(restChannel(7, 454_000_000L));

        assertTrue(subscriber.frequencies.isEmpty());
    }

    @Test
    void sourceFrequencyChangeEndsTheOldStandardChannelCall()
    {
        Channel parent = multiFrequencyChannel(Channel.ChannelType.STANDARD);
        DMRDecoderState state = new DMRDecoderState(parent, 1, null);
        List<IDecodeEvent> completedEvents = new CopyOnWriteArrayList<>();
        state.addDecodeEventListener(completedEvents::add);
        DecodeEvent call = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1L)
            .channel(new DMRAbsoluteChannel(1, 1, FREQUENCY_1, 0))
            .identifiers(new IdentifierCollection())
            .timeslot(1)
            .build();
        state.setCurrentFrequency(FREQUENCY_1);
        state.setCurrentCallEvent(call);

        state.receiveDecoderStateEvent(sourceFrequency(FREQUENCY_2));
        state.receiveDecoderStateEvent(sourceFrequency(FREQUENCY_2));

        assertEquals(FREQUENCY_2, state.getCurrentFrequency());
        assertTrue(call.getTimeEnd() > call.getTimeStart());
        assertEquals(List.of(call), completedEvents);
    }

    @Test
    void sourceBoundaryEndsBothTimeslotCallsBeforeTargetAcquisition()
    {
        Channel parent = multiFrequencyChannel(Channel.ChannelType.STANDARD);
        DMRDecoderState state1 = new DMRDecoderState(parent, 1, null);
        DMRDecoderState state2 = new DMRDecoderState(parent, 2, null);
        MultiChannelState channelState = new MultiChannelState(parent, new AliasModel(), new int[]{1, 2});
        List<IDecodeEvent> completedEvents = new CopyOnWriteArrayList<>();
        state1.addDecodeEventListener(completedEvents::add);
        state2.addDecodeEventListener(completedEvents::add);
        DecodeEvent call1 = call(1);
        DecodeEvent call2 = call(2);
        state1.setCurrentCallEvent(call1);
        state2.setCurrentCallEvent(call2);
        channelState.setDecoderStateListener(event -> {
            state1.getDecoderStateListener().receive(event);
            state2.getDecoderStateListener().receive(event);
        });

        channelState.getSourceEventListener().receive(SourceEvent.stopSampleStreamNotification(null));

        assertEquals(2, completedEvents.size());
        assertTrue(completedEvents.contains(call1));
        assertTrue(completedEvents.contains(call2));
    }

    @Test
    void trafficEventIsDeliveredOnlyByItsOriginatingProcessingChain()
    {
        Channel parent = multiFrequencyChannel(Channel.ChannelType.STANDARD);
        DMRTrafficChannelManager manager = new DMRTrafficChannelManager(parent);
        EmittingDecoderState trafficState = new EmittingDecoderState(
            multiFrequencyChannel(Channel.ChannelType.TRAFFIC), manager);
        List<IDecodeEvent> trafficDeliveries = new CopyOnWriteArrayList<>();
        List<IDecodeEvent> parentDeliveries = new CopyOnWriteArrayList<>();
        trafficState.addDecodeEventListener(trafficDeliveries::add);
        manager.addDecodeEventListener(parentDeliveries::add);
        DecodeEvent event = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .channel(new DMRAbsoluteChannel(1, 1, FREQUENCY_2, 0))
            .identifiers(new IdentifierCollection())
            .timeslot(1)
            .build();

        trafficState.emit(event);

        assertEquals(List.of(event), trafficDeliveries);
        assertTrue(parentDeliveries.isEmpty());
    }

    private static DecoderStateEvent sourceFrequency(long frequency)
    {
        return new DecoderStateEvent(DMRDecoderStateRestChannelRetuneTest.class,
            DecoderStateEvent.Event.NOTIFICATION_SOURCE_FREQUENCY, State.IDLE, 1, frequency);
    }

    private static DecodeEvent call(int timeslot)
    {
        return DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1L)
            .channel(new DMRAbsoluteChannel(1, timeslot, FREQUENCY_1, 0))
            .identifiers(new IdentifierCollection())
            .timeslot(timeslot)
            .build();
    }

    private static Channel multiFrequencyChannel(Channel.ChannelType channelType)
    {
        Channel channel = dmrChannel(channelType);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(FREQUENCY_1, FREQUENCY_2, FREQUENCY_3));
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static Channel dmrChannel(Channel.ChannelType channelType)
    {
        Channel channel = new Channel("DMR Site", channelType);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(2);
        channel.setDecodeConfiguration(config);
        return channel;
    }

    private static CapacityPlusRestChannel restChannel(int lsn, long frequency)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(32);
        bits.load(0, 4, 15);  //Capacity Plus rest-channel short-link-control opcode
        bits.load(4, 8, 16);  //Motorola Capacity Plus vendor
        bits.load(15, 5, lsn);
        CapacityPlusRestChannel message = new CapacityPlusRestChannel(bits, 1_000L, 0);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(((lsn - 1) / 2) + 1);
        mapping.setDownlinkFrequency(frequency);
        message.apply(List.of(mapping));
        return message;
    }

    private static class SelectionSubscriber
    {
        private final List<Long> frequencies = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(ChannelRotationFrequencySelectionRequest request)
        {
            frequencies.add(request.frequency());
        }
    }

    private static class EmittingDecoderState extends DMRDecoderState
    {
        private EmittingDecoderState(Channel channel, DMRTrafficChannelManager manager)
        {
            super(channel, 1, manager);
        }

        private void emit(IDecodeEvent event)
        {
            broadcast(event);
        }
    }
}
