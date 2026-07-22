/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lifecycle owner for the one persisted administrator credential, bounded login work, and transient sessions.
 *
 * <p>This service is deliberately transport-neutral.  HTTP cookies, secure-transport requirements, same-origin checks,
 * CSRF headers, and WebSocket invalidation listeners belong to the integration layer.</p>
 */
public final class SingleAdminAuthenticationService implements AutoCloseable, WebAdminAuthenticationOperations
{
    private static final int MAXIMUM_SOURCE_KEY_CHARACTERS = 128;
    private static final Duration EXECUTOR_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private final WebAdminCredentialStore mCredentialStore;
    private final Pbkdf2PasswordHasher mPasswordHasher;
    private final WebAdminSessionManager mSessionManager;
    private final LoginThrottle mLoginThrottle;
    private final AccountLoginAdmissionLimiter mAccountLoginAdmissionLimiter;
    private final ThreadPoolExecutor mLoginExecutor;
    private final ReentrantLock mCredentialMutationLock = new ReentrantLock();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final WebAdminCredential mDummyCredential;
    private volatile WebAdminCredential mCredential;

    public SingleAdminAuthenticationService(WebAdminCredentialStore credentialStore)
        throws IOException, SQLException
    {
        this(credentialStore, new Pbkdf2PasswordHasher(), new WebAdminSessionManager(),
            LoginThrottle.Configuration.defaults(), AccountLoginAdmissionLimiter.Configuration.defaults(),
            Clock.systemUTC(), 1);
    }

    SingleAdminAuthenticationService(WebAdminCredentialStore credentialStore,
                                     Pbkdf2PasswordHasher passwordHasher,
                                     WebAdminSessionManager sessionManager,
                                     LoginThrottle.Configuration throttleConfiguration, Clock clock,
                                     int maximumPendingLoginTasks) throws IOException, SQLException
    {
        this(credentialStore, passwordHasher, sessionManager, throttleConfiguration,
            AccountLoginAdmissionLimiter.Configuration.defaults(), clock, maximumPendingLoginTasks);
    }

