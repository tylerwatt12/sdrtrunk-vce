/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transport-neutral bounded login worker and transient-session owner.
 */
public final class WebAuthenticationService implements AutoCloseable
{
    private static final int MAXIMUM_SOURCE_KEY_CHARACTERS = 128;
    private static final Duration EXECUTOR_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private final WebAccessService mAccessService;
    private final WebAccessSessionManager mSessionManager;
    private final LoginThrottle mLoginThrottle;
    private final AccountLoginAdmissionLimiter mAdmissionLimiter;
    private final ThreadPoolExecutor mLoginExecutor;
    private final AtomicBoolean mClosed = new AtomicBoolean();

    public WebAuthenticationService(WebAccessService accessService)
    {
        this(accessService, new WebAccessSessionManager(), LoginThrottle.Configuration.defaults(),
            AccountLoginAdmissionLimiter.Configuration.defaults(), Clock.systemUTC(), 2);
    }

    WebAuthenticationService(WebAccessService accessService, WebAccessSessionManager sessionManager,
                             LoginThrottle.Configuration throttleConfiguration,
                             AccountLoginAdmissionLimiter.Configuration admissionConfiguration, Clock clock,
                             int maximumPendingLoginTasks)
    {
        mAccessService = Objects.requireNonNull(accessService, "Web access service cannot be null");
        mSessionManager = Objects.requireNonNull(sessionManager, "Web session manager cannot be null");
        Clock actualClock = Objects.requireNonNull(clock, "Clock cannot be null");
        mLoginThrottle = new LoginThrottle(throttleConfiguration, actualClock);
        mAdmissionLimiter = new AccountLoginAdmissionLimiter(admissionConfiguration, actualClock);

        if(maximumPendingLoginTasks < 1 || maximumPendingLoginTasks > 32)
        {
            throw new IllegalArgumentException("Maximum pending login tasks must be between 1 and 32");
        }

        mLoginExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(maximumPendingLoginTasks), runnable ->
            {
                Thread thread = Thread.ofPlatform().daemon(true).name("sdrtrunk web authentication")
                    .unstarted(runnable);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Schedules at most one PBKDF2 calculation at a time with a small bounded pending queue.
     */
    public CompletableFuture<LoginResult> login(String username, char[] password, String sourceKey)
    {
        return login(username, password, sourceKey, null);
    }

    /**
     * Schedules a login while retaining the caller's current session as a capacity-safe fallback.  A session is
     * reused only after successful credential verification, only when a new session cannot be allocated, and only
     * when it belongs to the exact current account metadata.
     */
    public CompletableFuture<LoginResult> login(String username, char[] password, String sourceKey,
                                                 String existingSessionId)
    {
        if(mClosed.get())
        {
            return CompletableFuture.completedFuture(LoginResult.of(LoginStatus.BUSY));
        }

        String boundedSourceKey = boundedSourceKey(sourceKey);
        LoginThrottle.Decision decision = mLoginThrottle.check(boundedSourceKey);

        if(!decision.allowed())
        {
            return CompletableFuture.completedFuture(LoginResult.throttled(decision.retryAfterMillis()));
        }

        AccountLoginAdmissionLimiter.Decision admission = mAdmissionLimiter.tryAcquire();

        if(!admission.allowed())
        {
            return CompletableFuture.completedFuture(LoginResult.throttled(admission.retryAfterMillis()));
        }

        LoginTask task = new LoginTask(boundedUsername(username), boundedPasswordCopy(password), boundedSourceKey,
            existingSessionId);

        try
        {
            mLoginExecutor.execute(task);
        }
        catch(RejectedExecutionException exception)
        {
            task.clearPassword();
            task.completion.complete(LoginResult.of(LoginStatus.BUSY));
        }

        return task.completion;
    }

    public Optional<WebAccessSession> resolveSession(String sessionId)
    {
        return mSessionManager.resolve(sessionId, mAccessService);
    }

    public boolean validateCsrf(String sessionId, String csrfToken)
    {
        return mSessionManager.validateCsrf(sessionId, csrfToken, mAccessService);
    }

    public boolean logout(String sessionId)
    {
        return mSessionManager.invalidate(sessionId);
    }

    public int invalidateAccountSessions(String username)
    {
        return mSessionManager.invalidateAccount(username);
    }

    public void invalidateAllSessions()
    {
        mSessionManager.invalidateAll();
    }

    public int getActiveSessionCount()
    {
        return mSessionManager.getActiveSessionCount();
    }

    private LoginResult authenticate(String username, char[] password, String sourceKey, String existingSessionId)
    {
        Optional<WebAccessAccount> authenticated = mAccessService.authenticate(username, password);

        if(authenticated.isEmpty() || mClosed.get())
        {
            mLoginThrottle.recordFailure(sourceKey);
            return LoginResult.of(LoginStatus.DENIED);
        }

        mLoginThrottle.recordSuccess(sourceKey);
        WebAccessAccount account = authenticated.get();
        Optional<WebAccessSession> session = mSessionManager.createOrReuseAtCapacity(account, existingSessionId);

        if(session.isEmpty())
        {
            return LoginResult.of(LoginStatus.SESSION_CAPACITY);
        }

        if(!mAccessService.isCurrent(account) || mClosed.get())
        {
            mSessionManager.invalidate(session.get().sessionId());
            return LoginResult.of(LoginStatus.DENIED);
        }

        return LoginResult.success(session.get());
    }

    private static char[] boundedPasswordCopy(char[] password)
    {
        if(password == null || password.length > Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS)
        {
            return new char[0];
        }

        return Arrays.copyOf(password, password.length);
    }

    private static String boundedSourceKey(String sourceKey)
    {
        if(sourceKey == null || sourceKey.isBlank())
        {
            return "unknown";
        }

        String normalized = sourceKey.strip().toLowerCase(java.util.Locale.ROOT);

        if(normalized.length() <= MAXIMUM_SOURCE_KEY_CHARACTERS)
        {
            return normalized;
        }

        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        }
        catch(java.security.NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("Required source-key digest is unavailable", exception);
        }
    }

    private static String boundedUsername(String username)
    {
        if(username == null || username.length() > WebAdminCredential.MAXIMUM_USERNAME_CHARACTERS * 4)
        {
            return "invalid";
        }

        return username;
    }

    @Override
    public void close()
    {
        if(!mClosed.compareAndSet(false, true))
        {
            return;
        }

        List<Runnable> pending = mLoginExecutor.shutdownNow();

        for(Runnable runnable: pending)
        {
            if(runnable instanceof LoginTask loginTask)
            {
                loginTask.clearPassword();
                loginTask.completion.complete(LoginResult.of(LoginStatus.BUSY));
            }
        }

        try
        {
            mLoginExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }

        mSessionManager.close();
        mLoginThrottle.clear();
        mAdmissionLimiter.clear();
    }

    public enum LoginStatus
    {
        SUCCESS,
        DENIED,
        THROTTLED,
        BUSY,
        SESSION_CAPACITY
    }

    public record LoginResult(LoginStatus status, Optional<WebAccessSession> session, long retryAfterMillis)
    {
        public LoginResult
        {
            Objects.requireNonNull(status, "Login status cannot be null");
            session = Objects.requireNonNull(session, "Login session cannot be null");

            if((status == LoginStatus.SUCCESS) != session.isPresent() || retryAfterMillis < 0)
            {
                throw new IllegalArgumentException("Invalid web login result");
            }
        }

        private static LoginResult success(WebAccessSession session)
        {
            return new LoginResult(LoginStatus.SUCCESS, Optional.of(session), 0);
        }

        private static LoginResult throttled(long retryAfterMillis)
        {
            return new LoginResult(LoginStatus.THROTTLED, Optional.empty(), retryAfterMillis);
        }

        private static LoginResult of(LoginStatus status)
        {
            return new LoginResult(status, Optional.empty(), 0);
        }

        @Override
        public String toString()
        {
            return "LoginResult[status=" + status + ", session=" +
                (session.isPresent() ? "<redacted>" : "empty") + ", retryAfterMillis=" + retryAfterMillis + "]";
        }
    }

    private final class LoginTask implements Runnable
    {
        private final String mUsername;
        private final String mSourceKey;
        private final String mExistingSessionId;
        private final CompletableFuture<LoginResult> completion = new CompletableFuture<>();
        private char[] mPassword;

        private LoginTask(String username, char[] password, String sourceKey, String existingSessionId)
        {
            mUsername = username;
            mPassword = password;
            mSourceKey = sourceKey;
            mExistingSessionId = existingSessionId;
        }

        @Override
        public void run()
        {
            try
            {
                LoginResult result = mClosed.get() ? LoginResult.of(LoginStatus.BUSY) :
                    authenticate(mUsername, mPassword, mSourceKey, mExistingSessionId);

                // A timed-out HTTP request may cancel its completion while PBKDF2 is still finishing.  Never retain
                // the session that task created when there is no caller left to receive its cookie.
                if(!completion.complete(result) && result.session().isPresent() &&
                    !result.session().get().sessionId().equals(mExistingSessionId))
                {
                    mSessionManager.invalidate(result.session().get().sessionId());
                }
            }
            catch(Throwable throwable)
            {
                completion.completeExceptionally(throwable);
            }
            finally
            {
                clearPassword();
            }
        }

        private void clearPassword()
        {
            char[] value = mPassword;
            mPassword = null;

            if(value != null)
            {
                Arrays.fill(value, '\u0000');
            }
        }
    }
}
