/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import io.github.dsheirer.web.settings.WebUserPreferences;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Security application service over normalized web-user and access-policy repositories.
 *
 * <p>Every request reads one immutable in-memory snapshot. Database work occurs only during startup or serialized
 * mutations, so session resolution never blocks on SQLite.</p>
 */
public final class WebAccessService
{
    public static final String PRIMARY_ADMIN_USERNAME = "admin";
    /** Maximum administrator-managed users; the fixed primary administrator is not part of this limit. */
    public static final int MAXIMUM_USERS = 256;
    private static final int MAXIMUM_UNNORMALIZED_USERNAME_CHARACTERS =
        WebPasswordVerifier.MAXIMUM_USERNAME_CHARACTERS * 4;
    private final WebUserRepository mUsers;
    private final WebAccessPolicyRepository mPolicies;
    private final Pbkdf2PasswordHasher mPasswordHasher;
    private final ReentrantLock mMutationLock = new ReentrantLock();
    private final WebPasswordVerifier mDummyVerifier;
    private volatile SecuritySnapshot mSnapshot;

    public WebAccessService(Path databasePath) throws IOException, SQLException
    {
        this(new WebUserRepository(databasePath), new WebAccessPolicyRepository(databasePath),
            new Pbkdf2PasswordHasher());
    }

    WebAccessService(WebUserRepository users, WebAccessPolicyRepository policies,
                     Pbkdf2PasswordHasher passwordHasher) throws IOException, SQLException
    {
        mUsers = Objects.requireNonNull(users, "Web user repository cannot be null");
        mPolicies = Objects.requireNonNull(policies, "Web policy repository cannot be null");
        mPasswordHasher = Objects.requireNonNull(passwordHasher, "Web password hasher cannot be null");
        mSnapshot = loadSnapshot();
        WebPasswordVerifier existing = mSnapshot.accountsByUsername().values().stream().findFirst()
            .map(WebUserRepository.StoredAccount::verifier).orElse(null);

        if(existing != null)
        {
            mDummyVerifier = existing;
        }
        else
        {
            char[] dummyPassword = "sdrtrunk-dummy-password-verifier".toCharArray();
            try
            {
                mDummyVerifier = mPasswordHasher.createVerifier(PRIMARY_ADMIN_USERNAME, dummyPassword, 1);
            }
            finally
            {
                Arrays.fill(dummyPassword, '\u0000');
            }
        }
    }

    public boolean isPrimaryAdminConfigured()
    {
        return mSnapshot.primaryAdmin() != null;
    }

    public Optional<WebAccessAccount> primaryAdmin()
    {
        return Optional.ofNullable(mSnapshot.primaryAdmin()).map(WebUserRepository.StoredAccount::account);
    }

    public List<WebAccessAccount> accounts()
    {
        return mSnapshot.orderedAccounts();
    }

    public Optional<WebAccessAccount> account(String username)
    {
        String normalized;
        try
        {
            normalized = WebPasswordVerifier.normalizeUsername(username);
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            return Optional.empty();
        }
        return Optional.ofNullable(mSnapshot.accountsByUsername().get(normalized))
            .map(WebUserRepository.StoredAccount::account);
    }

    /** Constant-time snapshot check used while the session-manager lock is held. */
    public boolean isCurrent(WebAccessAccount account)
    {
        if(account == null)
        {
            return false;
        }
        WebUserRepository.StoredAccount current = mSnapshot.accountsByUsername().get(account.username());
        return current != null && current.account().equals(account);
    }

    /** Performs one PBKDF2 calculation for both known and unknown usernames. */
    public Optional<WebAccessAccount> authenticate(String username, char[] password)
    {
        SecuritySnapshot before = mSnapshot;
        String boundedUsername = boundedUsername(username);
        WebUserRepository.StoredAccount candidate = before.accountsByUsername().get(boundedUsername);
        WebPasswordVerifier verifier = candidate == null ? mDummyVerifier : candidate.verifier();

        if(!mPasswordHasher.verify(verifier, boundedUsername, password) || candidate == null)
        {
            return Optional.empty();
        }

        WebUserRepository.StoredAccount current = mSnapshot.accountsByUsername().get(verifier.username());
        if(current == null || !current.verifier().equals(verifier))
        {
            return Optional.empty();
        }
        return Optional.of(current.account());
    }

