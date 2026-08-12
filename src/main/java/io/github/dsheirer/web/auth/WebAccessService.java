/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Transport-neutral owner for web accounts, password verification, and feature access policies.
 *
 * <p>HTTP sessions, cookies, CSRF, request throttling, and route enforcement remain integration concerns.  All
 * mutations here are serialized, saved immediately, and published only after the database write succeeds.</p>
 */
public final class WebAccessService
{
    public static final String KEY = "web.access.v1";
    public static final String SETTING_KEY = KEY;
    public static final String PRIMARY_ADMIN_USERNAME = "admin";
    public static final int MAXIMUM_USERS = WebAccessConfiguration.MAXIMUM_USERS;
    private static final int MAXIMUM_UNNORMALIZED_USERNAME_CHARACTERS =
        WebAdminCredential.MAXIMUM_USERNAME_CHARACTERS * 4;
    private final WebAccessStore mStore;
    private final Pbkdf2PasswordHasher mPasswordHasher;
    private final ReentrantLock mMutationLock = new ReentrantLock();
    private final WebAdminCredential mDummyCredential;
    private volatile WebAccessConfiguration mConfiguration;

    public WebAccessService(Path databasePath) throws IOException, SQLException
    {
        this(new WebAccessStore(databasePath), new Pbkdf2PasswordHasher());
    }

    WebAccessService(WebAccessStore store, Pbkdf2PasswordHasher passwordHasher) throws IOException, SQLException
    {
        mStore = Objects.requireNonNull(store, "Web access store cannot be null");
        mPasswordHasher = Objects.requireNonNull(passwordHasher, "Web password hasher cannot be null");
        mConfiguration = mStore.load().orElseGet(WebAccessConfiguration::empty);
        WebAdminCredential existing = firstCredential(mConfiguration);

        if(existing != null)
        {
            mDummyCredential = existing;
        }
        else
        {
            char[] dummyPassword = "sdrtrunk-dummy-password-verifier".toCharArray();

            try
            {
                mDummyCredential = mPasswordHasher.createCredential(PRIMARY_ADMIN_USERNAME, dummyPassword, 1);
            }
            finally
            {
                Arrays.fill(dummyPassword, '\u0000');
            }
        }
    }

    public boolean isPrimaryAdminConfigured()
    {
        return mConfiguration.primaryAdmin() != null;
    }

    public Optional<WebAccessAccount> primaryAdmin()
    {
        WebAdminCredential credential = mConfiguration.primaryAdmin();
        return credential == null ? Optional.empty() : Optional.of(account(credential, AccessTier.ADMIN, true));
    }

    /**
     * Returns verifier-free account metadata with the fixed primary administrator first, followed by sorted users.
     */
    public List<WebAccessAccount> accounts()
    {
        WebAccessConfiguration configuration = mConfiguration;
        List<WebAccessAccount> accounts = new ArrayList<>(configuration.users().size() + 1);

        if(configuration.primaryAdmin() != null)
        {
            accounts.add(account(configuration.primaryAdmin(), AccessTier.ADMIN, true));
        }

        for(WebStoredUser user: configuration.users())
        {
            accounts.add(account(user.credential(), user.tier(), false));
        }

        return List.copyOf(accounts);
    }

    public Optional<WebAccessAccount> account(String username)
    {
        String normalized;

        try
        {
            normalized = WebAdminCredential.normalizeUsername(username);
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            return Optional.empty();
        }

        return findAccount(mConfiguration, normalized);
    }

    /**
     * Indicates whether an authenticated account snapshot still matches the current credential and role state.
     */
    public boolean isCurrent(WebAccessAccount account)
    {
        if(account == null)
        {
            return false;
        }

        return findAccount(mConfiguration, account.username()).filter(account::equals).isPresent();
    }

