/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.message.MessageProviderModule;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEventProvider;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessingChainHistoryIsolationTest
{
    @Test
    void directHistorySeedingDoesNotEnterLiveChainBroadcasts()
    {
        Channel channel = new Channel("History Isolation");
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        ProcessingChain chain = new ProcessingChain(channel, new AliasModel());
        MessageProviderModule messageProvider = new MessageProviderModule();
        MessageConsumerModule messageConsumer = new MessageConsumerModule();
        DecodeEventProviderModule decodeEventProvider = new DecodeEventProviderModule();
        List<IDecodeEvent> liveDecodeEvents = new ArrayList<>();

        chain.addModule(messageProvider);
        chain.addModule(messageConsumer);
        chain.addModule(decodeEventProvider);
        chain.addDecodeEventListener(liveDecodeEvents::add);

        TestMessage historicalMessage = new TestMessage(1_000L);
        IDecodeEvent historicalDecodeEvent = new DecodeEvent(DecodeEventType.CALL, 1_000L);
        chain.getMessageHistory().receive(historicalMessage);
        chain.getDecodeEventHistory().receive(historicalDecodeEvent);

        assertEquals(List.of(historicalMessage), chain.getMessageHistory().getItems());
        assertEquals(List.of(historicalDecodeEvent), chain.getDecodeEventHistory().getItems());
        assertTrue(messageConsumer.getMessages().isEmpty());
        assertTrue(liveDecodeEvents.isEmpty());

        TestMessage liveMessage = new TestMessage(2_000L);
        IDecodeEvent liveDecodeEvent = new DecodeEvent(DecodeEventType.CALL, 2_000L);
        messageProvider.receive(liveMessage);
        decodeEventProvider.receive(liveDecodeEvent);

        assertEquals(List.of(historicalMessage, liveMessage), chain.getMessageHistory().getItems());
        assertEquals(List.of(liveMessage), messageConsumer.getMessages());
        assertEquals(List.of(historicalDecodeEvent, liveDecodeEvent), chain.getDecodeEventHistory().getItems());
        assertEquals(List.of(liveDecodeEvent), liveDecodeEvents);

        chain.dispose();
    }

    private static class MessageConsumerModule extends Module implements IMessageListener
    {
        private final List<IMessage> mMessages = new ArrayList<>();
        private final Listener<IMessage> mListener = mMessages::add;

        @Override
        public Listener<IMessage> getMessageListener()
        {
            return mListener;
        }

        private List<IMessage> getMessages()
        {
            return mMessages;
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
        }

        @Override
        public void stop()
        {
        }
    }

    private static class DecodeEventProviderModule extends Module implements IDecodeEventProvider
    {
        private Listener<IDecodeEvent> mListener;

        private void receive(IDecodeEvent decodeEvent)
        {
            if(mListener != null)
            {
                mListener.receive(decodeEvent);
            }
        }

        @Override
        public void addDecodeEventListener(Listener<IDecodeEvent> listener)
        {
            mListener = listener;
        }

        @Override
        public void removeDecodeEventListener(Listener<IDecodeEvent> listener)
        {
            if(mListener == listener)
            {
                mListener = null;
            }
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
        }

        @Override
        public void stop()
        {
        }
    }

    private static class TestMessage implements IMessage
    {
        private final long mTimestamp;

        private TestMessage(long timestamp)
        {
            mTimestamp = timestamp;
        }

        @Override
        public long getTimestamp()
        {
            return mTimestamp;
        }

        @Override
        public boolean isValid()
        {
            return true;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.DMR;
        }

        @Override
        public int getTimeslot()
        {
            return 0;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }
    }
}