    /** Local-only primary-administrator bootstrap and recovery. */
    public WebAccessAccount provisionOrResetPrimaryAdmin(char[] password) throws IOException, SQLException
    {
        char[] copy = copyPassword(password);
        mMutationLock.lock();
        try
        {
            SecuritySnapshot current = mSnapshot;
            WebUserRepository.StoredAccount existing = mSnapshot.primaryAdmin();
            long revision = existing == null ? 1 : Math.incrementExact(existing.account().authRevision());
            WebPasswordVerifier verifier = mPasswordHasher.createVerifier(PRIMARY_ADMIN_USERNAME, copy, revision);
            WebUserRepository.StoredAccount replacement;

            if(existing == null)
            {
                long id = mUsers.insert(verifier, AccessTier.ADMIN, true,
                    WebUserPreferencesCodec.encode(WebUserPreferences.defaults()));
                replacement = storedAccount(id, verifier, AccessTier.ADMIN, true);
            }
            else
            {
                mUsers.replaceVerifier(existing.account().id(), existing.account().authRevision(), verifier);
                replacement = storedAccount(existing.account().id(), verifier, AccessTier.ADMIN, true);
            }

            mSnapshot = withAccount(current, replacement);
            return replacement.account();
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
            SecuritySnapshot current = mSnapshot;
            if(current.primaryAdmin() == null)
            {
                throw new IllegalStateException("The primary web administrator must be configured first");
            }
            if(current.accountsByUsername().containsKey(normalized))
            {
                throw new IllegalStateException("A web user with that username already exists");
            }
            if(ordinaryUserCount(current) >= MAXIMUM_USERS)
            {
                throw new IllegalStateException("The maximum number of web users has been reached");
            }

            WebPasswordVerifier verifier = mPasswordHasher.createVerifier(normalized, copy, 1);
            long id = mUsers.insert(verifier, tier, false,
                WebUserPreferencesCodec.encode(WebUserPreferences.defaults()));
            WebUserRepository.StoredAccount created = storedAccount(id, verifier, tier, false);
            mSnapshot = withAccount(current, created);
            return created.account();
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
            WebUserRepository.StoredAccount existing = requireOrdinaryAccount(normalized);
            long revision = Math.incrementExact(existing.account().authRevision());
            WebPasswordVerifier verifier = mPasswordHasher.createVerifier(normalized, copy, revision);
            mUsers.replaceVerifier(existing.account().id(), existing.account().authRevision(), verifier);
            WebUserRepository.StoredAccount updated = storedAccount(existing.account().id(), verifier,
                existing.account().tier(), false);
            mSnapshot = withAccount(mSnapshot, updated);
            return updated.account();
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
            WebUserRepository.StoredAccount existing = requireOrdinaryAccount(normalized);
            if(existing.account().tier() == tier)
            {
                return existing.account();
            }
            long revision = Math.incrementExact(existing.account().authRevision());
            mUsers.replaceTier(existing.account().id(), existing.account().authRevision(), tier, revision);
            WebAccessAccount updated = new WebAccessAccount(existing.account().id(), normalized, tier,
                existing.account().passwordChangedAtEpochMillis(), revision, false);
            mSnapshot = withAccount(mSnapshot,
                new WebUserRepository.StoredAccount(updated, existing.verifier().withAuthRevision(revision)));
            return updated;
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
            WebUserRepository.StoredAccount existing = requireOrdinaryAccount(normalized);
            mUsers.delete(existing.account().id(), existing.account().authRevision());
            mSnapshot = withoutAccount(mSnapshot, normalized);
            return existing.account();
        }
        finally
        {
            mMutationLock.unlock();
        }
    }

    public List<CapabilityPolicy> policies()
    {
        SecuritySnapshot snapshot = mSnapshot;
        List<CapabilityPolicy> policies = new ArrayList<>(WebCapability.values().length);
        for(WebCapability capability: WebCapability.values())
        {
            policies.add(policy(snapshot, capability));
        }
        return List.copyOf(policies);
    }

    public AccessTier requiredTier(WebCapability capability)
    {
        return policy(mSnapshot, Objects.requireNonNull(capability, "Web capability cannot be null")).requiredTier();
    }

    public boolean isAllowed(AccessTier actualTier, String capabilityId)
    {
        return WebCapability.fromId(capabilityId).map(capability -> isAllowed(actualTier, capability)).orElse(false);
    }

