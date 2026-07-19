/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed-cardinality, in-memory web feature policy.  Reads use one immutable snapshot.  Mutations and synchronous
 * listener delivery are serialized so listener revisions are ordered and a PUBLIC to ADMIN_ONLY update can revoke
 * anonymous live subscriptions before the update call returns.
 *
 * This class stores feature policy only.  It stores no sessions, subscriptions, viewer identities, or access history.
 */
public final class InMemoryFeatureAccessPolicy implements FeatureAccessGateway
{
    public static final int DEFAULT_MAXIMUM_LISTENERS = 32;
    private static final Logger mLog = LoggerFactory.getLogger(InMemoryFeatureAccessPolicy.class);
    private final ReentrantLock mMutationLock = new ReentrantLock();
    private final List<ListenerRegistration> mListeners = new ArrayList<>();
    private final int mMaximumListeners;
    private volatile Snapshot mSnapshot;

    private InMemoryFeatureAccessPolicy(Map<WebFeature,FeatureAccessMode> modes, int maximumListeners)
    {
        if(maximumListeners < 1)
        {
            throw new IllegalArgumentException("Maximum listeners must be positive");
        }

        mMaximumListeners = maximumListeners;
        mSnapshot = new Snapshot(0, modes);
    }

    /**
     * Compatibility defaults for a profile that already exposes the current stats interface.  Existing status,
     * statistics, and call audio remain public, and the new read-only wideband signal view starts public.  Other new
     * monitoring features start admin-only.
     */
    public static InMemoryFeatureAccessPolicy currentProfileDefaults()
    {
        EnumMap<WebFeature,FeatureAccessMode> modes = allFeatures(FeatureAccessMode.ADMIN_ONLY);
        modes.put(WebFeature.STATUS_STATISTICS, FeatureAccessMode.PUBLIC);
        modes.put(WebFeature.CALL_AUDIO, FeatureAccessMode.PUBLIC);
        modes.put(WebFeature.WIDEBAND_SIGNAL, FeatureAccessMode.PUBLIC);
        return new InMemoryFeatureAccessPolicy(modes, DEFAULT_MAXIMUM_LISTENERS);
    }

    /**
     * Secure defaults for a newly created profile.
     */
    public static InMemoryFeatureAccessPolicy newProfileDefaults()
    {
        return new InMemoryFeatureAccessPolicy(allFeatures(FeatureAccessMode.ADMIN_ONLY), DEFAULT_MAXIMUM_LISTENERS);
    }

    /**
     * Creates a policy from a complete feature map, primarily for composition and tests.
     */
    public static InMemoryFeatureAccessPolicy create(Map<WebFeature,FeatureAccessMode> modes)
    {
        return new InMemoryFeatureAccessPolicy(modes, DEFAULT_MAXIMUM_LISTENERS);
    }

    /**
     * Creates a policy from a complete feature map with a bounded listener count.
     */
    public static InMemoryFeatureAccessPolicy create(Map<WebFeature,FeatureAccessMode> modes, int maximumListeners)
    {
        return new InMemoryFeatureAccessPolicy(modes, maximumListeners);
    }

    private static EnumMap<WebFeature,FeatureAccessMode> allFeatures(FeatureAccessMode mode)
    {
        EnumMap<WebFeature,FeatureAccessMode> modes = new EnumMap<>(WebFeature.class);

        for(WebFeature feature: WebFeature.values())
        {
            modes.put(feature, mode);
        }

        return modes;
    }

    @Override
    public FeatureAccessDecision authorize(FeatureAccessRequest request)
    {
        Objects.requireNonNull(request, "Access request cannot be null");
        Snapshot snapshot = mSnapshot;
        FeatureAccessMode mode = snapshot.mode(request.feature());
        boolean allowed = mode == FeatureAccessMode.PUBLIC || request.subject().isAuthenticatedAdmin();
        FeatureAccessDecision.Outcome outcome = allowed ? FeatureAccessDecision.Outcome.ALLOWED :
            FeatureAccessDecision.Outcome.AUTHENTICATION_REQUIRED;
        return new FeatureAccessDecision(request, mode, snapshot.revision(), outcome);
    }

    public Snapshot snapshot()
    {
        return mSnapshot;
    }

