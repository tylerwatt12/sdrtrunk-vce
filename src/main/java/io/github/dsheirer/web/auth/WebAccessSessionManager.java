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
 * Fixed-capacity, memory-only browser sessions with idle and absolute expiration.
 *
 * <p>Resolution verifies the account's complete current metadata.  Password resets, role changes, account deletion,
 * and primary-administrator resets therefore revoke old sessions on their next use.</p>
 */
public final class WebAccessSessionManager implements AutoCloseable
{
    private static final int MAXIMUM_TOKEN_COLLISION_ATTEMPTS = 16;
    private static final int MAXIMUM_SESSIONS_PER_ACCOUNT = 8;
    private static final int PRIMARY_ADMIN_RESERVED_SESSIONS = 2;
    private final Configuration mConfiguration;
    private final SecureRandom mSecureRandom;
    private final Clock mClock;
    private final ReentrantLock mLock = new ReentrantLock();
    private final Map<String,SessionState> mSessions = new HashMap<>();

    public WebAccessSessionManager()
    {
        this(Configuration.defaults(), new SecureRandom(), Clock.systemUTC());
    }

    public WebAccessSessionManager(Configuration configuration)
    {
        this(configuration, new SecureRandom(), Clock.systemUTC());
    }

    WebAccessSessionManager(Configuration configuration, SecureRandom secureRandom, Clock clock)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Session configuration cannot be null");
        mSecureRandom = Objects.requireNonNull(secureRandom, "Secure random cannot be null");
        mClock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    public Optional<WebAccessSession> create(WebAccessAccount account)
    {
        return createOrReuseAtCapacity(account, null);
    }

    /**
     * Creates a new session when capacity permits.  At capacity, a caller that already holds a current session for
     * the authenticated account may keep that session and refresh its idle lifetime.  The existing session's token,
     * CSRF token, creation time, and absolute expiration are preserved.
     */
    Optional<WebAccessSession> createOrReuseAtCapacity(WebAccessAccount account, String existingSessionId)
    {
        Objects.requireNonNull(account, "Web session account cannot be null");
        mLock.lock();

        try
        {
            long now = nonNegativeNow();
            removeExpired(now);
            long accountSessions = mSessions.values().stream()
                .filter(state -> state.account.username().equals(account.username()))
                .count();
            int reservedForPrimary = Math.min(PRIMARY_ADMIN_RESERVED_SESSIONS,
                Math.max(0, mConfiguration.maximumSessions() - 1));
            boolean accountCapacityReached = accountSessions >=
                Math.min(MAXIMUM_SESSIONS_PER_ACCOUNT, mConfiguration.maximumSessions());
            boolean accountClassCapacityReached = !account.primaryAdmin() &&
                mSessions.size() >= mConfiguration.maximumSessions() - reservedForPrimary;
            boolean totalCapacityReached = mSessions.size() >= mConfiguration.maximumSessions();

            if(accountCapacityReached || accountClassCapacityReached || totalCapacityReached)
            {
                return refreshMatchingSession(existingSessionId, account, now);
            }

            for(int attempt = 0; attempt < MAXIMUM_TOKEN_COLLISION_ATTEMPTS; attempt++)
            {
                String sessionId = token();

                if(!sessionId.equals(existingSessionId) && !mSessions.containsKey(sessionId))
                {
                    SessionState state = new SessionState(sessionId, token(), account, now,
                        saturatingAdd(now, mConfiguration.absoluteTimeout().toMillis()));
                    mSessions.put(sessionId, state);
                    return Optional.of(snapshot(state));
                }
            }

            throw new IllegalStateException("Unable to allocate a unique web session identifier");
        }
        finally
        {
            mLock.unlock();
        }
    }

    private Optional<WebAccessSession> refreshMatchingSession(String sessionId, WebAccessAccount account, long now)
    {
        if(!hasExpectedTokenLength(sessionId))
        {
            return Optional.empty();
        }

        SessionState state = mSessions.get(sessionId);

        if(state == null || !state.account.equals(account))
        {
            return Optional.empty();
        }

        state.lastSeenAtEpochMillis = Math.max(state.lastSeenAtEpochMillis, now);
        return Optional.of(snapshot(state));
    }

    /**
     * Resolves and refreshes an unexpired session only if its account, role, and credential version remain current.
     */
    public Optional<WebAccessSession> resolve(String sessionId, WebAccessService accessService)
    {
        Objects.requireNonNull(accessService, "Web access service cannot be null");

        if(!hasExpectedTokenLength(sessionId))
        {
            return Optional.empty();
        }

        WebAccessSession resolved;
        mLock.lock();

        try
        {
            long now = nonNegativeNow();
            SessionState state = mSessions.get(sessionId);

            if(state == null)
            {
                return Optional.empty();
            }

            if(isExpired(state, now) || !accessService.isCurrent(state.account))
            {
                mSessions.remove(sessionId);
                return Optional.empty();
            }

            state.lastSeenAtEpochMillis = Math.max(state.lastSeenAtEpochMillis, now);
            resolved = snapshot(state);
        }
        finally
        {
            mLock.unlock();
        }

        if(!accessService.isCurrent(resolved.account()))
        {
            invalidate(sessionId);
            return Optional.empty();
        }

        return Optional.of(resolved);
    }

    public boolean validateCsrf(String sessionId, String candidateCsrfToken, WebAccessService accessService)
    {
        if(!hasExpectedTokenLength(candidateCsrfToken))
        {
            return false;
        }

        Optional<WebAccessSession> resolved = resolve(sessionId, accessService);

        if(resolved.isEmpty())
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

    public int invalidateAccount(String username)
    {
        String normalized;

        try
        {
            normalized = WebAdminCredential.normalizeUsername(username);
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            return 0;
        }

        mLock.lock();

        try
        {
            int before = mSessions.size();
            mSessions.values().removeIf(state -> state.account.username().equals(normalized));
            return before - mSessions.size();
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

    private WebAccessSession snapshot(SessionState state)
    {
        long idleExpiry = saturatingAdd(state.lastSeenAtEpochMillis, mConfiguration.idleTimeout().toMillis());
        return new WebAccessSession(state.sessionId, state.csrfToken, state.account, state.createdAtEpochMillis,
            state.lastSeenAtEpochMillis, Math.min(state.absoluteExpiresAtEpochMillis, idleExpiry));
    }

    private void removeExpired(long now)
    {
        Iterator<SessionState> iterator = mSessions.values().iterator();

        while(iterator.hasNext())
        {
            if(isExpired(iterator.next(), now))
            {
                iterator.remove();
            }
        }
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
            if(maximumSessions < 1 || maximumSessions > 256)
            {
                throw new IllegalArgumentException("Maximum web sessions must be between 1 and 256");
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
            return new Configuration(64, Duration.ofMinutes(30), Duration.ofHours(12), 32);
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
        private final WebAccessAccount account;
        private final long createdAtEpochMillis;
        private final long absoluteExpiresAtEpochMillis;
        private long lastSeenAtEpochMillis;

        private SessionState(String sessionId, String csrfToken, WebAccessAccount account,
                             long createdAtEpochMillis, long absoluteExpiresAtEpochMillis)
        {
            this.sessionId = sessionId;
            this.csrfToken = csrfToken;
            this.account = account;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.absoluteExpiresAtEpochMillis = absoluteExpiresAtEpochMillis;
            lastSeenAtEpochMillis = createdAtEpochMillis;
        }
    }
}
