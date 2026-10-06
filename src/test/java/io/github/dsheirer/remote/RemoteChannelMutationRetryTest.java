/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.channel.ChannelAdministrationService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteChannelMutationRetryTest
{
    @Test
    void successfulMutationIsNotRepeated()
    {
        AtomicInteger attempts = new AtomicInteger();

        RemoteConnectivityService.retryChannelMutation(attempts::incrementAndGet);

        assertEquals(1, attempts.get());
    }

    @Test
    void retriesTemporaryChannelRevisionConflictsBeforeSucceeding()
    {
        AtomicInteger attempts = new AtomicInteger();

        RemoteConnectivityService.retryChannelMutation(() -> {
            int attempt = attempts.incrementAndGet();
            if(attempt < 3)
            {
                throw new ChannelAdministrationService.StaleRevisionException(attempt, attempt + 1);
            }
        });

        assertEquals(3, attempts.get());
    }

    @Test
    void persistentChannelRevisionConflictStopsAfterThreeAttempts()
    {
        AtomicInteger attempts = new AtomicInteger();
        ChannelAdministrationService.StaleRevisionException conflict =
            new ChannelAdministrationService.StaleRevisionException(7, 8);

        assertSame(conflict, assertThrows(ChannelAdministrationService.StaleRevisionException.class,
            () -> RemoteConnectivityService.retryChannelMutation(() -> {
                attempts.incrementAndGet();
                throw conflict;
            })));

        assertEquals(3, attempts.get());
    }

    @Test
    void validationPersistenceAndExternalRevisionErrorsAreNeverRetried()
    {
        List<RuntimeException> failures = List.of(
            new IllegalArgumentException("Invalid channel"),
            new ChannelAdministrationService.PersistenceException("Unable to save", null),
            new ChannelAdministrationService.ConfigurationBusyException(),
            new RemoteLinkAdministrationService.StaleRevisionException());

        for(RuntimeException failure: failures)
        {
            AtomicInteger attempts = new AtomicInteger();
            assertSame(failure, assertThrows(failure.getClass(),
                () -> RemoteConnectivityService.retryChannelMutation(() -> {
                    attempts.incrementAndGet();
                    throw failure;
                })));
            assertEquals(1, attempts.get(), failure.getClass().getSimpleName());
        }
    }
}