    public boolean isAllowed(AccessTier actualTier, WebCapability capability)
    {
        AccessTier actual = actualTier == null ? AccessTier.PUBLIC : actualTier;
        WebCapability required = Objects.requireNonNull(capability, "Web capability cannot be null");
        if(required != WebCapability.SITE_ACCESS && !actual.allows(requiredTier(WebCapability.SITE_ACCESS)))
        {
            return false;
        }
        return actual.allows(requiredTier(required));
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

    public CapabilityPolicy setCapabilityTier(WebCapability capability, AccessTier tier) throws IOException, SQLException
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
            if(mSnapshot.primaryAdmin() == null)
            {
                throw new IllegalStateException("The primary web administrator must be configured first");
            }
            SecuritySnapshot current = mSnapshot;
            mPolicies.save(capability, tier);
            Map<WebCapability,AccessTier> policies = new LinkedHashMap<>(current.policies());
            if(tier == capability.defaultTier())
            {
                policies.remove(capability);
            }
            else
            {
                policies.put(capability, tier);
            }
            mSnapshot = new SecuritySnapshot(current.accountsByUsername(), current.orderedAccounts(),
                current.primaryAdmin(), Map.copyOf(policies));
            return policy(mSnapshot, capability);
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

    private SecuritySnapshot loadSnapshot() throws IOException, SQLException
    {
        List<WebUserRepository.StoredAccount> stored = mUsers.loadSecurityAccounts();
        long ordinaryUsers = stored.stream().filter(entry -> !entry.account().primaryAdmin()).count();
        if(ordinaryUsers > MAXIMUM_USERS)
        {
            throw new SQLException("Persisted web user count exceeds " + MAXIMUM_USERS);
        }

        Map<String,WebUserRepository.StoredAccount> byUsername = new LinkedHashMap<>();
        WebUserRepository.StoredAccount primary = null;
        List<WebAccessAccount> ordered = new ArrayList<>();
        for(WebUserRepository.StoredAccount entry: stored)
        {
            if(byUsername.put(entry.account().username(), entry) != null)
            {
                throw new SQLException("Duplicate persisted web username");
            }
            if(entry.account().primaryAdmin())
            {
                if(primary != null)
                {
                    throw new SQLException("Multiple primary web administrators are persisted");
                }
                primary = entry;
            }
            ordered.add(entry.account());
        }
        if(primary == null && !stored.isEmpty())
        {
            throw new SQLException("Ordinary web users exist without the primary administrator");
        }
        return new SecuritySnapshot(Map.copyOf(byUsername), List.copyOf(ordered), primary, mPolicies.load());
    }

    private WebUserRepository.StoredAccount requireOrdinaryAccount(String username)
    {
        WebUserRepository.StoredAccount account = mSnapshot.accountsByUsername().get(username);
        if(account == null || account.account().primaryAdmin())
        {
            throw new IllegalStateException("Web user does not exist");
        }
        return account;
    }

    private static long ordinaryUserCount(SecuritySnapshot snapshot)
    {
        return snapshot.orderedAccounts().stream().filter(account -> !account.primaryAdmin()).count();
    }

    private static WebUserRepository.StoredAccount storedAccount(long id, WebPasswordVerifier verifier,
                                                                 AccessTier tier, boolean primary)
    {
        WebAccessAccount account = new WebAccessAccount(id, verifier.username(), tier,
            verifier.passwordChangedAtEpochMillis(), verifier.authRevision(), primary);
        return new WebUserRepository.StoredAccount(account, verifier);
    }

    private static SecuritySnapshot withAccount(SecuritySnapshot current,
                                                WebUserRepository.StoredAccount replacement)
    {
        Map<String,WebUserRepository.StoredAccount> accounts = new LinkedHashMap<>(current.accountsByUsername());
        accounts.put(replacement.account().username(), replacement);
        return snapshot(accounts.values(), current.policies());
    }

    private static SecuritySnapshot withoutAccount(SecuritySnapshot current, String username)
    {
        Map<String,WebUserRepository.StoredAccount> accounts = new LinkedHashMap<>(current.accountsByUsername());
        accounts.remove(username);
        return snapshot(accounts.values(), current.policies());
    }

    /** Builds the immutable post-write view only from already-validated values; it performs no fallible I/O. */
    private static SecuritySnapshot snapshot(Collection<WebUserRepository.StoredAccount> accounts,
                                             Map<WebCapability,AccessTier> policies)
    {
        List<WebUserRepository.StoredAccount> orderedStored = new ArrayList<>(accounts);
        orderedStored.sort(Comparator.comparing((WebUserRepository.StoredAccount entry) ->
                !entry.account().primaryAdmin())
            .thenComparing(entry -> entry.account().username()));
        Map<String,WebUserRepository.StoredAccount> byUsername = new LinkedHashMap<>();
        List<WebAccessAccount> orderedAccounts = new ArrayList<>(orderedStored.size());
        WebUserRepository.StoredAccount primary = null;
        for(WebUserRepository.StoredAccount entry: orderedStored)
        {
            byUsername.put(entry.account().username(), entry);
            orderedAccounts.add(entry.account());
            if(entry.account().primaryAdmin())
            {
                primary = entry;
            }
        }
        return new SecuritySnapshot(Map.copyOf(byUsername), List.copyOf(orderedAccounts), primary,
            Map.copyOf(policies));
    }

    private static CapabilityPolicy policy(SecuritySnapshot snapshot, WebCapability capability)
    {
        AccessTier required = capability.configurable() ?
            snapshot.policies().getOrDefault(capability, capability.defaultTier()) : capability.defaultTier();
        return new CapabilityPolicy(capability.id(), capability.displayName(), required, capability.defaultTier(),
            capability.configurable());
    }

    private static String requireOrdinaryUsername(String username)
    {
        String normalized = WebPasswordVerifier.normalizeUsername(username);
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
            return WebPasswordVerifier.normalizeUsername(username);
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

    private record SecuritySnapshot(Map<String,WebUserRepository.StoredAccount> accountsByUsername,
                                    List<WebAccessAccount> orderedAccounts,
                                    WebUserRepository.StoredAccount primaryAdmin,
                                    Map<WebCapability,AccessTier> policies)
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
