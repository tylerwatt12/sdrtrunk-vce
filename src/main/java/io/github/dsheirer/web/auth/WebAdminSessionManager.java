/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-capacity, in-memory administrator sessions with idle and absolute expiration.
 */
public final class WebAdminSessionManager implements AutoCloseable
{
    private static final int MAXIMUM_TOKEN_COLLISION_ATTEMPTS = 16;
    private final Configuration mConfiguration;
    private final SecureRandom mSecureRandom;
    private final Clock mClock;
    private final ReentrantLock mLock = new ReentrantLock();
    private final Map<String,SessionState> mSessions = new HashMap<>();

    public WebAdminSessionManager()
    {
        this(Configuration.defaults(), new SecureRandom(), Clock.systemUTC());
    }

    public WebAdminSessionManager(Configuration configuration)
    {
        this(configuration, new SecureRandom(), Clock.systemUTC());
    }

    WebAdminSessionManager(Configuration configuration, SecureRandom secureRandom, Clock clock)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Session configuration cannot be null");
        mSecureRandom = Objects.requireNonNull(secureRandom, "Secure random cannot be null");
        mClock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    public Optional<WebAdminSession> create(long authGeneration)
    {
        if(authGeneration < 1)
        {
            throw new IllegalArgumentException("Auth generation must be positive");
        }

        mLock.lock();

        try
        {
            long now = nonNegativeNow();
            removeExpired(now);

            if(mSessions.size() >= mConfiguration.maximumSessions())
            {
                return Optional.empty();
            }

            for(int attempt = 0; attempt < MAXIMUM_TOKEN_COLLISION_ATTEMPTS; attempt++)
            {
                String sessionId = token();

                if(!mSessions.containsKey(sessionId))
                {
                    SessionState state = new SessionState(sessionId, token(), now,
                        saturatingAdd(now, mConfiguration.absoluteTimeout().toMillis()), authGeneration);
                    mSessions.put(sessionId, state);
                    return Optional.of(snapshot(state));
                }
            }

            throw new IllegalStateException("Unable to allocate a unique web administrator session identifier");
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Resolves and refreshes an unexpired session for the current credential generation.
     */
    public Optional<WebAdminSession> resolve(String sessionId, long currentAuthGeneration)
    {
        if(!hasExpectedTokenLength(sessionId) || currentAuthGeneration < 1)
        {
            return Optional.empty();
        }

        mLock.lock();

        try
        {
            long now = nonNegativeNow();
            SessionState state = mSessions.get(sessionId);

            if(state == null)
            {
                return Optional.empty();
            }

            if(isExpired(state, now) || state.authGeneration != currentAuthGeneration)
            {
                mSessions.remove(sessionId);
                return Optional.empty();
            }

            state.lastSeenAtEpochMillis = Math.max(state.lastSeenAtEpochMillis, now);
            return Optional.of(snapshot(state));
        }
        finally
        {
            mLock.unlock();
        }
    }

    public boolean validateCsrf(String sessionId, String candidateCsrfToken, long currentAuthGeneration)
    {
        if(!hasExpectedTokenLength(candidateCsrfToken))
        {
            return false;
        }

        Optional<WebAdminSession> resolved = resolve(sessionId, currentAuthGeneration);

        if(resolved.isEmpty() || candidateCsrfToken == null)
        {
            return false;
        }

        byte[] expected = resolved.get().csrfToken().getBytes(StandardCharsets.US_ASCII);
        byte[] candidate = candidateCsrfToken.getBytes(StandardCharsets.US_ASCII);

        try
        {
            return MessageDigest.isEqual(expected, candidate);
        }
        finally
        {
            Arrays.fill(expected, (byte)0);
            Arrays.fill(candidate, (byte)0);
        }
    }

    public boolean invalidate(String sessionId)
    {
        if(sessionId == null)
        {
            return false;
        }

        mLock.lock();

        try
        {
            return mSessions.remove(sessionId) != null;
        }
        finally
        {
            mLock.unlock();
        }
    }

    public int invalidateExceptGeneration(long currentAuthGeneration)
    {
        mLock.lock();

        try
        {
            int removed = 0;
            Iterator<SessionState> iterator = mSessions.values().iterator();

            while(iterator.hasNext())
            {
                if(iterator.next().authGeneration != currentAuthGeneration)
                {
                    iterator.remove();
                    removed++;
                }
            }

            return removed;
        }
        finally
        {
            mLock.unlock();
        }
    }

    public void invalidateAll()
    {
        mLock.lock();

        try
        {
            mSessions.clear();
        }
        finally
        {
            mLock.unlock();
        }
    }

    public int getActiveSessionCount()
    {
        mLock.lock();

        try
        {
            removeExpired(nonNegativeNow());
            return mSessions.size();
        }
        finally
        {
            mLock.unlock();
        }
    }

    private WebAdminSession snapshot(SessionState state)
    {
        long idleExpiry = saturatingAdd(state.lastSeenAtEpochMillis, mConfiguration.idleTimeout().toMillis());
        return new WebAdminSession(state.sessionId, state.csrfToken, state.createdAtEpochMillis,
            state.lastSeenAtEpochMillis, Math.min(state.absoluteExpiresAtEpochMillis, idleExpiry),
            state.authGeneration);
    }

    private void removeExpired(long now)
    {
        mSessions.values().removeIf(state -> isExpired(state, now));
    }

    private boolean isExpired(SessionState state, long now)
    {
        return now >= state.absoluteExpiresAtEpochMillis ||
            now - state.lastSeenAtEpochMillis >= mConfiguration.idleTimeout().toMillis();
    }

    private String token()
    {
        byte[] bytes = new byte[mConfiguration.tokenBytes()];
        mSecureRandom.nextBytes(bytes);

        try
        {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        finally
        {
            Arrays.fill(bytes, (byte)0);
        }
    }

    private boolean hasExpectedTokenLength(String token)
    {
        int encodedCharacters = (mConfiguration.tokenBytes() * Byte.SIZE + 5) / 6;
        return token != null && token.length() == encodedCharacters;
    }

    private long nonNegativeNow()
    {
        return Math.max(0, mClock.millis());
    }

    private static long saturatingAdd(long value, long increment)
    {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    @Override
    public void close()
    {
        invalidateAll();
    }

    public record Configuration(int maximumSessions, Duration idleTimeout, Duration absoluteTimeout, int tokenBytes)
    {
        public Configuration
        {
            if(maximumSessions < 1 || maximumSessions > 64)
            {
                throw new IllegalArgumentException("Maximum web administrator sessions must be between 1 and 64");
            }

            requirePositive(idleTimeout, "Session idle timeout");
            requirePositive(absoluteTimeout, "Session absolute timeout");

            if(idleTimeout.compareTo(absoluteTimeout) > 0)
            {
                throw new IllegalArgumentException("Session idle timeout cannot exceed its absolute timeout");
            }

            if(tokenBytes < 32 || tokenBytes > 64)
            {
                throw new IllegalArgumentException("Session token size must be between 32 and 64 bytes");
            }
        }

        public static Configuration defaults()
        {
            return new Configuration(8, Duration.ofMinutes(30), Duration.ofHours(12), 32);
        }

        private static void requirePositive(Duration duration, String label)
        {
            Objects.requireNonNull(duration, label + " cannot be null");

            if(duration.isZero() || duration.isNegative() || duration.toMillis() <= 0)
            {
                throw new IllegalArgumentException(label + " must be positive");
            }
        }
    }

    private static final class SessionState
    {
        private final String sessionId;
        private final String csrfToken;
        private final long createdAtEpochMillis;
        private final long absoluteExpiresAtEpochMillis;
        private final long authGeneration;
        private long lastSeenAtEpochMillis;

        private SessionState(String sessionId, String csrfToken, long createdAtEpochMillis,
                             long absoluteExpiresAtEpochMillis, long authGeneration)
        {
            this.sessionId = sessionId;
            this.csrfToken = csrfToken;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.absoluteExpiresAtEpochMillis = absoluteExpiresAtEpochMillis;
            this.authGeneration = authGeneration;
            lastSeenAtEpochMillis = createdAtEpochMillis;
        }
    }
}
