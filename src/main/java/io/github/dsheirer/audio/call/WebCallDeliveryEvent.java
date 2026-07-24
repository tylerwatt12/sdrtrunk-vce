/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.call;

import java.util.Objects;
import java.util.Set;

/**
 * Browser-call lifecycle handoff used to preserve call-start order without retaining completed PCM in the
 * coordinator.
 *
 * <p>A web delivery service reserves each {@link Opened} key. On {@link Resolved}, it can immediately move the call
 * audio to its bounded disk spool, remove every listed source-call reservation, and publish the earliest resolved
 * key only when no earlier reservation remains. {@link Abandoned} removes a silent orphan reservation. Recording and
 * configured upload streaming do not wait for this web publication ordering.</p>
 */
public sealed interface WebCallDeliveryEvent
    permits WebCallDeliveryEvent.Opened, WebCallDeliveryEvent.Resolved, WebCallDeliveryEvent.Abandoned
{
    /**
     * Stable total ordering for one active call reservation.
     */
    record OrderKey(long startTimestamp, long registrationOrdinal, AudioCallId callId)
        implements Comparable<OrderKey>
    {
        public OrderKey
        {
            Objects.requireNonNull(callId, "Call order key requires a call ID");
        }

        @Override
        public int compareTo(OrderKey other)
        {
            if(other == null)
            {
                return -1;
            }

            int comparison = Long.compare(startTimestamp, other.startTimestamp);

            if(comparison == 0)
            {
                comparison = Long.compare(registrationOrdinal, other.registrationOrdinal);
            }

            if(comparison == 0)
            {
                comparison = compareCallIds(callId, other.callId);
            }

            return comparison;
        }

        private static int compareCallIds(AudioCallId first, AudioCallId second)
        {
            int comparison = Long.compare(first.producerId(), second.producerId());

            if(comparison == 0)
            {
                comparison = Long.compare(first.sequence(), second.sequence());
            }

            if(comparison == 0)
            {
                comparison = Integer.compare(first.timeslot(), second.timeslot());
            }

            return comparison;
        }
    }

    /**
     * Reserves an active call's chronological position using only compact identifiers.
     */
    record Opened(OrderKey orderKey) implements WebCallDeliveryEvent
    {
        public Opened
        {
            Objects.requireNonNull(orderKey, "Opened delivery requires an order key");
        }
    }

    /**
     * Supplies one resolved logical call and closes all physical receiver-copy reservations in its cohort.
     *
     * <p>The recipient must hand off or spool the call without blocking this callback and must not retain PCM in an
     * unbounded in-memory queue.</p>
     */
    record Resolved(OrderKey orderKey, Set<AudioCallId> sourceCallIds, CompletedAudioCall call)
        implements WebCallDeliveryEvent
    {
        public Resolved
        {
            Objects.requireNonNull(orderKey, "Resolved delivery requires an order key");
            Objects.requireNonNull(call, "Resolved delivery requires a completed call");
            sourceCallIds = sourceCallIds != null ? Set.copyOf(sourceCallIds) : Set.of();

            if(sourceCallIds.isEmpty())
            {
                throw new IllegalArgumentException("Resolved delivery requires at least one source call ID");
            }
        }
    }

    /**
     * Releases one inactive reservation after the bounded coordinator watchdog expires.
     */
    record Abandoned(OrderKey orderKey, Reason reason) implements WebCallDeliveryEvent
    {
        public Abandoned
        {
            Objects.requireNonNull(orderKey, "Abandoned delivery requires an order key");
            reason = reason != null ? reason : Reason.INACTIVITY;
        }

        public enum Reason
        {
            INACTIVITY,
            SHUTDOWN
        }
    }
}