    /**
     * Performs a full PBKDF2 calculation for known and unknown account names.  The caller must place this method behind
     * the bounded authentication executor and admission controls used by the HTTP integration.
     */
    public Optional<WebAccessAccount> authenticate(String username, char[] password)
    {
        WebAccessConfiguration before = mConfiguration;
        String boundedUsername = boundedUsername(username);
        CredentialAndTier candidate = findCredential(before, boundedUsername);
        WebAdminCredential verifier = candidate == null ? mDummyCredential : candidate.credential();

        if(!mPasswordHasher.verify(verifier, boundedUsername, password) || candidate == null)
        {
            return Optional.empty();
        }

        WebAccessConfiguration after = mConfiguration;
        CredentialAndTier current = findCredential(after, verifier.username());

        if(current == null || !current.credential().equals(verifier))
        {
            return Optional.empty();
        }

        return Optional.of(account(current.credential(), current.tier(), current.primaryAdmin()));
    }

    /**
     * Local desktop bootstrap/recovery operation for the fixed primary account.  It must not be exposed as an
     * unauthenticated web route.
     */
    public WebAccessAccount provisionOrResetPrimaryAdmin(char[] password) throws IOException, SQLException
    {
        char[] copy = copyPassword(password);
        mMutationLock.lock();

        try
        {
            WebAccessConfiguration current = mConfiguration;
            long version = current.primaryAdmin() == null ? 1 :
                Math.incrementExact(current.primaryAdmin().credentialVersion());
            WebAdminCredential credential = mPasswordHasher.createCredential(PRIMARY_ADMIN_USERNAME, copy, version);
            persist(current.withPrimaryAdmin(credential));
            return account(credential, AccessTier.ADMIN, true);
        }
        finally
        {
            mMutationLock.unlock();
            Arrays.fill(copy, '\u0000');
        }
    }

    public WebAccessAccount createUser(String username, char[] password, AccessTier tier)
        throws IOException, SQLException
    {
        String normalized = requireOrdinaryUsername(username);
        requireAccountTier(tier);
        char[] copy = copyPassword(password);
        mMutationLock.lock();

        try
        {
            WebAccessConfiguration current = mConfiguration;

            if(current.primaryAdmin() == null)
            {
                throw new IllegalStateException("The primary web administrator must be configured first");
            }

            if(findStoredUser(current, normalized) != null)
            {
                throw new IllegalStateException("A web user with that username already exists");
            }

            if(current.users().size() >= MAXIMUM_USERS)
            {
                throw new IllegalStateException("The maximum number of web users has been reached");
            }

            WebAdminCredential credential = mPasswordHasher.createCredential(normalized, copy, 1);
            WebStoredUser added = new WebStoredUser(tier, credential);
            List<WebStoredUser> users = new ArrayList<>(current.users());
            users.add(added);
            persist(current.withUsers(users));
            return account(credential, tier, false);
        }
        finally
        {
            mMutationLock.unlock();
            Arrays.fill(copy, '\u0000');
        }
    }

    public WebAccessAccount resetUserPassword(String username, char[] password) throws IOException, SQLException
    {
        String normalized = requireOrdinaryUsername(username);
        char[] copy = copyPassword(password);
        mMutationLock.lock();

        try
        {
            WebAccessConfiguration current = mConfiguration;
            WebStoredUser existing = requireStoredUser(current, normalized);
            long version = Math.incrementExact(existing.credential().credentialVersion());
            WebAdminCredential credential = mPasswordHasher.createCredential(normalized, copy, version);
            replaceUser(current, existing, existing.withCredential(credential));
            return account(credential, existing.tier(), false);
        }
        finally
        {
            mMutationLock.unlock();
            Arrays.fill(copy, '\u0000');
        }
    }

    public WebAccessAccount changeUserTier(String username, AccessTier tier) throws IOException, SQLException
    {
        String normalized = requireOrdinaryUsername(username);
        requireAccountTier(tier);
        mMutationLock.lock();

        try
        {
            WebAccessConfiguration current = mConfiguration;
            WebStoredUser existing = requireStoredUser(current, normalized);

            if(existing.tier() == tier)
            {
                return account(existing.credential(), existing.tier(), false);
            }

            long version = Math.incrementExact(existing.credential().credentialVersion());
            WebStoredUser replacement = existing.withTier(tier, version);
            replaceUser(current, existing, replacement);
            return account(replacement.credential(), replacement.tier(), false);
        }
        finally
        {
            mMutationLock.unlock();
        }
    }

