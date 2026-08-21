/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MultiChannelStateConfigurationTransitionTest
{
    @Test
    void teardownOverlappingTrafficPublicationIsDispatchedExactlyOnceAfterCommit() throws Exception
    {
        BlockingStandardChannel parent = new BlockingStandardChannel("test-dmr-decoder-teardown");
        Channel traffic = channel(Channel.ChannelType.TRAFFIC);
        MultiChannelState state = new MultiChannelState(parent, new AliasModel(), new int[]{1, 2});
        List<ChannelEvent> channelEvents = new CopyOnWriteArrayList<>();
        state.setChannelEventListener(channelEvents::add);
        moveToFade(state, 1);

        Thread decoder = new Thread(() -> state.getDecoderStateListener().receive(
            new DecoderStateEvent(this, DecoderStateEvent.Event.END, State.TEARDOWN, 1)),
            "test-dmr-decoder-teardown");
        decoder.start();
        assertTrue(parent.mTrafficDecisionEntered.await(5, TimeUnit.SECONDS));

        AbstractChannelState.ChannelConfigurationTransition transition =
            state.beginChannelConfigurationTransition(traffic);
        state.publishChannelConfigurationTransition(transition);
        state.completeChannelConfigurationTransition(transition);

        parent.mReleaseTrafficDecision.countDown();
        decoder.join(5_000);
        assertFalse(decoder.isAlive());
        assertEquals(1, channelEvents.size());
        assertEquals(ChannelEvent.Event.REQUEST_DISABLE, channelEvents.getFirst().getEvent());
        assertEquals(traffic, channelEvents.getFirst().getChannel());
        assertTrue(state.isTeardownSequenceCompleted());
    }

    @Test
    void rollbackRestoresStandardTeardownResetWithoutDisable() throws Exception
    {
        BlockingStandardChannel parent = new BlockingStandardChannel("test-dmr-decoder-teardown-rollback");
        Channel traffic = channel(Channel.ChannelType.TRAFFIC);
        MultiChannelState state = new MultiChannelState(parent, new AliasModel(), new int[]{1, 2});
        List<ChannelEvent> channelEvents = new CopyOnWriteArrayList<>();
        state.setChannelEventListener(channelEvents::add);
        moveToFade(state, 1);

        Thread decoder = new Thread(() -> state.getDecoderStateListener().receive(
            new DecoderStateEvent(this, DecoderStateEvent.Event.END, State.TEARDOWN, 1)),
            "test-dmr-decoder-teardown-rollback");
        decoder.start();
        assertTrue(parent.mTrafficDecisionEntered.await(5, TimeUnit.SECONDS));

        AbstractChannelState.ChannelConfigurationTransition transition =
            state.beginChannelConfigurationTransition(traffic);
        state.rollbackChannelConfigurationTransition(transition);

        parent.mReleaseTrafficDecision.countDown();
        decoder.join(5_000);
        assertFalse(decoder.isAlive());
        assertTrue(channelEvents.isEmpty());
        assertFalse(state.isTeardownState());
    }

    @Test
    void completionClearsMarkerBeforeReconcileAndLateTeardownIsNotLost() throws Exception
    {
        Channel parent = channel(Channel.ChannelType.STANDARD);
        Channel traffic = channel(Channel.ChannelType.TRAFFIC);
        BlockingCompletionMultiChannelState state =
            new BlockingCompletionMultiChannelState(parent, new AliasModel(), new int[]{1, 2});
        List<ChannelEvent> channelEvents = new CopyOnWriteArrayList<>();
        state.setChannelEventListener(channelEvents::add);
        moveToFade(state, 1);
        AbstractChannelState.ChannelConfigurationTransition transition =
            state.beginChannelConfigurationTransition(traffic);
        state.publishChannelConfigurationTransition(transition);
        AtomicReference<Throwable> lifecycleFailure = new AtomicReference<>();
        Thread lifecycle = new Thread(() -> {
            try
            {
                state.completeChannelConfigurationTransition(transition);
            }
            catch(Throwable throwable)
            {
                lifecycleFailure.set(throwable);
            }
        }, "test-dmr-transition-completion");
        lifecycle.start();

        try
        {
            assertTrue(state.mCompletionHookEntered.await(5, TimeUnit.SECONDS));
            assertTrue(state.mMarkerClearedBeforeHook);

            //This TEARDOWN is published after the completion handoff.  It must take the committed TRAFFIC path while
            //the lifecycle hook is paused, and the hook's later reconciliation must not duplicate the request.
            state.getDecoderStateListener().receive(new DecoderStateEvent(this,
                DecoderStateEvent.Event.END, State.TEARDOWN, 1));
        }
        finally
        {
            state.mReleaseCompletionHook.countDown();
            lifecycle.join(5_000);
        }

        assertFalse(lifecycle.isAlive());
        assertNull(lifecycleFailure.get());
        assertEquals(1, channelEvents.size());
        assertEquals(ChannelEvent.Event.REQUEST_DISABLE, channelEvents.getFirst().getEvent());
        assertEquals(traffic, channelEvents.getFirst().getChannel());
    }

    private static void moveToFade(MultiChannelState state, int timeslot)
    {
        Object source = new Object();
        state.getDecoderStateListener().receive(new DecoderStateEvent(source,
            DecoderStateEvent.Event.CONTINUATION, State.CALL, timeslot));
        state.getDecoderStateListener().receive(new DecoderStateEvent(source,
            DecoderStateEvent.Event.END, State.FADE, timeslot));
    }

    private static Channel channel(Channel.ChannelType type)
    {
        Channel channel = new Channel(type == Channel.ChannelType.STANDARD ? "Parent" : "Traffic", type);
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        return channel;
    }

    /** Holds the old STANDARD decision after the decoder has already read the pre-transition channel. */
    private static final class BlockingStandardChannel extends Channel
    {
        private final String mDecoderThreadName;
        private final CountDownLatch mTrafficDecisionEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseTrafficDecision = new CountDownLatch(1);

        private BlockingStandardChannel(String decoderThreadName)
        {
            super("Parent", ChannelType.STANDARD);
            mDecoderThreadName = decoderThreadName;
            setDecodeConfiguration(new DecodeConfigDMR());
        }

        @Override
        public boolean isTrafficChannel()
        {
            if(Thread.currentThread().getName().equals(mDecoderThreadName))
            {
                mTrafficDecisionEntered.countDown();

                try
                {
                    mReleaseTrafficDecision.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }

            return false;
        }
    }

    private static final class BlockingCompletionMultiChannelState extends MultiChannelState
    {
        private final CountDownLatch mCompletionHookEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseCompletionHook = new CountDownLatch(1);
        private volatile boolean mMarkerClearedBeforeHook;

        private BlockingCompletionMultiChannelState(Channel channel, AliasModel aliasModel, int[] timeslots)
        {
            super(channel, aliasModel, timeslots);
        }

        @Override
        protected void channelConfigurationTransitionCompleted(ChannelConfigurationTransition transition)
        {
            mMarkerClearedBeforeHook = getChannelConfigurationTransition() == null;
            mCompletionHookEntered.countDown();

            try
            {
                if(!mReleaseCompletionHook.await(5, TimeUnit.SECONDS))
                {
                    throw new AssertionError("Timed out waiting to release completion hook");
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }

            super.channelConfigurationTransitionCompleted(transition);
        }
    }
}
