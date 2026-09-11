/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Fixed-capacity, memory-only browser sessions without time-based expiration.
 *
 * <p>Resolution verifies the account's complete current metadata.  Password resets, role changes, account deletion,
 * and primary-administrator resets therefore revoke old sessions on their next use.</p>
 */
public final class WebAccessSessionManager implements AutoCloseable
{
    private static final int MAXIMUM_TOKEN_COLLISION_ATTEMPTS = 16;
    private static final int PRIMARY_ADMIN_RESERVED_SESSIONS = 2;
    private final Configuration mConfiguration;
    private final SecureRandom mSecureRandom;
    private final ReentrantLock mLock = new ReentrantLock();
    /** Access ordering supports non-time-based replacement when abandoned browser cookies consume session capacity. */
    private final Map<String,SessionState> mSessions = new LinkedHashMap<>(16, 0.75f, true);

    public WebAccessSessionManager()
    {
        this(Configuration.defaults(), new SecureRandom());
    }

    public WebAccessSessionManager(Configuration configuration)
    {
        this(configuration, new SecureRandom());
    }

    WebAccessSessionManager(Configuration configuration, SecureRandom secureRandom)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Session configuration cannot be null");
        mSecureRandom = Objects.requireNonNull(secureRandom, "Secure random cannot be null");
    }

    public Optional<WebAccessSession> create(WebAccessAccount account)
    {
        return createOrReuseAtCapacity(account, null);
    }

    /**
     * Creates a new session when capacity permits. At capacity, a caller that already holds a current session for the
     * authenticated account keeps that session. Otherwise, the least-recently-used eligible session is replaced so
     * that browser-session cookies discarded without signing out cannot permanently prevent future logins.
     */
    Optional<WebAccessSession> createOrReuseAtCapacity(WebAccessAccount account, String existingSessionId)
    {
        Objects.requireNonNull(account, "Web session account cannot be null");
        mLock.lock();

        try
        {
            int reservedForPrimary = Math.min(PRIMARY_ADMIN_RESERVED_SESSIONS,
                Math.max(0, mConfiguration.maximumSessions() - 1));
            long primarySessions = mSessions.values().stream().filter(state -> state.account.primaryAdmin()).count();
            long ordinarySessions = mSessions.size() - primarySessions;
            boolean ordinaryCapacityReached = !account.primaryAdmin() && ordinarySessions >=
                mConfiguration.maximumSessions() - reservedForPrimary;
            boolean totalCapacityReached = mSessions.size() >= mConfiguration.maximumSessions();
            String replacementSessionId = null;

            if(ordinaryCapacityReached || totalCapacityReached)
            {
                Optional<WebAccessSession> existing = matchingSession(existingSessionId, account);

                if(existing.isPresent())
                {
                    return existing;
                }

                replacementSessionId = replacementSessionId(account, ordinaryCapacityReached,
                    totalCapacityReached, primarySessions, reservedForPrimary);

                if(replacementSessionId == null)
                {
                    return Optional.empty();
                }
            }

            for(int attempt = 0; attempt < MAXIMUM_TOKEN_COLLISION_ATTEMPTS; attempt++)
            {
                String sessionId = token();

                if(!sessionId.equals(existingSessionId) && !mSessions.containsKey(sessionId))
                {
                    SessionState state = new SessionState(sessionId, token(), account);

                    if(replacementSessionId != null)
                    {
                        mSessions.remove(replacementSessionId);
                    }

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

    private Optional<WebAccessSession> matchingSession(String sessionId, WebAccessAccount account)
    {
        if(!hasExpectedTokenLength(sessionId))
        {
            return Optional.empty();
        }

        SessionState state = null;

        for(Map.Entry<String,SessionState> entry: mSessions.entrySet())
        {
            if(entry.getKey().equals(sessionId))
            {
                state = entry.getValue();
                break;
            }
        }

        if(state == null || !state.account.equals(account))
        {
            return Optional.empty();
        }

        //Only a matching session should move to the most-recently-used end of the access-ordered map.
        mSessions.get(sessionId);
        return Optional.of(snapshot(state));
    }

    /**
     * Selects an access-ordered replacement without using session age or wall-clock time. Prefer one of the same
     * account's sessions, then an ordinary session when preserving the primary-administrator reserve. At global
     * capacity, a primary administrator may replace any session and an ordinary account may replace a primary
     * session only when more than the reserved number of primary sessions exist.
     */
    private String replacementSessionId(WebAccessAccount account, boolean ordinaryCapacityReached,
                                        boolean totalCapacityReached, long primarySessions, int reservedForPrimary)
    {
        String sessionId = firstSessionMatching(state -> state.account.username().equals(account.username()));

        if(sessionId == null && ordinaryCapacityReached)
        {
            sessionId = firstSessionMatching(state -> !state.account.primaryAdmin());
        }

        if(sessionId == null && totalCapacityReached && account.primaryAdmin())
        {
            sessionId = firstSessionMatching(state -> true);
        }

        if(sessionId == null && totalCapacityReached && primarySessions > reservedForPrimary)
        {
            sessionId = firstSessionMatching(state -> state.account.primaryAdmin());
        }

        return sessionId;
    }

    private String firstSessionMatching(Predicate<SessionState> predicate)
    {
        for(Map.Entry<String,SessionState> entry: mSessions.entrySet())
        {
            if(predicate.test(entry.getValue()))
            {
                return entry.getKey();
            }
        }

        return null;
    }

    /**
     * Resolves a session only if its account, role, and authentication revision remain current.
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
            SessionState state = mSessions.get(sessionId);

            if(state == null)
            {
                return Optional.empty();
            }

            if(!accessService.isCurrent(state.account))
            {
                mSessions.remove(sessionId);
                return Optional.empty();
            }

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
            normalized = WebPasswordVerifier.normalizeUsername(username);
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
            return mSessions.size();
        }
        finally
        {
            mLock.unlock();
        }
    }

    private WebAccessSession snapshot(SessionState state)
    {
        return new WebAccessSession(state.sessionId, state.csrfToken, state.account);
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

    @Override
    public void close()
    {
        invalidateAll();
    }

    public record Configuration(int maximumSessions, int tokenBytes)
    {
        public Configuration
        {
            if(maximumSessions < 1 || maximumSessions > 256)
            {
                throw new IllegalArgumentException("Maximum web sessions must be between 1 and 256");
            }

            if(tokenBytes < 32 || tokenBytes > 64)
            {
                throw new IllegalArgumentException("Session token size must be between 32 and 64 bytes");
            }
        }

        public static Configuration defaults()
        {
            return new Configuration(64, 32);
        }
    }

    private static final class SessionState
    {
        private final String sessionId;
        private final String csrfToken;
        private final WebAccessAccount account;

        private SessionState(String sessionId, String csrfToken, WebAccessAccount account)
        {
            this.sessionId = sessionId;
            this.csrfToken = csrfToken;
            this.account = account;
        }
    }
}
