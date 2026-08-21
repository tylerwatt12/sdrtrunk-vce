/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationChangeNotification;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.TalkgroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DMRDecoderStateOperationalModeTest
{
    @Test
    void currentControlAuthorityDispatchesGrant()
    {
        Channel parent = channel("Capacity Plus", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(parent);
        DMRDecoderState decoderState = new DMRDecoderState(parent, 1, manager);

        decoderState.receive(grant());

        assertEquals(1, manager.getGrantCount());
    }

    @Test
    void conversionRevokesGrantCapturedByInFlightDecoderCallback() throws Exception
    {
        Channel parent = channel("Capacity Plus", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(parent);
        DMRDecoderState decoderState = new DMRDecoderState(parent, 1, manager);
        CountDownLatch authorityCheckReached = new CountDownLatch(1);
        CountDownLatch resumeDecoder = new CountDownLatch(1);
        AtomicReference<Throwable> decoderFailure = new AtomicReference<>();
        decoderState.setBeforeChannelGrantAuthorityCheckForTest(() -> {
            authorityCheckReached.countDown();

            try
            {
                if(!resumeDecoder.await(5, TimeUnit.SECONDS))
                {
                    throw new AssertionError("Timed out waiting to resume decoder callback");
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });

        Thread decoder = new Thread(() -> {
            try
            {
                decoderState.receive(grant());
            }
            catch(Throwable throwable)
            {
                decoderFailure.set(throwable);
            }
        }, "dmr-operational-mode-race-test");
        decoder.start();

        assertTrue(authorityCheckReached.await(5, TimeUnit.SECONDS));
        decoderState.channelChanged(new ChannelConfigurationChangeNotification(
            channel("Converted Traffic", Channel.ChannelType.TRAFFIC)));
        resumeDecoder.countDown();
        decoder.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(decoder.isAlive());
        assertNull(decoderFailure.get());
        assertEquals(0, manager.getGrantCount());
    }

    @Test
    void rollbackUsesFreshAuthorityGenerationAndCannotReviveStaleCallback() throws Exception
    {
        Channel parent = channel("Capacity Plus", Channel.ChannelType.STANDARD);
        Channel traffic = channel("Converted Traffic", Channel.ChannelType.TRAFFIC);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(parent);
        DMRDecoderState decoderState = new DMRDecoderState(parent, 1, manager);
        DMRChannelConfigurationTransitionNotification.Suspend suspension =
            new DMRChannelConfigurationTransitionNotification.Suspend(traffic);
        CountDownLatch authorityCheckReached = new CountDownLatch(1);
        CountDownLatch resumeDecoder = new CountDownLatch(1);
        AtomicReference<Throwable> decoderFailure = new AtomicReference<>();
        decoderState.setBeforeChannelGrantAuthorityCheckForTest(() -> {
            authorityCheckReached.countDown();

            try
            {
                if(!resumeDecoder.await(5, TimeUnit.SECONDS))
                {
                    throw new AssertionError("Timed out waiting to resume decoder callback");
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });

        Thread decoder = new Thread(() -> {
            try
            {
                decoderState.receive(grant());
            }
            catch(Throwable throwable)
            {
                decoderFailure.set(throwable);
            }
        }, "dmr-operational-mode-rollback-race-test");
        decoder.start();

        assertTrue(authorityCheckReached.await(5, TimeUnit.SECONDS));
        decoderState.suspendChannelConfigurationTransition(suspension);
        assertTrue(suspension.isAcknowledged(1));
        assertEquals(1, suspension.getAcknowledgedSubscriberCount());
        decoderState.suspendChannelConfigurationTransition(suspension);
        assertEquals(1, suspension.getAcknowledgedSubscriberCount());
        assertFalse(decoderState.hasAllocationAuthorityForTest());
        decoderState.rollbackChannelConfigurationTransition(suspension.rollback());
        assertTrue(decoderState.hasAllocationAuthorityForTest());
        resumeDecoder.countDown();
        decoder.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(decoder.isAlive());
        assertNull(decoderFailure.get());
        assertEquals(0, manager.getGrantCount());

        decoderState.receive(grant());

        assertEquals(1, manager.getGrantCount());
    }

    @Test
    void suspensionDoesNotAcknowledgeWhileGrantDispatchIsInFlight() throws Exception
    {
        Channel parent = channel("Capacity Plus", Channel.ChannelType.STANDARD);
        Channel traffic = channel("Converted Traffic", Channel.ChannelType.TRAFFIC);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(parent);
        DMRDecoderState decoderState = new DMRDecoderState(parent, 1, manager);
        DMRChannelConfigurationTransitionNotification.Suspend blockedSuspension =
            new DMRChannelConfigurationTransitionNotification.Suspend(traffic);
        CountDownLatch authorityAcquired = new CountDownLatch(1);
        CountDownLatch resumeDecoder = new CountDownLatch(1);
        AtomicReference<Throwable> decoderFailure = new AtomicReference<>();
        decoderState.setAfterChannelGrantAuthorityAcquiredForTest(() -> {
            authorityAcquired.countDown();

            try
            {
                if(!resumeDecoder.await(5, TimeUnit.SECONDS))
                {
                    throw new AssertionError("Timed out waiting to resume in-flight grant");
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });

        Thread decoder = new Thread(() -> {
            try
            {
                decoderState.receive(grant());
            }
            catch(Throwable throwable)
            {
                decoderFailure.set(throwable);
            }
        }, "dmr-operational-mode-in-flight-grant-test");
        decoder.start();

        assertTrue(authorityAcquired.await(5, TimeUnit.SECONDS));
        decoderState.suspendChannelConfigurationTransition(blockedSuspension);
        assertFalse(blockedSuspension.isAcknowledged(1));
        assertTrue(decoderState.hasAllocationAuthorityForTest());
        decoderState.rollbackChannelConfigurationTransition(blockedSuspension.rollback());
        resumeDecoder.countDown();
        decoder.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(decoder.isAlive());
        assertNull(decoderFailure.get());
        assertEquals(1, manager.getGrantCount());

        DMRChannelConfigurationTransitionNotification.Suspend retry =
            new DMRChannelConfigurationTransitionNotification.Suspend(traffic);
        decoderState.suspendChannelConfigurationTransition(retry);

        assertTrue(retry.isAcknowledged(1));
        assertFalse(decoderState.hasAllocationAuthorityForTest());
    }

    private static Channel channel(String name, Channel.ChannelType channelType)
    {
        Channel channel = new Channel(name, channelType);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        channel.setDecodeConfiguration(config);
        return channel;
    }

    private static TalkgroupVoiceChannelGrant grant()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(80);
        bits.load(2, 6, 49);
        bits.load(16, 12, 4);
        bits.load(32, 24, 1200);
        bits.load(56, 24, 3400);
        CorrectedBinaryMessage slotBits = new CorrectedBinaryMessage(24);
        slotBits.load(8, 4, 3);
        return new TalkgroupVoiceChannelGrant(DMRSyncPattern.BASE_STATION_DATA, bits, null,
            new SlotType(slotBits), 1_000L, 1);
    }

    private static class RecordingTrafficChannelManager extends DMRTrafficChannelManager
    {
        private final AtomicInteger mGrantCount = new AtomicInteger();

        private RecordingTrafficChannelManager(Channel parentChannel)
        {
            super(parentChannel);
        }

        @Override
        public void processChannelGrant(DMRChannel channel, IdentifierCollection identifierCollection,
                                        Opcode opcode, long timestamp, boolean encrypted)
        {
            mGrantCount.incrementAndGet();
        }

        private int getGrantCount()
        {
            return mGrantCount.get();
        }
    }
}