    public WebAccessAccount deleteUser(String username) throws IOException, SQLException
    {
        String normalized = requireOrdinaryUsername(username);
        mMutationLock.lock();

        try
        {
            WebAccessConfiguration current = mConfiguration;
            WebStoredUser existing = requireStoredUser(current, normalized);
            List<WebStoredUser> users = new ArrayList<>(current.users());
            users.remove(existing);
            persist(current.withUsers(users));
            return account(existing.credential(), existing.tier(), false);
        }
        finally
        {
            mMutationLock.unlock();
        }
    }

    public List<CapabilityPolicy> policies()
    {
        WebAccessConfiguration configuration = mConfiguration;
        List<CapabilityPolicy> policies = new ArrayList<>(WebCapability.values().length);

        for(WebCapability capability: WebCapability.values())
        {
            policies.add(policy(configuration, capability));
        }

        return List.copyOf(policies);
    }

    public AccessTier requiredTier(WebCapability capability)
    {
        return policy(mConfiguration, Objects.requireNonNull(capability, "Web capability cannot be null"))
            .requiredTier();
    }

    /**
     * Unknown identifiers are never authorized, including for administrators.
     */
    public boolean isAllowed(AccessTier actualTier, String capabilityId)
    {
        Optional<WebCapability> capability = WebCapability.fromId(capabilityId);
        return capability.isPresent() && isAllowed(actualTier, capability.get());
    }

    public boolean isAllowed(AccessTier actualTier, WebCapability capability)
    {
        AccessTier actual = actualTier == null ? AccessTier.PUBLIC : actualTier;
        return actual.allows(requiredTier(capability));
    }

    public boolean isAllowed(WebAccessAccount account, WebCapability capability)
    {
        return isAllowed(account == null ? AccessTier.PUBLIC : account.tier(), capability);
    }

