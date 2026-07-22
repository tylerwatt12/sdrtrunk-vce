/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.web.diagnostic;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local lease that keeps the one bounded FFT/symbol diagnostic workspace exclusive across web views.
 */
public final class DiagnosticWorkspaceLease
{
    private final AtomicReference<Lease> mActiveLease = new AtomicReference<>();

    public Optional<Lease> tryAcquire(Owner owner)
    {
        Lease candidate = new Lease(Objects.requireNonNull(owner, "Diagnostic workspace owner cannot be null"));
        return mActiveLease.compareAndSet(null, candidate) ? Optional.of(candidate) : Optional.empty();
    }

    public Optional<Owner> getOwner()
    {
        Lease lease = mActiveLease.get();
        return lease != null ? Optional.of(lease.owner()) : Optional.empty();
    }

    public boolean isActive()
    {
        return mActiveLease.get() != null;
    }

    public enum Owner
    {
        WIDEBAND_SIGNAL,
        SELECTED_CHANNEL
    }

    public final class Lease implements AutoCloseable
    {
        private final Owner mOwner;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private Lease(Owner owner)
        {
            mOwner = owner;
        }

        public Owner owner()
        {
            return mOwner;
        }

        public boolean isClosed()
        {
            return mClosed.get();
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mActiveLease.compareAndSet(this, null);
            }
        }
    }
}