    public long getRevision()
    {
        return mSnapshot.revision();
    }

    public FeatureAccessMode getMode(WebFeature feature)
    {
        return mSnapshot.mode(feature);
    }

    /**
     * Atomically updates one feature.  A no-op update neither increments the revision nor notifies listeners.
     */
    public Optional<FeaturePolicyChange> setMode(WebFeature feature, FeatureAccessMode mode)
    {
        Objects.requireNonNull(feature, "Feature cannot be null");
        Objects.requireNonNull(mode, "Feature access mode cannot be null");
        mMutationLock.lock();

        try
        {
            Snapshot previous = mSnapshot;
            FeatureAccessMode previousMode = previous.mode(feature);

            if(previousMode == mode)
            {
                return Optional.empty();
            }

            long revision = Math.incrementExact(previous.revision());
            EnumMap<WebFeature,FeatureAccessMode> modes = new EnumMap<>(previous.modes());
            modes.put(feature, mode);
            mSnapshot = new Snapshot(revision, modes);
            FeaturePolicyChange change = new FeaturePolicyChange(feature, previousMode, mode, previous.revision(),
                revision);

            for(ListenerRegistration registration: List.copyOf(mListeners))
            {
                registration.publish(change);
            }

            return Optional.of(change);
        }
        finally
        {
            mMutationLock.unlock();
        }
    }

    /**
     * Registers a policy listener.  Registrations are process-local and bounded; callers should register one listener
     * per transport broker rather than one listener per viewer.
     */
    public Registration addListener(FeaturePolicyListener listener)
    {
        Objects.requireNonNull(listener, "Feature policy listener cannot be null");
        mMutationLock.lock();

        try
        {
            if(mListeners.size() >= mMaximumListeners)
            {
                throw new IllegalStateException("Maximum feature policy listener count reached: " + mMaximumListeners);
            }

            ListenerRegistration registration = new ListenerRegistration(listener);
            mListeners.add(registration);
            return registration;
        }
        finally
        {
            mMutationLock.unlock();
        }
    }

    public int getListenerCount()
    {
        mMutationLock.lock();

        try
        {
            return mListeners.size();
        }
        finally
        {
            mMutationLock.unlock();
        }
    }

    /**
     * Immutable complete policy snapshot.
     */
    public record Snapshot(long revision, Map<WebFeature,FeatureAccessMode> modes)
    {
        public Snapshot
        {
            if(revision < 0)
            {
                throw new IllegalArgumentException("Policy revision cannot be negative");
            }

            Objects.requireNonNull(modes, "Feature modes cannot be null");
            EnumMap<WebFeature,FeatureAccessMode> copy = new EnumMap<>(WebFeature.class);
            copy.putAll(modes);

            for(WebFeature feature: WebFeature.values())
            {
                if(copy.get(feature) == null)
                {
                    throw new IllegalArgumentException("Feature policy is missing mode for: " + feature.getId());
                }
            }

            if(copy.size() != WebFeature.values().length)
            {
                throw new IllegalArgumentException("Feature policy contains an unsupported feature");
            }

            modes = Collections.unmodifiableMap(copy);
        }

        public FeatureAccessMode mode(WebFeature feature)
        {
            return modes.get(Objects.requireNonNull(feature, "Feature cannot be null"));
        }
    }

    /**
     * Closeable process-local listener registration.
     */
    public interface Registration extends AutoCloseable
    {
        @Override
        void close();
    }

    private final class ListenerRegistration implements Registration
    {
        private final FeaturePolicyListener mListener;
        private boolean mClosed;

        private ListenerRegistration(FeaturePolicyListener listener)
        {
            mListener = listener;
        }

        private void publish(FeaturePolicyChange change)
        {
            if(!mClosed)
            {
                try
                {
                    mListener.policyChanged(change);
                }
                catch(RuntimeException e)
                {
                    mLog.warn("Feature access policy listener failed at revision [{}]", change.revision(), e);
                }
            }
        }

        @Override
        public void close()
        {
            mMutationLock.lock();

            try
            {
                if(!mClosed)
                {
                    mClosed = true;
                    mListeners.remove(this);
                }
            }
            finally
            {
                mMutationLock.unlock();
            }
        }
    }
}