    public CapabilityPolicy setCapabilityTier(String capabilityId, AccessTier tier) throws IOException, SQLException
    {
        WebCapability capability = WebCapability.fromId(capabilityId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown web capability"));
        return setCapabilityTier(capability, tier);
    }

    public CapabilityPolicy setCapabilityTier(WebCapability capability, AccessTier tier)
        throws IOException, SQLException
    {
        Objects.requireNonNull(capability, "Web capability cannot be null");
        Objects.requireNonNull(tier, "Required web access tier cannot be null");

        if(!capability.configurable())
        {
            throw new IllegalArgumentException("This administrative capability is fixed at ADMIN access");
        }

        mMutationLock.lock();

        try
        {
            WebAccessConfiguration current = mConfiguration;

            if(current.primaryAdmin() == null)
            {
                throw new IllegalStateException("The primary web administrator must be configured first");
            }

            Map<String,AccessTier> overrides = new LinkedHashMap<>(current.policyOverrides());

            if(tier == capability.defaultTier())
            {
                overrides.remove(capability.id());
            }
            else
            {
                overrides.put(capability.id(), tier);
            }

            WebAccessConfiguration replacement = current.withPolicyOverrides(overrides);
            persist(replacement);
            return policy(replacement, capability);
        }
        finally
        {
            mMutationLock.unlock();
        }
    }

    public CapabilityPolicy resetCapabilityTier(WebCapability capability) throws IOException, SQLException
    {
        return setCapabilityTier(capability, capability.defaultTier());
    }

    private void replaceUser(WebAccessConfiguration current, WebStoredUser existing, WebStoredUser replacement)
        throws IOException, SQLException
    {
        List<WebStoredUser> users = new ArrayList<>(current.users());
        int index = users.indexOf(existing);

        if(index < 0)
        {
            throw new IllegalStateException("Web user changed during mutation");
        }

        users.set(index, replacement);
        persist(current.withUsers(users));
    }

    private void persist(WebAccessConfiguration replacement) throws IOException, SQLException
    {
        mStore.save(replacement);
        mConfiguration = replacement;
    }

    private static CapabilityPolicy policy(WebAccessConfiguration configuration, WebCapability capability)
    {
        AccessTier required = capability.configurable() ?
            configuration.policyOverrides().getOrDefault(capability.id(), capability.defaultTier()) :
            capability.defaultTier();
        return new CapabilityPolicy(capability.id(), capability.displayName(), required, capability.defaultTier(),
            capability.configurable());
    }

    private static Optional<WebAccessAccount> findAccount(WebAccessConfiguration configuration, String username)
    {
        CredentialAndTier credential = findCredential(configuration, username);
        return credential == null ? Optional.empty() : Optional.of(account(credential.credential(), credential.tier(),
            credential.primaryAdmin()));
    }

    private static CredentialAndTier findCredential(WebAccessConfiguration configuration, String username)
    {
        if(configuration.primaryAdmin() != null && configuration.primaryAdmin().username().equals(username))
        {
            return new CredentialAndTier(configuration.primaryAdmin(), AccessTier.ADMIN, true);
        }

        WebStoredUser user = findStoredUser(configuration, username);
        return user == null ? null : new CredentialAndTier(user.credential(), user.tier(), false);
    }

    private static WebStoredUser findStoredUser(WebAccessConfiguration configuration, String username)
    {
        for(WebStoredUser user: configuration.users())
        {
            if(user.credential().username().equals(username))
            {
                return user;
            }
        }

        return null;
    }

    private static WebStoredUser requireStoredUser(WebAccessConfiguration configuration, String username)
    {
        WebStoredUser user = findStoredUser(configuration, username);

        if(user == null)
        {
            throw new IllegalStateException("Web user does not exist");
        }

        return user;
    }

    private static WebAdminCredential firstCredential(WebAccessConfiguration configuration)
    {
        if(configuration.primaryAdmin() != null)
        {
            return configuration.primaryAdmin();
        }

        return configuration.users().isEmpty() ? null : configuration.users().getFirst().credential();
    }

    private static WebAccessAccount account(WebAdminCredential credential, AccessTier tier, boolean primary)
    {
        return new WebAccessAccount(credential.username(), tier, credential.passwordChangedAtEpochMillis(),
            credential.credentialVersion(), primary);
    }

    private static String requireOrdinaryUsername(String username)
    {
        String normalized = WebAdminCredential.normalizeUsername(username);

        if(PRIMARY_ADMIN_USERNAME.equals(normalized))
        {
            throw new IllegalArgumentException("The primary administrator is managed only by the JavaFX interface");
        }

        return normalized;
    }

    private static void requireAccountTier(AccessTier tier)
    {
        if(tier == null || !tier.isAccountTier())
        {
            throw new IllegalArgumentException("Web users must have USER or ADMIN access");
        }
    }

    private static String boundedUsername(String username)
    {
        if(username == null || username.length() > MAXIMUM_UNNORMALIZED_USERNAME_CHARACTERS)
        {
            return "invalid";
        }

        try
        {
            return WebAdminCredential.normalizeUsername(username);
        }
        catch(IllegalArgumentException exception)
        {
            return "invalid";
        }
    }

    private static char[] copyPassword(char[] password)
    {
        return password == null ? new char[0] : Arrays.copyOf(password, password.length);
    }

    private record CredentialAndTier(WebAdminCredential credential, AccessTier tier, boolean primaryAdmin)
    {
    }

    public record CapabilityPolicy(String id, String displayName, AccessTier requiredTier, AccessTier defaultTier,
                                   boolean configurable)
    {
        public CapabilityPolicy
        {
            Objects.requireNonNull(id, "Web capability identifier cannot be null");
            Objects.requireNonNull(displayName, "Web capability display name cannot be null");
            Objects.requireNonNull(requiredTier, "Required web access tier cannot be null");
            Objects.requireNonNull(defaultTier, "Default web access tier cannot be null");
        }
    }
}