    SingleAdminAuthenticationService(WebAdminCredentialStore credentialStore,
                                     Pbkdf2PasswordHasher passwordHasher,
                                     WebAdminSessionManager sessionManager,
                                     LoginThrottle.Configuration throttleConfiguration,
                                     AccountLoginAdmissionLimiter.Configuration admissionConfiguration, Clock clock,
                                     int maximumPendingLoginTasks) throws IOException, SQLException
    {
        mCredentialStore = Objects.requireNonNull(credentialStore, "Credential store cannot be null");
        mPasswordHasher = Objects.requireNonNull(passwordHasher, "Password hasher cannot be null");
        mSessionManager = Objects.requireNonNull(sessionManager, "Session manager cannot be null");
        Clock actualClock = Objects.requireNonNull(clock, "Clock cannot be null");
        mLoginThrottle = new LoginThrottle(throttleConfiguration, actualClock);
        mAccountLoginAdmissionLimiter = new AccountLoginAdmissionLimiter(admissionConfiguration, actualClock);

        if(maximumPendingLoginTasks < 1 || maximumPendingLoginTasks > 32)
        {
            throw new IllegalArgumentException("Maximum pending login tasks must be between 1 and 32");
        }

        mLoginExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(maximumPendingLoginTasks),
            runnable ->
            {
                Thread thread = Thread.ofPlatform().daemon(true).name("sdrtrunk web authentication")
                    .unstarted(runnable);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
        mCredential = mCredentialStore.load().orElse(null);

        if(mCredential != null)
        {
            mDummyCredential = mCredential;
        }
        else
        {
            char[] dummyPassword = "sdrtrunk-dummy-password-verifier".toCharArray();

            try
            {
                mDummyCredential = mPasswordHasher.createCredential("admin", dummyPassword, 1);
            }
            finally
            {
                Arrays.fill(dummyPassword, '\u0000');
            }
        }
    }

    @Override
    public boolean isConfigured()
    {
        return mCredential != null;
    }

    @Override
    public Optional<CredentialMetadata> getCredentialMetadata()
    {
        WebAdminCredential credential = mCredential;
        return credential == null ? Optional.empty() : Optional.of(metadata(credential));
    }

    /**
     * Creates or replaces the only credential.  This method is for a protected local bootstrap/recovery owner; it must
     * never be exposed as an unauthenticated web route.
     */
    public CredentialMetadata provisionOrReset(String username, char[] password) throws IOException, SQLException
    {
        requireOpen();
        char[] copy = copyPassword(password);
        mCredentialMutationLock.lock();

        try
        {
            long generation = mCredential == null ? 1 : Math.incrementExact(mCredential.authGeneration());
            WebAdminCredential replacement = mPasswordHasher.createCredential(username, copy, generation);
            mCredentialStore.save(replacement);
            mCredential = replacement;
            mSessionManager.invalidateAll();
            mLoginThrottle.clear();
            mAccountLoginAdmissionLimiter.clear();
            return metadata(replacement);
        }
        finally
        {
            mCredentialMutationLock.unlock();
            Arrays.fill(copy, '\u0000');
        }
    }

    /**
     * Schedules at most one PBKDF2 calculation at a time with a small, bounded pending queue.
     */
    @Override
    public CompletableFuture<LoginResult> login(String username, char[] password, String sourceKey)
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

        AccountLoginAdmissionLimiter.Decision accountDecision = mAccountLoginAdmissionLimiter.tryAcquire();

        if(!accountDecision.allowed())
        {
            return CompletableFuture.completedFuture(LoginResult.throttled(accountDecision.retryAfterMillis()));
        }

        char[] copy = boundedPasswordCopy(password);
        LoginTask task = new LoginTask(boundedUsername(username), copy, boundedSourceKey);

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

    @Override
    public Optional<WebAdminSession> resolveSession(String sessionId)
    {
        WebAdminCredential credential = mCredential;
        return credential == null ? Optional.empty() : mSessionManager.resolve(sessionId, credential.authGeneration());
    }

    @Override
    public boolean validateCsrf(String sessionId, String csrfToken)
    {
        WebAdminCredential credential = mCredential;
        return credential != null &&
            mSessionManager.validateCsrf(sessionId, csrfToken, credential.authGeneration());
    }

    @Override
    public boolean logout(String sessionId)
    {
        return mSessionManager.invalidate(sessionId);
    }

    public void invalidateAllSessions()
    {
        mSessionManager.invalidateAll();
    }

    public int getActiveSessionCount()
    {
        return mSessionManager.getActiveSessionCount();
    }

    private LoginResult authenticate(String username, char[] password, String sourceKey)
    {
        WebAdminCredential actualCredential = mCredential;
        WebAdminCredential verificationCredential = actualCredential != null ? actualCredential : mDummyCredential;
        boolean verified = mPasswordHasher.verify(verificationCredential, username, password);

        if(!verified || actualCredential == null || actualCredential != verificationCredential ||
            !isCurrent(actualCredential) || mClosed.get())
        {
            mLoginThrottle.recordFailure(sourceKey);
            return LoginResult.of(LoginStatus.DENIED);
        }

        mLoginThrottle.recordSuccess(sourceKey);
        mSessionManager.invalidateExceptGeneration(actualCredential.authGeneration());
        Optional<WebAdminSession> session = mSessionManager.create(actualCredential.authGeneration());

        if(session.isEmpty())
        {
            return LoginResult.of(LoginStatus.SESSION_CAPACITY);
        }

        if(!isCurrent(actualCredential) || mClosed.get())
        {
            mSessionManager.invalidate(session.get().sessionId());
            return LoginResult.of(LoginStatus.DENIED);
        }

        return LoginResult.success(session.get());
    }

    private boolean isCurrent(WebAdminCredential expected)
    {
        WebAdminCredential current = mCredential;
        return current == expected && current.authGeneration() == expected.authGeneration();
    }

    private static CredentialMetadata metadata(WebAdminCredential credential)
    {
        return new CredentialMetadata(credential.username(), credential.passwordChangedAtEpochMillis(),
            credential.authGeneration());
    }

    private static char[] copyPassword(char[] password)
    {
        return password == null ? new char[0] : Arrays.copyOf(password, password.length);
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

    private void requireOpen()
    {
        if(mClosed.get())
        {
            throw new IllegalStateException("Web administrator authentication service is closed");
        }
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
        mAccountLoginAdmissionLimiter.clear();
    }

    public enum LoginStatus
    {
        SUCCESS,
        DENIED,
        THROTTLED,
        BUSY,
        SESSION_CAPACITY
    }

    public record LoginResult(LoginStatus status, Optional<WebAdminSession> session, long retryAfterMillis)
    {
        public LoginResult
        {
            Objects.requireNonNull(status, "Login status cannot be null");
            session = Objects.requireNonNull(session, "Login session cannot be null");

            if((status == LoginStatus.SUCCESS) != session.isPresent() || retryAfterMillis < 0)
            {
                throw new IllegalArgumentException("Invalid web administrator login result");
            }
        }

        private static LoginResult success(WebAdminSession session)
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

    public record CredentialMetadata(String username, long passwordChangedAtEpochMillis, long authGeneration)
    {
        public CredentialMetadata
        {
            username = WebAdminCredential.normalizeUsername(username);

            if(passwordChangedAtEpochMillis <= 0 || authGeneration < 1)
            {
                throw new IllegalArgumentException("Invalid web administrator credential metadata");
            }
        }
    }

    private final class LoginTask implements Runnable
    {
        private final String username;
        private final String sourceKey;
        private final CompletableFuture<LoginResult> completion = new CompletableFuture<>();
        private char[] password;

        private LoginTask(String username, char[] password, String sourceKey)
        {
            this.username = username;
            this.password = password;
            this.sourceKey = sourceKey;
        }

        @Override
        public void run()
        {
            try
            {
                if(mClosed.get())
                {
                    completion.complete(LoginResult.of(LoginStatus.BUSY));
                }
                else
                {
                    completion.complete(authenticate(username, password, sourceKey));
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
            char[] value = password;
            password = null;

            if(value != null)
            {
                Arrays.fill(value, '\u0000');
            }
        }
    }
}
