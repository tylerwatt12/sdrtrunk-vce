/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.ModuleEventBusMessage;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DMR-specific lifecycle notifications that revoke control-channel allocation authority before a rest-channel
 * conversion changes processing-chain ownership and restore it if that conversion is rolled back.
 */
public final class DMRChannelConfigurationTransitionNotification
{
    private DMRChannelConfigurationTransitionNotification()
    {
    }

    /**
     * Identity-scoped request to suspend control-channel allocation authority for one conversion attempt.
     */
    public static final class Suspend implements ModuleEventBusMessage
    {
        private final Channel mTargetChannel;
        private final Set<DMRDecoderState> mAcknowledgedSubscribers = ConcurrentHashMap.newKeySet();

        public Suspend(Channel targetChannel)
        {
            mTargetChannel = Objects.requireNonNull(targetChannel, "Target channel cannot be null");

            if(!targetChannel.isTrafficChannel())
            {
                throw new IllegalArgumentException("Target channel must be a traffic channel");
            }
        }

        public Channel getTargetChannel()
        {
            return mTargetChannel;
        }

        void acknowledge(DMRDecoderState subscriber)
        {
            mAcknowledgedSubscribers.add(Objects.requireNonNull(subscriber, "Subscriber cannot be null"));
        }

        /**
         * Indicates that every expected DMR decoder-state subscriber successfully published the suspension.
         */
        public boolean isAcknowledged(int expectedSubscribers)
        {
            return expectedSubscribers > 0 && mAcknowledgedSubscribers.size() >= expectedSubscribers;
        }

        public int getAcknowledgedSubscriberCount()
        {
            return mAcknowledgedSubscribers.size();
        }

        /**
         * Creates an exact-identity rollback notification for this suspension.
         */
        public Rollback rollback()
        {
            return new Rollback(this);
        }
    }

    /**
     * Request to restore authority suspended by the exact referenced request.  Duplicate, stale, and post-commit
     * rollback notifications are harmless no-ops.
     */
    public static final class Rollback implements ModuleEventBusMessage
    {
        private final Suspend mSuspension;

        private Rollback(Suspend suspension)
        {
            mSuspension = Objects.requireNonNull(suspension, "Suspension cannot be null");
        }

        public Suspend getSuspension()
        {
            return mSuspension;
        }
    }
}
